package com.xlythe.view.camera.caption;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

import static com.xlythe.view.camera.ICameraModule.TAG;

/**
 * The audio of a recording, decoded back to plain samples.
 *
 * <p>Needed because speech recognition takes samples and a recording holds compressed audio.
 * The platform will decode it - {@link MediaExtractor} to find the track and
 * {@link MediaCodec} to turn it back into samples - but neither will hand over a whole file at
 * once, so this reads it in windows and keeps track of where in the recording each one came
 * from. The time is the part that matters: a caption is text plus when it was said, and the
 * when comes from here.</p>
 *
 * <p>Nothing is resampled. The recogniser is told the sample rate and channel count the decoder
 * actually produced, which it accepts - converting them here would mean writing a resampler to
 * tell it a number it was happy to be told.</p>
 */
@RequiresApi(21)
final class AudioPcm implements Closeable {
    /** How long to wait on the codec for one buffer, in microseconds. */
    private static final long CODEC_TIMEOUT_US = 10_000L;

    private final MediaExtractor mExtractor;
    private final MediaCodec mDecoder;
    private final int mSampleRate;
    private final int mChannelCount;

    /** Samples decoded but not yet handed out, because a window was not full. */
    private final ByteArrayOutputStream mPending = new ByteArrayOutputStream();

    /** Where the samples in {@link #mPending} begin, in the recording. */
    private long mPendingStartMs;

    /** Where the next window begins, in the recording. */
    private long mPositionMs;

    private boolean mFedEverything;
    private boolean mDrained;

    private AudioPcm(@NonNull MediaExtractor extractor, @NonNull MediaCodec decoder,
            int sampleRate, int channelCount) {
        mExtractor = extractor;
        mDecoder = decoder;
        mSampleRate = sampleRate;
        mChannelCount = channelCount;
    }

    /**
     * Opens the audio of a recording, or returns null if it has none that can be decoded.
     *
     * <p>Null rather than thrown for a recording with no audio track, which is an ordinary
     * thing - a video recorded without the microphone permission has none, and it has no
     * captions rather than being an error.</p>
     */
    @Nullable
    static AudioPcm open(@NonNull File video) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(video.getAbsolutePath());
            for (int track = 0; track < extractor.getTrackCount(); track++) {
                MediaFormat format = extractor.getTrackFormat(track);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("audio/")) {
                    continue;
                }
                extractor.selectTrack(track);
                MediaCodec decoder = MediaCodec.createDecoderByType(mime);
                decoder.configure(format, null, null, 0);
                decoder.start();
                return new AudioPcm(extractor, decoder,
                        format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                        format.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
            }
            Log.d(TAG, "This recording has no audio track to transcribe");
            extractor.release();
            return null;
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "Unable to read the audio of " + video, error);
            extractor.release();
            return null;
        }
    }

    int getSampleRate() {
        return mSampleRate;
    }

    int getChannelCount() {
        return mChannelCount;
    }

    /** Where the next window will begin, in the recording. */
    long getPositionMs() {
        return mPositionMs;
    }

    /** How many bytes of samples make up a given length of this audio. */
    int bytesFor(long millis) {
        // Sixteen bits a sample per channel, which is what the decoder produces by default and
        // what the recogniser is told to expect.
        return (int) (millis * mSampleRate * mChannelCount * 2 / 1000);
    }

    /**
     * The next window of samples, or null once the recording has run out.
     *
     * <p>Always a whole window except the last, which is whatever was left. A window short of
     * a few samples at the end is still worth recognising - the last thing said in a recording
     * is usually in it.</p>
     */
    @Nullable
    byte[] readWindow(long windowMs) {
        int wanted = bytesFor(windowMs);
        try {
            while (mPending.size() < wanted && !mDrained) {
                feed();
                drain();
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to decode the audio of this recording", error);
            mDrained = true;
        }
        if (mPending.size() == 0) {
            return null;
        }
        byte[] all = mPending.toByteArray();
        mPending.reset();
        if (all.length <= wanted) {
            mPositionMs = mPendingStartMs + millisFor(all.length);
            long start = mPendingStartMs;
            mPendingStartMs = mPositionMs;
            mWindowStartMs = start;
            return all;
        }
        // More than a window was decoded, so the rest is kept for the next one rather than
        // thrown away - which would drop whatever was said in it.
        byte[] window = new byte[wanted];
        System.arraycopy(all, 0, window, 0, wanted);
        mPending.write(all, wanted, all.length - wanted);
        mWindowStartMs = mPendingStartMs;
        mPendingStartMs += millisFor(wanted);
        mPositionMs = mPendingStartMs;
        return window;
    }

    /** Where in the recording the window from the last {@link #readWindow} began. */
    private long mWindowStartMs;

    long getWindowStartMs() {
        return mWindowStartMs;
    }

    /** How long a number of bytes of these samples lasts. */
    long millisFor(int bytes) {
        int perSecond = mSampleRate * mChannelCount * 2;
        return perSecond == 0 ? 0 : bytes * 1000L / perSecond;
    }

    /** Hands the decoder more compressed audio, if there is any left. */
    private void feed() {
        if (mFedEverything) {
            return;
        }
        int index = mDecoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
        if (index < 0) {
            return;
        }
        ByteBuffer input = mDecoder.getInputBuffer(index);
        if (input == null) {
            return;
        }
        int read = mExtractor.readSampleData(input, 0);
        if (read < 0) {
            mDecoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            mFedEverything = true;
            return;
        }
        mDecoder.queueInputBuffer(index, 0, read, mExtractor.getSampleTime(), 0);
        mExtractor.advance();
    }

    /** Takes whatever samples the decoder has ready. */
    private void drain() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int index = mDecoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
        if (index < 0) {
            return;
        }
        ByteBuffer output = mDecoder.getOutputBuffer(index);
        if (output != null && info.size > 0) {
            byte[] samples = new byte[info.size];
            output.position(info.offset);
            output.get(samples);
            mPending.write(samples, 0, samples.length);
        }
        mDecoder.releaseOutputBuffer(index, false);
        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            mDrained = true;
        }
    }

    @Override
    public void close() {
        try {
            mDecoder.stop();
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to stop the audio decoder", error);
        }
        mDecoder.release();
        mExtractor.release();
    }
}
