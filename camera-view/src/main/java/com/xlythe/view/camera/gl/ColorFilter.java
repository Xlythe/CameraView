package com.xlythe.view.camera.gl;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * A {@link FrameRenderer} that puts every pixel through a {@link ColorMatrix}, in the preview, the
 * recording and the pictures alike: black and white, sepia, a warm or cool cast, more or less
 * contrast. It looks at one pixel at a time, so a blur, sharpening or edge detection needs a
 * renderer of your own.
 *
 * <p>The matrix can be changed between frames without restarting anything.</p>
 */
public final class ColorFilter implements FrameRenderer {
    private static final String TAG = "ColorFilter";

    private static final int BYTES_PER_FLOAT = 4;
    private static final int COORDINATES_PER_ATTRIBUTE = 2;
    private static final int FLOATS_PER_VERTEX = 4;
    private static final int VERTEX_COUNT = 4;

    private static final String VERTEX_SHADER =
            "uniform mat4 uTextureMatrix;\n"
            + "attribute vec4 aPosition;\n"
            + "attribute vec4 aTextureCoordinate;\n"
            + "varying vec2 vTextureCoordinate;\n"
            + "void main() {\n"
            + "  gl_Position = aPosition;\n"
            + "  vTextureCoordinate = (uTextureMatrix * aTextureCoordinate).xy;\n"
            + "}\n";

    // The extension directive has to be the first line, before the precision qualifier, or the
    // compiler rejects the samplerExternalOES that follows it.
    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float;\n"
            + "uniform samplerExternalOES uTexture;\n"
            + "uniform mat4 uColorMatrix;\n"
            + "varying vec2 vTextureCoordinate;\n"
            + "void main() {\n"
            + "  vec4 colour = texture2D(uTexture, vTextureCoordinate);\n"
            // The matrix is applied to straight RGB with alpha carried through, which is what
            // the camera gives us: opaque frames, nothing premultiplied to undo.
            + "  gl_FragColor = clamp(uColorMatrix * vec4(colour.rgb, 1.0), 0.0, 1.0);\n"
            + "}\n";

    private final float[] mMatrix = ColorMatrix.identity();

    private int mProgram;
    private int mPositionHandle;
    private int mTextureCoordinateHandle;
    private int mTextureMatrixHandle;
    private int mColorMatrixHandle;
    @Nullable private FloatBuffer mVertices;
    private boolean mFailed;

    /** A filter that changes nothing until it is given a matrix. */
    public ColorFilter() {}

    /** A filter that starts with the given matrix. */
    public ColorFilter(@NonNull float[] matrix) {
        setMatrix(matrix);
    }

    /**
     * Changes what the filter does, from any thread. The array is copied, so changing it afterwards
     * changes nothing.
     */
    public void setMatrix(@NonNull float[] matrix) {
        if (matrix.length != ColorMatrix.SIZE) {
            throw new IllegalArgumentException("A colour matrix is " + ColorMatrix.SIZE
                    + " floats, not " + matrix.length);
        }
        synchronized (mMatrix) {
            System.arraycopy(matrix, 0, mMatrix, 0, ColorMatrix.SIZE);
        }
    }

    @Override
    public void onRendererStarted() {
        try {
            int vertexShader = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
            int fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
            mProgram = GLES20.glCreateProgram();
            GLES20.glAttachShader(mProgram, vertexShader);
            GLES20.glAttachShader(mProgram, fragmentShader);
            GLES20.glLinkProgram(mProgram);
            int[] linked = new int[1];
            GLES20.glGetProgramiv(mProgram, GLES20.GL_LINK_STATUS, linked, 0);
            if (linked[0] == 0) {
                throw new IllegalStateException(
                        "Filter program would not link: " + GLES20.glGetProgramInfoLog(mProgram));
            }
            // Attached shaders are kept alive by the program and are of no further use to us.
            GLES20.glDeleteShader(vertexShader);
            GLES20.glDeleteShader(fragmentShader);

            mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
            mTextureCoordinateHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoordinate");
            mTextureMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uTextureMatrix");
            mColorMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uColorMatrix");

            float[] quad = GlMath.fullFrameQuad();
            mVertices = ByteBuffer.allocateDirect(quad.length * BYTES_PER_FLOAT)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            mVertices.put(quad).position(0);
        } catch (RuntimeException error) {
            // A driver that will not compile this is a driver the camera still has to work on.
            // The frame is drawn unfiltered rather than not at all.
            Log.w(TAG, "Unable to build the filter program; frames will go through unfiltered",
                    error);
            mFailed = true;
        }
    }

    @Override
    public boolean onDrawFrame(@NonNull GlFrame frame) {
        if (mFailed || mVertices == null) {
            return false;
        }
        float[] colorMatrix = new float[ColorMatrix.SIZE];
        synchronized (mMatrix) {
            System.arraycopy(mMatrix, 0, colorMatrix, 0, ColorMatrix.SIZE);
        }

        GLES20.glViewport(0, 0, frame.getWidth(), frame.getHeight());
        GLES20.glUseProgram(mProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, frame.getTextureName());
        GLES20.glUniformMatrix4fv(mTextureMatrixHandle, 1, false, frame.getTextureMatrix(), 0);
        GLES20.glUniformMatrix4fv(mColorMatrixHandle, 1, false, colorMatrix, 0);

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
        return true;
    }

    @Override
    public void onRendererStopped() {
        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }
        mVertices = null;
    }

    private static int compile(int type, @NonNull String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("Filter shader would not compile: " + log);
        }
        return shader;
    }
}
