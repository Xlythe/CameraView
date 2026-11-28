package com.xlythe.view.camera.pano;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

/**
 * Aligns two overlapping frames by their brightness alone, for overlaps too plain for corners: a
 * ceiling, a floor, a clear sky, or the narrow strip a frame aimed at a pole shares with the ring
 * below it.
 *
 * <p>It maximises the enhanced correlation coefficient between the first frame and the second
 * turned onto it (Evangelidis and Psarakis, 2008), over the three angles of a rotation about the
 * lens. The coefficient ignores a difference in gain and offset between the frames, so exposure
 * need not be matched first. It works coarse to fine over a pyramid, starting from the best of a
 * few turns tried on the coarsest level, so a sensor several degrees out still lands in the right
 * place.</p>
 *
 * <p>What it finds goes to the {@link BundleAdjuster} as matches: pixels spread over the overlap,
 * each paired with where the turn found takes it in the other frame. Those say what the
 * alignment measured, pixel to pixel, so they mean the same whatever focal length the adjustment
 * settles on, as a turn measured at one focal length would not.</p>
 */
@WorkerThread
final class DirectAligner {
    /** How many levels the pyramid has, each half the size of the one before. */
    static final int LEVELS = 4;

    /** The smallest share of a frame that must overlap the other for the two to be aligned. */
    static final float MIN_OVERLAP = 0.03f;

    /**
     * The widest the pyramid's finest level is: finer adds nothing a sweep's frames need, a
     * twentieth of a pixel there is a hundredth of a degree, and costs memory a phone may not
     * have for every frame at once.
     */
    static final int MAX_WIDTH = 400;

    /** The lowest correlation, once aligned, that is taken as the frames agreeing. */
    static final double MIN_CORRELATION = 0.5;

    /** How far the turn found may be from where it started, in degrees, before it is doubted. */
    static final double MAX_CORRECTION_DEGREES = 8;

    /** How many matches a link stands for, spread over its overlap. */
    static final int MATCHES = 16;

    /** How far either way the coarsest level tries turns before refining, in degrees. */
    static final double SEARCH_DEGREES = 4;

    /** The step between the turns tried, in degrees. */
    static final double SEARCH_STEP_DEGREES = 2;

    /** The most refinements on one level. */
    private static final int MAX_ITERATIONS = 20;

    /** A refinement smaller than this, in pixels of its level, has converged. */
    private static final double CONVERGED_PIXELS = 0.02;

    /**
     * The most of the first frame's pixels used on one level; past it they are thinned evenly,
     * which costs the alignment nothing a sweep can see and bounds its time.
     */
    static final int MAX_POINTS = 6000;

    /** Pixels this close to a frame's edge are left out, where its gradient is one-sided. */
    private static final int BORDER = 2;

    /** A frame's brightness, smoothed, at several sizes. */
    static final class Pyramid {
        /** Each level's brightness, row by row. */
        final float[][] levels;

        /** Each level's horizontal gradient. */
        final float[][] dx;

        /** Each level's vertical gradient. */
        final float[][] dy;

        /** Each level's width. */
        final int[] widths;

        /** Each level's height. */
        final int[] heights;

        /** How much smaller the finest level is than the brightness it was made from. */
        final int shrink;

