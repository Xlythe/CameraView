package com.xlythe.view.camera;

import android.location.Location;

import androidx.exifinterface.media.ExifInterface;

import junit.framework.AssertionFailedError;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.io.InputStream;
import java.util.TimeZone;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@SuppressWarnings("JavaUtilDate")
@Config(sdk = {29, 37})
public class ExifTest {
    Exif exif;

    @Before
    public void setup() throws Exception {
        exif = new Exif(Mockito.mock(InputStream.class));
    }

    @Test
    public void defaults() {
        assertEquals(0, exif.getRotation());
        assertEquals(false, exif.isFlippedHorizontally());
        assertEquals(false, exif.isFlippedVertically());
        assertEquals(-1, exif.getTimestamp());
        assertEquals(null, exif.getLocation());
        assertEquals(null, exif.getDescription());
    }

    @Test
    public void rotate() {
        assertEquals(0, exif.getRotation());
        exif.rotate(90);
        assertEquals(90, exif.getRotation());
        exif.rotate(90);
        assertEquals(180, exif.getRotation());
        exif.rotate(90);
        assertEquals(270, exif.getRotation());
        exif.rotate(90);
        assertEquals(0, exif.getRotation());
        exif.rotate(-90);
        assertEquals(270, exif.getRotation());
        exif.rotate(360);
        assertEquals(270, exif.getRotation());
        exif.rotate(500 * 360 - 90);
        assertEquals(180, exif.getRotation());
    }

    @Test
    public void flipHorizontally() {
        assertEquals(false, exif.isFlippedHorizontally());
        exif.flipHorizontally();
        assertEquals(true, exif.isFlippedHorizontally());
        exif.flipHorizontally();
        assertEquals(false, exif.isFlippedHorizontally());
    }

    @Test
    public void flipVertically() {
        assertEquals(false, exif.isFlippedVertically());
        exif.flipVertically();
        assertEquals(true, exif.isFlippedVertically());
        exif.flipVertically();
        assertEquals(false, exif.isFlippedVertically());
    }

    @Test
    public void flipAndRotate() {
        assertEquals(0, exif.getRotation());
        assertEquals(false, exif.isFlippedHorizontally());
        assertEquals(false, exif.isFlippedVertically());

        exif.rotate(-90);
        assertEquals(270, exif.getRotation());

        exif.flipHorizontally();
        assertEquals(90, exif.getRotation());
        assertEquals(true, exif.isFlippedVertically());

        exif.flipVertically();
        assertEquals(90, exif.getRotation());
        assertEquals(false, exif.isFlippedHorizontally());
        assertEquals(false, exif.isFlippedVertically());

        exif.rotate(90);
        assertEquals(180, exif.getRotation());

        exif.flipVertically();
        assertEquals(0, exif.getRotation());
        assertEquals(true, exif.isFlippedHorizontally());
        assertEquals(false, exif.isFlippedVertically());

        exif.flipHorizontally();
        assertEquals(0, exif.getRotation());
        assertEquals(false, exif.isFlippedHorizontally());
        assertEquals(false, exif.isFlippedVertically());
    }

    @Test
    public void timestamp() {
        assertEquals(-1, exif.getTimestamp());

        exif.attachTimestamp();
        assertWithin(System.currentTimeMillis(), exif.getTimestamp(), 3);

        exif.removeTimestamp();
        assertEquals(-1, exif.getTimestamp());
    }

    @Test
    public void location() {
        assertEquals(null, exif.getLocation());

        Location location = new Location("TEST");
        location.setLatitude(22.3);
        location.setLongitude(114);
        location.setTime(System.currentTimeMillis() / 1000 * 1000);
        exif.attachLocation(location);
        assertEquals(location.getProvider(), exif.getLocation().getProvider());
        assertEquals(location.getLatitude(), exif.getLocation().getLatitude());
        assertEquals(location.getLongitude(), exif.getLocation().getLongitude());
        assertEquals(location.getTime(), exif.getLocation().getTime());

        exif.removeLocation();
        assertEquals(null, exif.getLocation());
    }

    @Test
    public void locationWithAltitude() {
        Location location = new Location("TEST");
        location.setLatitude(22.3);
        location.setLongitude(114);
        location.setTime(System.currentTimeMillis() / 1000 * 1000);
        location.setAltitude(5.0);
        exif.attachLocation(location);
        assertEquals(location.getProvider(), exif.getLocation().getProvider());
        assertEquals(location.getLatitude(), exif.getLocation().getLatitude());
        assertEquals(location.getLongitude(), exif.getLocation().getLongitude());
        assertEquals(location.getTime(), exif.getLocation().getTime());
        assertEquals(location.getAltitude(), exif.getLocation().getAltitude());
    }

    @Test
    public void locationWithSpeed() {
        Location location = new Location("TEST");
        location.setLatitude(22.3);
        location.setLongitude(114);
        location.setTime(System.currentTimeMillis() / 1000 * 1000);
        location.setSpeed(5.0f);
        exif.attachLocation(location);
        assertEquals(location.getProvider(), exif.getLocation().getProvider());
        assertEquals(location.getLatitude(), exif.getLocation().getLatitude());
        assertEquals(location.getLongitude(), exif.getLocation().getLongitude());
        assertEquals(location.getTime(), exif.getLocation().getTime());
        assertWithin(location.getSpeed(), exif.getLocation().getSpeed(), 0.1f);
    }

    @Test
    public void description() {
        assertEquals(null, exif.getDescription());

        exif.setDescription("Hello World");
        assertEquals("Hello World", exif.getDescription());

        exif.setDescription(null);
        assertEquals(null, exif.getDescription());
    }

