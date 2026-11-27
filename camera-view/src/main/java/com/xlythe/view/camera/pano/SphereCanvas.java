package com.xlythe.view.camera.pano;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

/**
 * The whole sphere as an equirectangular picture, painted one frame at a time.
 *
 * <p>Column x of the picture looks along a yaw and row y along a pitch, both linearly: the middle
 * column is the yaw the sweep started at, the top row is straight up. A frame is painted by asking,
 * for each canvas pixel it might cover, where that pixel's direction lands in the frame, and
 * sampling there. Going that way round leaves no holes, and handles the stretch towards the poles
 * and a frame that contains one with no special case, since every canvas pixel asks for itself.</p>
 *
 * <h2>Seams</h2>
 *
 * <p>Overlapping frames disagree a little wherever they meet - the lens is not perfect, the phone
 * was not turned exactly about the lens, and the rotation is only as good as the sensor - and the
 * edge of a frame is where both its lens and its rotation are worst. So each canvas pixel keeps
 * a score saying how central it was in the frame that painted it, and a later frame only takes over
 * where it is more central. The seam between two frames ends up halfway between their centres,
 * which is as far from either edge as it can be. Across a band either side of the seam the two
 * are cross-faded rather than cut, so what is left of a disagreement is a soft blur rather than a
 * step.</p>
 *
 * <h2>Exposure</h2>
 *
 * <p>The camera keeps metering while the phone turns, so a frame facing a window comes out darker
 * than the one before it. Each frame is measured against what is already on the canvas where they
 * overlap, and scaled to match, channel by channel, before it is painted.</p>
 *
 * <h2>Memory</h2>
 *
 * <p>The canvas is kept as square tiles, allocated the first time a frame reaches them. A
 * panorama is a band round the middle and never touches most of the sphere, and only what was
 * swept costs anything.</p>
 */
@WorkerThread
final class SphereCanvas {
    /** The side of a tile. A power of two, so a pixel's tile is a shift away. */
    static final int TILE = 128;

    /** {@code log2(TILE)}. */
    private static final int TILE_SHIFT = 7;

    /** The width, in score units, of the band either side of a seam that is cross-faded. */
    static final int FEATHER = 64;

    /**
     * How far a frame's colour may be scaled to match the canvas. Further than this and the
     * difference is not exposure but something in the scene, like a light being turned on.
     */
    private static final float MAX_GAIN = 1.5f;

    /** The fewest overlapping samples worth matching exposure on. */
    private static final int MIN_GAIN_SAMPLES = 64;

    /** Matching exposure looks at one pixel in this many each way, which is plenty to average. */
    private static final int GAIN_STRIDE = 4;

    /** A channel this bright is clipped, and says nothing about exposure. */
    private static final int CLIPPED = 250;

    /** How many pixels across the whole sphere is. */
    final int width;

    /** How many rows, from straight up to straight down: half the width. */
    final int height;

    /** The yaw of the middle of the canvas, between columns width / 2 - 1 and width / 2. */
    final float yawOrigin;

    /** How many tiles across. */
    private final int mTilesX;

    /** Each tile's pixels, or null for one nothing has reached. */
    private final int[][] mTilePixels;

    /**
     * Each tile's scores, alongside {@link #mTilePixels}: how central the pixel was in the frame
     * that painted it, from 1 to 255, and 0 for a pixel nothing has painted.
     */
    private final byte[][] mTileScores;

    /** The sine of each column's yaw. */
    private final float[] mSinYaw;

    /** The cosine of each column's yaw. */
    private final float[] mCosYaw;

    /** The sine of each row's pitch. */
    private final float[] mSinPitch;

    /** The cosine of each row's pitch. */
    private final float[] mCosPitch;

    /** The region the last call to {@link #paint} could have touched: x, columns, top, bottom. */
    private final int[] mLastRegion = new int[4];

    /** How many frames have been painted. */
    private int mFramesPainted;

