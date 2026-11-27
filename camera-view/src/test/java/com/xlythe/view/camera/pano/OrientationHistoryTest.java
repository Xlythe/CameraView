package com.xlythe.view.camera.pano;

import static com.xlythe.view.camera.pano.SphereMathTest.matrixOf;
import static com.xlythe.view.camera.pano.SphereMathTest.uprightFacing;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The rotation at the moment a frame was captured, from the readings either side of it. */
public class OrientationHistoryTest {
    private static final long MILLIS = 1_000_000L;

    @Test
    public void sample_betweenReadings_interpolates() {
        OrientationHistory history = new OrientationHistory();
        history.add(100 * MILLIS, uprightFacing(0f));
        history.add(120 * MILLIS, uprightFacing(10f));
        float[] quaternion = new float[4];
        assertTrue(history.sample(115 * MILLIS, quaternion));
        assertEquals(7.5f, yaw(quaternion), 0.01f);
    }

    @Test
    public void sample_onAReading_isThatReading() {
        OrientationHistory history = new OrientationHistory();
        history.add(100 * MILLIS, uprightFacing(0f));
        history.add(120 * MILLIS, uprightFacing(10f));
        history.add(140 * MILLIS, uprightFacing(30f));
        float[] quaternion = new float[4];
        assertTrue(history.sample(120 * MILLIS, quaternion));
        assertEquals(10f, yaw(quaternion), 0.01f);
        assertTrue(history.sample(100 * MILLIS, quaternion));
        assertEquals(0f, yaw(quaternion), 0.01f);
    }

    @Test
    public void sample_justPastTheNewest_usesTheNewest() {
        OrientationHistory history = new OrientationHistory();
        history.add(100 * MILLIS, uprightFacing(5f));
        float[] quaternion = new float[4];
        assertTrue(history.sample(150 * MILLIS, quaternion));
        assertEquals(5f, yaw(quaternion), 0.01f);
    }

    @Test
    public void sample_longPastTheNewest_refuses() {
        OrientationHistory history = new OrientationHistory();
        history.add(100 * MILLIS, uprightFacing(5f));
        assertFalse(history.sample(100 * MILLIS + OrientationHistory.MAX_EXTRAPOLATION_NANOS + 1,
                new float[4]));
    }

    @Test
    public void sample_beforeTheOldest_refuses() {
        OrientationHistory history = new OrientationHistory();
        history.add(100 * MILLIS, uprightFacing(5f));
        history.add(110 * MILLIS, uprightFacing(6f));
        assertFalse(history.sample(99 * MILLIS, new float[4]));
    }

    @Test
    public void sample_withNothingRead_refuses() {
        OrientationHistory history = new OrientationHistory();
        assertTrue(history.isEmpty());
        assertEquals(0, history.newestTime());
        assertFalse(history.sample(0, new float[4]));
    }

    @Test
    public void outOfOrderReadings_areDropped() {
        OrientationHistory history = new OrientationHistory();
        history.add(100 * MILLIS, uprightFacing(0f));
        history.add(90 * MILLIS, uprightFacing(50f));
        history.add(100 * MILLIS, uprightFacing(50f));
        assertEquals(100 * MILLIS, history.newestTime());
        float[] quaternion = new float[4];
        assertTrue(history.sample(100 * MILLIS, quaternion));
        assertEquals(0f, yaw(quaternion), 0.01f);
    }

    @Test
    public void aFullRing_forgetsTheOldest() {
        OrientationHistory history = new OrientationHistory();
        int readings = OrientationHistory.CAPACITY + 10;
        for (int i = 0; i < readings; i++) {
            history.add(i * 10 * MILLIS, uprightFacing(i * 0.1f));
        }
        float[] quaternion = new float[4];
        assertFalse(history.sample(5 * 10 * MILLIS, quaternion));
        long newest = (readings - 1) * 10 * MILLIS;
        assertEquals(newest, history.newestTime());
        assertTrue(history.sample(newest - 5 * MILLIS, quaternion));
        assertEquals((readings - 1.5f) * 0.1f, yaw(quaternion), 0.01f);
        long oldest = 10 * 10 * MILLIS;
        assertTrue(history.sample(oldest, quaternion));
        assertEquals(1f, yaw(quaternion), 0.01f);
    }

    @Test
    public void speed_isTheTurnPerSecond() {
        OrientationHistory history = new OrientationHistory();
        // Thirty degrees a second, read every ten milliseconds.
        for (int i = 0; i <= 100; i++) {
            history.add(i * 10 * MILLIS, uprightFacing(i * 0.3f));
        }
        assertEquals(30f, history.speed(500 * MILLIS), 0.5f);
        // At the newest reading there is only history behind it, which is measured.
        assertEquals(30f, history.speed(1000 * MILLIS), 0.5f);
    }

    @Test
    public void speed_withoutHistory_isZero() {
        OrientationHistory history = new OrientationHistory();
        assertEquals(0f, history.speed(100 * MILLIS), 0f);
        history.add(100 * MILLIS, uprightFacing(0f));
        assertEquals(0f, history.speed(100 * MILLIS), 0f);
    }

    private static float yaw(float[] quaternion) {
        return SphereMathTest.backCamera(matrixOf(quaternion)).yaw();
    }
}
