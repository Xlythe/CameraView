package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.location.Location;

import androidx.exifinterface.media.ExifInterface;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Locale;

/**
 * What a timestamp reads as when the file carries half of one, or carries nonsense.
 *
 * <p>A GPS fix records its date and its time as two separate tags, so a file can hold either
 * without the other - a capture that lost the fix between the two writes, or a file whose tags
 * another tool has edited. Each half alone still dates the fix, and a tag holding text that is
 * not a date at all is worth no date rather than an exception thrown out of a getter.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class ExifTimestampTest {
    private static final double LATITUDE = 51.5072;
    private static final double LONGITUDE = -0.1276;

    private File file;

    @Before
    public void setUp() throws IOException {
        file = File.createTempFile("timestamp", ".jpg");
        file.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(minimalJpeg());
        }
    }

    /** A date with no time dates the fix to midnight rather than leaving it undated. */
    @Test
    public void aGpsDateWithoutATimeStillDatesTheFix() throws IOException {
        Location location = locationWith("2026:11:25", null);

        assertNotNull(location);
        assertEquals(parse("yyyy:MM:dd", "2026:11:25"), location.getTime());
    }

    /** A time with no date is still a time, measured from the epoch. */
    @Test
    public void aGpsTimeWithoutADateStillDatesTheFix() throws IOException {
        Location location = locationWith(null, "14:21:09");

        assertNotNull(location);
        assertEquals(parse("HH:mm:ss", "14:21:09"), location.getTime());
    }

    /** A date tag holding something that is not a date leaves the fix undated. */
    @Test
    public void aGpsDateThatWillNotParseLeavesTheFixUndated() throws IOException {
        Location location = locationWith("last Tuesday", null);

        assertNotNull(location);
        assertEquals(0L, location.getTime());
    }

    /** And so does a time tag holding something that is not a time. */
    @Test
    public void aGpsTimeThatWillNotParseLeavesTheFixUndated() throws IOException {
        Location location = locationWith(null, "about noon");

        assertNotNull(location);
        assertEquals(0L, location.getTime());
    }

    /** The capture timestamp is unavailable rather than wrong when the tag is unreadable. */
    @Test
    public void aCaptureTimeThatWillNotParseIsNotATime() throws IOException {
        ExifInterface original = new ExifInterface(file.getPath());
        original.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "sometime on Tuesday");
        original.saveAttributes();

        assertEquals(-1L, new Exif(file).getTimestamp());
    }

    /**
     * Writes the two GPS time tags, with a fix so there is a location to hang them on, and reads
     * the result back through Exif.
     */
    private Location locationWith(String datestamp, String timestamp) throws IOException {
        ExifInterface original = new ExifInterface(file.getPath());
        original.setLatLong(LATITUDE, LONGITUDE);
        original.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, datestamp);
        original.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, timestamp);
        original.saveAttributes();

        return new Exif(file).getLocation();
    }

    /** The same parse Exif does, so the expected value cannot disagree about the time zone. */
    private static long parse(String pattern, String value) {
        try {
            return new SimpleDateFormat(pattern, Locale.ENGLISH).parse(value).getTime();
        } catch (java.text.ParseException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /** The shortest JPEG ExifInterface will write tags into: a quantisation table and nothing. */
    private static byte[] minimalJpeg() {
        return new byte[] {
                (byte) 0xFF, (byte) 0xD8,
                (byte) 0xFF, (byte) 0xDB, 0x00, 0x43, 0x00,
                0x08, 0x06, 0x06, 0x07, 0x06, 0x05, 0x08, 0x07, 0x07, 0x07, 0x09, 0x09, 0x08,
                0x0A, 0x0C, 0x14, 0x0D, 0x0C, 0x0B, 0x0B, 0x0C, 0x19, 0x12, 0x13, 0x0F, 0x14,
                0x1D, 0x1A, 0x1F, 0x1E, 0x1D, 0x1A, 0x1C, 0x1C, 0x20, 0x24, 0x2E, 0x27, 0x20,
                0x22, 0x2C, 0x23, 0x1C, 0x1C, 0x28, 0x37, 0x29, 0x2C, 0x30, 0x31, 0x34, 0x34,
                0x34, 0x1F, 0x27, 0x39, 0x3D, 0x38, 0x32, 0x3C, 0x2E, 0x33, 0x34, 0x32,
                (byte) 0xFF, (byte) 0xD9,
        };
    }
}
