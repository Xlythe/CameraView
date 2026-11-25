package com.xlythe.view.camera;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

/**
 * Writes the thumbnail that Exif calls IFD1, which no Android API will write for you.
 *
 * <p>Two of the three backends produce photographs with no thumbnail at all - measured, not
 * assumed - so a gallery listing them has to decode a multi-megapixel JPEG to draw a postage
 * stamp. The old camera API happens to write one; camera2 and CameraX do not, and
 * {@code androidx.exifinterface} can read a thumbnail, report its range and decode it to a
 * bitmap, but has no setter of any kind. Hence this.</p>
 *
 * <h2>What it does</h2>
 *
 * <p>Exif keeps the thumbnail in a second image file directory chained off the first: IFD0 ends
 * with a four-byte offset to the next IFD, and IFD1's entries describe a small JPEG stored
 * further along the same APP1 segment. Rather than rebuild that segment - which would mean
 * recomputing every offset androidx just wrote - this appends IFD1 and the thumbnail after
 * everything already there and repoints IFD0's "next" field at it. Appending cannot invalidate an
 * offset, because every existing offset points backwards from the new bytes.</p>
 *
 * <h2>What it will not do</h2>
 *
 * <p>An APP1 segment is at most 65,533 bytes, the whole of Exif included, and that is the real
 * constraint on a thumbnail rather than any aesthetic one. A thumbnail that will not fit is
 * refused rather than truncated, and refused is the right answer: a photograph with no thumbnail
 * displays slowly, and a photograph with a corrupt APP1 segment does not display at all.</p>
 */
final class ExifThumbnail {
    /** JPEG markers, which all begin with this byte. */

    /** "Exif\0\0", which distinguishes an Exif APP1 from an XMP one. */


    /** The most an APP1 segment may hold, excluding the two length bytes themselves. */
    @VisibleForTesting 

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
     * How many bytes a thumbnail may occupy in this file, or -1 if it can take none.
     *
     * <p>Worth asking before encoding rather than after: the segment holds the whole of Exif,
     * and a maker note can be tens of kilobytes on its own, so the room left over varies by
     * device and by shot. A caller that encodes first and asks later throws the work away.</p>
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
        int ifd0 = segment.tiffOffset + (int) ExifSegment.u32(jpeg, segment.tiffOffset + 4, segment.littleEndian);
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
     * A thumbnail already in the file, and whether anything says what it is.
     *
     * <p>The interesting case, and the common one: a camera writes a perfectly good thumbnail,
     * the Exif block is rewritten by something that does not carry Compression over, and what is
     * left is fifteen kilobytes of JPEG no reader will recognise because nothing declares the
     * format. Measured on a Pixel 7 Pro, where IFD1 came back holding JPEGInterchangeFormat and
     * its length, the two resolutions, YCbCrPositioning, ResolutionUnit and DateTime - and no
     * Compression at all.</p>
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
    static Existing findExistingThumbnail(@NonNull byte[] jpeg, @NonNull ExifSegment.Segment segment) {
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
                declared = ExifSegment.u16(jpeg, entry + 8, segment.littleEndian) == COMPRESSION_JPEG;
            }
        }
        if (offset <= 0 || length <= 0 || segment.tiffOffset + offset + length > jpeg.length) {
            return null;
        }
        return new Existing(offset, length, declared);
    }

    /**
     * Declares a thumbnail that is already in the file but says nothing about itself.
     *
     * <p>Writes a fresh IFD1 beside the existing one, naming the same bytes and adding the
     * Compression the old directory lacked, then points IFD0 at the new one. The old directory
     * stays where it is: a few dozen bytes nothing refers to, which costs less than rewriting
     * the segment and recomputing every offset in it. The thumbnail itself is neither moved nor
     * re-encoded, so what the camera produced is what survives.</p>
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
     * Returns the JPEG with the given thumbnail attached, or null when it cannot be attached.
     *
     * <p>Null rather than an exception, and null for every ordinary reason: the file is not a
     * JPEG, carries no Exif to extend, already has a thumbnail, or has no room for one. A
     * thumbnail is a convenience, and none of those is worth failing a capture over.</p>
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
     * Appends an IFD1 and points IFD0 at it.
     *
     * <p>Appending rather than rewriting is the whole trick: every offset already in the segment
     * points backwards from the new bytes, so not one of them has to be recomputed.</p>
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

        // Seven, and counted rather than assumed: a directory whose count is short by one
        // puts its next-IFD pointer on top of its own last entry, which is how the thumbnail
        // length went missing the first time this was written.
        //
        // Seven rather than nine because the specification forbids two of the obvious ones.
        // CIPA DC-008 Table 21 marks ImageWidth and ImageLength "J" for a compressed thumbnail -
        // not allowed, because the dimensions are already in the thumbnail's own JPEG marker and
        // a second copy is a second opinion. Of what remains, Compression,
        // JPEGInterchangeFormat and JPEGInterchangeFormatLength are mandatory and the rest are
        // optional.
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
     * Writes one 12-byte directory entry.
     *
     * <p>A value of four bytes or fewer lives in the entry itself; anything larger is an offset
     * to where it really is. Everything written here is a SHORT, a LONG or an offset to a
     * RATIONAL, so the value always fits and is always written as the four-byte field - a SHORT
     * occupying the first two of them, which is what the format requires and the easiest thing
     * to get wrong.</p>
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
