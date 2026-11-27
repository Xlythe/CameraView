package com.xlythe.view.camera;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The entries of a JPEG's Exif private directory, read and written by number as the bytes the
 * file stores. {@link Exif} keeps the ones ExifInterface drops when it saves, and those set by
 * number, through here.
 *
 * <p>Writing appends a copy of the directory, with the new entries in ascending tag order as TIFF
 * requires, and points IFD0 at the copy. Nothing moves, so every offset in the old entries still
 * resolves. Removing rewrites the directory where it is, which needs no room.</p>
 */
final class ExifTags {
    /** The entry that locates the Interoperability directory rather than holding a value. */
    static final int TAG_INTEROPERABILITY_POINTER = 0xA005;
    /** Ambient temperature, in degrees Celsius. */
    static final int TAG_TEMPERATURE = 0x9400;
    /** Relative humidity, as a percentage. */
    static final int TAG_HUMIDITY = 0x9401;
    /** Ambient pressure, in hectopascals. */
    static final int TAG_PRESSURE = 0x9402;
    /** Depth below the water's surface, in metres. */
    static final int TAG_WATER_DEPTH = 0x9403;
    /** Acceleration, in milligals. */
    static final int TAG_ACCELERATION = 0x9404;
    /** The camera's angle above the horizontal, in degrees. */
    static final int TAG_CAMERA_ELEVATION_ANGLE = 0x9405;
    /** The image's title. */
    static final int TAG_IMAGE_TITLE = 0xA436;
    /** Who took the picture. */
    static final int TAG_PHOTOGRAPHER = 0xA437;

    /** How many bytes one value of each TIFF type occupies, indexed by type. */
    private static final int[] TYPE_SIZES = {0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8};

    /** The largest values a SHORT and a LONG hold, both being unsigned. */
    private static final int MAX_SHORT = 0xFFFF;
    private static final long MAX_LONG = 0xFFFFFFFFL;

    /**
     * Denominators for a decimal written as a rational, finest first: thousandths, which is finer
     * than any reading these tags hold, then coarser ones for a number too big for thousandths.
     */
    private static final long[] RATIONAL_SCALES = {1000, 100, 10, 1};

    /** Text in these tags is UTF-8, which plain ASCII also is. */
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    /** The private directory tags ExifInterface reads and writes by name, by number. */
    private static final Map<Integer, String> ATTRIBUTES = new HashMap<>();

    /**
     * The private directory tags the standard names and ExifInterface doesn't, by name: each
     * one's number and the type the standard gives it.
     */
    private static final Map<String, int[]> STANDARD = new HashMap<>();

