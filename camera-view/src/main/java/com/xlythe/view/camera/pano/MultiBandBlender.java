package com.xlythe.view.camera.pano;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

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
 * which gives the same result as blending the whole picture at once in a fraction of the
 * memory.</p>
 */
@WorkerThread
final class MultiBandBlender {
    /** How many bands: the coarsest blends over about 2^BANDS pixels. */
    static final int BANDS = 5;

    /** The side of a tile written out. */
    static final int TILE = 512;

    /** How far past a tile its pyramid reaches, so the tile's own pixels come out right. */
    static final int MARGIN = 3 << BANDS;

    /** A tile and its margin. A multiple of 2^BANDS, so every level halves exactly. */
    private static final int BUFFER = TILE + 2 * MARGIN;

    /** Supplies each frame at full size. */
    interface Frames {
        /** The frame as kept, which may be read from storage. */
        @NonNull
        ArgbImage image(int frame);
    }

    /** Takes the finished picture a tile at a time. */
    interface Sink {
        /** Pixels of the region, opaque ARGB, row by row, w to a row. */
        void write(@NonNull int[] pixels, int x, int y, int w, int h);
    }

    /** Each level's width and height. */
    private final int[] mSizes = new int[BANDS + 1];

    /** Each level's blended colour, three planes, accumulated over frames. */
    private final float[][][] mSum = new float[BANDS + 1][3][];

    /** Each level's total mask weight. */
    private final float[][] mWeight = new float[BANDS + 1][];

    /** One frame's Gaussian then Laplacian pyramid. */
    private final float[][][] mImage = new float[BANDS + 1][3][];

    /** One frame's mask pyramid. */
    private final float[][] mMask = new float[BANDS + 1][];

    /** Scratch for one pass of a separable filter. */
    private final float[] mScratch = new float[BUFFER * BUFFER];

