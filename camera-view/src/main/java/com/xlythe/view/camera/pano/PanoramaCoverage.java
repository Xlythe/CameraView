package com.xlythe.view.camera.pano;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

/**
 * What a sweep has covered so far, and what is still missing.
 *
 * <p>The thing a panorama needs that a still does not: somebody has to turn the phone, and they
 * have to be told where to point it next and when to stop. This is the record of where they
 * have pointed it - a coarse grid over the sphere, one bit per cell - which is enough to answer
 * both questions and small enough to update on every frame.</p>
 *
 * <p>Coarse on purpose. A cell is several degrees across, so a cell counts as covered the
 * moment any part of a frame falls in it; the alternative, tracking it per pixel, would be a
 * second canvas and would answer a question nobody asks. What is wanted is "keep going to your
 * right" and "that is the whole row", and a few degrees is finer than a person can aim
 * anyway.</p>
 *
 * <p>Not thread safe. It is written from whichever thread the frames arrive on and read for
 * guidance, so a caller that reads it elsewhere should read {@link #getProgress} and the
 * suggestion rather than walking the grid.</p>
 */
public final class PanoramaCoverage {
    /** How wide and tall a cell is, in degrees. Finer than anyone can aim a phone. */
    @VisibleForTesting
    static final int CELL_DEGREES = 5;

    private final int mColumns;
    private final int mRows;
    private final boolean[] mCovered;

    /** The band of pitches this sweep is asking for, which is what decides when it is done. */
    private final float mMinPitch;
    private final float mMaxPitch;

    /** How much of the yaw range is wanted, so that a half sweep is not reported as unfinished. */
    private final float mYawSpan;

    /** The yaw the sweep began at, which every yaw is measured from. */
    private float mStartYaw = Float.NaN;

    /** The widest the sweep has got, either way, so progress cannot go backwards. */
    private float mLeftmostYaw;
    private float mRightmostYaw;

    private int mCoveredCells;

    /**
     * @param yawSpanDegrees how far round the sweep is meant to go: 360 for a full turn, less
     *     for a flat panorama that stops
     * @param minPitchDegrees the lowest row wanted, -90 for a full sphere
     * @param maxPitchDegrees the highest row wanted, 90 for a full sphere
     */
    public PanoramaCoverage(float yawSpanDegrees, float minPitchDegrees, float maxPitchDegrees) {
        mYawSpan = Math.max(CELL_DEGREES, Math.min(Equirectangular.FULL_TURN_DEGREES,
                yawSpanDegrees));
        mMinPitch = Equirectangular.clampPitch(Math.min(minPitchDegrees, maxPitchDegrees));
        mMaxPitch = Equirectangular.clampPitch(Math.max(minPitchDegrees, maxPitchDegrees));
        mColumns = (int) Math.ceil(mYawSpan / CELL_DEGREES);
        mRows = Math.max(1, (int) Math.ceil((mMaxPitch - mMinPitch) / CELL_DEGREES));
        mCovered = new boolean[mColumns * mRows];
    }

    /** A sweep all the way round at eye level, which is what a panorama usually means. */
    @NonNull
    public static PanoramaCoverage forPanorama() {
        // One cell above and below the horizon: a handheld sweep wanders by a few degrees and
        // asking for exactly the horizon would never finish.
        return new PanoramaCoverage(Equirectangular.FULL_TURN_DEGREES, -CELL_DEGREES,
                CELL_DEGREES);
    }

    /** The whole sphere, which is what a photosphere means. */
    @NonNull
    public static PanoramaCoverage forPhotosphere() {
        return new PanoramaCoverage(Equirectangular.FULL_TURN_DEGREES, -90f, 90f);
    }

    /**
     * Records that the camera was pointed here, and says whether this is new ground.
     *
     * <p>The answer is what decides whether the frame is worth drawing: a sweep that pauses
     * delivers thirty frames a second of the same place, and drawing them all costs the
     * blending quality for nothing.</p>
     *
     * @return whether this covered a cell that was not covered before
     */
    public boolean cover(float yawDegrees, float pitchDegrees) {
        if (Float.isNaN(mStartYaw)) {
            mStartYaw = Equirectangular.wrapYaw(yawDegrees);
        }
        float offset = Equirectangular.wrapYaw(yawDegrees - mStartYaw);
        mLeftmostYaw = Math.min(mLeftmostYaw, offset);
        mRightmostYaw = Math.max(mRightmostYaw, offset);

        int row = rowFor(pitchDegrees);
        if (row < 0) {
            return false;
        }
        int column = columnFor(offset);
        if (column < 0) {
            return false;
        }
        int cell = row * mColumns + column;
        if (mCovered[cell]) {
            return false;
        }
        mCovered[cell] = true;
        mCoveredCells++;
        return true;
    }

