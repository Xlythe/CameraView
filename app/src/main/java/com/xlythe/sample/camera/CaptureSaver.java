package com.xlythe.sample.camera;

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

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Moves a capture out of the cache directory and into the device's shared media collections.
 *
 * <p>CameraView writes to whatever File it is given, and CameraFragment gives it a cache file,
 * which the system may evict. Putting it somewhere permanent is the app's job.</p>
 *
 * <p>Which route it takes depends on the release, because the right answer changed twice:</p>
 *
 * <ul>
 *   <li>API 29 and up: a MediaStore insert. Scoped storage means an app cannot write to
 *       {@code Environment.getExternalStoragePublicDirectory} at all, and
 *       WRITE_EXTERNAL_STORAGE stopped granting it, so this is the only route that works. It also
 *       needs no permission for media the app created, and makes the capture visible without a
 *       broadcast.</li>
 *   <li>Below API 29: a direct copy into the public directory, which does work there with
 *       WRITE_EXTERNAL_STORAGE. Below API 24 the result is announced with
 *       {@code ACTION_NEW_PICTURE} or {@code ACTION_NEW_VIDEO}, and from 24 to 28 those
 *       broadcasts are no longer delivered, so the media scanner is asked to index it instead.</li>
 * </ul>
 *
 * <p>The sample keeps both paths so it stays a working reference for a consumer whose own minSdk
 * is below the sample's.</p>
 */
final class CaptureSaver {
    private static final String TAG = CaptureSaver.class.getSimpleName();

    /** The album captures are filed under, inside Pictures or Movies. */
    private static final String ALBUM = "CameraView";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "CaptureSaver");
        thread.setDaemon(true);
        return thread;
    });

    interface Callback {
        /** Called on the main thread with where the capture landed, or null if it could not be saved. */
        void onSaved(@Nullable Uri uri);
    }

    private CaptureSaver() {}

    static void save(Context context, File file, boolean isVideo, Callback callback) {
        Context applicationContext = context.getApplicationContext();
        Handler mainHandler = new Handler(Looper.getMainLooper());
        EXECUTOR.execute(() -> {
            Uri uri = Build.VERSION.SDK_INT >= 29
                    ? insert(applicationContext.getContentResolver(), file, isVideo)
                    : copyToPublicDirectory(applicationContext, file, isVideo);
            if (uri != null && !file.delete()) {
                Log.w(TAG, "Saved " + uri + " but could not remove the cached original");
            }
            mainHandler.post(() -> callback.onSaved(uri));
        });
    }

    /**
     * The pre-scoped-storage route: copy into the public directory and announce it.
     *
     * <p>Kept for consumers building against a minSdk below the sample's own, where this is the
     * only route that exists.</p>
     */
    @Nullable
    @SuppressWarnings("deprecation")
    private static Uri copyToPublicDirectory(Context context, File file, boolean isVideo) {
        File directory = Environment.getExternalStoragePublicDirectory(
                isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES);
        if (!directory.exists() && !directory.mkdirs()) {
            Log.e(TAG, "Could not create " + directory);
            return null;
        }

        File destination = new File(directory, displayName(file));
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
     * Tells the system about a file written directly to public storage.
     *
     * <p>ACTION_NEW_PICTURE and ACTION_NEW_VIDEO stopped being delivered to apps targeting API
     * 24, so above that the media scanner is asked to index the file instead. Neither is needed
     * on API 29 and up, where the MediaStore insert has already done it.</p>
     */
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

    @Nullable
    private static Uri insert(ContentResolver resolver, File file, boolean isVideo) {
        Uri collection = isVideo
                ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName(file));
        values.put(MediaStore.MediaColumns.MIME_TYPE, isVideo ? "video/mp4" : "image/jpeg");
        if (Build.VERSION.SDK_INT >= 29) {
            String directory = isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES;
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, directory + "/" + ALBUM);
            // Hide the row until the bytes are written, so nothing reads a half-copied file.
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        }

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
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues published = new ContentValues();
                published.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(uri, published, null, null);
            }
            return uri;
        } catch (IOException | RuntimeException error) {
            Log.e(TAG, "Failed to save " + file + " to MediaStore", error);
            if (uri != null) {
                // Leave no pending row behind, which would otherwise stay invisible forever.
                try {
                    resolver.delete(uri, null, null);
                } catch (RuntimeException cleanupError) {
                    Log.w(TAG, "Failed to remove the incomplete MediaStore row", cleanupError);
                }
            }
            return null;
        }
    }

    /**
     * A name MediaStore will accept.
     *
     * <p>Characters that are not legal in a file name on every filesystem Android mounts, colons
     * most of all, are replaced rather than passed through to be rejected or silently rewritten.</p>
     */
    private static String displayName(File file) {
        return file.getName().replaceAll("[\\\\/:*?\"<>|]", "-");
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        output.flush();
    }
}
