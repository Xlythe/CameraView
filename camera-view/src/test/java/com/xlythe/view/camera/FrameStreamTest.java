package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.media.FakeImage;
import android.media.ImageReader;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.Nullable;

import com.xlythe.view.camera.shadow.ShadowInjectableImageReader;
import com.xlythe.view.camera.stream.VideoRecorder;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The frame pump behind {@link CameraView#startFrameAnalysis}.
 *
 * <p>Driven through the same {@link VideoRecorder.Canvas} contract the backends implement, with a
 * fake standing in for the camera: it asks for a surface the way a backend does, from a thread of
 * its own, and the frames are then handed to the reader directly.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, shadows = {ShadowInjectableImageReader.class})
public class FrameStreamTest {
    /** How long a test waits for the pump thread to get somewhere. */
    private static final long TIMEOUT_SECONDS = 5;

    private final List<FrameStream> mStreams = Collections.synchronizedList(new ArrayList<>());

    @After
    public void tearDown() {
        for (FrameStream stream : new ArrayList<>(mStreams)) {
            stream.close();
        }
    }

    /**
     * A backend that reports frames of a fixed shape, asking for the surface off-thread.
     *
     * <p>Off-thread matters: the provider blocks until the stream hands back a surface, and the
     * stream is the one calling attachSurface, so a backend that asked on the caller's thread
     * would deadlock. Real backends ask from their camera thread.</p>
     */
    private static final class FakeCanvas implements VideoRecorder.Canvas {
        private final int mWidth;
        private final int mHeight;
        private final int mOrientation;
        private final boolean mFlipped;

        final CountDownLatch attached = new CountDownLatch(1);
        final CountDownLatch detached = new CountDownLatch(1);
        final AtomicReference<Size> requestedMaxSize = new AtomicReference<>();

        FakeCanvas(int width, int height, int orientation, boolean flipped) {
            mWidth = width;
            mHeight = height;
            mOrientation = orientation;
            mFlipped = flipped;
        }

        @Override
        public void attachSurface(VideoRecorder.SurfaceProvider surfaceProvider) {
            requestedMaxSize.set(surfaceProvider.getMaxSize());
            Thread camera = new Thread(() -> {
                Surface surface = surfaceProvider.getSurface(
                        mWidth, mHeight, mOrientation, mFlipped);
                assertNotNull("the stream has to supply a surface", surface);
                attached.countDown();
            }, "FakeCanvas");
            camera.setDaemon(true);
            camera.start();
        }

        @Override
        public void detachSurface(VideoRecorder.SurfaceProvider surfaceProvider) {
            detached.countDown();
        }
    }

