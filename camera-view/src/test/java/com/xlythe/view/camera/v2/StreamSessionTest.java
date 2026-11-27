package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.media.FakeImage;
import android.media.ImageReader;
import android.view.Surface;

import androidx.annotation.NonNull;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.shadow.ShadowCallbackCameraCaptureSession;
import com.xlythe.view.camera.shadow.ShadowInjectableImageReader;
import com.xlythe.view.camera.shadow.ShadowRefusingCameraDevice;
import com.xlythe.view.camera.stream.VideoRecorder;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPausedAsyncTask;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A stream attached to a Camera2 camera - the frames background replacement and frame analysis
 * read, or a live stream's encoder - and the pictures taken beside it.
 *
 * <p>A stream used to take the session over with one that had no still output, so every picture
 * taken while one ran failed, and any session built after it - for a recording, a setting or the
 * camera reopening - left it out, so it quietly stopped.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, qualifiers = "w360dp-h800dp-xxxhdpi", shadows = {
        ShadowCallbackCameraCaptureSession.class,
        ShadowInjectableImageReader.class,
        ShadowRefusingCameraDevice.class})
public class StreamSessionTest {
    /** Stands in for encoded JPEG bytes; nothing in the path parses them. */
    private static final byte[] JPEG_BYTES =
            "a-picture-taken-beside-a-stream".getBytes(StandardCharsets.US_ASCII);

    private FakeCamera2.TestCameraView cameraView;
    private Camera2Module module;
    private File output;
    private File video;
    private Stream stream;
    private final AtomicReference<File> captured = new AtomicReference<>();
    private final AtomicBoolean failed = new AtomicBoolean();

