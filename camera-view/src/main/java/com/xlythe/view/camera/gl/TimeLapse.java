package com.xlythe.view.camera.gl;

import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.SystemClock;

import androidx.annotation.AnyThread;
import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

/**
 * Which camera frames a time-lapse keeps, and when each one is played back.
 *
 * <p>A time-lapse keeps one frame for every {@code speed} a recording would, and stamps the ones
 * it keeps a frame interval apart, so the file plays at its normal frame rate and gets through
 * the time it took {@code speed} times as fast. Frames are kept on a schedule counted from the
 * first one rather than by counting frames, so a camera that slows down in the dark leaves gaps
 * in the schedule instead of slowing the time-lapse down with it.</p>
 *
 * <p>Nothing is kept until {@link #start}, which a backend calls once its encoder has started.
 * An encoder drops every frame stamped before it started, and a time-lapse stamps its frames
 * behind the time they were taken, so a schedule begun earlier would lose its first seconds.</p>
 *
 * <p>Started and finished from the main thread and read on the render thread, hence the lock.</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class TimeLapse {
    /** What {@link #presentationTime} returns for a frame the time-lapse leaves out. */
    static final long SKIP = -1;

    /** Nanoseconds in a second, which a frame rate is counted against. */
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    /** How long each kept frame is shown for when the file plays. */
    private final long mFrameNanos;

    /** How far apart, in the time they were taken, the kept frames are. */
    private final long mIntervalNanos;

    /** Whether the encoder has started. Until it has, every frame is left out. */
    private boolean mStarted;

    /** When the encoder started, on the monotonic clock, before which frames are left out. */
    private long mStartedMonotonicNanos;

    /** The same moment on the clock that counts sleep, for a camera that stamps frames on it. */
    private long mStartedRealtimeNanos;

    /** Whether the recording is stopping. See {@link #finish}. */
    private boolean mFinishing;

    /** Whether a frame has been kept, which is what the schedule counts from. */
    private boolean mHasFirstFrame;

    /** When the first kept frame was taken. */
    private long mFirstFrameNanos;

    /** Which place in the schedule the last kept frame filled, counted from the first. */
    private long mLastSlot;

    /** The presentation time the last kept frame was given, which the next must come after. */
    private long mLastPresentationNanos;

    /**
     * @param speed how many times faster than it happened the recording plays
     * @param frameRate the frame rate the encoder plays the recording at
     */
    public TimeLapse(@IntRange(from = 2) int speed, @IntRange(from = 1) int frameRate) {
        if (speed < 2) {
            throw new IllegalArgumentException("A time-lapse plays faster than real time: " + speed);
        }
        if (frameRate < 1) {
            throw new IllegalArgumentException("Frame rate must be positive: " + frameRate);
        }
        mFrameNanos = NANOS_PER_SECOND / frameRate;
        mIntervalNanos = mFrameNanos * speed;
    }

    /**
     * Sets a recorder up from a profile's video settings alone, since a time-lapse has no sound.
     *
     * <p>Rather than {@link MediaRecorder#setProfile}, which sets the profile's audio too, and
     * fails without an audio source to set it for.</p>
     *
     * @param captureSpeed how many times faster than it happened the recorder itself plays what it
     *     is given, for a recorder the camera writes into directly; 1 for one fed by a renderer
     *     that already keeps the frames a time-lapse keeps
     */
    public static void setUpRecorder(@NonNull MediaRecorder recorder,
            @NonNull CamcorderProfile profile, @IntRange(from = 1) int captureSpeed) {
        recorder.setOutputFormat(profile.fileFormat);
        recorder.setVideoFrameRate(profile.videoFrameRate);
        recorder.setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight);
        recorder.setVideoEncodingBitRate(profile.videoBitRate);
        recorder.setVideoEncoder(profile.videoCodec);
        if (captureSpeed > 1) {
            recorder.setCaptureRate((double) profile.videoFrameRate / captureSpeed);
        }
    }

    /** The encoder has started, so frames taken from now on are kept. */
    @RequiresApi(17)
    @AnyThread
    public void start() {
        start(System.nanoTime(), SystemClock.elapsedRealtimeNanos());
    }

    @VisibleForTesting
    synchronized void start(long monotonicNanos, long realtimeNanos) {
        mStarted = true;
        mStartedMonotonicNanos = monotonicNanos;
        mStartedRealtimeNanos = realtimeNanos;
    }

    /**
     * The recording is stopping, so the next frame is stamped with the moment it is drawn instead
     * of its place in the schedule.
     *
     * <p>For an encoder that stops at a moment and waits for a frame stamped after it. Every
     * frame a time-lapse keeps is stamped behind the moment it was taken, so without this one
     * that encoder waits out its own timeout instead, and keeps recording while it waits.</p>
     */
    @AnyThread
    public synchronized void finish() {
        mFinishing = true;
    }

    /**
     * When this frame is played, or {@link #SKIP} for a frame the time-lapse leaves out.
     *
     * @param timestampNanos when the frame was taken
     * @param realtimeClock whether that timestamp is on the clock that counts sleep rather than the
     *     monotonic one. The result is on the same clock.
     * @param monotonicNowNanos the monotonic clock now
     * @param realtimeNowNanos the clock that counts sleep, now
     */
    @WorkerThread
    synchronized long presentationTime(long timestampNanos, boolean realtimeClock,
            long monotonicNowNanos, long realtimeNowNanos) {
        if (!mStarted) {
            return SKIP;
        }
        if (mFinishing) {
            long now = realtimeClock ? realtimeNowNanos : monotonicNowNanos;
            mLastPresentationNanos = Math.max(now, mLastPresentationNanos + mFrameNanos);
            return mLastPresentationNanos;
        }
        long startedNanos = realtimeClock ? mStartedRealtimeNanos : mStartedMonotonicNanos;
        if (timestampNanos < startedNanos) {
            return SKIP;
        }
        if (!mHasFirstFrame) {
            mHasFirstFrame = true;
            mFirstFrameNanos = timestampNanos;
            mLastSlot = 0;
            mLastPresentationNanos = timestampNanos;
            return mLastPresentationNanos;
        }
        // The place in the schedule this frame has reached, counting one up to half a frame
        // early as there, so a camera running a little fast still fills each place.
        long slot = (timestampNanos - mFirstFrameNanos + mFrameNanos / 2) / mIntervalNanos;
        if (slot <= mLastSlot) {
            return SKIP;
        }
        mLastSlot = slot;
        mLastPresentationNanos = mFirstFrameNanos + slot * mFrameNanos;
        return mLastPresentationNanos;
    }
}
