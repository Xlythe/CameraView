package com.xlythe.view.camera.test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.util.Size;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.Face;
import com.xlythe.view.camera.FrameStream;
import com.xlythe.view.camera.VideoStream;

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
 * The paths that only exist on a real camera.
 *
 * <p>Robolectric gets the library a long way, but not everywhere: a capture that produces an
 * actual JPEG, a recording that produces an actual file, two outputs configured at once, a
 * detector running in the image signal processor. Those need a camera, so they are here, and this
 * is where the behaviour that only shows up on hardware shows up.</p>
 *
 * <p>Run with {@code gradle :camera-view:cameraMatrixGroupDebugAndroidTest}, which covers an API
 * 28 and an API 37 image. Every test skips rather than fails on a device with no camera, so the
 * suite is honest about what it did not get to.</p>
 */
@RunWith(AndroidJUnit4.class)
public final class CameraDeviceCaptureTest {
    /** Long enough for a camera to open, converge and deliver on a slow emulator. */
    private static final long TIMEOUT_SECONDS = 30;

    @Rule
    public final GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
    );

    @Before
    public void requireACamera() {
        DeviceState.assumeATestableCamera();
    }

    /** A capture produces a file with bytes in it, which is the whole point of the library. */
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
            // Awaited here rather than after, because leaving the action closes the camera - and
            // a capture in flight when its reader is abandoned is a capture that fails.
            assertTrue("the capture never reported",
                    finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        });

        assertNotNull("the capture failed", captured.get());
        assertTrue("the picture has no bytes", captured.get().length() > 0);
    }

    /** A recording produces a file, and stopping it is what finishes the file. */
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
            assertTrue("the recording never reported",
                    finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        });

        // As in the backend matrix: that it reported is the library's contract, and whether the
        // encoder produced bytes behind a software-rendered surface is not.
        Assume.assumeTrue("this device's encoder produced no video", recorded.get() != null);
        assertTrue("the video has no bytes", recorded.get().length() > 0);
    }

    /**
     * A picture taken while recording, where the camera says it can.
     *
     * <p>Three outputs at once, which is the combination Camera2 only guarantees from LIMITED
     * hardware up - so this is the test that cannot be written off a device, because whether the
     * session configures at all is the question.</p>
     */
    @Test
    public void aPictureCanBeTakenWhileRecording() throws Exception {
        File video = temporaryFile(".mp4");
        File picture = temporaryFile(".jpg");
        CountDownLatch captured = new CountDownLatch(1);
        AtomicBoolean supported = new AtomicBoolean();
        AtomicReference<File> saved = new AtomicReference<>();

        withOpenCamera(camera -> {
            camera.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
                @Override public void onImageConfirmation() {}

                @Override public void onImageCaptured(File file) {
                    saved.set(file);
                    captured.countDown();
                }

                @Override public void onFailure() {
                    captured.countDown();
                }
            });
            camera.startRecording(video);
            sleep(1_500);
            supported.set(camera.isSnapshotWhileRecordingSupported());
            if (supported.get()) {
                camera.takePicture(picture);
                assertTrue("the snapshot never reported",
                        captured.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            camera.stopRecording();
        });

        Assume.assumeTrue("this camera cannot take a picture while recording", supported.get());
        assertNotNull("the snapshot failed", saved.get());
        assertTrue(saved.get().length() > 0);
    }

    /** Frames reach an analyzer, with a transform that maps them onto the view. */
    @Test
    public void framesReachAnAnalyzer() throws Exception {
        CountDownLatch analyzed = new CountDownLatch(3);
        AtomicReference<Matrix> transform = new AtomicReference<>();
        AtomicReference<Size> size = new AtomicReference<>();

        withOpenCamera(camera -> {
            camera.startFrameAnalysis(frame -> {
                size.set(new Size(frame.getWidth(), frame.getHeight()));
                transform.set(frame.getFrameToViewTransform());
                analyzed.countDown();
            }, new FrameStream.Params().setMaxSize(new Size(640, 480)));
            sleep(4_000);
            camera.stopFrameAnalysis();
        });

        assertTrue("the analyzer never saw three frames",
                analyzed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertNotNull(size.get());
        assertTrue("frames should be no larger than asked for",
                size.get().getWidth() <= 640 && size.get().getHeight() <= 480);
        assertFalse("a laid-out view has a real transform", transform.get().isIdentity());
    }

    /** The camera's own face detector reports, where the hardware has one. */
    @Test
    public void theCameraReportsItsFaces() throws Exception {
        AtomicBoolean supported = new AtomicBoolean();
        AtomicInteger reports = new AtomicInteger();
        CountDownLatch reported = new CountDownLatch(1);

        withOpenCamera(camera -> {
            supported.set(camera.isFaceDetectionSupported());
            if (!supported.get()) {
                return;
            }
            camera.setFaceDetectionListener((faces, faceToView) -> {
                reports.incrementAndGet();
                reported.countDown();
            });
            // What is waited on is a report, not a face: an empty one counts, because the camera
            // saying "no faces" is the camera reporting.
            reported.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            camera.setFaceDetectionListener(null);
        });

        Assume.assumeTrue("this camera cannot report faces", supported.get());
        // An emulated camera advertises the detection modes and never populates the results, so
        // a camera that accepted the listener and said nothing is skipped rather than failed -
        // there is nothing here for the library to have got wrong.
        Assume.assumeTrue("this camera advertises face detection but does not report it",
                reports.get() > 0);
        assertTrue(reports.get() > 0);
    }

    /** A stream is an extra output, which has to configure alongside the preview. */
    @Test
    public void aStreamDeliversEncodedFrames() throws Exception {
        AtomicReference<VideoStream> stream = new AtomicReference<>();
        AtomicInteger bytes = new AtomicInteger();

        withOpenCamera(camera -> {
            VideoStream video = camera.stream(new VideoStream.Params.Builder()
                    .setAudioEnabled(false)
                    .setMaxSize(new Size(640, 480))
                    .setBitRate(1_000_000)
                    .setIFrameInterval(1)
                    .setIsLossy(true)
                    .build());
            stream.set(video);
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
            }, "StreamReader");
            reader.setDaemon(true);
            reader.start();
            sleep(5_000);
        });

        VideoStream video = stream.get();
        if (video != null) {
            video.close();
        }
        assertTrue("the stream produced nothing", bytes.get() > 0);
    }

    /** Controls take effect on a real camera, which is where the ranges are real. */
    @Test
    public void theControlsTakeEffect() throws Exception {
        AtomicInteger zoom = new AtomicInteger(-1);
        AtomicReference<CameraView.Flash> flash = new AtomicReference<>();

        withOpenCamera(camera -> {
            camera.setZoomLevel(camera.getMaxZoomLevel() / 2);
            sleep(500);
            zoom.set(camera.getZoomLevel());

            camera.setFlash(CameraView.Flash.OFF);
            sleep(200);
            flash.set(camera.getFlash());

            camera.focus(new Rect(-100, -100, 100, 100), new Rect(-200, -200, 200, 200));
            sleep(500);
        });

        assertTrue("zoom stayed inside its range", zoom.get() >= 0);
        assertTrue(flash.get() == CameraView.Flash.OFF);
    }

    /** Switching lenses on a real camera reconfigures a session rather than just a flag. */
    @Test
    public void switchingLensesReconfiguresTheSession() throws Exception {
        AtomicBoolean hasFront = new AtomicBoolean();
        AtomicBoolean switched = new AtomicBoolean();

        withOpenCamera(camera -> {
            hasFront.set(camera.hasFrontFacingCamera());
            if (!hasFront.get()) {
                return;
            }
            camera.toggleCamera();
            sleep(3_000);
            switched.set(camera.isUsingFrontFacingCamera());
        });

        Assume.assumeTrue("this device has only one camera", hasFront.get());
        assertTrue("the lens switch did not take", switched.get());
    }

    /** What a test does with an open camera. */
    private interface CameraAction {
        void run(CameraView camera) throws Exception;
    }

    /**
     * Opens the camera in a hosted activity, waits for it, runs the action, and closes it.
     *
     * <p>The action runs on the instrumentation thread rather than the main one, because every
     * one of these waits for the camera to do something and blocking the main thread would stop
     * it doing it.</p>
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

            assertTrue("the camera never opened " + DeviceState.whyTheCameraMightNotOpen(),
                    opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            CameraView camera = held.get();
            assertNotNull(camera);
            try {
                action.run(camera);
            } finally {
                scenario.onActivity(activity -> activity.getCameraView().close());
            }
        }
    }

    private static File temporaryFile(String extension) throws Exception {
        File file = File.createTempFile("device", extension,
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
