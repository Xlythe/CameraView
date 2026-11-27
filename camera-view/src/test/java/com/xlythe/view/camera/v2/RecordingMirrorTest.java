package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.shadow.ShadowCallbackCameraCaptureSession;
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
 * Which Camera2 recordings go through GL to be mirrored like their preview, and what the module
 * says about the file afterwards.
 *
 * <p>The mirror itself needs a GPU and is asserted on a device, in
 * {@code FrontRecordingMirrorDeviceTest}. Robolectric's EGL starts and draws nothing, which is
 * enough to follow a recording down the path it takes; {@link ShadowUnavailableEgl} stands in for
 * a device where GL will not run.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, shadows = {ShadowCallbackCameraCaptureSession.class},
        qualifiers = "w360dp-h800dp-xxxhdpi")
public class RecordingMirrorTest {
    /** The name Camera2Module gives the render thread a recording has to itself. */
    private static final String RECORDING_RENDERER = "CameraViewCamera2Recorder";

    /** How long a stopped render thread is given to finish, since it quits rather than dies. */
    private static final long THREAD_EXIT_MILLIS = 2_000;

    private FakeCamera2.TestCameraView cameraView;
    private Camera2Module module;
    private File output;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        FakeCamera2.addBackAndFrontCameras(context);
        cameraView = FakeCamera2.laidOutCameraView(context, 1080, 1920);
        module = new Camera2Module(cameraView);
        output = new File(context.getCacheDir(), "mirror-test.mp4");
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

    /** The back camera's preview is not mirrored, so its recording has nothing to gain from GL. */
    @Test
    public void aBackRecordingWithNothingToDrawStaysOnTheCamera() {
        assertEquals(RecordingPath.CAMERA, VideoSession.recordingPath(false, false, true));
        assertEquals(RecordingPath.CAMERA, VideoSession.recordingPath(false, false, false));
    }

    @Test
    public void aFrontRecordingIsDrawnToBeMirrored() {
        assertEquals(RecordingPath.MIRROR, VideoSession.recordingPath(false, true, true));
    }

    /** Where GL will not run, the recording is made as it always was, and left unmirrored. */
    @Test
    public void aFrontRecordingStaysOnTheCameraWhereGlWillNotRun() {
        assertEquals(RecordingPath.CAMERA, VideoSession.recordingPath(false, true, false));
    }

    /**
     * An effect is already drawing the preview, so it draws the recording too, whichever lens
     * it is. GL is plainly running if an effect is.
     */
    @Test
    public void anEffectDrawsEveryRecording() {
        assertEquals(RecordingPath.EFFECT, VideoSession.recordingPath(true, false, true));
        assertEquals(RecordingPath.EFFECT, VideoSession.recordingPath(true, true, true));
    }

    /** A back recording never brings GL up, so it costs what it always did. */
    @Test
    public void aBackRecordingGoesStraightToTheRecorder() {
        module.open();
        FakeCamera2.idleMainLooper();

        module.startRecording(output);
        FakeCamera2.idleMainLooper();

        assertTrue("the recording should have started", module.isRecording());
        assertEquals(RecordingPath.CAMERA, recordingSession().getRecordingPath());
        assertFalse("a back recording should not start a renderer", recordingRendererIsRunning());
        assertFalse(module.isFrontVideoMirrored());
    }

    /**
     * A front recording is drawn by a renderer of its own, for as long as it lasts, and the file
     * is reported mirrored after it has stopped - which is when the confirmation asks.
     */
    @Test
    public void aFrontRecordingIsDrawnMirroredAndSaysSo() throws Exception {
        module.setLensFacing(CameraView.LensFacing.FRONT);
        module.open();
        FakeCamera2.idleMainLooper();
        assertTrue(module.isUsingFrontFacingCamera());

        module.startRecording(output);
        FakeCamera2.idleMainLooper();

        assertTrue("the recording should have started", module.isRecording());
        assertEquals(RecordingPath.MIRROR, recordingSession().getRecordingPath());
        assertTrue("a front recording should be drawn by its own renderer",
                recordingRendererIsRunning());
        assertTrue(module.isFrontVideoMirrored());

        module.stopRecording();
        FakeCamera2.idleMainLooper();

        assertFalse("the renderer should go with the recording", awaitRecordingRendererGone());
        assertTrue("the answer has to outlive the recording, for the confirmation",
                module.isFrontVideoMirrored());
        assertTrue("a recording that ran through GL leaves effects on offer",
                module.isFrameEffectSupported());
    }

    /**
     * Where GL will not run, a front recording goes ahead on the camera, unmirrored, and says
     * so; and GL is not offered again, to an effect or to the next recording.
     */
    @Test
    @Config(shadows = {ShadowUnavailableEgl.class})
    public void aFrontRecordingWhereGlWillNotRunIsMadeUnmirrored() {
        module.setLensFacing(CameraView.LensFacing.FRONT);
        module.open();
        FakeCamera2.idleMainLooper();

        module.startRecording(output);
        FakeCamera2.idleMainLooper();

        assertTrue("the recording should have gone ahead without GL", module.isRecording());
        assertEquals(RecordingPath.CAMERA, recordingSession().getRecordingPath());
        assertFalse("a file the camera wrote is not mirrored", module.isFrontVideoMirrored());
        assertFalse(module.isGlAvailable());
        assertFalse(module.isFrameEffectSupported());
    }

    /** The session the module is recording through. */
    private VideoSession recordingSession() {
        try {
            Field field = Camera2Module.class.getDeclaredField("mActiveSession");
            field.setAccessible(true);
            Object session = field.get(module);
            assertTrue("expected a VideoSession, not " + session, session instanceof VideoSession);
            return (VideoSession) session;
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

    /** @return whether the renderer is still running once it has had time to finish */
    private static boolean awaitRecordingRendererGone() throws InterruptedException {
        long deadline = System.currentTimeMillis() + THREAD_EXIT_MILLIS;
        while (recordingRendererIsRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        return recordingRendererIsRunning();
    }
}