    SphereCanvas(int width, float yawOrigin) {
        if (width < 2 || width % 2 != 0) {
            throw new IllegalArgumentException("The width must be even, not " + width);
        }
        this.width = width;
        this.height = width / 2;
        this.yawOrigin = yawOrigin;
        mTilesX = (width + TILE - 1) / TILE;
        int tilesY = (height + TILE - 1) / TILE;
        mTilePixels = new int[mTilesX * tilesY][];
        mTileScores = new byte[mTilesX * tilesY][];
        mSinYaw = new float[width];
        mCosYaw = new float[width];
        for (int x = 0; x < width; x++) {
            double yaw = Math.toRadians(yawAt(x));
            mSinYaw[x] = (float) Math.sin(yaw);
            mCosYaw[x] = (float) Math.cos(yaw);
        }
        mSinPitch = new float[height];
        mCosPitch = new float[height];
        for (int y = 0; y < height; y++) {
            double pitch = Math.toRadians(pitchAt(y));
            mSinPitch[y] = (float) Math.sin(pitch);
            mCosPitch[y] = (float) Math.cos(pitch);
        }
    }

    /** The yaw a column's centre looks along. */
    float yawAt(int x) {
        return SphereMath.wrap(yawOrigin + (x + 0.5f - width / 2f) * 360f / width);
    }

    /** The pitch a row's centre looks along. */
    float pitchAt(int y) {
        return 90f - (y + 0.5f) * 180f / height;
    }

    /** Where a yaw falls across the canvas, continuously: 0 is the left edge of column 0. */
    float xOf(float yaw) {
        return SphereMath.wrap(yaw - yawOrigin) / 360f * width + width / 2f;
    }

    /** Where a pitch falls down the canvas, continuously: 0 is the top edge of row 0. */
    float yOf(float pitch) {
        return (90f - pitch) / 180f * height;
    }

    /** How many frames have been painted so far. */
    int getFramesPainted() {
        return mFramesPainted;
    }

    /**
     * The region the last {@link #paint} could have changed, as the first column, the number of
     * columns from there (wrapping past the right edge), the top row and the bottom row.
     */
    @NonNull
    int[] getLastRegion() {
        return mLastRegion;
    }

