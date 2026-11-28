package com.xlythe.view.camera;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * An MP3, or bare AAC, with an ID3 tag at its start: version 2.2, 2.3 or 2.4 to read, and 2.3
 * or 2.4 to write, keeping the version the file has. A file with only the old ID3v1 tag at its
 * end has that read, and kept up to date as far as its 30 characters allow.
 *
 * <p>The title is TIT2, the artist TPE1, the description the first COMM, the copyright TCOP,
 * the software TSSE, the recording time TDRC (TYER, TDAT and TIME before 2.4) and the cover the
 * front cover APIC. XMP goes in a PRIV frame owned by "XMP", as Adobe's specification says.</p>
 *
 * <p>A new tag goes in the old one's place, padded to its size, when it fits; otherwise the file
 * is rewritten with room to spare for next time.</p>
 */
final class Id3Container extends MediaContainer {
    /** Room left in a rewritten tag, so the next change can be made in place. */
    private static final int PADDING = 1024;

    /** The owner of the PRIV frame XMP goes in. */
    private static final String XMP_OWNER = "XMP";

    /** The most of a tag this reads. */
    private static final int MAX_TAG = 64 * 1024 * 1024;

    /** The picture type of a front cover. */
    private static final int FRONT_COVER = 3;

    /** Version 2.2's three-character frames, as the four-character ones they became. */
    private static final Map<String, String> V22 = new HashMap<>();

    static {
        String[][] pairs = {{"TT2", "TIT2"}, {"TP1", "TPE1"}, {"TP2", "TPE2"}, {"TAL", "TALB"},
                {"TCR", "TCOP"}, {"TYE", "TYER"}, {"TDA", "TDAT"}, {"TIM", "TIME"},
                {"TSS", "TSSE"}, {"TEN", "TENC"}, {"TCO", "TCON"}, {"TRK", "TRCK"},
                {"COM", "COMM"}, {"PIC", "APIC"}, {"TCM", "TCOM"}, {"TXT", "TEXT"},
                {"ULT", "USLT"}, {"TPA", "TPOS"}, {"TBP", "TBPM"}, {"TXX", "TXXX"}};
        for (String[] pair : pairs) {
            V22.put(pair[0], pair[1]);
        }
    }

    /** One frame: its id, its flags as stored, and its content. */
    @VisibleForTesting
    static final class Frame {
        @NonNull
        final String id;

        /** The two flag bytes, as the file's version writes them. */
        final int flags;

        @NonNull
        final byte[] data;

        Frame(@NonNull String id, int flags, @NonNull byte[] data) {
            this.id = id;
            this.flags = flags;
            this.data = data;
        }

        /** Whether the content is compressed or encrypted, so it can be kept but not read. */
        boolean isOpaque(int version) {
            return version == 4 ? (flags & 0x000C) != 0 : (flags & 0x00C0) != 0;
        }
    }

    /** Where the file is read from. */
    @NonNull
    private final ByteSource mSource;

    /** Whether the file can be written. */
    private final boolean mWritable;

    /** MP3 or AAC. */
    private final int mFormat;

    /** The tag's major version: 2, 3 or 4, or 0 when the file has no ID3v2 tag. */
    private final int mVersion;

    /** How many bytes the tag takes at the start of the file, or 0. */
    private final long mTagSize;

    /** The frames, as read or as changed since. */
    @NonNull
    private final List<Frame> mFrames;

    /** The old tag at the end of the file, or null. */
    @Nullable
    private final byte[] mV1;

    /** The packet, as read or as changed. */
    @NonNull
    private final Xmp mXmp;

    private Id3Container(@NonNull ByteSource source, boolean writable, int format, int version,
            long tagSize, @NonNull List<Frame> frames, @Nullable byte[] v1, @NonNull Xmp xmp) {
        mSource = source;
        mWritable = writable;
        mFormat = format;
        mVersion = version;
        mTagSize = tagSize;
        mFrames = frames;
        mV1 = v1;
        mXmp = xmp;
    }

