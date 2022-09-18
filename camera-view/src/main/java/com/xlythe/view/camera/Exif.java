package com.xlythe.view.camera;

import android.location.Location;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;

/**
 * Modifies metadata on JPEG files. Call {@link #save()} to persist changes to disc.
 */
public class Exif {
    private static final String TAG = Exif.class.getSimpleName();

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy:MM:dd", Locale.ENGLISH);
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss", Locale.ENGLISH);
    private static final SimpleDateFormat DATETIME_FORMAT = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.ENGLISH);

    private static final String KILOMETERS_PER_HOUR = "K";
    private static final String MILES_PER_HOUR = "M";
    private static final String KNOTS = "N";

    private final ExifInterface mExifInterface;

    // When true, avoid saving any time. This is a privacy issue.
    private boolean mRemoveTimestamp = false;

    public Exif(File file) throws IOException {
        this(file.toString());
    }

    public Exif(String filePath) throws IOException {
        this(new ExifInterface(filePath));
    }

    public Exif(InputStream is) throws IOException {
        this(new ExifInterface(is));
    }

    private Exif(ExifInterface exifInterface) {
        mExifInterface = exifInterface;
    }

    /**
     * Persists changes to disc.
     */
    public void save() throws IOException {
        if (!mRemoveTimestamp) {
            attachLastModifiedTimestamp();
        }
        mExifInterface.saveAttributes();
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

        try {
            String subsec = Long.toString(now - convertFromExifDateTime(datetime).getTime());
            mExifInterface.setAttribute(ExifInterface.TAG_SUBSEC_TIME, subsec);
        } catch (ParseException e) {
            e.printStackTrace();
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

        String subSecs = mExifInterface.getAttribute(ExifInterface.TAG_SUBSEC_TIME);
        if (subSecs != null) {
            try {
                long sub = Long.parseLong(subSecs);
                while (sub > 1000) {
                    sub /= 10;
                }
                timestamp += sub;
            } catch (NumberFormatException e) {
                e.printStackTrace();
            }
        }

        return timestamp;
    }

    /**
     * @return The timestamp (in millis) that this picture was taken, or -1 if no time is available.
     */
    public long getTimestamp() {
        long timestamp = parseTimestamp(mExifInterface.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL));
        if (timestamp == -1) {
            return -1;
        }

        String subSecs = mExifInterface.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL);
        if (subSecs != null) {
            try {
                long sub = Long.parseLong(subSecs);
                while (sub > 1000) {
                    sub /= 10;
                }
                timestamp += sub;
            } catch (NumberFormatException e) {
                // Ignored
            }
        }

        return timestamp;
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
                    orientation = ExifInterface.ORIENTATION_ROTATE_90;
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

        try {
            String subsec = Long.toString(now - convertFromExifDateTime(datetime).getTime());
            mExifInterface.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, subsec);
            mExifInterface.setAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, subsec);
        } catch (ParseException e) {
            e.printStackTrace();
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
        mRemoveTimestamp = true;
    }

    /**
     * Attaches the given location to the file.
     */
    public void attachLocation(Location location) {
        mExifInterface.setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD, location.getProvider());
        mExifInterface.setLatLong(location.getLatitude(), location.getLongitude());
        if (location.hasAltitude()) {
            mExifInterface.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, Integer.toString(0));
            mExifInterface.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, Double.toString(location.getAltitude()) + "/1");
        }
        if (location.hasSpeed()) {
            mExifInterface.setAttribute(ExifInterface.TAG_GPS_SPEED_REF, KILOMETERS_PER_HOUR);
            mExifInterface.setAttribute(ExifInterface.TAG_GPS_SPEED, Double.toString(Speed.fromMetersPerSecond(location.getSpeed()).toKilometersPerHour()) + "/1");
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

    private static String convertToExifDateTime(long timestamp) {
        return DATETIME_FORMAT.format(new Date(timestamp));
    }

    private static Date convertFromExifDateTime(String dateTime) throws ParseException {
        return DATETIME_FORMAT.parse(dateTime);
    }

    private static String convertToExifDate(long timestamp) {
        return DATE_FORMAT.format(new Date(timestamp));
    }

    private static Date convertFromExifDate(String date) throws ParseException {
        return DATE_FORMAT.parse(date);
    }

    private static String convertToExifTime(long timestamp) {
        return TIME_FORMAT.format(new Date(timestamp));
    }

    private static Date convertFromExifTime(String time) throws ParseException {
        return TIME_FORMAT.parse(time);
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
