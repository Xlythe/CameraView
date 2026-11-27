package com.xlythe.view.camera;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.SequenceInputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.channels.FileChannel;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import androidx.annotation.AnyThread;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;
import androidx.exifinterface.media.ExifInterface;

/**
 * Modifies metadata on JPEG files. Call {@link #save()} to persist changes to disc.
 */
public class Exif {
    private static final String TAG = Exif.class.getSimpleName();
    private static final boolean DEBUG = false;

    /** The types a tag's value can have, as the Exif standard numbers them. */
    @IntDef({TYPE_BYTE, TYPE_ASCII, TYPE_SHORT, TYPE_LONG, TYPE_RATIONAL, TYPE_SBYTE,
            TYPE_UNDEFINED, TYPE_SSHORT, TYPE_SLONG, TYPE_SRATIONAL, TYPE_FLOAT, TYPE_DOUBLE,
            TYPE_UTF8})
    @Retention(RetentionPolicy.SOURCE)
    public @interface TagType {}

    /** Unsigned 8-bit integers, written as decimals separated by commas. */
    public static final int TYPE_BYTE = 1;
    /** Text. */
    public static final int TYPE_ASCII = 2;
    /** Unsigned 16-bit integers, written as decimals separated by commas. */
    public static final int TYPE_SHORT = 3;
    /** Unsigned 32-bit integers, written as decimals separated by commas. */
    public static final int TYPE_LONG = 4;
    /**
     * Unsigned fractions, each written as "numerator/denominator" or as a decimal, separated by
     * commas.
     */
    public static final int TYPE_RATIONAL = 5;
    /** Signed 8-bit integers, written as decimals separated by commas. */
    public static final int TYPE_SBYTE = 6;
    /** Bytes with no structure, written as text or as the bytes themselves. */
    public static final int TYPE_UNDEFINED = 7;
    /** Signed 16-bit integers, written as decimals separated by commas. */
    public static final int TYPE_SSHORT = 8;
    /** Signed 32-bit integers, written as decimals separated by commas. */
    public static final int TYPE_SLONG = 9;
    /**
     * Signed fractions, each written as "numerator/denominator" or as a decimal, separated by
     * commas.
     */
    public static final int TYPE_SRATIONAL = 10;
    /** Single-precision floating point numbers, written as decimals separated by commas. */
    public static final int TYPE_FLOAT = 11;
    /** Double-precision floating point numbers, written as decimals separated by commas. */
    public static final int TYPE_DOUBLE = 12;
    /** Text in UTF-8. */
    public static final int TYPE_UTF8 = 129;

    // SimpleDateFormat keeps mutable parsing state, so a shared instance cannot be used from more
    // than one thread. Pictures are saved on AsyncTask's thread pool, so two captures finishing
    // together were formatting and parsing through the same objects: the usual result is a
    // corrupted timestamp, and the unlucky one is an exception thrown from inside the formatter.
    private static final ThreadLocal<SimpleDateFormat> DATETIME_FORMAT =
            formatter("yyyy:MM:dd HH:mm:ss", null);

    // Every other time Exif records is local, but the GPS ones are UTC.
    private static final ThreadLocal<SimpleDateFormat> GPS_DATE_FORMAT =
            formatter("yyyy:MM:dd", TimeZone.getTimeZone("UTC"));
    private static final ThreadLocal<SimpleDateFormat> GPS_TIME_FORMAT =
            formatter("HH:mm:ss", TimeZone.getTimeZone("UTC"));
    private static final ThreadLocal<SimpleDateFormat> GPS_DATETIME_FORMAT =
            formatter("yyyy:MM:dd HH:mm:ss", TimeZone.getTimeZone("UTC"));