    /**
     * Paints a frame where it was looking.
     *
     * @param pose where the frame was looking, sized to match the image
     * @return how many canvas pixels it changed
     */
    int paint(@NonNull ArgbImage image, @NonNull CameraPose pose) {
        Footprint footprint = Footprint.of(pose);
        int top = Math.max(0, (int) Math.floor(yOf(footprint.maxPitch)));
        int bottom = Math.min(height - 1, (int) Math.ceil(yOf(footprint.minPitch)));
        int left;
        int columns;
        if (footprint.allYaws) {
            left = 0;
            columns = width;
        } else {
            float centre = xOf(footprint.centreYaw);
            float scale = width / 360f;
            left = (int) Math.floor(centre + (footprint.minYaw - footprint.centreYaw) * scale);
            int right = (int) Math.ceil(centre + (footprint.maxYaw - footprint.centreYaw) * scale);
            columns = Math.min(width, right - left + 1);
            left = Math.floorMod(left, width);
        }
        mLastRegion[0] = left;
        mLastRegion[1] = columns;
        mLastRegion[2] = top;
        mLastRegion[3] = bottom;

        // The parts of each column's direction that do not depend on the row, in camera terms.
        int[] columnX = new int[columns];
        float[] alongRight = new float[columns];
        float[] alongDown = new float[columns];
        float[] alongForward = new float[columns];
        for (int i = 0; i < columns; i++) {
            int x = (left + i) % width;
            columnX[i] = x;
            float east = mSinYaw[x];
            float north = mCosYaw[x];
            alongRight[i] = pose.right[0] * east + pose.right[1] * north;
            alongDown[i] = pose.down[0] * east + pose.down[1] * north;
            alongForward[i] = pose.forward[0] * east + pose.forward[1] * north;
        }

        float[] gains = matchExposure(image, pose, columnX, top, bottom,
                alongRight, alongDown, alongForward);
        int gainRed = (int) (gains[0] * 256);
        int gainGreen = (int) (gains[1] * 256);
        int gainBlue = (int) (gains[2] * 256);
        boolean unity = gainRed == 256 && gainGreen == 256 && gainBlue == 256;

        float halfWidth = pose.width / 2f;
        float halfHeight = pose.height / 2f;
        float maxX = image.width - 1;
        float maxY = image.height - 1;
        int painted = 0;
        for (int y = top; y <= bottom; y++) {
            float cos = mCosPitch[y];
            float sin = mSinPitch[y];
            float upRight = pose.right[2] * sin;
            float upDown = pose.down[2] * sin;
            float upForward = pose.forward[2] * sin;
            for (int i = 0; i < columns; i++) {
                float z = cos * alongForward[i] + upForward;
                if (z <= 1e-4f) {
                    continue;
                }
                float u = halfWidth + pose.focal * (cos * alongRight[i] + upRight) / z;
                float v = halfHeight + pose.focal * (cos * alongDown[i] + upDown) / z;
                float sampleX = u - 0.5f;
                float sampleY = v - 0.5f;
                if (sampleX < 0f || sampleY < 0f || sampleX > maxX || sampleY > maxY) {
                    continue;
                }
                float offX = (u - halfWidth) / halfWidth;
                float offY = (v - halfHeight) / halfHeight;
                int score = 1 + (int) (254 * (1 - offX * offX) * (1 - offY * offY));

                int x = columnX[i];
                int tile = (y >> TILE_SHIFT) * mTilesX + (x >> TILE_SHIFT);
                int index = ((y & (TILE - 1)) << TILE_SHIFT) | (x & (TILE - 1));
                byte[] scores = mTileScores[tile];
                int previous = scores == null ? 0 : scores[index] & 0xFF;
                int advantage = score - previous;
                if (previous != 0 && advantage <= -FEATHER / 2) {
                    continue;
                }
                if (scores == null) {
                    scores = mTileScores[tile] = new byte[TILE * TILE];
                    mTilePixels[tile] = new int[TILE * TILE];
                }
                int[] pixels = mTilePixels[tile];
                int colour = image.sample(sampleX, sampleY);
                if (!unity) {
                    colour = scale(colour, gainRed, gainGreen, gainBlue);
                }
                if (previous == 0 || advantage >= FEATHER / 2) {
                    pixels[index] = colour;
                    scores[index] = (byte) score;
                } else {
                    // Within the band either side of the seam: cross-fade, by how much more
                    // central this frame is than the one already there.
                    int weight = ((advantage + FEATHER / 2) << 8) / FEATHER;
                    pixels[index] = blend(pixels[index], colour, weight);
                    scores[index] = (byte) Math.max(previous, score);
                }
                painted++;
            }
        }
        mFramesPainted++;
        return painted;
    }

