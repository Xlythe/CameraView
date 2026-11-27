package com.xlythe.view.camera;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * A WAV: RIFF's INFO list for the title (INAM), artist (IART), comment (ICMT), copyright
 * (ICOP), creation date (ICRD) and software (ISFT), and XMP in a {@code _PMX} chunk for the
 * rest. A Broadcast Wave's bext chunk and an ID3 chunk are read too.
 *
 * <p>The audio never moves: the old INFO and XMP chunks become JUNK where they are, or are cut
 * off when nothing follows them, and the new ones go at the end. An RF64 file, whose sizes live
 * in a chunk of their own, is read but not written.</p>
 */
final class WavContainer extends MediaContainer {
    /** The INFO ids for each field. */
    private static final String TITLE_ID = "INAM";
    private static final String AUTHOR_ID = "IART";
    private static final String COMMENT_ID = "ICMT";
    private static final String COPYRIGHT_ID = "ICOP";
    private static final String DATE_ID = "ICRD";
    private static final String SOFTWARE_ID = "ISFT";

    /** The most of a chunk this reads. */
    private static final long MAX_CHUNK = 16 * 1024 * 1024;

    /** A chunk's place in the file. */
    private static final class Chunk {
        @NonNull
        final String id;

        /** Where its header is. */
        final long offset;

        /** How many bytes of data it holds, before any padding byte. */
        final long length;

        /** For a LIST, its list type, such as "INFO"; otherwise null. */
        @Nullable
        final String listType;

        Chunk(@NonNull String id, long offset, long length, @Nullable String listType) {
            this.id = id;
            this.offset = offset;
            this.length = length;
            this.listType = listType;
        }

        long end() {
            return offset + 8 + length + (length & 1);
        }

        /** Whether a save replaces this chunk. */
        boolean isReplaced() {
            return "_PMX".equals(id) || ("LIST".equals(id) && "INFO".equals(listType));
        }
    }

    /** Whether the file can be written. */
    private final boolean mWritable;

    /** The INFO entries, as read or as changed since; a null value marks one removed. */
    @NonNull
    private final Map<String, String> mInfo;

    /** The Broadcast Wave description and origination time, or null. */
    @Nullable
    private final String[] mBext;

    /** Frames of an ID3 chunk, or empty. */
    @NonNull
    private final List<Id3Container.Frame> mId3;

    /** That chunk's ID3 version. */
    private final int mId3Version;

    /** The packet, as read or as changed. */
    @NonNull
    private final Xmp mXmp;

    private WavContainer(boolean writable, @NonNull Map<String, String> info,
            @Nullable String[] bext, @NonNull List<Id3Container.Frame> id3, int id3Version,
            @NonNull Xmp xmp) {
        mWritable = writable;
        mInfo = info;
        mBext = bext;
        mId3 = id3;
        mId3Version = id3Version;
        mXmp = xmp;
    }

    @WorkerThread
    @NonNull
    static WavContainer read(@NonNull ByteSource source, boolean writable) throws IOException {
        byte[] header = source.bytesUpTo(0, 12);
        boolean rf64 = !Bytes.startsWith(header, 0, "RIFF");
        Map<String, String> info = new LinkedHashMap<>();
        String[] bext = null;
        List<Id3Container.Frame> id3 = new ArrayList<>();
        int id3Version = 0;
        Xmp xmp = null;
        for (Chunk chunk : chunks(source)) {
            if (chunk.length > MAX_CHUNK) {
                continue;
            }
            if ("LIST".equals(chunk.id) && "INFO".equals(chunk.listType)) {
                readInfo(source.bytes(chunk.offset + 12, chunk.length - 4), info);
            } else if ("_PMX".equals(chunk.id) && xmp == null) {
                xmp = Xmp.parse(source.bytes(chunk.offset + 8, chunk.length));
            } else if ("bext".equals(chunk.id) && chunk.length >= 346) {
                byte[] data = source.bytes(chunk.offset + 8, 346);
                bext = new String[] {latin(data, 0, 256), latin(data, 320, 10) + "T"
                        + latin(data, 330, 8).replace('-', ':')};
            } else if (("id3 ".equals(chunk.id) || "ID3 ".equals(chunk.id)) && id3.isEmpty()) {
                byte[] tag = source.bytes(chunk.offset + 8, chunk.length);
                if (Id3Container.tagSize(tag) > 0 && tag.length > 10) {
                    id3Version = tag[3];
                    id3 = Id3Container.frames(tag, Arrays.copyOfRange(tag, 10,
                            (int) Math.min(tag.length, Id3Container.tagSize(tag))));
                }
            }
        }
        return new WavContainer(writable && !rf64, info, bext, id3, id3Version,
                xmp == null ? Xmp.empty() : xmp);
    }

    @NonNull
    private static String latin(@NonNull byte[] data, int at, int length) {
        return new String(data, at, Bytes.terminator(data, at, at + length) - at,
                Bytes.LATIN_1).trim();
    }

    /** The chunks after the RIFF header. */
    @NonNull
    private static List<Chunk> chunks(@NonNull ByteSource source) throws IOException {
        List<Chunk> chunks = new ArrayList<>();
        long at = 12;
        byte[] header = new byte[12];
        while (at + 8 <= source.length()) {
            int read = source.read(at, header, 0, 12);
            String id = Bytes.fourCc(header, 0);
            long length = Bytes.u32le(header, 4);
            if ("data".equals(id) && at + 8 + length > source.length()) {
                // A recording cut short, or one that never had its size written: the audio runs
                // to the end.
                length = source.length() - at - 8;
            }
            if (at + 8 + length > source.length()) {
                break;
            }
            chunks.add(new Chunk(id, at, length, read >= 12 && "LIST".equals(id) && length >= 4
                    ? Bytes.fourCc(header, 8) : null));
            at += 8 + length + (length & 1);
        }
        return chunks;
    }

