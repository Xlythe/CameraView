package com.xlythe.view.camera;

import android.graphics.Matrix;
import android.graphics.RectF;
import android.util.Size;

import androidx.annotation.NonNull;
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
    private TransformMath() {}

    /** Normalizes any rotation, including negative ones, into [0, 360). */
    public static int within360(int rotationDegrees) {
        return ((rotationDegrees % 360) + 360) % 360;
    }

    /** Swaps a size's dimensions for the quarter turns that transpose them. */
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

        float scale = Math.max(viewWidth / oriented.width(), viewHeight / oriented.height());
        bufferToView.postScale(scale, scale);
        bufferToView.postTranslate(
                (viewWidth - oriented.width() * scale) / 2f,
                (viewHeight - oriented.height() * scale) / 2f);
        return bufferToView;
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
            @NonNull Matrix bufferToView, @NonNull Size bufferSize,
            int viewWidth, int viewHeight) {
        Matrix identity = new Matrix();
        if (bufferSize.getWidth() <= 0 || bufferSize.getHeight() <= 0
                || viewWidth <= 0 || viewHeight <= 0) {
            return identity;
        }

        Matrix defaultBufferToView = new Matrix();
        defaultBufferToView.setRectToRect(
                new RectF(0f, 0f, bufferSize.getWidth(), bufferSize.getHeight()),
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