        Pyramid(@NonNull byte[] luma, int width, int height) {
            levels = new float[LEVELS][];
            dx = new float[LEVELS][];
            dy = new float[LEVELS][];
            widths = new int[LEVELS];
            heights = new int[LEVELS];
            int w = width;
            int h = height;
            float[] base = new float[width * height];
            for (int i = 0; i < base.length; i++) {
                base[i] = luma[i] & 0xFF;
            }
            int shrink = 1;
            while (w > MAX_WIDTH) {
                base = half(base, w, h);
                w = Math.max(1, w / 2);
                h = Math.max(1, h / 2);
                shrink *= 2;
            }
            this.shrink = shrink;
            float[] level = blur(base, w, h);
            for (int l = 0; l < LEVELS; l++) {
                levels[l] = level;
                widths[l] = w;
                heights[l] = h;
                dx[l] = new float[w * h];
                dy[l] = new float[w * h];
                for (int y = 1; y < h - 1; y++) {
                    for (int x = 1; x < w - 1; x++) {
                        int p = y * w + x;
                        dx[l][p] = (level[p + 1] - level[p - 1]) / 2;
                        dy[l][p] = (level[p + w] - level[p - w]) / 2;
                    }
                }
                if (l + 1 < LEVELS) {
                    level = blur(half(level, w, h), Math.max(1, w / 2), Math.max(1, h / 2));
                    w = Math.max(1, w / 2);
                    h = Math.max(1, h / 2);
                }
            }
        }

        /** The brightness between pixel centres, from the four around, held inside the level. */
        float sample(@NonNull float[] values, int level, float x, float y) {
            int w = widths[level];
            int h = heights[level];
            x = Math.max(0, Math.min(w - 1.001f, x));
            y = Math.max(0, Math.min(h - 1.001f, y));
            int x0 = (int) x;
            int y0 = (int) y;
            float fx = x - x0;
            float fy = y - y0;
            int p = y0 * w + x0;
            float top = values[p] + fx * (values[p + 1] - values[p]);
            float bottom = values[p + w] + fx * (values[p + w + 1] - values[p + w]);
            return top + fy * (bottom - top);
        }
    }

    /** A turn between two frames found from their brightness. */
    static final class Link {
        /** The first frame. */
        final int first;

        /** The second frame. */
        final int second;

        /** The rotation from the first camera's axes to the second's. */
        final double[] rotation;

        /** The correlation of the two frames once aligned, from -1 to 1. */
        final double correlation;

        /** The correlation where it started. */
        final double startCorrelation;

        /** How many pixels of the finest level overlapped. */
        final int points;

        /**
         * Where pixels spread over the overlap land in the second frame, as matches: what the
         * alignment measured, pixel to pixel, whatever focal length it was measured at.
         */
        final PairMatcher.Pair matches;

        Link(int first, int second, double[] rotation, double correlation,
                double startCorrelation, int points, PairMatcher.Pair matches) {
            this.first = first;
            this.second = second;
            this.rotation = rotation;
            this.correlation = correlation;
            this.startCorrelation = startCorrelation;
            this.points = points;
            this.matches = matches;
        }
    }

    private DirectAligner() {}

    /**
     * Aligns the second frame to the first.
     *
     * @param initial where to start: the rotation from the first camera's axes to the second's
     * @param lumaFocal the focal length in the pixels of the brightness the pyramids were made
     *     from
     * @return the turn found, or null if the frames overlap too little or never agree
     */
    @Nullable
    static Link align(int first, @NonNull Pyramid a, int second, @NonNull Pyramid b,
            @NonNull double[] initial, double lumaFocal) {
        double focal = lumaFocal / a.shrink;
        double[] rotation = initial.clone();
        Points points = null;
        double startCorrelation = Double.NaN;
        for (int level = LEVELS - 1; level >= 0; level--) {
            double f = focal / (1 << level);
            points = new Points(a, b, level, f, rotation);
            if (points.count < 16) {
                continue;
            }
            if (Double.isNaN(startCorrelation)) {
                startCorrelation = points.correlation(b, level, f, rotation);
                rotation = search(points, b, level, f, rotation);
            }
            for (int i = 0; i < MAX_ITERATIONS; i++) {
                double[] step = points.step(b, level, f, rotation);
                if (step == null) {
                    break;
                }
                rotation = Rotations.multiply(Rotations.exp(step[0], step[1], step[2]), rotation);
                if (f * Math.sqrt(step[0] * step[0] + step[1] * step[1] + step[2] * step[2])
                        < CONVERGED_PIXELS) {
                    break;
                }
            }
        }
        if (points == null || Double.isNaN(startCorrelation)) {
            return null;
        }
        startCorrelation = points.correlation(b, 0, focal, initial);
        double correlation = points.correlation(b, 0, focal, rotation);
        PairMatcher.Pair matches = points.matches(first, second, rotation, MATCHES);
        int count = points.overlapping * points.thinning;
        double correction = Math.toDegrees(Rotations.angle(
                Rotations.multiply(rotation, Rotations.transpose(initial))));
        if (matches == null || count < MIN_OVERLAP * a.widths[0] * a.heights[0]
                || correlation < MIN_CORRELATION
                || correlation <= startCorrelation || correction > MAX_CORRECTION_DEGREES) {
            return null;
        }
        return new Link(first, second, rotation, correlation, startCorrelation, count, matches);
    }

