package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.PointF;
import android.opengl.Matrix;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Putting a place in the world onto the view showing it.
 *
 * <p>Every mistake available here is silent. A marker an app draws at a wrong position still looks
 * like a marker - it just points at the wrong thing, or drifts as the camera turns, or sits on the
 * wrong side of the sofa. So the values are checked against arithmetic that can be done by hand,
 * and the three classic sign errors - the y axis of a view running the opposite way to the world,
 * a point behind the camera projecting as though it were in front, and a depth test that hides
 * what it should show - each have a test of their own.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class WorldOverlayTest {
    private static final int WIDTH = 1080;
    private static final int HEIGHT = 1920;

    /** Straight ahead is the middle, whatever the angle of view. */
    @Test
    public void aBearingOfZeroIsTheMiddleOfTheView() {
        assertEquals(WIDTH / 2f,
                WorldOverlay.screenXForBearing(0f, 67f, WIDTH), 0.01f);
        assertEquals(WIDTH / 2f,
                WorldOverlay.screenXForBearing(0f, 20f, WIDTH), 0.01f);
    }

    /** Half the angle of view away is the edge, which is what makes the angle worth knowing. */
    @Test
    public void halfTheAngleOfViewIsTheEdge() {
        assertEquals("the right edge",
                WIDTH, WorldOverlay.screenXForBearing(30f, 60f, WIDTH), 0.5f);
        assertEquals("and the left",
                0f, WorldOverlay.screenXForBearing(-30f, 60f, WIDTH), 0.5f);
    }

    /**
     * And a quarter of the way is not half way across.
     *
     * <p>This is the whole reason to go through this rather than scaling the angle: the view is a
     * projection, so position goes as the tangent. At 15 degrees into a 60 degree view the
     * tangent is 0.2679 against a half-extent of 0.5774, which is 0.4641 of the way out - 0.732
     * of the half-width, not 0.5 of it.</p>
     */
    @Test
    public void aBearingProjectsByTangentRatherThanInProportion() {
        float x = WorldOverlay.screenXForBearing(15f, 60f, WIDTH);
        float halfway = WIDTH / 2f + WIDTH / 4f;

        assertEquals(WIDTH / 2f + WIDTH / 2f * 0.4641f, x, 1f);
        assertTrue("scaling the angle would have put it at " + halfway + ", not " + x,
                x < halfway);
    }

    /** Up in the world is down the view, and the flip belongs here rather than at the call site. */
    @Test
    public void elevationIsFlippedForTheView() {
        assertEquals("the middle", HEIGHT / 2f,
                WorldOverlay.screenYForElevation(0f, 50f, HEIGHT), 0.01f);
        assertEquals("looking up puts it at the top of the view",
                0f, WorldOverlay.screenYForElevation(25f, 50f, HEIGHT), 0.5f);
        assertEquals("and down at the bottom",
                HEIGHT, WorldOverlay.screenYForElevation(-25f, 50f, HEIGHT), 0.5f);
    }

    /** Out of frame is reported as a position outside the view, not clamped to its edge. */
    @Test
    public void aBearingOutOfFrameIsOutsideTheView() {
        assertTrue("past the right edge",
                WorldOverlay.screenXForBearing(45f, 60f, WIDTH) > WIDTH);
        assertTrue("past the left",
                WorldOverlay.screenXForBearing(-45f, 60f, WIDTH) < 0f);
    }

    /** With no angle of view, or at a right angle, there is no answer to give. */
    @Test
    public void anImpossibleBearingHasNoPosition() {
        assertTrue(Float.isNaN(WorldOverlay.screenXForBearing(10f, 0f, WIDTH)));
        assertTrue(Float.isNaN(WorldOverlay.screenXForBearing(10f, 180f, WIDTH)));
        assertTrue(Float.isNaN(WorldOverlay.screenXForBearing(10f, 60f, 0)));
        assertTrue("a right angle is parallel to the view plane",
                Float.isNaN(WorldOverlay.screenXForBearing(90f, 60f, WIDTH)));
        assertTrue(Float.isNaN(WorldOverlay.screenYForElevation(-90f, 50f, HEIGHT)));
    }

    /** A point dead ahead projects to the middle of the view. */
    @Test
    public void aPointAheadProjectsToTheMiddle() {
        PointF at = new PointF();

        assertTrue(WorldOverlay.project(viewProjection(), 0f, 0f, -5f, WIDTH, HEIGHT, at));

        assertEquals(WIDTH / 2f, at.x, 1f);
        assertEquals(HEIGHT / 2f, at.y, 1f);
    }

    /** Right of the camera is right of the view, and above it is above. */
    @Test
    public void aPointProjectsToTheRightSideUp() {
        PointF right = new PointF();
        PointF up = new PointF();
        float[] viewProjection = viewProjection();

        assertTrue(WorldOverlay.project(viewProjection, 1f, 0f, -5f, WIDTH, HEIGHT, right));
        assertTrue(WorldOverlay.project(viewProjection, 0f, 1f, -5f, WIDTH, HEIGHT, up));

        assertTrue("a point to the right belongs right of centre", right.x > WIDTH / 2f);
        assertEquals("and at the same height", HEIGHT / 2f, right.y, 1f);
        assertTrue("a point above belongs above centre, which is a smaller y", up.y < HEIGHT / 2f);
        assertEquals("and at the same place across", WIDTH / 2f, up.x, 1f);
    }

    /**
     * A point behind the camera is refused rather than projected.
     *
     * <p>Dividing by a negative w mirrors the point through the centre, so something behind you
     * lands plausibly in front - a marker for the keys you have already walked past, drawn on the
     * wall ahead. The sign of w is the only thing that catches it.</p>
     */
    @Test
    public void aPointBehindTheCameraIsRefused() {
        PointF at = new PointF(-1f, -1f);

        assertFalse("behind the camera", WorldOverlay.project(
                viewProjection(), 0f, 0f, 5f, WIDTH, HEIGHT, at));
        assertEquals("and the caller's point is left alone", -1f, at.x, 0f);
        assertEquals(-1f, at.y, 0f);

        assertFalse("and exactly at it is just as unanswerable", WorldOverlay.project(
                viewProjection(), 0f, 0f, 0f, WIDTH, HEIGHT, at));
    }

    /** A matrix that is not a matrix, or a view with no size, is refused too. */
    @Test
    public void anImpossibleProjectionIsRefused() {
        PointF at = new PointF();
        assertFalse(WorldOverlay.project(new float[4], 0f, 0f, -5f, WIDTH, HEIGHT, at));
        assertFalse(WorldOverlay.project(viewProjection(), 0f, 0f, -5f, 0, HEIGHT, at));
        assertFalse(WorldOverlay.project(viewProjection(), 0f, 0f, -5f, WIDTH, 0, at));
    }

    /** How far away something is, which is what a depth reading gets compared against. */
    @Test
    public void reportsHowFarAwayAPointIs() {
        assertEquals("five metres down the view direction",
                5f, WorldOverlay.distanceFromCamera(view(), 0f, 0f, -5f), 0.001f);
        assertEquals("sideways does not count as distance along the view",
                0f, WorldOverlay.distanceFromCamera(view(), 3f, 0f, 0f), 0.001f);
        assertTrue("behind the camera is a negative distance",
                WorldOverlay.distanceFromCamera(view(), 0f, 0f, 2f) < 0f);
        assertTrue(Float.isNaN(WorldOverlay.distanceFromCamera(new float[4], 0f, 0f, -5f)));
    }

    /** Something nearer than the marker hides it; something further away does not. */
    @Test
    public void nearerSceneHidesTheMarker() {
        assertTrue("a sofa two metres away hides a pillar five metres away",
                WorldOverlay.isOccluded(5f, 2f, 0.1f));
        assertFalse("a wall ten metres away does not",
                WorldOverlay.isOccluded(5f, 10f, 0.1f));
    }

    /**
     * A reading within the tolerance leaves the marker alone.
     *
     * <p>Depth is noisy and is measured a moment apart from the pose, so an exact comparison makes
     * a marker flicker along every edge it is near.</p>
     */
    @Test
    public void aCloseReadingDoesNotFlickerTheMarker() {
        assertFalse("within the tolerance is not in the way",
                WorldOverlay.isOccluded(5f, 4.95f, 0.1f));
        assertTrue("beyond it is", WorldOverlay.isOccluded(5f, 4.5f, 0.1f));
    }

    /** No depth reading means show the marker, because guessing either way is worse. */
    @Test
    public void noDepthReadingShowsTheMarker() {
        assertFalse(WorldOverlay.isOccluded(5f, 0f, 0.1f));
        assertFalse(WorldOverlay.isOccluded(5f, -1f, 0.1f));
        assertFalse(WorldOverlay.isOccluded(5f, Float.NaN, 0.1f));
        assertFalse(WorldOverlay.isOccluded(Float.NaN, 2f, 0.1f));
    }

    /**
     * A pillar whose base is hidden still shows its top.
     *
     * <p>The point of a pillar of light is to be findable over the furniture, so losing the whole
     * marker because the floor beside it is behind a sofa would defeat it.</p>
     */
    @Test
    public void aPillarBehindSomethingLowStillShowsItsTop() {
        // The scene is close low down the view and far away higher up, which is what a sofa in
        // front of an open room looks like to a depth reading.
        WorldOverlay.SceneDepth sofa = (x, y) -> y > HEIGHT / 2f ? 1f : 100f;

        WorldOverlay.VisibleRun visible = WorldOverlay.visiblePillar(
                view(), viewProjection(), 0f, -1.5f /* ground */, -5f, 3f /* metres tall */,
                WIDTH, HEIGHT, 32 /* samples */, 0.1f, sofa);

        assertNotNull("the top of the pillar is not hidden", visible);
        assertTrue("and what shows is the upper part of it",
                visible.getTo().y <= HEIGHT / 2f);
        assertTrue("the visible stretch runs upwards",
                visible.getTo().y <= visible.getFrom().y);
    }

    /** A pillar entirely behind a wall shows nothing at all. */
    @Test
    public void aPillarBehindAWallShowsNothing() {
        WorldOverlay.SceneDepth wall = (x, y) -> 1f;

        assertNull(WorldOverlay.visiblePillar(
                view(), viewProjection(), 0f, -1.5f, -5f, 3f,
                WIDTH, HEIGHT, 16, 0.1f, wall));
    }

    /** A pillar in the open shows all of itself. */
    @Test
    public void aPillarInTheOpenShowsAllOfItself() {
        WorldOverlay.SceneDepth openRoom = (x, y) -> 100f;

        WorldOverlay.VisibleRun visible = WorldOverlay.visiblePillar(
                view(), viewProjection(), 0f, -1.5f, -5f, 3f,
                WIDTH, HEIGHT, 16, 0.1f, openRoom);

        assertNotNull(visible);
        assertTrue("the bottom is below the top on screen",
                visible.getFrom().y > visible.getTo().y);
    }

    /** Nonsense asked of a pillar gets nothing back rather than a guess. */
    @Test
    public void anImpossiblePillarIsRefused() {
        WorldOverlay.SceneDepth open = (x, y) -> 100f;
        assertNull("one sample is not a pillar", WorldOverlay.visiblePillar(
                view(), viewProjection(), 0f, 0f, -5f, 3f, WIDTH, HEIGHT, 1, 0.1f, open));
        assertNull("nor is one with no height", WorldOverlay.visiblePillar(
                view(), viewProjection(), 0f, 0f, -5f, 0f, WIDTH, HEIGHT, 16, 0.1f, open));
    }

    /** A camera at the origin looking down its own negative z, as a tracked session reports it. */
    private static float[] view() {
        float[] matrix = new float[16];
        Matrix.setLookAtM(matrix, 0,
                0f, 0f, 0f /* eye */,
                0f, 0f, -1f /* centre */,
                0f, 1f, 0f /* up */);
        return matrix;
    }

    /** That camera's view matrix with a plain perspective projection in front of it. */
    private static float[] viewProjection() {
        float[] projection = new float[16];
        // A 90 degree vertical field of view, so the half-extent is exactly 1 and the arithmetic
        // above can be checked by hand.
        Matrix.perspectiveM(projection, 0, 90f, (float) WIDTH / HEIGHT, 0.1f, 100f);
        float[] viewProjection = new float[16];
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view(), 0);
        return viewProjection;
    }
}
