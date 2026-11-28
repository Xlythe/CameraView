package com.xlythe.view.camera.gl;

import android.graphics.Bitmap;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

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
    /** The tag this logs under. */
    private static final String TAG = "BackgroundReplacer";

    /** The bytes in one float of the vertex buffer. */
    private static final int BYTES_PER_FLOAT = 4;
    /** The floats in each attribute: x and y, or s and t. */
    private static final int COORDINATES_PER_ATTRIBUTE = 2;
    /** The floats in each vertex: a position and a texture coordinate. */
    private static final int FLOATS_PER_VERTEX = 4;
    /** The vertices in the quad that covers the output. */
    private static final int VERTEX_COUNT = 4;
    /** The largest value of an 8-bit colour channel. */
    private static final float BYTE_MAX = 255f;

    /** Positions the quad and passes on the camera, mask and background coordinates. */
    private static final String VERTEX_SHADER =
            "uniform mat4 uTextureMatrix;\n"
            + "uniform mat4 uMaskMatrix;\n"
            + "uniform mat4 uBackgroundMatrix;\n"
            + "attribute vec4 aPosition;\n"
            + "attribute vec4 aTextureCoordinate;\n"
            + "varying vec2 vTextureCoordinate;\n"
            + "varying vec2 vMaskCoordinate;\n"
            + "varying vec2 vBackgroundCoordinate;\n"
            + "void main() {\n"
            + "  gl_Position = aPosition;\n"
            + "  vTextureCoordinate = (uTextureMatrix * aTextureCoordinate).xy;\n"
            // The mask and the image are in the frame's orientation as it is shown, not the
            // camera texture's, so they are read from the untransformed coordinate: through a
            // matrix only because each was uploaded top row first, and the mask may be mirrored.
            + "  vMaskCoordinate = (uMaskMatrix * aTextureCoordinate).xy;\n"
            + "  vBackgroundCoordinate = (uBackgroundMatrix * aTextureCoordinate).xy;\n"
            + "}\n";

    /** Mixes the camera frame over the background colour or image by the mask. */
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
            + "varying vec2 vBackgroundCoordinate;\n"
            + "void main() {\n"
            + "  vec4 camera = texture2D(uTexture, vTextureCoordinate);\n"
            + "  vec4 background = mix(uBackgroundColor,\n"
            + "      texture2D(uBackground, vBackgroundCoordinate), uHasBackgroundImage);\n"
            // Without a mask every pixel is subject, so the camera frame comes through whole.
            + "  float subject = mix(1.0, texture2D(uMask, vMaskCoordinate).a, uHasMask);\n"
            + "  gl_FragColor = mix(background, camera, subject);\n"
            + "}\n";

    /** The newest mask, waiting to be uploaded at the next frame, or null. */
    private final AtomicReference<SegmentationMask> mPendingMask = new AtomicReference<>();
    /** The newest background image, waiting to be uploaded at the next frame, or null. */
    private final AtomicReference<Bitmap> mPendingBackground = new AtomicReference<>();
    /**
     * The background image set, or null for the colour: kept, so a renderer that stops and
     * starts again, as it does when the camera reopens, uploads it again into its new texture.
     */
    @Nullable private volatile Bitmap mBackground;
    /** The background colour as RGBA from 0 to 1, guarded by itself. */
    private final float[] mBackgroundColor = {0f, 0f, 0f, 1f};

    /** The compiled program, or 0 before the renderer starts. */
    private int mProgram;
    /** The location of the vertex position attribute. */
    private int mPositionHandle;
    /** The location of the texture coordinate attribute. */
    private int mTextureCoordinateHandle;
    /** The location of the camera texture's transform matrix. */
    private int mTextureMatrixHandle;
    /** The location of the matrix that reads the mask. */
    private int mMaskMatrixHandle;
    /** The location of the matrix that reads the background image. */
    private int mBackgroundMatrixHandle;
    /** The location of the mask sampler. */
    private int mMaskHandle;
    /** The location of the background image sampler. */
    private int mBackgroundHandle;
    /** The location of the background colour. */
    private int mBackgroundColorHandle;
    /** The location of the flag that says a background image is set. */
    private int mHasBackgroundImageHandle;
    /** The location of the flag that says a mask has arrived. */
    private int mHasMaskHandle;
    /** The 2D texture the mask is uploaded into. */
    private int mMaskTexture;
    /** The 2D texture the background image is uploaded into. */
    private int mBackgroundTexture;
    /** The width of the mask last uploaded, or 0 before any has been. */
    private int mMaskWidth;
    /** The height of the mask last uploaded, or 0 before any has been. */
    private int mMaskHeight;
    /** Whether the background texture holds an image, rather than the colour being drawn. */
    private boolean mHasBackgroundImage;
    /** The width of the background image last uploaded, for fitting it to each frame. */
    private int mBackgroundWidth;
    /** The height of the background image last uploaded. */
    private int mBackgroundHeight;
    /** Whether the mask last uploaded was made from a mirrored frame, and is read mirrored. */
    private boolean mMaskMirrored;
    /** The quad's positions and texture coordinates, or null while the renderer is stopped. */
    @Nullable private FloatBuffer mVertices;
    /** Whether the program failed to build, after which frames go through unchanged. */
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
     * The image to put behind the subject, or null to go back to the colour. It fills the frame,
     * cropped to the frame's shape rather than stretched. It is read at the next frame and again
     * whenever the camera reopens, so keep it valid while it is set; it is never recycled for
     * you.
     */
    public void setBackgroundImage(@Nullable Bitmap background) {
        mBackground = background;
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
            mMaskMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMaskMatrix");
            mBackgroundMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uBackgroundMatrix");
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
        GLES20.glUniformMatrix4fv(mMaskMatrixHandle, 1, false,
                GlMath.topRowFirst(mMaskMirrored), 0);
        // Never mirrored: a background shows the way it was given, whichever lens is in use.
        GLES20.glUniformMatrix4fv(mBackgroundMatrixHandle, 1, false,
                backgroundMatrix(frame.getWidth(), frame.getHeight(), mBackgroundWidth,
                        mBackgroundHeight), 0);
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
        // The image went with its texture; the next renderer uploads it again, unless a newer
        // one, or none, has been set since.
        mHasBackgroundImage = false;
        Bitmap background = mBackground;
        if (background != null) {
            mPendingBackground.compareAndSet(null, background);
        }
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
        mMaskMirrored = mask.isMirrored();
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
        mBackgroundWidth = background.getWidth();
        mBackgroundHeight = background.getHeight();
    }

    /**
     * Where each point of a frame reads the background image: top row first, unmirrored, and
     * cropped about its centre to the frame's shape, so it fills the frame without stretching
     * whatever shape the backend's frames are.
     */
    @VisibleForTesting
    @NonNull
    static float[] backgroundMatrix(int frameWidth, int frameHeight, int imageWidth,
            int imageHeight) {
        float[] matrix = GlMath.topRowFirst(false);
        if (frameWidth <= 0 || frameHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) {
            return matrix;
        }
        float frameShape = (float) frameWidth / frameHeight;
        float imageShape = (float) imageWidth / imageHeight;
        // The share of the image's width and height the frame shows.
        float across = Math.min(1f, frameShape / imageShape);
        float down = Math.min(1f, imageShape / frameShape);
        // Scales the coordinate the flip produced about the centre: s' = 0.5 + (s - 0.5) * k.
        matrix[0] *= across;
        matrix[4] *= across;
        matrix[12] = 0.5f + (matrix[12] - 0.5f) * across;
        matrix[1] *= down;
        matrix[5] *= down;
        matrix[13] = 0.5f + (matrix[13] - 0.5f) * down;
        return matrix;
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
