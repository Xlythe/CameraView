package com.xlythe.view.camera.x;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Size;

import androidx.annotation.NonNull;

import com.xlythe.view.camera.TransformMath;

/** Pure coordinate and transform helpers shared by the CameraX backend and its tests. */
final class CameraXMath {
    /** The minimum valid CameraX metering-point size. */
    private static final float MIN_METERING_SIZE = 0.01f;

    /** The square space androidx.camera rotates rectangles in before mapping them to a buffer. */
    private static final RectF NORMALIZED_RECT = new RectF(-1f, -1f, 1f, 1f);

    private CameraXMath() {}

    /** Converts CameraView's [-1000, 1000] coordinate into a surface pixel coordinate. */
    static float toSurfaceCoordinate(int coordinate, int length) {
        if (length <= 0) {
            return 0f;
        }
        float normalized = (clamp(coordinate, -1000, 1000) + 1000f) / 2000f;
        return normalized * length;
    }

    /** Converts a CameraView metering rectangle into CameraX's fractional point size. */
    static float toMeteringSize(@NonNull Rect rectangle) {
        float size = Math.max(Math.abs(rectangle.width()), Math.abs(rectangle.height())) / 2000f;
        return clamp(size, MIN_METERING_SIZE, 1f);
    }

    /** Normalizes any rotation, including negative ones, into [0, 360). */
    static int within360(int rotationDegrees) {
        return TransformMath.within360(rotationDegrees);
    }

    /**
     * Returns the rotation CameraView still has to apply after the camera's own buffer transform.
     *
     * <p>When the camera feeds the SurfaceTexture directly it has already rotated the buffer into
     * the device's natural orientation and horizontally flipped it if mirroring was requested, so
     * only the difference between that and the target rotation is left. This is the same split
     * androidx.camera.view.PreviewTransformation#getRemainingRotationDegrees performs, and it
     * reduces to minus the display rotation for a back camera or a mirrored front camera.</p>
     *
     * @param rotationDegrees TransformationInfo#getRotationDegrees, sensor to target rotation
     * @param sensorOrientationDegrees the rotation the camera itself applied to the buffer
     * @param mirrored TransformationInfo#isMirroring
     */
    static int remainingRotationDegrees(
            int rotationDegrees, int sensorOrientationDegrees, boolean mirrored) {
        // A horizontal flip reverses the direction of any rotation applied before it, so the
        // leftover rotation is negated whenever the camera mirrored the buffer.
        return mirrored
                ? within360(sensorOrientationDegrees - rotationDegrees)
                : within360(rotationDegrees - sensorOrientationDegrees);
    }

    /** Swaps a size's dimensions for the quarter turns that transpose them. */
    @NonNull
    static Size rotateSize(@NonNull Size size, int rotationDegrees) {
        return TransformMath.rotateSize(size, rotationDegrees);
    }

    /**
     * Builds the TextureView transform which maps the CameraX crop rectangle into the view using
     * center-crop scaling, CameraX's rotation, and CameraX's explicit mirror flag.
     *
     * @param hasCameraTransform TransformationInfo#hasCameraTransform. When true the SurfaceTexture
     *     already carries the camera's rotation and mirror, so applying the full rotation here
     *     would turn the preview twice.
     * @param sensorOrientationDegrees the active lens' sensor orientation, which is the rotation
     *     the camera writes into the SurfaceTexture
     */
    @NonNull
    static Matrix createTextureTransform(
            @NonNull Size bufferSize,
            @NonNull Rect cropRectangle,
            int rotationDegrees,
            boolean mirrored,
            boolean hasCameraTransform,
            int sensorOrientationDegrees,
            int viewWidth,
            int viewHeight) {
        if (!hasCameraTransform) {
            // CameraX copied the stream through its own pipeline, so the SurfaceTexture carries no
            // camera transform and this matrix owns the whole rotation and the mirror.
            return createCenterCropTransform(bufferSize, new RectF(cropRectangle), rotationDegrees,
                    mirrored, viewWidth, viewHeight);
        }
        int applied = within360(sensorOrientationDegrees);
        Size orientedBuffer = rotateSize(bufferSize, applied);
        RectF orientedCrop = mapThroughCameraTransform(
                new RectF(cropRectangle), bufferSize, orientedBuffer, applied, mirrored);
        return createCenterCropTransform(orientedBuffer, orientedCrop,
                remainingRotationDegrees(rotationDegrees, applied, mirrored),
                false /* the camera already mirrored the buffer */, viewWidth, viewHeight);
    }

