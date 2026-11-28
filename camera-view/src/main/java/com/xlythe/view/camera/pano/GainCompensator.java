package com.xlythe.view.camera.pano;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import java.util.List;

/**
 * Matches the frames' exposures: one gain per frame and colour channel, so that where two frames
 * overlap they agree on how bright it is.
 *
 * <p>The camera keeps metering while the phone turns, so a frame facing a window comes out
 * darker than the next. Following Brown and Lowe, the gains minimise, over every overlap, the
 * squared difference between the two frames' mean brightness there once each is scaled by its
 * gain, weighted by how many pixels the overlap has; a pull towards a gain of one keeps the whole
 * sweep from drifting darker or lighter together. It is a small linear system per channel.
 * Pixels clipped in either frame say nothing about exposure and are left out.</p>
 */
@WorkerThread
final class GainCompensator {
    /** How far apart two overlapping frames' brightness may plausibly be, out of 255. */
    static final double SIGMA_N = 10;

    /** How far a gain may plausibly be from one. */
    static final double SIGMA_G = 0.1;

    /** The fewest overlapping pixels worth matching on. */
    static final int MIN_OVERLAP = 32;

    /** Gains are held to this range, beyond which the difference is the scene, not exposure. */
    static final float MAX_GAIN = 2f;

    private GainCompensator() {}

    /**
     * Sets each frame's gains.
     *
     * @param warps every frame drawn onto the same small picture
     * @param pictureWidth that picture's width, for wrapping
     */
    static void compensate(@NonNull List<FrameWarp> warps, @NonNull StitchFrame[] frames,
            int pictureWidth) {
        int n = frames.length;
        // Per pair: pixel count, and each side's sum of each channel.
        int[] counts = new int[n * n];
        double[] sums = new double[n * n * 3];
        for (int a = 0; a < warps.size(); a++) {
            FrameWarp first = warps.get(a);
            for (int b = a + 1; b < warps.size(); b++) {
                FrameWarp second = warps.get(b);
                overlap(first, second, pictureWidth, counts, sums, n);
            }
        }
        for (int channel = 0; channel < 3; channel++) {
            double[] matrix = new double[n * n];
            double[] rhs = new double[n];
            for (int i = 0; i < n; i++) {
                matrix[i * n + i] += 1e-6;
                for (int j = 0; j < n; j++) {
                    int count = counts[i * n + j];
                    if (i == j || count < MIN_OVERLAP) {
                        continue;
                    }
                    double meanI = mean(sums, counts, i, j, n, channel);
                    double meanJ = mean(sums, counts, j, i, n, channel);
                    matrix[i * n + i] += count * (2 * meanI * meanI / (SIGMA_N * SIGMA_N)
                            + 1 / (SIGMA_G * SIGMA_G));
                    matrix[i * n + j] -= count * 2 * meanI * meanJ / (SIGMA_N * SIGMA_N);
                    rhs[i] += count / (SIGMA_G * SIGMA_G);
                }
                if (rhs[i] == 0) {
                    // An island: nothing to match it to, so leave it as it is.
                    matrix[i * n + i] = 1;
                    rhs[i] = 1;
                }
            }
            double[] gains = Linear.solve(matrix, rhs, n);
            for (int i = 0; i < n; i++) {
                double gain = gains == null || Double.isNaN(gains[i]) ? 1 : gains[i];
                frames[i].gains[channel] =
                        (float) Math.max(1 / MAX_GAIN, Math.min(MAX_GAIN, gain));
            }
        }
    }

    /** The mean of one channel of frame i where it overlaps frame j. */
    private static double mean(@NonNull double[] sums, @NonNull int[] counts, int i, int j,
            int n, int channel) {
        return sums[(i * n + j) * 3 + channel] / counts[i * n + j];
    }

    /** Adds up where two frames overlap and neither is clipped. */
    private static void overlap(@NonNull FrameWarp a, @NonNull FrameWarp b, int pictureWidth,
            @NonNull int[] counts, @NonNull double[] sums, int n) {
        int top = Math.max(a.top, b.top);
        int bottom = Math.min(a.top + a.height, b.top + b.height);
        if (top >= bottom) {
            return;
        }
        int ij = a.frame * n + b.frame;
        int ji = b.frame * n + a.frame;
        for (int y = top; y < bottom; y++) {
            for (int column = 0; column < a.width; column++) {
                int x = ((a.left + column) % pictureWidth + pictureWidth) % pictureWidth;
                int pa = a.pixels[(y - a.top) * a.width + column];
                if (pa == 0) {
                    continue;
                }
                int pb = b.at(x, y, pictureWidth);
                if (pb == 0 || isClipped(pa) || isClipped(pb)) {
                    continue;
                }
                counts[ij]++;
                sums[ij * 3] += ArgbImage.red(pa);
                sums[ij * 3 + 1] += ArgbImage.green(pa);
                sums[ij * 3 + 2] += ArgbImage.blue(pa);
                sums[ji * 3] += ArgbImage.red(pb);
                sums[ji * 3 + 1] += ArgbImage.green(pb);
                sums[ji * 3 + 2] += ArgbImage.blue(pb);
            }
        }
        counts[ji] = counts[ij];
    }

    private static boolean isClipped(int argb) {
        int r = ArgbImage.red(argb);
        int g = ArgbImage.green(argb);
        int b = ArgbImage.blue(argb);
        return Math.max(r, Math.max(g, b)) >= 250 || Math.min(r, Math.min(g, b)) <= 3;
    }
}
