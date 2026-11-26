package com.xlythe.view.camera.gl;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * The one shader this library needs: an external texture drawn through a 4x4 matrix.
 *
 * <p>A camera writes into a {@code GL_TEXTURE_EXTERNAL_OES} texture, which a normal sampler cannot
 * read - the format is the driver's own, often YUV, and the conversion happens in the sampler. So
 * the fragment shader declares the extension and nothing else about the frame is assumed: which
 * way up it is, whether it is mirrored and how much of it to show all arrive as the matrix, which
 * is what the camera itself publishes.</p>
 *
 * <p>Thin on purpose, like {@link EglCore}. Call every method on the thread that owns the
 * context.</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class OesTextureProgram {
    /** Floats per vertex in {@link GlMath#fullFrameQuad}: x, y, s, t. */
    private static final int FLOATS_PER_VERTEX = 4;

    private static final int BYTES_PER_FLOAT = 4;

    private static final int VERTEX_COUNT = 4;

    private static final int COORDINATES_PER_ATTRIBUTE = 2;

    static final String VERTEX_SHADER =
            "uniform mat4 uTextureMatrix;\n"
            + "attribute vec4 aPosition;\n"
            + "attribute vec4 aTextureCoordinate;\n"
            + "varying vec2 vTextureCoordinate;\n"
            + "void main() {\n"
            + "  gl_Position = aPosition;\n"
            + "  vTextureCoordinate = (uTextureMatrix * aTextureCoordinate).xy;\n"
            + "}\n";

    // The extension directive has to be the first line of the shader, before the precision
    // qualifier, or the compiler rejects the samplerExternalOES type that follows it.
    static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float;\n"
            + "uniform samplerExternalOES uTexture;\n"
            + "varying vec2 vTextureCoordinate;\n"
            + "void main() {\n"
            + "  gl_FragColor = texture2D(uTexture, vTextureCoordinate);\n"
            + "}\n";

    private final int mProgram;
    private final int mPositionHandle;
    private final int mTextureCoordinateHandle;
    private final int mTextureMatrixHandle;
    private final FloatBuffer mVertices;

    /**
     * @throws IllegalStateException if either shader will not compile or the two will not link,
     *     which on a device means the external-texture extension is missing or the driver is
     *     refusing to talk to us
     */
    public OesTextureProgram() {
        int vertexShader = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
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
            throw new IllegalStateException("The frame shader would not link: " + log);
        }
        // The shaders are in the program now, so the program holds the only reference that
        // matters and these can go.
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
     * A new external texture, set up the way a camera needs it.
     *
     * <p>Clamped and linear because an external texture supports nothing else: it has no mipmaps
     * and repeating it is meaningless.</p>
     */
    public int createExternalTexture() {
        return newExternalTexture();
    }

    /**
     * Creates a camera texture without a program to hang it on.
     *
     * <p>Static because a {@link CameraTextureProvider} has to make the texture itself and has
     * no program to borrow one from - it runs before the renderer has drawn anything. The
     * parameters are the ones a camera texture needs rather than the GL defaults, which repeat
     * and use mipmaps that an external texture does not have.</p>
     */
    public static int newExternalTexture() {
        int[] names = new int[1];
        GLES20.glGenTextures(1, names, 0);
        if (names[0] == 0) {
            throw new IllegalStateException("GL would not create a texture");
        }
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, names[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        return names[0];
    }

    /**
     * Draws the texture over the viewport.
     *
     * <p>The caller owns the viewport and whatever was there before, so a renderer drawing to
     * several outputs, or drawing a frame under its own geometry, gets to decide the order.</p>
     *
     * @param textureMatrix 4x4, column-major, applied to the texture coordinates
     * @param viewport {@code {x, y, width, height}} as {@link GlMath#viewport} returns it
     */
    public void draw(int textureName, @NonNull float[] textureMatrix, @NonNull int[] viewport) {
        GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        GLES20.glUseProgram(mProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureName);
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
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        GLES20.glUseProgram(0);
    }

    public void deleteTexture(int textureName) {
        GLES20.glDeleteTextures(1, new int[] {textureName}, 0);
    }

    public void release() {
        GLES20.glDeleteProgram(mProgram);
    }

    private static int compile(int type, @NonNull String source) {
        int shader = GLES20.glCreateShader(type);
        if (shader == 0) {
            throw new IllegalStateException("GL would not create a shader");
        }
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] != GLES20.GL_TRUE) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("A frame shader would not compile: " + log);
        }
        return shader;
    }
}
