package com.xlythe.view.camera;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * A WebP: Exif in its EXIF chunk and XMP in its XMP chunk, both of which need the extended
 * format's VP8X header to say they are there. A simple WebP gains that header when it gains
 * either chunk.
 */
final class WebpContainer extends ExifImageContainer {
    /** The VP8X flags for the chunks this writes. */
    @VisibleForTesting
    static final int FLAG_EXIF = 0x08;
    @VisibleForTesting
    static final int FLAG_XMP = 0x04;

    /** The flag for an alpha channel, which a lossless picture may have. */
    private static final int FLAG_ALPHA = 0x10;

    /** A chunk's place in the file. */
    private static final class Chunk {
        @NonNull
        final String type;

        /** Where its header is. */
        final long offset;

        /** How many bytes of data it holds, before any padding byte. */
        final long length;

        Chunk(@NonNull String type, long offset, long length) {
            this.type = type;
            this.offset = offset;
            this.length = length;
        }

        long data() {
            return offset + 8;
        }

        /** One past its end, padding included. */
        long end() {
            return offset + 8 + length + (length & 1);
        }
    }

    /** The bare TIFF block of the EXIF chunk, or null. */
    @Nullable
    private final byte[] mTiff;

    /** The packet, as read or as changed since. */
    @NonNull
    private final Xmp mXmp;

    /** Whether the packet has changed. */
    private boolean mXmpDirty;

    private WebpContainer(@NonNull ByteSource source, @Nullable byte[] tiff, @NonNull Xmp xmp,
            boolean writable) throws IOException {
        super(writable);
        mTiff = tiff;
        mXmp = xmp;
        setReadExif(tiff == null ? null : readTiff(tiff));
    }

    @WorkerThread
    @NonNull
    static WebpContainer read(@NonNull ByteSource source, boolean writable) throws IOException {
        byte[] tiff = null;
        Xmp xmp = null;
        for (Chunk chunk : chunks(source)) {
            if ("EXIF".equals(chunk.type) && tiff == null) {
                tiff = tiffOf(source.bytes(chunk.data(), chunk.length));
            } else if ("XMP ".equals(chunk.type) && xmp == null) {
                xmp = Xmp.parse(source.bytes(chunk.data(), chunk.length));
            }
        }
        return new WebpContainer(source, tiff, xmp == null ? Xmp.empty() : xmp, writable);
    }

    @NonNull
    private static List<Chunk> chunks(@NonNull ByteSource source) throws IOException {
        byte[] header = source.bytesUpTo(0, 12);
        if (header.length < 12 || !Bytes.startsWith(header, 0, "RIFF")
                || !Bytes.startsWith(header, 8, "WEBP")) {
            throw new IOException("Not a WebP");
        }
        long end = Math.min(source.length(), 8 + Bytes.u32le(header, 4));
        List<Chunk> chunks = new ArrayList<>();
        long at = 12;
        byte[] chunkHeader = new byte[8];
        while (at + 8 <= end) {
            source.readFully(at, chunkHeader, 0, 8);
            long length = Bytes.u32le(chunkHeader, 4);
            if (at + 8 + length > end) {
                break;
            }
            chunks.add(new Chunk(Bytes.fourCc(chunkHeader, 0), at, length));
            at += 8 + length + (length & 1);
        }
        return chunks;
    }

    @Override
    int format() {
        return MediaTags.FORMAT_WEBP;
    }

    @Override
    int kind() {
        return MediaTags.KIND_IMAGE;
    }

    @Nullable
    @Override
    byte[] tiffBlock() {
        return mTiff;
    }

    @NonNull
    @Override
    Xmp xmp() {
        return mXmp;
    }

    @Override
    int maxXmpBytes() {
        return isWritable() ? Integer.MAX_VALUE : NO_XMP;
    }

    @Override
    void xmpChanged() {
        super.xmpChanged();
        mXmpDirty = true;
    }

