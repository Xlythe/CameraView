package com.xlythe.view.camera.legacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.xlythe.view.camera.Burst;
import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.ExifCaptureParameters;
import com.xlythe.view.camera.shadow.ShadowAvailableTextureView;
import com.xlythe.view.camera.shadow.ShadowFrameRateRefusingCamera;
import com.xlythe.view.camera.shadow.ShadowRefusingCamera;
import com.xlythe.view.camera.shadow.ShadowSlowPictureCamera;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowCamera;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPausedAsyncTask;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The old camera API keeps up with the other backends: it lights the flash and the torch, makes
 * a picture smaller below MAX, and tells the app of a failed picture on the main thread.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, shadows = {ShadowAvailableTextureView.class})
public class LegacyParityTest {
    /** The sizes the fake back camera takes pictures at, largest first, as a phone lists them. */
    private static final String PICTURE_SIZES = "4000x3000,1920x1080,1600x1200,1280x720,640x480";

    private TestCameraView cameraView;
    private LegacyCameraModule module;

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

    @Before
    public void setUp() {
        ShadowSlowPictureCamera.reset();
        Context context = RuntimeEnvironment.getApplication();
        Shadows.shadowOf((android.app.Application) context).grantPermissions(
                Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO);
        Camera.CameraInfo info = new Camera.CameraInfo();
        info.facing = Camera.CameraInfo.CAMERA_FACING_BACK;
        info.orientation = 90;
        info.canDisableShutterSound = true;
        ShadowCamera.addCameraInfo(0, info);
        cameraView = new TestCameraView(context);
        cameraView.layout(0, 0, 1080, 1920);
        module = new LegacyCameraModule(cameraView);
    }

    @After
    public void tearDown() {
        module.close();
        idle();
        cameraView.release();
        ShadowCamera.clearCameraInfo();
    }

    /** The flash strategy reaches the camera's parameters, on open and when it changes. */
    @Test
    public void theFlashStrategyReachesTheCamera() {
        givenTheCameraOffers(Camera.Parameters.FLASH_MODE_OFF, Camera.Parameters.FLASH_MODE_AUTO,
                Camera.Parameters.FLASH_MODE_ON, Camera.Parameters.FLASH_MODE_TORCH);
        module.setFlash(CameraView.Flash.ON);
        module.open();
        idle();

        assertTrue(module.hasFlash());
        assertEquals("set before opening", Camera.Parameters.FLASH_MODE_ON, flashMode());
        module.setFlash(CameraView.Flash.AUTO);
        assertEquals(Camera.Parameters.FLASH_MODE_AUTO, flashMode());
        module.setFlash(CameraView.Flash.OFF);
        assertEquals(Camera.Parameters.FLASH_MODE_OFF, flashMode());
    }

    /**
     * The preview runs no slower than 15 fps where the camera allows it, rather than at the 5 fps
     * the camera service's widest range lets a dim scene fall to. These are the API 37 emulator's
     * ranges, where the default made a flash picture take three times as long as on Camera2.
     */
    @Test
    public void thePreviewKeepsItsFrameRateUp() {
        givenTheCameraOffersFrameRates("(15000,15000),(5000,30000),(15000,30000),(30000,30000)",
                "5000,30000");
        module.open();
        idle();

        Camera camera = ReflectionHelpers.getField(module, "mCamera");
        int[] range = new int[2];
        camera.getParameters().getPreviewFpsRange(range);
        assertEquals(15000, range[Camera.Parameters.PREVIEW_FPS_MIN_INDEX]);
        assertEquals(30000, range[Camera.Parameters.PREVIEW_FPS_MAX_INDEX]);
    }

    /** A camera that will not take the range it lists keeps its own, and still opens. */
    @Test
    @Config(shadows = {ShadowFrameRateRefusingCamera.class})
    public void aCameraThatRefusesTheFrameRateStillOpens() {
        givenTheCameraOffersFrameRates("(5000,30000),(15000,30000)", "5000,30000");
        module.open();
        idle();

        assertTrue(module.isCameraReady());
        Camera camera = ReflectionHelpers.getField(module, "mCamera");
        assertEquals("5000,30000", camera.getParameters().get("preview-fps-range"));
    }

