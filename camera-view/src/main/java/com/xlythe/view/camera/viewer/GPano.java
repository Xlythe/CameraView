package com.xlythe.view.camera.viewer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xlythe.view.camera.MediaTags;

/**
 * Where an equirectangular picture sits on the sphere, worked out from its photo sphere (GPano)
 * metadata as {@link MediaTags} reads it.
 *
 * <p>The six cropped-area properties say which part of the whole sphere the stored picture is.
 * They are in the metadata's own pixels, which need not be the stored picture's: a picture that
 * was resized after it was written keeps its old numbers. So everything here is a fraction of
 * the full panorama, and only the ratios matter.</p>
 */
final class GPano {
    /** Where the left edge of the stored picture falls, as a fraction of the full width. */
    final double left;

    /** Where the top edge falls, as a fraction of the full height. */
    final double top;

    /** How much of the full width the stored picture covers, as a fraction. */
    final double width;

    /** How much of the full height it covers, as a fraction. */
    final double height;

    /** The compass heading of the middle of the full panorama, clockwise from north. */
    final float poseHeadingDegrees;

    /** How far the middle of the full panorama is above the horizon. */
    final float posePitchDegrees;

    /** How far the panorama is rolled; the horizon turns anticlockwise as it grows. */
    final float poseRollDegrees;

    /** The compass heading to open at, or NaN to open at the middle of the picture. */
    final float initialHeadingDegrees;

    /** The pitch to open at, or NaN to open level with the middle of the picture. */
    final float initialPitchDegrees;

    /** How wide a view to open with, in degrees, or NaN for the viewer's own choice. */
    final float initialHorizontalFovDegrees;

    GPano(double left, double top, double width, double height, float poseHeadingDegrees,
            float posePitchDegrees, float poseRollDegrees, float initialHeadingDegrees,
            float initialPitchDegrees, float initialHorizontalFovDegrees) {
        this.left = left;
        this.top = top;
        this.width = width;
        this.height = height;
        this.poseHeadingDegrees = poseHeadingDegrees;
        this.posePitchDegrees = posePitchDegrees;
        this.poseRollDegrees = poseRollDegrees;
        this.initialHeadingDegrees = initialHeadingDegrees;
        this.initialPitchDegrees = initialPitchDegrees;
        this.initialHorizontalFovDegrees = initialHorizontalFovDegrees;
    }

    /**
     * What a picture with no metadata is taken to be: all the way round, and as tall as the same
     * angle per pixel makes it, centred on the horizon. A 2:1 picture is the whole sphere.
     */
    @NonNull
    static GPano assumed(int imageWidth, int imageHeight) {
        double height = Math.min(1.0, 2.0 * imageHeight / Math.max(1, imageWidth));
        return new GPano(0, (1 - height) / 2, 1, height, 0f, 0f, 0f, Float.NaN, Float.NaN,
                Float.NaN);
    }

    /** Whether the picture goes all the way round, so its two ends meet. */
    boolean isFullCircle() {
        return width >= 1.0 - 1e-6;
    }

    /**
     * The heading a column of the picture looks along, relative to the middle of the full
     * panorama, in degrees.
     *
     * @param x across the stored picture, from 0 at its left edge to 1 at its right
     */
    double headingAt(double x) {
        return (left + x * width) * 360.0 - 180.0;
    }

    /**
     * The pitch a row of the picture looks along, in degrees above the horizon.
     *
     * @param y down the stored picture, from 0 at its top edge to 1 at its bottom
     */
    double pitchAt(double y) {
        return 90.0 - (top + y * height) * 180.0;
    }

    /**
     * Where a picture sits, from its photo sphere metadata, filling in what the metadata leaves
     * out: the cropped size from the stored picture's, the full size from the cropped one, and
     * the crop's position as the middle of the full panorama.
     *
     * @param panorama the metadata, or null if the picture has none
     * @param imageWidth the stored picture's width
     * @param imageHeight its height
     * @return where it sits, or null if it has no metadata, names a projection other than
     *     equirectangular, or gives sizes that are not positive
     */
    @Nullable
    static GPano from(@Nullable MediaTags.Panorama panorama, int imageWidth, int imageHeight) {
        if (panorama == null) {
            return null;
        }
        String projection = panorama.getProjectionType();
        if (projection != null
                && !MediaTags.Panorama.PROJECTION_EQUIRECTANGULAR.equalsIgnoreCase(projection)) {
            return null;
        }
        double croppedWidth = number(panorama.getCroppedAreaImageWidthPixels(), imageWidth);
        double croppedHeight = number(panorama.getCroppedAreaImageHeightPixels(), imageHeight);
        double fullWidth = number(panorama.getFullPanoWidthPixels(), croppedWidth);
        double fullHeight = number(panorama.getFullPanoHeightPixels(), fullWidth / 2);
        double left = number(panorama.getCroppedAreaLeftPixels(), (fullWidth - croppedWidth) / 2);
        double top = number(panorama.getCroppedAreaTopPixels(), (fullHeight - croppedHeight) / 2);
        if (!(croppedWidth > 0) || !(croppedHeight > 0) || !(fullWidth > 0)
                || !(fullHeight > 0)) {
            return null;
        }
        // A crop that claims more than the whole is clamped to it rather than refused: the
        // picture is still a panorama, and its writer rounded.
        double width = Math.min(1.0, croppedWidth / fullWidth);
        double height = Math.min(1.0, croppedHeight / fullHeight);
        return new GPano(
                clamp(left / fullWidth, 1 - width),
                clamp(top / fullHeight, 1 - height),
                width,
                height,
                finite(panorama.getPoseHeadingDegrees(), 0),
                finite(panorama.getPosePitchDegrees(), 0),
                finite(panorama.getPoseRollDegrees(), 0),
                number(panorama.getInitialViewHeadingDegrees()),
                number(panorama.getInitialViewPitchDegrees()),
                finite(panorama.getInitialHorizontalFovDegrees(), Float.NaN));
    }

    /** A pixel count, or the fallback if it is missing. */
    private static double number(@Nullable Integer value, double fallback) {
        return value == null ? fallback : value;
    }

    /** An angle in whole degrees, or NaN if it is missing. */
    private static float number(@Nullable Integer value) {
        return value == null ? Float.NaN : value;
    }

    /** A number, or the fallback if it is missing or not finite. */
    private static float finite(float value, float fallback) {
        return Float.isNaN(value) || Float.isInfinite(value) ? fallback : value;
    }

    /** Holds a fraction between 0 and the most it can be. */
    private static double clamp(double value, double max) {
        return Math.max(0, Math.min(max, value));
    }
}
