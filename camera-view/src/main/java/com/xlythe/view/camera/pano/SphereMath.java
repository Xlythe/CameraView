package com.xlythe.view.camera.pano;

import androidx.annotation.NonNull;

/**
 * Rotations and directions on the sphere a sweep is painted onto.
 *
 * <p>The world frame is the one Android's rotation sensors report against: x points east, y north
 * and z up. A direction in it is named by its yaw, in degrees clockwise from north, and its pitch,
 * in degrees above the horizon.</p>
 *
 * <p>Rotation matrices are 3x3 and row-major, which is how
 * {@code SensorManager.getRotationMatrixFromVector} fills them, and they turn a vector in the
 * device's own frame into the world's. Element {@code [3 * row + column]} is the world's
 * {@code row} component of the device's {@code column} axis, so a device axis is read down a
 * column, not along a row.</p>
 */
final class SphereMath {
    /** Below this, two quaternions are close enough that a straight blend is as good as a slerp. */
    private static final double SLERP_THRESHOLD = 0.9995;

    private SphereMath() {}

    /**
     * Reads a rotation vector sensor's values as a unit quaternion.
     *
     * <p>The sensor reports x, y and z, and only sometimes w: older devices leave it out, and it
     * is then whatever makes the quaternion unit length, as {@code SensorManager} works it out.</p>
     *
     * @param out w, x, y, z
     */
    static void quaternionFromRotationVector(@NonNull float[] values, @NonNull float[] out) {
        float x = values[0];
        float y = values[1];
        float z = values[2];
        float w;
        if (values.length >= 4) {
            w = values[3];
        } else {
            float squared = 1f - x * x - y * y - z * z;
            w = squared > 0f ? (float) Math.sqrt(squared) : 0f;
        }
        out[0] = w;
        out[1] = x;
        out[2] = y;
        out[3] = z;
        normalize4(out);
    }

    /**
     * The device-to-world matrix for a quaternion, row-major, element for element what
     * {@code SensorManager.getRotationMatrixFromVector} returns for the same rotation.
     */
    static void rotationMatrix(@NonNull float[] wxyz, @NonNull float[] out) {
        float w = wxyz[0];
        float x = wxyz[1];
        float y = wxyz[2];
        float z = wxyz[3];
        out[0] = 1f - 2f * (y * y + z * z);
        out[1] = 2f * (x * y - z * w);
        out[2] = 2f * (x * z + y * w);
        out[3] = 2f * (x * y + z * w);
        out[4] = 1f - 2f * (x * x + z * z);
        out[5] = 2f * (y * z - x * w);
        out[6] = 2f * (x * z - y * w);
        out[7] = 2f * (y * z + x * w);
        out[8] = 1f - 2f * (x * x + y * y);
    }

    /**
     * The rotation a fraction {@code t} of the way from {@code a} to {@code b}, both w, x, y, z.
     *
     * <p>Taken the short way round: q and -q are the same rotation, and blending towards the
     * wrong one of them turns the long way, through everything in between.</p>
     */
    static void slerp(@NonNull float[] a, @NonNull float[] b, float t, @NonNull float[] out) {
        double dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
        double sign = 1;
        if (dot < 0) {
            dot = -dot;
            sign = -1;
        }
        double fromA;
        double fromB;
        if (dot > SLERP_THRESHOLD) {
            fromA = 1 - t;
            fromB = t;
        } else {
            double angle = Math.acos(dot);
            double sin = Math.sin(angle);
            fromA = Math.sin((1 - t) * angle) / sin;
            fromB = Math.sin(t * angle) / sin;
        }
        fromB *= sign;
        for (int i = 0; i < 4; i++) {
            out[i] = (float) (fromA * a[i] + fromB * b[i]);
        }
        normalize4(out);
    }

    /** How far apart two rotations are, in degrees, whichever axis separates them. */
    static float angleBetweenRotations(@NonNull float[] a, @NonNull float[] b) {
        double dot = Math.abs(a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]);
        return (float) Math.toDegrees(2 * Math.acos(Math.min(1, dot)));
    }

    /** {@code rotation} applied to (x, y, z): a device-frame vector, in world coordinates. */
    static void rotate(@NonNull float[] rotation, float x, float y, float z, @NonNull float[] out) {
        float e = rotation[0] * x + rotation[1] * y + rotation[2] * z;
        float n = rotation[3] * x + rotation[4] * y + rotation[5] * z;
        float u = rotation[6] * x + rotation[7] * y + rotation[8] * z;
        out[0] = e;
        out[1] = n;
        out[2] = u;
    }

    /** Degrees clockwise from north, in (-180, 180]. Zero for a direction straight up or down. */
    static float yaw(float east, float north) {
        if (east == 0f && north == 0f) {
            return 0f;
        }
        return wrap((float) Math.toDegrees(Math.atan2(east, north)));
    }

    /** Degrees above the horizon, from -90 to 90. */
    static float pitch(float east, float north, float up) {
        return (float) Math.toDegrees(Math.atan2(up, Math.hypot(east, north)));
    }

    /** The unit vector for a yaw and a pitch. */
    static void direction(float yawDegrees, float pitchDegrees, @NonNull float[] out) {
        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(pitchDegrees);
        double horizontal = Math.cos(pitch);
        out[0] = (float) (horizontal * Math.sin(yaw));
        out[1] = (float) (horizontal * Math.cos(yaw));
        out[2] = (float) Math.sin(pitch);
    }

    /** An angle brought into (-180, 180], so that two yaws can be compared across north. */
    static float wrap(float degrees) {
        float wrapped = degrees % 360f;
        if (wrapped <= -180f) {
            wrapped += 360f;
        } else if (wrapped > 180f) {
            wrapped -= 360f;
        }
        return wrapped;
    }

    /** The angle between two unit vectors, in degrees. */
    static float angleBetween(@NonNull float[] a, @NonNull float[] b) {
        double dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
        return (float) Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot))));
    }

    /**
     * How far the {@code reference} world is turned about the vertical from the
     * {@code relative} one, both given as the device-to-world matrix of the same pose.
     *
     * <p>Used to put a compass heading on a sweep that was placed without the compass: the
     * difference between the two readings of one pose is the turn between their two norths.</p>
     *
     * @return degrees to add to a yaw in the relative world to get one in the reference world
     */
    static float headingOffset(@NonNull float[] reference, @NonNull float[] relative) {
        // The relative world's north, taken back into the device frame by the transpose of the
        // relative matrix, and out again into the reference world.
        float x = relative[3];
        float y = relative[4];
        float z = relative[5];
        float east = reference[0] * x + reference[1] * y + reference[2] * z;
        float north = reference[3] * x + reference[4] * y + reference[5] * z;
        return yaw(east, north);
    }

    private static void normalize4(@NonNull float[] q) {
        double length = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        if (length == 0) {
            q[0] = 1f;
            return;
        }
        for (int i = 0; i < 4; i++) {
            q[i] = (float) (q[i] / length);
        }
    }
}
