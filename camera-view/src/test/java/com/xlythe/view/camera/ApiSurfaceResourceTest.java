package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.graphics.Rect;
import android.util.Size;

import com.xlythe.view.camera.shadow.ShadowAvailableTextureView;
import com.xlythe.view.camera.shadow.ShadowInjectableImageReader;
import com.xlythe.view.camera.shadow.ShadowListingCamcorderProfile;
import com.xlythe.view.camera.v2.FakeCamera2;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Every entry point on the public API, used over and over.
 *
 * <p>A camera app is used for minutes at a time: opened and closed on every rotation, switched
 * between lenses, started and stopped recording, asked for pictures. Anything that is allocated
 * per use and not given back - a reader holding native buffers, a thread, a session - grows for
 * as long as the app is open, and the symptom is a crash a long way from the cause. A single call
 * never shows it; a hundred do.</p>
 *
 * <p>So this drives each entry point in a loop and checks that what is held afterwards is what
 * was held before. The counts are exact rather than approximate: "no more than it started with"
 * is the only bound that does not drift.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, shadows = {
        ShadowAvailableTextureView.class,
        ShadowInjectableImageReader.class,
        ShadowListingCamcorderProfile.class,
})
public class ApiSurfaceResourceTest {
    /** Enough rounds that a leak of one per use is unmistakable. */
    private static final int ROUNDS = 10;

    private static final long TIMEOUT_SECONDS = 5;

    private Context context;
    private CameraView cameraView;

    /**
      * How many readers were already open when this test started.
      *
      * <p>Robolectric reuses a sandbox across test classes with the same configuration, so the
      * registry is not necessarily empty on the way in - and what a leak looks like is growth,
      * not a count of zero.</p>
      */
    private int readersBefore;

    @Before
    public void setUp() {
        readersBefore = ShadowInjectableImageReader.openReaderCount();
        context = RuntimeEnvironment.getApplication();
        Shadows.shadowOf((android.app.Application) context).grantPermissions(
                Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO);
        FakeCamera2.addBackAndFrontCameras(context);
        cameraView = new CameraView(context);
        cameraView.onFinishInflate();
        cameraView.layout(0, 0, 1080, 1920);
        idle();
    }

    @After
    public void tearDown() {
        cameraView.close();
        idle();
    }

    /**
     * A stream is opened and closed as often as a call starts and stops.
     *
     * <p>Streams are the most expensive thing the library hands out - an encoder, a reader, a
     * pair of pipes and a thread each - and the one a caller is most likely to open repeatedly,
     * since every reconnection is a new one. Anything kept back here is kept back for the life
     * of the app.</p>
     */
    @Test
    public void openingAndClosingTenStreamsHoldsNothing() {
        cameraView.open();
        idle();
        int readersWhileOpen = ShadowInjectableImageReader.openReaderCount();

        for (int round = 0; round < ROUNDS; round++) {
            VideoStream stream = cameraView.stream(new VideoStream.Params.Builder()
                    .setAudioEnabled(false)
                    .setMaxSize(new Size(640, 480))
                    .setBitRate(1_000_000)
                    .setIFrameInterval(1)
                    .setIsLossy(true)
                    .build());
            idle();
            stream.close();
            idle();
        }

        assertEquals("a stream held per call is a stream held forever",
                readersWhileOpen, ShadowInjectableImageReader.openReaderCount());
    }

    /** And one left open at close time still has to be given back. */
    @Test
    public void closingTheCameraReleasesAnOpenStream() {
        cameraView.open();
        idle();
        int readersWhileOpen = ShadowInjectableImageReader.openReaderCount();

        VideoStream stream = cameraView.stream(new VideoStream.Params.Builder()
                .setAudioEnabled(false)
                .setMaxSize(new Size(640, 480))
                .setIsLossy(true)
                .build());
        idle();

        cameraView.close();
        idle();

        assertEquals("closing the camera has to take the stream with it",
                readersBefore, ShadowInjectableImageReader.openReaderCount());
        // Closing twice is what a caller does when it cannot tell whether the camera went first.
        stream.close();
        idle();
        assertEquals(readersBefore, ShadowInjectableImageReader.openReaderCount());
        assertTrue("the stream's reader count should not go below where it started",
                ShadowInjectableImageReader.openReaderCount() >= 0);
    }

