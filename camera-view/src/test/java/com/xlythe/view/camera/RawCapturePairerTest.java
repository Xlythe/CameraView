package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.hardware.camera2.DngCreator;
import android.media.FakeImage;

import androidx.annotation.NonNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Pairing a RAW frame with the capture result that describes it.
 *
 * <p>The two halves of a DNG arrive on different callbacks in no particular order. What can go
 * wrong is pairing by arrival rather than by timestamp, holding a half whose partner never comes,
 * and letting either queue grow.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class RawCapturePairerTest {
    /** A frame small enough to build thousands of, since nothing here reads its pixels. */
    private static final class TestFrame implements RawCapturePairer.Frame {
        private final FakeImage image;

        TestFrame(long timestamp) {
            image = FakeImage.raw(8, 8, timestamp);
        }

        @Override
        public void writeTo(@NonNull DngCreator creator, @NonNull OutputStream output)
                throws IOException {
            creator.writeImage(output, image);
        }

        @Override
        public long getTimestamp() {
            return image.getTimestamp();
        }

        @Override
        public void close() {
            image.close();
        }

        boolean isClosed() {
            return image.isClosed();
        }
    }

    /** The frame first, which is the order a reader usually beats the session in. */
    @Test
    public void aFrameIsPairedWithTheResultThatArrivesAfterIt() {
        RawCapturePairer<String> pairer = new RawCapturePairer<>(2);
        TestFrame frame = new TestFrame(1_000L);

        assertNull("nothing to pair it with yet", pairer.offerFrame(frame));
        assertEquals(1, pairer.pendingFrameCount());

        RawCapturePairer.Pairing<String> pairing = pairer.offerMetadata("result", 1_000L);

        assertNotNull(pairing);
        assertSame(frame, pairing.frame);
        assertEquals("result", pairing.metadata);
        assertEquals(0, pairer.pendingFrameCount());
        assertEquals(0, pairer.pendingMetadataCount());
        assertFalse("a paired frame belongs to the writer, not the pairer", frame.isClosed());
    }

    /** And the other order, which nothing rules out. */
    @Test
    public void aResultIsPairedWithTheFrameThatArrivesAfterIt() {
        RawCapturePairer<String> pairer = new RawCapturePairer<>(2);

        assertNull(pairer.offerMetadata("result", 2_000L));
        assertEquals(1, pairer.pendingMetadataCount());

        TestFrame frame = new TestFrame(2_000L);
        RawCapturePairer.Pairing<String> pairing = pairer.offerFrame(frame);

        assertNotNull(pairing);
        assertSame(frame, pairing.frame);
        assertEquals("result", pairing.metadata);
        assertEquals(0, pairer.pendingMetadataCount());
    }

    /**
     * Two captures in flight are matched by timestamp, not order: the second capture's result
     * can arrive before the first one's, and a queue read in order would swap their metadata.
     */
    @Test
    public void capturesAreMatchedByTimestampAndNotByArrivalOrder() {
        RawCapturePairer<String> pairer = new RawCapturePairer<>(2);
        TestFrame first = new TestFrame(10L);
        TestFrame second = new TestFrame(20L);
        pairer.offerFrame(first);
        pairer.offerFrame(second);

        RawCapturePairer.Pairing<String> secondPair = pairer.offerMetadata("second", 20L);
        RawCapturePairer.Pairing<String> firstPair = pairer.offerMetadata("first", 10L);

        assertNotNull(secondPair);
        assertSame(second, secondPair.frame);
        assertEquals("second", secondPair.metadata);
        assertNotNull(firstPair);
        assertSame(first, firstPair.frame);
        assertEquals("first", firstPair.metadata);
    }

    /** A timestamp that matches nothing pairs with nothing, rather than with the nearest. */
    @Test
    public void aResultForAnotherFrameDoesNotPair() {
        RawCapturePairer<String> pairer = new RawCapturePairer<>(2);
        TestFrame frame = new TestFrame(100L);
        pairer.offerFrame(frame);

        assertNull("a near miss is still a miss", pairer.offerMetadata("result", 101L));
        assertEquals(1, pairer.pendingFrameCount());
        assertEquals(1, pairer.pendingMetadataCount());
        assertFalse(frame.isClosed());
    }

    /** Past the bound the oldest frame is closed, so the reader can hand out the next one. */
    @Test
    public void anUnmatchedFrameIsClosedRatherThanHeld() {
        RawCapturePairer<String> pairer = new RawCapturePairer<>(2);
        TestFrame abandoned = new TestFrame(1L);
        TestFrame held = new TestFrame(2L);
        TestFrame newest = new TestFrame(3L);

        pairer.offerFrame(abandoned);
        pairer.offerFrame(held);
        pairer.offerFrame(newest);

        assertTrue("the oldest frame must be closed, not leaked", abandoned.isClosed());
        assertFalse(held.isClosed());
        assertFalse(newest.isClosed());
        assertEquals(2, pairer.pendingFrameCount());
        assertEquals(1, pairer.droppedFrameCount());
        RawCapturePairer.Pairing<String> pairing = pairer.offerMetadata("newest", 3L);
        assertNotNull("the frames still held still pair", pairing);
        assertSame(newest, pairing.frame);
    }

    /** The same bound on the results, which have nothing to close but still queue. */
    @Test
    public void unmatchedResultsAreBoundedToo() {
        RawCapturePairer<String> pairer = new RawCapturePairer<>(2);

        for (int i = 1; i <= 50; i++) {
            assertNull(pairer.offerMetadata("result-" + i, i));
        }

        assertEquals(2, pairer.pendingMetadataCount());
        assertEquals(48, pairer.droppedMetadataCount());
        assertNull("a dropped result is gone", pairer.offerFrame(new TestFrame(1L)));
        RawCapturePairer.Pairing<String> pairing = pairer.offerFrame(new TestFrame(50L));
        assertNotNull(pairing);
        assertEquals("result-50", pairing.metadata);
    }

    /** Clearing closes every waiting frame and forgets every waiting result. */
    @Test
    public void clearingReleasesEverythingHeld() {
        RawCapturePairer<String> pairer = new RawCapturePairer<>(3);
        TestFrame first = new TestFrame(1L);
        TestFrame second = new TestFrame(2L);
        pairer.offerFrame(first);
        pairer.offerFrame(second);
        pairer.offerMetadata("orphan", 3L);

        pairer.clear();

        assertTrue(first.isClosed());
        assertTrue(second.isClosed());
        assertEquals(0, pairer.pendingFrameCount());
        assertEquals(0, pairer.pendingMetadataCount());
        assertNull("a cleared result no longer pairs", pairer.offerFrame(new TestFrame(3L)));
    }

    /** A pairer that can hold nothing would drop every capture. */
    @Test
    public void aPairerMustHoldSomething() {
        assertThrows(IllegalArgumentException.class, () -> new RawCapturePairer<String>(0));
    }
}
