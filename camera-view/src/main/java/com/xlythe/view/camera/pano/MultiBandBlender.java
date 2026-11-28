package com.xlythe.view.camera.pano;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Draws the frames into the finished picture and blends them across their seams band by band
 * (Burt and Adelson, 1983; Brown and Lowe, 2007).
 *
 * <p>Each frame is split into a Laplacian pyramid, from fine detail to broad shading, and its seam
 * mask into a Gaussian pyramid. Each band is blended with the mask blurred to match: fine detail
 * over a pixel or two, so edges stay sharp and nothing is doubled, and broad shading over a wide
 * stretch, so a difference in brightness the gains left behind fades out instead of stepping.</p>
 *
 * <p>The picture is drawn in tiles, each with a margin as wide as the coarsest band reaches,
 * which gives the same result as blending the whole picture at once in a fraction of the memory.
 * Within a tile each frame is only drawn and split as far as its own seams reach, and tiles are
 * drawn on a few threads at once.</p>
 */
@WorkerThread
final class MultiBandBlender {
    /** How many bands: the coarsest blends over about 2^BANDS pixels. */
    static final int BANDS = 4;

    /** The side of a tile written out. */
    static final int TILE = 512;

    /** How far past a tile its pyramid reaches, so the tile's own pixels come out right. */
    static final int MARGIN = 3 << BANDS;

    /** A tile and its margin. A multiple of 2^BANDS, so every level halves exactly. */
    static final int BUFFER = TILE + 2 * MARGIN;

    /** The most tiles drawn at once, each holding a few megabytes of pyramid. */
    static final int MAX_THREADS = 3;

    /** Supplies each frame at full size. */
    interface Frames {
        /** The frame as kept, which may be read from storage. Called from several threads. */
        @NonNull
        ArgbImage image(int frame);
    }

    /** Takes the finished picture a tile at a time, from any thread, one tile at a time. */
    interface Sink {
        /** Pixels of the region, opaque ARGB, row by row, w to a row. */
        void write(@NonNull int[] pixels, int x, int y, int w, int h);
    }

    private MultiBandBlender() {}

