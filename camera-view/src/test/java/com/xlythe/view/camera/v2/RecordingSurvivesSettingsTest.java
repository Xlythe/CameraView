package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import android.content.Context;
import android.graphics.ColorSpace;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.ColorSpaceProfiles;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Size;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.shadow.ShadowCallbackCameraCaptureSession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A recording is never cut short by a settings change.
 *
 * <p>The colour space, the picture format and the quality are each carried by the session rather
 * than by a request, so changing one rebuilds the session - and rebuilding the session a recording
 * runs in ends the recording. A change made while recording therefore waits, and the session built
 * after the recording carries it.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, shadows = {ShadowCallbackCameraCaptureSession.class},
        qualifiers = "w360dp-h800dp-xxxhdpi")
public class RecordingSurvivesSettingsTest {
    private Context context;
    private FakeCamera2.TestCameraView cameraView;
    private Camera2Module module;
    private File output;
    /** Every terminal callback the recording reported, success or failure. */
    private final AtomicInteger reports = new AtomicInteger();

    @Before
    public void setUp() {
        ShadowCallbackCameraCaptureSession.reset();
        context = RuntimeEnvironment.getApplication();
        FakeCamera2.addBackAndFrontCameras(context);
        cameraView = FakeCamera2.laidOutCameraView(context, 1080, 1920);
        module = new Camera2Module(cameraView);
        output = new File(context.getCacheDir(), "settings-test.mp4");
        cameraView.setOnVideoCapturedListener(new CameraView.OnVideoCapturedListener() {
            @Override public void onVideoConfirmation() {}

            @Override public void onVideoCaptured(File file) {
                reports.incrementAndGet();
            }

            @Override public void onFailure() {
                reports.incrementAndGet();
            }
        });
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
    @Config(minSdk = 34)
    public void aColourSpaceChangeWaitsForTheRecordingToEnd() {
        givenDisplayP3();
        Camera2Module.Session recording = startRecording();

        module.setColorSpace(CameraView.ColorSpace.DISPLAY_P3);
        FakeCamera2.idleMainLooper();

        assertStillRecording(recording);
        assertEquals("the change is kept for later",
                CameraView.ColorSpace.DISPLAY_P3, module.getColorSpace());
        assertTheNextSessionFollows(recording);
    }

    /** Asked for while the recording session is still being configured, which is no different. */
    @Test
    @Config(minSdk = 34)
    public void aColourSpaceChangeWaitsForARecordingThatHasNotStarted() {
        givenDisplayP3();
        module.open();
        FakeCamera2.idleMainLooper();

        module.startRecording(output);
        module.setColorSpace(CameraView.ColorSpace.DISPLAY_P3);
        FakeCamera2.idleMainLooper();

        assertTrue(activeSession() instanceof VideoSession);
        assertStillRecording(activeSession());
    }

    @Test
    @Config(minSdk = 34)
    public void aPictureFormatChangeWaitsForTheRecordingToEnd() {
        givenUltraHdr();
        Camera2Module.Session recording = startRecording();

        module.setPictureFormat(CameraView.PictureFormat.ULTRA_HDR);
        FakeCamera2.idleMainLooper();

        assertStillRecording(recording);
        assertEquals(CameraView.PictureFormat.ULTRA_HDR, module.getPictureFormat());
        assertTheNextSessionFollows(recording);
    }

    @Test
    public void aQualityChangeWaitsForTheRecordingToEnd() {
        Camera2Module.Session recording = startRecording();

        module.setQuality(CameraView.Quality.LOW);
        FakeCamera2.idleMainLooper();

        assertStillRecording(recording);
        assertEquals(CameraView.Quality.LOW, module.getQuality());
        assertTheNextSessionFollows(recording);
    }

    /** Opens the camera and starts recording, returning the session the recording runs in. */
    private Camera2Module.Session startRecording() {
        module.open();
        FakeCamera2.idleMainLooper();
        module.startRecording(output);
        FakeCamera2.idleMainLooper();
        Camera2Module.Session recording = activeSession();
        assertTrue(recording instanceof VideoSession);
        assertTrue(module.isRecording());
        return recording;
    }

    private void assertStillRecording(Camera2Module.Session recording) {
        assertSame("the recording's session was replaced", recording, activeSession());
        assertTrue("the recording was stopped", module.isRecording());
        assertTrue(((VideoSession) recording).isRecording());
        assertEquals("the recording reported an end it never reached", 0, reports.get());
    }

    /** Stopping reports the recording once, and builds a picture session with the change. */
    private void assertTheNextSessionFollows(Camera2Module.Session recording) {
        module.stopRecording();
        FakeCamera2.idleMainLooper();

        assertEquals("one recording, reported once", 1, reports.get());
        assertFalse(module.isRecording());
        assertNotSame(recording, activeSession());
        assertTrue(activeSession() instanceof PictureSession);
        assertFalse(activeSession() instanceof VideoSession);
    }

    /** Teaches the fake back camera the Display P3 profile for JPEG. */
    private void givenDisplayP3() {
        long[] packed = {
                ColorSpace.Named.DISPLAY_P3.ordinal(),
                ImageFormat.JPEG,
                ColorSpaceProfiles.UNSPECIFIED,
        };
        FakeCamera2.setCharacteristic(context, FakeCamera2.BACK_ID,
                CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES,
                new ColorSpaceProfiles(packed));
    }

    /**
     * Has the fake back camera list a JPEG_R size. The map is a spy of the real one, because
     * Robolectric's builder has no JPEG_R configuration to add a size to.
     */
    private void givenUltraHdr() {
        try {
            StreamConfigurationMap map = context.getSystemService(CameraManager.class)
                    .getCameraCharacteristics(FakeCamera2.BACK_ID)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            StreamConfigurationMap withJpegR = spy(map);
            doReturn(new Size[] {FakeCamera2.SIZE_1080P})
                    .when(withJpegR).getOutputSizes(ImageFormat.JPEG_R);
            FakeCamera2.setCharacteristic(context, FakeCamera2.BACK_ID,
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP, withJpegR);
        } catch (CameraAccessException error) {
            throw new AssertionError(error);
        }
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
