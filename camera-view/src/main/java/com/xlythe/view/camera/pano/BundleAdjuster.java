package com.xlythe.view.camera.pano;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.util.Arrays;
import java.util.List;

/**
 * Turns every frame of a sweep at once until the rays the frames share agree, which takes out the
 * drift a rotation sensor builds up over a sweep and closes the loop where a panorama meets its own
 * start.
 *
 * <p>Each match says two frames saw one point, so their rays to it should be one ray in the
 * world. The error is the gap between them, scaled by the focal length so it reads in pixels, with
 * a Huber loss so a wrong match that got through cannot pull a frame off. Each frame is also held
 * to where the sensor put it, firmly about the horizontal axes, which gravity measures well, and
 * loosely about the vertical, which is where a gyroscope drifts. That keeps the horizon level,
 * places a frame no match reaches, and settles the one freedom the matches cannot: turning every
 * frame together.</p>
 *
 * <p>The unknowns are a small turn of each frame in the world's axes, three to a frame, solved by
 * Levenberg-Marquardt on the dense normal equations, which for a hundred frames is a few hundred
 * unknowns.</p>
 */
@WorkerThread
final class BundleAdjuster {
    /** Beyond this many pixels a ray's error counts linearly rather than squared. */
    static final double HUBER_PIXELS = 2;

    /** How far the sensor may be off about a horizontal axis, in radians: about a degree. */
    static final double TILT_SIGMA = Math.toRadians(1);

    /** How far it may be off about the vertical, where a gyroscope drifts. */
    static final double YAW_SIGMA = Math.toRadians(8);

    /** The most iterations. */
    private static final int MAX_ITERATIONS = 40;

    /** What the adjustment did, in pixels at the focal length the matches were measured at. */
    static final class Result {
        /** Each frame's rotation into the world after adjustment. */
        final double[][] toWorld;

        /** The root mean square of the rays' errors with the sensor's rotations. */
        final double rmsBefore;

        /** The same after adjustment. */
        final double rmsAfter;

        /** The median ray error after adjustment. */
        final double medianAfter;

        /** The median ray error with the sensor's rotations. */
        final double medianBefore;

        /** The 95th percentile of the ray errors after adjustment. */
        final double p95After;

        /** The 95th percentile with the sensor's rotations. */
        final double p95Before;

        /** How many matched rays there were. */
        final int rays;

        /** How many iterations it took. */
        final int iterations;

        Result(double[][] toWorld, double rmsBefore, double rmsAfter, double medianBefore,
                double medianAfter, double p95Before, double p95After, int rays,
                int iterations) {
            this.toWorld = toWorld;
            this.rmsBefore = rmsBefore;
            this.rmsAfter = rmsAfter;
            this.medianBefore = medianBefore;
            this.medianAfter = medianAfter;
            this.p95Before = p95Before;
            this.p95After = p95After;
            this.rays = rays;
            this.iterations = iterations;
        }
    }

    private BundleAdjuster() {}