    /** How many bytes an ID3v2 tag at the start of a file takes, or 0 if there is none. */
    static long tagSize(@NonNull byte[] header) {
        if (header.length < 10 || !Bytes.startsWith(header, 0, "ID3")) {
            return 0;
        }
        long size = syncsafe(header, 6);
        boolean footer = header[3] == 4 && (header[5] & 0x10) != 0;
        return 10 + size + (footer ? 10 : 0);
    }

    @WorkerThread
    @NonNull
    static Id3Container read(@NonNull ByteSource source, boolean writable) throws IOException {
        byte[] header = source.bytesUpTo(0, 10);
        long tagSize = tagSize(header);
        int version = 0;
        List<Frame> frames = new ArrayList<>();
        if (tagSize > 0) {
            version = header[3];
            if (version < 2 || version > 4 || tagSize > MAX_TAG || tagSize > source.length()) {
                throw new IOException("An ID3 tag of a version or size this cannot read");
            }
            frames = frames(header, source.bytes(10, tagSize - 10 - footerSize(header)));
        }
        byte[] after = source.bytesUpTo(tagSize, 4);
        int format = after.length >= 2 && Bytes.u8(after, 0) == 0xFF
                && (Bytes.u8(after, 1) & 0xF6) == 0xF0 ? MediaTags.FORMAT_AAC
                : MediaTags.FORMAT_MP3;
        byte[] v1 = null;
        if (source.length() >= tagSize + 128) {
            byte[] tail = source.bytes(source.length() - 128, 128);
            if (Bytes.startsWith(tail, 0, "TAG")) {
                v1 = tail;
            }
        }
        Xmp xmp = null;
        for (Frame frame : frames) {
            if ("PRIV".equals(frame.id) && !frame.isOpaque(version)) {
                int end = Bytes.terminator(frame.data, 0, frame.data.length);
                if (XMP_OWNER.equals(new String(frame.data, 0, end, Bytes.LATIN_1))) {
                    xmp = Xmp.parse(Arrays.copyOfRange(frame.data, Math.min(end + 1,
                            frame.data.length), frame.data.length));
                    break;
                }
            }
        }
        return new Id3Container(source, writable, format, version, tagSize, frames, v1,
                xmp == null ? Xmp.empty() : xmp);
    }

    private static int footerSize(@NonNull byte[] header) {
        return header[3] == 4 && (header[5] & 0x10) != 0 ? 10 : 0;
    }

    /** A tag's frames, from its header and the bytes after it. */
    @VisibleForTesting
    @NonNull
    static List<Frame> frames(@NonNull byte[] header, @NonNull byte[] body) {
        int version = header[3];
        int flags = header[5] & 0xFF;
        byte[] data = body;
        if ((flags & 0x80) != 0 && version < 4) {
            data = resync(data);
        }
        int at = 0;
        if ((flags & 0x40) != 0 && version >= 3 && data.length >= 4) {
            // An extended header, which says nothing this needs.
            at = version == 4 ? (int) syncsafe(data, 0) : (int) Bytes.u32(data, 0) + 4;
        }
        List<Frame> frames = new ArrayList<>();
        int headerSize = version == 2 ? 6 : 10;
        while (at + headerSize <= data.length) {
            if (data[at] == 0) {
                // Padding.
                break;
            }
            String id;
            long size;
            int frameFlags = 0;
            if (version == 2) {
                id = new String(data, at, 3, Bytes.LATIN_1);
                size = Bytes.u24(data, at + 3);
            } else {
                id = new String(data, at, 4, Bytes.LATIN_1);
                size = version == 4 ? syncsafe(data, at + 4) : Bytes.u32(data, at + 4);
                frameFlags = Bytes.u16(data, at + 8);
            }
            if (size < 0 || at + headerSize + size > data.length) {
                break;
            }
            byte[] content = Arrays.copyOfRange(data, at + headerSize,
                    (int) (at + headerSize + size));
            if (version == 4 && ((frameFlags & 0x0002) != 0 || (flags & 0x80) != 0)
                    && (frameFlags & 0x000C) == 0) {
                content = resync(content);
                frameFlags &= ~0x0002;
            }
            if (version == 4 && (frameFlags & 0x0001) != 0 && (frameFlags & 0x000C) == 0
                    && content.length >= 4) {
                // A data length indicator ahead of the content.
                content = Arrays.copyOfRange(content, 4, content.length);
                frameFlags &= ~0x0001;
            }
            if (version == 2) {
                String mapped = V22.get(id);
                if (mapped == null) {
                    at += headerSize + size;
                    continue;
                }
                if ("PIC".equals(id)) {
                    content = pictureFromV22(content);
                }
                id = mapped;
            }
            frames.add(new Frame(id, frameFlags, content));
            at += headerSize + size;
        }
        return frames;
    }

