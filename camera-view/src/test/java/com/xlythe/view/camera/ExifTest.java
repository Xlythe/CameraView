package com.xlythe.view.camera;

import android.location.Location;

import junit.framework.AssertionFailedError;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.io.InputStream;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;

@RunWith(RobolectricTestRunner.class)
@SuppressWarnings("JavaUtilDate")
@Config(sdk = 37)
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
