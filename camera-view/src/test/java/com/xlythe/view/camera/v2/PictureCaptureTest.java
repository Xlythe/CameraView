package com.xlythe.view.camera.v2;

import static com.xlythe.view.camera.FakeLocation.LATITUDE;
import static com.xlythe.view.camera.FakeLocation.LONGITUDE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.media.FakeImage;
import android.media.ImageReader;
import android.media.MediaActionSound;

import com.xlythe.view.camera.CameraSoundsTest;
import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.Exif;
import com.xlythe.view.camera.FakeLocation;
import com.xlythe.view.camera.shadow.ShadowAvailableTextureView;
import com.xlythe.view.camera.shadow.ShadowCallbackCameraCaptureSession;
import com.xlythe.view.camera.shadow.ShadowInjectableImageReader;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowCamera;
import org.robolectric.shadows.ShadowMediaActionSound;
import org.robolectric.shadows.ShadowPausedAsyncTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The Camera2 still-capture path, from the request through to the file and its callbacks.
 *
 * <p>Reachable only with a frame to hand the reader, which is what
 * {@link ShadowInjectableImageReader} provides.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, qualifiers = "w360dp-h800dp-xxxhdpi", shadows = {
        ShadowAvailableTextureView.class,
        ShadowCallbackCameraCaptureSession.class,
        ShadowInjectableImageReader.class})
public class PictureCaptureTest {
    /** Stands in for encoded JPEG bytes; nothing in the path parses them. */
    private static final byte[] JPEG_BYTES =
            "not-really-a-jpeg-but-exactly-these-bytes".getBytes(StandardCharsets.US_ASCII);

    /** The smallest JPEG that EXIF can be written into, for the tests that read it back. */
    private static final byte[] EMPTY_JPEG = {
            (byte) 0xFF, (byte) 0xD8, // SOI
            (byte) 0xFF, (byte) 0xD9, // EOI
    };

