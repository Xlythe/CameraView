package com.xlythe.view.camera.v2;

import static com.xlythe.view.camera.FakeLocation.LATITUDE;
import static com.xlythe.view.camera.FakeLocation.LONGITUDE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.hardware.camera2.CameraDevice;
import android.location.Location;
import android.location.LocationManager;
import android.media.FakeImage;
import android.media.ImageReader;
import android.os.Looper;

import com.xlythe.view.camera.CameraSoundsTest;
import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.Exif;
import com.xlythe.view.camera.FakeLocation;
import com.xlythe.view.camera.shadow.ShadowAvailableTextureView;
import com.xlythe.view.camera.shadow.ShadowCallbackCameraCaptureSession;
import com.xlythe.view.camera.shadow.ShadowGeotaggingMediaRecorder;
import com.xlythe.view.camera.shadow.ShadowInjectableImageReader;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowCamera;
import org.robolectric.shadows.ShadowPausedAsyncTask;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A Camera2 picture whose shutter has fired, when the camera closes before its frame arrives.
 *
 * <p>Closing the device discards a capture in flight, so the picture would be lost; and closing
 * the reader frees the buffer a saver may be copying out of. The camera is kept open until the
 * frame is in, then closed, and a picture whose shutter had not fired fails at once.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, qualifiers = "w360dp-h800dp-xxxhdpi", shadows = {
        ShadowAvailableTextureView.class,
        ShadowCallbackCameraCaptureSession.class,
        ShadowGeotaggingMediaRecorder.class,
        ShadowInjectableImageReader.class})
public class FinishPictureAfterCloseTest {
    /** The smallest JPEG that EXIF can be written into. */
    private static final byte[] EMPTY_JPEG = {
            (byte) 0xFF, (byte) 0xD8, // SOI
            (byte) 0xFF, (byte) 0xD9, // EOI
    };