    /** Undoes unsynchronisation: every 0xFF 0x00 back to 0xFF. */
    @VisibleForTesting
    @NonNull
    static byte[] resync(@NonNull byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        for (int i = 0; i < data.length; i++) {
            out.write(data[i]);
            if ((data[i] & 0xFF) == 0xFF && i + 1 < data.length && data[i + 1] == 0) {
                i++;
            }
        }
        return out.toByteArray();
    }

    /** A 2.2 picture, which names its format in three letters, as a 2.3 picture. */
    @NonNull
    private static byte[] pictureFromV22(@NonNull byte[] content) {
        if (content.length < 5) {
            return content;
        }
        String format = new String(content, 1, 3, Bytes.LATIN_1);
        String mime = "PNG".equalsIgnoreCase(format) ? "image/png" : "image/jpeg";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(content[0]);
        byte[] name = mime.getBytes(Bytes.LATIN_1);
        out.write(name, 0, name.length);
        out.write(0);
        out.write(content, 4, content.length - 4);
        return out.toByteArray();
    }

    /** Seven bits from each of four bytes, as ID3 writes sizes that avoid 0xFF. */
    static long syncsafe(@NonNull byte[] data, int at) {
        return ((long) (data[at] & 0x7F) << 21) | ((data[at + 1] & 0x7F) << 14)
                | ((data[at + 2] & 0x7F) << 7) | (data[at + 3] & 0x7F);
    }

    static void putSyncsafe(@NonNull byte[] out, int at, long value) {
        out[at] = (byte) ((value >> 21) & 0x7F);
        out[at + 1] = (byte) ((value >> 14) & 0x7F);
        out[at + 2] = (byte) ((value >> 7) & 0x7F);
        out[at + 3] = (byte) (value & 0x7F);
    }

    @Override
    int format() {
        return mFormat;
    }

    @Override
    int kind() {
        return MediaTags.KIND_AUDIO;
    }

    /** The version a changed tag is written in. */
    private int writeVersion() {
        return mVersion == 4 ? 4 : 3;
    }

    @Override
    boolean keeps(@Field int field) {
        switch (field) {
            case TITLE:
            case DESCRIPTION:
            case AUTHOR:
            case COPYRIGHT:
            case SOFTWARE:
            case CAPTURE_TIME:
            case COVER_ART:
                return mWritable;
            default:
                return false;
        }
    }

    @Override
    boolean hasNativeHome(@Field int field) {
        return field == TITLE || field == DESCRIPTION || field == AUTHOR || field == COPYRIGHT
                || field == SOFTWARE || field == CAPTURE_TIME || field == COVER_ART;
    }

    /** The text frame a field is kept in, or null. */
    @Nullable
    private static String frameId(@Field int field) {
        switch (field) {
            case TITLE:
                return "TIT2";
            case AUTHOR:
                return "TPE1";
            case COPYRIGHT:
                return "TCOP";
            case SOFTWARE:
                return "TSSE";
            default:
                return null;
        }
    }

    @Nullable
    private static Frame frame(@NonNull List<Frame> frames, int version, @NonNull String id) {
        for (Frame frame : frames) {
            if (id.equals(frame.id) && !frame.isOpaque(version)) {
                return frame;
            }
        }
        return null;
    }

