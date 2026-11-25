package com.xlythe.view.camera.ar;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Reading metres out of a depth image, which is the testable half of occlusion.
 *
 * <p>The frame these samples come from cannot be made off a device, but the buffer inside it is
 * ordinary bytes, and every way of misreading it is here: the stride that is not the width, the
 * zero that means "no reading" rather than "at the lens", the point outside the image, and the
 * scaling from millimetres.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class DepthLookupTest {

    /** Millimetres in, metres out. */
    @Test
    public void aReadingComesBackInMetres() {
        DepthLookup lookup = given(4, 4, millimetres(4, 4, 2500));

        assertEquals(2.5f, lookup.metersAt(50, 50), 0.001f);
    }

    /**
     * Zero is "nothing here", not "against the lens".
     *
     * <p>The distinction matters because an occlusion test asks whether the scene is nearer
     * than the thing being drawn. Treating an absent reading as zero metres puts the whole
     * world in front of everything, and the pillar is never visible anywhere.</p>
     */
    @Test
    public void noReadingIsNotTheSameAsZeroDistance() {
        DepthLookup lookup = given(4, 4, millimetres(4, 4, 0));

        assertEquals("absent, so nothing is known about this point",
                0f, lookup.metersAt(50, 50), 0.0f);
    }

    /** Rows are not always packed: the stride is what gets you to the next one. */
    @Test
    public void theStrideIsHonoured() {
        int width = 2;
        int height = 2;
        int stride = 8;  // four bytes of padding per row
        ByteBuffer buffer = ByteBuffer.allocate(stride * height).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort(0, (short) 1000);               // row 0, sample 0
        buffer.putShort(2, (short) 2000);               // row 0, sample 1
        buffer.putShort(stride, (short) 3000);          // row 1, sample 0
        buffer.putShort(stride + 2, (short) 4000);      // row 1, sample 1

        DepthLookup lookup = new DepthLookup(buffer, width, height, stride, 100, 100);

        assertEquals("top left", 1.0f, lookup.metersAt(10, 10), 0.001f);
        assertEquals("top right", 2.0f, lookup.metersAt(90, 10), 0.001f);
        assertEquals("bottom left, which the stride decides", 3.0f,
                lookup.metersAt(10, 90), 0.001f);
        assertEquals("bottom right", 4.0f, lookup.metersAt(90, 90), 0.001f);
    }

    /** A point outside the view has no reading rather than the nearest one. */
    @Test
    public void somethingOffTheEdgeHasNoReading() {
        DepthLookup lookup = given(4, 4, millimetres(4, 4, 1500));

        assertEquals(0f, lookup.metersAt(-1, 50), 0.0f);
        assertEquals(0f, lookup.metersAt(50, -1), 0.0f);
        assertEquals(0f, lookup.metersAt(100, 50), 0.0f);
        assertEquals(0f, lookup.metersAt(50, 100), 0.0f);
    }

    /** The far edge of the view lands on the last sample rather than past it. */
    @Test
    public void theFarEdgeLandsInsideTheImage() {
        assertEquals("the last column", 3, DepthLookup.sampleFor(99.9f, 100, 4));
        assertEquals("the first", 0, DepthLookup.sampleFor(0, 100, 4));
        assertEquals("outside is outside", -1, DepthLookup.sampleFor(100, 100, 4));
        assertEquals(-1, DepthLookup.sampleFor(-0.1f, 100, 4));
    }

    /** A sample covers a span of the view, and every point in it reads the same. */
    @Test
    public void aSampleCoversItsWholeSpan() {
        assertEquals(0, DepthLookup.sampleFor(0, 100, 4));
        assertEquals(0, DepthLookup.sampleFor(24.9f, 100, 4));
        assertEquals("and the next span is the next sample", 1,
                DepthLookup.sampleFor(25, 100, 4));
    }

    /** Readings above the signed range still read as distances, not as negatives. */
    @Test
    public void aReadingBeyondThirtyTwoMetresIsStillPositive() {
        DepthLookup lookup = given(2, 2, millimetres(2, 2, 60000));

        assertEquals("sixteen bits are unsigned", 60.0f, lookup.metersAt(50, 50), 0.001f);
    }

    private static DepthLookup given(int width, int height, ByteBuffer buffer) {
        return new DepthLookup(buffer, width, height, width * 2, 100, 100);
    }

    private static ByteBuffer millimetres(int width, int height, int value) {
        ByteBuffer buffer = ByteBuffer.allocate(width * height * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < width * height; i++) {
            buffer.putShort(i * 2, (short) value);
        }
        return buffer;
    }
}