    @Test
    public void framesCarryTheirSizeRotationAndLens() throws Exception {
        FakeCanvas canvas = new FakeCanvas(640, 480, 90, true);
        List<FrameStream.Frame> seen = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch analyzed = new CountDownLatch(1);
        FrameStream stream = start(canvas, frame -> {
            seen.add(frame);
            analyzed.countDown();
        }, new FrameStream.Params());

        ImageReader reader = awaitReader(canvas);
        ShadowInjectableImageReader.offerImage(reader, FakeImage.yuv(640, 480));

        assertTrue("expected the analyzer to be called",
                analyzed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        FrameStream.Frame frame = seen.get(0);
        assertEquals(640, frame.getWidth());
        assertEquals(480, frame.getHeight());
        assertEquals("the sensor rotation the backend reported", 90, frame.getRotationDegrees());
        assertTrue("a front camera's frames are mirrored", frame.isMirrored());
        assertEquals("upright the frame is portrait",
                new Size(480, 640), frame.getUprightSize());
        assertTrue(stream.isAnalyzing());
    }

    /** The image belongs to the camera, so it is recycled as soon as the analyzer returns. */
    @Test
    public void theImageIsReleasedAfterEachFrame() throws Exception {
        FakeCanvas canvas = new FakeCanvas(320, 240, 0, false);
        AtomicReference<FakeImage> analyzed = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        start(canvas, frame -> {
            analyzed.set((FakeImage) frame.getImage());
            assertFalse("the image is live inside onFrame",
                    ((FakeImage) frame.getImage()).isClosed());
            done.countDown();
        }, new FrameStream.Params());

        ImageReader reader = awaitReader(canvas);
        FakeImage image = FakeImage.yuv(320, 240);
        ShadowInjectableImageReader.offerImage(reader, image);

        assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        // The pump closes it right after onFrame returns, so give it a moment to come back round.
        assertTrue("the image has to be released back to the camera",
                awaitClosed(analyzed.get()));
    }

    /** An analyzer that throws costs its frame, not the stream. */
    @Test
    public void anAnalyzerThatThrowsDoesNotStopTheStream() throws Exception {
        FakeCanvas canvas = new FakeCanvas(320, 240, 0, false);
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch second = new CountDownLatch(2);
        FrameStream stream = start(canvas, frame -> {
            first.countDown();
            second.countDown();
            throw new IllegalStateException("analysis blew up");
        }, new FrameStream.Params());

        ImageReader reader = awaitReader(canvas);
        // One frame at a time: the pump keeps only the newest, so two offered together are one
        // delivery and one drop.
        ShadowInjectableImageReader.offerImage(reader, FakeImage.yuv(320, 240));
        assertTrue(first.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        ShadowInjectableImageReader.offerImage(reader, FakeImage.yuv(320, 240));

        assertTrue("the second frame still arrives",
                second.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(stream.isAnalyzing());
    }

    /** Only the newest frame is kept, so a slow analyzer drops frames instead of lagging. */
    @Test
    public void onlyTheNewestFrameIsDelivered() throws Exception {
        FakeCanvas canvas = new FakeCanvas(320, 240, 0, false);
        List<Long> timestamps = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);
        start(canvas, frame -> {
            timestamps.add(frame.getImage().getTimestamp());
            done.countDown();
        }, new FrameStream.Params());

        ImageReader reader = awaitReader(canvas);
        FakeImage stale = FakeImage.yuv(320, 240);
        FakeImage newest = FakeImage.yuv(320, 240);
        ShadowInjectableImageReader.offerImage(reader, stale);
        ShadowInjectableImageReader.offerImage(reader, newest);

        assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals("the newest frame is the one analyzed",
                newest.getTimestamp(), (long) timestamps.get(0));
        assertTrue("and the stale one is returned to the camera", awaitClosed(stale));
    }

    /** The cap an analyzer asks for is the one the backend is given. */
    @Test
    public void theRequestedMaxSizeReachesTheBackend() throws Exception {
        FakeCanvas canvas = new FakeCanvas(640, 480, 0, false);
        start(canvas, frame -> {}, new FrameStream.Params().setMaxSize(new Size(320, 240)));

        canvas.attached.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(new Size(320, 240), canvas.requestedMaxSize.get());
    }

    @Test
    public void closingGivesTheSurfaceBack() throws Exception {
        FakeCanvas canvas = new FakeCanvas(640, 480, 0, false);
        FrameStream stream = start(canvas, frame -> {}, new FrameStream.Params());
        awaitReader(canvas);

        stream.close();

        assertTrue("the backend has to be told the surface is going",
                canvas.detached.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertFalse(stream.isAnalyzing());
    }

    @Test
    public void startingTwiceIsIgnored() throws Exception {
        FakeCanvas canvas = new FakeCanvas(640, 480, 0, false);
        FrameStream stream = start(canvas, frame -> {}, new FrameStream.Params());
        awaitReader(canvas);

        stream.start();

        // Still running, and the surface it already has was never handed back.
        assertTrue(stream.isAnalyzing());
        assertEquals("a second start must not detach anything",
                1, canvas.detached.getCount());
    }

    /** Without a view there is nothing to map onto, which is an answer rather than a crash. */
    @Test
    public void aStreamWithNoViewHasNoTransform() throws Exception {
        FakeCanvas canvas = new FakeCanvas(640, 480, 90, false);
        AtomicReference<Matrix> transform = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        start(canvas, frame -> {
            transform.set(frame.getFrameToViewTransform());
            done.countDown();
        }, new FrameStream.Params());

        ShadowInjectableImageReader.offerImage(awaitReader(canvas), FakeImage.yuv(640, 480));

        assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(transform.get().isIdentity());
    }

    @Test
    public void paramsReportWhatWasAskedFor() {
        FrameStream.Params params = new FrameStream.Params();
        assertEquals("analysis follows the preview by default", null, params.getMaxSize());
        assertEquals(ImageFormat.YUV_420_888, params.getImageFormat());

        params.setMaxSize(new Size(320, 240)).setImageFormat(ImageFormat.PRIVATE);
        assertEquals(new Size(320, 240), params.getMaxSize());
        assertEquals(ImageFormat.PRIVATE, params.getImageFormat());
    }

    private FrameStream start(
            FakeCanvas canvas, FrameStream.Analyzer analyzer, FrameStream.Params params) {
        FrameStream stream = new FrameStream(canvas, null, params, analyzer);
        mStreams.add(stream);
        stream.start();
        return stream;
    }

    /** Waits for the pump to create the reader it polls, which it does off the main thread. */
    private static ImageReader awaitReader(FakeCanvas canvas) throws InterruptedException {
        assertTrue("the stream never attached a surface",
                canvas.attached.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        ImageReader reader = awaitNonNull();
        assertNotNull("the stream never created an ImageReader", reader);
        return reader;
    }

    @Nullable
    private static ImageReader awaitNonNull() throws InterruptedException {
        for (long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
                System.nanoTime() < deadline; ) {
            ImageReader reader = ShadowInjectableImageReader.latestReader();
            if (reader != null) {
                return reader;
            }
            Thread.sleep(5);
        }
        return null;
    }

    private static boolean awaitClosed(FakeImage image) throws InterruptedException {
        for (long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
                System.nanoTime() < deadline; ) {
            if (image.isClosed()) {
                return true;
            }
            Thread.sleep(5);
        }
        return false;
    }
}