    /**
     * Adjusts the frames.
     *
     * @param sensor each frame's rotation into the world as the sensor put it
     * @param pairs the matches between frames
     * @param focal the focal length the matches' pixels were measured at
     */
    @NonNull
    static Result adjust(@NonNull double[][] sensor, @NonNull List<PairMatcher.Pair> pairs,
            double focal) {
        int frames = sensor.length;
        double[][] current = new double[frames][];
        for (int i = 0; i < frames; i++) {
            current[i] = sensor[i].clone();
        }
        int rays = 0;
        for (PairMatcher.Pair pair : pairs) {
            rays += pair.count;
        }
        double[] errorsBefore = errors(current, pairs, focal, rays);
        if (rays == 0) {
            return new Result(current, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        int unknowns = frames * 3;
        double[] h = new double[unknowns * unknowns];
        double[] g = new double[unknowns];
        double lambda = 1e-3;
        double cost = cost(current, sensor, pairs, focal);
        int iteration = 0;
        for (; iteration < MAX_ITERATIONS; iteration++) {
            Arrays.fill(h, 0);
            Arrays.fill(g, 0);
            linearize(current, sensor, pairs, focal, h, g);
            boolean improved = false;
            for (int attempt = 0; attempt < 8 && !improved; attempt++) {
                double[] step = solve(h, g, unknowns, lambda);
                if (step == null) {
                    lambda *= 10;
                    continue;
                }
                double[][] candidate = new double[frames][];
                for (int i = 0; i < frames; i++) {
                    candidate[i] = Rotations.multiply(
                            Rotations.exp(step[i * 3], step[i * 3 + 1], step[i * 3 + 2]),
                            current[i]);
                }
                double candidateCost = cost(candidate, sensor, pairs, focal);
                if (candidateCost < cost) {
                    double decrease = (cost - candidateCost) / Math.max(cost, 1e-12);
                    current = candidate;
                    cost = candidateCost;
                    lambda = Math.max(1e-9, lambda / 3);
                    improved = true;
                    if (decrease < 1e-7) {
                        iteration = MAX_ITERATIONS;
                    }
                } else {
                    lambda *= 4;
                }
            }
            if (!improved) {
                break;
            }
        }
        double[] errorsAfter = errors(current, pairs, focal, rays);
        return new Result(current, rms(errorsBefore), rms(errorsAfter),
                percentile(errorsBefore, 0.5), percentile(errorsAfter, 0.5),
                percentile(errorsBefore, 0.95), percentile(errorsAfter, 0.95), rays,
                Math.min(iteration, MAX_ITERATIONS));
    }

    /** Each matched ray's error, in pixels. */
    @NonNull
    private static double[] errors(@NonNull double[][] toWorld,
            @NonNull List<PairMatcher.Pair> pairs, double focal, int rays) {
        double[] errors = new double[rays];
        double[] di = new double[3];
        double[] dj = new double[3];
        int n = 0;
        for (PairMatcher.Pair pair : pairs) {
            double[] mi = toWorld[pair.first];
            double[] mj = toWorld[pair.second];
            for (int k = 0; k < pair.count; k++) {
                Rotations.apply(mi, pair.firstRays[k * 3], pair.firstRays[k * 3 + 1],
                        pair.firstRays[k * 3 + 2], di);
                Rotations.apply(mj, pair.secondRays[k * 3], pair.secondRays[k * 3 + 1],
                        pair.secondRays[k * 3 + 2], dj);
                double ex = di[0] - dj[0];
                double ey = di[1] - dj[1];
                double ez = di[2] - dj[2];
                errors[n++] = focal * Math.sqrt(ex * ex + ey * ey + ez * ez);
            }
        }
        return errors;
    }

    private static double rms(@NonNull double[] values) {
        if (values.length == 0) {
            return 0;
        }
        double sum = 0;
        for (double v : values) {
            sum += v * v;
        }
        return Math.sqrt(sum / values.length);
    }

    private static double percentile(@NonNull double[] values, double fraction) {
        if (values.length == 0) {
            return 0;
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[Math.min(sorted.length - 1, (int) (fraction * sorted.length))];
    }

    /** The robust cost: Huber on every ray's error, and the sensor's pull on every frame. */
    private static double cost(@NonNull double[][] toWorld, @NonNull double[][] sensor,
            @NonNull List<PairMatcher.Pair> pairs, double focal) {
        double cost = 0;
        double[] di = new double[3];
        double[] dj = new double[3];
        for (PairMatcher.Pair pair : pairs) {
            double[] mi = toWorld[pair.first];
            double[] mj = toWorld[pair.second];
            for (int k = 0; k < pair.count; k++) {
                Rotations.apply(mi, pair.firstRays[k * 3], pair.firstRays[k * 3 + 1],
                        pair.firstRays[k * 3 + 2], di);
                Rotations.apply(mj, pair.secondRays[k * 3], pair.secondRays[k * 3 + 1],
                        pair.secondRays[k * 3 + 2], dj);
                double ex = di[0] - dj[0];
                double ey = di[1] - dj[1];
                double ez = di[2] - dj[2];
                double e = focal * Math.sqrt(ex * ex + ey * ey + ez * ez);
                cost += e <= HUBER_PIXELS ? e * e : 2 * HUBER_PIXELS * e - HUBER_PIXELS * HUBER_PIXELS;
            }
        }
        for (int i = 0; i < toWorld.length; i++) {
            double[] e = Rotations.log(Rotations.multiply(toWorld[i],
                    Rotations.transpose(sensor[i])));
            cost += sq(e[0] / TILT_SIGMA) + sq(e[1] / TILT_SIGMA) + sq(e[2] / YAW_SIGMA);
        }
        return cost;
    }

    /** Fills the normal equations about the current rotations. */
    private static void linearize(@NonNull double[][] toWorld, @NonNull double[][] sensor,
            @NonNull List<PairMatcher.Pair> pairs, double focal, @NonNull double[] h,
            @NonNull double[] g) {
        int n = toWorld.length * 3;
        double[] di = new double[3];
        double[] dj = new double[3];
        double[] ji = new double[9];
        double[] jj = new double[9];
        for (PairMatcher.Pair pair : pairs) {
            int a = pair.first * 3;
            int b = pair.second * 3;
            double[] mi = toWorld[pair.first];
            double[] mj = toWorld[pair.second];
            for (int k = 0; k < pair.count; k++) {
                Rotations.apply(mi, pair.firstRays[k * 3], pair.firstRays[k * 3 + 1],
                        pair.firstRays[k * 3 + 2], di);
                Rotations.apply(mj, pair.secondRays[k * 3], pair.secondRays[k * 3 + 1],
                        pair.secondRays[k * 3 + 2], dj);
                double rx = focal * (di[0] - dj[0]);
                double ry = focal * (di[1] - dj[1]);
                double rz = focal * (di[2] - dj[2]);
                double e = Math.sqrt(rx * rx + ry * ry + rz * rz);
                double w = e <= HUBER_PIXELS ? 1 : HUBER_PIXELS / e;
                // A small turn w of a frame moves its ray d by w x d = -[d]x w.
                skew(di, -focal, ji);
                skew(dj, focal, jj);
                double[] r = {rx, ry, rz};
                accumulate(h, g, n, a, ji, a, ji, r, w);
                accumulate(h, g, n, b, jj, b, jj, r, w);
                cross(h, n, a, ji, b, jj, w);
                cross(h, n, b, jj, a, ji, w);
            }
        }
        double[] weights = {1 / TILT_SIGMA, 1 / TILT_SIGMA, 1 / YAW_SIGMA};
        for (int i = 0; i < toWorld.length; i++) {
            double[] e = Rotations.log(Rotations.multiply(toWorld[i],
                    Rotations.transpose(sensor[i])));
            for (int axis = 0; axis < 3; axis++) {
                double s = weights[axis];
                h[(i * 3 + axis) * n + i * 3 + axis] += s * s;
                g[i * 3 + axis] += s * s * e[axis];
            }
        }
    }

    /** scale times the matrix that crosses a vector with d from the left: [d]x. */
    private static void skew(@NonNull double[] d, double scale, @NonNull double[] out) {
        out[0] = 0;
        out[1] = -d[2] * scale;
        out[2] = d[1] * scale;
        out[3] = d[2] * scale;
        out[4] = 0;
        out[5] = -d[0] * scale;
        out[6] = -d[1] * scale;
        out[7] = d[0] * scale;
        out[8] = 0;
    }

    /** Adds w JᵀJ to a diagonal block and w Jᵀr to the gradient. */
    private static void accumulate(@NonNull double[] h, @NonNull double[] g, int n, int row,
            @NonNull double[] jr, int column, @NonNull double[] jc, @NonNull double[] r,
            double w) {
        for (int p = 0; p < 3; p++) {
            for (int q = 0; q < 3; q++) {
                double sum = 0;
                for (int k = 0; k < 3; k++) {
                    sum += jr[k * 3 + p] * jc[k * 3 + q];
                }
                h[(row + p) * n + column + q] += w * sum;
            }
            double sum = 0;
            for (int k = 0; k < 3; k++) {
                sum += jr[k * 3 + p] * r[k];
            }
            g[row + p] += w * sum;
        }
    }

    /** Adds w JaᵀJb to an off-diagonal block. */
    private static void cross(@NonNull double[] h, int n, int row, @NonNull double[] jr,
            int column, @NonNull double[] jc, double w) {
        for (int p = 0; p < 3; p++) {
            for (int q = 0; q < 3; q++) {
                double sum = 0;
                for (int k = 0; k < 3; k++) {
                    sum += jr[k * 3 + p] * jc[k * 3 + q];
                }
                h[(row + p) * n + column + q] += w * sum;
            }
        }
    }

    /**
     * Solves (H + lambda diag(H)) x = -g by Cholesky.
     *
     * @return the step, or null if the system is not positive definite
     */
    @Nullable
    private static double[] solve(@NonNull double[] h, @NonNull double[] g, int n,
            double lambda) {
        double[] l = new double[n * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = h[i * n + j];
                if (i == j) {
                    sum += lambda * h[i * n + i];
                }
                for (int k = 0; k < j; k++) {
                    sum -= l[i * n + k] * l[j * n + k];
                }
                if (i == j) {
                    if (sum <= 0) {
                        return null;
                    }
                    l[i * n + i] = Math.sqrt(sum);
                } else {
                    l[i * n + j] = sum / l[j * n + j];
                }
            }
        }
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = -g[i];
            for (int k = 0; k < i; k++) {
                sum -= l[i * n + k] * y[k];
            }
            y[i] = sum / l[i * n + i];
        }
        double[] x = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            double sum = y[i];
            for (int k = i + 1; k < n; k++) {
                sum -= l[k * n + i] * x[k];
            }
            x[i] = sum / l[i * n + i];
        }
        return x;
    }

    private static double sq(double v) {
        return v * v;
    }
}
