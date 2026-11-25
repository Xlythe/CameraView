package com.xlythe.view.camera.gl;

import android.graphics.SurfaceTexture;

import androidx.annotation.NonNull;

/**
 * Supplies the texture the camera renders into, and is told when a frame has landed on it.
 *
 * <p>This exists because the renderer generating its own texture is not good enough for everything
 * that wants camera frames. A library that tracks the world, segments a subject or runs its own
 * GL pipeline is often built to be handed a texture name that it created itself - it has to be,
 * because it owns the context the texture has to live in, or because its own API takes the name
 * rather than giving one out. ARCore's non-shared session is the example to hand:
 * {@code Session.setCameraTextureName(int)} takes a texture the caller made, and until it has one
 * {@code Session.update()} never returns a frame. A consumer like that cannot adopt a texture
 * this library already created, so the only seam that works is the camera rendering into theirs.
 * </p>
 *
 * <p>Everything else in the GL core generates its own texture, so this is a seam rather than the
 * way in: set it and the camera renders into your texture, leave it unset and nothing changes.</p>
 *
 * <p>Every method is called on the render thread with the GL context current. That is the only
 * thread the texture may be touched from, because it is the only one the context belongs to.</p>
 */
public interface CameraTextureProvider {
    /**
     * Creates the external texture the camera renders into, and returns its name.
     *
     * <p>Called once, before any frame arrives. The texture has to be bound as
     * {@code GL_TEXTURE_EXTERNAL_OES}, which is what both a {@link SurfaceTexture} and every
     * consumer of camera frames requires; a {@code GL_TEXTURE_2D} name is accepted here and then
     * fails when the camera writes to it.</p>
     */
    int onCreateCameraTexture();

    /**
     * A frame has arrived on the texture and has not been consumed yet.
     *
     * <p>This is the point at which a consumer that advances the texture itself gets to do so -
     * whatever call it is that reads the camera into its own pipeline goes here, before the
     * renderer draws. After this returns, {@link SurfaceTexture#getTimestamp()} and
     * {@link SurfaceTexture#getTransformMatrix} describe the frame that was consumed.</p>
     *
     * @return whether this advanced the texture. False has the renderer call
     *     {@link SurfaceTexture#updateTexImage()} itself, which is what a consumer that only wants
     *     to be told a frame exists should return. True says the texture already holds the new
     *     frame, so that a consumer whose own API consumed it does not have it consumed twice -
     *     the second call would either block or skip a frame.
     */
    boolean onCameraFrameAvailable(@NonNull SurfaceTexture texture);

    /**
     * Lets go of the texture from {@link #onCreateCameraTexture}.
     *
     * <p>The renderer does not delete a texture it did not create, because the consumer that made
     * it may still be using it, and deleting it out from under one is a crash in somebody else's
     * code. So deletion is here, and it is the last call on the render thread.</p>
     */
    void onReleaseCameraTexture(int textureName);
}
