package com.xlythe.view.camera.viewer;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Where a picture is, a file or a content Uri, opened afresh each time a reader needs it.
 *
 * <p>Pictures are read several times over: once for their size, once for their metadata and
 * again for each piece decoded. A file opens the same way every time, and a content Uri goes
 * through its provider.</p>
 */
final class ImageLocation {
    /** The file, or null when the picture is behind a Uri. */
    @Nullable
    private final File mFile;

    /** The Uri, or null when the picture is a file. */
    @Nullable
    private final Uri mUri;

    private ImageLocation(@Nullable File file, @Nullable Uri uri) {
        mFile = file;
        mUri = uri;
    }

    @AnyThread
    @NonNull
    static ImageLocation of(@NonNull File file) {
        return new ImageLocation(file, null);
    }

    /** A Uri, unless it names a file, in which case that file. */
    @AnyThread
    @NonNull
    static ImageLocation of(@NonNull Uri uri) {
        if (ContentResolver.SCHEME_FILE.equals(uri.getScheme()) && uri.getPath() != null) {
            return new ImageLocation(new File(uri.getPath()), null);
        }
        return new ImageLocation(null, uri);
    }

    @WorkerThread
    @NonNull
    InputStream open(@NonNull Context context) throws IOException {
        if (mFile != null) {
            return new FileInputStream(mFile);
        }
        InputStream stream = context.getContentResolver().openInputStream(uri());
        if (stream == null) {
            throw new FileNotFoundException("Nothing to read at " + mUri);
        }
        return stream;
    }

    /** A descriptor to read from, which for a provider's pipe cannot seek. */
    @WorkerThread
    @NonNull
    ParcelFileDescriptor openDescriptor(@NonNull Context context) throws IOException {
        if (mFile != null) {
            return ParcelFileDescriptor.open(mFile, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        ParcelFileDescriptor descriptor =
                context.getContentResolver().openFileDescriptor(uri(), "r");
        if (descriptor == null) {
            throw new FileNotFoundException("Nothing to read at " + mUri);
        }
        return descriptor;
    }

    /**
     * Names this picture as it is now, so that a copy made from it is found again while the
     * picture is unchanged and missed once it is replaced.
     */
    @WorkerThread
    @NonNull
    String fingerprint(@NonNull Context context) {
        if (mFile != null) {
            return mFile.getAbsolutePath() + '|' + mFile.length() + '|' + mFile.lastModified();
        }
        long size = -1;
        try {
            ParcelFileDescriptor descriptor = openDescriptor(context);
            try {
                size = descriptor.getStatSize();
            } finally {
                descriptor.close();
            }
        } catch (IOException | RuntimeException unknown) {
            // A provider that cannot say how big the picture is still names it by its Uri.
        }
        return uri().toString() + '|' + size;
    }

    @NonNull
    private Uri uri() {
        if (mUri == null) {
            throw new IllegalStateException("Not a Uri");
        }
        return mUri;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (!(other instanceof ImageLocation)) {
            return false;
        }
        ImageLocation location = (ImageLocation) other;
        return mFile != null ? mFile.equals(location.mFile)
                : location.mFile == null && uri().equals(location.mUri);
    }

    @Override
    public int hashCode() {
        return mFile != null ? mFile.hashCode() : uri().hashCode();
    }

    @NonNull
    @Override
    public String toString() {
        return mFile != null ? mFile.toString() : uri().toString();
    }
}
