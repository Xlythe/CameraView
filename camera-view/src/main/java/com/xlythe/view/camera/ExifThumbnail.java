package com.xlythe.view.camera;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

/**
 * Writes the thumbnail Exif keeps in IFD1, which camera2 and CameraX leave out and androidx's
 * ExifInterface cannot write.
 *
 * <p>IFD1 and the thumbnail are appended to the end of the APP1 segment and IFD0's next-directory
 * pointer is aimed at them, so no existing offset moves. A thumbnail that would take the segment
 * past its 64KB limit is refused rather than truncated.</p>
 */
final class ExifThumbnail {
    private static final int TAG_COMPRESSION = 0x0103;
    private static final int TAG_ORIENTATION = 0x0112;
    private static final int TAG_X_RESOLUTION = 0x011A;
    private static final int TAG_Y_RESOLUTION = 0x011B;
    private static final int TAG_RESOLUTION_UNIT = 0x0128;
    private static final int TAG_JPEG_INTERCHANGE_FORMAT = 0x0201;
    private static final int TAG_JPEG_INTERCHANGE_FORMAT_LENGTH = 0x0202;

    private static final int TYPE_SHORT = 3;
    private static final int TYPE_LONG = 4;
    private static final int TYPE_RATIONAL = 5;

    private static final int COMPRESSION_JPEG = 6;
    private static final int RESOLUTION_UNIT_INCHES = 2;
    private static final int ENTRY_BYTES = 12;

    /** Directory, next pointer and the two resolution rationals, before any thumbnail. */
    @VisibleForTesting static final int DIRECTORY_OVERHEAD = 2 + 7 * ENTRY_BYTES + 4 + 16;

    private ExifThumbnail() {}

    /**
     * How many bytes a thumbnail may occupy in this file, or -1 if it can take none. Worth asking
     * before encoding one, since a maker note can leave little room.
     */
    static int roomForThumbnail(@NonNull byte[] jpeg) {
        ExifSegment.Segment segment = ExifSegment.find(jpeg);
        if (segment == null) {
            return -1;
        }
        int pointerOffset = nextIfdPointerOffset(jpeg, segment);
        if (pointerOffset < 0 || ExifSegment.u32(jpeg, pointerOffset, segment.littleEndian) != 0) {
            return -1;
        }
        int room = ExifSegment.MAX_APP1_PAYLOAD - segment.payloadLength - DIRECTORY_OVERHEAD;
        return room > 0 ? room : -1;
    }



    /**
     * Where IFD0's "next directory" pointer lives, which is the field IFD1 hangs from.
     *
     * @return the absolute offset of the four-byte pointer, or -1 if the directory is malformed
     */
    @VisibleForTesting
    static int nextIfdPointerOffset(@NonNull byte[] jpeg, @NonNull ExifSegment.Segment segment) {
        int ifd0 = segment.tiffOffset
                + (int) ExifSegment.u32(jpeg, segment.tiffOffset + 4, segment.littleEndian);
        if (ifd0 + 2 > jpeg.length) {
            return -1;
        }
        int count = ExifSegment.u16(jpeg, ifd0, segment.littleEndian);
        int pointer = ifd0 + 2 + count * ENTRY_BYTES;
        if (pointer + 4 > jpeg.length) {
            return -1;
        }
        return pointer;
    }

    /**
     * A thumbnail already in the file, and whether anything says what it is. Some pipelines
     * rewrite IFD1 without its Compression tag, as a Pixel 7 Pro does, leaving a thumbnail no
     * reader recognises.
     */
    @VisibleForTesting
    static final class Existing {
        final int offset;
        final int length;
        final boolean declared;

        Existing(int offset, int length, boolean declared) {
            this.offset = offset;
            this.length = length;
            this.declared = declared;
        }
    }

