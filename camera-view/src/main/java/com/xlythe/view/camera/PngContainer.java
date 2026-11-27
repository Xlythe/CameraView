package com.xlythe.view.camera;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A PNG: its own text keywords for the title, author, description, copyright and software,
 * Exif in its eXIf chunk for the rest, and XMP in an iTXt chunk.
 *
 * <p>New chunks go before the image data, where readers that stop at it still find them.</p>
 */
final class PngContainer extends ExifImageContainer {
    private static final String TAG = "MediaTags";

    /** Every PNG starts with these eight bytes. */
    static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    /** The keyword XMP is stored under. */
    static final String XMP_KEYWORD = "XML:com.adobe.xmp";

    /** The keyword PNG gives the time the image was made. */
    static final String CREATION_TIME = "Creation Time";

    /** What ImageMagick's hexadecimal profile keywords start with. */
    static final String RAW_PROFILE = "Raw profile type ";

    /** The most a compressed chunk is allowed to grow to when it is read. */
    private static final int MAX_INFLATED = 16 * 1024 * 1024;

    /** How PNG recommends writing a creation time: RFC 1123. */
    private static final String RFC_1123 = "EEE, dd MMM yyyy HH:mm:ss z";

    /** A chunk's place in the file. */
    private static final class Chunk {
        /** Its type. */
        @NonNull
        final String type;

        /** Where its length field is. */
        final long offset;

        /** How many bytes of data it holds. */
        final long length;

        Chunk(@NonNull String type, long offset, long length) {
            this.type = type;
            this.offset = offset;
            this.length = length;
        }

        long data() {
            return offset + 8;
        }

        long end() {
            return offset + 12 + length;
        }
    }

    /** The bare TIFF block of the eXIf chunk, or null. */
    @Nullable
    private final byte[] mTiff;

    /** Each text keyword, as read or as changed since; null marks one removed. */
    @NonNull
    private final Map<String, String> mText;

    /** The keywords changed since the file was read. */
    @NonNull
    private final Map<String, String> mChangedText = new LinkedHashMap<>();

    /** The packet, as read or as changed since. */
    @NonNull
    private final Xmp mXmp;

    /** Whether the packet has changed. */
    private boolean mXmpDirty;

    private PngContainer(@NonNull ByteSource source, @Nullable byte[] tiff,
            @NonNull Map<String, String> text, @NonNull Xmp xmp, boolean writable)
            throws IOException {
        super(writable);
        mTiff = tiff;
        mText = text;
        mXmp = xmp;
        setReadExif(tiff == null ? null : readTiff(tiff));
    }

    @WorkerThread
    @NonNull
    static PngContainer read(@NonNull ByteSource source, boolean writable) throws IOException {
        byte[] tiff = null;
        Map<String, String> text = new LinkedHashMap<>();
        Xmp xmp = null;
        for (Chunk chunk : chunks(source)) {
            if ("eXIf".equals(chunk.type) && tiff == null) {
                tiff = tiffOf(source.bytes(chunk.data(), chunk.length));
            } else if ("tEXt".equals(chunk.type) || "zTXt".equals(chunk.type)
                    || "iTXt".equals(chunk.type)) {
                if (chunk.length > MAX_INFLATED) {
                    continue;
                }
                String[] entry = readText(chunk.type, source.bytes(chunk.data(), chunk.length));
                if (entry == null) {
                    continue;
                }
                if (XMP_KEYWORD.equals(entry[0])) {
                    if (xmp == null) {
                        xmp = Xmp.parse(entry[1]);
                    }
                } else if (entry[0].startsWith(RAW_PROFILE)) {
                    // ImageMagick's older way to keep a profile, as hexadecimal text.
                    byte[] profile = rawProfile(entry[1]);
                    String kind = entry[0].substring(RAW_PROFILE.length());
                    if (profile != null && tiff == null && !"xmp".equalsIgnoreCase(kind)) {
                        tiff = tiffOf(profile);
                    } else if (profile != null && xmp == null && "xmp".equalsIgnoreCase(kind)) {
                        xmp = Xmp.parse(profile);
                    }
                } else if (!text.containsKey(entry[0])) {
                    text.put(entry[0], entry[1]);
                }
            }
        }
        return new PngContainer(source, tiff, text, xmp == null ? Xmp.empty() : xmp, writable);
    }

