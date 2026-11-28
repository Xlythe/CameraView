package com.xlythe.view.camera.pano;

import androidx.annotation.NonNull;

/**
 * One frame kept from a sweep for stitching: how it was taken, and where it ends up pointing once
 * the frames are aligned against each other.
 *
 * <p>The camera's own axes are x across the frame as the sensor wrote it, y down it and z out
 * through the lens, as in {@link CameraPose}; its rotation into the world is the matrix whose
 * columns are those three axes in the world.</p>
 */
final class StitchFrame {
    /** The frame's width as kept. */
    final int width;

    /** Its height. */
    final int height;

    /** The focal length in the frame's pixels as kept. */
    final float focal;

    /** The clockwise turn that stands the frame upright. */
    final int sensorRotation;

    /** Whether it came from a lens facing the user. */
    final boolean frontFacing;

    /** The camera's rotation into the world, as the sensor put it. */
    final double[] sensorToWorld;

    /** The camera's rotation into the world, once aligned. Starts as the sensor's. */
    double[] toWorld;

    /** When it was taken, in wall-clock milliseconds. */
    final long timeMillis;

    /** Each colour channel's gain, red, green and blue, once exposure is matched. */
    final float[] gains = {1f, 1f, 1f};

    StitchFrame(@NonNull CameraPose pose, long timeMillis) {
        this.width = pose.width;
        this.height = pose.height;
        this.focal = pose.focal;
        this.sensorRotation = pose.sensorRotation;
        this.frontFacing = pose.frontFacing;
        this.sensorToWorld = new double[] {
                pose.right[0], pose.down[0], pose.forward[0],
                pose.right[1], pose.down[1], pose.forward[1],
                pose.right[2], pose.down[2], pose.forward[2]};
        this.toWorld = sensorToWorld.clone();
        this.timeMillis = timeMillis;
    }

    /** The world direction the lens looks along, once aligned. */
    void forward(@NonNull double[] out) {
        out[0] = toWorld[2];
        out[1] = toWorld[5];
        out[2] = toWorld[8];
    }

    /** The aligned pose, scaled to a frame of the given width, for drawing with. */
    @NonNull
    CameraPose pose(int scaledWidth, int scaledHeight) {
        float scale = (float) scaledWidth / width;
        // The device-to-world matrix the pose is built from, undoing the camera's axes.
        CameraPose pose = new CameraPose(new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1}, sensorRotation,
                frontFacing, scaledWidth, scaledHeight, focal * scale);
        for (int i = 0; i < 3; i++) {
            pose.right[i] = (float) toWorld[i * 3];
            pose.down[i] = (float) toWorld[i * 3 + 1];
            pose.forward[i] = (float) toWorld[i * 3 + 2];
        }
        return pose;
    }
}
