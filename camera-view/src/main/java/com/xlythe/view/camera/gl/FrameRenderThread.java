package com.xlythe.view.camera.gl;

import android.graphics.SurfaceTexture;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;

import com.xlythe.view.camera.ICameraModule;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The thread that owns the GL context, the camera's {@link SurfaceTexture} and the outputs.
 *
 * <p>One camera stream comes in and any number of surfaces go out: the preview, an encoder, a
 * still. Every frame is drawn once per output, each with its own size and its own transform,
 * because a preview and a recording rarely want the same shape of the same frame.</p>
 *
 * <p>The context belongs to this thread and nothing may touch it from anywhere else, which is why
 * the camera's frames arrive here rather than on the main thread: {@link SurfaceTexture} is given
 * this thread's handler, so a frame is consumed and drawn on the thread that can.</p>
 *
 * <p>This is the driver. It is as thin as a thing that owns a thread can be, and every decision it
 * makes is in {@link GlMath}, because nothing here can run without a GPU.</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class FrameRenderThread {
    private static final String TAG = ICameraModule.TAG;

    /**
     * How long to wait for the context to come up, or to go away, before giving up on it.
     *
     * <p>Under the five seconds an unresponsive main thread costs an app, because this is waited
     * on from there: a camera being configured is a main-thread operation in every backend here.
     * Two is far longer than bringing an EGL context up takes even on a cold driver, so reaching
     * it means the thread is wedged - which is reported as GL being unavailable rather than held
     * on to until the system kills the app.</p>
     */
    private static final long START_TIMEOUT_SECONDS = 2;

    /** How the matrix a frame is drawn with is arrived at, which is the backend's business. */
    public interface TextureTransform {
        /**
         * @param surfaceTextureMatrix what {@link SurfaceTexture#getTransformMatrix} reported for
         *     this frame
         * @param into receives the 4x4 column-major matrix to draw with. Copy the argument in to
         *     draw the camera's own idea of the frame.
         */
        void onTextureTransform(@NonNull float[] surfaceTextureMatrix, @NonNull float[] into);
    }

    /** One surface being drawn into, and everything known about how to draw into it. */
    private static final class Output {
        private final Surface mSurface;
        private final Size mSize;
        private final Size mFrameSize;
        private final GlMath.ScaleType mScaleType;
        private final TextureTransform mTransform;
        @Nullable private EGLSurface mEglSurface;
        /** Set once this output has failed, so a broken surface is not retried every frame. */
        private boolean mFailed;

        private Output(@NonNull Surface surface, @NonNull Size size, @NonNull Size frameSize,
                @NonNull GlMath.ScaleType scaleType, @NonNull TextureTransform transform) {
            mSurface = surface;
            mSize = size;
            mFrameSize = frameSize;
            mScaleType = scaleType;
            mTransform = transform;
        }
    }

    private final String mName;

    /**
     * Outputs by the key the caller added them under, in the order they were added.
     *
     * <p>Only ever read and written on the render thread, which is what makes a plain map safe
     * here: adding, removing and drawing are all posted to it.</p>
     */
    private final Map<Object, Output> mOutputs = new LinkedHashMap<>();

    private final float[] mSurfaceTextureMatrix = GlMath.identity();
    private final float[] mTextureMatrix = GlMath.identity();

    @Nullable private HandlerThread mThread;
    @Nullable private Handler mHandler;
    @Nullable private EglCore mEgl;
    @Nullable private OesTextureProgram mProgram;
    @Nullable private SurfaceTexture mSurfaceTexture;
    @Nullable private GlFrame mFrame;
    @Nullable private volatile FrameRenderer mRenderer;
    @Nullable private CameraTextureProvider mTextureProvider;
    private int mTextureName;
    private volatile boolean mRunning;

    public FrameRenderThread(@NonNull String name) {
        mName = name;
    }

    /**
     * Who creates the texture the camera renders into. Must be set before {@link #start}, because
     * that is when the texture is made and a camera cannot be moved to another one afterwards.
     */
    public void setCameraTextureProvider(@Nullable CameraTextureProvider provider) {
        if (mRunning) {
            throw new IllegalStateException("The camera texture is created when the renderer starts");
        }
        mTextureProvider = provider;
    }

    /** What draws each frame, or null to draw the camera frame and nothing else. */
    public void setFrameRenderer(@Nullable FrameRenderer renderer) {
        mRenderer = renderer;
    }

    /**
     * Brings up the thread and the context.
     *
     * <p>Returns rather than throws, because a device whose driver will not give us a context is
     * a device the camera still has to work on: the caller's answer to false is to use the path
     * that does not go through GL.</p>
     *
     * @return whether the context, the shader and the texture all came up
     */
    public boolean start() {
        if (mRunning) {
            return true;
        }
        HandlerThread thread = new HandlerThread(mName);
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        mHandler = handler;
        CountDownLatch ready = new CountDownLatch(1);
        AtomicBoolean started = new AtomicBoolean();
        handler.post(() -> {
            started.set(initialize());
            ready.countDown();
        });
        try {
            if (!ready.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(TAG, "Timed out starting the frame renderer");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "Interrupted starting the frame renderer", error);
        }
        if (!started.get()) {
            thread.quitSafely();
            mHandler = null;
            return false;
        }
        mThread = thread;
        mRunning = true;
        return true;
    }

    /** The texture the camera writes into. Null until {@link #start} has succeeded. */
    @Nullable
    public SurfaceTexture getInputSurfaceTexture() {
        return mSurfaceTexture;
    }

    /**
     * Adds a surface to draw every frame into, replacing any output under the same key.
     *
     * @param key identifies this output for {@link #removeOutput}; the caller's own object
     * @param frameSize the size the frame has once {@code transform} has been applied to it, which
     *     is what decides where {@code scaleType} places it. A backend whose transform already
     *     fits the frame to the output passes the output's own size.
     */
    public void addOutput(@NonNull Object key, @NonNull Surface surface, @NonNull Size size,
            @NonNull Size frameSize, @NonNull GlMath.ScaleType scaleType,
            @NonNull TextureTransform transform) {
        Output output = new Output(surface, size, frameSize, scaleType, transform);
        post(() -> {
            releaseOutput(mOutputs.remove(key));
            mOutputs.put(key, output);
        });
    }

    /** Stops drawing into the surface added under this key and lets go of it. */
    public void removeOutput(@NonNull Object key) {
        post(() -> releaseOutput(mOutputs.remove(key)));
    }

    /**
     * Shuts the thread down and gives the context, the texture and every output back.
     *
     * <p>Blocks until the GL thread has finished, because what comes next is usually the camera
     * releasing the surfaces this thread is drawing into.</p>
     */
    public void stop() {
        HandlerThread thread = mThread;
        Handler handler = mHandler;
        if (thread == null || handler == null) {
            return;
        }
        mRunning = false;
        CountDownLatch stopped = new CountDownLatch(1);
        handler.post(() -> {
            try {
                releaseGl();
            } finally {
                stopped.countDown();
            }
        });
        try {
            if (!stopped.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(TAG, "Timed out stopping the frame renderer");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "Interrupted stopping the frame renderer", error);
        }
        thread.quitSafely();
        mThread = null;
        mHandler = null;
    }

    private void post(@NonNull Runnable work) {
        Handler handler = mHandler;
        if (handler == null) {
            Log.w(TAG, "The frame renderer is not running");
            return;
        }
        handler.post(work);
    }

    /** Runs on the render thread. Everything it creates is released by {@link #releaseGl}. */
    private boolean initialize() {
        try {
            EglCore egl = new EglCore();
            mEgl = egl;
            // A GLES call without a current context does nothing and reports nothing, so the
            // context is made current against the offscreen surface before the program and the
            // texture are created - there is no output surface to make current against yet.
            egl.makeCurrent();
            OesTextureProgram program = new OesTextureProgram();
            mProgram = program;
            mFrame = new GlFrame(program);
            CameraTextureProvider provider = mTextureProvider;
            mTextureName = provider == null
                    ? program.createExternalTexture() : provider.onCreateCameraTexture();
            SurfaceTexture texture = new SurfaceTexture(mTextureName);
            texture.setOnFrameAvailableListener(this::onFrameAvailable, mHandler);
            mSurfaceTexture = texture;
            FrameRenderer renderer = mRenderer;
            if (renderer != null) {
                renderer.onRendererStarted();
            }
            return true;
        } catch (RuntimeException | LinkageError error) {
            Log.e(TAG, "This device will not render camera frames through GL", error);
            releaseGl();
            return false;
        }
    }

    /** Runs on the render thread, because that is the handler the SurfaceTexture was given. */
    private void onFrameAvailable(@NonNull SurfaceTexture texture) {
        EglCore egl = mEgl;
        if (!mRunning || egl == null) {
            return;
        }
        try {
            // Consuming a frame is a GL operation on the texture, so it needs a current context
            // even though nothing is being drawn yet.
            egl.makeCurrent();
            CameraTextureProvider provider = mTextureProvider;
            boolean advanced = provider != null && provider.onCameraFrameAvailable(texture);
            if (!advanced) {
                texture.updateTexImage();
            }
            texture.getTransformMatrix(mSurfaceTextureMatrix);
            long timestampNanos = texture.getTimestamp();
            for (Output output : mOutputs.values()) {
                drawInto(output, timestampNanos);
            }
        } catch (RuntimeException | LinkageError error) {
            // One bad frame is not worth tearing the camera down for: the next one usually works,
            // and a surface that has genuinely gone is caught per output below.
            Log.w(TAG, "Dropped a camera frame", error);
        }
    }

    private void drawInto(@NonNull Output output, long timestampNanos) {
        EglCore egl = mEgl;
        GlFrame frame = mFrame;
        if (egl == null || frame == null || output.mFailed) {
            return;
        }
        try {
            if (output.mEglSurface == null) {
                output.mEglSurface = egl.createWindowSurface(output.mSurface);
            }
            egl.makeCurrent(output.mEglSurface);
            output.mTransform.onTextureTransform(mSurfaceTextureMatrix, mTextureMatrix);
            // Cleared because a frame that does not cover the output - and under FIT_CENTER none
            // of them do - would otherwise show whatever the last frame left in the buffer.
            GLES20.glViewport(0, 0, output.mSize.getWidth(), output.mSize.getHeight());
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            frame.set(mTextureName, mTextureMatrix, output.mSize.getWidth(),
                    output.mSize.getHeight(), timestampNanos,
                    GlMath.viewport(output.mScaleType, output.mFrameSize, output.mSize));
            FrameRenderer renderer = mRenderer;
            if (renderer == null || !renderer.onDrawFrame(frame)) {
                frame.drawCameraFrame();
            }
            egl.setPresentationTime(output.mEglSurface, timestampNanos);
            egl.swapBuffers(output.mEglSurface);
        } catch (RuntimeException | LinkageError error) {
            // This output is gone, which happens when a use case is unbound a frame before the
            // renderer hears about it. The others keep drawing.
            Log.w(TAG, "Giving up on a frame output", error);
            output.mFailed = true;
            releaseOutput(output);
        }
    }

    private void releaseOutput(@Nullable Output output) {
        EglCore egl = mEgl;
        if (output == null || output.mEglSurface == null) {
            return;
        }
        if (egl != null) {
            egl.releaseSurface(output.mEglSurface);
        }
        output.mEglSurface = null;
    }

    /**
     * Runs on the render thread, and has to cope with being called part way through
     * {@link #initialize} - which is the whole reason every field it touches is nullable.
     */
    private void releaseGl() {
        mRunning = false;
        FrameRenderer renderer = mRenderer;
        if (renderer != null && mProgram != null) {
            try {
                renderer.onRendererStopped();
            } catch (RuntimeException error) {
                Log.w(TAG, "A frame renderer threw on the way out", error);
            }
        }
        for (Output output : mOutputs.values()) {
            releaseOutput(output);
        }
        mOutputs.clear();
        if (mSurfaceTexture != null) {
            mSurfaceTexture.setOnFrameAvailableListener(null);
            mSurfaceTexture.release();
            mSurfaceTexture = null;
        }
        if (mTextureName != 0) {
            CameraTextureProvider provider = mTextureProvider;
            if (provider != null) {
                provider.onReleaseCameraTexture(mTextureName);
            } else if (mProgram != null) {
                mProgram.deleteTexture(mTextureName);
            }
            mTextureName = 0;
        }
        if (mProgram != null) {
            mProgram.release();
            mProgram = null;
        }
        mFrame = null;
        if (mEgl != null) {
            mEgl.release();
            mEgl = null;
        }
    }
}
