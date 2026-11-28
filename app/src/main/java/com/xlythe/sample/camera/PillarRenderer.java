package com.xlythe.sample.camera;

import android.opengl.GLES20;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.xlythe.view.camera.ArAnchor;
import com.xlythe.view.camera.gl.FrameRenderer;
import com.xlythe.view.camera.gl.GlFrame;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.xlythe.sample.camera.MainActivity.TAG;

/**
 * Stands a pillar of light on every anchor, hidden wherever something real stands in front of it.
 *
 * <p>Each pillar is a quad turned about the vertical to face the camera, so it reads as a beam
 * from any side, drawn additively so it glows over the frame. What hides it is the depth AR
 * measures: every fragment compares how far away it is with how far away the scene is at the same
 * place in the camera's picture, and fades out over the last few centimetres behind it, so a chair
 * leg passes in front of the light rather than the light being painted over the chair.</p>
 *
 * <p>All of the library's part is three calls on the frame - the projection, the view and the
 * depth texture - and the anchors' model matrices. Everything about how the light looks is
 * here.</p>
 */
final class PillarRenderer implements FrameRenderer {
    /** How tall a pillar stands, in metres. */
    private static final float HEIGHT_METERS = 3f;

    /** Half a pillar's width, in metres, out to where its glow ends. */
    private static final float HALF_WIDTH_METERS = 0.15f;

    /** The nearest anything is drawn, in metres. Nearer than a phone's lens is pointless. */
    private static final float NEAR_METERS = 0.05f;

    /** The furthest anything is drawn, in metres. */
    private static final float FAR_METERS = 100f;

    /** The light's colour, a warm white. */
    private static final float[] COLOR = {1f, 0.86f, 0.5f};

    /** Stands the quad at the anchor, and says how far away each corner is. */
    @VisibleForTesting
    static final String VERTEX_SHADER =
            "uniform mat4 uProjection;\n"
                    + "uniform mat4 uView;\n"
                    + "uniform vec3 uBase;\n"
                    + "uniform vec3 uAcross;\n"
                    + "uniform float uHeight;\n"
                    + "attribute vec2 aCorner;\n"
                    + "varying vec2 vCorner;\n"
                    + "varying vec4 vClip;\n"
                    + "varying float vDistance;\n"
                    + "void main() {\n"
                    + "  vec3 world = uBase + uAcross * aCorner.x\n"
                    + "      + vec3(0.0, uHeight * aCorner.y, 0.0);\n"
                    + "  vec4 eye = uView * vec4(world, 1.0);\n"
                    + "  vDistance = -eye.z;\n"
                    + "  vClip = uProjection * eye;\n"
                    + "  vCorner = aCorner;\n"
                    + "  gl_Position = vClip;\n"
                    + "}\n";

    /**
     * Glows brightest down the middle and fades towards the top, and out wherever the scene is
     * nearer. Depth arrives as millimetres split over two bytes, low in luminance and high in
     * alpha, and is sampled where the camera's own picture is: the frame's texture matrix applied
     * to where this fragment lands on the frame.
     */
    @VisibleForTesting
    static final String FRAGMENT_SHADER =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
                    + "precision highp float;\n"
                    + "#else\n"
                    + "precision mediump float;\n"
                    + "#endif\n"
                    + "uniform sampler2D uDepth;\n"
                    + "uniform mat4 uTexMatrix;\n"
                    + "uniform float uHasDepth;\n"
                    + "uniform vec3 uColor;\n"
                    + "varying vec2 vCorner;\n"
                    + "varying vec4 vClip;\n"
                    + "varying float vDistance;\n"
                    + "void main() {\n"
                    + "  float across = 1.0 - abs(vCorner.x);\n"
                    + "  float core = pow(across, 6.0);\n"
                    + "  float glow = 0.45 * pow(across, 1.5);\n"
                    + "  float up = 1.0 - vCorner.y;\n"
                    + "  float fade = up * smoothstep(0.0, 0.04, vCorner.y);\n"
                    + "  float light = (core + glow) * (0.25 + 0.75 * fade);\n"
                    + "  if (uHasDepth > 0.5) {\n"
                    + "    vec2 frame = vClip.xy / vClip.w * 0.5 + 0.5;\n"
                    + "    vec2 camera = (uTexMatrix * vec4(frame, 0.0, 1.0)).xy;\n"
                    + "    vec4 texel = texture2D(uDepth, camera);\n"
                    + "    float scene = dot(texel.ra, vec2(255.0, 65280.0)) / 1000.0;\n"
                    + "    if (scene > 0.0) {\n"
                    + "      light *= clamp((scene - vDistance) / 0.1 + 1.0, 0.0, 1.0);\n"
                    + "    }\n"
                    + "  }\n"
                    + "  gl_FragColor = vec4(uColor * light, light);\n"
                    + "}\n";

    /** The quad's corners: across from -1 to 1, up from 0 to 1, as a strip. */
    private static final float[] CORNERS = {-1f, 0f, 1f, 0f, -1f, 1f, 1f, 1f};

    /** The anchors to stand pillars on. Added on the main thread, read on the render thread. */
    private final List<ArAnchor> mAnchors = new CopyOnWriteArrayList<>();

