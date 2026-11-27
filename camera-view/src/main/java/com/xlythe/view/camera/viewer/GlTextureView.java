package com.xlythe.view.camera.viewer;

import static com.xlythe.view.camera.ICameraModule.TAG;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.TextureView;

import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGL11;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

/**
 * Draws a GL renderer into a TextureView, on a thread of its own, a frame each time one is asked
 * for.
 *
 * <p>A TextureView rather than a SurfaceView because it is part of the view hierarchy: it is
 * clipped, faded, animated and covered like any other view, which a SurfaceView, a hole cut
 * through the window to a surface behind it, is not. Inside Compose a SurfaceView's hole is
 * painted over by whatever the composition draws behind it.</p>
 *
 * <p>The renderer hears onSurfaceCreated for each new GL context: one each time the view gets a
 * surface, and again if the context is lost.</p>
 */
final class GlTextureView extends TextureView implements TextureView.SurfaceTextureListener {
    /** EGL_RENDERABLE_TYPE, which EGL 1.0 does not name. */
    private static final int EGL_RENDERABLE_TYPE = 0x3040;

    /** EGL_OPENGL_ES2_BIT. */
    private static final int EGL_OPENGL_ES2_BIT = 4;

    /** EGL_CONTEXT_CLIENT_VERSION. */
    private static final int EGL_CONTEXT_CLIENT_VERSION = 0x3098;

    /** What draws each frame. */
    @NonNull
    private final GLSurfaceView.Renderer mRenderer;

    /** The thread drawing into the current surface, or null without one. */
    @Nullable
    private volatile RenderThread mThread;

    GlTextureView(@NonNull Context context, @NonNull GLSurfaceView.Renderer renderer) {
        super(context);
        mRenderer = renderer;
        setSurfaceTextureListener(this);
    }

    /** Asks for a frame. Requests made before the last one is drawn are drawn together. */
    @AnyThread
    void requestRender() {
        RenderThread thread = mThread;
        if (thread != null) {
            thread.requestRender();
        }
    }