    /** A text frame's first value, or null. */
    @Nullable
    private String textFrame(@NonNull String id) {
        return textFrame(mFrames, mVersion, id);
    }

    @Nullable
    private static String textFrame(@NonNull List<Frame> frames, int version,
            @NonNull String id) {
        Frame frame = frame(frames, version, id);
        if (frame == null || frame.data.length < 1) {
            return null;
        }
        String text = decode(frame.data[0], frame.data, 1, frame.data.length);
        int end = text.indexOf('\u0000');
        text = end >= 0 ? text.substring(0, end) : text;
        return text.trim().isEmpty() ? null : text.trim();
    }

    /** Text in one of ID3's four encodings. */
    @NonNull
    private static String decode(int encoding, @NonNull byte[] data, int from, int to) {
        if (from >= to) {
            return "";
        }
        return new String(data, from, to - from, charset(encoding));
    }

    @NonNull
    private static Charset charset(int encoding) {
        switch (encoding) {
            case 1:
                return Bytes.UTF_16;
            case 2:
                return Bytes.UTF_16BE;
            case 3:
                return Bytes.UTF_8;
            default:
                return Bytes.LATIN_1;
        }
    }

    /** Where a terminated string in an encoding ends, and so where the next part begins. */
    private static int skipString(int encoding, @NonNull byte[] data, int from) {
        if (encoding == 1 || encoding == 2) {
            for (int i = from; i + 1 < data.length; i += 2) {
                if (data[i] == 0 && data[i + 1] == 0) {
                    return i + 2;
                }
            }
            return data.length;
        }
        return Math.min(data.length, Bytes.terminator(data, from, data.length) + 1);
    }

    @Nullable
    @Override
    String text(@Field int field) {
        String value = field(mFrames, mVersion, field);
        return value != null ? value : v1Text(field);
    }

