package org.kleeui.camera;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;
import android.util.Size;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Encodes camera frames from CPU-readable YUV planes into an MP4. */
final class YuvVideo implements AutoCloseable {
    private static final String TAG = "KleeYuvVideo";
    private static final int HIGH_FRAME_RATE = 60;
    private static final int YUV420 = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;
    private static final long DRAIN_TIMEOUT_NS = 3_000_000_000L;

    private final MediaCodec encoder;
    private final MediaMuxer muxer;
    private final Size size;
    private final AacInput audio;
    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
    private final List<PendingSample> pending = new ArrayList<>();
    private byte[] sourceRow = new byte[0];
    private byte[] targetRow = new byte[0];
    private int videoTrack = -1;
    private int audioTrack = -1;
    private int videoSamples;
    private int audioSamples;
    private int unavailableInputBuffers;
    private int pendingBytes;
    private long firstTimestamp = -1;
    private boolean started;
    private boolean stopped;
    private boolean closed;
    private boolean muxerStarted;
    private long lastTimestamp = -1;
    private long audioOffsetUs = -1;

    YuvVideo(File output, Size size, int fps) throws IOException {
        this(output, size, fps, 0);
    }

    YuvVideo(File output, Size size, int fps, int orientation) throws IOException {
        this(output, size, fps, orientation, false);
    }

    YuvVideo(File output, Size size, int fps, int orientation, boolean withAudio) throws IOException {
        if (size.getWidth() <= 0 || size.getHeight() <= 0 || fps <= 0
                || (size.getWidth() & 1) != 0 || (size.getHeight() & 1) != 0) {
            throw new IllegalArgumentException("Invalid YUV video size or frame rate");
        }
        this.size = size;
        encoder = configureEncoder(inputFormat(size, fps), fps != HIGH_FRAME_RATE && size.getWidth() <= 1920);
        MediaMuxer writer = null;
        try {
            writer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            writer.setOrientationHint(orientation);
            audio = withAudio ? new AacInput() : null;
            muxer = writer;
        } catch (IOException | RuntimeException error) {
            if (writer != null) writer.release();
            encoder.release();
            throw error;
        }
    }

