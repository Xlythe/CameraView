package com.xlythe.view.camera;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import java.io.File;
import java.io.IOException;

/** A GIF, read only: no metadata this reads, but how many frames it shows in turn. */
final class GifContainer extends MediaContainer {
    /** The block that starts an image. */
    private static final int IMAGE = 0x2C;

    /** The block that starts an extension. */
    private static final int EXTENSION = 0x21;

    /** How many frames the file holds. */
    private final int mFrames;

    /** An empty packet, which can never be written. */
    private final Xmp mXmp = Xmp.empty();

    private GifContainer(int frames) {
        mFrames = frames;
    }

    /** Reads a GIF's structure, counting its frames. */
    @WorkerThread
    @NonNull
    static GifContainer read(@NonNull ByteSource source) throws IOException {
        return new GifContainer(frames(source));
    }

    /** How many images a GIF holds, counted until its trailer or the first block it cannot read. */
    @VisibleForTesting
    @WorkerThread
    static int frames(@NonNull ByteSource source) throws IOException {
        byte[] screen = source.bytesUpTo(0, 13);
        if (screen.length < 13) {
            return 0;
        }
        long at = 13;
        int flags = Bytes.u8(screen, 10);
        if ((flags & 0x80) != 0) {
            at += 3L << ((flags & 0x07) + 1);
        }
        int frames = 0;
        long length = source.length();
        while (at < length) {
            int block = Bytes.u8(source.bytesUpTo(at, 1), 0);
            if (block == IMAGE) {
                byte[] descriptor = source.bytesUpTo(at, 10);
                if (descriptor.length < 10) {
                    break;
                }
                frames++;
                at += 10;
                int local = Bytes.u8(descriptor, 9);
                if ((local & 0x80) != 0) {
                    at += 3L << ((local & 0x07) + 1);
                }
                // The LZW code size, then the data's sub-blocks.
                at = skipSubBlocks(source, at + 1);
            } else if (block == EXTENSION) {
                at = skipSubBlocks(source, at + 2);
            } else {
                // The trailer, or something no reader understands.
                break;
            }
            if (at < 0) {
                break;
            }
        }
        return frames;
    }

    /** Where a run of sub-blocks ends, or -1 if it runs past the file. */
    private static long skipSubBlocks(@NonNull ByteSource source, long at) throws IOException {
        long length = source.length();
        while (at < length) {
            int size = Bytes.u8(source.bytesUpTo(at, 1), 0);
            at += 1 + size;
            if (size == 0) {
                return at;
            }
        }
        return -1;
    }

    @Override
    int format() {
        return MediaTags.FORMAT_GIF;
    }

    @Override
    int kind() {
        return MediaTags.KIND_IMAGE;
    }

    @Override
    boolean animated() {
        return mFrames > 1;
    }

    @Override
    boolean keeps(@Field int field) {
        return false;
    }

    @NonNull
    @Override
    Xmp xmp() {
        return mXmp;
    }

    @Override
    int maxXmpBytes() {
        return NO_XMP;
    }

    @Override
    void save(@NonNull File file) throws IOException {
        throw new IOException("GIF keeps no metadata this writes");
    }
}
