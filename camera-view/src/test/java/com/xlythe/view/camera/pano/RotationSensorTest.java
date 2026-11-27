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

    /**
     * A rotation is trusted only while it turns as fast as the gyroscope says the device does:
     * one turning while the gyroscope says still is the fusion behind it settling.
     */
    @Test
    public void withAGyroscope_trustsOnlyARotationThatTurnsWithIt() {
        Sensor game = addSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        Sensor gyro = addSensor(Sensor.TYPE_GYROSCOPE);
        RotationSensor sensor = new RotationSensor(context());
        sensor.start();
        assertTrue(shadow().hasListener(sensor, game));
        assertTrue(shadow().hasListener(sensor, gyro));

        long now = SystemClock.elapsedRealtimeNanos();
        // A gyroscope event lands in the gyroscope's history, as degrees per second.
        shadow().sendSensorEventToListeners(SensorEventBuilder.newBuilder()
                .setSensor(gyro).setTimestamp(now).setValues(new float[] {0f, 0.5f, 0f})
                .build(), gyro);
        assertEquals(Math.toDegrees(0.5), sensor.gyroscope.average(now, now), 1e-3);
        assertTrue("a gyroscope event is no rotation", sensor.history.isEmpty());
        sensor.stop();

        long ms = 1_000_000L;
        // Thirty degrees a second.
        sensor.onReading(false, now + 100 * ms, vector(uprightFacing(0f)));
        sensor.onReading(false, now + 200 * ms, vector(uprightFacing(3f)));
        assertTrue("no gyroscope reading then says otherwise", sensor.isSettled(now + 150 * ms));

        sensor.onGyroscopeReading(now + 140 * ms, new float[] {0f, 0f, 0f});
        sensor.onGyroscopeReading(now + 160 * ms, new float[] {0f, 0f, 0f});
        assertFalse("the gyroscope says still", sensor.isSettled(now + 150 * ms));

        // Turning together, give or take the sensors' noise.
        float thirty = (float) Math.toRadians(30);
        sensor.onReading(false, now + 300 * ms, vector(uprightFacing(6f)));
        sensor.onGyroscopeReading(now + 240 * ms, new float[] {0f, thirty * 0.95f, 0f});
        sensor.onGyroscopeReading(now + 260 * ms, new float[] {0f, thirty * 1.05f, 0f});
        assertTrue(sensor.isSettled(now + 250 * ms));
    }

    @Test
    public void withoutAGyroscope_trustsEveryRotation() {
        addSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        RotationSensor sensor = new RotationSensor(context());
        sensor.onReading(false, 100_000_000L, vector(uprightFacing(0f)));
        sensor.onReading(false, 200_000_000L, vector(uprightFacing(30f)));
        sensor.onGyroscopeReading(150_000_000L, new float[] {0f, 0f, 0f});
        assertTrue(sensor.isSettled(150_000_000L));
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
