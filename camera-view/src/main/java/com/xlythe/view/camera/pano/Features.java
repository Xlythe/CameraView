package com.xlythe.view.camera.pano;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import java.util.Arrays;
import java.util.Random;

/**
 * Corners found in one frame, each with a binary descriptor of the patch around it, for matching
 * the frames of a sweep against each other.
 *
 * <p>This is ORB without its scale pyramid: FAST corners ranked by their Harris response and
 * spread over the frame on a grid, each given the orientation of its patch's intensity centroid
 * and a 256-bit BRIEF descriptor sampled along that orientation. A sweep turns on the spot, so
 * the same wall is the same size in every frame and a pyramid would only cost time; but at the
 * poles one frame is turned against the next about the lens, which is what the orientation is
 * for.</p>
 */
@WorkerThread
final class Features {
    /** How much brighter or darker than the middle the FAST ring must be, out of 255. */
    static final int FAST_THRESHOLD = 20;

    /** How many of the ring's 16 pixels must agree in a row. */
    private static final int FAST_ARC = 9;

    /** The radius of the patch the orientation and the descriptor are taken over. */
    static final int PATCH_RADIUS = 15;

    /** How far from the edge a corner must be for its turned patch to stay inside the frame. */
    static final int BORDER = 22;

    /** The grid corners are spread over, so that no part of the frame goes unmatched. */
    static final int GRID_COLUMNS = 8;

    /** See {@link #GRID_COLUMNS}. */
    static final int GRID_ROWS = 6;

    /** The most corners kept from one cell of the grid. */
    static final int PER_CELL = 12;

    /** How many angles the descriptor's pattern is precomputed for. */
    private static final int ANGLE_BINS = 30;

    /** The descriptor's length in longs. */
    static final int WORDS = 4;

    /** The ring FAST tests, as x then y offsets, clockwise from straight up. */
    private static final int[] RING_X = {0, 1, 2, 3, 3, 3, 2, 1, 0, -1, -2, -3, -3, -3, -2, -1};

    /** See {@link #RING_X}. */
    private static final int[] RING_Y = {-3, -3, -2, -1, 0, 1, 2, 3, 3, 3, 2, 1, 0, -1, -2, -3};

    /**
     * The descriptor's 256 comparisons, turned to each of {@link #ANGLE_BINS} angles: for each
     * angle, x1, y1, x2, y2 of each comparison. Drawn once, the same for every run, from a
     * Gaussian about the corner as in BRIEF.
     */
    private static final int[][] PATTERNS = buildPatterns();

    /** How many corners there are. */
    final int count;

    /** Each corner's x, in the frame's pixels. */
    final float[] x;

    /** Each corner's y. */
    final float[] y;

    /** Each corner's descriptor, {@link #WORDS} longs to a corner. */
    final long[] descriptors;

    /** The width of the frame the corners were found in. */
    final int width;

    /** Its height. */
    final int height;

    private Features(int count, float[] x, float[] y, long[] descriptors, int width,
            int height) {
        this.count = count;
        this.x = x;
        this.y = y;
        this.descriptors = descriptors;
        this.width = width;
        this.height = height;
    }

    /**
     * Finds the corners of a greyscale frame.
     *
     * @param luma one byte of brightness per pixel, row by row
     */
    @NonNull
    static Features detect(@NonNull byte[] luma, int width, int height) {
        int[] score = new int[width * height];
        int[] candidates = new int[width * height / 4 + 1];
        int found = 0;
        for (int y = BORDER; y < height - BORDER; y++) {
            for (int x = BORDER; x < width - BORDER; x++) {
                if (isCorner(luma, width, x, y) && found < candidates.length) {
                    int s = harris(luma, width, x, y);
                    if (s > 0) {
                        score[y * width + x] = s;
                        candidates[found++] = y * width + x;
                    }
                }
            }
        }

        // Keep each corner only where it beats its eight neighbours, then the strongest of each
        // cell of the grid.
        int cells = GRID_COLUMNS * GRID_ROWS;
        int[][] best = new int[cells][];
        int[] bestCount = new int[cells];
        for (int c = 0; c < cells; c++) {
            best[c] = new int[PER_CELL];
        }
        for (int i = 0; i < found; i++) {
            int p = candidates[i];
            int s = score[p];
            if (s <= score[p - 1] || s < score[p + 1] || s <= score[p - width]
                    || s < score[p + width] || s <= score[p - width - 1]
                    || s < score[p + width + 1] || s <= score[p - width + 1]
                    || s < score[p + width - 1]) {
                continue;
            }
            int cx = (p % width) * GRID_COLUMNS / width;
            int cy = (p / width) * GRID_ROWS / height;
            int cell = cy * GRID_COLUMNS + cx;
            int[] list = best[cell];
            int n = bestCount[cell];
            if (n < PER_CELL) {
                list[n] = p;
                bestCount[cell] = n + 1;
            } else {
                int weakest = 0;
                for (int k = 1; k < PER_CELL; k++) {
                    if (score[list[k]] < score[list[weakest]]) {
                        weakest = k;
                    }
                }
                if (score[list[weakest]] < s) {
                    list[weakest] = p;
                }
            }
        }

        int total = 0;
        for (int c = 0; c < cells; c++) {
            total += bestCount[c];
        }
        byte[] smooth = smooth(luma, width, height);
        float[] xs = new float[total];
        float[] ys = new float[total];
        long[] descriptors = new long[total * WORDS];
        int n = 0;
        for (int c = 0; c < cells; c++) {
            for (int k = 0; k < bestCount[c]; k++) {
                int p = best[c][k];
                int px = p % width;
                int py = p / width;
                int bin = angleBin(smooth, width, px, py);
                describe(smooth, width, px, py, bin, descriptors, n * WORDS);
                xs[n] = px + 0.5f;
                ys[n] = py + 0.5f;
                n++;
            }
        }
        return new Features(n, xs, ys, descriptors, width, height);
    }

