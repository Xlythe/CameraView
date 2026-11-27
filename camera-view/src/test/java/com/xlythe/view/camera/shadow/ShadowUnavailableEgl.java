package com.xlythe.view.camera.shadow;

import android.opengl.EGL14;
import android.opengl.EGLDisplay;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * An EGL with no display, which is what a device whose driver will not give us a context looks
 * like from the library's side.
 *
 * <p>Robolectric's own EGL hands out a display, a context and a surface that draw nothing, so
 * under it the GL pipeline starts as it would on a device. A test that wants the path taken when
 * GL will not run declares this instead.</p>
 */
@Implements(EGL14.class)
public class ShadowUnavailableEgl {
    @Implementation
    protected static EGLDisplay eglGetDisplay(int displayId) {
        return EGL14.EGL_NO_DISPLAY;
    }
}
