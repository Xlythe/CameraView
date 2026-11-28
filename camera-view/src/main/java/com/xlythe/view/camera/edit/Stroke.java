package com.xlythe.view.camera.edit;

import android.graphics.Matrix;
import android.graphics.RectF;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;

/**
 * One stroke of ink: where the pen went, and how hard it pressed, how far it leaned and when,
 * at every sample, with the brush it was drawn with. Strokes are geometry rather than pixels, so
 * they draw sharply at any size. They are immutable.
 *
 * <p>An axis the device could not report is NaN at every sample: NaN pressure means the device
 * cannot tell, while 0 means the pen barely touched.</p>
 */
public final class Stroke {
    /** Each sample's x, in the drawing's units. */
    private final float[] mXs;
    /** Each sample's y. */
    private final float[] mYs;
    /** Each sample's pressure, 0 to 1, or NaN. */
    private final float[] mPressures;
    /** Each sample's tilt towards +x, in degrees, or NaN. */
    private final float[] mTiltXs;
    /** Each sample's tilt towards +y, in degrees, or NaN. */
    private final float[] mTiltYs;
    /** Each sample's time from the first, in milliseconds. */
    private final int[] mTimes;
    /** The brush. */
    @NonNull private final Brush mBrush;
    /** What the stroke is called across edits and sessions. */
    @NonNull private final String mId;

    Stroke(@NonNull float[] xs, @NonNull float[] ys, @NonNull float[] pressures,
            @NonNull float[] tiltXs, @NonNull float[] tiltYs, @NonNull int[] times,
            @NonNull Brush brush, @NonNull String id) {
        int count = xs.length;
        if (count == 0 || ys.length != count || pressures.length != count
                || tiltXs.length != count || tiltYs.length != count || times.length != count) {
            throw new IllegalArgumentException("A stroke has at least one sample, and as many of "
                    + "each axis as of x");
        }
        mXs = xs;
        mYs = ys;
        mPressures = pressures;
        mTiltXs = tiltXs;
        mTiltYs = tiltYs;
        mTimes = times;
        mBrush = brush;
        mId = id;
    }

    /** How many samples there are: at least one. */
    @AnyThread
    public int size() {
        return mXs.length;
    }

    @AnyThread
    public float getX(int index) {
        return mXs[index];
    }

    @AnyThread
    public float getY(int index) {
        return mYs[index];
    }

    /** How hard the pen pressed, 0 to 1, or NaN where the device cannot tell. */
    @AnyThread
    public float getPressure(int index) {
        return mPressures[index];
    }

    /** How far the pen leaned towards +x, in degrees, or NaN where the device cannot tell. */
    @AnyThread
    public float getTiltX(int index) {
        return mTiltXs[index];
    }

    /** How far the pen leaned towards +y, in degrees, or NaN where the device cannot tell. */
    @AnyThread
    public float getTiltY(int index) {
        return mTiltYs[index];
    }

    /** Milliseconds from the first sample to this one. */
    @AnyThread
    public int getTimeOffsetMillis(int index) {
        return mTimes[index];
    }

    @AnyThread
    @NonNull
    public Brush getBrush() {
        return mBrush;
    }

    /** What the stroke is called across edits and sessions. */
    @AnyThread
    @NonNull
    public String getId() {
        return mId;
    }

    /** How fast the pen moved into a sample, in units a millisecond, or NaN with no timing. */
    @AnyThread
    float speedAt(int index) {
        if (index <= 0 || index >= mXs.length) {
            return Float.NaN;
        }
        int elapsed = mTimes[index] - mTimes[index - 1];
        if (elapsed <= 0) {
            return Float.NaN;
        }
        float dx = mXs[index] - mXs[index - 1];
        float dy = mYs[index] - mYs[index - 1];
        return (float) Math.sqrt(dx * dx + dy * dy) / elapsed;
    }

    /** The stroke's length along its samples. */
    @AnyThread
    float length() {
        float total = 0f;
        for (int i = 1; i < mXs.length; i++) {
            float dx = mXs[i] - mXs[i - 1];
            float dy = mYs[i] - mYs[i - 1];
            total += (float) Math.sqrt(dx * dx + dy * dy);
        }
        return total;
    }

    /** What the ink covers, the samples grown by the brush's widest half-width. */
    @AnyThread
    @NonNull
    RectF inkedBounds() {
        float left = mXs[0];
        float right = mXs[0];
        float top = mYs[0];
        float bottom = mYs[0];
        for (int i = 1; i < mXs.length; i++) {
            left = Math.min(left, mXs[i]);
            right = Math.max(right, mXs[i]);
            top = Math.min(top, mYs[i]);
            bottom = Math.max(bottom, mYs[i]);
        }
        float radius = mBrush.maxRadius() + 1f;
        return new RectF(left - radius, top - radius, right + radius, bottom + radius);
    }

