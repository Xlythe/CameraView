package com.xlythe.view.camera.pano;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Matching exposures, cutting seams and blending across them, and the whole stitch on frames of
 * a scene whose every direction is known.
 */
@RunWith(RobolectricTestRunner.class)
public class CompositingTest {
    @Test
    public void maxFlow_cutsAtTheBottleneck() {
        // Source - 0 - 1 - 2 - sink, with the middle link the weakest.
        MaxFlow flow = new MaxFlow(3);
        flow.addTerminals(0, MaxFlow.INFINITE, 0);
        flow.addEdge(0, 1, 5, 5);
        flow.addEdge(1, 2, 2, 2);
        flow.addTerminals(2, 0, MaxFlow.INFINITE);
        assertEquals(2f, flow.solve(), 1e-6f);
        assertTrue(flow.isSourceSide(0));
        assertTrue(flow.isSourceSide(1));
        assertFalse(flow.isSourceSide(2));
    }

    @Test
    public void maxFlow_addsUpParallelPaths_andGrowsPastItsGuess() {
        MaxFlow flow = new MaxFlow(2);
        for (int i = 0; i < 20; i++) {
            flow.addEdge(0, 1, 1, 0);
        }
        flow.addTerminals(0, MaxFlow.INFINITE, 0);
        flow.addTerminals(1, 0, MaxFlow.INFINITE);
        assertEquals(20f, flow.solve(), 1e-6f);
    }

    @Test
    public void gains_matchADarkerFrameToItsNeighbour() {
        StitchScene scene = new StitchScene().add(0, 0, 0, 0, 0).add(20, 0, 0, 0, 0);
        darken(scene.images.get(1), 0.75f);
        Equirect small = new Equirect(360, 0);
        List<FrameWarp> warps = warps(scene, small);
        StitchFrame[] frames = scene.frames.toArray(new StitchFrame[0]);
        GainCompensator.compensate(warps, frames, small.width);
        for (int c = 0; c < 3; c++) {
            float ratio = frames[1].gains[c] / frames[0].gains[c];
            // Brown and Lowe's pull towards a gain of one leaves a little of the difference, for
            // the blend to take out.
            assertEquals(1 / 0.75f, ratio, 0.1f);
        }
    }

    @Test
    public void seams_goAroundSomethingOnlyOneFrameSaw() {
        StitchScene scene = new StitchScene().add(0, 0, 0, 0, 0).add(20, 0, 0, 0, 0);
        Equirect small = new Equirect(360, 0);
        // Someone walked into the second frame, just where the two frames' centres split the
        // overlap.
        paint(scene, 1, 10f, 0f, 3f, 0xFFFF0000);
        List<FrameWarp> warps = warps(scene, small);
        StitchFrame[] frames = scene.frames.toArray(new StitchFrame[0]);
        int[] labels = SeamFinder.find(warps, frames, small, 0, small.height);
        // Every pixel of the walker comes from the same frame: either all of them, or none.
        double[] direction = new double[3];
        int owned = 0;
        int total = 0;
        for (int y = 0; y < small.height; y++) {
            for (int x = 0; x < small.width; x++) {
                small.direction(x, y, direction);
                if (angleTo(direction, 10f, 0f) < 2.5f) {
                    total++;
                    if (labels[y * small.width + x] == 0) {
                        owned++;
                    }
                }
            }
        }
        assertTrue(total > 0);
        assertTrue("the walker is split across the seam: " + owned + " of " + total,
                owned == 0 || owned == total);
    }

