package com.xlythe.view.camera.v2;

import static com.xlythe.view.camera.FakeLocation.LATITUDE;
import static com.xlythe.view.camera.FakeLocation.LONGITUDE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.hardware.camera2.CaptureRequest;
import android.location.Location;
import android.location.LocationManager;
import android.media.FakeImage;
import android.media.ImageReader;
import android.media.MediaActionSound;

import androidx.annotation.NonNull;

import com.xlythe.view.camera.Burst;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A Camera2 burst, from the requests it makes through to the files and what the burst hears.
 *
 * <p>The backend keeps a few still requests in flight and asks for another as each frame lands, so
 * what is checked is that bookkeeping: how many requests are out, which file each frame becomes,
 * and that every picture asked for is accounted for however the burst ends.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, qualifiers = "w360dp-h800dp-xxxhdpi", shadows = {
        ShadowAvailableTextureView.class,
        ShadowCallbackCameraCaptureSession.class,
        ShadowInjectableImageReader.class})
public class BurstCaptureTest {
    /** The smallest JPEG that Exif can be written into. */
    private static final byte[] EMPTY_JPEG = {
            (byte) 0xFF, (byte) 0xD8, // SOI
            (byte) 0xFF, (byte) 0xD9, // EOI
    };

    private Context context;
    private FakeCamera2.TestCameraView cameraView;
    private Camera2Module module;
    private File directory;
    private final Heard heard = new Heard();
    private int failures;
    /** How many times the picture listener heard a shutter. */
    private int shutters;

    /** Everything the burst reported. */
    private static final class Heard implements Burst.Callback {
        final List<File> pictures = new ArrayList<>();
        int finishes;

        @Override
        public void onPictureTaken(@NonNull File file) {
            pictures.add(file);
        }

        @Override
        public void onBurstFinished() {
            finishes++;
        }
    }