    /** Whether FAST finds nine of the ring in a row all brighter, or all darker, than the middle. */
    private static boolean isCorner(@NonNull byte[] luma, int width, int x, int y) {
        int center = luma[y * width + x] & 0xFF;
        int high = center + FAST_THRESHOLD;
        int low = center - FAST_THRESHOLD;
        // A quick reject: of the four compass points, at least three must be on the same side.
        int top = luma[(y - 3) * width + x] & 0xFF;
        int bottom = luma[(y + 3) * width + x] & 0xFF;
        int left = luma[y * width + x - 3] & 0xFF;
        int right = luma[y * width + x + 3] & 0xFF;
        int brighter = (top > high ? 1 : 0) + (bottom > high ? 1 : 0) + (left > high ? 1 : 0)
                + (right > high ? 1 : 0);
        int darker = (top < low ? 1 : 0) + (bottom < low ? 1 : 0) + (left < low ? 1 : 0)
                + (right < low ? 1 : 0);
        if (brighter < 2 && darker < 2) {
            return false;
        }
        int runBright = 0;
        int runDark = 0;
        for (int i = 0; i < 16 + FAST_ARC - 1; i++) {
            int k = i % 16;
            int value = luma[(y + RING_Y[k]) * width + x + RING_X[k]] & 0xFF;
            if (value > high) {
                runBright++;
                runDark = 0;
            } else if (value < low) {
                runDark++;
                runBright = 0;
            } else {
                runBright = 0;
                runDark = 0;
            }
            if (runBright >= FAST_ARC || runDark >= FAST_ARC) {
                return true;
            }
        }
        return false;
    }

