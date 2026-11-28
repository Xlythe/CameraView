package com.xlythe.view.camera.pano;

import androidx.annotation.NonNull;

/** A frame's pixels as opaque ARGB, in the orientation the sensor wrote them. */
final class ArgbImage {
    /** Row by row from the top left, {@link #width} to a row. */
    final int[] pixels;

    /** How many pixels across. */
    final int width;

    /** How many rows. */
    final int height;

    ArgbImage(@NonNull int[] pixels, int width, int height) {
        if (pixels.length < width * height) {
            throw new IllegalArgumentException(
                    "Need " + width * height + " pixels, have " + pixels.length);
        }
        this.pixels = pixels;
        this.width = width;
        this.height = height;
    }

    /**
     * The colour at a point between pixel centres, blended from the four around it.
     *
     * <p>Sampling the nearest pixel instead steps every edge in the frame wherever the sphere
     * stretches it, which on a finished panorama is a staircase along every straight line.</p>
     *
     * @param x from 0 at the first pixel's centre to width - 1 at the last's
     * @param y likewise down the frame
     */
    int sample(float x, float y) {
        int x0 = (int) x;
        int y0 = (int) y;
        int x1 = Math.min(x0 + 1, width - 1);
        int y1 = Math.min(y0 + 1, height - 1);
        int fx = (int) ((x - x0) * 256);
        int fy = (int) ((y - y0) * 256);
        int topLeft = pixels[y0 * width + x0];
        int topRight = pixels[y0 * width + x1];
        int bottomLeft = pixels[y1 * width + x0];
        int bottomRight = pixels[y1 * width + x1];
        int r = lerp(lerp(red(topLeft), red(topRight), fx),
                lerp(red(bottomLeft), red(bottomRight), fx), fy);
        int g = lerp(lerp(green(topLeft), green(topRight), fx),
                lerp(green(bottomLeft), green(bottomRight), fx), fy);
        int b = lerp(lerp(blue(topLeft), blue(topRight), fx),
                lerp(blue(bottomLeft), blue(bottomRight), fx), fy);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int lerp(int from, int to, int fraction) {
        return from + (((to - from) * fraction) >> 8);
    }

    static int red(int argb) {
        return (argb >> 16) & 0xFF;
    }

    static int green(int argb) {
        return (argb >> 8) & 0xFF;
    }

    static int blue(int argb) {
        return argb & 0xFF;
    }
}
