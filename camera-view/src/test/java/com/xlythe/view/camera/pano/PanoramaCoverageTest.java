package com.xlythe.view.camera.pano;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Whether a sweep knows what it has covered and where to go next.
 *
 * <p>This is the part of a panorama that the person holding the phone interacts with, so the
 * cases that matter are the human ones: pausing, turning the wrong way, wandering off the row,
 * and crossing the back of the sphere. Each of those used to be a way to report nonsense -
 * progress that never moved, or a sweep that could not be finished.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class PanoramaCoverageTest {
    private static final float TOLERANCE = 0.01f;

    @Test
    public void nothingIsCoveredBeforeAnythingIsSeen() {
        PanoramaCoverage coverage = PanoramaCoverage.forPanorama();

        assertEquals(0f, coverage.getProgress(), TOLERANCE);
        assertFalse(coverage.isComplete());
        assertEquals(0f, coverage.getSweptDegrees(), TOLERANCE);
    }

    @Test
    public void thefirstFrameSetsWhereTheSweepStarted() {
        PanoramaCoverage coverage = PanoramaCoverage.forPanorama();

        coverage.cover(42f, 0f);

        assertEquals(42f, coverage.getStartYaw(), TOLERANCE);
        assertTrue(coverage.getProgress() > 0f);
    }

    /**
     * Pointing at the same place twice is only new ground once.
     *
     * <p>What the return value is for. A sweep that pauses delivers the same place thirty times
     * a second, and a pipeline that redrew each of them would spend its time blending a frame
     * into itself.</p>
     */
    @Test
    public void thesamePlaceIsOnlyNewOnce() {
        PanoramaCoverage coverage = PanoramaCoverage.forPanorama();

        assertTrue("the first look at a cell is new", coverage.cover(0f, 0f));
        assertFalse("the second is not", coverage.cover(0f, 0f));
        assertFalse("nor is a wobble within the same cell", coverage.cover(1f, 1f));
    }

    @Test
    public void turningCoversNewGround() {
        PanoramaCoverage coverage = PanoramaCoverage.forPanorama();
        coverage.cover(0f, 0f);

        assertTrue(coverage.cover(10f, 0f));
        assertTrue(coverage.cover(20f, 0f));
        assertEquals(20f, coverage.getSweptDegrees(), TOLERANCE);
    }

    /** Turning the wrong way is still progress, which it would not be if the start were an edge. */
    @Test
    public void turningEitherWayCounts() {
        PanoramaCoverage coverage = PanoramaCoverage.forPanorama();
        coverage.cover(0f, 0f);

        assertTrue("to the left", coverage.cover(-10f, 0f));
        assertTrue("to the right", coverage.cover(10f, 0f));
        assertEquals(20f, coverage.getSweptDegrees(), TOLERANCE);
    }

    /**
     * A sweep that crosses the back of the sphere keeps counting.
     *
     * <p>The case that breaks if yaws are compared as plain numbers: a sweep from 170 through
     * 180 to -170 has gone twenty degrees, and treating it as a jump of 340 would either report
     * the sweep as finished or throw the frames away.</p>
     */
    @Test
    public void crossingTheBackOfTheSphereIsASmallStep() {
        PanoramaCoverage coverage = PanoramaCoverage.forPanorama();
        coverage.cover(170f, 0f);

        assertTrue(coverage.cover(178f, 0f));
        assertTrue(coverage.cover(-178f, 0f));
        assertTrue(coverage.cover(-170f, 0f));
        assertEquals(20f, coverage.getSweptDegrees(), TOLERANCE);
    }

    /** Pointing outside the band being asked for is not covered, and not an error either. */
    @Test
    public void pointingOutsideTheBandCoversNothing() {
        PanoramaCoverage coverage = PanoramaCoverage.forPanorama();
        coverage.cover(0f, 0f);

        assertFalse("straight up is not part of a flat panorama", coverage.cover(0f, 80f));
        assertFalse(coverage.cover(0f, -80f));
    }

    @Test
    public void aFullTurnAtEyeLevelFinishesAPanorama() {
        PanoramaCoverage coverage = PanoramaCoverage.forPanorama();

        for (float yaw = -180f; yaw < 180f; yaw += 1f) {
            coverage.cover(yaw, 0f);
            coverage.cover(yaw, 4f);
            coverage.cover(yaw, -4f);
        }

        assertTrue("a full turn through every row should finish it, got "
                + coverage.getProgress(), coverage.isComplete());
        assertEquals(1f, coverage.getProgress(), TOLERANCE);
    }

    /** A full turn at eye level is not a photosphere, which wants the sky and the floor too. */
    @Test
    public void aFullTurnAtEyeLevelDoesNotFinishAPhotosphere() {
        PanoramaCoverage coverage = PanoramaCoverage.forPhotosphere();

        for (float yaw = -180f; yaw < 180f; yaw += 1f) {
            coverage.cover(yaw, 0f);
        }

        assertFalse(coverage.isComplete());
        assertTrue("some progress", coverage.getProgress() > 0f);
        assertTrue("but not much of a sphere", coverage.getProgress() < 0.2f);
    }

    @Test
    public void thenextPlaceToPointIsSomewhereNotCovered() {
        PanoramaCoverage coverage = PanoramaCoverage.forPanorama();
        coverage.cover(0f, 0f);
        float[] next = new float[2];

        assertTrue(coverage.suggestNext(0f, 0f, next));

        assertFalse("it should not send you back where you already are",
                Math.abs(Equirectangular.wrapYaw(next[0])) < 1f && Math.abs(next[1]) < 1f);
    }

    /**
     * The suggestion is the nearest gap, not the next cell in order.
     *
     * <p>So that somebody who drifted off the row is sent to the hole they left rather than
     * made to finish the circle and come back for it.</p>
     */
    @Test
    public void thenextPlaceIsTheNearestGap() {
        PanoramaCoverage coverage = PanoramaCoverage.forPanorama();
        // Everything covered except a band behind where we now stand. The band is wider than a
        // cell on purpose: a cell is five degrees across, so skipping less than that still
        // leaves it covered by its own edges and there would be no gap to find.
        float gapFrom = 25f;
        float gapTo = 35f;
        for (float yaw = -180f; yaw < 180f; yaw += 1f) {
            if (yaw >= gapFrom && yaw <= gapTo) {
                continue;
            }
            for (float pitch = -4f; pitch <= 4f; pitch += 4f) {
                coverage.cover(yaw, pitch);
            }
        }
        float[] next = new float[2];

        assertTrue("there should be a gap left to find", coverage.suggestNext(90f, 0f, next));

        assertTrue("the gap is between " + gapFrom + " and " + gapTo + ", got " + next[0],
                next[0] >= gapFrom - PanoramaCoverage.CELL_DEGREES
                        && next[0] <= gapTo + PanoramaCoverage.CELL_DEGREES);
    }

    @Test
    public void thereIsNothingToSuggestOnceItIsDone() {
        PanoramaCoverage coverage = new PanoramaCoverage(10f, -2f, 2f);
        coverage.cover(0f, 0f);
        for (float yaw = -10f; yaw <= 10f; yaw += 1f) {
            coverage.cover(yaw, 0f);
        }
        float[] next = new float[2];

        assertTrue(coverage.isComplete());
        assertFalse(coverage.suggestNext(0f, 0f, next));
    }

    /** A sweep asked for in degrees reports the band and span it was asked for. */
    @Test
    public void itremembersWhatWasAskedFor() {
        PanoramaCoverage coverage = new PanoramaCoverage(90f, -20f, 20f);

        assertEquals(90f, coverage.getYawSpan(), TOLERANCE);
        assertEquals(-20f, coverage.getMinPitch(), TOLERANCE);
        assertEquals(20f, coverage.getMaxPitch(), TOLERANCE);
    }

    /** A span nobody could sweep is clamped rather than producing a grid with no cells. */
    @Test
    public void animpossibleSpanIsClamped() {
        PanoramaCoverage tiny = new PanoramaCoverage(0f, 0f, 0f);
        tiny.cover(0f, 0f);

        assertTrue("a zero span should still have somewhere to put a frame",
                tiny.getProgress() > 0f);

        PanoramaCoverage huge = new PanoramaCoverage(1000f, -200f, 200f);
        assertEquals(360f, huge.getYawSpan(), TOLERANCE);
        assertEquals(-90f, huge.getMinPitch(), TOLERANCE);
        assertEquals(90f, huge.getMaxPitch(), TOLERANCE);
    }
}
