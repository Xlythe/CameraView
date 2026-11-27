package com.xlythe.view.camera.gl;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps the subject sharp and blurs what is behind them, for background blur on a camera without
 * a blur of its own. The segmenter hands it a mask for each frame it reads, through
 * {@link #setMask}.
 *
 * <p>The background is blurred at a reduced size: the frame is shrunk to
 * {@link #REDUCED_SHORT_SIDE} pixels across its shorter side, blurred there across and then down,
 * and stretched back under the subject. A fixed reduced size makes the blur the same share of
 * the picture in the preview and in a full-size still, and costs the same few thousand pixels
 * per pass however large the output is.</p>
 *
 * <p>Until the first mask arrives, frames are drawn unchanged, so the subject is never blurred
 * along with the background.</p>
 */
@RequiresApi(21)
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class BackgroundBlur implements FrameRenderer {
    /** The tag this logs under. */
    private static final String TAG = "BackgroundBlur";

    /** The shorter side of the reduced copy the background is blurred at, in pixels. */
    @VisibleForTesting
    static final int REDUCED_SHORT_SIDE = 96;

    /** How many reduced pixels the blur reaches to either side of each one. */
    @VisibleForTesting
    static final int BLUR_RADIUS = 6;

    /** The blur's standard deviation, in reduced pixels: half its reach. */
    private static final float BLUR_SIGMA = BLUR_RADIUS / 2f;

    /**
     * How many samples the shrinking pass takes across each reduced pixel, in each direction.
     * Each is a linear sample of four camera pixels, so four of them cover a reduced pixel of
     * eight camera pixels across, which is roughly what a preview shrinks by.
     */
    private static final int SHRINK_TAPS = 4;

    /** Below this confidence a mask sample is background, drawn fully blurred. */
    private static final float BACKGROUND_EDGE = 0.25f;

    /** Above this confidence a mask sample is subject, drawn fully sharp. */
    private static final float SUBJECT_EDGE = 0.75f;

    /** The bytes in one float of the vertex buffer. */
    private static final int BYTES_PER_FLOAT = 4;
    /** The floats in each attribute: x and y, or s and t. */
    private static final int COORDINATES_PER_ATTRIBUTE = 2;
    /** The floats in each vertex: a position and a texture coordinate. */
    private static final int FLOATS_PER_VERTEX = 4;
    /** The vertices in the quad that covers the output. */
    private static final int VERTEX_COUNT = 4;

    /**
     * Positions the quad and passes on three coordinates: the camera's, through its matrix; the
     * output's own, which reads what this renderer drew itself; and the mask's.
     */
    private static final String VERTEX_SHADER =
            "uniform mat4 uTextureMatrix;\n"
            + "uniform mat4 uMaskMatrix;\n"
            + "attribute vec4 aPosition;\n"
            + "attribute vec4 aTextureCoordinate;\n"
            + "varying vec2 vTextureCoordinate;\n"
            + "varying vec2 vCoordinate;\n"
            + "varying vec2 vMaskCoordinate;\n"
            + "void main() {\n"
            + "  gl_Position = aPosition;\n"
            + "  vTextureCoordinate = (uTextureMatrix * aTextureCoordinate).xy;\n"
            + "  vCoordinate = aTextureCoordinate.xy;\n"
            + "  vMaskCoordinate = (uMaskMatrix * aTextureCoordinate).xy;\n"
            + "}\n";

    /** The newest mask, waiting to be uploaded at the next frame, or null. */
    private final AtomicReference<SegmentationMask> mPendingMask = new AtomicReference<>();

    /** Shrinks the camera frame, upright, into the reduced copy. */
    @Nullable private Program mShrink;
    /** Blurs one reduced copy into the other, along one direction at a time. */
    @Nullable private Program mBlur;
    /** Draws the camera frame over the blurred copy wherever the mask says subject. */
    @Nullable private Program mComposite;
    /** The reduced copy the frame is shrunk into and blurred back into. */
    @Nullable private ShownCanvas mReduced;
    /** The reduced copy blurred across, between the two blur passes. */
    @Nullable private ShownCanvas mAcross;
    /** The 2D texture the mask is uploaded into. */
    private int mMaskTexture;
    /** Whether a mask has been uploaded, before which frames are drawn unchanged. */
    private boolean mHasMask;
    /** Whether the mask last uploaded came from a mirrored frame, and is read mirrored. */
    private boolean mMaskMirrored;
    /** The quad's positions and texture coordinates, or null while the renderer is stopped. */
    @Nullable private FloatBuffer mVertices;
    /** Whether GL refused something, after which frames go through unchanged. */
    private boolean mFailed;

    /** The newest mask, from any thread. It replaces any mask not yet drawn. */
    public void setMask(@Nullable SegmentationMask mask) {
        mPendingMask.set(mask);
    }

    @Override
    public void onRendererStarted() {
        try {
            mShrink = new Program(shrinkShader());
            mBlur = new Program(blurShader(gaussianWeights(BLUR_RADIUS, BLUR_SIGMA)));
            mComposite = new Program(compositeShader());
            mReduced = new ShownCanvas();
            mAcross = new ShownCanvas();
            mMaskTexture = createMaskTexture();
            float[] quad = GlMath.fullFrameQuad();
            mVertices = ByteBuffer.allocateDirect(quad.length * BYTES_PER_FLOAT)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            mVertices.put(quad).position(0);
        } catch (RuntimeException error) {
            // The camera has to keep working on a driver that will not build these.
            Log.w(TAG, "Unable to build the blur; frames will go through as they are", error);
            mFailed = true;
        }
    }

    @Override
    public boolean onDrawFrame(@NonNull GlFrame frame) {
        uploadPendingMask();
        Program shrink = mShrink;
        Program blur = mBlur;
        Program composite = mComposite;
        ShownCanvas reduced = mReduced;
        ShownCanvas across = mAcross;
        if (mFailed || !mHasMask || shrink == null || blur == null || composite == null
                || reduced == null || across == null || mVertices == null) {
            return false;
        }
        int width = frame.getWidth();
        int height = frame.getHeight();
        Size size = reducedSize(width, height);

        // The output is a surface or a framebuffer of the caller's, and goes back to being the
        // target once the reduced copies are drawn.
        int[] target = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, target, 0);
        try {
            reduced.bind(size);
            across.bind(size);
        } catch (IllegalStateException error) {
            Log.w(TAG, "GL will not draw the reduced copy; frames will go through as they are",
                    error);
            mFailed = true;
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target[0]);
            return false;
        }

        // The camera frame, upright as it is shown, shrunk into the reduced copy.
        reduced.bind(size);
        float[] matrix = frame.getTextureMatrix();
        shrink.use();
        bindCamera(shrink, frame.getTextureName(), 0);
        shrink.setMatrix("uTextureMatrix", matrix);
        shrink.setMatrix("uMaskMatrix", GlMath.identity());
        float[] step = shrinkStep(matrix, size);
        shrink.setVector("uStepAcross", step[0], step[1]);
        shrink.setVector("uStepDown", step[2], step[3]);
        draw(shrink);

        // Blurred across into the other copy, and down back into the first.
        across.bind(size);
        blur.use();
        bindTexture(blur, "uTexture", reduced.getTexture(), 0);
        blur.setMatrix("uTextureMatrix", GlMath.identity());
        blur.setMatrix("uMaskMatrix", GlMath.identity());
        blur.setVector("uStep", 1f / size.getWidth(), 0f);
        draw(blur);

        reduced.bind(size);
        blur.use();
        bindTexture(blur, "uTexture", across.getTexture(), 0);
        blur.setVector("uStep", 0f, 1f / size.getHeight());
        draw(blur);

        // The camera frame over the blur, wherever the mask says subject.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target[0]);
        GLES20.glViewport(0, 0, width, height);
        composite.use();
        bindCamera(composite, frame.getTextureName(), 0);
        bindTexture(composite, "uBlurred", reduced.getTexture(), 1);
        bindTexture(composite, "uMask", mMaskTexture, 2);
        composite.setMatrix("uTextureMatrix", matrix);
        composite.setMatrix("uMaskMatrix", GlMath.topRowFirst(mMaskMirrored));
        draw(composite);

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glUseProgram(0);
        return true;
    }

    @Override
    public void onRendererStopped() {
        for (Program program : new Program[] {mShrink, mBlur, mComposite}) {
            if (program != null) {
                program.release();
            }
        }
        mShrink = null;
        mBlur = null;
        mComposite = null;
        for (ShownCanvas canvas : new ShownCanvas[] {mReduced, mAcross}) {
            if (canvas != null) {
                canvas.release();
            }
        }
        mReduced = null;
        mAcross = null;
        if (mMaskTexture != 0) {
            GLES20.glDeleteTextures(1, new int[] {mMaskTexture}, 0);
            mMaskTexture = 0;
        }
        mHasMask = false;
        mVertices = null;
    }

    /**
     * The size of the reduced copy: {@link #REDUCED_SHORT_SIDE} across the shorter side, and the
     * output's shape. An output already that small is not shrunk.
     */
    @VisibleForTesting
    @NonNull
    static Size reducedSize(int width, int height) {
        int shortSide = Math.max(1, Math.min(width, height));
        if (shortSide <= REDUCED_SHORT_SIDE) {
            return new Size(Math.max(1, width), Math.max(1, height));
        }
        float scale = (float) REDUCED_SHORT_SIDE / shortSide;
        return new Size(Math.max(1, Math.round(width * scale)),
                Math.max(1, Math.round(height * scale)));
    }

    /**
     * The weights of a gaussian from its centre out to {@code radius}, normalised so that the
     * centre plus twice every other weight is one, which keeps a blurred picture as bright as it
     * was.
     */
    @VisibleForTesting
    @NonNull
    static float[] gaussianWeights(int radius, float sigma) {
        float[] weights = new float[radius + 1];
        float total = 0f;
        for (int i = 0; i <= radius; i++) {
            weights[i] = (float) Math.exp(-(i * i) / (2f * sigma * sigma));
            total += i == 0 ? weights[i] : 2f * weights[i];
        }
        for (int i = 0; i <= radius; i++) {
            weights[i] /= total;
        }
        return weights;
    }

    /**
     * How far apart the shrinking pass's samples are in the camera's texture, as the step across
     * and the step down: one {@link #SHRINK_TAPS}th of a reduced pixel each, turned by the
     * frame's matrix so that across is across the picture as it is shown.
     *
     * @return {across s, across t, down s, down t}
     */
    @VisibleForTesting
    @NonNull
    static float[] shrinkStep(@NonNull float[] textureMatrix, @NonNull Size reduced) {
        float across = 1f / (reduced.getWidth() * SHRINK_TAPS);
        float down = 1f / (reduced.getHeight() * SHRINK_TAPS);
        // Column-major: the first column is where a step in s goes, the second a step in t.
        return new float[] {
                textureMatrix[0] * across, textureMatrix[1] * across,
                textureMatrix[4] * down, textureMatrix[5] * down,
        };
    }

    /** Averages a grid of samples across each reduced pixel, upright as the frame is shown. */
    @NonNull
    private static String shrinkShader() {
        StringBuilder shader = new StringBuilder()
                .append("#extension GL_OES_EGL_image_external : require\n")
                .append("precision mediump float;\n")
                .append("uniform samplerExternalOES uTexture;\n")
                .append("uniform vec2 uStepAcross;\n")
                .append("uniform vec2 uStepDown;\n")
                .append("varying vec2 vTextureCoordinate;\n")
                .append("varying vec2 vCoordinate;\n")
                .append("varying vec2 vMaskCoordinate;\n")
                .append("void main() {\n")
                .append("  vec4 sum = vec4(0.0);\n");
        float centre = (SHRINK_TAPS - 1) / 2f;
        for (int down = 0; down < SHRINK_TAPS; down++) {
            for (int across = 0; across < SHRINK_TAPS; across++) {
                shader.append(String.format(Locale.US,
                        "  sum += texture2D(uTexture, vTextureCoordinate + %.1f * uStepAcross"
                                + " + %.1f * uStepDown);\n",
                        across - centre, down - centre));
            }
        }
        return shader.append(String.format(Locale.US, "  gl_FragColor = sum / %.1f;\n",
                (float) (SHRINK_TAPS * SHRINK_TAPS)))
                .append("}\n")
                .toString();
    }

    /** Blurs along one direction with the given weights, centre first. */
    @NonNull
    private static String blurShader(@NonNull float[] weights) {
        StringBuilder shader = new StringBuilder()
                .append("precision mediump float;\n")
                .append("uniform sampler2D uTexture;\n")
                .append("uniform vec2 uStep;\n")
                .append("varying vec2 vTextureCoordinate;\n")
                .append("varying vec2 vCoordinate;\n")
                .append("varying vec2 vMaskCoordinate;\n")
                .append("void main() {\n")
                .append(String.format(Locale.US,
                        "  vec4 sum = texture2D(uTexture, vCoordinate) * %.6f;\n", weights[0]));
        for (int i = 1; i < weights.length; i++) {
            shader.append(String.format(Locale.US,
                    "  sum += (texture2D(uTexture, vCoordinate + %d.0 * uStep)"
                            + " + texture2D(uTexture, vCoordinate - %d.0 * uStep)) * %.6f;\n",
                    i, i, weights[i]));
        }
        return shader.append("  gl_FragColor = sum;\n").append("}\n").toString();
    }

    /** Mixes the camera frame over the blurred copy by the mask, with a soft edge. */
    @NonNull
    private static String compositeShader() {
        return "#extension GL_OES_EGL_image_external : require\n"
                + "precision mediump float;\n"
                + "uniform samplerExternalOES uTexture;\n"
                + "uniform sampler2D uBlurred;\n"
                + "uniform sampler2D uMask;\n"
                + "varying vec2 vTextureCoordinate;\n"
                + "varying vec2 vCoordinate;\n"
                + "varying vec2 vMaskCoordinate;\n"
                + "void main() {\n"
                + "  vec4 camera = texture2D(uTexture, vTextureCoordinate);\n"
                + "  vec4 blurred = texture2D(uBlurred, vCoordinate);\n"
                + "  float confidence = texture2D(uMask, vMaskCoordinate).a;\n"
                + String.format(Locale.US,
                        "  float subject = smoothstep(%.2f, %.2f, confidence);\n",
                        BACKGROUND_EDGE, SUBJECT_EDGE)
                + "  gl_FragColor = mix(blurred, camera, subject);\n"
                + "}\n";
    }

    /** Binds the camera's external texture to a unit, as the program's {@code uTexture}. */
    private static void bindCamera(@NonNull Program program, int texture, int unit) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
        GLES20.glUniform1i(program.location("uTexture"), unit);
    }

    /** Binds a 2D texture to a unit, as the program's sampler of that name. */
    private static void bindTexture(@NonNull Program program, @NonNull String sampler,
            int texture, int unit) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(program.location(sampler), unit);
    }

    /** Draws the quad through a program, which is in use. */
    private void draw(@NonNull Program program) {
        FloatBuffer vertices = mVertices;
        if (vertices == null) {
            return;
        }
        int stride = FLOATS_PER_VERTEX * BYTES_PER_FLOAT;
        vertices.position(0);
        GLES20.glVertexAttribPointer(program.mPositionHandle, COORDINATES_PER_ATTRIBUTE,
                GLES20.GL_FLOAT, false, stride, vertices);
        GLES20.glEnableVertexAttribArray(program.mPositionHandle);
        vertices.position(COORDINATES_PER_ATTRIBUTE);
        GLES20.glVertexAttribPointer(program.mTextureCoordinateHandle, COORDINATES_PER_ATTRIBUTE,
                GLES20.GL_FLOAT, false, stride, vertices);
        GLES20.glEnableVertexAttribArray(program.mTextureCoordinateHandle);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, VERTEX_COUNT);
        GLES20.glDisableVertexAttribArray(program.mPositionHandle);
        GLES20.glDisableVertexAttribArray(program.mTextureCoordinateHandle);
    }

    /** Uploads the newest mask, if one arrived since the last frame. */
    private void uploadPendingMask() {
        SegmentationMask mask = mPendingMask.getAndSet(null);
        if (mask == null || mMaskTexture == 0) {
            return;
        }
        byte[] alpha = mask.toAlpha();
        ByteBuffer buffer = ByteBuffer.allocateDirect(alpha.length);
        buffer.put(alpha).position(0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mMaskTexture);
        // One channel, read as .a, as BackgroundReplacer reads its mask.
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_ALPHA, mask.getWidth(),
                mask.getHeight(), 0, GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE, buffer);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        mHasMask = true;
        mMaskMirrored = mask.isMirrored();
    }

    /** A 2D texture for the mask, clamped and linear so its edge is smooth when stretched. */
    private static int createMaskTexture() {
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

    /** One linked program over the shared vertex shader, and where its inputs go. */
    private static final class Program {
        /** The linked program. */
        final int mProgram;
        /** The location of the vertex position attribute. */
        final int mPositionHandle;
        /** The location of the texture coordinate attribute. */
        final int mTextureCoordinateHandle;

        /**
         * @throws IllegalStateException if either shader will not compile or the two will not
         *     link
         */
        Program(@NonNull String fragmentShader) {
            int vertex = OesTextureProgram.compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
            int fragment = OesTextureProgram.compile(GLES20.GL_FRAGMENT_SHADER, fragmentShader);
            mProgram = GLES20.glCreateProgram();
            if (mProgram == 0) {
                throw new IllegalStateException("GL would not create a program");
            }
            GLES20.glAttachShader(mProgram, vertex);
            GLES20.glAttachShader(mProgram, fragment);
            GLES20.glLinkProgram(mProgram);
            int[] linked = new int[1];
            GLES20.glGetProgramiv(mProgram, GLES20.GL_LINK_STATUS, linked, 0);
            if (linked[0] != GLES20.GL_TRUE) {
                String log = GLES20.glGetProgramInfoLog(mProgram);
                GLES20.glDeleteProgram(mProgram);
                throw new IllegalStateException("The blur would not link: " + log);
            }
            GLES20.glDeleteShader(vertex);
            GLES20.glDeleteShader(fragment);
            mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
            mTextureCoordinateHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoordinate");
        }

        void use() {
            GLES20.glUseProgram(mProgram);
        }

        int location(@NonNull String uniform) {
            return GLES20.glGetUniformLocation(mProgram, uniform);
        }

        void setMatrix(@NonNull String uniform, @NonNull float[] matrix) {
            GLES20.glUniformMatrix4fv(location(uniform), 1, false, matrix, 0);
        }

        void setVector(@NonNull String uniform, float x, float y) {
            GLES20.glUniform2f(location(uniform), x, y);
        }

        void release() {
            GLES20.glDeleteProgram(mProgram);
        }
    }
}
