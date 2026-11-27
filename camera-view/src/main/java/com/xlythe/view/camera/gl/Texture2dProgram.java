package com.xlythe.view.camera.gl;

import android.opengl.GLES20;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Draws an ordinary texture through a 4x4 matrix, which is how a {@link ShownCanvas} is turned
 * onto the output it was drawn for.
 *
 * <p>{@link OesTextureProgram}'s twin for a {@code GL_TEXTURE_2D}: the same quad and the same
 * vertex shader, with a sampler that reads what GL itself drew rather than what a camera wrote.
 * Call every method on the thread that owns the context.</p>
 */
final class Texture2dProgram {
    /** Floats per vertex in {@link GlMath#fullFrameQuad}: x, y, s, t. */
    private static final int FLOATS_PER_VERTEX = 4;

    /** The bytes in one float of the vertex buffer. */
    private static final int BYTES_PER_FLOAT = 4;

    /** The vertices in the quad that covers the output. */
    private static final int VERTEX_COUNT = 4;

    /** The floats in each attribute: x and y, or s and t. */
    private static final int COORDINATES_PER_ATTRIBUTE = 2;

    /** Samples the 2D texture as it is. */
    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n"
            + "uniform sampler2D uTexture;\n"
            + "varying vec2 vTextureCoordinate;\n"
            + "void main() {\n"
            + "  gl_FragColor = texture2D(uTexture, vTextureCoordinate);\n"
            + "}\n";

    /** The linked program, deleted by {@link #release}. */
    private final int mProgram;

    /** Where the quad's corners go in, in clip space. */
    private final int mPositionHandle;

    /** Where the quad's texture coordinates go in, before the matrix. */
    private final int mTextureCoordinateHandle;

    /** Where the matrix goes in. */
    private final int mTextureMatrixHandle;

    /** The quad from {@link GlMath#fullFrameQuad}, in native memory where GL can read it. */
    private final FloatBuffer mVertices;

    /**
     * @throws IllegalStateException if either shader will not compile or the two will not link
     */
    Texture2dProgram() {
        int vertexShader = OesTextureProgram.compile(
                GLES20.GL_VERTEX_SHADER, OesTextureProgram.VERTEX_SHADER);
        int fragmentShader = OesTextureProgram.compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        mProgram = GLES20.glCreateProgram();
        if (mProgram == 0) {
            throw new IllegalStateException("GL would not create a program");
        }
        GLES20.glAttachShader(mProgram, vertexShader);
        GLES20.glAttachShader(mProgram, fragmentShader);
        GLES20.glLinkProgram(mProgram);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(mProgram, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] != GLES20.GL_TRUE) {
            String log = GLES20.glGetProgramInfoLog(mProgram);
            GLES20.glDeleteProgram(mProgram);
            throw new IllegalStateException("The canvas shader would not link: " + log);
        }
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);

        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        mTextureCoordinateHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoordinate");
        mTextureMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uTextureMatrix");

        float[] quad = GlMath.fullFrameQuad();
        mVertices = ByteBuffer.allocateDirect(quad.length * BYTES_PER_FLOAT)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        mVertices.put(quad).position(0);
    }

    /**
     * Draws the texture over the viewport.
     *
     * @param textureMatrix 4x4, column-major, applied to the texture coordinates
     * @param viewport {@code {x, y, width, height}} as {@link GlMath#viewport} returns it
     */
    void draw(int textureName, @NonNull float[] textureMatrix, @NonNull int[] viewport) {
        GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        GLES20.glUseProgram(mProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureName);
        GLES20.glUniformMatrix4fv(mTextureMatrixHandle, 1, false, textureMatrix, 0);

        int stride = FLOATS_PER_VERTEX * BYTES_PER_FLOAT;
        mVertices.position(0);
        GLES20.glVertexAttribPointer(mPositionHandle, COORDINATES_PER_ATTRIBUTE,
                GLES20.GL_FLOAT, false, stride, mVertices);
        GLES20.glEnableVertexAttribArray(mPositionHandle);
        mVertices.position(COORDINATES_PER_ATTRIBUTE);
        GLES20.glVertexAttribPointer(mTextureCoordinateHandle, COORDINATES_PER_ATTRIBUTE,
                GLES20.GL_FLOAT, false, stride, mVertices);
        GLES20.glEnableVertexAttribArray(mTextureCoordinateHandle);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, VERTEX_COUNT);

        GLES20.glDisableVertexAttribArray(mPositionHandle);
        GLES20.glDisableVertexAttribArray(mTextureCoordinateHandle);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glUseProgram(0);
    }

    void release() {
        GLES20.glDeleteProgram(mProgram);
    }
}
