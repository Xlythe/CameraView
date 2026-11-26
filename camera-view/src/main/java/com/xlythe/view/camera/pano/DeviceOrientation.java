package com.xlythe.view.camera.pano;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;
import androidx.annotation.Nullable;

import static com.xlythe.view.camera.ICameraModule.TAG;

/**
 * Which way the phone is pointed, in the frame a panorama is painted in.
 *
 * <p>Where the frames of a sweep get placed from, on any device. An AR session knows this far
 * better - it watches the scene as well as the sensors - but it needs ARCore and a certified
 * phone, and a panorama should not. The rotation vector is on every Android device and is
 * accurate enough for a sweep taken from one spot.</p>
 *
 * <h2>Two coordinate systems</h2>
 *
 * <p>Android reports rotation against East-North-Up: x east, y north, z straight up. Everything
 * in this package works in the frame a camera and an AR session use: x right, y up, and the
 * camera looking down its own negative z, with the world's negative z being the direction yaw
 * is measured from. Those are not the same axes, and reading one as the other gives a panorama
 * that is a quarter turn out and rotated on its side - which looks like a stitching fault
 * rather than a frame-of-reference one.</p>
 *
 * <p>So the conversion is {@link #toPanoramaFrame}, kept as a function of a matrix rather than
 * folded into the listener, because it is the half of this that can be checked without a
 * phone.</p>

 * <h2>Not part of the API</h2>
 *
 * <p>Where the phone is pointed, for the sweep that places frames by it. Restricted to the
 * library: an app that wants the rotation has the sensor itself, and what is here is only the
 * conversion into the frame this package paints in.</p>
 *
 * @hide
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class DeviceOrientation implements SensorEventListener {
    private final SensorManager mSensorManager;

    @Nullable
    private final Sensor mRotationVector;

    /** The newest reading, in the panorama frame. Written on the sensor thread. */
    private final float[] mMatrix = new float[16];

    /** Scratch for the reading as Android reports it, before it is converted. */
    private final float[] mEastNorthUp = new float[16];

    private volatile boolean mHasReading;

    public DeviceOrientation(@NonNull Context context) {
        mSensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        Sensor sensor = null;
        if (mSensorManager != null) {
            // The game rotation vector first: it leaves the magnetometer out, so it does not
            // swing when the phone passes a speaker or a car door, and a sweep only needs
            // rotation relative to where it started. The ordinary one is the fallback, since a
            // few devices have no game variant.
            sensor = mSensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
            if (sensor == null) {
                sensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            }
        }
        mRotationVector = sensor;
    }

    /** Whether this device can report its rotation at all. */
    public boolean isSupported() {
        return mRotationVector != null;
    }

    /** Starts listening. Call it when a sweep starts, and {@link #stop} when it ends. */
    public void start() {
        if (mSensorManager == null || mRotationVector == null) {
            Log.w(TAG, "This device cannot report its rotation, so it cannot sweep a panorama");
            return;
        }
        // The fastest rate the sensor offers: a sweep is placed per camera frame, and a reading
        // that is a frame old is a reading from a few degrees ago.
        mSensorManager.registerListener(this, mRotationVector, SensorManager.SENSOR_DELAY_GAME);
    }

    public void stop() {
        if (mSensorManager != null) {
            mSensorManager.unregisterListener(this);
        }
        mHasReading = false;
    }

    /**
     * The newest rotation, or false if none has arrived yet.
     *
     * @param out receives 16 floats, column-major, mapping a direction in the camera's frame to
     *     one in the world - which is what {@link Equirectangular#directionFor} takes
     */
    public boolean getRotationMatrix(@NonNull float[] out) {
        if (out.length < 16 || !mHasReading) {
            return false;
        }
        synchronized (mMatrix) {
            System.arraycopy(mMatrix, 0, out, 0, 16);
        }
        return true;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor != mRotationVector) {
            return;
        }
        synchronized (mMatrix) {
            SensorManager.getRotationMatrixFromVector(mEastNorthUp, event.values);
            toPanoramaFrame(mEastNorthUp, mMatrix);
        }
        mHasReading = true;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    /**
     * The same rotation, in the frame this package paints in.
     *
     * <p>Android gives a matrix that takes a direction in the device's own frame to one in
     * East-North-Up. What is wanted is one that takes a direction in the camera's frame to a
     * world where y is up and negative z is the direction yaw starts from.</p>
     *
     * <p>The camera's frame needs no conversion: the rear camera looks down the device's
     * negative z, with x to the right and y up the screen, which is already the convention. The
     * world does: east stays as x, up becomes y, and north becomes negative z - so a reading of
     * north is a yaw of zero, and a phone held upright looking at the horizon is level.</p>
     *
     * <p>Written out as a multiplication rather than through
     * {@code SensorManager.remapCoordinateSystem}, which remaps the device axes rather than the
     * world ones and is a different operation that is easy to mistake for this one.</p>
     *
     * @param eastNorthUp 16 floats, as {@code getRotationMatrixFromVector} fills in
     * @param out 16 floats, which may not be the same array
     */
    public static void toPanoramaFrame(@NonNull float[] eastNorthUp, @NonNull float[] out) {
        if (eastNorthUp.length < 16 || out.length < 16 || eastNorthUp == out) {
            throw new IllegalArgumentException(
                    "toPanoramaFrame needs two different 16-float matrices");
        }
        // Column-major, so element [column * 4 + row]. Each column of the result is a column of
        // the input with its rows reordered: east stays the x row, up becomes the y row, and
        // north becomes the negated z row.
        for (int column = 0; column < 4; column++) {
            float east = eastNorthUp[column * 4];
            float north = eastNorthUp[column * 4 + 1];
            float up = eastNorthUp[column * 4 + 2];
            out[column * 4] = east;
            out[column * 4 + 1] = up;
            out[column * 4 + 2] = -north;
            out[column * 4 + 3] = eastNorthUp[column * 4 + 3];
        }
    }
}