    /**
     * The best start among a few turns of the second camera across the line of sight, by
     * correlation on the coarsest level.
     */
    @NonNull
    private static double[] search(@NonNull Points points, @NonNull Pyramid b, int level,
            double f, @NonNull double[] rotation) {
        double best = points.correlation(b, level, f, rotation);
        double[] bestRotation = rotation;
        int steps = (int) Math.round(SEARCH_DEGREES / SEARCH_STEP_DEGREES);
        double step = Math.toRadians(SEARCH_STEP_DEGREES);
        for (int i = -steps; i <= steps; i++) {
            for (int j = -steps; j <= steps; j++) {
                if (i == 0 && j == 0) {
                    continue;
                }
                // About the second camera's x and y axes: across its frame, either way.
                double[] candidate = Rotations.multiply(Rotations.exp(i * step, j * step, 0),
                        rotation);
                double correlation = points.correlation(b, level, f, candidate);
                if (correlation > best) {
                    best = correlation;
                    bestRotation = candidate;
                }
            }
        }
        return bestRotation;
    }

    /** The first frame's pixels on one level, as rays, with their brightness. */
    private static final class Points {
        /** How many. */
        final int count;

        /** Each pixel's ray in the first camera, x, y and z one after another. */
        final double[] rays;

        /** Each pixel's brightness. */
        final float[] values;

        /** How many overlapped the second frame when last measured. */
        int overlapping;

        /** One pixel in this many of those near the overlap is used. */
        final int thinning;

        /** The second frame's position of each pixel when last measured, scratch. */
        private final float[] mU;

        /** See {@link #mU}. */
        private final float[] mV;

        /** Where each pixel lands in the second camera, x, y, z, scratch. */
        private final double[] mB;

        /**
         * Takes the first frame's pixels that land in the second, or near enough that a search
         * or a few refinements could bring them in.
         */
        Points(@NonNull Pyramid a, @NonNull Pyramid b, int level, double f,
                @NonNull double[] rotation) {
            int w = a.widths[level];
            int h = a.heights[level];
            int bw = b.widths[level];
            int bh = b.heights[level];
            double margin = f * Math.tan(Math.toRadians(SEARCH_DEGREES + 2));
            float[] image = a.levels[level];
            int n = 0;
            double[] rays = new double[w * h * 3];
            float[] values = new float[w * h];
            for (int y = BORDER; y < h - BORDER; y++) {
                for (int x = BORDER; x < w - BORDER; x++) {
                    double rx = (x + 0.5 - w / 2.0) / f;
                    double ry = (y + 0.5 - h / 2.0) / f;
                    double length = Math.sqrt(rx * rx + ry * ry + 1);
                    rx /= length;
                    ry /= length;
                    double rz = 1 / length;
                    double bx = rotation[0] * rx + rotation[1] * ry + rotation[2] * rz;
                    double by = rotation[3] * rx + rotation[4] * ry + rotation[5] * rz;
                    double bz = rotation[6] * rx + rotation[7] * ry + rotation[8] * rz;
                    if (bz < 0.2) {
                        continue;
                    }
                    double u = f * bx / bz + bw / 2.0;
                    double v = f * by / bz + bh / 2.0;
                    if (u < -margin || v < -margin || u > bw + margin || v > bh + margin) {
                        continue;
                    }
                    rays[n * 3] = rx;
                    rays[n * 3 + 1] = ry;
                    rays[n * 3 + 2] = rz;
                    values[n] = image[y * w + x];
                    n++;
                }
            }
            int thinning = (n + MAX_POINTS - 1) / MAX_POINTS;
            if (thinning > 1) {
                int kept = 0;
                for (int k = 0; k < n; k += thinning) {
                    System.arraycopy(rays, k * 3, rays, kept * 3, 3);
                    values[kept] = values[k];
                    kept++;
                }
                n = kept;
            }
            this.thinning = Math.max(1, thinning);
            this.count = n;
            this.rays = rays;
            this.values = values;
            mU = new float[n];
            mV = new float[n];
            mB = new double[n * 3];
        }

