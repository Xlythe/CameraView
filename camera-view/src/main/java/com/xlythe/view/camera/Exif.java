package com.xlythe.view.camera;

import android.location.Location;
import android.support.annotation.Nullable;
import android.support.media.ExifInterface;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Modifies metadata on JPEG files. Call {@link #save()} to persist changes to disc.
 */
public class Exif {
    private static final String TAG = Exif.class.getSimpleName();

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy:MM:dd", Locale.ENGLISH);
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss", Locale.ENGLISH);
    private static final SimpleDateFormat DATETIME_FORMAT = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.ENGLISH);

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
        } catch (ParseException e) {}
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
                // Ignored
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
        double speed = mExifInterface.getAttributeDouble(ExifInterface.TAG_GPS_SPEED, 0)
                * mExifInterface.getAttributeDouble(ExifInterface.TAG_GPS_SPEED_REF, 1);
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
        } catch (ParseException e) {}

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
            mExifInterface.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, Double.toString(location.getAltitude()));
            mExifInterface.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, Integer.toString(1));
        }
        if (location.hasSpeed()) {
            mExifInterface.setAttribute(ExifInterface.TAG_GPS_SPEED, Float.toString(location.getSpeed()));
            mExifInterface.setAttribute(ExifInterface.TAG_GPS_SPEED_REF, Integer.toString(1));
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
}
