package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.media.MediaActionSound;

import com.xlythe.view.camera.CameraSoundsTest;
import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.shadow.ShadowCallbackCameraCaptureSession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowMediaActionSound;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The Camera2 recording path, which only becomes reachable once capture callbacks are delivered:
 * VideoSession arms the recorder and waits for the first completed capture before starting it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, shadows = {ShadowCallbackCameraCaptureSession.class},
        qualifiers = "w360dp-h800dp-xxxhdpi")
public class VideoRecordingTest {
    private Context context;
    private FakeCamera2.TestCameraView cameraView;
    private Camera2Module module;
    private File output;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        FakeCamera2.addBackAndFrontCameras(context);
        cameraView = FakeCamera2.laidOutCameraView(context, 1080, 1920);
        module = new Camera2Module(cameraView);
        output = new File(context.getCacheDir(), "recording-test.mp4");
        CameraSoundsTest.resetSounds();
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
    public void captureCallbacksReachTheSessionDuringRecording() {
        module.open();
        FakeCamera2.idleMainLooper();

        // The preview registers no callback, so only a recording asks to be told about frames.
        module.startRecording(output);
        FakeCamera2.idleMainLooper();

        assertTrue("the recording request should have produced a completed capture",
                ShadowCallbackCameraCaptureSession.getCompletedCaptureCount() > 0);
    }

    @Test
    public void startingARecordingSwitchesToAVideoSession() {
        module.open();
        FakeCamera2.idleMainLooper();

        module.startRecording(output);
        FakeCamera2.idleMainLooper();

        assertTrue("expected a VideoSession", activeSession() instanceof VideoSession);
        assertTrue(module.isRecording());
    }

    @Test
    public void stoppingARecordingReturnsToAPictureSession() {
        module.open();
        FakeCamera2.idleMainLooper();
        module.startRecording(output);
        FakeCamera2.idleMainLooper();

        module.stopRecording();
        FakeCamera2.idleMainLooper();

        assertFalse(module.isRecording());
        assertTrue("expected a PictureSession again", activeSession() instanceof PictureSession);
    }

    /** A tone as the recorder starts and another once it has stopped, so neither is recorded. */
    @Test
    public void aRecordingIsBracketedByItsTones() {
        module.open();
        FakeCamera2.idleMainLooper();
        module.startRecording(output);
        FakeCamera2.idleMainLooper();

        assertEquals(1,
                ShadowMediaActionSound.getPlayCount(MediaActionSound.START_VIDEO_RECORDING));
        assertEquals(0,
                ShadowMediaActionSound.getPlayCount(MediaActionSound.STOP_VIDEO_RECORDING));

        module.stopRecording();
        FakeCamera2.idleMainLooper();

        assertEquals(1,
                ShadowMediaActionSound.getPlayCount(MediaActionSound.STOP_VIDEO_RECORDING));
    }

    @Test
    public void silencedRecordingsHaveNoTones() {
        module.setShutterSoundEnabled(false);
        module.open();
        FakeCamera2.idleMainLooper();
        module.startRecording(output);
        FakeCamera2.idleMainLooper();
        module.stopRecording();
        FakeCamera2.idleMainLooper();

        assertEquals(0,
                ShadowMediaActionSound.getPlayCount(MediaActionSound.START_VIDEO_RECORDING));
        assertEquals(0,
                ShadowMediaActionSound.getPlayCount(MediaActionSound.STOP_VIDEO_RECORDING));
    }

    @Test
    public void aSecondStartIsIgnoredWhileRecording() {
        module.open();
        FakeCamera2.idleMainLooper();
        module.startRecording(output);
        FakeCamera2.idleMainLooper();
        Camera2Module.Session first = activeSession();

        module.startRecording(new File(context.getCacheDir(), "second.mp4"));
        FakeCamera2.idleMainLooper();

        assertTrue("the first recording should still own the session", first == activeSession());
    }

    @Test
    public void recordingBeforeOpeningReportsFailure() {
        AtomicReference<Boolean> failed = new AtomicReference<>(false);
        cameraView.setOnVideoCapturedListener(new CameraView.OnVideoCapturedListener() {
            @Override public void onVideoConfirmation() {}
            @Override public void onVideoCaptured(File file) {}
            @Override public void onFailure() { failed.set(true); }
        });

        module.startRecording(output);
        FakeCamera2.idleMainLooper();

        assertTrue("expected onFailure rather than a silent no-op", failed.get());
        assertFalse(module.isRecording());
    }

    @Test
    public void closingWhileRecordingDoesNotLeaveItRecording() {
        module.open();
        FakeCamera2.idleMainLooper();
        module.startRecording(output);
        FakeCamera2.idleMainLooper();
        assertTrue(module.isRecording());

        module.close();
        FakeCamera2.idleMainLooper();

        assertFalse(module.isRecording());
        assertNotNull("the module should survive closing mid-recording", module);
    }

    private Camera2Module.Session activeSession() {
        try {
            java.lang.reflect.Field field = Camera2Module.class.getDeclaredField("mActiveSession");
            field.setAccessible(true);
            return (Camera2Module.Session) field.get(module);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }
}
