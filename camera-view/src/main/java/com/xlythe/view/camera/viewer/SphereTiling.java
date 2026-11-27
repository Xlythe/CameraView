package com.xlythe.view.camera.viewer;

import android.graphics.Rect;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * How a photo sphere is cut into textures: shrunk, if need be, to the texture budget, and cut
 * into tiles each small enough for the GPU.
 *
 * <p>Each tile carries a one-texel border copied from its neighbours, so where two tiles meet the
 * GPU blends the same texels from either side and no seam shows. A sphere that goes all the way
 * round takes the borders of its first and last columns from the other end.</p>
 */
final class SphereTiling {
    /** The most texels the tiles hold together: 16 MP, 64 MB of GPU memory. */
    static final long TEXEL_BUDGET = 16L * 1024 * 1024;

    /** How many texels across a tile's inside is, at most, before its borders. */
    static final int TILE_TEXELS = 1024;

    /** How many texels across the low-resolution copy shown first is, at most. */
    static final int BASE_TEXELS = 1024;

    /** What to assume when the GPU does not say how large a texture it takes. */
    static final int DEFAULT_MAX_TEXTURE_SIZE = 2048;

    /** The stored picture's width. */
    final int imageWidth;

    /** The stored picture's height. */
    final int imageHeight;

    /** Texels per stored pixel across; 1 unless the picture is over the budget. */
    final double scaleX;

    /** Texels per stored pixel down. */
    final double scaleY;

    /** The picture's width in texels. */
    final int textureWidth;

    /** The picture's height in texels. */
    final int textureHeight;

    /** The sample size the low-resolution copy is decoded at. */
    final int baseSampleSize;

    /** The tiles, left to right and top to bottom. */
    final List<Tile> tiles;

    /** One texture of the picture. */
    static final class Tile {
        /** The texels it shows, inside its borders, in the whole picture's texels. */
        final Rect inside;

        /** The texels its texture holds: the inside and a border wherever a neighbour is. */
        final Rect outside;

        /** Whether its left border comes from the picture's right edge. */
        final boolean wrapsLeft;

        /** Whether its right border comes from the picture's left edge. */
        final boolean wrapsRight;

        Tile(@NonNull Rect inside, @NonNull Rect outside, boolean wrapsLeft,
                boolean wrapsRight) {
            this.inside = inside;
            this.outside = outside;
            this.wrapsLeft = wrapsLeft;
            this.wrapsRight = wrapsRight;
        }

        /** The texture's width, borders and all. */
        int textureWidth() {
            return outside.width() + (wrapsLeft ? 1 : 0) + (wrapsRight ? 1 : 0);
        }

        int textureHeight() {
            return outside.height();
        }

        /** Where the inside starts in the texture, across. */
        int insideLeft() {
            return inside.left - outside.left + (wrapsLeft ? 1 : 0);
        }

        int insideTop() {
            return inside.top - outside.top;
        }

        long texels() {
            return (long) textureWidth() * textureHeight();
        }
    }

    private SphereTiling(int imageWidth, int imageHeight, int textureWidth, int textureHeight,
            int baseSampleSize, @NonNull List<Tile> tiles) {
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        // Each axis rounds to whole texels on its own, so each keeps its own scale.
        this.scaleX = (double) textureWidth / imageWidth;
        this.scaleY = (double) textureHeight / imageHeight;
        this.textureWidth = textureWidth;
        this.textureHeight = textureHeight;
        this.baseSampleSize = baseSampleSize;
        this.tiles = tiles;
    }

    /**
     * Plans the textures for a picture.
     *
     * @param maxTextureSize the largest texture the GPU takes, or 0 if it did not say
     * @param wraps whether the picture goes all the way round
     */
    @AnyThread
    @NonNull
    static SphereTiling plan(int imageWidth, int imageHeight, int maxTextureSize, boolean wraps) {
        int maxSize = maxTextureSize > 0 ? maxTextureSize : DEFAULT_MAX_TEXTURE_SIZE;
        int tileSize = Math.max(1, Math.min(TILE_TEXELS, maxSize - 2));
        int baseSize = Math.min(BASE_TEXELS, maxSize);
        int baseSampleSize = 1;
        while (Tiles.ceilDivide(imageWidth, baseSampleSize) > baseSize
                || Tiles.ceilDivide(imageHeight, baseSampleSize) > baseSize) {
            baseSampleSize *= 2;
        }
        double scale = Math.min(1.0,
                Math.sqrt((double) TEXEL_BUDGET / ((double) imageWidth * imageHeight)));
        while (true) {
            int width = Math.max(1, (int) Math.round(imageWidth * scale));
            int height = Math.max(1, (int) Math.round(imageHeight * scale));
            List<Tile> tiles = cut(width, height, tileSize, wraps);
            long texels = 0;
            for (Tile tile : tiles) {
                texels += tile.texels();
            }
            // The borders take a little more than the picture; shrink a touch until they fit.
            if (texels <= TEXEL_BUDGET) {
                return new SphereTiling(imageWidth, imageHeight, width, height, baseSampleSize,
                        Collections.unmodifiableList(tiles));
            }
            scale *= 0.99;
        }
    }

    @NonNull
    private static List<Tile> cut(int width, int height, int tileSize, boolean wraps) {
        List<Tile> tiles = new ArrayList<>();
        int columns = Tiles.ceilDivide(width, tileSize);
        int rows = Tiles.ceilDivide(height, tileSize);
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                Rect inside = new Rect(column * tileSize, row * tileSize,
                        Math.min(width, (column + 1) * tileSize),
                        Math.min(height, (row + 1) * tileSize));
                Rect outside = new Rect(Math.max(0, inside.left - 1), Math.max(0, inside.top - 1),
                        Math.min(width, inside.right + 1), Math.min(height, inside.bottom + 1));
                tiles.add(new Tile(inside, outside, wraps && inside.left == 0,
                        wraps && inside.right == width));
            }
        }
        return tiles;
    }

    /**
     * The stored pixels a range of texels comes from, rounded out to whole pixels and cut to the
     * picture.
     */
    @AnyThread
    @NonNull
    Rect source(int left, int top, int right, int bottom) {
        Rect region = new Rect(
                (int) Math.floor(left / scaleX),
                (int) Math.floor(top / scaleY),
                (int) Math.ceil(right / scaleX),
                (int) Math.ceil(bottom / scaleY));
        if (!region.intersect(0, 0, imageWidth, imageHeight)) {
            region.setEmpty();
        }
        return region;
    }

    /** The sample size to decode at, which is never coarser than the texels need. */
    @AnyThread
    int sampleSize() {
        return Tiles.sampleSize(Math.max(scaleX, scaleY));
    }
}
