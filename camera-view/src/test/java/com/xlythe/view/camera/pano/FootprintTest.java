package com.xlythe.view.camera.pano;

import static com.xlythe.view.camera.pano.SphereMathTest.matrixOf;
import static com.xlythe.view.camera.pano.SphereMathTest.uprightFacing;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The yaws and pitches a frame covers, including across north and over a pole. */
public class FootprintTest {
    /** A sensor whose 640 pixels span 60 degrees, mounted a quarter turn round. */
    private static final float FOCAL = (float) (320 / Math.tan(Math.toRadians(30)));

    @Test
    public void atTheHorizon_coversItsAnglesOfView() {
        Footprint footprint = Footprint.of(pose(0f, 0f));
        assertFalse(footprint.allYaws);
        // Upright, the 480 side is across: 2 * atan(240 / focal) is about 46 degrees.
        assertEquals(-23.4f, footprint.minYaw, 2f);
        assertEquals(23.4f, footprint.maxYaw, 2f);
        assertEquals(-30f, footprint.minPitch, 2f);
        assertEquals(30f, footprint.maxPitch, 2f);
        assertEquals(0f, footprint.centreYaw, 0.01f);
    }

    @Test
    public void acrossSouth_staysInOnePiece() {
        Footprint footprint = Footprint.of(pose(178f, 0f));
        assertFalse(footprint.allYaws);
        assertTrue(footprint.minYaw < 178f);
        assertTrue(footprint.maxYaw > 180f);
        assertTrue(footprint.maxYaw - footprint.minYaw < 60f);
    }

    @Test
    public void containingThePole_coversEveryYaw() {
        Footprint up = Footprint.of(pose(30f, 80f));
        assertTrue(up.allYaws);
        assertEquals(90f, up.maxPitch, 0f);
        Footprint down = Footprint.of(pose(30f, -85f));
        assertTrue(down.allYaws);
        assertEquals(-90f, down.minPitch, 0f);
    }

    @Test
    public void everyPixel_isInsideTheFootprint() {
        // Every way a frame can be held near a pole, rolled as well as tilted: whatever the
        // footprint says, nothing the frame saw may fall outside it.
        float[] direction = new float[3];
        boolean sawAllYaws = false;
        boolean sawSomeYaws = false;
        for (float pitch = -88f; pitch <= 88f; pitch += 4f) {
            for (float roll = 0f; roll < 360f; roll += 30f) {
                CameraPose pose = new CameraPose(matrixOf(rolled(uprightFacing(40f, pitch), roll)),
                        90, false, 640, 480, FOCAL);
                Footprint footprint = Footprint.of(pose);
                sawAllYaws |= footprint.allYaws;
                sawSomeYaws |= !footprint.allYaws;
                for (int i = 0; i <= 8; i++) {
                    for (int j = 0; j <= 8; j++) {
                        pose.unproject(i * 80f, j * 60f, direction);
                        float seenPitch =
                                SphereMath.pitch(direction[0], direction[1], direction[2]);
                        assertTrue(seenPitch >= footprint.minPitch);
                        assertTrue(seenPitch <= footprint.maxPitch);
                        if (!footprint.allYaws) {
                            float seenYaw = footprint.centreYaw + SphereMath.wrap(
                                    SphereMath.yaw(direction[0], direction[1])
                                            - footprint.centreYaw);
                            assertTrue(seenYaw >= footprint.minYaw);
                            assertTrue(seenYaw <= footprint.maxYaw);
                        }
                    }
                }
            }
        }
        assertTrue(sawAllYaws);
        assertTrue(sawSomeYaws);
    }

    /** A pose turned about its own lens, as a phone held at an angle is. */
    static float[] rolled(float[] wxyz, float degrees) {
        double half = Math.toRadians(degrees) / 2;
        float rw = (float) Math.cos(half);
        float rz = (float) Math.sin(half);
        // wxyz * (rw, 0, 0, rz): the roll is about the device's own z axis, so it goes second.
        return new float[] {
                wxyz[0] * rw - wxyz[3] * rz,
                wxyz[1] * rw + wxyz[2] * rz,
                wxyz[2] * rw - wxyz[1] * rz,
                wxyz[3] * rw + wxyz[0] * rz,
        };
    }

    private static CameraPose pose(float yaw, float pitch) {
        return new CameraPose(matrixOf(uprightFacing(yaw, pitch)), 90, false, 640, 480, FOCAL);
    }
}
