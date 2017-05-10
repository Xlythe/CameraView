package com.xlythe.view.camera.legacy;

import android.hardware.Camera;
import android.location.Location;
import android.os.AsyncTask;
import android.util.Log;

import com.xlythe.view.camera.Exif;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import static com.xlythe.view.camera.ICameraModule.TAG;

@SuppressWarnings("deprecation")
class LegacyPictureListener implements Camera.PictureCallback {
    // The file we're saving the picture to.
    private final File mFile;

    // The camera's orientation. If it's not 0, we'll have to rotate the image.
    private final int mOrientation;

    // True if the picture is mirrored
    private final boolean mIsReversed;

    // The listener to notify when we're done.
    private final LegacyCameraModule mModule;

    LegacyPictureListener(File file, int orientation, boolean mirrored, LegacyCameraModule module) {
        mFile = file;
        mIsReversed = mirrored;
        mOrientation = orientation;
        mModule = module;
    }

    @Override
    public void onPictureTaken(final byte[] data, final Camera camera) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                FileOutputStream output = null;
                try {
                    output = new FileOutputStream(mFile);
                    output.write(data);

                    Exif exif = new Exif(mFile);
                    exif.attachTimestamp();
                    exif.rotate(mOrientation);
                    if (mIsReversed) {
                        exif.flipHorizontally();
                    }
                    Location location = LegacyCameraModule.getLocation(mModule.getContext());
                    if (location != null) {
                        exif.attachLocation(location);
                    }
                    exif.save();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to write the file", e);
                } finally {
                    if (output != null) {
                        try {
                            output.close();
                        } catch (IOException e) {
                            Log.e(TAG, "Failed to close the output stream", e);
                        }
                    }
                }

                return null;
            }

            @Override
            protected void onPostExecute(Void aVoid) {
                mModule.showImageConfirmation(mFile);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        camera.startPreview();
    }
}
