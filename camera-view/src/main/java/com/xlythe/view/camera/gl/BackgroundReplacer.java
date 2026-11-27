package com.xlythe.view.camera.gl;

import android.graphics.Bitmap;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps the subject and draws a colour or an image behind them, in the preview, the recording and
 * the pictures. {@link com.xlythe.view.camera.CameraView#enterBackgroundReplacement} segments each
 * frame for it; to use a model of your own, set it as the frame renderer and hand it masks with
 * {@link #setMask}.
 *
 * <p>Masks arrive slower than frames, so the newest mask is drawn with the newest frame, and the
 * edge trails a subject that moves quickly. Until the first mask arrives, frames are drawn
 * unchanged.</p>
 */
public final class BackgroundReplacer implements FrameRenderer {
    private static final String TAG = "BackgroundReplacer";

    private static final int BYTES_PER_FLOAT = 4;
    private static final int COORDINATES_PER_ATTRIBUTE = 2;
    private static final int FLOATS_PER_VERTEX = 4;
    private static final int VERTEX_COUNT = 4;
    private static final float BYTE_MAX = 255f;

    private static final String VERTEX_SHADER =
            "uniform mat4 uTextureMatrix;\n"
            + "attribute vec4 aPosition;\n"
            + "attribute vec4 aTextureCoordinate;\n"
            + "varying vec2 vTextureCoordinate;\n"
            + "varying vec2 vMaskCoordinate;\n"
            + "void main() {\n"
            + "  gl_Position = aPosition;\n"
            + "  vTextureCoordinate = (uTextureMatrix * aTextureCoordinate).xy;\n"
            // The mask is in the frame's own orientation, not the camera texture's, so it is
            // sampled with the untransformed coordinate rather than the rotated one.
            + "  vMaskCoordinate = aTextureCoordinate.xy;\n"
            + "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float;\n"
            + "uniform samplerExternalOES uTexture;\n"
            + "uniform sampler2D uMask;\n"
            + "uniform sampler2D uBackground;\n"
            + "uniform vec4 uBackgroundColor;\n"
            + "uniform float uHasBackgroundImage;\n"
            + "uniform float uHasMask;\n"
            + "varying vec2 vTextureCoordinate;\n"
            + "varying vec2 vMaskCoordinate;\n"
            + "void main() {\n"
            + "  vec4 camera = texture2D(uTexture, vTextureCoordinate);\n"
            + "  vec4 background = mix(uBackgroundColor,\n"
            + "      texture2D(uBackground, vMaskCoordinate), uHasBackgroundImage);\n"
            // Without a mask every pixel is subject, so the camera frame comes through whole.
            + "  float subject = mix(1.0, texture2D(uMask, vMaskCoordinate).a, uHasMask);\n"
            + "  gl_FragColor = mix(background, camera, subject);\n"
            + "}\n";

    private final AtomicReference<SegmentationMask> mPendingMask = new AtomicReference<>();
    private final AtomicReference<Bitmap> mPendingBackground = new AtomicReference<>();
    private final float[] mBackgroundColor = {0f, 0f, 0f, 1f};

    private int mProgram;
    private int mPositionHandle;
    private int mTextureCoordinateHandle;
    private int mTextureMatrixHandle;
    private int mMaskHandle;
    private int mBackgroundHandle;
    private int mBackgroundColorHandle;
    private int mHasBackgroundImageHandle;
    private int mHasMaskHandle;
    private int mMaskTexture;
    private int mBackgroundTexture;
    private int mMaskWidth;
    private int mMaskHeight;
    private boolean mHasBackgroundImage;
    @Nullable private FloatBuffer mVertices;
    private boolean mFailed;

    /** The colour behind the subject until an image is given. */
    public void setBackgroundColor(@ColorInt int color) {
        synchronized (mBackgroundColor) {
            mBackgroundColor[0] = ((color >> 16) & 0xFF) / BYTE_MAX;
            mBackgroundColor[1] = ((color >> 8) & 0xFF) / BYTE_MAX;
            mBackgroundColor[2] = (color & 0xFF) / BYTE_MAX;
            mBackgroundColor[3] = ((color >>> 24) & 0xFF) / BYTE_MAX;
        }
    }

    /**
     * The image to put behind the subject, or null to go back to the colour. It is read at the
     * next frame, so keep it valid until then; it is never recycled for you.
     */
    public void setBackgroundImage(@Nullable Bitmap background) {
        mPendingBackground.set(background);
        if (background == null) {
            mHasBackgroundImage = false;
        }
    }

    /** The newest mask, from any thread. It replaces any mask not yet drawn. */
    public void setMask(@Nullable SegmentationMask mask) {
        mPendingMask.set(mask);
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
                throw new IllegalStateException("Background program would not link: "
                        + GLES20.glGetProgramInfoLog(mProgram));
            }
            GLES20.glDeleteShader(vertexShader);
            GLES20.glDeleteShader(fragmentShader);

            mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
            mTextureCoordinateHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoordinate");
            mTextureMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uTextureMatrix");
            mMaskHandle = GLES20.glGetUniformLocation(mProgram, "uMask");
            mBackgroundHandle = GLES20.glGetUniformLocation(mProgram, "uBackground");
            mBackgroundColorHandle = GLES20.glGetUniformLocation(mProgram, "uBackgroundColor");
            mHasBackgroundImageHandle =
                    GLES20.glGetUniformLocation(mProgram, "uHasBackgroundImage");
            mHasMaskHandle = GLES20.glGetUniformLocation(mProgram, "uHasMask");

            mMaskTexture = createTexture();
            mBackgroundTexture = createTexture();

            float[] quad = GlMath.fullFrameQuad();
            mVertices = ByteBuffer.allocateDirect(quad.length * BYTES_PER_FLOAT)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            mVertices.put(quad).position(0);
        } catch (RuntimeException error) {
            // The camera has to keep working on a driver that will not compile this.
            Log.w(TAG, "Unable to build the background program; frames will go through as they"
                    + " are", error);
            mFailed = true;
        }
    }

    @Override
    public boolean onDrawFrame(@NonNull GlFrame frame) {
        if (mFailed || mVertices == null) {
            return false;
        }
        uploadPendingMask();
        uploadPendingBackground();

        float[] color = new float[4];
        synchronized (mBackgroundColor) {
            System.arraycopy(mBackgroundColor, 0, color, 0, 4);
        }

        GLES20.glViewport(0, 0, frame.getWidth(), frame.getHeight());
        GLES20.glUseProgram(mProgram);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, frame.getTextureName());
        GLES20.glUniform1i(GLES20.glGetUniformLocation(mProgram, "uTexture"), 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mMaskTexture);
        GLES20.glUniform1i(mMaskHandle, 1);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mBackgroundTexture);
        GLES20.glUniform1i(mBackgroundHandle, 2);

        GLES20.glUniformMatrix4fv(mTextureMatrixHandle, 1, false, frame.getTextureMatrix(), 0);
        GLES20.glUniform4fv(mBackgroundColorHandle, 1, color, 0);
        GLES20.glUniform1f(mHasBackgroundImageHandle, mHasBackgroundImage ? 1f : 0f);
        GLES20.glUniform1f(mHasMaskHandle, mMaskWidth > 0 ? 1f : 0f);

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
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glUseProgram(0);
        return true;
    }

    @Override
    public void onRendererStopped() {
        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }
        int[] textures = {mMaskTexture, mBackgroundTexture};
        GLES20.glDeleteTextures(textures.length, textures, 0);
        mMaskTexture = 0;
        mBackgroundTexture = 0;
        mMaskWidth = 0;
        mMaskHeight = 0;
        mVertices = null;
    }

    /** Uploads the newest mask, if one arrived since the last frame. */
    private void uploadPendingMask() {
        SegmentationMask mask = mPendingMask.getAndSet(null);
        if (mask == null) {
            return;
        }
        byte[] alpha = mask.toAlpha();
        ByteBuffer buffer = ByteBuffer.allocateDirect(alpha.length);
        buffer.put(alpha).position(0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mMaskTexture);
        // One channel, because a mask is coverage and nothing else. ALPHA rather than LUMINANCE
        // so the shader can read it as .a without a swizzle that ES 2.0 does not have.
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_ALPHA, mask.getWidth(),
                mask.getHeight(), 0, GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE, buffer);
        mMaskWidth = mask.getWidth();
        mMaskHeight = mask.getHeight();
    }

    /** Uploads the newest background image, if one arrived since the last frame. */
    private void uploadPendingBackground() {
        Bitmap background = mPendingBackground.getAndSet(null);
        if (background == null || background.isRecycled()) {
            return;
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mBackgroundTexture);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, background, 0);
        mHasBackgroundImage = true;
    }

    /** A 2D texture clamped and linear, which is what both of these want. */
    private static int createTexture() {
        int[] names = new int[1];
        GLES20.glGenTextures(1, names, 0);
        if (names[0] == 0) {
            throw new IllegalStateException("GL would not create a texture");
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, names[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        return names[0];
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
            throw new IllegalStateException("Background shader would not compile: " + log);
        }
        return shader;
    }
}
