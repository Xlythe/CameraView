package com.xlythe.view.camera.pano;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.util.Arrays;
import java.util.Random;

/**
 * Matches the corners of two overlapping frames, and keeps the matches one rotation about the
 * lens explains.
 *
 * <p>The rotation sensor already says roughly how the two frames sit, so each corner of the first
 * is looked for only near where that puts it in the second, which rules out nearly every wrong
 * match before any is made. What is left goes through RANSAC with the smallest model there is for
 * a camera turning on the spot: two rays pin a rotation, so each trial draws two matches, and
 * since a rotation keeps angles, a pair whose rays are a different angle apart in the two frames
 * is thrown out before it is solved. The rotation the most matches agree with is solved again from
 * all of them.</p>
 */
@WorkerThread
final class PairMatcher {
    /** The most bits two descriptors may differ in and still be a match. */
    static final int MAX_DISTANCE = 64;

    /** The nearest descriptor must be this much nearer than the runner-up to be trusted. */
    static final float RATIO = 0.8f;

    /** How far from the matched ray a corner may land and still agree, in pixels. */
    static final float INLIER_PIXELS = 2.5f;

    /** The fewest agreeing matches that make a pair worth trusting. */
    static final int MIN_INLIERS = 10;

    /** The smallest share of the matches that must agree. */
    static final float MIN_INLIER_SHARE = 0.2f;

    /**
     * How far the matched rotation may be from the sensor's before it is a coincidence rather
     * than a correction, in degrees.
     */
    static final double MAX_CORRECTION_DEGREES = 12;

    /** The most RANSAC trials. */
    private static final int MAX_TRIALS = 400;

    /** Two frames, and the rays both saw. */
    static final class Pair {
        /** The first frame. */
        final int first;

        /** The second frame. */
        final int second;

        /** How many matches agree. */
        final int count;

        /** Each match's ray in the first frame's camera, x, y, z one after another. */
        final double[] firstRays;

        /** The same matches' rays in the second frame's camera. */
        final double[] secondRays;

        /** The rotation the matches agree on, from the first camera to the second. */
        final double[] rotation;

        /** How many matches there were before RANSAC. */
        final int candidates;

        Pair(int first, int second, int count, double[] firstRays, double[] secondRays,
                double[] rotation, int candidates) {
            this.first = first;
            this.second = second;
            this.count = count;
            this.firstRays = firstRays;
            this.secondRays = secondRays;
            this.rotation = rotation;
            this.candidates = candidates;
        }
    }

    private PairMatcher() {}

    /**
     * Matches two frames.
     *
     * @param firstToWorld the first camera's rotation into the world, as the sensor put it
     * @param focal the focal length in the pixels the corners were found in
     * @param searchRadius how far from where the sensor puts it a corner is looked for, in pixels
     * @return the matches that agree, or null if too few do
     */
    @Nullable
    static Pair match(int first, @NonNull Features a, @NonNull double[] firstToWorld,
            int second, @NonNull Features b, @NonNull Features.Index bIndex,
            @NonNull double[] secondToWorld, float focal, float searchRadius) {
        double[] prior = Rotations.multiply(Rotations.transpose(secondToWorld), firstToWorld);
        float cxA = a.width / 2f;
        float cyA = a.height / 2f;
        float cxB = b.width / 2f;
        float cyB = b.height / 2f;

        // The best corner of b for each corner of a, keeping only the best a for each b.
        int[] partner = new int[b.count];
        int[] partnerDistance = new int[b.count];
        Arrays.fill(partner, -1);
        int[] nearest = new int[3];
        double[] ray = new double[3];
        double[] predicted = new double[3];
        for (int i = 0; i < a.count; i++) {
            rayOf(a.x[i], a.y[i], cxA, cyA, focal, ray);
            Rotations.apply(prior, ray[0], ray[1], ray[2], predicted);
            if (predicted[2] <= 0.2) {
                continue;
            }
            // Where it lands, counted as corners are: from the first pixel's centre.
            float px = (float) (cxB + focal * predicted[0] / predicted[2]) - 0.5f;
            float py = (float) (cyB + focal * predicted[1] / predicted[2]) - 0.5f;
            if (px < -searchRadius || py < -searchRadius || px > b.width + searchRadius
                    || py > b.height + searchRadius) {
                continue;
            }
            bIndex.nearest(a, i, px, py, searchRadius, nearest);
            int j = nearest[0];
            if (j < 0 || nearest[1] > MAX_DISTANCE
                    || (nearest[2] != Integer.MAX_VALUE && nearest[1] >= RATIO * nearest[2])) {
                continue;
            }
            if (partner[j] < 0 || nearest[1] < partnerDistance[j]) {
                partner[j] = i;
                partnerDistance[j] = nearest[1];
            }
        }
        int candidates = 0;
        for (int j = 0; j < b.count; j++) {
            if (partner[j] >= 0) {
                candidates++;
            }
        }
        if (candidates < MIN_INLIERS) {
            return null;
        }
        double[] raysA = new double[candidates * 3];
        double[] raysB = new double[candidates * 3];
        int n = 0;
        for (int j = 0; j < b.count; j++) {
            if (partner[j] < 0) {
                continue;
            }
            int i = partner[j];
            rayOf(a.x[i], a.y[i], cxA, cyA, focal, ray);
            System.arraycopy(ray, 0, raysA, n * 3, 3);
            rayOf(b.x[j], b.y[j], cxB, cyB, focal, ray);
            System.arraycopy(ray, 0, raysB, n * 3, 3);
            n++;
        }

        double threshold = 1 - Math.cos(Math.atan(INLIER_PIXELS / focal));
        boolean[] inliers = ransac(raysA, raysB, candidates, threshold);
        if (inliers == null) {
            return null;
        }
        double[] rotation = refit(raysA, raysB, candidates, inliers);
        int count = mark(rotation, raysA, raysB, candidates, threshold, inliers);
        if (count < MIN_INLIERS || count < MIN_INLIER_SHARE * candidates) {
            return null;
        }
        rotation = refit(raysA, raysB, candidates, inliers);
        count = mark(rotation, raysA, raysB, candidates, threshold, inliers);
        double correction = Math.toDegrees(Rotations.angle(
                Rotations.multiply(rotation, Rotations.transpose(prior))));
        if (count < MIN_INLIERS || correction > MAX_CORRECTION_DEGREES) {
            return null;
        }
        double[] keptA = new double[count * 3];
        double[] keptB = new double[count * 3];
        int k = 0;
        for (int i = 0; i < candidates; i++) {
            if (inliers[i]) {
                System.arraycopy(raysA, i * 3, keptA, k * 3, 3);
                System.arraycopy(raysB, i * 3, keptB, k * 3, 3);
                k++;
            }
        }
        return new Pair(first, second, count, keptA, keptB, rotation, candidates);
    }

