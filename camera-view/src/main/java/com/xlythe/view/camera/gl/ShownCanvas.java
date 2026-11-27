package com.xlythe.view.camera.gl;

import android.opengl.GLES20;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

/**
 * An offscreen picture the right way up as it is shown, for an output that holds it some other
 * way round.
 *
 * <p>A recording is stored in the sensor's orientation and turned by its tag, and a preview is
 * held in the camera buffer's shape and turned by its view. A renderer drawing straight into
 * either would draw sideways, so it draws here instead, and this is then turned onto the output.
 * Call every method on the thread that owns the context.</p>
 */
@RequiresApi(21)
final class ShownCanvas {
    /** The framebuffer drawn into, or 0 before the first {@link #bind}. */
    private int mFramebuffer;

    /** The texture behind the framebuffer, which is what gets turned onto the output. */
    private int mTexture;

    /** The size the texture was allocated at, so a picture of the same size reuses it. */
    @NonNull
    private Size mSize = new Size(0, 0);

    /**
     * Makes this the target of drawing, at the given size, allocating or resizing as needed.
     *
     * @throws IllegalStateException if the driver will not complete a framebuffer this size
     */
    void bind(@NonNull Size size) {
        if (mFramebuffer == 0 || !mSize.equals(size)) {
            allocate(size);
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFramebuffer);
        GLES20.glViewport(0, 0, size.getWidth(), size.getHeight());
    }

    /** Goes back to drawing into the output's own surface. */
    void unbind() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    int getTexture() {
        return mTexture;
    }

    private void allocate(@NonNull Size size) {
        release();
        int[] names = new int[1];
        GLES20.glGenTextures(1, names, 0);
        mTexture = names[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexture);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, size.getWidth(),
                size.getHeight(), 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        GLES20.glGenFramebuffers(1, names, 0);
        mFramebuffer = names[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFramebuffer);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, mTexture, 0);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            release();
            throw new IllegalStateException("GL would not draw offscreen at " + size
                    + " (status " + status + ")");
        }
        mSize = size;
    }

    /** Gives the framebuffer and its texture back. Safe to call more than once. */
    void release() {
        if (mFramebuffer != 0) {
            GLES20.glDeleteFramebuffers(1, new int[] {mFramebuffer}, 0);
            mFramebuffer = 0;
        }
        if (mTexture != 0) {
            GLES20.glDeleteTextures(1, new int[] {mTexture}, 0);
            mTexture = 0;
        }
        mSize = new Size(0, 0);
    }
}
