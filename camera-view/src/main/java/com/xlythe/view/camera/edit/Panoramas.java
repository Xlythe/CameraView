package com.xlythe.view.camera.edit;

import android.graphics.RectF;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xlythe.view.camera.MediaTags;

/**
 * Moves a panorama's photo sphere metadata with an edit, so a viewer still knows where on the
 * sphere the edited picture sits.
 */
final class Panoramas {
    private Panoramas() {}

    /**
     * The metadata for the edited picture, or null when the edit leaves a picture that is no
     * longer part of a sphere: one that is turned, flipped or straightened.
     *
     * <p>The metadata's pixels need not be the picture's own; a writer may describe a smaller
     * picture than it stores. The crop is measured in the metadata's pixels, and the result is
     * given in the edited picture's own, with the full panorama scaled to match.</p>
     *
     * @param uprightWidth the original picture's width, upright
     * @param uprightHeight its height
     * @param width the edited picture's width
     * @param height its height
     */
    @AnyThread
    @Nullable
    static MediaTags.Panorama edited(@NonNull MediaTags.Panorama panorama, int uprightWidth,
            int uprightHeight, @NonNull PictureEdits edits, int width, int height) {
        int[] net = PictureGeometry.combine(0, false, edits.getRotationDegrees(),
                edits.isFlippedHorizontally(), edits.isFlippedVertically());
        if (net[0] != 0 || net[1] != 0 || edits.getStraightenDegrees() != 0f) {
            return null;
        }
        RectF crop = edits.getCrop();
        if (crop == null) {
            crop = new RectF(0, 0, 1, 1);
        }
        double imageWidth = orDefault(panorama.getCroppedAreaImageWidthPixels(), uprightWidth);
        double imageHeight = orDefault(panorama.getCroppedAreaImageHeightPixels(), uprightHeight);
        double fullWidth = orDefault(panorama.getFullPanoWidthPixels(), imageWidth);
        double fullHeight = orDefault(panorama.getFullPanoHeightPixels(), imageHeight);
        double left = orDefault(panorama.getCroppedAreaLeftPixels(), (fullWidth - imageWidth) / 2);
        double top = orDefault(panorama.getCroppedAreaTopPixels(), (fullHeight - imageHeight) / 2);
        // From the metadata's pixels to the edited picture's.
        double scaleX = width / (imageWidth * crop.width());
        double scaleY = height / (imageHeight * crop.height());

        MediaTags.Panorama.Builder builder = builder(panorama)
                .setFullPanoWidthPixels((int) Math.round(fullWidth * scaleX))
                .setFullPanoHeightPixels((int) Math.round(fullHeight * scaleY))
                .setCroppedAreaLeftPixels(
                        (int) Math.round((left + crop.left * imageWidth) * scaleX))
                .setCroppedAreaTopPixels((int) Math.round((top + crop.top * imageHeight) * scaleY))
                .setCroppedAreaImageWidthPixels(width)
                .setCroppedAreaImageHeightPixels(height);
        Integer validLeft = panorama.getLargestValidInteriorRectLeft();
        Integer validTop = panorama.getLargestValidInteriorRectTop();
        Integer validWidth = panorama.getLargestValidInteriorRectWidth();
        Integer validHeight = panorama.getLargestValidInteriorRectHeight();
        if (validLeft != null && validTop != null && validWidth != null && validHeight != null) {
            // The rectangle is in the stored picture's pixels, so it is cropped with it.
            RectF valid = new RectF(validLeft, validTop, validLeft + validWidth,
                    validTop + validHeight);
            RectF kept = new RectF((float) (crop.left * imageWidth),
                    (float) (crop.top * imageHeight), (float) (crop.right * imageWidth),
                    (float) (crop.bottom * imageHeight));
            if (valid.intersect(kept)) {
                builder.setLargestValidInteriorRectLeft(
                                (int) Math.round((valid.left - kept.left) * scaleX))
                        .setLargestValidInteriorRectTop(
                                (int) Math.round((valid.top - kept.top) * scaleY))
                        .setLargestValidInteriorRectWidth(
                                (int) Math.round(valid.width() * scaleX))
                        .setLargestValidInteriorRectHeight(
                                (int) Math.round(valid.height() * scaleY));
            } else {
                builder.setLargestValidInteriorRectLeft(null)
                        .setLargestValidInteriorRectTop(null)
                        .setLargestValidInteriorRectWidth(null)
                        .setLargestValidInteriorRectHeight(null);
            }
        }
        return builder.build();
    }

    /** A builder holding everything a panorama's metadata already says. */
    @NonNull
    private static MediaTags.Panorama.Builder builder(@NonNull MediaTags.Panorama panorama) {
        return new MediaTags.Panorama.Builder()
                .setUsePanoramaViewer(panorama.getUsePanoramaViewer())
                .setCaptureSoftware(panorama.getCaptureSoftware())
                .setStitchingSoftware(panorama.getStitchingSoftware())
                .setProjectionType(panorama.getProjectionType())
                .setPoseHeadingDegrees(panorama.getPoseHeadingDegrees())
                .setPosePitchDegrees(panorama.getPosePitchDegrees())
                .setPoseRollDegrees(panorama.getPoseRollDegrees())
                .setInitialViewHeadingDegrees(panorama.getInitialViewHeadingDegrees())
                .setInitialViewPitchDegrees(panorama.getInitialViewPitchDegrees())
                .setInitialViewRollDegrees(panorama.getInitialViewRollDegrees())
                .setInitialHorizontalFovDegrees(panorama.getInitialHorizontalFovDegrees())
                .setInitialVerticalFovDegrees(panorama.getInitialVerticalFovDegrees())
                .setFirstPhotoDate(panorama.getFirstPhotoDate())
                .setLastPhotoDate(panorama.getLastPhotoDate())
                .setSourcePhotosCount(panorama.getSourcePhotosCount())
                .setExposureLockUsed(panorama.getExposureLockUsed())
                .setInitialCameraDolly(panorama.getInitialCameraDolly())
                .setLargestValidInteriorRectLeft(panorama.getLargestValidInteriorRectLeft())
                .setLargestValidInteriorRectTop(panorama.getLargestValidInteriorRectTop())
                .setLargestValidInteriorRectWidth(panorama.getLargestValidInteriorRectWidth())
                .setLargestValidInteriorRectHeight(panorama.getLargestValidInteriorRectHeight());
    }

    private static double orDefault(@Nullable Integer value, double fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