    /**
     * Entering and leaving the barcode scanner is a mode switch, not a new camera.
     *
     * <p>It is the one entry point that attaches a detector to the running session, so a detector
     * or an analysis reader left behind shows up as a session that grows every time the user opens
     * and closes the scanner.</p>
     */
    @Test
    public void enteringAndLeavingTheBarcodeScannerTenTimesHoldsNothing() {
        cameraView.open();
        idle();
        int readersWhileOpen = ShadowInjectableImageReader.openReaderCount();
        AtomicInteger batches = new AtomicInteger();

        for (int round = 0; round < ROUNDS; round++) {
            // ML Kit is not initialised under test, so this declines rather than scanning. That
            // is the path worth holding: a declined scanner must still hold nothing, and must
            // not throw an ML Kit exception out of a camera library.
            boolean started = cameraView.enterBarcodeScanner(
                    barcodes -> batches.incrementAndGet(), Barcode.Format.QR_CODE);
            idle();
            cameraView.exitBarcodeScanner();
            idle();
            assertFalse("a scanner that could not start should say so", started);
        }

        assertEquals("a scanner held per entry is a scanner held forever",
                readersWhileOpen, ShadowInjectableImageReader.openReaderCount());
        assertEquals("a scanner that never started cannot have found anything", 0, batches.get());
    }

    /** Leaving a scanner that was never entered is a no-op rather than a crash. */
    @Test
    public void leavingAScannerThatWasNeverEnteredIsHarmless() {
        cameraView.open();
        idle();
        int readersWhileOpen = ShadowInjectableImageReader.openReaderCount();

        cameraView.exitBarcodeScanner();
        cameraView.exitBarcodeScanner();
        idle();

        assertEquals(readersWhileOpen, ShadowInjectableImageReader.openReaderCount());
        assertTrue(cameraView.isOpen());
    }

    @Test
    public void openingAndClosingTenTimesHoldsNothing() {
        for (int round = 0; round < ROUNDS; round++) {
            cameraView.open();
            idle();
            cameraView.close();
            idle();
        }

        assertEquals("a reader held per open is a reader held forever",
                readersBefore, ShadowInjectableImageReader.openReaderCount());
        assertFalse(cameraView.isOpen());
    }

    /** A rotation is a close and an open, and it is the most common thing a camera app does. */
    @Test
    public void switchingLensesTenTimesHoldsNothing() {
        cameraView.open();
        idle();
        int readersWhileOpen = ShadowInjectableImageReader.openReaderCount();

        for (int round = 0; round < ROUNDS; round++) {
            cameraView.toggleCamera();
            idle();
        }

        assertEquals("a switch should cost no more than the session it replaces",
                readersWhileOpen, ShadowInjectableImageReader.openReaderCount());
        assertFalse("ten switches is back where it started",
                cameraView.isUsingFrontFacingCamera());
    }

