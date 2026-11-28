package com.xlythe.view.camera.pano;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;

/**
 * The last second or so of the device's rotation, so a frame can be placed by where the phone
 * was pointing when the frame was captured rather than when it got here.
 *
 * <p>A frame reaches the analyzer several tens of milliseconds after its exposure, and a phone
 * being swept turns a degree or more in that time. Placing it with the newest rotation puts every
 * frame that far behind the sweep, by an amount that changes with the speed of the turn, so
 * neighbouring frames disagree about where the same wall is. Each frame carries its capture time,
 * and the rotation at that time is interpolated between the two readings either side of it.</p>
 *
 * <p>Readings are added on the sensor thread and read on the analysis and main threads.</p>
 */
@AnyThread
final class OrientationHistory {
    /**
     * How many readings are kept: five seconds at the rate the sweep asks the sensor for, which
     * covers a frame that a busy device delivers seconds late.
     */
    static final int CAPACITY = 512;

    /**
     * How far past the newest reading a time may be and still be given that reading.
     *
     * <p>A frame can be captured just after the last reading arrived. A few tens of milliseconds
     * of a sweep is a fraction of a degree, so the newest reading is good enough; much longer
     * and the sensor has stopped, which is worth refusing a frame over.</p>
     */
    static final long MAX_EXTRAPOLATION_NANOS = 60_000_000L;

    /** The span the turning speed is measured over, either side of the time asked about. */
    private static final long SPEED_WINDOW_NANOS = 40_000_000L;

    /** Reading times, a ring of {@link #CAPACITY} with the oldest at {@link #mStart}. */
    private final long[] mTimes = new long[CAPACITY];

    /** Each reading as w, x, y, z, four floats per slot of {@link #mTimes}. */
    private final float[] mQuaternions = new float[CAPACITY * 4];

    /** Where the oldest reading is. */
    private int mStart;

    /** How many readings are held. */
    private int mCount;

    /** Scratch for {@link #sample}, guarded by this. */
    private final float[] mBefore = new float[4];

    /** Scratch for {@link #sample}, guarded by this. */
    private final float[] mAfter = new float[4];

    /** Scratch for {@link #speed}, guarded by this. */
    private final float[] mEarlier = new float[4];

    /** Scratch for {@link #speed}, guarded by this. */
    private final float[] mLater = new float[4];

    /**
     * Adds a reading. One older than the newest is dropped, since sensor events arrive in order
     * and an out of order one could only be from a sensor that was swapped mid-sweep.
     *
     * @param quaternion w, x, y, z
     */
    synchronized void add(long timeNanos, @NonNull float[] quaternion) {
        if (mCount > 0 && timeNanos <= mTimes[index(mCount - 1)]) {
            return;
        }
        int slot;
        if (mCount < CAPACITY) {
            slot = index(mCount);
            mCount++;
        } else {
            slot = mStart;
            mStart = (mStart + 1) % CAPACITY;
        }
        mTimes[slot] = timeNanos;
        System.arraycopy(quaternion, 0, mQuaternions, slot * 4, 4);
    }

    /** Whether anything has been read yet. */
    synchronized boolean isEmpty() {
        return mCount == 0;
    }

    /** The time of the newest reading, or 0 when there is none. */
    synchronized long newestTime() {
        return mCount == 0 ? 0 : mTimes[index(mCount - 1)];
    }

    /** The time of the oldest reading still held, or 0 when there is none. */
    synchronized long oldestTime() {
        return mCount == 0 ? 0 : mTimes[mStart];
    }

    /**
     * Whether there are readings either side of where {@link #speed} starts measuring at a
     * moment, so that it measures the turn there rather than calling it 0 for want of history.
     */
    synchronized boolean canMeasureSpeed(long timeNanos) {
        long from = timeNanos - SPEED_WINDOW_NANOS;
        return mCount > 1 && mTimes[mStart] <= from && mTimes[index(mCount - 1)] > from;
    }

    /**
     * The rotation at a moment, as w, x, y, z.
     *
     * @return false when the moment is older than anything held, or too far past the newest
     */
    synchronized boolean sample(long timeNanos, @NonNull float[] out) {
        if (mCount == 0) {
            return false;
        }
        int newest = index(mCount - 1);
        if (timeNanos >= mTimes[newest]) {
            if (timeNanos - mTimes[newest] > MAX_EXTRAPOLATION_NANOS) {
                return false;
            }
            System.arraycopy(mQuaternions, newest * 4, out, 0, 4);
            return true;
        }
        if (timeNanos < mTimes[mStart]) {
            return false;
        }
        // Binary search for the last reading at or before the moment.
        int low = 0;
        int high = mCount - 1;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (mTimes[index(middle)] <= timeNanos) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        int before = index(low);
        int after = index(low + 1);
        float t = (float) (timeNanos - mTimes[before]) / (mTimes[after] - mTimes[before]);
        System.arraycopy(mQuaternions, before * 4, mBefore, 0, 4);
        System.arraycopy(mQuaternions, after * 4, mAfter, 0, 4);
        SphereMath.slerp(mBefore, mAfter, t, out);
        return true;
    }

    /**
     * How fast the device was turning at a moment, in degrees per second.
     *
     * @return the speed, or 0 when there is not enough history either side to tell
     */
    synchronized float speed(long timeNanos) {
        long from = timeNanos - SPEED_WINDOW_NANOS;
        long to = Math.min(timeNanos + SPEED_WINDOW_NANOS, newestTime());
        if (to <= from || !sample(from, mEarlier) || !sample(to, mLater)) {
            return 0f;
        }
        return SphereMath.angleBetweenRotations(mEarlier, mLater) * 1e9f / (to - from);
    }

    private int index(int offset) {
        return (mStart + offset) % CAPACITY;
    }
}
