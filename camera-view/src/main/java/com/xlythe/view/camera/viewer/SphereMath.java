package com.xlythe.view.camera.viewer;

import android.opengl.Matrix;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;

/**
 * The geometry of looking around a sphere from its middle.
 *
 * <p>The world is east, north and up along x, y and z, as the rotation sensors have it. A
 * direction is a heading, clockwise from north, and a pitch above the horizon. The phone is
 * described the way the rotation sensors describe it, by the rotation that takes its own axes
 * into the world's: x to the right of the screen, y up it and z out of it, so the view looks
 * along its -z. That rotation's transpose is the view matrix, since the eye looks along -z
 * too.</p>
 *
 * <p>3x3 rotations are row-major, as {@link android.hardware.SensorManager} writes them; 4x4
 * matrices are column-major, as GL reads them.</p>
 */
final class SphereMath {
    /** How close to the eye the near plane is; inside the unit sphere the picture is on. */
    private static final float NEAR = 0.1f;

    /** How far the far plane is; outside it. */
    private static final float FAR = 10f;

    private SphereMath() {}

    /** A rotation about the up axis by an angle, anticlockwise seen from above. */
    @AnyThread
    @NonNull
    static float[] rotationZ(double degrees) {
        float cos = (float) Math.cos(Math.toRadians(degrees));
        float sin = (float) Math.sin(Math.toRadians(degrees));
        return new float[] {cos, -sin, 0, sin, cos, 0, 0, 0, 1};
    }

    /** A rotation about the x axis, tipping y towards z. */
    @AnyThread
    @NonNull
    static float[] rotationX(double degrees) {
        float cos = (float) Math.cos(Math.toRadians(degrees));
        float sin = (float) Math.sin(Math.toRadians(degrees));
        return new float[] {1, 0, 0, 0, cos, -sin, 0, sin, cos};
    }

    /** A rotation about the y axis, tipping z towards x. */
    @AnyThread
    @NonNull
    static float[] rotationY(double degrees) {
        float cos = (float) Math.cos(Math.toRadians(degrees));
        float sin = (float) Math.sin(Math.toRadians(degrees));
        return new float[] {cos, 0, sin, 0, 1, 0, -sin, 0, cos};
    }

    /** a times b, both 3x3 and row-major. */
    @AnyThread
    @NonNull
    static float[] multiply(@NonNull float[] a, @NonNull float[] b) {
        float[] product = new float[9];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                float sum = 0;
                for (int i = 0; i < 3; i++) {
                    sum += a[row * 3 + i] * b[i * 3 + column];
                }
                product[row * 3 + column] = sum;
            }
        }
        return product;
    }

    /**
     * The phone held upright and level, then turned to a heading and tipped up to a pitch: with
     * no rotation at all it lies flat facing the sky with its top to the north, so a quarter turn
     * about x stands it up facing north first.
     */
    @AnyThread
    @NonNull
    static float[] lookRotation(double headingDegrees, double pitchDegrees) {
        return multiply(rotationZ(-headingDegrees), rotationX(90 + pitchDegrees));
    }

    /** The heading a phone in this rotation looks along, in degrees from -180 to 180. */
    @AnyThread
    static double headingOf(@NonNull float[] rotation) {
        // It looks along its -z, which in the world is minus the rotation's third column.
        return Math.toDegrees(Math.atan2(-rotation[2], -rotation[5]));
    }

    /** The pitch it looks along, in degrees above the horizon. */
    @AnyThread
    static double pitchOf(@NonNull float[] rotation) {
        return Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, -rotation[8]))));
    }

    /** The unit vector for a heading and pitch. */
    @AnyThread
    static void direction(double headingDegrees, double pitchDegrees, @NonNull float[] out,
            int offset) {
        double heading = Math.toRadians(headingDegrees);
        double pitch = Math.toRadians(pitchDegrees);
        out[offset] = (float) (Math.sin(heading) * Math.cos(pitch));
        out[offset + 1] = (float) (Math.cos(heading) * Math.cos(pitch));
        out[offset + 2] = (float) Math.sin(pitch);
    }

    /**
     * Where a panorama's own directions point in the world: turned to its heading, tipped to its
     * pitch and rolled, so the middle of the full panorama lands on its pose.
     *
     * @return a 3x3 row-major rotation
     */
    @AnyThread
    @NonNull
    static float[] pose(@NonNull GPano gpano) {
        return multiply(multiply(rotationZ(-gpano.poseHeadingDegrees),
                rotationX(gpano.posePitchDegrees)), rotationY(gpano.poseRollDegrees));
    }

    /** A 3x3 row-major rotation as a 4x4 column-major matrix. */
    @AnyThread
    static void toGl(@NonNull float[] rotation, @NonNull float[] out) {
        Matrix.setIdentityM(out, 0);
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                out[column * 4 + row] = rotation[row * 3 + column];
            }
        }
    }

    /** The view matrix for a phone in a rotation: its transpose, as a 4x4 column-major. */
    @AnyThread
    static void view(@NonNull float[] rotation, @NonNull float[] out) {
        Matrix.setIdentityM(out, 0);
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                out[column * 4 + row] = rotation[column * 3 + row];
            }
        }
    }

    /**
     * The field of view across the view's height, given the one across its shorter side.
     */
    @AnyThread
    static double verticalFov(double shortSideFov, int width, int height) {
        if (height <= width) {
            return shortSideFov;
        }
        return Math.toDegrees(2 * Math.atan(
                Math.tan(Math.toRadians(shortSideFov / 2)) * height / Math.max(1, width)));
    }

    /** The field of view across the view's width, given the one across its shorter side. */
    @AnyThread
    static double horizontalFov(double shortSideFov, int width, int height) {
        if (width <= height) {
            return shortSideFov;
        }
        return Math.toDegrees(2 * Math.atan(
                Math.tan(Math.toRadians(shortSideFov / 2)) * width / Math.max(1, height)));
    }

    /** The field of view across the shorter side, given the one across the width. */
    @AnyThread
    static double shortSideFov(double horizontalFov, int width, int height) {
        if (width <= height) {
            return horizontalFov;
        }
        return Math.toDegrees(2 * Math.atan(
                Math.tan(Math.toRadians(horizontalFov / 2)) * height / Math.max(1, width)));
    }

    /** The perspective projection for a field of view across the shorter side. */
    @AnyThread
    static void projection(double shortSideFov, int width, int height, @NonNull float[] out) {
        Matrix.perspectiveM(out, 0, (float) verticalFov(shortSideFov, width, height),
                (float) width / Math.max(1, height), NEAR, FAR);
    }

    /** An angle in degrees, brought into -180 to 180. */
    @AnyThread
    static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped > 180) {
            wrapped -= 360;
        } else if (wrapped <= -180) {
            wrapped += 360;
        }
        return wrapped;
    }
}
