package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;

import androidx.exifinterface.media.ExifInterface;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.TimeZone;

/** Everything a fix can honestly put in the GPS directory, and taking all of it out again. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class ExifLocationTest {
    /** 2023-11-14 22:13:20 UTC, which is already the 15th in Kolkata. */
    private static final long FIX_TIME = 1_700_000_000_000L;

    private TimeZone mDefaultTimeZone;
    private Exif mExif;

    @Before
    public void setUp() throws IOException {
        mDefaultTimeZone = TimeZone.getDefault();
        // Far enough from UTC that a local time written as GPS time lands on another day.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
        mExif = new Exif(Mockito.mock(InputStream.class));
    }

    @After
    public void tearDown() {
        TimeZone.setDefault(mDefaultTimeZone);
    }

    /** GPS time is UTC, whatever timezone the phone is in. */
    @Test
    public void theTimeOfAFixIsWrittenInUtc() {
        mExif.attachLocation(fix(LocationManager.GPS_PROVIDER));

        assertEquals("2023:11:14", mExif.getAttribute(ExifInterface.TAG_GPS_DATESTAMP));
        assertEquals("22:13:20", mExif.getAttribute(ExifInterface.TAG_GPS_TIMESTAMP));
    }

    /** And it is read back as UTC, so the fix keeps its moment. */
    @Test
    public void theTimeOfAFixRoundTrips() {
        mExif.attachLocation(fix(LocationManager.GPS_PROVIDER));

        assertEquals(FIX_TIME, mExif.getLocation().getTime());
    }

    /** A date alone, or a time alone, still reads as UTC. */
    @Test
    public void halfATimestampReadsAsUtc() {
        mExif.attachLocation(fix(LocationManager.GPS_PROVIDER));
        mExif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, null);
        assertEquals(1_699_920_000_000L, mExif.getLocation().getTime());

        mExif.attachLocation(fix(LocationManager.GPS_PROVIDER));
        mExif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, null);
        assertEquals((22 * 3600 + 13 * 60 + 20) * 1000L, mExif.getLocation().getTime());
    }

    /** A Location nobody set the time on has no time to write. */
    @Test
    public void aFixWithNoTimeWritesNone() {
        Location location = fix(LocationManager.GPS_PROVIDER);
        location.setTime(0);
        mExif.attachLocation(location);

        assertNull(mExif.getAttribute(ExifInterface.TAG_GPS_DATESTAMP));
        assertNull(mExif.getAttribute(ExifInterface.TAG_GPS_TIMESTAMP));
    }

    /** A satellite fix with a height solved in three dimensions. */
    @Test
    public void aSatelliteFixWithAHeightIsThreeDimensional() {
        mExif.attachLocation(fix(LocationManager.GPS_PROVIDER));

        assertEquals("3", mExif.getAttribute(ExifInterface.TAG_GPS_MEASURE_MODE));
    }

    /** One without, in two. */
    @Test
    public void aSatelliteFixWithoutAHeightIsTwoDimensional() {
        Location location = fix(LocationManager.GPS_PROVIDER);
        location.removeAltitude();
        mExif.attachLocation(location);

        assertEquals("2", mExif.getAttribute(ExifInterface.TAG_GPS_MEASURE_MODE));
    }

    /** A fused or network fix has no measurement mode to report. */
    @Test
    public void aFixFromAnotherProviderHasNoMeasureMode() {
        mExif.attachLocation(fix(LocationManager.NETWORK_PROVIDER));

        assertNull(mExif.getAttribute(ExifInterface.TAG_GPS_MEASURE_MODE));
        assertEquals(LocationManager.NETWORK_PROVIDER,
                mExif.getAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD));
    }

    /** The GPS provider counts its satellites in an extra, which goes in GPSSatellites. */
    @Test
    public void theSatellitesBehindAFixAreCounted() {
        Location location = fix(LocationManager.GPS_PROVIDER);
        Bundle extras = new Bundle();
        extras.putInt("satellites", 9);
        location.setExtras(extras);
        mExif.attachLocation(location);

        assertEquals("9", mExif.getAttribute(ExifInterface.TAG_GPS_SATELLITES));
    }

    /** No count, or a count of none, writes nothing. */
    @Test
    public void noSatelliteCountWritesNone() {
        mExif.attachLocation(fix(LocationManager.GPS_PROVIDER));
        assertNull(mExif.getAttribute(ExifInterface.TAG_GPS_SATELLITES));

        Location location = fix(LocationManager.GPS_PROVIDER);
        location.setExtras(new Bundle());
        mExif.attachLocation(location);
        assertNull(mExif.getAttribute(ExifInterface.TAG_GPS_SATELLITES));
    }

    /** Sea level where the fix knows it, and the ellipsoid where that is all there is. */
    @Test
    public void theAltitudeIsAboveSeaLevelWhereTheFixKnowsIt() {
        Location location = fix(LocationManager.GPS_PROVIDER);
        location.setAltitude(100);
        if (Build.VERSION.SDK_INT >= 34) {
            location.setMslAltitudeMeters(68.5);
        }
        mExif.attachLocation(location);

        double expected = Build.VERSION.SDK_INT >= 34 ? 68.5 : 100;
        assertEquals(expected,
                number(mExif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE)), 0.001);
    }

    /** Everything a fix has to say, speed and heading and accuracy included. */
    @Test
    public void theRestOfTheFixIsWritten() {
        mExif.attachLocation(fix(LocationManager.GPS_PROVIDER));

        assertEquals("K", mExif.getAttribute(ExifInterface.TAG_GPS_SPEED_REF));
        assertEquals(18.0, number(mExif.getAttribute(ExifInterface.TAG_GPS_SPEED)), 0.01);
        assertEquals("T", mExif.getAttribute(ExifInterface.TAG_GPS_TRACK_REF));
        assertEquals(271.5, number(mExif.getAttribute(ExifInterface.TAG_GPS_TRACK)), 0.001);
        assertEquals(6.5,
                number(mExif.getAttribute(ExifInterface.TAG_GPS_H_POSITIONING_ERROR)), 0.001);
    }

    /** Removing the location empties the GPS directory, including what another writer put in. */
    @Test
    public void removingTheLocationEmptiesTheGpsDirectory() throws IOException {
        File file = File.createTempFile("location", ".jpg");
        file.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(ExifMetadataTest.minimalJpeg());
        }
        Location location = fix(LocationManager.GPS_PROVIDER);
        Bundle extras = new Bundle();
        extras.putInt("satellites", 9);
        location.setExtras(extras);
        Exif exif = new Exif(file);
        exif.attachLocation(location);
        exif.setAttribute(ExifInterface.TAG_GPS_DOP, "12/10");
        exif.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION_REF, "M");
        exif.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION, "90/1");
        exif.setAttribute(ExifInterface.TAG_GPS_AREA_INFORMATION, "Somewhere");
        exif.save();

        Exif stripped = new Exif(file);
        stripped.removeLocation();
        stripped.save();

        ExifInterface saved = new ExifInterface(file.getPath());
        for (String tag : new String[] {
                ExifInterface.TAG_GPS_VERSION_ID, ExifInterface.TAG_GPS_LATITUDE,
                ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_ALTITUDE,
                ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
                ExifInterface.TAG_GPS_SATELLITES, ExifInterface.TAG_GPS_MEASURE_MODE,
                ExifInterface.TAG_GPS_DOP, ExifInterface.TAG_GPS_SPEED,
                ExifInterface.TAG_GPS_TRACK, ExifInterface.TAG_GPS_IMG_DIRECTION,
                ExifInterface.TAG_GPS_MAP_DATUM, ExifInterface.TAG_GPS_PROCESSING_METHOD,
                ExifInterface.TAG_GPS_AREA_INFORMATION,
                ExifInterface.TAG_GPS_H_POSITIONING_ERROR}) {
            assertNull(tag + " survived removing the location", saved.getAttribute(tag));
        }
    }

    /** A rational as Exif reads one back, "18000/1000", as a number. */
    private static double number(String rational) {
        String[] parts = rational.split("/");
        return Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]);
    }

    private static Location fix(String provider) {
        Location location = new Location(provider);
        location.setLatitude(37.422);
        location.setLongitude(-122.084);
        location.setAltitude(30);
        location.setAccuracy(6.5f);
        location.setBearing(271.5f);
        location.setSpeed(5f);
        location.setTime(FIX_TIME);
        return location;
    }
}
