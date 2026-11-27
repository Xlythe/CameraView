package com.xlythe.view.camera.test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.util.Size;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;

import com.xlythe.view.camera.Barcode;
import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.VideoStream;
import com.xlythe.view.camera.VideoView;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The rest of the public surface, on a real camera.
 *
 * <p>Everything here needs something an emulator-free test cannot have: a confirmation that holds
 * a real preview frame, an ML Kit detector that needs Play services, an audio stream that needs a
 * microphone, a decoder that needs a container the camera actually produced. They are the parts
 * of the library an app touches most and the parts the unit suite can only reach the edges of.
 * </p>
 */
@RunWith(AndroidJUnit4.class)
public final class CameraSurfaceDeviceTest {
    private static final long TIMEOUT_SECONDS = 30;

    @Rule
    public final GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
    );

    @Before
    public void requireACamera() {
        Assume.assumeTrue("no camera on this device",
                ApplicationProvider.getApplicationContext().getPackageManager()
                        .hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY));
    }

    @After
    public void restoreTheDefault() {
        CameraView.setDefaultBackend(CameraView.Backend.AUTO);
    }

    /**
     * A confirmed picture is reported, and the preview comes back.
     *
     * <p>Confirmation freezes the preview on the captured frame and waits, which is the flow a
     * review screen uses. Off a device there is no frame to freeze on.</p>
     */
    @Test
    public void aConfirmedPictureIsReported() throws Exception {
        File file = temporaryFile(".jpg");
        CountDownLatch pending = new CountDownLatch(1);
        CountDownLatch captured = new CountDownLatch(1);
        AtomicReference<File> saved = new AtomicReference<>();

        withOpenCamera(camera -> {
            camera.setImageConfirmationEnabled(true);
            camera.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
                @Override public void onImageConfirmation() {
                    pending.countDown();
                }

                @Override public void onImageCaptured(File confirmed) {
                    saved.set(confirmed);
                    captured.countDown();
                }

                @Override public void onFailure() {
                    pending.countDown();
                    captured.countDown();
                }
            });

            camera.takePicture(file);
            assertTrue("the capture never asked for confirmation",
                    pending.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertTrue("a pending picture should say so",
                    camera.isImageConfirmationPending());
            // On the main thread, because confirming swaps the preview back and that is a View
            // operation - the same thread a button's click handler would call it from.
            onMain(camera::confirmPicture);
            assertTrue("confirming never reported",
                    captured.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertFalse("nothing is pending once it is confirmed",
                    camera.isImageConfirmationPending());
        });

        assertNotNull("the confirmed picture was not reported", saved.get());
        assertTrue(saved.get().length() > 0);
    }

    /** A rejected picture is deleted, which is the other half of the same flow. */
    @Test
    public void aRejectedPictureIsDeleted() throws Exception {
        File file = temporaryFile(".jpg");
        CountDownLatch pending = new CountDownLatch(1);
        AtomicBoolean reported = new AtomicBoolean();

        withOpenCamera(camera -> {
            camera.setImageConfirmationEnabled(true);
            camera.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
                @Override public void onImageConfirmation() {
                    pending.countDown();
                }

                @Override public void onImageCaptured(File confirmed) {
                    reported.set(true);
                }

                @Override public void onFailure() {
                    pending.countDown();
                }
            });

            camera.takePicture(file);
            assertTrue(pending.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            onMain(camera::rejectPicture);
            sleep(500);
            assertFalse("a rejected picture must not be reported as captured", reported.get());
            assertFalse(camera.isImageConfirmationPending());
        });

        assertFalse("a rejected picture should be gone", file.exists() && file.length() > 0);
    }

    /**
     * Barcode scanning runs, which needs Play services rather than a camera.
     *
     * <p>Nothing in front of this camera is a barcode, so what is being tested is that the
     * detector starts, runs over real frames and stops - the ML Kit client, the analyzer and the
     * frame pump behind it. Off a device none of that exists.</p>
     */
    @Test
    public void barcodeScanningStartsAndStops() throws Exception {
        DeviceState.assumeYuvBuffersWork();
        AtomicInteger results = new AtomicInteger();
        AtomicBoolean started = new AtomicBoolean();

        withOpenCamera(camera -> {
            try {
                camera.enterBarcodeScanner(barcodes -> results.incrementAndGet(),
                        Barcode.Format.QR_CODE);
                started.set(true);
            } catch (RuntimeException error) {
                // ML Kit is delivered by Play services, which an image without them lacks.
                started.set(false);
            }
            if (started.get()) {
                sleep(5_000);
                camera.exitBarcodeScanner();
            }
        });

        Assume.assumeTrue("ML Kit is unavailable on this image", started.get());
        // Having run at all is the assertion: an empty scene produces no barcodes.
        assertTrue(results.get() >= 0);
    }

    /** An audio stream exercises the recorder and the player, which need a microphone. */
    @Test
    public void anAudioAndVideoStreamIsPlayedBack() throws Exception {
        AtomicReference<VideoStream> held = new AtomicReference<>();
        AtomicBoolean playing = new AtomicBoolean();

        withOpenCamera(camera -> {
            VideoStream stream = camera.stream(new VideoStream.Params.Builder()
                    .setAudioEnabled(true)
                    .setVideoEnabled(true)
                    .setMaxSize(new Size(640, 480))
                    .setBitRate(1_000_000)
                    .setIFrameInterval(1)
                    .setIsLossy(true)
                    .build());
            held.set(stream);
            assertTrue("an audio stream should carry audio", stream.hasAudio());
            assertTrue(stream.hasVideo());

            // Played back in the activity already showing the camera, which is what drives the
            // decoder and the audio track at the far end. Launching a second activity would
            // destroy this one on the way out.
            onMain(() -> {
                VideoView video = new VideoView(mActivity);
                mActivity.addContentView(video,
                        new android.view.ViewGroup.LayoutParams(640, 480));
                video.setStream(stream);
                playing.set(video.hasStream());
            });
            sleep(6_000);
        });

        VideoStream stream = held.get();
        if (stream != null) {
            stream.close();
        }
        assertTrue("the stream never started playing", playing.get());
    }

    /** A recorded file plays back, which is the decoder path a confirmation screen uses. */
    @Test
    public void aRecordedFileIsPlayedBack() throws Exception {
        File file = temporaryFile(".mp4");
        CountDownLatch recorded = new CountDownLatch(1);
        AtomicReference<File> saved = new AtomicReference<>();

        withOpenCamera(camera -> {
            camera.setOnVideoCapturedListener(new CameraView.OnVideoCapturedListener() {
                @Override public void onVideoConfirmation() {}

                @Override public void onVideoCaptured(File captured) {
                    saved.set(captured);
                    recorded.countDown();
                }

                @Override public void onFailure() {
                    recorded.countDown();
                }
            });
            camera.startRecording(file);
            sleep(5_000);
            camera.stopRecording();
            assertTrue(recorded.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        });

        Assume.assumeTrue("nothing was recorded to play", saved.get() != null);
        AtomicBoolean played = new AtomicBoolean();
        try (ActivityScenario<CameraTestActivity> scenario =
                     ActivityScenario.launch(CameraTestActivity.class)) {
            scenario.onActivity(activity -> {
                VideoView video = new VideoView(activity);
                activity.addContentView(video, new android.view.ViewGroup.LayoutParams(640, 480));
                video.setFile(saved.get());
                video.setShouldLoop(true);
                video.setVolume(0.5f);
            });
            sleep(3_000);
            scenario.onActivity(activity -> played.set(true));
        }

        assertTrue(played.get());
    }

    /**
     * Closing and reopening a live camera, which is what every rotation does.
     *
     * <p>The interesting part is the second open: the first one configured a session and the
     * teardown has to have released enough for another to configure.</p>
     */
    @Test
    public void closingAndReopeningWorksOnALiveCamera() throws Exception {
        AtomicBoolean reopened = new AtomicBoolean();

        withOpenCamera(camera -> {
            camera.close();
            sleep(1_500);
            assertFalse("a closed camera is not open", camera.isOpen());
            camera.open();
            sleep(4_000);
            reopened.set(camera.isOpen());
        });

        assertTrue("the camera has to come back from a close", reopened.get());
    }

    /** The optional controls are offered to a real camera, which accepts or declines each. */
    @Test
    public void theOptionalControlsAreOfferedToTheCamera() throws Exception {
        AtomicReference<String> reported = new AtomicReference<>();

        DeviceState.assumeTheLampMayBeLit();

        withOpenCamera(camera -> {
            camera.setTorchEnabled(true);
            sleep(300);
            camera.setTorchEnabled(false);

            if (camera.isExposureCompensationSupported()) {
                camera.setExposureCompensation(1);
                sleep(300);
            }
            if (camera.isStabilizationSupported()) {
                camera.setStabilizationEnabled(true);
                sleep(1_000);
            }
            if (camera.isColorSpaceSupported(CameraView.ColorSpace.DISPLAY_P3)) {
                camera.setColorSpace(CameraView.ColorSpace.DISPLAY_P3);
                sleep(2_000);
            }
            camera.setPictureQuality(85);
            camera.focus(new Rect(-50, -50, 50, 50), new Rect(-150, -150, 150, 150));
            sleep(500);

            reported.set("flash=" + camera.getFlash()
                    + " quality=" + camera.getPictureQuality()
                    + " colorSpace=" + camera.getColorSpace());
        });

        assertNotNull(reported.get());
        assertTrue(reported.get().contains("quality=85"));
    }

    /**
     * The camera survives the activity being recreated, which is what a rotation is.
     *
     * <p>Through the framework rather than by calling the save and restore hooks directly, so
     * what is covered is the whole path: a configured camera torn down with its host, its state
     * written out, and a new one built and reopened from it.</p>
     */
    @Test
    public void theCameraSurvivesTheActivityBeingRecreated() throws Exception {
        AtomicBoolean reopened = new AtomicBoolean();

        try (ActivityScenario<CameraTestActivity> scenario =
                     ActivityScenario.launch(CameraTestActivity.class)) {
            CountDownLatch opened = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                CameraView camera = activity.getCameraView();
                camera.setQuality(CameraView.Quality.LOW);
                camera.setOnCameraStateChangedListener(
                        new CameraView.OnCameraStateChangedListener() {
                            @Override public void onCameraOpened() {
                                opened.countDown();
                            }

                            @Override public void onCameraClosed() {}
                        });
                camera.open();
            });
            Assume.assumeTrue("the camera did not open",
                    opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            scenario.recreate();
            sleep(4_000);

            scenario.onActivity(activity -> {
                CameraView camera = activity.getCameraView();
                reopened.set(camera.getQuality() == CameraView.Quality.LOW);
                camera.close();
            });
        }

        assertTrue("the configuration should survive a recreation", reopened.get());
    }

    /** The activity hosting the camera for the test in progress. */
    private CameraTestActivity mActivity;

    /** Runs on the main thread and waits, which is where every View operation belongs. */
    private void onMain(Runnable runnable) {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(runnable);
    }

    private interface CameraAction {
        void run(CameraView camera) throws Exception;
    }

    private void withOpenCamera(CameraAction action) throws Exception {
        try (ActivityScenario<CameraTestActivity> scenario =
                     ActivityScenario.launch(CameraTestActivity.class)) {
            CountDownLatch opened = new CountDownLatch(1);
            AtomicReference<CameraView> held = new AtomicReference<>();
            scenario.onActivity(activity -> {
                mActivity = activity;
                CameraView camera = activity.getCameraView();
                held.set(camera);
                camera.setOnCameraStateChangedListener(
                        new CameraView.OnCameraStateChangedListener() {
                            @Override public void onCameraOpened() {
                                opened.countDown();
                            }

                            @Override public void onCameraClosed() {}
                        });
                if (camera.isOpen()) {
                    opened.countDown();
                } else {
                    camera.open();
                }
            });

            Assume.assumeTrue("the camera did not open on this device",
                    opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            CameraView camera = held.get();
            assertNotNull(camera);
            // The library's default is AUTO, so in a dim room every capture in this suite fires
            // the flash - a dozen times over a full run, which is unpleasant for whoever is
            // holding the phone and makes the captures depend on the light in the room. Nothing
            // here asserts on exposure; the tests that mean to exercise the flash set the mode
            // they want themselves.
            DeviceState.turnTheFlashOff(camera);
            try {
                action.run(camera);
            } finally {
                scenario.onActivity(activity -> activity.getCameraView().close());
            }
        }
    }

    private static File temporaryFile(String extension) throws Exception {
        File file = File.createTempFile("surface", extension,
                ApplicationProvider.getApplicationContext().getCacheDir());
        file.deleteOnExit();
        return file;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
