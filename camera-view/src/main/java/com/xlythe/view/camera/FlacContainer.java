package com.xlythe.view.camera;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * A FLAC: Vorbis comments in its VORBIS_COMMENT block and the cover in a PICTURE block. FLAC
 * keeps no XMP. New blocks go in place of the old ones, padded to their size when they fit, and
 * otherwise the file is rewritten with room to spare.
 */
final class FlacContainer extends MediaContainer {
    /** The block types this reads and writes. */
    private static final int STREAMINFO = 0;
    private static final int PADDING = 1;
    private static final int VORBIS_COMMENT = 4;
    private static final int PICTURE = 6;

    /** Room left in a rewritten file, so the next change can be made in place. */
    private static final int PADDING_BYTES = 4096;

    /** The most of a block this reads. */
    private static final int MAX_BLOCK = 16 * 1024 * 1024;

    /** One metadata block. */
    private static final class Block {
        final int type;

        @NonNull
        final byte[] data;

        Block(int type, @NonNull byte[] data) {
            this.type = type;
            this.data = data;
        }
    }

    /** Whether the file can be written. */
    private final boolean mWritable;

    /** Where "fLaC" is, after any ID3 tag in front of it. */
    private final long mStart;

    /** Where the audio frames start, after the last block. */
    private final long mAudioStart;

    /** The blocks other than padding, as read or as changed since. */
    @NonNull
    private final List<Block> mBlocks;

    /** The comments, as read or as changed since. */
    @NonNull
    private final VorbisComments mComments;

    /** The frames of an ID3 tag in front of the FLAC, read as a fallback, or empty. */
    @NonNull
    private final List<Id3Container.Frame> mId3;

    /** That tag's version. */
    private final int mId3Version;

    /** An empty packet: FLAC keeps none. */
    private final Xmp mXmp = Xmp.empty();

    private FlacContainer(boolean writable, long start, long audioStart,
            @NonNull List<Block> blocks, @NonNull VorbisComments comments,
            @NonNull List<Id3Container.Frame> id3, int id3Version) {
        mWritable = writable;
        mId3 = id3;
        mId3Version = id3Version;
        mStart = start;
        mAudioStart = audioStart;
        mBlocks = blocks;
        mComments = comments;
    }

    @WorkerThread
    @NonNull
    static FlacContainer read(@NonNull ByteSource source, boolean writable) throws IOException {
        long start = Id3Container.tagSize(source.bytesUpTo(0, 10));
        if (!Bytes.startsWith(source.bytesUpTo(start, 4), 0, "fLaC")) {
            throw new IOException("Not a FLAC");
        }
        long at = start + 4;
        List<Block> blocks = new ArrayList<>();
        VorbisComments comments = null;
        boolean last = false;
        while (!last && at + 4 <= source.length()) {
            byte[] header = source.bytes(at, 4);
            last = (header[0] & 0x80) != 0;
            int type = header[0] & 0x7F;
            int length = Bytes.u24(header, 1);
            if (at + 4 + length > source.length() || length > MAX_BLOCK) {
                throw new IOException("A FLAC metadata block runs past the file");
            }
            if (type != PADDING) {
                byte[] data = source.bytes(at + 4, length);
                blocks.add(new Block(type, data));
                if (type == VORBIS_COMMENT && comments == null) {
                    comments = VorbisComments.parse(data, 0);
                }
            }
            at += 4 + length;
        }
        if (blocks.isEmpty() || blocks.get(0).type != STREAMINFO) {
            throw new IOException("A FLAC without its stream information");
        }
        List<Id3Container.Frame> id3 = new ArrayList<>();
        int id3Version = 0;
        if (start > 10) {
            byte[] header = source.bytes(0, 10);
            id3Version = header[3];
            id3 = Id3Container.frames(header, source.bytes(10, start - 10));
        }
        return new FlacContainer(writable, start, at, blocks,
                comments == null ? VorbisComments.empty() : comments, id3, id3Version);
    }

    @Override
    int format() {
        return MediaTags.FORMAT_FLAC;
    }

