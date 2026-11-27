package com.xlythe.view.camera.pano;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * What a sweep has covered, what is left, and where to point next.
 *
 * <p>The sphere is divided into cells a couple of degrees across, finer than anyone can aim a
 * phone. A frame covers every cell whose centre it saw, comfortably inside its edges - every
 * cell, not just the one it was aimed at, so a frame aimed near a pole covers the cap around it
 * and the sweep can finish.</p>
 *
 * <p>On top of the cells is a much coarser set of targets: the points the guide asks the user to
 * aim at, spaced so that a frame aimed at one covers everything nearer it than any other target.
 * Each cell belongs to its nearest target, and a target is done when all of its cells are. A
 * panorama has one row of targets, along the pitch it started at; a photosphere has rows from the
 * horizon to each pole, with one target on each pole.</p>
 *
 * <p>Progress is measured in area rather than in cells or targets: cells shrink towards the poles,
 * and counting them would say a sphere was a fifth done when only its caps were.</p>
 */
final class SweepCoverage {
    /** The side of a cell, in degrees. */
    static final float CELL_DEGREES = 2f;

    /** Cells round the sphere. */
    static final int COLUMNS = (int) (360 / CELL_DEGREES);

    /** Cells from pole to pole. */
    static final int ROWS = (int) (180 / CELL_DEGREES);

    /**
     * How much closer another target has to be before the guide switches to it. Without this
     * the arrow flickers between two targets the user is halfway between.
     */
    static final float HYSTERESIS_DEGREES = 12f;

    /** The yaw at the middle of the grid, between column COLUMNS / 2 - 1 and COLUMNS / 2. */
    final float yawOrigin;

    /** The row a panorama is swept along, or -1 for a photosphere. */
    final int bandRow;

    /** Whether each cell has been covered, row by row from the top. */
    private final boolean[] mCovered = new boolean[COLUMNS * ROWS];

    /** Each cell's centre as a unit vector, three floats per cell. */
    private final float[] mCellDirections = new float[COLUMNS * ROWS * 3];

    /** The target each cell belongs to, or -1 for a cell no target asks for. */
    private final int[] mOwner = new int[COLUMNS * ROWS];

    /** The area of one cell in each row, relative to one on the horizon. */
    private final float[] mRowArea = new float[ROWS];

    /** The targets' yaws. */
    private final float[] mTargetYaws;

    /** The targets' pitches. */
    private final float[] mTargetPitches;

    /** Each target as a unit vector, three floats per target. */
    private final float[] mTargetDirections;

    /** How many of each target's cells are still uncovered. */
    private final int[] mRemaining;

    /** The area of every cell a target owns, which is what progress is out of. */
    private final float mTotalArea;

    /** The area covered so far, of cells a target owns. */
    private float mCoveredArea;

    /** How many targets still have uncovered cells. */
    private int mIncomplete;

    private SweepCoverage(float yawOrigin, int bandRow, @NonNull List<float[]> targets) {
        this.yawOrigin = yawOrigin;
        this.bandRow = bandRow;
        int count = targets.size();
        mTargetYaws = new float[count];
        mTargetPitches = new float[count];
        mTargetDirections = new float[count * 3];
        mRemaining = new int[count];
        float[] direction = new float[3];
        for (int t = 0; t < count; t++) {
            mTargetYaws[t] = SphereMath.wrap(targets.get(t)[0]);
            mTargetPitches[t] = targets.get(t)[1];
            SphereMath.direction(mTargetYaws[t], mTargetPitches[t], direction);
            System.arraycopy(direction, 0, mTargetDirections, t * 3, 3);
        }

        float total = 0;
        for (int row = 0; row < ROWS; row++) {
            mRowArea[row] = (float) Math.cos(Math.toRadians(pitchOfRow(row)));
            for (int column = 0; column < COLUMNS; column++) {
                int cell = row * COLUMNS + column;
                SphereMath.direction(yawOfColumn(column), pitchOfRow(row), direction);
                System.arraycopy(direction, 0, mCellDirections, cell * 3, 3);
                int owner = -1;
                if (bandRow < 0 || row == bandRow) {
                    owner = nearestTarget(direction);
                }
                mOwner[cell] = owner;
                if (owner >= 0) {
                    if (mRemaining[owner]++ == 0) {
                        mIncomplete++;
                    }
                    total += mRowArea[row];
                }
            }
        }
        mTotalArea = total;
    }

