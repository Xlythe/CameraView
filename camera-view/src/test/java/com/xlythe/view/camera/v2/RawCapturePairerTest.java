package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.media.FakeImage;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Pairing a RAW frame with the capture result that describes it.
 *
 * <p>The two halves of a DNG arrive on different callbacks in no particular order, and writing
 * one needs both. This is the part of RAW capture with no camera in it, so it is the part that
 * can be asserted: that the match is by sensor timestamp and not by arrival order, that a half
 * whose partner never comes is released rather than held forever, and that neither queue can
 * grow without a bound.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class RawCapturePairerTest {
    /** A frame small enough to build a few thousand of, since nothing here reads its pixels. */
    private static FakeImage frame(long timestamp) {
        return FakeImage.raw(8, 8, timestamp);
    }

    /** The frame first, which is the order a reader usually beats the session in. */
    @Test
    public void aFrameIsPairedWithTheResultThatArrivesAfterIt() {
        RawCapturePairer<String> pairer = new RawCapturePairer<>(2);
        FakeImage image = frame(1_000L);

        assertNull("nothing to pair it with yet", pairer.offerImage(image));
        assertEquals(1, pairer.pendingImageCount());

        RawCapturePairer.Pairing<String> pairing = pairer.offerMetadata("result", 1_000L);

        assertNotNull("the result completes the pair", pairing);
        assertSame(image, pairing.image);
        assertEquals("result", pairing.metadata);
        assertEquals("the pair must not still be held", 0, pairer.pendingImageCount());
        assertEquals(0, pairer.pendingMetadataCount());
        assertFalse("a paired frame belongs to the writer, not the pairer", image.isClosed());
    }

    /** And the other order, which is the one with no ordering guarantee behind it. */
    @Test
    public void aResultIsPairedWithTheFrameThatArrivesAfterIt() {
        RawCapturePairer<String> pairer = new RawCapturePairer<>(2);

        assertNull(pairer.offerMetadata("result", 2_000L));
        assertEquals(1, pairer.pendingMetadataCount());

        FakeImage image = frame(2_000L);
        RawCapturePairer.Pairing<String> pairing = pairer.offerImage(image);

        assertNotNull("the frame completes the pair", pairing);
        assertSame(image, pairing.image);
        assertEquals("result", pairing.metadata);
        assertEquals(0, pairer.pendingMetadataCount());
    }

    /**
     * Two captures in flight are matched by their timestamps rather than by their order.
     *
     * <p>This is the whole point of pairing on the timestamp: the second capture's result can
     * complete before the first capture's frame is delivered, and a queue read in order would
     * hand each frame the other's metadata.</p>
     */
    @Test
    public void capturesAreMatchedByTimestampAndNotByArrivalOrder() {
        RawCapturePairer<String> pairer = new RawCapturePairer<>(2);
        FakeImage first = frame(10L);
        FakeImage second = frame(20L);

        assertNull(pairer.offerImage(first));
        assertNull(pairer.offerImage(second));

        RawCapturePairer.Pairing<String> secondPair = pairer.offerMetadata("second", 20L);
        assertNotNull(secondPair);
        assertSame(second, secondPair.image);
        assertEquals("second", secondPair.metadata);

        RawCapturePairer.Pairing<String> firstPair = pairer.offerMetadata("first", 10L);
        assertNotNull(firstPair);
        assertSame(first, firstPair.image);
        assertEquals("first", firstPair.metadata);
    }

    /** A timestamp that matches nothing pairs with nothing, rather than with whatever is nearest. */
    @Test
    public void aResultForAnotherFrameDoesNotPair() {
        RawCapturePairer<String> pairer = new RawCapturePairer<>(2);
        FakeImage image = frame(100L);
        pairer.offerImage(image);

        assertNull("a near miss is still a miss", pairer.offerMetadata("result", 101L));
        assertEquals(1, pairer.pendingImageCount());
        assertEquals(1, pairer.pendingMetadataCount());
        assertFalse(image.isClosed());
    }

    /**
     * A frame whose result never arrives is closed once the bound is reached.
     *
     * <p>An ImageReader hands out a fixed number of buffers, so a frame held forever is one the
     * next capture cannot have. Dropping the oldest keeps the newest capture working, which is
     * the one somebody is waiting on.</p>
     */
    @Test
    public void anUnmatchedFrameIsReleasedRatherThanHeld() {
        RawCapturePairer<String> pairer = new RawCapturePairer<>(2);
        FakeImage abandoned = frame(1L);
        FakeImage held = frame(2L);
        FakeImage newest = frame(3L);

        pairer.offerImage(abandoned);
        pairer.offerImage(held);
        pairer.offerImage(newest);

        assertTrue("the oldest frame must be closed, not leaked", abandoned.isClosed());
        assertFalse(held.isClosed());
        assertFalse(newest.isClosed());
        assertEquals("never more than the bound", 2, pairer.pendingImageCount());
        assertEquals(1, pairer.droppedImageCount());

        // And the frames that are still held are still pairable.
        RawCapturePairer.Pairing<String> pairing = pairer.offerMetadata("newest", 3L);
        assertNotNull(pairing);
        assertSame(newest, pairing.image);
    }

    /** The same bound on the other side, where there is nothing to release but still a queue. */
    @Test
    public void unmatchedResultsAreBoundedToo() {
        RawCapturePairer<String> pairer = new RawCapturePairer<>(2);

        for (int i = 1; i <= 50; i++) {
            assertNull(pairer.offerMetadata("result-" + i, i));
        }

        assertEquals(2, pairer.pendingMetadataCount());
        assertEquals(48, pairer.droppedMetadataCount());
        assertNull("the dropped results are gone for good", pairer.offerImage(frame(1L)));

        RawCapturePairer.Pairing<String> pairing = pairer.offerImage(frame(50L));
        assertNotNull("the newest result is the one still held", pairing);
        assertEquals("result-50", pairing.metadata);
    }

    /** A pairer holding one of each still pairs, which is the bound a still capture lives at. */
    @Test
    public void aBoundOfOneStillPairs() {
        RawCapturePairer<String> pairer = new RawCapturePairer<>(1);
        FakeImage image = frame(7L);

        assertNull(pairer.offerImage(image));
        RawCapturePairer.Pairing<String> pairing = pairer.offerMetadata("result", 7L);

        assertNotNull(pairing);
        assertSame(image, pairing.image);
    }

    /** A session going away releases both queues, because nothing is coming to claim them. */
    @Test
    public void clearingReleasesEveryHeldFrame() {
        RawCapturePairer<String> pairer = new RawCapturePairer<>(3);
        FakeImage first = frame(1L);
        FakeImage second = frame(2L);
        pairer.offerImage(first);
        pairer.offerImage(second);
        pairer.offerMetadata("orphan", 3L);

        pairer.clear();

        assertTrue(first.isClosed());
        assertTrue(second.isClosed());
        assertEquals(0, pairer.pendingImageCount());
        assertEquals(0, pairer.pendingMetadataCount());
        assertNull("and a frame for a cleared result no longer pairs",
                pairer.offerImage(frame(3L)));
    }

    /** A pairer that can hold nothing would drop every capture, so it is a programming error. */
    @Test
    public void aPairerMustBeAbleToHoldSomething() {
        assertThrows(IllegalArgumentException.class, () -> new RawCapturePairer<String>(0));
    }
}
