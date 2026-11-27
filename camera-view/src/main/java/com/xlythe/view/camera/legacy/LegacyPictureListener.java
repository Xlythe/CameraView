package com.xlythe.view.camera.legacy;

import android.hardware.Camera;
import android.location.Location;
import android.os.AsyncTask;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.xlythe.view.camera.Exif;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.Executor;

import static com.xlythe.view.camera.ICameraModule.TAG;

@SuppressWarnings("deprecation")
class LegacyPictureListener implements Camera.PictureCallback {
    /** Hears how a picture turned out, on the main thread. */
    interface Callback {
        /**
         * The camera has the picture and is previewing again, or failed to, which is when it can
         * be asked for another.
         */
        @MainThread
        void onPreviewRestarted(boolean restarted);

        /** The picture was written, with its Exif. */
        @MainThread
        void onPictureSaved(@NonNull File file);

        /** The picture could not be written. */
        @MainThread
        void onPictureFailed(@NonNull File file);
    }

    // The file we're saving the picture to.
    private final File mFile;

    // The camera's orientation. If it's not 0, we'll have to rotate the image.
    private final int mOrientation;

    // True if the picture is mirrored
    private final boolean mIsReversed;

    // Where the location to tag the picture with comes from.
    private final LegacyCameraModule mModule;

    // Where the picture is written. A burst writes its pictures one at a time, in order.
    private final Executor mExecutor;

    // The listener to notify when we're done.
    private final Callback mCallback;

    /** Saves a picture from takePicture, which the module shows for confirmation or fails. */
    LegacyPictureListener(File file, int orientation, boolean mirrored, LegacyCameraModule module) {
        this(file, orientation, mirrored, module, AsyncTask.THREAD_POOL_EXECUTOR, new Callback() {
            @Override
            public void onPreviewRestarted(boolean restarted) {}

            @Override
            public void onPictureSaved(@NonNull File saved) {
                module.showImageConfirmation(saved);
            }

            @Override
            public void onPictureFailed(@NonNull File failed) {
                module.onImageFailed();
            }
        });
    }

    LegacyPictureListener(File file, int orientation, boolean mirrored, LegacyCameraModule module,
            Executor executor, Callback callback) {
        mFile = file;
        mIsReversed = mirrored;
        mOrientation = orientation;
        mModule = module;
        mExecutor = executor;
        mCallback = callback;
    }

    @Override
    public void onPictureTaken(final byte[] data, final Camera camera) {
        new AsyncTask<Void, Void, Boolean>() {
            @WorkerThread
            @Override
            protected Boolean doInBackground(Void... params) {
                try (FileOutputStream output = new FileOutputStream(mFile)) {
                    output.write(data);
                } catch (IOException e) {
                    Log.e(TAG, "Failed to write the file", e);
                    return false;
                }
                try {
                    Exif exif = new Exif(mFile);
                    exif.attachTimestamp();
                    exif.rotate(mOrientation);
                    if (mIsReversed) {
                        exif.flipHorizontally();
                    }
                    Location location = mModule.getLocation();
                    if (location != null) {
                        exif.attachLocation(location);
                    }
                    exif.save();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to write the file", e);
                    return false;
                }
                return true;
            }

            @MainThread
            @Override
            protected void onPostExecute(Boolean saved) {
                if (Boolean.TRUE.equals(saved)) {
                    mCallback.onPictureSaved(mFile);
                } else {
                    mCallback.onPictureFailed(mFile);
                }
            }
        }.executeOnExecutor(mExecutor);
        boolean restarted;
        try {
            camera.startPreview();
            restarted = true;
        } catch (RuntimeException e) {
            // The camera was released while the picture was on its way back.
            Log.w(TAG, "Failed to restart the preview", e);
            restarted = false;
        }
        mCallback.onPreviewRestarted(restarted);
    }
}
