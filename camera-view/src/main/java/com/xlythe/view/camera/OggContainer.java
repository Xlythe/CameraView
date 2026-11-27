package com.xlythe.view.camera;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * An Ogg file of Vorbis, Opus or FLAC: Vorbis comments in its comment header packet, and the
 * cover as a picture entry among them. Ogg keeps no XMP.
 *
 * <p>Changing the comments means new header pages, so every page after them is numbered again
 * and its checksum worked out again; the audio itself is copied as it is.</p>
 */
final class OggContainer extends MediaContainer {
    /** The codecs this tells apart. */
    private static final int VORBIS = 1;
    private static final int OPUS = 2;
    private static final int FLAC = 3;
    private static final int OTHER = 0;

    /** A page that begins a stream. */
    private static final int BEGINNING = 0x02;

    /** A page that continues a packet from the one before. */
    private static final int CONTINUED = 0x01;

    /** The most pages this reads looking for the header packets. */
    private static final int MAX_HEADER_PAGES = 1024;

    /** Ogg's CRC-32: polynomial 0x04C11DB7, not reflected, starting from zero. */
    private static final int[] CRC_TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int crc = i << 24;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x80000000) != 0 ? (crc << 1) ^ 0x04C11DB7 : crc << 1;
            }
            CRC_TABLE[i] = crc;
        }
    }

    /** One page's place in the file. */
    @VisibleForTesting
    static final class Page {
        final long offset;
        final int type;
        final long serial;
        final long sequence;
        /** How many bytes its header takes, segment table included. */
        final int header;
        /** Its segment table. */
        @NonNull
        final int[] lacing;

        Page(long offset, int type, long serial, long sequence, @NonNull int[] lacing) {
            this.offset = offset;
            this.type = type;
            this.serial = serial;
            this.sequence = sequence;
            this.header = 27 + lacing.length;
            this.lacing = lacing;
        }

        int dataLength() {
            int length = 0;
            for (int value : lacing) {
                length += value;
            }
            return length;
        }

        long end() {
            return offset + header + dataLength();
        }
    }

    /** Whether the file can be written. */
    private final boolean mWritable;

    /** The codec. */
    private final int mCodec;

    /** The header packets, as read. */
    @NonNull
    private final List<byte[]> mHeaders;

    /** Which header packet holds the comments, or -1. */
    private final int mCommentIndex;

    /** How many pages the header packets take. */
    private final int mHeaderPages;

    /** The comments, as read or as changed. */
    @NonNull
    private final VorbisComments mComments;

    /** An empty packet: Ogg keeps none. */
    private final Xmp mXmp = Xmp.empty();

    private OggContainer(boolean writable, int codec, @NonNull List<byte[]> headers,
            int commentIndex, int headerPages, @NonNull VorbisComments comments) {
        mWritable = writable;
        mCodec = codec;
        mHeaders = headers;
        mCommentIndex = commentIndex;
        mHeaderPages = headerPages;
        mComments = comments;
    }

    @WorkerThread
    @NonNull
    static OggContainer read(@NonNull ByteSource source, boolean writable) throws IOException {
        List<byte[]> packets = new ArrayList<>();
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        int codec = OTHER;
        int expected = Integer.MAX_VALUE;
        long serial = -1;
        int pages = 0;
        boolean mixed = false;
        long at = 0;
        while (packets.size() < expected && pages < MAX_HEADER_PAGES) {
            Page page = page(source, at);
            if (page == null) {
                break;
            }
            at = page.end();
            if (serial == -1) {
                serial = page.serial;
            } else if (page.serial != serial) {
                // Another stream's page, interleaved.
                continue;
            }
            pages++;
            byte[] data = source.bytes(page.offset + page.header, page.dataLength());
            int position = 0;
            for (int i = 0; i < page.lacing.length; i++) {
                int value = page.lacing[i];
                packet.write(data, position, value);
                position += value;
                if (value < 255) {
                    packets.add(packet.toByteArray());
                    packet.reset();
                    if (packets.size() == 1) {
                        byte[] first = packets.get(0);
                        codec = codecOf(first);
                        expected = headerCount(codec, first);
                    }
                    if (packets.size() >= expected) {
                        // Audio sharing the last header page would be lost in a rewrite.
                        mixed = i + 1 < page.lacing.length;
                        break;
                    }
                }
            }
        }
        int commentIndex = -1;
        VorbisComments comments = null;
        for (int i = 1; i < packets.size() && comments == null; i++) {
            int offset = commentOffset(codec, packets.get(i));
            if (offset >= 0) {
                comments = VorbisComments.parse(packets.get(i), offset);
                commentIndex = comments == null ? -1 : i;
            }
        }
        boolean complete = packets.size() == expected && commentIndex >= 0 && packet.size() == 0
                && !mixed;
        return new OggContainer(writable && complete, codec, packets, commentIndex, pages,
                comments == null ? VorbisComments.empty() : comments);
    }

    /** The page at an offset, or null if there is none. */
    @Nullable
    private static Page page(@NonNull ByteSource source, long at) throws IOException {
        byte[] header = source.bytesUpTo(at, 27);
        if (header.length < 27 || !Bytes.startsWith(header, 0, "OggS")) {
            return null;
        }
        int segments = Bytes.u8(header, 26);
        byte[] table = source.bytesUpTo(at + 27, segments);
        if (table.length < segments) {
            return null;
        }
        int[] lacing = new int[segments];
        for (int i = 0; i < segments; i++) {
            lacing[i] = Bytes.u8(table, i);
        }
        Page page = new Page(at, Bytes.u8(header, 5), Bytes.u32le(header, 14),
                Bytes.u32le(header, 18), lacing);
        return page.end() <= source.length() ? page : null;
    }

    private static int codecOf(@NonNull byte[] first) {
        if (Bytes.startsWith(first, 0, "\u0001vorbis")) {
            return VORBIS;
        }
        if (Bytes.startsWith(first, 0, "OpusHead")) {
            return OPUS;
        }
        if (Bytes.startsWith(first, 0, "\u007FFLAC")) {
            return FLAC;
        }
        return OTHER;
    }

    /** How many header packets a stream of a codec has. */
    private static int headerCount(int codec, @NonNull byte[] first) {
        switch (codec) {
            case VORBIS:
                return 3;
            case OPUS:
                return 2;
            case FLAC: {
                int count = first.length >= 9 ? Bytes.u16(first, 7) : 0;
                return count == 0 ? 2 : 1 + count;
            }
            default:
                return 2;
        }
    }

    /** Where the comments start in a header packet, or -1 if it is not the comment packet. */
    private static int commentOffset(int codec, @NonNull byte[] packet) {
        switch (codec) {
            case VORBIS:
                return Bytes.startsWith(packet, 0, "\u0003vorbis") ? 7 : -1;
            case OPUS:
                return Bytes.startsWith(packet, 0, "OpusTags") ? 8 : -1;
            case FLAC:
                return packet.length >= 4 && (packet[0] & 0x7F) == 4 ? 4 : -1;
            default:
                return -1;
        }
    }

    @Override
    int format() {
        return mCodec == OPUS ? MediaTags.FORMAT_OPUS : MediaTags.FORMAT_OGG;
    }

    @Override
    int kind() {
        return mCodec == OTHER ? MediaTags.KIND_UNKNOWN : MediaTags.KIND_AUDIO;
    }

    @Override
    boolean keeps(@Field int field) {
        return mWritable && hasNativeHome(field);
    }

    @Override
    boolean hasNativeHome(@Field int field) {
        return mCodec != OTHER && (VorbisComments.nameOf(field) != null
                || field == CAPTURE_TIME || field == COVER_ART);
    }

    @Nullable
    @Override
    String text(@Field int field) {
        return mComments.field(field);
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
        return mComments.picture();
    }

    @Override
    boolean setCoverArt(@Nullable byte[] image) {
        byte[] block = image == null ? null : VorbisComments.pictureBlock(image);
        if (image != null && block == null) {
            return false;
        }
        mComments.setPicture(block);
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
        List<byte[]> headers = new ArrayList<>(mHeaders);
        byte[] old = headers.get(mCommentIndex);
        byte[] comments = mComments.toBytes();
        byte[] packet;
        switch (mCodec) {
            case VORBIS:
                // The framing bit Vorbis ends its comment header with.
                packet = Bytes.concat(Arrays.copyOf(old, 7), comments, new byte[] {1});
                break;
            case OPUS:
                packet = Bytes.concat(Arrays.copyOf(old, 8), comments);
                break;
            default: {
                byte[] header = new byte[4];
                header[0] = old[0];
                Bytes.put24(header, 1, comments.length);
                packet = Bytes.concat(header, comments);
            }
        }
        headers.set(mCommentIndex, packet);
        try (ByteSource source = ByteSource.of(file)) {
            List<Page> pages = new ArrayList<>();
            long at = 0;
            long serial = -1;
            Page page;
            while ((page = page(source, at)) != null) {
                if (serial == -1) {
                    serial = page.serial;
                } else if (page.serial != serial) {
                    throw new IOException("An Ogg file of several streams cannot be rewritten");
                }
                pages.add(page);
                at = page.end();
            }
            if (pages.size() < mHeaderPages) {
                throw new IOException("The Ogg file changed since it was read");
            }
            final long streamSerial = serial;
            final List<Page> all = pages;
            MediaFiles.replace(file, out -> {
                List<byte[]> written = headerPages(headers, streamSerial);
                for (byte[] bytes : written) {
                    out.write(bytes);
                }
                long shift = written.size() - mHeaderPages;
                for (int i = mHeaderPages; i < all.size(); i++) {
                    copyPage(source, all.get(i), shift, out);
                }
                MediaFiles.copy(source, all.get(all.size() - 1).end(), source.length(), out);
            });
        }
        saved();
    }

    /** The header packets as pages: the first alone, then the rest packed together. */
    @VisibleForTesting
    @NonNull
    static List<byte[]> headerPages(@NonNull List<byte[]> headers, long serial) {
        List<byte[]> pages = new ArrayList<>();
        pages.add(page(BEGINNING, 0, serial, 0, Arrays.asList(headers.get(0)), false));
        List<Integer> lacing = new ArrayList<>();
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        boolean continued = false;
        for (int p = 1; p < headers.size(); p++) {
            byte[] packet = headers.get(p);
            int at = 0;
            while (true) {
                int segment = Math.min(255, packet.length - at);
                lacing.add(segment);
                data.write(packet, at, segment);
                at += segment;
                boolean ended = segment < 255;
                if (lacing.size() == 255) {
                    pages.add(rawPage(continued ? CONTINUED : 0, serial, pages.size(), lacing,
                            data.toByteArray()));
                    lacing.clear();
                    data.reset();
                    continued = !ended;
                }
                if (ended) {
                    break;
                }
            }
        }
        if (!lacing.isEmpty()) {
            pages.add(rawPage(continued ? CONTINUED : 0, serial, pages.size(), lacing,
                    data.toByteArray()));
        }
        return pages;
    }

    /** A page holding whole packets. */
    @NonNull
    private static byte[] page(int type, long granule, long serial, long sequence,
            @NonNull List<byte[]> packets, boolean continued) {
        List<Integer> lacing = new ArrayList<>();
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (byte[] packet : packets) {
            int length = packet.length;
            while (length >= 255) {
                lacing.add(255);
                length -= 255;
            }
            lacing.add(length);
            data.write(packet, 0, packet.length);
        }
        return rawPage(type | (continued ? CONTINUED : 0), serial, sequence, lacing,
                data.toByteArray());
    }

    /** A page from its segment table and data, with a granule position of zero. */
    @NonNull
    private static byte[] rawPage(int type, long serial, long sequence,
            @NonNull List<Integer> lacing, @NonNull byte[] data) {
        byte[] page = new byte[27 + lacing.size() + data.length];
        System.arraycopy(Bytes.fourCc("OggS"), 0, page, 0, 4);
        page[5] = (byte) type;
        Bytes.put32le(page, 14, serial);
        Bytes.put32le(page, 18, sequence);
        page[26] = (byte) lacing.size();
        for (int i = 0; i < lacing.size(); i++) {
            page[27 + i] = (byte) (int) lacing.get(i);
        }
        System.arraycopy(data, 0, page, 27 + lacing.size(), data.length);
        Bytes.put32le(page, 22, crc(page));
        return page;
    }

    /** Copies a page, numbered further along by a shift, with its checksum worked out again. */
    private static void copyPage(@NonNull ByteSource source, @NonNull Page page, long shift,
            @NonNull OutputStream out) throws IOException {
        byte[] bytes = source.bytes(page.offset, page.end() - page.offset);
        if (shift != 0) {
            Bytes.put32le(bytes, 18, page.sequence + shift);
            Bytes.put32le(bytes, 22, 0);
            Bytes.put32le(bytes, 22, crc(bytes));
        }
        out.write(bytes);
    }

    /** A page's checksum, with its own checksum field taken as zero. */
    @VisibleForTesting
    static long crc(@NonNull byte[] page) {
        int crc = 0;
        for (int i = 0; i < page.length; i++) {
            int value = i >= 22 && i < 26 ? 0 : page[i] & 0xFF;
            crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ value) & 0xFF];
        }
        return crc & 0xFFFFFFFFL;
    }
}
