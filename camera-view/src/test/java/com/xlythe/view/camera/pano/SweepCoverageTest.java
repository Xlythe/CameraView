package com.xlythe.view.camera.pano;

import static com.xlythe.view.camera.pano.SphereCanvasTest.pose;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** What a sweep has covered, when it is finished, and where the guide sends the user next. */
public class SweepCoverageTest {
    /** The narrower angle of view of the test camera: its upright width. */
    private static final float NARROWEST = 46.8f;

    @Test
    public void photosphere_aimedAtEveryTarget_isComplete() {
        SweepCoverage coverage = SweepCoverage.forPhotosphere(0f, NARROWEST);
        assertFalse(coverage.isComplete());
        for (int t = 0; t < coverage.getTargetCount(); t++) {
            float yaw = coverage.getTargetYaw(t);
            float pitch = coverage.getTargetPitch(t);
            // Turned a little about the lens at each one, as a hand does, which matters only at
            // the poles: a frame aimed straight up covers a cap, and rolling it covers the corners
            // of the cap the frame's own corners did not.
            for (float roll = 0f; roll < 90f; roll += 30f) {
                coverage.cover(pose(yaw, pitch, roll));
            }
        }
        assertTrue(coverage.isComplete());
        assertEquals(1f, coverage.getProgress(), 0f);
        for (int t = 0; t < coverage.getTargetCount(); t++) {
            assertTrue(coverage.isTargetComplete(t));
        }
        assertEquals(-1, coverage.nextTarget(direction(0f, 0f), -1));
    }

    @Test
    public void photosphere_withoutTheCaps_isNotComplete() {
        SweepCoverage coverage = SweepCoverage.forPhotosphere(0f, NARROWEST);
        for (int t = 0; t < coverage.getTargetCount(); t++) {
            if (Math.abs(coverage.getTargetPitch(t)) < 50f) {
                coverage.cover(pose(coverage.getTargetYaw(t), coverage.getTargetPitch(t), 0f));
            }
        }
        assertFalse(coverage.isComplete());
        // The caps are a small part of the area, so progress is most of the way...
        assertTrue(coverage.getProgress() > 0.8f);
        assertTrue(coverage.getProgress() < 1f);
        // ...and what is left to aim at is up in the cap.
        int next = coverage.nextTarget(direction(0f, 60f), -1);
        assertTrue(coverage.getTargetPitch(next) > 50f);
        assertFalse(coverage.isTargetComplete(next));
    }

    @Test
    public void aFrameOfThePole_coversTheCapAroundIt() {
        SweepCoverage coverage = SweepCoverage.forPhotosphere(0f, NARROWEST);
        coverage.cover(pose(0f, 90f, 0f));
        // Every cell of the top two rows, all the way round.
        for (int row = 0; row < 2; row++) {
            for (int column = 0; column < SweepCoverage.COLUMNS; column++) {
                assertTrue(coverage.isCovered(column, row));
            }
        }
    }

    @Test
    public void aFrame_coversEveryCellItSaw_notJustTheOneItWasAimedAt() {
        SweepCoverage coverage = SweepCoverage.forPhotosphere(0f, NARROWEST);
        int covered = coverage.cover(pose(0f, 0f, 0f));
        // About 44 by 58 degrees inside the margin, in two-degree cells.
        assertTrue(covered > 500);
        assertEquals(0, coverage.cover(pose(0f, 0f, 0f)));
        assertEquals(0, coverage.countUncovered(pose(0f, 0f, 0f)));
        assertTrue(coverage.countUncovered(pose(20f, 0f, 0f)) > 0);
    }

    @Test
    public void uncoveredShare_isWhatPartOfAFrameIsNew() {
        SweepCoverage coverage = SweepCoverage.forPhotosphere(0f, NARROWEST);
        assertEquals(1f, coverage.uncoveredShare(pose(0f, 0f, 0f)), 0f);
        coverage.cover(pose(0f, 0f, 0f));
        assertEquals(0f, coverage.uncoveredShare(pose(0f, 0f, 0f)), 0f);
        // Turned by half its width, about half of it is new.
        assertEquals(0.5f, coverage.uncoveredShare(pose(23.4f, 0f, 0f)), 0.1f);
        // Nothing of a frame aimed straight behind the lens has been seen.
        assertEquals(1f, coverage.uncoveredShare(pose(180f, 0f, 0f)), 0f);
    }

    @Test
    public void aFrameAcrossTheGridsEdge_coversBothSides() {
        SweepCoverage coverage = SweepCoverage.forPhotosphere(0f, NARROWEST);
        coverage.cover(pose(180f, 0f, 0f));
        int row = SweepCoverage.ROWS / 2;
        assertTrue(coverage.isCovered(0, row));
        assertTrue(coverage.isCovered(SweepCoverage.COLUMNS - 1, row));
        assertFalse(coverage.isCovered(SweepCoverage.COLUMNS / 2, row));
    }

