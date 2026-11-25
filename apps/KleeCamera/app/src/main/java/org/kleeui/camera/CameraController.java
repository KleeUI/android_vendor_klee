package org.kleeui.camera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.ExtensionSessionConfiguration;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class CameraController {
    public enum Mode {
        PHOTO, VIDEO, VIDEO_HIGH_FRAME_RATE, VIDEO_4K, VIDEO_4K_HIGH_FRAME_RATE, SLOW_MOTION,
        PRO, RAW, HDR, NIGHT, LIGHT_TRAILS, PORTRAIT, HIGH_RES, TIME_LAPSE, DOCUMENT
    }
    public enum Flash { OFF, AUTO, ON, TORCH }

    public interface Listener {
        void onReady(Lens lens, Mode mode);
        void onSaved(Uri uri, boolean video);
        void onError(String message);
        void onRecording(boolean recording);
        void onBusy(boolean busy);
    }

    public static final class Lens {
        public final String id, label;
        public final boolean front, flash;
        public final float minZoom, maxZoom, maxFocus;
        public final int minIso, maxIso, minEv, maxEv;
        public final int[] whiteBalanceModes;
        public final long minExposure, maxExposure;
        final CameraCharacteristics info;
        final StreamConfigurationMap streams;
        final Map<Mode, Integer> extensions = new EnumMap<>(Mode.class);
        final Size jpeg, raw, video, video4k, highResolution, highSpeed;
        final boolean videoHighFrameRate, video4kHighFrameRate;
        final boolean maxPixelMode, manual;

        Lens(String id, CameraCharacteristics info, CameraManager manager, boolean cupid) {
            this.id = id;
            this.info = info;
            front = get(info, CameraCharacteristics.LENS_FACING, -1) == CameraCharacteristics.LENS_FACING_FRONT;
            label = front ? "前置" : cupid && id.equals("2") ? "超广角" : cupid && id.equals("3") ? "微距" : "主摄";
            flash = get(info, CameraCharacteristics.FLASH_INFO_AVAILABLE, false);
            streams = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            jpeg = largest(streams.getOutputSizes(ImageFormat.JPEG));
            raw = largest(streams.getOutputSizes(ImageFormat.RAW_SENSOR));
            Size[] yuvSizes = streams.getOutputSizes(ImageFormat.YUV_420_888);
            video = choose(yuvSizes, 16.0 / 9, 1920, 1080);
            Size uhd = new Size(3840, 2160);
            video4k = yuvSizes != null && Arrays.asList(yuvSizes).contains(uhd)
                    && streams.getOutputMinFrameDuration(ImageFormat.YUV_420_888, uhd) <= 34_000_000L
                    && YuvVideo.supports(uhd, 30) ? uhd : null;
            boolean cupidMain = cupid && id.equals("0");
            videoHighFrameRate = new Size(1920, 1080).equals(video) && supportsHighFrameRate(info, cupidMain, video);
            video4kHighFrameRate = video4k != null && supportsHighFrameRate(info, cupidMain, uhd);
            Size large = largest(streams.getHighResolutionOutputSizes(ImageFormat.JPEG));
            boolean maximum = false;
            if (Build.VERSION.SDK_INT >= 31) {
                StreamConfigurationMap maximumMap = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION);
                if (maximumMap != null) {
                    Size maximumJpeg = largest(maximumMap.getOutputSizes(ImageFormat.JPEG));
                    if (area(maximumJpeg) > Math.max(area(large), area(jpeg))) {
                        large = maximumJpeg;
                        maximum = true;
                    }
                }
                try {
                    CameraExtensionCharacteristics ext = manager.getCameraExtensionCharacteristics(id);
                    for (Mode mode : new Mode[]{Mode.HDR, Mode.NIGHT, Mode.PORTRAIT}) {
                        int kind = mode == Mode.HDR ? CameraExtensionCharacteristics.EXTENSION_HDR
                                : mode == Mode.NIGHT ? CameraExtensionCharacteristics.EXTENSION_NIGHT
                                : CameraExtensionCharacteristics.EXTENSION_BOKEH;
                        if (ext.getSupportedExtensions().contains(kind)
                                && !ext.getExtensionSupportedSizes(kind, ImageFormat.JPEG).isEmpty()
                                && !ext.getExtensionSupportedSizes(kind, SurfaceTexture.class).isEmpty()) {
                            extensions.put(mode, kind);
                        }
                    }
                } catch (CameraAccessException | RuntimeException e) {
                    Log.d(TAG, "Extensions unavailable for " + id, e);
                }
            }
            highResolution = area(large) > area(jpeg) ? large : area(jpeg) >= 24_000_000 ? jpeg : null;
            maxPixelMode = maximum;
            Size[] speedSizes = streams.getHighSpeedVideoSizes();
            Size speedSize = null;
            if (speedSizes != null) {
                for (Size size : speedSizes) {
                    if (area(size) <= area(speedSize) || area(size) > 1920 * 1080) continue;
                    if (Arrays.asList(streams.getHighSpeedVideoFpsRangesFor(size)).contains(SLOW_RATE)
                            && SlowVideo.supports(size, SLOW_RATE.getUpper())) speedSize = size;
                }
            }
            highSpeed = speedSize;
            Range<Float> zoom = info.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            minZoom = zoom == null ? 1 : zoom.getLower();
            maxZoom = Math.max(1, zoom == null ? get(info, CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM, 1f) : zoom.getUpper());
            Range<Integer> iso = info.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            Range<Long> exposure = info.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            manual = iso != null && exposure != null && contains(info.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES),
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR);
            minIso = iso == null ? 0 : iso.getLower();
            maxIso = iso == null ? 0 : iso.getUpper();
            minExposure = exposure == null ? 0 : exposure.getLower();
            maxExposure = exposure == null ? 0 : exposure.getUpper();
            maxFocus = get(info, CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE, 0f);
            whiteBalanceModes = get(info, CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES, new int[0]);
            Range<Integer> ev = info.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            minEv = ev == null ? 0 : ev.getLower();
            maxEv = ev == null ? 0 : ev.getUpper();
        }

        public boolean supports(Mode mode) {
            switch (mode) {
                case RAW: return raw != null;
                case PRO: return manual && jpeg != null;
                case VIDEO:
                case TIME_LAPSE: return video != null;
                case VIDEO_HIGH_FRAME_RATE: return videoHighFrameRate;
                case VIDEO_4K: return video4k != null;
                case VIDEO_4K_HIGH_FRAME_RATE: return video4kHighFrameRate;
                case SLOW_MOTION: return highSpeed != null;
                case HIGH_RES: return highResolution != null;
                case HDR:
                case NIGHT: return jpeg != null && (manual || extensions.containsKey(mode));
                case LIGHT_TRAILS: return jpeg != null && manual;
                case PORTRAIT: return extensions.containsKey(mode) || (jpeg != null && contains(
                        info.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES), scene(mode)));
                default: return jpeg != null;
            }
        }
    }

    private static final String TAG = "KleeCamera";
    private static final Range<Integer> VIDEO_RATE = new Range<>(30, 30);
    private static final Range<Integer> HIGH_VIDEO_RATE = new Range<>(60, 60);
    private static final String STREAM_FPS_SECTION = "org.quic.camera2.streamBasedFPS.info.";
    private static final CaptureRequest.Key<Integer> PREVIEW_FPS =
            new CaptureRequest.Key<>(STREAM_FPS_SECTION + "PreviewFPS", Integer.class);
    private static final CaptureRequest.Key<Integer> RECORDING_FPS =
            new CaptureRequest.Key<>(STREAM_FPS_SECTION + "VideoFPS", Integer.class);
    private static final Range<Integer> SLOW_RATE = new Range<>(120, 120);
    private final Context context;
    private final CameraManager manager;
    private final TextureView texture;
    private final RecordingPreview recordingPreview;
    private final Listener listener;
    private final CaptureStore store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread workerThread = new HandlerThread("KleeCamera2");
    private final Handler worker;
    private final Executor executor;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile List<Lens> lenses = Collections.emptyList();
    private volatile String selectedId;
    private Mode mode = Mode.PHOTO;
    private Lens lens;
    private volatile boolean enabled, destroyed;
    private int generation;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private CameraExtensionSession extension;
    private Surface preview;
    private ImageReader reader;
    private CaptureRequest.Builder repeating;
    private YuvVideo recorder;
    private SlowVideo slowRecorder;
    private File videoFile;
    private volatile boolean recording;
    private boolean capturePending;
    private boolean stoppingSlowVideo;
    private volatile boolean captureHighRatePreview;
    private final AtomicInteger previewFramesDuringRecording = new AtomicInteger();
    private long recordingSince;
    private long videoFirstTimestamp, videoFrameCount;
    private long captureToken;
    private Image pendingRaw;
    private TotalCaptureResult rawResult;
    private int rawOrientation;
    private byte[][] bracket;
    private final Map<Long, Integer> bracketIndices = new HashMap<>();
    private final Map<Long, byte[]> bracketImages = new HashMap<>();
    private Flash flash = Flash.OFF;
    private float zoom = 1, focusDistance = -1;
    private int exposureComp, iso, whiteBalance;
    private long exposureTime;
    private Rect touchRegion;
    private int autoIso = 100;
    private long autoExposure = 10_000_000;
    private volatile android.view.OrientationEventListener orientationListener;
    private volatile int deviceDegrees;
    private volatile Size previewSize;

    CameraController(Context context, TextureView texture, RecordingPreview recordingPreview,
                     CaptureRequestResult captureRequest, Listener listener) {
        this.context = context;
        this.manager = context.getSystemService(CameraManager.class);
        this.texture = texture;
        this.recordingPreview = recordingPreview;
        this.listener = listener;
        this.store = new CaptureStore(context, captureRequest);
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        executor = command -> worker.post(command);
        orientationListener = new android.view.OrientationEventListener(context) {
            @Override public void onOrientationChanged(int value) {
                if (value != ORIENTATION_UNKNOWN && Math.abs((value - deviceDegrees + 540) % 360 - 180) >= 55) {
                    deviceDegrees = ((value + 45) / 90 * 90) % 360;
                }
            }
        };
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                if (enabled) worker.post(() -> open(surface));
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) { refreshTransform(); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                worker.post(() -> { closeCamera(); surface.release(); });
                return false;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                if (captureHighRatePreview) previewFramesDuringRecording.incrementAndGet();
            }
        });
    }

    public void start() {
        if (destroyed || enabled) return;
        enabled = true;
        orientationListener.enable();
        SurfaceTexture target = texture.getSurfaceTexture();
        worker.post(() -> { discover(); if (target != null && camera == null) open(target); });
    }

    public void stop() {
        enabled = false;
        orientationListener.disable();
        worker.post(this::closeCamera);
    }

    public void destroy() {
        if (destroyed) return;
        stop();
        destroyed = true;
        texture.setSurfaceTextureListener(null);
        worker.post(() -> { io.shutdown(); workerThread.quitSafely(); });
    }

    public List<Lens> getLenses() { return lenses; }
    public String getActiveId() { return selectedId; }

    private void discover() {
        if (!lenses.isEmpty()) return;
        try {
            boolean cupid = "cupid".equals(Build.DEVICE);
            List<String> ids = new ArrayList<>(Arrays.asList(manager.getCameraIdList()));
            if (cupid) for (String id : new String[]{"0", "1", "2", "3"}) if (!ids.contains(id)) ids.add(id);
            List<Lens> found = new ArrayList<>();
            for (String id : ids) {
                if (cupid && !Arrays.asList("0", "1", "2", "3").contains(id)) continue;
                try {
                    CameraCharacteristics info = manager.getCameraCharacteristics(id);
                    if (info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) == null) continue;
                    Lens candidate = new Lens(id, info, manager, cupid);
                    if (candidate.jpeg != null) {
                        found.add(candidate);
                        Log.i(TAG, "Camera " + id + " JPEG=" + candidate.jpeg + " RAW=" + candidate.raw
                                + " highRes=" + candidate.highResolution + " video=" + candidate.video
                                + " 4K=" + candidate.video4k + " slow=" + candidate.highSpeed
                                + " extensions=" + candidate.extensions + " scenes=" + Arrays.toString(
                                info.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)));
                    }
                } catch (CameraAccessException | IllegalArgumentException e) {
                    Log.i(TAG, "Camera " + id + " unavailable", e);
                }
            }
            found.sort(Comparator.comparing((Lens item) -> item.front).thenComparing(item -> item.id));
            lenses = Collections.unmodifiableList(found);
            if (selectedId == null && !found.isEmpty()) selectedId = found.get(0).id;
            try {
                Log.i(TAG, "Concurrent camera IDs: " + manager.getConcurrentCameraIds());
            } catch (CameraAccessException e) {
                Log.w(TAG, "Could not read concurrent camera IDs", e);
            }
        } catch (CameraAccessException | SecurityException e) { error("无法读取相机", e); }
    }

    public void select(String lensId, Mode requested) {
        SurfaceTexture surface = texture.getSurfaceTexture();
        worker.post(() -> {
            if (!enabled || recording) return;
            Lens next = find(lensId);
            if (next == null || !next.supports(requested)) return;
            if (lensId.equals(selectedId) && mode == requested && camera != null && (session != null || extension != null)) return;
            boolean changeLens = !lensId.equals(selectedId) || camera == null;
            selectedId = lensId;
            mode = requested;
            resetControls();
            if (surface == null) return;
            if (changeLens) open(surface);
            else configure(surface, false);
        });
    }

    private Lens find(String id) {
        for (Lens item : lenses) if (item.id.equals(id)) return item;
        return null;
    }

    private void resetControls() {
        zoom = 1;
        touchRegion = null;
        exposureComp = iso = whiteBalance = 0;
        exposureTime = 0;
        focusDistance = -1;
        flash = Flash.OFF;
    }

    @SuppressLint("MissingPermission")
    private void open(SurfaceTexture surface) {
        if (!enabled) return;
        closeCamera();
        lens = find(selectedId);
        if (lens == null) { error("未找到可用的相机", null); return; }
        if (!lens.supports(mode)) mode = Mode.PHOTO;
        final int token = generation;
        busy(true);
        try {
            manager.openCamera(lens.id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    if (!enabled || generation != token) { device.close(); return; }
                    camera = device;
                    configure(surface, false);
                }
                @Override public void onDisconnected(CameraDevice device) { deviceError(device, "相机连接已断开", token); }
                @Override public void onError(CameraDevice device, int code) { deviceError(device, "无法打开此镜头（" + code + "）", token); }
            }, worker);
        } catch (CameraAccessException | RuntimeException e) { error("无法打开此镜头", e); }
    }

    private void deviceError(CameraDevice device, String reason, int openToken) {
        device.close();
        if (camera == device || openToken == generation) {
            closeCamera();
            error(reason, null);
        }
    }

    private void configure(SurfaceTexture textureSurface, boolean forRecording) {
        int token = ++generation;
        closeSession();
        busy(true);
        try {
            boolean film = isVideo(mode);
            boolean slow = forRecording && mode == Mode.SLOW_MOTION;
            boolean highFrameRate = forRecording && isHighFrameRate(mode);
            boolean previewFromRecording = forRecording && mode == Mode.VIDEO_4K_HIGH_FRAME_RATE;
            Integer extensionType = lens.extensions.get(mode);
            Size still = mode == Mode.RAW ? lens.raw : mode == Mode.HIGH_RES ? lens.highResolution : lens.jpeg;
            Size display;
            if (extensionType != null && Build.VERSION.SDK_INT >= 31) {
                CameraExtensionCharacteristics ext = manager.getCameraExtensionCharacteristics(lens.id);
                still = largest(ext.getExtensionSupportedSizes(extensionType, ImageFormat.JPEG).toArray(new Size[0]));
                display = choose(ext.getExtensionSupportedSizes(extensionType, SurfaceTexture.class).toArray(new Size[0]),
                        ratio(still), 1440, 1080);
            } else if (film) {
                display = slow ? videoSize()
                        : choose(lens.streams.getOutputSizes(SurfaceTexture.class), ratio(videoSize()), 1920, 1080);
            } else {
                display = choose(lens.streams.getOutputSizes(SurfaceTexture.class), ratio(still), 1440, 1080);
            }
            if (display == null) throw new IllegalStateException("Preview stream unavailable");
            previewSize = display;
            textureSurface.setDefaultBufferSize(display.getWidth(), display.getHeight());
            preview = new Surface(textureSurface);
            main.post(this::refreshTransform);
            List<OutputConfiguration> outputs = new ArrayList<>();
            if (!previewFromRecording) outputs.add(new OutputConfiguration(preview));
            if (!film) {
                int format = mode == Mode.RAW ? ImageFormat.RAW_SENSOR : ImageFormat.JPEG;
                reader = ImageReader.newInstance(still.getWidth(), still.getHeight(), format, 3);
                reader.setOnImageAvailableListener(images -> onImage(images, token), worker);
                OutputConfiguration captureOutput = new OutputConfiguration(reader.getSurface());
                if (mode == Mode.HIGH_RES && lens.maxPixelMode && Build.VERSION.SDK_INT >= 31) {
                    captureOutput.addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);
                }
                outputs.add(captureOutput);
            }
            if (extensionType != null && Build.VERSION.SDK_INT >= 31) {
                configureExtension(outputs, extensionType, token);
                return;
            }
            if (slow) {
                outputs.add(new OutputConfiguration(slowRecorder.surface()));
            } else if (forRecording) {
                Size size = videoSize();
                reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888,
                        highFrameRate ? 6 : 4);
                reader.setOnImageAvailableListener(images -> videoFrame(images, token), worker);
                outputs.add(new OutputConfiguration(reader.getSurface()));
            }
            int sessionType = slow ? SessionConfiguration.SESSION_HIGH_SPEED : SessionConfiguration.SESSION_REGULAR;
            SessionConfiguration config = new SessionConfiguration(sessionType, outputs, executor, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession ready) {
                    if (token != generation || camera == null) { ready.close(); return; }
                    session = ready;
                    try {
                        repeating = camera.createCaptureRequest(forRecording ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW);
                        if (!previewFromRecording) repeating.addTarget(preview);
                        if (slow) {
                            repeating.addTarget(slowRecorder.surface());
                            repeating.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, SLOW_RATE);
                            slowRecorder.start();
                        } else if (forRecording) {
                            repeating.addTarget(reader.getSurface());
                            Range<Integer>[] rates = lens.info.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                            if (highFrameRate) {
                                setHighVideoRate(repeating);
                            } else if (mode == Mode.VIDEO_4K && rates != null && Arrays.asList(rates).contains(VIDEO_RATE)) {
                                repeating.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, VIDEO_RATE);
                            }
                            recorder.start();
                        }
                        updatePreview();
                        if (forRecording) {
                            previewFramesDuringRecording.set(0);
                            captureHighRatePreview = highFrameRate && !previewFromRecording;
                            if (previewFromRecording) recordingPreview.begin();
                            recording = true;
                            recordingSince = android.os.SystemClock.elapsedRealtime();
                            videoFirstTimestamp = videoFrameCount = 0;
                            notifyRecording(true);
                            if (slow) worker.post(() -> pumpSlowVideo(token));
                        }
                        ready();
                    } catch (CameraAccessException | RuntimeException e) {
                        failConfiguration(forRecording, "无法启动相机预览", e);
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession failed) {
                    failed.close();
                    if (token == generation) failConfiguration(forRecording, "当前镜头不支持此拍摄组合", null);
                }
                @Override public void onReady(CameraCaptureSession idle) {
                    if (token == generation && idle == session && stoppingSlowVideo) finishVideo(true);
                }
            });
            if (slow || highFrameRate) {
                CaptureRequest.Builder parameters = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                if (slow) parameters.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, SLOW_RATE);
                else setHighVideoRate(parameters);
                config.setSessionParameters(parameters.build());
            }
            camera.createCaptureSession(config);
        } catch (Exception e) { failConfiguration(forRecording, "无法配置相机", e); }
    }

    private void configureExtension(List<OutputConfiguration> outputs, int type, int token) throws CameraAccessException {
        camera.createExtensionSession(new ExtensionSessionConfiguration(type, outputs, executor, new CameraExtensionSession.StateCallback() {
            @Override public void onConfigured(CameraExtensionSession ready) {
                if (token != generation || camera == null) { closeExtension(ready); return; }
                extension = ready;
                try {
                    repeating = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                    repeating.addTarget(preview);
                    updatePreview();
                    ready();
                } catch (CameraAccessException | RuntimeException e) { failConfiguration(false, "无法启动相机扩展", e); }
            }
            @Override public void onConfigureFailed(CameraExtensionSession failed) {
                closeExtension(failed);
                if (token == generation) failConfiguration(false, "此镜头无法启动相机扩展", null);
            }
        }));
    }

    private void failConfiguration(boolean recordingAttempt, String message, Exception e) {
        closeSession();
        if (recordingAttempt) {
            discardVideo();
            recoverPreview();
        }
        error(message, e);
    }

    public void refreshTransform() {
        Size buffer = previewSize;
        int width = texture.getWidth(), height = texture.getHeight();
        if (buffer == null || width == 0 || height == 0) return;
        float aspect = (float) buffer.getHeight() / buffer.getWidth();
        float viewAspect = (float) width / height;
        Matrix transform = new Matrix();
        transform.setScale(Math.max(1, aspect / viewAspect), Math.max(1, viewAspect / aspect), width / 2f, height / 2f);
        texture.setTransform(transform);
    }

    public void takePhoto() {
        worker.post(() -> {
            if (camera == null || reader == null || capturePending || (session == null && extension == null)) return;
            capturePending = true;
            long token = ++captureToken;
            busy(true);
            try {
                CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                request.addTarget(reader.getSurface());
                int rotation = outputRotation();
                rawOrientation = rotation == 90 ? ExifInterface.ORIENTATION_ROTATE_90
                        : rotation == 180 ? ExifInterface.ORIENTATION_ROTATE_180
                        : rotation == 270 ? ExifInterface.ORIENTATION_ROTATE_270 : ExifInterface.ORIENTATION_NORMAL;
                if (mode != Mode.RAW) {
                    request.set(CaptureRequest.JPEG_ORIENTATION, rotation);
                    request.set(CaptureRequest.JPEG_QUALITY, (byte) 96);
                }
                if (extension != null && Build.VERSION.SDK_INT >= 31) {
                    extension.capture(request.build(), executor, new CameraExtensionSession.ExtensionCaptureCallback() {
                        @Override public void onCaptureFailed(CameraExtensionSession s, CaptureRequest request) { captureFailed(token); }
                    });
                } else if (mode == Mode.HDR || mode == Mode.NIGHT || mode == Mode.LIGHT_TRAILS) {
                    captureBracket(rotation, token);
                } else {
                    controls(request, true);
                    if (mode == Mode.HIGH_RES && lens.maxPixelMode && Build.VERSION.SDK_INT >= 31) {
                        request.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);
                    }
                    session.capture(request.build(), new CameraCaptureSession.CaptureCallback() {
                        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult result) {
                            if (token == captureToken && mode == Mode.RAW) { rawResult = result; finishRaw(); }
                        }
                        @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, CaptureFailure failed) { captureFailed(token); }
                    }, worker);
                }
                worker.postDelayed(() -> { if (capturePending && captureToken == token) captureFailed(token); }, 14000);
            } catch (CameraAccessException | RuntimeException e) {
                clearPending(); error("拍摄失败", e);
            }
        });
    }

    private void captureFailed(long token) {
        if (capturePending && captureToken == token) { clearPending(); error("相机未返回照片", null); }
    }

    private void captureBracket(int rotation, long token) throws CameraAccessException {
        Mode captureMode = mode;
        double[] factors = captureMode == Mode.HDR ? new double[]{0.25, 1, 4}
                : captureMode == Mode.NIGHT ? new double[]{1, 1, 1, 1}
                : new double[]{1, 1, 1, 1, 1, 1, 1, 1};
        bracket = new byte[factors.length][];
        List<CaptureRequest> requests = new ArrayList<>();
        for (int index = 0; index < factors.length; index++) {
            CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            request.addTarget(reader.getSurface());
            request.setTag(index);
            request.set(CaptureRequest.JPEG_ORIENTATION, rotation);
            request.set(CaptureRequest.JPEG_QUALITY, (byte) 94);
            controls(request, true);
            request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            request.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
            request.set(CaptureRequest.CONTROL_AWB_LOCK, get(lens.info, CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE, false));
            long manualTime = Math.max(lens.minExposure, Math.min(lens.maxExposure, Math.round(autoExposure * factors[index])));
            request.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualTime);
            request.set(CaptureRequest.SENSOR_SENSITIVITY, Math.max(lens.minIso, Math.min(lens.maxIso, autoIso)));
            requests.add(request.build());
        }
        CameraCaptureSession.CaptureCallback callback = new CameraCaptureSession.CaptureCallback() {
            @Override public void onCaptureStarted(CameraCaptureSession s, CaptureRequest request, long timestamp, long frameNumber) {
                if (token != captureToken || bracket == null) return;
                bracketIndices.put(timestamp, (Integer) request.getTag());
                matchBracket(timestamp);
            }
            @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request, TotalCaptureResult result) {
                Log.i(TAG, captureMode + " burst frame " + request.getTag()
                        + " exposureNs=" + request.get(CaptureRequest.SENSOR_EXPOSURE_TIME)
                        + "/" + result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                        + " ISO=" + request.get(CaptureRequest.SENSOR_SENSITIVITY)
                        + "/" + result.get(CaptureResult.SENSOR_SENSITIVITY)
                        + " durationNs=" + result.get(CaptureResult.SENSOR_FRAME_DURATION));
                int next = (Integer) request.getTag() + 1;
                if (captureMode == Mode.LIGHT_TRAILS && next < requests.size()) {
                    worker.postDelayed(() -> {
                        if (token != captureToken || session != s || bracket == null) return;
                        try {
                            s.capture(requests.get(next), this, worker);
                        } catch (CameraAccessException | RuntimeException e) {
                            clearPending();
                            error("光轨拍摄中断", e);
                        }
                    }, 150);
                }
            }
            @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest request, CaptureFailure failure) { captureFailed(token); }
        };
        if (captureMode == Mode.LIGHT_TRAILS) session.capture(requests.get(0), callback, worker);
        else session.captureBurst(requests, callback, worker);
    }

    private void matchBracket(long timestamp) {
        Integer index = bracketIndices.get(timestamp);
        byte[] image = bracketImages.get(timestamp);
        if (index == null || image == null) return;
        bracketIndices.remove(timestamp);
        bracketImages.remove(timestamp);
        bracket[index] = image;
        for (byte[] item : bracket) if (item == null) return;
        List<byte[]> frames = Arrays.asList(bracket);
        Mode captureMode = mode;
        clearPending();
        save(() -> {
            byte[] merged = captureMode == Mode.LIGHT_TRAILS ? LightTrails.merge(frames)
                    : ExposureFusion.merge(frames, captureMode == Mode.HDR);
            Log.i(TAG, captureMode + " bracket=" + frames.size()
                    + " fused=" + (merged != frames.get(1) && merged != frames.get(0)));
            return store.saveJpeg(merged);
        }, false);
    }

    private void onImage(ImageReader images, int token) {
        Image image;
        try { image = images.acquireNextImage(); } catch (IllegalStateException e) { return; }
        if (image == null) return;
        if (!capturePending || token != generation) { image.close(); return; }
        if (mode == Mode.RAW) {
            if (pendingRaw != null) pendingRaw.close();
            pendingRaw = image;
            finishRaw();
        } else {
            byte[] data;
            long timestamp = image.getTimestamp();
            try (Image owned = image) {
                java.nio.ByteBuffer bytes = owned.getPlanes()[0].getBuffer();
                data = new byte[bytes.remaining()];
                bytes.get(data);
            }
            if (bracket != null) {
                bracketImages.put(timestamp, data);
                matchBracket(timestamp);
                return;
            }
            clearPending();
            boolean document = mode == Mode.DOCUMENT;
            save(() -> store.saveJpeg(document ? DocumentPhoto.enhance(data) : data), false);
        }
    }

    private void finishRaw() {
        if (pendingRaw == null || rawResult == null) return;
        if (pendingRaw.getTimestamp() != get(rawResult, CaptureResult.SENSOR_TIMESTAMP, -1L)) return;
        try (Image image = pendingRaw) {
            // The ImageReader owns the RAW buffer, so finish writing before replacing the session.
            Uri uri = store.saveDng(lens.info, rawResult, image, rawOrientation);
            main.post(() -> listener.onSaved(uri, false));
        } catch (Exception e) { error("无法保存 RAW", e); }
        pendingRaw = null;
        clearPending();
        busy(false);
    }

    private void clearPending() {
        capturePending = false;
        captureToken++;
        if (pendingRaw != null) { pendingRaw.close(); pendingRaw = null; }
        rawResult = null;
        bracket = null;
        bracketIndices.clear();
        bracketImages.clear();
    }

    public void startRecording(boolean audio) {
        SurfaceTexture target = texture.getSurfaceTexture();
        worker.post(() -> {
            if (camera == null || session == null || recorder != null || slowRecorder != null
                    || !isVideo(mode) || target == null) return;
            try {
                videoFile = store.newVideoFile();
                if (mode == Mode.SLOW_MOTION) {
                    slowRecorder = new SlowVideo(videoFile, videoSize(), SLOW_RATE.getUpper(), outputRotation());
                } else {
                    int fps = isHighFrameRate(mode) ? 60 : 30;
                    recorder = new YuvVideo(videoFile, videoSize(), fps, outputRotation(), audio && hasVideoAudio(mode));
                }
                configure(target, true);
            } catch (Exception e) { discardVideo(); error("无法开始录像", e); }
        });
    }

    private Size videoSize() {
        if (mode == Mode.VIDEO_4K || mode == Mode.VIDEO_4K_HIGH_FRAME_RATE) return lens.video4k;
        if (mode == Mode.SLOW_MOTION) return lens.highSpeed;
        return lens.video;
    }

    private void pumpSlowVideo(int token) {
        if (!recording || token != generation || slowRecorder == null) return;
        try {
            slowRecorder.pump();
            worker.postDelayed(() -> pumpSlowVideo(token), 12);
        } catch (RuntimeException e) {
            error("慢动作编码失败", e);
            finishVideo(true);
        }
    }

    private void videoFrame(ImageReader source, int token) {
        try (Image image = isHighFrameRate(mode)
                ? source.acquireNextImage() : source.acquireLatestImage()) {
            if (image == null || !recording || token != generation) return;
            long now = image.getTimestamp();
            if (videoFirstTimestamp == 0) videoFirstTimestamp = now;
            long elapsedUs = (now - videoFirstTimestamp) / 1000;
            boolean lapse = mode == Mode.TIME_LAPSE;
            if (lapse && elapsedUs < videoFrameCount * 500_000L) return;
            recorder.frame(image, lapse ? videoFrameCount * 1_000_000L / 30 : elapsedUs);
            if (mode == Mode.VIDEO_4K_HIGH_FRAME_RATE) {
                recordingPreview.offer(image, get(lens.info, CameraCharacteristics.SENSOR_ORIENTATION, 90));
            }
            videoFrameCount++;
        } catch (Exception e) {
            if (recording && token == generation) {
                error("录像编码失败", e);
                finishVideo(true);
            }
        }
    }

    public void stopRecording() {
        worker.post(() -> {
            if (!recording) return;
            if (android.os.SystemClock.elapsedRealtime() - recordingSince < 550) {
                int token = generation;
                worker.postDelayed(() -> { if (recording && generation == token) stopSessionRecording(); }, 550);
            } else stopSessionRecording();
        });
    }

    private void stopSessionRecording() {
        if (slowRecorder == null) {
            finishVideo(true);
            return;
        }
        if (stoppingSlowVideo) return;
        stoppingSlowVideo = true;
        int token = generation;
        try {
            session.stopRepeating();
            worker.postDelayed(() -> {
                if (token == generation && stoppingSlowVideo) finishVideo(true);
            }, 1750);
        } catch (CameraAccessException | RuntimeException e) {
            Log.w(TAG, "Could not stop high-speed requests", e);
            finishVideo(true);
        }
    }

    private void finishVideo(boolean reopen) {
        if (mode == Mode.VIDEO_4K_HIGH_FRAME_RATE) {
            Log.i(TAG, "Preview displayed " + recordingPreview.end() + " frames during recording");
        } else if (captureHighRatePreview) {
            captureHighRatePreview = false;
            Log.i(TAG, "Preview displayed " + previewFramesDuringRecording.get() + " frames during recording");
        }
        recording = false;
        stoppingSlowVideo = false;
        notifyRecording(false);
        File output = videoFile;
        try {
            closeSession();
            if (slowRecorder != null) slowRecorder.stop();
            else recorder.stop();
            save(() -> { try { return store.publishVideo(output); } finally { output.delete(); } }, true);
            videoFile = null;
        } catch (Exception e) { error("录像无法保存，可能录制时间太短", e); }
        finally { closeSession(); discardVideo(); }
        if (reopen) recoverPreview();
    }

    private void recoverPreview() {
        main.post(() -> {
            SurfaceTexture current = texture.getSurfaceTexture();
            if (current != null) worker.post(() -> { if (enabled && camera != null) configure(current, false); });
        });
    }

    private void discardVideo() {
        if (slowRecorder != null) { slowRecorder.close(); slowRecorder = null; }
        if (recorder != null) { recorder.close(); recorder = null; }
        if (videoFile != null) { videoFile.delete(); videoFile = null; }
    }

    public void setZoom(float factor) { worker.post(() -> { zoom = factor; touchRegion = null; refreshControls(); }); }
    public void setFlash(Flash value) { worker.post(() -> { flash = value; refreshControls(); }); }
    public void setExposure(int value) { worker.post(() -> { exposureComp = value; refreshControls(); }); }
    public void setManual(int sensitivity, long nanos, float diopters, int wb) {
        worker.post(() -> { iso = sensitivity; exposureTime = nanos; focusDistance = diopters; whiteBalance = wb; refreshControls(); });
    }

    public void focus(float x, float y) {
        float width = texture.getWidth(), height = texture.getHeight();
        if (width == 0 || height == 0) return;
        Matrix inverse = new Matrix();
        if (!texture.getTransform(null).invert(inverse)) return;
        float[] point = {x * width, y * height};
        inverse.mapPoints(point);
        float previewX = point[0] / width, previewY = point[1] / height;
        worker.post(() -> {
            if (session == null || repeating == null || camera == null) return;
            Rect bounds = lens.info.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (bounds == null) return;
            float tx = lens.front ? 1f - previewX : previewX;
            int rotation = get(lens.info, CameraCharacteristics.SENSOR_ORIENTATION, 90);
            float sx = rotation == 90 ? previewY : 1f - previewY;
            float sy = rotation == 90 ? 1f - tx : tx;
            int w = Math.max(2, bounds.width() / 10), h = Math.max(2, bounds.height() / 10);
            int left = Math.max(bounds.left, Math.min(bounds.right - w, bounds.left + Math.round(sx * bounds.width()) - w / 2));
            int top = Math.max(bounds.top, Math.min(bounds.bottom - h, bounds.top + Math.round(sy * bounds.height()) - h / 2));
            touchRegion = new Rect(left, top, left + w, top + h);
            try {
                controls(repeating, false);
                if (get(lens.info, CameraCharacteristics.CONTROL_MAX_REGIONS_AF, 0) != 0 && focusDistance < 0) {
                    repeating.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                    repeating.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                    if (session instanceof CameraConstrainedHighSpeedCaptureSession) {
                        CameraConstrainedHighSpeedCaptureSession highSpeed = (CameraConstrainedHighSpeedCaptureSession) session;
                        highSpeed.captureBurst(highSpeed.createHighSpeedRequestList(repeating.build()), previewCallback, worker);
                    } else {
                        session.capture(repeating.build(), previewCallback, worker);
                    }
                    repeating.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                }
                updatePreview();
            } catch (CameraAccessException | RuntimeException e) { Log.w(TAG, "Tap focus failed", e); }
        });
    }

    private void refreshControls() {
        if (repeating == null) return;
        try { updatePreview(); } catch (CameraAccessException | RuntimeException e) { Log.w(TAG, "Camera settings rejected", e); }
    }

    private final CameraCaptureSession.CaptureCallback previewCallback = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult result) {
            if (s != session || iso != 0 || exposureTime != 0) return;
            autoIso = get(result, CaptureResult.SENSOR_SENSITIVITY, autoIso);
            autoExposure = get(result, CaptureResult.SENSOR_EXPOSURE_TIME, autoExposure);
        }
    };

    private void updatePreview() throws CameraAccessException {
        if (extension != null && Build.VERSION.SDK_INT >= 31) { extension.setRepeatingRequest(repeating.build(), executor, null); return; }
        controls(repeating, false);
        if (session instanceof CameraConstrainedHighSpeedCaptureSession) {
            CameraConstrainedHighSpeedCaptureSession highSpeed = (CameraConstrainedHighSpeedCaptureSession) session;
            highSpeed.setRepeatingBurst(highSpeed.createHighSpeedRequestList(repeating.build()), previewCallback, worker);
        } else {
            session.setRepeatingRequest(repeating.build(), previewCallback, worker);
        }
    }

    private void controls(CaptureRequest.Builder request, boolean still) {
        int scene = scene(mode);
        boolean sceneEnabled = scene != -1 && mode != Mode.HDR && mode != Mode.NIGHT && !lens.extensions.containsKey(mode);
        request.set(CaptureRequest.CONTROL_MODE, sceneEnabled ? CaptureRequest.CONTROL_MODE_USE_SCENE_MODE : CaptureRequest.CONTROL_MODE_AUTO);
        if (sceneEnabled) request.set(CaptureRequest.CONTROL_SCENE_MODE, scene);
        if (lens.info.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null) {
            request.set(CaptureRequest.CONTROL_ZOOM_RATIO, Math.max(lens.minZoom, Math.min(lens.maxZoom, zoom)));
        } else if (zoom > 1) {
            Rect sensor = lens.info.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (sensor != null) {
                float f = Math.min(zoom, lens.maxZoom);
                int w = Math.round(sensor.width() / f / 2), h = Math.round(sensor.height() / f / 2);
                request.set(CaptureRequest.SCALER_CROP_REGION, new Rect(sensor.centerX() - w, sensor.centerY() - h, sensor.centerX() + w, sensor.centerY() + h));
            }
        } else request.set(CaptureRequest.SCALER_CROP_REGION, null);
        boolean pro = mode == Mode.PRO || mode == Mode.RAW;
        boolean manualExposure = pro && lens.manual && (iso != 0 || exposureTime != 0);
        int ae = CaptureRequest.CONTROL_AE_MODE_ON;
        if (manualExposure) ae = CaptureRequest.CONTROL_AE_MODE_OFF;
        else if (lens.flash && !isVideo(mode) && flash == Flash.AUTO) ae = CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH;
        else if (lens.flash && !isVideo(mode) && flash == Flash.ON) ae = CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH;
        request.set(CaptureRequest.CONTROL_AE_MODE, ae);
        request.set(CaptureRequest.FLASH_MODE, lens.flash && flash == Flash.TORCH ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
        if (manualExposure) {
            long time = exposureTime == 0 ? autoExposure : exposureTime;
            request.set(CaptureRequest.SENSOR_SENSITIVITY, Math.max(lens.minIso, Math.min(lens.maxIso, iso == 0 ? autoIso : iso)));
            request.set(CaptureRequest.SENSOR_EXPOSURE_TIME, Math.max(lens.minExposure, Math.min(lens.maxExposure, still ? time : Math.min(time, 100_000_000L))));
        } else request.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, Math.max(lens.minEv, Math.min(lens.maxEv, exposureComp)));
        int af = isVideo(mode) ? CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO : CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
        int[] modes = lens.info.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (pro && focusDistance >= 0 && lens.maxFocus > 0) {
            af = CaptureRequest.CONTROL_AF_MODE_OFF;
            request.set(CaptureRequest.LENS_FOCUS_DISTANCE, Math.min(focusDistance, lens.maxFocus));
        } else if (!contains(modes, af)) af = contains(modes, CaptureRequest.CONTROL_AF_MODE_AUTO)
                ? CaptureRequest.CONTROL_AF_MODE_AUTO : CaptureRequest.CONTROL_AF_MODE_OFF;
        else if (touchRegion != null && contains(modes, CaptureRequest.CONTROL_AF_MODE_AUTO)) af = CaptureRequest.CONTROL_AF_MODE_AUTO;
        request.set(CaptureRequest.CONTROL_AF_MODE, af);
        if (touchRegion != null) {
            MeteringRectangle[] areas = {new MeteringRectangle(touchRegion, MeteringRectangle.METERING_WEIGHT_MAX)};
            if (get(lens.info, CameraCharacteristics.CONTROL_MAX_REGIONS_AF, 0) != 0) request.set(CaptureRequest.CONTROL_AF_REGIONS, areas);
            if (get(lens.info, CameraCharacteristics.CONTROL_MAX_REGIONS_AE, 0) != 0) request.set(CaptureRequest.CONTROL_AE_REGIONS, areas);
        }
        int[] wb = lens.info.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
        request.set(CaptureRequest.CONTROL_AWB_MODE, pro && contains(wb, whiteBalance) && whiteBalance > 0
                ? whiteBalance : CaptureRequest.CONTROL_AWB_MODE_AUTO);
        int[] ois = lens.info.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        if (contains(ois, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
            request.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
        }
    }

    private void closeCamera() {
        generation++;
        if (recording) finishVideo(false);
        closeSession();
        discardVideo();
        if (camera != null) { camera.close(); camera = null; }
    }

    private void closeSession() {
        repeating = null;
        clearPending();
        if (session != null) { session.close(); session = null; }
        if (extension != null) { closeExtension(extension); extension = null; }
        if (reader != null) { reader.close(); reader = null; }
        if (preview != null) { preview.release(); preview = null; }
    }

    private void closeExtension(CameraExtensionSession value) {
        try { value.close(); } catch (CameraAccessException e) { Log.w(TAG, "Extension close failed", e); }
    }

    private int outputRotation() {
        int sensor = get(lens.info, CameraCharacteristics.SENSOR_ORIENTATION, 90);
        return (sensor + (lens.front ? -deviceDegrees : deviceDegrees) + 360) % 360;
    }

    private interface SaveTask { Uri save() throws Exception; }

    private void save(SaveTask task, boolean video) {
        io.execute(() -> {
            try {
                Uri uri = task.save();
                main.post(() -> { if (!destroyed) listener.onSaved(uri, video); });
                Log.i(TAG, "Saved " + (video ? "video " : "image ") + uri);
            } catch (Exception e) { error("无法保存到相册", e); }
            finally { if (!video) busy(false); }
        });
    }

    private void ready() {
        Lens current = lens;
        Mode currentMode = mode;
        main.post(() -> { if (enabled) listener.onReady(current, currentMode); });
        busy(false);
    }

    private void notifyRecording(boolean value) { main.post(() -> { if (!destroyed) listener.onRecording(value); }); }
    private void busy(boolean value) { main.post(() -> { if (!destroyed) listener.onBusy(value); }); }

    private void error(String message, Exception cause) {
        Log.w(TAG, message, cause);
        main.post(() -> { if (enabled) listener.onError(message); });
        busy(false);
    }

    public static boolean isVideo(Mode mode) {
        return hasVideoAudio(mode) || mode == Mode.TIME_LAPSE || mode == Mode.SLOW_MOTION;
    }

    public static boolean hasVideoAudio(Mode mode) {
        return mode == Mode.VIDEO || mode == Mode.VIDEO_4K || isHighFrameRate(mode);
    }

    private static boolean isHighFrameRate(Mode mode) {
        return mode == Mode.VIDEO_HIGH_FRAME_RATE || mode == Mode.VIDEO_4K_HIGH_FRAME_RATE;
    }

    private static boolean supportsHighFrameRate(CameraCharacteristics info, boolean cupidMain, Size size) {
        if (!cupidMain) return false;
        try {
            int[] rates = info.get(new CameraCharacteristics.Key<>(STREAM_FPS_SECTION + "StreamBasedFPSTable",
                    int[].class));
            for (int i = 0; rates != null && i + 3 < rates.length; i += 4) {
                if (rates[i] == size.getWidth() && rates[i + 1] == size.getHeight()
                        && rates[i + 2] == 60 && rates[i + 3] == 60) return YuvVideo.supports(size, 60);
            }
        } catch (IllegalArgumentException ignored) {
            // This vendor tag is optional on other camera provider builds.
        }
        return false;
    }

    private static void setHighVideoRate(CaptureRequest.Builder request) {
        request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, HIGH_VIDEO_RATE);
        try {
            request.set(PREVIEW_FPS, 30);
            request.set(RECORDING_FPS, 60);
        } catch (IllegalArgumentException ignored) {
            // The regular session also accepts the fixed target range without these hints.
        }
    }


    private static int scene(Mode mode) {
        if (mode == Mode.HDR) return CameraMetadata.CONTROL_SCENE_MODE_HDR;
        if (mode == Mode.NIGHT) return CameraMetadata.CONTROL_SCENE_MODE_NIGHT;
        if (mode == Mode.PORTRAIT) return CameraMetadata.CONTROL_SCENE_MODE_PORTRAIT;
        return -1;
    }

    private static boolean contains(int[] values, int value) {
        if (values != null) for (int candidate : values) if (candidate == value) return true;
        return false;
    }

    private static Size largest(Size[] values) {
        return values == null ? null : Arrays.stream(values).max(Comparator.comparingLong(CameraController::area)).orElse(null);
    }

    private static long area(Size size) { return size == null ? 0 : (long) size.getWidth() * size.getHeight(); }
    private static double ratio(Size size) { return size == null ? 4.0 / 3 : (double) size.getWidth() / size.getHeight(); }

    private static Size choose(Size[] values, double aspect, int maxWidth, int maxHeight) {
        if (values == null || values.length == 0) return null;
        return Arrays.stream(values).filter(size -> size.getWidth() <= maxWidth && size.getHeight() <= maxHeight)
                .min(Comparator.<Size>comparingDouble(size -> Math.round(Math.abs(ratio(size) - aspect) * 100)).thenComparingLong(size -> -area(size)))
                .orElseGet(() -> Arrays.stream(values).min(Comparator.comparingLong(CameraController::area)).orElse(null));
    }

    private static <T> T get(CameraCharacteristics data, CameraCharacteristics.Key<T> key, T fallback) {
        T value = data.get(key);
        return value == null ? fallback : value;
    }

    private static <T> T get(CaptureResult data, CaptureResult.Key<T> key, T fallback) {
        T value = data.get(key);
        return value == null ? fallback : value;
    }
}
