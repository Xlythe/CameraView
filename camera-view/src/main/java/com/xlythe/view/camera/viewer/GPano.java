package com.xlythe.view.camera.viewer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where an equirectangular picture sits on the sphere, read from its photo sphere (GPano) XMP.
 *
 * <p>The six cropped-area properties say which part of the whole sphere the stored picture is.
 * They are in the metadata's own pixels, which need not be the stored picture's: a picture that
 * was resized after it was written keeps its old numbers. So everything here is a fraction of
 * the full panorama, and only the ratios matter.</p>
 */
final class GPano {
    /** The namespace every property is in. */
    static final String NAMESPACE = "http://ns.google.com/photos/1.0/panorama/";

    /** What an XMP segment starts with, terminator included. */
    private static final String XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000";

    /** The only projection this reads; a picture in any other is not placed on the sphere. */
    private static final String EQUIRECTANGULAR = "equirectangular";

    /** The marker that opens an APP1 segment, where both Exif and XMP live. */
    private static final int APP1 = 0xE1;

    /** Start of scan: compressed data follows, and there are no more segments to look at. */
    private static final int START_OF_SCAN = 0xDA;

    /** End of image. */
    private static final int END_OF_IMAGE = 0xD9;

    /** The prefix a packet binds to the GPano namespace, which is usually but not always GPano. */
    private static final Pattern PREFIX = Pattern.compile(
            "xmlns:([\\w.-]+)\\s*=\\s*[\"']" + Pattern.quote(NAMESPACE) + "[\"']");

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
     * Reads the metadata out of a JPEG, stopping where the compressed picture starts.
     *
     * @param imageWidth the stored picture's width, for properties the packet leaves out
     * @param imageHeight its height
     * @return where the picture sits, or null if it carries no usable GPano properties
     */
    @Nullable
    @WorkerThread
    static GPano read(@NonNull InputStream jpeg, int imageWidth, int imageHeight)
            throws IOException {
        String xmp = findXmp(jpeg);
        return xmp == null ? null : parse(xmp, imageWidth, imageHeight);
    }

    /** The XMP packet in a JPEG, or null if it is not a JPEG or has none. */
    @Nullable
    @WorkerThread
    static String findXmp(@NonNull InputStream input) throws IOException {
        DataInputStream jpeg = new DataInputStream(input);
        byte[] header = XMP_HEADER.getBytes("ISO-8859-1");
        try {
            if (jpeg.readUnsignedByte() != 0xFF || jpeg.readUnsignedByte() != 0xD8) {
                return null;
            }
            while (true) {
                if (jpeg.readUnsignedByte() != 0xFF) {
                    return null;
                }
                // Any number of 0xFF fill bytes may come before the marker's own byte.
                int marker;
                do {
                    marker = jpeg.readUnsignedByte();
                } while (marker == 0xFF);
                if (marker == START_OF_SCAN || marker == END_OF_IMAGE) {
                    return null;
                }
                int length = jpeg.readUnsignedShort() - 2;
                if (length < 0) {
                    return null;
                }
                byte[] payload = new byte[length];
                jpeg.readFully(payload);
                if (marker == APP1 && startsWith(payload, header)) {
                    return new String(payload, header.length, payload.length - header.length,
                            "UTF-8");
                }
            }
        } catch (EOFException truncated) {
            return null;
        }
    }

    private static boolean startsWith(@NonNull byte[] bytes, @NonNull byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reads the properties out of an XMP packet, whichever of the two forms each is written in.
     *
     * <p>A property the packet leaves out is filled in from the rest: the cropped size from the
     * stored picture's, the full size from the cropped one, and the crop's position as the middle
     * of the full panorama. A packet that names another projection, or none of the namespace's
     * properties, is not a photo sphere.</p>
     */
    @Nullable
    static GPano parse(@NonNull String xmp, int imageWidth, int imageHeight) {
        Map<String, String> properties = properties(xmp);
        if (properties.isEmpty()) {
            return null;
        }
        String projection = properties.get("ProjectionType");
        if (projection != null && !EQUIRECTANGULAR.equalsIgnoreCase(projection.trim())) {
            return null;
        }
        double croppedWidth = number(properties, "CroppedAreaImageWidthPixels", imageWidth);
        double croppedHeight = number(properties, "CroppedAreaImageHeightPixels", imageHeight);
        double fullWidth = number(properties, "FullPanoWidthPixels", croppedWidth);
        double fullHeight = number(properties, "FullPanoHeightPixels", fullWidth / 2);
        double left = number(properties, "CroppedAreaLeftPixels", (fullWidth - croppedWidth) / 2);
        double top = number(properties, "CroppedAreaTopPixels", (fullHeight - croppedHeight) / 2);
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
                (float) number(properties, "PoseHeadingDegrees", 0),
                (float) number(properties, "PosePitchDegrees", 0),
                (float) number(properties, "PoseRollDegrees", 0),
                (float) number(properties, "InitialViewHeadingDegrees", Double.NaN),
                (float) number(properties, "InitialViewPitchDegrees", Double.NaN),
                (float) number(properties, "InitialHorizontalFOVDegrees", Double.NaN));
    }

    /** Every property under the namespace's prefix, attributes and elements both. */
    @NonNull
    private static Map<String, String> properties(@NonNull String xmp) {
        Map<String, String> properties = new HashMap<>();
        Matcher bound = PREFIX.matcher(xmp);
        String prefix = bound.find() ? Pattern.quote(bound.group(1)) : "GPano";
        Pattern attribute = Pattern.compile(
                "\\b" + prefix + ":(\\w+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
        Pattern element = Pattern.compile(
                "<" + prefix + ":(\\w+)\\s*>([^<]*)</" + prefix + ":\\1\\s*>");
        Matcher matcher = attribute.matcher(xmp);
        while (matcher.find()) {
            String value = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            properties.put(matcher.group(1), unescape(value));
        }
        matcher = element.matcher(xmp);
        while (matcher.find()) {
            properties.put(matcher.group(1), unescape(matcher.group(2)));
        }
        return properties;
    }

    /** A number property, or the fallback if it is missing or not a finite number. */
    private static double number(@NonNull Map<String, String> properties, @NonNull String name,
            double fallback) {
        String value = properties.get(name);
        if (value == null) {
            return fallback;
        }
        try {
            double parsed = Double.parseDouble(value.trim());
            return Double.isNaN(parsed) || Double.isInfinite(parsed) ? fallback : parsed;
        } catch (NumberFormatException malformed) {
            return fallback;
        }
    }

    /** Holds a fraction between 0 and the most it can be. */
    private static double clamp(double value, double max) {
        return Math.max(0, Math.min(max, value));
    }

    @NonNull
    private static String unescape(@NonNull String value) {
        return value.replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&apos;", "'").replace("&amp;", "&");
    }
}
