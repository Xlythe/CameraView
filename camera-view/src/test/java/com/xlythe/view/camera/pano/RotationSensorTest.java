package com.xlythe.view.camera.pano;

import static com.xlythe.view.camera.pano.SphereMathTest.uprightFacing;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.SystemClock;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.SensorEventBuilder;
import org.robolectric.shadows.ShadowSensor;
import org.robolectric.shadows.ShadowSensorManager;

/** Which sensor a sweep is placed by, and how a compass heading is put on it. */
@RunWith(RobolectricTestRunner.class)
public class RotationSensorTest {
    @Test
    public void withoutARotationSensor_isUnavailable() {
        RotationSensor sensor = new RotationSensor(context());
        assertFalse(sensor.isAvailable());
        sensor.start();
        sensor.stop();
        assertTrue(Float.isNaN(sensor.getHeadingOffset()));
    }

    @Test
    public void withOnlyTheCompassedSensor_placesByTheCompass() {
        Sensor full = addSensor(Sensor.TYPE_ROTATION_VECTOR);
        RotationSensor sensor = new RotationSensor(context());
        assertTrue(sensor.isAvailable());
        assertEquals(0f, sensor.getHeadingOffset(), 0f);
        sensor.start();
        assertTrue(shadow().hasListener(sensor, full));
        sensor.stop();
        assertFalse(shadow().hasListener(sensor));
    }

    @Test
    public void withBoth_listensToBoth_andPlacesByTheGyroscope() {
        Sensor game = addSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        Sensor full = addSensor(Sensor.TYPE_ROTATION_VECTOR);
        RotationSensor sensor = new RotationSensor(context());
        sensor.start();
        assertTrue(shadow().hasListener(sensor, game));
        assertTrue(shadow().hasListener(sensor, full));
        // Starting twice registers nothing more.
        sensor.start();

        long now = SystemClock.elapsedRealtimeNanos();
        shadow().sendSensorEventToListeners(SensorEventBuilder.newBuilder()
                .setSensor(game).setTimestamp(now).setValues(vector(uprightFacing(10f)))
                .build(), game);
        assertFalse(sensor.history.isEmpty());
        assertEquals(now, sensor.history.newestTime());
        // Until the two have both been read, the heading is not known.
        assertTrue(Float.isNaN(sensor.getHeadingOffset()));

        shadow().sendSensorEventToListeners(SensorEventBuilder.newBuilder()
                .setSensor(full).setTimestamp(now).setValues(vector(uprightFacing(40f)))
                .build(), full);
        assertEquals(30f, sensor.getHeadingOffset(), 0.01f);
        sensor.stop();
        sensor.stop();
    }

    @Test
    public void headingOffset_isACircularMean() {
        addSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        addSensor(Sensor.TYPE_ROTATION_VECTOR);
        RotationSensor sensor = new RotationSensor(context());
        // A compass reading before any gyroscope reading has nothing to compare against.
        sensor.onReading(true, 1, vector(uprightFacing(0f)));
        assertTrue(Float.isNaN(sensor.getHeadingOffset()));
        sensor.onReading(false, 2, vector(uprightFacing(0f)));
        // Offsets either side of north average to north, not to south.
        sensor.onReading(true, 3, vector(uprightFacing(-170f)));
        sensor.onReading(true, 4, vector(uprightFacing(170f)));
        assertEquals(180f, Math.abs(sensor.getHeadingOffset()), 0.01f);
    }

    @Test
    public void readingsWithoutW_areAccepted() {
        addSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        RotationSensor sensor = new RotationSensor(context());
        float[] quaternion = uprightFacing(20f);
        sensor.onReading(false, 5, new float[] {quaternion[1], quaternion[2], quaternion[3]});
        float[] read = new float[4];
        assertTrue(sensor.history.sample(5, read));
        assertEquals(Math.abs(quaternion[0]), Math.abs(read[0]), 1e-4f);
    }

    /** A quaternion as a rotation vector sensor reports it: x, y, z, then w. */
    static float[] vector(float[] wxyz) {
        return new float[] {wxyz[1], wxyz[2], wxyz[3], wxyz[0]};
    }

    static Sensor addSensor(int type) {
        Sensor sensor = ShadowSensor.newInstance(type);
        shadow().addSensor(sensor);
        return sensor;
    }

    private static ShadowSensorManager shadow() {
        return Shadows.shadowOf(
                (SensorManager) context().getSystemService(Context.SENSOR_SERVICE));
    }

    private static Context context() {
        return RuntimeEnvironment.getApplication();
    }
}
