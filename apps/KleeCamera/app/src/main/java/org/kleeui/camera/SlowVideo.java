package org.kleeui.camera;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Encodes a Camera2 high-speed surface and stretches its capture timestamps to 30 fps. */
final class SlowVideo implements AutoCloseable {
    private static final String TAG = "KleeSlowVideo";
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final int PLAYBACK_FPS = 30;
    private static final long STOP_TIMEOUT_NS = 3_000_000_000L;

    private final MediaCodec codec;
    private final MediaMuxer muxer;
    private final Surface surface;
    private final int captureFps;
    private final MediaCodec.BufferInfo buffer = new MediaCodec.BufferInfo();
    private int track = -1;
    private boolean muxerStarted;
    private boolean started;
    private boolean stopped;
    private boolean closed;
    private long firstSourceUs = -1;
    private long lastPlaybackUs = -1;
    private int frameCount;

    SlowVideo(File output, Size size, int captureFps, int orientation) throws IOException {
        if (size == null || captureFps <= PLAYBACK_FPS) {
            throw new IllegalArgumentException("Invalid high-speed video configuration");
        }
        this.captureFps = captureFps;
        codec = openEncoder(size, captureFps);
        Surface input = null;
        MediaMuxer writer = null;
        try {
            input = codec.createInputSurface();
            writer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            writer.setOrientationHint(orientation);
            muxer = writer;
            surface = input;
        } catch (IOException | RuntimeException error) {
            if (writer != null) writer.release();
            if (input != null) input.release();
            codec.release();
            throw error;
        }
    }

    static boolean supports(Size size, int captureFps) {
        return size != null && captureFps > PLAYBACK_FPS && !hardwareEncoders(size, captureFps).isEmpty();
    }

    private static List<MediaCodecInfo> hardwareEncoders(Size size, int captureFps) {
        List<MediaCodecInfo> matches = new ArrayList<>();
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder() || !info.isHardwareAccelerated() || info.isAlias()) continue;
            try {
                MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(MIME);
                if (!caps.getVideoCapabilities().areSizeAndRateSupported(size.getWidth(), size.getHeight(), captureFps)) {
                    continue;
                }
                for (int colorFormat : caps.colorFormats) {
                    if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) {
                        matches.add(info);
                        break;
                    }
                }
            } catch (IllegalArgumentException ignored) {
                // The encoder does not implement AVC.
            }
        }
        return matches;
    }

    private static MediaCodec openEncoder(Size size, int captureFps) throws IOException {
        IOException failure = new IOException("No hardware AVC encoder accepted the high-speed surface");
        for (MediaCodecInfo info : hardwareEncoders(size, captureFps)) {
            MediaCodec candidate = null;
            try {
                int width = size.getWidth();
                int height = size.getHeight();
                MediaCodecInfo.VideoCapabilities caps = info.getCapabilitiesForType(MIME).getVideoCapabilities();
                MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                // The Qualcomm encoder drops high-speed input when configured at the playback rate.
                format.setInteger(MediaFormat.KEY_FRAME_RATE, captureFps);
                format.setInteger(MediaFormat.KEY_OPERATING_RATE, captureFps);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
                int bitrate = (int) Math.min(70_000_000, (double) width * height * captureFps * 0.12);
                format.setInteger(MediaFormat.KEY_BIT_RATE, caps.getBitrateRange().clamp(bitrate));
                candidate = MediaCodec.createByCodecName(info.getName());
                candidate.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                Log.i(TAG, "Using " + info.getName() + " at " + size + " / " + captureFps + " fps");
                return candidate;
            } catch (IOException | IllegalArgumentException | IllegalStateException error) {
                if (candidate != null) candidate.release();
                failure.addSuppressed(error);
            }
        }
        throw failure;
    }

    Surface surface() {
        return surface;
    }

    void start() {
        if (closed || started) throw new IllegalStateException("High-speed encoder is unavailable");
        codec.start();
        started = true;
    }

    void pump() {
        if (started && !stopped && !closed) drain(false, 0);
    }

    private void drain(boolean finishing, long deadline) {
        do {
            int index = codec.dequeueOutputBuffer(buffer, finishing ? 10_000 : 0);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!finishing) return;
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) throw new IllegalStateException("High-speed AVC output format changed twice");
                MediaFormat format = codec.getOutputFormat();
                format.setInteger(MediaFormat.KEY_FRAME_RATE, PLAYBACK_FPS);
                track = muxer.addTrack(format);
                muxer.start();
                muxerStarted = true;
            } else if (index >= 0) {
                boolean eos = (buffer.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                try {
                    if (buffer.size > 0 && (buffer.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        write(index);
                    }
                } finally {
                    codec.releaseOutputBuffer(index, false);
                }
                if (eos) return;
            }
        } while (!finishing || System.nanoTime() < deadline);
        throw new IllegalStateException("Timed out finalizing high-speed video");
    }

    private void write(int index) {
        ByteBuffer encoded = codec.getOutputBuffer(index);
        if (encoded == null || !muxerStarted) throw new IllegalStateException("High-speed AVC track is unavailable");
        long timestampUs = buffer.presentationTimeUs;
        if (frameCount == 0) firstSourceUs = timestampUs;
        long playbackUs = Math.round((timestampUs - firstSourceUs) * ((double) captureFps / PLAYBACK_FPS));
        if (playbackUs <= lastPlaybackUs) {
            throw new IllegalStateException("High-speed encoder returned non-monotonic timestamps");
        }
        buffer.presentationTimeUs = playbackUs;
        muxer.writeSampleData(track, encoded, buffer);
        lastPlaybackUs = playbackUs;
        frameCount++;
    }

    void stop() {
        if (!started || stopped || closed) return;
        stopped = true;
        codec.signalEndOfInputStream();
        drain(true, System.nanoTime() + STOP_TIMEOUT_NS);
        if (frameCount < 2) throw new IllegalStateException("High-speed capture produced too few frames");
        muxerStarted = false;
        muxer.stop();
        Log.i(TAG, "Saved " + frameCount + " frames over " + lastPlaybackUs / 1000 + " ms");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            codec.release();
        } finally {
            surface.release();
            try {
                if (muxerStarted) muxer.stop();
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not finalize aborted high-speed clip", error);
            } finally {
                muxer.release();
            }
        }
    }
}
