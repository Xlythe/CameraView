package com.xlythe.view.camera.pano;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** How fast the gyroscope said the device turned, over a span of time. */
public class GyroscopeHistoryTest {
    private static final long MILLIS = 1_000_000L;

    @Test
    public void average_withNothingRead_isUnknown() {
        GyroscopeHistory history = new GyroscopeHistory();
        assertTrue(history.isEmpty());
        assertTrue(Float.isNaN(history.average(0, 100 * MILLIS)));
        history.add(100 * MILLIS, 5f);
        assertFalse(history.isEmpty());
    }

    @Test
    public void average_takesOnlyTheReadingsInTheSpan_bothEndsIncluded() {
        GyroscopeHistory history = new GyroscopeHistory();
        history.add(90 * MILLIS, 100f);
        history.add(100 * MILLIS, 10f);
        history.add(110 * MILLIS, 20f);
        history.add(120 * MILLIS, 30f);
        history.add(130 * MILLIS, 100f);
        assertEquals(20f, history.average(100 * MILLIS, 120 * MILLIS), 1e-4f);
        assertTrue(Float.isNaN(history.average(131 * MILLIS, 200 * MILLIS)));
    }

    @Test
    public void aReadingNoNewerThanTheNewest_isDropped() {
        GyroscopeHistory history = new GyroscopeHistory();
        history.add(100 * MILLIS, 10f);
        history.add(100 * MILLIS, 50f);
        history.add(90 * MILLIS, 50f);
        assertEquals(10f, history.average(0, 200 * MILLIS), 1e-4f);
    }

    @Test
    public void aFullHistory_keepsTheNewest() {
        GyroscopeHistory history = new GyroscopeHistory();
        for (int i = 0; i < GyroscopeHistory.CAPACITY + 10; i++) {
            history.add(i * MILLIS, i);
        }
        int newest = GyroscopeHistory.CAPACITY + 9;
        assertEquals(newest, history.average(newest * MILLIS, newest * MILLIS), 1e-4f);
        // The oldest ten went to make room.
        assertTrue(Float.isNaN(history.average(0, 9 * MILLIS)));
        assertEquals(10f, history.average(0, 10 * MILLIS), 1e-4f);
    }
}
