package com.xlythe.view.camera.pano;

import static com.xlythe.view.camera.pano.FootprintTest.rolled;
import static com.xlythe.view.camera.pano.SphereMathTest.matrixOf;
import static com.xlythe.view.camera.pano.SphereMathTest.uprightFacing;

import java.util.ArrayList;
import java.util.List;

/**
 * Frames of {@link TestScene} for the stitching tests: each rendered from where the camera really
 * pointed, and each placed where a sensor that was some way off said it pointed.
 */
final class StitchScene {
    /** Frames as the sensor wrote them: landscape, turned a quarter to stand up. */
    static final int WIDTH = 640;

    /** See {@link #WIDTH}. */
    static final int HEIGHT = 480;

    /** 60 degrees across the sensor's width. */
    static final float FOCAL = (float) (WIDTH / 2 / Math.tan(Math.toRadians(30)));

    /** The frames' rotations as the sensor gave them. */
    final List<StitchFrame> frames = new ArrayList<>();

    /** What each frame really shows. */
    final List<ArgbImage> images = new ArrayList<>();

    /** Where each frame really pointed, as a camera pose. */
    final List<CameraPose> truth = new ArrayList<>();

    /**
     * Adds a frame.
     *
     * @param yaw where it really pointed
     * @param yawError how far off the sensor was about the vertical
     * @param pitchError how far off it was in pitch
     */
    StitchScene add(float yaw, float pitch, float roll, float yawError, float pitchError) {
        CameraPose real = pose(yaw, pitch, roll);
        truth.add(real);
        images.add(TestScene.render(real));
        frames.add(new StitchFrame(pose(yaw + yawError, pitch + pitchError, roll),
                frames.size()));
        return this;
    }

    /** Supplies the frames' pixels. */
    Stitcher.Frames source() {
        return images::get;
    }

    static CameraPose pose(float yaw, float pitch, float roll) {
        return new CameraPose(matrixOf(rolled(uprightFacing(yaw, pitch), roll)), 90, false,
                WIDTH, HEIGHT, FOCAL);
    }

    /** The true rotation into the world of a frame's camera, as a StitchFrame would hold it. */
    double[] trueToWorld(int frame) {
        return new StitchFrame(truth.get(frame), 0).sensorToWorld;
    }
}