    @MainThread
    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width,
            int height) {
        RenderThread thread = new RenderThread(surface, width, height, mRenderer);
        mThread = thread;
        thread.start();
    }

    @MainThread
    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width,
            int height) {
        RenderThread thread = mThread;
        if (thread != null) {
            thread.resize(width, height);
        }
    }

    /** Hands the surface to the thread to release, once it has stopped drawing into it. */
    @MainThread
    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
        RenderThread thread = mThread;
        mThread = null;
        if (thread == null) {
            return true;
        }
        thread.finish();
        return false;
    }

    @MainThread
    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}

    /** Owns the GL context and draws into one surface until told to finish. */
    private static final class RenderThread extends HandlerThread {
        /** What it draws into, released when the thread finishes. */
        @NonNull
        private final SurfaceTexture mSurfaceTexture;

        /** What draws each frame. */
        @NonNull
        private final GLSurfaceView.Renderer mRenderer;

        /** Whether a frame has been asked for and not yet drawn. */
        private final AtomicBoolean mFramePending = new AtomicBoolean();

        /** Draws the frame asked for. */
        private final Runnable mDraw = new Runnable() {
            @Override
            public void run() {
                draw();
            }
        };

        /** Where the thread's work is posted; null until its looper is ready. */
        @Nullable
        private volatile Handler mHandler;

        /** The surface's size when it arrived. */
        private final int mWidth;
        private final int mHeight;

        /** EGL, once the thread has started. */
        private EGL10 mEgl;

        /** The display the context is on. */
        private EGLDisplay mDisplay = EGL10.EGL_NO_DISPLAY;

        /** The GL context, which owns the renderer's textures. */
        private EGLContext mContext = EGL10.EGL_NO_CONTEXT;

        /** The window surface over the SurfaceTexture, which frames are swapped into. */
        private EGLSurface mSurface = EGL10.EGL_NO_SURFACE;

        RenderThread(@NonNull SurfaceTexture surfaceTexture, int width, int height,
                @NonNull GLSurfaceView.Renderer renderer) {
            super("PhotosphereRender");
            mSurfaceTexture = surfaceTexture;
            mWidth = width;
            mHeight = height;
            mRenderer = renderer;
        }

        @Override
        protected void onLooperPrepared() {
            mHandler = new Handler(getLooper());
            if (makeContext()) {
                mRenderer.onSurfaceChanged(null, mWidth, mHeight);
                draw();
            }
        }

        @AnyThread
        void requestRender() {
            Handler handler = mHandler;
            if (handler != null && mFramePending.compareAndSet(false, true)) {
                handler.post(mDraw);
            }
        }

        @AnyThread
        void resize(final int width, final int height) {
            post(new Runnable() {
                @Override
                public void run() {
                    if (mSurface != EGL10.EGL_NO_SURFACE) {
                        mRenderer.onSurfaceChanged(null, width, height);
                        draw();
                    }
                }
            });
        }

        @AnyThread
        void finish() {
            post(new Runnable() {
                @Override
                public void run() {
                    releaseContext();
                    mSurfaceTexture.release();
                    quit();
                }
            });
        }

        /** Runs on the thread; getLooper waits for the looper if the thread is still starting. */
        private void post(@NonNull Runnable runnable) {
            Handler handler = mHandler;
            (handler != null ? handler : new Handler(getLooper())).post(runnable);
        }

        private void draw() {
            mFramePending.set(false);
            if (mSurface == EGL10.EGL_NO_SURFACE) {
                return;
            }
            mRenderer.onDrawFrame(null);
            if (!mEgl.eglSwapBuffers(mDisplay, mSurface)
                    && mEgl.eglGetError() == EGL11.EGL_CONTEXT_LOST) {
                // The textures went with the context; the renderer is told and starts again.
                Log.w(TAG, "The photo sphere's GL context was lost; making another");
                releaseContext();
                if (makeContext()) {
                    mRenderer.onSurfaceChanged(null, mWidth, mHeight);
                    draw();
                }
            }
        }

        /** Makes a GL ES 2.0 context drawing into the surface. False, and says why, if not. */
        private boolean makeContext() {
            mEgl = (EGL10) EGLContext.getEGL();
            mDisplay = mEgl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
            if (mDisplay == EGL10.EGL_NO_DISPLAY || !mEgl.eglInitialize(mDisplay, null)) {
                Log.e(TAG, "Unable to initialize EGL: " + mEgl.eglGetError());
                return false;
            }
            int[] attributes = {
                    EGL10.EGL_RED_SIZE, 8,
                    EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_ALPHA_SIZE, 8,
                    EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                    EGL10.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            if (!mEgl.eglChooseConfig(mDisplay, attributes, configs, 1, count) || count[0] == 0) {
                Log.e(TAG, "No EGL config for the photo sphere: " + mEgl.eglGetError());
                return false;
            }
            mContext = mEgl.eglCreateContext(mDisplay, configs[0], EGL10.EGL_NO_CONTEXT,
                    new int[] {EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE});
            if (mContext == null || mContext == EGL10.EGL_NO_CONTEXT) {
                mContext = EGL10.EGL_NO_CONTEXT;
                Log.e(TAG, "Unable to make a GL context: " + mEgl.eglGetError());
                return false;
            }
            mSurface = mEgl.eglCreateWindowSurface(mDisplay, configs[0], mSurfaceTexture, null);
            if (mSurface == null || mSurface == EGL10.EGL_NO_SURFACE
                    || !mEgl.eglMakeCurrent(mDisplay, mSurface, mSurface, mContext)) {
                Log.e(TAG, "Unable to draw into the photo sphere's surface: "
                        + mEgl.eglGetError());
                releaseContext();
                return false;
            }
            mRenderer.onSurfaceCreated(null, configs[0]);
            return true;
        }

        private void releaseContext() {
            if (mEgl == null || mDisplay == EGL10.EGL_NO_DISPLAY) {
                return;
            }
            mEgl.eglMakeCurrent(mDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE,
                    EGL10.EGL_NO_CONTEXT);
            if (mSurface != null && mSurface != EGL10.EGL_NO_SURFACE) {
                mEgl.eglDestroySurface(mDisplay, mSurface);
            }
            if (mContext != null && mContext != EGL10.EGL_NO_CONTEXT) {
                mEgl.eglDestroyContext(mDisplay, mContext);
            }
            mSurface = EGL10.EGL_NO_SURFACE;
            mContext = EGL10.EGL_NO_CONTEXT;
            mDisplay = EGL10.EGL_NO_DISPLAY;
        }
    }
}