    /**
     * The unit ray, in the camera, through a corner of a frame.
     *
     * @param x the corner's column, counted from the first pixel's centre as corners are, so
     *     the frame's own edge is half a pixel before it
     * @param cx the middle of the frame, counted from its edge
     */
    static void rayOf(float x, float y, float cx, float cy, float focal, @NonNull double[] out) {
        double a = (x + 0.5 - cx) / focal;
        double b = (y + 0.5 - cy) / focal;
        double length = Math.sqrt(a * a + b * b + 1);
        out[0] = a / length;
        out[1] = b / length;
        out[2] = 1 / length;
    }

    /** Which matches the best two-match rotation explains, or null if none explains enough. */
    @Nullable
    private static boolean[] ransac(@NonNull double[] a, @NonNull double[] b, int count,
            double threshold) {
        Random random = new Random(count * 31L + 7);
        boolean[] best = null;
        int bestCount = 0;
        boolean[] trial = new boolean[count];
        double[] sampleA = new double[6];
        double[] sampleB = new double[6];
        double angleTolerance = 2 * Math.acos(1 - threshold);
        int trials = MAX_TRIALS;
        for (int t = 0; t < trials; t++) {
            int i = random.nextInt(count);
            int j = random.nextInt(count);
            if (i == j) {
                continue;
            }
            double dotA = a[i * 3] * a[j * 3] + a[i * 3 + 1] * a[j * 3 + 1]
                    + a[i * 3 + 2] * a[j * 3 + 2];
            double dotB = b[i * 3] * b[j * 3] + b[i * 3 + 1] * b[j * 3 + 1]
                    + b[i * 3 + 2] * b[j * 3 + 2];
            double apart = Math.acos(Math.min(1, dotA));
            if (Math.abs(apart - Math.acos(Math.min(1, dotB))) > angleTolerance
                    || apart < 0.01) {
                continue;
            }
            System.arraycopy(a, i * 3, sampleA, 0, 3);
            System.arraycopy(a, j * 3, sampleA, 3, 3);
            System.arraycopy(b, i * 3, sampleB, 0, 3);
            System.arraycopy(b, j * 3, sampleB, 3, 3);
            double[] rotation = Rotations.fit(sampleA, sampleB, 2, null);
            int agreeing = mark(rotation, a, b, count, threshold, trial);
            if (agreeing > bestCount) {
                bestCount = agreeing;
                best = trial.clone();
                double share = (double) agreeing / count;
                double needed = Math.log(0.01) / Math.log(Math.max(1e-9, 1 - share * share));
                trials = Math.min(MAX_TRIALS, Math.max(t + 1, (int) Math.ceil(needed)));
            }
        }
        return bestCount >= 2 ? best : null;
    }

    /** Marks the matches a rotation explains, and counts them. */
    private static int mark(@NonNull double[] rotation, @NonNull double[] a, @NonNull double[] b,
            int count, double threshold, @NonNull boolean[] out) {
        int agreeing = 0;
        double[] turned = new double[3];
        for (int i = 0; i < count; i++) {
            Rotations.apply(rotation, a[i * 3], a[i * 3 + 1], a[i * 3 + 2], turned);
            double dot = turned[0] * b[i * 3] + turned[1] * b[i * 3 + 1] + turned[2] * b[i * 3 + 2];
            out[i] = 1 - dot < threshold;
            if (out[i]) {
                agreeing++;
            }
        }
        return agreeing;
    }

    /** The rotation that best fits the marked matches. */
    @NonNull
    private static double[] refit(@NonNull double[] a, @NonNull double[] b, int count,
            @NonNull boolean[] marked) {
        double[] weights = new double[count];
        for (int i = 0; i < count; i++) {
            weights[i] = marked[i] ? 1 : 0;
        }
        return Rotations.fit(a, b, count, weights);
    }
}
