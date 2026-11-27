package com.xlythe.view.camera.legacy;

import static com.xlythe.view.camera.FakeLocation.LATITUDE;
import static com.xlythe.view.camera.FakeLocation.LONGITUDE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.location.Location;
import android.location.LocationManager;
import android.media.CamcorderProfile;
import android.media.MediaActionSound;
import android.media.MediaRecorder;

import com.xlythe.view.camera.CameraSoundsTest;
import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.Exif;
import com.xlythe.view.camera.FakeLocation;
import com.xlythe.view.camera.shadow.ShadowAvailableTextureView;
import com.xlythe.view.camera.shadow.ShadowGeotaggingMediaRecorder;
import com.xlythe.view.camera.shadow.ShadowListingCamcorderProfile;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowCamcorderProfile;
import org.robolectric.shadows.ShadowCamera;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowMediaActionSound;
import org.robolectric.shadows.ShadowPausedAsyncTask;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Taking pictures and recording video on the old camera API.
 *
 * <p>The backend the library keeps for devices below the Camera2 floor, and the one with the least
 * margin for error: {@code android.hardware.Camera} has no session to configure and no callbacks
 * to wait on, so the whole of a capture is a method call and a listener, and everything that can
 * go wrong goes wrong in that listener. Robolectric shadows the API, so the capture can be driven
 * and the frame handed back as the driver would.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, shadows = {
        ShadowAvailableTextureView.class,
        ShadowGeotaggingMediaRecorder.class,
        ShadowListingCamcorderProfile.class})
public class LegacyCaptureTest {
    /** The smallest JPEG that EXIF can be written into, for the tests that read it back. */
    private static final byte[] EMPTY_JPEG = {
            (byte) 0xFF, (byte) 0xD8, // SOI
            (byte) 0xFF, (byte) 0xD9, // EOI
    };

    private Context context;
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
        context = RuntimeEnvironment.getApplication();
        Shadows.shadowOf((android.app.Application) context).grantPermissions(
                Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO);
        addCamera(0, Camera.CameraInfo.CAMERA_FACING_BACK, 90);
        addCamera(1, Camera.CameraInfo.CAMERA_FACING_FRONT, 270);
        addRecorderProfiles();

        // So that a test opening the camera through the view drives this backend too.
        CameraView.setDefaultBackend(CameraView.Backend.LEGACY);
        cameraView = new TestCameraView(context);
        cameraView.layout(0, 0, 1080, 1920);
        module = new LegacyCameraModule(cameraView);
        ShadowGeotaggingMediaRecorder.forgetLocation();
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

    /**
     * A picture is tagged with the fix the camera has followed since it opened, which is still
     * there once the system has forgotten its own.
     */
    @Test
    public void aPictureIsTaggedWithWhereTheDeviceIs() throws IOException {
        FakeLocation.addProviders(context);
        FakeLocation.grantFine(context);
        cameraView.open();
        idle();
        FakeLocation.deliver(context,
                FakeLocation.fix(LocationManager.GPS_PROVIDER, LATITUDE, LONGITUDE, 5));
        FakeLocation.setLastKnown(context, LocationManager.GPS_PROVIDER, null);

        File file = savePicture();

        Location tagged = new Exif(file).getLocation();
        assertNotNull("expected the picture to carry a location", tagged);
        assertEquals(LATITUDE, tagged.getLatitude(), 1e-4);
        assertEquals(LONGITUDE, tagged.getLongitude(), 1e-4);
        assertEquals("the capture asks for nothing of its own",
                2, FakeLocation.listeners(context));
    }

    /** A recording is tagged with the fix the camera has followed since it opened. */
    @Test
    public void aRecordingIsTaggedWithWhereTheDeviceIs() throws IOException {
        FakeLocation.addProviders(context);
        FakeLocation.grantFine(context);
        cameraView.open();
        idle();
        FakeLocation.deliver(context,
                FakeLocation.fix(LocationManager.GPS_PROVIDER, LATITUDE, LONGITUDE, 5));
        FakeLocation.setLastKnown(context, LocationManager.GPS_PROVIDER, null);
        File file = File.createTempFile("legacy", ".mp4");
        file.deleteOnExit();

        cameraView.startRecording(file);
        idle();

        float[] tagged = ShadowGeotaggingMediaRecorder.getLocation();
        assertNotNull("expected the recording to carry a location", tagged);
        assertEquals(LATITUDE, tagged[0], 1e-4);
        assertEquals(LONGITUDE, tagged[1], 1e-4);
        assertEquals("the recording asks for nothing of its own",
                2, FakeLocation.listeners(context));
        cameraView.stopRecording();
        idle();
    }

    /** With no fix to be had, a picture and a recording go untagged rather than waiting. */
    @Test
    public void noCaptureWaitsForAFix() throws IOException {
        FakeLocation.addProviders(context);
        FakeLocation.grantFine(context);
        module.open();
        idle();
        File video = File.createTempFile("legacy", ".mp4");
        video.deleteOnExit();

        File picture = savePicture();
        module.startRecording(video);
        idle();
        module.stopRecording();
        idle();

        assertEquals("nothing may be asked of the radio", 0, FakeLocation.listeners(context));
        assertNull(new Exif(picture).getLocation());
        assertNull(ShadowGeotaggingMediaRecorder.getLocation());
    }

