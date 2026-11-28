package com.xlythe.view.camera.stream;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.annotation.WorkerThread;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Compresses PCM into frames of one format, one MediaCodec at a time.
 *
 * <p>Used from the recorder's thread alone. A change of bit rate is a new encoder rather than a
 * parameter on this one: the platform's audio encoders read their bit rate when they start, and
 * one that ignored a change would leave the stream at the rate that was congesting it. Audio frames
 * stand alone and the decoder's configuration depends only on the rate and channels, so the
 * receiver never notices the swap.</p>
 */
@RequiresApi(16)
final class AudioEncoder {
    /** How long to wait for an input buffer at a time, collecting output in between. */
    private static final long INPUT_TIMEOUT_US = 10_000;

    /**
     * How many waits before the rest of a chunk is given up on. Half a second: an encoder that
     * is still starting, or working through a burst, gets that long, and only one that has
     * stalled loses audio.
     */
    private static final int INPUT_ATTEMPTS = 50;

    /** Drains whatever output is ready, without waiting for more. */
    private static final long OUTPUT_TIMEOUT_US = 0;

    /** Large enough for any chunk AudioRecord hands over at 48kHz stereo. */
    private static final int MAX_INPUT_BYTES = 64 * 1024;

    /** 16-bit PCM. */
    private static final int BYTES_PER_SAMPLE = 2;

    /** Where encoded output goes. */
    interface Sink {
        /** The codec's configuration, which arrives before its first frame. */
        @WorkerThread
        void onConfig(@NonNull byte[] config) throws IOException;

        /** One encoded frame. */
        @WorkerThread
        void onFrame(@NonNull byte[] frame) throws IOException;
    }

    /** The format produced. */
    @AudioCodec.Codec private final int mCodec;

    /** Samples a second of the PCM fed in. */
    private final int mSampleRate;

    /** Interleaved channels of the PCM fed in. */
    private final int mChannelCount;

    /** The bit rate the encoder was started at. */
    private final int mBitRate;

    /** The platform encoder. */
    private final MediaCodec mCodecInstance;

    /** Reused for every output buffer. */
    private final MediaCodec.BufferInfo mInfo = new MediaCodec.BufferInfo();

    /** Frames of PCM fed so far, which is the clock the presentation times run on. */
    private long mFramesFed;

    /**
     * Starts an encoder.
     *
     * @throws IOException if the device has no encoder for the format, or it refuses this shape
     */
    @WorkerThread
    AudioEncoder(@AudioCodec.Codec int codec, int sampleRate, int channelCount, int bitRate)
            throws IOException {
        mCodec = codec;
        mSampleRate = sampleRate;
        mChannelCount = channelCount;
        mBitRate = bitRate;
        String mimeType = Objects.requireNonNull(AudioCodec.mimeTypeOf(codec),
                "PCM is written as it is, not through an encoder");
        MediaFormat format = MediaFormat.createAudioFormat(mimeType, sampleRate, channelCount);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES);
        if (codec == AudioCodec.AAC) {
            format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        }
        MediaCodec encoder = MediaCodec.createEncoderByType(mimeType);
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
        } catch (RuntimeException e) {
            encoder.release();
            throw new IOException("The " + mimeType + " encoder refused " + format, e);
        }
        mCodecInstance = encoder;
    }

    @AudioCodec.Codec
    int getCodec() {
        return mCodec;
    }

    int getBitRate() {
        return mBitRate;
    }

    /**
     * Feeds a chunk of PCM and hands on whatever frames come out. An empty chunk still collects
     * frames the encoder finished since the last one.
     */
    @WorkerThread
    void encode(@NonNull byte[] pcm, int length, @NonNull Sink sink) throws IOException {
        try {
            if (length <= 0) {
                drain(sink);
                return;
            }
            int offset = 0;
            while (offset < length) {
                int index = dequeueInput(sink);
                if (index < 0) {
                    // The encoder has stalled. The rest of the chunk is dropped rather than
                    // held, because the recorder cannot wait on a live microphone for long.
                    return;
                }
                ByteBuffer input = inputBuffer(index);
                input.clear();
                int count = Math.min(length - offset, input.remaining());
                count -= count % (BYTES_PER_SAMPLE * mChannelCount);
                if (count <= 0) {
                    throw new IOException("Encoder input buffer cannot hold one frame");
                }
                input.put(pcm, offset, count);
                long presentationTimeUs = mFramesFed * 1_000_000L / mSampleRate;
                mCodecInstance.queueInputBuffer(index, 0, count, presentationTimeUs, 0);
                mFramesFed += count / (BYTES_PER_SAMPLE * mChannelCount);
                offset += count;
                drain(sink);
            }
        } catch (IllegalStateException e) {
            throw new IOException("The audio encoder failed", e);
        }
    }

    /**
     * An input buffer, collecting output while the encoder frees one, or -1 once it has not for
     * long enough. Output is what frees input, so a wait that only waited could wait forever.
     */
    @WorkerThread
    private int dequeueInput(@NonNull Sink sink) throws IOException {
        for (int attempt = 0; attempt < INPUT_ATTEMPTS; attempt++) {
            int index = mCodecInstance.dequeueInputBuffer(INPUT_TIMEOUT_US);
            if (index >= 0) {
                return index;
            }
            drain(sink);
        }
        return -1;
    }

    /** Hands on every frame the encoder has ready. */
    @WorkerThread
    private void drain(@NonNull Sink sink) throws IOException {
        while (true) {
            int index = mCodecInstance.dequeueOutputBuffer(mInfo, OUTPUT_TIMEOUT_US);
            if (index < 0) {
                // Try again later, a new output format, or new buffers: none of them carry a frame,
                // and only the first means there is nothing more to collect now.
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    return;
                }
                continue;
            }
            ByteBuffer output = outputBuffer(index);
            byte[] bytes = new byte[mInfo.size];
            output.position(mInfo.offset);
            output.limit(mInfo.offset + mInfo.size);
            output.get(bytes);
            boolean config = (mInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
            mCodecInstance.releaseOutputBuffer(index, false);
            if (bytes.length == 0) {
                continue;
            }
            if (config) {
                sink.onConfig(bytes);
            } else {
                sink.onFrame(bytes);
            }
        }
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

    /** Stops and releases the encoder. Frames still inside it are discarded. */
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