        /**
         * Places every pixel in the second frame; those outside it get a NaN position.
         *
         * @return how many landed inside
         */
        private int place(@NonNull Pyramid b, int level, double f, @NonNull double[] rotation) {
            int w = b.widths[level];
            int h = b.heights[level];
            int inside = 0;
            for (int k = 0; k < count; k++) {
                double x = rotation[0] * rays[k * 3] + rotation[1] * rays[k * 3 + 1]
                        + rotation[2] * rays[k * 3 + 2];
                double y = rotation[3] * rays[k * 3] + rotation[4] * rays[k * 3 + 1]
                        + rotation[5] * rays[k * 3 + 2];
                double z = rotation[6] * rays[k * 3] + rotation[7] * rays[k * 3 + 1]
                        + rotation[8] * rays[k * 3 + 2];
                mB[k * 3] = x;
                mB[k * 3 + 1] = y;
                mB[k * 3 + 2] = z;
                if (z < 0.2) {
                    mU[k] = Float.NaN;
                    continue;
                }
                float u = (float) (f * x / z + w / 2.0 - 0.5);
                float v = (float) (f * y / z + h / 2.0 - 0.5);
                if (u < BORDER || v < BORDER || u > w - 1 - BORDER || v > h - 1 - BORDER) {
                    mU[k] = Float.NaN;
                    continue;
                }
                mU[k] = u;
                mV[k] = v;
                inside++;
            }
            overlapping = inside;
            return inside;
        }

        /**
         * Up to {@code count} of the pixels that overlap, spread over them, with where the
         * rotation takes them in the second camera.
         */
        @Nullable
        PairMatcher.Pair matches(int first, int second, @NonNull double[] rotation, int count) {
            int[] inside = new int[this.count];
            int n = 0;
            for (int k = 0; k < this.count; k++) {
                if (!Float.isNaN(mU[k])) {
                    inside[n++] = k;
                }
            }
            if (n < count) {
                return null;
            }
            double[] a = new double[count * 3];
            double[] b = new double[count * 3];
            double[] turned = new double[3];
            for (int i = 0; i < count; i++) {
                int k = inside[(int) ((i + 0.5) * n / count)];
                System.arraycopy(rays, k * 3, a, i * 3, 3);
                Rotations.apply(rotation, rays[k * 3], rays[k * 3 + 1], rays[k * 3 + 2], turned);
                System.arraycopy(turned, 0, b, i * 3, 3);
            }
            return new PairMatcher.Pair(first, second, count, a, b, rotation, count);
        }

        /** The correlation of the overlap, or -1 where too little overlaps. */
        double correlation(@NonNull Pyramid b, int level, double f, @NonNull double[] rotation) {
            if (place(b, level, f, rotation) < 16) {
                return -1;
            }
            double st = 0;
            double si = 0;
            double stt = 0;
            double sii = 0;
            double sti = 0;
            int n = 0;
            float[] image = b.levels[level];
            for (int k = 0; k < count; k++) {
                if (Float.isNaN(mU[k])) {
                    continue;
                }
                double t = values[k];
                double i = b.sample(image, level, mU[k], mV[k]);
                st += t;
                si += i;
                stt += t * t;
                sii += i * i;
                sti += t * i;
                n++;
            }
            double vt = stt - st * st / n;
            double vi = sii - si * si / n;
            if (vt <= 1e-9 || vi <= 1e-9) {
                return -1;
            }
            return (sti - st * si / n) / Math.sqrt(vt * vi);
        }

