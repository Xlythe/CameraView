package com.xlythe.view.camera.gl;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;

/**
 * One EGL display, config and context, and the surfaces drawn through them.
 *
 * <p>Thin on purpose. Every method here is a call into EGL and a check that it worked, because a
 * GPU is the one thing a unit test cannot have; the decisions live in {@link GlMath} instead.</p>
 *
 * <p>Every method must be called on the thread that created the instance. An EGL context belongs
 * to a thread, and the only safe way to share camera frames between threads is not to.</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class EglCore {
    /** The GLES version the external-texture shader needs, and nothing newer. */
    private static final int GLES_VERSION = 2;

    /** The side of the pbuffer that exists only to have a surface to make current against. */
    private static final int OFFSCREEN_SIZE = 1;

    private final EGLDisplay mDisplay;
    private final EGLConfig mConfig;
    private final EGLContext mContext;

    /**
     * Somewhere to make the context current when no output surface is in play, which is where the
     * texture and the program are created and where a frame is consumed from the camera. GLES
     * refuses every call without a current context, and a context cannot be made current without
     * a surface, so there has to be one of these even though nothing is ever drawn into it.
     */
    private final EGLSurface mOffscreenSurface;

    /**
     * @throws IllegalStateException if EGL will not give us a display, a config or a context,
     *     which happens on devices whose driver does not offer a recordable external-texture
     *     config and in processes with no GPU access at all
     */
    public EglCore() {
        mDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (mDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new IllegalStateException("EGL has no default display");
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(mDisplay, version, 0, version, 1)) {
            throw new IllegalStateException("EGL would not initialize: " + lastError());
        }
        mConfig = chooseConfig(mDisplay);
        int[] contextAttributes = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, GLES_VERSION,
                EGL14.EGL_NONE,
        };
        mContext = EGL14.eglCreateContext(
                mDisplay, mConfig, EGL14.EGL_NO_CONTEXT, contextAttributes, 0);
        if (mContext == null || mContext == EGL14.EGL_NO_CONTEXT) {
            throw new IllegalStateException("EGL would not create a context: " + lastError());
        }
        int[] offscreenAttributes = {
                EGL14.EGL_WIDTH, OFFSCREEN_SIZE,
                EGL14.EGL_HEIGHT, OFFSCREEN_SIZE,
                EGL14.EGL_NONE,
        };
        mOffscreenSurface = EGL14.eglCreatePbufferSurface(
                mDisplay, mConfig, offscreenAttributes, 0);
        if (mOffscreenSurface == null || mOffscreenSurface == EGL14.EGL_NO_SURFACE) {
            throw new IllegalStateException("EGL would not create a pbuffer: " + lastError());
        }
    }

    /**
     * A config that can draw to a window and be recorded.
     *
     * <p>EGL_RECORDABLE_ANDROID is what lets the same context feed a MediaCodec surface as well as
     * the screen. Asking for it up front is cheaper than discovering at record time that the
     * context cannot write to an encoder, so a device that cannot offer one is treated as a device
     * with no GL rather than one that works until someone presses record.</p>
     */
    @NonNull
    private static EGLConfig chooseConfig(@NonNull EGLDisplay display) {
        int[] attributes = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGLExt.EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE,
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] found = new int[1];
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, found, 0)
                || found[0] < 1 || configs[0] == null) {
            throw new IllegalStateException("No recordable EGL config on this device");
        }
        return configs[0];
    }

    /**
     * Wraps a surface the camera or the encoder handed us so it can be drawn into.
     *
     * @throws IllegalStateException if EGL refuses the surface, which it does for one that has
     *     already been released
     */
    @NonNull
    public EGLSurface createWindowSurface(@NonNull Surface surface) {
        int[] attributes = {EGL14.EGL_NONE};
        EGLSurface eglSurface =
                EGL14.eglCreateWindowSurface(mDisplay, mConfig, surface, attributes, 0);
        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
            throw new IllegalStateException("EGL would not wrap the surface: " + lastError());
        }
        return eglSurface;
    }

    /** Makes the context current against a surface, which is what every GLES call needs. */
    public void makeCurrent(@NonNull EGLSurface surface) {
        if (!EGL14.eglMakeCurrent(mDisplay, surface, surface, mContext)) {
            throw new IllegalStateException("EGL would not make the surface current: " + lastError());
        }
    }

    /** Makes the context current with nothing to draw into, for work that only touches textures. */
    public void makeCurrent() {
        makeCurrent(mOffscreenSurface);
    }

    /**
     * Stamps the frame with the time the camera reported it.
     *
     * <p>Only an encoder reads this, and it is why a recording made through GL has the same
     * timing as one made from the camera directly: without it every frame carries the time it was
     * drawn, and the recording runs at whatever rate the renderer managed rather than the rate the
     * camera captured.</p>
     */
    public void setPresentationTime(@NonNull EGLSurface surface, long timestampNanos) {
        EGLExt.eglPresentationTimeANDROID(mDisplay, surface, timestampNanos);
    }

    /** Publishes what has been drawn. False when the surface has gone, which is not an error. */
    public boolean swapBuffers(@NonNull EGLSurface surface) {
        return EGL14.eglSwapBuffers(mDisplay, surface);
    }

    public void releaseSurface(@Nullable EGLSurface surface) {
        if (surface != null && surface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(mDisplay, surface);
        }
    }

    /** Gives the display, the context and the offscreen surface back. */
    public void release() {
        EGL14.eglMakeCurrent(mDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT);
        releaseSurface(mOffscreenSurface);
        EGL14.eglDestroyContext(mDisplay, mContext);
        // Releases the thread's EGL bookkeeping as well. Without it a renderer started and stopped
        // repeatedly - which is what a rotation does - leaks a little each time.
        EGL14.eglReleaseThread();
        EGL14.eglTerminate(mDisplay);
    }

    @NonNull
    private static String lastError() {
        return "EGL error 0x" + Integer.toHexString(EGL14.eglGetError());
    }
}
