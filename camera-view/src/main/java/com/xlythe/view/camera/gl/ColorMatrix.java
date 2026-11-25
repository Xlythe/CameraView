package com.xlythe.view.camera.gl;

import androidx.annotation.NonNull;

/**
 * The colour transforms a filter is made of, as 4x4 matrices a shader can apply.
 *
 * <p>A filter is a matrix multiply per pixel and nothing else, which is why this is the part
 * worth writing carefully: the shader that applies it is four lines and cannot really be wrong,
 * and the coefficients can be wrong in ways nobody notices until a face comes out green.</p>
 *
 * <p>Column-major, like everything else GL takes, and operating on straight RGB with alpha left
 * alone. The camera gives opaque frames, so there is no premultiplication to undo and no reason
 * for a filter to touch the fourth channel.</p>
 */
public final class ColorMatrix {
    /** A 4x4 matrix is sixteen floats, in the order GL wants them. */
    public static final int SIZE = 16;

    /**
     * How much each channel contributes to perceived brightness, from Rec. 601.
     *
     * <p>Rec. 601 rather than 709 because this is applied to camera output in the same space
     * the encoder works in, and because the difference between them is smaller than the
     * difference between either and the naive average that is the usual mistake.</p>
     */
    private static final float LUMA_RED = 0.299f;
    private static final float LUMA_GREEN = 0.587f;
    private static final float LUMA_BLUE = 0.114f;

    private ColorMatrix() {}

    /** The matrix that changes nothing. */
    @NonNull
    public static float[] identity() {
        return new float[] {
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, 1,
        };
    }

    /**
     * Pulls colour towards grey.
     *
     * <p>Each channel becomes a mix of itself and the luminance of the whole pixel, so the
     * weights are what decide whether a red jumper and a green one come out the same shade.</p>
     *
     * @param amount 1 leaves colour alone, 0 removes it, above 1 exaggerates it
     */
    @NonNull
    public static float[] saturation(float amount) {
        float inverse = 1 - amount;
        float red = inverse * LUMA_RED;
        float green = inverse * LUMA_GREEN;
        float blue = inverse * LUMA_BLUE;
        return new float[] {
                red + amount, red, red, 0,
                green, green + amount, green, 0,
                blue, blue, blue + amount, 0,
                0, 0, 0, 1,
        };
    }

    /** Black and white, which is saturation taken all the way out. */
    @NonNull
    public static float[] grayscale() {
        return saturation(0);
    }

    /**
     * Scales every channel about mid-grey.
     *
     * <p>About mid-grey rather than about black, because scaling about black darkens as it
     * flattens and the result reads as underexposed rather than as low contrast.</p>
     *
     * @param amount 1 leaves it alone, 0 flattens to grey, above 1 increases contrast
     */
    @NonNull
    public static float[] contrast(float amount) {
        float shift = (1 - amount) * 0.5f;
        return new float[] {
                amount, 0, 0, 0,
                0, amount, 0, 0,
                0, 0, amount, 0,
                shift, shift, shift, 1,
        };
    }

    /**
     * Adds to every channel.
     *
     * @param amount in the 0..1 range the channels themselves use; negative darkens
     */
    @NonNull
    public static float[] brightness(float amount) {
        return new float[] {
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                amount, amount, amount, 1,
        };
    }

    /** The warm brown of a photograph that has been in a drawer. */
    @NonNull
    public static float[] sepia() {
        return new float[] {
                0.393f, 0.349f, 0.272f, 0,
                0.769f, 0.686f, 0.534f, 0,
                0.189f, 0.168f, 0.131f, 0,
                0, 0, 0, 1,
        };
    }

    /** Swaps each channel for its opposite. */
    @NonNull
    public static float[] invert() {
        return new float[] {
                -1, 0, 0, 0,
                0, -1, 0, 0,
                0, 0, -1, 0,
                1, 1, 1, 1,
        };
    }

    /**
     * Scales the channels against each other, for a cast without a shift in brightness.
     *
     * @param red scale for red, 1 being unchanged
     */
    @NonNull
    public static float[] tint(float red, float green, float blue) {
        return new float[] {
                red, 0, 0, 0,
                0, green, 0, 0,
                0, 0, blue, 0,
                0, 0, 0, 1,
        };
    }

    /**
     * One matrix doing what the second does to the result of the first.
     *
     * <p>Order matters and is easy to get backwards: {@code concat(a, b)} applies {@code a}
     * first. Column-major multiplication reads the other way round from that, which is the
     * trap, so the arguments here are named for the order a person thinks in.</p>
     */
    @NonNull
    public static float[] concat(@NonNull float[] first, @NonNull float[] second) {
        if (first.length != SIZE || second.length != SIZE) {
            throw new IllegalArgumentException("A colour matrix is " + SIZE + " floats");
        }
        return GlMath.multiply(second, first);
    }

    /**
     * Applies a matrix to one colour, for testing and for a caller previewing a filter.
     *
     * @param rgba four channels in 0..1
     * @return four channels in 0..1, clamped the way the shader's output is
     */
    @NonNull
    public static float[] apply(@NonNull float[] matrix, @NonNull float[] rgba) {
        if (matrix.length != SIZE || rgba.length != 4) {
            throw new IllegalArgumentException("A colour is four channels and a matrix is " + SIZE);
        }
        float[] out = new float[4];
        for (int row = 0; row < 4; row++) {
            float sum = 0;
            for (int column = 0; column < 4; column++) {
                sum += matrix[column * 4 + row] * rgba[column];
            }
            out[row] = Math.max(0, Math.min(1, sum));
        }
        return out;
    }
}
