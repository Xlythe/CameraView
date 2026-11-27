package com.xlythe.view.camera.viewer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds the largest JPEG a camera's raw file carries beside its sensor data, for the raws
 * Android cannot decode: Canon's CR2 and CR3, Nikon's NEF, Sony's ARW, Fujifilm's RAF, Olympus's
 * ORF and Panasonic's RW2, and a DNG on a release before Android 7.0. Most cameras embed a
 * full-size preview; some embed only a smaller one.
 */
final class RawPreview {
    /** The charset of box and chunk names, by name, which every release has. */
    private static final Charset ASCII = Charset.forName("US-ASCII");

    /** The TIFF tag of an IFD's sub-IFDs. */
    private static final int TAG_SUB_IFDS = 0x014A;

    /** The TIFF tag of the Exif IFD. */
    private static final int TAG_EXIF_IFD = 0x8769;

    /** The TIFF tag of an IFD's compression. */
    private static final int TAG_COMPRESSION = 0x0103;

    /** The TIFF tag of a single strip's offset. */
    private static final int TAG_STRIP_OFFSETS = 0x0111;

    /** The TIFF tag of a single strip's length. */
    private static final int TAG_STRIP_BYTE_COUNTS = 0x0117;

    /** The TIFF tag of an embedded JPEG's offset. */
    private static final int TAG_JPEG_OFFSET = 0x0201;

    /** The TIFF tag of an embedded JPEG's length. */
    private static final int TAG_JPEG_LENGTH = 0x0202;

    /** The Exif tag of a maker's own notes. */
    private static final int TAG_MAKER_NOTE = 0x927C;

    /** The Olympus maker note tag of its camera settings IFD. */
    private static final int TAG_OLYMPUS_CAMERA_SETTINGS = 0x2020;

    /** The Olympus camera settings tag of the preview's offset, from the maker note. */
    private static final int TAG_OLYMPUS_PREVIEW_START = 0x0101;

    /** The Olympus camera settings tag of the preview's length. */
    private static final int TAG_OLYMPUS_PREVIEW_LENGTH = 0x0102;

    /** The TIFF type of a 32-bit offset that points to an IFD. */
    private static final int TYPE_IFD = 13;

    /** The TIFF type of a 16-bit unsigned number. */
    private static final int TYPE_SHORT = 3;

    /** The TIFF type of bytes with no type. */
    private static final int TYPE_UNDEFINED = 7;

    /** The smallest blob worth looking at as an embedded JPEG. */
    private static final int MIN_JPEG = 1024;

    /** The most IFDs this walks, against loops. */
    private static final int MAX_IFDS = 64;

    /** How far into a JPEG this looks for its size. */
    private static final int MAX_HEADER = 256 * 1024;

    /** Where Fujifilm's header says its JPEG is. */
    private static final int RAF_JPEG_OFFSET = 84;

    /** An embedded JPEG: where it is, how long, and its size if known. */
    static final class Found {
        /** Where it starts in the file. */
        final long offset;

        /** How many bytes it is. */
        final long length;

        /** Its width, or 0 if its header could not be read. */
        final int width;

        /** Its height, or 0. */
        final int height;

        Found(long offset, long length, int width, int height) {
            this.offset = offset;
            this.length = length;
            this.width = width;
            this.height = height;
        }
    }

    /** The file being searched. */
    @NonNull
    private final FileChannel mChannel;

    /** Its length. */
    private final long mLength;

    /** The JPEGs found, before the largest is chosen. */
    private final List<Found> mFound = new ArrayList<>();

    /** The IFDs already walked. */
    private final Set<Long> mWalked = new HashSet<>();

    private RawPreview(@NonNull FileChannel channel) throws IOException {
        mChannel = channel;
        mLength = channel.size();
    }

    /** The largest JPEG a raw file embeds, or null if it has none or is not a raw file. */
    @WorkerThread
    @Nullable
    static Found find(@NonNull FileChannel channel) throws IOException {
        RawPreview search = new RawPreview(channel);
        search.search();
        Found best = null;
        for (Found found : search.mFound) {
            if (best == null || pixels(found) > pixels(best)
                    || (pixels(found) == pixels(best) && found.length > best.length)) {
                best = found;
            }
        }
        return best;
    }