    /**
     * Draws a region of the picture.
     *
     * @param labels which frame owns each pixel of the small picture, rows from labelTop on
     * @param small the small picture the labels are on
     * @param grid the finished picture's whole sphere
     * @param region the part of it drawn: left column, top row, width, height
     */
    static void blend(@NonNull StitchFrame[] frames, @NonNull Frames source,
            @NonNull int[] labels, int labelTop, int labelRows, @NonNull Equirect small,
            @NonNull Equirect grid, @NonNull int[] region, @NonNull Sink sink) {
        List<int[]> tiles = new ArrayList<>();
        for (int ty = 0; ty < region[3]; ty += TILE) {
            for (int tx = 0; tx < region[2]; tx += TILE) {
                tiles.add(new int[] {tx, ty});
            }
        }
        int threads = Math.max(1, Math.min(MAX_THREADS,
                Math.min(tiles.size(), Runtime.getRuntime().availableProcessors() - 1)));
        Labels owners = new Labels(labels, labelTop, labelRows, small,
                (float) grid.width / small.width);
        Sink locked = (pixels, x, y, w, h) -> {
            synchronized (sink) {
                sink.write(pixels, x, y, w, h);
            }
        };
        if (threads == 1) {
            Tile tile = new Tile();
            for (int[] t : tiles) {
                tile.draw(frames, source, owners, grid, region, t[0], t[1], locked);
            }
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int w = 0; w < threads; w++) {
                int worker = w;
                done.add(pool.submit(() -> {
                    Tile tile = new Tile();
                    for (int i = worker; i < tiles.size(); i += threads) {
                        int[] t = tiles.get(i);
                        tile.draw(frames, source, owners, grid, region, t[0], t[1], locked);
                    }
                }));
            }
            for (Future<?> future : done) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while blending", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException(cause);
        } finally {
            pool.shutdown();
        }
    }

    /** The seams, as which frame owns each pixel of the small picture. */
    private static final class Labels {
        /** The owners, row by row across the small picture's width. */
        final int[] labels;

        /** The small picture's first labelled row. */
        final int top;

        /** How many rows are labelled. */
        final int rows;

        /** The small picture. */
        final Equirect small;

        /** How much bigger the finished picture is. */
        final float scale;

        Labels(int[] labels, int top, int rows, Equirect small, float scale) {
            this.labels = labels;
            this.top = top;
            this.rows = rows;
            this.small = small;
            this.scale = scale;
        }

        /** 1 where a pixel of the small picture belongs to the frame, else 0. */
        float owns(int x, int y, int frame) {
            int row = Math.max(0, Math.min(rows - 1, y));
            return labels[row * small.width + small.wrap(x)] == frame ? 1f : 0f;
        }

        /** The frames that own any of the small picture under a stretch of the finished one. */
        @NonNull
        Set<Integer> under(int x0, int y0, int size) {
            Set<Integer> owners = new LinkedHashSet<>();
            int sx0 = (int) Math.floor(x0 / scale) - 1;
            int sx1 = (int) Math.ceil((x0 + size) / scale) + 1;
            int sy0 = Math.max(0, (int) Math.floor(y0 / scale) - 1 - top);
            int sy1 = Math.min(rows - 1, (int) Math.ceil((y0 + size) / scale) + 1 - top);
            for (int y = sy0; y <= sy1; y++) {
                for (int x = sx0; x <= sx1; x++) {
                    int label = labels[y * small.width + small.wrap(x)];
                    if (label >= 0) {
                        owners.add(label);
                    }
                }
            }
            return owners;
        }
    }

    /** One thread's buffers for drawing a tile. */
    private static final class Tile {
        /** Each level's side. */
        private final int[] mSizes = new int[BANDS + 1];

        /** Each level's blended colour, three planes, accumulated over frames. */
        private final float[][][] mSum = new float[BANDS + 1][3][];

        /** Each level's total mask weight. */
        private final float[][] mWeight = new float[BANDS + 1][];

        /** One frame's Gaussian then Laplacian pyramid, as big as a whole tile's. */
        private final float[][][] mImage = new float[BANDS + 1][3][];

        /** One frame's mask pyramid. */
        private final float[][] mMask = new float[BANDS + 1][];

        /** A level brought up a size, to take from the level below it. */
        private final float[] mExpanded = new float[BUFFER * BUFFER];

        /** Scratch for one pass of a separable filter. */
        private final float[] mScratch = new float[BUFFER * BUFFER];

        /** The mask over the whole tile, before it is cut down to the frame's reach. */
        private final float[] mTileMask = new float[BUFFER * BUFFER];

        /** The tile's columns' yaws' sines and cosines. */
        private final double[] mSinYaw = new double[BUFFER];

        /** See {@link #mSinYaw}. */
        private final double[] mCosYaw = new double[BUFFER];

        /** The finished tile, row by row. */
        private final int[] mOut = new int[TILE * TILE];

        Tile() {
            int size = BUFFER;
            for (int level = 0; level <= BANDS; level++) {
                mSizes[level] = size;
                int area = size * size;
                mWeight[level] = new float[area];
                mMask[level] = new float[area];
                for (int c = 0; c < 3; c++) {
                    mSum[level][c] = new float[area];
                    mImage[level][c] = new float[area];
                }
                size /= 2;
            }
        }

        void draw(@NonNull StitchFrame[] frames, @NonNull Frames source,
                @NonNull Labels labels, @NonNull Equirect grid, @NonNull int[] region, int tx,
                int ty, @NonNull Sink sink) {
            int x0 = region[0] + tx - MARGIN;
            int y0 = region[1] + ty - MARGIN;
            for (int level = 0; level <= BANDS; level++) {
                Arrays.fill(mWeight[level], 0f);
                for (int c = 0; c < 3; c++) {
                    Arrays.fill(mSum[level][c], 0f);
                }
            }
            for (int col = 0; col < BUFFER; col++) {
                double yaw = grid.yaw(x0 + col);
                mSinYaw[col] = Math.sin(yaw);
                mCosYaw[col] = Math.cos(yaw);
            }
            for (int frame : labels.under(x0, y0, BUFFER)) {
                addFrame(frames[frame], frame, source, labels, grid, x0, y0);
            }
            int w = Math.min(TILE, region[2] - tx);
            int h = Math.min(TILE, region[3] - ty);
            collapse(w, h);
            sink.write(mOut, tx, ty, w, h);
        }

        /** Warps one frame into the tile, as far as its seams reach, and adds its bands. */
        private void addFrame(@NonNull StitchFrame frame, int index, @NonNull Frames source,
                @NonNull Labels labels, @NonNull Equirect grid, int x0, int y0) {
            // The frame's share of the tile, and how far it reaches.
            float[] tileMask = mTileMask;
            int left = BUFFER;
            int right = -1;
            int top = BUFFER;
            int bottom = -1;
            float scale = labels.scale;
            for (int row = 0; row < BUFFER; row++) {
                float sy = (y0 + row + 0.5f) / scale - 0.5f - labels.top;
                int syi = (int) Math.floor(sy);
                float fy = sy - syi;
                for (int col = 0; col < BUFFER; col++) {
                    float sx = (x0 + col + 0.5f) / scale - 0.5f;
                    int sxi = (int) Math.floor(sx);
                    float fx = sx - sxi;
                    float m = (1 - fy) * ((1 - fx) * labels.owns(sxi, syi, index)
                            + fx * labels.owns(sxi + 1, syi, index))
                            + fy * ((1 - fx) * labels.owns(sxi, syi + 1, index)
                            + fx * labels.owns(sxi + 1, syi + 1, index));
                    tileMask[row * BUFFER + col] = m;
                    if (m > 0) {
                        left = Math.min(left, col);
                        right = Math.max(right, col);
                        top = Math.min(top, row);
                        bottom = Math.max(bottom, row);
                    }
                }
            }
            if (right < 0) {
                return;
            }
            // As far as the coarsest band carries the seam, on whole steps of the coarsest level.
            int step = 1 << BANDS;
            int reach = MARGIN;
            int sx0 = Math.max(0, (left - reach) / step * step);
            int sy0 = Math.max(0, (top - reach) / step * step);
            int sx1 = Math.min(BUFFER, (right + reach + step) / step * step);
            int sy1 = Math.min(BUFFER, (bottom + reach + step) / step * step);
            int width = sx1 - sx0;
            int height = sy1 - sy0;

            ArgbImage image = source.image(index);
            double[] toCamera = Rotations.transpose(frame.toWorld);
            double imageScale = (double) image.width / frame.width;
            double focal = frame.focal * imageScale;
            double cx = image.width / 2.0;
            double cy = image.height / 2.0;
            double[] vignetting = frame.vignetting;
            boolean vignetted = vignetting[0] != 0 || vignetting[1] != 0;
            double corner = cx * cx + cy * cy;
            float[] red = mImage[0][0];
            float[] green = mImage[0][1];
            float[] blue = mImage[0][2];
            float[] mask = mMask[0];
            for (int row = 0; row < height; row++) {
                double pitch = grid.pitch(y0 + sy0 + row);
                double horizontal = Math.cos(pitch);
                double up = Math.sin(pitch);
                for (int col = 0; col < width; col++) {
                    int p = row * width + col;
                    int tileCol = sx0 + col;
                    double east = horizontal * mSinYaw[tileCol];
                    double north = horizontal * mCosYaw[tileCol];
                    double x = toCamera[0] * east + toCamera[1] * north + toCamera[2] * up;
                    double y = toCamera[3] * east + toCamera[4] * north + toCamera[5] * up;
                    double z = toCamera[6] * east + toCamera[7] * north + toCamera[8] * up;
                    if (z <= 1e-6) {
                        red[p] = 0;
                        green[p] = 0;
                        blue[p] = 0;
                        mask[p] = 0;
                        continue;
                    }
                    mask[p] = tileMask[(sy0 + row) * BUFFER + tileCol];
                    float px = (float) (cx + focal * x / z - 0.5);
                    float py = (float) (cy + focal * y / z - 0.5);
                    px = Math.max(0, Math.min(image.width - 1, px));
                    py = Math.max(0, Math.min(image.height - 1, py));
                    int argb = image.sample(px, py);
                    float lens = 1f;
                    if (vignetted) {
                        double ux = focal * x / z;
                        double uy = focal * y / z;
                        lens = Vignetting.correction(vignetting, (ux * ux + uy * uy) / corner);
                    }
                    red[p] = ArgbImage.red(argb) * frame.gains[0] * lens;
                    green[p] = ArgbImage.green(argb) * frame.gains[1] * lens;
                    blue[p] = ArgbImage.blue(argb) * frame.gains[2] * lens;
                }
            }
            // Gaussian pyramids of the colour and the mask, over the frame's reach alone.
            for (int level = 1; level <= BANDS; level++) {
                int w = width >> (level - 1);
                int h = height >> (level - 1);
                for (int c = 0; c < 3; c++) {
                    down(mImage[level - 1][c], w, h, mImage[level][c]);
                }
                down(mMask[level - 1], w, h, mMask[level]);
            }
            // The colour's Laplacian pyramid, weighted into the tile's sums where it lies.
            for (int level = 0; level <= BANDS; level++) {
                int w = width >> level;
                int h = height >> level;
                int ox = sx0 >> level;
                int oy = sy0 >> level;
                int tileSide = mSizes[level];
                float[] weight = mMask[level];
                float[] tileWeight = mWeight[level];
                for (int c = 0; c < 3; c++) {
                    float[] band = mImage[level][c];
                    float[] sum = mSum[level][c];
                    if (level < BANDS) {
                        up(mImage[level + 1][c], w / 2, h / 2, mExpanded);
                    }
                    for (int row = 0; row < h; row++) {
                        int from = row * w;
                        int to = (oy + row) * tileSide + ox;
                        for (int col = 0; col < w; col++) {
                            float value = level < BANDS
                                    ? band[from + col] - mExpanded[from + col]
                                    : band[from + col];
                            sum[to + col] += value * weight[from + col];
                        }
                    }
                }
                for (int row = 0; row < h; row++) {
                    int from = row * w;
                    int to = (oy + row) * tileSide + ox;
                    for (int col = 0; col < w; col++) {
                        tileWeight[to + col] += weight[from + col];
                    }
                }
            }
        }

        /** Normalises each band by its weight, adds the bands back up, and writes the tile. */
        private void collapse(int w, int h) {
            float[][] image = new float[3][];
            for (int c = 0; c < 3; c++) {
                int side = mSizes[BANDS];
                float[] level = new float[side * side];
                for (int i = 0; i < level.length; i++) {
                    float weight = mWeight[BANDS][i];
                    level[i] = weight > 1e-6f ? mSum[BANDS][c][i] / weight : 0f;
                }
                for (int l = BANDS - 1; l >= 0; l--) {
                    int area = mSizes[l] * mSizes[l];
                    float[] expanded = new float[area];
                    up(level, mSizes[l + 1], mSizes[l + 1], expanded);
                    float[] weight = mWeight[l];
                    float[] sum = mSum[l][c];
                    for (int i = 0; i < area; i++) {
                        if (weight[i] > 1e-6f) {
                            expanded[i] += sum[i] / weight[i];
                        }
                    }
                    level = expanded;
                }
                image[c] = level;
            }
            float[] covered = mWeight[0];
            for (int row = 0; row < h; row++) {
                for (int col = 0; col < w; col++) {
                    int p = (row + MARGIN) * BUFFER + col + MARGIN;
                    if (covered[p] <= 1e-3f) {
                        mOut[row * w + col] = 0xFF000000;
                        continue;
                    }
                    int r = clamp(image[0][p]);
                    int g = clamp(image[1][p]);
                    int b = clamp(image[2][p]);
                    mOut[row * w + col] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
            }
        }

        /** Blurs with [1 4 6 4 1] / 16 each way and keeps every other pixel. */
        private void down(@NonNull float[] in, int w, int h, @NonNull float[] out) {
            int halfW = w / 2;
            int halfH = h / 2;
            float[] rows = mScratch;
            for (int y = 0; y < h; y++) {
                int base = y * w;
                for (int x = 0; x < halfW; x++) {
                    int c = 2 * x;
                    rows[y * halfW + x] = (in[base + Math.max(0, c - 2)]
                            + 4 * in[base + Math.max(0, c - 1)] + 6 * in[base + c]
                            + 4 * in[base + Math.min(w - 1, c + 1)]
                            + in[base + Math.min(w - 1, c + 2)]) / 16f;
                }
            }
            for (int y = 0; y < halfH; y++) {
                int c = 2 * y;
                int r0 = Math.max(0, c - 2) * halfW;
                int r1 = Math.max(0, c - 1) * halfW;
                int r2 = c * halfW;
                int r3 = Math.min(h - 1, c + 1) * halfW;
                int r4 = Math.min(h - 1, c + 2) * halfW;
                for (int x = 0; x < halfW; x++) {
                    out[y * halfW + x] = (rows[r0 + x] + 4 * rows[r1 + x] + 6 * rows[r2 + x]
                            + 4 * rows[r3 + x] + rows[r4 + x]) / 16f;
                }
            }
        }

        /** Doubles a level's size, interpolating with the same filter, into out. */
        private void up(@NonNull float[] in, int w, int h, @NonNull float[] out) {
            int fullW = w * 2;
            float[] rows = mScratch;
            for (int y = 0; y < h; y++) {
                int base = y * w;
                for (int x = 0; x < w; x++) {
                    float left = in[base + Math.max(0, x - 1)];
                    float here = in[base + x];
                    float right = in[base + Math.min(w - 1, x + 1)];
                    rows[y * fullW + 2 * x] = (left + 6 * here + right) / 8f;
                    rows[y * fullW + 2 * x + 1] = (here + right) / 2f;
                }
            }
            for (int y = 0; y < h; y++) {
                int above = Math.max(0, y - 1) * fullW;
                int here = y * fullW;
                int below = Math.min(h - 1, y + 1) * fullW;
                for (int x = 0; x < fullW; x++) {
                    out[2 * y * fullW + x] = (rows[above + x] + 6 * rows[here + x]
                            + rows[below + x]) / 8f;
                    out[(2 * y + 1) * fullW + x] = (rows[here + x] + rows[below + x]) / 2f;
                }
            }
        }

        private static int clamp(float value) {
            return Math.max(0, Math.min(255, Math.round(value)));
        }
    }
}
