package com.xlythe.view.camera.legacy;

import static com.xlythe.view.camera.FakeLocation.LATITUDE;
import static com.xlythe.view.camera.FakeLocation.LONGITUDE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.location.Location;
import android.location.LocationManager;

import androidx.annotation.NonNull;

import com.xlythe.view.camera.Burst;
import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.Exif;
import com.xlythe.view.camera.FakeLocation;
import com.xlythe.view.camera.shadow.ShadowAvailableTextureView;
import com.xlythe.view.camera.shadow.ShadowJpegCamera;

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
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPausedAsyncTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * A burst on the old camera API, which takes one picture at a time and has to restart its preview
 * before each next one.
 *
 * <p>{@link ShadowJpegCamera} hands each picture back on a later turn of the looper, as a driver
 * does, so the burst runs here the way it does on a phone: picture, preview, next picture.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, shadows = {ShadowAvailableTextureView.class, ShadowJpegCamera.class})
@SuppressWarnings("deprecation")
public class LegacyBurstTest {
    private Context context;
    private TestCameraView cameraView;
    private LegacyCameraModule module;
    private File directory;
    private final Heard heard = new Heard();
    private int failures;

    /** Supplies the SurfaceTexture a TextureView never produces without a hardware layer. */
    private static class TestCameraView extends CameraView {
        private final SurfaceTexture mSurfaceTexture = new SurfaceTexture(0);

        TestCameraView(Context context) {
            super(context);
        }

        @Override
        public SurfaceTexture getSurfaceTexture() {
            return mSurfaceTexture;
        }