    /**
     * Finds a thumbnail that IFD1 already points at.
     *
     * @return what is there, or null when no second directory exists or it names no thumbnail
     */
    @Nullable
    @VisibleForTesting
    static Existing findExistingThumbnail(
            @NonNull byte[] jpeg, @NonNull ExifSegment.Segment segment) {
        int pointerOffset = nextIfdPointerOffset(jpeg, segment);
        if (pointerOffset < 0) {
            return null;
        }
        long ifd1Offset = ExifSegment.u32(jpeg, pointerOffset, segment.littleEndian);
        if (ifd1Offset == 0) {
            return null;
        }
        int ifd1 = segment.tiffOffset + (int) ifd1Offset;
        if (ifd1 + 2 > jpeg.length) {
            return null;
        }
        int count = ExifSegment.u16(jpeg, ifd1, segment.littleEndian);
        if (ifd1 + 2 + count * ENTRY_BYTES + 4 > jpeg.length) {
            return null;
        }
        int offset = 0;
        int length = 0;
        boolean declared = false;
        for (int i = 0; i < count; i++) {
            int entry = ifd1 + 2 + i * ENTRY_BYTES;
            int tag = ExifSegment.u16(jpeg, entry, segment.littleEndian);
            if (tag == TAG_JPEG_INTERCHANGE_FORMAT) {
                offset = (int) ExifSegment.u32(jpeg, entry + 8, segment.littleEndian);
            } else if (tag == TAG_JPEG_INTERCHANGE_FORMAT_LENGTH) {
                length = (int) ExifSegment.u32(jpeg, entry + 8, segment.littleEndian);
            } else if (tag == TAG_COMPRESSION) {
                declared = ExifSegment.u16(jpeg, entry + 8, segment.littleEndian)
                        == COMPRESSION_JPEG;
            }
        }
        if (offset <= 0 || length <= 0 || segment.tiffOffset + offset + length > jpeg.length) {
            return null;
        }
        return new Existing(offset, length, declared);
    }

    /**
     * Declares a thumbnail that is already in the file but says nothing about itself: a fresh
     * IFD1 names the same bytes with the Compression the old one lacked, and the old directory is
     * left orphaned rather than the segment rewritten.
     *
     * @return the updated file, or null if there is nothing to repair or no room to do it
     */
    @Nullable
    static byte[] declareExistingThumbnail(@NonNull byte[] jpeg, int orientation) {
        ExifSegment.Segment segment = ExifSegment.find(jpeg);
        if (segment == null) {
            return null;
        }
        Existing existing = findExistingThumbnail(jpeg, segment);
        if (existing == null || existing.declared) {
            return null;
        }
        return append(jpeg, segment, null, existing.offset, existing.length, orientation);
    }

    /**
     * Returns the JPEG with the given thumbnail attached, or null when it cannot be: it is not a
     * JPEG, has no Exif, already has a thumbnail, or has no room for one.
     *
     * @param jpeg the whole file
     * @param thumbnail a complete JPEG, small enough to leave the segment under its limit
     * @param orientation the Exif orientation the thumbnail shares with the full image
     */
    @Nullable
    static byte[] attach(@NonNull byte[] jpeg, @NonNull byte[] thumbnail, int orientation) {
        ExifSegment.Segment segment = ExifSegment.find(jpeg);
        if (segment == null) {
            return null;
        }
        return append(jpeg, segment, thumbnail, 0, thumbnail.length, orientation);
    }

