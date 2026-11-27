package com.xlythe.view.camera.stream;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.annotation.WorkerThread;

import com.xlythe.view.camera.CameraView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A surface that copies every frame drawn into it onto another surface, mirrored left to right.
 *
 * <p>Exists because an encoder cannot be told to mirror. A front camera's preview is shown
 * mirrored, and a capture has to come out the way its preview looked, but the transform a camera
 * attaches to its buffers for the preview is one a video encoder ignores - and
 * {@code OutputConfiguration.setMirrorMode} says as much about MediaCodec outputs. So the frames
 * are drawn once more, through OpenGL, with the horizontal texture coordinate reversed.</p>
 *
 * <p>The copy samples the buffer as it is stored, ignoring the transform the producer attached,
 * so the result is the stored frame mirrored about its own vertical axis. Its timestamp is passed
 * on unchanged: an encoder takes a frame's presentation time from it, and the motion photo needs
 * the camera's.</p>
 */
@RequiresApi(21)
final class MirroringSurface implements SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = CameraView.class.getSimpleName();

    /** How long setting up the GL context may take before giving up. */
    private static final long SETUP_TIMEOUT_SECONDS = 5;

    /** {@code EGL_RECORDABLE_ANDROID}, which a config must have to render into an encoder. */
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n"
                    + "attribute vec2 aTexCoord;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "void main() {\n"
                    + "  gl_Position = aPosition;\n"
                    + "  vTexCoord = aTexCoord;\n"
                    + "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n"
                    + "precision mediump float;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "uniform samplerExternalOES uTexture;\n"
                    + "void main() {\n"
                    + "  gl_FragColor = texture2D(uTexture, vTexCoord);\n"
                    + "}\n";

    /**
     * A quad covering the viewport, as x, y, s, t per corner. The texture's t runs from the
     * buffer's first row, so the top of the viewport takes t = 0 and the copy is not turned upside
     * down; s runs backwards, which is the mirroring.
     */
    private static final float[] QUAD = {
            -1f, -1f, 1f, 1f,
            1f, -1f, 0f, 1f,
            -1f, 1f, 1f, 0f,
            1f, 1f, 0f, 0f,
    };

    /** The thread every GL call is made on, since a context belongs to one thread. */
    private final HandlerThread mThread;
    /** Posts work to {@link #mThread}. */
    private final Handler mHandler;
    /** The width frames are drawn at, which is the encoder's. */
    private final int mWidth;
    /** The height frames are drawn at, which is the encoder's. */
    private final int mHeight;

    /** The display the context and surface belong to. Only touched on {@link #mThread}. */
    private EGLDisplay mDisplay = EGL14.EGL_NO_DISPLAY;
    /** The GL context. Only touched on {@link #mThread}. */
    private EGLContext mContext = EGL14.EGL_NO_CONTEXT;
    /** The window surface wrapping the output. Only touched on {@link #mThread}. */
    private EGLSurface mEglSurface = EGL14.EGL_NO_SURFACE;
    /** The shader program. Only touched on {@link #mThread}. */
    private int mProgram;
    /** The external texture the input frames arrive in. Only touched on {@link #mThread}. */
    private int mTexture;
    /** The quad's vertices. Only touched on {@link #mThread}. */
    private FloatBuffer mQuad;
    /** Receives the input frames. */
    private SurfaceTexture mInputTexture;
    /** What producers draw into. */
    private Surface mInput;
    /** Set once released, after which frames are ignored. Only touched on {@link #mThread}. */
    private boolean mReleased;

    /**
     * Sets up the copy, blocking until the GL context is ready.
     *
     * @throws IllegalStateException if the device would not give a context that can render into
     *     the output
     */
    MirroringSurface(@NonNull Surface output, int width, int height) {
        mWidth = width;
        mHeight = height;
        mThread = new HandlerThread("MirroringSurface");
        mThread.start();
        mHandler = new Handler(mThread.getLooper());

        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        mHandler.post(() -> {
            try {
                setUp(output);
            } catch (RuntimeException error) {
                failure.set(error);
            } finally {
                ready.countDown();
            }
        });
        try {
            if (!ready.await(SETUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                failure.compareAndSet(null, new IllegalStateException("GL setup timed out"));
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            failure.compareAndSet(null, new IllegalStateException("Interrupted setting up GL"));
        }
        if (failure.get() != null) {
            release();
            throw failure.get();
        }
    }

    /** The surface to hand a camera. */
    @AnyThread
    @NonNull
    Surface getInputSurface() {
        return mInput;
    }

    /** Tears the copy down. The output surface stays its owner's to release. */
    @AnyThread
    void release() {
        mHandler.post(() -> {
            mReleased = true;
            if (mInput != null) {
                mInput.release();
            }
            if (mInputTexture != null) {
                mInputTexture.release();
            }
            if (mDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(mDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT);
                if (mEglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(mDisplay, mEglSurface);
                }
                if (mContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(mDisplay, mContext);
                }
                EGL14.eglReleaseThread();
                EGL14.eglTerminate(mDisplay);
            }
            mDisplay = EGL14.EGL_NO_DISPLAY;
            mContext = EGL14.EGL_NO_CONTEXT;
            mEglSurface = EGL14.EGL_NO_SURFACE;
        });
        mThread.quitSafely();
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        // Delivered on mThread, because that is the handler the listener was registered with.
        if (mReleased) {
            return;
        }
        try {
            drawFrame();
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to mirror a frame", error);
        }
    }

    @WorkerThread
    private void setUp(@NonNull Surface output) {
        mDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (mDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new IllegalStateException("No EGL display");
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(mDisplay, version, 0, version, 1)) {
            throw new IllegalStateException("EGL would not initialize");
        }
        int[] attributes = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(mDisplay, attributes, 0, configs, 0, 1, count, 0)
                || count[0] == 0) {
            throw new IllegalStateException("No recordable EGL config");
        }
        mContext = EGL14.eglCreateContext(mDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        if (mContext == EGL14.EGL_NO_CONTEXT) {
            throw new IllegalStateException("No EGL context");
        }
        mEglSurface = EGL14.eglCreateWindowSurface(
                mDisplay, configs[0], output, new int[] {EGL14.EGL_NONE}, 0);
        if (mEglSurface == EGL14.EGL_NO_SURFACE) {
            throw new IllegalStateException("The output cannot be rendered into");
        }
        if (!EGL14.eglMakeCurrent(mDisplay, mEglSurface, mEglSurface, mContext)) {
            throw new IllegalStateException("The EGL context would not become current");
        }

        mProgram = createProgram();
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        mTexture = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTexture);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        mQuad = ByteBuffer.allocateDirect(QUAD.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        mQuad.put(QUAD).position(0);

        mInputTexture = new SurfaceTexture(mTexture);
        mInputTexture.setDefaultBufferSize(mWidth, mHeight);
        mInputTexture.setOnFrameAvailableListener(this, mHandler);
        mInput = new Surface(mInputTexture);
    }

    @WorkerThread
    private void drawFrame() {
        mInputTexture.updateTexImage();
        GLES20.glViewport(0, 0, mWidth, mHeight);
        GLES20.glUseProgram(mProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTexture);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(mProgram, "uTexture"), 0);

        int position = GLES20.glGetAttribLocation(mProgram, "aPosition");
        int texCoord = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        mQuad.position(0);
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, mQuad);
        GLES20.glEnableVertexAttribArray(position);
        mQuad.position(2);
        GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 16, mQuad);
        GLES20.glEnableVertexAttribArray(texCoord);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(position);
        GLES20.glDisableVertexAttribArray(texCoord);

        EGLExt.eglPresentationTimeANDROID(mDisplay, mEglSurface, mInputTexture.getTimestamp());
        EGL14.eglSwapBuffers(mDisplay, mEglSurface);
    }

    @WorkerThread
    private static int createProgram() {
        int vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        if (linked[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new IllegalStateException("The mirroring shader would not link: " + log);
        }
        return program;
    }

    @WorkerThread
    private static int compile(int type, @NonNull String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("The mirroring shader would not compile: " + log);
        }
        return shader;
    }
}
