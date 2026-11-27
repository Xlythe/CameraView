package com.xlythe.view.camera;

import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import androidx.annotation.AnyThread;
import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;

import static com.xlythe.view.camera.ICameraModule.TAG;

/**
 * One shutter press, reported once however many files it writes.
 *
 * <p>{@link CameraView.PictureFormat#RAW_JPEG} writes a JPEG and a DNG from one press, on writers
 * that finish in no particular order. The caller hears once, after the last of them, so every file
 * is on disk by the time it does. A press that loses either file is a failure and leaves neither
 * behind, because half a picture is not what was asked for.</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class PendingPicture {
    /** Hears how the press turned out, once, on the thread that reported its last file. */
    public interface Callback {
        void onSaved(@NonNull File file);

        void onFailed();
    }

    /** The file the caller named, and the one it is told about. */
    @NonNull
    private final File mReported;

    /** Every file the press writes, deleted together if any of them fails. */
    @NonNull
    private final List<File> mFiles;

    /** The files still being written. The press is reported when this empties. */
    @GuardedBy("this")
    @NonNull
    private final Set<File> mRemaining;

    /** Told once, when {@link #mRemaining} empties. */
    @NonNull
    private final Callback mCallback;

    /** Whether any file failed, which fails the press however the others went. */
    @GuardedBy("this")
    private boolean mFailed;

    /** A press that writes one file, the one the caller named. */
    public PendingPicture(@NonNull File file, @NonNull Callback callback) {
        this(file, Arrays.asList(file), callback);
    }

    /**
     * @param reported the file the caller named
     * @param files every file the press writes, including {@code reported}
     */
    public PendingPicture(
            @NonNull File reported, @NonNull List<File> files, @NonNull Callback callback) {
        mReported = reported;
        mFiles = new ArrayList<>(files);
        mRemaining = new LinkedHashSet<>(files);
        mCallback = callback;
    }

    /**
     * Records how one file went. A file reported twice, or one this press does not write, is
     * ignored, so a writer and a teardown racing to report the same file cannot report the press
     * twice.
     */
    @AnyThread
    public void onWritten(@NonNull File file, boolean written) {
        boolean saved;
        synchronized (this) {
            if (!mRemaining.remove(file)) {
                return;
            }
            if (!written) {
                mFailed = true;
            }
            if (!mRemaining.isEmpty()) {
                return;
            }
            saved = !mFailed;
        }
        if (saved) {
            mCallback.onSaved(mReported);
            return;
        }
        for (File each : mFiles) {
            if (each.exists() && !each.delete()) {
                Log.w(TAG, "Failed to clean up " + each + " after a failed capture");
            }
        }
        mCallback.onFailed();
    }

    /** Fails every file still being written, for a capture the camera gave up on. */
    @AnyThread
    public void fail() {
        List<File> remaining;
        synchronized (this) {
            remaining = new ArrayList<>(mRemaining);
        }
        for (File file : remaining) {
            onWritten(file, false);
        }
    }

    /** Whether the press has been reported. */
    @AnyThread
    public synchronized boolean isReported() {
        return mRemaining.isEmpty();
    }
}