    /**
     * Appends an IFD1 and points IFD0 at it. Every existing offset points before the new bytes,
     * so none of them changes.
     *
     * @param thumbnail bytes to append, or null to name bytes already in the file
     * @param existingOffset where those bytes already are, used when thumbnail is null
     */
    @Nullable
    private static byte[] append(@NonNull byte[] jpeg, @NonNull ExifSegment.Segment segment,
            @Nullable byte[] thumbnail, int existingOffset, int thumbnailLength,
            int orientation) {
        int pointerOffset = nextIfdPointerOffset(jpeg, segment);
        if (pointerOffset < 0) {
            return null;
        }

        // Compression, JPEGInterchangeFormat and its length are required, and ImageWidth and
        // ImageLength are forbidden for a compressed thumbnail (CIPA DC-008 Table 21).
        int entries = 7;
        // Each rational is eight bytes held outside the entry, since only four fit inside one.
        int directoryBytes = 2 + entries * ENTRY_BYTES + 4;
        int rationalBytes = 16;
        int added = directoryBytes + rationalBytes + (thumbnail != null ? thumbnail.length : 0);
        if (segment.payloadLength + added > ExifSegment.MAX_APP1_PAYLOAD) {
            return null;
        }

        int insertAt = segment.end();
        byte[] out = new byte[jpeg.length + added];
        System.arraycopy(jpeg, 0, out, 0, insertAt);
        System.arraycopy(jpeg, insertAt, out, insertAt + added, jpeg.length - insertAt);

        boolean little = segment.littleEndian;
        int ifd1 = insertAt - segment.tiffOffset;
        int rationals = ifd1 + directoryBytes;
        int thumbAt = thumbnail != null ? rationals + rationalBytes : existingOffset;

        int at = insertAt;
        ExifSegment.putU16(out, at, entries, little);
        at += 2;
        at = putEntry(out, at, TAG_COMPRESSION, TYPE_SHORT, 1, COMPRESSION_JPEG, little);
        at = putEntry(out, at, TAG_ORIENTATION, TYPE_SHORT, 1, orientation, little);
        at = putEntry(out, at, TAG_X_RESOLUTION, TYPE_RATIONAL, 1, rationals, little);
        at = putEntry(out, at, TAG_Y_RESOLUTION, TYPE_RATIONAL, 1, rationals + 8, little);
        at = putEntry(out, at, TAG_RESOLUTION_UNIT, TYPE_SHORT, 1, RESOLUTION_UNIT_INCHES, little);
        at = putEntry(out, at, TAG_JPEG_INTERCHANGE_FORMAT, TYPE_LONG, 1, thumbAt, little);
        at = putEntry(out, at, TAG_JPEG_INTERCHANGE_FORMAT_LENGTH, TYPE_LONG, 1,
                thumbnailLength, little);
        // IFD1 is the last directory, so its own next pointer is zero.
        ExifSegment.putU32(out, at, 0, little);

        int rationalAt = segment.tiffOffset + rationals;
        ExifSegment.putU32(out, rationalAt, 72, little);
        ExifSegment.putU32(out, rationalAt + 4, 1, little);
        ExifSegment.putU32(out, rationalAt + 8, 72, little);
        ExifSegment.putU32(out, rationalAt + 12, 1, little);

        if (thumbnail != null) {
            System.arraycopy(thumbnail, 0, out, segment.tiffOffset + thumbAt, thumbnail.length);
        }

        // IFD0 now leads to IFD1, and the segment is longer by everything just added.
        ExifSegment.putU32(out, pointerOffset, ifd1, little);
        ExifSegment.putU16Big(out, segment.lengthOffset, segment.payloadLength + added + 2);
        return out;
    }

    /**
     * Writes one 12-byte directory entry. Every value here is a SHORT, a LONG or an offset, so it
     * fits the four-byte field, a SHORT in its first two bytes.
     */
    private static int putEntry(@NonNull byte[] out, int at, int tag, int type, int count,
            int value, boolean little) {
        ExifSegment.putU16(out, at, tag, little);
        ExifSegment.putU16(out, at + 2, type, little);
        ExifSegment.putU32(out, at + 4, count, little);
        if (type == TYPE_SHORT) {
            ExifSegment.putU16(out, at + 8, value, little);
            ExifSegment.putU16(out, at + 10, 0, little);
        } else {
            ExifSegment.putU32(out, at + 8, value, little);
        }
        return at + ENTRY_BYTES;
    }








}
