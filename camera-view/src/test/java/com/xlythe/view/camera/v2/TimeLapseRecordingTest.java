package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.media.MediaRecorder;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.shadow.ShadowCallbackCameraCaptureSession;
import com.xlythe.view.camera.shadow.ShadowGeotaggingMediaRecorder;
import com.xlythe.view.camera.shadow.ShadowUnavailableEgl;
import com.xlythe.view.camera.v2.VideoSession.RecordingPath;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.lang.reflect.Field;

/**
 * Who keeps a Camera2 time-lapse's frames, and what the recorder is told.
 *
 * <p>Where GL runs, a renderer of the recording's own keeps them and the recorder is told nothing
 * about a time-lapse; where it does not, the camera writes straight into the recorder, which is
 * told to keep them itself. Either way there is no microphone. Which frames a renderer keeps is
 * {@code TimeLapseTest}'s; how long the file plays is asserted on a device.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, shadows = {
        ShadowCallbackCameraCaptureSession.class,
        ShadowGeotaggingMediaRecorder.class},
        qualifiers = "w360dp-h800dp-xxxhdpi")
public class TimeLapseRecordingTest {
    /** The name Camera2Module gives the render thread a recording has to itself. */
    private static final String RECORDING_RENDERER = "CameraViewCamera2Recorder";

    private FakeCamera2.TestCameraView cameraView;
    private Camera2Module module;
    private File output;

    @Before
    public void setUp() {
        ShadowGeotaggingMediaRecorder.forgetEverything();
        Context context = RuntimeEnvironment.getApplication();
        FakeCamera2.addBackAndFrontCameras(context);
        cameraView = FakeCamera2.laidOutCameraView(context, 1080, 1920);
        module = new Camera2Module(cameraView);
        output = new File(context.getCacheDir(), "time-lapse-test.mp4");
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

    /** A time-lapse goes through GL even from the back camera, which keeps nothing else there. */
    @Test
    public void aTimeLapseIsDrawnByARendererOfItsOwn() {
        assertEquals(RecordingPath.RECORDING_RENDERER,
                VideoSession.recordingPath(false, false, true, true));
        assertEquals(RecordingPath.EFFECT, VideoSession.recordingPath(true, false, true, true));
        assertEquals(RecordingPath.CAMERA, VideoSession.recordingPath(false, false, false, true));
    }

    /**
     * A back time-lapse records through a renderer of its own, which keeps the frames, so the
     * recorder is not asked to and is given no microphone.
     */
    @Test
    public void aBackTimeLapseIsKeptByItsRendererWithoutSound() {
        module.setTimeLapseSpeed(10);
        module.open();
        FakeCamera2.idleMainLooper();

        module.startRecording(output);
        FakeCamera2.idleMainLooper();

        assertTrue("the recording should have started", module.isRecording());
        assertEquals(RecordingPath.RECORDING_RENDERER, recordingSession().getRecordingPath());
        assertTrue("its renderer keeps the frames", recordingRendererIsRunning());
        assertNotNull("and is handed a time-lapse to keep them by", recordingTimeLapse());
        assertNull("the recorder records what it is given",
                ShadowGeotaggingMediaRecorder.getLastCaptureRate());
        assertNull("a time-lapse has no sound", ShadowGeotaggingMediaRecorder.getLastAudioSource());
        assertFalse("a back recording is not mirrored", module.isFrontVideoMirrored());
    }

    /** Where GL will not run, the recorder keeps a time-lapse's frames itself. */
    @Test
    @Config(shadows = {ShadowUnavailableEgl.class})
    public void whereGlWillNotRunTheRecorderKeepsTheFrames() {
        module.setTimeLapseSpeed(10);
        module.open();
        FakeCamera2.idleMainLooper();

        module.startRecording(output);
        FakeCamera2.idleMainLooper();

        assertTrue("the recording should have gone ahead without GL", module.isRecording());
        assertEquals(RecordingPath.CAMERA, recordingSession().getRecordingPath());
        Double captureRate = ShadowGeotaggingMediaRecorder.getLastCaptureRate();
        assertNotNull("the recorder is told to keep one frame in ten", captureRate);
        assertEquals("three frames a second, played at thirty", 3.0, captureRate, 1e-9);
        assertNull(recordingTimeLapse());
        assertNull(ShadowGeotaggingMediaRecorder.getLastAudioSource());
    }

    /** A recording at normal speed still has its sound, and the recorder keeps every frame. */
    @Test
    public void aNormalRecordingKeepsItsSound() {
        module.open();
        FakeCamera2.idleMainLooper();

        module.startRecording(output);
        FakeCamera2.idleMainLooper();

        assertEquals(Integer.valueOf(MediaRecorder.AudioSource.CAMCORDER),
                ShadowGeotaggingMediaRecorder.getLastAudioSource());
        assertNull(ShadowGeotaggingMediaRecorder.getLastCaptureRate());
        assertNull(recordingTimeLapse());
    }

    /** The speed is read when a recording starts, so changing it mid-recording changes nothing. */
    @Test
    public void theSpeedIsReadWhenARecordingStarts() {
        module.setLensFacing(CameraView.LensFacing.FRONT);
        module.open();
        FakeCamera2.idleMainLooper();
        module.startRecording(output);
        FakeCamera2.idleMainLooper();

        module.setTimeLapseSpeed(10);

        assertNull("the recording under way keeps every frame", recordingTimeLapse());
    }

    /** The session the module is recording through. */
    private VideoSession recordingSession() {
        Object session = field(Camera2Module.class, module, "mActiveSession");
        assertTrue("expected a VideoSession, not " + session, session instanceof VideoSession);
        return (VideoSession) session;
    }

    /** The time-lapse the recording's renderer keeps its frames by, or null for none. */
    private Object recordingTimeLapse() {
        Object surface = field(VideoSession.class, recordingSession(), "mVideoSurface");
        return field(surface.getClass(), surface, "mTimeLapse");
    }

    private static Object field(Class<?> owner, Object instance, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(instance);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static boolean recordingRendererIsRunning() {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (RECORDING_RENDERER.equals(thread.getName()) && thread.isAlive()) {
                return true;
            }
        }
        return false;
    }
}
