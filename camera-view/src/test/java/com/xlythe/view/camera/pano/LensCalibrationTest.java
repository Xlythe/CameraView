package com.xlythe.view.camera.pano;

import static com.xlythe.view.camera.pano.SphereMathTest.matrixOf;
import static com.xlythe.view.camera.pano.SphereMathTest.uprightFacing;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/**
 * Measuring a lens whose published focal length is wrong, from frames of a made-up world taken
 * with the real one.
 */
public class LensCalibrationTest {
    private static final int WIDTH = 320;
    private static final int HEIGHT = 240;

    /** The lens the frames are really taken with: 60 degrees across the long side. */
    private static final float TRUE_FOCAL = (float) (160 / Math.tan(Math.toRadians(30)));

    @Test
    public void anHonestLens_isLeftAlone() {
        assertEquals(1f, calibrate(TRUE_FOCAL), 0.03f);
    }

    @Test
    public void aLensWiderThanItSays_isMeasured() {
        // As the emulator's virtual scene does: it publishes a focal length about half the one
        // it renders with, which would paint every frame nearly twice as wide as it is.
        assertEquals(1f / 0.55f, calibrate(TRUE_FOCAL * 0.55f), 0.05f);
    }

    @Test
    public void aLensNarrowerThanItSays_isMeasured() {
        assertEquals(1f / 1.4f, calibrate(TRUE_FOCAL * 1.4f), 0.03f);
    }

    @Test
    public void framesTooCloseTogether_sayNothing() {
        LensCalibration calibration = new LensCalibration();
        for (int i = 0; i < 10; i++) {
            offer(calibration, i * 0.5f, TRUE_FOCAL);
        }
        assertFalse(calibration.isDone());
        assertTrue(Float.isNaN(calibration.getScale()));
    }

    @Test
    public void featurelessFrames_keepThePublishedFocalLength() {
        LensCalibration calibration = new LensCalibration();
        int[] grey = new int[WIDTH * HEIGHT];
        Arrays.fill(grey, 0xFF808080);
        for (int i = 0; i < 20 && !calibration.isDone(); i++) {
            CameraPose pose = pose(i * 9f, TRUE_FOCAL);
            calibration.offer(new ArgbImage(grey, WIDTH, HEIGHT),
                    matrixOf(uprightFacing(i * 9f)), pose);
        }
        // Nothing can be measured from a blank wall, so after a few tries it gives up.
        assertTrue(calibration.isDone());
        assertEquals(1f, calibration.getScale(), 0f);
        assertTrue(Float.isNaN(LensCalibration.estimate(
                new LensCalibration.Thumbnail(new ArgbImage(grey, WIDTH, HEIGHT),
                        matrixOf(uprightFacing(0f)), pose(0f, TRUE_FOCAL)),
                new LensCalibration.Thumbnail(new ArgbImage(grey, WIDTH, HEIGHT),
                        matrixOf(uprightFacing(8f)), pose(8f, TRUE_FOCAL)))));
    }

    @Test
    public void estimatesThatNeverAgree_keepThePublishedFocalLength() {
        LensCalibration calibration = new LensCalibration();
        // Each frame from a different lens, so no two pairs agree.
        float[] lenses = {0.6f, 1.6f, 0.75f, 2.0f, 0.5f, 1.2f, 0.9f, 2.4f, 0.45f, 1.0f, 2.2f};
        for (int i = 0; i < lenses.length && !calibration.isDone(); i++) {
            CameraPose truth = pose(i * 10f, TRUE_FOCAL * lenses[i]);
            calibration.offer(TestScene.render(truth), matrixOf(uprightFacing(i * 10f)),
                    pose(i * 10f, TRUE_FOCAL));
        }
        assertTrue(calibration.isDone());
        assertEquals(1f, calibration.getScale(), 0f);
        // Once done, more frames change nothing.
        offer(calibration, 200f, TRUE_FOCAL);
        assertEquals(1f, calibration.getScale(), 0f);
    }

    @Test
    public void framesThatDoNotOverlap_sayNothing() {
        LensCalibration.Thumbnail north = thumbnail(0f, TRUE_FOCAL);
        LensCalibration.Thumbnail south = thumbnail(180f, TRUE_FOCAL);
        assertEquals(-2f, LensCalibration.correlation(north, south, 1f), 0f);
        assertTrue(Float.isNaN(LensCalibration.estimate(north, south)));
    }

    /** Offers frames of a steady turn until the calibration settles, and returns its scale. */
    private static float calibrate(float publishedFocal) {
        LensCalibration calibration = new LensCalibration();
        for (int i = 0; i < 12 && !calibration.isDone(); i++) {
            offer(calibration, i * 9f, publishedFocal);
        }
        assertTrue(calibration.isDone());
        return calibration.getScale();
    }

    private static void offer(LensCalibration calibration, float yaw, float publishedFocal) {
        calibration.offer(TestScene.render(pose(yaw, TRUE_FOCAL)), matrixOf(uprightFacing(yaw)),
                pose(yaw, publishedFocal));
    }

    private static LensCalibration.Thumbnail thumbnail(float yaw, float publishedFocal) {
        return new LensCalibration.Thumbnail(TestScene.render(pose(yaw, TRUE_FOCAL)),
                matrixOf(uprightFacing(yaw)), pose(yaw, publishedFocal));
    }

    private static CameraPose pose(float yaw, float focal) {
        return new CameraPose(matrixOf(uprightFacing(yaw)), 90, false, WIDTH, HEIGHT, focal);
    }
}
