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
 * Which Exif tags a capture carries. Logs every tag present, and asserts the ones Exif requires
 * of a compressed image, since what lands in the file depends mostly on the device.
 */
@RunWith(Parameterized.class)
public final class ExifCoverageDeviceTest {
    private static final String TAG = "ExifCoverage";
    private static final long TIMEOUT_SECONDS = 30;

    /**
     * Mandatory for a compressed image: YCbCrPositioning (CIPA DC-008 table 17), and ExifVersion,
     * ComponentsConfiguration, ColorSpace, PixelXDimension and PixelYDimension (table 18).
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

    /**
     * How the picture was taken. Which of these a camera writes is its own choice, so each is
     * logged, and checked for sense only where it is present.
     */
    private static final String[] CAPTURE_PARAMETERS = {
            ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_FLASH,
            ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
            ExifInterface.TAG_SUBJECT_DISTANCE, ExifInterface.TAG_LENS_SPECIFICATION,
            ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_SCENE_CAPTURE_TYPE,
            ExifInterface.TAG_EXPOSURE_MODE, ExifInterface.TAG_APERTURE_VALUE,
            ExifInterface.TAG_SHUTTER_SPEED_VALUE, ExifInterface.TAG_MAX_APERTURE_VALUE,
            ExifInterface.TAG_SENSING_METHOD, ExifInterface.TAG_FILE_SOURCE,
            ExifInterface.TAG_SCENE_TYPE, ExifInterface.TAG_IMAGE_UNIQUE_ID,
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
     * An optional tag that is present still has to be valid: zero is neither legal resolution
     * unit, and a resolution of zero describes nothing.
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

    /**
     * What each backend's file says about how the picture was taken. The camera's own values are
     * logged rather than asserted, and nonsense from a camera is reported rather than failed,
     * since the library leaves what a camera wrote alone. What the library fills wherever the
     * camera did not is asserted present and sane.
     */
    @Test
    public void aCaptureSaysHowItWasTaken() throws Exception {
        ExifInterface exif = new ExifInterface(capture().getAbsolutePath());

        List<String> carried = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String tag : CAPTURE_PARAMETERS) {
            String value = exif.getAttribute(tag);
            if (value != null) {
                carried.add(tag + "=" + value);
            } else {
                missing.add(tag);
            }
        }
        Log.i(TAG, backend + " capture parameters carried: " + carried);
        Log.i(TAG, backend + " capture parameters missing: " + missing);
        List<String> implausible = implausibleCaptureParameters(exif);
        if (!implausible.isEmpty()) {
            Log.w(TAG, backend + " capture parameters out of range: " + implausible);
        }

        // Every capture the library makes is automatic, unbiased and of a standard scene, which
        // it can always say.
        assertInRange(exif, ExifInterface.TAG_EXPOSURE_MODE, 0, 2);
        assertInRange(exif, ExifInterface.TAG_EXPOSURE_PROGRAM, 0, 8);
        assertInRange(exif, ExifInterface.TAG_WHITE_BALANCE, 0, 1);
        assertInRange(exif, ExifInterface.TAG_SCENE_CAPTURE_TYPE, 0, 3);
        assertInRange(exif, ExifInterface.TAG_EXPOSURE_BIAS_VALUE, -10, 10);
        assertNotNull(backend + " wrote no FileSource",
                exif.getAttribute(ExifInterface.TAG_FILE_SOURCE));
        assertNotNull(backend + " wrote no SceneType",
                exif.getAttribute(ExifInterface.TAG_SCENE_TYPE));
        String id = exif.getAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID);
        assertTrue(backend + " wrote no ImageUniqueID", id != null && !id.trim().isEmpty());
        // The APEX forms follow from the exposure and aperture wherever those are known.
        if (exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME) != null) {
            assertInRange(exif, ExifInterface.TAG_SHUTTER_SPEED_VALUE, -20, 30);
        }
        if (exif.getAttribute(ExifInterface.TAG_F_NUMBER) != null) {
            assertInRange(exif, ExifInterface.TAG_APERTURE_VALUE, 0, 20);
        }
        // A camera2 camera publishes the focal lengths and apertures of its lenses, which the
        // specification spans. Camera1 publishes no aperture, so legacy has none to write.
        if (backend != CameraView.Backend.LEGACY) {
            String lens = exif.getAttribute(ExifInterface.TAG_LENS_SPECIFICATION);
            assertNotNull(backend + " wrote no LensSpecification", lens);
            String[] parts = lens.split(",");
            assertTrue("a lens specification is four rationals: " + lens, parts.length == 4);
            assertTrue("a lens specification runs from short to long: " + lens,
                    rational(parts[0]) > 0 && rational(parts[0]) <= rational(parts[1]));
            assertTrue("a lens specification has an f-number at each end: " + lens,
                    rational(parts[2]) > 0 && rational(parts[3]) > 0);
        }
    }

    private void assertInRange(
            @NonNull ExifInterface exif, @NonNull String tag, double min, double max) {
        String value = exif.getAttribute(tag);
        assertNotNull(backend + " wrote no " + tag, value);
        double number = exif.getAttributeDouble(tag, Double.NaN);
        assertTrue(backend + " wrote " + tag + " = " + value, number >= min && number <= max);
    }

    /** The capture parameters whose values no camera could have meant. */
    @NonNull
    private static List<String> implausibleCaptureParameters(@NonNull ExifInterface exif) {
        List<String> implausible = new ArrayList<>();
        checkRange(exif, ExifInterface.TAG_EXPOSURE_TIME, Double.MIN_VALUE, 3600, implausible);
        checkRange(exif, ExifInterface.TAG_F_NUMBER, Double.MIN_VALUE, 100, implausible);
        checkRange(exif, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 1, 65535, implausible);
        checkRange(exif, ExifInterface.TAG_FOCAL_LENGTH, Double.MIN_VALUE, 1000, implausible);
        // Zero is how the standard says "unknown" for these.
        checkRange(exif, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0, 2000, implausible);
        checkRange(exif, ExifInterface.TAG_DIGITAL_ZOOM_RATIO, 0, 1000, implausible);
        checkRange(exif, ExifInterface.TAG_SUBJECT_DISTANCE, 0, Double.MAX_VALUE, implausible);
        checkRange(exif, ExifInterface.TAG_EXPOSURE_BIAS_VALUE, -10, 10, implausible);
        // Bits 0 to 6 are all the flash tag defines.
        checkRange(exif, ExifInterface.TAG_FLASH, 0, 0x7F, implausible);
        checkRange(exif, ExifInterface.TAG_WHITE_BALANCE, 0, 1, implausible);
        checkRange(exif, ExifInterface.TAG_EXPOSURE_PROGRAM, 0, 8, implausible);
        checkRange(exif, ExifInterface.TAG_EXPOSURE_MODE, 0, 2, implausible);
        checkRange(exif, ExifInterface.TAG_SCENE_CAPTURE_TYPE, 0, 3, implausible);
        int metering = exif.getAttributeInt(ExifInterface.TAG_METERING_MODE, 0);
        if ((metering < 0 || metering > 6) && metering != 255) {
            implausible.add(ExifInterface.TAG_METERING_MODE + "=" + metering);
        }
        String lens = exif.getAttribute(ExifInterface.TAG_LENS_SPECIFICATION);
        if (lens != null) {
            String[] parts = lens.split(",");
            if (parts.length != 4 || rational(parts[0]) > rational(parts[1])) {
                implausible.add(ExifInterface.TAG_LENS_SPECIFICATION + "=" + lens);
            }
        }
        return implausible;
    }

    private static void checkRange(@NonNull ExifInterface exif, @NonNull String tag, double min,
            double max, @NonNull List<String> implausible) {
        if (exif.getAttribute(tag) == null) {
            return;
        }
        double value = exif.getAttributeDouble(tag, Double.NaN);
        if (!(value >= min && value <= max)) {
            implausible.add(tag + "=" + exif.getAttribute(tag));
        }
    }

    /** A rational as ExifInterface prints one, "1/3", as a number; 0/0 is unknown, so zero. */
    private static double rational(@NonNull String value) {
        String[] parts = value.split("/");
        if (parts.length != 2) {
            return Double.parseDouble(value);
        }
        double denominator = Double.parseDouble(parts[1]);
        return denominator == 0 ? 0 : Double.parseDouble(parts[0]) / denominator;
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
