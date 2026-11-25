package org.kleeui.camera;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.camera2.CaptureRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.RoundedCorner;
import android.view.ScaleGestureDetector;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Chronometer;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.kleeui.camera.CameraController.Flash;
import org.kleeui.camera.CameraController.Lens;
import org.kleeui.camera.CameraController.Mode;

import static org.kleeui.camera.CameraController.hasVideoAudio;
import static org.kleeui.camera.CameraController.isVideo;

/** The camera's view and operator controls. Camera sessions are owned by CameraController. */
public final class MainActivity extends Activity implements CameraController.Listener {
    private static final int BACKGROUND = 0xff121211;
    private static final int PANEL = 0xff23221f;
    private static final int OUTLINE = 0xff3e3c36;
    private static final int FALLBACK_ACCENT = 0xffffcc80;
    private static final int LIGHT = 0xfff5f1e7;
    private static final int MUTED = 0xffb3ada0;
    private static final int RECORD = 0xfff57365;
    private static final int VIDEO_RED = 0xffff3838;
    private static final int VIDEO_CONTROL = 0x99222222;
    private static final int VIDEO_CONTROL_BORDER = 0x66ffffff;
    private static final int CAMERA_PERMISSION = 20;
    private static final int AUDIO_PERMISSION = 21;
    private static final int AUTO_WHITE_BALANCE = 0;
    private static final Mode[] MODES = {
        Mode.PRO, Mode.NIGHT, Mode.LIGHT_TRAILS, Mode.PORTRAIT, Mode.PHOTO, Mode.VIDEO,
        Mode.HIGH_RES, Mode.HDR, Mode.RAW, Mode.SLOW_MOTION, Mode.TIME_LAPSE, Mode.DOCUMENT
    };
    private static final Mode[] VIDEO_PROFILES = {
        Mode.VIDEO, Mode.VIDEO_HIGH_FRAME_RATE, Mode.VIDEO_4K, Mode.VIDEO_4K_HIGH_FRAME_RATE
    };

