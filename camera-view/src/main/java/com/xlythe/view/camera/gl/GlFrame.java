package com.xlythe.view.camera.gl;

import androidx.annotation.NonNull;

/**
 * One camera frame, handed to {@link FrameRenderer#onDrawFrame}.
 *
 * <p>Valid only during that call: the same instance describes the next frame, and the matrix is
 * the library's own array. Read them there, and neither keep nor write to them.</p>
 */
public final class GlFrame {
    // One instance describes every frame for every output, so these change between calls.

    /** Draws the camera frame for {@link #drawCameraFrame}. */
    private final OesTextureProgram mProgram;

    /** The external texture the camera wrote this frame into. */
    private int mTextureName;

    /** Turns the frame's texture coordinates into the ones to sample. */
    private float[] mTextureMatrix = GlMath.identity();

    /** The width of the picture being drawn, the right way up. */
    private int mWidth;

    /** The height of the picture being drawn, the right way up. */
    private int mHeight;

    /** When the camera captured the frame, in monotonic nanoseconds. */
    private long mTimestampNanos;

    /** Where in the output the camera frame goes, as x, y, width and height. */
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
     * The {@code GL_TEXTURE_EXTERNAL_OES} texture the camera wrote this frame into. Bind it to that
     * target, not to {@code GL_TEXTURE_2D}, and sample it with {@code samplerExternalOES}.
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

    /** The width in pixels of the picture being drawn, the right way up as it is shown. */
    public int getWidth() {
        return mWidth;
    }

    /** The height in pixels of the picture being drawn, the right way up as it is shown. */
    public int getHeight() {
        return mHeight;
    }

    /**
     * When the camera captured this frame, in monotonic nanoseconds on the clock of
     * {@link android.graphics.SurfaceTexture#getTimestamp}, for lining it up with other sensor
     * readings.
     */
    public long getTimestampNanos() {
        return mTimestampNanos;
    }

    /**
     * Draws the camera frame the right way up, filling the output. An overlay calls this first and
     * then draws on top. It leaves no program or texture bound.
     */
    public void drawCameraFrame() {
        mProgram.draw(mTextureName, mTextureMatrix, mViewport);
    }
}
