package com.xlythe.view.camera.stream;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * What the audio player discards to stay near live, and what it keeps.
 *
 * <p>Plain JUnit: nothing here touches the platform, so there is no SDK to run it against.</p>
 */
public class AudioCatchUpTest {
    /** 16kHz mono: two bytes a frame, 32 a millisecond. */
    private static final int BYTES_PER_MS = 32;

    /** A second of 16kHz mono, which is the window. */
    private static final int WINDOW_BYTES = AudioCatchUp.WINDOW_MILLIS * BYTES_PER_MS;

    /** The backlog kept at the window's lowest point. */
    private static final int TARGET_BYTES = AudioCatchUp.TARGET_MILLIS * BYTES_PER_MS;

    @Test
    public void nothingIsDiscardedBeforeAWindowHasPlayed() {
        AudioCatchUp catchUp = new AudioCatchUp(16_000, 1);
        assertEquals(0, catchUp.bytesToSkip(10 * WINDOW_BYTES));
        catchUp.onPlayed(WINDOW_BYTES - 2);
        assertEquals(0, catchUp.bytesToSkip(10 * WINDOW_BYTES));
    }

    /** What waited through the whole window, beyond the cushion, is delay. */
    @Test
    public void aBacklogThatNeverDrainedIsDiscardedDownToTheCushion() {
        AudioCatchUp catchUp = new AudioCatchUp(16_000, 1);
        catchUp.bytesToSkip(20_000);
        catchUp.onPlayed(WINDOW_BYTES / 2);
        catchUp.bytesToSkip(12_000);
        catchUp.onPlayed(WINDOW_BYTES / 2);
        assertEquals(12_000 - TARGET_BYTES, catchUp.bytesToSkip(16_000));
    }

    /** A backlog that ran dry during the window was cushion, not delay. */
    @Test
    public void aBacklogThatRanDryIsKept() {
        AudioCatchUp catchUp = new AudioCatchUp(16_000, 1);
        catchUp.bytesToSkip(0);
        catchUp.onPlayed(WINDOW_BYTES);
        assertEquals(0, catchUp.bytesToSkip(40_000));
    }

    /** Each window is judged on its own, so one decision does not carry into the next. */
    @Test
    public void eachWindowStartsAfresh() {
        AudioCatchUp catchUp = new AudioCatchUp(16_000, 1);
        catchUp.bytesToSkip(0);
        catchUp.onPlayed(WINDOW_BYTES);
        assertEquals(0, catchUp.bytesToSkip(40_000));

        catchUp.bytesToSkip(45_000);
        catchUp.onPlayed(WINDOW_BYTES);
        assertEquals(45_000 - TARGET_BYTES, catchUp.bytesToSkip(50_000));
    }

    /** A stream that cannot say what it holds reports nothing, and is never skipped. */
    @Test
    public void aStreamThatCannotTellIsNeverSkipped() {
        AudioCatchUp catchUp = new AudioCatchUp(48_000, 2);
        for (int i = 0; i < 10; i++) {
            catchUp.onPlayed(1024 * 1024);
            assertEquals(0, catchUp.bytesToSkip(0));
        }
    }

    /** Anything discarded is whole frames, so the samples after it are read from their start. */
    @Test
    public void skipsAreWholeFrames() {
        AudioCatchUp stereo = new AudioCatchUp(16_000, 2);
        assertEquals(4, stereo.getFrameBytes());
        stereo.bytesToSkip(2 * TARGET_BYTES + 4 * 1000 + 3);
        stereo.onPlayed(2 * WINDOW_BYTES);
        assertEquals(4 * 1000, stereo.bytesToSkip(1024 * 1024));
    }
}
