package com.xlythe.view.camera.gl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Reading a segmentation mask, which is the testable half of replacing a background.
 *
 * <p>The model that produces one cannot be run here, but a mask is a grid of floats and every
 * way of misreading it can be: the point outside it, the front camera whose frame was mirrored
 * before the model saw it, a confidence a model returned slightly outside the range it
 * promised, and the fact that a mask is far coarser than the view it describes.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class SegmentationMaskTest {

    /** One is subject, nought is background, which is the convention this assumes. */
    @Test
    public void confidenceComesBackAsGiven() {
        SegmentationMask mask = given(2, 2, false, 1f, 0f, 0.25f, 0.75f);

        assertEquals(1f, mask.confidenceAt(10, 10, 100, 100), 0.001f);
        assertEquals(0f, mask.confidenceAt(90, 10, 100, 100), 0.001f);
        assertEquals(0.25f, mask.confidenceAt(10, 90, 100, 100), 0.001f);
        assertEquals(0.75f, mask.confidenceAt(90, 90, 100, 100), 0.001f);
    }

    /**
     * A front camera's frame is mirrored before the model sees it, so the mask is too.
     *
     * <p>Getting this wrong replaces the background on the wrong side of the picture, which
     * looks like the segmentation failing rather than like a coordinate being flipped.</p>
     */
    @Test
    public void aMirroredMaskIsReadBackTheRightWayRound() {
        SegmentationMask mask = given(2, 1, true, 1f, 0f);

        assertEquals("the left of the view is the right of a mirrored mask",
                0f, mask.confidenceAt(10, 10, 100, 100), 0.001f);
        assertEquals(1f, mask.confidenceAt(90, 10, 100, 100), 0.001f);
    }

    /** A point outside the view is background rather than the nearest sample. */
    @Test
    public void somethingOffTheEdgeIsBackground() {
        SegmentationMask mask = given(2, 2, false, 1f, 1f, 1f, 1f);

        assertEquals(0f, mask.confidenceAt(-1, 10, 100, 100), 0.0f);
        assertEquals(0f, mask.confidenceAt(10, -1, 100, 100), 0.0f);
        assertEquals(0f, mask.confidenceAt(100, 10, 100, 100), 0.0f);
        assertEquals(0f, mask.confidenceAt(10, 100, 100, 100), 0.0f);
    }

    /** A model that overshoots the range it promised is clamped rather than trusted. */
    @Test
    public void aConfidenceOutsideTheRangeIsClamped() {
        SegmentationMask mask = given(2, 1, false, 1.4f, -0.3f);

        assertEquals(1f, mask.confidenceAt(10, 10, 100, 100), 0.001f);
        assertEquals(0f, mask.confidenceAt(90, 10, 100, 100), 0.001f);
    }

    /** The hard edge, for callers who want one rather than a blend. */
    @Test
    public void theSubjectTestSplitsAtAHalf() {
        SegmentationMask mask = given(2, 1, false, 0.49f, 0.51f);

        assertFalse(mask.isSubjectAt(10, 10, 100, 100));
        assertTrue(mask.isSubjectAt(90, 10, 100, 100));
    }

    /** A mask is coarser than the view, and a sample covers its whole span. */
    @Test
    public void oneSampleCoversManyPixels() {
        SegmentationMask mask = given(2, 1, false, 1f, 0f);

        assertEquals("everything in the left half", 1f,
                mask.confidenceAt(0, 0, 100, 100), 0.001f);
        assertEquals(1f, mask.confidenceAt(49, 0, 100, 100), 0.001f);
        assertEquals("and everything in the right", 0f,
                mask.confidenceAt(50, 0, 100, 100), 0.001f);
    }

    /** Eight bits per sample is what goes to a texture, and the ends must survive. */
    @Test
    public void alphaKeepsBothEnds() {
        byte[] alpha = given(3, 1, false, 0f, 0.5f, 1f).toAlpha();

        assertEquals(3, alpha.length);
        assertEquals(0, alpha[0] & 0xFF);
        assertEquals(128, alpha[1] & 0xFF);
        assertEquals("fully the subject has to reach the top of the range",
                255, alpha[2] & 0xFF);
    }

    private static SegmentationMask given(int width, int height, boolean mirrored,
            float... confidences) {
        ByteBuffer buffer = ByteBuffer.allocate(confidences.length * 4)
                .order(ByteOrder.nativeOrder());
        for (float confidence : confidences) {
            buffer.putFloat(confidence);
        }
        buffer.rewind();
        return new SegmentationMask(buffer, width, height, mirrored);
    }
}
