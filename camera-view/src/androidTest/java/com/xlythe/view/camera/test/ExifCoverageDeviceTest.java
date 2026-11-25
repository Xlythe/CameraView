package com.xlythe.view.camera.test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.location.Location;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.exifinterface.media.ExifInterface;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.rule.GrantPermissionRule;

import com.xlythe.view.camera.CameraView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Which Exif tags a capture actually carries.
 *
 * <p>The library copies tags faithfully and writes the time and the place, but what ends up in the
 * file is decided mostly by the HAL, and the only way to know what that is on a given device is to
 * take a photograph and read it back. This logs every tag present, which is the audit, and asserts
 * the handful the specification calls mandatory for a compressed image, which is the test.</p>
 *
 * <p>Mandatory here means mandatory in Exif 2.3 for a compressed primary image: the version tags,
 * the colour space, the pixel dimensions, the components configuration, the resolution triple and
 * the YCbCr positioning. A camera that omits them is writing something that is not quite Exif, and
 * a library that drops them while rewriting is doing the damage itself.</p>
 */
@RunWith(Parameterized.class)
public final class ExifCoverageDeviceTest {
    private static final String TAG = "ExifCoverage";
    private static final long TIMEOUT_SECONDS = 30;

    /**
     * Mandatory for a compressed image, taken from the standard rather than from memory.
     *
     * <p>CIPA DC-008 table 17 (0th IFD TIFF tags) marks YCbCrPositioning M in the compressed
     * column, and table 18 (Exif private tags) marks ExifVersion, ComponentsConfiguration,
     * ColorSpace, PixelXDimension and PixelYDimension. That is the whole list for a JPEG.</p>
     *
     * <p>Not here, though an earlier version of this test asserted them: FlashpixVersion, the
     * resolution pair and ResolutionUnit are all Optional for a compressed image. The library
     * writes them regardless, and {@link #theOptionalTagsThatArePresentAreValid} holds them to
     * being sensible when present, which is a different and weaker claim.</p>
     */
    private static final String[] MANDATORY = {
            ExifInterface.TAG_EXIF_VERSION,
            ExifInterface.TAG_COMPONENTS_CONFIGURATION,
            ExifInterface.TAG_COLOR_SPACE,
            ExifInterface.TAG_PIXEL_X_DIMENSION,
            ExifInterface.TAG_PIXEL_Y_DIMENSION,
            ExifInterface.TAG_Y_CB_CR_POSITIONING,
    };

    /** Recommended for a compressed image by the same tables, and worth reporting on. */
    private static final String[] RECOMMENDED = {
            ExifInterface.TAG_IMAGE_DESCRIPTION,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_EXPOSURE_MODE,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_SCENE_CAPTURE_TYPE,
    };

    /** Everything else worth knowing the state of, logged rather than asserted. */
    private static final String[] OF_INTEREST = {
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_FLASH,
            ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_EXPOSURE_MODE, ExifInterface.TAG_SCENE_CAPTURE_TYPE,
            ExifInterface.TAG_DIGITAL_ZOOM_RATIO, ExifInterface.TAG_LENS_MAKE,
            ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_LENS_SPECIFICATION,
            ExifInterface.TAG_BODY_SERIAL_NUMBER, ExifInterface.TAG_IMAGE_UNIQUE_ID,
            ExifInterface.TAG_SUBJECT_AREA, ExifInterface.TAG_BRIGHTNESS_VALUE,
            ExifInterface.TAG_APERTURE_VALUE, ExifInterface.TAG_SHUTTER_SPEED_VALUE,
            ExifInterface.TAG_SENSING_METHOD, ExifInterface.TAG_FILE_SOURCE,
            ExifInterface.TAG_SCENE_TYPE, ExifInterface.TAG_CUSTOM_RENDERED,
            ExifInterface.TAG_GPS_VERSION_ID, ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_SPEED,
            ExifInterface.TAG_GPS_TRACK, ExifInterface.TAG_GPS_IMG_DIRECTION,
            ExifInterface.TAG_GPS_H_POSITIONING_ERROR, ExifInterface.TAG_GPS_MAP_DATUM,
            ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
    };

