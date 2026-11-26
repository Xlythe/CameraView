package com.xlythe.view.camera.pano;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import static com.xlythe.view.camera.ICameraModule.TAG;

/**
 * Shows a panorama as the place it was taken, and lets you look around it.
 *
 * <p>A camera library that can take a photosphere and not show one is half a feature: the file
 * is an equirectangular picture, and every ordinary image view shows it as a very wide
 * photograph with the horizon bent. This wraps it back onto a sphere.</p>
 *
 * <h2>How it draws</h2>
 *
 * <p>With one shader over the whole view rather than a textured sphere. For each pixel it works
 * out which way that pixel is looking, turns that direction into a longitude and latitude, and
 * samples the picture there - which is exactly the inverse of how
 * {@link Equirectangular} put the frames in. There is no mesh, so there are no seams at the
 * poles and nothing to subdivide; the whole projection is six lines of arithmetic in the
 * fragment shader.</p>
 *
 * <p>Where the picture does not cover the direction a pixel is looking - which for a flat sweep
 * is most of the sphere - nothing is drawn. That is read from the picture's own
 * {@link GPanoBounds}, so a sweep that went a quarter of the way round shows as a quarter of
 * the way round rather than being stretched over everything.</p>
 */
public class PhotosphereView extends GLSurfaceView {
    /** How wide an angle of view to show, in degrees. A little narrower than a phone camera. */
    private static final float DEFAULT_FIELD_OF_VIEW = 75f;

    /** How far the angle of view may be pinched, which is how far it can be zoomed. */
    private static final float MIN_FIELD_OF_VIEW = 25f;
    private static final float MAX_FIELD_OF_VIEW = 110f;

    /** How far a drag turns the view, in degrees per pixel at the default angle of view. */
    private static final float DEGREES_PER_PIXEL = 0.12f;

    private final Renderer mRenderer = new Renderer();
    private final ScaleGestureDetector mPinch;

    private float mYawDegrees;
    private float mPitchDegrees;
    private float mFieldOfView = DEFAULT_FIELD_OF_VIEW;
    private float mLastTouchX;
    private float mLastTouchY;

    public PhotosphereView(Context context) {
        this(context, null);
    }

