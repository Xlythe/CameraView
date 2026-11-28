package com.xlythe.view.camera;

import android.os.Handler;
import android.os.SystemClock;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;

/**
 * Tries a busy camera again, a little later each time, until it opens or a short window runs out.
 *
 * <p>A camera is busy for a moment whenever it changes hands: another app is letting it go, or
 * the camera this app had open is still closing. Turned down then, an open succeeds a few hundred
 * milliseconds later. A camera still busy after {@link #WINDOW_MILLIS} is held by someone who is
 * keeping it, and the open fails as it always did, rather than leaving the view opening with no
 * end.</p>
 *
 * <p>Every try is posted to the handler it is given, so nothing waits on the thread between
 * them.</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
@MainThread
public final class BusyCameraRetry {
    /**
     * How long a camera may stay busy, from the first open it turned down, before the open fails.
     *
     * <p>A camera changing hands is free inside it: the API 37 emulator's camera service takes
     * between 0.12 and 1.44 seconds to finish closing one, across a run of the device suites.
     * Longer than this, the camera is being kept, and an app showing a disabled shutter in the
     * meantime should be told rather than left waiting.</p>
     */
    public static final long WINDOW_MILLIS = 2_000;

    /** The wait before the first try again, which doubles for each one after it. */
    static final long FIRST_DELAY_MILLIS = 100;

    /**
     * The longest wait between two tries, so that a camera let go late in the window is not left
     * unasked for long.
     */
    static final long MAX_DELAY_MILLIS = 500;

    /** Where the tries are posted. */
    @NonNull private final Handler mHandler;

    /** When the first open of this run was turned down, or -1 when no run is under way. */
    private long mFirstRefusalAt = -1;

    /** How long to wait before the next try. */
    private long mNextDelay = FIRST_DELAY_MILLIS;

    /** The try waiting to run, or null when none is. */
    @Nullable private Runnable mPending;

    public BusyCameraRetry(@NonNull Handler handler) {
        mHandler = handler;
    }

    /**
     * Posts another try at an open the camera just turned down as busy.
     *
     * @return true if the try is posted; false if the window has run out, in which case the
     *     refusal is the answer and should be reported
     */
    public boolean retry(@NonNull Runnable open) {
        long now = SystemClock.uptimeMillis();
        if (mFirstRefusalAt < 0) {
            mFirstRefusalAt = now;
        }
        long remaining = mFirstRefusalAt + WINDOW_MILLIS - now;
        if (remaining <= 0) {
            cancel();
            return false;
        }
        long delay = Math.min(mNextDelay, remaining);
        mNextDelay = Math.min(mNextDelay * 2, MAX_DELAY_MILLIS);
        removePending();
        Runnable pending = new Runnable() {
            @Override
            public void run() {
                if (mPending == this) {
                    mPending = null;
                    open.run();
                }
            }
        };
        mPending = pending;
        mHandler.postDelayed(pending, delay);
        return true;
    }

    /** Whether a try is posted and has not run yet. */
    public boolean isPending() {
        return mPending != null;
    }

    /**
     * Drops the try waiting to run, if any, and starts the window afresh for the next refusal.
     * Called when the camera opens, closes, or is swapped for another.
     */
    public void cancel() {
        removePending();
        mFirstRefusalAt = -1;
        mNextDelay = FIRST_DELAY_MILLIS;
    }

    private void removePending() {
        if (mPending != null) {
            mHandler.removeCallbacks(mPending);
            mPending = null;
        }
    }
}
