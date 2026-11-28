package com.xlythe.view.camera.pano;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Finding corners, matching them between frames and turning the frames until the matches agree,
 * on frames of a scene whose every direction is known.
 */
public class AlignmentTest {
    @Test
    public void rotations_expAndLog_areInverse() {
        double[] r = Rotations.exp(0.3, -0.2, 0.5);
        double[] w = Rotations.log(r);
        assertEquals(0.3, w[0], 1e-9);
        assertEquals(-0.2, w[1], 1e-9);
        assertEquals(0.5, w[2], 1e-9);
        assertEquals(Math.sqrt(0.38), Rotations.angle(r), 1e-9);
        double[] half = Rotations.log(Rotations.exp(0, 0, Math.PI));
        assertEquals(Math.PI, Math.abs(half[2]), 1e-6);
        double[] identity = Rotations.multiply(r, Rotations.transpose(r));
        assertEquals(0, Rotations.angle(identity), 1e-7);
    }

    @Test
    public void rotations_fit_recoversARotationFromNoisyPairs() {
        double[] truth = Rotations.exp(0.1, 0.7, -0.4);
        Random random = new Random(1);
        int n = 50;
        double[] a = new double[n * 3];
        double[] b = new double[n * 3];
        double[] turned = new double[3];
        for (int i = 0; i < n; i++) {
            double x = random.nextGaussian();
            double y = random.nextGaussian();
            double z = random.nextGaussian();
            double length = Math.sqrt(x * x + y * y + z * z);
            a[i * 3] = x / length;
            a[i * 3 + 1] = y / length;
            a[i * 3 + 2] = z / length;
            Rotations.apply(truth, a[i * 3], a[i * 3 + 1], a[i * 3 + 2], turned);
            for (int k = 0; k < 3; k++) {
                b[i * 3 + k] = turned[k] + random.nextGaussian() * 1e-3;
            }
        }
        double[] fitted = Rotations.fit(a, b, n, null);
        double error = Rotations.angle(Rotations.multiply(fitted, Rotations.transpose(truth)));
        assertTrue("off by " + Math.toDegrees(error) + " degrees", error < Math.toRadians(0.2));
    }

    @Test
    public void features_areFoundAllOverTheFrame_andMatchThemselves() {
        StitchScene scene = new StitchScene().add(0, 0, 0, 0, 0);
        ArgbImage image = scene.images.get(0);
        Features features = detect(image);
        assertTrue("only " + features.count + " corners", features.count > 100);
        int[] cells = new int[4];
        for (int i = 0; i < features.count; i++) {
            int quadrant = (features.x[i] < features.width / 2f ? 0 : 1)
                    + (features.y[i] < features.height / 2f ? 0 : 2);
            cells[quadrant]++;
        }
        for (int count : cells) {
            assertTrue("a quarter of the frame has only " + count, count > 10);
        }
        Features.Index index = new Features.Index(features, 32);
        int[] nearest = new int[3];
        index.nearest(features, 5, features.x[5], features.y[5], 4f, nearest);
        assertEquals(5, nearest[0]);
        assertEquals(0, nearest[1]);
    }

    @Test
    public void pairMatcher_findsTheRotationBetweenTwoFrames_despiteTheSensor() {
        // Twenty degrees apart, with the sensor four degrees out about the vertical and one in
        // pitch.
        StitchScene scene = new StitchScene().add(0, 0, 0, 0, 0).add(20, 0, 0, 4, -1);
        PairMatcher.Pair pair = match(scene, 0, 1);
        assertNotNull(pair);
        assertTrue(pair.count >= PairMatcher.MIN_INLIERS);
        double[] truth = Rotations.multiply(Rotations.transpose(scene.trueToWorld(1)),
                scene.trueToWorld(0));
        double error = Math.toDegrees(Rotations.angle(
                Rotations.multiply(pair.rotation, Rotations.transpose(truth))));
        assertTrue("off by " + error + " degrees", error < 0.3);
    }

    @Test
    public void pairMatcher_givesUpOnFramesThatDoNotOverlap() {
        StitchScene scene = new StitchScene().add(0, 0, 0, 0, 0).add(150, 0, 0, 0, 0);
        assertNull(match(scene, 0, 1));
    }

