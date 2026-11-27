package com.xlythe.view.camera.gl;

import androidx.annotation.NonNull;

/**
 * One camera frame, as the renderer has it, handed to a {@link FrameRenderer} about to draw.
 *
 * <p>Valid only for the duration of the call it arrives in. The renderer reuses one instance for
 * every frame and every output rather than allocating thirty a second, so a reference kept past
 * the callback describes a different frame by the time it is read. The matrix is the renderer's
 * own array for the same reason: read it, do not keep it, and do not write to it.</p>
 */
public final class GlFrame {
    private final OesTextureProgram mProgram;
    private int mTextureName;
    private float[] mTextureMatrix = GlMath.identity();
    private int mWidth;
    private int mHeight;
    private long mTimestampNanos;
    private int[] mViewport = {0, 0, 0, 0};

    GlFrame(@NonNull OesTextureProgram program) {
        mProgram = program;
    }

    void set(int textureName, @NonNull float[] textureMatrix, int width, int height,
            long timestampNanos, @NonNull int[] viewport) {
        mTextureName = textureName;
        mTextureMatrix = textureMatrix;
        mWidth = width;
        mHeight = height;
        mTimestampNanos = timestampNanos;
        mViewport = viewport;
    }

    /**
     * The {@code GL_TEXTURE_EXTERNAL_OES} texture the camera wrote this frame into.
     *
     * <p>Bind it to that target, not to {@code GL_TEXTURE_2D}, and sample it with
     * {@code samplerExternalOES}: the format underneath is the driver's own.</p>
     */
    public int getTextureName() {
        return mTextureName;
    }

    /**
     * The 4x4 column-major matrix that turns this frame's texture coordinates into the ones to
     * sample, which is where the rotation, the mirror and the crop are.
     *
     * <p>Apply it to a {@code vec4} coordinate in a vertex shader and use the {@code xy} of the
     * result, as {@link #drawCameraFrame} does. Ignoring it draws the raw buffer, which is
     * sideways on most devices and backwards on a front camera.</p>
     */
    @NonNull
    public float[] getTextureMatrix() {
        return mTextureMatrix;
    }

    /**
     * The width in pixels of the picture being drawn, the right way up as it is shown. An output
     * held some other way round is drawn through a picture this shape and turned onto it.
     */
    public int getWidth() {
        return mWidth;
    }

    /** The height in pixels of the picture being drawn, the right way up as it is shown. */
    public int getHeight() {
        return mHeight;
    }

    /**
     * When the camera captured this frame, on the same clock as
     * {@link android.graphics.SurfaceTexture#getTimestamp}.
     *
     * <p>Monotonic nanoseconds, so it is the clock to line other sensor readings up against -
     * which is what anything tracking the world through the camera needs.</p>
     */
    public long getTimestampNanos() {
        return mTimestampNanos;
    }

    /**
     * Draws the camera frame as the library would, filling the output.
     *
     * <p>Here so that drawing over the frame does not mean reimplementing the frame. A renderer
     * that wants an overlay calls this first and then draws its own geometry; one that is replacing
     * the frame does not call it at all. It leaves no program or texture bound.</p>
     */
    public void drawCameraFrame() {
        mProgram.draw(mTextureName, mTextureMatrix, mViewport);
    }
}
