package com.xlythe.view.camera.pano;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** A frame's capture time, on whichever clock the camera stamped it with. */
public class TimeBaseTest {
    private static final long SECOND = 1_000_000_000L;

    /** A device that has slept for an hour, so the two clocks are an hour apart. */
    private static final long ELAPSED_NOW = 7200 * SECOND;
    private static final long MONOTONIC_NOW = 3600 * SECOND;

    @Test
    public void elapsedRealtimeStamp_isKept() {
        long captured = ELAPSED_NOW - 40_000_000L;
        assertEquals(captured,
                TimeBase.toElapsedRealtime(captured, ELAPSED_NOW, MONOTONIC_NOW));
    }

    @Test
    public void monotonicStamp_isMovedOntoTheElapsedClock() {
        long captured = MONOTONIC_NOW - 40_000_000L;
        assertEquals(ELAPSED_NOW - 40_000_000L,
                TimeBase.toElapsedRealtime(captured, ELAPSED_NOW, MONOTONIC_NOW));
    }

    @Test
    public void stampOnNeitherClock_isTakenAsArriving() {
        assertEquals(ELAPSED_NOW, TimeBase.toElapsedRealtime(12345L, ELAPSED_NOW, MONOTONIC_NOW));
    }

    @Test
    public void stampFromTheFuture_isTakenAsArriving() {
        assertEquals(ELAPSED_NOW,
                TimeBase.toElapsedRealtime(ELAPSED_NOW + SECOND, ELAPSED_NOW, MONOTONIC_NOW));
    }
}