    /**
     * A field from a tag's frames, for this format and for the others that carry an ID3 tag.
     *
     * @return the text, or null
     */
    @Nullable
    static String field(@NonNull List<Frame> frames, int version, @Field int field) {
        String id = frameId(field);
        if (id != null) {
            return textFrame(frames, version, id);
        }
        if (field == DESCRIPTION) {
            Frame comment = frame(frames, version, "COMM");
            if (comment != null && comment.data.length >= 4) {
                int encoding = comment.data[0];
                int text = skipString(encoding, comment.data, 4);
                String value = decode(encoding, comment.data, text, comment.data.length).trim();
                int end = value.indexOf('\u0000');
                value = end >= 0 ? value.substring(0, end).trim() : value;
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    /** A field of the old tag, or null. */
    @Nullable
    private String v1Text(@Field int field) {
        if (mV1 == null) {
            return null;
        }
        int at;
        int length = 30;
        switch (field) {
            case TITLE:
                at = 3;
                break;
            case AUTHOR:
                at = 33;
                break;
            case DESCRIPTION:
                at = 97;
                // ID3v1.1 ends the comment early to hold a track number.
                length = mV1[125] == 0 && mV1[126] != 0 ? 28 : 30;
                break;
            default:
                return null;
        }
        String text = new String(mV1, at, Bytes.terminator(mV1, at, at + length) - at,
                Bytes.LATIN_1).trim();
        return text.isEmpty() ? null : text;
    }

    @Override
    boolean setText(@Field int field, @Nullable String value) {
        String id = frameId(field);
        if (id != null) {
            put(id, value == null ? null : textContent(value));
        } else {
            put("COMM", value == null ? null : commentContent(value));
        }
        return true;
    }

    /** A text frame's content: its encoding, then the text. */
    @NonNull
    private byte[] textContent(@NonNull String text) {
        int encoding = encodingFor(text);
        return Bytes.concat(new byte[] {(byte) encoding}, text.getBytes(charset(encoding)));
    }

    /** A COMM frame's content: encoding, language, an empty description, the text. */
    @NonNull
    private byte[] commentContent(@NonNull String text) {
        int encoding = encodingFor(text);
        byte[] terminator = encoding == 1 ? new byte[] {(byte) 0xFF, (byte) 0xFE, 0, 0}
                : encoding == 2 ? new byte[2] : new byte[1];
        return Bytes.concat(new byte[] {(byte) encoding, 'u', 'n', 'd'}, terminator,
                text.getBytes(charset(encoding)));
    }

    /** Latin-1 where it holds the text, and otherwise UTF-8 in 2.4 or UTF-16 before it. */
    private int encodingFor(@NonNull String text) {
        if (Bytes.isLatin1(text)) {
            return 0;
        }
        return writeVersion() == 4 ? 3 : 1;
    }

    /** Puts a frame in place of every frame of its id, or removes them for null. */
    private void put(@NonNull String id, @Nullable byte[] content) {
        int index = -1;
        for (int i = mFrames.size() - 1; i >= 0; i--) {
            if (id.equals(mFrames.get(i).id)) {
                mFrames.remove(i);
                index = i;
            }
        }
        if (content != null) {
            Frame frame = new Frame(id, 0, content);
            if (index >= 0) {
                mFrames.add(index, frame);
            } else {
                mFrames.add(frame);
            }
        }
        markDirty();
    }

    @Override
    long captureTime() {
        String recorded = textFrame("TDRC");
        long time = MediaTags.parseIsoTime(recorded);
        if (time != -1) {
            return time;
        }
        String year = textFrame("TYER");
        if (year == null && mV1 != null) {
            year = new String(mV1, 93, 4, Bytes.LATIN_1).trim();
        }
        if (year == null || year.length() != 4) {
            return -1;
        }
        String date = textFrame("TDAT");
        String clock = textFrame("TIME");
        StringBuilder iso = new StringBuilder(year);
        if (date != null && date.length() == 4) {
            iso.append('-').append(date, 2, 4).append('-').append(date, 0, 2);
            if (clock != null && clock.length() == 4) {
                iso.append('T').append(clock, 0, 2).append(':').append(clock, 2, 4);
            }
        }
        return MediaTags.parseIsoTime(iso.toString());
    }

    @Override
    void setCaptureTime(long millis) {
        if (writeVersion() == 4) {
            put("TDRC", millis < 0 ? null : textContent(localTime(millis,
                    "%1$tY-%1$tm-%1$tdT%1$tH:%1$tM:%1$tS")));
            return;
        }
        put("TYER", millis < 0 ? null : textContent(localTime(millis, "%1$tY")));
        put("TDAT", millis < 0 ? null : textContent(localTime(millis, "%1$td%1$tm")));
        put("TIME", millis < 0 ? null : textContent(localTime(millis, "%1$tH%1$tM")));
    }

    @NonNull
    private static String localTime(long millis, @NonNull String pattern) {
        Calendar calendar = Calendar.getInstance(TimeZone.getDefault(),
                Locale.US);
        calendar.setTimeInMillis(millis);
        return String.format(Locale.US, pattern, calendar);
    }

    @Nullable
    @Override
    byte[] coverArt() {
        return cover(mFrames, mVersion);
    }

    /** The front cover in a tag's frames, or its first picture, or null. */
    @Nullable
    static byte[] cover(@NonNull List<Frame> frames, int version) {
        byte[] first = null;
        for (Frame frame : frames) {
            if (!"APIC".equals(frame.id) || frame.isOpaque(version) || frame.data.length < 4) {
                continue;
            }
            int encoding = frame.data[0];
            int type = Bytes.terminator(frame.data, 1, frame.data.length) + 1;
            if (type >= frame.data.length) {
                continue;
            }
            int picture = skipString(encoding, frame.data, type + 1);
            byte[] image = Arrays.copyOfRange(frame.data, picture, frame.data.length);
            if (frame.data[type] == FRONT_COVER) {
                return image;
            }
            if (first == null) {
                first = image;
            }
        }
        return first;
    }

    @Override
    boolean setCoverArt(@Nullable byte[] image) {
        if (image == null) {
            put("APIC", null);
            return true;
        }
        String mime = mimeOf(image);
        if (mime == null) {
            return false;
        }
        put("APIC", Bytes.concat(new byte[] {0}, mime.getBytes(Bytes.LATIN_1),
                new byte[] {0, FRONT_COVER, 0}, image));
        return true;
    }

    /** A picture's type from its first bytes: JPEG or PNG, or null for anything else. */
    @Nullable
    static String mimeOf(@NonNull byte[] image) {
        if (image.length >= 3 && Bytes.u8(image, 0) == 0xFF && Bytes.u8(image, 1) == 0xD8) {
            return "image/jpeg";
        }
        if (Bytes.startsWith(image, 0, PngContainer.SIGNATURE)) {
            return "image/png";
        }
        return null;
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
    void xmpChanged() {
        byte[] packet = mXmp.isEmpty() ? null : mXmp.toBytes();
        for (int i = mFrames.size() - 1; i >= 0; i--) {
            Frame frame = mFrames.get(i);
            if ("PRIV".equals(frame.id) && Bytes.startsWith(frame.data, 0, "XMP\u0000")) {
                mFrames.remove(i);
            }
        }
        if (packet != null) {
            mFrames.add(new Frame("PRIV", 0, Bytes.concat("XMP\u0000".getBytes(Bytes.LATIN_1),
                    packet)));
        }
        markDirty();
    }

    @Override
    void save(@NonNull File file) throws IOException {
        byte[] tag = tag(writeVersion(), mFrames, 0);
        long audioStart = mTagSize;
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            if (mTagSize > 0 && tag.length <= mTagSize) {
                // In place, padded out to the old tag's size, so the audio stays where it is.
                byte[] padded = tag(writeVersion(), mFrames, (int) (mTagSize - tag.length));
                raf.seek(0);
                raf.write(padded);
            } else {
                byte[] roomy = tag(writeVersion(), mFrames, PADDING);
                try (ByteSource source = ByteSource.of(file)) {
                    MediaFiles.replace(file, out -> {
                        out.write(roomy);
                        MediaFiles.copy(source, audioStart, source.length(), out);
                    });
                }
            }
        }
        if (mV1 != null) {
            writeV1(file);
        }
        saved();
    }

    /** A whole ID3v2 tag of frames, with some padding after them. */
    @VisibleForTesting
    @NonNull
    static byte[] tag(int version, @NonNull List<Frame> frames, int padding) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Frame frame : frames) {
            byte[] header = new byte[10];
            System.arraycopy(frame.id.getBytes(Bytes.LATIN_1), 0, header, 0, 4);
            if (version == 4) {
                putSyncsafe(header, 4, frame.data.length);
            } else {
                Bytes.put32(header, 4, frame.data.length);
            }
            Bytes.put16(header, 8, frame.flags);
            body.write(header, 0, header.length);
            body.write(frame.data, 0, frame.data.length);
        }
        byte[] header = new byte[10];
        header[0] = 'I';
        header[1] = 'D';
        header[2] = '3';
        header[3] = (byte) version;
        putSyncsafe(header, 6, body.size() + padding);
        return Bytes.concat(header, body.toByteArray(), new byte[padding]);
    }

    /** Brings the old tag at the end in line with the new one, as far as it can hold. */
    private void writeV1(@NonNull File file) throws IOException {
        byte[] v1 = mV1.clone();
        putV1(v1, 3, 30, text(TITLE));
        putV1(v1, 33, 30, text(AUTHOR));
        long time = captureTime();
        putV1(v1, 93, 4, time == -1 ? null : localTime(time, "%1$tY"));
        boolean track = v1[125] == 0 && v1[126] != 0;
        putV1(v1, 97, track ? 28 : 30, text(DESCRIPTION));
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.seek(raf.length() - 128);
            raf.write(v1);
        }
    }

    private static void putV1(@NonNull byte[] v1, int at, int length, @Nullable String text) {
        Arrays.fill(v1, at, at + length, (byte) 0);
        if (text != null) {
            byte[] bytes = text.getBytes(Bytes.LATIN_1);
            System.arraycopy(bytes, 0, v1, at, Math.min(length, bytes.length));
        }
    }
}