    @Test
    public void theFastestTopRateUpTo30FpsIsChosen() {
        assertRange(15000, 30000, new int[] {7500, 24000}, new int[] {15000, 30000},
                new int[] {30000, 60000});
        assertRange(30000, 30000, new int[] {7500, 30000}, new int[] {30000, 30000});
        assertRange(24000, 24000, new int[] {24000, 24000}, new int[] {15000, 15000});
    }

    @Test
    public void theLowestFloorOf15FpsIsChosen() {
        assertRange(15000, 30000, new int[] {30000, 30000}, new int[] {15000, 30000},
                new int[] {20000, 30000});
    }

    @Test
    public void aCameraWhoseFloorsAreAllLowGetsItsHighest() {
        assertRange(10000, 30000, new int[] {5000, 30000}, new int[] {10000, 30000},
                new int[] {7500, 30000});
    }

    @Test
    public void aCameraWhoseRangesAreAllFasterGetsItsSlowest() {
        assertRange(30000, 60000, new int[] {60000, 120000}, new int[] {30000, 60000});
    }

    @Test
    public void aCameraThatListsNoRangesIsLeftAlone() {
        assertNull(LegacyCameraModule.previewFpsRange(null));
        assertNull(LegacyCameraModule.previewFpsRange(new ArrayList<>()));
    }

    private static void assertRange(int min, int max, int[]... supported) {
        int[] range = LegacyCameraModule.previewFpsRange(Arrays.asList(supported));
        assertNotNull(range);
        assertEquals(min + "-" + max, range[Camera.Parameters.PREVIEW_FPS_MIN_INDEX] + "-"
                + range[Camera.Parameters.PREVIEW_FPS_MAX_INDEX]);
    }

    /** The torch lights the flash continuously, and turning it off gives the strategy back. */
    @Test
    public void theTorchLightsTheFlash() {
        givenTheCameraOffers(Camera.Parameters.FLASH_MODE_OFF, Camera.Parameters.FLASH_MODE_AUTO,
                Camera.Parameters.FLASH_MODE_ON, Camera.Parameters.FLASH_MODE_TORCH);
        module.setFlash(CameraView.Flash.AUTO);
        module.open();
        idle();

        module.setTorchEnabled(true);
        assertTrue(module.isTorchEnabled());
        assertEquals(Camera.Parameters.FLASH_MODE_TORCH, flashMode());

        module.setTorchEnabled(false);
        assertFalse(module.isTorchEnabled());
        assertEquals(Camera.Parameters.FLASH_MODE_AUTO, flashMode());
    }

    /** A torch asked for before opening is lit once the camera opens. */
    @Test
    public void aTorchAskedForWhileClosedIsLitOnOpening() {
        givenTheCameraOffers(Camera.Parameters.FLASH_MODE_OFF, Camera.Parameters.FLASH_MODE_TORCH);
        module.setTorchEnabled(true);
        module.open();
        idle();

        assertEquals(Camera.Parameters.FLASH_MODE_TORCH, flashMode());
    }

    /** A camera with a flash but no torch mode refuses the torch, as the other backends do. */
    @Test
    public void aCameraWithoutATorchModeRefusesTheTorch() {
        givenTheCameraOffers(Camera.Parameters.FLASH_MODE_OFF, Camera.Parameters.FLASH_MODE_ON);
        module.open();
        idle();

        assertTrue(module.hasFlash());
        module.setTorchEnabled(true);
        assertFalse(module.isTorchEnabled());
        assertEquals(Camera.Parameters.FLASH_MODE_OFF, flashMode());
    }

    /** A strategy the camera does not offer leaves the flash off rather than failing. */
    @Test
    public void aStrategyTheCameraLacksLeavesTheFlashOff() {
        givenTheCameraOffers(Camera.Parameters.FLASH_MODE_OFF, Camera.Parameters.FLASH_MODE_ON);
        module.setFlash(CameraView.Flash.AUTO);
        module.open();
        idle();

        assertEquals(Camera.Parameters.FLASH_MODE_OFF, flashMode());
    }

