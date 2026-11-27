package com.xlythe.view.camera;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Rewrites a file safely, for the metadata writers. */
final class MediaFiles {
    /** How much is copied at a time. */
    private static final int BUFFER_BYTES = 256 * 1024;

    /** Writes a file's new contents. */
    interface Writer {
        @WorkerThread
        void write(@NonNull OutputStream out) throws IOException;
    }

    private MediaFiles() {}

    /**
     * Writes a file's new contents beside it, then puts them in its place, so a failure part
     * way leaves the file as it was.
     */
    @WorkerThread
    static void replace(@NonNull File file, @NonNull Writer writer) throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        File temporary = new File(parent, "." + file.getName() + ".mediatags");
        try {
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(temporary),
                    BUFFER_BYTES)) {
                writer.write(out);
            }
            if (!temporary.renameTo(file)) {
                // Some storage refuses to rename over a file; copy over it instead.
                try (InputStream in = new FileInputStream(temporary);
                        OutputStream out = new FileOutputStream(file)) {
                    byte[] buffer = new byte[BUFFER_BYTES];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                }
            }
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                temporary.deleteOnExit();
            }
        }
    }

    /** Copies a range of a source to a stream. */
    @WorkerThread
    static void copy(@NonNull ByteSource source, long from, long to, @NonNull OutputStream out)
            throws IOException {
        byte[] buffer = new byte[(int) Math.min(BUFFER_BYTES, Math.max(0, to - from))];
        long at = from;
        while (at < to) {
            int count = (int) Math.min(buffer.length, to - at);
            source.readFully(at, buffer, 0, count);
            out.write(buffer, 0, count);
            at += count;
        }
    }
}
