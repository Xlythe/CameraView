package com.xlythe.view.camera.pano;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Aligning what corners cannot: a plain ceiling or floor by its brightness, a frame nothing matches
 * by the turns the sensor measured between it and its neighbours, and a lens calibrated a little
 * wrong by the loop a whole turn has to close.
 */
public class PoleAlignmentTest {
    /** A world with no corners: two soft waves of brightness crossing, like faint planks. */
    private static final Scene PLAIN = d ->
            110 + 7 * Math.sin(40 * (0.6 * d[0] + 0.8 * d[2]))
                    + 5 * Math.sin(29 * (0.8 * d[1] - 0.6 * d[2] + 0.2 * d[0]));

    /** The brightness of a world in each direction. */
    private interface Scene {
        double brightness(double[] direction);
    }

    @Test
    public void directAligner_alignsAPlainOverlap_thatHasNoCornersToMatch() {
        // A frame near the zenith and one of the ring below it, overlapping by a strip.
        CameraPose a = StitchScene.pose(0, 88, 0);
        CameraPose b = StitchScene.pose(30, 45, 0);
        ArgbImage imageA = render(PLAIN, a);
        ArgbImage imageB = render(PLAIN, b);
        assertTrue("found " + AlignmentTest.detect(imageA).count + " corners",
                AlignmentTest.detect(imageA).count < PairMatcher.MIN_INLIERS);

        double[] truth = relative(a, b);
        // The sensor three degrees out, about an axis across the frame and a little about the lens.
        double[] start = Rotations.multiply(Rotations.exp(Math.toRadians(2.2),
                Math.toRadians(-1.8), Math.toRadians(0.8)), truth);
        DirectAligner.Link link = align(imageA, imageB, start);
        assertNotNull(link);
        double error = Math.toDegrees(Rotations.angle(
                Rotations.multiply(link.rotation, Rotations.transpose(truth))));
        assertTrue("off by " + error + " degrees", error < 0.1);
        assertTrue(link.correlation > link.startCorrelation);
        assertTrue(link.correlation > 0.9);
    }

    @Test
    public void directAligner_givesMatchesWhereTheTrueTurnTakesThePixels() {
        CameraPose a = StitchScene.pose(0, 60, 0);
        CameraPose b = StitchScene.pose(25, 60, 0);
        double[] truth = relative(a, b);
        double[] start = Rotations.multiply(Rotations.exp(0.03, -0.02, 0.01), truth);
        DirectAligner.Link link = align(render(PLAIN, a), render(PLAIN, b), start);
        assertNotNull(link);
        PairMatcher.Pair matches = link.matches;
        assertEquals(DirectAligner.MATCHES, matches.count);
        double focal = StitchScene.FOCAL / Stitcher.FEATURE_SHRINK;
        double[] turned = new double[3];
        for (int k = 0; k < matches.count; k++) {
            Rotations.apply(truth, matches.firstRays[k * 3], matches.firstRays[k * 3 + 1],
                    matches.firstRays[k * 3 + 2], turned);
            double error = focal * Math.sqrt(sq(turned[0] - matches.secondRays[k * 3])
                    + sq(turned[1] - matches.secondRays[k * 3 + 1])
                    + sq(turned[2] - matches.secondRays[k * 3 + 2]));
            assertTrue("match " + k + " is " + error + " pixels out", error < 0.2);
        }
    }

    @Test
    public void directAligner_givesUpOnFramesThatBarelyOverlap() {
        CameraPose a = StitchScene.pose(0, 0, 0);
        CameraPose b = StitchScene.pose(75, 0, 0);
        assertNull(align(render(PLAIN, a), render(PLAIN, b), relative(a, b)));
    }

    @Test
    public void directAligner_refusesAWorldItCannotTellApart() {
        CameraPose a = StitchScene.pose(0, 30, 0);
        CameraPose b = StitchScene.pose(20, 30, 0);
        Scene flat = d -> 120;
        assertNull(align(render(flat, a), render(flat, b), relative(a, b)));
    }

