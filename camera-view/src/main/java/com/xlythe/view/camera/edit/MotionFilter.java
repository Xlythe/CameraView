package com.xlythe.view.camera.edit;

import androidx.annotation.NonNull;

/**
 * Takes the tremor out of a live stroke without making it lag, and gives what it took back as
 * pressure, so a steadied line keeps its life.
 *
 * <p>It is the one-euro filter (Casiez, Roussel and Vogel, CHI 2012): its cutoff rises with
 * speed, smoothing a slow hand hard and a fast deliberate stroke hardly at all. Speed is tracked
 * per axis and signed, so tremor, which is fast motion that goes nowhere, reads as slow and is
 * smoothed, where its unsigned speed would open the filter just when it is needed.</p>
 *
 * <p>The motion it removes is added back to pressure in proportion to the expression setting,
 * where the input has pressure; a device with no pressure keeps none. One filter belongs to one
 * stroke: {@link #reset} between strokes. The same samples always give the same result.</p>
 */
final class MotionFilter {
    /** 2π. */
    private static final float TAU = 6.283185f;
    /** The cutoff floor at the most smoothing, in hertz; below about 1 Hz the line trails. */
    private static final float MIN_MIN_CUTOFF_HZ = 1f;
    /** The cutoff floor with no smoothing: high enough to pass tremor of 8 to 12 Hz. */
    private static final float MAX_MIN_CUTOFF_HZ = 30f;
    /** How fast the cutoff opens as the hand speeds up. */
    private static final float SPEED_COEFFICIENT = 0.02f;
    /** The speed estimate's own cutoff, lower than the position's: it tracks intent. */
    private static final float VELOCITY_CUTOFF_HZ = 1f;
    /** The shortest interval taken as real, so a stalled clock cannot divide by zero. */
    private static final float MIN_DELTA_SECONDS = 0.001f;
    /** The removed motion, in drawing units, that counts as a full-strength shake. */
    private static final float EXPRESSION_REFERENCE_UNITS = 4f;
    /** The most expression may move pressure. */
    private static final float EXPRESSION_RANGE = 0.35f;

    /** How much tremor to remove, 0 to 1. */
    private final float mSmoothing;
    /** How much of the removed motion to give back as pressure, 0 to 1. */
    private final float mExpression;

    /** Whether a sample has been seen this stroke. */
    private boolean mHasPrevious;
    /** The last sample's time. */
    private long mPreviousTime;
    /** The filtered position. */
    private float mX;
    private float mY;
    /** The filtered signed speed on each axis. */
    private float mVelocityX;
    private float mVelocityY;
    /** The last filtered pressure, read back by the caller. */
    private float mPressure;

    MotionFilter(float smoothing, float expression) {
        mSmoothing = Brush.clamp(smoothing, 0f, 1f);
        mExpression = Brush.clamp(expression, 0f, 1f);
    }

    /** Forgets the stroke, so the next one does not start pulled towards where this ended. */
    void reset() {
        mHasPrevious = false;
        mPreviousTime = 0;
        mX = 0;
        mY = 0;
        mVelocityX = 0;
        mVelocityY = 0;
    }

    /**
     * A copy of this filter as it stands, for predicted samples: they must be smoothed as the
     * real ones replacing them will be, without moving this filter's clock.
     */
    @NonNull
    MotionFilter fork() {
        MotionFilter copy = new MotionFilter(mSmoothing, mExpression);
        copy.mHasPrevious = mHasPrevious;
        copy.mPreviousTime = mPreviousTime;
        copy.mX = mX;
        copy.mY = mY;
        copy.mVelocityX = mVelocityX;
        copy.mVelocityY = mVelocityY;
        return copy;
    }

    /**
     * Filters a sample at an absolute time. The result is read with {@link #x()}, {@link #y()}
     * and {@link #pressure()}.
     */
    void filter(float x, float y, float pressure, long timeMillis) {
        if (!mHasPrevious) {
            mHasPrevious = true;
            mPreviousTime = timeMillis;
            mX = x;
            mY = y;
            mVelocityX = 0;
            mVelocityY = 0;
            mPressure = pressure;
            return;
        }
        float delta = Math.max(MIN_DELTA_SECONDS, (timeMillis - mPreviousTime) / 1000f);
        mPreviousTime = timeMillis;
        mVelocityX = lowPass(mVelocityX, (x - mX) / delta, VELOCITY_CUTOFF_HZ, delta);
        mVelocityY = lowPass(mVelocityY, (y - mY) / delta, VELOCITY_CUTOFF_HZ, delta);
        float cutoff = minCutoff() + SPEED_COEFFICIENT * distance(mVelocityX, mVelocityY);
        float newX = lowPass(mX, x, cutoff, delta);
        float newY = lowPass(mY, y, cutoff, delta);
        float removed = distance(x - newX, y - newY);
        mX = newX;
        mY = newY;
        mPressure = expressed(pressure, removed);
    }

    float x() {
        return mX;
    }

    float y() {
        return mY;
    }

    float pressure() {
        return mPressure;
    }

    private float expressed(float pressure, float removed) {
        if (Float.isNaN(pressure) || mExpression <= 0f) {
            return pressure;
        }
        float amount = Brush.clamp(removed / EXPRESSION_REFERENCE_UNITS, 0f, 1f);
        return Brush.clamp(pressure + mExpression * EXPRESSION_RANGE * amount, 0f, 1f);
    }

    private float minCutoff() {
        return MAX_MIN_CUTOFF_HZ - mSmoothing * (MAX_MIN_CUTOFF_HZ - MIN_MIN_CUTOFF_HZ);
    }

    private static float distance(float dx, float dy) {
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** One step towards a target, weighted by a cutoff and the real time elapsed. */
    private static float lowPass(float current, float target, float cutoffHz, float delta) {
        float timeConstant = 1f / (TAU * cutoffHz);
        float alpha = 1f / (1f + timeConstant / delta);
        return current + alpha * (target - current);
    }
}
