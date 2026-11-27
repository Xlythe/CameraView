package com.xlythe.view.camera.pano;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Where a sweep keeps the frames it stitches once it is over. A photosphere is dozens of frames,
 * more than fit in memory at once, so they wait in the app's cache as JPEGs and are read back one
 * or two at a time.
 */
@WorkerThread
abstract class KeyframeStore {
    private static final String TAG = "CameraView";

    /** Keeps a frame under a number. The image may be reused once this returns. */
    abstract void put(int index, @NonNull ArgbImage image) throws IOException;

    /** A frame kept earlier, or null if it cannot be read back. */
    @Nullable
    abstract ArgbImage get(int index);

    /** Forgets every frame. */
    abstract void release();

    /** A store in the app's cache directory, for a real sweep. */
    @NonNull
    static KeyframeStore inCache(@NonNull Context context) {
        return new Files(new File(context.getCacheDir(), "panorama-" + System.nanoTime()));
    }

    /** A store in memory, for a sweep of a few small frames. */
    @NonNull
    static KeyframeStore inMemory() {
        return new Memory();
    }

    /** Frames as JPEGs in a directory of their own. */
    private static final class Files extends KeyframeStore {
        /** Near-lossless: the frames are cut and blended again, and the seams should not show. */
        private static final int QUALITY = 95;

        /** The directory, made on the first frame. */
        private final File mDirectory;

        Files(@NonNull File directory) {
            mDirectory = directory;
        }

        @Override
        void put(int index, @NonNull ArgbImage image) throws IOException {
            if (!mDirectory.isDirectory() && !mDirectory.mkdirs()) {
                throw new IOException("Cannot make " + mDirectory);
            }
            Bitmap bitmap = Bitmap.createBitmap(image.pixels, image.width, image.height,
                    Bitmap.Config.ARGB_8888);
            try (OutputStream out = new FileOutputStream(file(index))) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) {
                    throw new IOException("Cannot write frame " + index);
                }
            } finally {
                bitmap.recycle();
            }
        }

        @Nullable
        @Override
        ArgbImage get(int index) {
            Bitmap bitmap = BitmapFactory.decodeFile(file(index).getPath());
            if (bitmap == null) {
                Log.w(TAG, "Cannot read frame " + index + " back");
                return null;
            }
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            bitmap.recycle();
            return new ArgbImage(pixels, width, height);
        }

        @Override
        void release() {
            File[] files = mDirectory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (!file.delete()) {
                        Log.w(TAG, "Cannot delete " + file);
                    }
                }
            }
            if (mDirectory.exists() && !mDirectory.delete()) {
                Log.w(TAG, "Cannot delete " + mDirectory);
            }
        }

        @NonNull
        private File file(int index) {
            return new File(mDirectory, index + ".jpg");
        }
    }

    /** Frames kept as they are. */
    private static final class Memory extends KeyframeStore {
        /** The frames. */
        private final SparseArray<ArgbImage> mFrames = new SparseArray<>();

        @Override
        void put(int index, @NonNull ArgbImage image) {
            mFrames.put(index, new ArgbImage(image.pixels.clone(), image.width, image.height));
        }

        @Nullable
        @Override
        ArgbImage get(int index) {
            return mFrames.get(index);
        }

        @Override
        void release() {
            mFrames.clear();
        }
    }
}
