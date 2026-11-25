package com.xlythe.view.camera.pano;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.PointF;
import android.opengl.Matrix;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Whether a frame lands where it should on the canvas.
 *
 * <p>The arithmetic that decides whether a panorama is a panorama or a smear, and all of it
 * testable without a camera: every case here is a known direction with a known answer. The ones
 * worth having are the identities - straight ahead is the middle, a quarter turn is a quarter
 * across - and the two mistakes that look plausible and are not: placing a point in proportion
 * to its angle rather than its tangent, and letting a yaw wander outside the canvas.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class EquirectangularTest {
    private static final float TOLERANCE = 0.5f;

    /** The identity rotation, which is a camera pointed along the world's negative z. */
    private static float[] facingForward() {
        float[] matrix = new float[16];
        Matrix.setIdentityM(matrix, 0);
        return matrix;
    }

    /** A camera turned by a yaw, which is a rotation about the world's y axis. */
    private static float[] facing(float yawDegrees, float pitchDegrees) {
        float[] matrix = new float[16];
        Matrix.setIdentityM(matrix, 0);
        Matrix.rotateM(matrix, 0, yawDegrees, 0f, 1f, 0f);
        Matrix.rotateM(matrix, 0, pitchDegrees, 1f, 0f, 0f);
        return matrix;
    }

    @Test
    public void straightAheadIsTheMiddleOfTheCanvas() {
        PointF at = new PointF();

        Equirectangular.project(0f, 0f, 4000, 2000, at);

        assertEquals(2000f, at.x, TOLERANCE);
        assertEquals(1000f, at.y, TOLERANCE);
    }

    /**
     * Both edges of the canvas are the same meridian, and both land on it.
     *
     * <p>Asserted because the alternative is tempting and wrong: treating 180 as the right edge
     * puts it at an x of exactly the canvas width, which is one past the last column. A frame
     * straddling the back of the sphere then writes a stripe outside the canvas.</p>
     */
    @Test
    public void theEdgesOfTheCanvasAreTheSamePlace() {
        PointF left = new PointF();
        PointF right = new PointF();

        Equirectangular.project(-180f, 0f, 4000, 2000, left);
        Equirectangular.project(180f, 0f, 4000, 2000, right);

        assertEquals(0f, left.x, TOLERANCE);
        assertEquals("a half turn either way is the same column", left.x, right.x, TOLERANCE);
    }

    /** Nothing projects past the last column, whatever yaw it is given. */
    @Test
    public void everyYawLandsInsideTheCanvas() {
        PointF at = new PointF();
        for (float yaw = -540f; yaw <= 540f; yaw += 7.5f) {
            Equirectangular.project(yaw, 0f, 4000, 2000, at);
            assertTrue("yaw " + yaw + " landed at " + at.x, at.x >= 0f && at.x < 4000f);
        }
    }

    /** Up is the top, which is the one place a canvas and the world disagree about direction. */
    @Test
    public void theSkyIsAtTheTop() {
        PointF at = new PointF();

        Equirectangular.project(0f, 90f, 4000, 2000, at);
        assertEquals(0f, at.y, TOLERANCE);

        Equirectangular.project(0f, -90f, 4000, 2000, at);
        assertEquals(2000f, at.y, TOLERANCE);
    }

    @Test
    public void theCentreOfAForwardFrameIsStraightAhead() {
        float[] direction = new float[2];

        assertTrue(Equirectangular.directionFor(0.5f, 0.5f, facingForward(), 60f, 45f, direction));

        assertEquals("yaw", 0f, direction[0], TOLERANCE);
        assertEquals("pitch", 0f, direction[1], TOLERANCE);
    }

    /**
     * The edge of the frame is half the angle of view away, which is the easy half to get wrong.
     *
     * <p>A frame is a flat window, so its right edge is at exactly half the horizontal angle of
     * view - no more and no less. This is the assertion that fails if the window is treated as
     * curved.</p>
     */
    @Test
    public void theEdgeOfAFrameIsHalfTheAngleOfView() {
        float[] direction = new float[2];

        assertTrue(Equirectangular.directionFor(1f, 0.5f, facingForward(), 60f, 45f, direction));

        assertEquals(30f, direction[0], TOLERANCE);
        assertEquals(0f, direction[1], TOLERANCE);
    }

    /**
     * A point partway out is not partway round, which is the mistake this exists to catch.
     *
     * <p>Halfway from the centre to the right edge of a sixty degree frame is not fifteen
     * degrees: the window is flat, so it is the angle whose tangent is half of the edge's,
     * which is a little over sixteen. Placing frames the other way bows the horizon.</p>
     */
    @Test
    public void aPointIsPlacedByItsTangentRatherThanItsAngle() {
        float[] direction = new float[2];

        assertTrue(Equirectangular.directionFor(0.75f, 0.5f, facingForward(), 60f, 45f, direction));

        float naive = 15f;
        float correct = (float) Math.toDegrees(Math.atan(0.5 * Math.tan(Math.toRadians(30))));
        assertEquals(correct, direction[0], TOLERANCE);
        assertTrue("the tangent answer should be the larger one, by about a degree",
                correct > naive + 1f);
    }

    @Test
    public void turningTheCameraTurnsWhereTheFrameLands() {
        float[] direction = new float[2];

        assertTrue(Equirectangular.directionFor(0.5f, 0.5f, facing(90f, 0f), 60f, 45f, direction));

        assertEquals("a quarter turn to the left of straight ahead",
                -90f, direction[0], TOLERANCE);
    }

    @Test
    public void raisingTheCameraRaisesWhereTheFrameLands() {
        float[] direction = new float[2];

        assertTrue(Equirectangular.directionFor(0.5f, 0.5f, facing(0f, 30f), 60f, 45f, direction));

        assertEquals(30f, direction[1], TOLERANCE);
        assertEquals(0f, direction[0], TOLERANCE);
    }

    /** The top of the frame is above its centre, not below: a canvas counts down, the world up. */
    @Test
    public void theTopOfTheFrameIsAboveTheMiddle() {
        float[] direction = new float[2];

        assertTrue(Equirectangular.directionFor(0.5f, 0f, facingForward(), 60f, 45f, direction));

        assertEquals(22.5f, direction[1], TOLERANCE);
    }

    @Test
    public void anAngleOfViewThatCannotBeIsRefused() {
        float[] direction = new float[2];

        assertFalse("no angle of view at all",
                Equirectangular.directionFor(0.5f, 0.5f, facingForward(), 0f, 45f, direction));
        assertFalse("a lens cannot see a half turn",
                Equirectangular.directionFor(0.5f, 0.5f, facingForward(), 180f, 45f, direction));
        assertFalse("not a matrix",
                Equirectangular.directionFor(0.5f, 0.5f, new float[9], 60f, 45f, direction));
    }

    @Test
    public void aYawIsBroughtBackOntoTheCanvas() {
        assertEquals(10f, Equirectangular.wrapYaw(370f), TOLERANCE);
        assertEquals(-10f, Equirectangular.wrapYaw(-370f), TOLERANCE);
        assertEquals(-179f, Equirectangular.wrapYaw(181f), TOLERANCE);
        assertEquals("a half turn is the left edge, not the right one",
                -180f, Equirectangular.wrapYaw(180f), TOLERANCE);
        assertEquals(-180f, Equirectangular.wrapYaw(-180f), TOLERANCE);
        assertEquals(0f, Equirectangular.wrapYaw(720f), TOLERANCE);
    }

    /** Crossing the back of the sphere is a short step, which is what a sweep needs to know. */
    @Test
    public void theDistanceBetweenYawsIsTheShortWayRound() {
        assertEquals(20f, Equirectangular.yawDistance(170f, -170f), TOLERANCE);
        assertEquals(20f, Equirectangular.yawDistance(-170f, 170f), TOLERANCE);
        assertEquals(180f, Equirectangular.yawDistance(0f, 180f), TOLERANCE);
        assertEquals(0f, Equirectangular.yawDistance(90f, 90f), TOLERANCE);
    }

    @Test
    public void theCanvasIsTwiceAsWideAsItIsTall() {
        assertEquals(2000, Equirectangular.canvasHeightFor(4000));
    }

    @Test
    public void aPitchCannotGoPastThePole() {
        assertEquals(90f, Equirectangular.clampPitch(120f), TOLERANCE);
        assertEquals(-90f, Equirectangular.clampPitch(-120f), TOLERANCE);
    }

    /** The whole chain, so that a wrong sign anywhere in it is caught once. */
    @Test
    public void aQuarterTurnLandsAQuarterAcrossTheCanvas() {
        PointF at = new PointF();

        assertTrue(Equirectangular.projectFramepoint(0.5f, 0.5f, facing(-90f, 0f),
                60f, 45f, 4000, 2000, at));

        assertEquals("a quarter turn to the right is three quarters across",
                3000f, at.x, TOLERANCE);
        assertEquals(1000f, at.y, TOLERANCE);
    }
}