    @Test
    public void progress_isMeasuredInArea() {
        SweepCoverage coverage = SweepCoverage.forPhotosphere(0f, NARROWEST);
        for (int yaw = 0; yaw < 360; yaw += 20) {
            coverage.cover(pose(yaw, 0f, 0f));
        }
        // A band of about 56 degrees round the horizon: sin(28) of the sphere's area, which
        // would be a third of the cells but is nearly half the area.
        assertEquals(Math.sin(Math.toRadians(28)), coverage.getProgress(), 0.04f);
    }

    @Test
    public void photosphere_hasRowsUpToEachPole() {
        SweepCoverage coverage = SweepCoverage.forPhotosphere(10f, NARROWEST);
        boolean north = false;
        boolean south = false;
        boolean horizon = false;
        for (int t = 0; t < coverage.getTargetCount(); t++) {
            north |= coverage.getTargetPitch(t) == 90f;
            south |= coverage.getTargetPitch(t) == -90f;
            horizon |= coverage.getTargetPitch(t) == 0f && coverage.getTargetYaw(t) == 10f;
        }
        assertTrue(north && south && horizon);
    }

    @Test
    public void panorama_isCompleteOnceAllTheWayRound() {
        SweepCoverage coverage = SweepCoverage.forPanorama(0f, 0f, NARROWEST);
        for (int yaw = 0; yaw < 180; yaw += 15) {
            coverage.cover(pose(yaw, 0f, 0f));
        }
        assertFalse(coverage.isComplete());
        assertEquals(0.5f, coverage.getProgress(), 0.1f);
        for (int yaw = 180; yaw < 360; yaw += 15) {
            coverage.cover(pose(yaw, 0f, 0f));
        }
        assertTrue(coverage.isComplete());
        assertEquals(1f, coverage.getProgress(), 0f);
    }

    @Test
    public void panorama_ignoresWhatIsAboveAndBelowItsRow() {
        SweepCoverage coverage = SweepCoverage.forPanorama(0f, 0f, NARROWEST);
        assertEquals(0, coverage.cover(pose(0f, 80f, 0f)));
        assertEquals(0f, coverage.getProgress(), 0f);
    }

    @Test
    public void panorama_targetsLieAlongItsPitch() {
        SweepCoverage coverage = SweepCoverage.forPanorama(30f, 15f, NARROWEST);
        assertTrue(coverage.getTargetCount() >= 12);
        for (int t = 0; t < coverage.getTargetCount(); t++) {
            assertEquals(15f, coverage.getTargetPitch(t), 0f);
        }
        assertEquals(30f, coverage.getTargetYaw(0), 0f);
        assertEquals(SweepCoverage.ROWS / 2 - 8, coverage.bandRow);
    }

    @Test
    public void nextTarget_isTheNearestUnfinishedOne() {
        SweepCoverage coverage = SweepCoverage.forPanorama(0f, 0f, NARROWEST);
        coverage.cover(pose(0f, 0f, 0f));
        int next = coverage.nextTarget(direction(15f, 0f), -1);
        assertTrue(coverage.getTargetYaw(next) > 15f);
        next = coverage.nextTarget(direction(-15f, 0f), -1);
        assertTrue(coverage.getTargetYaw(next) < -15f);
    }

    @Test
    public void nextTarget_staysPutUntilAnotherIsClearlyNearer() {
        SweepCoverage coverage = SweepCoverage.forPanorama(0f, 0f, NARROWEST);
        coverage.cover(pose(0f, 0f, 0f));
        int right = coverage.nextTarget(direction(5f, 0f), -1);
        float rightYaw = coverage.getTargetYaw(right);
        // Slightly nearer the target on the left now, but not by enough to switch.
        assertEquals(right, coverage.nextTarget(direction(-3f, 0f), right));
        // Much nearer: switch.
        int left = coverage.nextTarget(direction(-20f, 0f), right);
        assertTrue(coverage.getTargetYaw(left) < 0f);
        assertTrue(rightYaw > 0f);
    }

    @Test
    public void nextTarget_forgetsAFinishedTarget() {
        SweepCoverage coverage = SweepCoverage.forPanorama(0f, 0f, NARROWEST);
        coverage.cover(pose(0f, 0f, 0f));
        int right = coverage.nextTarget(direction(5f, 0f), -1);
        coverage.cover(pose(coverage.getTargetYaw(right), 0f, 0f));
        assertTrue(coverage.isTargetComplete(right));
        assertTrue(coverage.nextTarget(direction(5f, 0f), right) != right);
    }

    @Test
    public void columnsAndRows_haveCentres() {
        SweepCoverage coverage = SweepCoverage.forPanorama(20f, 0f, NARROWEST);
        assertEquals(-159f, coverage.yawOfColumn(0), 0.001f);
        assertEquals(89f, SweepCoverage.pitchOfRow(0), 0.001f);
    }

    private static float[] direction(float yaw, float pitch) {
        float[] direction = new float[3];
        SphereMath.direction(yaw, pitch, direction);
        return direction;
    }
}
