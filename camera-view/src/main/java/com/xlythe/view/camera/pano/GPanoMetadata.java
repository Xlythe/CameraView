package com.xlythe.view.camera.pano;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.WorkerThread;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The photo sphere XMP metadata: the GPano namespace, which turns an equirectangular picture into
 * a panorama for Google Photos, Street View and the web viewers that follow them.
 *
 * <p>Every property the namespace defines is here, required or not. The six required ones say
 * where the picture sits in the whole sphere it was cropped from, which is what lets a viewer
 * leave the rest empty rather than stretch a partial sweep over everything. The optional ones are
 * written whenever they are set, and a reader gets back whatever a file carried.</p>
 *
 * <p>The packet is written into the JPEG as its own APP1 segment, after any Exif one: Exif has to
 * come first, and XMP is found by its own header rather than inside Exif. Writing replaces any XMP
 * packet already there, since two packets disagree and a reader takes whichever it finds
 * first.</p>
 */
@RequiresApi(19)
final class GPanoMetadata {
    /** The namespace every property is in. */
    static final String NAMESPACE = "http://ns.google.com/photos/1.0/panorama/";

    /** What an XMP segment starts with, terminator included. */
    static final String XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000";

    /** The only projection Google's viewers support, and the one a sweep produces. */
    static final String EQUIRECTANGULAR = "equirectangular";

    /** The largest a JPEG segment's payload can be, after the two bytes of its length. */
    private static final int MAX_SEGMENT_PAYLOAD = 0xFFFF - 2;

    /** The marker that opens an APP1 segment, where both Exif and XMP live. */
    private static final int APP1 = 0xE1;

    /** The marker that opens an APP0 segment, where JFIF lives. */
    private static final int APP0 = 0xE0;

    /** Start of scan: compressed data follows, and there are no more segments to look at. */
    private static final int START_OF_SCAN = 0xDA;

    /** A property written as an attribute: GPano:Name="value". */
    private static final Pattern ATTRIBUTE =
            Pattern.compile("GPano:(\\w+)\\s*=\\s*\"([^\"]*)\"");

    /** A property written as an element: &lt;GPano:Name&gt;value&lt;/GPano:Name&gt;. */
    private static final Pattern ELEMENT =
            Pattern.compile("<GPano:(\\w+)>([^<]*)</GPano:\\1>");

    /** Whether to show the picture in a panorama viewer rather than flat. */
    @Nullable Boolean usePanoramaViewer;

    /** The app the source photos were taken with. */
    @Nullable String captureSoftware;

    /** What stitched them. */
    @Nullable String stitchingSoftware;

    /** The projection, which is required. */
    @NonNull String projectionType = EQUIRECTANGULAR;

    /** The compass heading of the middle of the full panorama, clockwise from north. */
    @Nullable Float poseHeadingDegrees;

    /** How far the middle of the full panorama is above the horizon. */
    @Nullable Float posePitchDegrees;

    /** How far the panorama is rolled from level. */
    @Nullable Float poseRollDegrees;

    /** The compass heading a viewer should open at. */
    @Nullable Integer initialViewHeadingDegrees;

    /** The pitch a viewer should open at. */
    @Nullable Integer initialViewPitchDegrees;

    /** The roll a viewer should open at. */
    @Nullable Integer initialViewRollDegrees;

    /** How wide a view a viewer should open with, in degrees. */
    @Nullable Float initialHorizontalFovDegrees;

    /** How tall a view a viewer should open with; ignored when the width is given. */
    @Nullable Float initialVerticalFovDegrees;

    /** When the first source photo was taken, in milliseconds since the epoch. */
    @Nullable Long firstPhotoDate;

    /** When the last source photo was taken. */
    @Nullable Long lastPhotoDate;

    /** How many source photos went into the panorama. */
    @Nullable Integer sourcePhotosCount;

    /** Whether exposure was locked while the source photos were taken. */
    @Nullable Boolean exposureLockUsed;

    /** The width of the picture as stored. Required. */
    int croppedAreaImageWidthPixels;

    /** The height of the picture as stored. Required. */
    int croppedAreaImageHeightPixels;

