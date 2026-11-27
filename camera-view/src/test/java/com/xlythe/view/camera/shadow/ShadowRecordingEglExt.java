package com.xlythe.view.camera.shadow;

import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;

import androidx.annotation.NonNull;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;

import java.util.ArrayList;
import java.util.List;

/**
 * Remembers the presentation time each drawn frame was stamped with.
 *
 * <p>The stamp is the only thing an encoder learns about when a frame happened, and getting it on
 * the wrong clock puts the video as far after its audio as the phone has spent asleep. Robolectric
 * drops the call, so without this a test cannot see which time a frame was given.</p>
 *
 * <p>Robolectric's EGL hands every surface the same handle, so the stamps are kept in the order
 * they were made rather than by surface; a render thread draws its outputs in the order they were
 * added, which is what a test reads them back by.</p>
 */
@Implements(EGLExt.class)
public class ShadowRecordingEglExt {
    /** Every stamp, in the order the frames were drawn. */
    private static final List<Long> sPresentationTimes = new ArrayList<>();

    /** Every presentation time stamped so far, oldest first. */
    @NonNull
    public static List<Long> presentationTimes() {
        synchronized (sPresentationTimes) {
            return new ArrayList<>(sPresentationTimes);
        }
    }

    @Resetter
    public static void reset() {
        synchronized (sPresentationTimes) {
            sPresentationTimes.clear();
        }
    }

    @Implementation
    protected static boolean eglPresentationTimeANDROID(
            EGLDisplay display, EGLSurface surface, long timeNanos) {
        synchronized (sPresentationTimes) {
            sPresentationTimes.add(timeNanos);
        }
        return true;
    }
}
