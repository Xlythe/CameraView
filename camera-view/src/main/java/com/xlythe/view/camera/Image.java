package com.xlythe.view.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.AsyncTask;
import android.widget.ImageView;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class Image {
    private static Image sImage;

    private final Context mContext;
    private final Map<ImageView, Task> mImageTasks = new HashMap<>();

    public static Image with(Context context) {
        if (sImage == null) {
            sImage = new Image(context);
        }
        return sImage;
    }

    private Image(Context context) {
        mContext = context.getApplicationContext();
    }

    public Loader load(File file) {
        return new Loader(file);
    }

    public static void clear(ImageView imageView) {
        if (sImage != null) {
            Task task = sImage.mImageTasks.remove(imageView);
            if (task != null) {
                task.cancel(true);
            }
        }
    }

    public class Loader {
        private final File mFile;

        private Loader(File file) {
            mFile = file;
        }

        public void into(ImageView imageView) {
            Task task = new Task(imageView);
            mImageTasks.put(imageView, task);
            task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, mFile);
        }
    }

    private static class Task extends AsyncTask<File, Void, Bitmap> {
        private final ImageView mImageView;

        Task(ImageView imageView) {
            mImageView = imageView;
        }

        @Override
        protected void onPreExecute() {
            mImageView.setImageDrawable(null);
        }

        @Override
        protected Bitmap doInBackground(File... params) {
            try {
                File file = params[0];
                Exif exif = new Exif(file);

                Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());

                Matrix matrix = new Matrix();
                matrix.postRotate(exif.getRotation());
                if (exif.isFlippedHorizontally()) {
                    matrix.postScale(-1, 1);
                }
                if (exif.isFlippedVertically()) {
                    matrix.postScale(1, -1);
                }

                return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            } catch (IOException e) {
                cancel(true);
                return null;
            }
        }

        @Override
        protected void onPostExecute(Bitmap result) {
            mImageView.setImageBitmap(result);
        }
    }
}
