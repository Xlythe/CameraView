package com.xlythe.view.camera;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

/**
 * Finds the Exif block inside a JPEG, and reads, appends to and repoints its directories, in the
 * block's own byte order. The thumbnail writer and {@link ExifTags} share it.
 *
 * <p>Every offset in the block is measured from the TIFF header and points backwards from its
 * end, so a directory appended past the last byte invalidates nothing, and only the pointer that
 * should lead to it changes.</p>
 */
final class ExifSegment {
    /** JPEG markers, which all begin with this byte. */
    private static final int MARKER = 0xFF;
    private static final int SOI = 0xD8;
    private static final int APP1 = 0xE1;
    private static final int SOS = 0xDA;

    /** "Exif\0\0", which distinguishes an Exif APP1 from an XMP one. */
    private static final byte[] EXIF_IDENTIFIER = {0x45, 0x78, 0x69, 0x66, 0x00, 0x00};

    private static final int BYTE_ORDER_LITTLE = 0x4949;
    private static final int BYTE_ORDER_BIG = 0x4D4D;
    private static final int TIFF_MAGIC = 42;

    /** The most an APP1 segment may hold, excluding the two length bytes themselves. */
    static final int MAX_APP1_PAYLOAD = 0xFFFF - 2;

    /** Bytes in one directory entry: tag, type, count, and a value or an offset to one. */
    static final int ENTRY_BYTES = 12;

    /** The IFD0 tag whose value is the offset of the Exif private directory. */
    static final int TAG_EXIF_IFD_POINTER = 0x8769;

    /** The TIFF field types {@link #retypeAsUndefined} converts between. */
    private static final int TYPE_ASCII = 2;
    private static final int TYPE_UNDEFINED = 7;

    private ExifSegment() {}

    /** Where a JPEG's Exif APP1 segment is, and where the TIFF block inside it begins. */
    static final class Segment {
        /** Offset of the two length bytes, which cover themselves and the payload. */
        final int lengthOffset;
        /** Offset of the TIFF header, from which every Exif offset is measured. */
        final int tiffOffset;
        /** Length of the segment's payload, excluding the two length bytes. */
        final int payloadLength;
        /** True when the TIFF block is little-endian. */
        final boolean littleEndian;

        Segment(int lengthOffset, int tiffOffset, int payloadLength, boolean littleEndian) {
            this.lengthOffset = lengthOffset;
            this.tiffOffset = tiffOffset;
            this.payloadLength = payloadLength;
            this.littleEndian = littleEndian;
        }

        /** One past the last byte of this segment, which is where new bytes go. */
        int end() {
            return lengthOffset + 2 + payloadLength;
        }

        /** How many bytes may still be added before the segment outgrows its length field. */
        int room() {
            return MAX_APP1_PAYLOAD - payloadLength;
        }
    }

    /**
     * Finds the Exif APP1 segment by walking the marker chain, since "Exif\0\0" can also occur
     * inside compressed image data.
     *
     * @return the segment, or null when this is not a JPEG or carries no Exif
     */
    @Nullable
    @VisibleForTesting
    static Segment find(@NonNull byte[] jpeg) {
        if (jpeg.length < 4 || !isJpeg(jpeg)) {
            return null;
        }
        int offset = 2;
        while (offset + 4 <= jpeg.length) {
            if (u8(jpeg, offset) != MARKER) {
                return null;
            }
            int marker = u8(jpeg, offset + 1);
            // Compressed data begins here and markers stop being a reliable chain.
            if (marker == SOS) {
                return null;
            }
            int lengthOffset = offset + 2;
            int length = u16Big(jpeg, lengthOffset);
            if (length < 2 || lengthOffset + length > jpeg.length) {
                return null;
            }
            int payload = lengthOffset + 2;
            if (marker == APP1 && startsWith(jpeg, payload, EXIF_IDENTIFIER)) {
                int tiff = payload + EXIF_IDENTIFIER.length;
                if (tiff + 8 > jpeg.length) {
                    return null;
                }
                int order = u16Big(jpeg, tiff);
                boolean little = order == BYTE_ORDER_LITTLE;
                if (!little && order != BYTE_ORDER_BIG) {
                    return null;
                }
                if (u16(jpeg, tiff + 2, little) != TIFF_MAGIC) {
                    return null;
                }
                return new Segment(lengthOffset, tiff, length - 2, little);
            }
            offset = lengthOffset + length;
        }
        return null;
    }

