package com.xlythe.view.camera.pano;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import java.util.List;

/**
 * How much darker a lens makes the edges of its frames than their middle, found from where the
 * frames overlap, as Hugin's photometric optimisation does.
 *
 * <p>Two frames that see the same patch of a plain ceiling see it at different distances from
 * their middles, so a lens that darkens its edges shows each frame's outline on the ceiling as a
 * soft dark ring, which gains alone cannot take out: a gain brightens a frame's middle as much as
 * its edges. The falloff is modelled as the exponential of a polynomial in the square of the
 * distance from the middle, out to one at the corners, shared by every frame since every frame is
 * the same lens. Its logarithm is then linear in the two coefficients and in each frame's log gain,
 * so it is fitted by least squares over the logarithms of overlapping pixels, with a Huber weight
 * so that a person who moved, or an edge the alignment left a pixel out, does not count.</p>
 *
 * <p>Most phones correct their lens's shading before a frame is delivered, and then the fit
 * finds next to nothing and changes nothing.</p>
 */
@WorkerThread
final class Vignetting {
    /** Pixels sampled from each overlap, at most, spread evenly over it. */
    static final int SAMPLES_PER_PAIR = 600;

    /** How far a coefficient may plausibly be from none, in the logarithm of the falloff. */
    static final double SIGMA = 0.3;

    /** How far a frame's log gain may plausibly be from none. */
    static final double GAIN_SIGMA = 0.3;

    /** Beyond this difference of logarithms a sample counts linearly: about five percent. */
    static final double HUBER = 0.05;

    /** The most the corners may be made brighter by, so a poor fit cannot blow them out. */
    static final double MAX_CORRECTION = 1.6;

    /** No falloff. */
    static final double[] NONE = {0, 0};

    private Vignetting() {}

    /**
     * The falloff's logarithm at a squared distance from the middle, out to one at the corners:
     * a r² + b r⁴.
     */
    static double logFalloff(@NonNull double[] coefficients, double radiusSquared) {
        return coefficients[0] * radiusSquared
                + coefficients[1] * radiusSquared * radiusSquared;
    }

    /**
     * What a pixel's brightness is multiplied by to undo the falloff, held to
     * {@link #MAX_CORRECTION}.
     */
    static float correction(@NonNull double[] coefficients, double radiusSquared) {
        if (coefficients[0] == 0 && coefficients[1] == 0) {
            return 1f;
        }
        return (float) Math.min(MAX_CORRECTION,
                Math.exp(-logFalloff(coefficients, radiusSquared)));
    }

    /**
     * The squared distance from a frame's middle of a point in it, as a share of the corner's.
     *
     * @param x across the frame, in its own pixels, from its left edge
     */
    static double radiusSquared(@NonNull StitchFrame frame, double x, double y) {
        double dx = x - frame.width / 2.0;
        double dy = y - frame.height / 2.0;
        return (dx * dx + dy * dy)
                / (frame.width * frame.width / 4.0 + frame.height * frame.height / 4.0);
    }

    /**
     * Fits the falloff to the frames as drawn on the small picture.
     *
     * @return the two coefficients, or {@link #NONE} when the overlaps cannot say
     */
    @NonNull
    static double[] fit(@NonNull List<FrameWarp> warps, @NonNull StitchFrame[] frames,
            @NonNull Equirect grid) {
        int n = frames.length;
        // Unknowns: each frame's log gain, then the two coefficients.
        int unknowns = n + 2;
        double[] samples = collect(warps, frames, grid);
        int count = samples.length / 5;
        if (count < 100) {
            return NONE;
        }
        double[] coefficients = NONE.clone();
        double[] gains = new double[n];
        for (int iteration = 0; iteration < 4; iteration++) {
            double[] h = new double[unknowns * unknowns];
            double[] g = new double[unknowns];
            for (int k = 0; k < count; k++) {
                int a = (int) samples[k * 5];
                int b = (int) samples[k * 5 + 1];
                double ra = samples[k * 5 + 2];
                double rb = samples[k * 5 + 3];
                double measured = samples[k * 5 + 4];
                // log Ia - log Ib = (ga - gb) + a (ra² - rb²) + b (ra⁴ - rb⁴)
                double d2 = ra - rb;
                double d4 = ra * ra - rb * rb;
                double predicted = gains[a] - gains[b] + coefficients[0] * d2
                        + coefficients[1] * d4;
                double residual = measured - predicted;
                double weight = Math.abs(residual) <= HUBER ? 1 : HUBER / Math.abs(residual);
                int[] index = {a, b, n, n + 1};
                double[] jacobian = {1, -1, d2, d4};
                for (int p = 0; p < 4; p++) {
                    g[index[p]] += weight * jacobian[p] * measured;
                    for (int q = 0; q < 4; q++) {
                        h[index[p] * unknowns + index[q]] += weight * jacobian[p] * jacobian[q];
                    }
                }
            }
            // The priors: no gain and no falloff, loosely, which also settles the one freedom the
            // differences cannot, a gain shared by every frame.
            for (int i = 0; i < n; i++) {
                h[i * unknowns + i] += 1 / (GAIN_SIGMA * GAIN_SIGMA);
            }
            for (int i = n; i < unknowns; i++) {
                h[i * unknowns + i] += 1 / (SIGMA * SIGMA);
            }
            double[] solution = Linear.solve(h, g, unknowns);
            if (solution == null) {
                return NONE;
            }
            System.arraycopy(solution, 0, gains, 0, n);
            coefficients = new double[] {solution[n], solution[n + 1]};
        }
        // A fit that brightens the corners past what the correction allows is not a lens.
        double corner = Math.exp(-logFalloff(coefficients, 1));
        if (!(corner > 1 / MAX_CORRECTION && corner < MAX_CORRECTION)) {
            return NONE;
        }
        return coefficients;
    }