    private Context context;
    private FakeCamera2.TestCameraView cameraView;
    private Camera2Module module;
    private File output;
    private final AtomicReference<File> captured = new AtomicReference<>();
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicInteger shutters = new AtomicInteger();
    private final AtomicInteger videos = new AtomicInteger();
    private final AtomicInteger videoFailures = new AtomicInteger();

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        // Robolectric runs no resetter for a shadow a test declares itself.
        ShadowCallbackCameraCaptureSession.reset();
        ShadowInjectableImageReader.reset();
        ShadowGeotaggingMediaRecorder.forgetLocation();
        CameraSoundsTest.resetSounds();
        FakeCamera2.addBackAndFrontCameras(context);
        CameraView.setDefaultBackend(CameraView.Backend.CAMERA2);
        cameraView = FakeCamera2.laidOutCameraView(context, 1080, 1920);
        cameraView.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
            @Override public void onShutter() { shutters.incrementAndGet(); }
            @Override public void onImageConfirmation() {}
            @Override public void onImageCaptured(File file) { captured.set(file); }
            @Override public void onFailure() { failures.incrementAndGet(); }
        });
        cameraView.setOnVideoCapturedListener(new CameraView.OnVideoCapturedListener() {
            @Override public void onVideoConfirmation() {}
            @Override public void onVideoCaptured(File file) { videos.incrementAndGet(); }
            @Override public void onFailure() { videoFailures.incrementAndGet(); }
        });
        ShadowPausedAsyncTask.overrideExecutor(Runnable::run);
        module = new Camera2Module(cameraView);
        output = new File(context.getCacheDir(), "finish-test.jpg");
    }

    @After
    public void tearDown() {
        module.close();
        cameraView.close();
        FakeCamera2.idleMainLooper();
        cameraView.release();
        CameraView.setDefaultBackend(CameraView.Backend.AUTO);
        ShadowCamera.clearCameraInfo();
        if (output.exists() && !output.delete()) {
            output.deleteOnExit();
        }
    }

    @Test
    public void aPictureClosedAfterItsShutterIsSavedBeforeTheCameraGoes() {
        openAndTakePicture();
        CameraDevice device = currentDevice();
        assertEquals("the shutter has fired", 1, shutters.get());

        module.close();
        FakeCamera2.idleMainLooper();

        assertEquals("nothing is reported before the frame arrives", 0, failures.get());
        assertFalse("the camera stays open for the frame", isClosed(device));

        deliverFrame(FakeImage.jpeg(1920, 1080, EMPTY_JPEG));

        assertEquals(output, captured.get());
        assertEquals(0, failures.get());
        assertTrue(output.isFile());
        assertTrue("and goes once it has it", isClosed(device));
    }

    /** A picture still waiting for its shutter is the one a close does not wait for. */
    @Test
    public void aPictureClosedBeforeItsShutterFailsAtOnce() {
        module.open();
        FakeCamera2.idleMainLooper();
        CameraDevice device = currentDevice();

        module.takePicture(output);
        module.close();

        assertEquals("the picture fails as the camera closes", 1, failures.get());
        assertTrue(isClosed(device));
        FakeCamera2.idleMainLooper();
        assertNull(captured.get());
        assertEquals("and only once", 1, failures.get());
    }

    /** A camera that never delivers the frame is given up on, and the picture fails. */
    @Test
    public void aClosedCameraGivesUpOnAFrameThatNeverComes() {
        openAndTakePicture();
        CameraDevice device = currentDevice();
        module.close();
        FakeCamera2.idleMainLooper();

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(
                Duration.ofMillis(Camera2Module.FINISH_PICTURE_TIMEOUT_MILLIS));

        assertEquals(1, failures.get());
        assertNull(captured.get());
        assertTrue(isClosed(device));
    }

    /** Opening again waits for the device that is finishing, which may be the one it opens. */
    @Test
    public void reopeningWaitsForThePictureToFinish() {
        openAndTakePicture();
        module.close();
        FakeCamera2.idleMainLooper();

        module.open();
        FakeCamera2.idleMainLooper();
        assertFalse("nothing opens over a camera still finishing a picture",
                module.isCameraReady());

        deliverFrame(FakeImage.jpeg(1920, 1080, EMPTY_JPEG));
        FakeCamera2.idleMainLooper();

        assertEquals(output, captured.get());
        assertTrue("and the camera opens once it has finished", module.isCameraReady());
    }

    /** A camera closed and not reopened stays closed once the picture is in. */
    @Test
    public void aCloseWhileFinishingCancelsAReopen() {
        openAndTakePicture();
        module.close();
        module.open();
        module.close();
        FakeCamera2.idleMainLooper();

        deliverFrame(FakeImage.jpeg(1920, 1080, EMPTY_JPEG));
        FakeCamera2.idleMainLooper();

        assertEquals(output, captured.get());
        assertFalse(module.isCameraReady());
    }

    /**
     * A toggle after the shutter switches lens once the picture is in, and the picture keeps the
     * rotation and mirroring of the lens that exposed it.
     */
    @Test
    public void aLensToggleAfterTheShutterKeepsThePicturesMirroring() throws IOException {
        module.setLensFacing(CameraView.LensFacing.FRONT);
        File reference = new File(context.getCacheDir(), "finish-reference.jpg");
        try {
            module.open();
            FakeCamera2.idleMainLooper();
            module.takePicture(reference);
            FakeCamera2.idleMainLooper();
            deliverFrame(FakeImage.jpeg(1920, 1080, EMPTY_JPEG));
            assertTrue("a front picture is mirrored", new Exif(reference).isFlippedHorizontally());

            module.takePicture(output);
            FakeCamera2.idleMainLooper();
            module.toggleCamera();
            FakeCamera2.idleMainLooper();
            assertFalse("the back lens waits for the picture", module.isCameraReady());

            deliverFrame(FakeImage.jpeg(1920, 1080, EMPTY_JPEG));
            FakeCamera2.idleMainLooper();

            assertEquals(output, captured.get());
            assertEquals(signature(reference), signature(output));
            assertFalse("the back lens follows", module.isUsingFrontFacingCamera());
            assertTrue(module.isCameraReady());
        } finally {
            reference.delete();
        }
    }

    /** The picture is tagged where it was taken, though the camera stopped following location. */
    @Test
    public void aPictureClosedAfterItsShutterKeepsItsLocation() throws IOException {
        FakeLocation.addProviders(context);
        FakeLocation.grantFine(context);
        cameraView.open();
        FakeCamera2.idleMainLooper();
        FakeLocation.deliver(context,
                FakeLocation.fix(LocationManager.GPS_PROVIDER, LATITUDE, LONGITUDE, 5));
        FakeLocation.setLastKnown(context, LocationManager.GPS_PROVIDER, null);
        cameraView.takePicture(output);
        FakeCamera2.idleMainLooper();

        cameraView.close();
        FakeCamera2.idleMainLooper();
        deliverFrame(FakeImage.jpeg(1920, 1080, EMPTY_JPEG));

        assertEquals(output, captured.get());
        Location tagged = new Exif(output).getLocation();
        assertNotNull("expected the picture to carry a location", tagged);
        assertEquals(LATITUDE, tagged.getLatitude(), 1e-4);
        assertEquals(LONGITUDE, tagged.getLongitude(), 1e-4);
    }

    /** A frame still being written outlives its reader's session, and is saved. */
    @Test
    public void aFrameBeingWrittenOutlivesItsSession() {
        Queue<Runnable> saves = new ArrayDeque<>();
        ShadowPausedAsyncTask.overrideExecutor(saves::add);
        int readersBefore = ShadowInjectableImageReader.openReaderCount();
        openAndTakePicture();
        deliverFrame(FakeImage.jpeg(1920, 1080, EMPTY_JPEG));
        assertEquals("the save is waiting", 1, saves.size());

        module.close();
        FakeCamera2.idleMainLooper();
        assertEquals("the reader outlives the session for the frame being written",
                readersBefore + 1, ShadowInjectableImageReader.openReaderCount());

        saves.remove().run();
        FakeCamera2.idleMainLooper();

        assertEquals(output, captured.get());
        assertTrue(output.isFile());
        assertEquals("and goes once the frame is written",
                readersBefore, ShadowInjectableImageReader.openReaderCount());
    }

    /**
     * A snapshot taken while recording keeps the camera open, but not the recording, which is
     * finished as the camera closes and reported once.
     */
    @Test
    public void aSnapshotKeepsTheCameraButNotTheRecording() throws IOException {
        File video = File.createTempFile("finish", ".mp4");
        video.deleteOnExit();
        module.open();
        FakeCamera2.idleMainLooper();
        module.startRecording(video);
        FakeCamera2.idleMainLooper();
        assertTrue(module.isRecording());
        CameraDevice device = currentDevice();

        module.takePicture(output);
        FakeCamera2.idleMainLooper();
        module.close();
        FakeCamera2.idleMainLooper();

        assertEquals("the recording is finished with the camera", 1, videos.get());
        assertFalse("while the camera waits for the snapshot", isClosed(device));

        deliverFrame(FakeImage.jpeg(1280, 720, EMPTY_JPEG));
        FakeCamera2.idleMainLooper();

        assertEquals(output, captured.get());
        assertEquals("the recording is reported once", 1, videos.get());
        assertEquals(0, videoFailures.get());
        assertTrue(isClosed(device));
    }

    /**
     * A capture the camera reports lost, with no frame pushed, ends the wait at once. Closed from
     * inside onShutter, as an app may, which is before the camera can report anything else.
     */
    @Test
    public void aCaptureTheCameraLosesEndsTheWait() {
        module.open();
        FakeCamera2.idleMainLooper();
        CameraDevice device = currentDevice();
        cameraView.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
            @Override public void onShutter() {
                shutters.incrementAndGet();
                module.close();
            }
            @Override public void onImageConfirmation() {}
            @Override public void onImageCaptured(File file) { captured.set(file); }
            @Override public void onFailure() { failures.incrementAndGet(); }
        });
        ShadowCallbackCameraCaptureSession.failNextCapture();

        module.takePicture(output);
        FakeCamera2.idleMainLooper();

        assertEquals(1, shutters.get());
        assertEquals(1, failures.get());
        assertNull(captured.get());
        assertTrue(isClosed(device));
    }

    private void openAndTakePicture() {
        module.open();
        FakeCamera2.idleMainLooper();
        module.takePicture(output);
        FakeCamera2.idleMainLooper();
    }

    private CameraDevice currentDevice() {
        CameraDevice device = ReflectionHelpers.getField(module, "mCameraDevice");
        assertNotNull("the camera should be open", device);
        return device;
    }

    private static boolean isClosed(CameraDevice device) {
        return ReflectionHelpers.getField(Shadow.extract(device), "closed");
    }

    /** The parts of a picture's Exif that say how it was taken. */
    private static String signature(File file) throws IOException {
        Exif exif = new Exif(file);
        return "rotation=" + exif.getRotation() + " mirrored=" + exif.isFlippedHorizontally();
    }

    /** Hands the newest reader a frame and runs the save through to its callbacks. */
    private static void deliverFrame(FakeImage image) {
        ImageReader reader = ShadowInjectableImageReader.latestReader();
        assertNotNull("the picture session should have made a reader", reader);
        ShadowInjectableImageReader.offerImage(reader, image);
        FakeCamera2.idleMainLooper();
        FakeCamera2.idleMainLooper();
    }
}
