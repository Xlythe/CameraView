package com.xlythe.view.camera;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.view.View;
import android.view.Window;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Puts a window in HDR colour mode for as long as anything in it shows HDR content, and puts back
 * the mode it had once the last of them lets go. Several views in one window share the one
 * mode: each acquires and releases, and only the first acquire and the last release change it.
 *
 * <p>HDR colour mode arrived in Android 8.0; before it, this does nothing. A window whose mode
 * something else changes while it is held gets back the mode it had when it was first acquired.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class HdrColorMode {
    /** What each held window had before, and how many hold it. */
    private static final Map<Window, Hold> HOLDS = new WeakHashMap<>();

    /** One window's hold. */
    private static final class Hold {
        /** The colour mode to put back. */
        final int previous;

        /** How many hold the window in HDR. */
        int count;

        Hold(int previous) {
            this.previous = previous;
        }
    }

    private HdrColorMode() {}

    /** Puts a window in HDR colour mode, until as many releases as acquires. */
    @MainThread
    public static void acquire(@NonNull Window window) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        Hold hold = HOLDS.get(window);
        if (hold == null) {
            hold = new Hold(window.getColorMode());
            HOLDS.put(window, hold);
            window.setColorMode(ActivityInfo.COLOR_MODE_HDR);
        }
        hold.count++;
    }

    /** Lets go of a window acquired before; the last to let go puts its old mode back. */
    @MainThread
    public static void release(@NonNull Window window) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        Hold hold = HOLDS.get(window);
        if (hold == null) {
            return;
        }
        hold.count--;
        if (hold.count <= 0) {
            HOLDS.remove(window);
            window.setColorMode(hold.previous);
        }
    }

    /** How many hold a window in HDR, for tests. */
    @VisibleForTesting
    static int holds(@NonNull Window window) {
        Hold hold = HOLDS.get(window);
        return hold == null ? 0 : hold.count;
    }

    /** The window a view is shown in, or null if its context is not an activity's. */
    @MainThread
    @Nullable
    public static Window windowOf(@NonNull View view) {
        Context context = view.getContext();
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return ((Activity) context).getWindow();
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}
