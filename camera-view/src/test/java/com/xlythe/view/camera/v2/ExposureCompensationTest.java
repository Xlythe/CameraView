package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.util.Range;

import androidx.exifinterface.media.ExifInterface;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.Exif;
import com.xlythe.view.camera.shadow.ShadowCallbackCameraCaptureSession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.List;

/**
 * Exposure compensation on the Camera2 backend, which has to mean what it means on CameraX: an
 * index in the camera's own steps, within CONTROL_AE_COMPENSATION_RANGE, clamped to it once there
 * is a camera to ask.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, shadows = {ShadowCallbackCameraCaptureSession.class},
        qualifiers = "w360dp-h800dp-xxxhdpi")
public class ExposureCompensationTest {
    private Context context;
    private FakeCamera2.TestCameraView cameraView;
    private Camera2Module module;
    private File output;

    @Before
    public void setUp() {
        ShadowCallbackCameraCaptureSession.reset();
        context = RuntimeEnvironment.getApplication();
        FakeCamera2.addBackAndFrontCameras(context);
        cameraView = FakeCamera2.laidOutCameraView(context, 1080, 1920);
        module = new Camera2Module(cameraView);
        output = new File(context.getCacheDir(), "exposure-test.mp4");
    }

    @After
    public void tearDown() {
        module.close();
        FakeCamera2.idleMainLooper();
        cameraView.release();
        if (output.exists() && !output.delete()) {
            output.deleteOnExit();
        }
    }

    @Test
    public void reportsTheCamerasRange() {
        givenControls();

        assertTrue(module.isExposureCompensationSupported());
        assertEquals((int) FakeCamera2.EXPOSURE_RANGE.getLower(),
                module.getMinExposureCompensation());
        assertEquals((int) FakeCamera2.EXPOSURE_RANGE.getUpper(),
                module.getMaxExposureCompensation());
    }

    /** A camera that publishes no range cannot compensate, and an open one refuses the request. */
    @Test
    public void aCameraWithoutARangeDeclines() {
        assertFalse(module.isExposureCompensationSupported());
        assertEquals(0, module.getMinExposureCompensation());
        assertEquals(0, module.getMaxExposureCompensation());
        open();

        module.setExposureCompensation(3);
        FakeCamera2.idleMainLooper();

        assertEquals(0, module.getExposureCompensation());
        assertNull(repeating().get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION));
    }

    /**
     * A range that stops at zero on one side is published as it is, but counts as no range: the
     * camera cannot go both ways, and that is the line the CameraX backend draws too.
     */
    @Test
    public void aRangeThatStopsAtZeroIsUnsupported() {
        FakeCamera2.setCharacteristic(context, FakeCamera2.BACK_ID,
                CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE, new Range<>(0, 4));

        assertFalse(module.isExposureCompensationSupported());
        assertEquals(0, module.getMinExposureCompensation());
        assertEquals(4, module.getMaxExposureCompensation());
    }

    @Test
    public void theIndexLandsOnThePreviewAStillAndARecording() {
        givenControls();
        open();

        module.setExposureCompensation(4);
        FakeCamera2.idleMainLooper();
        assertEquals(4, module.getExposureCompensation());
        assertEquals(Integer.valueOf(4),
                repeating().get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION));

        module.takePicture(new File(context.getCacheDir(), "exposure-still.jpg"));
        FakeCamera2.idleMainLooper();
        List<CaptureRequest> stills = ShadowCallbackCameraCaptureSession.getCaptures();
        assertEquals(1, stills.size());
        assertEquals(Integer.valueOf(4),
                stills.get(0).get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION));

        module.startRecording(output);
        FakeCamera2.idleMainLooper();
        assertTrue(activeSession() instanceof VideoSession);
        assertEquals(Integer.valueOf(4),
                repeating().get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION));
    }

    @Test
    public void anIndexOutsideTheRangeIsClamped() {
        givenControls();
        open();

        module.setExposureCompensation(40);
        FakeCamera2.idleMainLooper();
        assertEquals(12, module.getExposureCompensation());
        assertEquals(Integer.valueOf(12),
                repeating().get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION));

        module.setExposureCompensation(-40);
        FakeCamera2.idleMainLooper();
        assertEquals(-12, module.getExposureCompensation());
        assertEquals(Integer.valueOf(-12),
                repeating().get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION));
    }

    /** With no camera to ask, the index is kept as given, and clamped once there is one. */
    @Test
    public void anIndexSetBeforeOpeningIsClampedOnceConfigured() {
        givenControls();

        module.setExposureCompensation(40);
        assertEquals(40, module.getExposureCompensation());

        open();
        assertEquals(12, module.getExposureCompensation());
        assertEquals(Integer.valueOf(12),
                repeating().get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION));
    }

    /** A lens with a narrower range holds the index to it; a rebuilt session keeps it. */
    @Test
    public void theIndexSurvivesALensSwitchAndAQualityChange() {
        givenControls();
        FakeCamera2.setCharacteristic(context, FakeCamera2.FRONT_ID,
                CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE, new Range<>(-6, 6));
        open();
        module.setExposureCompensation(10);
        FakeCamera2.idleMainLooper();

        module.setLensFacing(CameraView.LensFacing.FRONT);
        FakeCamera2.idleMainLooper();
        assertTrue(module.isUsingFrontFacingCamera());
        assertEquals(6, module.getExposureCompensation());
        assertEquals(Integer.valueOf(6),
                repeating().get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION));

        module.setQuality(CameraView.Quality.LOW);
        FakeCamera2.idleMainLooper();
        assertEquals(Integer.valueOf(6),
                repeating().get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION));
    }

    /** A picture's Exif reports the bias its requests carried: the index times the step, in EV. */
    @Test
    public void aPictureRecordsTheBiasInEv() throws Exception {
        givenControls();
        open();
        module.setExposureCompensation(4);
        FakeCamera2.idleMainLooper();

        assertEquals(4 / 6.0, exifExposureBias(), 0.001);

        module.setExposureCompensation(-40);
        FakeCamera2.idleMainLooper();
        assertEquals("the clamped index, as the requests carry it", -2.0, exifExposureBias(), 0.001);
    }

    /** A camera that cannot compensate takes pictures with none, whatever index is kept. */
    @Test
    public void aCameraWithoutARangeRecordsNoBias() throws Exception {
        module.setExposureCompensation(3);
        open();
        assertEquals("kept for a camera that can", 3, module.getExposureCompensation());

        assertEquals(0.0, exifExposureBias(), 0.0);
    }

    @Test
    public void anUnchangedIndexIsNotReissued() {
        givenControls();
        open();
        module.setExposureCompensation(2);
        FakeCamera2.idleMainLooper();
        int before = ShadowCallbackCameraCaptureSession.getRepeatingRequests().size();

        module.setExposureCompensation(2);
        FakeCamera2.idleMainLooper();

        assertEquals(before, ShadowCallbackCameraCaptureSession.getRepeatingRequests().size());
    }

    private void givenControls() {
        FakeCamera2.addExposureAndStabilization(context, FakeCamera2.BACK_ID);
        FakeCamera2.addExposureAndStabilization(context, FakeCamera2.FRONT_ID);
    }

    private void open() {
        module.open();
        FakeCamera2.idleMainLooper();
        assertNotNull("expected a session", activeSession());
    }

    /** The ExposureBiasValue a picture taken now would be tagged with, in EV. */
    private double exifExposureBias() throws Exception {
        Exif exif = new Exif(Mockito.mock(InputStream.class));
        module.exifCaptureParameters().applyTo(exif);
        String[] rational = exif.getAttribute(ExifInterface.TAG_EXPOSURE_BIAS_VALUE).split("/");
        return Double.parseDouble(rational[0]) / Double.parseDouble(rational[1]);
    }

    private static CaptureRequest repeating() {
        CaptureRequest request = ShadowCallbackCameraCaptureSession.getLastRepeatingRequest();
        assertNotNull("expected a repeating request", request);
        return request;
    }

    /** Reaches the module's active session, which it keeps private. */
    private Camera2Module.Session activeSession() {
        try {
            Field field = Camera2Module.class.getDeclaredField("mActiveSession");
            field.setAccessible(true);
            return (Camera2Module.Session) field.get(module);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }
}