        void release() {
            mSurfaceTexture.release();
        }
    }

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
        ShadowJpegCamera.reset();
        context = RuntimeEnvironment.getApplication();
        Shadows.shadowOf((android.app.Application) context).grantPermissions(
                Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO);
        addCamera(0, Camera.CameraInfo.CAMERA_FACING_BACK, 90);
        addCamera(1, Camera.CameraInfo.CAMERA_FACING_FRONT, 270);
        ShadowPausedAsyncTask.overrideExecutor(Runnable::run);
        CameraView.setDefaultBackend(CameraView.Backend.LEGACY);
        cameraView = new TestCameraView(context);
        cameraView.layout(0, 0, 1080, 1920);
        cameraView.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
            @Override public void onImageConfirmation() {}
            @Override public void onFailure() { failures++; }
        });
        module = new LegacyCameraModule(cameraView);
        directory = new File(context.getCacheDir(), "legacy-burst");
        assertTrue(directory.isDirectory() || directory.mkdirs());
    }

    @After
    public void tearDown() {
        module.close();
        cameraView.close();
        idle();
        cameraView.release();
        CameraView.setDefaultBackend(CameraView.Backend.AUTO);
        ShadowCamera.clearCameraInfo();
    }

    @Test
    public void eachPictureIsTakenAfterTheLastAndWrittenInOrder() {
        module.open();
        Burst burst = new Burst(directory, "order", 3, heard);

        module.startBurst(burst);
        runUntilFinished(burst);

        assertEquals(3, ShadowJpegCamera.getPicturesTaken());
        assertEquals(3, heard.pictures.size());
        for (int i = 0; i < 3; i++) {
            File picture = heard.pictures.get(i);
            assertTrue(picture.getName(),
                    picture.getName().endsWith(String.format(Locale.US, "_%03d.jpg", i + 1)));
            assertTrue(picture.isFile());
        }
        assertEquals(1, heard.finishes);
        assertTrue("the preview is running again afterwards", shadow().isPreviewing());
    }

    /** One picture at a time: the next is asked for only once the camera has the last. */
    @Test
    public void theNextPictureWaitsForTheLast() {
        module.open();
        ShadowJpegCamera.holdPictures();

        module.startBurst(new Burst(directory, "serial", 5, heard));
        idle();

        assertEquals(1, ShadowJpegCamera.getPicturesTaken());
    }

    /**
     * The next picture waits for the restarted preview to show a frame. A camera asked for one
     * before its preview is running again can fail and stay broken until it is reopened.
     */
    @Test
    public void theNextPictureWaitsForThePreviewToShowAFrame() {
        module.open();
        Burst burst = new Burst(directory, "frame", 3, heard);

        module.startBurst(burst);
        idle();
        assertEquals("the first is taken at once", 1, ShadowJpegCamera.getPicturesTaken());
        assertTrue("and the preview restarted after it", shadow().isPreviewing());
        idle();
        assertEquals("the second waits for a frame", 1, ShadowJpegCamera.getPicturesTaken());

        module.onPreviewFrameShown();
        idle();
        assertEquals(2, ShadowJpegCamera.getPicturesTaken());
        module.onPreviewFrameShown();
        idle();
        assertEquals(3, ShadowJpegCamera.getPicturesTaken());
        module.onPreviewFrameShown();
        idle();

        assertTrue(burst.isFinished());
        assertEquals(3, heard.pictures.size());
        assertEquals("a frame with nothing waiting on it asks for nothing",
                3, ShadowJpegCamera.getPicturesTaken());
    }

    /** A view that draws no frames holds the burst up only briefly. */
    @Test
    public void aPreviewThatShowsNoFrameHoldsTheNextPictureOnlySoLong() {
        module.open();
        module.startBurst(new Burst(directory, "noframe", 2, heard));
        idle();
        assertEquals(1, ShadowJpegCamera.getPicturesTaken());

        ShadowLooper.idleMainLooper(LegacyCameraModule.PREVIEW_FRAME_TIMEOUT_MILLIS - 1,
                TimeUnit.MILLISECONDS);
        assertEquals(1, ShadowJpegCamera.getPicturesTaken());
        ShadowLooper.idleMainLooper(1, TimeUnit.MILLISECONDS);

        assertEquals(2, ShadowJpegCamera.getPicturesTaken());
    }

    /** Closing while the next picture waits for a frame asks for nothing more. */
    @Test
    public void closingWhileWaitingForAFrameTakesNothingMore() {
        module.open();
        Burst burst = new Burst(directory, "closewait", 5, heard);
        module.startBurst(burst);
        idle();
        assertEquals(1, ShadowJpegCamera.getPicturesTaken());

        module.close();
        module.onPreviewFrameShown();
        ShadowLooper.idleMainLooper(LegacyCameraModule.PREVIEW_FRAME_TIMEOUT_MILLIS,
                TimeUnit.MILLISECONDS);

        assertEquals(1, ShadowJpegCamera.getPicturesTaken());
        assertTrue(burst.isFinished());
        assertEquals(1, heard.pictures.size());
    }

    @Test
    public void theShutterIsSilentForTheBurstAndComesBackAfter() {
        module.open();
        ShadowJpegCamera.holdPictures();
        Burst burst = new Burst(directory, "quiet", 2, heard);

        module.startBurst(burst);
        assertFalse("a burst must not click for every picture", shadow().isShutterSoundEnabled());

        ShadowJpegCamera.releasePictures();
        runUntilFinished(burst);
        assertTrue(shadow().isShutterSoundEnabled());
    }

    /** Afterwards the click is as the app set it, not simply back on. */
    @Test
    public void theShutterComesBackAsTheAppSetIt() {
        module.setShutterSoundEnabled(false);
        module.open();
        Burst burst = new Burst(directory, "muted", 2, heard);

        module.startBurst(burst);
        runUntilFinished(burst);

        assertFalse(shadow().isShutterSoundEnabled());
    }

    /** A click turned on during a burst waits for the burst to finish. */
    @Test
    public void aShutterTurnedOnDuringABurstWaitsForTheEnd() {
        module.setShutterSoundEnabled(false);
        module.open();
        ShadowJpegCamera.holdPictures();
        Burst burst = new Burst(directory, "later", 2, heard);

        module.startBurst(burst);
        module.setShutterSoundEnabled(true);
        assertFalse("still silent while the burst runs", shadow().isShutterSoundEnabled());

        ShadowJpegCamera.releasePictures();
        runUntilFinished(burst);
        assertTrue(shadow().isShutterSoundEnabled());
    }

    /** No picture of a burst reports its shutter, so no sound of the app's own plays for one. */
    @Test
    public void noPictureOfABurstReportsItsShutter() {
        int[] shutters = {0};
        cameraView.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
            @Override public void onShutter() { shutters[0]++; }
            @Override public void onImageConfirmation() {}
            @Override public void onFailure() { failures++; }
        });
        module.open();
        Burst burst = new Burst(directory, "unheard", 3, heard);

        module.startBurst(burst);
        runUntilFinished(burst);

        assertEquals(3, heard.pictures.size());
        assertEquals(0, shutters[0]);
    }

    @Test
    public void aBurstNeverFiresTheFlash() {
        module.open();
        Camera camera = ShadowJpegCamera.getLastCamera();
        Camera.Parameters parameters = camera.getParameters();
        parameters.set("flash-mode-values", "off,auto,on,torch");
        parameters.setFlashMode(Camera.Parameters.FLASH_MODE_AUTO);
        camera.setParameters(parameters);
        ShadowJpegCamera.holdPictures();
        Burst burst = new Burst(directory, "dark", 2, heard);

        module.startBurst(burst);
        assertEquals(Camera.Parameters.FLASH_MODE_OFF, camera.getParameters().getFlashMode());

        ShadowJpegCamera.releasePictures();
        runUntilFinished(burst);
        assertEquals("and the flash comes back afterwards",
                Camera.Parameters.FLASH_MODE_AUTO, camera.getParameters().getFlashMode());
    }

    /** A torch is not a flash that fires for a picture, so a burst leaves it lit. */
    @Test
    public void aBurstLeavesATorchAlone() {
        module.open();
        Camera camera = ShadowJpegCamera.getLastCamera();
        Camera.Parameters parameters = camera.getParameters();
        parameters.set("flash-mode-values", "off,auto,on,torch");
        parameters.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
        camera.setParameters(parameters);
        ShadowJpegCamera.holdPictures();

        module.startBurst(new Burst(directory, "torch", 2, heard));

        assertEquals(Camera.Parameters.FLASH_MODE_TORCH, camera.getParameters().getFlashMode());
    }

    @Test
    public void stoppingLetsThePictureInFlightLand() {
        module.open();
        ShadowJpegCamera.holdPictures();
        Burst burst = new Burst(directory, "stop", 10, heard);
        module.startBurst(burst);

        burst.end();
        ShadowJpegCamera.releasePictures();
        runUntilFinished(burst);

        assertEquals(1, ShadowJpegCamera.getPicturesTaken());
        assertEquals(1, heard.pictures.size());
    }

    /** A camera released with a picture outstanding never delivers it, so the burst stops. */
    @Test
    public void closingWithAPictureOutstandingFinishesTheBurst() {
        module.open();
        ShadowJpegCamera.holdPictures();
        Burst burst = new Burst(directory, "close", 10, heard);
        module.startBurst(burst);

        module.close();

        assertTrue(burst.isFinished());
        assertEquals(0, heard.pictures.size());
        assertEquals(1, heard.finishes);
    }

    @Test
    public void aPictureCannotBeTakenDuringABurst() throws IOException {
        module.open();
        ShadowJpegCamera.holdPictures();
        module.startBurst(new Burst(directory, "busy", 10, heard));

        module.takePicture(File.createTempFile("legacy", ".jpg"));

        assertEquals(1, failures);
        assertEquals(1, ShadowJpegCamera.getPicturesTaken());
    }

    @Test
    public void aBurstBeforeTheCameraOpensFinishesWithNothing() {
        Burst burst = new Burst(directory, "closed", 3, heard);

        module.startBurst(burst);

        assertTrue(burst.isFinished());
        assertEquals(0, ShadowJpegCamera.getPicturesTaken());
    }

    /** Every picture of a burst is tagged like any other, and reaches the app as a file. */
    @Test
    public void eachPictureIsTaggedWithWhereTheDeviceIs() throws IOException {
        FakeLocation.addProviders(context);
        FakeLocation.grantFine(context);
        cameraView.open();
        idle();
        FakeLocation.deliver(context,
                FakeLocation.fix(LocationManager.GPS_PROVIDER, LATITUDE, LONGITUDE, 5));
        List<File> captured = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();

        cameraView.startBurst(directory, 2, new CameraView.OnBurstListener() {
            @Override public void onBurstImageCaptured(@NonNull File file) { captured.add(file); }
            @Override public void onBurstFinished(int count) { counts.add(count); }
        });
        for (int i = 0; i < 20 && counts.isEmpty(); i++) {
            idle();
            ShadowLooper.idleMainLooper(LegacyCameraModule.PREVIEW_FRAME_TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS);
        }

        assertEquals(List.of(2), counts);
        for (File picture : captured) {
            Location tagged = new Exif(picture).getLocation();
            assertNotNull("expected " + picture.getName() + " to carry a location", tagged);
            assertEquals(LATITUDE, tagged.getLatitude(), 1e-4);
            assertEquals(LONGITUDE, tagged.getLongitude(), 1e-4);
        }
    }

    private static ShadowCamera shadow() {
        return Shadow.extract(ShadowJpegCamera.getLastCamera());
    }

    /**
     * Each picture takes a few turns of the looper: delivery, save, and the next request, which
     * waits for the preview to show a frame. The view here draws none, so the wait runs out.
     */
    private static void runUntilFinished(Burst burst) {
        for (int i = 0; i < 50 && !burst.isFinished(); i++) {
            idle();
            ShadowLooper.idleMainLooper(LegacyCameraModule.PREVIEW_FRAME_TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS);
        }
        assertTrue("the burst should have finished", burst.isFinished());
    }

    private static void idle() {
        ShadowLooper.shadowMainLooper().idle();
    }

    private static void addCamera(int id, int facing, int orientation) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        info.facing = facing;
        info.orientation = orientation;
        info.canDisableShutterSound = true;
        ShadowCamera.addCameraInfo(id, info);
    }
}
