package com.xlythe.view.camera.gl;

import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;

/**
 * What is known about the optics and the position of the camera that wrote one frame.
 *
 * <p>Filled in on the render thread, once per frame, by whoever knows: the lens calibration the
 * camera publishes, or ARCore while it is tracking. {@link GlFrame} turns it into the matrices a
 * renderer draws with, for each output in turn, because the same optics project differently onto
 * a preview, a recording and a still that crop and turn the frame differently.</p>
 *
 * <p>Reused for every frame rather than allocated, so it is only valid on the render thread and
 * only until the next frame arrives.</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class FrameGeometry {
    /**
     * The pinhole the buffer was taken through, as {@link GlMath#projection} reads it: focal
     * lengths, principal point and skew, each divided by the buffer's width or height.
     */
    private final float[] mIntrinsics = new float[GlMath.INTRINSICS_SIZE];

    /** Whether {@link #mIntrinsics} holds anything, which it does not for an unknown camera. */
    private boolean mHasIntrinsics;

    /** Into the camera's space, from the world while AR tracks and from the device otherwise. */
    private final float[] mViewMatrix = GlMath.identity();

    /** Whether {@link #mViewMatrix} holds anything. */
    private boolean mHasViewMatrix;

    /** The texture holding this frame's depth, or 0 when there is none. */
    private int mDepthTextureName;

    /** Forgets everything, which is where every frame starts. */
    public void clear() {
        mHasIntrinsics = false;
        mHasViewMatrix = false;
        mDepthTextureName = 0;
    }

    /**
     * The camera's intrinsics, already divided by the buffer's size, in the order
     * {@link GlMath#projection} takes them.
     */
    public void setNormalizedIntrinsics(@NonNull float[] intrinsics) {
        System.arraycopy(intrinsics, 0, mIntrinsics, 0, GlMath.INTRINSICS_SIZE);
        mHasIntrinsics = true;
    }

    public boolean hasIntrinsics() {
        return mHasIntrinsics;
    }

    /** The intrinsics as {@link GlMath#projection} takes them. Read it; do not keep it. */
    @NonNull
    public float[] getIntrinsics() {
        return mIntrinsics;
    }

    /** Copies in the 4x4 column-major matrix into the camera's space. */
    public void setViewMatrix(@NonNull float[] matrix) {
        System.arraycopy(matrix, 0, mViewMatrix, 0, GlMath.MATRIX_SIZE);
        mHasViewMatrix = true;
    }

    public boolean hasViewMatrix() {
        return mHasViewMatrix;
    }

    /** The view matrix. Read it; do not keep it. */
    @NonNull
    public float[] getViewMatrix() {
        return mViewMatrix;
    }

    public void setDepthTextureName(int textureName) {
        mDepthTextureName = textureName;
    }

    public int getDepthTextureName() {
        return mDepthTextureName;
    }
}
