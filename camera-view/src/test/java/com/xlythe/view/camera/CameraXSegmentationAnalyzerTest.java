package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.media.FakeImage;

import androidx.camera.core.ImageInfo;
import androidx.camera.core.ImageProxy;

import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.Segmenter;
import com.xlythe.view.camera.gl.SegmentationMask;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The CameraX segmenter reads each mask the way round its frame's lens is shown.
 *
 * <p>The module tells it before every bind, since a lens switch rebinds with the same analyzer.
 * An analyzer that fixed its mirroring when it started kept the first lens's answer, and drew a
 * front camera's mask on the wrong side of its subject after a switch from the back.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class CameraXSegmentationAnalyzerTest {
    /** The model's answer for the frame in flight, replaced for each frame. */
    private TaskCompletionSource<com.google.mlkit.vision.segmentation.SegmentationMask> task;
    /** The masks the analyzer reported, in order. */
    private final List<SegmentationMask> masks = new CopyOnWriteArrayList<>();
    private CameraXSegmentationAnalyzer analyzer;

    @Before
    public void setUp() {
        // ML Kit reads its context to make an InputImage.
        MlKitForTests.bringUp();
        Segmenter segmenter = Mockito.mock(Segmenter.class);
        when(segmenter.process(any(InputImage.class))).thenAnswer(invocation -> {
            task = new TaskCompletionSource<>();
            return task.getTask();
        });
        analyzer = new CameraXSegmentationAnalyzer(masks::add, Runnable::run, segmenter);
    }

    @After
    public void tearDown() {
        analyzer.close();
        MlKitForTests.takeDown();
    }

    @Test
    public void aMaskFollowsTheLensItWasToldAbout() {
        analyzer.setMirrored(false);
        analyze(frame());
        analyzer.setMirrored(true);
        analyze(frame());

        assertEquals(2, masks.size());
        assertEquals("the back camera's mask is read as it is",
                0.25f, masks.get(0).confidenceAt(0.5f, 0.5f, 2, 1), 0f);
        assertEquals("the front camera's mask is read mirrored",
                0.75f, masks.get(1).confidenceAt(0.5f, 0.5f, 2, 1), 0f);
    }

    /** A frame keeps the lens it arrived from, though the next lens is announced before it is done. */
    @Test
    public void aFrameInFlightKeepsItsOwnLens() {
        analyzer.setMirrored(true);
        ImageProxy frame = frame();
        analyzer.analyze(frame);

        analyzer.setMirrored(false);
        task.setResult(modelMask(0.25f, 0.75f));
        ShadowLooper.idleMainLooper();

        assertEquals(1, masks.size());
        assertEquals(0.75f, masks.get(0).confidenceAt(0.5f, 0.5f, 2, 1), 0f);
        verify(frame).close();
    }

    @Test
    public void aClosedAnalyzerReportsNothingAndGivesTheFrameBack() {
        ImageProxy frame = frame();
        analyzer.analyze(frame);
        analyzer.close();

        task.setResult(modelMask(1f, 1f));
        ShadowLooper.idleMainLooper();

        assertTrue(masks.isEmpty());
        verify(frame).close();
    }

    /** Hands the analyzer a frame and the model's mask for it. */
    private void analyze(ImageProxy frame) {
        analyzer.analyze(frame);
        task.setResult(modelMask(0.25f, 0.75f));
        ShadowLooper.idleMainLooper();
    }

    /** A camera frame as CameraX hands it over, upright as it is. */
    private static ImageProxy frame() {
        ImageInfo info = Mockito.mock(ImageInfo.class);
        when(info.getRotationDegrees()).thenReturn(0);
        ImageProxy proxy = Mockito.mock(ImageProxy.class);
        when(proxy.getImage()).thenReturn(FakeImage.yuv(4, 2));
        when(proxy.getImageInfo()).thenReturn(info);
        return proxy;
    }

    /** What ML Kit hands back: a two by one mask, row major. */
    private static com.google.mlkit.vision.segmentation.SegmentationMask modelMask(
            float left, float right) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder());
        buffer.putFloat(left).putFloat(right).rewind();
        com.google.mlkit.vision.segmentation.SegmentationMask mask =
                Mockito.mock(com.google.mlkit.vision.segmentation.SegmentationMask.class);
        when(mask.getBuffer()).thenReturn(buffer);
        when(mask.getWidth()).thenReturn(2);
        when(mask.getHeight()).thenReturn(1);
        return mask;
    }
}
