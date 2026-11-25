package com.xlythe.view.camera.ar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xlythe.view.camera.WorldOverlay;

/**
 * One tracked frame: where the camera is, what it can see, and how far away all of it is.
 *
 * <p>An interface, and deliberately made of nothing but floats. The implementation holds an
 * ARCore frame, but a library that exposed that type would force every app that only wants to
 * draw something onto the world to compile against ARCore - and would stop the library building
 * at all without it. Everything an overlay needs is here; an app that wants anchors, planes or
 * point clouds is past what this library is for and should drive ARCore itself.</p>
 *
 * <p>Valid only for the duration of the callback it arrives in. The matrices are the live
 * arrays rather than copies, and the depth is a view onto a buffer that is handed back when the
 * callback returns, so a reference kept past it describes a frame that no longer exists.</p>
 *
 * @see com.xlythe.view.camera.CameraView#setArFrameListener
 */
public interface ArFrame {
    /**
     * Where the camera is, as a 16-float column-major view matrix.
     *
     * <p>What {@link WorldOverlay#distanceFromCamera} takes - the number to compare a depth
     * reading against.</p>
     */
    @NonNull
    float[] getViewMatrix();

    /**
     * The projection matrix times the view matrix, 16 floats, column-major.
     *
     * <p>What {@link WorldOverlay#project} takes, and the one to use rather than multiplying
     * the two yourself: it is computed once per frame for the view being drawn into.</p>
     */
    @NonNull
    float[] getViewProjectionMatrix();

    /**
     * How far away the scene is, across the view, or null where this frame has no depth.
     *
     * <p>Null on a device without the depth API and for the first frames after tracking starts,
     * so an overlay that needs it has to carry on without it rather than wait - which is what
     * {@link WorldOverlay#isOccluded} does, by treating a missing reading as nothing in the
     * way.</p>
     */
    @Nullable
    WorldOverlay.SceneDepth getDepth();

    /**
     * When the camera captured this frame, in monotonic nanoseconds.
     *
     * <p>The same clock as {@link android.graphics.SurfaceTexture#getTimestamp}, so it is what
     * lines a pose up against anything else recorded alongside it.</p>
     */
    long getTimestampNanos();

    /**
     * The point in the world under a point in the view, or null if there is nothing there.
     *
     * <p>How something gets placed: a tap on the floor becomes a world position to stand a
     * marker at, and it stays there as the device moves because the position is in the world
     * rather than on the screen. Null when nothing tracked has been found along that direction
     * yet, which is ordinary in the first seconds and in a scene with no texture to track.</p>
     *
     * @param viewX pixels from the left edge of the view
     * @param viewY pixels from the top edge of the view
     * @return {x, y, z} in metres in the session's world, a new array, or null
     */
    @Nullable
    float[] hitTest(float viewX, float viewY);
}