    @Before
    public void setUp() {
        ShadowCallbackCameraCaptureSession.reset();
        ShadowInjectableImageReader.reset();
        ShadowRefusingCameraDevice.reset();
        Context context = RuntimeEnvironment.getApplication();
        FakeCamera2.addBackAndFrontCameras(context);
        cameraView = FakeCamera2.laidOutCameraView(context, 1080, 1920);
        cameraView.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
            @Override public void onImageConfirmation() {}
            @Override public void onImageCaptured(File file) { captured.set(file); }
            @Override public void onFailure() { failed.set(true); }
        });
        // The picture is saved on AsyncTask's pool. Run it inline, so a capture finishes within
        // the test rather than on a thread nothing waits for.
        ShadowPausedAsyncTask.overrideExecutor(Runnable::run);
        module = new Camera2Module(cameraView);
        output = new File(context.getCacheDir(), "beside-a-stream.jpg");
        video = new File(context.getCacheDir(), "beside-a-stream.mp4");
        stream = new Stream();
    }

    @After
    public void tearDown() {
        module.close();
        FakeCamera2.idleMainLooper();
        cameraView.release();
        stream.release();
        for (File file : new File[] {output, video}) {
            if (file.exists() && !file.delete()) {
                file.deleteOnExit();
            }
        }
    }

    @Test
    public void aPictureIsTakenBesideAStream() {
        openWithStream();
        assertTrue("the stream's session has a still output", streamSession().hasStillCapture());

        module.takePicture(output);
        FakeCamera2.idleMainLooper();
        ImageReader jpeg = ShadowInjectableImageReader.latestReader(ImageFormat.JPEG);
        assertNotNull("the still is written by a reader of its own", jpeg);
        ShadowInjectableImageReader.offerImage(jpeg, FakeImage.jpeg(1920, 1080, JPEG_BYTES));
        FakeCamera2.idleMainLooper();
        FakeCamera2.idleMainLooper();

        assertFalse("the picture should not have failed", failed.get());
        assertEquals(output, captured.get());
        assertEquals(JPEG_BYTES.length, output.length());
        assertTrue("and the stream is still in the session", carriesTheStream());
    }

    /** A stream attached while recording joins the session after it, rather than ending it. */
    @Test
    public void aStreamAttachedWhileRecordingWaitsForTheRecordingToEnd() {
        module.open();
        FakeCamera2.idleMainLooper();
        module.startRecording(video);
        FakeCamera2.idleMainLooper();
        Camera2Module.Session recording = activeSession();
        assertTrue(recording instanceof VideoSession);

        module.attachSurface(stream);
        FakeCamera2.idleMainLooper();
        assertSame("the recording's session was replaced", recording, activeSession());

        module.stopRecording();
        FakeCamera2.idleMainLooper();
        assertTrue("the stream joins the session after the recording", carriesTheStream());
    }

    /** A recording does not drop a stream: the session after it carries the stream again. */
    @Test
    public void aStreamOutlivesARecording() {
        openWithStream();

        module.startRecording(video);
        FakeCamera2.idleMainLooper();
        module.stopRecording();
        FakeCamera2.idleMainLooper();

        assertTrue(carriesTheStream());
    }

    /** Nor does the camera closing and opening again, while the stream stays attached. */
    @Test
    public void aStreamOutlivesTheCameraReopening() {
        openWithStream();

        module.close();
        FakeCamera2.idleMainLooper();
        module.open();
        FakeCamera2.idleMainLooper();

        assertTrue(carriesTheStream());
    }

    /** Detaching the stream builds a session without it, and pictures go on as before. */
    @Test
    public void detachingTheStreamLeavesThePictureSession() {
        openWithStream();

        module.detachSurface(stream);
        FakeCamera2.idleMainLooper();

        assertFalse(activeSession() instanceof StreamSession);
        assertTrue(activeSession() instanceof PictureSession);
        assertFalse(activeSession().getSurfaces().contains(stream.surface));
    }

    /** A detach that arrives after another stream took the place of the first leaves it be. */
    @Test
    public void aLateDetachLeavesTheStreamThatReplacedIt() {
        openWithStream();
        Stream second = new Stream();
        try {
            module.attachSurface(second);
            module.detachSurface(stream);
            FakeCamera2.idleMainLooper();

            assertTrue(activeSession() instanceof StreamSession);
            assertSame(second, streamSession().getSurfaceProvider());
        } finally {
            second.release();
        }
    }

    /**
     * A camera that will not configure a still beside the stream keeps the stream without one,
     * as streams ran before, and is not asked again; a picture then fails rather than the camera.
     */
    @Test
    public void aCameraThatRefusesAStillBesideAStreamKeepsTheStream() {
        // The preview and the stream, but not a still as well.
        ShadowRefusingCameraDevice.setMaxOutputs(2);
        openWithStream();

        assertTrue("the camera should still be open", isOpen());
        assertEquals(1, ShadowRefusingCameraDevice.getRefusals());
        assertTrue(carriesTheStream());
        assertFalse(streamSession().hasStillCapture());

        module.takePicture(output);
        FakeCamera2.idleMainLooper();
        assertTrue("a picture is refused", failed.get());

        module.close();
        FakeCamera2.idleMainLooper();
        module.open();
        FakeCamera2.idleMainLooper();
        assertEquals("a camera that refused is not asked again",
                1, ShadowRefusingCameraDevice.getRefusals());
        assertTrue(carriesTheStream());
    }

    /** A stream the camera will not carry at all is let go, and the camera goes on without it. */
    @Test
    public void aStreamTheCameraRefusesIsLetGo() {
        ShadowRefusingCameraDevice.setRefusedSurface(stream.surface);
        openWithStream();

        assertTrue("the camera should still be open", isOpen());
        assertEquals("refused with a still and without one",
                2, ShadowRefusingCameraDevice.getRefusals());
        assertFalse(activeSession() instanceof StreamSession);
        assertTrue(activeSession() instanceof PictureSession);
    }

    private void openWithStream() {
        module.open();
        FakeCamera2.idleMainLooper();
        module.attachSurface(stream);
        FakeCamera2.idleMainLooper();
        FakeCamera2.idleMainLooper();
    }

    /** Whether the active session is a stream session writing to the attached stream. */
    private boolean carriesTheStream() {
        Camera2Module.Session session = activeSession();
        return session instanceof StreamSession
                && session.getSurfaces().contains(stream.surface)
                && stream.asked.get() > 0;
    }

    @NonNull
    private StreamSession streamSession() {
        Camera2Module.Session session = activeSession();
        assertTrue("expected a stream session, found " + session,
                session instanceof StreamSession);
        return (StreamSession) session;
    }

    /** Whether the module still has the camera open, which a failed session takes away. */
    private boolean isOpen() {
        try {
            Field field = Camera2Module.class.getDeclaredField("mIsOpen");
            field.setAccessible(true);
            return (Boolean) field.get(module);
        } catch (ReflectiveOperationException error) {
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

    /** A stream that hands the camera one surface, however often it is asked. */
    private static final class Stream implements VideoRecorder.SurfaceProvider {
        /** What the stream's frames would be written into. */
        private final SurfaceTexture texture = new SurfaceTexture(0);
        /** The surface handed to the camera. */
        final Surface surface = new Surface(texture);
        /** How many times the camera asked for it. */
        final AtomicInteger asked = new AtomicInteger();

        @Override
        public Surface getSurface(int width, int height, int orientation, boolean flipped) {
            asked.incrementAndGet();
            return surface;
        }

        void release() {
            surface.release();
            texture.release();
        }
    }
}