    /** The quad, in native memory as GL reads it. */
    private final FloatBuffer mCorners = ByteBuffer.allocateDirect(CORNERS.length * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();

    /** The frame's projection, filled for each output. */
    private final float[] mProjection = new float[16];

    /** The frame's view, filled for each output. */
    private final float[] mView = new float[16];

    /** An anchor's model matrix, filled for each pillar. */
    private final float[] mModel = new float[16];

    /** The pillar's program, or 0 before the renderer starts. */
    private int mProgram;

    /** Where the program reads the frame's projection. */
    private int mProjectionLocation;
    /** Where it reads the frame's view. */
    private int mViewLocation;
    /** Where it reads the pillar's foot, in the world. */
    private int mBaseLocation;
    /** Where it reads half the pillar's width, level and square to the camera. */
    private int mAcrossLocation;
    /** Where it reads the pillar's height. */
    private int mHeightLocation;
    /** Where it reads each corner of the quad. */
    private int mCornerLocation;
    /** Where it reads the depth texture's unit. */
    private int mDepthLocation;
    /** Where it reads the frame's texture matrix, which places the depth. */
    private int mTexMatrixLocation;
    /** Where it reads whether there is depth to hide behind. */
    private int mHasDepthLocation;
    /** Where it reads the light's colour. */
    private int mColorLocation;

    PillarRenderer() {
        mCorners.put(CORNERS).position(0);
    }

    /** Stands a pillar on an anchor. */
    void add(@NonNull ArAnchor anchor) {
        mAnchors.add(anchor);
    }

    /** Takes every pillar down. */
    void clear() {
        for (ArAnchor anchor : mAnchors) {
            anchor.detach();
        }
        mAnchors.clear();
    }

    @Override
    public void onRendererStarted() {
        mProgram = link(VERTEX_SHADER, FRAGMENT_SHADER);
        mProjectionLocation = GLES20.glGetUniformLocation(mProgram, "uProjection");
        mViewLocation = GLES20.glGetUniformLocation(mProgram, "uView");
        mBaseLocation = GLES20.glGetUniformLocation(mProgram, "uBase");
        mAcrossLocation = GLES20.glGetUniformLocation(mProgram, "uAcross");
        mHeightLocation = GLES20.glGetUniformLocation(mProgram, "uHeight");
        mCornerLocation = GLES20.glGetAttribLocation(mProgram, "aCorner");
        mDepthLocation = GLES20.glGetUniformLocation(mProgram, "uDepth");
        mTexMatrixLocation = GLES20.glGetUniformLocation(mProgram, "uTexMatrix");
        mHasDepthLocation = GLES20.glGetUniformLocation(mProgram, "uHasDepth");
        mColorLocation = GLES20.glGetUniformLocation(mProgram, "uColor");
    }

    @Override
    public void onRendererStopped() {
        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }
    }

    @Override
    public boolean onDrawFrame(@NonNull GlFrame frame) {
        frame.drawCameraFrame();
        if (mProgram == 0 || mAnchors.isEmpty()
                || !frame.getProjectionMatrix(mProjection, NEAR_METERS, FAR_METERS)
                || !frame.getViewMatrix(mView)) {
            return true;
        }
        // Where the camera stands, which each pillar turns to face: the view matrix's inverse
        // translation.
        float cameraX = -(mView[0] * mView[12] + mView[1] * mView[13] + mView[2] * mView[14]);
        float cameraZ = -(mView[8] * mView[12] + mView[9] * mView[13] + mView[10] * mView[14]);

        GLES20.glUseProgram(mProgram);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);
        GLES20.glUniformMatrix4fv(mProjectionLocation, 1, false, mProjection, 0);
        GLES20.glUniformMatrix4fv(mViewLocation, 1, false, mView, 0);
        GLES20.glUniformMatrix4fv(mTexMatrixLocation, 1, false, frame.getTextureMatrix(), 0);
        GLES20.glUniform1f(mHeightLocation, HEIGHT_METERS);
        GLES20.glUniform3fv(mColorLocation, 1, COLOR, 0);
        int depth = frame.getDepthTextureName();
        GLES20.glUniform1f(mHasDepthLocation, depth == 0 ? 0f : 1f);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depth);
        GLES20.glUniform1i(mDepthLocation, 0);
        mCorners.position(0);
        GLES20.glEnableVertexAttribArray(mCornerLocation);
        GLES20.glVertexAttribPointer(mCornerLocation, 2, GLES20.GL_FLOAT, false, 0, mCorners);

        for (ArAnchor anchor : mAnchors) {
            if (!anchor.getModelMatrix(mModel)) {
                continue;
            }
            float baseX = mModel[12];
            float baseY = mModel[13];
            float baseZ = mModel[14];
            float towardX = cameraX - baseX;
            float towardZ = cameraZ - baseZ;
            float length = (float) Math.hypot(towardX, towardZ);
            if (length < 1e-3f) {
                continue;
            }
            // Across the pillar is level and square to the line to the camera.
            GLES20.glUniform3f(mBaseLocation, baseX, baseY, baseZ);
            GLES20.glUniform3f(mAcrossLocation, towardZ / length * HALF_WIDTH_METERS, 0f,
                    -towardX / length * HALF_WIDTH_METERS);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }

        GLES20.glDisableVertexAttribArray(mCornerLocation);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glUseProgram(0);
        return true;
    }

    private static int link(@NonNull String vertexSource, @NonNull String fragmentSource) {
        int vertex = compile(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragment = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            Log.e(TAG, "The pillar would not link: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            return 0;
        }
        return program;
    }

    private static int compile(int type, @NonNull String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "The pillar would not compile: " + GLES20.glGetShaderInfoLog(shader));
        }
        return shader;
    }
}