        /**
         * One step of the enhanced correlation coefficient's maximisation: a small turn of the
         * second camera about its own axes.
         *
         * @return the turn, or null if the overlap cannot say
         */
        @Nullable
        double[] step(@NonNull Pyramid b, int level, double f, @NonNull double[] rotation) {
            double[][] system = linearise(b, level, f, rotation);
            if (system == null) {
                return null;
            }
            double[] h = system[0];
            double[] gt = system[1];
            double[] gi = system[2];
            double[] norms = system[3];
            double templateNorm2 = norms[0];
            double imageNorm2 = norms[1];
            double dot = norms[2];
            double[] hgi = solve3(h, gi);
            double[] hgt = solve3(h, gt);
            if (hgi == null || hgt == null) {
                return null;
            }
            double numerator = imageNorm2 - (gi[0] * hgi[0] + gi[1] * hgi[1] + gi[2] * hgi[2]);
            double denominator = dot - (gt[0] * hgi[0] + gt[1] * hgi[1] + gt[2] * hgi[2]);
            double lambda;
            if (denominator > 1e-9) {
                lambda = numerator / denominator;
            } else {
                // Too far out for the closed form: step as plain least squares would.
                lambda = Math.sqrt(imageNorm2 / Math.max(1e-9, templateNorm2));
            }
            double[] error = {lambda * gt[0] - gi[0], lambda * gt[1] - gi[1],
                    lambda * gt[2] - gi[2]};
            return solve3(h, error);
        }

        /**
         * The zero-mean Jacobian's normal matrix, its products with the zero-mean template and
         * warped image, and their norms: {H, Gᵀt, Gᵀi, {|t|², |i|², t·i}}.
         */
        @Nullable
        private double[][] linearise(@NonNull Pyramid b, int level, double f,
                @NonNull double[] rotation) {
            int n = place(b, level, f, rotation);
            if (n < 16) {
                return null;
            }
            float[] image = b.levels[level];
            float[] gx = b.dx[level];
            float[] gy = b.dy[level];
            double[] jacobian = new double[n * 3];
            double[] t = new double[n];
            double[] warped = new double[n];
            double meanT = 0;
            double meanI = 0;
            double[] meanJ = new double[3];
            int m = 0;
            for (int k = 0; k < count; k++) {
                if (Float.isNaN(mU[k])) {
                    continue;
                }
                float u = mU[k];
                float v = mV[k];
                double ix = b.sample(gx, level, u, v);
                double iy = b.sample(gy, level, u, v);
                double x = mB[k * 3];
                double y = mB[k * 3 + 1];
                double z = mB[k * 3 + 2];
                // A turn w of the second camera moves its ray by w x b; the pixel follows.
                double du = f / z;
                double ux = du;
                double uz = -du * x / z;
                double vy = du;
                double vz = -du * y / z;
                // d(w x b)/dw rows: (0, z, -y), (-z, 0, x), (y, -x, 0).
                double j0 = ix * (uz * y) + iy * (-vy * z + vz * y);
                double j1 = ix * (ux * z - uz * x) + iy * (-vz * x);
                double j2 = ix * (-ux * y) + iy * (vy * x);
                jacobian[m * 3] = j0;
                jacobian[m * 3 + 1] = j1;
                jacobian[m * 3 + 2] = j2;
                t[m] = values[k];
                warped[m] = b.sample(image, level, u, v);
                meanT += t[m];
                meanI += warped[m];
                meanJ[0] += j0;
                meanJ[1] += j1;
                meanJ[2] += j2;
                m++;
            }
            meanT /= m;
            meanI /= m;
            meanJ[0] /= m;
            meanJ[1] /= m;
            meanJ[2] /= m;
            double[] h = new double[9];
            double[] gt = new double[3];
            double[] gi = new double[3];
            double tt = 0;
            double ii = 0;
            double ti = 0;
            for (int k = 0; k < m; k++) {
                double tk = t[k] - meanT;
                double ik = warped[k] - meanI;
                double j0 = jacobian[k * 3] - meanJ[0];
                double j1 = jacobian[k * 3 + 1] - meanJ[1];
                double j2 = jacobian[k * 3 + 2] - meanJ[2];
                h[0] += j0 * j0;
                h[1] += j0 * j1;
                h[2] += j0 * j2;
                h[4] += j1 * j1;
                h[5] += j1 * j2;
                h[8] += j2 * j2;
                gt[0] += j0 * tk;
                gt[1] += j1 * tk;
                gt[2] += j2 * tk;
                gi[0] += j0 * ik;
                gi[1] += j1 * ik;
                gi[2] += j2 * ik;
                tt += tk * tk;
                ii += ik * ik;
                ti += tk * ik;
            }
            h[3] = h[1];
            h[6] = h[2];
            h[7] = h[5];
            if (tt <= 1e-9 || ii <= 1e-9) {
                return null;
            }
            return new double[][] {h, gt, gi, {tt, ii, ti}};
        }
    }

