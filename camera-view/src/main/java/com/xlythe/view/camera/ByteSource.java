package com.xlythe.view.camera;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Reads a file at any position, whether it is a file, a descriptor a provider handed over, or
 * bytes already in memory. The metadata readers jump around a file: a video keeps its metadata
 * at its end as often as its start, and a HEIF picture says where its items are by offset.
 */
abstract class ByteSource implements Closeable {
    /** The most a stream that cannot seek is read into memory: enough for any picture's head. */
    static final int MAX_BUFFERED_STREAM = 64 * 1024 * 1024;

    /** How many bytes there are. */
    abstract long length();

    /**
     * Reads bytes from a position, as many as are asked for unless the end comes first.
     *
     * @return how many were read, which is fewer only at the end
     */
    @WorkerThread
    abstract int read(long position, @NonNull byte[] buffer, int offset, int count)
            throws IOException;

    /** Reads exactly as many bytes as asked for. */
    @WorkerThread
    void readFully(long position, @NonNull byte[] buffer, int offset, int count)
            throws IOException {
        if (read(position, buffer, offset, count) != count) {
            throw new EOFException("Only " + length() + " bytes, wanted " + count + " at "
                    + position);
        }
    }

    /** The bytes in a range, which must lie within the source. */
    @WorkerThread
    @NonNull
    byte[] bytes(long position, long count) throws IOException {
        if (count < 0 || count > Integer.MAX_VALUE - 8 || position < 0
                || position + count > length()) {
            throw new EOFException(count + " bytes at " + position + " run past "
                    + length());
        }
        byte[] bytes = new byte[(int) count];
        readFully(position, bytes, 0, bytes.length);
        return bytes;
    }

    /** As many bytes as there are in a range, up to its end or the source's. */
    @WorkerThread
    @NonNull
    byte[] bytesUpTo(long position, int count) throws IOException {
        long available = Math.max(0, Math.min(count, length() - position));
        return bytes(position, available);
    }

    @Override
    public void close() throws IOException {}

    /** A file, read in place. */
    @NonNull
    static ByteSource of(@NonNull File file) throws IOException {
        return new FileSource(new RandomAccessFile(file, "r"));
    }

    /** Bytes in memory. */
    @NonNull
    static ByteSource of(@NonNull byte[] bytes) {
        return new MemorySource(bytes);
    }

    /**
     * A stream: read in place if it is a file that can seek, such as one a provider opened on
     * real storage, and otherwise read into memory up to {@link #MAX_BUFFERED_STREAM}. Takes
     * ownership of the stream, which closes with the source.
     */
    @WorkerThread
    @NonNull
    static ByteSource of(@NonNull InputStream stream) throws IOException {
        if (stream instanceof FileInputStream) {
            FileChannel channel = ((FileInputStream) stream).getChannel();
            try {
                long size = channel.size();
                channel.position(0);
                if (size > 0) {
                    return new ChannelSource(stream, channel, size);
                }
            } catch (IOException notSeekable) {
                // A pipe: read it like any other stream.
            }
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int read;
            while (bytes.size() < MAX_BUFFERED_STREAM && (read = stream.read(buffer, 0,
                    Math.min(buffer.length, MAX_BUFFERED_STREAM - bytes.size()))) > 0) {
                bytes.write(buffer, 0, read);
            }
            return new MemorySource(bytes.toByteArray());
        } finally {
            stream.close();
        }
    }

    /** A file opened for reading. */
    private static final class FileSource extends ByteSource {
        /** The open file. */
        private final RandomAccessFile mFile;

        /** Its length, fixed when it was opened. */
        private final long mLength;

        FileSource(@NonNull RandomAccessFile file) throws IOException {
            mFile = file;
            mLength = file.length();
        }

        @Override
        long length() {
            return mLength;
        }

        @Override
        int read(long position, @NonNull byte[] buffer, int offset, int count)
                throws IOException {
            if (position >= mLength || count <= 0) {
                return 0;
            }
            mFile.seek(position);
            int total = 0;
            while (total < count) {
                int read = mFile.read(buffer, offset + total, count - total);
                if (read < 0) {
                    break;
                }
                total += read;
            }
            return total;
        }

        @Override
        public void close() throws IOException {
            mFile.close();
        }
    }

    /** A stream on a file that can seek. */
    private static final class ChannelSource extends ByteSource {
        /** Closed with the source. */
        private final InputStream mStream;

        /** Read at positions, which leaves the stream's own position alone. */
        private final FileChannel mChannel;

        /** The file's length. */
        private final long mLength;

        ChannelSource(@NonNull InputStream stream, @NonNull FileChannel channel, long length) {
            mStream = stream;
            mChannel = channel;
            mLength = length;
        }

        @Override
        long length() {
            return mLength;
        }

        @Override
        int read(long position, @NonNull byte[] buffer, int offset, int count)
                throws IOException {
            ByteBuffer target = ByteBuffer.wrap(buffer, offset, count);
            long at = position;
            while (target.hasRemaining()) {
                int read = mChannel.read(target, at);
                if (read < 0) {
                    break;
                }
                at += read;
            }
            return (int) (at - position);
        }

        @Override
        public void close() throws IOException {
            mStream.close();
        }
    }

    /** Bytes in memory. */
    private static final class MemorySource extends ByteSource {
        /** The bytes. */
        private final byte[] mBytes;

        MemorySource(@NonNull byte[] bytes) {
            mBytes = bytes;
        }

        @Override
        long length() {
            return mBytes.length;
        }

        @Override
        int read(long position, @NonNull byte[] buffer, int offset, int count) {
            if (position >= mBytes.length || count <= 0) {
                return 0;
            }
            int read = (int) Math.min(count, mBytes.length - position);
            System.arraycopy(mBytes, (int) position, buffer, offset, read);
            return read;
        }
    }
}
