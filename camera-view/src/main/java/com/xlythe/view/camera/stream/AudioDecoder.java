package com.xlythe.view.camera.stream;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.annotation.WorkerThread;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Turns frames of one compressed format back into PCM.
 *
 * <p>Used from the player's thread alone. The PCM may not be at the rate the stream was recorded
 * at - Opus always decodes at 48kHz - so the shape is reported from what the decoder says it
 * produces, and the player plays whatever that is.</p>
 */
@RequiresApi(16)
final class AudioDecoder {
    /** How long to wait for an input buffer at a time, collecting output in between. */
    private static final long INPUT_TIMEOUT_US = 10_000;

    /**
     * How many waits before a frame is given up on: half a second, long enough for a decoder that
     * is still starting. A frame lost here is a gap in the audio, which the player cannot repair.
     */
    private static final int INPUT_ATTEMPTS = 50;

    /** Drains whatever output is ready, without waiting for more. */
    private static final long OUTPUT_TIMEOUT_US = 0;

    /** Where decoded audio goes. */
    interface Sink {
        /** The shape of the PCM that follows. Called before the first PCM and on any change. */
        @WorkerThread
        void onFormat(int sampleRate, int channelCount);

        /** Decoded 16-bit PCM. */
        @WorkerThread
        void onPcm(@NonNull byte[] pcm, int length) throws IOException;
    }

    /** The platform decoder. */
    private final MediaCodec mCodecInstance;

    /** Reused for every output buffer. */
    private final MediaCodec.BufferInfo mInfo = new MediaCodec.BufferInfo();

    /** Reused for output, grown when a buffer is larger than any before it. */
    private byte[] mPcm = new byte[0];

    /** Whether the sink has been told the shape of the PCM yet. */
    private boolean mFormatReported;

    /** Frames queued, which numbers them in order; the decoder needs nothing more of a time. */
    private long mFramesQueued;

    /** The rate to report if the decoder never says, which is what the format decodes at. */
    private final int mDefaultSampleRate;

    /** The channels to report if the decoder never says. */
    private final int mDefaultChannelCount;

    /**
     * Starts a decoder for a stream described by its header.
     *
     * @throws IOException if the device has no decoder for the format, or it refuses this shape
     */
    @WorkerThread
    AudioDecoder(@AudioCodec.Codec int codec, int sampleRate, int channelCount,
            @NonNull byte[] config) throws IOException {
        String mimeType = Objects.requireNonNull(AudioCodec.mimeTypeOf(codec),
                "PCM is played as it is, not through a decoder");
        mDefaultSampleRate = codec == AudioCodec.OPUS ? AudioCodec.OPUS_DECODE_RATE : sampleRate;
        mDefaultChannelCount = channelCount;
        byte[] csd = config.length > 0
                ? config : AudioCodec.defaultConfig(codec, sampleRate, channelCount);
        MediaFormat format = MediaFormat.createAudioFormat(mimeType, sampleRate, channelCount);
        ByteBuffer[] buffers = AudioCodec.decoderConfig(codec, csd);
        for (int i = 0; i < buffers.length; i++) {
            format.setByteBuffer("csd-" + i, buffers[i]);
        }
        MediaCodec decoder = MediaCodec.createDecoderByType(mimeType);
        try {
            decoder.configure(format, null, null, 0);
            decoder.start();
        } catch (RuntimeException e) {
            decoder.release();
            throw new IOException("The " + mimeType + " decoder refused " + format, e);
        }
        mCodecInstance = decoder;
    }

    /** Decodes one frame and hands on whatever PCM comes out. */
    @WorkerThread
    void decode(@NonNull byte[] frame, @NonNull Sink sink) throws IOException {
        try {
            int index = -1;
            for (int attempt = 0; index < 0 && attempt < INPUT_ATTEMPTS; attempt++) {
                index = mCodecInstance.dequeueInputBuffer(INPUT_TIMEOUT_US);
                if (index < 0) {
                    // Output is what frees input.
                    drain(sink);
                }
            }
            if (index < 0) {
                // Losing a frame is a few milliseconds of audio, which beats waiting on a decoder
                // that has stalled while the stream keeps arriving.
                return;
            }
            ByteBuffer input = inputBuffer(index);
            input.clear();
            int length = Math.min(frame.length, input.remaining());
            input.put(frame, 0, length);
            mCodecInstance.queueInputBuffer(index, 0, length, mFramesQueued++, 0);
            drain(sink);
        } catch (IllegalStateException e) {
            throw new IOException("The audio decoder failed", e);
        }
    }

    @WorkerThread
    private void drain(@NonNull Sink sink) throws IOException {
        while (true) {
            int index = mCodecInstance.dequeueOutputBuffer(mInfo, OUTPUT_TIMEOUT_US);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return;
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                reportFormat(mCodecInstance.getOutputFormat(), sink);
                continue;
            }
            if (index < 0) {
                continue;
            }
            ByteBuffer output = outputBuffer(index);
            int size = mInfo.size;
            if (size > mPcm.length) {
                mPcm = new byte[size];
            }
            output.position(mInfo.offset);
            output.limit(mInfo.offset + size);
            output.get(mPcm, 0, size);
            mCodecInstance.releaseOutputBuffer(index, false);
            if (size == 0) {
                continue;
            }
            if (!mFormatReported) {
                reportFormat(mCodecInstance.getOutputFormat(), sink);
            }
            sink.onPcm(mPcm, size);
        }
    }

    private void reportFormat(@NonNull MediaFormat format, @NonNull Sink sink) {
        mFormatReported = true;
        sink.onFormat(
                format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                        ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : mDefaultSampleRate,
                format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                        ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : mDefaultChannelCount);
    }

    private ByteBuffer inputBuffer(int index) {
        if (Build.VERSION.SDK_INT >= 21) {
            return Objects.requireNonNull(mCodecInstance.getInputBuffer(index));
        }
        return mCodecInstance.getInputBuffers()[index];
    }

    private ByteBuffer outputBuffer(int index) {
        if (Build.VERSION.SDK_INT >= 21) {
            return Objects.requireNonNull(mCodecInstance.getOutputBuffer(index));
        }
        return mCodecInstance.getOutputBuffers()[index];
    }

    /** Stops and releases the decoder. */
    @WorkerThread
    void release() {
        try {
            mCodecInstance.stop();
        } catch (IllegalStateException e) {
            // Already stopped by a failure; releasing is all that is left to do.
        }
        mCodecInstance.release();
    }
}