    /**
     * Solves a symmetric 3x3 system, damped by a thousandth of its mean eigenvalue: an axis the
     * overlap cannot see, such as along a strip of planks, then stays where it started instead of
     * wandering off on noise, and an axis it can see barely notices.
     */
    @Nullable
    static double[] solve3(@NonNull double[] h, @NonNull double[] g) {
        double damping = 1e-3 * (h[0] + h[4] + h[8]) / 3;
        double[] m = h.clone();
        m[0] += damping;
        m[4] += damping;
        m[8] += damping;
        return Linear.solve(m, g, 3);
    }

    /** Halves a picture each way by averaging each two by two. */
    @NonNull
    static float[] half(@NonNull float[] in, int w, int h) {
        int nw = Math.max(1, w / 2);
        int nh = Math.max(1, h / 2);
        float[] out = new float[nw * nh];
        for (int y = 0; y < nh; y++) {
            for (int x = 0; x < nw; x++) {
                int p = 2 * y * w + 2 * x;
                int right = Math.min(2 * x + 1, w - 1) - 2 * x;
                int below = Math.min(2 * y + 1, h - 1) - 2 * y;
                out[y * nw + x] = (in[p] + in[p + right] + in[p + below * w]
                        + in[p + below * w + right]) / 4;
            }
        }
        return out;
    }

    /** Blurs with [1 4 6 4 1] / 16 each way. */
    @NonNull
    static float[] blur(@NonNull float[] in, int w, int h) {
        float[] rows = new float[w * h];
        for (int y = 0; y < h; y++) {
            int base = y * w;
            for (int x = 0; x < w; x++) {
                rows[base + x] = (in[base + Math.max(0, x - 2)]
                        + 4 * in[base + Math.max(0, x - 1)] + 6 * in[base + x]
                        + 4 * in[base + Math.min(w - 1, x + 1)]
                        + in[base + Math.min(w - 1, x + 2)]) / 16f;
            }
        }
        float[] out = new float[w * h];
        for (int y = 0; y < h; y++) {
            int r0 = Math.max(0, y - 2) * w;
            int r1 = Math.max(0, y - 1) * w;
            int r2 = y * w;
            int r3 = Math.min(h - 1, y + 1) * w;
            int r4 = Math.min(h - 1, y + 2) * w;
            for (int x = 0; x < w; x++) {
                out[r2 + x] = (rows[r0 + x] + 4 * rows[r1 + x] + 6 * rows[r2 + x]
                        + 4 * rows[r3 + x] + rows[r4 + x]) / 16f;
            }
        }
        return out;
    }
}