    /** The chunks, in order, up to the end chunk or the end of the file. */
    @NonNull
    private static List<Chunk> chunks(@NonNull ByteSource source) throws IOException {
        byte[] signature = source.bytesUpTo(0, SIGNATURE.length);
        if (!Bytes.startsWith(signature, 0, SIGNATURE)) {
            throw new IOException("Not a PNG");
        }
        List<Chunk> chunks = new ArrayList<>();
        long at = SIGNATURE.length;
        byte[] header = new byte[8];
        while (at + 12 <= source.length()) {
            source.readFully(at, header, 0, 8);
            long length = Bytes.u32(header, 0);
            String type = Bytes.fourCc(header, 4);
            if (at + 12 + length > source.length()) {
                break;
            }
            chunks.add(new Chunk(type, at, length));
            at += 12 + length;
            if ("IEND".equals(type)) {
                break;
            }
        }
        return chunks;
    }

    /**
     * A text chunk's keyword and text, uncompressed.
     *
     * @return the pair, or null if the chunk is malformed or compressed beyond what is read
     */
    @VisibleForTesting
    @Nullable
    static String[] readText(@NonNull String type, @NonNull byte[] data) {
        int keywordEnd = Bytes.terminator(data, 0, data.length);
        if (keywordEnd == 0 || keywordEnd >= data.length) {
            return null;
        }
        String keyword = new String(data, 0, keywordEnd, Bytes.LATIN_1);
        int at = keywordEnd + 1;
        try {
            switch (type) {
                case "tEXt":
                    return new String[] {keyword,
                            new String(data, at, data.length - at, Bytes.LATIN_1)};
                case "zTXt":
                    if (at >= data.length || data[at] != 0) {
                        return null;
                    }
                    return new String[] {keyword, new String(inflate(data, at + 1,
                            data.length - at - 1), Bytes.LATIN_1)};
                default: {
                    if (at + 2 > data.length) {
                        return null;
                    }
                    boolean compressed = data[at] != 0;
                    at += 2;
                    int language = Bytes.terminator(data, at, data.length);
                    int translated = Bytes.terminator(data, language + 1, data.length);
                    at = translated + 1;
                    if (at > data.length) {
                        return null;
                    }
                    byte[] text = compressed ? inflate(data, at, data.length - at)
                            : Arrays.copyOfRange(data, at, data.length);
                    return new String[] {keyword, new String(text, Bytes.UTF_8)};
                }
            }
        } catch (DataFormatException malformed) {
            Log.w(TAG, "Unable to read a compressed PNG text chunk", malformed);
            return null;
        }
    }

    /**
     * A profile ImageMagick wrote as text: its name and length on lines of their own, then the
     * bytes in hexadecimal across as many lines as they take.
     *
     * @return the bytes, or null if the text is not one
     */
    @VisibleForTesting
    @Nullable
    static byte[] rawProfile(@NonNull String text) {
        String[] lines = text.trim().split("\\s*\\n\\s*", 3);
        if (lines.length < 3) {
            return null;
        }
        int length;
        try {
            length = Integer.parseInt(lines[1].trim());
        } catch (NumberFormatException malformed) {
            return null;
        }
        if (length <= 0 || length > MAX_INFLATED) {
            return null;
        }
        byte[] bytes = new byte[length];
        int count = 0;
        int high = -1;
        String hex = lines[2];
        for (int i = 0; i < hex.length() && count < length; i++) {
            int digit = Character.digit(hex.charAt(i), 16);
            if (digit < 0) {
                continue;
            }
            if (high < 0) {
                high = digit;
            } else {
                bytes[count++] = (byte) ((high << 4) | digit);
                high = -1;
            }
        }
        return count == length ? bytes : null;
    }