    private static long pixels(@NonNull Found found) {
        return (long) found.width * found.height;
    }

    private void search() throws IOException {
        byte[] head = read(0, 16);
        if (head.length < 16) {
            return;
        }
        if (startsWith(head, 0, "FUJIFILMCCD-RAW")) {
            byte[] pointer = read(RAF_JPEG_OFFSET, 8);
            if (pointer.length == 8) {
                consider(u32(pointer, 0, false), u32(pointer, 4, false));
            }
            return;
        }
        if (startsWith(head, 4, "ftyp")) {
            searchBoxes(0, mLength, 0);
            return;
        }
        boolean little = head[0] == 'I' && head[1] == 'I';
        boolean big = head[0] == 'M' && head[1] == 'M';
        if (!little && !big) {
            return;
        }
        walk(u32(head, 4, little), little, 0);
    }

    /** Walks an IFD, the IFDs after it, and those it points to, considering every JPEG. */
    private void walk(long ifd, boolean little, int depth) throws IOException {
        while (ifd > 0 && ifd + 2 <= mLength && mWalked.size() < MAX_IFDS && mWalked.add(ifd)) {
            byte[] countBytes = read(ifd, 2);
            int count = u16(countBytes, 0, little);
            byte[] entries = read(ifd + 2, count * 12 + 4);
            long jpegOffset = -1;
            long jpegLength = -1;
            long stripOffset = -1;
            long stripLength = -1;
            int compression = 0;
            List<Long> next = new ArrayList<>();
            for (int i = 0; i < count * 12 && i + 12 <= entries.length; i += 12) {
                int tag = u16(entries, i, little);
                int type = u16(entries, i + 2, little);
                long n = u32(entries, i + 4, little);
                long value = type == TYPE_SHORT && n == 1 ? u16(entries, i + 8, little)
                        : u32(entries, i + 8, little);
                switch (tag) {
                    case TAG_SUB_IFDS:
                        next.addAll(offsets(value, n, little));
                        break;
                    case TAG_EXIF_IFD:
                        next.add(value);
                        break;
                    case TAG_JPEG_OFFSET:
                        jpegOffset = value;
                        break;
                    case TAG_JPEG_LENGTH:
                        jpegLength = value;
                        break;
                    case TAG_STRIP_OFFSETS:
                        stripOffset = n == 1 ? value : -1;
                        break;
                    case TAG_STRIP_BYTE_COUNTS:
                        stripLength = n == 1 ? value : -1;
                        break;
                    case TAG_COMPRESSION:
                        compression = (int) value;
                        break;
                    case TAG_MAKER_NOTE:
                        olympus(value, n);
                        break;
                    default:
                        if (type == TYPE_IFD) {
                            next.add(value);
                        } else if (type == TYPE_UNDEFINED && n >= MIN_JPEG) {
                            // Panasonic keeps a whole JPEG as one tag's bytes.
                            consider(value, n);
                        }
                        break;
                }
            }
            if (jpegOffset > 0 && jpegLength > 0) {
                consider(jpegOffset, jpegLength);
            }
            if (stripOffset > 0 && stripLength > 0 && (compression == 6 || compression == 7
                    || compression == 99)) {
                consider(stripOffset, stripLength);
            }
            if (depth < 4) {
                for (long child : next) {
                    walk(child, little, depth + 1);
                }
            }
            ifd = entries.length >= count * 12 + 4 ? u32(entries, count * 12, little) : 0;
        }
    }

    /** The offsets in an array of them, or the one offset given inline. */
    @NonNull
    private List<Long> offsets(long value, long count, boolean little) throws IOException {
        List<Long> offsets = new ArrayList<>();
        if (count <= 1) {
            offsets.add(value);
            return offsets;
        }
        byte[] array = read(value, (int) Math.min(count, MAX_IFDS) * 4);
        for (int i = 0; i + 4 <= array.length; i += 4) {
            offsets.add(u32(array, i, little));
        }
        return offsets;
    }

