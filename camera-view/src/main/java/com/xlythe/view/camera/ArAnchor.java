package com.xlythe.view.camera;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;

import com.xlythe.view.camera.gl.GlMath;

/**
 * A place in the room that AR keeps track of as the phone moves, made by
 * {@link CameraView#addArAnchor}.
 *
 * <p>Its model matrix stands something there. A {@link com.xlythe.view.camera.gl.FrameRenderer}
 * draws it with the frame's own matrices, {@code projection * view * model}, from
 * {@link com.xlythe.view.camera.gl.GlFrame#getProjectionMatrix} and
 * {@link com.xlythe.view.camera.gl.GlFrame#getViewMatrix}. The place is refined as AR learns more
 * about the room, so read the matrix again for every frame rather than keeping it.</p>
 *
 * <p>Safe to read from any thread.</p>
 */
public final class ArAnchor {
    /** From the anchor's space into the world's, column-major, as of the last tracked frame. */
    private final float[] mModelMatrix = GlMath.identity();

    /** Whether {@link #mModelMatrix} describes the frame being drawn. */
    private boolean mTracking;

    /** Whether the app has let go of the anchor, or AR has ended. Never cleared. */
    private boolean mDetached;

    @RestrictTo(RestrictTo.Scope.LIBRARY)
    public ArAnchor() {}

    /**
     * Copies the transform from the anchor's space into the world's: 4x4, column-major, in metres,
     * with y pointing up against gravity.
     *
     * @return whether AR knows where the anchor is right now. False, leaving the matrix untouched,
     *     while it is not tracking, and for good once the anchor is detached
     * @throws IllegalArgumentException if the matrix is shorter than sixteen floats
     */
    @AnyThread
    public synchronized boolean getModelMatrix(@NonNull float[] matrix) {
        if (matrix.length < GlMath.MATRIX_SIZE) {
            throw new IllegalArgumentException(
                    "A matrix is " + GlMath.MATRIX_SIZE + " floats, not " + matrix.length);
        }
        if (!mTracking) {
            return false;
        }
        System.arraycopy(mModelMatrix, 0, matrix, 0, GlMath.MATRIX_SIZE);
        return true;
    }

    /** Whether AR knows where the anchor is right now. */
    @AnyThread
    public synchronized boolean isTracking() {
        return mTracking;
    }

    /**
     * Stops tracking the anchor, which costs AR a little on every frame. An anchor is also let go
     * of when AR ends.
     */
    @AnyThread
    public synchronized void detach() {
        mDetached = true;
        mTracking = false;
    }

    /** Whether the anchor has been let go of, so the tracker stops following it. */
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    @AnyThread
    public synchronized boolean isDetached() {
        return mDetached;
    }

    /**
     * Records where the anchor is for the frame just tracked, or that it is not known.
     *
     * @param modelMatrix from the anchor's space into the world's, or null while untracked
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    @AnyThread
    public synchronized void onPose(@Nullable float[] modelMatrix) {
        if (mDetached) {
            return;
        }
        if (modelMatrix == null) {
            mTracking = false;
            return;
        }
        System.arraycopy(modelMatrix, 0, mModelMatrix, 0, GlMath.MATRIX_SIZE);
        mTracking = true;
    }
}
