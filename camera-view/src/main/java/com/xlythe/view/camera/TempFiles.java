package com.xlythe.view.camera;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Where the metadata writers put their temporary copies.
 *
 * <p>They go in java.io.tmpdir, which Android 4.3 and later point at the app's cache. Android 4.2
 * and older leave it at /sdcard, which an app without storage permission cannot write, so there a
 * copy goes beside the file being written instead: the one folder the write needs anyway, and one
 * known without a Context.</p>
 */
final class TempFiles {
    /** The system property File.createTempFile reads on every call. */
    private static final String TMPDIR = "java.io.tmpdir";

    /**
     * Holds java.io.tmpdir steady. Writes that use it as it is share it; a write that points it
     * somewhere else for a while holds it alone, so no other write sees the folder change under
     * it or put back before it has made its copy.
     */
    private static final ReadWriteLock TMPDIR_LOCK = new ReentrantReadWriteLock();

    /** A write that makes its temporary copies in java.io.tmpdir, out of the library's reach. */
    interface Write {
        @WorkerThread
        void run() throws IOException;
    }

    private TempFiles() {}

    /**
     * The folder for a temporary copy made beside a file: java.io.tmpdir if it can be written,
     * and otherwise the given folder, or null to leave File.createTempFile to fail as it would.
     */
    @AnyThread
    @Nullable
    static File directory(@Nullable File fallback) {
        return isWritable(System.getProperty(TMPDIR)) ? null : fallback;
    }

    /**
     * Runs a write that makes its temporary copies in java.io.tmpdir, such as
     * ExifInterface.saveAttributes, which takes no folder. Where java.io.tmpdir cannot be written
     * it points at the given folder for the write and is put back after.
     */
    @WorkerThread
    static void run(@Nullable File fallback, @NonNull Write write) throws IOException {
        Lock shared = TMPDIR_LOCK.readLock();
        shared.lock();
        try {
            if (fallback == null || isWritable(System.getProperty(TMPDIR))) {
                write.run();
                return;
            }
        } finally {
            shared.unlock();
        }

        Lock alone = TMPDIR_LOCK.writeLock();
        alone.lock();
        try {
            String original = System.getProperty(TMPDIR);
            if (isWritable(original)) {
                write.run();
                return;
            }
            System.setProperty(TMPDIR, fallback.getPath());
            try {
                write.run();
            } finally {
                if (original == null) {
                    System.clearProperty(TMPDIR);
                } else {
                    System.setProperty(TMPDIR, original);
                }
            }
        } finally {
            alone.unlock();
        }
    }

    /** Whether a path names a folder that can be written. */
    private static boolean isWritable(@Nullable String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        File directory = new File(path);
        return directory.isDirectory() && directory.canWrite();
    }
}
