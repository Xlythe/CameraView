package com.xlythe.view.camera.pano;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import android.hardware.SensorManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Random;

/**
 * The rotations a sweep is placed by, checked against Android's own reading of the sensor.
 *
 * <p>Poses are built the way a phone reports them - a rotation vector run through
 * {@link SensorManager#getRotationMatrixFromVector} - and checked against where a person holding
 * the phone that way would say the camera points. That is what catches a matrix read along the
 * wrong axis: transposing a rotation is another rotation, and every number it produces is
 * plausible.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class SphereMathTest {
    private static final float EPSILON = 1e-4f;

    @Test
    public void rotationMatrix_matchesAndroid() {
        Random random = new Random(7);
        for (int i = 0; i < 50; i++) {
            float[] vector = randomRotationVector(random);
            float[] android = new float[9];
            SensorManager.getRotationMatrixFromVector(android, vector);
            float[] quaternion = new float[4];
            SphereMath.quaternionFromRotationVector(vector, quaternion);
            float[] ours = new float[9];
            SphereMath.rotationMatrix(quaternion, ours);
            assertArrayEquals(android, ours, EPSILON);
        }
    }

    @Test
    public void quaternion_withoutW_isCompletedToUnitLength() {
        float[] full = {0.1f, 0.2f, 0.3f, (float) Math.sqrt(1 - 0.01 - 0.04 - 0.09)};
        float[] three = {0.1f, 0.2f, 0.3f};
        float[] fromFull = new float[4];
        float[] fromThree = new float[4];
        SphereMath.quaternionFromRotationVector(full, fromFull);
        SphereMath.quaternionFromRotationVector(three, fromThree);
        assertArrayEquals(fromFull, fromThree, EPSILON);
    }

    @Test
    public void quaternion_slightlyOverUnit_hasNoNegativeRoot() {
        float[] quaternion = new float[4];
        SphereMath.quaternionFromRotationVector(new float[] {1.0001f, 0f, 0f}, quaternion);
        assertEquals(0f, quaternion[0], EPSILON);
        assertEquals(1f, quaternion[1], EPSILON);
    }

    @Test
    public void quaternion_ofZeroLength_isTheIdentity() {
        float[] quaternion = new float[4];
        SphereMath.quaternionFromRotationVector(new float[] {0f, 0f, 0f, 0f}, quaternion);
        assertArrayEquals(new float[] {1f, 0f, 0f, 0f}, quaternion, EPSILON);
    }

    @Test
    public void upright_facingNorth_looksNorth() {
        // Held upright in portrait, screen towards the person, who faces north: the device's
        // y axis points up, its z axis points back at the person (south), its x axis east.
        CameraPose pose = backCamera(androidMatrix(uprightFacing(0f)));
        assertEquals(0f, pose.yaw(), 0.01f);
        assertEquals(0f, pose.pitch(), 0.01f);
    }

    @Test
    public void upright_turnedRight_looksEast() {
        CameraPose pose = backCamera(androidMatrix(uprightFacing(90f)));
        assertEquals(90f, pose.yaw(), 0.01f);
        assertEquals(0f, pose.pitch(), 0.01f);
    }

    @Test
    public void upright_turnedLeft_looksWest() {
        CameraPose pose = backCamera(androidMatrix(uprightFacing(-90f)));
        assertEquals(-90f, pose.yaw(), 0.01f);
    }

    @Test
    public void readingTheMatrixByRows_wouldLookTheWrongWay() {
        // The mistake this guards against: Android fills the matrix row-major, and reading it as
        // column-major takes the device's z axis from the bottom row instead of the right-hand
        // column. For a phone turned to face east, that row says the camera looks south.
        float[] matrix = androidMatrix(uprightFacing(90f));
        float yawFromRow = SphereMath.yaw(-matrix[6], -matrix[7]);
        float yawFromColumn = SphereMath.yaw(-matrix[2], -matrix[5]);
        assertEquals(90f, yawFromColumn, 0.01f);
        assertEquals(180f, Math.abs(yawFromRow), 0.01f);
        assertEquals(yawFromColumn, backCamera(matrix).yaw(), 0.01f);
    }

    @Test
    public void flatOnATable_looksAtTheFloor() {
        // Face up, top towards north: the device frame is the world frame.
        CameraPose pose = backCamera(androidMatrix(new float[] {1f, 0f, 0f, 0f}));
        assertEquals(-90f, pose.pitch(), 0.01f);
    }

    @Test
    public void tiltedBack_looksUp() {
        // Upright facing north, then tipped so the camera looks 30 degrees above the horizon.
        CameraPose pose = backCamera(androidMatrix(uprightFacing(0f, 30f)));
        assertEquals(30f, pose.pitch(), 0.01f);
        assertEquals(0f, pose.yaw(), 0.01f);
    }

    @Test
    public void rotate_appliesTheMatrixToAColumnVector() {
        float[] matrix = {0f, 1f, 0f, 0f, 0f, 1f, 1f, 0f, 0f};
        float[] out = new float[3];
        SphereMath.rotate(matrix, 1f, 2f, 3f, out);
        assertArrayEquals(new float[] {2f, 3f, 1f}, out, EPSILON);
    }

    @Test
    public void yawAndPitch_roundTripThroughDirection() {
        float[] direction = new float[3];
        for (float yaw = -170f; yaw <= 180f; yaw += 35f) {
            for (float pitch = -80f; pitch <= 80f; pitch += 20f) {
                SphereMath.direction(yaw, pitch, direction);
                assertEquals(yaw, SphereMath.yaw(direction[0], direction[1]), 0.01f);
                assertEquals(pitch, SphereMath.pitch(direction[0], direction[1], direction[2]),
                        0.01f);
            }
        }
    }

    @Test
    public void yaw_straightUp_isZero() {
        assertEquals(0f, SphereMath.yaw(0f, 0f), 0f);
        assertEquals(90f, SphereMath.pitch(0f, 0f, 1f), 0.01f);
    }

    @Test
    public void wrap_bringsAnglesIntoTheHalfOpenCircle() {
        assertEquals(180f, SphereMath.wrap(180f), 0f);
        assertEquals(180f, SphereMath.wrap(-180f), 0f);
        assertEquals(-170f, SphereMath.wrap(190f), EPSILON);
        assertEquals(170f, SphereMath.wrap(-190f), EPSILON);
        assertEquals(10f, SphereMath.wrap(730f), EPSILON);
        assertEquals(0f, SphereMath.wrap(0f), 0f);
    }

    @Test
    public void angleBetween_measuresTheGreatCircle() {
        float[] north = new float[3];
        float[] east = new float[3];
        SphereMath.direction(0f, 0f, north);
        SphereMath.direction(90f, 0f, east);
        assertEquals(90f, SphereMath.angleBetween(north, east), 0.01f);
        assertEquals(0f, SphereMath.angleBetween(north, north), 0.05f);
    }

    @Test
    public void slerp_halfway_turnsHalfTheAngle() {
        float[] start = uprightFacing(0f);
        float[] end = uprightFacing(60f);
        float[] middle = new float[4];
        SphereMath.slerp(start, end, 0.5f, middle);
        assertEquals(30f, backCamera(matrixOf(middle)).yaw(), 0.01f);
        assertEquals(30f, SphereMath.angleBetweenRotations(start, middle), 0.01f);
    }

    @Test
    public void slerp_takesTheShortWayRound() {
        float[] start = uprightFacing(0f);
        float[] end = uprightFacing(60f);
        // The same rotation as end, written as its negative.
        float[] negated = {-end[0], -end[1], -end[2], -end[3]};
        float[] middle = new float[4];
        SphereMath.slerp(start, negated, 0.5f, middle);
        assertEquals(30f, backCamera(matrixOf(middle)).yaw(), 0.01f);
    }

    @Test
    public void slerp_ofNearlyEqualRotations_blends() {
        float[] start = uprightFacing(0f);
        float[] end = uprightFacing(0.5f);
        float[] middle = new float[4];
        SphereMath.slerp(start, end, 0.5f, middle);
        assertEquals(0.25f, backCamera(matrixOf(middle)).yaw(), 0.01f);
    }

    @Test
    public void headingOffset_isTheTurnBetweenTwoNorths() {
        // The same pose read in a world whose north is 40 degrees further clockwise.
        float[] relative = matrixOf(uprightFacing(10f));
        float[] reference = matrixOf(uprightFacing(50f));
        assertEquals(40f, SphereMath.headingOffset(reference, relative), 0.01f);
        assertEquals(-40f, SphereMath.headingOffset(relative, reference), 0.01f);
    }

    /** A quaternion for a phone held upright, camera at the horizon, turned to face a yaw. */
    static float[] uprightFacing(float yaw) {
        return uprightFacing(yaw, 0f);
    }

    /**
     * A quaternion for a phone held upright, turned to a yaw and tipped back so its camera looks
     * {@code pitch} degrees above the horizon.
     */
    static float[] uprightFacing(float yaw, float pitch) {
        // Built as a matrix and converted, so the test does not trust the code it is testing to
        // know which way round a quaternion goes. The device's axes in world terms:
        float[] forward = new float[3];
        SphereMath.direction(yaw, pitch, forward);
        float[] up = new float[3];
        SphereMath.direction(yaw, pitch + 90f, up);
        // x = up cross back, where back = -forward.
        float[] x = {
                up[1] * -forward[2] - up[2] * -forward[1],
                up[2] * -forward[0] - up[0] * -forward[2],
                up[0] * -forward[1] - up[1] * -forward[0],
        };
        float[] matrix = {
                x[0], up[0], -forward[0],
                x[1], up[1], -forward[1],
                x[2], up[2], -forward[2],
        };
        return quaternionOf(matrix);
    }

    /** The quaternion for a rotation matrix, by the standard trace method. */
    static float[] quaternionOf(float[] m) {
        double trace = m[0] + m[4] + m[8];
        double w;
        double x;
        double y;
        double z;
        if (trace > 0) {
            double s = Math.sqrt(trace + 1) * 2;
            w = s / 4;
            x = (m[7] - m[5]) / s;
            y = (m[2] - m[6]) / s;
            z = (m[3] - m[1]) / s;
        } else if (m[0] > m[4] && m[0] > m[8]) {
            double s = Math.sqrt(1 + m[0] - m[4] - m[8]) * 2;
            w = (m[7] - m[5]) / s;
            x = s / 4;
            y = (m[1] + m[3]) / s;
            z = (m[2] + m[6]) / s;
        } else if (m[4] > m[8]) {
            double s = Math.sqrt(1 + m[4] - m[0] - m[8]) * 2;
            w = (m[2] - m[6]) / s;
            x = (m[1] + m[3]) / s;
            y = s / 4;
            z = (m[5] + m[7]) / s;
        } else {
            double s = Math.sqrt(1 + m[8] - m[0] - m[4]) * 2;
            w = (m[3] - m[1]) / s;
            x = (m[2] + m[6]) / s;
            y = (m[5] + m[7]) / s;
            z = s / 4;
        }
        return new float[] {(float) w, (float) x, (float) y, (float) z};
    }

    /** What Android makes of a quaternion, as a rotation vector sensor would report it. */
    static float[] androidMatrix(float[] wxyz) {
        float[] matrix = new float[9];
        SensorManager.getRotationMatrixFromVector(matrix,
                new float[] {wxyz[1], wxyz[2], wxyz[3], wxyz[0]});
        return matrix;
    }

    static float[] matrixOf(float[] wxyz) {
        float[] matrix = new float[9];
        SphereMath.rotationMatrix(wxyz, matrix);
        return matrix;
    }

    /** A 640x480 back camera with a sensor mounted a quarter turn round, as most phones have. */
    static CameraPose backCamera(float[] matrix) {
        return new CameraPose(matrix, 90, false, 640, 480, 500f);
    }

    private static float[] randomRotationVector(Random random) {
        double x = random.nextGaussian();
        double y = random.nextGaussian();
        double z = random.nextGaussian();
        double w = random.nextGaussian();
        double length = Math.sqrt(x * x + y * y + z * z + w * w);
        if (w < 0) {
            length = -length;
        }
        return new float[] {
                (float) (x / length), (float) (y / length), (float) (z / length),
                (float) (w / length)};
    }
}
