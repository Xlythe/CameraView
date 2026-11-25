package com.xlythe.view.camera.pano;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.hardware.SensorManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Whether a sensor reading means what the painting code thinks it means.
 *
 * <p>The one part of a panorama that cannot be noticed by looking at the code and is obvious the
 * moment you look at the output: Android reports rotation against East-North-Up, this package
 * paints in the frame a camera uses, and getting the conversion wrong produces a panorama that
 * is a quarter turn out and on its side. Every case here is a device in a pose anybody can
 * picture, with the direction its camera must be pointing.</p>
 *
 * <p>Checked through {@link Equirectangular#directionFor}, because that is what consumes the
 * matrix - a convention that is self-consistent but wrong between the two would pass a test of
 * either one alone.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class DeviceOrientationTest {
    private static final float TOLERANCE = 0.5f;

    /**
     * The rotation vector for a device held upright, its back camera facing a compass bearing.
     *
     * <p>Upright means the screen vertical and the long edge up, which is how anyone sweeps a
     * panorama. In that pose the device's own z points out of the screen, so a rotation about
     * the world's vertical axis is what aims the camera.</p>
     */
    private static float[] upright(float bearingDegrees) {
        // A quarter turn about east stands the device up from flat on its back; then a turn
        // about up aims it. Expressed as a rotation vector, which is the axis times the sine of
        // half the angle, through the matrix call the class under test uses.
        float[] enu = new float[16];
        float[] standUp = new float[16];
        float[] aim = new float[16];
        android.opengl.Matrix.setIdentityM(aim, 0);
        android.opengl.Matrix.rotateM(aim, 0, -bearingDegrees, 0f, 0f, 1f);
        android.opengl.Matrix.setIdentityM(standUp, 0);
        android.opengl.Matrix.rotateM(standUp, 0, 90f, 1f, 0f, 0f);
        android.opengl.Matrix.multiplyMM(enu, 0, aim, 0, standUp, 0);
        return enu;
    }

    /** Where the camera is pointing, in yaw and pitch, for a device-to-ENU rotation. */
    private static float[] cameraDirection(float[] eastNorthUp) {
        float[] panorama = new float[16];
        DeviceOrientation.toPanoramaFrame(eastNorthUp, panorama);
        float[] direction = new float[2];
        assertTrue("the converted matrix should be usable",
                Equirectangular.directionFor(0.5f, 0.5f, panorama, 60f, 45f, direction));
        return direction;
    }

    /** A phone held up facing north has its camera on the horizon, dead ahead. */
    @Test
    public void uprightFacingNorthIsStraightAheadAndLevel() {
        float[] direction = cameraDirection(upright(0f));

        assertEquals("yaw", 0f, direction[0], TOLERANCE);
        assertEquals("pitch", 0f, direction[1], TOLERANCE);
    }

    /** Turning to face east is a quarter turn of yaw, and still level. */
    @Test
    public void uprightFacingEastIsAQuarterTurn() {
        float[] direction = cameraDirection(upright(90f));

        assertEquals(90f, direction[0], TOLERANCE);
        assertEquals("turning does not tilt the camera", 0f, direction[1], TOLERANCE);
    }

    @Test
    public void uprightFacingSouthIsAHalfTurn() {
        float[] direction = cameraDirection(upright(180f));

        assertEquals("a half turn either way is the same direction",
                180f, Math.abs(direction[0]), TOLERANCE);
        assertEquals(0f, direction[1], TOLERANCE);
    }

    @Test
    public void uprightFacingWestIsAQuarterTurnTheOtherWay() {
        float[] direction = cameraDirection(upright(270f));

        assertEquals(-90f, direction[0], TOLERANCE);
        assertEquals(0f, direction[1], TOLERANCE);
    }

    /**
     * A phone lying flat on its back has its camera pointed at the ground.
     *
     * <p>The case that pins which way up the world is. An identity rotation vector is a device
     * flat on a table, screen up - so the camera on its back looks straight down, and a
     * conversion that mixed up the vertical axis would report the sky.</p>
     */
    @Test
    public void flatOnItsBackTheCameraLooksDown() {
        float[] flat = new float[16];
        android.opengl.Matrix.setIdentityM(flat, 0);

        float[] direction = cameraDirection(flat);

        assertEquals("the camera is pointed at the floor", -90f, direction[1], TOLERANCE);
    }

    /** Tilting a phone up from upright raises the camera by the same amount. */
    @Test
    public void tiltingBackRaisesTheCamera() {
        float[] enu = upright(0f);
        float[] tilted = new float[16];
        // About the device's own x axis, which is the short edge: tipping the top away.
        android.opengl.Matrix.setIdentityM(tilted, 0);
        android.opengl.Matrix.rotateM(tilted, 0, 30f, 1f, 0f, 0f);
        float[] combined = new float[16];
        android.opengl.Matrix.multiplyMM(combined, 0, enu, 0, tilted, 0);

        float[] direction = cameraDirection(combined);

        assertEquals("the camera should be thirty degrees above the horizon",
                30f, direction[1], TOLERANCE);
        assertEquals("and still facing north", 0f, direction[0], TOLERANCE);
    }

    /** The conversion is a rotation, so it cannot change the length of anything. */
    @Test
    public void theConversionIsARotation() {
        float[] panorama = new float[16];

        DeviceOrientation.toPanoramaFrame(upright(37f), panorama);

        for (int column = 0; column < 3; column++) {
            float x = panorama[column * 4];
            float y = panorama[column * 4 + 1];
            float z = panorama[column * 4 + 2];
            assertEquals("column " + column + " should still be a unit vector",
                    1f, Math.sqrt(x * x + y * y + z * z), 0.001f);
        }
        assertEquals("the bottom right of a rotation matrix is one", 1f, panorama[15], 0.001f);
    }

    /** Converting in place would read values it had already overwritten, so it is refused. */
    @Test
    public void convertingInPlaceIsRefused() {
        float[] matrix = new float[16];
        android.opengl.Matrix.setIdentityM(matrix, 0);

        try {
            DeviceOrientation.toPanoramaFrame(matrix, matrix);
            fail("converting a matrix onto itself should be refused");
        } catch (IllegalArgumentException expected) {
            // What should happen.
        }
    }

    @Test
    public void aMatrixThatIsNotAMatrixIsRefused() {
        try {
            DeviceOrientation.toPanoramaFrame(new float[9], new float[16]);
            fail("a nine float matrix should be refused");
        } catch (IllegalArgumentException expected) {
            // What should happen.
        }
    }

    /** The reading really does come from the framework call, not from a hand-built matrix. */
    @Test
    public void aRotationVectorFromTheFrameworkConvertsToo() {
        // A quarter turn about the vertical axis, as a rotation vector: the axis times the sine
        // of half the angle.
        float half = (float) Math.toRadians(90) / 2;
        float[] vector = {0f, 0f, (float) Math.sin(half), (float) Math.cos(half)};
        float[] enu = new float[16];
        SensorManager.getRotationMatrixFromVector(enu, vector);
        float[] panorama = new float[16];

        DeviceOrientation.toPanoramaFrame(enu, panorama);

        float[] direction = new float[2];
        assertTrue(Equirectangular.directionFor(0.5f, 0.5f, panorama, 60f, 45f, direction));
        assertEquals("still pointed at the ground, just rotated about it",
                -90f, direction[1], TOLERANCE);
    }
}
