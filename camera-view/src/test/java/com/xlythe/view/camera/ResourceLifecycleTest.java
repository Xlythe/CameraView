package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.media.FakeImage;
import android.media.ImageReader;
import android.view.Surface;

import com.xlythe.view.camera.shadow.ShadowAvailableTextureView;
import com.xlythe.view.camera.shadow.ShadowInjectableImageReader;
import com.xlythe.view.camera.stream.VideoRecorder;
import com.xlythe.view.camera.v2.FakeCamera2;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What the library holds on to, and for how long.
 *
 * <p>Wall-clock numbers measured off a device say nothing useful about a camera, so these are the
 * two things a unit test can pin that actually go wrong on a phone: resources that are allocated
 * per use and have to be given back - readers, surfaces, threads, players, listeners - and work
 * that has to stay bounded no matter how fast it arrives or how slowly it is consumed. A camera
 * app that fails either one dies of heap or of latency after a few minutes, not in a test.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, shadows = {ShadowInjectableImageReader.class, ShadowAvailableTextureView.class})
public class ResourceLifecycleTest {
    /** How long to wait on a background thread to notice something. */
    private static final long TIMEOUT_SECONDS = 5;

    private Context context;
    private FakeCamera2.TestCameraView cameraView;

    /**
      * How many readers were already open when this test started.
      *
      * <p>Robolectric reuses a sandbox across test classes with the same configuration, so the
      * registry of open readers is not necessarily empty on the way in. What a leak looks like is
      * growth across the work under test, not a count of zero.</p>
      */
    private int readersBefore;

    @Before
    public void setUp() {
        readersBefore = ShadowInjectableImageReader.openReaderCount();
        context = RuntimeEnvironment.getApplication();
        ShadowApplication application = Shadows.shadowOf(
                (android.app.Application) context);
        application.grantPermissions(Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO);
        FakeCamera2.addBackAndFrontCameras(context);
        cameraView = FakeCamera2.laidOutCameraView(context, 1080, 1920);
    }

    @After
    public void tearDown() {
        cameraView.close();
        idle();
        cameraView.release();
    }

    /**
     * Opening and closing repeatedly does not leave readers behind.
     *
     * <p>Each session allocates an ImageReader for the frames it is going to receive, and an
     * ImageReader holds native buffers that the garbage collector cannot reclaim. Leaking one per
     * open is the classic way a camera app runs out of graphics memory after a dozen rotations.
     * </p>
     */
    @Test
    public void openingAndClosingRepeatedlyLeavesNoReadersBehind() {
        for (int round = 0; round < 5; round++) {
            cameraView.open();
            idle();
            cameraView.close();
            idle();
        }

        assertEquals("a reader held per open is a reader held forever, leaked: "
                        + ShadowInjectableImageReader.describeOpenReaders(),
                readersBefore, ShadowInjectableImageReader.openReaderCount());
    }