    /**
     * Undoes the falloff in the frames as drawn on the small picture, so that exposure is matched
     * and seams are cut on what the lens saw.
     */
    static void correct(@NonNull List<FrameWarp> warps, @NonNull StitchFrame[] frames,
            @NonNull Equirect grid, @NonNull double[] coefficients) {
        if (coefficients[0] == 0 && coefficients[1] == 0) {
            return;
        }
        double[] direction = new double[3];
        double[] point = new double[2];
        for (FrameWarp warp : warps) {
            StitchFrame frame = frames[warp.frame];
            double[] toCamera = Rotations.transpose(frame.toWorld);
            for (int row = 0; row < warp.height; row++) {
                for (int column = 0; column < warp.width; column++) {
                    int p = row * warp.width + column;
                    int argb = warp.pixels[p];
                    if (argb == 0) {
                        continue;
                    }
                    grid.direction(warp.left + column, warp.top + row, direction);
                    if (!project(frame, toCamera, direction, point)) {
                        continue;
                    }
                    float c = correction(coefficients, radiusSquared(frame, point[0], point[1]));
                    warp.pixels[p] = 0xFF000000
                            | (scale(ArgbImage.red(argb), c) << 16)
                            | (scale(ArgbImage.green(argb), c) << 8)
                            | scale(ArgbImage.blue(argb), c);
                }
            }
        }
    }

    /**
     * Samples of overlapping pixels: frame a, frame b, a's squared radius, b's, and the
     * difference of their brightness's logarithms, five doubles to a sample.
     */
    @NonNull
    private static double[] collect(@NonNull List<FrameWarp> warps,
            @NonNull StitchFrame[] frames, @NonNull Equirect grid) {
        double[] out = new double[0];
        int count = 0;
        double[] direction = new double[3];
        double[] pointA = new double[2];
        double[] pointB = new double[2];
        for (int i = 0; i < warps.size(); i++) {
            FrameWarp first = warps.get(i);
            StitchFrame a = frames[first.frame];
            double[] toA = Rotations.transpose(a.toWorld);
            for (int j = i + 1; j < warps.size(); j++) {
                FrameWarp second = warps.get(j);
                int top = Math.max(first.top, second.top);
                int bottom = Math.min(first.top + first.height, second.top + second.height);
                if (top >= bottom) {
                    continue;
                }
                StitchFrame b = frames[second.frame];
                double[] toB = Rotations.transpose(b.toWorld);
                // Every overlapping pixel's position, to spread the samples over them evenly.
                int overlap = 0;
                for (int y = top; y < bottom; y++) {
                    for (int column = 0; column < first.width; column++) {
                        if (first.pixels[(y - first.top) * first.width + column] != 0
                                && second.at(grid.wrap(first.left + column), y, grid.width)
                                != 0) {
                            overlap++;
                        }
                    }
                }
                if (overlap < GainCompensator.MIN_OVERLAP) {
                    continue;
                }
                int stride = Math.max(1, overlap / SAMPLES_PER_PAIR);
                int seen = 0;
                for (int y = top; y < bottom; y++) {
                    for (int column = 0; column < first.width; column++) {
                        int pa = first.pixels[(y - first.top) * first.width + column];
                        if (pa == 0) {
                            continue;
                        }
                        int x = grid.wrap(first.left + column);
                        int pb = second.at(x, y, grid.width);
                        if (pb == 0 || seen++ % stride != 0) {
                            continue;
                        }
                        double la = luma(pa);
                        double lb = luma(pb);
                        if (la < 8 || lb < 8 || la > 245 || lb > 245) {
                            continue;
                        }
                        grid.direction(x, y, direction);
                        if (!project(a, toA, direction, pointA)
                                || !project(b, toB, direction, pointB)) {
                            continue;
                        }
                        if (count * 5 + 5 > out.length) {
                            double[] grown = new double[Math.max(5 * 1024, out.length * 2)];
                            System.arraycopy(out, 0, grown, 0, count * 5);
                            out = grown;
                        }
                        out[count * 5] = first.frame;
                        out[count * 5 + 1] = second.frame;
                        out[count * 5 + 2] = radiusSquared(a, pointA[0], pointA[1]);
                        out[count * 5 + 3] = radiusSquared(b, pointB[0], pointB[1]);
                        out[count * 5 + 4] = Math.log(la) - Math.log(lb);
                        count++;
                    }
                }
            }
        }
        double[] trimmed = new double[count * 5];
        System.arraycopy(out, 0, trimmed, 0, count * 5);
        return trimmed;
    }

    /** Where a world direction falls in a frame, in its own pixels; false if behind it. */
    private static boolean project(@NonNull StitchFrame frame, @NonNull double[] toCamera,
            @NonNull double[] direction, @NonNull double[] out) {
        double x = toCamera[0] * direction[0] + toCamera[1] * direction[1]
                + toCamera[2] * direction[2];
        double y = toCamera[3] * direction[0] + toCamera[4] * direction[1]
                + toCamera[5] * direction[2];
        double z = toCamera[6] * direction[0] + toCamera[7] * direction[1]
                + toCamera[8] * direction[2];
        if (z <= 1e-6) {
            return false;
        }
        out[0] = frame.width / 2.0 + frame.focal * x / z;
        out[1] = frame.height / 2.0 + frame.focal * y / z;
        return true;
    }

    private static double luma(int argb) {
        return 0.299 * ArgbImage.red(argb) + 0.587 * ArgbImage.green(argb)
                + 0.114 * ArgbImage.blue(argb);
    }

    private static int scale(int channel, float factor) {
        return Math.max(0, Math.min(255, Math.round(channel * factor)));
    }
}
