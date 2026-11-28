package com.xlythe.view.camera.pano;

/**
 * Puts a frame's capture time on the clock the rotation sensors use.
 *
 * <p>Sensor events are stamped with {@code SystemClock.elapsedRealtimeNanos}. A camera stamps
 * its frames with whatever its timestamp source says, which is either that same clock or one it
 * does not name - in practice {@code System.nanoTime}, which stops while the device sleeps and so
 * falls behind by however long it has slept. Rather than trust what a camera declares, a frame is
 * read against both clocks as it arrives: it was captured a little before now, so whichever clock
 * puts it the least time before now is the one it was stamped with.</p>
 *
 * <p>A frame that fits neither clock is not given the time it arrived instead. A busy device can
 * deliver frames seconds late, and placing one with the rotation of seconds later puts it a wall
 * away from where it belongs, which is far worse than not placing it at all.</p>
 */
final class TimeBase {
    /**
     * The latest a frame can arrive and still be read against a clock: longer than any rotation
     * is kept for, so a frame this late is refused for want of a rotation rather than misread.
     */
    static final long MAX_DELIVERY_NANOS = 10_000_000_000L;

    /** What a capture time that fits neither clock becomes: earlier than any rotation held. */
    static final long UNKNOWN = -1L;

    private TimeBase() {}

    /**
     * A capture time on the elapsed-realtime clock.
     *
     * @param timestamp the frame's own timestamp
     * @param elapsedNow {@code SystemClock.elapsedRealtimeNanos()} as the frame arrived
     * @param monotonicNow {@code System.nanoTime()} as the frame arrived
     * @return the capture time, or {@link #UNKNOWN} when the timestamp fits neither clock
     */
    static long toElapsedRealtime(long timestamp, long elapsedNow, long monotonicNow) {
        long sinceElapsed = elapsedNow - timestamp;
        long sinceMonotonic = monotonicNow - timestamp;
        boolean elapsedFits = sinceElapsed >= 0 && sinceElapsed <= MAX_DELIVERY_NANOS;
        boolean monotonicFits = sinceMonotonic >= 0 && sinceMonotonic <= MAX_DELIVERY_NANOS;
        if (elapsedFits && (!monotonicFits || sinceElapsed <= sinceMonotonic)) {
            return timestamp;
        }
        if (monotonicFits) {
            return elapsedNow - sinceMonotonic;
        }
        return UNKNOWN;
    }
}
