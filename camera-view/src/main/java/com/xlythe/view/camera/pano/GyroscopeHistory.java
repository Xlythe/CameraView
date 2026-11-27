package com.xlythe.view.camera.pano;

import androidx.annotation.AnyThread;

/**
 * How fast the gyroscope said the device was turning over the last few seconds, so the rotation
 * a frame is placed by can be checked against it.
 *
 * <p>Readings are added on the sensor thread and read on the analysis thread.</p>
 */
@AnyThread
final class GyroscopeHistory {
    /** How many readings are kept: as many as {@link OrientationHistory} keeps of its own. */
    static final int CAPACITY = OrientationHistory.CAPACITY;

    /** Reading times, a ring of {@link #CAPACITY} with the oldest at {@link #mStart}. */
    private final long[] mTimes = new long[CAPACITY];

    /** Each reading's turning speed in degrees per second, one per slot of {@link #mTimes}. */
    private final float[] mSpeeds = new float[CAPACITY];

    /** Where the oldest reading is. */
    private int mStart;

    /** How many readings are held. */
    private int mCount;

    /**
     * Adds a reading. One no newer than the newest is dropped, since sensor events arrive in
     * order.
     */
    synchronized void add(long timeNanos, float degreesPerSecond) {
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
        mSpeeds[slot] = degreesPerSecond;
    }

    /** Whether nothing has been read yet. */
    synchronized boolean isEmpty() {
        return mCount == 0;
    }

    /**
     * The mean speed of the readings taken from one moment to another, both included.
     *
     * @return degrees per second, or NaN when no reading was taken between them
     */
    synchronized float average(long fromNanos, long toNanos) {
        float sum = 0f;
        int count = 0;
        for (int i = mCount - 1; i >= 0; i--) {
            int slot = index(i);
            if (mTimes[slot] < fromNanos) {
                break;
            }
            if (mTimes[slot] <= toNanos) {
                sum += mSpeeds[slot];
                count++;
            }
        }
        return count == 0 ? Float.NaN : sum / count;
    }

    private int index(int offset) {
        return (mStart + offset) % CAPACITY;
    }
}
