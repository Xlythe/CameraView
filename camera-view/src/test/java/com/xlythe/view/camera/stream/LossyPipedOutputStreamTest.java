package com.xlythe.view.camera.stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.media.MediaCodec;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.io.PipedInputStream;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class LossyPipedOutputStreamTest {
    private static final int PIPE_BYTES = 64 * 1024;
    private static final int MAX_BUFFERED = 1024;

    private static byte[] frameOf(int length, byte fill) {
        byte[] frame = new byte[length];
        java.util.Arrays.fill(frame, fill);
        return frame;
    }

    @Test
    public void writesEverythingWhileTheReaderKeepsUp() throws IOException {
        PipedInputStream sink = new PipedInputStream(PIPE_BYTES);
        try (LossyPipedOutputStream stream = new LossyPipedOutputStream(sink, MAX_BUFFERED)) {
            assertTrue(stream.writeFrame(frameOf(16, (byte) 1), true));
            byte[] read = new byte[16];
            assertEquals(16, sink.read(read));
            assertArrayEquals(frameOf(16, (byte) 1), read);
            assertTrue(stream.writeFrame(frameOf(16, (byte) 2), true));
        }
    }

    @Test
    public void dropsDroppableFramesOnceTheReaderFallsBehind() throws IOException {
        PipedInputStream sink = new PipedInputStream(PIPE_BYTES);
        try (LossyPipedOutputStream stream = new LossyPipedOutputStream(sink, MAX_BUFFERED)) {
            // Fill past the threshold without anybody reading.
            assertTrue(stream.writeFrame(frameOf(MAX_BUFFERED + 1, (byte) 1), true));
            assertTrue(stream.bufferedBytes() > MAX_BUFFERED);

            assertFalse(stream.writeFrame(frameOf(16, (byte) 2), true));
            assertEquals(MAX_BUFFERED + 1, stream.bufferedBytes());
        }
    }

    /**
     * The whole point of the frame API: a key frame is what every later frame is decoded against,
     * so losing one costs an I-frame interval of video rather than a single frame.
     */
    @Test
    public void neverDropsAFrameOthersDependOn() throws IOException {
        PipedInputStream sink = new PipedInputStream(PIPE_BYTES);
        try (LossyPipedOutputStream stream = new LossyPipedOutputStream(sink, MAX_BUFFERED)) {
            assertTrue(stream.writeFrame(frameOf(MAX_BUFFERED + 1, (byte) 1), true));
            assertTrue(stream.bufferedBytes() > MAX_BUFFERED);

            assertTrue(stream.writeFrame(frameOf(32, (byte) 9), false));
            assertEquals(MAX_BUFFERED + 1 + 32, stream.bufferedBytes());
        }
    }

    /** A dropped frame leaves nothing behind, so the reader never sees a partial record. */
    @Test
    public void aDroppedFrameIsDroppedWhole() throws IOException {
        PipedInputStream sink = new PipedInputStream(PIPE_BYTES);
        try (LossyPipedOutputStream stream = new LossyPipedOutputStream(sink, MAX_BUFFERED)) {
            byte[] first = frameOf(MAX_BUFFERED + 1, (byte) 1);
            assertTrue(stream.writeFrame(first, true));
            assertFalse(stream.writeFrame(frameOf(64, (byte) 2), true));
            assertTrue(stream.writeFrame(frameOf(8, (byte) 3), false));

            byte[] read = new byte[MAX_BUFFERED + 1 + 8];
            int offset = 0;
            while (offset < read.length) {
                int count = sink.read(read, offset, read.length - offset);
                if (count < 0) {
                    break;
                }
                offset += count;
            }
            assertEquals(read.length, offset);
            // Only the undroppable frame follows the first one; no trace of the dropped frame.
            for (int i = 0; i < MAX_BUFFERED + 1; i++) {
                assertEquals(1, read[i]);
            }
            for (int i = MAX_BUFFERED + 1; i < read.length; i++) {
                assertEquals(3, read[i]);
            }
        }
    }

    /**
     * Past the hard ceiling even reference frames go, because only the newest one is ever needed
     * to resume and queueing the rest just deepens a backlog that is already too stale to show.
     */
    @Test
    public void discardsReferenceFramesOnceHopelesslyBehind() throws IOException {
        PipedInputStream sink = new PipedInputStream(PIPE_BYTES);
        try (LossyPipedOutputStream stream = new LossyPipedOutputStream(sink, MAX_BUFFERED)) {
            assertFalse(stream.consumeResyncRequest());

            // Four times the soft threshold is the ceiling, so push past it.
            assertTrue(stream.writeFrame(frameOf(4 * MAX_BUFFERED + 1, (byte) 1), false));
            assertFalse(stream.writeFrame(frameOf(32, (byte) 2), false));

            // Still too far behind to spend a key frame on.
            assertFalse(stream.consumeResyncRequest());

            byte[] drain = new byte[4 * MAX_BUFFERED + 1];
            int offset = 0;
            while (offset < drain.length) {
                int count = sink.read(drain, offset, drain.length - offset);
                if (count < 0) {
                    break;
                }
                offset += count;
            }

            // Caught up, so the producer is asked for a fresh reference frame exactly once.
            assertTrue(stream.consumeResyncRequest());
            assertFalse(stream.consumeResyncRequest());
        }
    }

    /**
     * A delta is predicted from the frame before it, so once one is dropped the rest of its group
     * cannot be decoded. They are dropped too, even after the reader has caught up, until a frame
     * others depend on is written again.
     */
    @Test
    public void aDroppedDeltaTakesTheRestOfItsGroupWithIt() throws IOException {
        PipedInputStream sink = new PipedInputStream(PIPE_BYTES);
        try (LossyPipedOutputStream stream = new LossyPipedOutputStream(sink, MAX_BUFFERED)) {
            assertTrue(stream.writeFrame(frameOf(MAX_BUFFERED + 1, (byte) 1), true));
            assertFalse(stream.writeFrame(frameOf(16, (byte) 2), true));
            drain(sink);

            assertFalse("predicted from a frame the reader never got",
                    stream.writeFrame(frameOf(16, (byte) 3), true));
            assertTrue(stream.writeFrame(frameOf(16, (byte) 4), false));
            assertTrue(stream.writeFrame(frameOf(16, (byte) 5), true));
        }
    }

    /**
     * Losing a delta asks for a key frame, but only once the reader has read everything: a key
     * frame landing on a backlog that is still deep only pushes it back over the threshold.
     */
    @Test
    public void aDroppedDeltaAsksForAKeyFrameOnceTheReaderHasEverything() throws IOException {
        PipedInputStream sink = new PipedInputStream(PIPE_BYTES);
        try (LossyPipedOutputStream stream = new LossyPipedOutputStream(sink, MAX_BUFFERED)) {
            assertTrue(stream.writeFrame(frameOf(MAX_BUFFERED + 1, (byte) 1), true));
            assertFalse(stream.writeFrame(frameOf(16, (byte) 2), true));
            assertFalse(stream.consumeResyncRequest());

            sink.read(new byte[MAX_BUFFERED]);
            assertTrue(stream.bufferedBytes() > 0);
            assertFalse("the reader still has a frame to read", stream.consumeResyncRequest());

            drain(sink);
            assertTrue(stream.consumeResyncRequest());
            assertFalse(stream.consumeResyncRequest());

            // Further drops in the same broken group do not ask again.
            assertFalse(stream.writeFrame(frameOf(16, (byte) 3), true));
            assertFalse(stream.consumeResyncRequest());
        }
    }

    /** A key frame that arrives on schedule repairs the group, so the request is withdrawn. */
    @Test
    public void aKeyFrameOnScheduleWithdrawsTheRequest() throws IOException {
        PipedInputStream sink = new PipedInputStream(PIPE_BYTES);
        try (LossyPipedOutputStream stream = new LossyPipedOutputStream(sink, MAX_BUFFERED)) {
            assertTrue(stream.writeFrame(frameOf(MAX_BUFFERED + 1, (byte) 1), true));
            assertFalse(stream.writeFrame(frameOf(16, (byte) 2), true));
            assertTrue(stream.writeFrame(frameOf(16, (byte) 3), false));
            drain(sink);
            assertFalse(stream.consumeResyncRequest());
        }
    }

    private static void drain(PipedInputStream sink) throws IOException {
        byte[] scratch = new byte[PIPE_BYTES];
        while (sink.available() > 0) {
            sink.read(scratch, 0, sink.available());
        }
    }

    @Test
    public void reportsAMissingSink() {
        LossyPipedOutputStream stream = new LossyPipedOutputStream();
        assertThrows(IOException.class, () -> stream.writeFrame(frameOf(4, (byte) 1), true));
        assertThrows(IOException.class, () -> stream.write(new byte[] {1}));
        assertThrows(IOException.class, stream::bufferedBytes);
    }

    @Test
    public void byteLevelWritesStillDropWholeChunks() throws IOException {
        PipedInputStream sink = new PipedInputStream(PIPE_BYTES);
        try (LossyPipedOutputStream stream = new LossyPipedOutputStream(sink, MAX_BUFFERED)) {
            // The first write is never dropped: nothing has flushed yet.
            stream.write(frameOf(MAX_BUFFERED + 1, (byte) 1));
            stream.flush();
            assertTrue(stream.bufferedBytes() > MAX_BUFFERED);

            stream.write(frameOf(16, (byte) 2));
            assertEquals(MAX_BUFFERED + 1, stream.bufferedBytes());
        }
    }

    /**
     * The same backlog means very different latency at different bit rates, and latency is what
     * the dropping exists to bound, so the budget is a duration rather than a byte count.
     */
    @Test
    public void backlogBudgetFollowsTheBitRate() {
        // A quarter second at 6 Mbps is 187 KB, and at 1 Mbps a sixteenth of that.
        assertEquals(187_500, VideoRecorder.backlogBytesFor(6_000_000, 250));
        assertEquals(31_250, VideoRecorder.backlogBytesFor(1_000_000, 250));

        // The old fixed 128 KiB was a fifth of a second at 6 Mbps but four seconds at 256 kbps,
        // which is the latency this is meant to bound. The budget now lands on the floor there.
        assertEquals(8 * 1024, VideoRecorder.backlogBytesFor(256_000, 250));

        // Clamped at both ends.
        assertEquals(8 * 1024, VideoRecorder.backlogBytesFor(1_000, 250));
        assertEquals(512 * 1024, VideoRecorder.backlogBytesFor(100_000_000, 250));
    }

    /** The audio budget is a duration too, so it scales with every rate the recorder can pick. */
    @Test
    public void audioBacklogBudgetFollowsTheSampleRate() {
        assertEquals(8_000, AudioRecorder.backlogBytesFor(16_000, 1, 250));
        assertEquals(4_000, AudioRecorder.backlogBytesFor(8_000, 1, 250));
        assertEquals(48_000, AudioRecorder.backlogBytesFor(48_000, 2, 250));
    }

    @Test
    public void theBudgetCanBeRetunedAfterConstruction() throws IOException {
        PipedInputStream sink = new PipedInputStream(PIPE_BYTES);
        try (LossyPipedOutputStream stream = new LossyPipedOutputStream(sink, MAX_BUFFERED)) {
            assertEquals(MAX_BUFFERED, stream.getMaxBufferedBytes());

            stream.setMaxBufferedBytes(64);
            assertEquals(64, stream.getMaxBufferedBytes());

            assertTrue(stream.writeFrame(frameOf(65, (byte) 1), true));
            assertFalse(stream.writeFrame(frameOf(8, (byte) 2), true));

            org.junit.Assert.assertThrows(IllegalArgumentException.class,
                    () -> stream.setMaxBufferedBytes(0));
        }
    }

    /**
     * A frame nothing depends on is dropped alone: once the reader catches up the next one goes
     * through, where a dropped delta would have taken every frame until a key frame with it.
     */
    @Test
    public void anIndependentFrameIsDroppedAlone() throws IOException {
        PipedInputStream sink = new PipedInputStream(PIPE_BYTES);
        try (LossyPipedOutputStream stream = new LossyPipedOutputStream(sink, MAX_BUFFERED)) {
            assertTrue(stream.writeIndependentFrame(frameOf(MAX_BUFFERED + 1, (byte) 1)));
            assertFalse("the reader is behind", stream.writeIndependentFrame(frameOf(8, (byte) 2)));

            byte[] backlog = new byte[MAX_BUFFERED + 1];
            assertEquals(backlog.length, sink.read(backlog));
            assertTrue("caught up, so the next frame goes through",
                    stream.writeIndependentFrame(frameOf(8, (byte) 3)));
            assertFalse("and nothing asks for a key frame", stream.consumeResyncRequest());
            byte[] next = new byte[8];
            assertEquals(8, sink.read(next));
            assertArrayEquals(frameOf(8, (byte) 3), next);
        }
    }

    @Test
    public void onlyDeltaFramesAreDroppable() {
        assertTrue(VideoRecorder.isDroppable(0));
        assertFalse(VideoRecorder.isDroppable(MediaCodec.BUFFER_FLAG_KEY_FRAME));
        assertFalse(VideoRecorder.isDroppable(MediaCodec.BUFFER_FLAG_CODEC_CONFIG));
        assertFalse(VideoRecorder.isDroppable(MediaCodec.BUFFER_FLAG_END_OF_STREAM));
        assertFalse(VideoRecorder.isDroppable(
                MediaCodec.BUFFER_FLAG_KEY_FRAME | MediaCodec.BUFFER_FLAG_CODEC_CONFIG));
    }
}
