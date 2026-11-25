package org.kleeui.camera;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Feeds microphone PCM into an AAC encoder from the video capture worker. */
final class AacInput implements AutoCloseable {
    private static final int RATE = 48_000;
    private static final int SAMPLE_BYTES = Short.BYTES;
    private static final int CHUNK_BYTES = 1024 * SAMPLE_BYTES;

    final MediaCodec codec;
    private final AudioRecord microphone;
    private final ByteBuffer pcm = ByteBuffer.allocateDirect(CHUNK_BYTES);
    private long sampleCount;
    private int pendingBytes;
    private boolean capturing;

    AacInput() throws IOException {
        int minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) throw new IOException("Microphone PCM format is unavailable");
        microphone = new AudioRecord(MediaRecorder.AudioSource.CAMCORDER, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minimum * 2, CHUNK_BYTES * 8));
        MediaCodec aac = null;
        try {
            if (microphone.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IOException("Could not open microphone");
            }
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 96_000);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_BYTES);
            aac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            aac.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec = aac;
        } catch (IOException | RuntimeException e) {
            if (aac != null) aac.release();
            microphone.release();
            throw e;
        }
    }

    void startCapture() {
        microphone.startRecording();
        capturing = microphone.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING;
        if (!capturing) throw new IllegalStateException("Could not start microphone");
    }

    boolean captured() {
        return sampleCount > 0;
    }

    boolean feed() {
        if (pendingBytes == 0) {
            pcm.clear();
            int read = microphone.read(pcm, CHUNK_BYTES, AudioRecord.READ_NON_BLOCKING);
            if (read < 0) throw new IllegalStateException("Microphone read failed: " + read);
            pendingBytes = read;
            if (pendingBytes == 0) return false;
            if (pendingBytes % SAMPLE_BYTES != 0) throw new IllegalStateException("Unaligned PCM data");
        }
        int index = codec.dequeueInputBuffer(0);
        if (index < 0) return false;
        ByteBuffer input = codec.getInputBuffer(index);
        if (input == null || input.capacity() < pendingBytes) {
            throw new IllegalStateException("AAC input buffer is unavailable");
        }
        input.clear();
        pcm.position(0);
        pcm.limit(pendingBytes);
        input.put(pcm);
        codec.queueInputBuffer(index, 0, pendingBytes, timestampUs(), 0);
        sampleCount += pendingBytes / SAMPLE_BYTES;
        pendingBytes = 0;
        return true;
    }

    void stopCapture() {
        if (capturing) {
            capturing = false;
            microphone.stop();
        }
    }

    long timestampUs() {
        return sampleCount * 1_000_000L / RATE;
    }

    @Override
    public void close() {
        try {
            stopCapture();
        } finally {
            try {
                microphone.release();
            } finally {
                codec.release();
            }
        }
    }
}
