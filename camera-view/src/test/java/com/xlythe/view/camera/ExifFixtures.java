package com.xlythe.view.camera;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** JPEGs whose Exif private directory carries exactly the entries a test needs. */
public final class ExifFixtures {
    /** Bytes in one directory entry. */
    private static final int ENTRY_BYTES = 12;
    /** The IFD0 tag that points at the private directory. */
    private static final int TAG_EXIF_IFD_POINTER = 0x8769;
    /** ExifVersion, which every fixture's private directory carries first. */
    public static final int TAG_EXIF_VERSION = 0x9000;
    /** A tag number no edition of the standard defines, for what no library can know. */
    public static final int TAG_UNDEFINED_BY_ANY_STANDARD = 0xA4F0;
    /** The TIFF types the fixtures write. */
    private static final int TYPE_SHORT = 3;
    private static final int TYPE_LONG = 4;
    private static final int TYPE_UNDEFINED = 7;

    private ExifFixtures() {}

    /**
     * A camera's picture whose private directory carries a tag no standard defines, so large that
     * it fits the block as the camera wrote it but not once a capture has added its own tags. It
     * has no image to decode.
     */
    public static byte[] overfullCapture() {
        byte[] value = new byte[65000];
        Arrays.fill(value, (byte) 'x');
        return exifJpeg(true,
                new int[] {TAG_UNDEFINED_BY_ANY_STANDARD, TYPE_UNDEFINED, value.length}, value);
    }

    /**
     * An Exif JPEG with an IFD0 pointing at a private directory that carries ExifVersion and then
     * any further entries, given as pairs of {tag, type, count} and big-endian value bytes, in
     * ascending tag order. A value of more than four bytes follows the directory; an empty one
     * leaves the entry pointing past the end of the block. It has no image to decode.
     */
    public static byte[] exifJpeg(boolean little, Object... extra) {
        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        writeShort(tiff, little ? 0x4949 : 0x4D4D, little);
        writeShort(tiff, 42, little);
        writeInt(tiff, 8, little);

        // IFD0: orientation, then the pointer to the private directory.
        int ifd0 = 8;
        int ifd0Size = 2 + 2 * ENTRY_BYTES + 4;
        int subIfd = ifd0 + ifd0Size;
        writeShort(tiff, 2, little);
        writeEntry(tiff, 0x0112, TYPE_SHORT, 1, 1, little);
        writeEntry(tiff, TAG_EXIF_IFD_POINTER, TYPE_LONG, 1, subIfd, little);
        writeInt(tiff, 0, little);

        // The private directory, carrying one tag so replacement differs from addition.
        int entries = 1 + extra.length / 2;
        writeShort(tiff, entries, little);
        writeEntry(tiff, TAG_EXIF_VERSION, 7, 4, 0x32333230, little);
        ByteArrayOutputStream values = new ByteArrayOutputStream();
        int valuesAt = subIfd + 2 + entries * ENTRY_BYTES + 4;
        for (int i = 0; i < extra.length; i += 2) {
            int[] entry = (int[]) extra[i];
            byte[] bytes = inOrder((byte[]) extra[i + 1], entry[1], little);
            writeShort(tiff, entry[0], little);
            writeShort(tiff, entry[1], little);
            writeInt(tiff, entry[2], little);
            if (bytes.length == 0) {
                writeInt(tiff, 0x7FFF, little);
            } else if (bytes.length <= 4) {
                tiff.write(bytes, 0, bytes.length);
                for (int pad = bytes.length; pad < 4; pad++) tiff.write(0);
            } else {
                writeInt(tiff, valuesAt + values.size(), little);
                values.write(bytes, 0, bytes.length);
            }
        }
        writeInt(tiff, 0, little);
        byte[] valueBytes = values.toByteArray();
        tiff.write(valueBytes, 0, valueBytes.length);

        byte[] tiffBytes = tiff.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8);
        out.write(0xFF);
        out.write(0xE1);
        int payload = 6 + tiffBytes.length;
        out.write(((payload + 2) >> 8) & 0xFF);
        out.write((payload + 2) & 0xFF);
        for (byte b : new byte[] {0x45, 0x78, 0x69, 0x66, 0x00, 0x00}) {
            out.write(b);
        }
        out.write(tiffBytes, 0, tiffBytes.length);
        out.write(0xFF);
        out.write(0xD9);
        return out.toByteArray();
    }

    /** The tags of a file's private directory, in the order they are stored. */
    public static int[] privateTags(byte[] jpeg) {
        ExifSegment.Segment segment = ExifSegment.find(jpeg);
        if (segment == null) return new int[0];
        int ifd = ExifSegment.exifIfd(jpeg, segment);
        int count = ExifSegment.entryCount(jpeg, segment, ifd);
        if (count < 0) return new int[0];
        int[] tags = new int[count];
        for (int i = 0; i < count; i++) {
            tags[i] = ExifSegment.u16(jpeg, ifd + 2 + i * ENTRY_BYTES, segment.littleEndian);
        }
        return tags;
    }

    /** Big-endian value bytes in the fixture's order: each short or long turned around. */
    private static byte[] inOrder(byte[] bigEndian, int type, boolean little) {
        byte[] bytes = bigEndian.clone();
        int unit = type == TYPE_SHORT ? 2 : type == TYPE_LONG ? 4 : 1;
        if (little) {
            for (int start = 0; start + unit <= bytes.length; start += unit) {
                for (int i = start, j = start + unit - 1; i < j; i++, j--) {
                    byte swap = bytes[i];
                    bytes[i] = bytes[j];
                    bytes[j] = swap;
                }
            }
        }
        return bytes;
    }

    private static void writeEntry(ByteArrayOutputStream out, int tag, int type, int count,
            int value, boolean little) {
        writeShort(out, tag, little);
        writeShort(out, type, little);
        writeInt(out, count, little);
        if (type == TYPE_SHORT) {
            writeShort(out, value, little);
            writeShort(out, 0, little);
        } else {
            writeInt(out, value, little);
        }
    }

    private static void writeShort(ByteArrayOutputStream out, int value, boolean little) {
        if (little) {
            out.write(value & 0xFF);
            out.write((value >> 8) & 0xFF);
        } else {
            out.write((value >> 8) & 0xFF);
            out.write(value & 0xFF);
        }
    }

    private static void writeInt(ByteArrayOutputStream out, int value, boolean little) {
        if (little) {
            out.write(value & 0xFF);
            out.write((value >> 8) & 0xFF);
            out.write((value >> 16) & 0xFF);
            out.write((value >> 24) & 0xFF);
        } else {
            out.write((value >> 24) & 0xFF);
            out.write((value >> 16) & 0xFF);
            out.write((value >> 8) & 0xFF);
            out.write(value & 0xFF);
        }
    }
}