    /**
     * Finds the Exif block to read: a JPEG's, as {@link #find} does, or a bare TIFF block's own,
     * as other formats keep their Exif.
     *
     * @return the block, or null when the data is neither
     */
    @Nullable
    static Segment findForReading(@NonNull byte[] data) {
        Segment segment = find(data);
        if (segment != null || data.length < 8) {
            return segment;
        }
        int order = u16Big(data, 0);
        boolean little = order == BYTE_ORDER_LITTLE;
        if ((!little && order != BYTE_ORDER_BIG) || u16(data, 2, little) != TIFF_MAGIC) {
            return null;
        }
        // No segment around it, so it ends where the data does.
        return new Segment(-2, 0, data.length, little);
    }

    /** Whether the data begins as a JPEG does, with a start-of-image marker. */
    static boolean isJpeg(@NonNull byte[] data) {
        return data.length >= 2 && u8(data, 0) == MARKER && u8(data, 1) == SOI;
    }

    /** The absolute offset of IFD0, or -1 if the header does not describe one. */
    static int ifd0(@NonNull byte[] jpeg, @NonNull Segment segment) {
        int at = segment.tiffOffset + (int) u32(jpeg, segment.tiffOffset + 4, segment.littleEndian);
        return at + 2 > jpeg.length ? -1 : at;
    }

    /** The number of entries in a directory, or -1 if the directory does not fit in the file. */
    static int entryCount(@NonNull byte[] jpeg, @NonNull Segment segment, int ifd) {
        if (ifd < 0 || ifd + 2 > jpeg.length) {
            return -1;
        }
        return u16(jpeg, ifd, segment.littleEndian);
    }

    /** The offset of the four-byte pointer that follows a directory's entries. */
    static int nextPointerOffset(@NonNull byte[] jpeg, @NonNull Segment segment, int ifd) {
        if (ifd < 0 || ifd + 2 > jpeg.length) {
            return -1;
        }
        int count = u16(jpeg, ifd, segment.littleEndian);
        int pointer = ifd + 2 + count * ENTRY_BYTES;
        return pointer + 4 > jpeg.length ? -1 : pointer;
    }

    /**
     * The offset of an entry for the given tag within a directory, or -1.
     *
     * @param ifd the absolute offset of the directory
     */
    static int findEntry(@NonNull byte[] jpeg, @NonNull Segment segment, int ifd, int tag) {
        if (ifd < 0 || ifd + 2 > jpeg.length) {
            return -1;
        }
        int count = u16(jpeg, ifd, segment.littleEndian);
        if (ifd + 2 + count * ENTRY_BYTES + 4 > jpeg.length) {
            return -1;
        }
        for (int i = 0; i < count; i++) {
            int entry = ifd + 2 + i * ENTRY_BYTES;
            if (u16(jpeg, entry, segment.littleEndian) == tag) {
                return entry;
            }
        }
        return -1;
    }

    /** The absolute offset of the Exif private directory, or -1 when IFD0 names none. */
    static int exifIfd(@NonNull byte[] jpeg, @NonNull Segment segment) {
        int entry = findEntry(jpeg, segment, ifd0(jpeg, segment), TAG_EXIF_IFD_POINTER);
        if (entry < 0) {
            return -1;
        }
        int at = segment.tiffOffset + (int) u32(jpeg, entry + 8, segment.littleEndian);
        return at + 2 > jpeg.length ? -1 : at;
    }

