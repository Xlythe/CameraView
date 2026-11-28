package com.xlythe.view.camera;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;

/**
 * The center-crop mapping every consumer of a camera buffer in this library shares.
 *
 * <p>A camera hands out buffers in sensor orientation at whatever aspect ratio it was asked for,
 * which is rarely the shape of the view showing them. Everything that draws or measures against
 * those buffers - the preview, a video stream being played back, and anything analysing frames -
 * needs the same answer: where does a pixel of the buffer land in the view, once the buffer has
 * been turned upright, mirrored if the lens faces the user, and scaled up until it covers the view
 * without being distorted.</p>
 *
 * <p>Keeping that in one place matters because the mapping is easy to get subtly wrong and hard to
 * notice: a preview that is rotated twice, or stretched by the difference between two aspect
 * ratios, still looks like a camera.</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class TransformMath {
    /** How far a focus or metering area reaches either side of center, in both axes. */
    private static final int METERING_RANGE = 1000;

    /** The side of a metering area at a coefficient of one: a sixth of the frame. */
    private static final int DEFAULT_METERING_SIZE = 300;

    private TransformMath() {}

    /**
     * Maps a point on a view onto the area a camera should focus or meter on.
     *
     * <p>Every backend here takes those areas in the same space - -1000 to 1000 across the
     * sensor, in the sensor's own orientation - so the point has to be turned to match the sensor
     * before it is normalized, which is what {@code relativeCameraOrientation} is for. The result
     * is an area rather than a point because a camera meters over a region, and the region is
     * clamped into range, so a tap near an edge produces a smaller area rather than one the
     * camera will reject.</p>
     *
     * @param relativeCameraOrientation degrees the camera's frame is turned relative to the view
     * @param coefficient scales the area; a metering area is conventionally larger than the focus
     *     area inside it
     * @return the area, or an empty one when the view has no size
     */
    @NonNull
    public static Rect meteringArea(float x, float y, int viewWidth, int viewHeight,
            int relativeCameraOrientation, float coefficient) {
        if (viewWidth <= 0 || viewHeight <= 0) {
            return new Rect();
        }

        int rotation = within360(relativeCameraOrientation);
        float width = viewWidth;
        float height = viewHeight;
        if (rotation == 90 || rotation == 270) {
            // Sideways relative to the view, so the sensor's axes are the view's transposed.
            float swapped = width;
            width = height;
            height = swapped;
            swapped = x;
            x = y;
            y = swapped;
        }
        if (rotation == 180 || rotation == 270) {
            x = width - x;
            y = height - y;
        }

        float centerX = -METERING_RANGE + 2 * METERING_RANGE * (x / width);
        float centerY = -METERING_RANGE + 2 * METERING_RANGE * (y / height);
        int size = (int) (DEFAULT_METERING_SIZE * coefficient);

        Rect area = new Rect();
        area.left = (int) centerX - size / 2;
        area.top = (int) centerY - size / 2;
        area.right = area.left + size;
        area.bottom = area.top + size;

        area.left = clampToMeteringRange(area.left);
        area.top = clampToMeteringRange(area.top);
        area.right = clampToMeteringRange(area.right);
        area.bottom = clampToMeteringRange(area.bottom);
        return area;
    }

    /**
     * The region of a view a focus or metering area covers: the inverse of {@link #meteringArea}.
     *
     * <p>This is what an app drawing over the preview needs, and it is a truer answer than the
     * point that was touched: an area near an edge has been clamped into the camera's range, and
     * this reports where the camera is actually looking rather than where the finger was.</p>
     *
     * @return the region in view coordinates, or an empty one when the view has no size
     */
    @NonNull
    public static RectF viewArea(@NonNull Rect meteringArea, int viewWidth, int viewHeight,
            int relativeCameraOrientation) {
        if (viewWidth <= 0 || viewHeight <= 0) {
            return new RectF();
        }

        int rotation = within360(relativeCameraOrientation);
        boolean transposed = rotation == 90 || rotation == 270;
        float width = transposed ? viewHeight : viewWidth;
        float height = transposed ? viewWidth : viewHeight;

        float left = denormalize(meteringArea.left, width);
        float right = denormalize(meteringArea.right, width);
        float top = denormalize(meteringArea.top, height);
        float bottom = denormalize(meteringArea.bottom, height);
        if (rotation == 180 || rotation == 270) {
            left = width - left;
            right = width - right;
            top = height - top;
            bottom = height - bottom;
        }

        // Undoing the transposition swaps which axis each pair belongs to, and either flip above
        // reverses a pair's order, so the corners are sorted back into a rectangle at the end.
        RectF area = transposed
                ? new RectF(top, left, bottom, right)
                : new RectF(left, top, right, bottom);
        area.sort();
        return area;
    }

    private static float denormalize(int metering, float viewSize) {
        return viewSize * (metering + METERING_RANGE) / (2 * METERING_RANGE);
    }

    private static int clampToMeteringRange(int value) {
        return Math.min(Math.max(value, -METERING_RANGE), METERING_RANGE);
    }

    /**
     * The angle of view a lens of this focal length sees across a sensor of this size.
     *
     * <p>Both arguments are in the same unit - millimetres, as a camera reports them - and which
     * dimension of the sensor is passed in decides whether the answer is the horizontal or the
     * vertical angle.</p>
     *
     * @return the angle in degrees, or 0 when either measurement is missing
     */
    public static float fieldOfViewDegrees(float sensorSizeMm, float focalLengthMm) {
        if (sensorSizeMm <= 0f || focalLengthMm <= 0f) {
            return 0f;
        }
        return (float) Math.toDegrees(2 * Math.atan(sensorSizeMm / (2 * focalLengthMm)));
    }

    /**
     * The angle of view that is left after zooming in.
     *
     * <p>Zoom is a crop, so it narrows what the camera sees. It does not narrow it in proportion
     * to the zoom ratio, though - halving the visible width halves the tangent of the half-angle,
     * not the angle - which is why anything drawn over the preview by bearing has to go through
     * this rather than dividing.</p>
     *
     * @return the angle in degrees, or 0 when either argument is missing
     */
    public static float zoomedFieldOfViewDegrees(float fieldOfViewDegrees, float zoomRatio) {
        if (fieldOfViewDegrees <= 0f || zoomRatio <= 0f) {
            return 0f;
        }
        if (zoomRatio == 1f) {
            return fieldOfViewDegrees;
        }
        double halfAngle = Math.toRadians(fieldOfViewDegrees) / 2;
        return (float) Math.toDegrees(2 * Math.atan(Math.tan(halfAngle) / zoomRatio));
    }

    /** Normalizes any rotation, including negative ones, into [0, 360). */
    public static int within360(int rotationDegrees) {
        return ((rotationDegrees % 360) + 360) % 360;
    }

    /** Swaps a size's dimensions for the quarter turns that transpose them. */
    @RequiresApi(21)
    @NonNull
    public static Size rotateSize(@NonNull Size size, int rotationDegrees) {
        int rotation = within360(rotationDegrees);
        return rotation == 90 || rotation == 270
                ? new Size(size.getHeight(), size.getWidth())
                : size;
    }

    /**
     * Maps a region of a camera buffer onto a view, upright and filling it.
     *
     * <p>The region is rotated by {@code rotationDegrees}, flipped horizontally when
     * {@code mirrored}, then scaled by the larger of the two axis ratios so the view is covered
     * and the overflow is split evenly on both sides. Pixels outside the view are the crop.</p>
     *
     * @param crop the region of the buffer to show, in buffer pixels
     * @return the mapping, or an identity matrix when the crop or the view is empty
     */
    @NonNull
    public static Matrix centerCrop(
            @NonNull RectF crop, int rotationDegrees, boolean mirrored,
            int viewWidth, int viewHeight) {
        return center(crop, rotationDegrees, mirrored, viewWidth, viewHeight, true);
    }

    /**
     * Maps a region of a buffer into a view the way {@link #centerCrop} does, except that it is
     * scaled by the smaller of the two axis ratios, so all of it shows and the view is
     * letterboxed instead.
     *
     * @return the mapping, or an identity matrix when the crop or the view is empty
     */
    @NonNull
    public static Matrix centerInside(
            @NonNull RectF crop, int rotationDegrees, boolean mirrored,
            int viewWidth, int viewHeight) {
        return center(crop, rotationDegrees, mirrored, viewWidth, viewHeight, false);
    }

    @NonNull
    private static Matrix center(@NonNull RectF crop, int rotationDegrees, boolean mirrored,
            int viewWidth, int viewHeight, boolean cover) {
        Matrix bufferToView = new Matrix();
        if (crop.isEmpty() || viewWidth <= 0 || viewHeight <= 0) {
            return bufferToView;
        }

        bufferToView.postTranslate(-crop.centerX(), -crop.centerY());
        bufferToView.postRotate(rotationDegrees);
        if (mirrored) {
            bufferToView.postScale(-1f, 1f);
        }

        // The crop is now centered on the origin, so this rectangle is the crop's size after
        // rotation: the shape that has to be made to cover the view.
        RectF oriented = new RectF(crop);
        bufferToView.mapRect(oriented);
        bufferToView.postTranslate(-oriented.left, -oriented.top);

        float scaleX = viewWidth / oriented.width();
        float scaleY = viewHeight / oriented.height();
        float scale = cover ? Math.max(scaleX, scaleY) : Math.min(scaleX, scaleY);
        bufferToView.postScale(scale, scale);
        bufferToView.postTranslate(
                (viewWidth - oriented.width() * scale) / 2f,
                (viewHeight - oriented.height() * scale) / 2f);
        return bufferToView;
    }

    /**
     * Fills a view with a camera stream drawn on a SurfaceView, the overflow split evenly.
     *
     * <p>The compositor turns and mirrors a camera's buffers on a SurfaceView itself, by the
     * transform the camera tags them with, and counter to the display's rotation; the SurfaceView
     * then stretches what it shows over its bounds. So all that is left is a scale and a
     * translation, from that stretch to the buffer's own shape, which is the buffer turned by
     * {@code turnDegrees}.</p>
     *
     * @param turnDegrees how far the buffer is turned to be shown upright: the sensor's
     *     orientation relative to the display
     * @return the transform, relative to the stretch, or an identity matrix when the buffer or
     *     the view is empty
     */
    @NonNull
    public static Matrix surfaceViewTransform(int bufferWidth, int bufferHeight, int turnDegrees,
            int viewWidth, int viewHeight) {
        int turn = within360(turnDegrees);
        boolean transposed = turn == 90 || turn == 270;
        int shownWidth = transposed ? bufferHeight : bufferWidth;
        int shownHeight = transposed ? bufferWidth : bufferHeight;
        return asTextureTransform(centerCrop(new RectF(0f, 0f, shownWidth, shownHeight), 0,
                false, viewWidth, viewHeight), shownWidth, shownHeight, viewWidth, viewHeight);
    }

    /**
     * Re-expresses a buffer-to-view mapping as a TextureView transform.
     *
     * <p>A TextureView has already stretched the whole buffer over its own bounds by the time its
     * transform is applied, so a transform is relative to that stretch rather than to the buffer.
     * Undoing the stretch first turns a mapping stated in buffer pixels into one TextureView can
     * use.</p>
     *
     * @return the transform, or an identity matrix when the buffer or the view is empty
     */
    @NonNull
    public static Matrix asTextureTransform(
            @NonNull Matrix bufferToView, int bufferWidth, int bufferHeight,
            int viewWidth, int viewHeight) {
        Matrix identity = new Matrix();
        if (bufferWidth <= 0 || bufferHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            return identity;
        }

        Matrix defaultBufferToView = new Matrix();
        defaultBufferToView.setRectToRect(
                new RectF(0f, 0f, bufferWidth, bufferHeight),
                new RectF(0f, 0f, viewWidth, viewHeight),
                Matrix.ScaleToFit.FILL);
        Matrix viewToBuffer = new Matrix();
        if (!defaultBufferToView.invert(viewToBuffer)) {
            return identity;
        }

        Matrix textureTransform = new Matrix();
        textureTransform.setConcat(bufferToView, viewToBuffer);
        return textureTransform;
    }
}