    private Context context;
    private FakeCamera2.TestCameraView cameraView;
    private Camera2Module module;
    private File output;
    private final AtomicReference<File> captured = new AtomicReference<>();
    private final AtomicReference<Boolean> failed = new AtomicReference<>(false);
    private final AtomicInteger shutters = new AtomicInteger();

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        FakeCamera2.addBackAndFrontCameras(context);
        cameraView = FakeCamera2.laidOutCameraView(context, 1080, 1920);
        CameraSoundsTest.resetSounds();
        cameraView.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
            @Override public void onShutter() { shutters.incrementAndGet(); }
            @Override public void onImageConfirmation() {}
            @Override public void onImageCaptured(File file) { captured.set(file); }
            @Override public void onFailure() { failed.set(true); }
        });
        // ImageSaver runs on AsyncTask.s thread pool. Run it inline so a capture completes
        // within the test rather than on a thread nothing waits for.
        ShadowPausedAsyncTask.overrideExecutor(Runnable::run);
        module = new Camera2Module(cameraView);
        output = new File(context.getCacheDir(), "capture-test.jpg");
    }

    @After
    public void tearDown() {
        module.close();
        cameraView.close();
        FakeCamera2.idleMainLooper();
        cameraView.release();
        ShadowCamera.clearCameraInfo();
        if (output.exists() && !output.delete()) {
            output.deleteOnExit();
        }
    }

    @Test
    public void aCapturedFrameIsWrittenAndReported() {
        module.open();
        FakeCamera2.idleMainLooper();

        module.takePicture(output);
        FakeCamera2.idleMainLooper();

        deliverFrame(FakeImage.jpeg(1920, 1080, JPEG_BYTES));

        assertEquals("expected the capture to be reported", output, captured.get());
        assertFalse("should not have reported a failure", failed.get());
        assertTrue("expected the frame to reach disk", output.isFile());
        assertEquals(JPEG_BYTES.length, output.length());
    }

    /**
     * A frame the library cannot decode must report failure, and must not leave a file behind
     * that a caller would take for a successful capture.
     */
    @Test
    public void aFrameThatCannotBeDecodedReportsFailure() {
        module.open();
        FakeCamera2.idleMainLooper();

        module.takePicture(output);
        FakeCamera2.idleMainLooper();

        deliverFrame(FakeImage.unsupportedFormat(1920, 1080));

        assertTrue("expected onFailure", failed.get());
        assertEquals(null, captured.get());
        assertFalse("a failed capture must not leave a file", output.exists());
    }

    /** The frame has to be closed however the save turns out, or the reader starves. */
    @Test
    public void theFrameIsAlwaysClosed() {
        module.open();
        FakeCamera2.idleMainLooper();
        module.takePicture(output);
        FakeCamera2.idleMainLooper();

        FakeImage image = FakeImage.jpeg(1920, 1080, JPEG_BYTES);
        deliverFrame(image);

        assertTrue("the Image must be closed", image.isClosed());
    }

    /** A frame arriving with no outstanding request is reported rather than written anywhere. */
    @Test
    public void anUnexpectedFrameReportsFailure() {
        module.open();
        FakeCamera2.idleMainLooper();

        ImageReader reader = ShadowInjectableImageReader.latestReader();
        assertNotNull("the picture session should have made a reader", reader);
        ShadowInjectableImageReader.offerImage(reader, FakeImage.jpeg(1920, 1080, JPEG_BYTES));
        FakeCamera2.idleMainLooper();

        assertTrue("expected onFailure for a frame nobody asked for", failed.get());
    }

    /** The click plays as the frame is exposed, and the listener hears of it before the save. */
    @Test
    public void theShutterClicksAsTheFrameIsExposed() {
        module.open();
        FakeCamera2.idleMainLooper();

        module.takePicture(output);
        FakeCamera2.idleMainLooper();

        assertEquals(1, ShadowMediaActionSound.getPlayCount(MediaActionSound.SHUTTER_CLICK));
        assertEquals(1, shutters.get());
        assertEquals("nothing has been saved yet", null, captured.get());
    }

    /** Silencing the click leaves the moment itself reported, for a sound of the app's own. */
    @Test
    public void aSilencedShutterStillReportsTheMoment() {
        module.setShutterSoundEnabled(false);
        module.open();
        FakeCamera2.idleMainLooper();

        module.takePicture(output);
        FakeCamera2.idleMainLooper();

        assertEquals(0, ShadowMediaActionSound.getPlayCount(MediaActionSound.SHUTTER_CLICK));
        assertEquals(1, shutters.get());
    }

    @Test
    public void aRequiredShutterClicksWhenSilenced() {
        CameraSoundsTest.requireSounds();
        module.setShutterSoundEnabled(false);
        module.open();
        FakeCamera2.idleMainLooper();

        module.takePicture(output);
        FakeCamera2.idleMainLooper();

        assertEquals(1, ShadowMediaActionSound.getPlayCount(MediaActionSound.SHUTTER_CLICK));
    }

    /**
     * A picture is tagged with the fix the camera has followed since it opened, which is still
     * there once the system has forgotten its own.
     */
    @Test
    public void aPictureIsTaggedWithWhereTheDeviceIs() throws IOException {
        FakeLocation.addProviders(context);
        FakeLocation.grantFine(context);
        cameraView.open();
        FakeCamera2.idleMainLooper();
        FakeLocation.deliver(context,
                FakeLocation.fix(LocationManager.GPS_PROVIDER, LATITUDE, LONGITUDE, 5));
        FakeLocation.setLastKnown(context, LocationManager.GPS_PROVIDER, null);

        cameraView.takePicture(output);
        FakeCamera2.idleMainLooper();
        deliverFrame(FakeImage.jpeg(1920, 1080, EMPTY_JPEG));

        Location tagged = new Exif(output).getLocation();
        assertNotNull("expected the picture to carry a location", tagged);
        assertEquals(LATITUDE, tagged.getLatitude(), 1e-4);
        assertEquals(LONGITUDE, tagged.getLongitude(), 1e-4);
        assertEquals("the capture asks for nothing of its own",
                2, FakeLocation.listeners(context));
    }

    /** With no fix to be had, a picture is saved untagged rather than waiting for one. */
    @Test
    public void aPictureNeverWaitsForAFix() throws IOException {
        FakeLocation.addProviders(context);
        FakeLocation.grantFine(context);
        module.open();
        FakeCamera2.idleMainLooper();

        module.takePicture(output);
        FakeCamera2.idleMainLooper();
        deliverFrame(FakeImage.jpeg(1920, 1080, EMPTY_JPEG));

        assertEquals(output, captured.get());
        assertEquals("nothing may be asked of the radio", 0, FakeLocation.listeners(context));
        assertNull(new Exif(output).getLocation());
    }

    /** Hands the reader a frame and runs the save through to its callbacks. */
    private void deliverFrame(FakeImage image) {
        ImageReader reader = ShadowInjectableImageReader.latestReader();
        assertNotNull("the picture session should have made a reader", reader);
        ShadowInjectableImageReader.offerImage(reader, image);
        FakeCamera2.idleMainLooper();
        // The save was handed to the inline executor installed in setUp, so it has already
        // run; this drains the result it posted back to the main thread.
        FakeCamera2.idleMainLooper();
    }
}
