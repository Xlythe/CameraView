package com.xlythe.view.camera.gl;

import androidx.annotation.NonNull;

/**
 * Draws every camera frame on its way to the preview, the recording and the stills.
 *
 * <p>The library decides nothing about what this draws. It owns the context, the thread and the
 * frame; what goes on the screen is the caller's, which is the whole point of the hook - a tint, a
 * watermark, a segmented background, a face drawn over a face, or the camera frame and nothing
 * else.</p>
 *
 * <p>Every output is drawn the right way up as it is shown: the top of what is drawn is the top of
 * the preview on screen, of the recording as a player shows it and of the photograph as a gallery
 * shows it, and left is left, on every backend and from either lens. Only the camera's own frame
 * turns and mirrors to match the preview, and {@link GlFrame#drawCameraFrame()} takes care of
 * that. A renderer never needs to know which way the sensor faces, how the display is turned or
 * whether the lens is mirrored.</p>
 *
 * <p>Every method is called on the render thread with the GL context current and the output
 * surface bound, so GLES calls are safe inside them and nowhere else. The thread is the camera's:
 * work done here that takes longer than a frame interval costs frames on the preview and in the
 * recording.</p>
 */
public interface FrameRenderer {
    /**
     * Draws one frame into the output that is current.
     *
     * <p>The output has already been cleared. {@link GlFrame#drawCameraFrame()} puts the camera
     * frame in, so an overlay is that call followed by whatever goes on top.</p>
     *
     * @return whether this drew the frame. False has the library draw the camera frame as it
     *     normally would, which is how a renderer passes through the frames it has nothing to say
     *     about rather than having to copy them.
     */
    boolean onDrawFrame(@NonNull GlFrame frame);

    /**
     * The context exists and nothing has been drawn yet, which is where a renderer's own shaders,
     * textures and buffers belong - compiling a shader per frame is the usual way to make a
     * camera preview stutter.
     */
    default void onRendererStarted() {}

    /**
     * The context is about to go, so anything created in {@link #onRendererStarted} has to be
     * given back now; after this call there is no context to delete it with.
     */
    default void onRendererStopped() {}
}
