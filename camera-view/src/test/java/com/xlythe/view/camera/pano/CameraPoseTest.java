package com.xlythe.view.camera.pano;

import static com.xlythe.view.camera.pano.SphereMathTest.matrixOf;
import static com.xlythe.view.camera.pano.SphereMathTest.uprightFacing;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Where each pixel of a frame looks, for each way a sensor can be mounted. */
public class CameraPoseTest {
    private static final float EPSILON = 1e-3f;

    @Test
    public void projectAndUnproject_areInverses() {
        float[] matrix = matrixOf(uprightFacing(37f, 12f));
        float[] direction = new float[3];
        float[] point = new float[2];
        for (int rotation = 0; rotation < 360; rotation += 90) {
            for (boolean front : new boolean[] {false, true}) {
                CameraPose pose = new CameraPose(matrix, rotation, front, 640, 480, 500f);
                for (float[] corner : new float[][] {{0f, 0f}, {640f, 0f}, {320f, 240f},
                        {100f, 400f}}) {
                    pose.unproject(corner[0], corner[1], direction);
                    assertTrue(pose.project(direction, point));
                    assertEquals(corner[0], point[0], EPSILON);
                    assertEquals(corner[1], point[1], EPSILON);
                }
            }
        }
    }

    @Test
    public void centre_looksAlongTheLens() {
        CameraPose pose = new CameraPose(matrixOf(uprightFacing(20f, -10f)), 90, false, 640,
                480, 500f);
        float[] direction = new float[3];
        pose.unproject(320f, 240f, direction);
        assertEquals(20f, SphereMath.yaw(direction[0], direction[1]), 0.01f);
        assertEquals(-10f, SphereMath.pitch(direction[0], direction[1], direction[2]), 0.01f);
    }

    @Test
    public void sidewaysSensor_hasTheTopOfThePictureAtTheStartOfEachRow() {
        // A back sensor mounted a quarter turn round: turned clockwise to stand up, so the
        // left-hand end of each of its rows is the top of the upright picture, and its first row
        // is the right-hand edge.
        CameraPose pose = new CameraPose(matrixOf(uprightFacing(0f)), 90, false, 640, 480, 500f);
        float[] direction = new float[3];
        pose.unproject(0f, 240f, direction);
        assertTrue(SphereMath.pitch(direction[0], direction[1], direction[2]) > 30f);
        pose.unproject(320f, 0f, direction);
        assertTrue(SphereMath.yaw(direction[0], direction[1]) > 20f);
    }

    @Test
    public void frontCamera_looksAtThePersonHoldingThePhone() {
        // Facing north with the screen towards you, the front camera looks south at you, and its
        // unmirrored picture has your left - the east - on its right.
        CameraPose pose = new CameraPose(matrixOf(uprightFacing(0f)), 270, true, 640, 480, 500f);
        assertEquals(180f, Math.abs(pose.yaw()), 0.01f);
        float[] direction = new float[3];
        // A front sensor turned 270 clockwise to stand up: the end of each row is the top.
        pose.unproject(640f, 240f, direction);
        assertTrue(SphereMath.pitch(direction[0], direction[1], direction[2]) > 30f);
        pose.unproject(320f, 480f, direction);
        // The last row is the upright picture's right, which for a lens looking south is west.
        assertEquals(-154.4f, SphereMath.yaw(direction[0], direction[1]), 0.1f);
    }

    @Test
    public void upsideDownSensor_standsUpAfterAHalfTurn() {
        CameraPose pose = new CameraPose(matrixOf(uprightFacing(0f)), 180, false, 640, 480,
                500f);
        float[] direction = new float[3];
        // Turned a half, so the bottom row is the top of the picture.
        pose.unproject(320f, 480f, direction);
        assertTrue(SphereMath.pitch(direction[0], direction[1], direction[2]) > 20f);
        assertFalse(pose.isSideways());
    }

    @Test
    public void behindTheLens_doesNotProject() {
        CameraPose pose = new CameraPose(matrixOf(uprightFacing(0f)), 0, false, 640, 480, 500f);
        float[] south = new float[3];
        SphereMath.direction(180f, 0f, south);
        assertFalse(pose.project(south, new float[2]));
    }

    @Test
    public void negativeRotation_isTheSameAsItsTurnRoundTheCircle() {
        CameraPose negative = new CameraPose(matrixOf(uprightFacing(0f)), -90, false, 64, 48,
                50f);
        assertEquals(270, negative.sensorRotation);
    }

    @Test
    public void contains_respectsTheMargin() {
        CameraPose pose = new CameraPose(matrixOf(uprightFacing(0f)), 0, false, 640, 480, 500f);
        assertTrue(pose.contains(new float[] {10f, 10f}, 5f));
        assertFalse(pose.contains(new float[] {4f, 10f}, 5f));
        assertFalse(pose.contains(new float[] {10f, 478f}, 5f));
    }

    @Test
    public void focalLength_isMeasuredAcrossWhicheverSideTheFrameSpans() {
        // A 4:3 sensor seeing 60 by 46.83 degrees: a 4:3 frame spans both.
        float across = (float) Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(30)) * 3 / 4));
        float fourThree = CameraPose.focalLength(640, 480, 60f, across);
        assertEquals(320 / Math.tan(Math.toRadians(30)), fourThree, 0.1f);
        // A 16:9 frame is cropped top and bottom, and keeps the full width.
        assertEquals(fourThree * 1280f / 640f, CameraPose.focalLength(1280, 720, 60f, across),
                0.5f);
        // A square one is cropped at the sides, and keeps the full height.
        assertEquals(240 / Math.tan(Math.toRadians(across / 2)),
                CameraPose.focalLength(480, 480, 60f, across), 0.1f);
    }

    @Test
    public void focalLength_isZeroWithoutOptics() {
        assertEquals(0f, CameraPose.focalLength(640, 480, 0f, 40f), 0f);
        assertEquals(0f, CameraPose.focalLength(640, 480, 60f, 0f), 0f);
        assertEquals(0f, CameraPose.focalLength(0, 480, 60f, 40f), 0f);
        assertEquals(0f, CameraPose.focalLength(640, 0, 60f, 40f), 0f);
        assertEquals(0f, CameraPose.focalLength(640, 480, 180f, 40f), 0f);
        assertEquals(0f, CameraPose.focalLength(640, 480, 60f, 200f), 0f);
    }

    @Test
    public void anglesOfView_followTheUprightPicture() {
        float focal = CameraPose.focalLength(640, 480, 60f, 46.83f);
        CameraPose sideways = new CameraPose(matrixOf(uprightFacing(0f)), 90, false, 640, 480,
                focal);
        assertEquals(46.83f, sideways.uprightHorizontalFov(), 0.05f);
        assertEquals(60f, sideways.uprightVerticalFov(), 0.05f);
        assertEquals(46.83f, sideways.narrowestFov(), 0.05f);
        CameraPose landscape = new CameraPose(matrixOf(uprightFacing(0f)), 0, false, 640, 480,
                focal);
        assertEquals(60f, landscape.uprightHorizontalFov(), 0.05f);
        assertEquals(46.83f, landscape.uprightVerticalFov(), 0.05f);
    }
}
