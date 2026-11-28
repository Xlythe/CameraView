package com.xlythe.view.camera.stream;

import android.media.MediaCodec;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The rolling window of encoded frames a motion photo's clip is cut from.
 *
 * <p>Frames here are stamped in milliseconds times a thousand, so a stream at 10 frames a second
 * with a key frame every second reads as key frames at 0, 1000000, 2000000 and so on.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class EncodedVideoBufferTest {
    private static final long SECOND = 1_000_000L;
    private static final long FRAME = 100_000L;
    private static final int KEY = MediaCodec.BUFFER_FLAG_KEY_FRAME;

    /** A buffer that keeps 1.5 s, splits at 0.5 s gaps, and holds at most a megabyte. */
    private EncodedVideoBuffer buffer() {
        return new EncodedVideoBuffer(1_500_000L, 500_000L, 1024 * 1024);
    }

    /** Adds frames from one time to another, a key frame on every whole second. */
    private static void feed(EncodedVideoBuffer buffer, long fromUs, long toUs) {
        for (long time = fromUs; time <= toUs; time += FRAME) {
            buffer.add(new byte[10], time, time % SECOND == 0 ? KEY : 0);
        }
    }

    @Test
    public void startsOnlyAtAKeyFrame() {
        EncodedVideoBuffer buffer = buffer();

        assertFalse("a frame with nothing to decode it against",
                buffer.add(new byte[10], 100_000, 0));
        assertEquals(0, buffer.size());
        assertTrue(buffer.add(new byte[10], 200_000, KEY));
        assertTrue(buffer.add(new byte[10], 300_000, 0));
        assertEquals(2, buffer.size());
        assertEquals(200_000, buffer.oldestPresentationTimeUs());
    }

    @Test
    public void keepsTheHistoryAWholeGroupAtATime() {
        EncodedVideoBuffer buffer = buffer();

        feed(buffer, 0, 5 * SECOND);

        // 1.5 s behind 5 s is 3.5 s, and the key frame at or before that is 3 s.
        assertEquals(3 * SECOND, buffer.oldestPresentationTimeUs());
        assertEquals(5 * SECOND, buffer.newestPresentationTimeUs());
        assertEquals(21, buffer.size());
        assertEquals(210, buffer.bytes());
    }

    @Test
    public void aHoldKeepsTheFramesACaptureStillNeeds() {
        EncodedVideoBuffer buffer = buffer();
        feed(buffer, 0, 2 * SECOND);

        buffer.hold(1_200_000);
        feed(buffer, 2 * SECOND + FRAME, 6 * SECOND);

        assertEquals("the key frame before the hold stays", SECOND,
                buffer.oldestPresentationTimeUs());
        buffer.release(1_200_000);
        assertEquals(4 * SECOND, buffer.oldestPresentationTimeUs());
    }

    @Test
    public void slicesFromTheKeyFrameBeforeTheStart() {
        EncodedVideoBuffer buffer = buffer();
        buffer.hold(0);
        feed(buffer, 0, 4 * SECOND);

        List<EncodedVideoBuffer.Sample> slice = buffer.slice(2_500_000, 3_300_000);

        assertEquals(2 * SECOND, slice.get(0).presentationTimeUs);
        assertTrue(slice.get(0).isKeyFrame());
        assertEquals(3_300_000, slice.get(slice.size() - 1).presentationTimeUs);
        assertEquals(14, slice.size());
    }

    @Test
    public void aSliceStartingBeforeEverythingStartsAtTheOldest() {
        EncodedVideoBuffer buffer = buffer();
        feed(buffer, SECOND, 2 * SECOND);

        List<EncodedVideoBuffer.Sample> slice = buffer.slice(0, 1_500_000);

        assertEquals(SECOND, slice.get(0).presentationTimeUs);
        assertEquals(6, slice.size());
    }

    @Test
    public void aGapStartsAgainAtTheNextKeyFrame() {
        EncodedVideoBuffer buffer = buffer();
        feed(buffer, 0, SECOND);

        assertFalse("after a gap, frames wait for a key frame",
                buffer.add(new byte[10], 3_100_000, 0));
        assertEquals(0, buffer.size());
        assertTrue(buffer.add(new byte[10], 3_200_000, KEY));
        assertEquals(3_200_000, buffer.oldestPresentationTimeUs());
    }

    @Test
    public void aLongerGapIsOneVideoWhenTheSourceSaysSo() {
        EncodedVideoBuffer buffer = new EncodedVideoBuffer(10 * SECOND, 500_000L, 1024 * 1024);
        buffer.setMaxGapUs(3 * SECOND);
        feed(buffer, 0, SECOND);

        assertTrue(buffer.add(new byte[10], 2_500_000, 0));
        assertEquals(12, buffer.size());
    }

    @Test
    public void dropsAFrameThatIsNotNewerThanTheLast() {
        EncodedVideoBuffer buffer = buffer();
        feed(buffer, 0, 500_000);

        assertTrue(buffer.add(new byte[10], 500_000, 0));
        assertTrue(buffer.add(new byte[10], 400_000, 0));
        assertEquals(6, buffer.size());
        assertEquals(500_000, buffer.newestPresentationTimeUs());
    }

    @Test
    public void ignoresTheCodecConfiguration() {
        EncodedVideoBuffer buffer = buffer();

        assertTrue(buffer.add(new byte[10], 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG));
        assertEquals(0, buffer.size());
    }

    @Test
    public void staysUnderItsByteCeilingWhateverTheHolds() {
        EncodedVideoBuffer buffer = new EncodedVideoBuffer(10 * SECOND, 500_000L, 150);
        buffer.hold(0);

        feed(buffer, 0, 3 * SECOND);

        assertEquals(110, buffer.bytes());
        assertEquals(2 * SECOND, buffer.oldestPresentationTimeUs());
        assertTrue("whole groups go", buffer.slice(0, 3 * SECOND).get(0).isKeyFrame());
    }

    @Test
    public void clearDropsEverything() {
        EncodedVideoBuffer buffer = buffer();
        feed(buffer, 0, SECOND);

        buffer.clear();

        assertEquals(0, buffer.size());
        assertEquals(0, buffer.bytes());
        assertEquals(Long.MIN_VALUE, buffer.newestPresentationTimeUs());
        assertEquals(Long.MIN_VALUE, buffer.oldestPresentationTimeUs());
    }
}
