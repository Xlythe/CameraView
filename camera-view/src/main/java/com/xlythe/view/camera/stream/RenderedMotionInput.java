package com.xlythe.view.camera.stream;

import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;

import com.xlythe.view.camera.gl.FrameRenderThread;
import com.xlythe.view.camera.gl.GlMath;

/**
 * Feeds a {@link MotionPhotoRecorder} through a renderer of its own, for frames the camera cannot
 * write into the encoder as they should be shown.
 *
 * <p>Two things send a camera's frames this way. A front camera's still is mirrored like its
 * preview and an encoder ignores the mirror a camera asks for, so the renderer mirrors the clip
 * the same way. And a still cropped to what the preview showed is matched by drawing the same part
 * of each frame. The frame is otherwise drawn as the camera wrote it, in the sensor's
 * orientation, because the clip is tagged with the turn that shows it upright; the mirror is taken
 * across the frame as it is shown, as the still's is.</p>
 */
@RequiresApi(21)
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class RenderedMotionInput {
    /** Draws each camera frame into the encoder. */
    private final FrameRenderThread mRenderer;
    /** The surface the camera writes into, over the renderer's texture. */
    private final Surface mSurface;
    /** Where the frames go. */
    private final MotionPhotoRecorder mRecorder;
    /** The size the camera writes. */
    private final Size mBufferSize;
    /** How the frames are turned clockwise to be shown, which the clip is tagged with. */
    @GlMath.Rotation
    private final int mRotationDegrees;
    /** Whether the frames are mirrored as they are shown. */
    private final boolean mMirrored;

    private RenderedMotionInput(@NonNull FrameRenderThread renderer, @NonNull Surface surface,
            @NonNull MotionPhotoRecorder recorder, @NonNull Size bufferSize,
            @GlMath.Rotation int rotationDegrees, boolean mirrored) {
        mRenderer = renderer;
        mSurface = surface;
        mRecorder = recorder;
        mBufferSize = bufferSize;
        mRotationDegrees = rotationDegrees;
        mMirrored = mirrored;
    }

    /**
     * Starts a renderer for frames of a size and has it draw every one into the recorder.
     *
     * @param rotationDegrees how the camera's buffer is turned clockwise to be shown
     * @param mirrored whether it is then mirrored, as a front camera's still is
     * @param cropToFollow whether nothing is drawn until {@link #setCrop} says what part of the
     *     frame to draw, rather than the whole frame from the start. Waiting saves starting an
     *     encoder for a size that is about to change.
     * @return the input, or null when GL will not run here
     * @throws IllegalStateException if the recorder cannot start an encoder
     */
    @MainThread
    @Nullable
    public static RenderedMotionInput start(@NonNull MotionPhotoRecorder recorder,
            @NonNull Size bufferSize, @GlMath.Rotation int rotationDegrees, boolean mirrored,
            boolean cropToFollow) {
        FrameRenderThread renderer = new FrameRenderThread("CameraViewMotionPhoto");
        if (!renderer.start()) {
            return null;
        }
        SurfaceTexture texture = renderer.getInputSurfaceTexture();
        if (texture == null) {
            renderer.stop();
            return null;
        }
        renderer.setInputBufferSize(bufferSize);
        RenderedMotionInput input = new RenderedMotionInput(renderer, new Surface(texture),
                recorder, bufferSize, rotationDegrees, mirrored);
        if (cropToFollow) {
            return input;
        }
        try {
            input.setCrop(null);
        } catch (IllegalStateException error) {
            input.release();
            throw error;
        }
        return input;
    }

    /** The surface the camera writes its frames into. */
    @NonNull
    public Surface getSurface() {
        return mSurface;
    }

    /**
     * Draws only part of each frame, as a still cropped to the preview shows only that part.
     *
     * @param crop the part, in the pixels of the buffer the camera writes, or null for all of it
     * @throws IllegalStateException if the recorder cannot start an encoder for the new size
     */
    @MainThread
    public void setCrop(@Nullable Rect crop) {
        Rect drawn = crop == null || crop.isEmpty()
                ? new Rect(0, 0, mBufferSize.getWidth(), mBufferSize.getHeight()) : crop;
        Size size = encodedSize(drawn.width(), drawn.height(), mRecorder.getMaxSize());
        Surface encoder = mRecorder.getInputSurface(size.getWidth(), size.getHeight(),
                mRotationDegrees, MotionPhotoRecorder.INPUT_RENDERED);
        float[] cropMatrix = cropMatrix(drawn, mBufferSize);
        float[] mirror = mMirrored ? GlMath.displayMirror(mRotationDegrees) : GlMath.identity();
        float[] afterBuffer = GlMath.multiply(cropMatrix, mirror);
        mRenderer.addEncoderOutput(this, encoder, size,
                (surfaceTextureMatrix, into) -> System.arraycopy(GlMath.multiply(
                        GlMath.withoutCameraTransform(surfaceTextureMatrix), afterBuffer),
                        0, into, 0, into.length),
                mRotationDegrees, mMirrored);
    }

    /** Stops the renderer and lets go of the camera's surface. The recorder is the owner's. */
    @MainThread
    public void release() {
        mRenderer.stop();
        mSurface.release();
    }

    /**
     * The size to encode a crop at: its own, scaled down to fit within the encoder's largest in
     * whichever orientation it has, and rounded to even sides, which every encoder takes.
     */
    @VisibleForTesting
    @NonNull
    static Size encodedSize(int width, int height, @NonNull Size max) {
        int maxLong = Math.max(max.getWidth(), max.getHeight());
        int maxShort = Math.min(max.getWidth(), max.getHeight());
        double scale = Math.min(1.0, Math.min(
                (double) maxLong / Math.max(width, height),
                (double) maxShort / Math.min(width, height)));
        int scaledWidth = (int) Math.round(width * scale) & ~1;
        int scaledHeight = (int) Math.round(height * scale) & ~1;
        return new Size(Math.max(2, scaledWidth), Math.max(2, scaledHeight));
    }

    /**
     * Takes the output's texture coordinates onto the part of the buffer the crop covers, in the
     * buffer as the camera wrote it. Texture coordinates count up from the bottom, as GL counts
     * them, while the crop counts its rows down from the top.
     */
    @VisibleForTesting
    @NonNull
    static float[] cropMatrix(@NonNull Rect crop, @NonNull Size bufferSize) {
        float width = bufferSize.getWidth();
        float height = bufferSize.getHeight();
        float[] matrix = GlMath.identity();
        matrix[0] = crop.width() / width;
        matrix[5] = crop.height() / height;
        matrix[12] = crop.left / width;
        matrix[13] = (height - crop.bottom) / height;
        return matrix;
    }
}
