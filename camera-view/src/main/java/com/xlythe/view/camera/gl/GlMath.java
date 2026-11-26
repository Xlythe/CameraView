package com.xlythe.view.camera.gl;

import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;

/**
 * The arithmetic the renderer runs on every frame, with no GL in it.
 *
 * <p>A render thread cannot be unit tested: it needs a GPU, a context and a surface. The decisions
 * it makes can be, and they are the part that goes wrong - a frame drawn with a matrix composed in
 * the wrong order comes out mirrored, and one placed with the wrong viewport comes out stretched.
 * Neither looks like a crash, so neither shows up anywhere but on a screen. Everything here is a
 * pure function of its arguments for that reason.</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class GlMath {
    /** Floats in the 4x4 matrices GL, SurfaceTexture and SurfaceOutput all deal in. */
    public static final int MATRIX_SIZE = 16;

    /** The side of the square matrix those sixteen floats hold. */
    private static final int MATRIX_SIDE = 4;

    /** How a frame is placed in an output whose shape is not its own. */
    public enum ScaleType {
        /**
         * Scales the frame up until it covers the output, losing the overhang off two edges. What
         * a camera preview does, because a letterboxed viewfinder reads as a broken one.
         */
        FILL_CENTER,
        /** Scales the frame down until all of it is inside the output, leaving the rest unpainted. */
        FIT_CENTER
    }

    private GlMath() {}

    /** A fresh identity matrix, in the column-major order GL takes. */
    @NonNull
    public static float[] identity() {
        float[] matrix = new float[MATRIX_SIZE];
        for (int diagonal = 0; diagonal < MATRIX_SIDE; diagonal++) {
            matrix[diagonal * MATRIX_SIDE + diagonal] = 1f;
        }
        return matrix;
    }

    /**
     * {@code left * right}, both 4x4 and column-major, in the order GL applies them: the right
     * matrix acts on the coordinate first.
     *
     * <p>Written out here rather than taken from {@link android.opengl.Matrix} because that one
     * multiplies in native code, which a unit test does not have and which gives nothing back for
     * sixteen dot products.</p>
     *
     * @throws IllegalArgumentException if either matrix is not sixteen floats, which is a mistake
     *     that otherwise reads a neighbouring column and produces a frame that is merely wrong
     */
    @NonNull
    public static float[] multiply(@NonNull float[] left, @NonNull float[] right) {
        checkMatrix(left);
        checkMatrix(right);
        float[] product = new float[MATRIX_SIZE];
        for (int column = 0; column < MATRIX_SIDE; column++) {
            for (int row = 0; row < MATRIX_SIDE; row++) {
                float sum = 0f;
                for (int step = 0; step < MATRIX_SIDE; step++) {
                    sum += left[step * MATRIX_SIDE + row] * right[column * MATRIX_SIDE + step];
                }
                product[column * MATRIX_SIDE + row] = sum;
            }
        }
        return product;
    }

    /**
     * Whether a frame's timestamp is on the clock that keeps counting through sleep, rather than
     * the monotonic one an encoder lines video up with audio on.
     *
     * <p>A camera writing to an encoder moves its timestamps onto the monotonic clock itself. A
     * camera writing to a SurfaceTexture leaves them on its own, which may be the other one - and
     * a video track that starts as far after its audio as the phone has spent asleep. Which clock
     * a timestamp is on is read off the timestamp, by which of the two it is nearer, because the
     * renderer is not told which camera wrote it.</p>
     */
    public static boolean isOnRealtimeClock(long timestampNanos, long monotonicNowNanos,
            long realtimeNowNanos) {
        return Math.abs(realtimeNowNanos - timestampNanos)
                < Math.abs(monotonicNowNanos - timestampNanos);
    }

    /**
     * Where a frame is drawn inside an output, as {@code glViewport} takes it.
     *
     * <p>The frame keeps its aspect ratio either way; the two scale types differ only in which
     * scale they take. {@link ScaleType#FILL_CENTER} takes the larger and lets the frame run past
     * the edges of the output, which GL clips for free - cheaper than cropping the texture
     * coordinates, and it leaves the transform matrix the camera supplied untouched.</p>
     *
     * @param frameSize the size the frame has once its transform matrix has been applied, which is
     *     not the camera's buffer size when the matrix rotates or crops it
     * @return {@code {x, y, width, height}} in output pixels. Under FILL_CENTER x or y is negative
     *     and the viewport is larger than the output, which is the overhang.
     */
    @NonNull
    public static int[] viewport(
            @NonNull ScaleType scaleType, @NonNull Size frameSize, @NonNull Size outputSize) {
        int outputWidth = Math.max(outputSize.getWidth(), 0);
        int outputHeight = Math.max(outputSize.getHeight(), 0);
        if (frameSize.getWidth() <= 0 || frameSize.getHeight() <= 0) {
            // Nothing is known about the frame's shape yet, so the whole output is the honest
            // answer: it is what a frame of the output's own shape would get.
            return new int[] {0, 0, outputWidth, outputHeight};
        }
        float horizontal = (float) outputWidth / frameSize.getWidth();
        float vertical = (float) outputHeight / frameSize.getHeight();
        float scale = scaleType == ScaleType.FILL_CENTER
                ? Math.max(horizontal, vertical)
                : Math.min(horizontal, vertical);
        int width = Math.round(frameSize.getWidth() * scale);
        int height = Math.round(frameSize.getHeight() * scale);
        return new int[] {(outputWidth - width) / 2, (outputHeight - height) / 2, width, height};
    }

    /**
     * The clip-space quad the frame is drawn as, and the texture coordinates to read it with.
     *
     * <p>Four vertices in triangle-strip order, interleaved as x, y, s, t. The quad covers all of
     * clip space and reads the whole texture; the rotation, mirror and crop all arrive as the
     * transform matrix, which is the only place they belong - a camera's idea of which way up a
     * buffer is changes with the lens and the display, and geometry baked into a vertex array
     * does not.</p>
     */
    @NonNull
    public static float[] fullFrameQuad() {
        return new float[] {
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f,
        };
    }

    private static void checkMatrix(@NonNull float[] matrix) {
        if (matrix.length != MATRIX_SIZE) {
            throw new IllegalArgumentException(
                    "A transform matrix is " + MATRIX_SIZE + " floats, not " + matrix.length);
        }
    }
}
