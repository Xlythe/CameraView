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

import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.xlythe.view.camera.ICameraModule.TAG;

/**
 * Moves a capture where the gallery looks: through MediaStore from API 29, and into the public
 * directory before that. {@link CameraView#takePicture()} and {@link CameraView#startRecording()}
 * file every capture through it; a capture given a File stays where the caller put it.
 *
 * <p>A capture can be more than one file: {@link CameraView.PictureFormat#RAW_JPEG} writes a JPEG
 * and a DNG. They are filed together or not at all, because half a picture is not what was asked
 * for.</p>
 */
final class MediaStoreSaver {
    /** The album captures are filed under, inside Pictures or Movies. */
    private static final String ALBUM = "CameraView";

    /** What a DNG is filed as. MediaStore checks it against the display name's extension. */
    @VisibleForTesting
    static final String MIME_TYPE_DNG = "image/x-adobe-dng";

    /** What every other picture is filed as. */
    @VisibleForTesting
    static final String MIME_TYPE_JPEG = "image/jpeg";

    /** What every video is filed as. */
    private static final String MIME_TYPE_MP4 = "video/mp4";

    /** One daemon thread for every save, since two copies at once only contend for the disc. */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MediaStoreSaver");
        thread.setDaemon(true);
        return thread;
    });

    /** Hears where a capture landed, on the main thread. */
    interface Callback {
        /**
         * Where each file was published, in the order they were given, or null if any of them
         * could not be, in which case none of them was.
         */
        @MainThread
        void onSaved(@Nullable List<Uri> uris);
    }

    private MediaStoreSaver() {}

    /**
     * Publishes the files of one capture, says where they went, and deletes the cache files they
     * came from. The cache files go even when publishing fails, since nothing else knows they are
     * there.
     */
    @AnyThread
    static void save(@NonNull Context context, @NonNull List<File> files, boolean isVideo,
            @NonNull Callback callback) {
        Context applicationContext = context.getApplicationContext();
        Handler mainHandler = new Handler(Looper.getMainLooper());
        List<File> captures = new ArrayList<>(files);
        EXECUTOR.execute(() -> {
            List<Uri> uris = Build.VERSION.SDK_INT >= 29
                    ? insert(applicationContext.getContentResolver(), captures, isVideo)
                    : copyToPublicDirectory(applicationContext, captures, isVideo);
            for (File file : captures) {
                if (file.exists() && !file.delete()) {
                    Log.w(TAG, "Could not delete " + file);
                }
            }
            mainHandler.post(() -> callback.onSaved(uris));
        });
    }

    /** The type a file is filed as, which follows its extension so a DNG is never a JPEG. */
    @VisibleForTesting
    @NonNull
    static String mimeType(@NonNull File file, boolean isVideo) {
        if (isVideo) {
            return MIME_TYPE_MP4;
        }
        return file.getName().toLowerCase(Locale.US).endsWith(".dng")
                ? MIME_TYPE_DNG : MIME_TYPE_JPEG;
    }

    /**
     * Below API 29: copy into the public directory, which needs WRITE_EXTERNAL_STORAGE. Nothing is
     * announced until every copy is made, so a capture that fails halfway is never scanned.
     */
    @Nullable
    @WorkerThread
    @SuppressWarnings("deprecation")
    private static List<Uri> copyToPublicDirectory(
            Context context, List<File> files, boolean isVideo) {
        File directory = Environment.getExternalStoragePublicDirectory(
                isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES);
        if (!directory.exists() && !directory.mkdirs()) {
            Log.e(TAG, "Could not create " + directory);
            return null;
        }

        List<File> copies = new ArrayList<>(files.size());
        for (File file : files) {
            File destination = new File(directory, file.getName());
            if (!copy(file, destination)) {
                for (File copied : copies) {
                    if (!copied.delete()) {
                        Log.w(TAG, "Could not remove " + copied + " after a failed save");
                    }
                }
                return null;
            }
            copies.add(destination);
        }

        List<Uri> uris = new ArrayList<>(copies.size());
        for (File copied : copies) {
            announce(context, copied, isVideo);
            uris.add(Uri.fromFile(copied));
        }
        return Collections.unmodifiableList(uris);
    }

    /** Copies one file over whatever is at its destination, and says whether it got there. */
    @WorkerThread
    private static boolean copy(File file, File destination) {
        if (destination.exists() && !destination.delete()) {
            Log.e(TAG, "Could not replace " + destination);
            return false;
        }
        try (InputStream input = new FileInputStream(file);
             OutputStream output = new FileOutputStream(destination)) {
            copy(input, output);
            return true;
        } catch (IOException error) {
            Log.e(TAG, "Failed to copy " + file + " to " + destination, error);
            if (destination.exists() && !destination.delete()) {
                Log.w(TAG, "Could not remove the partial copy " + destination);
            }
            return false;
        }
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
                new String[] {mimeType(file, isVideo)}, null);
    }

    /**
     * From API 29: hand the bytes to MediaStore, which needs no permission for the app's own
     * media. Every row stays pending until every copy finishes, so nothing reads half a file and
     * the gallery never shows half a capture.
     */
    @Nullable
    @WorkerThread
    @RequiresApi(29)
    private static List<Uri> insert(ContentResolver resolver, List<File> files, boolean isVideo) {
        Uri collection = isVideo
                ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String directory = isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES;

        List<Uri> uris = new ArrayList<>(files.size());
        try {
            for (File file : files) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, file.getName());
                values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType(file, isVideo));
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, directory + "/" + ALBUM);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);

                Uri uri = resolver.insert(collection, values);
                if (uri == null) {
                    throw new IOException("MediaStore refused to create a row for " + file);
                }
                uris.add(uri);
                try (InputStream input = new FileInputStream(file);
                     OutputStream output = resolver.openOutputStream(uri)) {
                    if (output == null) {
                        throw new IOException("MediaStore returned no stream for " + uri);
                    }
                    copy(input, output);
                }
            }
            for (Uri uri : uris) {
                ContentValues published = new ContentValues();
                published.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(uri, published, null, null);
            }
            return Collections.unmodifiableList(uris);
        } catch (IOException | RuntimeException error) {
            Log.e(TAG, "Failed to save " + files + " to MediaStore", error);
            // A pending row left behind would stay invisible forever, and a published one would be
            // half of a capture the caller is told failed.
            for (Uri uri : uris) {
                try {
                    resolver.delete(uri, null, null);
                } catch (RuntimeException cleanupError) {
                    Log.w(TAG, "Failed to remove the incomplete MediaStore row " + uri,
                            cleanupError);
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