    static {
        // Every tag ExifInterface 1.4 lists for the private directory, but the pointer to the
        // Interoperability directory, which it rebuilds itself.
        attribute(0x829A, ExifInterface.TAG_EXPOSURE_TIME);
        attribute(0x829D, ExifInterface.TAG_F_NUMBER);
        attribute(0x8822, ExifInterface.TAG_EXPOSURE_PROGRAM);
        attribute(0x8824, ExifInterface.TAG_SPECTRAL_SENSITIVITY);
        attribute(0x8827, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY);
        attribute(0x8828, ExifInterface.TAG_OECF);
        attribute(0x8830, ExifInterface.TAG_SENSITIVITY_TYPE);
        attribute(0x8831, ExifInterface.TAG_STANDARD_OUTPUT_SENSITIVITY);
        attribute(0x8832, ExifInterface.TAG_RECOMMENDED_EXPOSURE_INDEX);
        attribute(0x8833, ExifInterface.TAG_ISO_SPEED);
        attribute(0x8834, ExifInterface.TAG_ISO_SPEED_LATITUDE_YYY);
        attribute(0x8835, ExifInterface.TAG_ISO_SPEED_LATITUDE_ZZZ);
        attribute(0x9000, ExifInterface.TAG_EXIF_VERSION);
        attribute(0x9003, ExifInterface.TAG_DATETIME_ORIGINAL);
        attribute(0x9004, ExifInterface.TAG_DATETIME_DIGITIZED);
        attribute(0x9010, ExifInterface.TAG_OFFSET_TIME);
        attribute(0x9011, ExifInterface.TAG_OFFSET_TIME_ORIGINAL);
        attribute(0x9012, ExifInterface.TAG_OFFSET_TIME_DIGITIZED);
        attribute(0x9101, ExifInterface.TAG_COMPONENTS_CONFIGURATION);
        attribute(0x9102, ExifInterface.TAG_COMPRESSED_BITS_PER_PIXEL);
        attribute(0x9201, ExifInterface.TAG_SHUTTER_SPEED_VALUE);
        attribute(0x9202, ExifInterface.TAG_APERTURE_VALUE);
        attribute(0x9203, ExifInterface.TAG_BRIGHTNESS_VALUE);
        attribute(0x9204, ExifInterface.TAG_EXPOSURE_BIAS_VALUE);
        attribute(0x9205, ExifInterface.TAG_MAX_APERTURE_VALUE);
        attribute(0x9206, ExifInterface.TAG_SUBJECT_DISTANCE);
        attribute(0x9207, ExifInterface.TAG_METERING_MODE);
        attribute(0x9208, ExifInterface.TAG_LIGHT_SOURCE);
        attribute(0x9209, ExifInterface.TAG_FLASH);
        attribute(0x920A, ExifInterface.TAG_FOCAL_LENGTH);
        attribute(0x9214, ExifInterface.TAG_SUBJECT_AREA);
        attribute(0x927C, ExifInterface.TAG_MAKER_NOTE);
        attribute(0x9286, ExifInterface.TAG_USER_COMMENT);
        attribute(0x9290, ExifInterface.TAG_SUBSEC_TIME);
        attribute(0x9291, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL);
        attribute(0x9292, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED);
        attribute(0xA000, ExifInterface.TAG_FLASHPIX_VERSION);
        attribute(0xA001, ExifInterface.TAG_COLOR_SPACE);
        attribute(0xA002, ExifInterface.TAG_PIXEL_X_DIMENSION);
        attribute(0xA003, ExifInterface.TAG_PIXEL_Y_DIMENSION);
        attribute(0xA004, ExifInterface.TAG_RELATED_SOUND_FILE);
        attribute(0xA20B, ExifInterface.TAG_FLASH_ENERGY);
        attribute(0xA20C, ExifInterface.TAG_SPATIAL_FREQUENCY_RESPONSE);
        attribute(0xA20E, ExifInterface.TAG_FOCAL_PLANE_X_RESOLUTION);
        attribute(0xA20F, ExifInterface.TAG_FOCAL_PLANE_Y_RESOLUTION);
        attribute(0xA210, ExifInterface.TAG_FOCAL_PLANE_RESOLUTION_UNIT);
        attribute(0xA214, ExifInterface.TAG_SUBJECT_LOCATION);
        attribute(0xA215, ExifInterface.TAG_EXPOSURE_INDEX);
        attribute(0xA217, ExifInterface.TAG_SENSING_METHOD);
        attribute(0xA300, ExifInterface.TAG_FILE_SOURCE);
        attribute(0xA301, ExifInterface.TAG_SCENE_TYPE);
        attribute(0xA302, ExifInterface.TAG_CFA_PATTERN);
        attribute(0xA401, ExifInterface.TAG_CUSTOM_RENDERED);
        attribute(0xA402, ExifInterface.TAG_EXPOSURE_MODE);
        attribute(0xA403, ExifInterface.TAG_WHITE_BALANCE);
        attribute(0xA404, ExifInterface.TAG_DIGITAL_ZOOM_RATIO);
        attribute(0xA405, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM);
        attribute(0xA406, ExifInterface.TAG_SCENE_CAPTURE_TYPE);
        attribute(0xA407, ExifInterface.TAG_GAIN_CONTROL);
        attribute(0xA408, ExifInterface.TAG_CONTRAST);
        attribute(0xA409, ExifInterface.TAG_SATURATION);
        attribute(0xA40A, ExifInterface.TAG_SHARPNESS);
        attribute(0xA40B, ExifInterface.TAG_DEVICE_SETTING_DESCRIPTION);
        attribute(0xA40C, ExifInterface.TAG_SUBJECT_DISTANCE_RANGE);
        attribute(0xA420, ExifInterface.TAG_IMAGE_UNIQUE_ID);
        attribute(0xA430, ExifInterface.TAG_CAMERA_OWNER_NAME);
        attribute(0xA431, ExifInterface.TAG_BODY_SERIAL_NUMBER);
        attribute(0xA432, ExifInterface.TAG_LENS_SPECIFICATION);
        attribute(0xA433, ExifInterface.TAG_LENS_MAKE);
        attribute(0xA434, ExifInterface.TAG_LENS_MODEL);
        attribute(0xA500, ExifInterface.TAG_GAMMA);
        attribute(0xC612, ExifInterface.TAG_DNG_VERSION);
        attribute(0xC620, ExifInterface.TAG_DEFAULT_CROP_SIZE);

        // The rest of CIPA DC-008's private directory. LensSerialNumber has a name in
        // ExifInterface but no entry in the table it reads and writes.
        standard("Temperature", TAG_TEMPERATURE, Exif.TYPE_SRATIONAL);
        standard("Humidity", TAG_HUMIDITY, Exif.TYPE_RATIONAL);
        standard("Pressure", TAG_PRESSURE, Exif.TYPE_RATIONAL);
        standard("WaterDepth", TAG_WATER_DEPTH, Exif.TYPE_SRATIONAL);
        standard("Acceleration", TAG_ACCELERATION, Exif.TYPE_RATIONAL);
        standard("CameraElevationAngle", TAG_CAMERA_ELEVATION_ANGLE, Exif.TYPE_SRATIONAL);
        standard("DevelopmentType", 0xA40D, Exif.TYPE_SHORT);
        standard("DevelopmentTypeDescription", 0xA40E, Exif.TYPE_ASCII);
        standard("DistortionCorrection", 0xA40F, Exif.TYPE_SHORT);
        standard("ChromaticAberrationCorrection", 0xA410, Exif.TYPE_SHORT);
        standard("ShadingCorrection", 0xA411, Exif.TYPE_SHORT);
        standard("NoiseReduction", 0xA412, Exif.TYPE_SHORT);
        standard(ExifInterface.TAG_LENS_SERIAL_NUMBER, 0xA435, Exif.TYPE_ASCII);
        standard("ImageTitle", TAG_IMAGE_TITLE, Exif.TYPE_ASCII);
        standard("Photographer", TAG_PHOTOGRAPHER, Exif.TYPE_ASCII);
        standard("ImageEditor", 0xA438, Exif.TYPE_ASCII);
        standard("CameraFirmware", 0xA439, Exif.TYPE_ASCII);
        standard("RAWDevelopingSoftware", 0xA43A, Exif.TYPE_ASCII);
        standard("ImageEditingSoftware", 0xA43B, Exif.TYPE_ASCII);
        standard("MetadataEditingSoftware", 0xA43C, Exif.TYPE_ASCII);
        standard("CompositeImage", 0xA460, Exif.TYPE_SHORT);
        standard("SourceImageNumberOfCompositeImage", 0xA461, Exif.TYPE_SHORT);
        standard("SourceExposureTimesOfCompositeImage", 0xA462, Exif.TYPE_UNDEFINED);
    }

