package com.xlythe.view.camera;

import android.location.Location;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;
import androidx.exifinterface.media.ExifInterface;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.SimpleTimeZone;
import java.util.TimeZone;

/**
 * A still image format whose native metadata is Exif: JPEG, PNG, WebP, HEIF and TIFF.
 *
 * <p>Every format but JPEG keeps its Exif as a bare TIFF block in a box or chunk of its own.
 * That block is read as it is, and changed by wrapping it in the smallest JPEG that holds it,
 * so the same writer serves every format and the block is taken back out to be saved.</p>
 */
abstract class ExifImageContainer extends MediaContainer {
    /** The most a TIFF block can be and still fit the JPEG it is wrapped in to be changed. */
    @VisibleForTesting
    static final int MAX_WRAPPED_TIFF = 0xFFFF - 2 - 6;

    /** "Exif\0\0", which comes before the TIFF block in a JPEG and in some other formats too. */
    static final byte[] EXIF_IDENTIFIER = {0x45, 0x78, 0x69, 0x66, 0x00, 0x00};

    /** How an Exif date and time is written. */
    private static final String EXIF_DATE_TIME = "yyyy:MM:dd HH:mm:ss";

    /** The tags a capture time is written into, each with its sub-second and offset tags. */
    private static final String[][] CAPTURE_TIME_TAGS = {
            {ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
                    ExifInterface.TAG_OFFSET_TIME_ORIGINAL},
            {ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
                    ExifInterface.TAG_OFFSET_TIME_DIGITIZED},
    };

    /** The Exif as read, or as changed since; null when the file has none and none was made. */
    @Nullable
    private Exif mExif;

    /** Whether {@link #mExif} can be changed. */
    private boolean mExifWritable;

    /** Whether the Exif has changed since it was read or saved. */
    private boolean mExifDirty;

    /** The small JPEG a bare TIFF block is wrapped in to be changed, while it exists. */
    @Nullable
    private File mWrapped;

    /** Whether the file can be written at all. */
    private final boolean mWritable;

    ExifImageContainer(boolean writable) {
        mWritable = writable;
    }

    /** Whether the file can be written at all. */
    boolean isWritable() {
        return mWritable;
    }

    /** Sets the Exif as read, before anything is changed. */
    void setReadExif(@Nullable Exif exif) {
        mExif = exif;
    }

    /** The file's bare TIFF block as read, for formats other than JPEG, or null. */
    @Nullable
    byte[] tiffBlock() {
        return null;
    }

    @Override
    boolean canWriteExif() {
        byte[] tiff = tiffBlock();
        return mWritable && (tiff == null || tiff.length <= MAX_WRAPPED_TIFF);
    }

    @Override
    boolean keeps(@Field int field) {
        switch (field) {
            case TITLE:
            case DESCRIPTION:
            case AUTHOR:
            case COPYRIGHT:
            case MAKE:
            case MODEL:
            case SOFTWARE:
            case CAPTURE_TIME:
            case LOCATION:
            case HEADING:
            case ORIENTATION:
            case AMBIENT:
                return canWriteExif();
            default:
                return false;
        }
    }

    @Override
    boolean hasNativeHome(@Field int field) {
        return field != COVER_ART;
    }

    @Nullable
    @Override
    Exif exif() {
        return mExif;
    }

    @Nullable
    @Override
    Exif writableExif() throws IOException {
        if (!canWriteExif()) {
            return null;
        }
        if (!mExifWritable) {
            mExif = openWritableExif();
            mExifWritable = true;
        }
        mExifDirty = true;
        markDirty();
        return mExif;
    }