    @Test
    public void bundleAdjuster_frameWithNothingToMatch_followsItsNeighboursCorrection() {
        // Three frames a second apart; the sensor three degrees out about the vertical on the
        // last two, as a gyroscope that drifted between the first and the second would be. Only
        // the first two share matches; the third is a plain ceiling.
        double[][] truth = {toWorld(StitchScene.pose(0, 30, 0)),
                toWorld(StitchScene.pose(30, 30, 0)), toWorld(StitchScene.pose(30, 75, 0))};
        double[] drift = Rotations.exp(0, 0, Math.toRadians(3));
        double[][] sensor = {truth[0], Rotations.multiply(drift, truth[1]),
                Rotations.multiply(drift, truth[2])};
        List<PairMatcher.Pair> pairs = Collections.singletonList(rays(truth, 0, 1));
        double focal = 250;

        long[] close = {0, 1000, 2000};
        BundleAdjuster.Result followed = BundleAdjuster.adjust(sensor, close, pairs, focal,
                false);
        long[] apart = {0, 1000, 90_000};
        BundleAdjuster.Result loosely = BundleAdjuster.adjust(sensor, apart, pairs, focal,
                false);
        BundleAdjuster.Result alone = BundleAdjuster.adjust(sensor, pairs, focal);

        // Measured against the first frame, since matches alone cannot say how the whole turns.
        // The second frame is put right by its matches whichever way.
        assertTrue(degreesOff(followed, truth, 1) < 0.1);
        assertTrue(degreesOff(alone, truth, 1) < 0.1);
        // The third follows it when they were taken a second apart...
        assertTrue("off by " + degreesOff(followed, truth, 2),
                degreesOff(followed, truth, 2) < 0.2);
        // ...less when a minute and a half apart, since the gyroscope drifts in that time...
        assertTrue("off by " + degreesOff(loosely, truth, 2),
                degreesOff(loosely, truth, 2) > 0.5);
        assertTrue(degreesOff(loosely, truth, 2) < 1.4);
        // ...and not at all without the sensor's turns: it stays where the sensor put it, while
        // the first two share the correction their matches made.
        assertEquals(1.5, degreesOff(alone, truth, 2), 0.1);
    }

    @Test
    public void bundleAdjuster_putsAPlainFrameRight_byWhatItsBrightnessMatched() {
        // A frame aimed at a plain ceiling, three degrees out, with nothing but a link to the ring
        // below it.
        CameraPose ring = StitchScene.pose(0, 45, 0);
        CameraPose top = StitchScene.pose(20, 88, 0);
        double[][] truth = {toWorld(ring), toWorld(top)};
        double[] error = Rotations.exp(Math.toRadians(2), Math.toRadians(-1.5), Math.toRadians(1));
        double[][] sensor = {truth[0], Rotations.multiply(error, truth[1])};
        DirectAligner.Link link = align(render(PLAIN, ring), render(PLAIN, top),
                Rotations.multiply(Rotations.transpose(sensor[1]), sensor[0]));
        assertNotNull(link);
        BundleAdjuster.Result result = BundleAdjuster.adjust(sensor, null,
                Collections.singletonList(link.matches),
                StitchScene.FOCAL / Stitcher.FEATURE_SHRINK, false);
        assertTrue("off by " + degreesOff(result, truth, 1), degreesOff(result, truth, 1) < 0.1);
    }

