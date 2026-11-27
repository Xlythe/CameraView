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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import androidx.annotation.AnyThread;
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

    /**
     * The {@link ExifTags} tags the file is to carry: those it held when opened, as
     * {@link #setTags} has changed them since. ExifInterface cannot parse them, so its save
     * drops them, and every save writes these back after it.
     */
    private final Map<ExifTags.Tag, ExifTags.Value> mTags;

    /** Whether this is a JPEG file, the one kind whose Exif block {@link ExifTags} can write. */
    private final boolean mCanWriteTags;

    // When true, avoid saving any time. This is a privacy issue.
    private boolean mRemoveTimestamp = false;

    /**
     * What a file's metadata says, as one line, or why it could not be read.
     *
     * <p>For logging, which is the only thing a caller wants the whole of this for. Reading
     * metadata means opening the file, and doing that for a log line costs four lines of
     * ceremony around one of interest - which is a tempting place to swallow the failure
     * silently. This never throws: a file whose metadata cannot be read says so.</p>
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
        mTags = ExifTags.readAll(head);
    }

    public Exif(InputStream is) throws IOException {
        mExifInterface = new ExifInterface(is);
        mFilePath = null;
        mHasThumbnail = mExifInterface.hasThumbnail();
        mCanWriteTags = false;
        mTags = new EnumMap<>(ExifTags.Tag.class);
    }

    /**
     * Persists changes to disc. The {@link ExifTags} tags the file held and those
     * {@link #setTags} set are written, a thumbnail the file already holds is kept, and one
     * {@link #attachThumbnail()} asked for is written.
     *
     * @throws IOException if the file cannot be saved, or its Exif block has no room left for
     *     the {@link ExifTags} tags, in which case everything else is still saved
     */
    @WorkerThread
    public void save() throws IOException {
        if (!mRemoveTimestamp) {
            attachLastModifiedTimestamp();
        }
        attachMandatoryTags();
        mExifInterface.saveAttributes();
        restoreUndefinedTypes();
        try {
            saveTags();
        } catch (IOException error) {
            // As with the thumbnail: a picture without these tags is still a picture, and a save
            // that otherwise succeeded is never failed over metadata that did not fit.
            Log.w(TAG, "Unable to save the Exif 3.0 tags", error);
        }
        saveThumbnail();
    }

    /**
     * Writes the {@link ExifTags} tags into the block saveAttributes has just rewritten without
     * them. They follow restoreUndefinedTypes, since the private directory they are added to is
     * copied as it stands, and precede the thumbnail, which takes whatever room they leave.
     *
     * @throws IOException if the file cannot be rewritten or its block has no room for them
     */
    @WorkerThread
    private void saveTags() throws IOException {
        if (mTags.isEmpty()) {
            return;
        }
        // Only a JPEG file has tags to write, so there is always a file.
        File file = new File(mFilePath);
        byte[] updated;
        try {
            byte[] jpeg = read(file);
            updated = jpeg == null ? null : ExifTags.write(jpeg, mTags);
        } catch (RuntimeException | OutOfMemoryError error) {
            throw new IOException("Unable to write " + mTags.keySet(), error);
        }
        if (updated == null) {
            throw new IOException("No room in the Exif block for " + mTags.keySet());
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(updated);
        }
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

    public void copyFrom(Exif exif) {
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
            mExifInterface.setAttribute(attribute, exifInterface.getAttribute(attribute));
        }
    }

    @NonNull
    @Override
    public String toString() {
        return String.format(Locale.ENGLISH, "Exif{width=%s, height=%s, rotation=%d, "
                        + "isFlippedVertically=%s, isFlippedHorizontally=%s, location=%s, "
                        + "timestamp=%s, description=%s}",
                getWidth(), getHeight(), getRotation(), isFlippedVertically(), isFlippedHorizontally(),
                getLocation(), getTimestamp(), getDescription());
    }

    private int getOrientation() {
        return mExifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED);
    }

    /**
     * Reads any tag {@code ExifInterface} names, by its {@code ExifInterface.TAG_} constant. The
     * Exif 3.0 tags it has no name for are read with {@link #getTag}.
     */
    @Nullable
    public String getAttribute(@NonNull String tag) {
        return mExifInterface.getAttribute(tag);
    }

    /**
     * Sets any tag {@code ExifInterface} names, by its {@code ExifInterface.TAG_} constant, to be
     * written by {@link #save()}. Null removes it. The Exif 3.0 tags it has no name for are
     * written with {@link #setTags}.
     */
    public void setAttribute(@NonNull String tag, @Nullable String value) {
        mExifInterface.setAttribute(tag, value);
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

    /**
     * Has {@link #save()} write a thumbnail, if the file has none and there is room for one.
     * Saving writes it after the rest of the block, so this may be called at any point before.
     */
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
     * Writes the thumbnail into the block saveAttributes has just rewritten: the one the file
     * holds, declared again, or a new one if {@link #attachThumbnail()} asked for it. The bytes
     * go straight into the file, since ExifInterface cannot add a thumbnail, and so they go last:
     * saveAttributes rewrites the block from what it read, which drops them.
     */
    @WorkerThread
    private void saveThumbnail() {
        if (!mHasThumbnail && !mThumbnailRequested) {
            return;
        }
        // Only ever after a save, which a stream cannot have, so there is always a file.
        File file = new File(mFilePath);
        try {
            byte[] original = read(file);
            if (original == null) {
                return;
            }
            byte[] updated = repairOrEncode(file, original);
            if (updated == null) {
                mHasThumbnail = ExifThumbnail.hasThumbnail(original);
                return;
            }
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(updated);
            }
            mHasThumbnail = true;
        } catch (IOException | RuntimeException | OutOfMemoryError error) {
            // A photograph without a thumbnail is a photograph. Losing one is never worth
            // failing a save that has otherwise succeeded.
            Log.w(TAG, "Unable to save a thumbnail", error);
        }
    }

    /**
     * Declares the thumbnail already in the file, or makes one if there is none. A camera's own
     * thumbnail often loses its Compression tag when the block is rewritten, and naming it again
     * is cheaper than encoding a new one.
     *
     * @return the updated file, or null when nothing could be done
     */
    @Nullable
    private byte[] repairOrEncode(@NonNull File file, @NonNull byte[] original) {
        int orientation = getOrientation();
        byte[] declared = ExifThumbnail.declareExistingThumbnail(original, orientation);
        if (declared != null) {
            return declared;
        }
        int room = ExifThumbnail.roomForThumbnail(original);
        if (room <= 0) {
            if (DEBUG) Log.d(TAG, "No room beside this file's Exif for a thumbnail");
            return null;
        }
        byte[] thumbnail = encodeThumbnail(file, room);
        if (thumbnail == null) {
            return null;
        }
        return ExifThumbnail.attach(original, thumbnail, orientation);
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
     * Reads a tag the standard defines but {@code ExifInterface} has no name for, which is most
     * of what Exif 3.0 added: the value the file held when opened, or the one {@link #setTags}
     * has set since.
     *
     * @return the value as text in the shape the file stores it, a rational as
     *     "numerator/denominator", or null when the tag is absent
     */
    @AnyThread
    @Nullable
    public String getTag(@NonNull ExifTags.Tag tag) {
        ExifTags.Value value = mTags.get(tag);
        return value == null ? null : value.text();
    }

    /**
     * Sets tags the standard defines but {@code ExifInterface} has no name for, to be written by
     * {@link #save()} as {@link #setAttribute} is. A null value removes the tag. Saving writes
     * them after the rest of the block, so this may be called at any point before. If the block
     * has no room left for them, the save leaves them out and keeps everything else.
     *
     * @return true if saving will write them; false, with nothing changed, when a value does not
     *     suit its tag, such as a word for a number, or this was opened on a stream or on a file
     *     other than a JPEG, which have no Exif block these can be written into
     */
    @AnyThread
    public boolean setTags(@NonNull ExifTags.Builder tags) {
        return mCanWriteTags && ExifTags.apply(tags, mTags);
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

    @Nullable
    public String getDescription() {
        return mExifInterface.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION);
    }

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

        mExifInterface.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, datetime);
        mExifInterface.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, datetime);
        // And the IFD0 one, which removeTimestamp has always cleared: a file that kept DateTime
        // while losing the other two was stripped of nothing a reader actually looks at first.
        mExifInterface.setAttribute(ExifInterface.TAG_DATETIME, datetime);
        String offset = offsetFromUtc(now);
        mExifInterface.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset);
        mExifInterface.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, offset);
        mExifInterface.setAttribute(ExifInterface.TAG_OFFSET_TIME, offset);

        try {
            String subsec = formatSubSecond(now - convertFromExifDateTime(datetime).getTime());
            mExifInterface.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, subsec);
            mExifInterface.setAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, subsec);
            mExifInterface.setAttribute(ExifInterface.TAG_SUBSEC_TIME, subsec);
        } catch (ParseException e) {
            Log.w(TAG, "Unable to record the sub-second timestamp", e);
        }

        mRemoveTimestamp = false;
    }

    /**
     * Removes the timestamp from the file.
     */
    public void removeTimestamp() {
        mExifInterface.setAttribute(ExifInterface.TAG_DATETIME, null);
        mExifInterface.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, null);
        mExifInterface.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, null);
        mExifInterface.setAttribute(ExifInterface.TAG_SUBSEC_TIME, null);
        mExifInterface.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, null);
        mExifInterface.setAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, null);
        mExifInterface.setAttribute(ExifInterface.TAG_OFFSET_TIME, null);
        mExifInterface.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, null);
        mExifInterface.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, null);
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
     * Removes the location from the file: the whole GPS directory, since a heading, an accuracy
     * or the time of a fix still says something about where the picture was taken.
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