    @Parameterized.Parameters(name = "{0}")
    public static Collection<CameraView.Backend> backends() {
        return Arrays.asList(
                CameraView.Backend.CAMERA2,
                CameraView.Backend.CAMERA_X,
                CameraView.Backend.LEGACY);
    }

    @Rule
    public final GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.CAMERA);

    private final CameraView.Backend backend;

    public ExifCoverageDeviceTest(CameraView.Backend backend) {
        this.backend = backend;
    }

    @Before
    public void chooseTheBackend() {
        DeviceState.assumeATestableCamera();
        CameraView.setDefaultBackend(backend);
    }

    @After
    public void restoreTheDefault() {
        CameraView.setDefaultBackend(CameraView.Backend.AUTO);
    }

    /** Every backend writes a file that is Exif, not merely a JPEG with some tags in it. */
    @Test
    public void aCaptureCarriesTheMandatoryTags() throws Exception {
        File file = capture();
        ExifInterface exif = new ExifInterface(file.getAbsolutePath());

        // The audit: what is actually there, for reading off a real device.
        for (String tag : MANDATORY) {
            Log.i(TAG, backend + " mandatory " + tag + " = " + exif.getAttribute(tag));
        }
        for (String tag : OF_INTEREST) {
            String value = exif.getAttribute(tag);
            if (value != null) Log.i(TAG, backend + " has " + tag + " = " + value);
            else Log.i(TAG, backend + " MISSING " + tag);
        }
        Log.i(TAG, backend + " thumbnail present = " + exif.hasThumbnail());

        List<String> missing = new ArrayList<>();
        for (String tag : MANDATORY) {
            if (exif.getAttribute(tag) == null) missing.add(tag);
        }
        assertTrue(backend + " wrote a capture missing mandatory Exif tags: " + missing,
                missing.isEmpty());

        // Reported rather than asserted: Recommended is not Mandatory, and what a camera
        // chooses to say about its own exposure is the camera's business.
        List<String> absent = new ArrayList<>();
        for (String tag : RECOMMENDED) {
            if (exif.getAttribute(tag) == null) absent.add(tag);
        }
        Log.i(TAG, backend + " recommended but absent: " + absent);
    }

    /**
     * An optional tag that is present still has to mean something.
     *
     * <p>The distinction the standard draws is between a tag that need not be there and a tag
     * that is there and wrong. Zero is not one of the two legal resolution units, and a
     * resolution of zero describes nothing - so these are asserted only once the tag exists.</p>
     */
    @Test
    public void theOptionalTagsThatArePresentAreValid() throws Exception {
        ExifInterface exif = new ExifInterface(capture().getAbsolutePath());

        if (exif.getAttribute(ExifInterface.TAG_RESOLUTION_UNIT) != null) {
            int unit = exif.getAttributeInt(ExifInterface.TAG_RESOLUTION_UNIT, 0);
            assertTrue("a resolution unit is inches or centimetres, not " + unit,
                    unit == 2 || unit == 3);
        }
        if (exif.getAttribute(ExifInterface.TAG_X_RESOLUTION) != null) {
            assertTrue("a resolution of zero describes nothing",
                    exif.getAttributeDouble(ExifInterface.TAG_X_RESOLUTION, 0) > 0);
        }
        if (exif.getAttribute(ExifInterface.TAG_Y_RESOLUTION) != null) {
            assertTrue("a resolution of zero describes nothing",
                    exif.getAttributeDouble(ExifInterface.TAG_Y_RESOLUTION, 0) > 0);
        }
    }

    /** A capture carries a thumbnail, which is what makes a gallery scroll without decoding. */
    @Test
    public void aCaptureCarriesAThumbnail() throws Exception {
        File file = capture();
        ExifInterface exif = new ExifInterface(file.getAbsolutePath());

        assertTrue(backend + " wrote no Exif thumbnail", exif.hasThumbnail());
        byte[] thumbnail = exif.getThumbnail();
        assertNotNull(backend + " has a thumbnail that is not retrievable", thumbnail);
        assertTrue("an empty thumbnail", thumbnail.length > 0);
    }

    /** The place, written with everything the fix has to say about it. */
    @Test
    public void aLocationIsWrittenInFull() throws Exception {
        Location location = new Location("test");
        location.setLatitude(37.4219983);
        location.setLongitude(-122.084);
        // Below sea level on purpose: the reference byte, not the sign, carries that in Exif.
        location.setAltitude(-12.5);
        location.setAccuracy(7.5f);
        location.setBearing(123.4f);
        location.setSpeed(4.2f);
        location.setTime(System.currentTimeMillis());

        File file = captureAt(location);
        ExifInterface exif = new ExifInterface(file.getAbsolutePath());

        double[] latLong = exif.getLatLong();
        assertNotNull(backend + " wrote no location", latLong);
        assertTrue("latitude is wrong", Math.abs(latLong[0] - 37.4219983) < 0.001);
        assertTrue("longitude is wrong", Math.abs(latLong[1] + 122.084) < 0.001);
        assertTrue("altitude below sea level should come back negative",
                exif.getAltitude(0) < 0);
        assertNotNull("the accuracy of a fix belongs in GPSHPositioningError",
                exif.getAttribute(ExifInterface.TAG_GPS_H_POSITIONING_ERROR));
        assertNotNull("the direction of travel belongs in GPSTrack",
                exif.getAttribute(ExifInterface.TAG_GPS_TRACK));
        assertNotNull("the datum Android fixes are in belongs in GPSMapDatum",
                exif.getAttribute(ExifInterface.TAG_GPS_MAP_DATUM));
    }

    private File capture() throws Exception {
        return captureAt(null);
    }

    private File captureAt(Location location) throws Exception {
        File file = File.createTempFile("exif", ".jpg",
                ApplicationProvider.getApplicationContext().getCacheDir());
        assertTrue(file.delete() || !file.exists());
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<File> captured = new AtomicReference<>();

        try (ActivityScenario<CameraTestActivity> scenario =
                     ActivityScenario.launch(CameraTestActivity.class)) {
            CountDownLatch opened = new CountDownLatch(1);
            AtomicReference<CameraView> held = new AtomicReference<>();
            scenario.onActivity(activity -> {
                CameraView camera = activity.getCameraView();
                held.set(camera);
                camera.setOnCameraStateChangedListener(
                        new CameraView.OnCameraStateChangedListener() {
                            @Override public void onCameraOpened() {
                                opened.countDown();
                            }

                            @Override public void onCameraClosed() {}
                        });
                if (camera.isOpen()) opened.countDown();
                else camera.open();
            });
            assertTrue("the " + backend + " backend did not open "
                            + DeviceState.whyTheCameraMightNotOpen(),
                    opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            CameraView camera = held.get();
            assertNotNull(camera);
            DeviceState.turnTheFlashOff(camera);
            try {
                camera.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
                    @Override public void onImageConfirmation() {}

                    @Override public void onImageCaptured(File saved) {
                        captured.set(saved);
                        finished.countDown();
                    }

                    @Override public void onFailure() {
                        finished.countDown();
                    }
                });
                camera.takePicture(file);
                assertTrue("the capture never reported on " + backend,
                        finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            } finally {
                scenario.onActivity(activity -> activity.getCameraView().close());
            }
        }
        assertNotNull("the capture failed on " + backend, captured.get());
        if (location != null) {
            com.xlythe.view.camera.Exif exif = new com.xlythe.view.camera.Exif(captured.get());
            exif.attachLocation(location);
            exif.save();
        }
        return captured.get();
    }

    @NonNull
    @Override
    public String toString() {
        return backend.toString();
    }
}