    /**
     * A panorama: one row of targets all the way round at the pitch the sweep started at.
     *
     * @param fovAcross the angle of view across the picture as it is held
     */
    @NonNull
    static SweepCoverage forPanorama(float yaw, float pitch, float fovAcross) {
        float spacing = Math.max(10f, Math.min(30f, fovAcross / 2));
        int count = (int) Math.ceil(360f / spacing);
        List<float[]> targets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            targets.add(new float[] {yaw + i * 360f / count, pitch});
        }
        int row = Math.max(0, Math.min(ROWS - 1, (int) ((90f - pitch) / CELL_DEGREES)));
        return new SweepCoverage(yaw, row, targets);
    }

    /**
     * A photosphere: rows of targets from the horizon to each pole, closer together than the
     * narrower side of the frame so that one frame aimed at a target covers all of its cells.
     *
     * @param narrowestFov the angle of view across the narrower side of the frame
     */
    @NonNull
    static SweepCoverage forPhotosphere(float yaw, float narrowestFov) {
        float spacing = Math.max(20f, Math.min(45f, 0.7f * narrowestFov));
        List<float[]> targets = new ArrayList<>();
        for (int ring = 0; ring * spacing <= 90f - spacing / 2; ring++) {
            float pitch = ring * spacing;
            // The row's targets are spaced along its widest edge, the one nearest the horizon.
            float widest = (float) Math.cos(Math.toRadians(Math.max(0f, pitch - spacing / 2)));
            int count = Math.max(3, (int) Math.ceil(360f * widest / spacing));
            for (int i = 0; i < count; i++) {
                targets.add(new float[] {yaw + i * 360f / count, pitch});
                if (ring > 0) {
                    targets.add(new float[] {yaw + i * 360f / count, -pitch});
                }
            }
        }
        targets.add(new float[] {yaw, 90f});
        targets.add(new float[] {yaw, -90f});
        return new SweepCoverage(yaw, -1, targets);
    }

    /** The yaw of a column's centre. */
    float yawOfColumn(int column) {
        return SphereMath.wrap(yawOrigin - 180f + (column + 0.5f) * CELL_DEGREES);
    }

    /** The pitch of a row's centre. */
    static float pitchOfRow(int row) {
        return 90f - (row + 0.5f) * CELL_DEGREES;
    }

    private int nearestTarget(@NonNull float[] direction) {
        int nearest = -1;
        float best = -2f;
        for (int t = 0; t < mTargetYaws.length; t++) {
            float dot = direction[0] * mTargetDirections[t * 3]
                    + direction[1] * mTargetDirections[t * 3 + 1]
                    + direction[2] * mTargetDirections[t * 3 + 2];
            if (dot > best) {
                best = dot;
                nearest = t;
            }
        }
        return nearest;
    }

    /**
     * Marks the cells a frame saw, comfortably inside its edges.
     *
     * @return how many cells this frame was the first to cover
     */
    int cover(@NonNull CameraPose pose) {
        return visit(pose, true);
    }

    /** How many cells a frame would cover that are not covered yet, without marking them. */
    int countUncovered(@NonNull CameraPose pose) {
        return visit(pose, false);
    }

    private int visit(@NonNull CameraPose pose, boolean mark) {
        Footprint footprint = Footprint.of(pose);
        int top = Math.max(0, (int) Math.floor((90f - footprint.maxPitch) / CELL_DEGREES));
        int bottom = Math.min(ROWS - 1,
                (int) Math.floor((90f - footprint.minPitch) / CELL_DEGREES));
        if (bandRow >= 0) {
            top = Math.max(top, bandRow);
            bottom = Math.min(bottom, bandRow);
        }
        int first;
        int columns;
        if (footprint.allYaws) {
            first = 0;
            columns = COLUMNS;
        } else {
            // Measured from the frame's centre, so a frame across the grid's seam stays whole.
            float centre = columnOf(footprint.centreYaw);
            first = (int) Math.floor(
                    centre + (footprint.minYaw - footprint.centreYaw) / CELL_DEGREES);
            int last = (int) Math.ceil(
                    centre + (footprint.maxYaw - footprint.centreYaw) / CELL_DEGREES);
            columns = Math.min(COLUMNS, last - first + 1);
            first = Math.floorMod(first, COLUMNS);
        }
        // A cell counts when the frame saw all of it, so its centre has to be at least a
        // half-cell's angle inside the frame's edge, and a little more for the sensor's error.
        float margin = pose.focal * (float) Math.tan(Math.toRadians(CELL_DEGREES));
        float[] point = new float[2];
        int found = 0;
        for (int row = top; row <= bottom; row++) {
            for (int i = 0; i < columns; i++) {
                int cell = row * COLUMNS + (first + i) % COLUMNS;
                if (mCovered[cell]) {
                    continue;
                }
                if (!pose.project(mCellDirections[cell * 3], mCellDirections[cell * 3 + 1],
                        mCellDirections[cell * 3 + 2], point) || !pose.contains(point, margin)) {
                    continue;
                }
                found++;
                if (mark) {
                    mCovered[cell] = true;
                    int owner = mOwner[cell];
                    if (owner >= 0) {
                        mCoveredArea += mRowArea[row];
                        if (--mRemaining[owner] == 0) {
                            mIncomplete--;
                        }
                    }
                }
            }
        }
        return found;
    }

    /** Where a yaw falls across the grid, continuously, from 0 at the left edge of column 0. */
    private float columnOf(float yaw) {
        return (SphereMath.wrap(yaw - yawOrigin) + 180f) / CELL_DEGREES;
    }

    /** Whether a cell is covered. */
    boolean isCovered(int column, int row) {
        return mCovered[row * COLUMNS + column];
    }

    /** How much of what was asked for has been covered, from 0 to 1. */
    float getProgress() {
        if (mIncomplete == 0) {
            return 1f;
        }
        return Math.min(1f, mCoveredArea / mTotalArea);
    }

    /** Whether everything asked for has been covered. */
    boolean isComplete() {
        return mIncomplete == 0;
    }

    /** How many targets there are, done or not. */
    int getTargetCount() {
        return mTargetYaws.length;
    }

    float getTargetYaw(int target) {
        return mTargetYaws[target];
    }

    float getTargetPitch(int target) {
        return mTargetPitches[target];
    }

    /** Whether every cell a target owns is covered. */
    boolean isTargetComplete(int target) {
        return mRemaining[target] == 0;
    }

    /**
     * The target to aim at next: the nearest unfinished one, unless the one already being aimed
     * at is still unfinished and not much further.
     *
     * @param direction where the camera points, as a unit vector
     * @param previous the target the guide showed last, or -1
     * @return the target, or -1 when there is nothing left to aim at
     */
    int nextTarget(@NonNull float[] direction, int previous) {
        int nearest = -1;
        float nearestAngle = Float.MAX_VALUE;
        float[] target = new float[3];
        for (int t = 0; t < mTargetYaws.length; t++) {
            if (mRemaining[t] == 0) {
                continue;
            }
            System.arraycopy(mTargetDirections, t * 3, target, 0, 3);
            float angle = SphereMath.angleBetween(direction, target);
            if (angle < nearestAngle) {
                nearestAngle = angle;
                nearest = t;
            }
        }
        if (previous >= 0 && previous < mTargetYaws.length && mRemaining[previous] > 0
                && previous != nearest) {
            System.arraycopy(mTargetDirections, previous * 3, target, 0, 3);
            if (SphereMath.angleBetween(direction, target)
                    <= nearestAngle + HYSTERESIS_DEGREES) {
                return previous;
            }
        }
        return nearest;
    }
}
