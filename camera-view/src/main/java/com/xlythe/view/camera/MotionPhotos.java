package com.xlythe.view.camera;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.GuardedBy;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static com.xlythe.view.camera.ICameraModule.TAG;

/**
 * Turns pictures into motion photos, the same way whichever backend took them.
 *
 * <p>A picture's clip is asked for as the shutter is pressed, from frames the backend has been
 * feeding an encoder, and arrives about a second later once the moments after the shutter are in.
 * The picture itself arrives whenever its backend has written it. Whichever is second sets off the
 * write: the clip is muxed into an MP4 and appended to the JPEG, which is described as a motion
 * photo in its XMP, and only then is the picture handed on. A picture whose clip comes to nothing
 * is handed on as the plain still it already is.</p>
 */
final class MotionPhotos {
    /** Where a picture's clip comes from: the encoder the backend is feeding. */
    interface ClipSource {
        /**
         * Marks the shutter now and starts cutting a clip around it.
         *
         * @return the clip being cut, or null when there are no frames to cut one from
         */
        @AnyThread
        @Nullable
        ClipRequest capture(@NonNull ClipListener listener);
    }

    /** A clip being cut for one picture. */
    interface ClipRequest {
        /** Abandons the clip, which then never reaches its listener. */
        @AnyThread
        void cancel();
    }

    /** Hears a clip once it is cut. */
    interface ClipListener {
        /** @param clip the clip, or null when nothing could be cut */
        @MainThread
        void onClip(@Nullable ClipFile clip);
    }

    /** A cut clip, which writes itself out as an MP4. */
    interface ClipFile {
        /** Where in the clip the still was taken, in microseconds from its start. */
        long getPresentationTimestampUs();

        /** Muxes the clip into an MP4 at this path. */
        @WorkerThread
        void writeTo(@NonNull File mp4) throws IOException;
    }

    /** Runs what has to happen on the main thread, where pictures are handed on. */
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    /** Writes clips into their pictures, one at a time, off the main thread. */
    private final Executor mWriter;
    /** Where a clip's MP4 is written before it is appended, which is deleted again after. */
    private final File mScratch;
    /** Guards {@link #mPending}. */
    private final Object mLock = new Object();
    /** Pictures with a clip on its way, in the order they were taken. */
    @GuardedBy("mLock")
    private final List<Picture> mPending = new ArrayList<>();

    MotionPhotos(@NonNull File scratch) {
        this(scratch, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "MotionPhotoWriter");
            thread.setDaemon(true);
            return thread;
        }));
    }

    @VisibleForTesting
    MotionPhotos(@NonNull File scratch, @NonNull Executor writer) {
        mScratch = scratch;
        mWriter = writer;
    }

    /**
     * Starts the clip for a picture about to be taken into a file.
     *
     * @param source where the clip comes from, or null where no encoder is being fed
     * @return whether a clip is coming, in which case the picture is held for it
     */
    @AnyThread
    boolean begin(@NonNull File file, @Nullable ClipSource source) {
        if (source == null) {
            return false;
        }
        Picture picture = new Picture(file);
        // Listed before the clip is asked for, so a clip that arrives first still finds it.
        synchronized (mLock) {
            mPending.add(picture);
        }
        ClipRequest request = source.capture(clip -> onClip(picture, clip));
        synchronized (mLock) {
            if (request == null) {
                mPending.remove(picture);
                return false;
            }
            picture.mRequest = request;
        }
        return true;
    }

    /** Whether any picture is waiting for its clip. */
    @AnyThread
    boolean isPending() {
        synchronized (mLock) {
            return !mPending.isEmpty();
        }
    }

    /**
     * Takes a finished picture, if its clip is still to be written into it.
     *
     * @param deliver hands the picture on, run on the main thread once its clip is in
     * @return whether the picture was taken; if not, the caller hands it on itself
     */
    @AnyThread
    boolean finish(@NonNull File file, @NonNull Runnable deliver) {
        Picture picture;
        synchronized (mLock) {
            picture = waitingForStill(file);
            if (picture == null) {
                return false;
            }
            picture.mDeliver = deliver;
        }
        writeIfReady(picture);
        return true;
    }

    /**
     * Abandons the clip of the oldest picture still being taken, because a picture failed. Pictures
     * finish in the order they are taken, so the oldest is the one that failed.
     */
    @AnyThread
    void fail() {
        Picture failed = null;
        synchronized (mLock) {
            for (Picture picture : mPending) {
                if (picture.mDeliver == null) {
                    failed = picture;
                    break;
                }
            }
            if (failed != null) {
                mPending.remove(failed);
            }
        }
        if (failed != null && failed.mRequest != null) {
            failed.mRequest.cancel();
        }
    }

    @GuardedBy("mLock")
    @Nullable
    private Picture waitingForStill(@NonNull File file) {
        for (Picture picture : mPending) {
            if (picture.mDeliver == null && picture.mFile.equals(file)) {
                return picture;
            }
        }
        return null;
    }

    @MainThread
    private void onClip(@NonNull Picture picture, @Nullable ClipFile clip) {
        synchronized (mLock) {
            picture.mClipDone = true;
            picture.mClip = clip;
        }
        writeIfReady(picture);
    }

    /** Writes the clip into the picture once both are in, then hands the picture on. */
    private void writeIfReady(@NonNull Picture picture) {
        ClipFile clip;
        Runnable deliver;
        synchronized (mLock) {
            if (!picture.mClipDone || picture.mDeliver == null || !mPending.remove(picture)) {
                return;
            }
            clip = picture.mClip;
            deliver = picture.mDeliver;
        }
        if (clip == null) {
            mMainHandler.post(deliver);
            return;
        }
        mWriter.execute(() -> {
            write(picture.mFile, clip, mScratch);
            mMainHandler.post(deliver);
        });
    }

    /**
     * Appends a clip to a picture and describes it as a motion photo. A clip that cannot be written
     * leaves the picture as it was, which is the still it would have been anyway.
     *
     * @return whether the picture is now a motion photo
     */
    @WorkerThread
    @VisibleForTesting
    static boolean write(@NonNull File jpeg, @NonNull ClipFile clip, @NonNull File scratch) {
        File mp4 = null;
        try {
            mp4 = File.createTempFile("motion", ".mp4", scratch);
            clip.writeTo(mp4);
            MotionPhotoWriter.write(jpeg, mp4, clip.getPresentationTimestampUs());
            return true;
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "Keeping the picture without its motion photo clip", error);
            return false;
        } finally {
            if (mp4 != null && mp4.exists() && !mp4.delete()) {
                Log.w(TAG, "Could not delete " + mp4);
            }
        }
    }

    /** One picture with a clip on its way. */
    private static final class Picture {
        /** Where the picture is being written. */
        final File mFile;
        /** The clip being cut, to abandon if the picture fails. */
        @Nullable ClipRequest mRequest;
        /** Whether the clip has arrived, or come to nothing. */
        boolean mClipDone;
        /** The clip, once it has arrived, or null if it came to nothing. */
        @Nullable ClipFile mClip;
        /** Hands the picture on, once the picture has been written. */
        @Nullable Runnable mDeliver;

        Picture(@NonNull File file) {
            mFile = file;
        }
    }
}
