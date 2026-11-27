package com.xlythe.view.camera.pano;

import androidx.annotation.NonNull;

/**
 * Where each pixel of one frame was looking, for a camera held in a known rotation.
 *
 * <p>A frame arrives as the sensor wrote it, which for a phone held upright is on its side: the
 * frame has to be turned clockwise by the sensor's rotation to stand up. Once upright, its right is
 * the device's +x and its top the device's +y for a camera on the back, which looks along -z. A
 * camera on the front looks along +z, and since the frame is not mirrored its right is the device's
 * -x. Those three facts and the device's rotation say which way every pixel faces, without turning
 * or flipping a single one.</p>
 *
 * <p>Pixel coordinates are continuous: the frame spans 0 to width, and pixel i covers [i, i + 1).
 * The lens is assumed to be centred and free of distortion.</p>
 */
final class CameraPose {
    /** The frame's width, as the sensor wrote it. */
    final int width;

    /** The frame's height, as the sensor wrote it. */
    final int height;

    /** The focal length in pixels of this frame, the same across and down. */
    final float focal;

    /** The clockwise turn that stands the frame upright, one of 0, 90, 180 and 270. */
    final int sensorRotation;

    /** Whether the frame came from a lens facing the user. */
    final boolean frontFacing;

    /** The world direction the frame's +x runs in. Row 0 of the world-to-camera matrix. */
    final float[] right = new float[3];

    /** The world direction the frame's +y runs in, which is down the frame. Row 1. */
    final float[] down = new float[3];

    /** The world direction the lens looks in. Row 2. */
    final float[] forward = new float[3];

    /**
     * @param rotation the device-to-world matrix, row-major, when the frame was captured
     * @param sensorRotation the clockwise turn that stands the frame upright
     */
    CameraPose(@NonNull float[] rotation, int sensorRotation, boolean frontFacing,
            int width, int height, float focal) {
        this.width = width;
        this.height = height;
        this.focal = focal;
        this.sensorRotation = ((sensorRotation % 360) + 360) % 360;
        this.frontFacing = frontFacing;

        // Exact rather than through Math.cos, so a quarter turn leaves no 1e-17 behind.
        int cos;
        int sin;
        switch (this.sensorRotation) {
            case 90:
                cos = 0;
                sin = 1;
                break;
            case 180:
                cos = -1;
                sin = 0;
                break;
            case 270:
                cos = 0;
                sin = -1;
                break;
            default:
                cos = 1;
                sin = 0;
                break;
        }
        // The frame's axes in the device frame. Turning the frame clockwise by the rotation takes
        // its +x to (cos, sin) and its +y to (-sin, cos) in the upright picture, whose right and
        // down are the device's +x and -y - or -x and -y on the front, where the picture is the
        // lens's own view and so not mirrored.
        if (frontFacing) {
            SphereMath.rotate(rotation, -cos, -sin, 0, right);
            SphereMath.rotate(rotation, sin, -cos, 0, down);
            SphereMath.rotate(rotation, 0, 0, 1, forward);
        } else {
            SphereMath.rotate(rotation, cos, -sin, 0, right);
            SphereMath.rotate(rotation, -sin, -cos, 0, down);
            SphereMath.rotate(rotation, 0, 0, -1, forward);
        }
    }

    /**
     * The focal length in pixels of a frame of this size, from the angles of view of the whole
     * sensor.
     *
     * <p>A frame whose shape differs from the sensor's is a crop of it, taken across the full width
     * or the full height of the sensor and not both. Whichever one the frame spans is the one its
     * focal length is measured from.</p>
     *
     * @param horizontalFov degrees across the sensor's width, which is the frame's x
     * @param verticalFov degrees down the sensor's height
     * @return the focal length, or 0 when the angles are not known
     */
    static float focalLength(int width, int height, float horizontalFov, float verticalFov) {
        if (width <= 0 || height <= 0 || horizontalFov <= 0f || verticalFov <= 0f
                || horizontalFov >= 180f || verticalFov >= 180f) {
            return 0f;
        }
        double across = Math.tan(Math.toRadians(horizontalFov) / 2);
        double down = Math.tan(Math.toRadians(verticalFov) / 2);
        if ((double) width / height >= across / down) {
            return (float) (width / 2.0 / across);
        }
        return (float) (height / 2.0 / down);
    }

    /**
     * Where a world direction lands in the frame.
     *
     * @param out the x and y it lands at, which may be outside the frame
     * @return false if the direction is behind the lens, when there is no such point
     */
    boolean project(float east, float north, float up, @NonNull float[] out) {
        float z = forward[0] * east + forward[1] * north + forward[2] * up;
        if (z <= 1e-6f) {
            return false;
        }
        float x = right[0] * east + right[1] * north + right[2] * up;
        float y = down[0] * east + down[1] * north + down[2] * up;
        out[0] = width / 2f + focal * x / z;
        out[1] = height / 2f + focal * y / z;
        return true;
    }

    /** {@link #project(float, float, float, float[])} for a direction given as a unit vector. */
    boolean project(@NonNull float[] direction, @NonNull float[] out) {
        return project(direction[0], direction[1], direction[2], out);
    }

    /** The world direction a point of the frame looks in, as a unit vector. */
    void unproject(float x, float y, @NonNull float[] out) {
        float a = (x - width / 2f) / focal;
        float b = (y - height / 2f) / focal;
        float e = right[0] * a + down[0] * b + forward[0];
        float n = right[1] * a + down[1] * b + forward[1];
        float u = right[2] * a + down[2] * b + forward[2];
        float length = (float) Math.sqrt(e * e + n * n + u * u);
        out[0] = e / length;
        out[1] = n / length;
        out[2] = u / length;
    }

    /** Whether a point of the frame is inside it, at least {@code margin} pixels from each edge. */
    boolean contains(@NonNull float[] point, float margin) {
        return point[0] >= margin && point[0] <= width - margin
                && point[1] >= margin && point[1] <= height - margin;
    }

    /** The yaw the lens looks at. */
    float yaw() {
        return SphereMath.yaw(forward[0], forward[1]);
    }

    /** The pitch the lens looks at. */
    float pitch() {
        return SphereMath.pitch(forward[0], forward[1], forward[2]);
    }

    /** Whether the frame has to be turned a quarter to stand up, which swaps its sides. */
    boolean isSideways() {
        return sensorRotation == 90 || sensorRotation == 270;
    }

    /** The angle of view across the upright picture, which is what the person framing it saw. */
    float uprightHorizontalFov() {
        return angleOfView(isSideways() ? height : width);
    }

    /** The angle of view down the upright picture. */
    float uprightVerticalFov() {
        return angleOfView(isSideways() ? width : height);
    }

    /** The narrower of the two angles of view, whichever way the frame stands. */
    float narrowestFov() {
        return angleOfView(Math.min(width, height));
    }

    private float angleOfView(int pixels) {
        return (float) Math.toDegrees(2 * Math.atan(pixels / 2.0 / focal));
    }
}
