package com.xlythe.view.camera;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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

    /** The largest values a SHORT and a LONG hold, both being unsigned. */
    private static final int MAX_SHORT = 0xFFFF;
    private static final long MAX_LONG = 0xFFFFFFFFL;

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

        /** The tag's number. */
        private final int mId;
        /** The TIFF type the standard gives it, which a caller's text is encoded as. */
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

        /** The tag with the given number, or null when it is not one of these. */
        @Nullable
        static Tag forId(int id) {
            for (Tag tag : values()) {
                if (tag.mId == id) {
                    return tag;
                }
            }
            return null;
        }
    }

    /** The values to write, built by the caller and applied in one pass. */
    public static final class Builder {
        /** The changes in the order they were asked for, so a later one for a tag wins. */
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
        /** The tag to change. */
        final Tag tag;
        /** Its new value as the caller wrote it, or null to remove it. */
        @Nullable final String value;

        Entry(@NonNull Tag tag, @Nullable String value) {
            this.tag = tag;
            this.value = value;
        }
    }

    /**
     * A tag's value as a file stores it: its TIFF type, how many values it holds, and their
     * bytes. The bytes are kept big-endian whatever order the file uses, so a value can move into
     * a block of the other order, and the type is the one its writer chose, so a file's own value
     * is written back exactly as it was.
     */
    static final class Value {
        /** The TIFF field type. */
        final int type;
        /** How many values of that type, which for the byte-counted types is bytes. */
        final int count;
        /** The values themselves, big-endian: {@code count} times the type's size. */
        final byte[] bytes;

        Value(int type, int count, @NonNull byte[] bytes) {
            this.type = type;
            this.count = count;
            this.bytes = bytes;
        }

        /**
         * The value as text, in the shapes ExifInterface uses: a rational as
         * "numerator/denominator", a short or a long as a decimal, and anything else as the
         * string it holds.
         *
         * @return the text, or null for a type with none of those shapes
         */
        @Nullable
        String text() {
            return decode(bytes, type, count);
        }
    }

    /**
     * Reads every one of these tags that a JPEG's Exif private directory holds.
     *
     * @return the values by tag, which is empty when there are none or the file carries no Exif
     */
    @NonNull
    static Map<Tag, Value> readAll(@NonNull byte[] jpeg) {
        Map<Tag, Value> values = new EnumMap<>(Tag.class);
        ExifSegment.Segment segment = ExifSegment.find(jpeg);
        if (segment == null) {
            return values;
        }
        int ifd = ExifSegment.exifIfd(jpeg, segment);
        int count = ExifSegment.entryCount(jpeg, segment, ifd);
        if (count < 0 || ifd + 2 + count * ExifSegment.ENTRY_BYTES > segment.end()) {
            return values;
        }
        for (int i = 0; i < count; i++) {
            int entry = ifd + 2 + i * ExifSegment.ENTRY_BYTES;
            Tag tag = Tag.forId(ExifSegment.u16(jpeg, entry, segment.littleEndian));
            if (tag == null) {
                continue;
            }
            Value value = readValue(jpeg, segment, entry);
            if (value != null) {
                values.put(tag, value);
            }
        }
        return values;
    }

    /**
     * One entry's value.
     *
     * @return the value, or null when its type has no known size or it lies outside the block
     */
    @Nullable
    private static Value readValue(@NonNull byte[] jpeg, @NonNull ExifSegment.Segment segment,
            int entry) {
        boolean little = segment.littleEndian;
        int type = ExifSegment.u16(jpeg, entry + 2, little);
        long count = ExifSegment.u32(jpeg, entry + 4, little);
        long size = sizeOf(type) * count;
        if (size <= 0 || size > ExifSegment.MAX_APP1_PAYLOAD) {
            return null;
        }
        // Four bytes or fewer live in the entry; anything longer is an offset to the real thing.
        long at = size <= 4
                ? entry + 8
                : segment.tiffOffset + ExifSegment.u32(jpeg, entry + 8, little);
        if (at < segment.tiffOffset || at + size > segment.end()) {
            return null;
        }
        byte[] bytes = Arrays.copyOfRange(jpeg, (int) at, (int) (at + size));
        if (little) {
            reverseEachValue(bytes, type);
        }
        return new Value(type, (int) count, bytes);
    }

    /**
     * Applies a builder's changes to a set of values: each tag set to its new value, or removed
     * when that is null. All or nothing, so one value that does not suit its tag, such as a word
     * for a number, changes none of them.
     *
     * @return false, with the values untouched, if any value cannot be encoded for its tag
     */
    static boolean apply(@NonNull Builder builder, @NonNull Map<Tag, Value> values) {
        List<Entry> entries = builder.entries();
        Value[] encoded = new Value[entries.size()];
        for (int i = 0; i < encoded.length; i++) {
            Entry entry = entries.get(i);
            if (entry.value != null) {
                encoded[i] = encode(entry.tag, entry.value);
                if (encoded[i] == null) {
                    return false;
                }
            }
        }
        for (int i = 0; i < encoded.length; i++) {
            if (encoded[i] == null) {
                values.remove(entries.get(i).tag);
            } else {
                values.put(entries.get(i).tag, encoded[i]);
            }
        }
        return true;
    }

    /**
     * Returns the JPEG with its Exif private directory holding exactly the given values of these
     * tags: each one in the map set, and any other of them removed. Every entry for a tag outside
     * this enum is kept as it was.
     *
     * @return the updated file, or null if it carries no Exif private directory or has no room
     *     left for the values
     */
    @Nullable
    static byte[] write(@NonNull byte[] jpeg, @NonNull Map<Tag, Value> values) {
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

        // Every entry that is staying, which is every one that is not one of these tags.
        List<int[]> kept = new ArrayList<>();
        for (int i = 0; i < existing; i++) {
            int entry = ifd + 2 + i * ExifSegment.ENTRY_BYTES;
            int tagId = ExifSegment.u16(jpeg, entry, segment.littleEndian);
            if (Tag.forId(tagId) == null) {
                kept.add(new int[] {tagId, entry});
            }
        }

        int total = kept.size() + values.size();
        int directoryBytes = 2 + total * ExifSegment.ENTRY_BYTES + 4;
        // Values longer than four bytes do not fit in their entry and follow the directory.
        int valueBytes = 0;
        for (Value value : values.values()) {
            if (value.bytes.length > 4) {
                valueBytes += value.bytes.length;
            }
        }
        byte[] addition = new byte[directoryBytes + valueBytes];

        // Ascending tag order, which TIFF requires of a directory.
        List<Object[]> rows = new ArrayList<>();
        for (int[] k : kept) {
            rows.add(new Object[] {k[0], k[1], null});
        }
        for (Map.Entry<Tag, Value> value : values.entrySet()) {
            rows.add(new Object[] {value.getKey().getId(), -1, value.getValue()});
        }
        Collections.sort(rows, new Comparator<Object[]>() {
            @Override public int compare(Object[] left, Object[] right) {
                return Integer.compare((Integer) left[0], (Integer) right[0]);
            }
        });

        boolean little = segment.littleEndian;
        int ifdStart = segment.end() - segment.tiffOffset;
        int valueAt = ifdStart + directoryBytes;
        int at = 0;
        ExifSegment.putU16(addition, at, total, little);
        at += 2;
        for (Object[] row : rows) {
            int sourceEntry = (Integer) row[1];
            if (sourceEntry >= 0) {
                // Copied verbatim: its value or offset is still correct, because nothing moved.
                System.arraycopy(jpeg, sourceEntry, addition, at, ExifSegment.ENTRY_BYTES);
                at += ExifSegment.ENTRY_BYTES;
                continue;
            }
            Value value = (Value) row[2];
            byte[] bytes = value.bytes.clone();
            if (little) {
                reverseEachValue(bytes, value.type);
            }
            ExifSegment.putU16(addition, at, (Integer) row[0], little);
            ExifSegment.putU16(addition, at + 2, value.type, little);
            ExifSegment.putU32(addition, at + 4, value.count, little);
            if (bytes.length <= 4) {
                System.arraycopy(bytes, 0, addition, at + 8, bytes.length);
            } else {
                ExifSegment.putU32(addition, at + 8, valueAt, little);
                System.arraycopy(bytes, 0, addition, valueAt - ifdStart, bytes.length);
                valueAt += bytes.length;
            }
            at += ExifSegment.ENTRY_BYTES;
        }
        // The private directory is the last in its chain.
        ExifSegment.putU32(addition, at, 0, little);

        return ExifSegment.append(jpeg, segment, addition, pointerEntry + 8);
    }

    private static int sizeOf(int type) {
        return type >= 0 && type < TYPE_SIZES.length ? TYPE_SIZES[type] : 0;
    }

    /**
     * Swaps each value's bytes between the two byte orders, in place. A rational is two numbers,
     * each swapped on its own; text and bytes have no order to swap.
     */
    private static void reverseEachValue(@NonNull byte[] bytes, int type) {
        int unit = type == TYPE_RATIONAL || type == TYPE_SRATIONAL ? 4 : sizeOf(type);
        if (unit <= 1) {
            return;
        }
        for (int start = 0; start + unit <= bytes.length; start += unit) {
            for (int i = start, j = start + unit - 1; i < j; i++, j--) {
                byte swap = bytes[i];
                bytes[i] = bytes[j];
                bytes[j] = swap;
            }
        }
    }

    /**
     * Turns the caller's text into the value the tag's type calls for.
     *
     * @return the value, or null when the text does not suit the type
     */
    @Nullable
    private static Value encode(@NonNull Tag tag, @NonNull String value) {
        int type = tag.getType();
        try {
            switch (type) {
                case TYPE_ASCII: {
                    byte[] text = value.getBytes("UTF-8");
                    // ASCII fields are NUL-terminated, and the terminator is part of the count.
                    byte[] out = new byte[text.length + 1];
                    System.arraycopy(text, 0, out, 0, text.length);
                    return new Value(type, out.length, out);
                }
                case TYPE_UNDEFINED: {
                    byte[] out = value.getBytes("UTF-8");
                    // A count of zero describes no value at all, which readers take for damage.
                    return out.length == 0 ? null : new Value(type, out.length, out);
                }
                case TYPE_SHORT: {
                    int number = Integer.parseInt(value.trim());
                    if (number < 0 || number > MAX_SHORT) {
                        return null;
                    }
                    byte[] out = new byte[2];
                    ExifSegment.putU16(out, 0, number, false);
                    return new Value(type, 1, out);
                }
                case TYPE_RATIONAL:
                case TYPE_SRATIONAL: {
                    byte[] out = rational(value, type == TYPE_SRATIONAL);
                    return out == null ? null : new Value(type, 1, out);
                }
                default:
                    return null;
            }
        } catch (NumberFormatException | UnsupportedEncodingException error) {
            // A caller who hands a word to a numeric tag gets nothing written rather than a
            // field full of rubbish that every reader afterwards has to cope with.
            return null;
        }
    }

    /**
     * "n/d" as written, or a decimal scaled to thousandths, as two big-endian numbers.
     *
     * @return the bytes, or null for a zero denominator or a number the type cannot hold
     */
    @Nullable
    private static byte[] rational(@NonNull String value, boolean signed) {
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
        if (denominator == 0 || !fits(numerator, signed) || !fits(denominator, signed)) {
            return null;
        }
        byte[] out = new byte[8];
        ExifSegment.putU32(out, 0, (int) numerator, false);
        ExifSegment.putU32(out, 4, (int) denominator, false);
        return out;
    }

    /** Whether a number fits the four bytes of one half of a rational, signed or not. */
    private static boolean fits(long number, boolean signed) {
        return signed
                ? number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE
                : number >= 0 && number <= MAX_LONG;
    }

    /** Big-endian bytes as text, in the same shapes ExifInterface hands back. */
    @Nullable
    private static String decode(@NonNull byte[] bytes, int type, int count) {
        try {
            switch (type) {
                case TYPE_ASCII: {
                    int end = 0;
                    while (end < count && bytes[end] != 0) {
                        end++;
                    }
                    return new String(bytes, 0, end, "UTF-8");
                }
                case TYPE_UNDEFINED:
                case TYPE_BYTE:
                    return new String(bytes, 0, count, "UTF-8");
                case TYPE_SHORT:
                    return Integer.toString(ExifSegment.u16(bytes, 0, false));
                case TYPE_LONG:
                    return Long.toString(ExifSegment.u32(bytes, 0, false));
                case TYPE_RATIONAL:
                case TYPE_SRATIONAL: {
                    long numerator = ExifSegment.u32(bytes, 0, false);
                    long denominator = ExifSegment.u32(bytes, 4, false);
                    if (type == TYPE_SRATIONAL) {
                        numerator = (int) numerator;
                        denominator = (int) denominator;
                    }
                    return numerator + "/" + denominator;
                }
                default:
                    return null;
            }
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