    /**
     * A copy moved by a matrix, its width scaled by how much the matrix scales area so a stroke
     * that grows thickens with it. Pressure, tilt and timing are kept.
     */
    @AnyThread
    @NonNull
    Stroke transformed(@NonNull Matrix matrix) {
        float[] points = new float[mXs.length * 2];
        for (int i = 0; i < mXs.length; i++) {
            points[i * 2] = mXs[i];
            points[i * 2 + 1] = mYs[i];
        }
        matrix.mapPoints(points);
        float[] xs = new float[mXs.length];
        float[] ys = new float[mXs.length];
        for (int i = 0; i < mXs.length; i++) {
            xs[i] = points[i * 2];
            ys[i] = points[i * 2 + 1];
        }
        float[] values = new float[9];
        matrix.getValues(values);
        float scale = (float) Math.sqrt(Math.abs(values[Matrix.MSCALE_X] * values[Matrix.MSCALE_Y]
                - values[Matrix.MSKEW_X] * values[Matrix.MSKEW_Y]));
        Brush brush = scale > 0f && scale != 1f ? mBrush.withWidth(mBrush.getWidth() * scale)
                : mBrush;
        return new Stroke(xs, ys, mPressures, mTiltXs, mTiltYs, mTimes, brush, mId);
    }

    /** Each axis, for writing out. */
    @NonNull
    float[] xs() {
        return mXs;
    }

    @NonNull
    float[] ys() {
        return mYs;
    }

    @NonNull
    float[] pressures() {
        return mPressures;
    }

    @NonNull
    float[] tiltXs() {
        return mTiltXs;
    }

    @NonNull
    float[] tiltYs() {
        return mTiltYs;
    }

    @NonNull
    int[] times() {
        return mTimes;
    }

    @NonNull
    @Override
    public String toString() {
        return "Stroke{id=" + mId + ", samples=" + mXs.length + ", brush=" + mBrush + "}";
    }

    /**
     * Builds a stroke sample by sample, into arrays that double as they fill, so a stroke at a
     * digitiser's full rate allocates nothing per sample.
     */
    static final class Builder {
        /** The brush. */
        @NonNull private final Brush mBrush;
        /** The stroke's name. */
        @NonNull private final String mId;
        /** Each axis so far. */
        private float[] mXs = new float[128];
        private float[] mYs = new float[128];
        private float[] mPressures = new float[128];
        private float[] mTiltXs = new float[128];
        private float[] mTiltYs = new float[128];
        private int[] mTimes = new int[128];
        /** How many samples there are. */
        private int mCount;
        /** When the first sample was, in milliseconds. */
        private long mFirstTime;

        Builder(@NonNull Brush brush, @NonNull String id) {
            mBrush = brush;
            mId = id;
        }

        int size() {
            return mCount;
        }

        @NonNull
        Brush getBrush() {
            return mBrush;
        }

        /** Adds a sample, at an absolute time; the stroke keeps times from its first sample. */
        void add(float x, float y, float pressure, float tiltX, float tiltY, long timeMillis) {
            if (mCount == mXs.length) {
                grow();
            }
            if (mCount == 0) {
                mFirstTime = timeMillis;
            }
            mXs[mCount] = x;
            mYs[mCount] = y;
            mPressures[mCount] = pressure;
            mTiltXs[mCount] = tiltX;
            mTiltYs[mCount] = tiltY;
            mTimes[mCount] = (int) Math.max(0, timeMillis - mFirstTime);
            mCount++;
        }

        float lastX() {
            return mXs[mCount - 1];
        }

        float lastY() {
            return mYs[mCount - 1];
        }

        /** The stroke so far, or with predicted samples after it, which are not kept. */
        @NonNull
        Stroke build() {
            if (mCount == 0) {
                throw new IllegalStateException("A stroke needs at least one sample");
            }
            return new Stroke(copy(mXs), copy(mYs), copy(mPressures), copy(mTiltXs),
                    copy(mTiltYs), copyOf(mTimes, mCount), mBrush, mId);
        }

        private float[] copy(float[] values) {
            float[] copy = new float[mCount];
            System.arraycopy(values, 0, copy, 0, mCount);
            return copy;
        }

        private static int[] copyOf(int[] values, int count) {
            int[] copy = new int[count];
            System.arraycopy(values, 0, copy, 0, count);
            return copy;
        }

        private void grow() {
            int size = mXs.length * 2;
            mXs = grown(mXs, size);
            mYs = grown(mYs, size);
            mPressures = grown(mPressures, size);
            mTiltXs = grown(mTiltXs, size);
            mTiltYs = grown(mTiltYs, size);
            int[] times = new int[size];
            System.arraycopy(mTimes, 0, times, 0, mCount);
            mTimes = times;
        }

        private float[] grown(float[] values, int size) {
            float[] grown = new float[size];
            System.arraycopy(values, 0, grown, 0, mCount);
            return grown;
        }
    }
}
