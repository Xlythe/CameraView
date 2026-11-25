package com.xlythe.view.camera;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.location.Location;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
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
    private static final ThreadLocal<SimpleDateFormat> DATE_FORMAT = formatter("yyyy:MM:dd");
    private static final ThreadLocal<SimpleDateFormat> TIME_FORMAT = formatter("HH:mm:ss");
    private static final ThreadLocal<SimpleDateFormat> DATETIME_FORMAT =
            formatter("yyyy:MM:dd HH:mm:ss");

    /** One formatter per thread, built lazily. */
    private static ThreadLocal<SimpleDateFormat> formatter(String pattern) {
        return new ThreadLocal<SimpleDateFormat>() {
            @Override
            protected SimpleDateFormat initialValue() {
                return new SimpleDateFormat(pattern, Locale.ENGLISH);
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

    private static final String KILOMETERS_PER_HOUR = "K";
    private static final String MILES_PER_HOUR = "M";
    private static final String KNOTS = "N";

    private final ExifInterface mExifInterface;

    /**
     * The file this came from, where there was one.
     *
     * <p>Only needed to measure the image when no tag says how big it is, which is a real case:
     * the CameraX backend writes a JPEG carrying neither ImageWidth nor PixelXDimension.</p>
     */
    @Nullable private final String mFilePath;

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
    }

    public Exif(InputStream is) throws IOException {
        mExifInterface = new ExifInterface(is);
        mFilePath = null;
    }

    /**
     * Persists changes to disc.
     */
    public void save() throws IOException {
        if (!mRemoveTimestamp) {
            attachLastModifiedTimestamp();
        }
        attachMandatoryTags();
        mExifInterface.saveAttributes();
    }

    /**
     * Fills in the tags Exif requires of a compressed image, where they are absent or nonsense.
     *
     * <p>Mandatory for a compressed image, per CIPA DC-008 tables 17 and 18: ExifVersion,
     * ComponentsConfiguration, ColorSpace, PixelXDimension, PixelYDimension and
     * YCbCrPositioning. The resolution triple and FlashpixVersion are Optional there, and are
     * written anyway for a narrower reason - they were already present and set to zero.</p>
     *
     * <p>Measured on a Pixel 7 Pro, not assumed: a capture came back declaring ColorSpace 0,
     * ResolutionUnit 0, YCbCrPositioning 0 and a resolution of 0/1 on the camera2 and CameraX
     * backends, and simply missing all of them on the legacy one. Optional does not mean a
     * value may be nonsense: zero is not one of the two choices for a resolution unit, and a
     * resolution of zero describes nothing, so a reader meets a file that claims to be Exif and
     * then contradicts itself. The legacy backend's file was the honest kind of wrong - it left
     * them out.</p>
     *
     * <p>Only absent or invalid values are written. A camera that reports its own resolution,
     * colour space or positioning knows more about the sensor than this does, and overwriting it
     * would trade a real measurement for a convention.</p>
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
     * How big the image actually is, read from its own header.
     *
     * <p>Preferred over the tags because this is called precisely when the tags are missing, and
     * decoding bounds costs no pixels - inJustDecodeBounds reads the JPEG's frame header and
     * stops.</p>
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
            // A file that will not decode is a file whose size cannot be stated, which is worth
            // nothing more than leaving the tag out. Writing the rest of the metadata still
            // matters, so this must not be what stops it.
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
     * The raw value of a tag this class has no reader of its own for.
     *
     * <p>Not public: the tags with a meaning worth exposing have named accessors, and the rest
     * are the file's business rather than a caller's.</p>
     */
    @Nullable
    String getAttribute(String tag) {
        return mExifInterface.getAttribute(tag);
    }

    /**
     * Writes a thumbnail into the file, if it has none and there is room for one.
     *
     * <p>Exif keeps a small copy of the picture in a second directory so that a gallery can draw
     * a grid without decoding every photograph at full size. Two of the three backends write no
     * thumbnail, and no Android API will add one - androidx's ExifInterface reads thumbnails and
     * has no setter - so the directory is written by hand.</p>
     *
     * <p>Call it after {@link #save()}: it works on the file as it stands on disc, and saving
     * again afterwards would rewrite the Exif block and drop it.</p>
     *
     * @return true if a thumbnail was written
     */
    public boolean attachThumbnail() {
        if (mFilePath == null) {
            return false;
        }
        File file = new File(mFilePath);
        try {
            byte[] original = read(file);
            if (original == null) {
                return false;
            }
            byte[] updated = repairOrEncode(file, original);
            if (updated == null) {
                return false;
            }
            FileOutputStream out = new FileOutputStream(file);
            try {
                out.write(updated);
            } finally {
                out.close();
            }
            return true;
        } catch (IOException | RuntimeException | OutOfMemoryError error) {
            // A photograph without a thumbnail is a photograph. Losing one is never worth
            // failing a capture that has already succeeded.
            Log.w(TAG, "Unable to attach a thumbnail", error);
            return false;
        }
    }

    /**
     * Declares the thumbnail already in the file, or makes one if there is none.
     *
     * <p>Repair first, and it is usually repair. A camera writes a thumbnail of its own, then
     * the Exif block is rewritten and the Compression tag that said what it was does not
     * survive, leaving bytes no reader will touch. Naming them again costs a few dozen bytes and
     * keeps the camera's own thumbnail; encoding a new one costs a decode, a scale and fifteen
     * kilobytes of a segment that has a hard limit.</p>
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
     * Shrinks the picture to a thumbnail and encodes it as JPEG.
     *
     * <p>Sampled down while decoding rather than afterwards, so a 12-megapixel capture is never
     * held in memory to make a postage stamp of it. The target is the 160x120 the specification
     * suggests; inSampleSize only halves, so the result is the first power-of-two reduction that
     * is no larger than the target in both directions.</p>
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
            // Quality first, because a slightly softer thumbnail at the right size reads better
            // than a sharp one at half of it, and because the room left over is whatever a maker
            // note did not take - on some devices that is generous and on others it is almost
            // nothing.
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
        // The offsets go with them: a picture stripped of when it was taken but still carrying
        // the timezone it was taken in has given away where in the world that was.
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
            // The sign lives in the reference byte, not in the number: GPSAltitude is an
            // unsigned rational, so a negative altitude written straight in is not below sea
            // level, it is unreadable.
            double altitude = location.getAltitude();
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
        mExifInterface.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, convertToExifDate(location.getTime()));
        mExifInterface.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, convertToExifTime(location.getTime()));
    }

    /**
     * Removes the location from the file.
     */
    public void removeLocation() {
        mExifInterface.setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD, null);
        mExifInterface.setAttribute(ExifInterface.TAG_GPS_LATITUDE, null);
        mExifInterface.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, null);
        mExifInterface.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, null);
        mExifInterface.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, null);
        mExifInterface.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, null);
        mExifInterface.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, null);
        mExifInterface.setAttribute(ExifInterface.TAG_GPS_SPEED, null);
        mExifInterface.setAttribute(ExifInterface.TAG_GPS_SPEED_REF, null);
        mExifInterface.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, null);
        mExifInterface.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, null);
        // The rest of what a fix knows, for the same reason the timezone goes with the time: a
        // picture stripped of where it was taken, still carrying which way the phone was moving
        // and how good the fix was, has not been stripped of much.
        mExifInterface.setAttribute(ExifInterface.TAG_GPS_TRACK, null);
        mExifInterface.setAttribute(ExifInterface.TAG_GPS_TRACK_REF, null);
        mExifInterface.setAttribute(ExifInterface.TAG_GPS_H_POSITIONING_ERROR, null);
        mExifInterface.setAttribute(ExifInterface.TAG_GPS_MAP_DATUM, null);
        mExifInterface.setAttribute(ExifInterface.TAG_GPS_VERSION_ID, null);
        mExifInterface.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION, null);
        mExifInterface.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION_REF, null);
    }

    /**
     * The offset from UTC, as EXIF writes it.
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
     * @return The timestamp (in millis), or -1 if no time is available.
     */
    private long parseTimestamp(@Nullable String date, @Nullable String time) {
        if (date == null && time == null) {
            return -1;
        }
        if (time == null) {
            try {
                return convertFromExifDate(date).getTime();
            } catch (ParseException e) {
                return -1;
            }
        }
        if (date == null) {
            try {
                return convertFromExifTime(time).getTime();
            } catch (ParseException e) {
                return -1;
            }
        }
        return parseTimestamp(date + " " + time);
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

    private static String convertToExifDate(long timestamp) {
        return DATE_FORMAT.get().format(new Date(timestamp));
    }

    private static Date convertFromExifDate(String date) throws ParseException {
        return DATE_FORMAT.get().parse(date);
    }

    private static String convertToExifTime(long timestamp) {
        return TIME_FORMAT.get().format(new Date(timestamp));
    }

    private static Date convertFromExifTime(String time) throws ParseException {
        return TIME_FORMAT.get().parse(time);
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
