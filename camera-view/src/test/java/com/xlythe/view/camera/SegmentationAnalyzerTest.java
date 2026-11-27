package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import android.media.FakeImage;

import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.mlkit.common.sdkinternal.MlKitContext;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.Segmenter;
import com.xlythe.view.camera.gl.SegmentationMask;
import com.xlythe.view.camera.stream.VideoRecorder;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The Camera2 segmenter holds each frame until the model has read it, and reports what the model
 * made of it.
 *
 * <p>The frame stream closes a frame as soon as its analyzer returns. A model still reading it
 * then reads a closed image, which on a device fails every frame with "Image is already closed",
 * so no mask is ever made. The model is stood in for by a task the test completes.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class SegmentationAnalyzerTest {
    /** Long enough that a frame thread still running is plainly waiting, not slow to return. */
    private static final long STILL_WAITING_MILLIS = 300;
    /** Longer than any frame here should take once its task completes. */
    private static final long JOIN_MILLIS = 5_000;

    /** The model's answer for the frame in flight, completed by each test. */
    private TaskCompletionSource<com.google.mlkit.vision.segmentation.SegmentationMask> task;
    /** Released once the model has been handed a frame. */
    private final CountDownLatch handedOver = new CountDownLatch(1);
    /** The masks the analyzer reported, in order. */
    private final List<SegmentationMask> masks = new CopyOnWriteArrayList<>();
    private SegmentationAnalyzer analyzer;

    @Before
    public void setUp() {
        // ML Kit reads its context to make an InputImage. An app gets it from a manifest
        // provider; a unit test brings it up itself.
        MlKitContext.initializeIfNeeded(RuntimeEnvironment.getApplication());
        task = new TaskCompletionSource<>();
        Segmenter segmenter = Mockito.mock(Segmenter.class);
        when(segmenter.process(any(InputImage.class))).thenAnswer(invocation -> {
            handedOver.countDown();
            return task.getTask();
        });
        analyzer = new SegmentationAnalyzer(masks::add, Runnable::run, false, segmenter);
    }

    @Test
    public void aFrameIsHeldUntilTheModelHasReadIt() throws Exception {
        FakeImage image = FakeImage.yuv(4, 2);
        Thread stream = analyze(image);
        assertTrue("the model was never handed the frame",
                handedOver.await(JOIN_MILLIS, TimeUnit.MILLISECONDS));

        stream.join(STILL_WAITING_MILLIS);
        assertTrue("the frame is held while the model reads it", stream.isAlive());
        assertFalse("and is not closed under the model", image.isClosed());

        task.setResult(modelMask(2, 1, 0.25f, 0.75f));
        stream.join(JOIN_MILLIS);
        assertFalse(stream.isAlive());
        assertEquals("the model's mask is reported", 1, masks.size());
        assertEquals(2, masks.get(0).getWidth());
        assertEquals(1, masks.get(0).getHeight());
        assertEquals(0.75f, masks.get(0).confidenceAt(1.5f, 0.5f, 2, 1), 0f);
    }

    @Test
    public void aFailedFrameReportsNothing() throws Exception {
        Thread stream = analyze(FakeImage.yuv(4, 2));

        task.setException(new IllegalStateException("the model gave up"));
        stream.join(JOIN_MILLIS);

        assertFalse(stream.isAlive());
        assertTrue(masks.isEmpty());
    }

    @Test
    public void aClosedAnalyzerReportsNothing() throws Exception {
        Thread stream = analyze(FakeImage.yuv(4, 2));
        analyzer.close();

        task.setResult(modelMask(1, 1, 1f));
        stream.join(JOIN_MILLIS);

        assertFalse(stream.isAlive());
        assertTrue("a mask that arrives after closing is dropped", masks.isEmpty());
    }

    /**
     * Hands the analyzer a frame from a thread of its own and closes it when the analyzer
     * returns, as the frame stream does.
     */
    private Thread analyze(FakeImage image) {
        FrameStream stream = FrameStream.forLibrary(new VideoRecorder.Canvas() {
            @Override public void attachSurface(VideoRecorder.SurfaceProvider provider) {}

            @Override public void detachSurface(VideoRecorder.SurfaceProvider provider) {}
        }, new FrameStream.Params(), analyzer);
        FrameStream.Frame frame = stream.new Frame(image, 90, false);
        Thread thread = new Thread(() -> {
            analyzer.onFrame(frame);
            image.close();
        }, "FrameAnalysis");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** What ML Kit hands back: one float per sample, row major. */
    private static com.google.mlkit.vision.segmentation.SegmentationMask modelMask(
            int width, int height, float... confidence) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(confidence.length * 4)
                .order(ByteOrder.nativeOrder());
        for (float value : confidence) {
            buffer.putFloat(value);
        }
        buffer.rewind();
        com.google.mlkit.vision.segmentation.SegmentationMask mask =
                Mockito.mock(com.google.mlkit.vision.segmentation.SegmentationMask.class);
        when(mask.getBuffer()).thenReturn(buffer);
        when(mask.getWidth()).thenReturn(width);
        when(mask.getHeight()).thenReturn(height);
        return mask;
    }
}
