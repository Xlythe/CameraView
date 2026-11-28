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
    public void aFrameSecondsLate_isStillReadOnItsOwnClock() {
        // A busy device can take this long; reading it as arriving now would place it with the
        // rotation of three seconds later.
        long captured = ELAPSED_NOW - 3 * SECOND;
        assertEquals(captured,
                TimeBase.toElapsedRealtime(captured, ELAPSED_NOW, MONOTONIC_NOW));
    }

    @Test
    public void whenBothClocksFit_theNearerOneWins() {
        // A device that has barely slept: the clocks are a second apart, and a stamp fits both.
        long elapsed = 100 * SECOND;
        long monotonic = 99 * SECOND;
        long captured = monotonic - 50_000_000L;
        assertEquals(elapsed - 50_000_000L,
                TimeBase.toElapsedRealtime(captured, elapsed, monotonic));
        captured = elapsed - 50_000_000L;
        assertEquals(captured, TimeBase.toElapsedRealtime(captured, elapsed, monotonic));
    }

    @Test
    public void stampOnNeitherClock_isUnknown() {
        assertEquals(TimeBase.UNKNOWN,
                TimeBase.toElapsedRealtime(12345L, ELAPSED_NOW, MONOTONIC_NOW));
    }

    @Test
    public void stampFromTheFuture_isUnknown() {
        assertEquals(TimeBase.UNKNOWN,
                TimeBase.toElapsedRealtime(ELAPSED_NOW + SECOND, ELAPSED_NOW, MONOTONIC_NOW));
    }

    @Test
    public void anUnknownTime_hasNoRotation() {
        OrientationHistory history = new OrientationHistory();
        history.add(SECOND, SphereMathTest.uprightFacing(0f));
        history.add(2 * SECOND, SphereMathTest.uprightFacing(0f));
        assertEquals(false, history.sample(TimeBase.UNKNOWN, new float[4]));
    }
}