    /** A frame stream gives its reader back when it is closed. */
    @Test
    public void closingAFrameStreamReleasesItsReader() throws Exception {
        RecordingCanvas canvas = new RecordingCanvas(640, 480);
        FrameStream stream = new FrameStream(
                canvas, null, new FrameStream.Params(), frame -> {});
        stream.start();
        assertTrue(canvas.attached.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        awaitReaders(readersBefore + 1);

        stream.close();

        assertTrue("the reader has to be closed, not just dropped",
                awaitReaders(readersBefore));
        assertTrue("and the backend told it can have the surface back",
                canvas.detached.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    /** And it stops its thread, rather than leaving one spinning for the life of the process. */
    @Test
    public void closingAFrameStreamStopsItsThread() throws Exception {
        RecordingCanvas canvas = new RecordingCanvas(640, 480);
        FrameStream stream = new FrameStream(
                canvas, null, new FrameStream.Params(), frame -> {});
        stream.start();
        assertTrue(canvas.attached.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        stream.close();

        assertFalse(stream.isAnalyzing());
        assertTrue("the pump thread should be gone", awaitNoThreadNamed("CameraView-FrameAnalysis"));
    }

    /**
     * A slow analyzer costs frames, not latency.
     *
     * <p>This is the property that keeps a frame consumer usable: with frames arriving faster than
     * they can be looked at, the queue must not grow. What the analyzer sees is always the newest
     * frame, and everything that piled up behind it is returned to the camera rather than held.
     * </p>
     */
    @Test
    public void aSlowAnalyzerSeesTheNewestFrameAndNotABacklog() throws Exception {
        RecordingCanvas canvas = new RecordingCanvas(320, 240);
        AtomicInteger analyzed = new AtomicInteger();
        CountDownLatch busy = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Long> seen = Collections.synchronizedList(new ArrayList<>());
        FrameStream stream = new FrameStream(canvas, null, new FrameStream.Params(), frame -> {
            seen.add(frame.getImage().getTimestamp());
            if (analyzed.incrementAndGet() == 1) {
                // Hold the pump inside the first frame while the rest pile up behind it.
                busy.countDown();
                try {
                    release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        try {
            stream.start();
            assertTrue(canvas.attached.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            ImageReader reader = requireReader();
            ShadowInjectableImageReader.offerImage(reader, FakeImage.yuv(320, 240));
            assertTrue(busy.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            List<FakeImage> piled = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                FakeImage image = FakeImage.yuv(320, 240);
                piled.add(image);
                ShadowInjectableImageReader.offerImage(reader, image);
            }
            FakeImage newest = FakeImage.yuv(320, 240);
            piled.add(newest);
            ShadowInjectableImageReader.offerImage(reader, newest);
            release.countDown();

            // The next delivery has to be the newest frame, not the oldest of the backlog.
            assertTrue(awaitAtLeast(seen, 2));
            assertEquals("the backlog is skipped, not worked through",
                    newest.getTimestamp(), (long) seen.get(1));
            for (FakeImage image : piled) {
                if (image != newest) {
                    assertTrue("a skipped frame still has to go back to the camera",
                            awaitClosed(image));
                }
            }
        } finally {
            release.countDown();
            stream.close();
        }
    }

    /** Closing the camera takes the analyzer with it, so nothing survives the view. */
    @Test
    public void closingTheCameraStopsFrameAnalysis() throws Exception {
        cameraView.open();
        idle();
        assertTrue("the fake camera should open", cameraView.isOpen());
        cameraView.startFrameAnalysis(frame -> {});
        assertTrue(cameraView.isAnalyzingFrames());

        cameraView.close();
        idle();

        assertFalse("analysis cannot outlive the camera", cameraView.isAnalyzingFrames());
    }

    /**
     * A listener that was removed is not held.
     *
     * <p>A camera view usually outlives the fragment that configured it, so a listener it keeps a
     * strong reference to after being told to let go keeps that fragment, its view tree and
     * whatever they reference alive.</p>
     */
    @Test
    public void aRemovedListenerIsNotRetained() {
        CameraView.OnCameraStateChangedListener listener =
                new CameraView.OnCameraStateChangedListener() {
                    @Override public void onCameraOpened() {}
                    @Override public void onCameraClosed() {}
                };
        WeakReference<CameraView.OnCameraStateChangedListener> reference =
                new WeakReference<>(listener);
        cameraView.setOnCameraStateChangedListener(listener);
        cameraView.setOnCameraStateChangedListener(null);
        listener = null;

        assertTrue("the view should not be holding the old listener", awaitCollected(reference));
    }

    /** The same for a face listener the camera refused or was told to drop. */
    @Test
    public void aRemovedFaceListenerIsNotRetained() {
        // An anonymous class, not a lambda: a lambda that captures nothing is cached by the
        // JVM for the life of the class, so it could never be collected whatever the view does.
        CameraView.FaceDetectionListener listener = new CameraView.FaceDetectionListener() {
            @Override public void onFacesDetected(
                    @androidx.annotation.NonNull java.util.List<Face> faces,
                    @androidx.annotation.NonNull android.graphics.Matrix faceToView) {}
        };
        WeakReference<CameraView.FaceDetectionListener> reference = new WeakReference<>(listener);
        cameraView.setFaceDetectionListener(listener);
        cameraView.setFaceDetectionListener(null);
        listener = null;

        assertTrue(awaitCollected(reference));
    }

    /** Replacing a VideoView's source closes the stream it was reading. */
    @Test
    public void replacingAVideoSourceClosesTheOldOne() throws Exception {
        VideoView videoView = new VideoView(context);
        videoView.layout(0, 0, 640, 480);
        CountingStream first = new CountingStream();
        videoView.setStream(new VideoStream.Builder().withVideoStream(first).build());
        idle();

        videoView.setStream(new VideoStream.Builder()
                .withVideoStream(new CountingStream()).build());
        idle();

        assertTrue("the stream that was replaced has to be closed", first.closed);
    }

    private static void idle() {
        ShadowLooper.shadowMainLooper().idle();
    }

    /** A canvas that asks for a surface the way a backend does, from its own thread. */
    private static final class RecordingCanvas implements VideoRecorder.Canvas {
        private final int mWidth;
        private final int mHeight;
        final CountDownLatch attached = new CountDownLatch(1);
        final CountDownLatch detached = new CountDownLatch(1);

        RecordingCanvas(int width, int height) {
            mWidth = width;
            mHeight = height;
        }

        @Override
        public void attachSurface(VideoRecorder.SurfaceProvider surfaceProvider) {
            Thread camera = new Thread(() -> {
                Surface surface = surfaceProvider.getSurface(mWidth, mHeight, 90, false);
                assertNotNull(surface);
                attached.countDown();
            }, "RecordingCanvas");
            camera.setDaemon(true);
            camera.start();
        }

        @Override
        public void detachSurface(VideoRecorder.SurfaceProvider surfaceProvider) {
            detached.countDown();
        }
    }

    /** An input stream that only remembers whether it was closed. */
    private static final class CountingStream extends java.io.InputStream {
        volatile boolean closed;

        @Override public int read() {
            return -1;
        }

        @Override public void close() {
            closed = true;
        }
    }

    private static ImageReader requireReader() throws InterruptedException {
        for (long deadline = deadline(); System.nanoTime() < deadline; ) {
            ImageReader reader = ShadowInjectableImageReader.latestReader();
            if (reader != null) {
                return reader;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("no ImageReader was created");
    }

    private static boolean awaitReaders(int expected) throws InterruptedException {
        for (long deadline = deadline(); System.nanoTime() < deadline; ) {
            if (ShadowInjectableImageReader.openReaderCount() == expected) {
                return true;
            }
            Thread.sleep(5);
        }
        assertEquals(expected, ShadowInjectableImageReader.openReaderCount());
        return true;
    }

    private static boolean awaitClosed(FakeImage image) throws InterruptedException {
        for (long deadline = deadline(); System.nanoTime() < deadline; ) {
            if (image.isClosed()) {
                return true;
            }
            Thread.sleep(2);
        }
        return false;
    }

    private static boolean awaitAtLeast(List<Long> seen, int count) throws InterruptedException {
        for (long deadline = deadline(); System.nanoTime() < deadline; ) {
            if (seen.size() >= count) {
                return true;
            }
            Thread.sleep(2);
        }
        return false;
    }

    private static boolean awaitNoThreadNamed(String name) throws InterruptedException {
        for (long deadline = deadline(); System.nanoTime() < deadline; ) {
            boolean found = false;
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (name.equals(thread.getName()) && thread.isAlive()) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return true;
            }
            Thread.sleep(5);
        }
        return false;
    }

    /**
     * Whether a reference can be collected.
     *
     * <p>A single collection is not guaranteed to clear a weak reference, so this asks several
     * times before giving up; what it cannot do is prove a leak, only its absence.</p>
     */
    private static boolean awaitCollected(WeakReference<?> reference) {
        for (int attempt = 0; attempt < 20; attempt++) {
            if (reference.get() == null) {
                return true;
            }
            System.gc();
            System.runFinalization();
            try {
                Thread.sleep(10);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return reference.get() == null;
    }

    private static long deadline() {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
    }
}