    @Test
    public void bundleAdjuster_refinesAFocalLengthCalibratedTwoPercentLong() {
        // A whole turn, matched with a focal length two percent longer than the lens's.
        StitchScene scene = new StitchScene();
        for (int i = 0; i < 12; i++) {
            scene.add(i * 30, 0, 0, 0.5f * i, 0);
        }
        float wrong = StitchScene.FOCAL * 1.02f / Stitcher.FEATURE_SHRINK;
        List<PairMatcher.Pair> pairs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            int j = (i + 1) % 12;
            Features a = AlignmentTest.detect(scene.images.get(i));
            Features b = AlignmentTest.detect(scene.images.get(j));
            PairMatcher.Pair pair = PairMatcher.match(i, a, scene.frames.get(i).sensorToWorld,
                    j, b, new Features.Index(b, 32), scene.frames.get(j).sensorToWorld, wrong,
                    (float) (wrong * Math.tan(Math.toRadians(Stitcher.SENSOR_ERROR_DEGREES)))
                            + 8);
            if (pair != null) {
                pairs.add(pair);
            }
        }
        assertTrue(pairs.size() >= 10);
        double[][] sensor = new double[12][];
        long[] times = new long[12];
        for (int i = 0; i < 12; i++) {
            sensor[i] = scene.frames.get(i).sensorToWorld;
            times[i] = i * 2000L;
        }
        BundleAdjuster.Result fixed = BundleAdjuster.adjust(sensor, times, pairs, wrong, false);
        BundleAdjuster.Result refined = BundleAdjuster.adjust(sensor, times, pairs, wrong, true);
        assertEquals(1, fixed.focalScale, 0);
        assertEquals(1 / 1.02, refined.focalScale, 0.004);
        assertTrue(refined.rmsAfter < 0.6 * fixed.rmsAfter);
    }

    @Test
    public void bundleAdjuster_leavesTheFocalLengthAlone_withTooFewMatchesToSay() {
        double[][] truth = {toWorld(StitchScene.pose(0, 0, 0)),
                toWorld(StitchScene.pose(30, 0, 0))};
        PairMatcher.Pair few = rays(truth, 0, 1, 20);
        BundleAdjuster.Result result = BundleAdjuster.adjust(truth, null,
                Collections.singletonList(few), 250, true);
        assertEquals(1, result.focalScale, 0);
    }

    // Helpers.

    private static DirectAligner.Link align(ArgbImage a, ArgbImage b, double[] start) {
        byte[] lumaA = Stitcher.luma(a, Stitcher.FEATURE_SHRINK);
        byte[] lumaB = Stitcher.luma(b, Stitcher.FEATURE_SHRINK);
        int w = a.width / Stitcher.FEATURE_SHRINK;
        int h = a.height / Stitcher.FEATURE_SHRINK;
        return DirectAligner.align(0, new DirectAligner.Pyramid(lumaA, w, h), 1,
                new DirectAligner.Pyramid(lumaB, w, h), start,
                StitchScene.FOCAL / Stitcher.FEATURE_SHRINK);
    }

    /** The rotation from a's camera axes to b's. */
    private static double[] relative(CameraPose a, CameraPose b) {
        return Rotations.multiply(Rotations.transpose(toWorld(b)), toWorld(a));
    }

    private static double[] toWorld(CameraPose pose) {
        return new StitchFrame(pose, 0).sensorToWorld;
    }

    private static ArgbImage render(Scene scene, CameraPose pose) {
        int[] pixels = new int[pose.width * pose.height];
        float[] direction = new float[3];
        double[] d = new double[3];
        for (int y = 0; y < pose.height; y++) {
            for (int x = 0; x < pose.width; x++) {
                pose.unproject(x + 0.5f, y + 0.5f, direction);
                double length = Math.sqrt(direction[0] * direction[0]
                        + direction[1] * direction[1] + direction[2] * direction[2]);
                d[0] = direction[0] / length;
                d[1] = direction[1] / length;
                d[2] = direction[2] / length;
                int level = (int) Math.max(0, Math.min(255, Math.round(scene.brightness(d))));
                pixels[y * pose.width + x] = 0xFF000000 | (level << 16) | (level << 8) | level;
            }
        }
        return new ArgbImage(pixels, pose.width, pose.height);
    }

    /** Matches between two frames, made from their true rotations. */
    private static PairMatcher.Pair rays(double[][] truth, int i, int j) {
        return rays(truth, i, j, 60);
    }

    private static PairMatcher.Pair rays(double[][] truth, int i, int j, int count) {
        Random random = new Random(i * 31 + j);
        double[] mid = new double[3];
        for (int k = 0; k < 3; k++) {
            mid[k] = truth[i][k * 3 + 2] + truth[j][k * 3 + 2];
        }
        double[] a = new double[count * 3];
        double[] b = new double[count * 3];
        double[] d = new double[3];
        double[] camera = new double[3];
        for (int n = 0; n < count; n++) {
            double length = 0;
            for (int k = 0; k < 3; k++) {
                d[k] = mid[k] + 0.3 * random.nextGaussian();
                length += d[k] * d[k];
            }
            length = Math.sqrt(length);
            for (int k = 0; k < 3; k++) {
                d[k] /= length;
            }
            Rotations.apply(Rotations.transpose(truth[i]), d[0], d[1], d[2], camera);
            System.arraycopy(camera, 0, a, n * 3, 3);
            Rotations.apply(Rotations.transpose(truth[j]), d[0], d[1], d[2], camera);
            System.arraycopy(camera, 0, b, n * 3, 3);
        }
        return new PairMatcher.Pair(i, j, count, a, b, Rotations.identity(), count);
    }

    /** How far a frame is from the truth, against how far the first frame is. */
    private static double degreesOff(BundleAdjuster.Result result, double[][] truth, int frame) {
        double[] first = Rotations.multiply(result.toWorld[0], Rotations.transpose(truth[0]));
        double[] error = Rotations.multiply(result.toWorld[frame],
                Rotations.transpose(truth[frame]));
        return Math.toDegrees(Rotations.angle(Rotations.multiply(error,
                Rotations.transpose(first))));
    }

    private static double sq(double v) {
        return v * v;
    }
}