    static boolean supports(Size size, int fps) {
        MediaFormat format = inputFormat(size, fps);
        for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (acceptsFormat(codec, format)) return true;
        }
        return false;
    }

    private static MediaFormat inputFormat(Size size, int fps) {
        String mime = fps == HIGH_FRAME_RATE ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
        MediaFormat format = MediaFormat.createVideoFormat(mime, size.getWidth(), size.getHeight());
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, YUV420);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        if (fps == HIGH_FRAME_RATE) {
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, fps);
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
            format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
        }
        double pixelsPerSecond = (double) size.getWidth() * size.getHeight() * fps;
        format.setInteger(MediaFormat.KEY_BIT_RATE, (int) Math.max(2_000_000, pixelsPerSecond * 0.18));
        return format;
    }

    private static boolean acceptsFormat(MediaCodecInfo codec, MediaFormat format) {
        if (!codec.isEncoder() || codec.isAlias()) return false;
        String mime = format.getString(MediaFormat.KEY_MIME);
        if (MediaFormat.MIMETYPE_VIDEO_HEVC.equals(mime) && !codec.isHardwareAccelerated()) return false;
        try {
            return codec.getCapabilitiesForType(mime).isFormatSupported(format);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static MediaCodec configureEncoder(MediaFormat format, boolean preferSoftware) throws IOException {
        String mime = format.getString(MediaFormat.KEY_MIME);
        IOException failures = new IOException("No available " + mime + " encoder accepted YUV420 input");
        MediaCodecInfo[] codecs = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
        for (boolean software : new boolean[]{preferSoftware, !preferSoftware}) {
            for (MediaCodecInfo codec : codecs) {
                if (codec.isSoftwareOnly() != software || !acceptsFormat(codec, format)) continue;
                MediaCodec instance = null;
                try {
                    instance = MediaCodec.createByCodecName(codec.getName());
                    instance.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    Log.i(TAG, "Using " + codec.getName() + " for " + mime + " YUV encoding");
                    return instance;
                } catch (IOException | IllegalArgumentException | IllegalStateException error) {
                    if (instance != null) instance.release();
                    failures.addSuppressed(error);
                }
            }
        }
        throw failures;
    }

    void start() {
        if (started || stopped || closed) throw new IllegalStateException("Video encoder is unavailable");
        encoder.start();
        if (audio != null) audio.codec.start();
        started = true;
    }

    void frame(Image image, long presentationUs) {
        if (!started || stopped || closed) return;
        drain(encoder, true, false, 0);
        pumpAudio();
        if (presentationUs < 0 || presentationUs <= lastTimestamp) return;
        int input = encoder.dequeueInputBuffer(4_000);
        if (input < 0) {
            unavailableInputBuffers++;
            return;
        }

        Image target = encoder.getInputImage(input);
        if (target == null) throw new IllegalStateException("Encoder did not expose a writable YUV image");
        int width = size.getWidth();
        int height = size.getHeight();
        Rect from = image.getCropRect();
        Rect to = target.getCropRect();
        if (image.getFormat() != ImageFormat.YUV_420_888 || target.getFormat() != ImageFormat.YUV_420_888
                || from.width() < width || from.height() < height || to.width() < width || to.height() < height) {
            throw new IllegalArgumentException("Unexpected YUV input or encoder crop");
        }
        Image.Plane[] sources = image.getPlanes();
        Image.Plane[] destinations = target.getPlanes();
        for (int plane = 0; plane < 3; plane++) {
            int shift = plane == 0 ? 0 : 1;
            copyPlane(sources[plane], destinations[plane], from.left >> shift, from.top >> shift,
                    to.left >> shift, to.top >> shift, width >> shift, height >> shift);
        }
        // The codec owns target and invalidates it when the input index is queued.
        encoder.queueInputBuffer(input, 0, width * height * 3 / 2, presentationUs, 0);
        if (firstTimestamp < 0) firstTimestamp = presentationUs;
        lastTimestamp = presentationUs;
        if (audio != null && audioOffsetUs < 0) {
            audio.startCapture();
            audioOffsetUs = presentationUs;
        }
        drain(encoder, true, false, 0);
    }

    private void copyPlane(Image.Plane source, Image.Plane destination, int sourceX, int sourceY,
                           int targetX, int targetY, int width, int height) {
        ByteBuffer read = source.getBuffer().duplicate();
        ByteBuffer write = destination.getBuffer().duplicate();
        int readStride = source.getPixelStride();
        int writeStride = destination.getPixelStride();
        int sourceRowStride = source.getRowStride();
        int targetRowStride = destination.getRowStride();
        int readStart = read.position() + sourceY * sourceRowStride + sourceX * readStride;
        int writeStart = write.position() + targetY * targetRowStride + targetX * writeStride;
        if (readStride == 1 && writeStride == 1) {
            copyRows(read, write, readStart, writeStart, sourceRowStride, targetRowStride, width, height);
            return;
        }
        int sourceLength = (width - 1) * readStride + 1;
        int targetLength = (width - 1) * writeStride + 1;
        if (sourceRow.length < sourceLength) sourceRow = new byte[sourceLength];
        if (targetRow.length < targetLength) targetRow = new byte[targetLength];
        for (int y = 0; y < height; y++) {
            read.position(readStart + y * sourceRowStride);
            read.get(sourceRow, 0, sourceLength);
            int targetOffset = writeStart + y * targetRowStride;
            write.position(targetOffset);
            // Interleaved chroma planes may share the same memory.
            if (writeStride > 1) {
                write.get(targetRow, 0, targetLength);
                write.position(targetOffset);
            }
            for (int x = 0; x < width; x++) {
                targetRow[x * writeStride] = sourceRow[x * readStride];
            }
            write.put(targetRow, 0, targetLength);
        }
    }

    private static void copyRows(ByteBuffer source, ByteBuffer target, int sourceStart, int targetStart,
                                 int sourceStride, int targetStride, int width, int height) {
        int sourceEnd = sourceStart + (height - 1) * sourceStride + width;
        if (sourceEnd > source.limit()) throw new IllegalArgumentException("YUV plane has insufficient data");
        if (width == sourceStride && width == targetStride) {
            source.limit(sourceEnd).position(sourceStart);
            target.position(targetStart);
            target.put(source);
            return;
        }
        for (int row = 0; row < height; row++) {
            int offset = sourceStart + row * sourceStride;
            source.limit(offset + width).position(offset);
            target.position(targetStart + row * targetStride);
            target.put(source);
        }
    }

    private void pumpAudio() {
        if (audio == null || audioOffsetUs < 0) return;
        drain(audio.codec, false, false, 0);
        // A bounded nonblocking loop drains PCM already buffered by AudioRecord.
        for (int i = 0; i < 16 && audio.feed(); i++) {
            drain(audio.codec, false, false, 0);
        }
    }

    private void drain(MediaCodec codec, boolean video, boolean finishing, long deadline) {
        do {
            int index = codec.dequeueOutputBuffer(bufferInfo, finishing ? 10_000 : 0);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!finishing) return;
                continue;
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (video ? videoTrack >= 0 : audioTrack >= 0) {
                    throw new IllegalStateException("Encoder output format changed twice");
                }
                int track = muxer.addTrack(codec.getOutputFormat());
                if (video) videoTrack = track;
                else audioTrack = track;
                startMuxerIfReady();
            } else if (index >= 0) {
                boolean eos = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                try {
                    if (bufferInfo.size > 0 && (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        ByteBuffer output = codec.getOutputBuffer(index);
                        int track = video ? videoTrack : audioTrack;
                        if (output == null || track < 0) throw new IllegalStateException("Encoded track is unavailable");
                        if (!video) bufferInfo.presentationTimeUs += audioOffsetUs;
                        saveSample(track, output);
                        if (video) videoSamples++;
                        else audioSamples++;
                    }
                } finally {
                    codec.releaseOutputBuffer(index, false);
                }
                if (eos) return;
            }
        } while (!finishing || System.nanoTime() < deadline);
        throw new IllegalStateException("Timed out while finalizing an encoded track");
    }

    private void startMuxerIfReady() {
        if (videoTrack < 0 || (audio != null && audioTrack < 0)) return;
        muxer.start();
        muxerStarted = true;
        for (PendingSample sample : pending) {
            muxer.writeSampleData(sample.track, ByteBuffer.wrap(sample.data), sample.info);
        }
        pending.clear();
        pendingBytes = 0;
    }

    private void saveSample(int track, ByteBuffer output) {
        output.position(bufferInfo.offset);
        output.limit(bufferInfo.offset + bufferInfo.size);
        if (muxerStarted) {
            muxer.writeSampleData(track, output, bufferInfo);
            return;
        }

        pendingBytes += bufferInfo.size;
        if (pendingBytes > 8 * 1024 * 1024) throw new IllegalStateException("Recorder tracks did not initialize");
        pending.add(new PendingSample(track, output, bufferInfo));
    }

    void stop() {
        if (!started || stopped || closed) return;
        stopped = true;
        if (audio != null && audioOffsetUs >= 0) {
            pumpAudio();
            audio.stopCapture();
        }
        long deadline = System.nanoTime() + DRAIN_TIMEOUT_NS;
        queueEnd(encoder, true, lastTimestamp + 1, deadline);
        if (audio != null) {
            if (!audio.captured()) throw new IllegalStateException("Microphone produced no audio");
            queueEnd(audio.codec, false, audio.timestampUs(), deadline);
        }
        drain(encoder, true, true, deadline);
        if (audio != null) drain(audio.codec, false, true, deadline);
        if (!muxerStarted || videoSamples == 0 || (audio != null && audioSamples == 0)) {
            throw new IllegalStateException("Recording is missing an encoded track");
        }
        muxerStarted = false;
        muxer.stop();
        Log.i(TAG, "Saved " + size + " samples=" + videoSamples + " durationUs="
                + (lastTimestamp - firstTimestamp) + " audioSamples=" + audioSamples
                + " unavailableInputBuffers=" + unavailableInputBuffers);
    }

    private void queueEnd(MediaCodec codec, boolean video, long timestamp, long deadline) {
        int input;
        do {
            drain(codec, video, false, 0);
            input = codec.dequeueInputBuffer(10_000);
        } while (input < 0 && System.nanoTime() < deadline);
        if (input < 0) throw new IllegalStateException("Timed out while finishing an encoded track");
        codec.queueInputBuffer(input, 0, 0, timestamp, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            try {
                if (audio != null) audio.close();
            } finally {
                encoder.release();
            }
        } finally {
            try {
                if (muxerStarted) muxer.stop();
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not finish aborted video", error);
            } finally {
                muxer.release();
            }
        }
    }

    private static final class PendingSample {
        final int track;
        final byte[] data;
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        PendingSample(int track, ByteBuffer buffer, MediaCodec.BufferInfo format) {
            this.track = track;
            data = new byte[format.size];
            buffer.get(data);
            info.set(0, data.length, format.presentationTimeUs, format.flags);
        }
    }
}
