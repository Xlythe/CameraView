package com.xlythe.view.camera.pano;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The device's rotation, read into an {@link OrientationHistory} for as long as a sweep runs.
 *
 * <p>Frames are placed with the game rotation vector where there is one. It is the gyroscope
 * and the accelerometer fused, with no compass, so it drifts slowly about the vertical but never
 * jumps - and a jump of a few degrees halfway round a room, when the compass passes a steel beam,
 * is a seam no blending hides, where a slow drift spreads over the whole sweep. The full rotation
 * vector is read alongside it only to learn how far its north is from the compass's, which is
 * what puts a real heading in the saved metadata.</p>
 *
 * <p>Events arrive on a thread of their own, so a busy main thread cannot hold them back.</p>
 */
final class RotationSensor implements SensorEventListener {
    /** A reading every ten milliseconds, so a frame is never more than five from one. */
    private static final int SAMPLING_PERIOD_MICROS = 10_000;

    /** The compass is only consulted for a heading, which a reading a tenth of a second does. */
    private static final int COMPASS_PERIOD_MICROS = 100_000;

    /** Where the rotation is kept for the sweep to look up. */
    final OrientationHistory history = new OrientationHistory();

    /** The sensor frames are placed by, or null on a device without one. */
    @Nullable private final Sensor mPlacement;

    /** The compass-referenced sensor read alongside it, or null when placement already is one. */
    @Nullable private final Sensor mCompass;

    /** Null when neither rotation sensor exists. */
    @Nullable private final SensorManager mSensorManager;

    /** The newest placement reading, for comparing a compass reading against. Guarded by this. */
    private final float[] mLatest = new float[4];

    /** Whether {@link #mLatest} holds a reading yet. Guarded by this. */
    private boolean mHasLatest;

    /**
     * The compass offsets seen so far, as the sums of their sines and cosines: the mean of a
     * set of angles that straddle north is not their arithmetic mean. Guarded by this.
     */
    private double mOffsetSin;

    /** See {@link #mOffsetSin}. Guarded by this. */
    private double mOffsetCos;

    /** The thread events arrive on, while listening. */
    @Nullable private HandlerThread mThread;

    RotationSensor(@NonNull Context context) {
        mSensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        Sensor game = mSensorManager == null
                ? null : mSensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        Sensor full = mSensorManager == null
                ? null : mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        mPlacement = game != null ? game : full;
        mCompass = game != null ? full : null;
    }

    /** Whether the device can report its rotation at all. */
    boolean isAvailable() {
        return mPlacement != null;
    }

    /** Starts listening. Does nothing on a device without a rotation sensor. */
    void start() {
        if (mPlacement == null || mSensorManager == null || mThread != null) {
            return;
        }
        HandlerThread thread = new HandlerThread("CameraView-Rotation");
        thread.start();
        mThread = thread;
        Handler handler = new Handler(thread.getLooper());
        mSensorManager.registerListener(this, mPlacement, SAMPLING_PERIOD_MICROS, handler);
        if (mCompass != null) {
            mSensorManager.registerListener(this, mCompass, COMPASS_PERIOD_MICROS, handler);
        }
    }

    /** Stops listening. The history stays readable. */
    void stop() {
        HandlerThread thread = mThread;
        if (thread == null || mSensorManager == null) {
            return;
        }
        mThread = null;
        mSensorManager.unregisterListener(this);
        thread.quitSafely();
    }

    /**
     * Degrees to add to a yaw the sweep was placed by to get a compass heading.
     *
     * @return the offset, 0 when placement is already by compass, or NaN when it is unknown
     */
    @AnyThread
    synchronized float getHeadingOffset() {
        if (mCompass == null) {
            return mPlacement != null && mPlacement.getType() == Sensor.TYPE_ROTATION_VECTOR
                    ? 0f : Float.NaN;
        }
        if (mOffsetSin == 0 && mOffsetCos == 0) {
            return Float.NaN;
        }
        return (float) Math.toDegrees(Math.atan2(mOffsetSin, mOffsetCos));
    }

    @Override
    public void onSensorChanged(@NonNull SensorEvent event) {
        long time = TimeBase.toElapsedRealtime(
                event.timestamp, SystemClock.elapsedRealtimeNanos(), System.nanoTime());
        onReading(event.sensor == mCompass, time, event.values);
    }

    /** Takes one reading from either sensor. */
    void onReading(boolean compass, long timeNanos, @NonNull float[] values) {
        float[] quaternion = new float[4];
        SphereMath.quaternionFromRotationVector(values, quaternion);
        if (!compass) {
            history.add(timeNanos, quaternion);
            synchronized (this) {
                System.arraycopy(quaternion, 0, mLatest, 0, 4);
                mHasLatest = true;
            }
            return;
        }
        synchronized (this) {
            if (!mHasLatest) {
                return;
            }
            float[] reference = new float[9];
            float[] relative = new float[9];
            SphereMath.rotationMatrix(quaternion, reference);
            SphereMath.rotationMatrix(mLatest, relative);
            double offset = Math.toRadians(SphereMath.headingOffset(reference, relative));
            mOffsetSin += Math.sin(offset);
            mOffsetCos += Math.cos(offset);
        }
    }

    @Override
    public void onAccuracyChanged(@NonNull Sensor sensor, int accuracy) {}
}
