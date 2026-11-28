package com.xlythe.view.camera.pano;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Turns every frame of a sweep at once until the rays the frames share agree, which takes out the
 * drift a rotation sensor builds up over a sweep and closes the loop where a panorama meets its own
 * start.
 *
 * <p>Each match says two frames saw one point, so their rays to it should be one ray in the
 * world. The error is the gap between them, scaled by the focal length so it reads in pixels, with
 * a Huber loss so a wrong match that got through cannot pull a frame off. Where two frames share
 * too few corners, the matches are the pixels their brightness aligned ({@link DirectAligner}).</p>
 *
 * <p>The sensor is heard twice. Each frame is held to where the sensor put it, firmly about the
 * horizontal axes, which gravity measures well, and loosely about the vertical, which is where a
 * gyroscope drifts; that keeps the horizon level and settles the one freedom the matches cannot,
 * turning every frame together. And each frame is held to the turn the sensor measured since the
 * frame before it, more loosely the longer the gap: whatever the sensor got wrong it got wrong
 * alike in frames moments apart, so a frame nothing else reaches follows the correction its
 * neighbours get instead of staying where the sensor alone put it, out of line with them.</p>
 *
 * <p>The focal length can be refined too, as Brown and Lowe do. A lens calibrated a percent long
 * makes every pair of frames look a percent further apart than they are, which no set of turns
 * can undo all the way round: the loop fails to close by several degrees, spread over every
 * seam. One focal length is shared by every frame, held loosely to the calibrated one.</p>
 *
 * <p>The unknowns are a small turn of each frame in the world's axes, three to a frame, and the
 * logarithm of the focal length's scale, solved by Levenberg-Marquardt on the dense normal
 * equations, which for a hundred frames is a few hundred unknowns.</p>
 */
@WorkerThread
final class BundleAdjuster {
    /** Beyond this many pixels a ray's error counts linearly rather than squared. */
    static final double HUBER_PIXELS = 2;

    /** How far the sensor may be off about a horizontal axis, in radians: about a degree. */
    static final double TILT_SIGMA = Math.toRadians(1);

    /** How far it may be off about the vertical, where a gyroscope drifts. */
    static final double YAW_SIGMA = Math.toRadians(8);

    /** How far the turn the sensor measured between two frames may be off, however close. */
    static final double CHAIN_SIGMA = Math.toRadians(0.5);

    /** How much further it may be off for every second between them, as the gyroscope drifts. */
    static final double CHAIN_DRIFT_PER_SECOND = Math.toRadians(0.1);

    /** How far the calibrated focal length may be off, as a share of it. */
    static final double FOCAL_SIGMA = 0.03;

    /** The fewest matched rays that say enough about the focal length to refine it. */
    static final int MIN_FOCAL_RAYS = 200;

    /** The most iterations. */
    private static final int MAX_ITERATIONS = 40;

    /** What the adjustment did, in pixels at the focal length the matches were measured at. */
    static final class Result {
        /** Each frame's rotation into the world after adjustment. */
        final double[][] toWorld;

        /** How much the focal length was scaled by: 1 unless it was refined. */
        final double focalScale;

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