    /**
     * Each quality below MAX takes the largest picture within its bound, as Camera2 does: 1080p
     * for HIGH, 720p for MEDIUM and 480p for LOW. They all used to take the camera's default.
     */
    @Test
    public void eachQualityTakesThePictureSizeItsBoundAllows() {
        givenThePictureSizes(PICTURE_SIZES);
        module.open();
        idle();

        module.setQuality(CameraView.Quality.MAX);
        assertEquals("4000x3000", pictureSize());
        module.setQuality(CameraView.Quality.HIGH);
        assertEquals("1920x1080", pictureSize());
        module.setQuality(CameraView.Quality.MEDIUM);
        assertEquals("1280x720", pictureSize());
        module.setQuality(CameraView.Quality.LOW);
        assertEquals("640x480", pictureSize());
    }

    /** A quality with nothing within its bound takes the smallest the next bound up allows. */
    @Test
    public void aQualityWithNothingWithinItsBoundGoesUp() {
        givenThePictureSizes("4000x3000,1280x720");
        module.setQuality(CameraView.Quality.LOW);
        module.open();
        idle();

        assertEquals("1280x720", pictureSize());
    }

    /**
     * A picture that could not be written is reported on the main thread, where every other
     * callback the app hears arrives. It used to be reported from the writer's thread.
     */
    @Test
    public void aFailedPictureIsReportedOnTheMainThread() throws Exception {
        AtomicReference<Thread> reportedOn = new AtomicReference<>();
        CountDownLatch reported = new CountDownLatch(1);
        cameraView.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
            @Override public void onImageConfirmation() {}
            @Override public void onImageCaptured(File file) {}
            @Override public void onFailure() {
                reportedOn.set(Thread.currentThread());
                reported.countDown();
            }
        });
        ExecutorService writer = Executors.newSingleThreadExecutor();
        ShadowPausedAsyncTask.overrideExecutor(writer);
        File unwritable = new File(RuntimeEnvironment.getApplication().getCacheDir(),
                "no-such-directory/picture.jpg");
        Camera camera = Camera.open(0);
        try {
            new LegacyPictureListener(unwritable, 0, false, new ExifCaptureParameters(), module)
                    .onPictureTaken(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9},
                            camera);
            // Generous, because a loaded machine can starve the writer for seconds; the loop
            // ends as soon as it reports, so a passing run is no slower.
            long deadline = System.currentTimeMillis() + 30_000;
            while (reported.getCount() > 0 && System.currentTimeMillis() < deadline) {
                idle();
                reported.await(10, TimeUnit.MILLISECONDS);
            }
        } finally {
            camera.release();
            writer.shutdownNow();
        }

        assertNotNull("the failure was never reported", reportedOn.get());
        assertSame(Looper.getMainLooper().getThread(), reportedOn.get());
    }

    /** A picture the camera refuses to take is reported failed rather than thrown. */
    @Test
    @Config(shadows = {ShadowRefusingCamera.class})
    public void aPictureTheCameraRefusesIsReportedFailed() throws Exception {
        AtomicReference<Boolean> failed = new AtomicReference<>(false);
        cameraView.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
            @Override public void onImageConfirmation() {}
            @Override public void onImageCaptured(File file) {}
            @Override public void onFailure() { failed.set(true); }
        });
        module.open();
        idle();

        module.takePicture(File.createTempFile("legacy", ".jpg"));
        idle();

        assertTrue(failed.get());
    }

    /**
     * A picture asked for while another is being taken is reported failed on the main thread,
     * and the first still arrives as itself. The second call used to replace the first one's
     * callback, so the first picture was filed as the second, or never answered.
     */
    @Test
    @Config(shadows = {ShadowSlowPictureCamera.class})
    public void aPictureAskedForWhileAnotherIsTakenFails() throws Exception {
        List<String> heard = new ArrayList<>();
        cameraView.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
            @Override public void onImageConfirmation() {}
            @Override public void onImageCaptured(File file) {
                heard.add("captured " + file.getName() + onMainThread());
            }
            @Override public void onFailure() {
                heard.add("failed" + onMainThread());
            }
        });
        ShadowPausedAsyncTask.overrideExecutor(Runnable::run);
        File directory = RuntimeEnvironment.getApplication().getCacheDir();
        module.open();
        idle();

        module.takePicture(new File(directory, "first.jpg"));
        module.takePicture(new File(directory, "second.jpg"));
        idle();
        assertEquals(Arrays.asList("failed on main"), heard);
        assertEquals("the second never reached the camera", 1,
                ShadowSlowPictureCamera.getRequestCount());

        ShadowSlowPictureCamera.deliverPicture(
                new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9});
        idle();
        assertEquals(Arrays.asList("failed on main", "captured first.jpg on main"), heard);

        module.takePicture(new File(directory, "third.jpg"));
        idle();
        assertEquals("once the first arrives the next is taken", 2,
                ShadowSlowPictureCamera.getRequestCount());
    }

    /**
     * A burst asked for while a picture is being taken finishes at once, untaken, since its first
     * picture would take the other's callback as a second picture would.
     */
    @Test
    @Config(shadows = {ShadowSlowPictureCamera.class})
    public void aBurstAskedForWhileAPictureIsTakenDoesNotStart() throws Exception {
        List<String> heard = new ArrayList<>();
        File directory = RuntimeEnvironment.getApplication().getCacheDir();
        module.open();
        idle();
        module.takePicture(new File(directory, "single.jpg"));

        module.startBurst(new Burst(directory, "late", 3, new Burst.Callback() {
            @Override public void onPictureTaken(@NonNull File file) {
                heard.add("burst picture " + file.getName());
            }
            @Override public void onBurstFinished() {
                heard.add("burst finished");
            }
        }));
        idle();

        assertEquals(Arrays.asList("burst finished"), heard);
        assertEquals("the burst never reached the camera", 1,
                ShadowSlowPictureCamera.getRequestCount());
    }

    /** A picture being taken when the camera closes does not hold up the next camera's. */
    @Test
    @Config(shadows = {ShadowSlowPictureCamera.class})
    public void closingTheCameraEndsThePictureBeingTaken() throws Exception {
        AtomicReference<Boolean> failed = new AtomicReference<>(false);
        cameraView.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
            @Override public void onImageConfirmation() {}
            @Override public void onImageCaptured(File file) {}
            @Override public void onFailure() { failed.set(true); }
        });
        File directory = RuntimeEnvironment.getApplication().getCacheDir();
        module.open();
        idle();
        module.takePicture(new File(directory, "abandoned.jpg"));
        module.close();
        module.open();
        idle();

        module.takePicture(new File(directory, "next.jpg"));
        idle();

        assertFalse(failed.get());
        assertEquals(2, ShadowSlowPictureCamera.getRequestCount());
    }

    /**
     * Makes the camera list the given preview frame rate ranges and start at one of them, as
     * its parameters say on the next open.
     */
    private static void givenTheCameraOffersFrameRates(String ranges, String current) {
        Camera camera = Camera.open(0);
        try {
            Camera.Parameters parameters = camera.getParameters();
            parameters.set("preview-fps-range-values", ranges);
            parameters.set("preview-fps-range", current);
            camera.setParameters(parameters);
        } finally {
            camera.release();
        }
    }

    /** Makes the camera list the given flash modes, as its parameters say on the next open. */
    private static void givenTheCameraOffers(String... modes) {
        Camera camera = Camera.open(0);
        try {
            Camera.Parameters parameters = camera.getParameters();
            Shadows.shadowOf(parameters).setSupportedFlashModes(modes);
            parameters.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
            camera.setParameters(parameters);
        } finally {
            camera.release();
        }
    }

    /** Makes the camera list the given picture sizes, the first being where it starts. */
    private static void givenThePictureSizes(String sizes) {
        Camera camera = Camera.open(0);
        try {
            Camera.Parameters parameters = camera.getParameters();
            parameters.set("picture-size-values", sizes);
            parameters.set("picture-size", sizes.split(",")[0]);
            camera.setParameters(parameters);
        } finally {
            camera.release();
        }
    }

    private String flashMode() {
        Camera camera = ReflectionHelpers.getField(module, "mCamera");
        return camera.getParameters().getFlashMode();
    }

    private String pictureSize() {
        Camera camera = ReflectionHelpers.getField(module, "mCamera");
        Camera.Size size = camera.getParameters().getPictureSize();
        return size.width + "x" + size.height;
    }

    private static String onMainThread() {
        return Looper.myLooper() == Looper.getMainLooper() ? " on main" : " off main";
    }

    private static void idle() {
        ShadowLooper.shadowMainLooper().idle();
    }
}