    MultiBandBlender() {
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

    /**
     * Draws a region of the picture.
     *
     * @param labels which frame owns each pixel of the small picture, rows from labelTop on
     * @param small the small picture the labels are on
     * @param grid the finished picture's whole sphere
     * @param region the part of it drawn: left column, top row, width, height
     */
    void blend(@NonNull StitchFrame[] frames, @NonNull Frames source, @NonNull int[] labels,
            int labelTop, int labelRows, @NonNull Equirect small, @NonNull Equirect grid,
            @NonNull int[] region, @NonNull Sink sink) {
        float scale = (float) grid.width / small.width;
        int[] out = new int[TILE * TILE];
        for (int ty = 0; ty < region[3]; ty += TILE) {
            for (int tx = 0; tx < region[2]; tx += TILE) {
                int x0 = region[0] + tx - MARGIN;
                int y0 = region[1] + ty - MARGIN;
                Set<Integer> owners = owners(labels, labelTop, labelRows, small, scale, x0, y0);
                clear();
                for (int frame : owners) {
                    addFrame(frames[frame], frame, source.image(frame), labels, labelTop,
                            labelRows, small, grid, scale, x0, y0);
                }
                int w = Math.min(TILE, region[2] - tx);
                int h = Math.min(TILE, region[3] - ty);
                collapse(out, w, h);
                sink.write(out, tx, ty, w, h);
            }
        }
    }

    /** The frames that own any of the small picture under a tile and its margin. */
    @NonNull
    private static Set<Integer> owners(@NonNull int[] labels, int labelTop, int labelRows,
            @NonNull Equirect small, float scale, int x0, int y0) {
        Set<Integer> owners = new LinkedHashSet<>();
        int sx0 = (int) Math.floor(x0 / scale) - 1;
        int sx1 = (int) Math.ceil((x0 + BUFFER) / scale) + 1;
        int sy0 = Math.max(0, (int) Math.floor(y0 / scale) - 1 - labelTop);
        int sy1 = Math.min(labelRows - 1, (int) Math.ceil((y0 + BUFFER) / scale) + 1 - labelTop);
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

    private void clear() {
        for (int level = 0; level <= BANDS; level++) {
            Arrays.fill(mWeight[level], 0f);
            for (int c = 0; c < 3; c++) {
                Arrays.fill(mSum[level][c], 0f);
            }
        }
    }

    /** Warps one frame into the tile, and adds its bands under its mask's. */
    private void addFrame(@NonNull StitchFrame frame, int index, @NonNull ArgbImage image,
            @NonNull int[] labels, int labelTop, int labelRows, @NonNull Equirect small,
            @NonNull Equirect grid, float scale, int x0, int y0) {
        float[] red = mImage[0][0];
        float[] green = mImage[0][1];
        float[] blue = mImage[0][2];
        float[] mask = mMask[0];
        double[] toCamera = Rotations.transpose(frame.toWorld);
        double imageScale = (double) image.width / frame.width;
        double focal = frame.focal * imageScale;
        double cx = image.width / 2.0;
        double cy = image.height / 2.0;
        float gainR = frame.gains[0];
        float gainG = frame.gains[1];
        float gainB = frame.gains[2];
        double[] sinYaw = new double[BUFFER];
        double[] cosYaw = new double[BUFFER];
        for (int col = 0; col < BUFFER; col++) {
            double yaw = grid.yaw(x0 + col);
            sinYaw[col] = Math.sin(yaw);
            cosYaw[col] = Math.cos(yaw);
        }
        boolean any = false;
        for (int row = 0; row < BUFFER; row++) {
            double pitch = grid.pitch(y0 + row);
            double horizontal = Math.cos(pitch);
            double up = Math.sin(pitch);
            // The mask: this frame's share of the small picture's labels, interpolated.
            float sy = (y0 + row + 0.5f) / scale - 0.5f - labelTop;
            int syi = (int) Math.floor(sy);
            float fy = sy - syi;
            for (int col = 0; col < BUFFER; col++) {
                int p = row * BUFFER + col;
                float sx = (x0 + col + 0.5f) / scale - 0.5f;
                int sxi = (int) Math.floor(sx);
                float fx = sx - sxi;
                float m = (1 - fy) * ((1 - fx) * owns(labels, labelRows, small, sxi, syi, index)
                        + fx * owns(labels, labelRows, small, sxi + 1, syi, index))
                        + fy * ((1 - fx) * owns(labels, labelRows, small, sxi, syi + 1, index)
                        + fx * owns(labels, labelRows, small, sxi + 1, syi + 1, index));
                mask[p] = m;
                if (m > 0) {
                    any = true;
                }
                double east = horizontal * sinYaw[col];
                double north = horizontal * cosYaw[col];
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
                float px = (float) (cx + focal * x / z - 0.5);
                float py = (float) (cy + focal * y / z - 0.5);
                px = Math.max(0, Math.min(image.width - 1, px));
                py = Math.max(0, Math.min(image.height - 1, py));
                int argb = image.sample(px, py);
                red[p] = ArgbImage.red(argb) * gainR;
                green[p] = ArgbImage.green(argb) * gainG;
                blue[p] = ArgbImage.blue(argb) * gainB;
            }
        }
        if (!any) {
            return;
        }
        // Gaussian pyramids of the colour and the mask.
        for (int level = 1; level <= BANDS; level++) {
            for (int c = 0; c < 3; c++) {
                down(mImage[level - 1][c], mSizes[level - 1], mImage[level][c]);
            }
            down(mMask[level - 1], mSizes[level - 1], mMask[level]);
        }
        // The colour's Laplacian pyramid, finest first, then weighted into the sums.
        float[] expanded = new float[BUFFER * BUFFER];
        for (int level = 0; level <= BANDS; level++) {
            int area = mSizes[level] * mSizes[level];
            float[] weight = mMask[level];
            for (int c = 0; c < 3; c++) {
                float[] band = mImage[level][c];
                if (level < BANDS) {
                    up(mImage[level + 1][c], mSizes[level + 1], expanded);
                    for (int i = 0; i < area; i++) {
                        mSum[level][c][i] += (band[i] - expanded[i]) * weight[i];
                    }
                } else {
                    for (int i = 0; i < area; i++) {
                        mSum[level][c][i] += band[i] * weight[i];
                    }
                }
            }
            for (int i = 0; i < area; i++) {
                mWeight[level][i] += weight[i];
            }
        }
    }

    /** 1 where the small picture's pixel belongs to the frame, else 0; nothing past its rows. */
    private static float owns(@NonNull int[] labels, int labelRows, @NonNull Equirect small,
            int x, int y, int frame) {
        int row = Math.max(0, Math.min(labelRows - 1, y));
        return labels[row * small.width + small.wrap(x)] == frame ? 1f : 0f;
    }

    /** Normalises each band by its weight, adds the bands back up, and writes the tile. */
    private void collapse(@NonNull int[] out, int w, int h) {
        float[][] image = new float[3][];
        for (int c = 0; c < 3; c++) {
            int top = mSizes[BANDS] * mSizes[BANDS];
            float[] level = new float[top];
            for (int i = 0; i < top; i++) {
                float weight = mWeight[BANDS][i];
                level[i] = weight > 1e-6f ? mSum[BANDS][c][i] / weight : 0f;
            }
            for (int l = BANDS - 1; l >= 0; l--) {
                int area = mSizes[l] * mSizes[l];
                float[] expanded = new float[area];
                up(level, mSizes[l + 1], expanded);
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
                    out[row * w + col] = 0xFF000000;
                    continue;
                }
                int r = clamp(image[0][p]);
                int g = clamp(image[1][p]);
                int b = clamp(image[2][p]);
                out[row * w + col] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
    }

    private static int clamp(float value) {
        return Math.max(0, Math.min(255, Math.round(value)));
    }

    /** Blurs with [1 4 6 4 1] / 16 each way and keeps every other pixel. */
    private void down(@NonNull float[] in, int size, @NonNull float[] out) {
        int half = size / 2;
        float[] rows = mScratch;
        for (int y = 0; y < size; y++) {
            int base = y * size;
            for (int x = 0; x < half; x++) {
                int c = 2 * x;
                rows[y * half + x] = (in[base + Math.max(0, c - 2)]
                        + 4 * in[base + Math.max(0, c - 1)] + 6 * in[base + c]
                        + 4 * in[base + Math.min(size - 1, c + 1)]
                        + in[base + Math.min(size - 1, c + 2)]) / 16f;
            }
        }
        for (int y = 0; y < half; y++) {
            int c = 2 * y;
            int r0 = Math.max(0, c - 2) * half;
            int r1 = Math.max(0, c - 1) * half;
            int r2 = c * half;
            int r3 = Math.min(size - 1, c + 1) * half;
            int r4 = Math.min(size - 1, c + 2) * half;
            for (int x = 0; x < half; x++) {
                out[y * half + x] = (rows[r0 + x] + 4 * rows[r1 + x] + 6 * rows[r2 + x]
                        + 4 * rows[r3 + x] + rows[r4 + x]) / 16f;
            }
        }
    }

    /** Doubles a level's size, interpolating with the same filter, into out. */
    private void up(@NonNull float[] in, int size, @NonNull float[] out) {
        int full = size * 2;
        float[] rows = mScratch;
        for (int y = 0; y < size; y++) {
            int base = y * size;
            for (int x = 0; x < size; x++) {
                float left = in[base + Math.max(0, x - 1)];
                float here = in[base + x];
                float right = in[base + Math.min(size - 1, x + 1)];
                rows[y * full + 2 * x] = (left + 6 * here + right) / 8f;
                rows[y * full + 2 * x + 1] = (here + right) / 2f;
            }
        }
        for (int y = 0; y < size; y++) {
            int above = Math.max(0, y - 1) * full;
            int here = y * full;
            int below = Math.min(size - 1, y + 1) * full;
            for (int x = 0; x < full; x++) {
                out[2 * y * full + x] = (rows[above + x] + 6 * rows[here + x] + rows[below + x])
                        / 8f;
                out[(2 * y + 1) * full + x] = (rows[here + x] + rows[below + x]) / 2f;
            }
        }
    }
}