    /** How much of what was asked for has been covered, 0 to 1. */
    public float getProgress() {
        return mCovered.length == 0 ? 1f : (float) mCoveredCells / mCovered.length;
    }

    /** Whether there is nothing left to point at. */
    public boolean isComplete() {
        return mCoveredCells == mCovered.length;
    }

    /**
     * Which way to turn next, as a yaw and pitch to aim at, or null when there is nowhere left.
     *
     * <p>The nearest uncovered cell rather than the next one in order, so that a person who
     * wandered off the row is sent back to the gap rather than made to finish the circle
     * first.</p>
     *
     * @param out set to {yaw, pitch} in degrees, in the same frame the yaws are reported in
     * @return whether anywhere was suggested
     */
    public boolean suggestNext(float currentYawDegrees, float currentPitchDegrees,
            @NonNull float[] out) {
        if (out.length < 2 || isComplete() || Float.isNaN(mStartYaw)) {
            return false;
        }
        float fromYaw = Equirectangular.wrapYaw(currentYawDegrees - mStartYaw);
        float best = Float.MAX_VALUE;
        for (int row = 0; row < mRows; row++) {
            for (int column = 0; column < mColumns; column++) {
                if (mCovered[row * mColumns + column]) {
                    continue;
                }
                float cellYaw = yawForColumn(column);
                float cellPitch = pitchForRow(row);
                // Angles, so the two axes are comparable and a cell straight up is not judged
                // nearer than one just along the row.
                float dYaw = Equirectangular.yawDistance(fromYaw, cellYaw);
                float dPitch = Math.abs(cellPitch - currentPitchDegrees);
                float distance = dYaw * dYaw + dPitch * dPitch;
                if (distance < best) {
                    best = distance;
                    out[0] = Equirectangular.wrapYaw(cellYaw + mStartYaw);
                    out[1] = cellPitch;
                }
            }
        }
        return best < Float.MAX_VALUE;
    }

    /** How far round the sweep has got, in degrees, counting both directions from the start. */
    public float getSweptDegrees() {
        return Float.isNaN(mStartYaw) ? 0f : mRightmostYaw - mLeftmostYaw;
    }

    /** The yaw the sweep began at, which the canvas is centred on, or NaN before it began. */
    public float getStartYaw() {
        return mStartYaw;
    }

    /** The band of pitches asked for, as the canvas has to be tall enough to hold it. */
    public float getMinPitch() {
        return mMinPitch;
    }

    public float getMaxPitch() {
        return mMaxPitch;
    }

    /** How far round was asked for, which a partial panorama needs in order to be cropped. */
    public float getYawSpan() {
        return mYawSpan;
    }

    private int rowFor(float pitchDegrees) {
        float pitch = Equirectangular.clampPitch(pitchDegrees);
        if (pitch < mMinPitch || pitch > mMaxPitch) {
            return -1;
        }
        return Math.min(mRows - 1, (int) ((pitch - mMinPitch) / CELL_DEGREES));
    }

    /**
     * The column an offset from the start falls in, or -1 when it is outside the sweep.
     *
     * <p>A full turn is laid out with the start in the middle, because a sweep is as likely to
     * go left as right and a person who turned the wrong way should not be told they have
     * covered nothing.</p>
     */
    private int columnFor(float offsetDegrees) {
        float half = mYawSpan / 2;
        if (offsetDegrees < -half || offsetDegrees > half) {
            return -1;
        }
        return Math.min(mColumns - 1, (int) ((offsetDegrees + half) / CELL_DEGREES));
    }

    private float yawForColumn(int column) {
        return (column + 0.5f) * CELL_DEGREES - mYawSpan / 2;
    }

    private float pitchForRow(int row) {
        return mMinPitch + (row + 0.5f) * CELL_DEGREES;
    }
}