    /**
     * Builds the SurfaceView transform which maps the CameraX crop rectangle into the view using
     * center-crop scaling, for a stream the camera writes straight into the SurfaceView.
     *
     * <p>The camera tags such a stream to be turned by the sensor orientation, mirrored where
     * asked, and turned back against the display's rotation, and the compositor does all three.
     * That last turn is the rotation {@link #createTextureTransform} still has to apply to a
     * TextureView, so the buffer arrives upright in the view's own coordinates and only a scale
     * and a translation are left: a View can apply those to a SurfaceView, and could not turn
     * it.</p>
     *
     * @return the transform, relative to the stretch a SurfaceView applies to a buffer over its
     *     own bounds; an identity matrix when the crop or the view is empty
     */
    @NonNull
    static Matrix createSurfaceViewTransform(
            @NonNull Size bufferSize,
            @NonNull Rect cropRectangle,
            int rotationDegrees,
            boolean mirrored,
            int sensorOrientationDegrees,
            int viewWidth,
            int viewHeight) {
        if (cropRectangle.isEmpty()) {
            return new Matrix();
        }
        int applied = within360(sensorOrientationDegrees);
        Size orientedBuffer = rotateSize(bufferSize, applied);
        RectF orientedCrop = mapThroughCameraTransform(
                new RectF(cropRectangle), bufferSize, orientedBuffer, applied, mirrored);
        int remaining = remainingRotationDegrees(rotationDegrees, applied, mirrored);
        Size shownBuffer = rotateSize(orientedBuffer, remaining);
        RectF shownCrop = mapThroughCameraTransform(
                orientedCrop, orientedBuffer, shownBuffer, remaining, false);
        return createCenterCropTransform(
                shownBuffer, shownCrop, 0, false, viewWidth, viewHeight);
    }

    /**
     * Maps a crop rectangle from a buffer's coordinates into those of the buffer turned by
     * {@code rotationDegrees} and then flipped when {@code mirrored}: from raw buffer coordinates
     * into the ones TextureView draws, which is the turn and flip the camera applies, or onward
     * from those through the turn the compositor applies to a SurfaceView.
     */
    @NonNull
    private static RectF mapThroughCameraTransform(
            @NonNull RectF cropRectangle,
            @NonNull Size bufferSize,
            @NonNull Size orientedBufferSize,
            int rotationDegrees,
            boolean mirrored) {
        Matrix cameraTransform = new Matrix();
        cameraTransform.setRectToRect(
                new RectF(0f, 0f, bufferSize.getWidth(), bufferSize.getHeight()),
                NORMALIZED_RECT, Matrix.ScaleToFit.FILL);
        cameraTransform.postRotate(rotationDegrees);
        if (mirrored) {
            cameraTransform.postScale(-1f, 1f);
        }
        Matrix normalizedToOriented = new Matrix();
        normalizedToOriented.setRectToRect(NORMALIZED_RECT,
                new RectF(0f, 0f, orientedBufferSize.getWidth(), orientedBufferSize.getHeight()),
                Matrix.ScaleToFit.FILL);
        cameraTransform.postConcat(normalizedToOriented);

        RectF orientedCrop = new RectF(cropRectangle);
        cameraTransform.mapRect(orientedCrop);
        return orientedCrop;
    }

    /**
     * Rotates the crop rectangle, scales it to fill the view without distorting it, and expresses
     * the result relative to the stretch TextureView applies to a buffer by default.
     */
    @NonNull
    private static Matrix createCenterCropTransform(
            @NonNull Size bufferSize,
            @NonNull RectF crop,
            int rotationDegrees,
            boolean mirrored,
            int viewWidth,
            int viewHeight) {
        if (crop.isEmpty()) {
            return new Matrix();
        }
        return TransformMath.asTextureTransform(
                TransformMath.centerCrop(crop, rotationDegrees, mirrored, viewWidth, viewHeight),
                bufferSize.getWidth(), bufferSize.getHeight(), viewWidth, viewHeight);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }
}
