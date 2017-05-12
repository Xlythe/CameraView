package com.xlythe.view.camera;

import android.location.Location;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.io.InputStream;

import static junit.framework.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=23, constants = BuildConfig.class)
public class ExifTest {
    Exif exif;

    @Before
    public void setup() throws Exception {
        ShadowLog.stream = System.out;
        exif = new Exif(Mockito.mock(InputStream.class));
    }

    @Test
    public void defaults() {
        assertEquals(0, exif.getRotation());
        assertEquals(false, exif.isFlippedHorizontally());
        assertEquals(false, exif.isFlippedVertically());
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
        assertEquals(System.currentTimeMillis() / 1000 * 1000, exif.getTimestamp());

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
        assertEquals(location, exif.getLocation());

        exif.removeLocation();
        assertEquals(null, exif.getLocation());
    }
}