        Result(double[][] toWorld, double focalScale, double rmsBefore, double rmsAfter,
                double medianBefore, double medianAfter, double p95Before, double p95After,
                int rays, int iterations) {
            this.toWorld = toWorld;
            this.focalScale = focalScale;
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

    /** A turn the sensor measured between two frames, and how firmly it is known. */
    private static final class Turn {
        /** The first frame. */
        final int first;

        /** The second frame. */
        final int second;

        /** The rotation from the first camera's axes to the second's. */
        final double[] rotation;

        /** One over the square of how far it may be off, in radians, about every axis. */
        final double weight;

        Turn(int first, int second, double[] rotation, double weight) {
            this.first = first;
            this.second = second;
            this.rotation = rotation;
            this.weight = weight;
        }
    }

    /** What is being solved for, and what it is held to. */
    private static final class Problem {
        /** Each frame's rotation as the sensor put it. */
        final double[][] sensor;

        /** The matches. */
        final List<PairMatcher.Pair> pairs;

        /** The turns the sensor measured between frames taken one after another. */
        final List<Turn> turns;

        /** The focal length the matches' rays were made with, which errors are measured in. */
        final double focal;

        /** Whether the focal length is an unknown. */
        final boolean refineFocal;

        /** The logarithm of the focal length's scale the calibration holds it to. */
        final double priorLogScale;

        Problem(double[][] sensor, List<PairMatcher.Pair> pairs, List<Turn> turns, double focal,
                boolean refineFocal, double priorLogScale) {
            this.sensor = sensor;
            this.pairs = pairs;
            this.turns = turns;
            this.focal = focal;
            this.refineFocal = refineFocal;
            this.priorLogScale = priorLogScale;
        }
    }

    private BundleAdjuster() {}

    /**
     * Adjusts the frames by their matches alone, with the sensor as an absolute prior.
     *
     * @param sensor each frame's rotation into the world as the sensor put it
     * @param pairs the matches between frames
     * @param focal the focal length the matches' pixels were measured at
     */
    @NonNull
    static Result adjust(@NonNull double[][] sensor, @NonNull List<PairMatcher.Pair> pairs,
            double focal) {
        return adjust(sensor, null, pairs, focal, false);
    }

    /**
     * Adjusts the frames.
     *
     * @param sensor each frame's rotation into the world as the sensor put it
     * @param times when each frame was taken, in milliseconds, so that the turn the sensor
     *     measured between one frame and the next holds them together; or null to leave it out
     * @param pairs the matches between frames
     * @param focal the focal length the matches' pixels were measured at
     * @param refineFocal whether to refine the focal length too, which it does only when there
     *     are matches enough to say
     */
    @NonNull
    static Result adjust(@NonNull double[][] sensor, @Nullable long[] times,
            @NonNull List<PairMatcher.Pair> pairs, double focal, boolean refineFocal) {
        int frames = sensor.length;
        double[][] current = new double[frames][];
        for (int i = 0; i < frames; i++) {
            current[i] = sensor[i].clone();
        }
        int rays = 0;
        for (PairMatcher.Pair pair : pairs) {
            rays += pair.count;
        }
        double logScale = 0;
        if (rays == 0) {
            return new Result(current, 1, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        List<Turn> turns = new ArrayList<>();
        if (times != null) {
            addChain(sensor, times, turns);
        }
        Problem problem = new Problem(sensor, pairs, turns, focal,
                refineFocal && rays >= MIN_FOCAL_RAYS, 0);
        double[] errorsBefore = errors(current, logScale, problem, rays);

        int unknowns = frames * 3 + (problem.refineFocal ? 1 : 0);
        double[] h = new double[unknowns * unknowns];
        double[] g = new double[unknowns];
        double lambda = 1e-3;
        double cost = cost(current, logScale, problem);
        int iteration = 0;
        for (; iteration < MAX_ITERATIONS; iteration++) {
            Arrays.fill(h, 0);
            Arrays.fill(g, 0);
            linearize(current, logScale, problem, h, g, unknowns);
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
                double candidateScale = problem.refineFocal
                        ? logScale + step[frames * 3] : logScale;
                double candidateCost = cost(candidate, candidateScale, problem);
                if (candidateCost < cost) {
                    double decrease = (cost - candidateCost) / Math.max(cost, 1e-12);
                    current = candidate;
                    logScale = candidateScale;
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
        double[] errorsAfter = errors(current, logScale, problem, rays);
        return new Result(current, Math.exp(logScale), rms(errorsBefore), rms(errorsAfter),
                percentile(errorsBefore, 0.5), percentile(errorsAfter, 0.5),
                percentile(errorsBefore, 0.95), percentile(errorsAfter, 0.95), rays,
                Math.min(iteration, MAX_ITERATIONS));
    }

    /**
     * The root mean square of one pair's ray errors, in pixels, with these rotations and the
     * focal length scaled by this much.
     */
    static double rms(@NonNull PairMatcher.Pair pair, @NonNull double[][] toWorld, double focal,
            double focalScale) {
        double logScale = Math.log(focalScale);
        double[] a = new double[6];
        double[] b = new double[6];
        double[] di = new double[3];
        double[] dj = new double[3];
        double sum = 0;
        for (int k = 0; k < pair.count; k++) {
            scaledRay(pair.firstRays, k, logScale, a);
            scaledRay(pair.secondRays, k, logScale, b);
            Rotations.apply(toWorld[pair.first], a[0], a[1], a[2], di);
            Rotations.apply(toWorld[pair.second], b[0], b[1], b[2], dj);
            sum += sq(di[0] - dj[0]) + sq(di[1] - dj[1]) + sq(di[2] - dj[2]);
        }
        return pair.count == 0 ? 0 : focal * Math.sqrt(sum / pair.count);
    }

    /**
     * A match's ray made again for the focal length scaled by e to the logScale, and how it
     * moves as that grows: out[0..2] the ray, out[3..5] its derivative.
     */
    private static void scaledRay(@NonNull double[] rays, int k, double logScale,
            @NonNull double[] out) {
        double z = rays[k * 3 + 2];
        double shrink = Math.exp(-logScale);
        double x = rays[k * 3] / z * shrink;
        double y = rays[k * 3 + 1] / z * shrink;
        double length = Math.sqrt(x * x + y * y + 1);
        double nx = x / length;
        double ny = y / length;
        double nz = 1 / length;
        out[0] = nx;
        out[1] = ny;
        out[2] = nz;
        // The unnormalised ray moves by (-x, -y, 0); normalising takes out the part along it.
        double dot = -(x * nx + y * ny);
        out[3] = (-x - dot * nx) / length;
        out[4] = (-y - dot * ny) / length;
        out[5] = (0 - dot * nz) / length;
    }

    /**
     * Holds each frame to the turn the sensor measured since the frame taken before it, more
     * loosely the longer between them.
     */
    private static void addChain(@NonNull double[][] sensor, @NonNull long[] times,
            @NonNull List<Turn> turns) {
        Integer[] order = new Integer[sensor.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> times[a] < times[b] ? -1 : times[a] > times[b] ? 1 : 0);
        for (int k = 1; k < order.length; k++) {
            int first = order[k - 1];
            int second = order[k];
            double seconds = Math.abs(times[second] - times[first]) / 1000.0;
            double sigma = CHAIN_SIGMA + CHAIN_DRIFT_PER_SECOND * seconds;
            turns.add(new Turn(first, second, Rotations.multiply(
                    Rotations.transpose(sensor[second]), sensor[first]), 1 / (sigma * sigma)));
        }
    }

    /** Each matched ray's error, in pixels. */
    @NonNull
    private static double[] errors(@NonNull double[][] toWorld, double logScale,
            @NonNull Problem problem, int rays) {
        double[] errors = new double[rays];
        double[] a = new double[6];
        double[] b = new double[6];
        double[] di = new double[3];
        double[] dj = new double[3];
        int n = 0;
        for (PairMatcher.Pair pair : problem.pairs) {
            double[] mi = toWorld[pair.first];
            double[] mj = toWorld[pair.second];
            for (int k = 0; k < pair.count; k++) {
                scaledRay(pair.firstRays, k, logScale, a);
                scaledRay(pair.secondRays, k, logScale, b);
                Rotations.apply(mi, a[0], a[1], a[2], di);
                Rotations.apply(mj, b[0], b[1], b[2], dj);
                double ex = di[0] - dj[0];
                double ey = di[1] - dj[1];
                double ez = di[2] - dj[2];
                errors[n++] = problem.focal * Math.sqrt(ex * ex + ey * ey + ez * ez);
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

    /**
     * How far two frames are from a turn measured between them, as a rotation vector in the
     * world's axes: zero when the second sits exactly that turn from the first.
     */
    @NonNull
    private static double[] turnError(@NonNull double[][] toWorld, @NonNull Turn turn) {
        // The first camera's axes taken into the world through the second and the turn,
        // against the first camera's own.
        return Rotations.log(Rotations.multiply(Rotations.multiply(toWorld[turn.second],
                turn.rotation), Rotations.transpose(toWorld[turn.first])));
    }

    /**
     * The robust cost: Huber on every ray's error, the sensor's pull on every frame and between
     * frames taken one after another, and the calibration's on the focal length.
     */
    private static double cost(@NonNull double[][] toWorld, double logScale,
            @NonNull Problem problem) {
        double cost = 0;
        double focal = problem.focal;
        double[] a = new double[6];
        double[] b = new double[6];
        double[] di = new double[3];
        double[] dj = new double[3];
        for (PairMatcher.Pair pair : problem.pairs) {
            double[] mi = toWorld[pair.first];
            double[] mj = toWorld[pair.second];
            for (int k = 0; k < pair.count; k++) {
                scaledRay(pair.firstRays, k, logScale, a);
                scaledRay(pair.secondRays, k, logScale, b);
                Rotations.apply(mi, a[0], a[1], a[2], di);
                Rotations.apply(mj, b[0], b[1], b[2], dj);
                double ex = di[0] - dj[0];
                double ey = di[1] - dj[1];
                double ez = di[2] - dj[2];
                double e = focal * Math.sqrt(ex * ex + ey * ey + ez * ez);
                cost += e <= HUBER_PIXELS ? e * e : 2 * HUBER_PIXELS * e - HUBER_PIXELS * HUBER_PIXELS;
            }
        }
        for (int i = 0; i < toWorld.length; i++) {
            double[] e = Rotations.log(Rotations.multiply(toWorld[i],
                    Rotations.transpose(problem.sensor[i])));
            cost += sq(e[0] / TILT_SIGMA) + sq(e[1] / TILT_SIGMA) + sq(e[2] / YAW_SIGMA);
        }
        for (Turn turn : problem.turns) {
            double[] e = turnError(toWorld, turn);
            cost += turn.weight * (e[0] * e[0] + e[1] * e[1] + e[2] * e[2]);
        }
        if (problem.refineFocal) {
            cost += sq((logScale - problem.priorLogScale) / FOCAL_SIGMA);
        }
        return cost;
    }

    /** Fills the normal equations about the current rotations and focal length. */
    private static void linearize(@NonNull double[][] toWorld, double logScale,
            @NonNull Problem problem, @NonNull double[] h, @NonNull double[] g, int n) {
        double focal = problem.focal;
        int f = toWorld.length * 3;
        double[] a = new double[6];
        double[] b = new double[6];
        double[] di = new double[3];
        double[] dj = new double[3];
        double[] ji = new double[9];
        double[] jj = new double[9];
        double[] js = new double[3];
        for (PairMatcher.Pair pair : problem.pairs) {
            int ia = pair.first * 3;
            int ib = pair.second * 3;
            double[] mi = toWorld[pair.first];
            double[] mj = toWorld[pair.second];
            for (int k = 0; k < pair.count; k++) {
                scaledRay(pair.firstRays, k, logScale, a);
                scaledRay(pair.secondRays, k, logScale, b);
                Rotations.apply(mi, a[0], a[1], a[2], di);
                Rotations.apply(mj, b[0], b[1], b[2], dj);
                double rx = focal * (di[0] - dj[0]);
                double ry = focal * (di[1] - dj[1]);
                double rz = focal * (di[2] - dj[2]);
                double e = Math.sqrt(rx * rx + ry * ry + rz * rz);
                double w = e <= HUBER_PIXELS ? 1 : HUBER_PIXELS / e;
                // A small turn w of a frame moves its ray d by w x d = -[d]x w.
                skew(di, -focal, ji);
                skew(dj, focal, jj);
                double[] r = {rx, ry, rz};
                accumulate(h, g, n, ia, ji, ia, ji, r, w);
                accumulate(h, g, n, ib, jj, ib, jj, r, w);
                cross(h, n, ia, ji, ib, jj, w);
                cross(h, n, ib, jj, ia, ji, w);
                if (problem.refineFocal) {
                    // How the error moves as the focal length grows: both rays turn outwards.
                    double[] ti = new double[3];
                    double[] tj = new double[3];
                    Rotations.apply(mi, a[3], a[4], a[5], ti);
                    Rotations.apply(mj, b[3], b[4], b[5], tj);
                    js[0] = focal * (ti[0] - tj[0]);
                    js[1] = focal * (ti[1] - tj[1]);
                    js[2] = focal * (ti[2] - tj[2]);
                    h[f * n + f] += w * (js[0] * js[0] + js[1] * js[1] + js[2] * js[2]);
                    g[f] += w * (js[0] * rx + js[1] * ry + js[2] * rz);
                    for (int p = 0; p < 3; p++) {
                        double si = 0;
                        double sj = 0;
                        for (int q = 0; q < 3; q++) {
                            si += js[q] * ji[q * 3 + p];
                            sj += js[q] * jj[q * 3 + p];
                        }
                        h[f * n + ia + p] += w * si;
                        h[(ia + p) * n + f] += w * si;
                        h[f * n + ib + p] += w * sj;
                        h[(ib + p) * n + f] += w * sj;
                    }
                }
            }
        }
        double[] weights = {1 / TILT_SIGMA, 1 / TILT_SIGMA, 1 / YAW_SIGMA};
        for (int i = 0; i < toWorld.length; i++) {
            double[] e = Rotations.log(Rotations.multiply(toWorld[i],
                    Rotations.transpose(problem.sensor[i])));
            for (int axis = 0; axis < 3; axis++) {
                double s = weights[axis];
                h[(i * 3 + axis) * n + i * 3 + axis] += s * s;
                g[i * 3 + axis] += s * s * e[axis];
            }
        }
        // A small turn of the second frame adds to a turn's error, and one of the first takes
        // from it.
        for (Turn turn : problem.turns) {
            double[] e = turnError(toWorld, turn);
            double w = turn.weight;
            int ia = turn.first * 3;
            int ib = turn.second * 3;
            for (int p = 0; p < 3; p++) {
                h[(ia + p) * n + ia + p] += w;
                h[(ib + p) * n + ib + p] += w;
                h[(ia + p) * n + ib + p] -= w;
                h[(ib + p) * n + ia + p] -= w;
                g[ia + p] -= w * e[p];
                g[ib + p] += w * e[p];
            }
        }
        if (problem.refineFocal) {
            double s = 1 / FOCAL_SIGMA;
            h[f * n + f] += s * s;
            g[f] += s * s * (logScale - problem.priorLogScale);
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
