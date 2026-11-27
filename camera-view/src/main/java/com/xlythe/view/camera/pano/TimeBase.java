package com.xlythe.view.camera.pano;

/**
 * Puts a frame's capture time on the clock the rotation sensors use.
 *
 * <p>Sensor events are stamped with {@code SystemClock.elapsedRealtimeNanos}. A camera stamps
 * its frames with whatever its timestamp source says, which is either that same clock or one it
 * does not name - in practice {@code System.nanoTime}, which stops while the device sleeps and so
 * falls behind by however long it has slept. Rather than trust what a camera declares, a frame is
 * read against both clocks as it arrives: it was captured a little before now, so whichever clock
 * puts it a little before now is the one it was stamped with.</p>
 */
final class TimeBase {
    /**
     * The longest a frame can plausibly take to arrive. Long enough for a slow pipeline, and far
     * shorter than the gap that opens between the two clocks after any time asleep.
     */
    static final long MAX_DELIVERY_NANOS = 1_000_000_000L;

    private TimeBase() {}

    /**
     * A capture time on the elapsed-realtime clock.
     *
     * @param timestamp the frame's own timestamp
     * @param elapsedNow {@code SystemClock.elapsedRealtimeNanos()} as the frame arrived
     * @param monotonicNow {@code System.nanoTime()} as the frame arrived
     * @return the capture time, or the arrival time when the timestamp fits neither clock
     */
    static long toElapsedRealtime(long timestamp, long elapsedNow, long monotonicNow) {
        long sinceElapsed = elapsedNow - timestamp;
        if (sinceElapsed >= 0 && sinceElapsed <= MAX_DELIVERY_NANOS) {
            return timestamp;
        }
        long sinceMonotonic = monotonicNow - timestamp;
        if (sinceMonotonic >= 0 && sinceMonotonic <= MAX_DELIVERY_NANOS) {
            return elapsedNow - sinceMonotonic;
        }
        return elapsedNow;
    }
}
