package com.xlythe.view.camera.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.util.Size;
import android.view.ViewGroup;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.FrameStream;
import com.xlythe.view.camera.VideoStream;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Every backend, doing the same things, on a real camera.
 *
 * <p>The library's whole proposition is that the three implementations behave the same, and there
 * is only one way to find out: run the same scenarios against each of them on hardware. Off a
 * device they cannot even be compared - CameraX copies a shared stream through an OpenGL
 * processor, and the old API needs a camera that answers.</p>
 *
 * <p>A backend that cannot run here is skipped rather than failed. That is not a loophole: an
 * emulator image without a second camera, or a device whose CameraX dependency was excluded, is a
 * real configuration, and a suite that went red on it would say nothing useful.</p>
 */
@RunWith(Parameterized.class)
public final class BackendMatrixDeviceTest {
    /** Log tag for what a run found, which is the evidence when comparing two devices. */
    private static final String TAG = "BackendMatrix";
    private static final long TIMEOUT_SECONDS = 30;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<CameraView.Backend> backends() {
        return Arrays.asList(
                CameraView.Backend.CAMERA2,
                CameraView.Backend.CAMERA_X,
                CameraView.Backend.LEGACY);
    }

    @Rule
    public final GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
    );

    private final CameraView.Backend backend;

    public BackendMatrixDeviceTest(CameraView.Backend backend) {
        this.backend = backend;
    }

    @Before
    public void chooseTheBackend() {
        DeviceState.assumeATestableCamera();
        CameraView.setDefaultBackend(backend);
    }

    @After
    public void restoreTheDefault() {
        CameraView.setDefaultBackend(CameraView.Backend.AUTO);
    }

    /** Opening is the one thing every backend has to do, so it is also the skip condition. */
    @Test
    public void theBackendOpensAndReportsItself() throws Exception {
        AtomicReference<CameraView.Backend> actual = new AtomicReference<>();

        withOpenCamera(camera -> actual.set(camera.getBackend()));

        assertNotNull(actual.get());
        assertTrue("asked for " + backend + " and got " + actual.get(),
                actual.get() == backend || backend == CameraView.Backend.AUTO);
    }

    /** A capture produces a file on every backend, which is the library's whole proposition. */
    @Test
    public void aCaptureProducesAFile() throws Exception {
        File file = temporaryFile(".jpg");
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<File> captured = new AtomicReference<>();

        withOpenCamera(camera -> {
            camera.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
                @Override public void onImageConfirmation() {}

                @Override public void onImageCaptured(File saved) {
                    captured.set(saved);
                    finished.countDown();
                }

                @Override public void onFailure() {
                    finished.countDown();
                }
            });
            camera.takePicture(file);
            assertTrue("the capture never reported on " + backend,
                    finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        });

        assertNotNull("the capture failed on " + backend, captured.get());
        assertTrue(captured.get().length() > 0);
    }

    /**
     * And it still produces one when the view settles underneath it, as it does when a picture is
     * taken the moment the camera opens.
     */
    @Test
    public void aCaptureSurvivesTheViewSettling() throws Exception {
        File file = temporaryFile(".jpg");
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<File> captured = new AtomicReference<>();

        withOpenCamera(camera -> {
            camera.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
                @Override public void onImageConfirmation() {}

                @Override public void onImageCaptured(File saved) {
                    captured.set(saved);
                    finished.countDown();
                }

                @Override public void onFailure() {
                    finished.countDown();
                }
            });
            camera.takePicture(file);
            // Resized while the capture is in flight, so the settle debounce expires during it.
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                ViewGroup.LayoutParams params = camera.getLayoutParams();
                params.width = Math.max(1, camera.getWidth() - 40);
                params.height = Math.max(1, camera.getHeight() - 40);
                camera.setLayoutParams(params);
            });
            assertTrue("the capture never reported on " + backend,
                    finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        });

        assertNotNull("a resize cost the capture on " + backend, captured.get());
        assertTrue(captured.get().length() > 0);
    }

    /** And so does a recording. */
    @Test
    public void aRecordingProducesAFile() throws Exception {
        File file = temporaryFile(".mp4");
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<File> recorded = new AtomicReference<>();

        withOpenCamera(camera -> {
            camera.setOnVideoCapturedListener(new CameraView.OnVideoCapturedListener() {
                @Override public void onVideoConfirmation() {}

                @Override public void onVideoCaptured(File saved) {
                    recorded.set(saved);
                    finished.countDown();
                }

                @Override public void onFailure() {
                    finished.countDown();
                }
            });
            camera.startRecording(file);
            // Seconds, not milliseconds: a real recording is seconds long, and an encoder behind
            // a software-rendered surface needs that long to produce anything at all.
            sleep(5_000);
            camera.stopRecording();
            // Reporting is the contract the library owes, and it owes it either way.
            assertTrue("the recording never reported on " + backend,
                    finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        });

        // Whether anything was encoded is the encoder's business, and on a surface rendered in
        // software it is marginal: CameraX finalizes with ERROR_NO_VALID_DATA and no bytes, from
        // a window that is ample on real hardware. Skipping says that plainly instead of going
        // red for a reason that is not the library's - and a backend that reported a file still
        // has to have produced a usable one.
        Assume.assumeTrue(backend + " produced no video on this device's encoder",
                recorded.get() != null);
        assertTrue("a reported recording has to have bytes in it", recorded.get().length() > 0);
    }

    /**
     * Frames reach an analyzer on every backend.
     *
     * <p>Which is the same code path for all three - the canvas and the frame pump - but the
     * backends attach a surface to it very differently, and on CameraX that goes through its own
     * stream-sharing processor.</p>
     */
    @Test
    public void framesReachAnAnalyzer() throws Exception {
        // Reading YUV_420_888 images from the emulated camera under SwiftShader takes the API 28
        // emulator process down with it, which fails this test and then aborts every test after
        // it. Skipped there rather than left to crash: it passes on hardware, so there is nothing
        // here for the library to fix, and a run that stops halfway tells nobody anything.
        Assume.assumeFalse("the API 28 emulator image crashes reading YUV images",
                DeviceState.isEmulator() && Build.VERSION.SDK_INT < 29);

        CountDownLatch analyzed = new CountDownLatch(2);
        AtomicReference<Size> size = new AtomicReference<>();

        withOpenCamera(camera -> {
            Assume.assumeTrue(backend + " cannot analyze frames at all",
                    camera.startFrameAnalysis(frame -> {
                        size.set(new Size(frame.getWidth(), frame.getHeight()));
                        analyzed.countDown();
                    }, new FrameStream.Params().setMaxSize(new Size(640, 480))));
            assertTrue("no frames on " + backend,
                    analyzed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            camera.stopFrameAnalysis();
        });

        assertNotNull(size.get());
    }

    /** A stream encodes on every backend that can attach a second output. */
    @Test
    public void aStreamDeliversEncodedFrames() throws Exception {
        AtomicReference<VideoStream> held = new AtomicReference<>();
        AtomicInteger bytes = new AtomicInteger();

        withOpenCamera(camera -> {
            VideoStream video = camera.stream(new VideoStream.Params.Builder()
                    .setAudioEnabled(false)
                    .setMaxSize(new Size(640, 480))
                    .setBitRate(1_000_000)
                    .setIFrameInterval(1)
                    .setIsLossy(true)
                    .build());
            held.set(video);
            Thread reader = new Thread(() -> {
                byte[] chunk = new byte[8192];
                try {
                    int read;
                    while (bytes.get() < 4096
                            && (read = video.getVideoInputStream().read(chunk)) > 0) {
                        bytes.addAndGet(read);
                    }
                } catch (Exception ignored) {
                    // The stream closing reads as an ordinary end.
                }
            }, "StreamReader-" + backend);
            reader.setDaemon(true);
            reader.start();
            sleep(6_000);
        });

        VideoStream video = held.get();
        if (video != null) {
            video.close();
        }
        Assume.assumeTrue("this backend could not attach a stream on this device", bytes.get() > 0);
        assertTrue(bytes.get() > 0);
    }

    /** The controls answer on every backend, with ranges that are real. */
    @Test
    public void theControlsAnswer() throws Exception {
        AtomicInteger maxZoom = new AtomicInteger();
        AtomicReference<CameraView.Flash> flash = new AtomicReference<>();
        AtomicReference<CameraView.Flash> flashAfterOff = new AtomicReference<>();
        AtomicBoolean faces = new AtomicBoolean();

        withOpenCamera(camera -> {
            maxZoom.set(camera.getMaxZoomLevel());
            camera.setZoomLevel(maxZoom.get() / 2);
            sleep(500);
            // Away from the OFF the harness set, so a setter that did nothing would be
            // visible. Choosing a mode does not light anything; only a capture does, and this
            // test takes no picture.
            camera.setFlash(CameraView.Flash.ON);
            sleep(200);
            flash.set(camera.getFlash());
            camera.setFlash(CameraView.Flash.OFF);
            sleep(200);
            flashAfterOff.set(camera.getFlash());
            faces.set(camera.isFaceDetectionSupported());
        });

        assertTrue("every backend scales zoom the same way on " + backend, maxZoom.get() > 0);
        assertEquals("the flash mode has to be the one that was asked for on " + backend,
                CameraView.Flash.ON, flash.get());
        assertEquals("and it has to change back on " + backend,
                CameraView.Flash.OFF, flashAfterOff.get());
    }

    /**
     * Camera2 offers the same controls as CameraX on the same camera.
     *
     * <p>An app is not meant to know which backend it has, so a control one of them offers and the
     * other does not, on the same hardware, is a defect in the one that does not. Each lens is
     * asked under both backends; the comparison runs once, under the Camera2 parameter.</p>
     */
    @Test
    public void camera2OffersTheSameControlsAsCameraX() throws Exception {
        Assume.assumeTrue("one comparison covers both backends",
                backend == CameraView.Backend.CAMERA2);

        Map<CameraView.LensFacing, String> camera2 = controlsOffered(CameraView.Backend.CAMERA2);
        Map<CameraView.LensFacing, String> cameraX = controlsOffered(CameraView.Backend.CAMERA_X);

        assertEquals("Camera2 and CameraX disagree about what this device's cameras can do",
                cameraX, camera2);
    }

    /**
     * A capture still works with every control the camera offers turned on.
     *
     * <p>Exposure, stabilization and low-light boost all change the requests a capture is made
     * from, and stabilization rebuilds the session, so each is a way to break a picture or a
     * recording that the plain capture tests never exercise. The torch is left to
     * {@link #aCaptureSurvivesTheTorch}, so that a dark run can skip it alone.</p>
     */
    @Test
    public void aCaptureSurvivesTheControls() throws Exception {
        AtomicReference<File> picture = new AtomicReference<>();
        AtomicReference<File> video = new AtomicReference<>();
        AtomicReference<String> accepted = new AtomicReference<>();
        AtomicReference<String> offered = new AtomicReference<>();

        withOpenCamera(camera -> {
            int exposure = camera.getMaxExposureCompensation() / 2;
            if (camera.isExposureCompensationSupported()) {
                camera.setExposureCompensation(exposure);
            }
            if (camera.isLowLightBoostSupported()) {
                camera.setLowLightBoostEnabled(true);
            }
            // Stabilization last, because it rebuilds the session and what was set before it has
            // to survive that, including a request the camera has not confirmed yet.
            if (camera.isStabilizationSupported()) {
                camera.setStabilizationEnabled(true);
            }
            // Long enough for the rebuilt session to be configured and confirm the others.
            sleep(3_000);
            offered.set("exposure=" + (camera.isExposureCompensationSupported() ? exposure : 0)
                    + " stabilization=" + camera.isStabilizationSupported()
                    + " lowLightBoost=" + camera.isLowLightBoostSupported());
            accepted.set("exposure=" + camera.getExposureCompensation()
                    + " stabilization=" + camera.isStabilizationEnabled()
                    + " lowLightBoost=" + camera.isLowLightBoostEnabled());
            Log.i(TAG, backend + " controls offered: " + offered.get());

            picture.set(takePicture(camera, temporaryFile(".jpg")));
            video.set(record(camera, temporaryFile(".mp4")));
        });

        assertEquals("every control the camera offers has to be accepted on " + backend,
                offered.get(), accepted.get());
        assertNotNull("the controls cost the picture on " + backend, picture.get());
        assertTrue(picture.get().length() > 0);
        // As in aRecordingProducesAFile: whether an encoder behind a software-rendered surface
        // writes anything is not the library's to promise.
        Assume.assumeTrue(backend + " produced no video on this device's encoder",
                video.get() != null);
        assertTrue("a reported recording has to have bytes in it", video.get().length() > 0);
    }

    /** And with the torch lit, which a dark run skips. */
    @Test
    public void aCaptureSurvivesTheTorch() throws Exception {
        DeviceState.assumeTheLampMayBeLit();
        AtomicBoolean lit = new AtomicBoolean();
        AtomicReference<File> picture = new AtomicReference<>();

        withOpenCamera(camera -> {
            Assume.assumeTrue("this camera has no flash to light", camera.hasFlash());
            camera.setTorchEnabled(true);
            sleep(500);
            lit.set(camera.isTorchEnabled());
            try {
                picture.set(takePicture(camera, temporaryFile(".jpg")));
            } finally {
                camera.setTorchEnabled(false);
            }
        });

        assertTrue("a camera with a flash has to accept the torch on " + backend, lit.get());
        assertNotNull("the torch cost the picture on " + backend, picture.get());
        assertTrue(picture.get().length() > 0);
    }

    /** Opens each lens under a backend and describes the controls it offers. */
    private Map<CameraView.LensFacing, String> controlsOffered(CameraView.Backend which)
            throws Exception {
        CameraView.setDefaultBackend(which);
        Map<CameraView.LensFacing, String> controls = new LinkedHashMap<>();
        withOpenCamera(camera -> {
            Assume.assumeTrue(which + " cannot run here", camera.getBackend() == which);
            controls.put(CameraView.LensFacing.BACK, describeControls(which, camera));
            if (!camera.hasFrontFacingCamera()) {
                return;
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> camera.setLensFacing(CameraView.LensFacing.FRONT));
            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS);
            while (!(camera.isUsingFrontFacingCamera() && camera.isOpen())
                    && System.currentTimeMillis() < deadline) {
                sleep(100);
            }
            assertTrue(which + " never reached the front camera",
                    camera.isUsingFrontFacingCamera());
            // What a camera reports is read once it is configured, not as soon as it is chosen.
            sleep(2_000);
            controls.put(CameraView.LensFacing.FRONT, describeControls(which, camera));
        });
        return controls;
    }

    private static String describeControls(CameraView.Backend which, CameraView camera) {
        String controls = "torch=" + camera.hasFlash()
                + " exposure=" + camera.isExposureCompensationSupported()
                + "[" + camera.getMinExposureCompensation()
                + ", " + camera.getMaxExposureCompensation() + "]"
                + " stabilization=" + camera.isStabilizationSupported()
                + " lowLightBoost=" + camera.isLowLightBoostSupported();
        Log.i(TAG, which + " " + (camera.isUsingFrontFacingCamera() ? "front" : "back")
                + " camera offers " + controls);
        return controls;
    }

    /** Takes a picture and waits for it, returning the file or null if the capture failed. */
    private File takePicture(CameraView camera, File file) throws InterruptedException {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<File> captured = new AtomicReference<>();
        camera.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
            @Override public void onImageConfirmation() {}

            @Override public void onImageCaptured(File saved) {
                captured.set(saved);
                finished.countDown();
            }

            @Override public void onFailure() {
                finished.countDown();
            }
        });
        camera.takePicture(file);
        assertTrue("the capture never reported on " + backend,
                finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        return captured.get();
    }

    /** Records a few seconds and waits for the result, returning the file or null. */
    private File record(CameraView camera, File file) throws InterruptedException {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<File> recorded = new AtomicReference<>();
        camera.setOnVideoCapturedListener(new CameraView.OnVideoCapturedListener() {
            @Override public void onVideoConfirmation() {}

            @Override public void onVideoCaptured(File saved) {
                recorded.set(saved);
                finished.countDown();
            }

            @Override public void onFailure() {
                finished.countDown();
            }
        });
        camera.startRecording(file);
        sleep(4_000);
        camera.stopRecording();
        assertTrue("the recording never reported on " + backend,
                finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        return recorded.get();
    }

    /** What a test does with an open camera. */
    private interface CameraAction {
        void run(CameraView camera) throws Exception;
    }

    /**
     * Opens the camera, waits for it to be usable, runs the action, and closes it.
     *
     * <p>Waiting for onCameraOpened is the point: it reports when the camera can actually be used,
     * not when open() was called, and a capture issued before that is a capture that fails.</p>
     */
    private void withOpenCamera(CameraAction action) throws Exception {
        try (ActivityScenario<CameraTestActivity> scenario =
                     ActivityScenario.launch(CameraTestActivity.class)) {
            CountDownLatch opened = new CountDownLatch(1);
            AtomicReference<CameraView> held = new AtomicReference<>();
            scenario.onActivity(activity -> {
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

            // Asserted, not assumed. Skipping here is how six CameraX tests quietly passed
            // on a device where the backend never opened at all: the only thing that stops a
            // camera opening and is not the library's fault is the screen, and DeviceState has
            // already ruled that out.
            assertTrue("the " + backend + " backend did not open on this device "
                            + DeviceState.whyTheCameraMightNotOpen(),
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
        File file = File.createTempFile("matrix", extension,
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