    @Test
    public void save() {
        assertEquals(-1, exif.getLastModifiedTimestamp());

        try {
            exif.save();
        } catch (IOException e) {
            // expected
        }

        assertWithin(System.currentTimeMillis(), exif.getLastModifiedTimestamp(), 100);

        // removeTimestamp should also be clearing the last modified timestamp
        exif.removeTimestamp();
        assertEquals(-1, exif.getLastModifiedTimestamp());

        // Even when saving again
        try {
            exif.save();
        } catch (IOException e) {
            // expected
        }

        assertEquals(-1, exif.getLastModifiedTimestamp());
    }

    @Test
    public void asString() {
        assertNotNull(exif.toString());
        exif.setDescription("Hello World");
        exif.attachTimestamp();
        Location location = new Location("TEST");
        location.setLatitude(22.3);
        location.setLongitude(114);
        location.setTime(System.currentTimeMillis() / 1000 * 1000);
        location.setAltitude(5.0);
        exif.attachLocation(location);
        assertNotNull(exif.toString());
    }

    /**
     * EXIF timestamps are local time with nothing to say where that was. Exif 2.31 added the
     * offset tags to answer it, and without them two pictures taken in different timezones
     * cannot be ordered against each other.
     */
    @Test
    public void timestampsCarryTheirOffsetFromUtc() {
        exif.attachTimestamp();

        String offset = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL);
        assertNotNull("the original timestamp was written without its offset", offset);
        assertEquals(offset, exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED));
        assertTrue("not an EXIF offset: " + offset,
                offset.matches("[+-]\\d\\d:\\d\\d"));
    }

    /** And the modification time has its own, written when the file is saved. */
    @Test
    public void theLastModifiedTimestampCarriesItsOffsetToo() {
        try {
            exif.save();
        } catch (IOException e) {
            // Expected: the stream is a mock. The attributes are still set.
        }

        String offset = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME);
        assertNotNull("the modification time was written without its offset", offset);
        assertTrue("not an EXIF offset: " + offset, offset.matches("[+-]\\d\\d:\\d\\d"));
    }

    /**
     * Removing the timestamp removes the offsets with it.
     *
     * <p>A picture stripped of when it was taken but still carrying the timezone it was taken
     * in has given away roughly where in the world that was, which is most of what stripping
     * the timestamp was for.</p>
     */
    @Test
    public void removingTheTimestampRemovesTheOffsets() {
        exif.attachTimestamp();
        assertNotNull(exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL));

        exif.removeTimestamp();

        assertEquals(null, exif.getAttribute(ExifInterface.TAG_OFFSET_TIME));
        assertEquals(null, exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL));
        assertEquals(null, exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED));
    }

    /** The offset is written the way EXIF writes it, including for zones off the hour. */
    @Test
    public void offsetsAreFormattedAsExifWantsThem() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            assertEquals("+00:00", Exif.offsetFromUtc(0));

            // Kolkata is five and a half hours ahead, which is why the minutes are not padding.
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
            assertEquals("+05:30", Exif.offsetFromUtc(0));

            // And Chatham is twelve and three quarters, so the minutes are not even half-hours.
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Chatham"));
            assertEquals("+12:45", Exif.offsetFromUtc(0));

            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            assertEquals("-08:00", Exif.offsetFromUtc(0));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    private static void assertWithin(long expected, long actual, long variance) {
        if ((variance - Math.abs(actual - expected)) < 0) {
            throw new AssertionFailedError(String.format("\nExpected :%s\nActual   :%s", expected, actual));
        }
    }

    private static void assertWithin(float expected, float actual, float variance) {
        if ((variance - Math.abs(actual - expected)) < 0) {
            throw new AssertionFailedError(String.format("\nExpected :%s\nActual   :%s", expected, actual));
        }
    }

    /**
     * SubSecTime is the digits after the decimal point, so the digit count sets the scale. The
     * old reading treated them as milliseconds, which under-read anything shorter than three
     * digits by a factor of ten or a hundred.
     */
    @Test
    public void subSecondsScaleByTheirDigitCount() {
        assertEquals(500, Exif.parseSubSecond("5"));
        assertEquals(50, Exif.parseSubSecond("05"));
        assertEquals(5, Exif.parseSubSecond("005"));
        assertEquals(123, Exif.parseSubSecond("123"));

        // More precision than a millisecond is truncated, not misread.
        assertEquals(123, Exif.parseSubSecond("1234567"));

        // Anything unparseable reads as no sub-second at all.
        assertEquals(0, Exif.parseSubSecond(null));
        assertEquals(0, Exif.parseSubSecond(""));
        assertEquals(0, Exif.parseSubSecond("abc"));
        assertEquals(0, Exif.parseSubSecond("-5"));
    }

    @Test
    public void subSecondsAreWrittenZeroPadded() {
        assertEquals("050", Exif.formatSubSecond(50));
        assertEquals("005", Exif.formatSubSecond(5));
        assertEquals("500", Exif.formatSubSecond(500));
        assertEquals("999", Exif.formatSubSecond(999));

        // Out-of-range values clamp rather than writing four digits.
        assertEquals("999", Exif.formatSubSecond(1500));
        assertEquals("000", Exif.formatSubSecond(-1));
    }

    /** What is written has to read back as the same number of milliseconds. */
    @Test
    public void subSecondsRoundTrip() {
        for (long millis : new long[] {0, 1, 5, 50, 99, 123, 500, 999}) {
            assertEquals(millis, Exif.parseSubSecond(Exif.formatSubSecond(millis)));
        }
    }
}