    /**
     * An Olympus maker note, whose camera settings say where the preview is, counted from the
     * note's own start.
     */
    private void olympus(long note, long length) throws IOException {
        byte[] head = read(note, 16);
        if (head.length < 16 || !startsWith(head, 0, "OLYMPUS")) {
            return;
        }
        boolean little = head[8] == 'I';
        byte[] settings = ifdEntry(note + 12, little, TAG_OLYMPUS_CAMERA_SETTINGS);
        if (settings == null) {
            return;
        }
        long base = note + u32(settings, 8, little);
        byte[] start = ifdEntry(base, little, TAG_OLYMPUS_PREVIEW_START);
        byte[] size = ifdEntry(base, little, TAG_OLYMPUS_PREVIEW_LENGTH);
        if (start != null && size != null) {
            consider(note + u32(start, 8, little), u32(size, 8, little));
        }
    }

    /** One tag's 12-byte entry in an IFD, or null. */
    @Nullable
    private byte[] ifdEntry(long ifd, boolean little, int tag) throws IOException {
        byte[] countBytes = read(ifd, 2);
        if (countBytes.length < 2) {
            return null;
        }
        int count = u16(countBytes, 0, little);
        byte[] entries = read(ifd + 2, count * 12);
        for (int i = 0; i + 12 <= entries.length; i += 12) {
            if (u16(entries, i, little) == tag) {
                byte[] entry = new byte[12];
                System.arraycopy(entries, i, entry, 0, 12);
                return entry;
            }
        }
        return null;
    }

    /**
     * ISO boxes, as Canon's CR3 is: each track's first sample, one of which is the full-size
     * JPEG, and the PRVW preview in Canon's uuid box.
     */
    private void searchBoxes(long start, long end, int depth) throws IOException {
        long at = start;
        while (at + 8 <= end) {
            byte[] header = read(at, 16);
            long size = u32(header, 0, false);
            String type = new String(header, 4, 4, ASCII);
            int headerSize = 8;
            if (size == 1 && header.length >= 16) {
                size = u64(header, 8);
                headerSize = 16;
            } else if (size == 0) {
                size = end - at;
            }
            if (size < headerSize || at + size > end) {
                return;
            }
            if (depth < 6 && ("moov".equals(type) || "trak".equals(type)
                    || "mdia".equals(type) || "minf".equals(type) || "stbl".equals(type))) {
                if ("trak".equals(type)) {
                    firstSample(at + headerSize, at + size);
                } else {
                    searchBoxes(at + headerSize, at + size, depth + 1);
                }
            } else if ("uuid".equals(type) && size > headerSize + 16 + 8) {
                // Canon's preview box: the uuid, eight bytes of its own, then PRVW.
                searchPreviewBox(at + headerSize + 16, at + size);
            }
            at += size;
        }
    }

    /** The first sample of a track, from its chunk offsets and sample sizes. */
    private void firstSample(long start, long end) throws IOException {
        long[] chunk = {-1};
        long[] sample = {-1};
        findSampleTables(start, end, chunk, sample, 0);
        if (chunk[0] > 0 && sample[0] > 0) {
            consider(chunk[0], sample[0]);
        }
    }

    private void findSampleTables(long start, long end, @NonNull long[] chunk,
            @NonNull long[] sample, int depth) throws IOException {
        long at = start;
        while (at + 8 <= end) {
            byte[] header = read(at, 24);
            long size = u32(header, 0, false);
            String type = new String(header, 4, 4, ASCII);
            if (size < 8 || at + size > end) {
                return;
            }
            if (depth < 4 && ("mdia".equals(type) || "minf".equals(type)
                    || "stbl".equals(type))) {
                findSampleTables(at + 8, at + size, chunk, sample, depth + 1);
            } else if ("stco".equals(type) && header.length >= 20 && u32(header, 12, false) > 0) {
                chunk[0] = u32(header, 16, false);
            } else if ("co64".equals(type) && header.length >= 24
                    && u32(header, 12, false) > 0) {
                chunk[0] = u64(header, 16);
            } else if ("stsz".equals(type) && header.length >= 20) {
                long fixed = u32(header, 12, false);
                if (fixed > 0) {
                    sample[0] = fixed;
                } else if (u32(header, 16, false) > 0) {
                    byte[] first = read(at + 20, 4);
                    sample[0] = first.length == 4 ? u32(first, 0, false) : -1;
                }
            }
            at += size;
        }
    }