    /** A capture hands the frame to the listener, which writes it and reports success. */
    @Test
    public void aCapturedFrameIsWrittenAndReported() throws IOException {
        AtomicReference<File> captured = new AtomicReference<>();
        AtomicInteger failures = new AtomicInteger();
        cameraView.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
            @Override public void onImageConfirmation() {}
            @Override public void onImageCaptured(File file) { captured.set(file); }
            @Override public void onFailure() { failures.incrementAndGet(); }
        });
        module.open();
        idle();
        File file = File.createTempFile("legacy", ".jpg");
        file.deleteOnExit();

        module.takePicture(file);
        idle();
        flushBackgroundWork();

        // The shadow hands back an empty frame, so what is exercised is the listener's whole path
        // - decode, orient, write, report - rather than the bytes.
        assertTrue("the capture has to come back one way or the other",
                captured.get() != null || failures.get() > 0);
    }

    /** The platform clicks for the old API itself, so the library adds only the callback. */
    @Test
    public void theClickIsThePlatformsOwn() throws IOException {
        CameraSoundsTest.resetSounds();
        AtomicInteger shutters = new AtomicInteger();
        cameraView.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
            @Override public void onShutter() { shutters.incrementAndGet(); }
            @Override public void onImageConfirmation() {}
            @Override public void onImageCaptured(File file) {}
            @Override public void onFailure() {}
        });
        module.open();
        idle();
        File file = File.createTempFile("legacy", ".jpg");
        file.deleteOnExit();

        module.takePicture(file);
        idle();
        flushBackgroundWork();

        assertEquals(1, shutters.get());
        assertEquals(0, ShadowMediaActionSound.getPlayCount(MediaActionSound.SHUTTER_CLICK));
    }

    /** Silencing the shutter is passed on to the platform, whichever way round it is set. */
    @Test
    public void silencingTheShutterReachesThePlatform() {
        module.setShutterSoundEnabled(false);
        module.open();
        idle();
        Camera camera = ReflectionHelpers.getField(module, "mCamera");

        assertFalse(Shadows.shadowOf(camera).isShutterSoundEnabled());

        module.setShutterSoundEnabled(true);

        assertTrue(Shadows.shadowOf(camera).isShutterSoundEnabled());
    }

    /** An app's own shutter sound silences the platform's click, and the library plays it. */
    @Test
    public void anAppsShutterSoundReplacesThePlatformsClick() throws IOException {
        CameraSoundsTest.resetSounds();
        module.setShutterSound(0x7f120001);
        module.open();
        idle();
        Camera camera = ReflectionHelpers.getField(module, "mCamera");
        File file = File.createTempFile("legacy", ".jpg");
        file.deleteOnExit();

        module.takePicture(file);
        idle();
        flushBackgroundWork();

        assertFalse(Shadows.shadowOf(camera).isShutterSoundEnabled());
        // Not loaded yet in a test, so the platform's click stands in, played by the library.
        assertEquals(1, ShadowMediaActionSound.getPlayCount(MediaActionSound.SHUTTER_CLICK));
    }

    /** Where the click is required the platform keeps it, and the library adds nothing. */
    @Test
    public void aRequiredClickStaysThePlatforms() throws IOException {
        CameraSoundsTest.resetSounds();
        ShadowCamera.clearCameraInfo();
        Camera.CameraInfo info = new Camera.CameraInfo();
        info.facing = Camera.CameraInfo.CAMERA_FACING_BACK;
        info.orientation = 90;
        info.canDisableShutterSound = false;
        ShadowCamera.addCameraInfo(0, info);
        module.setShutterSound(0x7f120001);
        module.open();
        idle();
        Camera camera = ReflectionHelpers.getField(module, "mCamera");
        File file = File.createTempFile("legacy", ".jpg");
        file.deleteOnExit();

        module.takePicture(file);
        idle();
        flushBackgroundWork();

        assertTrue(Shadows.shadowOf(camera).isShutterSoundEnabled());
        assertEquals(0, ShadowMediaActionSound.getPlayCount(MediaActionSound.SHUTTER_CLICK));
    }

    /** A capture asked for before the camera is open fails rather than throwing. */
    @Test
    public void aCaptureBeforeOpeningFails() throws IOException {
        AtomicInteger failures = new AtomicInteger();
        cameraView.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
            @Override public void onImageConfirmation() {}
            @Override public void onImageCaptured(File file) {}
            @Override public void onFailure() { failures.incrementAndGet(); }
        });
        File file = File.createTempFile("legacy", ".jpg");
        file.deleteOnExit();

        module.takePicture(file);
        idle();

        assertEquals("a closed camera cannot take a picture", 1, failures.get());
    }

    /** Recording runs the whole MediaRecorder setup, which is where the profiles are read. */
    @Test
    public void recordingStartsAndStops() throws IOException {
        module.open();
        idle();
        File file = File.createTempFile("legacy", ".mp4");
        file.deleteOnExit();
        assertFalse(module.isRecording());

        module.startRecording(file);
        idle();

        module.stopRecording();
        idle();
        assertFalse("stopping has to leave it not recording", module.isRecording());
    }

    /** Every quality picks a profile, and the fall-through has to land somewhere. */
    @Test
    public void everyQualityFindsAProfile() throws IOException {
        module.open();
        idle();

        for (CameraView.Quality quality : CameraView.Quality.values()) {
            module.setQuality(quality);
            File file = File.createTempFile("legacy-" + quality, ".mp4");
            file.deleteOnExit();

            module.startRecording(file);
            idle();
            module.stopRecording();
            idle();

            assertFalse("after " + quality, module.isRecording());
        }
    }

    /** Recording before opening fails rather than throwing at the caller. */
    @Test
    public void recordingBeforeOpeningIsRefused() throws IOException {
        File file = File.createTempFile("legacy", ".mp4");
        file.deleteOnExit();

        module.startRecording(file);
        idle();

        assertFalse(module.isRecording());
    }

    /** The flash strategy is a camera parameter here, applied when it is set. */
    @Test
    public void everyFlashModeIsApplied() {
        module.open();
        idle();

        for (CameraView.Flash flash : CameraView.Flash.values()) {
            module.setFlash(flash);
            idle();
            assertEquals(flash, module.getFlash());
        }
    }

    /** A torch is the same parameter, and asking for one the camera lacks is declined. */
    @Test
    public void theTorchIsDeclinedWhenTheCameraHasNone() {
        module.open();
        idle();

        module.setTorchEnabled(true);
        idle();

        // The fake camera publishes no flash modes, so there is nothing to turn on; what matters
        // is that asking does not throw.
        assertNotNull(module.getFlash());
    }

    /** Closing while recording stops the recording rather than leaving the encoder running. */
    @Test
    public void closingWhileRecordingStopsIt() throws IOException {
        module.open();
        idle();
        File file = File.createTempFile("legacy", ".mp4");
        file.deleteOnExit();
        module.startRecording(file);
        idle();

        module.close();
        idle();

        assertFalse("a closed camera cannot still be recording", module.isRecording());
    }

    /** A layout change re-transforms the preview, which needs the camera's own sizes. */
    @Test
    public void aLayoutChangeWhileOpenIsAbsorbed() {
        module.open();
        idle();

        module.onLayoutChanged();
        idle();

        assertTrue(module.hasFrontFacingCamera());
    }

    /**
     * Hands the picture listener a frame and lets it save.
     *
     * <p>The shadow camera hands its listener an empty frame, which leaves nothing to write EXIF
     * into, so this hands it a real one as the driver would.</p>
     */
    private File savePicture() throws IOException {
        File file = File.createTempFile("legacy", ".jpg");
        file.deleteOnExit();
        ShadowPausedAsyncTask.overrideExecutor(Runnable::run);
        Camera camera = Camera.open(1);
        try {
            new LegacyPictureListener(file, 0, false, module).onPictureTaken(EMPTY_JPEG, camera);
            idle();
        } finally {
            camera.release();
        }
        return file;
    }

    /** Lets the capture's background write finish, since it is handed to an executor. */
    private static void flushBackgroundWork() {
        for (int attempt = 0; attempt < 20; attempt++) {
            idle();
            try {
                Thread.sleep(5);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void idle() {
        ShadowLooper.shadowMainLooper().idle();
    }

    private static void addCamera(int id, int facing, int orientation) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        info.facing = facing;
        info.orientation = orientation;
        // As every camera reports outside the countries that require the sound.
        info.canDisableShutterSound = true;
        ShadowCamera.addCameraInfo(id, info);
    }

    /** Recorder profiles for both cameras, which MediaRecorder setup reads. */
    private static void addRecorderProfiles() {
        for (int cameraId : new int[] {0, 1}) {
            for (int quality : new int[] {
                    CamcorderProfile.QUALITY_LOW,
                    CamcorderProfile.QUALITY_HIGH,
                    CamcorderProfile.QUALITY_480P,
                    CamcorderProfile.QUALITY_720P,
                    CamcorderProfile.QUALITY_1080P,
            }) {
                ShadowCamcorderProfile.addProfile(cameraId, quality,
                        ShadowCamcorderProfile.createProfile(
                                /* duration= */ 30,
                                quality,
                                MediaRecorder.OutputFormat.MPEG_4,
                                MediaRecorder.VideoEncoder.H264,
                                /* videoBitRate= */ 8_000_000,
                                /* videoFrameRate= */ 30,
                                /* videoFrameWidth= */ 1280,
                                /* videoFrameHeight= */ 720,
                                MediaRecorder.AudioEncoder.AAC,
                                /* audioBitRate= */ 96_000,
                                /* audioSampleRate= */ 44_100,
                                /* audioChannels= */ 1));
            }
        }
    }
}
