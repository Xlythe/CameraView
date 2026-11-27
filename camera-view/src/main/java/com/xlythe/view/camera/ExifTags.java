package com.xlythe.view.camera;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The Exif tags {@code androidx.exifinterface} has no name for: the 23 of CIPA DC-008's tags it
 * lacks, most of them Exif 3.0 additions. Read and write them with {@link Exif#getTag} and
 * {@link Exif#setTags}; every other tag goes through {@link Exif#getAttribute} and
 * {@link Exif#setAttribute}.
 *
 * <p>A tag is written by appending a copy of the Exif private directory, with the new entries
 * in ascending tag order as TIFF requires, and pointing IFD0 at the copy. Nothing moves, so every
 * offset in the old entries still resolves.</p>
 */
public final class ExifTags {
    /** TIFF field types, as the standard numbers them. */
    private static final int TYPE_BYTE = 1;
    private static final int TYPE_ASCII = 2;
    private static final int TYPE_SHORT = 3;
    private static final int TYPE_LONG = 4;
    private static final int TYPE_RATIONAL = 5;
    private static final int TYPE_UNDEFINED = 7;
    private static final int TYPE_SRATIONAL = 10;

    /** How many bytes one value of each type occupies. */
    private static final int[] TYPE_SIZES = {0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8};

    /** Thousandths, which is finer than any of these readings and well inside a rational. */
    private static final long RATIONAL_SCALE = 1000;

    private ExifTags() {}

    /** A tag the standard defines and androidx does not, named as the standard names it. */
    public enum Tag {
        /** Whether the image may be used to train a model. Exif 3.0. */
        LEARNING_OPT_OUT_IN(0x9287, TYPE_UNDEFINED),
        /** Ambient temperature in degrees Celsius. */
        TEMPERATURE(0x9400, TYPE_SRATIONAL),
        /** Relative humidity as a percentage. */
        HUMIDITY(0x9401, TYPE_RATIONAL),
        /** Ambient pressure in hectopascals. */
        PRESSURE(0x9402, TYPE_RATIONAL),
        /** Depth below the surface in metres. */
        WATER_DEPTH(0x9403, TYPE_SRATIONAL),
        /** Acceleration in milligals. */
        ACCELERATION(0x9404, TYPE_RATIONAL),
        /** How far above or below the horizontal the camera was pointing, in degrees. */
        CAMERA_ELEVATION_ANGLE(0x9405, TYPE_SRATIONAL),
        /** Which development the image went through. Exif 3.0. */
        DEVELOPMENT_TYPE(0xA40D, TYPE_SHORT),
        /** A description of that development. Exif 3.0. */
        DEVELOPMENT_TYPE_DESCRIPTION(0xA40E, TYPE_ASCII),
        /** Whether distortion correction was applied. Exif 3.0. */
        DISTORTION_CORRECTION(0xA40F, TYPE_SHORT),
        /** Whether chromatic aberration correction was applied. Exif 3.0. */
        CHROMATIC_ABERRATION_CORRECTION(0xA410, TYPE_SHORT),
        /** Whether shading correction was applied. Exif 3.0. */
        SHADING_CORRECTION(0xA411, TYPE_SHORT),
        /** Whether noise reduction was applied. Exif 3.0. */
        NOISE_REDUCTION(0xA412, TYPE_SHORT),
        /** A title for the image. Exif 3.0. */
        IMAGE_TITLE(0xA436, TYPE_ASCII),
        /** Who took it. Exif 3.0. */
        PHOTOGRAPHER(0xA437, TYPE_ASCII),
        /** Who edited it. Exif 3.0. */
        IMAGE_EDITOR(0xA438, TYPE_ASCII),
        /** The camera's firmware version. Exif 3.0. */
        CAMERA_FIRMWARE(0xA439, TYPE_ASCII),
        /** What developed the raw file. Exif 3.0. */
        RAW_DEVELOPING_SOFTWARE(0xA43A, TYPE_ASCII),
        /** What edited the image. Exif 3.0. */
        IMAGE_EDITING_SOFTWARE(0xA43B, TYPE_ASCII),
        /** What edited the metadata. Exif 3.0. */
        METADATA_EDITING_SOFTWARE(0xA43C, TYPE_ASCII),
        /** Whether the image was composed from several exposures. Recommended by the standard. */
        COMPOSITE_IMAGE(0xA460, TYPE_SHORT),
        /** How many source images went into it. */
        SOURCE_IMAGE_NUMBER_OF_COMPOSITE_IMAGE(0xA461, TYPE_SHORT),
        /** Their exposure times. */
        SOURCE_EXPOSURE_TIMES_OF_COMPOSITE_IMAGE(0xA462, TYPE_UNDEFINED);

        private final int mId;
        private final int mType;

        Tag(int id, int type) {
            mId = id;
            mType = type;
        }

        /** The tag's number, as the standard assigns it. */
        public int getId() {
            return mId;
        }

        int getType() {
            return mType;
        }
    }

    /**
     * Reads a tag from a JPEG's Exif private directory, as text in the shapes ExifInterface uses:
     * a rational as "numerator/denominator", a short as a decimal.
     *
     * @return the value, or null when the tag is absent or the file carries no Exif
     */
    @Nullable
    static String read(@NonNull byte[] jpeg, @NonNull Tag tag) {
        ExifSegment.Segment segment = ExifSegment.find(jpeg);
        if (segment == null) {
            return null;
        }
        int ifd = ExifSegment.exifIfd(jpeg, segment);
        int entry = ExifSegment.findEntry(jpeg, segment, ifd, tag.getId());
        if (entry < 0) {
            return null;
        }
        int type = ExifSegment.u16(jpeg, entry + 2, segment.littleEndian);
        int count = (int) ExifSegment.u32(jpeg, entry + 4, segment.littleEndian);
        int size = sizeOf(type) * count;
        if (size <= 0) {
            return null;
        }
        // Four bytes or fewer live in the entry; anything longer is an offset to the real thing.
        int at = size <= 4
                ? entry + 8
                : segment.tiffOffset + (int) ExifSegment.u32(jpeg, entry + 8, segment.littleEndian);
        if (at < 0 || at + size > jpeg.length) {
            return null;
        }
        return decode(jpeg, at, type, count, segment.littleEndian);
    }

    /** The values to write, built by the caller and applied in one pass. */
    public static final class Builder {
        private final List<Entry> mEntries = new ArrayList<>();

        /** Sets a tag, or removes it when the value is null. */
        @NonNull
        public Builder set(@NonNull Tag tag, @Nullable String value) {
            mEntries.add(new Entry(tag, value));
            return this;
        }

        @NonNull
        List<Entry> entries() {
            return mEntries;
        }
    }

    /** One requested change. */
    static final class Entry {
        final Tag tag;
        @Nullable final String value;

        Entry(@NonNull Tag tag, @Nullable String value) {
            this.tag = tag;
            this.value = value;
        }
    }

    /**
     * Returns the JPEG with the given tags set, or null when they cannot be written.
     *
     * @return the updated file, or null if it carries no Exif or has no room left
     */
    @Nullable
    static byte[] write(@NonNull byte[] jpeg, @NonNull Builder builder) {
        List<Entry> wanted = builder.entries();
        if (wanted.isEmpty()) {
            return null;
        }
        ExifSegment.Segment segment = ExifSegment.find(jpeg);
        if (segment == null) {
            return null;
        }
        int ifd = ExifSegment.exifIfd(jpeg, segment);
        if (ifd < 0) {
            return null;
        }
        int pointerEntry = ExifSegment.findEntry(
                jpeg, segment, ExifSegment.ifd0(jpeg, segment), ExifSegment.TAG_EXIF_IFD_POINTER);
        if (pointerEntry < 0) {
            return null;
        }
        int existing = ExifSegment.entryCount(jpeg, segment, ifd);
        if (existing < 0
                || ifd + 2 + existing * ExifSegment.ENTRY_BYTES + 4 > jpeg.length) {
            return null;
        }

        // Every entry that is staying, keyed by tag so a new value replaces rather than joins.
        List<int[]> kept = new ArrayList<>();
        for (int i = 0; i < existing; i++) {
            int entry = ifd + 2 + i * ExifSegment.ENTRY_BYTES;
            int tagId = ExifSegment.u16(jpeg, entry, segment.littleEndian);
            if (!isRequested(wanted, tagId)) {
                kept.add(new int[] {tagId, entry});
            }
        }

        List<Entry> written = new ArrayList<>();
        for (Entry entry : wanted) {
            if (entry.value != null) {
                written.add(entry);
            }
        }

        int total = kept.size() + written.size();
        int directoryBytes = 2 + total * ExifSegment.ENTRY_BYTES + 4;
        // Values longer than four bytes do not fit in their entry and follow the directory.
        List<byte[]> encoded = new ArrayList<>();
        int valueBytes = 0;
        for (Entry entry : written) {
            byte[] bytes = encode(entry, segment.littleEndian);
            if (bytes == null) {
                return null;
            }
            encoded.add(bytes);
            if (bytes.length > 4) {
                valueBytes += bytes.length;
            }
        }
        byte[] addition = new byte[directoryBytes + valueBytes];

        // Ascending tag order, which TIFF requires of a directory.
        List<Object[]> rows = new ArrayList<>();
        for (int[] k : kept) {
            rows.add(new Object[] {k[0], k[1], null, null});
        }
        for (int i = 0; i < written.size(); i++) {
            rows.add(new Object[] {written.get(i).tag.getId(), -1, written.get(i), encoded.get(i)});
        }
        Collections.sort(rows, new Comparator<Object[]>() {
            @Override public int compare(Object[] left, Object[] right) {
                return Integer.compare((Integer) left[0], (Integer) right[0]);
            }
        });

        int ifdStart = segment.end() - segment.tiffOffset;
        int valueAt = ifdStart + directoryBytes;
        int at = 0;
        ExifSegment.putU16(addition, at, total, segment.littleEndian);
        at += 2;
        for (Object[] row : rows) {
            int sourceEntry = (Integer) row[1];
            if (sourceEntry >= 0) {
                // Copied verbatim: its value or offset is still correct, because nothing moved.
                System.arraycopy(jpeg, sourceEntry, addition, at, ExifSegment.ENTRY_BYTES);
                at += ExifSegment.ENTRY_BYTES;
                continue;
            }
            Entry entry = (Entry) row[2];
            byte[] bytes = (byte[]) row[3];
            ExifSegment.putU16(addition, at, entry.tag.getId(), segment.littleEndian);
            ExifSegment.putU16(addition, at + 2, entry.tag.getType(), segment.littleEndian);
            ExifSegment.putU32(addition, at + 4, countOf(entry, bytes), segment.littleEndian);
            if (bytes.length <= 4) {
                System.arraycopy(bytes, 0, addition, at + 8, bytes.length);
            } else {
                ExifSegment.putU32(addition, at + 8, valueAt, segment.littleEndian);
                System.arraycopy(bytes, 0, addition, valueAt - ifdStart, bytes.length);
                valueAt += bytes.length;
            }
            at += ExifSegment.ENTRY_BYTES;
        }
        // The private directory is the last in its chain.
        ExifSegment.putU32(addition, at, 0, segment.littleEndian);

        return ExifSegment.append(jpeg, segment, addition, pointerEntry + 8);
    }

    private static boolean isRequested(@NonNull List<Entry> wanted, int tagId) {
        for (Entry entry : wanted) {
            if (entry.tag.getId() == tagId) {
                return true;
            }
        }
        return false;
    }

    /** How many values the entry declares, which is bytes for the byte-counted types. */
    private static int countOf(@NonNull Entry entry, @NonNull byte[] bytes) {
        int type = entry.tag.getType();
        if (type == TYPE_ASCII || type == TYPE_UNDEFINED || type == TYPE_BYTE) {
            return bytes.length;
        }
        return bytes.length / sizeOf(type);
    }

    private static int sizeOf(int type) {
        return type >= 0 && type < TYPE_SIZES.length ? TYPE_SIZES[type] : 0;
    }

    /** Turns the caller's text into the bytes the tag's type calls for. */
    @Nullable
    private static byte[] encode(@NonNull Entry entry, boolean little) {
        String value = entry.value;
        if (value == null) {
            return null;
        }
        try {
            switch (entry.tag.getType()) {
                case TYPE_ASCII: {
                    byte[] text = value.getBytes("UTF-8");
                    // ASCII fields are NUL-terminated, and the terminator is part of the count.
                    byte[] out = new byte[text.length + 1];
                    System.arraycopy(text, 0, out, 0, text.length);
                    return out;
                }
                case TYPE_UNDEFINED:
                    return value.getBytes("UTF-8");
                case TYPE_SHORT: {
                    byte[] out = new byte[2];
                    ExifSegment.putU16(out, 0, Integer.parseInt(value.trim()), little);
                    return out;
                }
                case TYPE_LONG: {
                    byte[] out = new byte[4];
                    ExifSegment.putU32(out, 0, Integer.parseInt(value.trim()), little);
                    return out;
                }
                case TYPE_RATIONAL:
                case TYPE_SRATIONAL:
                    return rational(value, little);
                default:
                    return null;
            }
        } catch (NumberFormatException | UnsupportedEncodingException error) {
            // A caller who hands a word to a numeric tag gets nothing written rather than a
            // field full of rubbish that every reader afterwards has to cope with.
            return null;
        }
    }

    /** "n/d" as written, or a decimal scaled to thousandths. */
    @Nullable
    @VisibleForTesting
    static byte[] rational(@NonNull String value, boolean little) {
        long numerator;
        long denominator;
        int slash = value.indexOf('/');
        if (slash > 0) {
            numerator = Long.parseLong(value.substring(0, slash).trim());
            denominator = Long.parseLong(value.substring(slash + 1).trim());
        } else {
            numerator = Math.round(Double.parseDouble(value.trim()) * RATIONAL_SCALE);
            denominator = RATIONAL_SCALE;
        }
        if (denominator == 0) {
            return null;
        }
        byte[] out = new byte[8];
        ExifSegment.putU32(out, 0, (int) numerator, little);
        ExifSegment.putU32(out, 4, (int) denominator, little);
        return out;
    }

    /** The stored bytes as text, in the same shapes ExifInterface hands back. */
    @Nullable
    private static String decode(@NonNull byte[] jpeg, int at, int type, int count,
            boolean little) {
        switch (type) {
            case TYPE_ASCII: {
                int end = at;
                while (end < at + count && jpeg[end] != 0) {
                    end++;
                }
                try {
                    return new String(jpeg, at, end - at, "UTF-8");
                } catch (UnsupportedEncodingException impossible) {
                    throw new AssertionError(impossible);
                }
            }
            case TYPE_UNDEFINED:
            case TYPE_BYTE: {
                try {
                    return new String(jpeg, at, count, "UTF-8");
                } catch (UnsupportedEncodingException impossible) {
                    throw new AssertionError(impossible);
                }
            }
            case TYPE_SHORT:
                return Integer.toString(ExifSegment.u16(jpeg, at, little));
            case TYPE_LONG:
                return Long.toString(ExifSegment.u32(jpeg, at, little));
            case TYPE_RATIONAL:
            case TYPE_SRATIONAL: {
                long numerator = ExifSegment.u32(jpeg, at, little);
                long denominator = ExifSegment.u32(jpeg, at + 4, little);
                if (type == TYPE_SRATIONAL) {
                    numerator = (int) numerator;
                    denominator = (int) denominator;
                }
                return numerator + "/" + denominator;
            }
            default:
                return null;
        }
    }
}
