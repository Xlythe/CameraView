package com.xlythe.view.camera.legacy;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.hardware.Camera;
import android.os.AsyncTask;

import com.xlythe.view.camera.CameraView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

@SuppressWarnings("deprecation")
class LegacyPictureListener implements Camera.PictureCallback {
    // The file we're saving the picture to.
    private final File mFile;

    // The camera's orientation. If it's not 0, we'll have to rotate the image.
    private final int mOrientation;

    // The listener to notify when we're done.
    private final LegacyCameraModule mModule;

    private static final double MAX_UPPER = 2560.0;
    private static final double MAX_LOWER = 1440.0;

    LegacyPictureListener(File file, int orientation, LegacyCameraModule module) {
        mFile = file;
        mOrientation = orientation;
        mModule = module;
    }

    @Override
    public void onPictureTaken(final byte[] data, final Camera camera) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                byte[] cleanedUp = manuallyRotateImage(data);

                try {
                    FileOutputStream fos = new FileOutputStream(mFile);
                    fos.write(cleanedUp);
                    fos.close();
                } catch (IOException e) {
                    e.printStackTrace();
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

    private byte[] manuallyRotateImage(byte[] data) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);

        Matrix matrix = new Matrix();
        matrix.postRotate(mOrientation);

        int max = Math.max(bitmap.getHeight(), bitmap.getWidth());
        int height;
        int width;
        double scale;
        if (max > MAX_UPPER) {
            scale = MAX_UPPER / max;
            width = (int) (bitmap.getWidth() * scale);
            height = (int) (bitmap.getHeight() * scale);
            bitmap = Bitmap.createScaledBitmap(bitmap, width, height, false);
        }
        int min = Math.min(bitmap.getHeight(), bitmap.getWidth());
        if (min > MAX_LOWER) {
            scale = MAX_LOWER / min;
            width = (int) (bitmap.getWidth() * scale);
            height = (int) (bitmap.getHeight() * scale);
            bitmap = Bitmap.createScaledBitmap(bitmap, width, height, false);
        }

        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);

        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream);

        return stream.toByteArray();
    }
}