    public PhotosphereView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setEGLContextClientVersion(2);
        setRenderer(mRenderer);
        // Only when something changed, which for a picture that is not moving is when the view
        // is turned: a photosphere redrawn sixty times a second to show the same thing is a
        // flat battery.
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        mPinch = new ScaleGestureDetector(context, new ScaleGestureDetector.OnScaleGestureListener() {
            @Override
            public boolean onScale(@NonNull ScaleGestureDetector detector) {
                setFieldOfView(mFieldOfView / detector.getScaleFactor());
                return true;
            }

            @Override
            public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
                return true;
            }

            @Override
            public void onScaleEnd(@NonNull ScaleGestureDetector detector) {}
        });
    }

    /**
     * Shows a panorama file.
     *
     * <p>Decoded on the calling thread, so for a large photosphere that belongs off the main
     * one. The bounds are read from the file, so a flat sweep is shown as a band rather than
     * wrapped round the sphere - and a picture that is not a panorama at all is still shown, as
     * a full sphere, since that is the most useful guess for a file with no metadata.</p>
     *
     * @return whether the file could be decoded
     */
    public boolean setPanorama(@NonNull File file) {
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (bitmap == null) {
            Log.w(TAG, "Unable to decode " + file);
            return false;
        }
        GPanoBounds bounds = GPanoBounds.from(file);
        setPanorama(bitmap, bounds != null ? bounds
                : GPanoBounds.forFullSphere(bitmap.getWidth(), bitmap.getHeight()));
        return true;
    }

    /**
     * Shows a picture whose bounds the caller already knows.
     *
     * <p>For a panorama that did not come from a file - one just swept, whose
     * {@link PanoramaCanvas} is still in memory.</p>
     */
    public void setPanorama(@NonNull Bitmap panorama, @NonNull GPanoBounds bounds) {
        // Centred where the picture is, so that opening a band does not start you looking at
        // the empty part of the sphere.
        mYawDegrees = bounds.getLeftYaw() + bounds.getYawSpan() / 2;
        mPitchDegrees = bounds.getTopPitch() - bounds.getPitchSpan() / 2;
        mRenderer.setPanorama(panorama, bounds);
        requestRender();
    }

    /** Where the view is looking, as a yaw in degrees. */
    public float getYawDegrees() {
        return mYawDegrees;
    }

    /** Where it is looking, as a pitch in degrees. */
    public float getPitchDegrees() {
        return mPitchDegrees;
    }

    /** Points the view somewhere. */
    public void lookAt(float yawDegrees, float pitchDegrees) {
        mYawDegrees = Equirectangular.wrapYaw(yawDegrees);
        // Held short of the poles: looking exactly along the vertical axis leaves the yaw
        // undefined, so a drag past the pole would spin the view.
        mPitchDegrees = Math.max(-89f, Math.min(89f, pitchDegrees));
        requestRender();
    }

    /** How wide an angle of view to show, which is the zoom. */
    public void setFieldOfView(float degrees) {
        mFieldOfView = Math.max(MIN_FIELD_OF_VIEW, Math.min(MAX_FIELD_OF_VIEW, degrees));
        requestRender();
    }

    public float getFieldOfView() {
        return mFieldOfView;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        mPinch.onTouchEvent(event);
        if (event.getPointerCount() > 1) {
            // A pinch is a zoom, not a drag. Turning at the same time makes both feel wrong.
            return true;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mLastTouchX = event.getX();
                mLastTouchY = event.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                // Scaled by the angle of view, so a drag moves what is under your finger by the
                // same amount however far it is zoomed in.
                float scale = DEGREES_PER_PIXEL * mFieldOfView / DEFAULT_FIELD_OF_VIEW;
                lookAt(mYawDegrees - (event.getX() - mLastTouchX) * scale,
                        mPitchDegrees + (event.getY() - mLastTouchY) * scale);
                mLastTouchX = event.getX();
                mLastTouchY = event.getY();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    /** Draws the sphere, which is one quad and one shader. */
    private final class Renderer implements GLSurfaceView.Renderer {
        private static final String VERTEX_SHADER =
                "attribute vec4 aPosition;\n"
                        + "varying vec2 vScreen;\n"
                        + "void main() {\n"
                        + "  gl_Position = aPosition;\n"
                        + "  vScreen = aPosition.xy;\n"
                        + "}\n";

        /**
         * Samples the sphere per pixel.
         *
         * <p>The direction a pixel looks in is the screen position scaled by the tangent of half
         * the angle of view - a flat window a unit away, the same model the frames were placed
         * with - turned by the view rotation. That direction becomes a longitude and latitude,
         * and those become a place in the picture. Outside the picture, nothing is drawn.</p>
         */
        private static final String FRAGMENT_SHADER =
                "precision highp float;\n"
                        + "uniform sampler2D uTexture;\n"
                        + "uniform mat3 uRotation;\n"
                        + "uniform vec2 uHalfExtent;\n"
                        + "uniform vec4 uBounds;\n"
                        + "varying vec2 vScreen;\n"
                        + "const float PI = 3.14159265359;\n"
                        + "void main() {\n"
                        + "  vec3 ray = normalize(uRotation\n"
                        + "      * vec3(vScreen.x * uHalfExtent.x, vScreen.y * uHalfExtent.y, -1.0));\n"
                        + "  float yaw = atan(ray.x, -ray.z);\n"
                        + "  float pitch = asin(clamp(ray.y, -1.0, 1.0));\n"
                        + "  vec2 full = vec2(yaw / (2.0 * PI) + 0.5, 0.5 - pitch / PI);\n"
                        + "  vec2 at = (full - uBounds.xy) / uBounds.zw;\n"
                        + "  if (at.x < 0.0 || at.x > 1.0 || at.y < 0.0 || at.y > 1.0) {\n"
                        + "    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);\n"
                        + "    return;\n"
                        + "  }\n"
                        + "  gl_FragColor = texture2D(uTexture, at);\n"
                        + "}\n";

        /** A quad over the whole view, in normalized device coordinates. */
        private final FloatBuffer mQuad = floats(new float[] {
                -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f,
        });

        private final float[] mRotation = new float[9];
        private final float[] mMatrix = new float[16];

        private int mProgram;
        private int mPositionHandle;
        private int mTextureHandle;
        private int mRotationHandle;
        private int mHalfExtentHandle;
        private int mBoundsHandle;
        private int mTextureName;

        private int mWidth;
        private int mHeight;

        @Nullable private Bitmap mPending;
        @Nullable private GPanoBounds mBounds;

        private void setPanorama(@NonNull Bitmap panorama, @NonNull GPanoBounds bounds) {
            synchronized (this) {
                mPending = panorama;
                mBounds = bounds;
            }
        }

        @Override
        public void onSurfaceCreated(GL10 unused, EGLConfig config) {
            mProgram = link(VERTEX_SHADER, FRAGMENT_SHADER);
            if (mProgram == 0) {
                return;
            }
            mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
            mTextureHandle = GLES20.glGetUniformLocation(mProgram, "uTexture");
            mRotationHandle = GLES20.glGetUniformLocation(mProgram, "uRotation");
            mHalfExtentHandle = GLES20.glGetUniformLocation(mProgram, "uHalfExtent");
            mBoundsHandle = GLES20.glGetUniformLocation(mProgram, "uBounds");
            int[] names = new int[1];
            GLES20.glGenTextures(1, names, 0);
            mTextureName = names[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureName);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            // Clamped rather than repeated: the picture may be a band, and repeating it would
            // tile the band across the sphere instead of leaving the rest empty.
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        }

        @Override
        public void onSurfaceChanged(GL10 unused, int width, int height) {
            mWidth = width;
            mHeight = height;
            GLES20.glViewport(0, 0, width, height);
        }

        @Override
        public void onDrawFrame(GL10 unused) {
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (mProgram == 0) {
                return;
            }
            GPanoBounds bounds;
            synchronized (this) {
                if (mPending != null) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureName);
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, mPending, 0);
                    mPending = null;
                }
                bounds = mBounds;
            }
            if (bounds == null || mWidth == 0 || mHeight == 0) {
                return;
            }
            GLES20.glUseProgram(mProgram);
            mQuad.position(0);
            GLES20.glVertexAttribPointer(mPositionHandle, 2, GLES20.GL_FLOAT, false, 0, mQuad);
            GLES20.glEnableVertexAttribArray(mPositionHandle);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureName);
            GLES20.glUniform1i(mTextureHandle, 0);
            GLES20.glUniformMatrix3fv(mRotationHandle, 1, false, rotation(), 0);
            // Half the width and height of the window a unit away, which is what turns a screen
            // position into a direction. The vertical follows from the aspect ratio, so the
            // picture is not squashed when the view is not square.
            float halfWidth = (float) Math.tan(Math.toRadians(mFieldOfView / 2));
            GLES20.glUniform2f(mHalfExtentHandle, halfWidth,
                    halfWidth * mHeight / Math.max(1, mWidth));
            GLES20.glUniform4f(mBoundsHandle,
                    (float) bounds.getLeft() / bounds.getFullWidth(),
                    (float) bounds.getTop() / bounds.getFullHeight(),
                    (float) bounds.getImageWidth() / bounds.getFullWidth(),
                    (float) bounds.getImageHeight() / bounds.getFullHeight());
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(mPositionHandle);
        }

        /** Where the view is looking, as the rotation the shader applies to each ray. */
        @NonNull
        private float[] rotation() {
            Matrix.setIdentityM(mMatrix, 0);
            // Negated, because a positive rotation about the vertical axis turns the camera
            // westward while a yaw counts eastward - so using the yaw as the angle looks the
            // opposite way round the sphere, which draws something plausible and wrong.
            Matrix.rotateM(mMatrix, 0, -mYawDegrees, 0f, 1f, 0f);
            Matrix.rotateM(mMatrix, 0, mPitchDegrees, 1f, 0f, 0f);
            // The top left three by three of a four by four, which is all a direction needs.
            for (int column = 0; column < 3; column++) {
                for (int row = 0; row < 3; row++) {
                    mRotation[column * 3 + row] = mMatrix[column * 4 + row];
                }
            }
            return mRotation;
        }
    }

    @NonNull
    private static FloatBuffer floats(@NonNull float[] values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(values).position(0);
        return buffer;
    }

    /** Builds the program, or returns zero and says why rather than drawing nothing in silence. */
    private static int link(@NonNull String vertex, @NonNull String fragment) {
        int vertexShader = compile(GLES20.GL_VERTEX_SHADER, vertex);
        int fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER, fragment);
        if (vertexShader == 0 || fragmentShader == 0) {
            return 0;
        }
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        // Deleted either way: they are attached, so the program keeps them alive while it needs
        // them, and nothing else will.
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);
        if (linked[0] == 0) {
            Log.e(TAG, "Unable to link the photosphere program: "
                    + GLES20.glGetProgramInfoLog(program));
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
            Log.e(TAG, "Unable to compile a photosphere shader: "
                    + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }
}
