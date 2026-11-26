package com.xlythe.view.camera.stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.io.PipedInputStream;
import java.util.Arrays;

/**
 * Memory and latency behavior of the lossy stream under a reader that cannot keep up.
 *
 * <p>These are the two failure modes that matter on a live link, and they pull against each
 * other: holding frames costs latency, and dropping the wrong ones costs a decodable picture. The
 * bounds asserted here are the ones the design relies on, not timing measurements, so they hold
 * on a loaded CI machine as well as a quiet one.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37)
public class StreamBackpressureTest {
    /** Comfortably larger than any backlog the stream should allow, so the pipe never blocks. */
    private static final int PIPE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_BUFFERED = 32 * 1024;
    private static final int HARD_CEILING = 4 * MAX_BUFFERED;
    /** A plausible encoded delta frame. */
    private static final int DELTA_BYTES = 2 * 1024;
    /** A plausible encoded key frame, an order of magnitude larger. */
    private static final int KEY_BYTES = 24 * 1024;

    private static byte[] frame(int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) 7);
        return bytes;
    }

    /**
     * A reader that has stopped entirely is the worst case. Nothing may accumulate without
     * bound, and no write may block, or the encoder drain loop stalls behind it.
     */
    @Test
    public void astalledReaderNeitherGrowsNorBlocks() throws IOException {
        PipedInputStream sink = new PipedInputStream(PIPE_BYTES);
        try (LossyPipedOutputStream stream = new LossyPipedOutputStream(sink, MAX_BUFFERED)) {
            int accepted = 0;
            for (int i = 0; i < 10_000; i++) {
                boolean isKeyFrame = i % 30 == 0;
                if (stream.writeFrame(frame(isKeyFrame ? KEY_BYTES : DELTA_BYTES), !isKeyFrame)) {
                    accepted++;
                }
                // The invariant that makes the pipe size safe: one frame may cross a threshold,
                // but the backlog can never run away from the ceiling.
                assertTrue("backlog ran away at frame " + i,
                        stream.bufferedBytes() <= HARD_CEILING + KEY_BYTES);
            }

            // Almost everything was shed rather than queued.
            assertTrue("expected most frames to be dropped, accepted " + accepted,
                    accepted < 100);
        }
    }

    /**
     * A reader draining more slowly than the writer produces should settle at a bounded backlog
     * rather than drifting further behind for as long as the stream runs.
     */
    @Test
    public void aSlowReaderSettlesAtABoundedBacklog() throws IOException {
        PipedInputStream sink = new PipedInputStream(PIPE_BYTES);
        try (LossyPipedOutputStream stream = new LossyPipedOutputStream(sink, MAX_BUFFERED)) {
            byte[] scratch = new byte[DELTA_BYTES];
            for (int i = 0; i < 3_000; i++) {
                boolean isKeyFrame = i % 30 == 0;
                stream.writeFrame(frame(isKeyFrame ? KEY_BYTES : DELTA_BYTES), !isKeyFrame);

                // Drain a third of what one frame costs, so the reader is persistently behind.
                if (sink.available() >= scratch.length) {
                    sink.read(scratch, 0, scratch.length / 3);
                }
                assertTrue("backlog ran away at frame " + i,
                        stream.bufferedBytes() <= HARD_CEILING + KEY_BYTES);
            }
        }
    }

    /**
     * The backlog is a latency budget, so the bound has to mean the same number of milliseconds
     * at every bit rate. The ceiling is four times the drop threshold, which is a quarter second
     * of video, so a full ceiling is about a second behind.
     */
    @Test
    public void theBacklogBoundIsTheSameLatencyAtEveryBitRate() {
        for (int bitRate : new int[] {256_000, 1_000_000, 2_500_000, 6_000_000}) {
            int softBytes = VideoRecorder.backlogBytesFor(bitRate, 250);
            long softMillis = (long) softBytes * 8 * 1000 / bitRate;
            long ceilingMillis = softMillis * 4;

            // Clamping moves the floor up at very low bit rates, so allow a little slack there,
            // but a second and a half behind is the most the design ever tolerates.
            assertTrue("soft budget was " + softMillis + "ms at " + bitRate,
                    softMillis >= 200 && softMillis <= 300);
            assertTrue("ceiling was " + ceilingMillis + "ms at " + bitRate,
                    ceilingMillis <= 1_500);
        }
    }

    /**
     * Recovery has to be bounded too. Once the reader catches up the producer is told to make a
     * new reference frame, and until it does the reader has nothing it can decode.
     */
    @Test
    public void recoveryIsRequestedExactlyOnceAfterTheReaderCatchesUp() throws IOException {
        PipedInputStream sink = new PipedInputStream(PIPE_BYTES);
        try (LossyPipedOutputStream stream = new LossyPipedOutputStream(sink, MAX_BUFFERED)) {
            for (int i = 0; i < 200; i++) {
                boolean isKeyFrame = i % 30 == 0;
                stream.writeFrame(frame(isKeyFrame ? KEY_BYTES : DELTA_BYTES), !isKeyFrame);
            }
            assertTrue("expected the ceiling to have been hit", stream.bufferedBytes() > MAX_BUFFERED);
            assertFalse("must not resync while still behind", stream.consumeResyncRequest());

            byte[] scratch = new byte[64 * 1024];
            while (sink.available() > 0) {
                sink.read(scratch, 0, Math.min(scratch.length, sink.available()));
            }

            assertTrue("expected a resync once caught up", stream.consumeResyncRequest());
            assertFalse("resync must only be handed out once", stream.consumeResyncRequest());
        }
    }

    /**
     * Dropping must not depend on the reader having drained completely. The original policy
     * dropped whenever anything at all was buffered, which on a pipe smaller than one frame meant
     * blocking mid-frame on every frame instead of shedding load.
     */
    @Test
    public void aPartiallyDrainedReaderStillReceivesFrames() throws IOException {
        PipedInputStream sink = new PipedInputStream(PIPE_BYTES);
        try (LossyPipedOutputStream stream = new LossyPipedOutputStream(sink, MAX_BUFFERED)) {
            assertTrue(stream.writeFrame(frame(DELTA_BYTES), true));
            assertTrue("a small backlog must not stop the next frame",
                    stream.writeFrame(frame(DELTA_BYTES), true));
            assertEquals(2 * DELTA_BYTES, stream.bufferedBytes());
        }
    }

    /**
     * The frame the reader is given has to be the frame the writer wrote, byte for byte, however
     * much was dropped around it. A desync here would be silent and unrecoverable.
     */
    @Test
    public void framesThatSurviveArriveIntact() throws IOException {
        PipedInputStream sink = new PipedInputStream(PIPE_BYTES);
        try (LossyPipedOutputStream stream = new LossyPipedOutputStream(sink, MAX_BUFFERED)) {
            byte[] expected = new byte[DELTA_BYTES];
            for (int i = 0; i < expected.length; i++) {
                expected[i] = (byte) (i % 251);
            }

            assertTrue(stream.writeFrame(expected, false));
            byte[] actual = new byte[expected.length];
            int offset = 0;
            while (offset < actual.length) {
                int count = sink.read(actual, offset, actual.length - offset);
                if (count < 0) {
                    break;
                }
                offset += count;
            }
            assertEquals(expected.length, offset);
            org.junit.Assert.assertArrayEquals(expected, actual);
        }
    }
}
