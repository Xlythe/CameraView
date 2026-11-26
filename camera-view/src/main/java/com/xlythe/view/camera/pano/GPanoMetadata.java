package com.xlythe.view.camera.pano;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.Locale;

import static com.xlythe.view.camera.ICameraModule.TAG;

/**
 * The metadata that makes a wide picture a panorama to everything else.
 *
 * <p>A stitched equirectangular image is, to any viewer that has not been told otherwise, a
 * picture with an odd aspect ratio. What makes Google Photos offer to pan around it, and a web
 * viewer wrap it onto a sphere, is an XMP packet in the GPano namespace saying what projection
 * it is and how much of the sphere it covers. Writing the pixels and not the metadata produces
 * a file that looks right and behaves like a snapshot.</p>
 *
 * <h2>Partial coverage</h2>
 *
 * <p>The seven properties that matter are all about the difference between the picture and the
 * whole sphere: a sweep that went most of the way round and a hand's width above the horizon is
 * a crop out of a full equirectangular canvas, and the Cropped and Full pairs say where in that
 * canvas it sits. A viewer uses them to fill the rest with nothing rather than stretching what
 * there is, which is the difference between a panorama that stops and a panorama that is
 * wrong.</p>
 *
 * <p>Written as its own APP1 segment, after any Exif one. Exif has to be the first segment
 * after the start of the image, so this goes after it rather than first - and in its own
 * segment rather than inside the Exif, because XMP is identified by a different header and a
 * reader looking for one will not find it in the other.</p>

 * <h2>Not part of the API</h2>
 *
 * <p>Writing the metadata that makes a picture a panorama. Restricted to the library, which
 * writes it for every sweep it saves. Reading it back is the half an app has a use for, and
 * that is GPanoBounds.</p>
 *
 * @hide
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class GPanoMetadata {
    /** What an XMP segment announces itself as, including the terminator. */
    @VisibleForTesting
    static final String XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000";

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    /**
     * The byte order mark an XMP packet header carries.
     *
     * <p>Built here rather than written into the string, because a literal one puts the mark in
     * the middle of this source file - which is a thing lint rejects and a thing that breaks
     * tools that read source as bytes. The packet still gets it, which is what the XMP
     * specification asks for.</p>
     */
    private static final char PACKET_BOM = (char) 0xFEFF;

    /** The largest a JPEG segment can be, counting the two bytes of its own length. */
    private static final int MAX_SEGMENT_BYTES = 0xFFFF;

    private final int imageWidth;
    private final int imageHeight;
    private final int fullWidth;
    private final int fullHeight;
    private final int left;
    private final int top;
    private final float headingDegrees;

    /**
     * @param imageWidth the width of the picture actually written
     * @param imageHeight its height
     * @param fullWidth the width the whole sphere would be at this scale
     * @param fullHeight its height, which is half the width
     * @param left where the picture sits in that canvas, in pixels from its left edge
     * @param top where it sits from the top
     * @param headingDegrees the compass heading of the centre of the picture, or NaN if unknown
     */
    public GPanoMetadata(int imageWidth, int imageHeight, int fullWidth, int fullHeight,
            int left, int top, float headingDegrees) {
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.fullWidth = fullWidth;
        this.fullHeight = fullHeight;
        this.left = left;
        this.top = top;
        this.headingDegrees = headingDegrees;
    }

    /**
     * The metadata for a picture that is the whole sphere.
     *
     * <p>The simple case, and the one a photosphere viewer is happiest with: nothing is
     * cropped, so the picture and the canvas are the same thing.</p>
     */
    @NonNull
    public static GPanoMetadata forFullSphere(int width, int height, float headingDegrees) {
        return new GPanoMetadata(width, height, width, height, 0, 0, headingDegrees);
    }

    /**
     * The XMP packet, as the bytes that go in the segment.
     *
     * <p>Built by hand rather than through an XMP library because this is nine properties in
     * one namespace, and the alternative is a dependency the size of the rest of this library
     * for a string.</p>
     */
    @NonNull
    public byte[] toXmpPacket() {
        StringBuilder xmp = new StringBuilder();
        xmp.append("<?xpacket begin=\"").append(PACKET_BOM)
                .append("\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>")
                .append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">")
                .append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">")
                .append("<rdf:Description rdf:about=\"\"")
                .append(" xmlns:GPano=\"http://ns.google.com/photos/1.0/panorama/\"")
                // Asked for explicitly. It defaults to true, but a file that says so is one a
                // reader does not have to guess about.
                .append(attribute("UsePanoramaViewer", "True"))
                .append(attribute("ProjectionType", "equirectangular"))
                .append(attribute("CroppedAreaImageWidthPixels", imageWidth))
                .append(attribute("CroppedAreaImageHeightPixels", imageHeight))
                .append(attribute("FullPanoWidthPixels", fullWidth))
                .append(attribute("FullPanoHeightPixels", fullHeight))
                .append(attribute("CroppedAreaLeftPixels", left))
                .append(attribute("CroppedAreaTopPixels", top));
        if (!Float.isNaN(headingDegrees)) {
            // Which way the middle of the picture faces, so a viewer can open it pointing north
            // rather than wherever the sweep happened to start.
            xmp.append(attribute("PoseHeadingDegrees",
                    String.format(Locale.US, "%.1f", headingDegrees)));
        }
        xmp.append("/></rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>");
        return xmp.toString().getBytes(UTF_8);
    }

    /**
     * Writes this into a JPEG that has already been saved.
     *
     * <p>After the fact rather than during the encode, because the encoder is whatever wrote
     * the file - Bitmap.compress here, a camera elsewhere - and none of them take XMP. The file
     * is rewritten rather than edited in place: a segment is being inserted, so everything
     * after it moves.</p>
     *
     * @return whether it was written
     */
    public boolean writeTo(@NonNull File jpeg) {
        byte[] packet = toXmpPacket();
        byte[] header = XMP_HEADER.getBytes(UTF_8);
        int segmentLength = 2 + header.length + packet.length;
        if (segmentLength > MAX_SEGMENT_BYTES) {
            // Unreachable for nine properties, and checked because a segment that overflows its
            // own length field corrupts every segment after it rather than being rejected.
            Log.w(TAG, "The panorama metadata is too large for one JPEG segment");
            return false;
        }
        try (RandomAccessFile file = new RandomAccessFile(jpeg, "rw")) {
            long[] region = findRegion(file, header);
            if (region == null) {
                return false;
            }
            long replaceFrom = region[0];
            long replaceTo = region[1];
            // Read once, write once. The tail is the rest of the picture, and a panorama is
            // large, so this is the one part worth being careful about - but it is still far
            // cheaper than re-encoding to insert a header.
            byte[] tail = new byte[(int) (file.length() - replaceTo)];
            file.seek(replaceTo);
            file.readFully(tail);

            ByteArrayOutputStream segment = new ByteArrayOutputStream(segmentLength + 2);
            segment.write(0xFF);
            segment.write(0xE1);
            segment.write((segmentLength >> 8) & 0xFF);
            segment.write(segmentLength & 0xFF);
            segment.write(header);
            segment.write(packet);

            file.seek(replaceFrom);
            file.write(segment.toByteArray());
            file.write(tail);
            // Shorter than it was if the packet being replaced was the larger one, and a file
            // left long keeps the tail of the old segment after the picture.
            file.setLength(file.getFilePointer());
            return true;
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "Unable to write panorama metadata into " + jpeg, error);
            return false;
        }
    }

    /**
     * The stretch of the file the new segment replaces.
     *
     * <p>Two cases in one walk. A file that already has an XMP segment has it replaced, because
     * leaving the old one in gives a file with two packets that disagree and readers take
     * whichever they meet first - so writing twice, as a retry does, would publish the stale
     * one. A file with none has the segment inserted instead, at the end of the run of
     * application segments: Exif insists on being the first segment after the start of the
     * image, so the front is not available.</p>
     *
     * @return {from, to} to replace, equal when there is nothing to replace, or null if this is
     *     not a JPEG that can be edited
     */
    @Nullable
    private static long[] findRegion(@NonNull RandomAccessFile file, @NonNull byte[] header)
            throws IOException {
        if (file.length() < 4) {
            return null;
        }
        file.seek(0);
        if ((file.readUnsignedByte() != 0xFF) || (file.readUnsignedByte() != 0xD8)) {
            Log.w(TAG, "Panorama metadata can only be written into a JPEG");
            return null;
        }
        long insertAt = file.getFilePointer();
        while (file.getFilePointer() + 4 <= file.length()) {
            long segmentStart = file.getFilePointer();
            if (file.readUnsignedByte() != 0xFF) {
                break;
            }
            int marker = file.readUnsignedByte();
            // APP0 to APP15, the only run this may be inserted into. Anything else - a
            // quantisation table, a frame header, the scan - is the picture starting.
            if (marker < 0xE0 || marker > 0xEF) {
                break;
            }
            int length = file.readUnsignedShort();
            if (length < 2 || segmentStart + 2 + length > file.length()) {
                // A length that does not fit is a file that cannot be walked, and guessing past
                // it would put the segment inside the picture.
                return new long[] {insertAt, insertAt};
            }
            long segmentEnd = segmentStart + 2 + length;
            if (marker == 0xE1 && length - 2 >= header.length) {
                byte[] candidate = new byte[header.length];
                file.readFully(candidate);
                if (matches(candidate, header)) {
                    return new long[] {segmentStart, segmentEnd};
                }
            }
            file.seek(segmentEnd);
            insertAt = segmentEnd;
        }
        return new long[] {insertAt, insertAt};
    }

    private static String attribute(@NonNull String name, int value) {
        return attribute(name, Integer.toString(value));
    }

    private static String attribute(@NonNull String name, @NonNull String value) {
        return " GPano:" + name + "=\"" + value + "\"";
    }

    /** What is in a JPEG's XMP segment, or null if it has none. For reading one back. */
    @Nullable
    public static String readXmp(@NonNull File jpeg) {
        try (RandomAccessFile file = new RandomAccessFile(jpeg, "r")) {
            if (file.length() < 4) {
                return null;
            }
            file.seek(2);
            byte[] header = XMP_HEADER.getBytes(UTF_8);
            while (file.getFilePointer() + 4 <= file.length()) {
                long segmentStart = file.getFilePointer();
                if (file.readUnsignedByte() != 0xFF) {
                    return null;
                }
                int marker = file.readUnsignedByte();
                if (marker < 0xE0 || marker > 0xEF) {
                    return null;
                }
                int length = file.readUnsignedShort();
                if (length < 2 || segmentStart + 2 + length > file.length()) {
                    return null;
                }
                if (marker == 0xE1 && length - 2 > header.length) {
                    byte[] candidate = new byte[header.length];
                    file.readFully(candidate);
                    if (matches(candidate, header)) {
                        byte[] packet = new byte[length - 2 - header.length];
                        file.readFully(packet);
                        return new String(packet, UTF_8);
                    }
                }
                file.seek(segmentStart + 2 + length);
            }
            return null;
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "Unable to read panorama metadata from " + jpeg, error);
            return null;
        }
    }

    private static boolean matches(@NonNull byte[] candidate, @NonNull byte[] expected) {
        if (candidate.length != expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (candidate[i] != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