    @Before
    public void setUp() {
        ShadowCallbackCameraCaptureSession.reset();
        ShadowInjectableImageReader.reset();
        CameraSoundsTest.resetSounds();
        context = RuntimeEnvironment.getApplication();
        FakeCamera2.addBackAndFrontCameras(context);
        cameraView = FakeCamera2.laidOutCameraView(context, 1080, 1920);
        cameraView.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
            @Override public void onShutter() { shutters++; }
            @Override public void onImageConfirmation() {}
            @Override public void onFailure() { failures++; }
        });
        // Saves run inline, so a frame is written by the time the looper is idle.
        ShadowPausedAsyncTask.overrideExecutor(Runnable::run);
        module = new Camera2Module(cameraView);
        directory = new File(context.getCacheDir(), "camera2-burst");
        assertTrue(directory.isDirectory() || directory.mkdirs());
    }

    @After
    public void tearDown() {
        module.close();
        cameraView.close();
        FakeCamera2.idleMainLooper();
        cameraView.release();
        ShadowCamera.clearCameraInfo();
    }

    @Test
    public void eachFrameIsWrittenInOrderAndTheBurstFinishesAtItsCount() {
        Burst burst = openAndStart(3);

        deliverFrames(3);

        assertEquals(3, heard.pictures.size());
        for (int i = 0; i < 3; i++) {
            File picture = heard.pictures.get(i);
            assertTrue(picture.getName(),
                    picture.getName().endsWith(String.format(Locale.US, "_%03d.jpg", i + 1)));
            assertTrue("expected the frame on disk", picture.isFile());
        }
        assertTrue(burst.isFinished());
        assertEquals(1, heard.finishes);
        assertEquals("a burst is not a picture failure", 0, failures);
    }

    /** A few requests are out at once, and one more is asked for as each frame lands. */
    @Test
    public void anotherPictureIsAskedForAsEachFrameLands() {
        openAndStart(10);
        assertEquals(PictureSession.BURST_PICTURES_IN_FLIGHT, burstRequests().size());

        deliverFrames(1);

        assertEquals(PictureSession.BURST_PICTURES_IN_FLIGHT + 1, burstRequests().size());
    }

    @Test
    public void noMoreIsAskedForThanTheCount() {
        openAndStart(2);

        deliverFrames(2);

        assertEquals(2, burstRequests().size());
    }

    @Test
    public void aBurstNeverFiresTheFlash() {
        openAndStart(10);

        CaptureRequest request = burstRequests().get(0);
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AE_MODE_ON),
                request.get(CaptureRequest.CONTROL_AE_MODE));
        assertEquals(Integer.valueOf(CaptureRequest.FLASH_MODE_OFF),
                request.get(CaptureRequest.FLASH_MODE));
        assertEquals("compressed like any other picture",
                Byte.valueOf((byte) module.getPictureQuality()),
                request.get(CaptureRequest.JPEG_QUALITY));
    }

    /** Stopping asks for nothing more, and the pictures already asked for still land. */
    @Test
    public void stoppingKeepsThePicturesAlreadyAskedFor() {
        Burst burst = openAndStart(10);

        burst.end();
        deliverFrames(PictureSession.BURST_PICTURES_IN_FLIGHT);

        assertEquals(PictureSession.BURST_PICTURES_IN_FLIGHT, burstRequests().size());
        assertEquals(PictureSession.BURST_PICTURES_IN_FLIGHT, heard.pictures.size());
        assertTrue(burst.isFinished());
    }

    /** The camera closing takes the frames it owed with it, and the burst still finishes. */
    @Test
    public void closingTheCameraFinishesTheBurst() {
        Burst burst = openAndStart(10);
        deliverFrames(1);

        module.close();
        FakeCamera2.idleMainLooper();

        assertTrue(burst.isFinished());
        assertEquals(1, heard.pictures.size());
        assertEquals(1, heard.finishes);
    }

    @Test
    public void aFrameThatCannotBeSavedEndsTheBurst() {
        Burst burst = openAndStart(10);

        deliver(FakeImage.unsupportedFormat(1920, 1080));

        assertTrue(burst.isEnded());
        deliverFrames(PictureSession.BURST_PICTURES_IN_FLIGHT - 1);
        assertTrue("the rest of what was asked for still lands", burst.isFinished());
        assertEquals(PictureSession.BURST_PICTURES_IN_FLIGHT - 1, heard.pictures.size());
    }

    /** A frame the camera reports lost will never arrive, so the burst stops waiting for it. */
    @Test
    public void aLostFrameEndsTheBurst() {
        Burst burst = openAndStart(10);
        ShadowCallbackCameraCaptureSession.Capture first =
                ShadowCallbackCameraCaptureSession.getCapturesWithCallbacks().get(0);
        ImageReader reader = ShadowInjectableImageReader.latestReader();
        assertNotNull(reader);

        first.callback.onCaptureBufferLost(null, first.request, reader.getSurface(), 1);
        first.callback.onCaptureBufferLost(null, first.request, reader.getSurface(), 1);
        deliverFrames(PictureSession.BURST_PICTURES_IN_FLIGHT - 1);

        assertTrue(burst.isFinished());
        assertEquals(PictureSession.BURST_PICTURES_IN_FLIGHT - 1, heard.pictures.size());
    }

    /** A burst is silent: no click plays and no shutter is reported for any of its pictures. */
    @Test
    public void aBurstPlaysNoClickAndReportsNoShutter() {
        openAndStart(2);

        deliverFrames(2);

        assertEquals(0, ShadowMediaActionSound.getPlayCount(MediaActionSound.SHUTTER_CLICK));
        assertEquals("the picture listener hears of single pictures only", 0, shutters);
    }

    /** Where the click is required, every picture of a burst plays it, as each single one does. */
    @Test
    public void aRequiredClickPlaysForEveryPicture() {
        CameraSoundsTest.requireSounds();
        openAndStart(2);

        deliverFrames(2);

        assertEquals(2, ShadowMediaActionSound.getPlayCount(MediaActionSound.SHUTTER_CLICK));
        assertEquals(0, shutters);
    }

    @Test
    public void aPictureCannotBeTakenDuringABurst() {
        openAndStart(10);

        module.takePicture(new File(directory, "single.jpg"));
        FakeCamera2.idleMainLooper();

        assertEquals(1, failures);
    }

    @Test
    public void aBurstBeforeTheCameraIsReadyFinishesWithNothing() {
        Burst burst = new Burst(directory, "early", 5, heard);

        module.startBurst(burst);

        assertTrue(burst.isFinished());
        assertEquals(0, heard.pictures.size());
    }

    /** Every picture of a burst is tagged like any other, and reaches the app as a file. */
    @Test
    public void eachPictureIsTaggedWithWhereTheDeviceIs() throws IOException {
        FakeLocation.addProviders(context);
        FakeLocation.grantFine(context);
        cameraView.open();
        FakeCamera2.idleMainLooper();
        FakeLocation.deliver(context,
                FakeLocation.fix(LocationManager.GPS_PROVIDER, LATITUDE, LONGITUDE, 5));
        List<File> captured = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        cameraView.startBurst(directory, 2, new CameraView.OnBurstListener() {
            @Override public void onBurstImageCaptured(@NonNull File file) { captured.add(file); }
            @Override public void onBurstFinished(int count) { counts.add(count); }
        });
        FakeCamera2.idleMainLooper();

        deliverFrames(2);

        assertEquals(List.of(2), counts);
        assertEquals(2, captured.size());
        for (File picture : captured) {
            assertEquals(directory, picture.getParentFile());
            Location tagged = new Exif(picture).getLocation();
            assertNotNull("expected " + picture.getName() + " to carry a location", tagged);
            assertEquals(LATITUDE, tagged.getLatitude(), 1e-4);
            assertEquals(LONGITUDE, tagged.getLongitude(), 1e-4);
        }
    }

    private Burst openAndStart(int count) {
        module.open();
        FakeCamera2.idleMainLooper();
        Burst burst = new Burst(directory, "burst", count, heard);
        module.startBurst(burst);
        FakeCamera2.idleMainLooper();
        assertFalse("the camera should have taken the burst on", burst.isFinished());
        return burst;
    }

    /** The one-off requests the burst made, which carry the file each picture goes to. */
    private static List<CaptureRequest> burstRequests() {
        List<CaptureRequest> requests = new ArrayList<>();
        for (CaptureRequest request : ShadowCallbackCameraCaptureSession.getCaptures()) {
            if (request.getTag() instanceof File) {
                requests.add(request);
            }
        }
        return requests;
    }

    private void deliverFrames(int count) {
        for (int i = 0; i < count; i++) {
            deliver(FakeImage.jpeg(1920, 1080, EMPTY_JPEG));
        }
    }

    private void deliver(FakeImage image) {
        ImageReader reader = ShadowInjectableImageReader.latestReader();
        assertNotNull("the picture session should have made a reader", reader);
        ShadowInjectableImageReader.offerImage(reader, image);
        FakeCamera2.idleMainLooper();
        FakeCamera2.idleMainLooper();
        assertTrue("a burst frame goes back to the reader at once", image.isClosed());
    }
}
