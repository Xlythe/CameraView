package com.xlythe.view.camera.pano;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.xlythe.view.camera.ICameraModule.TAG;

/**
 * Where a panorama sits on the sphere, read back out of its own metadata.
 *
 * <p>The other half of {@link GPanoMetadata}: that writes where a picture belongs, this reads
 * it. Needed by anything that shows a panorama, because the picture alone does not say. A sweep
 * that went a quarter of the way round at eye level is a small crop out of a large canvas, and
 * a viewer that assumes the picture is the whole sphere wraps that quarter all the way round -
 * which is the characteristic way a panorama is shown wrongly.</p>
 *
 * <p>Parsed with a pattern per property rather than through an XML parser. The packet is a
 * single element with attributes in one namespace, written by whoever made the file, and the
 * properties are needed individually - so matching each one is both shorter and more forgiving
 * of a packet that has something unexpected elsewhere in it.</p>
 */
public final class GPanoBounds {
    /** What a panorama with no crop information is assumed to be: the whole sphere. */
    private static final String PROJECTION_EQUIRECTANGULAR = "equirectangular";

    private final int mImageWidth;
    private final int mImageHeight;
    private final int mFullWidth;
    private final int mFullHeight;
    private final int mLeft;
    private final int mTop;
    private final float mHeadingDegrees;

    private GPanoBounds(int imageWidth, int imageHeight, int fullWidth, int fullHeight,
            int left, int top, float headingDegrees) {
        mImageWidth = imageWidth;
        mImageHeight = imageHeight;
        mFullWidth = fullWidth;
        mFullHeight = fullHeight;
        mLeft = left;
        mTop = top;
        mHeadingDegrees = headingDegrees;
    }

    /**
     * The bounds of a panorama file, or null if it is not one.
     *
     * <p>Null for a picture with no GPano packet, which is every ordinary photograph - so a
     * viewer handed one can say so rather than showing a flat picture wrapped round a
     * sphere.</p>
     */
    @Nullable
    public static GPanoBounds from(@NonNull File jpeg) {
        return fromXmp(GPanoMetadata.readXmp(jpeg));
    }

    /** The bounds in an XMP packet, or null if it does not describe a panorama. */
    @Nullable
    public static GPanoBounds fromXmp(@Nullable String xmp) {
        if (xmp == null) {
            return null;
        }
        String projection = string(xmp, "ProjectionType");
        if (projection != null && !PROJECTION_EQUIRECTANGULAR.equalsIgnoreCase(projection)) {
            // Cylindrical and the rest exist in the specification and are not what anything
            // here produces or can show; saying so beats showing them as if they were
            // equirectangular.
            Log.w(TAG, "This panorama is " + projection + ", which cannot be shown as a sphere");
            return null;
        }
        int fullWidth = integer(xmp, "FullPanoWidthPixels", -1);
        int fullHeight = integer(xmp, "FullPanoHeightPixels", -1);
        int imageWidth = integer(xmp, "CroppedAreaImageWidthPixels", fullWidth);
        int imageHeight = integer(xmp, "CroppedAreaImageHeightPixels", fullHeight);
        if (fullWidth <= 0 || fullHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) {
            // A packet without the dimensions says nothing about where the picture sits, which
            // is the only thing this is for.
            return null;
        }
        return new GPanoBounds(imageWidth, imageHeight, fullWidth, fullHeight,
                integer(xmp, "CroppedAreaLeftPixels", 0),
                integer(xmp, "CroppedAreaTopPixels", 0),
                number(xmp, "PoseHeadingDegrees"));
    }

    /** The whole sphere, for a picture that is one without saying so. */
    @NonNull
    public static GPanoBounds forFullSphere(int width, int height) {
        return new GPanoBounds(width, height, width, height, 0, 0, Float.NaN);
    }

    /** How wide the picture itself is, in pixels. */
    public int getImageWidth() {
        return mImageWidth;
    }

    /** How tall the picture itself is. */
    public int getImageHeight() {
        return mImageHeight;
    }

    /** How wide the whole sphere would be at this scale. */
    public int getFullWidth() {
        return mFullWidth;
    }

    /** How tall the whole sphere would be. */
    public int getFullHeight() {
        return mFullHeight;
    }

    /** Where the picture sits in that canvas, in pixels from its left edge. */
    public int getLeft() {
        return mLeft;
    }

    /** Where it sits from the top. */
    public int getTop() {
        return mTop;
    }

    /** The compass heading of the middle of the picture, or NaN if it does not say. */
    public float getHeadingDegrees() {
        return mHeadingDegrees;
    }

    /** Whether the picture covers the whole sphere, and so needs no crop handling at all. */
    public boolean isFullSphere() {
        return mLeft == 0 && mTop == 0
                && mImageWidth == mFullWidth && mImageHeight == mFullHeight;
    }

    /** The westmost longitude the picture covers, in degrees, measured as a yaw. */
    public float getLeftYaw() {
        return (float) mLeft / mFullWidth * Equirectangular.FULL_TURN_DEGREES
                - Equirectangular.HALF_TURN_DEGREES;
    }

    /** How much longitude it covers. */
    public float getYawSpan() {
        return (float) mImageWidth / mFullWidth * Equirectangular.FULL_TURN_DEGREES;
    }

    /** The highest latitude it covers, as a pitch. */
    public float getTopPitch() {
        return 90f - (float) mTop / mFullHeight * Equirectangular.HALF_TURN_DEGREES;
    }

    /** How much latitude it covers. */
    public float getPitchSpan() {
        return (float) mImageHeight / mFullHeight * Equirectangular.HALF_TURN_DEGREES;
    }

    /**
     * Where a direction falls in the picture, as texture coordinates.
     *
     * <p>What a renderer needs per pixel, inverted: given the direction a screen pixel looks in,
     * where to sample. Outside 0 to 1 means the direction is not in the picture at all, which is
     * most of the sphere for a flat sweep and is the caller's cue to draw nothing.</p>
     *
     * @param out set to {u, v}
     */
    public void textureCoordinatesFor(float yawDegrees, float pitchDegrees, @NonNull float[] out) {
        float u = (Equirectangular.wrapYaw(yawDegrees) + Equirectangular.HALF_TURN_DEGREES)
                / Equirectangular.FULL_TURN_DEGREES * mFullWidth;
        float v = (90f - Equirectangular.clampPitch(pitchDegrees))
                / Equirectangular.HALF_TURN_DEGREES * mFullHeight;
        out[0] = (u - mLeft) / mImageWidth;
        out[1] = (v - mTop) / mImageHeight;
    }

    @Nullable
    private static String string(@NonNull String xmp, @NonNull String name) {
        Matcher matcher = Pattern.compile(
                "GPano:" + name + "\\s*=\\s*\"([^\"]*)\"").matcher(xmp);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static int integer(@NonNull String xmp, @NonNull String name, int fallback) {
        String value = string(xmp, name);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException error) {
            Log.w(TAG, "GPano:" + name + " is not a number: " + value);
            return fallback;
        }
    }

    private static float number(@NonNull String xmp, @NonNull String name) {
        String value = string(xmp, name);
        if (value == null) {
            return Float.NaN;
        }
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException error) {
            Log.w(TAG, "GPano:" + name + " is not a number: " + value);
            return Float.NaN;
        }
    }
}