    private CameraController camera;
    private TextureView preview;
    private RecordingPreview recordingPreview;
    private CameraLayout cameraLayout;
    private Guide guide;
    private TextView flashButton;
    private TextView optionsButton;
    private TextView toolsButton;
    private TextView previewMessage;
    private TextView notice;
    private TextView countLabel;
    private TextView zoomLabel;
    private TextView videoSettingsButton;
    private TextView exposureLabel;
    private TextView flipButton;
    private FrameLayout galleryButton;
    private Chronometer recordingClock;
    private CaptureButton shutterButton;
    private HorizontalScrollView modeScroll;
    private LinearLayout modeRow;
    private LinearLayout lensRow;
    private LinearLayout manualPanel;
    private final List<SeekBar> manualSliders = new ArrayList<>();
    private int accent;
    private Lens lens;
    private Mode currentMode = Mode.PHOTO;
    private Flash flash = Flash.OFF;
    private boolean recording;
    private boolean busy;
    private boolean controlsReady;
    private boolean cameraStarted;
    private boolean resumed;
    private boolean permissionRequested;
    private boolean selectionPending;
    private boolean awaitingAudioPermission;
    private Boolean pendingAudio;
    private boolean showGrid;
    private Mode preferredVideoMode = Mode.VIDEO;
    private int timerSeconds;
    private CountDownTimer timer;
    private int burstCount = 1;
    private int burstRemaining;
    private boolean photoInFlight;
    private boolean photoSaved;
    private boolean initialVideoPending;
    private boolean captureResultPending;
    private CaptureRequestResult captureRequest;
    private int cameraGeneration;
    private boolean restoreSelection;
    private Uri lastCapture;
    private boolean lastCaptureVideo;
    private String lastRear;
    private String lastFront;
    private float currentZoom = 1f;
    private int iso;
    private int exposureCompensation;
    private long exposureNanos;
    private float focusDistance = -1f;
    private int whiteBalance = AUTO_WHITE_BALANCE;
    private final ExecutorService thumbnails = Executors.newSingleThreadExecutor();
    private final Runnable clearNotice = () -> notice.setVisibility(View.GONE);

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        String action = getIntent().getAction();
        boolean imageCapture = MediaStore.ACTION_IMAGE_CAPTURE.equals(action);
        boolean videoCapture = MediaStore.ACTION_VIDEO_CAPTURE.equals(action);
        initialVideoPending = videoCapture || MediaStore.INTENT_ACTION_VIDEO_CAMERA.equals(action);
        if (imageCapture || videoCapture) {
            try {
                captureRequest = new CaptureRequestResult(this, videoCapture);
            } catch (IllegalArgumentException | android.os.BadParcelableException | ClassCastException | SecurityException e) {
                setResult(RESULT_CANCELED);
                finish();
                return;
            }
        }
        accent = resolveAccent();
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setDecorFitsSystemWindows(false);
        WindowManager.LayoutParams window = getWindow().getAttributes();
        window.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setAttributes(window);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        String recent = captureRequest == null ? getPreferences(MODE_PRIVATE).getString("recent", null) : null;
        if (recent != null) {
            lastCapture = Uri.parse(recent);
            lastCaptureVideo = getPreferences(MODE_PRIVATE).getBoolean("recentVideo", false);
        }
        showGrid = getPreferences(MODE_PRIVATE).getBoolean("grid", false);
        String preference = getPreferences(MODE_PRIVATE).getString("videoProfile", Mode.VIDEO.name());
        for (Mode profile : VIDEO_PROFILES) {
            if (profile.name().equals(preference)) preferredVideoMode = profile;
        }
        createInterface();
        WindowInsetsController bars = getWindow().getInsetsController();
        if (bars != null) {
            bars.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            bars.hide(WindowInsets.Type.statusBars());
        }
        preview.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
            if (camera != null && (r - l != or - ol || b - t != ob - ot)) camera.refreshTransform();
        });
        installGestures();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isFinishing()) return;
        refreshAccent();
        resumed = true;
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else if (!permissionRequested) {
            requestCameraPermission();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        refreshAccent();
    }

    private int resolveAccent() {
        TypedValue value = new TypedValue();
        if (!getTheme().resolveAttribute(android.R.attr.colorAccent, value, true)) return FALLBACK_ACCENT;
        int color = value.data;
        double contrast = (Color.luminance(color) + 0.05) / (Color.luminance(PANEL) + 0.05);
        return Color.alpha(color) == 255 && contrast >= 4.5 ? color : FALLBACK_ACCENT;
    }

    private void refreshAccent() {
        getTheme().rebase();
        int color = resolveAccent();
        if (accent == color) return;
        accent = color;
        for (int i = 0; i < modeRow.getChildCount(); i++) {
            styleModeChip((TextView) modeRow.getChildAt(i));
        }
        for (int i = 0; i < lensRow.getChildCount(); i++) {
            styleLensChip((TextView) lensRow.getChildAt(i));
        }
        for (SeekBar slider : manualSliders) tintSlider(slider);
        updateFlash();
        videoSettingsButton.setTextColor(accent);
        shutterButton.invalidate();
        guide.invalidate();
    }

    @Override
    protected void onPause() {
        if (notice == null) {
            super.onPause();
            return;
        }
        resumed = false;
        cancelPhotoSequence();
        if (photoInFlight) retireCamera();
        else if (cameraStarted) camera.stop();
        cameraStarted = false;
        selectionPending = false;
        controlsReady = false;
        busy = false;
        updateRecording(false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (notice != null) notice.removeCallbacks(clearNotice);
        if (guide != null) guide.hideFocusMark();
        thumbnails.shutdownNow();
        cameraGeneration++;
        if (camera != null) camera.destroy();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == CAMERA_PERMISSION) {
            if (granted && resumed) startCamera();
            if (!granted) {
                if (captureRequest != null) {
                    finish();
                    return;
                }
                previewMessage.setText("点击授权相机");
                previewMessage.setVisibility(View.VISIBLE);
            }
        } else if (requestCode == AUDIO_PERMISSION && awaitingAudioPermission) {
            awaitingAudioPermission = false;
            pendingAudio = granted;
            recordAfterAudioPermission();
        }
    }

    private void requestCameraPermission() {
        permissionRequested = true;
        requestPermissions(new String[] {Manifest.permission.CAMERA}, CAMERA_PERMISSION);
    }

    private void startCamera() {
        if (cameraStarted) return;
        if (camera == null) createCamera();
        cameraStarted = true;
        previewMessage.setText("正在打开相机");
        previewMessage.setVisibility(View.VISIBLE);
        camera.start();
        updateEnabled();
    }

    private void createCamera() {
        int generation = ++cameraGeneration;
        camera = new CameraController(this, preview, recordingPreview, captureRequest, new CameraController.Listener() {
            @Override public void onReady(Lens readyLens, Mode mode) {
                if (generation == cameraGeneration) MainActivity.this.onReady(readyLens, mode);
            }

            @Override public void onSaved(Uri uri, boolean video) {
                if (generation == cameraGeneration) MainActivity.this.onSaved(uri, video);
            }

            @Override public void onError(String message) {
                if (generation == cameraGeneration) MainActivity.this.onError(message);
            }

            @Override public void onRecording(boolean active) {
                if (generation == cameraGeneration) MainActivity.this.onRecording(active);
            }

            @Override public void onBusy(boolean active) {
                if (generation == cameraGeneration) MainActivity.this.onBusy(active);
            }
        });
    }

    private void retireCamera() {
        // A previous controller may still finish writing its image. Its callbacks cannot sequence new captures.
        cameraGeneration++;
        camera.destroy();
        camera = null;
        cameraStarted = false;
        controlsReady = false;
        photoInFlight = false;
        photoSaved = false;
        restoreSelection = lens != null;
    }

    private void createInterface() {
        LinearLayout header = horizontal(Gravity.CENTER_VERTICAL);
        toolsButton = headerButton("调整", "调整曝光或专业参数");
        toolsButton.setOnClickListener(view -> toggleTools());
        header.addView(toolsButton, headerSize());

        videoSettingsButton = headerButton("1080p | 30 fps", "录像画质与帧率");
        videoSettingsButton.setTextColor(accent);
        videoSettingsButton.setTextSize(14);
        videoSettingsButton.setPadding(dp(10), 0, dp(10), 0);
        videoSettingsButton.setMaxLines(1);
        videoSettingsButton.setBackground(pressable(VIDEO_CONTROL, VIDEO_CONTROL_BORDER, 24));
        videoSettingsButton.setOnClickListener(this::showVideoSettings);
        header.addView(videoSettingsButton, new LinearLayout.LayoutParams(-2, dp(44)));
        videoSettingsButton.setVisibility(View.GONE);

        header.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1));
        LinearLayout topActions = horizontal(Gravity.CENTER_VERTICAL);
        header.addView(topActions, new LinearLayout.LayoutParams(-2, dp(44)));
        flashButton = headerButton("闪光", "闪光灯关闭");
        flashButton.setOnClickListener(view -> cycleFlash());
        topActions.addView(flashButton, headerSize());
        optionsButton = headerButton("设置", "网格、定时与连拍设置");
        optionsButton.setOnClickListener(this::showOptions);
        topActions.addView(optionsButton, headerSize());

        FrameLayout viewport = new FrameLayout(this);
        viewport.setBackgroundColor(Color.BLACK);
        preview = new TextureView(this);
        preview.setOpaque(true);
        viewport.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        recordingPreview = new RecordingPreview(this);
        viewport.addView(recordingPreview, new FrameLayout.LayoutParams(-1, -1));
        guide = new Guide();
        viewport.addView(guide, new FrameLayout.LayoutParams(-1, -1));

        previewMessage = overlayLabel("点击授权相机", 14);
        previewMessage.setOnClickListener(view -> {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                if (permissionRequested && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                    Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", getPackageName(), null));
                    startActivity(intent);
                } else {
                    requestCameraPermission();
                }
            } else if (!controlsReady && !busy && resumed) {
                camera.stop();
                cameraStarted = false;
                startCamera();
            }
        });
        viewport.addView(previewMessage, inFrame(-2, -2, Gravity.CENTER, 0));

        lensRow = horizontal(Gravity.CENTER);
        lensRow.setPadding(dp(4), dp(1), dp(4), dp(1));
        lensRow.setBackground(shape(0xcc151412, 0x996a6457, 18, 1));
        viewport.addView(lensRow, inFrame(-2, dp(38), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 12));
        lensRow.setVisibility(View.GONE);
        zoomLabel = overlayLabel("", 12);
        viewport.addView(zoomLabel, inFrame(-2, -2, Gravity.TOP | Gravity.START, 12));
        zoomLabel.setVisibility(View.GONE);

        recordingClock = new Chronometer(this);
        recordingClock.setGravity(Gravity.CENTER);
        recordingClock.setTextColor(LIGHT);
        recordingClock.setTextSize(12);
        recordingClock.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        recordingClock.setPadding(dp(10), dp(6), dp(10), dp(6));
        recordingClock.setBackground(shape(0xf788302c, Color.TRANSPARENT, 13, 0));
        viewport.addView(recordingClock, inFrame(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 12));
        recordingClock.setVisibility(View.GONE);

        countLabel = label("", 76, LIGHT);
        countLabel.setGravity(Gravity.CENTER);
        countLabel.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        countLabel.setShadowLayer(dp(6), 0, dp(1), Color.BLACK);
        viewport.addView(countLabel, inFrame(-2, -2, Gravity.CENTER, 0));
        countLabel.setVisibility(View.GONE);

        notice = overlayLabel("", 13);
        notice.setMaxLines(2);
        viewport.addView(notice, inFrame(-1, -2, Gravity.TOP, 12));
        notice.setVisibility(View.GONE);

        manualPanel = vertical();
        manualPanel.setBackground(shape(0xf522211e, OUTLINE, 13, 1));
        manualPanel.setPadding(dp(10), dp(6), dp(10), dp(6));
        FrameLayout.LayoutParams panelLayout = inFrame(-1, -2, Gravity.BOTTOM, 10);
        panelLayout.bottomMargin = dp(59);
        viewport.addView(manualPanel, panelLayout);
        manualPanel.setVisibility(View.GONE);

        LinearLayout controls = vertical();
        modeScroll = new HorizontalScrollView(this);
        modeScroll.setHorizontalScrollBarEnabled(false);
        modeScroll.setFillViewport(true);
        modeScroll.setClipToPadding(false);
        modeRow = horizontal(Gravity.CENTER_VERTICAL);
        modeRow.setPadding(dp(3), 0, dp(3), 0);
        modeScroll.addView(modeRow, new HorizontalScrollView.LayoutParams(-2, -1));
        controls.addView(modeScroll, matchHeight(dp(62)));

        LinearLayout footer = horizontal(Gravity.CENTER);
        footer.setPadding(dp(10), 0, dp(10), 0);
        controls.addView(footer, new LinearLayout.LayoutParams(-1, 0, 1));
        FrameLayout left = new FrameLayout(this);
        footer.addView(left, new LinearLayout.LayoutParams(0, -1, 1));
        galleryButton = new FrameLayout(this);
        galleryButton.setContentDescription("相册");
        galleryButton.setFocusable(true);
        galleryButton.setOnClickListener(view -> openGallery());
        left.addView(galleryButton, inFrame(dp(49), dp(49), Gravity.CENTER, 0));
        setGalleryIcon();

        shutterButton = new CaptureButton();
        shutterButton.setFocusable(true);
        shutterButton.setOnClickListener(view -> shutter());
        footer.addView(shutterButton, new LinearLayout.LayoutParams(dp(73), dp(73)));

        FrameLayout right = new FrameLayout(this);
        footer.addView(right, new LinearLayout.LayoutParams(0, -1, 1));
        flipButton = label("翻转", 12, LIGHT);
        flipButton.setGravity(Gravity.CENTER);
        flipButton.setContentDescription("切换前后摄像头");
        flipButton.setBackground(pressable(PANEL, OUTLINE, 18));
        flipButton.setOnClickListener(view -> switchCamera());
        right.addView(flipButton, inFrame(dp(49), dp(49), Gravity.CENTER, 0));
        cameraLayout = new CameraLayout(header, topActions, viewport, controls);
        setContentView(cameraLayout);
        cameraLayout.requestApplyInsets();
        updateEnabled();
    }

    private final class CameraLayout extends FrameLayout {
        private static final float PREVIEW_TOP_FRACTION = 324f / 2848;
        private static final float PHOTO_ASPECT = 3f / 4;
        private static final float VIDEO_ASPECT = 9f / 16;
        private final LinearLayout header;
        private final LinearLayout topActions;
        private final FrameLayout viewport;
        private final LinearLayout controls;

        CameraLayout(LinearLayout header, LinearLayout topActions, FrameLayout viewport, LinearLayout controls) {
            super(MainActivity.this);
            this.header = header;
            this.topActions = topActions;
            this.viewport = viewport;
            this.controls = controls;
            setBackgroundColor(Color.BLACK);
            addView(viewport, new FrameLayout.LayoutParams(-1, 0, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
            addView(header, new FrameLayout.LayoutParams(-1, 0, Gravity.TOP));
            addView(controls, new FrameLayout.LayoutParams(-1, 0, Gravity.BOTTOM));
        }

        void updateVideoChrome(boolean fullscreen) {
            if (fullscreen) {
                header.removeView(toolsButton);
                topActions.addView(toolsButton, 0, headerSize());
            } else {
                topActions.removeView(toolsButton);
                header.addView(toolsButton, 0, headerSize());
            }
            topActions.setBackground(fullscreen ? shape(VIDEO_CONTROL, VIDEO_CONTROL_BORDER, 24, 1) : null);
            topActions.getLayoutParams().height = dp(fullscreen ? 44 : 40);
            for (TextView button : new TextView[]{toolsButton, flashButton, optionsButton}) {
                button.setBackground(pressable(fullscreen ? Color.TRANSPARENT : PANEL,
                        fullscreen ? Color.TRANSPARENT : OUTLINE, fullscreen ? 24 : 13));
            }
            flipButton.setBackground(fullscreen ? pressable(VIDEO_CONTROL, VIDEO_CONTROL_BORDER, 26)
                    : pressable(PANEL, OUTLINE, 18));
            styleGalleryButton();
            lensRow.setBackground(fullscreen ? shape(VIDEO_CONTROL, VIDEO_CONTROL_BORDER, 24, 1)
                    : shape(0xcc151412, 0x996a6457, 18, 1));
            requestLayout();
        }

        @Override
        public WindowInsets onApplyWindowInsets(WindowInsets windowInsets) {
            Insets insets = windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            int top = insets.top;
            int bottom = insets.bottom;
            if (Build.VERSION.SDK_INT >= 31) {
                top = Math.max(top, cornerRadius(windowInsets, RoundedCorner.POSITION_TOP_LEFT));
                top = Math.max(top, cornerRadius(windowInsets, RoundedCorner.POSITION_TOP_RIGHT));
                bottom = Math.max(bottom, cornerRadius(windowInsets, RoundedCorner.POSITION_BOTTOM_LEFT));
                bottom = Math.max(bottom, cornerRadius(windowInsets, RoundedCorner.POSITION_BOTTOM_RIGHT));
            }
            if ("cupid".equals(Build.DEVICE) && !isInMultiWindowMode()) {
                // Use the Cupid panel geometry when the ROM leaves the corner data unset.
                int width = getWindowManager().getMaximumWindowMetrics().getBounds().width();
                top = Math.max(top, Math.round(width * 120f / 1080));
                bottom = Math.max(bottom, Math.round(width * 111f / 1080));
            }
            header.setPadding(dp(12) + insets.left, top, dp(17) + insets.right, dp(15));
            controls.setPadding(insets.left, 0, insets.right, bottom);
            return windowInsets;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int height = MeasureSpec.getSize(heightSpec);
            boolean fullscreen = isVideoProfileMode(currentMode);
            int minimumTop = header.getPaddingTop() + dp(fullscreen ? 44 : 40) + header.getPaddingBottom();
            int minimumBottom = dp(62) + dp(73) + controls.getPaddingBottom();
            int top = Math.min(Math.max(Math.round(height * PREVIEW_TOP_FRACTION), minimumTop),
                    Math.max(0, height - minimumBottom));
            int frameHeight = Math.min(Math.round(width / PHOTO_ASPECT), Math.max(0, height - top - minimumBottom));
            int controlsHeight = height - top - frameHeight;
            header.getLayoutParams().height = top;
            controls.getLayoutParams().height = controlsHeight;
            FrameLayout.LayoutParams frame = (FrameLayout.LayoutParams) viewport.getLayoutParams();
            float aspect = isVideo(currentMode) ? VIDEO_ASPECT : PHOTO_ASPECT;
            frame.width = fullscreen ? width : Math.min(width, Math.round(frameHeight * aspect));
            frame.height = fullscreen ? height : frameHeight;
            frame.topMargin = fullscreen ? 0 : top;
            FrameLayout.LayoutParams lenses = (FrameLayout.LayoutParams) lensRow.getLayoutParams();
            lenses.height = dp(fullscreen ? 42 : 38);
            lenses.bottomMargin = fullscreen ? controlsHeight + dp(20) : dp(12);
            int overlayTop = (fullscreen ? top : 0) + dp(12);
            ((FrameLayout.LayoutParams) recordingClock.getLayoutParams()).topMargin = overlayTop;
            ((FrameLayout.LayoutParams) notice.getLayoutParams()).topMargin = overlayTop;
            ((FrameLayout.LayoutParams) zoomLabel.getLayoutParams()).topMargin = overlayTop;
            FrameLayout.LayoutParams manual = (FrameLayout.LayoutParams) manualPanel.getLayoutParams();
            manual.bottomMargin = (fullscreen ? controlsHeight : 0) + dp(fullscreen ? 70 : 59);
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    private static int cornerRadius(WindowInsets insets, int position) {
        RoundedCorner corner = insets.getRoundedCorner(position);
        return corner == null ? 0 : corner.getRadius();
    }

    private void installGestures() {
        ScaleGestureDetector scale = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (lens == null || !controlsReady || busy) return false;
                setZoom(currentZoom * detector.getScaleFactor());
                return true;
            }
        });
        preview.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;
            private boolean multiTouch;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                scale.onTouchEvent(event);
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    downX = event.getX();
                    downY = event.getY();
                    multiTouch = false;
                } else if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
                    multiTouch = true;
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP && !multiTouch
                        && controlsReady && !busy && Math.hypot(event.getX() - downX, event.getY() - downY) < dp(18)) {
                    camera.focus(event.getX() / view.getWidth(), event.getY() / view.getHeight());
                    guide.focusAt(event.getX(), event.getY());
                    view.performClick();
                }
                return true;
            }
        });
    }

    private void updateModes() {
        modeRow.removeAllViews();
        TextView selected = null;
        for (Mode mode : MODES) {
            if (captureRequest != null && mode != (captureRequest.video ? Mode.VIDEO : Mode.PHOTO)) continue;
            if (lens == null || !lens.supports(mode)) continue;
            boolean active = currentMode == mode || (mode == Mode.VIDEO && isVideoProfileMode(currentMode));
            TextView chip = label(modeName(mode), 14, MUTED);
            chip.setGravity(Gravity.CENTER);
            chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            chip.setPadding(dp(13), 0, dp(13), 0);
            chip.setSelected(active);
            styleModeChip(chip);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2,
                    dp(isVideoProfileMode(currentMode) ? 49 : 34));
            params.setMarginEnd(dp(3));
            modeRow.addView(chip, params);
            chip.setOnClickListener(view -> {
                if (!canSwitch()) return;
                if (mode == Mode.VIDEO && isVideoProfileMode(currentMode)) return;
                if (mode == Mode.VIDEO) {
                    select(lens, compatibleVideoMode(lens, preferredVideoMode));
                } else if (currentMode != mode) {
                    select(lens, mode);
                }
            });
            if (active) selected = chip;
        }
        if (selected == null) return;
        TextView target = selected;
        modeScroll.post(() -> modeScroll.smoothScrollTo(
                target.getLeft() - (modeScroll.getWidth() - target.getWidth()) / 2, 0));
    }

    private void styleModeChip(TextView chip) {
        boolean active = chip.isSelected();
        if (isVideoProfileMode(currentMode)) {
            chip.setTextColor(Color.WHITE);
            chip.setTypeface(Typeface.create("sans-serif-medium", active ? Typeface.BOLD : Typeface.NORMAL));
            chip.setShadowLayer(dp(3), 0, dp(1), Color.BLACK);
            GradientDrawable marker = shape(active ? VIDEO_RED : Color.TRANSPARENT, 0, 6, 0);
            marker.setBounds(0, 0, dp(8), dp(8));
            chip.setCompoundDrawablePadding(dp(7));
            chip.setCompoundDrawables(null, null, null, marker);
            chip.setBackground(pressable(Color.TRANSPARENT, Color.TRANSPARENT, 16));
        } else {
            chip.setTextColor(active ? BACKGROUND : MUTED);
            chip.setBackground(pressable(active ? accent : Color.TRANSPARENT, Color.TRANSPARENT, 16));
        }
    }

    private void updateLenses() {
        lensRow.removeAllViews();
        if (lens == null) return;
        boolean video = isVideoProfileMode(currentMode);
        for (Lens item : camera.getLenses()) {
            if (item.front != lens.front || !item.supports(video ? Mode.VIDEO : currentMode)) continue;
            boolean active = item.id.equals(lens.id);
            boolean main = video && !item.front && "0".equals(item.id);
            String name = main ? "1×" : video && "超广角".equals(item.label) ? "W" : item.label;
            TextView chip = addLensChip(name, "镜头 " + item.label);
            if (main && active) chip.setTag(1f);
            chip.setSelected(active);
            styleLensChip(chip);
            chip.setOnClickListener(view -> {
                if (!canSwitch()) return;
                if (!active) select(item, modeForLens(item));
                else if (main) setZoom(1f);
            });
        }
        if (video && !lens.front && "0".equals(lens.id)) {
            for (int factor : new int[]{2, 4, 10}) {
                if (factor < lens.minZoom || factor > lens.maxZoom) continue;
                String label = factor + "×";
                TextView chip = addLensChip(label, "变焦 " + label);
                chip.setTag((float) factor);
                chip.setOnClickListener(view -> {
                    if (canSwitch()) setZoom(factor);
                });
            }
            updateZoomChips();
        }
        lensRow.setVisibility(lensRow.getChildCount() > 1 && !recording ? View.VISIBLE : View.GONE);
    }

    private TextView addLensChip(String name, String description) {
        TextView chip = label(name, 12, LIGHT);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(10), 0, dp(10), 0);
        chip.setMinimumWidth(dp(42));
        chip.setContentDescription(description);
        lensRow.addView(chip, new LinearLayout.LayoutParams(-2, dp(32)));
        return chip;
    }

    private void setZoom(float factor) {
        currentZoom = Math.max(lens.minZoom, Math.min(lens.maxZoom, factor));
        camera.setZoom(currentZoom);
        updateZoomChips();
        updateZoomLabel(true);
    }

    private void updateZoomChips() {
        for (int i = 0; i < lensRow.getChildCount(); i++) {
            TextView chip = (TextView) lensRow.getChildAt(i);
            if (chip.getTag() instanceof Float) {
                chip.setSelected(Math.abs((Float) chip.getTag() - currentZoom) < 0.05f);
                styleLensChip(chip);
            }
        }
    }

    private void styleLensChip(TextView chip) {
        boolean active = chip.isSelected();
        boolean video = isVideoProfileMode(currentMode);
        chip.setTextColor(active ? (video ? BACKGROUND : accent) : LIGHT);
        chip.setBackground(pressable(active ? (video ? accent : PANEL) : Color.TRANSPARENT,
                Color.TRANSPARENT, video ? 18 : 13));
    }

    private void switchCamera() {
        if (lens == null || !canSwitch()) return;
        List<Lens> choices = camera.getLenses();
        Lens preferred = null;
        String remembered = lens.front ? lastRear : lastFront;
        for (Lens item : choices) {
            if (item.front == lens.front) continue;
            if (captureRequest != null && captureRequest.video && !item.supports(Mode.VIDEO)) continue;
            if (preferred == null || item.id.equals(remembered)
                    || (item.supports(currentMode) && !preferred.supports(currentMode))) preferred = item;
            if (preferred.id.equals(remembered) && preferred.supports(currentMode)) break;
        }
        if (preferred != null) select(preferred, modeForLens(preferred));
    }

    private Mode modeForLens(Lens target) {
        if (isVideoProfileMode(currentMode) && target.supports(Mode.VIDEO)) {
            return compatibleVideoMode(target, preferredVideoMode);
        }
        return target.supports(currentMode) ? currentMode : Mode.PHOTO;
    }

    private static Mode compatibleVideoMode(Lens target, Mode preferred) {
        if (target.supports(preferred)) return preferred;
        if (preferred == Mode.VIDEO_4K_HIGH_FRAME_RATE && target.supports(Mode.VIDEO_4K)) return Mode.VIDEO_4K;
        return Mode.VIDEO;
    }

    private void select(Lens nextLens, Mode mode) {
        cancelPhotoSequence();
        controlsReady = false;
        selectionPending = true;
        manualPanel.setVisibility(View.GONE);
        guide.hideFocusMark();
        updateEnabled();
        camera.select(nextLens.id, mode);
    }

    private void showVideoSettings(View anchor) {
        if (lens == null || !isVideoProfileMode(currentMode) || recording || !canSwitch()) return;
        PopupMenu popup = new PopupMenu(this, anchor);
        for (Mode profile : VIDEO_PROFILES) {
            if (!lens.supports(profile)) continue;
            popup.getMenu().add(videoProfileLabel(profile)).setCheckable(true).setChecked(profile == currentMode)
                    .setOnMenuItemClickListener(item -> {
                        if (canSwitch()) {
                            preferredVideoMode = profile;
                            getPreferences(MODE_PRIVATE).edit().putString("videoProfile", profile.name()).apply();
                            if (currentMode != profile) select(lens, profile);
                        }
                        return true;
                    });
        }
        popup.show();
    }

    private String videoProfileLabel(Mode profile) {
        String quality = profile == Mode.VIDEO_4K || profile == Mode.VIDEO_4K_HIGH_FRAME_RATE
                ? "4K" : lens.video.getHeight() + "p";
        int fps = profile == Mode.VIDEO_HIGH_FRAME_RATE || profile == Mode.VIDEO_4K_HIGH_FRAME_RATE ? 60 : 30;
        return quality + " | " + fps + " fps";
    }

    private void updateVideoSettings() {
        if (videoSettingsButton == null) return;
        boolean visible = lens != null && isVideoProfileMode(currentMode);
        videoSettingsButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) videoSettingsButton.setText(videoProfileLabel(currentMode));
    }

    private void cycleFlash() {
        if (lens == null || !lens.flash || busy || !controlsReady) return;
        if (isVideo(currentMode)) {
            flash = flash == Flash.TORCH ? Flash.OFF : Flash.TORCH;
        } else {
            switch (flash) {
                case OFF: flash = Flash.AUTO; break;
                case AUTO: flash = Flash.ON; break;
                case ON: flash = Flash.TORCH; break;
                default: flash = Flash.OFF;
            }
        }
        camera.setFlash(flash);
        updateFlash();
    }

    private void updateFlash() {
        String name;
        switch (flash) {
            case AUTO: name = "自动"; break;
            case ON: name = "开启"; break;
            case TORCH: name = "常亮"; break;
            default: name = "关闭";
        }
        flashButton.setText(flash == Flash.OFF ? "闪光" : name);
        flashButton.setTextColor(flash == Flash.OFF ? LIGHT : accent);
        flashButton.setContentDescription("闪光灯" + name);
    }

    private void showOptions(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(showGrid ? "关闭网格" : "开启网格").setOnMenuItemClickListener(item -> {
            showGrid = !showGrid;
            getPreferences(MODE_PRIVATE).edit().putBoolean("grid", showGrid).apply();
            guide.invalidate();
            return true;
        });
        popup.getMenu().add(timerSeconds == 0 ? "定时拍照" : "定时拍照 · " + timerSeconds + " 秒")
                .setOnMenuItemClickListener(item -> {
                    showTimerDialog();
                    return true;
                });
        if (supportsBurst()) {
            String choice = burstCount == 1 ? "单张" : burstCount + " 张";
            popup.getMenu().add("连拍 · " + choice).setOnMenuItemClickListener(item -> {
                showBurstDialog();
                return true;
            });
        }
        popup.show();
    }

    private void showTimerDialog() {
        int[] values = {0, 3, 5, 10};
        int checked = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == timerSeconds) checked = i;
        new AlertDialog.Builder(this).setTitle("定时拍照")
                .setSingleChoiceItems(new String[] {"关闭", "3 秒", "5 秒", "10 秒"}, checked, (dialog, index) -> {
                    timerSeconds = values[index];
                    optionsButton.setText(timerSeconds == 0 ? "设置" : timerSeconds + " 秒");
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null).show();
    }

    private void showBurstDialog() {
        int[] counts = {1, 3, 5};
        int checked = burstCount == 5 ? 2 : burstCount == 3 ? 1 : 0;
        new AlertDialog.Builder(this).setTitle("连拍张数")
                .setSingleChoiceItems(new String[] {"单张拍照", "连拍 3 张", "连拍 5 张"}, checked, (dialog, index) -> {
                    burstCount = counts[index];
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null).show();
    }

    private boolean supportsBurst() {
        return captureRequest == null && (currentMode == Mode.PHOTO || currentMode == Mode.PRO);
    }

    private void toggleTools() {
        if (lens == null) return;
        if (manualPanel.getVisibility() == View.VISIBLE) {
            manualPanel.setVisibility(View.GONE);
            return;
        }
        manualPanel.removeAllViews();
        manualSliders.clear();
        if (lens.minEv < lens.maxEv) {
            exposureLabel = label(evName(exposureCompensation), 12, LIGHT);
            slider(exposureLabel, lens.maxEv - lens.minEv, exposureCompensation - lens.minEv,
                    value -> setEv(lens.minEv + value));
        }
        if (currentMode == Mode.PRO || currentMode == Mode.RAW) loadManualControls();
        if (manualPanel.getChildCount() == 0) {
            showNotice("当前镜头没有可调整的曝光参数");
        } else {
            manualPanel.setVisibility(View.VISIBLE);
        }
    }

    private void setEv(int value) {
        exposureCompensation = value;
        exposureLabel.setText(evName(value));
        camera.setExposure(value);
    }

    private static String evName(int value) {
        return String.format(Locale.ROOT, "曝光补偿 %+d", value);
    }

    private void loadManualControls() {
        if (lens.minIso > 0 && lens.maxIso >= lens.minIso) {
            TextView label = label(iso == 0 ? "ISO 自动" : "ISO " + iso, 12, LIGHT);
            slider(label, 100, logProgress(iso, lens.minIso, lens.maxIso), value -> {
                iso = value == 0 ? 0 : (int) logStep(lens.minIso, lens.maxIso, value);
                label.setText(iso == 0 ? "ISO 自动" : "ISO " + iso);
                submitManual();
            });
        }
        if (lens.minExposure > 0 && lens.maxExposure >= lens.minExposure) {
            TextView label = label(shutterTime(exposureNanos), 12, LIGHT);
            slider(label, 100, logProgress(exposureNanos, lens.minExposure, lens.maxExposure), value -> {
                exposureNanos = value == 0 ? 0 : logStep(lens.minExposure, lens.maxExposure, value);
                label.setText(shutterTime(exposureNanos));
                submitManual();
            });
        }
        if (lens.maxFocus > 0) {
            TextView label = label(focusDistance < 0 ? "对焦 自动" : focusName(focusDistance), 12, LIGHT);
            int focusProgress = focusDistance < 0 ? 0 : 1 + Math.round(focusDistance / lens.maxFocus * 99f);
            slider(label, 100, focusProgress, value -> {
                focusDistance = value == 0 ? -1 : lens.maxFocus * (value - 1) / 99f;
                label.setText(focusDistance < 0 ? "对焦 自动" : focusName(focusDistance));
                submitManual();
            });
        }
        addWhiteBalanceControl();
    }

    private void addWhiteBalanceControl() {
        List<Integer> values = new ArrayList<>();
        for (int mode : lens.whiteBalanceModes) {
            if (mode != CaptureRequest.CONTROL_AWB_MODE_OFF && whiteBalanceName(mode) != null) values.add(mode);
        }
        if (values.size() > 1) {
            TextView choice = label("白平衡 · " + whiteBalanceName(whiteBalance), 12, LIGHT);
            choice.setContentDescription(choice.getText());
            choice.setPadding(dp(7), 0, dp(7), 0);
            choice.setBackground(pressable(PANEL, OUTLINE, 8));
            manualPanel.addView(choice, new LinearLayout.LayoutParams(-1, dp(32)));
            choice.setOnClickListener(view -> {
                PopupMenu menu = new PopupMenu(this, choice);
                for (int value : values) {
                    menu.getMenu().add(whiteBalanceName(value)).setOnMenuItemClickListener(item -> {
                        whiteBalance = value;
                        String selected = "白平衡 · " + whiteBalanceName(value);
                        choice.setText(selected);
                        choice.setContentDescription(selected);
                        submitManual();
                        return true;
                    });
                }
                menu.show();
            });
        }
    }

    private static String whiteBalanceName(int value) {
        switch (value) {
            case AUTO_WHITE_BALANCE:
            case CaptureRequest.CONTROL_AWB_MODE_AUTO: return "自动";
            case CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT: return "白炽灯";
            case CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT: return "荧光灯";
            case CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT: return "暖色荧光灯";
            case CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT: return "日光";
            case CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT: return "阴天";
            case CaptureRequest.CONTROL_AWB_MODE_TWILIGHT: return "暮光";
            case CaptureRequest.CONTROL_AWB_MODE_SHADE: return "阴影";
            default: return null;
        }
    }

    private void submitManual() {
        camera.setManual(iso, exposureNanos, focusDistance, whiteBalance);
    }

    private SeekBar slider(TextView title, int max, int value, SliderAction change) {
        LinearLayout row = horizontal(Gravity.CENTER_VERTICAL);
        manualPanel.addView(row, matchHeight(dp(35)));
        row.addView(title, new LinearLayout.LayoutParams(dp(111), -1));
        SeekBar seek = new SeekBar(this);
        seek.setMax(max);
        seek.setProgress(value);
        seek.setContentDescription(title.getText());
        tintSlider(seek);
        manualSliders.add(seek);
        row.addView(seek, new LinearLayout.LayoutParams(0, -1, 1));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) change.changed(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        return seek;
    }

    private void tintSlider(SeekBar slider) {
        ColorStateList tint = ColorStateList.valueOf(accent);
        slider.setProgressTintList(tint);
        slider.setThumbTintList(tint);
    }

    private static long logStep(long lower, long upper, int progress) {
        return Math.round(lower * Math.pow((double) upper / lower, (progress - 1) / 99.0));
    }

    private static int logProgress(long value, long lower, long upper) {
        if (value <= 0) return 0;
        if (upper == lower) return 1;
        return 1 + (int) Math.round(99 * Math.log((double) value / lower) / Math.log((double) upper / lower));
    }

    private static String shutterTime(long nanos) {
        if (nanos == 0) return "快门 自动";
        if (nanos < 500_000_000) return "快门 1/" + Math.round(1_000_000_000d / nanos) + " 秒";
        return String.format(Locale.ROOT, "快门 %.1f 秒", nanos / 1e9);
    }

    private static String focusName(float diopters) {
        if (diopters <= 0.01f) return "对焦 ∞";
        return String.format(Locale.ROOT, "对焦 %.2f 米", 1f / diopters);
    }

    private void shutter() {
        if (captureResultPending) return;
        if (timer != null) {
            cancelPhotoSequence();
            return;
        }
        if (burstRemaining > 0) {
            cancelPhotoSequence();
            showNotice("已取消剩余连拍");
            return;
        }
        if (recording) {
            captureResultPending = captureRequest != null;
            camera.stopRecording();
            if (captureResultPending) updateEnabled();
            return;
        }
        if (!controlsReady || busy || photoInFlight || awaitingAudioPermission) return;
        if (isVideo(currentMode)) {
            boolean audio = hasVideoAudio(currentMode);
            if (audio && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                awaitingAudioPermission = true;
                requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION);
            } else {
                camera.startRecording(audio);
            }
            return;
        }
        if (timerSeconds == 0) {
            beginPhotoSequence();
            return;
        }
        manualPanel.setVisibility(View.GONE);
        countLabel.setText(String.valueOf(timerSeconds));
        countLabel.setVisibility(View.VISIBLE);
        timer = new CountDownTimer(timerSeconds * 1000L, 80) {
            @Override public void onTick(long remaining) {
                if (timer != this) return;
                countLabel.setText(String.valueOf((remaining + 999) / 1000));
            }

            @Override public void onFinish() {
                if (timer != this) return;
                timer = null;
                countLabel.setVisibility(View.GONE);
                if (controlsReady && !busy && !photoInFlight && resumed) beginPhotoSequence();
                else updateEnabled();
            }
        }.start();
        updateEnabled();
    }

    private void beginPhotoSequence() {
        burstRemaining = supportsBurst() && burstCount > 1 ? burstCount : 0;
        if (burstRemaining > 0) manualPanel.setVisibility(View.GONE);
        takeNextPhoto();
    }

    private void takeNextPhoto() {
        photoInFlight = true;
        photoSaved = false;
        busy = true;
        updateEnabled();
        camera.takePhoto();
    }

    private void advancePhotoSequence() {
        if (!photoInFlight || !photoSaved || busy) return;
        photoInFlight = false;
        photoSaved = false;
        if (burstRemaining == 0) return;
        burstRemaining--;
        if (burstRemaining > 0 && resumed && controlsReady) takeNextPhoto();
        else burstRemaining = 0;
    }

    private void recordAfterAudioPermission() {
        if (pendingAudio == null || !resumed || !controlsReady) return;
        boolean useAudio = pendingAudio;
        pendingAudio = null;
        if (!hasVideoAudio(currentMode) || recording || busy) return;
        if (!useAudio) showNotice("麦克风权限未开启，正在录制静音视频");
        camera.startRecording(useAudio);
    }

    private void cancelPhotoSequence() {
        if (timer != null) timer.cancel();
        timer = null;
        burstRemaining = 0;
        countLabel.setVisibility(View.GONE);
        updateEnabled();
    }

    private void openGallery() {
        if (recording || busy || photoInFlight || timer != null || burstRemaining > 0) return;
        Intent view = new Intent(Intent.ACTION_VIEW);
        if (lastCapture != null) {
            view.setDataAndType(lastCapture, lastCaptureVideo ? "video/mp4" : "image/*");
            view.setClipData(ClipData.newRawUri("Klee Camera", lastCapture));
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            view.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
        }
        try {
            startActivity(view);
        } catch (android.content.ActivityNotFoundException | SecurityException e) {
            showNotice(lastCapture == null ? "相册里还没有拍摄作品" : "请在相册中打开 KleeCamera 相簿");
        }
    }

    private void setGalleryIcon() {
        galleryButton.removeAllViews();
        styleGalleryButton();
        galleryButton.setClipToOutline(true);
        TextView icon = label(lastCapture == null ? "相册" : lastCaptureVideo ? "视频" : "照片", 12, LIGHT);
        icon.setGravity(Gravity.CENTER);
        galleryButton.addView(icon, new FrameLayout.LayoutParams(-1, -1));
        Uri requested = lastCapture;
        if (requested == null || thumbnails.isShutdown()) return;
        thumbnails.execute(() -> {
            try {
                android.graphics.Bitmap thumbnail = getContentResolver().loadThumbnail(requested, new Size(144, 144), null);
                runOnUiThread(() -> {
                    if (isDestroyed() || !requested.equals(lastCapture)) return;
                    ImageView image = new ImageView(this);
                    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    image.setImageBitmap(thumbnail);
                    image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    galleryButton.removeAllViews();
                    galleryButton.addView(image, new FrameLayout.LayoutParams(-1, -1));
                });
            } catch (java.io.IOException | RuntimeException ignored) {
                // Some providers cannot render thumbnails for DNG images.
            }
        });
    }

    private void styleGalleryButton() {
        galleryButton.setBackground(isVideoProfileMode(currentMode)
                ? pressable(VIDEO_CONTROL, VIDEO_CONTROL_BORDER, 26) : pressable(PANEL, OUTLINE, 15));
    }

    @Override
    public void onReady(Lens newLens, Mode mode) {
        runOnUiThread(() -> {
            if (!cameraStarted || !resumed) return;
            if (initialVideoPending) {
                initialVideoPending = false;
                if (newLens.supports(Mode.VIDEO)) {
                    Mode requested = captureRequest == null ? preferredVideoMode : Mode.VIDEO;
                    Mode profile = compatibleVideoMode(newLens, requested);
                    if (profile != mode) {
                        select(newLens, profile);
                        return;
                    }
                }
            }
            if (captureRequest != null && captureRequest.video != isVideo(mode)) {
                finish();
                return;
            }
            if (restoreSelection) {
                restoreSelection = false;
                if (lens != null && (!lens.id.equals(newLens.id) || currentMode != mode)) {
                    select(lens, currentMode);
                    return;
                }
            }
            boolean changed = lens == null || !lens.id.equals(newLens.id) || currentMode != mode;
            lens = newLens;
            preview.setContentDescription("相机预览 · " + newLens.label);
            boolean wasFullscreen = isVideoProfileMode(currentMode);
            boolean videoLayoutChanged = isVideo(currentMode) != isVideo(mode);
            currentMode = mode;
            boolean fullscreen = isVideoProfileMode(mode);
            if (wasFullscreen != fullscreen) cameraLayout.updateVideoChrome(fullscreen);
            else if (videoLayoutChanged) cameraLayout.requestLayout();
            selectionPending = false;
            if (lens.front) lastFront = lens.id;
            else lastRear = lens.id;
            if (changed) {
                currentZoom = Math.max(lens.minZoom, Math.min(lens.maxZoom, 1f));
                flash = Flash.OFF;
                iso = 0;
                exposureCompensation = 0;
                exposureNanos = 0;
                focusDistance = -1f;
                whiteBalance = AUTO_WHITE_BALANCE;
            }
            controlsReady = true;
            previewMessage.setVisibility(View.GONE);
            updateLenses();
            updateModes();
            updateVideoSettings();
            updateFlash();
            updateZoomLabel(false);
            updateEnabled();
            recordAfterAudioPermission();
        });
    }

    @Override
    public void onSaved(Uri uri, boolean video) {
        runOnUiThread(() -> {
            if (captureRequest != null) {
                captureResultPending = true;
                if (!isFinishing() && !isDestroyed()) {
                    setResult(RESULT_OK, captureRequest.resultIntent(uri));
                    finish();
                }
                return;
            }
            lastCapture = uri;
            lastCaptureVideo = video;
            getPreferences(MODE_PRIVATE).edit().putString("recent", uri.toString()).putBoolean("recentVideo", video).apply();
            setGalleryIcon();
            if (resumed) {
                if (video) showNotice("视频已保存");
                else if (burstRemaining > 0) showNotice("连拍已保存 " + (burstCount - burstRemaining + 1) + " / " + burstCount + " 张");
                else showNotice("照片已保存");
            }
            if (!video && photoInFlight) {
                photoSaved = true;
                advancePhotoSequence();
                updateEnabled();
            }
        });
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> {
            captureResultPending = false;
            if (!resumed) return;
            busy = false;
            cancelPhotoSequence();
            if (photoInFlight) {
                retireCamera();
                startCamera();
            } else if (selectionPending && lens != null) {
                selectionPending = false;
                camera.select(lens.id, currentMode);
            } else if (!controlsReady) {
                previewMessage.setText("点击重新打开相机");
                previewMessage.setVisibility(View.VISIBLE);
            }
            showNotice(message);
            updateEnabled();
        });
    }

    @Override
    public void onRecording(boolean active) {
        runOnUiThread(() -> updateRecording(active && resumed));
    }

    @Override
    public void onBusy(boolean active) {
        runOnUiThread(() -> {
            busy = active && resumed;
            advancePhotoSequence();
            updateEnabled();
        });
    }

    private void updateRecording(boolean active) {
        if (recording != active) {
            recording = active;
            if (active) {
                recordingClock.setBase(SystemClock.elapsedRealtime());
                recordingClock.start();
            } else {
                recordingClock.stop();
            }
        }
        recordingClock.setVisibility(active ? View.VISIBLE : View.GONE);
        if (active) manualPanel.setVisibility(View.GONE);
        if (camera != null) updateLenses();
        updateEnabled();
    }

    private void updateZoomLabel(boolean visible) {
        if (lens == null || !visible) {
            zoomLabel.setVisibility(View.GONE);
            return;
        }
        zoomLabel.setText(String.format(Locale.ROOT, "%.1f×", currentZoom));
        zoomLabel.setVisibility(View.VISIBLE);
    }

    private void showNotice(String text) {
        if (text == null || text.isEmpty()) return;
        notice.removeCallbacks(clearNotice);
        notice.setText(text);
        notice.setVisibility(View.VISIBLE);
        notice.postDelayed(clearNotice, 2800);
    }

    private void updateEnabled() {
        if (shutterButton == null) return;
        boolean cancelable = timer != null || burstRemaining > 0;
        boolean capturesIdle = !busy && !photoInFlight && !cancelable && !captureResultPending;
        boolean switchingEnabled = canSwitch();
        for (int i = 0; i < modeRow.getChildCount(); i++) enable(modeRow.getChildAt(i), switchingEnabled);
        for (int i = 0; i < lensRow.getChildCount(); i++) enable(lensRow.getChildAt(i), switchingEnabled);
        enable(flashButton, lens != null && lens.flash && controlsReady && capturesIdle);
        enable(toolsButton, switchingEnabled);
        enable(optionsButton, !recording && capturesIdle);
        enable(videoSettingsButton, switchingEnabled && isVideoProfileMode(currentMode));
        enable(flipButton, switchingEnabled && hasOtherFacing());
        enable(galleryButton, captureRequest == null && !recording && capturesIdle);
        shutterButton.setEnabled(!captureResultPending && (recording || cancelable || (controlsReady && capturesIdle)));
        String shutterLabel = isVideo(currentMode) ? "开始录像" : "拍照";
        if (recording) shutterLabel = "停止录像";
        else if (timer != null) shutterLabel = "取消倒计时";
        else if (burstRemaining > 0) shutterLabel = "取消连拍";
        shutterButton.setContentDescription(shutterLabel);
        shutterButton.invalidate();
    }

    private boolean canSwitch() {
        return controlsReady && !busy && !recording && !photoInFlight && !captureResultPending
                && timer == null && burstRemaining == 0;
    }

    private boolean hasOtherFacing() {
        if (camera == null || lens == null) return false;
        for (Lens option : camera.getLenses()) {
            if (option.front != lens.front && (captureRequest == null || !captureRequest.video || option.supports(Mode.VIDEO))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isVideoProfileMode(Mode mode) {
        return mode == Mode.VIDEO || mode == Mode.VIDEO_HIGH_FRAME_RATE
                || mode == Mode.VIDEO_4K || mode == Mode.VIDEO_4K_HIGH_FRAME_RATE;
    }

    private static void enable(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.36f);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_CAMERA) {
            if (event.getRepeatCount() == 0) shutter();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_CAMERA) return true;
        return super.onKeyUp(keyCode, event);
    }

    private static String modeName(Mode mode) {
        switch (mode) {
            case VIDEO: return "录像";
            case PRO: return "专业";
            case RAW: return "RAW";
            case HDR: return "HDR";
            case NIGHT: return "夜景";
            case LIGHT_TRAILS: return "光轨";
            case PORTRAIT: return "人像场景";
            case HIGH_RES: return "高像素";
            case SLOW_MOTION: return "慢动作";
            case TIME_LAPSE: return "延时";
            case DOCUMENT: return "黑白文档";
            default: return "拍照";
        }
    }

    private TextView label(String text, float size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private TextView overlayLabel(String text, float size) {
        TextView view = label(text, size, LIGHT);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(12), dp(7), dp(12), dp(7));
        view.setBackground(shape(0xdc1c1a17, 0xaa645849, 12, 1));
        return view;
    }

    private TextView headerButton(String text, String description) {
        TextView view = label(text, 12, LIGHT);
        view.setGravity(Gravity.CENTER);
        view.setContentDescription(description);
        view.setBackground(pressable(PANEL, OUTLINE, 13));
        return view;
    }

    private LinearLayout.LayoutParams headerSize() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(49), dp(40));
        params.setMarginStart(dp(7));
        return params;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout horizontal(int gravity) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(gravity);
        return layout;
    }

    private LinearLayout.LayoutParams matchHeight(int height) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
    }

    private FrameLayout.LayoutParams inFrame(int width, int height, int gravity, int margin) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height, gravity);
        params.setMargins(dp(margin), dp(margin), dp(margin), dp(margin));
        return params;
    }

    private GradientDrawable shape(int fill, int border, int radiusDp, int borderDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        if (borderDp > 0) drawable.setStroke(dp(borderDp), border);
        return drawable;
    }

    private RippleDrawable pressable(int fill, int border, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x33ffffff),
                shape(fill, border, radius, border == Color.TRANSPARENT ? 0 : 1), shape(Color.WHITE, 0, radius, 0));
    }

    private int dp(float amount) {
        return Math.round(amount * getResources().getDisplayMetrics().density);
    }

    private interface SliderAction {
        void changed(int value);
    }

    private final class CaptureButton extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();

        CaptureButton() {
            super(MainActivity.this);
            setBackground(new RippleDrawable(ColorStateList.valueOf(0x50ffffff), null,
                    shape(Color.WHITE, 0, 37, 0)));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float centerX = getWidth() / 2f;
            float centerY = getHeight() / 2f;
            boolean videoMode = isVideo(currentMode);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(isEnabled() ? LIGHT : OUTLINE);
            canvas.drawCircle(centerX, centerY, dp(33), paint);
            paint.setStyle(Paint.Style.FILL);
            boolean fullscreen = isVideoProfileMode(currentMode);
            if (fullscreen) {
                paint.setColor(isEnabled() ? Color.WHITE : OUTLINE);
                canvas.drawCircle(centerX, centerY, dp(26), paint);
            }
            paint.setColor(isEnabled() ? (fullscreen ? VIDEO_RED : videoMode ? RECORD : accent) : MUTED);
            if (recording || timer != null || burstRemaining > 0) {
                float half = dp(14);
                bounds.set(centerX - half, centerY - half, centerX + half, centerY + half);
                canvas.drawRoundRect(bounds, dp(4), dp(4), paint);
            } else {
                canvas.drawCircle(centerX, centerY, dp(fullscreen ? 13 : 27), paint);
            }
        }

        @Override
        public CharSequence getAccessibilityClassName() {
            return android.widget.Button.class.getName();
        }
    }

    private final class Guide extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path focusMark = new Path();
        private float focusX = -1;
        private float focusY = -1;
        private final Runnable clear = this::hideFocusMark;

        Guide() {
            super(MainActivity.this);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void focusAt(float x, float y) {
            focusX = Math.max(dp(26), Math.min(getWidth() - dp(26), x));
            focusY = Math.max(dp(26), Math.min(getHeight() - dp(26), y));
            removeCallbacks(clear);
            postDelayed(clear, 1200);
            invalidate();
        }

        void hideFocusMark() {
            focusX = -1;
            removeCallbacks(clear);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (showGrid) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1));
                paint.setColor(0x66ffffff);
                for (int i = 1; i <= 2; i++) {
                    float x = getWidth() * i / 3f;
                    float y = getHeight() * i / 3f;
                    canvas.drawLine(x, 0, x, getHeight(), paint);
                    canvas.drawLine(0, y, getWidth(), y, paint);
                }
            }
            if (focusX < 0) return;
            focusMark.reset();
            int edge = dp(23);
            int arm = dp(10);
            for (int horizontal : new int[] {-1, 1}) {
                for (int vertical : new int[] {-1, 1}) {
                    float x = focusX + edge * horizontal;
                    float y = focusY + edge * vertical;
                    focusMark.moveTo(x - arm * horizontal, y);
                    focusMark.lineTo(x, y);
                    focusMark.lineTo(x, y - arm * vertical);
                }
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(4));
            paint.setColor(BACKGROUND);
            canvas.drawPath(focusMark, paint);
            paint.setStrokeWidth(dp(2));
            paint.setColor(accent);
            canvas.drawPath(focusMark, paint);
        }
    }
}
