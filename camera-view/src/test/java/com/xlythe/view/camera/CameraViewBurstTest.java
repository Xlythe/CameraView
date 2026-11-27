package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.hardware.camera2.CaptureRequest;
import android.media.FakeImage;
import android.media.ImageReader;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.annotation.NonNull;

import com.xlythe.view.camera.shadow.ShadowAvailableTextureView;
import com.xlythe.view.camera.shadow.ShadowCallbackCameraCaptureSession;
import com.xlythe.view.camera.shadow.ShadowInjectableImageReader;
import com.xlythe.view.camera.v2.FakeCamera2;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPausedAsyncTask;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * What CameraView does with a burst: the cases it refuses before asking a backend, and how it
 * hands the pictures over, as files or filed in the gallery.
 *
 * <p>The pictures come from the Camera2 backend against Robolectric's camera shadows, with
 * {@link ShadowInjectableImageReader} standing in for the frames a sensor would produce.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, qualifiers = "w360dp-h800dp-xxxhdpi", shadows = {
        ShadowAvailableTextureView.class,
        ShadowCallbackCameraCaptureSession.class,
        ShadowInjectableImageReader.class})
public class CameraViewBurstTest {
    /** The smallest JPEG that Exif can be written into. */
    private static final byte[] EMPTY_JPEG = {
            (byte) 0xFF, (byte) 0xD8, // SOI
            (byte) 0xFF, (byte) 0xD9, // EOI
    };

    /** How many frames the backend has asked for before any arrive. */
    private static final int IN_FLIGHT = 3;

    private Context context;
    private FakeCamera2.TestCameraView cameraView;
    private File directory;
    private final Heard heard = new Heard();
    private int pictureFailures;
    private int videoFailures;

    /** Everything a burst listener heard. */
    private static final class Heard implements CameraView.OnBurstListener {
        final List<File> captured = new ArrayList<>();
        final List<Uri> saved = new ArrayList<>();
        final List<Integer> finishes = new ArrayList<>();

        @Override
        public void onBurstImageCaptured(@NonNull File file) {
            captured.add(file);
        }

        @Override
        public void onBurstImageSaved(@NonNull Uri uri) {
            saved.add(uri);
        }

        @Override
        public void onBurstFinished(int count) {
            finishes.add(count);
        }
    }