    /**
     * Turns an Exif-directory entry written as ASCII text back into the UNDEFINED bytes the
     * standard defines it as, in place: its type, its count without the text's terminator, and
     * its bytes moved into the entry, where four or fewer belong.
     *
     * @param count how many bytes the standard gives the tag, at most four
     * @return the offset of the entry if it changed, or -1 when it is absent or already right
     */
    static int retypeAsUndefined(@NonNull byte[] jpeg, @NonNull Segment segment, int tag,
            int count) {
        int entry = findEntry(jpeg, segment, exifIfd(jpeg, segment), tag);
        if (entry < 0 || u16(jpeg, entry + 2, segment.littleEndian) != TYPE_ASCII
                || u32(jpeg, entry + 4, segment.littleEndian) != count + 1) {
            return -1;
        }
        // The text and its terminator fit in the entry when they come to four bytes or fewer.
        int at = count + 1 <= 4
                ? entry + 8
                : segment.tiffOffset + (int) u32(jpeg, entry + 8, segment.littleEndian);
        if (at < 0 || at + count > jpeg.length) {
            return -1;
        }
        byte[] value = new byte[4];
        System.arraycopy(jpeg, at, value, 0, count);
        putU16(jpeg, entry + 2, TYPE_UNDEFINED, segment.littleEndian);
        putU32(jpeg, entry + 4, count, segment.littleEndian);
        System.arraycopy(value, 0, jpeg, entry + 8, value.length);
        return entry;
    }

    /**
     * Grows the segment by inserting bytes at its end and repointing a four-byte field at them.
     *
     * @param addition the bytes to append, whose first byte lands at the end of the segment
     * @param pointerOffset the absolute offset of the field that should name the new bytes
     * @return the updated file, or null if it will not fit
     */
    @Nullable
    static byte[] append(@NonNull byte[] jpeg, @NonNull Segment segment, @NonNull byte[] addition,
            int pointerOffset) {
        if (addition.length > segment.room()) {
            return null;
        }
        int insertAt = segment.end();
        byte[] out = new byte[jpeg.length + addition.length];
        System.arraycopy(jpeg, 0, out, 0, insertAt);
        System.arraycopy(addition, 0, out, insertAt, addition.length);
        System.arraycopy(jpeg, insertAt, out, insertAt + addition.length,
                jpeg.length - insertAt);
        putU32(out, pointerOffset, insertAt - segment.tiffOffset, segment.littleEndian);
        putU16Big(out, segment.lengthOffset, segment.payloadLength + addition.length + 2);
        return out;
    }

    private static boolean startsWith(@NonNull byte[] data, int at, @NonNull byte[] prefix) {
        if (at + prefix.length > data.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[at + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    static int u8(@NonNull byte[] data, int at) {
        return data[at] & 0xFF;
    }

    static int u16Big(@NonNull byte[] data, int at) {
        return (u8(data, at) << 8) | u8(data, at + 1);
    }

    static int u16(@NonNull byte[] data, int at, boolean little) {
        return little ? (u8(data, at + 1) << 8) | u8(data, at) : u16Big(data, at);
    }

    static long u32(@NonNull byte[] data, int at, boolean little) {
        if (little) {
            return ((long) u8(data, at + 3) << 24) | (u8(data, at + 2) << 16)
                    | (u8(data, at + 1) << 8) | u8(data, at);
        }
        return ((long) u8(data, at) << 24) | (u8(data, at + 1) << 16)
                | (u8(data, at + 2) << 8) | u8(data, at + 3);
    }

    static void putU16(@NonNull byte[] out, int at, int value, boolean little) {
        if (little) {
            out[at] = (byte) (value & 0xFF);
            out[at + 1] = (byte) ((value >> 8) & 0xFF);
        } else {
            putU16Big(out, at, value);
        }
    }

    static void putU16Big(@NonNull byte[] out, int at, int value) {
        out[at] = (byte) ((value >> 8) & 0xFF);
        out[at + 1] = (byte) (value & 0xFF);
    }

    static void putU32(@NonNull byte[] out, int at, int value, boolean little) {
        if (little) {
            out[at] = (byte) (value & 0xFF);
            out[at + 1] = (byte) ((value >> 8) & 0xFF);
            out[at + 2] = (byte) ((value >> 16) & 0xFF);
            out[at + 3] = (byte) ((value >> 24) & 0xFF);
        } else {
            out[at] = (byte) ((value >> 24) & 0xFF);
            out[at + 1] = (byte) ((value >> 16) & 0xFF);
            out[at + 2] = (byte) ((value >> 8) & 0xFF);
            out[at + 3] = (byte) (value & 0xFF);
        }
    }
}