    /** The width of the whole sphere the picture is cut from. Required. */
    int fullPanoWidthPixels;

    /** The height of the whole sphere, half its width. Required. */
    int fullPanoHeightPixels;

    /** Where the picture's left edge falls in the whole sphere. Required. */
    int croppedAreaLeftPixels;

    /** Where the picture's top edge falls in the whole sphere. Required. */
    int croppedAreaTopPixels;

    /** How far back from the centre of the sphere a viewer's camera sits; 0 is the centre. */
    @Nullable Float initialCameraDolly;

    /** The XMP packet, ready to go into a JPEG segment. */
    @NonNull
    byte[] toXmpPacket() {
        StringBuilder xmp = new StringBuilder()
                .append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
                .append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n")
                .append(" <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
                .append("  <rdf:Description rdf:about=\"\"\n")
                .append("    xmlns:GPano=\"").append(NAMESPACE).append('"');
        property(xmp, "UsePanoramaViewer", usePanoramaViewer);
        property(xmp, "CaptureSoftware", captureSoftware);
        property(xmp, "StitchingSoftware", stitchingSoftware);
        property(xmp, "ProjectionType", projectionType);
        property(xmp, "PoseHeadingDegrees", real(poseHeadingDegrees));
        property(xmp, "PosePitchDegrees", real(posePitchDegrees));
        property(xmp, "PoseRollDegrees", real(poseRollDegrees));
        property(xmp, "InitialViewHeadingDegrees", initialViewHeadingDegrees);
        property(xmp, "InitialViewPitchDegrees", initialViewPitchDegrees);
        property(xmp, "InitialViewRollDegrees", initialViewRollDegrees);
        property(xmp, "InitialHorizontalFOVDegrees", real(initialHorizontalFovDegrees));
        property(xmp, "InitialVerticalFOVDegrees", real(initialVerticalFovDegrees));
        property(xmp, "FirstPhotoDate", date(firstPhotoDate));
        property(xmp, "LastPhotoDate", date(lastPhotoDate));
        property(xmp, "SourcePhotosCount", sourcePhotosCount);
        property(xmp, "ExposureLockUsed", exposureLockUsed);
        property(xmp, "CroppedAreaImageWidthPixels", croppedAreaImageWidthPixels);
        property(xmp, "CroppedAreaImageHeightPixels", croppedAreaImageHeightPixels);
        property(xmp, "FullPanoWidthPixels", fullPanoWidthPixels);
        property(xmp, "FullPanoHeightPixels", fullPanoHeightPixels);
        property(xmp, "CroppedAreaLeftPixels", croppedAreaLeftPixels);
        property(xmp, "CroppedAreaTopPixels", croppedAreaTopPixels);
        property(xmp, "InitialCameraDolly", real(initialCameraDolly));
        xmp.append("/>\n")
                .append(" </rdf:RDF>\n")
                .append("</x:xmpmeta>\n")
                .append("<?xpacket end=\"w\"?>");
        return xmp.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void property(@NonNull StringBuilder xmp, @NonNull String name,
            @Nullable Object value) {
        if (value == null) {
            return;
        }
        String text;
        if (value instanceof Boolean) {
            // XMP spells its booleans with a capital, unlike Java.
            text = (Boolean) value ? "True" : "False";
        } else {
            text = escape(value.toString());
        }
        xmp.append("\n    GPano:").append(name).append("=\"").append(text).append('"');
    }

    /** A real number the way XMP wants it: plain digits, never an exponent. */
    @Nullable
    private static String real(@Nullable Float value) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return null;
        }
        return new BigDecimal(value).setScale(3, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    @Nullable
    private static String date(@Nullable Long millis) {
        return millis == null ? null : dateFormat().format(new Date(millis));
    }

    /** XMP's date and time form, in UTC so the zone never has to be worked out. */
    @NonNull
    private static SimpleDateFormat dateFormat() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format;
    }

    @NonNull
    private static String escape(@NonNull String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    @NonNull
    private static String unescape(@NonNull String value) {
        return value.replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&apos;", "'").replace("&amp;", "&");
    }

    /**
     * Reads the properties out of an XMP packet, whichever form each is written in.
     *
     * @return the metadata, or null if the packet has no GPano properties at all
     */
    @Nullable
    static GPanoMetadata parse(@NonNull String xmp) {
        GPanoMetadata metadata = new GPanoMetadata();
        boolean found = false;
        for (Pattern pattern : new Pattern[] {ATTRIBUTE, ELEMENT}) {
            Matcher matcher = pattern.matcher(xmp);
            while (matcher.find()) {
                found |= metadata.set(matcher.group(1), unescape(matcher.group(2).trim()));
            }
        }
        return found ? metadata : null;
    }

    /** Sets one property from its text. Returns whether the name was a GPano property. */
    private boolean set(@NonNull String name, @NonNull String value) {
        try {
            switch (name) {
                case "UsePanoramaViewer":
                    usePanoramaViewer = Boolean.parseBoolean(value);
                    return true;
                case "CaptureSoftware":
                    captureSoftware = value;
                    return true;
                case "StitchingSoftware":
                    stitchingSoftware = value;
                    return true;
                case "ProjectionType":
                    projectionType = value;
                    return true;
                case "PoseHeadingDegrees":
                    poseHeadingDegrees = Float.parseFloat(value);
                    return true;
                case "PosePitchDegrees":
                    posePitchDegrees = Float.parseFloat(value);
                    return true;
                case "PoseRollDegrees":
                    poseRollDegrees = Float.parseFloat(value);
                    return true;
                case "InitialViewHeadingDegrees":
                    initialViewHeadingDegrees = integer(value);
                    return true;
                case "InitialViewPitchDegrees":
                    initialViewPitchDegrees = integer(value);
                    return true;
                case "InitialViewRollDegrees":
                    initialViewRollDegrees = integer(value);
                    return true;
                case "InitialHorizontalFOVDegrees":
                    initialHorizontalFovDegrees = Float.parseFloat(value);
                    return true;
                case "InitialVerticalFOVDegrees":
                    initialVerticalFovDegrees = Float.parseFloat(value);
                    return true;
                case "FirstPhotoDate":
                    firstPhotoDate = dateFormat().parse(value).getTime();
                    return true;
                case "LastPhotoDate":
                    lastPhotoDate = dateFormat().parse(value).getTime();
                    return true;
                case "SourcePhotosCount":
                    sourcePhotosCount = integer(value);
                    return true;
                case "ExposureLockUsed":
                    exposureLockUsed = Boolean.parseBoolean(value);
                    return true;
                case "CroppedAreaImageWidthPixels":
                    croppedAreaImageWidthPixels = integer(value);
                    return true;
                case "CroppedAreaImageHeightPixels":
                    croppedAreaImageHeightPixels = integer(value);
                    return true;
                case "FullPanoWidthPixels":
                    fullPanoWidthPixels = integer(value);
                    return true;
                case "FullPanoHeightPixels":
                    fullPanoHeightPixels = integer(value);
                    return true;
                case "CroppedAreaLeftPixels":
                    croppedAreaLeftPixels = integer(value);
                    return true;
                case "CroppedAreaTopPixels":
                    croppedAreaTopPixels = integer(value);
                    return true;
                case "InitialCameraDolly":
                    initialCameraDolly = Float.parseFloat(value);
                    return true;
                default:
                    return false;
            }
        } catch (NumberFormatException | ParseException malformed) {
            // A property this reader cannot make sense of is one it does not have.
            return false;
        }
    }

    /** An integer property, which some writers give a fractional part to. */
    private static int integer(@NonNull String value) {
        return Math.round(Float.parseFloat(value));
    }

    /**
     * Writes this metadata into a JPEG, replacing any XMP packet it already carries.
     *
     * @throws IOException if the file is not a JPEG, or cannot be read or written
     */
    @WorkerThread
    void writeTo(@NonNull File jpeg) throws IOException {
        byte[] original = readAll(jpeg);
        byte[] updated = insert(original, toXmpPacket());
        try (FileOutputStream out = new FileOutputStream(jpeg)) {
            out.write(updated);
        }
    }

    /**
     * Reads the metadata a JPEG carries.
     *
     * @return the metadata, or null if the file carries no GPano properties
     * @throws IOException if the file cannot be read
     */
    @Nullable
    @WorkerThread
    static GPanoMetadata readFrom(@NonNull File jpeg) throws IOException {
        String xmp = findXmp(readAll(jpeg));
        return xmp == null ? null : parse(xmp);
    }

    @NonNull
    private static byte[] readAll(@NonNull File file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long length = input.length();
            if (length > Integer.MAX_VALUE) {
                throw new IOException(file + " is too large to be a picture");
            }
            byte[] bytes = new byte[(int) length];
            input.readFully(bytes);
            return bytes;
        }
    }

    /**
     * A JPEG with an XMP packet in place of any it had, after its JFIF and Exif segments.
     *
     * @throws IOException if the bytes are not a JPEG, or the packet is too large for a segment
     */
    @NonNull
    static byte[] insert(@NonNull byte[] jpeg, @NonNull byte[] packet) throws IOException {
        byte[] header = XMP_HEADER.getBytes(StandardCharsets.ISO_8859_1);
        int payload = header.length + packet.length;
        if (payload > MAX_SEGMENT_PAYLOAD) {
            throw new IOException("The XMP packet is " + packet.length + " bytes, over a segment");
        }
        if (jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
            throw new IOException("Not a JPEG");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length + payload + 4);
        out.write(jpeg, 0, 2);
        boolean inserted = false;
        int position = 2;
        while (position + 4 <= jpeg.length && (jpeg[position] & 0xFF) == 0xFF) {
            int marker = jpeg[position + 1] & 0xFF;
            if (marker == START_OF_SCAN) {
                break;
            }
            int length = ((jpeg[position + 2] & 0xFF) << 8) | (jpeg[position + 3] & 0xFF);
            int end = position + 2 + length;
            if (length < 2 || end > jpeg.length) {
                throw new IOException("A segment at " + position + " runs past the end");
            }
            boolean leading = marker == APP0 || (marker == APP1 && !isXmp(jpeg, position, header));
            if (!inserted && !leading) {
                writeSegment(out, header, packet);
                inserted = true;
            }
            if (!(marker == APP1 && isXmp(jpeg, position, header))) {
                out.write(jpeg, position, end - position);
            }
            position = end;
        }
        if (!inserted) {
            writeSegment(out, header, packet);
        }
        out.write(jpeg, position, jpeg.length - position);
        return out.toByteArray();
    }

    /** The XMP packet in a JPEG, or null if it has none. */
    @Nullable
    static String findXmp(@NonNull byte[] jpeg) {
        byte[] header = XMP_HEADER.getBytes(StandardCharsets.ISO_8859_1);
        int position = 2;
        while (position + 4 <= jpeg.length && (jpeg[position] & 0xFF) == 0xFF) {
            int marker = jpeg[position + 1] & 0xFF;
            if (marker == START_OF_SCAN) {
                return null;
            }
            int length = ((jpeg[position + 2] & 0xFF) << 8) | (jpeg[position + 3] & 0xFF);
            if (length < 2 || position + 2 + length > jpeg.length) {
                return null;
            }
            if (marker == APP1 && isXmp(jpeg, position, header)) {
                int start = position + 4 + header.length;
                return new String(jpeg, start, position + 2 + length - start,
                        StandardCharsets.UTF_8);
            }
            position += 2 + length;
        }
        return null;
    }

    private static boolean isXmp(@NonNull byte[] jpeg, int segment, @NonNull byte[] header) {
        int start = segment + 4;
        if (start + header.length > jpeg.length) {
            return false;
        }
        for (int i = 0; i < header.length; i++) {
            if (jpeg[start + i] != header[i]) {
                return false;
            }
        }
        return true;
    }

    private static void writeSegment(@NonNull ByteArrayOutputStream out, @NonNull byte[] header,
            @NonNull byte[] packet) {
        int length = 2 + header.length + packet.length;
        out.write(0xFF);
        out.write(APP1);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.write(header, 0, header.length);
        out.write(packet, 0, packet.length);
    }
}
