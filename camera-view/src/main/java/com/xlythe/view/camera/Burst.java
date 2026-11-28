package com.xlythe.view.camera;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.xlythe.view.camera.ICameraModule.TAG;

/**
 * A burst in progress, as the backend taking it sees it.
 *
 * <p>The backend asks for a file before each picture and reports how each one turned out. The
 * burst decides when it has enough, and finishes once every picture it handed a file to has been
 * reported. Pictures already asked for when a burst ends still land, so a backend that keeps
 * several in flight never has one thrown away.</p>
 *
 * <p>Everything here happens on the main thread, where every backend already reports.</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
@MainThread
public final class Burst {
    /**
     * How long a burst waits on a picture before it stops waiting. A camera that stops answering
     * would otherwise leave the app waiting for an end that never comes.
     */
    @VisibleForTesting
    static final long STALL_TIMEOUT_MILLIS = 10_000;

    /** One thread for every burst's saves, so pictures land in the order they were taken. */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "CameraView-Burst");
        thread.setDaemon(true);
        return thread;
    });

    /** Hears what a burst produced, on the main thread. */
    public interface Callback {
        /** A picture was written, with its Exif, to a file the burst handed out. */
        void onPictureTaken(@NonNull File file);

        /** The burst is over and every picture it asked for has been reported. */
        void onBurstFinished();
    }

    /** Where every picture of the burst is written. */
    private final File mDirectory;

    /** What each file is named after, before its place in the burst. */
    private final String mName;

    /** The most pictures this burst takes. */
    private final int mMaxCount;

    /** Hears each picture and the end; CameraView, which delivers them to the app. */
    private final Callback mCallback;

    /** Runs the stall timeout. */
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /** Gives up on pictures the camera has stopped answering for. */
    private final Runnable mStallTimeout = this::onStalled;

    /** What the backend undoes once the burst finishes, such as a flash it turned off. */
    private final List<Runnable> mOnFinished = new ArrayList<>();

    /** What runs once the burst is stopped short of its count, or finishes. */
    private final List<Runnable> mOnStopped = new ArrayList<>();

    /** Whether the burst has been stopped short of its count, or has finished. */
    private boolean mStopped;

    /** How many files have been handed out, which also numbers the next one. */
    private int mRequested;

    /** How many of the files handed out have not been reported yet. */
    private int mPending;

    /** Whether no more files will be handed out. */
    private boolean mEnded;

    /** Whether the callback has heard the end. Nothing is reported after it. */
    private boolean mFinished;

    /**
     * @param directory where the pictures go, which has to exist
     * @param name what the files are named after; each adds its place in the burst
     * @param maxCount the most pictures to take, at least one
     */
    public Burst(@NonNull File directory, @NonNull String name, int maxCount,
            @NonNull Callback callback) {
        if (maxCount < 1) {
            throw new IllegalArgumentException("A burst takes at least one picture");
        }
        mDirectory = directory;
        mName = name;
        mMaxCount = maxCount;
        mCallback = callback;
    }

    /**
     * Where the next picture goes, or null once the burst has ended and no more should be taken.
     * Every file handed out has to be reported, through {@link #onPictureTaken} or
     * {@link #onPictureFailed}.
     */
    @Nullable
    public File nextFile() {
        if (mEnded) {
            return null;
        }
        mRequested++;
        mPending++;
        if (mRequested >= mMaxCount) {
            mEnded = true;
        }
        restartStallTimeout();
        return new File(mDirectory, String.format(Locale.US, "%s_%03d.jpg", mName, mRequested));
    }

    /** Reports a picture written to a file from {@link #nextFile}. */
    public void onPictureTaken(@NonNull File file) {
        if (mFinished) {
            // Landed after the burst gave up on it, and after the app was told how many there
            // were, so it is not one of them.
            delete(file);
            return;
        }
        mPending--;
        mCallback.onPictureTaken(file);
        restartStallTimeout();
        finishIfDone();
    }

    /**
     * Reports a picture that could not be taken or saved. The burst ends with it, since whatever
     * stopped one picture stops the next.
     */
    public void onPictureFailed(@NonNull File file) {
        delete(file);
        if (mFinished) {
            return;
        }
        mPending--;
        mEnded = true;
        stop();
        restartStallTimeout();
        finishIfDone();
    }

    /** Hands out no more files. Pictures already asked for still land. */
    public void end() {
        mEnded = true;
        stop();
        finishIfDone();
    }

    /** Whether no more files will be handed out. */
    public boolean isEnded() {
        return mEnded;
    }

    /** Whether every picture has been reported and the burst is over. */
    public boolean isFinished() {
        return mFinished;
    }

    /**
     * Whether the burst was stopped short of its count, by {@link #end}, a picture that failed or
     * a camera that stopped answering, or has finished.
     */
    public boolean isStopped() {
        return mStopped;
    }

    /** How many files have been handed out so far. */
    public int getRequestedCount() {
        return mRequested;
    }

    /**
     * Runs something once the burst is stopped short of its count or finishes, or now if it
     * already has. A burst stopped short still lands the pictures already asked for.
     */
    public void whenStopped(@NonNull Runnable action) {
        if (mStopped) {
            action.run();
        } else {
            mOnStopped.add(action);
        }
    }

    /** Runs something once the burst finishes, or now if it already has. */
    public void whenFinished(@NonNull Runnable action) {
        if (mFinished) {
            action.run();
        } else {
            mOnFinished.add(action);
        }
    }

    /**
     * Where a backend saves the pictures it is handed as bytes. One thread, so they finish in the
     * order they were taken.
     */
    @NonNull
    public Executor getExecutor() {
        return EXECUTOR;
    }

    private void finishIfDone() {
        if (!mEnded || mPending > 0 || mFinished) {
            return;
        }
        mFinished = true;
        mHandler.removeCallbacks(mStallTimeout);
        stop();
        for (Runnable action : mOnFinished) {
            action.run();
        }
        mOnFinished.clear();
        mCallback.onBurstFinished();
    }

    private void stop() {
        if (mStopped) {
            return;
        }
        mStopped = true;
        for (Runnable action : mOnStopped) {
            action.run();
        }
        mOnStopped.clear();
    }

    private void restartStallTimeout() {
        mHandler.removeCallbacks(mStallTimeout);
        if (mPending > 0) {
            mHandler.postDelayed(mStallTimeout, STALL_TIMEOUT_MILLIS);
        }
    }

    private void onStalled() {
        Log.w(TAG, "The camera stopped answering partway through a burst");
        mEnded = true;
        mPending = 0;
        stop();
        finishIfDone();
    }

    private static void delete(@NonNull File file) {
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Failed to clean up " + file);
        }
    }
}