    @Test
    public void stitch_alignsADriftingSweep_andBlendsItSeamlessly() {
        // A quarter turn with the sensor drifting half a degree per frame and one frame darker.
        StitchScene scene = new StitchScene();
        for (int i = 0; i < 6; i++) {
            scene.add(i * 18f, 0, 0, i * 0.5f, 0.3f);
        }
        darken(scene.images.get(3), 0.8f);
        Stitcher stitcher = new Stitcher(scene.frames, scene.source(), false, 0f, 45f, 1800);
        Picture picture = new Picture();
        Stitcher.Result result = stitcher.stitch((width, height) -> picture);
        assertNotNull(result);
        assertNotNull(result.alignment);
        assertTrue(result.pairs >= 5);
        assertTrue(result.alignment.rmsAfter < result.alignment.rmsBefore);
        assertTrue(result.alignment.rmsAfter < 1.0);
        // About 90 degrees of sweep plus a frame's width, and a band either side of the horizon.
        float degrees = 360f * result.region[2] / result.grid.width;
        assertTrue("the panorama is " + degrees + " degrees wide", degrees > 100 && degrees < 145);
        assertEquals(result.region[2] * result.region[3], picture.written);

        // Every pixel shows the scene where it looks, give or take the blend. The matches fix how
        // the frames sit against each other, but the sweep as a whole is turned however far the
        // sensor was off on average, so that is turned back first.
        double[] toTruth = Rotations.multiply(scene.trueToWorld(0),
                Rotations.transpose(scene.frames.get(0).toWorld));
        double[] stitched = new double[3];
        double[] direction = new double[3];
        int wrong = 0;
        int checked = 0;
        for (int y = 2; y < result.region[3] - 2; y += 3) {
            for (int x = 2; x < result.region[2] - 2; x += 3) {
                result.grid.direction(result.region[0] + x, result.region[1] + y, stitched);
                Rotations.apply(toTruth, stitched[0], stitched[1], stitched[2], direction);
                if (!picture.isPatchInterior(result.grid, direction)) {
                    continue;
                }
                int expected = TestScene.brightness((float) direction[0], (float) direction[1],
                        (float) direction[2]);
                int actual = ArgbImage.green(picture.at(x, y, result.region[2]));
                checked++;
                if (Math.abs(actual - expected) > 24) {
                    wrong++;
                }
            }
        }
        assertTrue(checked > 1000);
        assertTrue(wrong + " of " + checked + " pixels show the wrong thing",
                wrong < checked / 50);
    }

    private static List<FrameWarp> warps(StitchScene scene, Equirect small) {
        List<FrameWarp> warps = new ArrayList<>();
        for (int i = 0; i < scene.frames.size(); i++) {
            warps.add(FrameWarp.of(i, scene.frames.get(i), scene.images.get(i), 1, small));
        }
        return warps;
    }

    private static void darken(ArgbImage image, float factor) {
        for (int i = 0; i < image.pixels.length; i++) {
            int argb = image.pixels[i];
            int r = Math.round(ArgbImage.red(argb) * factor);
            int g = Math.round(ArgbImage.green(argb) * factor);
            int b = Math.round(ArgbImage.blue(argb) * factor);
            image.pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }

    /** Paints a disc of a colour into one frame, centred on a direction. */
    private static void paint(StitchScene scene, int frame, float yaw, float pitch,
            float radiusDegrees, int color) {
        CameraPose pose = scene.truth.get(frame);
        ArgbImage image = scene.images.get(frame);
        float[] direction = new float[3];
        double[] d = new double[3];
        for (int y = 0; y < image.height; y++) {
            for (int x = 0; x < image.width; x++) {
                pose.unproject(x + 0.5f, y + 0.5f, direction);
                d[0] = direction[0];
                d[1] = direction[1];
                d[2] = direction[2];
                if (angleTo(d, yaw, pitch) < radiusDegrees) {
                    image.pixels[y * image.width + x] = color;
                }
            }
        }
    }

    private static float angleTo(double[] direction, float yaw, float pitch) {
        float[] target = new float[3];
        SphereMath.direction(yaw, pitch, target);
        float[] d = {(float) direction[0], (float) direction[1], (float) direction[2]};
        return SphereMath.angleBetween(d, target);
    }

    /** Collects the stitched picture. */
    private static final class Picture implements MultiBandBlender.Sink {
        /** Pixels written, by position. */
        private final Map<Long, Integer> mPixels = new HashMap<>();

        /** How many pixels were written. */
        int written;

        @Override
        public void write(int[] pixels, int x, int y, int w, int h) {
            for (int row = 0; row < h; row++) {
                for (int col = 0; col < w; col++) {
                    mPixels.put(((long) (y + row) << 32) | (x + col), pixels[row * w + col]);
                    written++;
                }
            }
        }

        int at(int x, int y, int width) {
            Integer value = mPixels.get(((long) y << 32) | x);
            return value == null ? 0 : value;
        }

        /** Whether a direction is well inside one of the scene's patches, away from any edge. */
        boolean isPatchInterior(Equirect grid, double[] direction) {
            double yaw = Math.toDegrees(Math.atan2(direction[0], direction[1]));
            double pitch = Math.toDegrees(Math.asin(direction[2]));
            double u = ((yaw + 180) / 2) % 1;
            double v = ((pitch + 90) / 2) % 1;
            return u > 0.3 && u < 0.7 && v > 0.3 && v < 0.7;
        }
    }
}
