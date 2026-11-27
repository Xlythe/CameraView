package com.xlythe.view.camera.gl;

import androidx.annotation.NonNull;

/**
 * Draws every camera frame on its way to the preview, the recording and the pictures. Set one with
 * {@link com.xlythe.view.camera.CameraView#setFrameRenderer}.
 *
 * <p>Every output is drawn the right way up as it is shown: the top of what is drawn is the top of
 * the preview on screen, of the recording as a player shows it and of the picture as a gallery
 * shows it, and left is left, from either lens and however the phone is held. Only the camera's
 * own frame turns and mirrors to match the preview, and {@link GlFrame#drawCameraFrame()} does
 * that.</p>
 *
 * <p>Every method is called on the render thread with the GL context current and the output
 * surface bound, so GLES calls are safe inside them and nowhere else. A frame that takes longer
 * than a frame interval to draw costs frames in the preview and the recording.</p>
 */
public interface FrameRenderer {
    /**
     * Draws one frame into the current output, which has already been cleared.
     * {@link GlFrame#drawCameraFrame()} draws the camera frame, so an overlay is that call followed
     * by whatever goes on top.
     *
     * @return whether this drew the frame. False draws the camera frame unchanged.
     */
    boolean onDrawFrame(@NonNull GlFrame frame);

    /**
     * Called once the GL context exists, before the first frame. Create shaders, textures and
     * buffers here rather than in {@link #onDrawFrame}.
     */
    default void onRendererStarted() {}

    /**
     * Called before the GL context goes. Delete what {@link #onRendererStarted} created: there is
     * no context once this returns.
     */
    default void onRendererStopped() {}
}