    /**
     * What to multiply each channel of this frame by so it matches the canvas where they overlap.
     *
     * @return red, green and blue gains, all 1 when there is too little overlap to tell
     */
    @NonNull
    private float[] matchExposure(@NonNull ArgbImage image, @NonNull CameraPose pose,
            @NonNull int[] columnX, int top, int bottom, @NonNull float[] alongRight,
            @NonNull float[] alongDown, @NonNull float[] alongForward) {
        long[] canvas = new long[3];
        long[] frame = new long[3];
        int samples = 0;
        float halfWidth = pose.width / 2f;
        float halfHeight = pose.height / 2f;
        for (int y = top; y <= bottom; y += GAIN_STRIDE) {
            float cos = mCosPitch[y];
            float sin = mSinPitch[y];
            for (int i = 0; i < columnX.length; i += GAIN_STRIDE) {
                int existing = pixelAt(columnX[i], y);
                if (existing == 0) {
                    continue;
                }
                float z = cos * alongForward[i] + pose.forward[2] * sin;
                if (z <= 1e-4f) {
                    continue;
                }
                float u = halfWidth + pose.focal * (cos * alongRight[i] + pose.right[2] * sin) / z;
                float v = halfHeight + pose.focal * (cos * alongDown[i] + pose.down[2] * sin) / z;
                if (u < 0.5f || v < 0.5f || u > image.width - 0.5f || v > image.height - 0.5f) {
                    continue;
                }
                int colour = image.sample(u - 0.5f, v - 0.5f);
                if (isClipped(existing) || isClipped(colour)) {
                    continue;
                }
                canvas[0] += ArgbImage.red(existing);
                canvas[1] += ArgbImage.green(existing);
                canvas[2] += ArgbImage.blue(existing);
                frame[0] += ArgbImage.red(colour);
                frame[1] += ArgbImage.green(colour);
                frame[2] += ArgbImage.blue(colour);
                samples++;
            }
        }
        float[] gains = {1f, 1f, 1f};
        if (samples < MIN_GAIN_SAMPLES) {
            return gains;
        }
        for (int channel = 0; channel < 3; channel++) {
            if (frame[channel] == 0 || canvas[channel] == 0) {
                continue;
            }
            float gain = (float) canvas[channel] / frame[channel];
            gains[channel] = Math.max(1f / MAX_GAIN, Math.min(MAX_GAIN, gain));
        }
        return gains;
    }

    private static boolean isClipped(int argb) {
        return ArgbImage.red(argb) >= CLIPPED || ArgbImage.green(argb) >= CLIPPED
                || ArgbImage.blue(argb) >= CLIPPED;
    }