    /** The Harris response over a 7x7 window, scaled down to stay an int. */
    private static int harris(@NonNull byte[] luma, int width, int x, int y) {
        long sxx = 0;
        long syy = 0;
        long sxy = 0;
        for (int dy = -3; dy <= 3; dy++) {
            int row = (y + dy) * width;
            for (int dx = -3; dx <= 3; dx++) {
                int p = row + x + dx;
                int gx = (luma[p + 1] & 0xFF) - (luma[p - 1] & 0xFF);
                int gy = (luma[p + width] & 0xFF) - (luma[p - width] & 0xFF);
                sxx += gx * gx;
                syy += gy * gy;
                sxy += gx * gy;
            }
        }
        double det = (double) sxx * syy - (double) sxy * sxy;
        double trace = sxx + syy;
        double response = det - 0.04 * trace * trace;
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, response / 1024));
    }

    /** The image under a 5x5 box blur, which BRIEF compares rather than single noisy pixels. */
    @NonNull
    private static byte[] smooth(@NonNull byte[] luma, int width, int height) {
        int[] rows = new int[width * height];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int sum = 0;
                for (int k = -2; k <= 2; k++) {
                    sum += luma[row + Math.max(0, Math.min(width - 1, x + k))] & 0xFF;
                }
                rows[row + x] = sum;
            }
        }
        byte[] out = new byte[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int sum = 0;
                for (int k = -2; k <= 2; k++) {
                    sum += rows[Math.max(0, Math.min(height - 1, y + k)) * width + x];
                }
                out[y * width + x] = (byte) (sum / 25);
            }
        }
        return out;
    }

    /** Which of the precomputed angles is nearest the patch's intensity centroid. */
    private static int angleBin(@NonNull byte[] image, int width, int x, int y) {
        long m10 = 0;
        long m01 = 0;
        int r2 = PATCH_RADIUS * PATCH_RADIUS;
        for (int dy = -PATCH_RADIUS; dy <= PATCH_RADIUS; dy++) {
            int row = (y + dy) * width + x;
            for (int dx = -PATCH_RADIUS; dx <= PATCH_RADIUS; dx++) {
                if (dx * dx + dy * dy > r2) {
                    continue;
                }
                int value = image[row + dx] & 0xFF;
                m10 += (long) dx * value;
                m01 += (long) dy * value;
            }
        }
        double angle = Math.atan2(m01, m10);
        int bin = (int) Math.round(angle / (2 * Math.PI) * ANGLE_BINS);
        return ((bin % ANGLE_BINS) + ANGLE_BINS) % ANGLE_BINS;
    }

    /** Writes the descriptor of the patch at (x, y), its pattern turned to an angle. */
    private static void describe(@NonNull byte[] image, int width, int x, int y, int bin,
            @NonNull long[] out, int offset) {
        int[] pattern = PATTERNS[bin];
        int center = y * width + x;
        for (int word = 0; word < WORDS; word++) {
            long bits = 0;
            for (int bit = 0; bit < 64; bit++) {
                int k = (word * 64 + bit) * 4;
                int a = image[center + pattern[k + 1] * width + pattern[k]] & 0xFF;
                int b = image[center + pattern[k + 3] * width + pattern[k + 2]] & 0xFF;
                if (a < b) {
                    bits |= 1L << bit;
                }
            }
            out[offset + word] = bits;
        }
    }

    /** How many bits two corners' descriptors differ in. */
    int distance(int i, @NonNull Features other, int j) {
        int d = 0;
        for (int w = 0; w < WORDS; w++) {
            d += Long.bitCount(descriptors[i * WORDS + w] ^ other.descriptors[j * WORDS + w]);
        }
        return d;
    }

    @NonNull
    private static int[][] buildPatterns() {
        Random random = new Random(0x0B5E55EDL);
        float[] base = new float[256 * 4];
        float sigma = (2 * PATCH_RADIUS + 1) / 5f;
        for (int i = 0; i < base.length; i++) {
            float value;
            do {
                value = (float) random.nextGaussian() * sigma;
            } while (Math.abs(value) > PATCH_RADIUS);
            base[i] = value;
        }
        int[][] patterns = new int[ANGLE_BINS][256 * 4];
        for (int bin = 0; bin < ANGLE_BINS; bin++) {
            double angle = 2 * Math.PI * bin / ANGLE_BINS;
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            for (int i = 0; i < 256 * 2; i++) {
                float px = base[i * 2];
                float py = base[i * 2 + 1];
                patterns[bin][i * 2] = Math.round(px * cos - py * sin);
                patterns[bin][i * 2 + 1] = Math.round(px * sin + py * cos);
            }
        }
        return patterns;
    }

    /** Finds corners near a point quickly, by sorting them into a coarse grid. */
    static final class Index {
        /** The side of a cell of the grid, in pixels. */
        private final int mCell;

        /** How many cells across. */
        private final int mColumns;

        /** How many cells down. */
        private final int mRows;

        /** Where each cell's corners start in {@link #mOrder}; one more entry than cells. */
        private final int[] mStarts;

        /** The corners, cell by cell. */
        private final int[] mOrder;

        /** The corners indexed. */
        private final Features mFeatures;

        Index(@NonNull Features features, int cell) {
            mFeatures = features;
            mCell = cell;
            mColumns = features.width / cell + 1;
            mRows = features.height / cell + 1;
            int[] counts = new int[mColumns * mRows + 1];
            int[] cellOf = new int[features.count];
            for (int i = 0; i < features.count; i++) {
                cellOf[i] = cellOf(features.x[i], features.y[i]);
                counts[cellOf[i] + 1]++;
            }
            for (int c = 1; c < counts.length; c++) {
                counts[c] += counts[c - 1];
            }
            mStarts = counts.clone();
            mOrder = new int[features.count];
            int[] next = Arrays.copyOf(counts, counts.length);
            for (int i = 0; i < features.count; i++) {
                mOrder[next[cellOf[i]]++] = i;
            }
        }

        private int cellOf(float x, float y) {
            int cx = Math.max(0, Math.min(mColumns - 1, (int) (x / mCell)));
            int cy = Math.max(0, Math.min(mRows - 1, (int) (y / mCell)));
            return cy * mColumns + cx;
        }

        /**
         * The corner whose descriptor is nearest a given one, among those within a radius of a
         * point, and how near the runner-up is.
         *
         * @param out the nearest corner's index, its distance, and the second nearest's distance;
         *     the index is -1 when there is no corner in range
         */
        void nearest(@NonNull Features query, int queryIndex, float x, float y, float radius,
                @NonNull int[] out) {
            out[0] = -1;
            out[1] = Integer.MAX_VALUE;
            out[2] = Integer.MAX_VALUE;
            int x0 = Math.max(0, (int) ((x - radius) / mCell));
            int x1 = Math.min(mColumns - 1, (int) ((x + radius) / mCell));
            int y0 = Math.max(0, (int) ((y - radius) / mCell));
            int y1 = Math.min(mRows - 1, (int) ((y + radius) / mCell));
            float r2 = radius * radius;
            for (int cy = y0; cy <= y1; cy++) {
                for (int cx = x0; cx <= x1; cx++) {
                    int c = cy * mColumns + cx;
                    for (int k = mStarts[c]; k < mStarts[c + 1]; k++) {
                        int j = mOrder[k];
                        float dx = mFeatures.x[j] - x;
                        float dy = mFeatures.y[j] - y;
                        if (dx * dx + dy * dy > r2) {
                            continue;
                        }
                        int d = query.distance(queryIndex, mFeatures, j);
                        if (d < out[1]) {
                            out[2] = out[1];
                            out[1] = d;
                            out[0] = j;
                        } else if (d < out[2]) {
                            out[2] = d;
                        }
                    }
                }
            }
        }
    }
}