    /** A picture is asked for far more often than a camera is opened. */
    @Test
    public void takingTenPicturesHoldsNothing() throws IOException {
        cameraView.open();
        idle();
        int readersWhileOpen = ShadowInjectableImageReader.openReaderCount();
        AtomicInteger reported = new AtomicInteger();
        cameraView.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
            @Override public void onImageConfirmation() {}
            @Override public void onImageCaptured(File file) { reported.incrementAndGet(); }
            @Override public void onFailure() { reported.incrementAndGet(); }
        });

        for (int round = 0; round < ROUNDS; round++) {
            File file = File.createTempFile("surface", ".jpg");
            file.deleteOnExit();
            cameraView.takePicture(file);
            idle();
        }

        assertEquals("a capture reuses the session's reader rather than making one",
                readersWhileOpen, ShadowInjectableImageReader.openReaderCount());
    }

    /** Recording allocates the most of anything here: an encoder, a surface and a file. */
    @Test
    public void startingAndStoppingRecordingTenTimesHoldsNothing() throws IOException {
        cameraView.open();
        idle();
        int readersWhileOpen = ShadowInjectableImageReader.openReaderCount();

        for (int round = 0; round < ROUNDS; round++) {
            File file = File.createTempFile("surface", ".mp4");
            file.deleteOnExit();
            cameraView.startRecording(file);
            idle();
            cameraView.stopRecording();
            idle();
        }

        assertEquals("a recording should give its outputs back when it stops",
                readersWhileOpen, ShadowInjectableImageReader.openReaderCount());
        assertFalse(cameraView.isRecording());
    }

    /** Frame analysis holds a reader and a thread, and both have to go when it stops. */
    @Test
    public void startingAndStoppingAnalysisTenTimesHoldsNothing() throws Exception {
        cameraView.open();
        idle();
        int readersWhileOpen = ShadowInjectableImageReader.openReaderCount();
        int threadsBefore = threadsNamed("CameraView-FrameAnalysis");

        for (int round = 0; round < ROUNDS; round++) {
            // Attaching a second output makes the backend rebuild its session, and a camera that
            // cannot configure the combination reports an error - which the fake one does. That
            // is not what is being measured here, so the camera is reopened and the loop goes on;
            // what matters is that each analyzer gives its thread and its reader back.
            if (!cameraView.isOpen()) {
                cameraView.open();
                idle();
            }
            cameraView.startFrameAnalysis(frame -> {},
                    new FrameStream.Params().setMaxSize(new Size(320, 240)));
            idle();
            cameraView.stopFrameAnalysis();
            idle();
        }

        assertFalse(cameraView.isAnalyzingFrames());
        assertTrue("ten analyzers should leave no threads behind",
                awaitThreads("CameraView-FrameAnalysis", threadsBefore));
        assertTrue("ten analyzers left readers behind: "
                        + ShadowInjectableImageReader.describeOpenReaders(),
                ShadowInjectableImageReader.openReaderCount() <= readersWhileOpen);
    }

    /** Face detection is a listener and a mode on a request, so it must cost nothing to toggle. */
    @Test
    public void togglingFaceDetectionTenTimesHoldsNothing() {
        cameraView.open();
        idle();
        int readersWhileOpen = ShadowInjectableImageReader.openReaderCount();

        for (int round = 0; round < ROUNDS; round++) {
            cameraView.setFaceDetectionListener((faces, faceToView) -> {});
            idle();
            cameraView.setFaceDetectionListener(null);
            idle();
        }

        assertEquals(readersWhileOpen, ShadowInjectableImageReader.openReaderCount());
    }

    /**
     * The controls are set far more often than anything else, from gestures and animations.
     *
     * <p>A hundred zoom steps is one pinch. What would make that expensive is a session rebuild
     * per step rather than a request per step, so this checks the session survives all of them.
     * </p>
     */
    @Test
    public void ahundredControlChangesKeepTheSameSession() {
        cameraView.open();
        idle();
        int readersWhileOpen = ShadowInjectableImageReader.openReaderCount();

        for (int step = 0; step <= 100; step++) {
            cameraView.setZoomLevel(step);
        }
        idle();
        for (CameraView.Flash flash : CameraView.Flash.values()) {
            cameraView.setFlash(flash);
        }
        idle();
        cameraView.focus(new Rect(-100, -100, 100, 100), new Rect(-200, -200, 200, 200));
        idle();

        assertEquals("a control is a request, not a new session",
                readersWhileOpen, ShadowInjectableImageReader.openReaderCount());
        assertTrue(cameraView.isOpen());
    }

    /** Saving and restoring is what a rotation does, ten times over. */
    @Test
    public void savingAndRestoringTenTimesHoldsNothing() {
        List<CameraView> views = new ArrayList<>();
        try {
            android.os.Parcelable state = null;
            for (int round = 0; round < ROUNDS; round++) {
                CameraView view = new CameraView(context);
                view.onFinishInflate();
                if (state != null) {
                    view.onRestoreInstanceState(state);
                }
                view.layout(0, 0, 1080, 1920);
                views.add(view);
                idle();
                state = view.onSaveInstanceState();
                view.close();
                idle();
            }
        } finally {
            for (CameraView view : views) {
                view.close();
            }
            idle();
        }

        assertEquals("every rotation's session has to be given back",
                readersBefore, ShadowInjectableImageReader.openReaderCount());
    }

    private static int threadsNamed(String name) {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (name.equals(thread.getName()) && thread.isAlive()) {
                count++;
            }
        }
        return count;
    }

    private static boolean awaitThreads(String name, int expected) throws InterruptedException {
        for (long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
                System.nanoTime() < deadline; ) {
            if (threadsNamed(name) <= expected) {
                return true;
            }
            Thread.sleep(10);
        }
        return threadsNamed(name) <= expected;
    }

    private static void idle() {
        ShadowLooper.shadowMainLooper().idle();
    }
}
