package com.xlythe.view.camera.viewer;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * A copy of a QuickTime movie that opens without a file type box, as older QuickTime movies
 * and some Live Photos do, with one put in front. Android's player does not recognise such a
 * movie at all; with the box, and every chunk offset moved along by its size, it plays.
 */
final class QuickTimeCopy {
    /** The charset of box and chunk names, by name, which every release has. */
    private static final Charset ASCII = Charset.forName("US-ASCII");

    /** The boxes a QuickTime movie may open with, before brands. */
    private static final String[] OPENING_BOXES = {"wide", "free", "skip", "mdat", "moov",
            "pnot"};

    /** The boxes on the way down to a track's chunk offsets. */
    private static final String[] CONTAINERS = {"moov", "trak", "mdia", "minf", "stbl"};

    /** The largest movie copied: a Live Photo's clip is a few megabytes. */
    @VisibleForTesting
    static final int MAX_BYTES = 64 * 1024 * 1024;

    /** The file type box put in front: QuickTime, minor version 2005.03, QuickTime. */
    private static final byte[] FTYP = {0, 0, 0, 20, 'f', 't', 'y', 'p', 'q', 't', ' ', ' ', 0x20,
            0x05, 0x03, 0x00, 'q', 't', ' ', ' '};

    private QuickTimeCopy() {}

    /**
     * A copy of the movie that plays, made once and kept in the cache, or null if the movie
     * needs none or is too large to copy.
     */
    @WorkerThread
    @Nullable
    static File playable(@NonNull Context context, @NonNull ImageLocation location)
            throws IOException {
        byte[] head;
        try (InputStream input = location.open(context)) {
            head = new byte[8];
            if (read(input, head) < 8 || !needsCopy(head)) {
                return null;
            }
        }
        File directory = new File(context.getCacheDir(), ImageSources.CACHE_DIRECTORY);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Unable to make " + directory);
        }
        File copy = new File(directory,
                ImageSources.digest(location.fingerprint(context)) + ".mov");
        if (copy.isFile()) {
            return copy;
        }
        byte[] movie = readAll(context, location);
        if (movie == null) {
            return null;
        }
        byte[] fixed = withFileType(movie);
        File partial = new File(directory, copy.getName() + ".partial");
        try (FileOutputStream output = new FileOutputStream(partial)) {
            output.write(fixed);
        }
        if (!partial.renameTo(copy)) {
            throw new IOException("Unable to keep the copy of " + location);
        }
        ImageSources.trim(directory);
        return copy;
    }

    /** Whether a file opens with a box a QuickTime movie may, rather than a file type box. */
    @VisibleForTesting
    static boolean needsCopy(@NonNull byte[] head) {
        String type = new String(head, 4, 4, ASCII);
        for (String opening : OPENING_BOXES) {
            if (opening.equals(type)) {
                return true;
            }
        }
        return false;
    }

    /** The movie with a file type box in front and its chunk offsets moved to match. */
    @VisibleForTesting
    @NonNull
    static byte[] withFileType(@NonNull byte[] movie) {
        byte[] out = new byte[FTYP.length + movie.length];
        System.arraycopy(FTYP, 0, out, 0, FTYP.length);
        System.arraycopy(movie, 0, out, FTYP.length, movie.length);
        shiftOffsets(out, FTYP.length, out.length, FTYP.length, 0);
        return out;
    }

    /** Adds to every chunk offset in the boxes between two places. */
    private static void shiftOffsets(@NonNull byte[] data, int start, int end, int shift,
            int depth) {
        int at = start;
        while (at + 8 <= end) {
            long size = u32(data, at);
            String type = new String(data, at + 4, 4, ASCII);
            int header = 8;
            if (size == 1 && at + 16 <= end) {
                size = (u32(data, at + 8) << 32) | u32(data, at + 12);
                header = 16;
            } else if (size == 0) {
                size = end - at;
            }
            if (size < header || at + size > end) {
                return;
            }
            int boxEnd = (int) (at + size);
            if (depth < CONTAINERS.length && isContainer(type)) {
                shiftOffsets(data, at + header, boxEnd, shift, depth + 1);
            } else if ("stco".equals(type) && at + 16 <= boxEnd) {
                long count = u32(data, at + 12);
                for (long i = 0; i < count && at + 16 + i * 4 + 4 <= boxEnd; i++) {
                    int entry = (int) (at + 16 + i * 4);
                    put32(data, entry, u32(data, entry) + shift);
                }
            } else if ("co64".equals(type) && at + 16 <= boxEnd) {
                long count = u32(data, at + 12);
                for (long i = 0; i < count && at + 16 + i * 8 + 8 <= boxEnd; i++) {
                    int entry = (int) (at + 16 + i * 8);
                    long value = ((u32(data, entry) << 32) | u32(data, entry + 4)) + shift;
                    put32(data, entry, value >>> 32);
                    put32(data, entry + 4, value);
                }
            }
            at = boxEnd;
        }
    }

    private static boolean isContainer(@NonNull String type) {
        for (String container : CONTAINERS) {
            if (container.equals(type)) {
                return true;
            }
        }
        return false;
    }

    /** The whole movie, or null if it is larger than is worth copying. */
    @Nullable
    private static byte[] readAll(@NonNull Context context, @NonNull ImageLocation location)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream input = location.open(context)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_BYTES) {
                    return null;
                }
            }
        }
        return out.toByteArray();
    }

    private static int read(@NonNull InputStream input, @NonNull byte[] buffer)
            throws IOException {
        int done = 0;
        while (done < buffer.length) {
            int read = input.read(buffer, done, buffer.length - done);
            if (read < 0) {
                break;
            }
            done += read;
        }
        return done;
    }

    private static long u32(@NonNull byte[] data, int at) {
        return ((data[at] & 0xFFL) << 24) | ((data[at + 1] & 0xFFL) << 16)
                | ((data[at + 2] & 0xFFL) << 8) | (data[at + 3] & 0xFFL);
    }

    private static void put32(@NonNull byte[] data, int at, long value) {
        data[at] = (byte) (value >>> 24);
        data[at + 1] = (byte) (value >>> 16);
        data[at + 2] = (byte) (value >>> 8);
        data[at + 3] = (byte) value;
    }
}