    @Before
    public void setUp() {
        ShadowCallbackCameraCaptureSession.reset();
        ShadowInjectableImageReader.reset();
        context = RuntimeEnvironment.getApplication();
        // Cameras that write RAW, so that a RAW format can be set for a burst to refuse.
        FakeCamera2.addBackAndFrontCameras(context, FakeCamera2.RawSupport.FULL);
        ShadowPausedAsyncTask.overrideExecutor(Runnable::run);
        cameraView = FakeCamera2.laidOutCameraView(context, 1080, 1920);
        cameraView.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
            @Override public void onImageConfirmation() {}
            @Override public void onFailure() { pictureFailures++; }
        });
        cameraView.setOnVideoCapturedListener(new CameraView.OnVideoCapturedListener() {
            @Override public void onVideoConfirmation() {}
            @Override public void onFailure() { videoFailures++; }
        });
        directory = new File(context.getCacheDir(), "burst");
    }

    @After
    public void tearDown() {
        cameraView.close();
        idle();
        cameraView.release();
    }

    /** A button pressed before the camera opens has to hear that nothing was taken. */
    @Test
    public void aBurstOnAClosedCameraFinishesWithNothing() {
        cameraView.startBurst(directory, 5, heard);

        assertEquals(List.of(0), heard.finishes);
        assertEquals(0, heard.captured.size());
    }

    @Test
    public void aBurstTakesAtLeastOnePicture() {
        try {
            cameraView.startBurst(directory, 0, heard);
            fail("expected a count of zero to be refused");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    /** Releasing a burst button with no burst running is ordinary, not an error. */
    @Test
    public void stoppingWithNoBurstDoesNothing() {
        cameraView.stopBurst();
        assertEquals(0, heard.finishes.size());
    }

    /**
     * A burst writes JPEGs, so a RAW format refuses it rather than writing JPEGs where DNGs were
     * asked for: it finishes at once with nothing, having asked the camera for nothing.
     */
    @Test
    public void aRawFormatRefusesABurst() {
        cameraView.setPictureFormat(CameraView.PictureFormat.RAW);
        open();
        assertEquals(CameraView.PictureFormat.RAW, cameraView.getPictureFormat());

        cameraView.startBurst(directory, 3, heard);
        cameraView.setPictureFormat(CameraView.PictureFormat.RAW_JPEG);
        idle();
        cameraView.startBurst(directory, 3, heard);
        idle();

        assertEquals(List.of(0, 0), heard.finishes);
        for (CaptureRequest request : ShadowCallbackCameraCaptureSession.getCaptures()) {
            assertFalse("no burst picture should be asked for", request.getTag() instanceof File);
        }
    }

    @Test
    public void aDirectoryBurstReportsEachFileThenTheCount() {
        open();
        cameraView.startBurst(directory, 2, heard);
        idle();

        deliverFrames(2);

        assertEquals(List.of(2), heard.finishes);
        assertEquals(2, heard.captured.size());
        for (File picture : heard.captured) {
            assertEquals("written where the caller asked", directory, picture.getParentFile());
            assertTrue(picture.isFile());
        }
        assertEquals("nothing goes to the gallery", 0, heard.saved.size());
    }

    @Test
    public void aGalleryBurstFilesEachPictureThenReportsTheCount() throws Exception {
        open();
        cameraView.startBurst(2, heard);
        idle();

        deliverFrames(2);
        awaitFinish();

        assertEquals(List.of(2), heard.finishes);
        assertEquals(2, heard.saved.size());
        for (Uri uri : heard.saved) {
            assertTrue(uri.toString(), uri.toString().startsWith(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI.toString()));
        }
        assertEquals("a filed picture is not also reported as a file", 0, heard.captured.size());
        String[] left = CameraView.captureDirectory(context).list();
        assertTrue("the cache copies go once they are filed", left == null || left.length == 0);
    }

    /** A gallery that refuses one picture refuses the rest, so the burst ends at the first. */
    @Test
    public void aGalleryThatRefusesAPictureEndsTheBurst() throws Exception {
        Robolectric.buildContentProvider(CameraViewFilingTest.RefusingMediaProvider.class)
                .create(MediaStore.AUTHORITY);
        open();
        cameraView.startBurst(10, heard);
        idle();

        deliverFrames(1);
        awaitFilingOfOne();
        deliverFrames(IN_FLIGHT);
        awaitFinish();

        assertEquals(List.of(0), heard.finishes);
        assertEquals(0, heard.saved.size());
    }

    @Test
    public void stoppingKeepsThePicturesAlreadyBeingTaken() {
        open();
        cameraView.startBurst(directory, 10, heard);
        idle();

        cameraView.stopBurst();
        deliverFrames(IN_FLIGHT);

        assertEquals(List.of(IN_FLIGHT), heard.finishes);
    }

    @Test
    public void closingTheCameraEndsTheBurst() {
        open();
        cameraView.startBurst(directory, 10, heard);
        idle();
        deliverFrames(1);

        cameraView.close();
        idle();

        assertEquals(List.of(1), heard.finishes);
    }

    /** A picture or a recording asked for mid-burst is refused, and the burst carries on. */
    @Test
    public void nothingElseCanBeTakenDuringABurst() {
        open();
        cameraView.startBurst(directory, 10, heard);
        idle();

        cameraView.takePicture(new File(directory, "single.jpg"));
        cameraView.startRecording(new File(directory, "single.mp4"));
        Heard second = new Heard();
        cameraView.startBurst(directory, 3, second);
        idle();

        assertEquals(1, pictureFailures);
        assertEquals(1, videoFailures);
        assertEquals("only one burst runs at a time", List.of(0), second.finishes);
        assertFalse("and the first is still going", cameraView.isRecording());
        assertEquals(0, heard.finishes.size());
    }

    /** A picture awaiting confirmation holds the preview, so a burst waits for it. */
    @Test
    public void aBurstWaitsForAConfirmation() throws Exception {
        open();
        cameraView.setImageConfirmationEnabled(true);
        File pending = new File(context.getCacheDir(), "pending.jpg");
        cameraView.takePicture(pending);
        idle();
        // Not a JPEG, so the confirmation preview finds nothing to decode rather than failing to.
        ShadowInjectableImageReader.offerImage(ShadowInjectableImageReader.latestReader(),
                FakeImage.jpeg(1920, 1080, new byte[] {1, 2, 3, 4, 5, 6, 7, 8}));
        idle();
        idle();
        assertTrue(cameraView.isImageConfirmationPending());

        cameraView.startBurst(directory, 3, heard);

        assertEquals(List.of(0), heard.finishes);
    }

    private void open() {
        cameraView.open();
        idle();
        assertTrue("the camera should be open", cameraView.isOpen());
    }

    private void deliverFrames(int count) {
        for (int i = 0; i < count; i++) {
            ImageReader reader = ShadowInjectableImageReader.latestReader();
            assertNotNull("the picture session should have made a reader", reader);
            ShadowInjectableImageReader.offerImage(reader, FakeImage.jpeg(1920, 1080, EMPTY_JPEG));
            idle();
            idle();
        }
    }

    /** Runs the main looper until filing, which happens on a thread of its own, has finished. */
    private void awaitFinish() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (heard.finishes.isEmpty() && System.currentTimeMillis() < deadline) {
            idle();
            Thread.sleep(10);
        }
        assertFalse("the burst never finished", heard.finishes.isEmpty());
    }

    /** Gives the first picture long enough to have been filed, or refused. */
    private static void awaitFilingOfOne() throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            idle();
            Thread.sleep(10);
        }
    }

    private static void idle() {
        ShadowLooper.idleMainLooper();
    }
}
