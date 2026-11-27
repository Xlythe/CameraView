package com.xlythe.view.camera.viewer;

import android.graphics.Rect;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;

import java.util.Collection;

/**
 * How a picture is cut into tiles: the sample size a scale needs, the tiles a region of the
 * picture falls in, and how much memory they take.
 *
 * <p>A tile is {@link #TILE_SIZE} decoded pixels square, so at a sample size of 4 it covers 2048
 * stored pixels. Each level of detail has its own grid, and a tile is named by its sample size,
 * column and row packed into one long.</p>
 */
final class Tiles {
    /**
     * How many decoded pixels across a tile is. Large enough that filling a screen takes a dozen
     * decodes rather than a hundred, since each decode of a JPEG reads every row above the tile
     * again; small enough that tiles off the edge of the screen waste little memory.
     */
    static final int TILE_SIZE = 512;

    /** Bytes per decoded pixel, for ARGB_8888. */
    static final int BYTES_PER_PIXEL = 4;

    /** Bits a key gives the column and the row each. */
    private static final int INDEX_BITS = 29;

    /** Masks a column or row out of a key. */
    private static final long INDEX_MASK = (1L << INDEX_BITS) - 1;

    private Tiles() {}

    /** The largest power of two no greater than a value, and at least 1. */
    @AnyThread
    static int floorPowerOfTwo(double value) {
        int power = 1;
        while (power <= value / 2 && power < (1 << 30)) {
            power *= 2;
        }
        return power;
    }

    /**
     * The sample size that shows a picture sharply at a scale: the coarsest whose pixels are
     * still no larger than the screen's.
     *
     * @param scale screen pixels per stored pixel
     */
    @AnyThread
    static int sampleSize(double scale) {
        return scale <= 0 ? 1 : floorPowerOfTwo(1.0 / scale);
    }

    /** The smallest sample size that decodes a picture to no more than a number of pixels. */
    @AnyThread
    static int sampleSizeWithin(int width, int height, long maxPixels) {
        int sampleSize = 1;
        while (decodedPixels(width, height, sampleSize) > maxPixels && sampleSize < (1 << 30)) {
            sampleSize *= 2;
        }
        return sampleSize;
    }

    /** How many pixels a region decodes to at a sample size. */
    @AnyThread
    static long decodedPixels(int width, int height, int sampleSize) {
        return (long) ceilDivide(width, sampleSize) * ceilDivide(height, sampleSize);
    }

    @AnyThread
    static int ceilDivide(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    /** Packs a tile's sample size, column and row into one key. */
    @AnyThread
    static long key(int sampleSize, int column, int row) {
        return ((long) Integer.numberOfTrailingZeros(sampleSize) << (2 * INDEX_BITS))
                | ((long) column << INDEX_BITS) | row;
    }

    @AnyThread
    static int sampleSizeOf(long key) {
        return 1 << (int) (key >>> (2 * INDEX_BITS));
    }

    @AnyThread
    static int columnOf(long key) {
        return (int) ((key >>> INDEX_BITS) & INDEX_MASK);
    }

    @AnyThread
    static int rowOf(long key) {
        return (int) (key & INDEX_MASK);
    }

    /** The stored pixels a tile covers, cut short at the picture's edges. */
    @AnyThread
    @NonNull
    static Rect region(long key, int width, int height) {
        int span = TILE_SIZE * sampleSizeOf(key);
        int left = columnOf(key) * span;
        int top = rowOf(key) * span;
        return new Rect(left, top, Math.min(width, left + span), Math.min(height, top + span));
    }

    /** How many pixels a tile decodes to. */
    @AnyThread
    static long decodedPixels(long key, int width, int height) {
        Rect region = region(key, width, height);
        return decodedPixels(region.width(), region.height(), sampleSizeOf(key));
    }

    /**
     * Adds the tiles a region of the picture falls in.
     *
     * @param left the region, in stored pixels; outside the picture is ignored
     * @param keys where the tiles' keys go
     * @return how many pixels the tiles decode to, counting each new key once
     */
    @AnyThread
    static long covering(double left, double top, double right, double bottom, int sampleSize,
            int width, int height, @NonNull Collection<Long> keys) {
        int span = TILE_SIZE * sampleSize;
        int firstColumn = (int) Math.max(0, Math.floor(left / span));
        int firstRow = (int) Math.max(0, Math.floor(top / span));
        int lastColumn = (int) Math.min(ceilDivide(width, span) - 1,
                Math.ceil(right / span) - 1);
        int lastRow = (int) Math.min(ceilDivide(height, span) - 1, Math.ceil(bottom / span) - 1);
        long pixels = 0;
        for (int row = firstRow; row <= lastRow; row++) {
            for (int column = firstColumn; column <= lastColumn; column++) {
                long key = key(sampleSize, column, row);
                if (keys.add(key)) {
                    pixels += decodedPixels(key, width, height);
                }
            }
        }
        return pixels;
    }
}