    /** One formatter per thread, built lazily, in the given timezone or else the device's. */
    private static ThreadLocal<SimpleDateFormat> formatter(
            String pattern, @Nullable TimeZone timeZone) {
        return new ThreadLocal<SimpleDateFormat>() {
            @Override
            protected SimpleDateFormat initialValue() {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.ENGLISH);
                if (timeZone != null) {
                    format.setTimeZone(timeZone);
                }
                return format;
            }
        };
    }

    /** Thousandths, which is finer than any GPS reports and well inside a rational's range. */
    private static final long RATIONAL_SCALE = 1000;

    /** Exif 2.32, the version whose tags this class writes. */
    private static final String EXIF_VERSION_2_32 = "0232";
    private static final String FLASHPIX_VERSION_1_0 = "0100";
    private static final String COLOR_SPACE_SRGB = "1";
    private static final String RESOLUTION_UNIT_INCHES = "2";
    private static final String DEFAULT_RESOLUTION = "72/1";
    private static final String Y_CB_CR_POSITIONING_CENTERED = "1";
    /** The thumbnail size the specification suggests, and small enough to fit beside the Exif. */
    private static final int THUMBNAIL_WIDTH = 160;
    private static final int THUMBNAIL_HEIGHT = 120;
    /** Tried in turn until one fits the room left beside the Exif. */
    private static final int[] THUMBNAIL_QUALITIES = {80, 60, 40, 25};
    /** Y, Cb, Cr and nothing in the fourth slot: bytes 1, 2, 3, 0. */
    private static final String COMPONENTS_Y_CB_CR = "\u0001\u0002\u0003\u0000";

    /** The GPS tag version every current writer declares, as the four bytes 2, 3, 0, 0. */
    private static final String GPS_VERSION_2_3 = "2/1,3/1,0/1,0/1";
    /** The datum Android's fixes are expressed in, named because a coordinate needs one. */
    private static final String WGS_84 = "WGS-84";
    private static final String ALTITUDE_ABOVE_SEA_LEVEL = "0";
    private static final String ALTITUDE_BELOW_SEA_LEVEL = "1";
    /** True north rather than magnetic, which is what Location.getBearing reports. */
    private static final String DIRECTION_TRUE_NORTH = "T";
    /** A satellite fix that solved for height took four satellites; one that did not, three. */
    private static final String MEASURE_MODE_3D = "3";
    private static final String MEASURE_MODE_2D = "2";
    /** The extra Android's GPS provider counts the satellites used in a fix under. */
    private static final String EXTRA_SATELLITES = "satellites";

    /** Every tag in the GPS directory, all of which say something about where a picture was. */
    private static final String[] GPS_TAGS = {
            ExifInterface.TAG_GPS_VERSION_ID,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_SATELLITES,
            ExifInterface.TAG_GPS_STATUS,
            ExifInterface.TAG_GPS_MEASURE_MODE,
            ExifInterface.TAG_GPS_DOP,
            ExifInterface.TAG_GPS_SPEED_REF,
            ExifInterface.TAG_GPS_SPEED,
            ExifInterface.TAG_GPS_TRACK_REF,
            ExifInterface.TAG_GPS_TRACK,
            ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
            ExifInterface.TAG_GPS_IMG_DIRECTION,
            ExifInterface.TAG_GPS_MAP_DATUM,
            ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
            ExifInterface.TAG_GPS_DEST_LATITUDE,
            ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
            ExifInterface.TAG_GPS_DEST_LONGITUDE,
            ExifInterface.TAG_GPS_DEST_BEARING_REF,
            ExifInterface.TAG_GPS_DEST_BEARING,
            ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
            ExifInterface.TAG_GPS_DEST_DISTANCE,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_AREA_INFORMATION,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_DIFFERENTIAL,
            ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
    };

    /** A digital still camera, as FileSource records it: the one byte 3. */
    private static final String FILE_SOURCE_DIGITAL_STILL_CAMERA = "\u0003";
    /** A directly photographed image, as SceneType records it: the one byte 1. */
    private static final String SCENE_TYPE_DIRECTLY_PHOTOGRAPHED = "\u0001";

    /** The Exif tags the standard defines as UNDEFINED bytes, with how many bytes each holds. */
    private static final int[][] UNDEFINED_TAGS = {
            {0x9101 /* ComponentsConfiguration */, 4},
            {0xA300 /* FileSource */, 1},
            {0xA301 /* SceneType */, 1},
    };
    /** Enough of the file to hold an Exif segment, which is at most 64KB, and what precedes it. */
    private static final int HEAD_BYTES = 256 * 1024;

    private static final String KILOMETERS_PER_HOUR = "K";
    private static final String MILES_PER_HOUR = "M";
    private static final String KNOTS = "N";

    private final ExifInterface mExifInterface;

    /** The file this came from, if any, for measuring an image no tag gives the size of. */
    @Nullable private final String mFilePath;

    /** Whether {@link #attachThumbnail()} asked {@link #save()} to write a thumbnail. */
    private boolean mThumbnailRequested;

    /**
     * Whether the file holds a thumbnail: one it had when opened, such as a camera's own, or one
     * a save wrote. Every save declares it again, so rewriting the block never loses it.
     */
    private boolean mHasThumbnail;

    /** The file's Exif private directory as stored when opened, or as the last save left it. */
    private Map<Integer, ExifTags.Value> mDirectory;

    /**
     * The private directory entries every save writes itself, after ExifInterface's: the file's
     * own that ExifInterface does not keep, since its save drops them, and the ones set by
     * number. They replace any entry ExifInterface writes for the same tag.
     */
    private final Map<Integer, ExifTags.Value> mTags;

    /** Tags removed by number, which every save takes out of the private directory. */
    private final Set<Integer> mRemovedTags = new HashSet<>();

    /** Whether this is a JPEG file, the one kind whose private directory can be written. */
    private final boolean mCanWriteTags;

    // When true, avoid saving any time. This is a privacy issue.
    private boolean mRemoveTimestamp = false;

    /**
     * What a file's metadata says, as one line for a log, or why it could not be read. Never
     * throws.
     */
    @NonNull
    public static String describe(@NonNull File file) {
        try {
            return new Exif(file).toString();
        } catch (IOException | RuntimeException error) {
            return file + ": metadata could not be read (" + error + ")";
        }
    }

    public Exif(File file) throws IOException {
        this(file.toString());
    }

    public Exif(String filePath) throws IOException {
        mExifInterface = new ExifInterface(filePath);
        mFilePath = filePath;
        mHasThumbnail = mExifInterface.hasThumbnail();
        byte[] head = readHead(filePath);
        mCanWriteTags = ExifSegment.isJpeg(head);
        mDirectory = ExifTags.readAll(head);
        mTags = ownTags(mDirectory);
    }

    /** Reads the metadata of an image from a stream, such as one opened from a content URI. */
    public Exif(InputStream is) throws IOException {
        // The head is read ahead of ExifInterface and handed back to it. A stream that can go
        // back, a file by seeking or another by mark and reset, is handed back as it is, so
        // ExifInterface still sees what it was given; any other is replayed.
        FileChannel channel = is instanceof FileInputStream
                ? ((FileInputStream) is).getChannel() : null;
        long start = position(channel);
        InputStream source = is;
        byte[] head;
        if (start >= 0) {
            head = readHead(is);
            if (!rewind(channel, start)) {
                source = new SequenceInputStream(new ByteArrayInputStream(head), is);
            }
        } else if (is.markSupported()) {
            is.mark(HEAD_BYTES);
            head = readHead(is);
            is.reset();
        } else {
            head = readHead(is);
            source = new SequenceInputStream(new ByteArrayInputStream(head), is);
        }
        mExifInterface = new ExifInterface(source);
        mFilePath = null;
        mHasThumbnail = mExifInterface.hasThumbnail();
        mCanWriteTags = false;
        mDirectory = ExifTags.readAll(head);
        mTags = ownTags(mDirectory);
    }

    /**
     * The entries of a private directory ExifInterface does not keep, which every save writes
     * back. It keeps an entry only if it has a name for the tag and could read the value.
     */
    @NonNull
    private Map<Integer, ExifTags.Value> ownTags(@NonNull Map<Integer, ExifTags.Value> directory) {
        Map<Integer, ExifTags.Value> own = new TreeMap<>();
        for (Map.Entry<Integer, ExifTags.Value> entry : directory.entrySet()) {
            int tag = entry.getKey();
            String name = ExifTags.attributeName(tag);
            if (tag == ExifTags.TAG_INTEROPERABILITY_POINTER
                    || (name != null && mExifInterface.hasAttribute(name))) {
                continue;
            }
            own.put(tag, entry.getValue());
        }
        return own;
    }

    /**
     * Writes the changes to the file.
     *
     * @throws ExifNoRoomException if the file has no room for some of its tags, which
     *     {@link ExifNoRoomException#getTags()} names; everything else is still written
     * @throws IOException if the file cannot be written
     */
    @WorkerThread
    public void save() throws IOException {
        if (!mRemoveTimestamp) {
            attachLastModifiedTimestamp();
        }
        attachMandatoryTags();
        mExifInterface.saveAttributes();
        restoreUndefinedTypes();
        finishBlock();
    }

    /**
     * Adds what saveAttributes cannot write to the block it has just written: the private
     * directory entries it drops, the removals by number, and the thumbnail, whose bytes it
     * cannot add and whose Compression tag it can lose. This follows restoreUndefinedTypes,
     * since the private directory is copied as it stands.
     *
     * <p>The tags go first and the thumbnail takes the room they leave. When they do not fit
     * beside a thumbnail the file already holds, the thumbnail gives way, as the library derives
     * it: it comes out, and goes back as it was, or re-encoded smaller, if there is room after
     * the tags. Tags that still do not fit are left out, largest first, and named once
     * everything else is written.</p>
     *
     * @throws ExifNoRoomException if tags were left out
     */
    @WorkerThread
    private void finishBlock() throws IOException {
        boolean thumbnail = mHasThumbnail || mThumbnailRequested;
        if (mFilePath == null || (mTags.isEmpty() && mRemovedTags.isEmpty() && !thumbnail)) {
            // A stream has no file to add any of this to.
            return;
        }
        if (!mCanWriteTags) {
            // Only a JPEG's private directory can be written here.
            if (!mTags.isEmpty()) {
                throw new ExifNoRoomException(mTags.keySet());
            }
            return;
        }
        File file = new File(mFilePath);
        byte[] original;
        byte[] jpeg;
        byte[] givenWay = null;
        Set<Integer> leftOut = new TreeSet<>();
        try {
            original = read(file);
            if (original == null) {
                return;
            }
            jpeg = original;
            if (!mRemovedTags.isEmpty()) {
                jpeg = original.clone();
                ExifTags.remove(jpeg, mRemovedTags);
            }
            Map<Integer, ExifTags.Value> tags = new TreeMap<>(mTags);
            byte[] withTags = tags.isEmpty() ? jpeg : ExifTags.write(jpeg, tags);
            if (withTags == null) {
                byte[] detached = ExifThumbnail.detach(jpeg);
                if (detached != null) {
                    givenWay = ExifThumbnail.thumbnailBytes(jpeg);
                    jpeg = detached;
                    withTags = ExifTags.write(jpeg, tags);
                }
            }
            while (withTags == null) {
                int largest = largest(tags);
                tags.remove(largest);
                leftOut.add(largest);
                withTags = tags.isEmpty() ? jpeg : ExifTags.write(jpeg, tags);
            }
            jpeg = withTags;
        } catch (RuntimeException | OutOfMemoryError error) {
            throw new IOException("Unable to write the Exif tags", error);
        }
        jpeg = withThumbnail(file, jpeg, givenWay);
        if (jpeg != original) {
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(jpeg);
            }
        }
        mHasThumbnail = ExifThumbnail.hasThumbnail(jpeg);
        mDirectory = ExifTags.readAll(jpeg);
        if (!leftOut.isEmpty()) {
            throw new ExifNoRoomException(leftOut);
        }
    }

    /** The tag whose value takes the most room, the higher numbered one of two that tie. */
    private static int largest(@NonNull Map<Integer, ExifTags.Value> tags) {
        int largest = -1;
        int most = -1;
        for (Map.Entry<Integer, ExifTags.Value> tag : tags.entrySet()) {
            int cost = ExifTags.cost(tag.getValue());
            if (cost >= most) {
                most = cost;
                largest = tag.getKey();
            }
        }
        return largest;
    }

    /**
     * Gives the tags in {@link #UNDEFINED_TAGS} back the type the standard defines. Set by name,
     * ExifInterface 1.4 saves them as text, which adds a terminator and leaves a reader "?" in
     * place of every byte below a space. Only those entries are rewritten.
     */
    private void restoreUndefinedTypes() {
        // Only ever after a save, which a stream cannot have, so there is always a file.
        try (RandomAccessFile file = new RandomAccessFile(mFilePath, "rw")) {
            byte[] head = new byte[(int) Math.min(file.length(), HEAD_BYTES)];
            file.readFully(head);
            ExifSegment.Segment segment = ExifSegment.find(head);
            if (segment == null) {
                return;
            }
            for (int[] tag : UNDEFINED_TAGS) {
                int entry = ExifSegment.retypeAsUndefined(head, segment, tag[0], tag[1]);
                if (entry >= 0) {
                    file.seek(entry);
                    file.write(head, entry, ExifSegment.ENTRY_BYTES);
                }
            }
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "Unable to restore the type of the Exif tags written as bytes", error);
        }
    }

    /**
     * Fills in the tags Exif requires of a compressed image (CIPA DC-008 tables 17 and 18), and
     * the resolution and FlashpixVersion where a camera wrote zero for them, as camera2 and
     * CameraX do. A value the camera did write sensibly is left alone.
     */
    private void attachMandatoryTags() {
        // 2.32, because the offset tags this class writes arrived in 2.31 and a file carrying
        // them while declaring an earlier version is asking to be parsed wrongly.
        if (isBlank(mExifInterface.getAttribute(ExifInterface.TAG_EXIF_VERSION))) {
            mExifInterface.setAttribute(ExifInterface.TAG_EXIF_VERSION, EXIF_VERSION_2_32);
        }
        if (isBlank(mExifInterface.getAttribute(ExifInterface.TAG_FLASHPIX_VERSION))) {
            mExifInterface.setAttribute(ExifInterface.TAG_FLASHPIX_VERSION, FLASHPIX_VERSION_1_0);
        }
        // sRGB. The other legal value means "uncalibrated", which is a claim about the sensor
        // this cannot make on the camera's behalf.
        if (isUnsetOrZero(ExifInterface.TAG_COLOR_SPACE)) {
            mExifInterface.setAttribute(ExifInterface.TAG_COLOR_SPACE, COLOR_SPACE_SRGB);
        }
        // Inches, and 72 of them to the unit: the convention for a photograph that was never
        // printed and has no real resolution to report.
        if (isUnsetOrZero(ExifInterface.TAG_RESOLUTION_UNIT)) {
            mExifInterface.setAttribute(ExifInterface.TAG_RESOLUTION_UNIT, RESOLUTION_UNIT_INCHES);
        }
        if (isUnsetOrZeroRational(ExifInterface.TAG_X_RESOLUTION)) {
            mExifInterface.setAttribute(ExifInterface.TAG_X_RESOLUTION, DEFAULT_RESOLUTION);
        }
        if (isUnsetOrZeroRational(ExifInterface.TAG_Y_RESOLUTION)) {
            mExifInterface.setAttribute(ExifInterface.TAG_Y_RESOLUTION, DEFAULT_RESOLUTION);
        }
        // Centered, which is what every JPEG encoder on a phone produces.
        if (isUnsetOrZero(ExifInterface.TAG_Y_CB_CR_POSITIONING)) {
            mExifInterface.setAttribute(
                    ExifInterface.TAG_Y_CB_CR_POSITIONING, Y_CB_CR_POSITIONING_CENTERED);
        }
        // YCbCr in the usual order, as the four bytes 1, 2, 3, 0. The legacy backend omits it;
        // the other two write it already, which is why this only fills a gap.
        if (mExifInterface.getAttribute(ExifInterface.TAG_COMPONENTS_CONFIGURATION) == null) {
            mExifInterface.setAttribute(
                    ExifInterface.TAG_COMPONENTS_CONFIGURATION, COMPONENTS_Y_CB_CR);
        }
        // The pixel dimensions, which the CameraX backend leaves out along with ImageWidth, so
        // there is nothing to copy them from and the image itself has to be measured.
        if (isUnsetOrZero(ExifInterface.TAG_PIXEL_X_DIMENSION)
                || isUnsetOrZero(ExifInterface.TAG_PIXEL_Y_DIMENSION)) {
            Point size = measureImage();
            if (size != null) {
                mExifInterface.setAttribute(
                        ExifInterface.TAG_PIXEL_X_DIMENSION, Integer.toString(size.x));
                mExifInterface.setAttribute(
                        ExifInterface.TAG_PIXEL_Y_DIMENSION, Integer.toString(size.y));
            }
        }
    }

    /**
     * How big the image is, from its own frame header, which costs no pixels to read.
     *
     * @return the size, or null when there is no file to measure or it will not decode
     */
    @Nullable
    private Point measureImage() {
        int width = getWidth();
        int height = getHeight();
        if (width > 0 && height > 0) {
            return new Point(width, height);
        }
        if (mFilePath == null) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try {
            BitmapFactory.decodeFile(mFilePath, bounds);
        } catch (RuntimeException | OutOfMemoryError error) {
            // The size is left out, and the rest of the metadata still written.
            Log.w(TAG, "Unable to measure the image to record its dimensions", error);
            return null;
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        return new Point(bounds.outWidth, bounds.outHeight);
    }

    /** Whether a tag is missing or holds the zero that stands for "nobody filled this in". */
    private boolean isUnsetOrZero(@NonNull String tag) {
        return mExifInterface.getAttributeInt(tag, 0) == 0;
    }

    /** The same, for a rational, whose zero is written 0/1 rather than 0. */
    private boolean isUnsetOrZeroRational(@NonNull String tag) {
        return mExifInterface.getAttributeDouble(tag, 0) == 0;
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }

    /** Copies another picture's metadata onto this one, to be written by {@link #save()}. */
    public void copyFrom(Exif exif) {
        for (Map.Entry<Integer, ExifTags.Value> tag : exif.mTags.entrySet()) {
            putTag(tag.getKey(), tag.getValue());
        }
        copy(exif, ExifInterface.TAG_APERTURE_VALUE);
        copy(exif, ExifInterface.TAG_ARTIST);
        copy(exif, ExifInterface.TAG_BITS_PER_SAMPLE);
        copy(exif, ExifInterface.TAG_BODY_SERIAL_NUMBER);
        copy(exif, ExifInterface.TAG_CAMERA_OWNER_NAME);
        copy(exif, ExifInterface.TAG_CFA_PATTERN);
        copy(exif, ExifInterface.TAG_COLOR_SPACE);
        copy(exif, ExifInterface.TAG_COMPONENTS_CONFIGURATION);
        copy(exif, ExifInterface.TAG_COMPRESSED_BITS_PER_PIXEL);
        copy(exif, ExifInterface.TAG_COMPRESSION);
        copy(exif, ExifInterface.TAG_CONTRAST);
        copy(exif, ExifInterface.TAG_COPYRIGHT);
        copy(exif, ExifInterface.TAG_CUSTOM_RENDERED);
        copy(exif, ExifInterface.TAG_DATETIME);
        copy(exif, ExifInterface.TAG_DATETIME_DIGITIZED);
        copy(exif, ExifInterface.TAG_DATETIME_ORIGINAL);
        copy(exif, ExifInterface.TAG_DEFAULT_CROP_SIZE);
        copy(exif, ExifInterface.TAG_DEVICE_SETTING_DESCRIPTION);
        copy(exif, ExifInterface.TAG_DIGITAL_ZOOM_RATIO);
        copy(exif, ExifInterface.TAG_EXIF_VERSION);
        copy(exif, ExifInterface.TAG_EXPOSURE_BIAS_VALUE);
        copy(exif, ExifInterface.TAG_EXPOSURE_INDEX);
        copy(exif, ExifInterface.TAG_EXPOSURE_MODE);
        copy(exif, ExifInterface.TAG_EXPOSURE_PROGRAM);
        copy(exif, ExifInterface.TAG_EXPOSURE_TIME);
        copy(exif, ExifInterface.TAG_FILE_SOURCE);
        copy(exif, ExifInterface.TAG_FLASH);
        copy(exif, ExifInterface.TAG_FLASHPIX_VERSION);
        copy(exif, ExifInterface.TAG_FLASH_ENERGY);
        copy(exif, ExifInterface.TAG_FOCAL_LENGTH);
        copy(exif, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM);
        copy(exif, ExifInterface.TAG_FOCAL_PLANE_RESOLUTION_UNIT);
        copy(exif, ExifInterface.TAG_FOCAL_PLANE_X_RESOLUTION);
        copy(exif, ExifInterface.TAG_FOCAL_PLANE_Y_RESOLUTION);
        copy(exif, ExifInterface.TAG_F_NUMBER);
        copy(exif, ExifInterface.TAG_GAIN_CONTROL);
        copy(exif, ExifInterface.TAG_GAMMA);
        copy(exif, ExifInterface.TAG_GPS_ALTITUDE);
        copy(exif, ExifInterface.TAG_GPS_ALTITUDE_REF);
        copy(exif, ExifInterface.TAG_GPS_AREA_INFORMATION);
        copy(exif, ExifInterface.TAG_GPS_DATESTAMP);
        copy(exif, ExifInterface.TAG_GPS_DEST_BEARING);
        copy(exif, ExifInterface.TAG_GPS_DEST_BEARING_REF);
        copy(exif, ExifInterface.TAG_GPS_DEST_DISTANCE);
        copy(exif, ExifInterface.TAG_GPS_DEST_DISTANCE_REF);
        copy(exif, ExifInterface.TAG_GPS_DEST_LATITUDE);
        copy(exif, ExifInterface.TAG_GPS_DEST_LATITUDE_REF);
        copy(exif, ExifInterface.TAG_GPS_DEST_LONGITUDE);
        copy(exif, ExifInterface.TAG_GPS_DEST_LONGITUDE_REF);
        copy(exif, ExifInterface.TAG_GPS_DIFFERENTIAL);
        copy(exif, ExifInterface.TAG_GPS_DOP);
        copy(exif, ExifInterface.TAG_GPS_H_POSITIONING_ERROR);
        copy(exif, ExifInterface.TAG_GPS_IMG_DIRECTION);
        copy(exif, ExifInterface.TAG_GPS_IMG_DIRECTION_REF);
        copy(exif, ExifInterface.TAG_GPS_LATITUDE);
        copy(exif, ExifInterface.TAG_GPS_LATITUDE_REF);
        copy(exif, ExifInterface.TAG_GPS_LONGITUDE);
        copy(exif, ExifInterface.TAG_GPS_LONGITUDE_REF);
        copy(exif, ExifInterface.TAG_GPS_MAP_DATUM);
        copy(exif, ExifInterface.TAG_GPS_MEASURE_MODE);
        copy(exif, ExifInterface.TAG_GPS_PROCESSING_METHOD);
        copy(exif, ExifInterface.TAG_GPS_SATELLITES);
        copy(exif, ExifInterface.TAG_GPS_SPEED);
        copy(exif, ExifInterface.TAG_GPS_SPEED_REF);
        copy(exif, ExifInterface.TAG_GPS_STATUS);
        copy(exif, ExifInterface.TAG_GPS_TIMESTAMP);
        copy(exif, ExifInterface.TAG_GPS_TRACK);
        copy(exif, ExifInterface.TAG_GPS_TRACK_REF);
        copy(exif, ExifInterface.TAG_GPS_VERSION_ID);
        copy(exif, ExifInterface.TAG_IMAGE_DESCRIPTION);
        copy(exif, ExifInterface.TAG_IMAGE_LENGTH);
        copy(exif, ExifInterface.TAG_IMAGE_UNIQUE_ID);
        copy(exif, ExifInterface.TAG_IMAGE_WIDTH);
        copy(exif, ExifInterface.TAG_INTEROPERABILITY_INDEX);
        copy(exif, ExifInterface.TAG_ISO_SPEED);
        copy(exif, ExifInterface.TAG_ISO_SPEED_LATITUDE_YYY);
        copy(exif, ExifInterface.TAG_ISO_SPEED_LATITUDE_ZZZ);
        copy(exif, ExifInterface.TAG_ISO_SPEED_RATINGS);
        copy(exif, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY);
        copy(exif, ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT);
        copy(exif, ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT_LENGTH);
        copy(exif, ExifInterface.TAG_LENS_MAKE);
        copy(exif, ExifInterface.TAG_LENS_MODEL);
        copy(exif, ExifInterface.TAG_LENS_SERIAL_NUMBER);
        copy(exif, ExifInterface.TAG_LENS_SPECIFICATION);
        copy(exif, ExifInterface.TAG_LIGHT_SOURCE);
        copy(exif, ExifInterface.TAG_MAKE);
        copy(exif, ExifInterface.TAG_MAKER_NOTE);
        copy(exif, ExifInterface.TAG_MAX_APERTURE_VALUE);
        copy(exif, ExifInterface.TAG_METERING_MODE);
        copy(exif, ExifInterface.TAG_MODEL);
        copy(exif, ExifInterface.TAG_NEW_SUBFILE_TYPE);
        copy(exif, ExifInterface.TAG_OECF);
        copy(exif, ExifInterface.TAG_OFFSET_TIME);
        copy(exif, ExifInterface.TAG_OFFSET_TIME_DIGITIZED);
        copy(exif, ExifInterface.TAG_OFFSET_TIME_ORIGINAL);
        copy(exif, ExifInterface.TAG_ORF_ASPECT_FRAME);
        copy(exif, ExifInterface.TAG_ORF_PREVIEW_IMAGE_LENGTH);
        copy(exif, ExifInterface.TAG_ORF_PREVIEW_IMAGE_START);
        copy(exif, ExifInterface.TAG_ORF_THUMBNAIL_IMAGE);
        copy(exif, ExifInterface.TAG_ORIENTATION);
        copy(exif, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY);
        copy(exif, ExifInterface.TAG_PHOTOMETRIC_INTERPRETATION);
        copy(exif, ExifInterface.TAG_PIXEL_X_DIMENSION);
        copy(exif, ExifInterface.TAG_PIXEL_Y_DIMENSION);
        copy(exif, ExifInterface.TAG_PLANAR_CONFIGURATION);
        copy(exif, ExifInterface.TAG_PRIMARY_CHROMATICITIES);
        copy(exif, ExifInterface.TAG_RECOMMENDED_EXPOSURE_INDEX);
        copy(exif, ExifInterface.TAG_REFERENCE_BLACK_WHITE);
        copy(exif, ExifInterface.TAG_RELATED_SOUND_FILE);
        copy(exif, ExifInterface.TAG_RESOLUTION_UNIT);
        copy(exif, ExifInterface.TAG_ROWS_PER_STRIP);
        copy(exif, ExifInterface.TAG_RW2_ISO);
        copy(exif, ExifInterface.TAG_RW2_JPG_FROM_RAW);
        copy(exif, ExifInterface.TAG_RW2_SENSOR_BOTTOM_BORDER);
        copy(exif, ExifInterface.TAG_RW2_SENSOR_LEFT_BORDER);
        copy(exif, ExifInterface.TAG_RW2_SENSOR_RIGHT_BORDER);
        copy(exif, ExifInterface.TAG_RW2_SENSOR_TOP_BORDER);
        copy(exif, ExifInterface.TAG_SAMPLES_PER_PIXEL);
        copy(exif, ExifInterface.TAG_SATURATION);
        copy(exif, ExifInterface.TAG_SCENE_CAPTURE_TYPE);
        copy(exif, ExifInterface.TAG_SCENE_TYPE);
        copy(exif, ExifInterface.TAG_SENSING_METHOD);
        copy(exif, ExifInterface.TAG_SENSITIVITY_TYPE);
        copy(exif, ExifInterface.TAG_SHARPNESS);
        copy(exif, ExifInterface.TAG_SHUTTER_SPEED_VALUE);
        copy(exif, ExifInterface.TAG_SOFTWARE);
        copy(exif, ExifInterface.TAG_SPATIAL_FREQUENCY_RESPONSE);
        copy(exif, ExifInterface.TAG_SPECTRAL_SENSITIVITY);
        copy(exif, ExifInterface.TAG_STANDARD_OUTPUT_SENSITIVITY);
        copy(exif, ExifInterface.TAG_STRIP_BYTE_COUNTS);
        copy(exif, ExifInterface.TAG_STRIP_OFFSETS);
        copy(exif, ExifInterface.TAG_SUBFILE_TYPE);
        copy(exif, ExifInterface.TAG_SUBJECT_AREA);
        copy(exif, ExifInterface.TAG_SUBJECT_DISTANCE);
        copy(exif, ExifInterface.TAG_SUBJECT_DISTANCE_RANGE);
        copy(exif, ExifInterface.TAG_SUBJECT_LOCATION);
        copy(exif, ExifInterface.TAG_SUBSEC_TIME);
        copy(exif, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED);
        copy(exif, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL);
        copy(exif, ExifInterface.TAG_THUMBNAIL_IMAGE_LENGTH);
        copy(exif, ExifInterface.TAG_IMAGE_LENGTH);
        copy(exif, ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH);
        copy(exif, ExifInterface.TAG_IMAGE_WIDTH);
        copy(exif, ExifInterface.TAG_TRANSFER_FUNCTION);
        copy(exif, ExifInterface.TAG_USER_COMMENT);
        copy(exif, ExifInterface.TAG_WHITE_BALANCE);
        copy(exif, ExifInterface.TAG_WHITE_POINT);
        copy(exif, ExifInterface.TAG_XMP);
        copy(exif, ExifInterface.TAG_X_RESOLUTION);
        copy(exif, ExifInterface.TAG_Y_CB_CR_COEFFICIENTS);
        copy(exif, ExifInterface.TAG_Y_CB_CR_POSITIONING);
        copy(exif, ExifInterface.TAG_Y_CB_CR_SUB_SAMPLING);
        copy(exif, ExifInterface.TAG_Y_RESOLUTION);
    }

    private void copy(Exif exif, String attribute) {
        ExifInterface exifInterface = exif.mExifInterface;
        if (exifInterface.hasAttribute(attribute)) {
            putAttribute(attribute, exifInterface.getAttribute(attribute));
        }
    }

    @NonNull
    @Override
    public String toString() {
        return String.format(Locale.ENGLISH, "Exif{width=%s, height=%s, rotation=%d, "
                        + "isFlippedVertically=%s, isFlippedHorizontally=%s, location=%s, "
                        + "timestamp=%s, description=%s, title=%s, photographer=%s, "
                        + "copyright=%s, ambientConditions=%s}",
                getWidth(), getHeight(), getRotation(), isFlippedVertically(), isFlippedHorizontally(),
                getLocation(), getTimestamp(), getDescription(), getTitle(), getPhotographer(),
                getCopyright(), getAmbientConditions());
    }

    private int getOrientation() {
        return mExifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED);
    }

    /**
     * Reads a tag by its standard Exif name, such as "FNumber".
     *
     * @return the value as text, or null when the tag is absent
     */
    @Nullable
    public String getAttribute(@NonNull String tag) {
        int[] standard = ExifTags.standardTag(tag);
        if (standard != null) {
            return getTag(standard[0]);
        }
        return mExifInterface.getAttribute(tag);
    }

    /**
     * Sets a tag by its standard Exif name, such as "FNumber", to be written by {@link #save()}.
     * Null removes it.
     */
    public void setAttribute(@NonNull String tag, @Nullable String value) {
        int[] standard = ExifTags.standardTag(tag);
        if (standard == null) {
            putAttribute(tag, value);
        } else if (value == null) {
            putTag(standard[0], null);
        } else {
            ExifTags.Value encoded = standard[1] == TYPE_ASCII
                    ? ExifTags.text(value) : ExifTags.encode(standard[1], value);
            if (encoded != null) {
                putTag(standard[0], encoded);
            }
        }
    }

    /**
     * Sets a tag ExifInterface writes, and forgets any value set by number for the same tag, so
     * the later of the two is the one saved.
     */
    private void putAttribute(@NonNull String tag, @Nullable String value) {
        mExifInterface.setAttribute(tag, value);
        if (value != null && value.indexOf('/') >= 0 && !holds(tag, value)) {
            // ExifInterface before 1.4, which the API 14 build uses, takes FNumber,
            // DigitalZoomRatio, ExposureTime and SubjectDistance only as a decimal and drops a
            // fraction. The fraction is tried first because a decimal is stored in ten-thousandths.
            mExifInterface.setAttribute(tag, Double.toString(ExifCaptureParameters.number(value)));
        }
        int number = ExifTags.attributeTag(tag);
        if (number >= 0) {
            mTags.remove(number);
            mRemovedTags.remove(number);
        }
    }

    /** Whether a tag now reads back as the value just set, as a number. */
    private boolean holds(@NonNull String tag, @NonNull String value) {
        double expected = ExifCaptureParameters.number(value);
        double actual = ExifCaptureParameters.number(mExifInterface.getAttribute(tag));
        return Double.isNaN(expected)
                || Math.abs(actual - expected) <= 1e-6 * Math.max(1, Math.abs(expected));
    }

    /**
     * Fills in what the library knows of any capture and the camera left out: the device's make
     * and model, the app, with its version, as the software, that a camera photographed the
     * scene directly, and an id unique to this picture.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    public void attachCaptureDevice(@NonNull Context context) {
        if (isBlank(mExifInterface.getAttribute(ExifInterface.TAG_MAKE))) {
            mExifInterface.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER);
        }
        if (isBlank(mExifInterface.getAttribute(ExifInterface.TAG_MODEL))) {
            mExifInterface.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL);
        }
        if (isBlank(mExifInterface.getAttribute(ExifInterface.TAG_SOFTWARE))) {
            mExifInterface.setAttribute(ExifInterface.TAG_SOFTWARE, softwareName(context));
        }
        if (mExifInterface.getAttribute(ExifInterface.TAG_FILE_SOURCE) == null) {
            mExifInterface.setAttribute(
                    ExifInterface.TAG_FILE_SOURCE, FILE_SOURCE_DIGITAL_STILL_CAMERA);
        }
        if (mExifInterface.getAttribute(ExifInterface.TAG_SCENE_TYPE) == null) {
            mExifInterface.setAttribute(
                    ExifInterface.TAG_SCENE_TYPE, SCENE_TYPE_DIRECTLY_PHOTOGRAPHED);
        }
        if (isBlank(mExifInterface.getAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID))) {
            mExifInterface.setAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID, newImageUniqueId());
        }
    }

    /**
     * A fresh 128-bit id as the 32 hexadecimal digits ImageUniqueID holds. Random, since nothing
     * about the device or the moment should be recoverable from it.
     */
    @VisibleForTesting
    @NonNull
    static String newImageUniqueId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** The app's name and version, as the software that made a capture, or null if unknown. */
    @Nullable
    static String softwareName(@NonNull Context context) {
        try {
            PackageManager packageManager = context.getPackageManager();
            String label = context.getApplicationInfo().loadLabel(packageManager).toString();
            String version =
                    packageManager.getPackageInfo(context.getPackageName(), 0).versionName;
            return version == null ? label : label + " " + version;
        } catch (PackageManager.NameNotFoundException | RuntimeException error) {
            Log.w(TAG, "Unable to name the app", error);
            return null;
        }
    }

    /** Has {@link #save()} write a thumbnail if the file has none. Call it any time before. */
    @AnyThread
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    public void attachThumbnail() {
        mThumbnailRequested = true;
    }

    /** Whether the file holds a thumbnail, as of when it was opened or last saved. */
    @VisibleForTesting
    boolean hasThumbnail() {
        return mHasThumbnail;
    }

    /**
     * The file with its thumbnail written into the block saveAttributes has just rewritten: the
     * one that gave way to the tags, put back as it was or re-encoded to fit; the one the file
     * holds, declared again; or a new one if {@link #attachThumbnail()} asked for it. The bytes
     * go straight into the file, since ExifInterface cannot add a thumbnail.
     *
     * @param givenWay the thumbnail taken out to make room for the tags, or null
     * @return the updated file, or the one given when there is nothing to do or no room
     */
    @WorkerThread
    @NonNull
    private byte[] withThumbnail(@NonNull File file, @NonNull byte[] jpeg,
            @Nullable byte[] givenWay) {
        if (!mHasThumbnail && !mThumbnailRequested) {
            return jpeg;
        }
        try {
            int orientation = getOrientation();
            if (givenWay != null) {
                byte[] restored = ExifThumbnail.attach(jpeg, givenWay, orientation);
                if (restored != null) {
                    return restored;
                }
            } else {
                // A camera's own thumbnail often loses its Compression tag when the block is
                // rewritten, and naming it again is cheaper than encoding a new one.
                byte[] declared = ExifThumbnail.declareExistingThumbnail(jpeg, orientation);
                if (declared != null) {
                    return declared;
                }
            }
            int room = ExifThumbnail.roomForThumbnail(jpeg);
            if (room <= 0) {
                if (DEBUG) Log.d(TAG, "No room beside this file's Exif for a thumbnail");
                return jpeg;
            }
            byte[] thumbnail = encodeThumbnail(file, room);
            if (thumbnail == null) {
                return jpeg;
            }
            byte[] attached = ExifThumbnail.attach(jpeg, thumbnail, orientation);
            return attached == null ? jpeg : attached;
        } catch (RuntimeException | OutOfMemoryError error) {
            // A photograph without a thumbnail is a photograph. Losing one is never worth
            // failing a save that has otherwise succeeded.
            Log.w(TAG, "Unable to save a thumbnail", error);
            return jpeg;
        }
    }

    /**
     * Shrinks the picture to the 160x120 the specification suggests and encodes it as JPEG,
     * sampled down while decoding so the full image is never held in memory.
     */
    @Nullable
    private byte[] encodeThumbnail(@NonNull File file, int room) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight);
        Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
        if (bitmap == null) {
            return null;
        }
        try {
            // Quality first, since a softer thumbnail at full size reads better than a sharp one
            // at half, and the room a maker note leaves varies by device.
            for (int quality : THUMBNAIL_QUALITIES) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bytes)) {
                    return null;
                }
                if (bytes.size() <= room) {
                    return bytes.toByteArray();
                }
            }
            if (DEBUG) Log.d(TAG, "A thumbnail will not fit in " + room + " bytes");
            return null;
        } finally {
            bitmap.recycle();
        }
    }

    /** The first halving that brings both sides to the target or below. */
    @VisibleForTesting
    static int sampleSizeFor(int width, int height) {
        int sample = 1;
        while (width / (sample * 2) >= THUMBNAIL_WIDTH
                && height / (sample * 2) >= THUMBNAIL_HEIGHT) {
            sample *= 2;
        }
        return sample;
    }

    /**
     * Reads a tag of the Exif private directory by its number, such as 0x829D for FNumber: as
     * the file stores it, or as {@link #setTag} has set it since.
     *
     * @return the value as text in the shape the file stores it, which {@link TagType} describes
     *     for each type, or null when the tag is absent
     */
    @AnyThread
    @Nullable
    public String getTag(int tag) {
        ExifTags.Value value = tagValue(tag);
        return value == null ? null : value.text();
    }

    /**
     * Reads a tag of the Exif private directory by its number, as bytes, for a value that isn't
     * text, such as one of type {@link #TYPE_UNDEFINED}. Numbers come most significant byte
     * first.
     *
     * @return the bytes, or null when the tag is absent
     */
    @AnyThread
    @Nullable
    public byte[] getTagBytes(int tag) {
        ExifTags.Value value = tagValue(tag);
        return value == null ? null : value.bytes.clone();
    }

    /**
     * Sets a tag of the Exif private directory by its number, to be written by {@link #save()}.
     * This reaches any tag, including one newer than this library. The value is saved in place
     * of the same tag set any other way before this call.
     *
     * @param type the type to store the value as
     * @param value the value as text, in the shape {@link TagType} describes for the type, or
     *     null to remove the tag
     * @return false, with nothing changed, when the value doesn't suit the type, such as a word
     *     for a number, or the tag locates other data rather than holding a value
     */
    @AnyThread
    public boolean setTag(int tag, @TagType int type, @Nullable String value) {
        if (!holdsValue(tag)) {
            return false;
        }
        if (value == null) {
            putTag(tag, null);
            return true;
        }
        ExifTags.Value encoded = ExifTags.encode(type, value);
        if (encoded == null) {
            return false;
        }
        putTag(tag, encoded);
        return true;
    }

    /**
     * Sets a tag of the Exif private directory by its number to bytes of type
     * {@link #TYPE_UNDEFINED}, to be written by {@link #save()}. Otherwise as
     * {@link #setTag(int, int, String)}.
     *
     * @param value the bytes, or null to remove the tag
     * @return false, with nothing changed, when there are no bytes, or the tag locates other data
     *     rather than holding a value
     */
    @AnyThread
    public boolean setTag(int tag, @Nullable byte[] value) {
        if (!holdsValue(tag) || (value != null && value.length == 0)) {
            return false;
        }
        putTag(tag, value == null ? null : ExifTags.undefined(value));
        return true;
    }

    /** Whether a number names a private directory entry that holds a value of its own. */
    private static boolean holdsValue(int tag) {
        return tag >= 0 && tag <= 0xFFFF && tag != ExifTags.TAG_INTEROPERABILITY_POINTER;
    }

    /** A tag's value as {@link #getTag} reads it, or null. */
    @Nullable
    private ExifTags.Value tagValue(int tag) {
        if (mTags.containsKey(tag)) {
            return mTags.get(tag);
        }
        return mRemovedTags.contains(tag) ? null : mDirectory.get(tag);
    }

    /** Sets a private directory entry for every save to write, or to remove when null. */
    private void putTag(int tag, @Nullable ExifTags.Value value) {
        if (value == null) {
            mTags.remove(tag);
            mRemovedTags.add(tag);
        } else {
            mTags.put(tag, value);
            mRemovedTags.remove(tag);
        }
    }

    /** Sets text for every save to write, or removes it when null. */
    private void putText(int tag, @Nullable String text) {
        putTag(tag, text == null ? null : ExifTags.text(text));
    }

    /** A reading of the conditions, or NaN when it is absent. */
    private double reading(int tag) {
        ExifTags.Value value = tagValue(tag);
        return value == null ? Double.NaN : value.number();
    }

    /** Sets a reading of the conditions, or removes it when absent or the tag can't hold it. */
    private void putReading(int tag, double reading, boolean signed) {
        putTag(tag, Double.isNaN(reading) ? null : ExifTags.rational(reading, signed));
    }

    /** Returns the image's title, or null. */
    @AnyThread
    @Nullable
    public String getTitle() {
        return getTag(ExifTags.TAG_IMAGE_TITLE);
    }

    /** Sets the image's title, or removes it when null. */
    @AnyThread
    public void setTitle(@Nullable String title) {
        putText(ExifTags.TAG_IMAGE_TITLE, title);
    }

    /** Returns who took the picture, or null. */
    @AnyThread
    @Nullable
    public String getPhotographer() {
        String photographer = getTag(ExifTags.TAG_PHOTOGRAPHER);
        return !isBlank(photographer)
                ? photographer : mExifInterface.getAttribute(ExifInterface.TAG_ARTIST);
    }

    /** Sets who took the picture, or removes it when null. */
    @AnyThread
    public void setPhotographer(@Nullable String photographer) {
        // Artist too, the older tag, which every reader knows.
        putText(ExifTags.TAG_PHOTOGRAPHER, photographer);
        putAttribute(ExifInterface.TAG_ARTIST, photographer);
    }

    /** Returns the image's copyright notice, or null. */
    @AnyThread
    @Nullable
    public String getCopyright() {
        return mExifInterface.getAttribute(ExifInterface.TAG_COPYRIGHT);
    }

    /** Sets the image's copyright notice, or removes it when null. */
    @AnyThread
    public void setCopyright(@Nullable String copyright) {
        putAttribute(ExifInterface.TAG_COPYRIGHT, copyright);
    }

    /** Returns the conditions the picture was taken in, or null if none were recorded. */
    @AnyThread
    @Nullable
    public AmbientConditions getAmbientConditions() {
        AmbientConditions conditions = new AmbientConditions.Builder()
                .setTemperature(reading(ExifTags.TAG_TEMPERATURE))
                .setHumidity(reading(ExifTags.TAG_HUMIDITY))
                .setPressure(reading(ExifTags.TAG_PRESSURE))
                .setWaterDepth(reading(ExifTags.TAG_WATER_DEPTH))
                .setAcceleration(reading(ExifTags.TAG_ACCELERATION))
                .setCameraElevationAngle(reading(ExifTags.TAG_CAMERA_ELEVATION_ANGLE))
                .build();
        return conditions.isEmpty() ? null : conditions;
    }

    /**
     * Records the conditions the picture was taken in, in place of any recorded before. A
     * reading the conditions leave out is removed, as is one outside what the file can hold,
     * such as a negative humidity.
     */
    @AnyThread
    public void attachAmbientConditions(@NonNull AmbientConditions conditions) {
        putReading(ExifTags.TAG_TEMPERATURE, conditions.getTemperature(), true);
        putReading(ExifTags.TAG_HUMIDITY, conditions.getHumidity(), false);
        putReading(ExifTags.TAG_PRESSURE, conditions.getPressure(), false);
        putReading(ExifTags.TAG_WATER_DEPTH, conditions.getWaterDepth(), true);
        putReading(ExifTags.TAG_ACCELERATION, conditions.getAcceleration(), false);
        putReading(ExifTags.TAG_CAMERA_ELEVATION_ANGLE, conditions.getCameraElevationAngle(),
                true);
    }

    /** Removes the conditions the picture was taken in. */
    @AnyThread
    public void removeAmbientConditions() {
        putTag(ExifTags.TAG_TEMPERATURE, null);
        putTag(ExifTags.TAG_HUMIDITY, null);
        putTag(ExifTags.TAG_PRESSURE, null);
        putTag(ExifTags.TAG_WATER_DEPTH, null);
        putTag(ExifTags.TAG_ACCELERATION, null);
        putTag(ExifTags.TAG_CAMERA_ELEVATION_ANGLE, null);
    }

    /** The start of a file, enough to hold its Exif segment if it has one. */
    @NonNull
    private static byte[] readHead(@NonNull String filePath) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(filePath, "r")) {
            byte[] head = new byte[(int) Math.min(file.length(), HEAD_BYTES)];
            file.readFully(head);
            return head;
        }
    }

    /** The start of a stream, as much of it as there is up to what a file's head holds. */
    @NonNull
    private static byte[] readHead(@NonNull InputStream in) throws IOException {
        byte[] head = new byte[HEAD_BYTES];
        int read = 0;
        while (read < head.length) {
            int count = in.read(head, read, head.length - read);
            // Nothing read is taken for the end too, since a stream that never ends would
            // otherwise hold this here.
            if (count <= 0) {
                break;
            }
            read += count;
        }
        return read == head.length ? head : Arrays.copyOf(head, read);
    }

    /** Where a file's channel is, or -1 when there is no channel or it can't seek. */
    private static long position(@Nullable FileChannel channel) {
        if (channel == null) {
            return -1;
        }
        try {
            return channel.position();
        } catch (IOException error) {
            return -1;
        }
    }

    /** Moves a file's channel back to where it was, or says it can't. */
    private static boolean rewind(@NonNull FileChannel channel, long position) {
        try {
            channel.position(position);
            return true;
        } catch (IOException error) {
            return false;
        }
    }

    @Nullable
    private static byte[] read(@NonNull File file) throws IOException {
        long length = file.length();
        if (length <= 0 || length > Integer.MAX_VALUE) {
            return null;
        }
        byte[] data = new byte[(int) length];
        FileInputStream in = new FileInputStream(file);
        try {
            int read = 0;
            while (read < data.length) {
                int count = in.read(data, read, data.length - read);
                if (count < 0) {
                    return null;
                }
                read += count;
            }
        } finally {
            in.close();
        }
        return data;
    }

    /**
     * Returns the width of the photo in pixels.
     */
    public int getWidth() {
        return mExifInterface.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0);
    }

    /**
     * Returns the height of the photo in pixels.
     */
    public int getHeight() {
        return mExifInterface.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0);
    }

    /** Returns the image's description, or null. */
    @Nullable
    public String getDescription() {
        return mExifInterface.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION);
    }

    /** Sets the image's description, or removes it when null. */
    public void setDescription(@Nullable String description) {
        mExifInterface.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, description);
    }

    /**
     * @return The degree of rotation (eg. 0, 90, 180, 270).
     */
    public int getRotation() {
        switch (getOrientation()) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                return 0;
            case ExifInterface.ORIENTATION_ROTATE_180:
                return 180;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                return 180;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                return 270;
            case ExifInterface.ORIENTATION_ROTATE_90:
                return 90;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                return 90;
            case ExifInterface.ORIENTATION_ROTATE_270:
                return 270;
            case ExifInterface.ORIENTATION_NORMAL:
                // Fall-through
            case ExifInterface.ORIENTATION_UNDEFINED:
                // Fall-through
            default:
                return 0;
        }
    }

    /**
     * @return True if the image is flipped vertically after rotation.
     */
    public boolean isFlippedVertically() {
        switch (getOrientation()) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                return false;
            case ExifInterface.ORIENTATION_ROTATE_180:
                return false;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                return true;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                return true;
            case ExifInterface.ORIENTATION_ROTATE_90:
                return false;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                return true;
            case ExifInterface.ORIENTATION_ROTATE_270:
                return false;
            case ExifInterface.ORIENTATION_NORMAL:
                // Fall-through
            case ExifInterface.ORIENTATION_UNDEFINED:
                // Fall-through
            default:
                return false;
        }
    }

    /**
     * @return True if the image is flipped horizontally after rotation.
     */
    public boolean isFlippedHorizontally() {
        switch (getOrientation()) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                return true;
            case ExifInterface.ORIENTATION_ROTATE_180:
                return false;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                return false;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                return false;
            case ExifInterface.ORIENTATION_ROTATE_90:
                return false;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                return false;
            case ExifInterface.ORIENTATION_ROTATE_270:
                return false;
            case ExifInterface.ORIENTATION_NORMAL:
                // Fall-through
            case ExifInterface.ORIENTATION_UNDEFINED:
                // Fall-through
            default:
                return false;
        }
    }

    private void attachLastModifiedTimestamp() {
        long now = System.currentTimeMillis();
        String datetime = convertToExifDateTime(now);

        mExifInterface.setAttribute(ExifInterface.TAG_DATETIME, datetime);
        mExifInterface.setAttribute(ExifInterface.TAG_OFFSET_TIME, offsetFromUtc(now));

        try {
            long millis = now - convertFromExifDateTime(datetime).getTime();
            mExifInterface.setAttribute(ExifInterface.TAG_SUBSEC_TIME, formatSubSecond(millis));
        } catch (ParseException e) {
            Log.w(TAG, "Unable to record the sub-second timestamp", e);
        }
    }

    /**
     * @return The timestamp (in millis) that this picture was modified, or -1 if no time is available.
     */
    public long getLastModifiedTimestamp() {
        long timestamp = parseTimestamp(mExifInterface.getAttribute(ExifInterface.TAG_DATETIME));
        if (timestamp == -1) {
            return -1;
        }

        return timestamp
                + parseSubSecond(mExifInterface.getAttribute(ExifInterface.TAG_SUBSEC_TIME));
    }

    /**
     * Writes a sub-second count as EXIF expects it.
     *
     * <p>SubSecTime is the digits after the decimal point, so 50 milliseconds is "050" and not
     * "50", which any other reader would take for half a second.</p>
     */
    static String formatSubSecond(long millis) {
        long clamped = Math.max(0, Math.min(millis, 999));
        return String.format(Locale.ENGLISH, "%03d", clamped);
    }

    /**
     * Reads an EXIF sub-second string as milliseconds.
     *
     * <p>The digit count sets the scale, because the value is a fraction of a second rather than
     * a count of anything: "5" is half a second, "05" is fifty milliseconds, and "005" is five.
     * Treating the digits as milliseconds, as this used to, under-read every value with fewer
     * than three digits by a factor of ten or a hundred.</p>
     */
    static long parseSubSecond(@Nullable String subSeconds) {
        if (subSeconds == null) {
            return 0;
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < subSeconds.length() && digits.length() < 3; i++) {
            char character = subSeconds.charAt(i);
            if (character < '0' || character > '9') {
                break;
            }
            digits.append(character);
        }
        if (digits.length() == 0) {
            return 0;
        }
        long value = Long.parseLong(digits.toString());
        for (int i = digits.length(); i < 3; i++) {
            value *= 10;
        }
        return value;
    }

    /**
     * @return The timestamp (in millis) that this picture was taken, or -1 if no time is available.
     */
    public long getTimestamp() {
        long timestamp = parseTimestamp(mExifInterface.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL));
        if (timestamp == -1) {
            return -1;
        }

        return timestamp + parseSubSecond(
                mExifInterface.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL));
    }

    /**
     * @return The location this picture was taken, or null if no location is available.
     */
    @Nullable
    public Location getLocation() {
        String provider = mExifInterface.getAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD);
        double[] latlng = mExifInterface.getLatLong();
        double altitude = mExifInterface.getAltitude(0);
        double speed = mExifInterface.getAttributeDouble(ExifInterface.TAG_GPS_SPEED, 0);
        String speedRef = mExifInterface.getAttribute(ExifInterface.TAG_GPS_SPEED_REF);
        speedRef = speedRef == null ? KILOMETERS_PER_HOUR : speedRef; // Ensure speedRef is not null
        long timestamp = parseTimestamp(
                mExifInterface.getAttribute(ExifInterface.TAG_GPS_DATESTAMP),
                mExifInterface.getAttribute(ExifInterface.TAG_GPS_TIMESTAMP));
        if (latlng == null) {
            return null;
        }
        if (provider == null) {
            provider = TAG;
        }

        Location location = new Location(provider);
        location.setLatitude(latlng[0]);
        location.setLongitude(latlng[1]);
        if (altitude != 0) {
            location.setAltitude(altitude);
        }
        if (speed != 0) {
            switch (speedRef) {
                case MILES_PER_HOUR:
                    speed = Speed.fromMilesPerHour(speed).toMetersPerSecond();
                    break;
                case KNOTS:
                    speed = Speed.fromKnots(speed).toMetersPerSecond();
                    break;
                case KILOMETERS_PER_HOUR:
                    // fall through
                default:
                    speed = Speed.fromKilometersPerHour(speed).toMetersPerSecond();
                    break;
            }
            location.setSpeed((float) speed);
        }
        if (timestamp != -1) {
            location.setTime(timestamp);
        }
        return location;
    }

    /**
     * Rotates the image by the given degrees. Can only rotate by right angles (eg. 90, 180, -90).
     * Other increments will set the orientation to undefined.
     */
    public void rotate(int degrees) {
        if (degrees % 90 != 0) {
            Log.w(TAG, String.format("Can only rotate in right angles (eg. 0, 90, 180, 270). %d is unsupported.", degrees));
            mExifInterface.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_UNDEFINED));
            return;
        }

        degrees %= 360;

        int orientation = getOrientation();
        while (degrees < 0) {
            degrees += 90;

            switch (orientation) {
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                    orientation = ExifInterface.ORIENTATION_TRANSPOSE;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    orientation = ExifInterface.ORIENTATION_ROTATE_90;
                    break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                    orientation = ExifInterface.ORIENTATION_TRANSVERSE;
                    break;
                case ExifInterface.ORIENTATION_TRANSPOSE:
                    orientation = ExifInterface.ORIENTATION_FLIP_VERTICAL;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_90:
                    orientation = ExifInterface.ORIENTATION_NORMAL;
                    break;
                case ExifInterface.ORIENTATION_TRANSVERSE:
                    orientation = ExifInterface.ORIENTATION_FLIP_HORIZONTAL;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    orientation = ExifInterface.ORIENTATION_ROTATE_180;
                    break;
                case ExifInterface.ORIENTATION_NORMAL:
                    // Fall-through
                case ExifInterface.ORIENTATION_UNDEFINED:
                    // Fall-through
                default:
                    orientation = ExifInterface.ORIENTATION_ROTATE_270;
                    break;
            }
        }
        while (degrees > 0) {
            degrees -= 90;

            switch (orientation) {
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                    orientation = ExifInterface.ORIENTATION_TRANSVERSE;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    orientation = ExifInterface.ORIENTATION_ROTATE_270;
                    break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                    orientation = ExifInterface.ORIENTATION_TRANSPOSE;
                    break;
                case ExifInterface.ORIENTATION_TRANSPOSE:
                    orientation = ExifInterface.ORIENTATION_FLIP_HORIZONTAL;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_90:
                    orientation = ExifInterface.ORIENTATION_ROTATE_180;
                    break;
                case ExifInterface.ORIENTATION_TRANSVERSE:
                    orientation = ExifInterface.ORIENTATION_FLIP_VERTICAL;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    orientation = ExifInterface.ORIENTATION_NORMAL;
                    break;
                case ExifInterface.ORIENTATION_NORMAL:
                    // Fall-through
                case ExifInterface.ORIENTATION_UNDEFINED:
                    // Fall-through
                default:
                    orientation = ExifInterface.ORIENTATION_ROTATE_90;
                    break;
            }
        }
        mExifInterface.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(orientation));
    }

    /**
     * Flips the image over the horizon so that the top and bottom are reversed.
     */
    public void flipVertically() {
        int orientation;
        switch (getOrientation()) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                orientation = ExifInterface.ORIENTATION_ROTATE_180;
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                orientation = ExifInterface.ORIENTATION_FLIP_HORIZONTAL;
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                orientation = ExifInterface.ORIENTATION_NORMAL;
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                orientation = ExifInterface.ORIENTATION_ROTATE_270;
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                orientation = ExifInterface.ORIENTATION_TRANSVERSE;
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                orientation = ExifInterface.ORIENTATION_ROTATE_90;
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                orientation = ExifInterface.ORIENTATION_TRANSPOSE;
                break;
            case ExifInterface.ORIENTATION_NORMAL:
                // Fall-through
            case ExifInterface.ORIENTATION_UNDEFINED:
                // Fall-through
            default:
                orientation = ExifInterface.ORIENTATION_FLIP_VERTICAL;
                break;
        }
        mExifInterface.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(orientation));
    }

    /**
     * Flips the image over the vertical so that the left and right are reversed.
     */
    public void flipHorizontally() {
        int orientation;
        switch (getOrientation()) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                orientation = ExifInterface.ORIENTATION_NORMAL;
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                orientation = ExifInterface.ORIENTATION_FLIP_VERTICAL;
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                orientation = ExifInterface.ORIENTATION_ROTATE_180;
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                orientation = ExifInterface.ORIENTATION_ROTATE_90;
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                orientation = ExifInterface.ORIENTATION_TRANSPOSE;
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                orientation = ExifInterface.ORIENTATION_ROTATE_270;
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                orientation = ExifInterface.ORIENTATION_TRANSVERSE;
                break;
            case ExifInterface.ORIENTATION_NORMAL:
                // Fall-through
            case ExifInterface.ORIENTATION_UNDEFINED:
                // Fall-through
            default:
                orientation = ExifInterface.ORIENTATION_FLIP_HORIZONTAL;
                break;
        }
        mExifInterface.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(orientation));
    }

    /**
     * Attaches the current timestamp to the file.
     */
    public void attachTimestamp() {
        long now = System.currentTimeMillis();
        String datetime = convertToExifDateTime(now);

        putAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, datetime);
        putAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, datetime);
        // And the IFD0 one, which removeTimestamp has always cleared: a file that kept DateTime
        // while losing the other two was stripped of nothing a reader actually looks at first.
        putAttribute(ExifInterface.TAG_DATETIME, datetime);
        String offset = offsetFromUtc(now);
        putAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset);
        putAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, offset);
        putAttribute(ExifInterface.TAG_OFFSET_TIME, offset);

        try {
            String subsec = formatSubSecond(now - convertFromExifDateTime(datetime).getTime());
            putAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, subsec);
            putAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, subsec);
            putAttribute(ExifInterface.TAG_SUBSEC_TIME, subsec);
        } catch (ParseException e) {
            Log.w(TAG, "Unable to record the sub-second timestamp", e);
        }

        mRemoveTimestamp = false;
    }

    /**
     * Removes the timestamp from the file.
     */
    public void removeTimestamp() {
        putAttribute(ExifInterface.TAG_DATETIME, null);
        putAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, null);
        putAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, null);
        putAttribute(ExifInterface.TAG_SUBSEC_TIME, null);
        putAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, null);
        putAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, null);
        putAttribute(ExifInterface.TAG_OFFSET_TIME, null);
        putAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, null);
        putAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, null);
        mRemoveTimestamp = true;
    }

    /**
     * Attaches the given location to the file.
     */
    public void attachLocation(Location location) {
        mExifInterface.setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD, location.getProvider());
        mExifInterface.setLatLong(location.getLatitude(), location.getLongitude());
        // 2.3.0.0, the version every GPS IFD written this century declares. A reader that finds
        // the IFD without it has to guess at the layout of what follows.
        mExifInterface.setAttribute(ExifInterface.TAG_GPS_VERSION_ID, GPS_VERSION_2_3);
        // Android fixes are WGS-84, and a coordinate without its datum is a coordinate that
        // cannot be placed on a map to better than a few hundred metres.
        mExifInterface.setAttribute(ExifInterface.TAG_GPS_MAP_DATUM, WGS_84);
        if (location.hasAltitude()) {
            // GPSAltitude is unsigned; the sign goes in the reference byte.
            double altitude = altitudeAboveSeaLevel(location);
            mExifInterface.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF,
                    altitude < 0 ? ALTITUDE_BELOW_SEA_LEVEL : ALTITUDE_ABOVE_SEA_LEVEL);
            mExifInterface.setAttribute(ExifInterface.TAG_GPS_ALTITUDE,
                    rational(Math.abs(altitude)));
        }
        if (location.hasSpeed()) {
            mExifInterface.setAttribute(ExifInterface.TAG_GPS_SPEED_REF, KILOMETERS_PER_HOUR);
            mExifInterface.setAttribute(ExifInterface.TAG_GPS_SPEED, rational(
                    Speed.fromMetersPerSecond(location.getSpeed()).toKilometersPerHour()));
        }
        if (location.hasBearing()) {
            // Where the device was heading, which is not where the camera was pointing -
            // GPSImgDirection is that, and a fix cannot know it.
            mExifInterface.setAttribute(ExifInterface.TAG_GPS_TRACK_REF, DIRECTION_TRUE_NORTH);
            mExifInterface.setAttribute(ExifInterface.TAG_GPS_TRACK,
                    rational(location.getBearing()));
        }
        if (location.hasAccuracy()) {
            // How much the coordinate above should be trusted, in metres. Without it a fix good
            // to a street and a fix good to a city look exactly alike.
            mExifInterface.setAttribute(ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
                    rational(location.getAccuracy()));
        }
        if (LocationManager.GPS_PROVIDER.equals(location.getProvider())) {
            // Only a satellite fix has a measurement mode. A fused or network fix is not one,
            // even when satellites contributed to it.
            mExifInterface.setAttribute(ExifInterface.TAG_GPS_MEASURE_MODE,
                    location.hasAltitude() ? MEASURE_MODE_3D : MEASURE_MODE_2D);
        }
        Bundle extras = location.getExtras();
        int satellites = extras == null ? 0 : extras.getInt(EXTRA_SATELLITES, 0);
        if (satellites > 0) {
            mExifInterface.setAttribute(
                    ExifInterface.TAG_GPS_SATELLITES, Integer.toString(satellites));
        }
        // A time of zero is a Location nobody set the time on, not a fix taken in 1970.
        if (location.getTime() > 0) {
            Date time = new Date(location.getTime());
            mExifInterface.setAttribute(
                    ExifInterface.TAG_GPS_DATESTAMP, GPS_DATE_FORMAT.get().format(time));
            mExifInterface.setAttribute(
                    ExifInterface.TAG_GPS_TIMESTAMP, GPS_TIME_FORMAT.get().format(time));
        }
    }

    /**
     * The fix's height above sea level, which is what GPSAltitude records. Location#getAltitude
     * is the height above the WGS-84 ellipsoid, up to a hundred metres away from sea level, so
     * the sea-level height is used wherever the fix carries one.
     */
    private static double altitudeAboveSeaLevel(@NonNull Location location) {
        if (Build.VERSION.SDK_INT >= 34 && location.hasMslAltitude()) {
            return location.getMslAltitudeMeters();
        }
        return location.getAltitude();
    }

    /**
     * Removes the location from the file, with everything else recorded about the fix, such as
     * its heading, accuracy and time.
     */
    public void removeLocation() {
        for (String tag : GPS_TAGS) {
            mExifInterface.setAttribute(tag, null);
        }
    }

    /**
     * The offset from UTC.
     *
     * <p>EXIF timestamps are local time with nothing to say where that was, which makes them
     * ambiguous by an hour twice a year and by half the world the rest of the time. Exif 2.31
     * added these three tags to answer it, and a picture without them cannot be ordered against
     * one taken in another timezone.</p>
     */
    static String offsetFromUtc(long timestamp) {
        int offsetMinutes = TimeZone.getDefault().getOffset(timestamp) / 60_000;
        char sign = offsetMinutes < 0 ? '-' : '+';
        int magnitude = Math.abs(offsetMinutes);
        return String.format(
                Locale.US, "%c%02d:%02d", sign, magnitude / 60, magnitude % 60);
    }

    /**
     * Reads a GPS date and time, which are UTC.
     *
     * @return The timestamp (in millis), or -1 if no time is available.
     */
    private long parseTimestamp(@Nullable String date, @Nullable String time) {
        if (date == null && time == null) {
            return -1;
        }
        try {
            if (time == null) {
                return GPS_DATE_FORMAT.get().parse(date).getTime();
            }
            if (date == null) {
                return GPS_TIME_FORMAT.get().parse(time).getTime();
            }
            return GPS_DATETIME_FORMAT.get().parse(date + " " + time).getTime();
        } catch (ParseException e) {
            return -1;
        }
    }

    /**
     * @return The timestamp (in millis), or -1 if no time is available.
     */
    private long parseTimestamp(@Nullable String datetime) {
        if (datetime == null) {
            return -1;
        }
        try {
            return convertFromExifDateTime(datetime).getTime();
        } catch (ParseException e) {
            return -1;
        }
    }

    /**
     * A decimal as the rational EXIF stores.
     *
     * <p>Both halves of an EXIF rational are integers, so writing the decimal itself over a
     * denominator of one - which is what this used to do - leaves a numerator the reader has to
     * truncate. A speed of 48.28 km/h came back as 48, and an altitude of 5.7m as 5.</p>
     */
    private static String rational(double value) {
        return Math.round(value * RATIONAL_SCALE) + "/" + RATIONAL_SCALE;
    }

    private static String convertToExifDateTime(long timestamp) {
        return DATETIME_FORMAT.get().format(new Date(timestamp));
    }

    private static Date convertFromExifDateTime(String dateTime) throws ParseException {
        return DATETIME_FORMAT.get().parse(dateTime);
    }

    private static class Speed {
        static Converter fromKilometersPerHour(double kph) {
            return new Converter(kph * 0.621371);
        }

        static Converter fromMetersPerSecond(double mps) {
            return new Converter(mps * 2.23694);
        }

        static Converter fromMilesPerHour(double mph) {
            return new Converter(mph);
        }

        static Converter fromKnots(double knots) {
            return new Converter(knots * 1.15078);
        }

        static class Converter {
            final double mph;

            Converter(double mph) {
                this.mph = mph;
            }

            double toKilometersPerHour() {
                return mph / 0.621371;
            }

            double toMilesPerHour() {
                return mph;
            }

            double toKnots() {
                return mph / 1.15078;
            }

            double toMetersPerSecond() {
                return mph / 2.23694;
            }
        }
    }
}