    /**
     * Opens the Exif for changing. By default this wraps the bare TIFF block, or nothing when
     * the file has none, in a JPEG of its own in the cache.
     */
    @WorkerThread
    @NonNull
    Exif openWritableExif() throws IOException {
        byte[] tiff = tiffBlock();
        byte[] jpeg = tiff == null ? new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF,
                (byte) 0xD9} : wrap(tiff);
        File wrapped = File.createTempFile("mediatags", ".jpg");
        try (FileOutputStream out = new FileOutputStream(wrapped)) {
            out.write(jpeg);
        }
        mWrapped = wrapped;
        return new Exif(wrapped);
    }

    /** Whether the Exif has changes to save. */
    boolean isExifDirty() {
        return mExifDirty;
    }

    /**
     * Saves the changed Exif into its wrapping and takes the bare TIFF block back out.
     *
     * @return the new block, or null if the Exif has not changed
     */
    @WorkerThread
    @Nullable
    byte[] saveWrappedExif() throws IOException {
        if (!mExifDirty || mWrapped == null || mExif == null) {
            return null;
        }
        keepCaptureTime(mExif);
        mExif.save();
        byte[] jpeg;
        try (RandomAccessFile in = new RandomAccessFile(mWrapped, "r")) {
            jpeg = new byte[(int) in.length()];
            in.readFully(jpeg);
        }
        byte[] tiff = unwrap(jpeg);
        if (tiff == null) {
            throw new IOException("The Exif could not be written");
        }
        return tiff;
    }

    @Override
    void saved() {
        super.saved();
        mExifDirty = false;
    }

    @Override
    void release() {
        if (mWrapped != null) {
            // Nothing else holds it; a failure leaves a few kilobytes for the cache to clear.
            mWrapped.delete();
            mWrapped = null;
            mExifWritable = false;
        }
    }

    // ---------- Wrapping ----------

    /** A TIFF block in the smallest JPEG that holds it: a start, one Exif segment, an end. */
    @NonNull
    static byte[] wrap(@NonNull byte[] tiff) {
        int length = 2 + EXIF_IDENTIFIER.length + tiff.length;
        byte[] jpeg = new byte[4 + length + 2];
        jpeg[0] = (byte) 0xFF;
        jpeg[1] = (byte) 0xD8;
        jpeg[2] = (byte) 0xFF;
        jpeg[3] = (byte) 0xE1;
        Bytes.put16(jpeg, 4, length);
        System.arraycopy(EXIF_IDENTIFIER, 0, jpeg, 6, EXIF_IDENTIFIER.length);
        System.arraycopy(tiff, 0, jpeg, 12, tiff.length);
        jpeg[jpeg.length - 2] = (byte) 0xFF;
        jpeg[jpeg.length - 1] = (byte) 0xD9;
        return jpeg;
    }

    /** The TIFF block of a JPEG's Exif segment, or null if it has none. */
    @Nullable
    static byte[] unwrap(@NonNull byte[] jpeg) {
        ExifSegment.Segment segment = ExifSegment.find(jpeg);
        if (segment == null) {
            return null;
        }
        byte[] tiff = new byte[segment.end() - segment.tiffOffset];
        System.arraycopy(jpeg, segment.tiffOffset, tiff, 0, tiff.length);
        return tiff;
    }

    /**
     * A bare TIFF block from what a format stores, which may or may not start "Exif\0\0".
     *
     * @return the block, or null if it is not one
     */
    @Nullable
    static byte[] tiffOf(@Nullable byte[] stored) {
        if (stored == null) {
            return null;
        }
        int start = Bytes.startsWith(stored, 0, EXIF_IDENTIFIER) ? EXIF_IDENTIFIER.length : 0;
        if (stored.length < start + 8 || !isTiffHeader(stored, start)) {
            return null;
        }
        byte[] tiff = new byte[stored.length - start];
        System.arraycopy(stored, start, tiff, 0, tiff.length);
        return tiff;
    }

    /** Whether a TIFF header, in either byte order, begins at an offset. */
    static boolean isTiffHeader(@NonNull byte[] data, int at) {
        return data.length >= at + 4 && ((data[at] == 'I' && data[at + 1] == 'I'
                && data[at + 2] == 42 && data[at + 3] == 0) || (data[at] == 'M'
                && data[at + 1] == 'M' && data[at + 2] == 0 && data[at + 3] == 42));
    }

    /** The Exif of a bare TIFF block, to read. */
    @NonNull
    static Exif readTiff(@NonNull byte[] tiff) throws IOException {
        return new Exif(new ByteArrayInputStream(tiff));
    }

    // ---------- Fields ----------

    @Nullable
    @Override
    String text(@Field int field) {
        Exif exif = mExif;
        if (exif == null) {
            return null;
        }
        switch (field) {
            case TITLE:
                return blankToNull(exif.getAttribute("ImageTitle"));
            case DESCRIPTION:
                return blankToNull(exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION));
            case AUTHOR: {
                String photographer = blankToNull(exif.getAttribute("Photographer"));
                return photographer != null ? photographer
                        : blankToNull(exif.getAttribute(ExifInterface.TAG_ARTIST));
            }
            case COPYRIGHT:
                return blankToNull(exif.getAttribute(ExifInterface.TAG_COPYRIGHT));
            case MAKE:
                return blankToNull(exif.getAttribute(ExifInterface.TAG_MAKE));
            case MODEL:
                return blankToNull(exif.getAttribute(ExifInterface.TAG_MODEL));
            case SOFTWARE:
                return blankToNull(exif.getAttribute(ExifInterface.TAG_SOFTWARE));
            default:
                return null;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>The tags that came before Exif 3.0 hold only ASCII, so text in any other script is
     * refused rather than written as question marks. The title and the photographer hold UTF-8.
     */
    @Override
    boolean setText(@Field int field, @Nullable String value) throws IOException {
        Exif exif = writableExif();
        if (exif == null) {
            return false;
        }
        boolean ascii = value == null || isAscii(value);
        switch (field) {
            case TITLE:
                exif.setAttribute("ImageTitle", value);
                return true;
            case AUTHOR:
                exif.setAttribute("Photographer", value);
                // Artist too, the older tag every reader knows, when it can hold the name.
                exif.setAttribute(ExifInterface.TAG_ARTIST, ascii ? value : null);
                return true;
            case DESCRIPTION:
                return setAscii(exif, ExifInterface.TAG_IMAGE_DESCRIPTION, value, ascii);
            case COPYRIGHT:
                return setAscii(exif, ExifInterface.TAG_COPYRIGHT, value, ascii);
            case MAKE:
                return setAscii(exif, ExifInterface.TAG_MAKE, value, ascii);
            case MODEL:
                return setAscii(exif, ExifInterface.TAG_MODEL, value, ascii);
            case SOFTWARE:
                return setAscii(exif, ExifInterface.TAG_SOFTWARE, value, ascii);
            default:
                return false;
        }
    }

    private static boolean setAscii(@NonNull Exif exif, @NonNull String tag,
            @Nullable String value, boolean ascii) {
        if (!ascii) {
            return false;
        }
        exif.setAttribute(tag, value);
        return true;
    }

    private static boolean isAscii(@NonNull String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x7E || (text.charAt(i) < 0x20 && text.charAt(i) != '\n')) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * {@inheritDoc}
     *
     * <p>When the picture was taken, or else digitized, or else, for a file that says only when
     * it was last changed, as some early phones' panoramas do, that.
     */
    @Override
    long captureTime() {
        Exif exif = mExif;
        if (exif == null) {
            return -1;
        }
        for (String[] tags : CAPTURE_TIME_TAGS) {
            long time = parseExifTime(exif.getAttribute(tags[0]), exif.getAttribute(tags[1]),
                    exif.getAttribute(tags[2]));
            if (time != -1) {
                return time;
            }
        }
        return parseExifTime(exif.getAttribute(ExifInterface.TAG_DATETIME),
                exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME),
                exif.getAttribute(ExifInterface.TAG_OFFSET_TIME));
    }

    /**
     * Before a save, which stamps DateTime with the moment of saving, a file that says when it
     * was taken only there has it kept as the time it was taken.
     */
    static void keepCaptureTime(@NonNull Exif exif) {
        if (exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) != null
                || exif.getAttribute(ExifInterface.TAG_DATETIME) == null) {
            return;
        }
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL,
                exif.getAttribute(ExifInterface.TAG_DATETIME));
        exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
                exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME));
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
                exif.getAttribute(ExifInterface.TAG_OFFSET_TIME));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Removing it removes every time the file keeps, the time it was last changed included,
     * which a save then leaves out too, so no other time stands in for it.
     */
    @Override
    @SuppressWarnings("deprecation")
    void setCaptureTime(long millis) throws IOException {
        Exif exif = requireWritableExif();
        if (millis < 0) {
            exif.removeTimestamp();
            return;
        }
        String[] formatted = formatExifTime(millis, TimeZone.getDefault());
        for (String[] tags : CAPTURE_TIME_TAGS) {
            for (int i = 0; i < tags.length; i++) {
                exif.setAttribute(tags[i], formatted[i]);
            }
        }
    }

    /**
     * An Exif time as milliseconds since the epoch: in the zone its offset gives, or the
     * device's own when it has none.
     *
     * @return the time, or -1 when it is missing or not a time
     */
    @VisibleForTesting
    static long parseExifTime(@Nullable String dateTime, @Nullable String subSeconds,
            @Nullable String offset) {
        if (dateTime == null || dateTime.trim().length() < 19) {
            return -1;
        }
        TimeZone zone = parseOffset(offset);
        SimpleDateFormat format = new SimpleDateFormat(EXIF_DATE_TIME, Locale.US);
        format.setLenient(false);
        format.setTimeZone(zone == null ? TimeZone.getDefault() : zone);
        try {
            // Some writers separate the date with dashes; the layout is otherwise the same.
            String normalized = dateTime.trim().substring(0, 19).replace('-', ':')
                    .replace('T', ' ');
            return format.parse(normalized).getTime() + Exif.parseSubSecond(subSeconds);
        } catch (ParseException | RuntimeException notATime) {
            return -1;
        }
    }

    /**
     * A time as Exif writes it in a zone: the date and time, the milliseconds as SubSecTime
     * does, and the offset.
     */
    @VisibleForTesting
    @NonNull
    static String[] formatExifTime(long millis, @NonNull TimeZone zone) {
        SimpleDateFormat format = new SimpleDateFormat(EXIF_DATE_TIME, Locale.US);
        format.setTimeZone(zone);
        long seconds = millis - (((millis % 1000) + 1000) % 1000);
        return new String[] {format.format(seconds), Exif.formatSubSecond(millis - seconds),
                formatOffset(zone.getOffset(millis))};
    }

    /** An offset such as "+05:30", which is how Exif and ISO 8601 write one. */
    @NonNull
    static String formatOffset(int offsetMillis) {
        int minutes = offsetMillis / 60_000;
        char sign = minutes < 0 ? '-' : '+';
        minutes = Math.abs(minutes);
        return String.format(Locale.US, "%c%02d:%02d", sign, minutes / 60, minutes % 60);
    }

    /** A zone for an offset such as "+05:30", "-0800" or "Z", or null if it is not one. */
    @Nullable
    static TimeZone parseOffset(@Nullable String offset) {
        if (offset == null) {
            return null;
        }
        String text = offset.trim();
        if ("Z".equalsIgnoreCase(text)) {
            return TimeZone.getTimeZone("UTC");
        }
        if (text.length() < 3 || (text.charAt(0) != '+' && text.charAt(0) != '-')) {
            return null;
        }
        String digits = text.substring(1).replace(":", "");
        if (digits.length() != 4 && digits.length() != 2) {
            return null;
        }
        try {
            int hours = Integer.parseInt(digits.substring(0, 2));
            int minutes = digits.length() == 4 ? Integer.parseInt(digits.substring(2)) : 0;
            if (hours > 18 || minutes > 59) {
                return null;
            }
            int total = (hours * 60 + minutes) * 60_000 * (text.charAt(0) == '-' ? -1 : 1);
            return new SimpleTimeZone(total, "UTC" + text);
        } catch (NumberFormatException notAnOffset) {
            return null;
        }
    }

    @Nullable
    @Override
    @SuppressWarnings("deprecation")
    Location location() {
        return mExif == null ? null : mExif.getLocation();
    }

    @Override
    @SuppressWarnings("deprecation")
    void setLocation(@Nullable Location location) throws IOException {
        Exif exif = requireWritableExif();
        // Removing the location takes every GPS tag with it, the direction the camera faced
        // too, which is a field of its own here, so it goes back.
        String direction = exif.getAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION);
        String reference = exif.getAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION_REF);
        exif.removeLocation();
        if (location != null) {
            exif.attachLocation(location);
        }
        exif.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION, direction);
        exif.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION_REF, reference);
    }

    @Override
    float heading() {
        if (mExif == null) {
            return Float.NaN;
        }
        double degrees = ExifCaptureParameters.number(
                mExif.getAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION));
        return Double.isNaN(degrees) ? Float.NaN : (float) degrees;
    }

    @Override
    void setHeading(float degrees) throws IOException {
        Exif exif = requireWritableExif();
        if (Float.isNaN(degrees)) {
            exif.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION, null);
            exif.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION_REF, null);
            return;
        }
        double normalized = ((degrees % 360) + 360) % 360;
        exif.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION_REF, "T");
        exif.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION,
                Math.round(normalized * 100) + "/100");
    }

    @Override
    int orientation() {
        if (mExif == null) {
            return 0;
        }
        String value = mExif.getAttribute(ExifInterface.TAG_ORIENTATION);
        try {
            int orientation = value == null ? 0 : Integer.parseInt(value.trim());
            return orientation >= 1 && orientation <= 8 ? orientation : 0;
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    @Override
    boolean setOrientation(int orientation) throws IOException {
        requireWritableExif().setAttribute(ExifInterface.TAG_ORIENTATION,
                Integer.toString(orientation));
        return true;
    }

    @Nullable
    @Override
    @SuppressWarnings("deprecation")
    AmbientConditions ambient() {
        return mExif == null ? null : mExif.getAmbientConditions();
    }

    @Override
    @SuppressWarnings("deprecation")
    void setAmbient(@Nullable AmbientConditions conditions) throws IOException {
        Exif exif = requireWritableExif();
        if (conditions == null) {
            exif.removeAmbientConditions();
        } else {
            exif.attachAmbientConditions(conditions);
        }
    }

    /** The Exif to change, which a field this format keeps always has. */
    @NonNull
    private Exif requireWritableExif() throws IOException {
        Exif exif = writableExif();
        if (exif == null) {
            throw new IOException("This file's Exif cannot be written");
        }
        return exif;
    }
}
