package com.xlythe.view.camera.gl;

import android.util.Size;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

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

    /**
     * Floats in a camera's intrinsics as {@link #projection} takes them: the focal lengths, the
     * principal point and the skew, each divided by the width or height of the buffer.
     */
    public static final int INTRINSICS_SIZE = 5;

    /** How far a frame is turned clockwise to be shown, in whole quarter turns. */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({0, 90, 180, 270})
    public @interface Rotation {}

    /** How far apart two matrix entries may be and still count as the same. */
    private static final float TOLERANCE = 1e-4f;

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
     * The matrix that draws a camera frame the way the camera wrote it, from the one its
     * SurfaceTexture reported.
     *
     * <p>A camera asks every output for the turn and the mirror that make its frame upright on
     * the device's natural screen, and a SurfaceTexture folds that request into its matrix. A view
     * wants it. An encoder ignores it when the camera writes to it directly, so a recording or a
     * still is in the sensor's orientation and tagged with how to turn it - and a frame drawn for
     * one has to match, or the tag turns it a second time.</p>
     *
     * <p>The request is read back out of the matrix rather than worked out from the sensor
     * orientation and the lens, because the matrix is what the camera actually asked for. A
     * SurfaceTexture builds it as a vertical flip, then a crop, then the request. The crop only
     * scales each axis by a positive amount, so the signs of the matrix give the request away,
     * and multiplying by its inverse leaves the flip and the crop.</p>
     *
     * @param surfaceTextureMatrix what {@link android.graphics.SurfaceTexture#getTransformMatrix}
     *     reported
     */
    @NonNull
    public static float[] withoutCameraTransform(@NonNull float[] surfaceTextureMatrix) {
        checkMatrix(surfaceTextureMatrix);
        float[] request = signs(surfaceTextureMatrix);
        // The matrix's second row is negated by the flip in front of it.
        request[2] = -request[2];
        request[3] = -request[3];
        return multiply(surfaceTextureMatrix, unitSquareSymmetry(transpose(request)));
    }

    /**
     * Mirrors a frame left to right as it is shown, for a frame that is turned to be shown.
     *
     * <p>Applied before the matrix that reads the camera texture, so it flips the output rather
     * than the camera frame. A frame shown a quarter turn round has its columns shown as rows, so
     * mirroring what is shown flips it top to bottom.</p>
     *
     * @param rotationDegrees how far the output is turned clockwise to be shown, which for a
     *     recording is the orientation it is tagged with
     */
    @NonNull
    public static float[] displayMirror(@Rotation int rotationDegrees) {
        boolean quarterTurned = (rotationDegrees / 90) % 2 != 0;
        return unitSquareSymmetry(quarterTurned
                ? new float[] {1f, 0f, 0f, -1f}
                : new float[] {-1f, 0f, 0f, 1f});
    }

    /**
     * The matrix to draw a recording with: the frame as the camera wrote it, mirrored as it is
     * shown when it is mirrored at all.
     *
     * @see #withoutCameraTransform
     * @see #displayMirror
     */
    @NonNull
    public static float[] recordingMatrix(@NonNull float[] surfaceTextureMatrix,
            @Rotation int rotationDegrees, boolean mirrored) {
        float[] asWritten = withoutCameraTransform(surfaceTextureMatrix);
        return mirrored ? multiply(asWritten, displayMirror(rotationDegrees)) : asWritten;
    }

    /**
     * The matrix to draw a frame with so that it comes out the right way up as it is shown, with
     * nothing left for a tag to do.
     *
     * @param rotationDegrees how far the camera's buffer is turned clockwise to be shown
     * @param mirrored whether it is then shown mirrored left to right, as a front camera's is
     */
    @NonNull
    public static float[] uprightMatrix(@NonNull float[] surfaceTextureMatrix,
            @Rotation int rotationDegrees, boolean mirrored) {
        return multiply(recordingMatrix(surfaceTextureMatrix, rotationDegrees, mirrored),
                invertAffine(asShown(rotationDegrees, false)));
    }

    /**
     * Where each point of a frame lands as it is shown, for a frame turned clockwise to be shown
     * and then mirrored left to right or not. Both sides are texture coordinates, counted up from
     * the bottom as GL counts them.
     */
    @NonNull
    public static float[] asShown(@Rotation int rotationDegrees, boolean mirrored) {
        // A quarter turn clockwise, as shown, takes (s, t) to (t, 1 - s).
        float[] quarterTurn = unitSquareSymmetry(new float[] {0f, 1f, -1f, 0f});
        float[] shown = identity();
        for (int turned = 0; turned < (rotationDegrees % 360 + 360) % 360; turned += 90) {
            shown = multiply(quarterTurn, shown);
        }
        return mirrored
                ? multiply(unitSquareSymmetry(new float[] {-1f, 0f, 0f, 1f}), shown)
                : shown;
    }

    /**
     * The turn and mirror a camera asks a SurfaceTexture for, as the camera service composes it
     * from the sensor orientation and the lens: the matrix a SurfaceTexture folds in after its
     * flip and its crop, taking an upright texture coordinate to the buffer's.
     *
     * <p>For frames that reach a texture without that request in them, so that they can be
     * drawn exactly as frames that came straight from the camera are.</p>
     *
     * @param sensorOrientation how far the sensor is turned from the device's natural
     *     orientation, as the camera reports it
     * @param front whether the lens faces the same way as the screen, which the camera mirrors
     */
    @NonNull
    public static float[] cameraRequest(@Rotation int sensorOrientation, boolean front) {
        // A quarter turn as the camera service composes one: (s, t) is read from (1 - t, s).
        float[] rotate90 = unitSquareSymmetry(new float[] {0f, -1f, 1f, 0f});
        float[] flipS = unitSquareSymmetry(new float[] {-1f, 0f, 0f, 1f});
        float[] flipT = unitSquareSymmetry(new float[] {1f, 0f, 0f, -1f});
        // The flags the camera service sets, which a front lens takes with a horizontal flip
        // folded in and its quarter turns the other way round.
        boolean flipH;
        boolean flipV;
        boolean quarter;
        switch ((sensorOrientation % 360 + 360) % 360) {
            case 90:
                flipH = false;
                flipV = front;
                quarter = true;
                break;
            case 180:
                flipH = !front;
                flipV = true;
                quarter = false;
                break;
            case 270:
                flipH = true;
                flipV = !front;
                quarter = true;
                break;
            default:
                flipH = front;
                flipV = false;
                quarter = false;
                break;
        }
        float[] request = identity();
        if (flipH) {
            request = multiply(request, flipS);
        }
        if (flipV) {
            request = multiply(request, flipT);
        }
        if (quarter) {
            request = multiply(request, rotate90);
        }
        return request;
    }

    /**
     * Where each of an output's texture coordinates reads the camera's buffer, as the camera
     * wrote it: the output's own matrix with the SurfaceTexture's flip, crop and camera request
     * taken back off.
     */
    @NonNull
    public static float[] outputToBuffer(@NonNull float[] outputMatrix,
            @NonNull float[] surfaceTextureMatrix) {
        return multiply(invertAffine(withoutCameraTransform(surfaceTextureMatrix)), outputMatrix);
    }

    /**
     * Where each of an output's texture coordinates lands in the picture as it is shown.
     *
     * <p>Worked out from how the output holds the camera's buffer rather than told by whoever
     * made the output. The camera's frames are shown the same way round on every output - the
     * preview, the recording and the still all show what the person framed - so how the buffer
     * is shown, and how one output holds it, say how that output is shown. A preview a view turns
     * for the screen, a recording a player turns by its tag, and an output whose turning another
     * library decides downstream all come out of the same arithmetic.</p>
     *
     * <p>Only the turn and the mirror are kept. An output that shows part of the buffer is still
     * shown whole, so the crop is left out.</p>
     *
     * @param outputToBuffer from {@link #outputToBuffer}
     * @param rotationDegrees how far the camera's buffer is turned clockwise to be shown
     * @param mirrored whether it is then shown mirrored left to right, as a front camera's is
     */
    @NonNull
    public static float[] outputAsShown(@NonNull float[] outputToBuffer,
            @Rotation int rotationDegrees, boolean mirrored) {
        checkMatrix(outputToBuffer);
        return multiply(asShown(rotationDegrees, mirrored),
                unitSquareSymmetry(signs(outputToBuffer)));
    }

    /**
     * The size to draw an output at in the shape it is shown: the output's own pixels, arranged
     * the way the picture is shown.
     *
     * <p>Not simply the output's size turned with it, because an output can hold the picture
     * squeezed. A Camera2 preview is the camera's buffer, in the buffer's shape, holding a frame
     * already turned upright for the device, and the view stretches it back - so a renderer drawing
     * at the buffer's shape would draw a circle that the screen shows as an ellipse. Which way the
     * picture is shown, wide or tall, comes from the part of the buffer the output reads.</p>
     *
     * @param outputToBuffer from {@link #outputToBuffer}
     * @param bufferSize the size the camera writes its frames at
     * @param rotationDegrees how far the camera's buffer is turned clockwise to be shown
     */
    @RequiresApi(21)
    @NonNull
    public static Size shownSize(@NonNull float[] outputToBuffer, @NonNull Size bufferSize,
            @NonNull Size outputSize, @Rotation int rotationDegrees) {
        checkMatrix(outputToBuffer);
        float readWidth = (Math.abs(outputToBuffer[0]) + Math.abs(outputToBuffer[MATRIX_SIDE]))
                * bufferSize.getWidth();
        float readHeight = (Math.abs(outputToBuffer[1]) + Math.abs(outputToBuffer[MATRIX_SIDE + 1]))
                * bufferSize.getHeight();
        boolean shownWide = (readWidth >= readHeight) != ((rotationDegrees / 90) % 2 != 0);
        boolean outputWide = outputSize.getWidth() >= outputSize.getHeight();
        return shownWide == outputWide
                ? outputSize
                : new Size(outputSize.getHeight(), outputSize.getWidth());
    }

    /**
     * The inverse of a matrix that only moves, scales, turns and mirrors in two dimensions, which
     * is every matrix a frame is drawn with.
     *
     * @throws IllegalArgumentException if it flattens the frame, which cannot be undone
     */
    @NonNull
    public static float[] invertAffine(@NonNull float[] matrix) {
        checkMatrix(matrix);
        float a = matrix[0];
        float b = matrix[MATRIX_SIDE];
        float c = matrix[1];
        float d = matrix[MATRIX_SIDE + 1];
        float determinant = a * d - b * c;
        if (Math.abs(determinant) < TOLERANCE * TOLERANCE) {
            throw new IllegalArgumentException("A matrix that flattens the frame has no inverse");
        }
        float x = matrix[3 * MATRIX_SIDE];
        float y = matrix[3 * MATRIX_SIDE + 1];
        float[] inverse = identity();
        inverse[0] = d / determinant;
        inverse[MATRIX_SIDE] = -b / determinant;
        inverse[1] = -c / determinant;
        inverse[MATRIX_SIDE + 1] = a / determinant;
        inverse[3 * MATRIX_SIDE] = -(inverse[0] * x + inverse[MATRIX_SIDE] * y);
        inverse[3 * MATRIX_SIDE + 1] = -(inverse[1] * x + inverse[MATRIX_SIDE + 1] * y);
        return inverse;
    }

    /**
     * The projection that lines geometry in the camera's space up with a frame drawn through
     * {@code outputToBuffer}.
     *
     * <p>The camera's space is GL eye space, held the way the camera wrote its buffer: x towards
     * the buffer's right, y towards its top, looking down -z. That is the space ARCore reports
     * the camera's pose in and the one a lens's calibration describes, and it does not turn with
     * the display - the turn, the mirror and the crop of each output are all in
     * {@code outputToBuffer}, so one view matrix serves every output.</p>
     *
     * <p>The result maps onto the frame's own clip space, which is the viewport the frame is
     * drawn into rather than the whole output: under a fill the frame overhangs the output, and a
     * point at the edge of the camera's view belongs at the edge of the frame.</p>
     *
     * @param intrinsics {fx/w, fy/h, cx/w, cy/h, skew/w} of the buffer, with the principal point
     *     measured from the buffer's top left as the camera wrote it
     * @param outputToBuffer from {@link #outputToBuffer}
     * @param near the distance to the near clipping plane, in the units of the camera's space
     * @param far the distance to the far clipping plane
     * @throws IllegalArgumentException if the planes are not in front of the camera and in order
     */
    @NonNull
    public static float[] projection(@NonNull float[] intrinsics, @NonNull float[] outputToBuffer,
            float near, float far) {
        checkIntrinsics(intrinsics);
        if (!(near > 0f) || !(far > near)) {
            throw new IllegalArgumentException(
                    "The clipping planes must satisfy 0 < near < far, not " + near + ", " + far);
        }
        float[] bufferToOutput = invertAffine(outputToBuffer);
        float a = bufferToOutput[0];
        float b = bufferToOutput[MATRIX_SIDE];
        float e = bufferToOutput[3 * MATRIX_SIDE];
        float c = bufferToOutput[1];
        float d = bufferToOutput[MATRIX_SIDE + 1];
        float f = bufferToOutput[3 * MATRIX_SIDE + 1];
        // A point (x, y, z) lands on the buffer at s = px + qx * x / -z + k * y / -z and
        // t = py + qy * y / -z, counted up from the bottom as GL counts texture coordinates, and
        // the frame's clip space is twice its texture coordinates less one, times -z.
        float qx = intrinsics[0];
        float qy = intrinsics[1];
        float px = intrinsics[2];
        float py = 1f - intrinsics[3];
        float k = -intrinsics[4];
        float[] matrix = new float[MATRIX_SIZE];
        matrix[0] = 2f * a * qx;
        matrix[1] = 2f * c * qx;
        matrix[MATRIX_SIDE] = 2f * (a * k + b * qy);
        matrix[MATRIX_SIDE + 1] = 2f * (c * k + d * qy);
        matrix[2 * MATRIX_SIDE] = 1f - 2f * (a * px + b * py + e);
        matrix[2 * MATRIX_SIDE + 1] = 1f - 2f * (c * px + d * py + f);
        matrix[2 * MATRIX_SIDE + 2] = -(far + near) / (far - near);
        matrix[2 * MATRIX_SIDE + 3] = -1f;
        matrix[3 * MATRIX_SIDE + 2] = -2f * far * near / (far - near);
        return matrix;
    }

    /**
     * The direction, in the camera's space, of the ray through a point of the buffer. The
     * inverse of {@link #projection} for one point, which is what a hit test casts.
     *
     * @param s across the buffer from its left, from 0 to 1
     * @param t up the buffer from its bottom, from 0 to 1, as GL counts texture coordinates
     * @return {x, y, -1}, a direction rather than a unit vector
     */
    @NonNull
    public static float[] bufferRay(@NonNull float[] intrinsics, float s, float t) {
        checkIntrinsics(intrinsics);
        float y = (t - (1f - intrinsics[3])) / intrinsics[1];
        float x = (s - intrinsics[2] + intrinsics[4] * y) / intrinsics[0];
        return new float[] {x, y, -1f};
    }

    /** Whether a matrix leaves every coordinate where it was, to within rounding. */
    public static boolean isIdentity(@NonNull float[] matrix) {
        checkMatrix(matrix);
        float[] identity = identity();
        for (int i = 0; i < MATRIX_SIZE; i++) {
            if (Math.abs(matrix[i] - identity[i]) > TOLERANCE) {
                return false;
            }
        }
        return true;
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
    @RequiresApi(21)
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

    /**
     * A turn or flip of the unit square onto itself, from its 2x2 part as {s from s, s from t,
     * t from s, t from t}. Every one of them keeps the centre where it is, which fixes the
     * translation.
     */
    @NonNull
    private static float[] unitSquareSymmetry(@NonNull float[] linear) {
        float[] matrix = identity();
        matrix[0] = linear[0];
        matrix[MATRIX_SIDE] = linear[1];
        matrix[1] = linear[2];
        matrix[MATRIX_SIDE + 1] = linear[3];
        matrix[3 * MATRIX_SIDE] = 0.5f - 0.5f * (linear[0] + linear[1]);
        matrix[3 * MATRIX_SIDE + 1] = 0.5f - 0.5f * (linear[2] + linear[3]);
        return matrix;
    }

    /**
     * The turn or flip in a matrix that may also move and scale, as the signs of its 2x2 part:
     * {s from s, s from t, t from s, t from t}, each -1, 0 or 1. Whichever pair of entries is
     * larger decides whether the axes are swapped, so a little rounding cannot.
     */
    @NonNull
    private static float[] signs(@NonNull float[] matrix) {
        float a = matrix[0];
        float b = matrix[MATRIX_SIDE];
        float c = matrix[1];
        float d = matrix[MATRIX_SIDE + 1];
        if (Math.abs(a) + Math.abs(d) >= Math.abs(b) + Math.abs(c)) {
            return new float[] {Math.signum(a), 0f, 0f, Math.signum(d)};
        }
        return new float[] {0f, Math.signum(b), Math.signum(c), 0f};
    }

    /** The transpose of a 2x2, which for a turn or a flip is also its inverse. */
    @NonNull
    private static float[] transpose(@NonNull float[] linear) {
        return new float[] {linear[0], linear[2], linear[1], linear[3]};
    }

    private static void checkIntrinsics(@NonNull float[] intrinsics) {
        if (intrinsics.length != INTRINSICS_SIZE) {
            throw new IllegalArgumentException(
                    "Intrinsics are " + INTRINSICS_SIZE + " floats, not " + intrinsics.length);
        }
        if (!(intrinsics[0] > 0f) || !(intrinsics[1] > 0f)) {
            throw new IllegalArgumentException("A camera's focal lengths are positive");
        }
    }

    private static void checkMatrix(@NonNull float[] matrix) {
        if (matrix.length != MATRIX_SIZE) {
            throw new IllegalArgumentException(
                    "A transform matrix is " + MATRIX_SIZE + " floats, not " + matrix.length);
        }
    }
}