    private ExifTags() {}

    private static void attribute(int tag, @NonNull String name) {
        ATTRIBUTES.put(tag, name);
    }

    private static void standard(@NonNull String name, int tag, int type) {
        STANDARD.put(name, new int[] {tag, type});
    }

    /** The name ExifInterface reads and writes a private directory tag by, or null. */
    @Nullable
    static String attributeName(int tag) {
        return ATTRIBUTES.get(tag);
    }

    /** The number of a private directory tag ExifInterface reads and writes by name, or -1. */
    static int attributeTag(@NonNull String name) {
        for (Map.Entry<Integer, String> attribute : ATTRIBUTES.entrySet()) {
            if (attribute.getValue().equals(name)) {
                return attribute.getKey();
            }
        }
        return -1;
    }

    /**
     * A private directory tag the standard names and ExifInterface doesn't.
     *
     * @return its number and the type the standard gives it, or null for any other name
     */
    @Nullable
    static int[] standardTag(@NonNull String name) {
        return STANDARD.get(name);
    }

    /** What to call a tag in a message: its standard name if it has one, and its number. */
    @NonNull
    static String describe(int tag) {
        String name = ATTRIBUTES.get(tag);
        for (Map.Entry<String, int[]> standard : STANDARD.entrySet()) {
            if (standard.getValue()[0] == tag) {
                name = standard.getKey();
            }
        }
        String number = String.format(Locale.ENGLISH, "0x%04X", tag);
        return name == null ? number : name + " (" + number + ")";
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
         * The value as text: a number in decimal, a rational as "numerator/denominator", several
         * of either separated by commas, and text or bytes as the string they hold.
         *
         * @return the text, or null for a type with none of those shapes
         */
        @Nullable
        String text() {
            return decode(bytes, type, count);
        }

        /** The first number the value holds, or NaN when it holds none. */
        double number() {
            if (count < 1) {
                return Double.NaN;
            }
            switch (type) {
                case Exif.TYPE_BYTE:
                    return ExifSegment.u8(bytes, 0);
                case Exif.TYPE_SBYTE:
                    return bytes[0];
                case Exif.TYPE_SHORT:
                    return ExifSegment.u16(bytes, 0, false);
                case Exif.TYPE_SSHORT:
                    return (short) ExifSegment.u16(bytes, 0, false);
                case Exif.TYPE_LONG:
                    return ExifSegment.u32(bytes, 0, false);
                case Exif.TYPE_SLONG:
                    return (int) ExifSegment.u32(bytes, 0, false);
                case Exif.TYPE_RATIONAL:
                case Exif.TYPE_SRATIONAL: {
                    long numerator = ExifSegment.u32(bytes, 0, false);
                    long denominator = ExifSegment.u32(bytes, 4, false);
                    if (type == Exif.TYPE_SRATIONAL) {
                        numerator = (int) numerator;
                        denominator = (int) denominator;
                    }
                    return denominator == 0 ? Double.NaN : (double) numerator / denominator;
                }
                case Exif.TYPE_FLOAT:
                    return Float.intBitsToFloat((int) ExifSegment.u32(bytes, 0, false));
                case Exif.TYPE_DOUBLE:
                    return Double.longBitsToDouble(doubleBits(bytes, 0));
                default:
                    return Double.NaN;
            }
        }
    }

