package com.xlythe.view.camera;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.WorkerThread;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.xlythe.view.camera.ICameraModule.TAG;

/**
 * Moves a capture where the gallery looks: through MediaStore from API 29, and into the public
 * directory before that. {@link CameraView#takePicture()} and {@link CameraView#startRecording()}
 * file every capture through it; a capture given a File stays where the caller put it.
 */
final class MediaStoreSaver {
    /** The album captures are filed under, inside Pictures or Movies. */
    private static final String ALBUM = "CameraView";

    /** One daemon thread for every save, since two copies at once only contend for the disc. */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MediaStoreSaver");
        thread.setDaemon(true);
        return thread;
    });

    /** Hears where a capture landed, on the main thread. */
    interface Callback {
        /** The published capture, or null if it could not be saved. */
        void onSaved(@Nullable Uri uri);
    }

    private MediaStoreSaver() {}

    /**
     * Publishes a capture, says where it went, and deletes the cache file it came from. The cache
     * file goes even when publishing fails, since nothing else knows it is there.
     */
    static void save(@NonNull Context context, @NonNull File file, boolean isVideo,
            @NonNull Callback callback) {
        Context applicationContext = context.getApplicationContext();
        Handler mainHandler = new Handler(Looper.getMainLooper());
        EXECUTOR.execute(() -> {
            Uri uri = Build.VERSION.SDK_INT >= 29
                    ? insert(applicationContext.getContentResolver(), file, isVideo)
                    : copyToPublicDirectory(applicationContext, file, isVideo);
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Could not delete " + file);
            }
            mainHandler.post(() -> callback.onSaved(uri));
        });
    }

    /** Below API 29: copy into the public directory, which needs WRITE_EXTERNAL_STORAGE. */
    @Nullable
    @WorkerThread
    @SuppressWarnings("deprecation")
    private static Uri copyToPublicDirectory(Context context, File file, boolean isVideo) {
        File directory = Environment.getExternalStoragePublicDirectory(
                isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES);
        if (!directory.exists() && !directory.mkdirs()) {
            Log.e(TAG, "Could not create " + directory);
            return null;
        }

        File destination = new File(directory, file.getName());
        if (destination.exists() && !destination.delete()) {
            Log.e(TAG, "Could not replace " + destination);
            return null;
        }
        try (InputStream input = new FileInputStream(file);
             OutputStream output = new FileOutputStream(destination)) {
            copy(input, output);
        } catch (IOException error) {
            Log.e(TAG, "Failed to copy " + file + " to " + destination, error);
            return null;
        }

        announce(context, destination, isVideo);
        return Uri.fromFile(destination);
    }

    /**
     * Tells the system about a file written directly to public storage. The new-picture broadcasts
     * are not delivered from API 24, so the media scanner is asked there instead.
     */
    @WorkerThread
    @SuppressWarnings("deprecation")
    private static void announce(Context context, File file, boolean isVideo) {
        if (Build.VERSION.SDK_INT < 24) {
            Intent intent = new Intent(isVideo
                    ? android.hardware.Camera.ACTION_NEW_VIDEO
                    : android.hardware.Camera.ACTION_NEW_PICTURE);
            intent.setData(Uri.fromFile(file));
            context.sendBroadcast(intent);
            return;
        }
        MediaScannerConnection.scanFile(context, new String[] {file.getAbsolutePath()},
                new String[] {isVideo ? "video/mp4" : "image/jpeg"}, null);
    }

    /**
     * From API 29: hand the bytes to MediaStore, which needs no permission for the app's own
     * media. The row stays pending until the copy finishes, so nothing reads half a file.
     */
    @Nullable
    @WorkerThread
    @RequiresApi(29)
    private static Uri insert(ContentResolver resolver, File file, boolean isVideo) {
        Uri collection = isVideo
                ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, file.getName());
        values.put(MediaStore.MediaColumns.MIME_TYPE, isVideo ? "video/mp4" : "image/jpeg");
        String directory = isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES;
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, directory + "/" + ALBUM);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri = null;
        try {
            uri = resolver.insert(collection, values);
            if (uri == null) {
                Log.e(TAG, "MediaStore refused to create a row for " + file);
                return null;
            }
            try (InputStream input = new FileInputStream(file);
                 OutputStream output = resolver.openOutputStream(uri)) {
                if (output == null) {
                    throw new IOException("MediaStore returned no stream for " + uri);
                }
                copy(input, output);
            }
            ContentValues published = new ContentValues();
            published.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, published, null, null);
            return uri;
        } catch (IOException | RuntimeException error) {
            Log.e(TAG, "Failed to save " + file + " to MediaStore", error);
            if (uri != null) {
                // A pending row left behind would stay invisible forever.
                try {
                    resolver.delete(uri, null, null);
                } catch (RuntimeException cleanupError) {
                    Log.w(TAG, "Failed to remove the incomplete MediaStore row", cleanupError);
                }
            }
            return null;
        }
    }

    @WorkerThread
    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }
}