    /** The entries of an INFO list's data, after its type. */
    private static void readInfo(@NonNull byte[] data, @NonNull Map<String, String> info) {
        int at = 0;
        while (at + 8 <= data.length) {
            String id = Bytes.fourCc(data, at);
            long length = Bytes.u32le(data, at + 4);
            if (at + 8 + length > data.length) {
                break;
            }
            String value = new String(data, at + 8, Bytes.terminator(data, at + 8,
                    (int) (at + 8 + length)) - at - 8, Bytes.UTF_8).trim();
            if (!value.isEmpty() && !info.containsKey(id)) {
                info.put(id, value);
            }
            at += 8 + length + (length & 1);
        }
    }

    @Override
    int format() {
        return MediaTags.FORMAT_WAV;
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
        return infoId(field) != null || field == CAPTURE_TIME;
    }

    @Nullable
    private static String infoId(@Field int field) {
        switch (field) {
            case TITLE:
                return TITLE_ID;
            case AUTHOR:
                return AUTHOR_ID;
            case DESCRIPTION:
                return COMMENT_ID;
            case COPYRIGHT:
                return COPYRIGHT_ID;
            case SOFTWARE:
                return SOFTWARE_ID;
            default:
                return null;
        }
    }

    @Nullable
    @Override
    String text(@Field int field) {
        String id = infoId(field);
        String value = id == null ? null : mInfo.get(id);
        if (value == null && field == DESCRIPTION && mBext != null && !mBext[0].isEmpty()) {
            value = mBext[0];
        }
        if (value == null) {
            value = Id3Container.field(mId3, mId3Version, field);
        }
        return value;
    }

    @Nullable
    @Override
    byte[] coverArt() {
        return Id3Container.cover(mId3, mId3Version);
    }

    @Override
    boolean setText(@Field int field, @Nullable String value) {
        String id = infoId(field);
        if (id == null) {
            return false;
        }
        mInfo.put(id, value);
        markDirty();
        return true;
    }

    @Override
    long captureTime() {
        long time = MediaTags.parseIsoTime(mInfo.get(DATE_ID));
        if (time == -1 && mBext != null) {
            time = MediaTags.parseIsoTime(mBext[1].replace("T", "T").replaceFirst(
                    "T(\\d{2}):(\\d{2}):(\\d{2})$", "T$1:$2:$3"));
        }
        return time;
    }

    /** {@inheritDoc} ICRD keeps only the date. */
    @Override
    void setCaptureTime(long millis) {
        mInfo.put(DATE_ID, millis < 0 ? null : date(millis));
        markDirty();
    }

    @VisibleForTesting
    @NonNull
    static String date(long millis) {
        Calendar calendar = Calendar.getInstance(TimeZone.getDefault(),
                Locale.US);
        calendar.setTimeInMillis(millis);
        return String.format(Locale.US, "%1$tY-%1$tm-%1$td", calendar);
    }

    @NonNull
    @Override
    Xmp xmp() {
        return mXmp;
    }

    @Override
    int maxXmpBytes() {
        return mWritable ? Integer.MAX_VALUE : NO_XMP;
    }

    @Override
    void save(@NonNull File file) throws IOException {
        byte[] info = infoChunk(mInfo);
        byte[] pmx = mXmp.isEmpty() ? null : chunk("_PMX", mXmp.toBytes());
        try (ByteSource source = ByteSource.of(file)) {
            List<Chunk> chunks = chunks(source);
            // Everything from the first replaced chunk that only replaced chunks follow is cut
            // off; replaced chunks before that become JUNK.
            long cut = source.length();
            for (int i = chunks.size() - 1; i >= 0 && chunks.get(i).isReplaced(); i--) {
                cut = chunks.get(i).offset;
            }
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                for (Chunk chunk : chunks) {
                    if (chunk.isReplaced() && chunk.offset < cut) {
                        raf.seek(chunk.offset);
                        raf.write(Bytes.fourCc("JUNK"));
                    }
                }
                raf.setLength(cut);
                raf.seek(cut);
                if (info != null) {
                    raf.write(info);
                }
                if (pmx != null) {
                    raf.write(pmx);
                }
                byte[] size = new byte[4];
                Bytes.put32le(size, 0, raf.length() - 8);
                raf.seek(4);
                raf.write(size);
            }
        }
        saved();
    }

    /** A LIST INFO chunk of the entries, or null if there are none. */
    @Nullable
    private static byte[] infoChunk(@NonNull Map<String, String> info) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(Bytes.fourCc("INFO"), 0, 4);
        boolean any = false;
        for (Map.Entry<String, String> entry : info.entrySet()) {
            if (entry.getValue() != null) {
                byte[] text = Bytes.concat(entry.getValue().getBytes(Bytes.UTF_8), new byte[1]);
                byte[] entryChunk = chunk(entry.getKey(), text);
                data.write(entryChunk, 0, entryChunk.length);
                any = true;
            }
        }
        return any ? chunk("LIST", data.toByteArray()) : null;
    }

    /** A chunk: its id, its length, its data and a padding byte if it is odd. */
    @NonNull
    static byte[] chunk(@NonNull String id, @NonNull byte[] data) {
        byte[] chunk = new byte[8 + data.length + (data.length & 1)];
        System.arraycopy(Bytes.fourCc(id), 0, chunk, 0, 4);
        Bytes.put32le(chunk, 4, data.length);
        System.arraycopy(data, 0, chunk, 8, data.length);
        return chunk;
    }
}