    /** A colour with each channel multiplied by a gain in 1/256ths. */
    static int scale(int argb, int red, int green, int blue) {
        int r = Math.min(255, (ArgbImage.red(argb) * red) >> 8);
        int g = Math.min(255, (ArgbImage.green(argb) * green) >> 8);
        int b = Math.min(255, (ArgbImage.blue(argb) * blue) >> 8);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** {@code weight} 256ths of the way from one colour to another. */
    static int blend(int from, int to, int weight) {
        int keep = 256 - weight;
        int r = (ArgbImage.red(from) * keep + ArgbImage.red(to) * weight) >> 8;
        int g = (ArgbImage.green(from) * keep + ArgbImage.green(to) * weight) >> 8;
        int b = (ArgbImage.blue(from) * keep + ArgbImage.blue(to) * weight) >> 8;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** A painted pixel's colour, or 0 for one nothing has painted. */
    int pixelAt(int x, int y) {
        int tile = (y >> TILE_SHIFT) * mTilesX + (x >> TILE_SHIFT);
        byte[] scores = mTileScores[tile];
        if (scores == null) {
            return 0;
        }
        int index = ((y & (TILE - 1)) << TILE_SHIFT) | (x & (TILE - 1));
        return scores[index] == 0 ? 0 : mTilePixels[tile][index];
    }

    /**
     * Copies part of a row out, wrapping past the right edge.
     *
     * @param fill what a pixel nothing has painted is given
     */
    void readRow(int y, int left, int count, @NonNull int[] out, int offset, int fill) {
        for (int i = 0; i < count; i++) {
            int colour = pixelAt((left + i) % width, y);
            out[offset + i] = colour == 0 ? fill : colour;
        }
    }

    /**
     * Brings a small copy of the canvas up to date with what the last {@link #paint} changed.
     *
     * <p>The copy is the whole sphere at a lower resolution, with 0 where nothing has been
     * painted, and is what the on-screen guide shows the sweep filling in with. Each of its pixels
     * takes the canvas pixel nearest its centre.</p>
     */
    void updateThumbnail(@NonNull int[] thumbnail, int thumbnailWidth, int thumbnailHeight) {
        int[] region = mLastRegion;
        float across = (float) thumbnailWidth / width;
        float down = (float) thumbnailHeight / height;
        int first = (int) Math.floor(region[0] * across);
        int count = Math.min(thumbnailWidth, (int) Math.ceil(region[1] * across) + 2);
        int top = Math.max(0, (int) Math.floor(region[2] * down));
        int bottom = Math.min(thumbnailHeight - 1, (int) Math.ceil(region[3] * down));
        for (int ty = top; ty <= bottom; ty++) {
            int y = Math.min(height - 1, (int) ((ty + 0.5f) / down));
            for (int i = 0; i < count; i++) {
                int tx = (first + i) % thumbnailWidth;
                int x = Math.min(width - 1, (int) ((tx + 0.5f) / across));
                thumbnail[ty * thumbnailWidth + tx] = pixelAt(x, y);
            }
        }
    }

    /**
     * The rectangle worth saving, as left, top, width and height in canvas pixels. The left edge
     * may be anywhere, and the rectangle wraps past the right edge of the canvas when it has to.
     *
     * <p>Across, it is every yaw that was painted: the whole width once the sweep has gone all the
     * way round, and otherwise everything but the widest gap. Down, it depends on what is being
     * saved. A sphere keeps every row anything reached. A panorama is a rectangle, and keeps only
     * the rows painted all the way across, around the pitch it was swept at - the ragged top and
     * bottom of a sweep that drifted up and down are cut off rather than filled with black.</p>
     *
     * @param bandRow the row a panorama was swept along, or -1 for a sphere
     * @return the rectangle, or null if nothing has been painted
     */
    @Nullable
    int[] coveredRegion(int bandRow) {
        boolean[] paintedColumns = new boolean[width];
        int[] paintedPerRow = new int[height];
        int top = -1;
        int bottom = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (pixelAt(x, y) != 0) {
                    paintedColumns[x] = true;
                    paintedPerRow[y]++;
                }
            }
            if (paintedPerRow[y] > 0) {
                if (top < 0) {
                    top = y;
                }
                bottom = y;
            }
        }
        if (top < 0) {
            return null;
        }

        // The widest run of unpainted columns, going round, is what is left out.
        int gapStart = 0;
        int gapLength = 0;
        int runStart = -1;
        for (int i = 0; i < 2 * width; i++) {
            boolean painted = paintedColumns[i % width];
            if (!painted && runStart < 0) {
                runStart = i;
            } else if (painted && runStart >= 0) {
                int length = Math.min(width, i - runStart);
                if (length > gapLength) {
                    gapLength = length;
                    gapStart = runStart % width;
                }
                runStart = -1;
            }
        }
        int left = gapLength == 0 ? 0 : (gapStart + gapLength) % width;
        int columns = width - gapLength;

        if (bandRow >= 0) {
            int[] band = fullBand(left, columns, bandRow);
            if (band != null) {
                top = band[0];
                bottom = band[1];
            }
        }
        return new int[] {left, top, columns, bottom - top + 1};
    }

    /**
     * The run of rows painted all the way across, nearest the row a panorama was swept along.
     *
     * @return the top and bottom rows, or null when no row is complete
     */
    @Nullable
    private int[] fullBand(int left, int columns, int bandRow) {
        boolean[] full = new boolean[height];
        for (int y = 0; y < height; y++) {
            boolean complete = true;
            for (int i = 0; i < columns && complete; i++) {
                complete = pixelAt((left + i) % width, y) != 0;
            }
            full[y] = complete;
        }
        // Outwards from the swept row to the nearest complete one.
        int start = -1;
        for (int distance = 0; distance < height && start < 0; distance++) {
            if (bandRow - distance >= 0 && full[bandRow - distance]) {
                start = bandRow - distance;
            } else if (bandRow + distance < height && full[bandRow + distance]) {
                start = bandRow + distance;
            }
        }
        if (start < 0) {
            return null;
        }
        int top = start;
        int bottom = start;
        while (top > 0 && full[top - 1]) {
            top--;
        }
        while (bottom < height - 1 && full[bottom + 1]) {
            bottom++;
        }
        return new int[] {top, bottom};
    }

    /** Lets the tiles go. Nothing may be painted or read afterwards. */
    void release() {
        for (int i = 0; i < mTilePixels.length; i++) {
            mTilePixels[i] = null;
            mTileScores[i] = null;
        }
    }
}