    @Override
    void save(@NonNull File file) throws IOException {
        byte[] tiff = saveWrappedExif();
        byte[] packet = mXmpDirty ? (mXmp.isEmpty() ? new byte[0] : mXmp.toBytes()) : null;
        try (ByteSource source = ByteSource.of(file)) {
            List<Chunk> chunks = chunks(source);
            byte[] exifData = tiff;
            byte[] xmpData = packet;
            // What is not being replaced is carried over as it was.
            for (Chunk chunk : chunks) {
                if ("EXIF".equals(chunk.type) && exifData == null) {
                    exifData = source.bytes(chunk.data(), chunk.length);
                } else if ("XMP ".equals(chunk.type) && xmpData == null) {
                    xmpData = source.bytes(chunk.data(), chunk.length);
                }
            }
            final byte[] exif = exifData != null && exifData.length > 0 ? exifData : null;
            final byte[] xmp = xmpData != null && xmpData.length > 0 ? xmpData : null;
            final byte[] vp8x = extendedHeader(source, chunks, exif != null, xmp != null);
            long size = 4 + (vp8x == null ? 0 : vp8x.length);
            for (Chunk chunk : chunks) {
                if (!isReplaced(chunk)) {
                    size += chunk.end() - chunk.offset;
                }
            }
            size += exif == null ? 0 : 8 + exif.length + (exif.length & 1);
            size += xmp == null ? 0 : 8 + xmp.length + (xmp.length & 1);
            final long riffSize = size;
            MediaFiles.replace(file, out -> {
                byte[] header = new byte[12];
                System.arraycopy(Bytes.fourCc("RIFF"), 0, header, 0, 4);
                Bytes.put32le(header, 4, riffSize);
                System.arraycopy(Bytes.fourCc("WEBP"), 0, header, 8, 4);
                out.write(header);
                if (vp8x != null) {
                    out.write(vp8x);
                }
                for (Chunk chunk : chunks) {
                    if (!isReplaced(chunk)) {
                        MediaFiles.copy(source, chunk.offset, chunk.end(), out);
                    }
                }
                if (exif != null) {
                    writeChunk(out, "EXIF", exif);
                }
                if (xmp != null) {
                    writeChunk(out, "XMP ", xmp);
                }
            });
        }
        mXmpDirty = false;
        saved();
    }

    /** Whether a chunk is written anew rather than copied. */
    private static boolean isReplaced(@NonNull Chunk chunk) {
        return "VP8X".equals(chunk.type) || "EXIF".equals(chunk.type)
                || "XMP ".equals(chunk.type);
    }

    /**
     * The VP8X chunk for the file: its own with the EXIF and XMP flags set to match, or a new
     * one for a simple WebP that gains either.
     *
     * @return the chunk, or null for a simple WebP that needs none
     */
    @Nullable
    private static byte[] extendedHeader(@NonNull ByteSource source, @NonNull List<Chunk> chunks,
            boolean exif, boolean xmp) throws IOException {
        byte[] data = null;
        for (Chunk chunk : chunks) {
            if ("VP8X".equals(chunk.type) && chunk.length >= 10) {
                data = source.bytes(chunk.data(), 10);
            }
        }
        if (data == null) {
            if (!exif && !xmp) {
                return null;
            }
            data = new byte[10];
            int[] canvas = canvas(source, chunks);
            if (canvas == null) {
                throw new IOException("The WebP's picture has no size to give its header");
            }
            if (canvas[2] != 0) {
                data[0] |= FLAG_ALPHA;
            }
            Bytes.put24le(data, 4, canvas[0] - 1);
            Bytes.put24le(data, 7, canvas[1] - 1);
        }
        data[0] = (byte) ((data[0] & ~(FLAG_EXIF | FLAG_XMP)) | (exif ? FLAG_EXIF : 0)
                | (xmp ? FLAG_XMP : 0));
        byte[] chunk = new byte[18];
        System.arraycopy(Bytes.fourCc("VP8X"), 0, chunk, 0, 4);
        Bytes.put32le(chunk, 4, 10);
        System.arraycopy(data, 0, chunk, 8, 10);
        return chunk;
    }

    /**
     * A simple WebP's size, from its bitstream's own header, and whether it has alpha.
     *
     * @return {width, height, alpha as 1 or 0}, or null if the bitstream is neither kind
     */
    @Nullable
    private static int[] canvas(@NonNull ByteSource source, @NonNull List<Chunk> chunks)
            throws IOException {
        for (Chunk chunk : chunks) {
            if ("VP8 ".equals(chunk.type) && chunk.length >= 10) {
                byte[] frame = source.bytes(chunk.data(), 10);
                return new int[] {Bytes.u16le(frame, 6) & 0x3FFF,
                        Bytes.u16le(frame, 8) & 0x3FFF, 0};
            }
            if ("VP8L".equals(chunk.type) && chunk.length >= 5) {
                byte[] frame = source.bytes(chunk.data(), 5);
                long bits = Bytes.u32le(frame, 1);
                return new int[] {(int) (bits & 0x3FFF) + 1, (int) ((bits >> 14) & 0x3FFF) + 1,
                        (int) ((bits >> 28) & 1)};
            }
        }
        return null;
    }

    /** Writes a chunk: its type, its length, its data, and a padding byte if it is odd. */
    static void writeChunk(@NonNull OutputStream out, @NonNull String type, @NonNull byte[] data)
            throws IOException {
        byte[] header = new byte[8];
        System.arraycopy(Bytes.fourCc(type), 0, header, 0, 4);
        Bytes.put32le(header, 4, data.length);
        out.write(header);
        out.write(data);
        if ((data.length & 1) != 0) {
            out.write(0);
        }
    }
}