    @Test
    public void pairMatcher_matchesFramesTurnedAboutTheLens_asAtThePoles() {
        StitchScene scene = new StitchScene().add(0, 80, 0, 0, 0).add(0, 80, 35, 0, 0);
        PairMatcher.Pair pair = match(scene, 0, 1);
        assertNotNull(pair);
        double[] truth = Rotations.multiply(Rotations.transpose(scene.trueToWorld(1)),
                scene.trueToWorld(0));
        double error = Math.toDegrees(Rotations.angle(
                Rotations.multiply(pair.rotation, Rotations.transpose(truth))));
        assertTrue("off by " + error + " degrees", error < 0.3);
    }

    @Test
    public void bundleAdjuster_takesOutASensorsDrift_aroundAWholeTurn() {
        // Twelve frames thirty degrees apart, the sensor drifting a degree further each frame:
        // eleven degrees out by the time the turn closes on itself.
        StitchScene scene = new StitchScene();
        for (int i = 0; i < 12; i++) {
            scene.add(i * 30, 0, 0, i, 0);
        }
        List<PairMatcher.Pair> pairs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            PairMatcher.Pair pair = match(scene, i, (i + 1) % 12);
            if (pair != null) {
                pairs.add(pair);
            }
        }
        assertTrue("only " + pairs.size() + " pairs matched", pairs.size() >= 10);
        double[][] sensor = new double[12][];
        for (int i = 0; i < 12; i++) {
            sensor[i] = scene.frames.get(i).sensorToWorld;
        }
        BundleAdjuster.Result result =
                BundleAdjuster.adjust(sensor, pairs, StitchScene.FOCAL / Stitcher.FEATURE_SHRINK);
        assertTrue(result.rmsAfter < 1.0);
        assertTrue(result.rmsBefore > 5 * result.rmsAfter);
        assertTrue(result.p95After <= result.p95Before);
        assertTrue(result.medianAfter <= result.medianBefore);
        assertEquals(pairsRays(pairs), result.rays);
        // Every frame is where it really was relative to the first, which the matches pin down;
        // the turn of the whole is the sensor's average, which they cannot.
        double[] firstError = Rotations.multiply(result.toWorld[0],
                Rotations.transpose(scene.trueToWorld(0)));
        for (int i = 1; i < 12; i++) {
            double[] error = Rotations.multiply(result.toWorld[i],
                    Rotations.transpose(scene.trueToWorld(i)));
            double relative = Math.toDegrees(Rotations.angle(
                    Rotations.multiply(error, Rotations.transpose(firstError))));
            assertTrue("frame " + i + " is " + relative + " degrees off", relative < 0.3);
        }
    }

    @Test
    public void bundleAdjuster_withNoMatches_leavesTheSensorAlone() {
        double[][] sensor = {Rotations.exp(0, 0, 1)};
        BundleAdjuster.Result result = BundleAdjuster.adjust(sensor, new ArrayList<>(), 300);
        assertEquals(0, result.rays);
        assertEquals(1, Rotations.angle(result.toWorld[0]), 1e-9);
    }

    @Test
    public void linear_solve_solvesAndRefusesASingularSystem() {
        double[] x = Linear.solve(new double[] {2, 1, 1, 3}, new double[] {3, 5}, 2);
        assertNotNull(x);
        assertEquals(0.8, x[0], 1e-9);
        assertEquals(1.4, x[1], 1e-9);
        assertNull(Linear.solve(new double[] {1, 2, 2, 4}, new double[] {1, 2}, 2));
    }

    private static int pairsRays(List<PairMatcher.Pair> pairs) {
        int rays = 0;
        for (PairMatcher.Pair pair : pairs) {
            rays += pair.count;
        }
        return rays;
    }

    static Features detect(ArgbImage image) {
        return Features.detect(Stitcher.luma(image, Stitcher.FEATURE_SHRINK),
                image.width / Stitcher.FEATURE_SHRINK, image.height / Stitcher.FEATURE_SHRINK);
    }

    static PairMatcher.Pair match(StitchScene scene, int i, int j) {
        Features a = detect(scene.images.get(i));
        Features b = detect(scene.images.get(j));
        float focal = StitchScene.FOCAL / Stitcher.FEATURE_SHRINK;
        float radius = (float) (focal * Math.tan(Math.toRadians(Stitcher.SENSOR_ERROR_DEGREES)))
                + 8;
        return PairMatcher.match(i, a, scene.frames.get(i).sensorToWorld, j, b,
                new Features.Index(b, 32), scene.frames.get(j).sensorToWorld, focal, radius);
    }
}
