package com.xlythe.view.camera.gl;

import android.graphics.SurfaceTexture;

import androidx.annotation.NonNull;

/**
 * Creates the texture the camera writes its frames into, and is told as each frame lands on it.
 * Set one with {@link com.xlythe.view.camera.CameraView#setCameraTextureProvider}, for a consumer
 * whose API takes the name of a texture it made rather than one it is given.
 *
 * <p>Every method is called on the render thread with the GL context current, which is the only
 * thread the texture may be used on.</p>
 */
public interface CameraTextureProvider {
    /**
     * Creates the texture the camera writes into, and returns its name. Called once, before the
     * first frame.
     *
     * <p>Bind it as {@code GL_TEXTURE_EXTERNAL_OES}: a {@code GL_TEXTURE_2D} name is accepted here
     * and then fails when the camera writes to it.</p>
     */
    int onCreateCameraTexture();

    /**
     * A frame has arrived on the texture and has not been consumed yet. A consumer that advances
     * the texture itself does so here, before the frame is drawn. After this returns,
     * {@link SurfaceTexture#getTimestamp()} and {@link SurfaceTexture#getTransformMatrix} describe
     * the frame.
     *
     * @return whether this advanced the texture. False has {@link SurfaceTexture#updateTexImage()}
     *     called for you. True says the texture already holds the new frame, so it is not
     *     advanced a second time.
     */
    boolean onCameraFrameAvailable(@NonNull SurfaceTexture texture);

    /**
     * Lets go of the texture from {@link #onCreateCameraTexture}, which the library never deletes
     * itself. This is the last call on the render thread.
     */
    void onReleaseCameraTexture(int textureName);
}