    @NonNull
    private static byte[] inflate(@NonNull byte[] data, int offset, int length)
            throws DataFormatException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data, offset, length);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                out.write(buffer, 0, count);
                if (out.size() > MAX_INFLATED) {
                    throw new DataFormatException("Too large");
                }
            }
            return out.toByteArray();
        } finally {
            inflater.end();
        }
    }

    @Override
    int format() {
        return MediaTags.FORMAT_PNG;
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

    @Override
    boolean keeps(@Field int field) {
        if (keyword(field) != null) {
            return isWritable();
        }
        return super.keeps(field);
    }

    @Override
    boolean hasNativeHome(@Field int field) {
        return keyword(field) != null || super.hasNativeHome(field);
    }

    /** The PNG keyword a field is kept under, or null for one kept in Exif. */
    @Nullable
    static String keyword(@Field int field) {
        switch (field) {
            case TITLE:
                return "Title";
            case AUTHOR:
                return "Author";
            case DESCRIPTION:
                return "Description";
            case COPYRIGHT:
                return "Copyright";
            case SOFTWARE:
                return "Software";
            default:
                return null;
        }
    }

    @Nullable
    @Override
    String text(@Field int field) {
        String keyword = keyword(field);
        String value = keyword == null ? null : mText.get(keyword);
        return value != null && !value.trim().isEmpty() ? value.trim() : super.text(field);
    }

    @Override
    boolean setText(@Field int field, @Nullable String value) throws IOException {
        String keyword = keyword(field);
        if (keyword == null) {
            return super.setText(field, value);
        }
        putText(keyword, value);
        // Exif may keep the same field; it says the same, or nothing if it cannot.
        if (super.text(field) != null && canWriteExif() && !super.setText(field, value)) {
            super.setText(field, null);
        }
        return true;
    }

    private void putText(@NonNull String keyword, @Nullable String value) {
        mText.put(keyword, value);
        mChangedText.put(keyword, value);
        markDirty();
    }

    @Override
    long captureTime() {
        long time = super.captureTime();
        if (time != -1) {
            return time;
        }
        return parseCreationTime(mText.get(CREATION_TIME));
    }

    @Override
    void setCaptureTime(long millis) throws IOException {
        super.setCaptureTime(millis);
        if (mText.get(CREATION_TIME) != null) {
            putText(CREATION_TIME, millis < 0 ? null : formatCreationTime(millis));
        }
    }

    /** A creation time as PNG recommends writing it. */
    @VisibleForTesting
    @NonNull
    static String formatCreationTime(long millis) {
        SimpleDateFormat format = new SimpleDateFormat(RFC_1123, Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        return format.format(millis);
    }

    /** A creation time in RFC 1123, as PNG recommends, or ISO 8601, or -1. */
    @VisibleForTesting
    static long parseCreationTime(@Nullable String text) {
        if (text == null) {
            return -1;
        }
        try {
            SimpleDateFormat format = new SimpleDateFormat(RFC_1123, Locale.US);
            return format.parse(text.trim()).getTime();
        } catch (ParseException notRfc1123) {
            return MediaTags.parseIsoTime(text);
        }
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
        final byte[] tiff = saveWrappedExif();
        final byte[] packet = mXmpDirty ? (mXmp.isEmpty() ? new byte[0] : mXmp.toBytes()) : null;
        final Map<String, String> text = new LinkedHashMap<>(mChangedText);
        try (ByteSource source = ByteSource.of(file)) {
            final List<Chunk> chunks = chunks(source);
            MediaFiles.replace(file, out -> {
                out.write(SIGNATURE);
                boolean inserted = false;
                for (Chunk chunk : chunks) {
                    if (!inserted && ("IDAT".equals(chunk.type) || "IEND".equals(chunk.type))) {
                        writeNew(out, tiff, packet, text);
                        inserted = true;
                    }
                    if (!replaced(source, chunk, tiff != null, packet != null, text)) {
                        MediaFiles.copy(source, chunk.offset, chunk.end(), out);
                    }
                }
                if (!inserted) {
                    writeNew(out, tiff, packet, text);
                }
            });
        }
        mChangedText.clear();
        mXmpDirty = false;
        saved();
    }

    /** Whether a chunk is one the new ones replace, and so is left out. */
    private static boolean replaced(@NonNull ByteSource source, @NonNull Chunk chunk,
            boolean exif, boolean xmp, @NonNull Map<String, String> text) throws IOException {
        if ("eXIf".equals(chunk.type)) {
            return exif;
        }
        if (!"tEXt".equals(chunk.type) && !"zTXt".equals(chunk.type)
                && !"iTXt".equals(chunk.type)) {
            return false;
        }
        byte[] head = source.bytesUpTo(chunk.data(), (int) Math.min(chunk.length, 80));
        String keyword = new String(head, 0, Bytes.terminator(head, 0, head.length),
                Bytes.LATIN_1);
        if (keyword.startsWith(RAW_PROFILE)) {
            return "xmp".equalsIgnoreCase(keyword.substring(RAW_PROFILE.length())) ? xmp : exif;
        }
        return XMP_KEYWORD.equals(keyword) ? xmp : text.containsKey(keyword);
    }

    private static void writeNew(@NonNull OutputStream out, @Nullable byte[] tiff,
            @Nullable byte[] packet, @NonNull Map<String, String> text) throws IOException {
        if (tiff != null) {
            writeChunk(out, "eXIf", tiff);
        }
        if (packet != null && packet.length > 0) {
            writeChunk(out, "iTXt", textChunk(XMP_KEYWORD, new String(packet, Bytes.UTF_8),
                    true));
        }
        for (Map.Entry<String, String> entry : text.entrySet()) {
            if (entry.getValue() != null) {
                boolean latin = Bytes.isLatin1(entry.getValue());
                writeChunk(out, latin ? "tEXt" : "iTXt",
                        textChunk(entry.getKey(), entry.getValue(), !latin));
            }
        }
    }

    /** A text chunk's data: plain Latin-1, or uncompressed UTF-8 with no language. */
    @VisibleForTesting
    @NonNull
    static byte[] textChunk(@NonNull String keyword, @NonNull String text, boolean international) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        byte[] name = keyword.getBytes(Bytes.LATIN_1);
        data.write(name, 0, name.length);
        data.write(0);
        byte[] value;
        if (international) {
            // No compression, no language and no translated keyword.
            data.write(0);
            data.write(0);
            data.write(0);
            data.write(0);
            value = text.getBytes(Bytes.UTF_8);
        } else {
            value = text.getBytes(Bytes.LATIN_1);
        }
        data.write(value, 0, value.length);
        return data.toByteArray();
    }

    /** Writes a chunk: its length, type, data and CRC. */
    static void writeChunk(@NonNull OutputStream out, @NonNull String type, @NonNull byte[] data)
            throws IOException {
        out.write(chunk(type, data));
    }

    /** A chunk as bytes. */
    @NonNull
    static byte[] chunk(@NonNull String type, @NonNull byte[] data) {
        byte[] chunk = new byte[12 + data.length];
        Bytes.put32(chunk, 0, data.length);
        byte[] name = Bytes.fourCc(type);
        System.arraycopy(name, 0, chunk, 4, 4);
        System.arraycopy(data, 0, chunk, 8, data.length);
        CRC32 crc = new CRC32();
        crc.update(chunk, 4, 4 + data.length);
        Bytes.put32(chunk, 8 + data.length, crc.getValue());
        return chunk;
    }
}