    /** A PRVW box among those in Canon's uuid box. */
    private void searchPreviewBox(long start, long end) throws IOException {
        long at = start;
        while (at + 8 <= end) {
            byte[] header = read(at, 8);
            long size = u32(header, 0, false);
            String type = new String(header, 4, 4, ASCII);
            if (size < 8 || at + size > end) {
                // The box's own eight bytes come first; step past them once.
                at += 8;
                continue;
            }
            if ("PRVW".equals(type) && size > 8 + 16) {
                // Version, flags and dimensions, then the JPEG's length and the JPEG.
                byte[] fields = read(at + 8, 16);
                long length = u32(fields, 12, false);
                consider(at + 8 + 16, length);
                return;
            }
            at += size;
        }
    }

    /** Keeps a candidate that really is a JPEG inside the file, with its size if readable. */
    private void consider(long offset, long length) throws IOException {
        if (offset <= 0 || length < MIN_JPEG || offset + length > mLength) {
            return;
        }
        byte[] header = read(offset, (int) Math.min(length, MAX_HEADER));
        if (header.length < 4 || (header[0] & 0xFF) != 0xFF || (header[1] & 0xFF) != 0xD8
                || (header[2] & 0xFF) != 0xFF) {
            return;
        }
        int[] size = jpegSize(header);
        if (size != null) {
            mFound.add(new Found(offset, length, size[0], size[1]));
        }
    }

    /**
     * A JPEG's width and height from its frame header, zeros if it has none in reach, or null
     * for a lossless JPEG: the raw sensor data some cameras keep, which no Android decoder reads.
     */
    @VisibleForTesting
    @Nullable
    static int[] jpegSize(@NonNull byte[] jpeg) {
        int at = 2;
        while (at + 4 <= jpeg.length) {
            if ((jpeg[at] & 0xFF) != 0xFF) {
                return new int[2];
            }
            int marker = jpeg[at + 1] & 0xFF;
            if (marker == 0xFF) {
                at++;
                continue;
            }
            int length = ((jpeg[at + 2] & 0xFF) << 8) | (jpeg[at + 3] & 0xFF);
            boolean frame = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4
                    && marker != 0xC8 && marker != 0xCC;
            if (frame && (marker & 0x03) == 0x03) {
                return null;
            }
            if (frame && at + 9 <= jpeg.length) {
                int height = ((jpeg[at + 5] & 0xFF) << 8) | (jpeg[at + 6] & 0xFF);
                int width = ((jpeg[at + 7] & 0xFF) << 8) | (jpeg[at + 8] & 0xFF);
                return new int[] {width, height};
            }
            if (marker == 0xDA || length < 2) {
                return new int[2];
            }
            at += 2 + length;
        }
        return new int[2];
    }

    /** Up to a number of bytes at a place, fewer at the end of the file. */
    @NonNull
    private byte[] read(long position, int count) throws IOException {
        if (position < 0 || position >= mLength || count <= 0) {
            return new byte[0];
        }
        int available = (int) Math.min(count, mLength - position);
        ByteBuffer buffer = ByteBuffer.allocate(available);
        while (buffer.hasRemaining()) {
            int read = mChannel.read(buffer, position + buffer.position());
            if (read < 0) {
                break;
            }
        }
        byte[] bytes = new byte[buffer.position()];
        System.arraycopy(buffer.array(), 0, bytes, 0, bytes.length);
        return bytes;
    }

    private static boolean startsWith(@NonNull byte[] data, int at, @NonNull String text) {
        byte[] ascii = text.getBytes(ASCII);
        if (at + ascii.length > data.length) {
            return false;
        }
        for (int i = 0; i < ascii.length; i++) {
            if (data[at + i] != ascii[i]) {
                return false;
            }
        }
        return true;
    }

    private static int u16(@NonNull byte[] data, int at, boolean little) {
        if (at + 2 > data.length) {
            return 0;
        }
        int a = data[at] & 0xFF;
        int b = data[at + 1] & 0xFF;
        return little ? a | (b << 8) : (a << 8) | b;
    }

    private static long u32(@NonNull byte[] data, int at, boolean little) {
        if (at + 4 > data.length) {
            return 0;
        }
        long value = 0;
        for (int i = 0; i < 4; i++) {
            int b = data[at + (little ? 3 - i : i)] & 0xFF;
            value = (value << 8) | b;
        }
        return value;
    }

    private static long u64(@NonNull byte[] data, int at) {
        return (u32(data, at, false) << 32) | u32(data, at + 4, false);
    }
}