    @Override
    int kind() {
        return MediaTags.KIND_AUDIO;
    }

    @Override
    boolean keeps(@Field int field) {
        return mWritable && hasNativeHome(field);
    }

    @Override
    boolean hasNativeHome(@Field int field) {
        return VorbisComments.nameOf(field) != null || field == CAPTURE_TIME
                || field == COVER_ART;
    }

    @Nullable
    @Override
    String text(@Field int field) {
        String value = mComments.field(field);
        return value != null ? value : Id3Container.field(mId3, mId3Version, field);
    }

    @Override
    boolean setText(@Field int field, @Nullable String value) {
        String name = VorbisComments.nameOf(field);
        if (name == null) {
            return false;
        }
        mComments.set(name, value);
        if (field == DESCRIPTION) {
            mComments.set("COMMENT", null);
        }
        markDirty();
        return true;
    }

    @Override
    long captureTime() {
        return mComments.date();
    }

    @Override
    void setCaptureTime(long millis) {
        mComments.setDate(millis);
        markDirty();
    }

    @Nullable
    @Override
    byte[] coverArt() {
        byte[] first = null;
        for (Block block : mBlocks) {
            if (block.type == PICTURE) {
                byte[] image = VorbisComments.pictureData(block.data);
                if (VorbisComments.pictureType(block.data) == VorbisComments.FRONT_COVER) {
                    return image;
                }
                if (first == null) {
                    first = image;
                }
            }
        }
        if (first == null) {
            first = mComments.picture();
        }
        return first != null ? first : Id3Container.cover(mId3, mId3Version);
    }

    @Override
    boolean setCoverArt(@Nullable byte[] image) {
        byte[] block = image == null ? null : VorbisComments.pictureBlock(image);
        if (image != null && block == null) {
            return false;
        }
        for (int i = mBlocks.size() - 1; i >= 0; i--) {
            if (mBlocks.get(i).type == PICTURE) {
                mBlocks.remove(i);
            }
        }
        if (block != null) {
            mBlocks.add(new Block(PICTURE, block));
        }
        markDirty();
        return true;
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
        List<Block> blocks = new ArrayList<>();
        boolean commented = false;
        for (Block block : mBlocks) {
            if (block.type == VORBIS_COMMENT) {
                if (!commented) {
                    blocks.add(new Block(VORBIS_COMMENT, mComments.toBytes()));
                    commented = true;
                }
            } else {
                blocks.add(block);
            }
        }
        if (!commented) {
            blocks.add(1, new Block(VORBIS_COMMENT, mComments.toBytes()));
        }
        long room = mAudioStart - mStart - 4;
        int size = 0;
        for (Block block : blocks) {
            size += 4 + block.data.length;
        }
        if (size == room || size + 4 <= room) {
            byte[] metadata = metadata(blocks, size == room ? -1 : (int) (room - size - 4));
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                raf.seek(mStart + 4);
                raf.write(metadata);
            }
        } else {
            byte[] metadata = metadata(blocks, PADDING_BYTES);
            try (ByteSource source = ByteSource.of(file)) {
                MediaFiles.replace(file, out -> {
                    MediaFiles.copy(source, 0, mStart + 4, out);
                    out.write(metadata);
                    MediaFiles.copy(source, mAudioStart, source.length(), out);
                });
            }
        }
        saved();
    }

    /** The blocks, the last marked as such, with padding of a size at the end unless -1. */
    @NonNull
    private static byte[] metadata(@NonNull List<Block> blocks, int padding) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            boolean last = padding < 0 && i == blocks.size() - 1;
            writeBlock(out, block.type, block.data, last);
        }
        if (padding >= 0) {
            writeBlock(out, PADDING, new byte[padding], true);
        }
        return out.toByteArray();
    }

    private static void writeBlock(@NonNull ByteArrayOutputStream out, int type,
            @NonNull byte[] data, boolean last) {
        byte[] header = new byte[4];
        header[0] = (byte) ((last ? 0x80 : 0) | type);
        Bytes.put24(header, 1, data.length);
        out.write(header, 0, 4);
        out.write(data, 0, data.length);
    }
}