    /**
     * Reads every entry of a JPEG's Exif private directory.
     *
     * @return the values by tag, in ascending order, which is empty when there are none or the
     *     file carries no Exif
     */
    @NonNull
    static Map<Integer, Value> readAll(@NonNull byte[] jpeg) {
        Map<Integer, Value> values = new TreeMap<>();
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
            Value value = readValue(jpeg, segment, entry);
            if (value != null) {
                values.put(ExifSegment.u16(jpeg, entry, segment.littleEndian), value);
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
     * Takes entries out of a JPEG's Exif private directory, in place: the entries after each one
     * move up, and the pointer that ends the directory follows them. The values they pointed at
     * stay where they were, unreferenced.
     *
     * @return whether any entry was removed
     */
    static boolean remove(@NonNull byte[] jpeg, @NonNull Collection<Integer> tags) {
        ExifSegment.Segment segment = ExifSegment.find(jpeg);
        if (segment == null) {
            return false;
        }
        int ifd = ExifSegment.exifIfd(jpeg, segment);
        int count = ExifSegment.entryCount(jpeg, segment, ifd);
        if (count < 0 || ifd + 2 + count * ExifSegment.ENTRY_BYTES + 4 > segment.end()) {
            return false;
        }
        boolean little = segment.littleEndian;
        int kept = 0;
        for (int i = 0; i < count; i++) {
            int entry = ifd + 2 + i * ExifSegment.ENTRY_BYTES;
            if (tags.contains(ExifSegment.u16(jpeg, entry, little))) {
                continue;
            }
            if (kept != i) {
                System.arraycopy(jpeg, entry, jpeg, ifd + 2 + kept * ExifSegment.ENTRY_BYTES,
                        ExifSegment.ENTRY_BYTES);
            }
            kept++;
        }
        if (kept == count) {
            return false;
        }
        int oldEnd = ifd + 2 + count * ExifSegment.ENTRY_BYTES;
        int newEnd = ifd + 2 + kept * ExifSegment.ENTRY_BYTES;
        System.arraycopy(jpeg, oldEnd, jpeg, newEnd, 4);
        Arrays.fill(jpeg, newEnd + 4, oldEnd + 4, (byte) 0);
        ExifSegment.putU16(jpeg, ifd, kept, little);
        return true;
    }

    /**
     * Returns the JPEG with its Exif private directory holding the given values: each one set,
     * replacing any entry for its tag, and every other entry kept as it was.
     *
     * @return the updated file, or null if it carries no Exif private directory or has no room
     *     left for the values
     */
    @Nullable
    static byte[] write(@NonNull byte[] jpeg, @NonNull Map<Integer, Value> values) {
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

        // Every entry that is staying, which is every one not being given a new value.
        List<int[]> kept = new ArrayList<>();
        for (int i = 0; i < existing; i++) {
            int entry = ifd + 2 + i * ExifSegment.ENTRY_BYTES;
            int tag = ExifSegment.u16(jpeg, entry, segment.littleEndian);
            if (!values.containsKey(tag)) {
                kept.add(new int[] {tag, entry});
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
        for (Map.Entry<Integer, Value> value : values.entrySet()) {
            rows.add(new Object[] {value.getKey(), -1, value.getValue()});
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

    /** How many bytes a value adds to the block: its entry, and its bytes if they don't fit. */
    static int cost(@NonNull Value value) {
        return ExifSegment.ENTRY_BYTES + (value.bytes.length > 4 ? value.bytes.length : 0);
    }

    private static int sizeOf(int type) {
        if (type == Exif.TYPE_UTF8) {
            return 1;
        }
        return type >= 0 && type < TYPE_SIZES.length ? TYPE_SIZES[type] : 0;
    }

    /**
     * Swaps each value's bytes between the two byte orders, in place. A rational is two numbers,
     * each swapped on its own; text and bytes have no order to swap.
     */
    private static void reverseEachValue(@NonNull byte[] bytes, int type) {
        int unit = type == Exif.TYPE_RATIONAL || type == Exif.TYPE_SRATIONAL ? 4 : sizeOf(type);
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
     * Text as a value of the plainest type that holds it: ASCII when every character is, and
     * UTF-8 otherwise.
     */
    @NonNull
    static Value text(@NonNull String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) >= 0x80) {
                return terminated(Exif.TYPE_UTF8, text);
            }
        }
        return terminated(Exif.TYPE_ASCII, text);
    }

    /**
     * Bytes as an UNDEFINED value.
     *
     * @return the value, or null for no bytes, since a count of zero describes no value at all
     */
    @Nullable
    static Value undefined(@NonNull byte[] bytes) {
        return bytes.length == 0
                ? null
                : new Value(Exif.TYPE_UNDEFINED, bytes.length, bytes.clone());
    }

    /**
     * A decimal as a rational, as finely as the type holds it.
     *
     * @return the value, or null for a number the type cannot hold
     */
    @Nullable
    static Value rational(double value, boolean signed) {
        byte[] out = rationalBytes(value, signed);
        return out == null
                ? null
                : new Value(signed ? Exif.TYPE_SRATIONAL : Exif.TYPE_RATIONAL, 1, out);
    }

    /**
     * Turns text into a value of the given type: numbers in decimal, separated by commas when
     * there are several, a rational as "numerator/denominator" or a decimal, and text or bytes
     * as the string to store.
     *
     * @return the value, or null when the text does not suit the type
     */
    @Nullable
    static Value encode(int type, @NonNull String text) {
        try {
            switch (type) {
                case Exif.TYPE_ASCII:
                case Exif.TYPE_UTF8:
                    return terminated(type, text);
                case Exif.TYPE_UNDEFINED:
                    return undefined(text.getBytes(UTF_8));
                case Exif.TYPE_BYTE:
                    return integers(type, text, 0, 0xFF);
                case Exif.TYPE_SBYTE:
                    return integers(type, text, Byte.MIN_VALUE, Byte.MAX_VALUE);
                case Exif.TYPE_SHORT:
                    return integers(type, text, 0, MAX_SHORT);
                case Exif.TYPE_SSHORT:
                    return integers(type, text, Short.MIN_VALUE, Short.MAX_VALUE);
                case Exif.TYPE_LONG:
                    return integers(type, text, 0, MAX_LONG);
                case Exif.TYPE_SLONG:
                    return integers(type, text, Integer.MIN_VALUE, Integer.MAX_VALUE);
                case Exif.TYPE_RATIONAL:
                case Exif.TYPE_SRATIONAL:
                    return rationals(type, text);
                case Exif.TYPE_FLOAT:
                case Exif.TYPE_DOUBLE:
                    return floats(type, text);
                default:
                    return null;
            }
        } catch (NumberFormatException error) {
            // A caller who hands a word to a numeric tag gets nothing written rather than a
            // field full of rubbish that every reader afterwards has to cope with.
            return null;
        }
    }

    /** Text and the NUL that ends it, which the count includes. */
    @NonNull
    private static Value terminated(int type, @NonNull String text) {
        byte[] bytes = text.getBytes(UTF_8);
        byte[] out = Arrays.copyOf(bytes, bytes.length + 1);
        return new Value(type, out.length, out);
    }

    /** Whole numbers within a range, each as many big-endian bytes as the type gives one. */
    @Nullable
    private static Value integers(int type, @NonNull String text, long min, long max) {
        String[] parts = text.split(",", -1);
        int size = sizeOf(type);
        byte[] out = new byte[parts.length * size];
        for (int i = 0; i < parts.length; i++) {
            long number = Long.parseLong(parts[i].trim());
            if (number < min || number > max) {
                return null;
            }
            for (int b = 0; b < size; b++) {
                out[i * size + b] = (byte) (number >> (8 * (size - 1 - b)));
            }
        }
        return new Value(type, parts.length, out);
    }

    /** Rationals, each as "numerator/denominator" or a decimal. */
    @Nullable
    private static Value rationals(int type, @NonNull String text) {
        String[] parts = text.split(",", -1);
        byte[] out = new byte[parts.length * 8];
        for (int i = 0; i < parts.length; i++) {
            byte[] one = rationalBytes(parts[i].trim(), type == Exif.TYPE_SRATIONAL);
            if (one == null) {
                return null;
            }
            System.arraycopy(one, 0, out, i * 8, 8);
        }
        return new Value(type, parts.length, out);
    }

    /** IEEE 754 numbers, as many bytes as the type gives one. */
    @NonNull
    private static Value floats(int type, @NonNull String text) {
        String[] parts = text.split(",", -1);
        int size = sizeOf(type);
        byte[] out = new byte[parts.length * size];
        for (int i = 0; i < parts.length; i++) {
            if (type == Exif.TYPE_FLOAT) {
                ExifSegment.putU32(out, i * 4,
                        Float.floatToIntBits(Float.parseFloat(parts[i].trim())), false);
            } else {
                long bits = Double.doubleToLongBits(Double.parseDouble(parts[i].trim()));
                ExifSegment.putU32(out, i * 8, (int) (bits >>> 32), false);
                ExifSegment.putU32(out, i * 8 + 4, (int) bits, false);
            }
        }
        return new Value(type, parts.length, out);
    }

    /**
     * "n/d" as written, or a decimal, as two big-endian numbers.
     *
     * @return the bytes, or null for a zero denominator or a number the type cannot hold
     */
    @Nullable
    private static byte[] rationalBytes(@NonNull String value, boolean signed) {
        int slash = value.indexOf('/');
        if (slash < 0) {
            return rationalBytes(Double.parseDouble(value), signed);
        }
        long numerator = Long.parseLong(value.substring(0, slash).trim());
        long denominator = Long.parseLong(value.substring(slash + 1).trim());
        if (denominator == 0 || !fits(numerator, signed) || !fits(denominator, signed)) {
            return null;
        }
        return rationalBytes(numerator, denominator);
    }

    /** A decimal over the finest denominator that leaves its numerator inside the type. */
    @Nullable
    private static byte[] rationalBytes(double value, boolean signed) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return null;
        }
        for (long scale : RATIONAL_SCALES) {
            long numerator = Math.round(value * scale);
            if (fits(numerator, signed)) {
                return rationalBytes(numerator, scale);
            }
        }
        return null;
    }

    @NonNull
    private static byte[] rationalBytes(long numerator, long denominator) {
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

    private static long doubleBits(@NonNull byte[] bytes, int at) {
        return (ExifSegment.u32(bytes, at, false) << 32) | ExifSegment.u32(bytes, at + 4, false);
    }

    /** Big-endian bytes as text, in the shapes {@link Value#text()} describes. */
    @Nullable
    private static String decode(@NonNull byte[] bytes, int type, int count) {
        if (type == Exif.TYPE_ASCII || type == Exif.TYPE_UTF8) {
            int end = 0;
            while (end < count && bytes[end] != 0) {
                end++;
            }
            return new String(bytes, 0, end, UTF_8);
        }
        if (type == Exif.TYPE_UNDEFINED) {
            return new String(bytes, 0, count, UTF_8);
        }
        int size = sizeOf(type);
        if (size == 0) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                text.append(',');
            }
            int at = i * size;
            switch (type) {
                case Exif.TYPE_BYTE:
                    text.append(ExifSegment.u8(bytes, at));
                    break;
                case Exif.TYPE_SBYTE:
                    text.append(bytes[at]);
                    break;
                case Exif.TYPE_SHORT:
                    text.append(ExifSegment.u16(bytes, at, false));
                    break;
                case Exif.TYPE_SSHORT:
                    text.append((short) ExifSegment.u16(bytes, at, false));
                    break;
                case Exif.TYPE_LONG:
                    text.append(ExifSegment.u32(bytes, at, false));
                    break;
                case Exif.TYPE_SLONG:
                    text.append((int) ExifSegment.u32(bytes, at, false));
                    break;
                case Exif.TYPE_RATIONAL:
                    text.append(ExifSegment.u32(bytes, at, false)).append('/')
                            .append(ExifSegment.u32(bytes, at + 4, false));
                    break;
                case Exif.TYPE_SRATIONAL:
                    text.append((int) ExifSegment.u32(bytes, at, false)).append('/')
                            .append((int) ExifSegment.u32(bytes, at + 4, false));
                    break;
                case Exif.TYPE_FLOAT:
                    text.append(Float.intBitsToFloat((int) ExifSegment.u32(bytes, at, false)));
                    break;
                default:
                    text.append(Double.longBitsToDouble(doubleBits(bytes, at)));
                    break;
            }
        }
        return text.toString();
    }
}
