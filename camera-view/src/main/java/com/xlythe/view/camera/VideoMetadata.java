package com.xlythe.view.camera;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads and writes the text fields an MP4 carries in its user data: QuickTime's {@code ©}-prefixed
 * boxes inside {@code moov/udta}, such as the device that made a recording, when, and where.
 *
 * <pre>{@code
 * VideoMetadata.write(file, new VideoMetadata.Builder()
 *         .set(VideoMetadata.Tag.TITLE, "Birthday")
 *         .set(VideoMetadata.Tag.COPYRIGHT, "2026 Example Films"));
 * String title = VideoMetadata.read(file, VideoMetadata.Tag.TITLE);
 * }</pre>
 *
 * <p>A write merges into the user data already there, replacing a field it sets again. Only
 * {@code moov} changes size, by growing into or giving back to the {@code free} box after it, or
 * by changing the file's length when it is the last box, so {@code mdat} stays where it is and
 * every chunk offset still points at its sample. Without that room nothing is written.</p>
 */
public final class VideoMetadata {
    private static final String TAG = VideoMetadata.class.getSimpleName();

    /** The copyright sign that begins QuickTime's user-data text keys. */
    private static final int COPYRIGHT_SIGN = 0xA9;

    private static final String BOX_MOOV = "moov";
    private static final String BOX_FREE = "free";
    private static final String BOX_SKIP = "skip";
    private static final String BOX_UDTA = "udta";

    /** Eight bytes of box header: a 32-bit size and a four-character type. */
    private static final int HEADER = 8;

    /** A text field's two-byte length and two-byte language code, ahead of its text. */
    private static final int TEXT_PREFIX = 4;

    /** "und", the packed ISO 639-2 code for no particular language. */
    private static final int LANGUAGE_UNDETERMINED = 0x55C4;

    /** The largest moov this reads into memory: far more than any recording's metadata. */
    private static final long MAX_MOOV_BYTES = 64L * 1024 * 1024;

    /** A user-data text field, named for what QuickTime defines it to hold. */
    public enum Tag {
        ARRANGER("arg"),
        ARRANGER_KEYWORDS("ark"),
        COMPOSER_KEYWORDS("cok"),
        COMPOSER("com"),
        COPYRIGHT("cpy"),
        /** When the content was created, in ISO 8601. */
        CREATION_DATE("day"),
        DIRECTOR("dir"),
        EDIT_1("ed1"),
        EDIT_2("ed2"),
        EDIT_3("ed3"),
        EDIT_4("ed4"),
        EDIT_5("ed5"),
        EDIT_6("ed6"),
        EDIT_7("ed7"),
        EDIT_8("ed8"),
        EDIT_9("ed9"),
        /** How the movie was made, such as computer-generated or digitised. */
        FORMAT("fmt"),
        INFORMATION("inf"),
        ISRC("isr"),
        RECORD_LABEL("lab"),
        RECORD_LABEL_URL("lal"),
        /** The manufacturer of the device that made the file. */
        MAKE("mak"),
        MAKE_URL("mal"),
        /** The model of the device that made the file. */
        MODEL("mod"),
        TITLE_KEYWORDS("nak"),
        TITLE("nam"),
        PRODUCER_KEYWORDS("pdk"),
        /** The recording copyright, usually written after a ℗. */
        PHONOGRAM_COPYRIGHT("phg"),
        PRODUCER("prd"),
        PERFORMERS("prf"),
        PERFORMER_KEYWORDS("prk"),
        PERFORMER_URL("prl"),
        /** Hardware or software needed to play the file. */
        REQUIREMENTS("req"),
        SUBTITLE_KEYWORDS("snk"),
        SUBTITLE("snm"),
        SOURCE_CREDITS("src"),
        SONGWRITER("swf"),
        SONGWRITER_KEYWORDS("swk"),
        /** The software, and its version, that made the file. */
        SOFTWARE("swr"),
        WRITER("wrt"),
        /** Where the content was recorded, in ISO 6709. See {@link Builder#setLocation}. */
        LOCATION("xyz");

        /** The three characters after the ©. */
        private final String mKey;

        Tag(String key) {
            mKey = key;
        }
    }

    /** The fields to write, applied together by {@link #write}. */
    public static final class Builder {
        /** Each tag's new value, in the order set; a tag set twice keeps the last value. */
        private final Map<Tag, String> mFields = new LinkedHashMap<>();

        /** Sets a field. Null or empty leaves it as it is in the file. */
        @NonNull
        public Builder set(@NonNull Tag tag, @Nullable String value) {
            if (value != null && !value.isEmpty()) {
                mFields.put(tag, value);
            }
            return this;
        }

        /**
         * Sets {@link Tag#LOCATION} from a latitude and longitude, to four decimal places. From
         * doubles, because the floats {@code MediaRecorder.setLocation} takes lose about a metre.
         */
        @NonNull
        public Builder setLocation(double latitude, double longitude) {
            return set(Tag.LOCATION, iso6709(latitude, longitude));
        }

        @NonNull
        @VisibleForTesting
        Map<Tag, String> fields() {
            return mFields;
        }
    }

    private VideoMetadata() {}

    /**
     * Reads a field from a recording.
     *
     * @return its text, or null when the file has no such field or is not an MP4
     */
    @Nullable
    public static String read(@NonNull File file, @NonNull Tag tag) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long[] moov = findMoov(raf);
            if (moov == null) {
                return null;
            }
            byte[] udta = findChild(readBox(raf, moov[0], moov[1]), BOX_UDTA);
            if (udta == null) {
                return null;
            }
            byte[] field = findChild(udta, keyOf(tag));
            return field == null ? null : textOf(field);
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "Unable to read " + tag + " from " + file, error);
            return null;
        }
    }

    /**
     * Writes fields into a recording, in place: a recording can be hundreds of megabytes, and
     * only its moov changes.
     *
     * @return true if the fields were written
     */
    public static boolean write(@NonNull File file, @NonNull Builder builder) {
        if (builder.fields().isEmpty()) {
            return false;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            long[] moovBox = findMoov(raf);
            if (moovBox == null) {
                return false;
            }
            long moovAt = moovBox[0];
            long moovSize = moovBox[1];
            byte[] moov = withUserData(readBox(raf, moovAt, moovSize), builder.fields());
            if (moov == null) {
                return false;
            }
            long grows = moov.length - moovSize;
            long after = moovAt + moovSize;
            long length = raf.length();

            if (after == length) {
                // The last box: the file simply changes length, and nothing follows to move.
                raf.seek(moovAt);
                raf.write(moov);
                raf.setLength(moovAt + moov.length);
                return true;
            }

            if (after + HEADER > length) {
                return false;
            }
            byte[] header = new byte[HEADER];
            raf.seek(after);
            raf.readFully(header);
            String type = type(header, 4);
            if (!BOX_FREE.equals(type) && !BOX_SKIP.equals(type)) {
                // No padding to grow into without moving everything after moov.
                return false;
            }
            long freeLeft = readInt(header, 0) - grows;
            // A box cannot be smaller than its header, unless it goes altogether.
            if (freeLeft != 0 && freeLeft < HEADER) {
                return false;
            }
            raf.seek(moovAt);
            raf.write(moov);
            if (freeLeft > 0) {
                byte[] free = new byte[HEADER];
                writeInt(free, 0, (int) freeLeft);
                writeType(free, 4, BOX_FREE);
                raf.write(free);
            }
            return true;
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "Unable to write metadata into " + file, error);
            return false;
        }
    }

    /** Where the top-level moov is, as {offset, size}, or null if there is none to read. */
    @Nullable
    private static long[] findMoov(@NonNull RandomAccessFile raf) throws IOException {
        long length = raf.length();
        long at = 0;
        byte[] header = new byte[HEADER];
        while (at + HEADER <= length) {
            raf.seek(at);
            raf.readFully(header);
            long size = readInt(header, 0);
            if (size < HEADER || at + size > length) {
                return null;
            }
            if (BOX_MOOV.equals(type(header, 4))) {
                return size > MAX_MOOV_BYTES ? null : new long[] {at, size};
            }
            at += size;
        }
        return null;
    }

    @NonNull
    private static byte[] readBox(@NonNull RandomAccessFile raf, long at, long size)
            throws IOException {
        byte[] box = new byte[(int) size];
        raf.seek(at);
        raf.readFully(box);
        return box;
    }

    /**
     * The moov with these fields merged into its user data: a field already there is replaced
     * where it stands, and the rest are added. Null when the moov's children cannot be walked.
     */
    @VisibleForTesting
    @Nullable
    static byte[] withUserData(@NonNull byte[] moov, @NonNull Map<Tag, String> fields) {
        List<byte[]> children = children(moov);
        if (children == null) {
            return null;
        }
        int udtaIndex = -1;
        for (int i = 0; i < children.size(); i++) {
            if (BOX_UDTA.equals(type(children.get(i), 4))) {
                udtaIndex = i;
                break;
            }
        }
        List<byte[]> userData = new ArrayList<>();
        if (udtaIndex >= 0) {
            List<byte[]> existing = children(children.get(udtaIndex));
            if (existing == null) {
                return null;
            }
            userData.addAll(existing);
        }
        Map<Tag, String> remaining = new LinkedHashMap<>(fields);
        for (int i = 0; i < userData.size(); i++) {
            for (Tag tag : Tag.values()) {
                if (remaining.containsKey(tag) && keyOf(tag).equals(type(userData.get(i), 4))) {
                    userData.set(i, textBox(tag, remaining.remove(tag)));
                }
            }
        }
        for (Map.Entry<Tag, String> field : remaining.entrySet()) {
            userData.add(textBox(field.getKey(), field.getValue()));
        }
        byte[] udta = box(BOX_UDTA, userData);
        if (udtaIndex >= 0) {
            children.set(udtaIndex, udta);
        } else {
            children.add(udta);
        }
        return box(BOX_MOOV, children);
    }

    /** A container's child boxes, or null if they do not fill it exactly. */
    @Nullable
    private static List<byte[]> children(@NonNull byte[] container) {
        List<byte[]> children = new ArrayList<>();
        int at = HEADER;
        while (at < container.length) {
            if (at + HEADER > container.length) {
                return null;
            }
            long size = readInt(container, at);
            if (size < HEADER || at + size > container.length) {
                return null;
            }
            byte[] child = new byte[(int) size];
            System.arraycopy(container, at, child, 0, child.length);
            children.add(child);
            at += size;
        }
        return children;
    }

    /** The first child of a container with this type, or null. */
    @Nullable
    private static byte[] findChild(@NonNull byte[] container, @NonNull String type) {
        List<byte[]> children = children(container);
        if (children == null) {
            return null;
        }
        for (byte[] child : children) {
            if (type.equals(type(child, 4))) {
                return child;
            }
        }
        return null;
    }

    @NonNull
    private static byte[] box(@NonNull String type, @NonNull List<byte[]> children) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] child : children) {
            body.write(child, 0, child.length);
        }
        byte[] inner = body.toByteArray();
        byte[] box = new byte[HEADER + inner.length];
        writeInt(box, 0, box.length);
        writeType(box, 4, type);
        System.arraycopy(inner, 0, box, HEADER, inner.length);
        return box;
    }

    /** One text field: its length and language, then UTF-8. */
    @VisibleForTesting
    @NonNull
    static byte[] textBox(@NonNull Tag tag, @NonNull String value) {
        byte[] text = value.getBytes(StandardCharsets.UTF_8);
        byte[] box = new byte[HEADER + TEXT_PREFIX + text.length];
        writeInt(box, 0, box.length);
        writeType(box, 4, keyOf(tag));
        box[8] = (byte) ((text.length >> 8) & 0xFF);
        box[9] = (byte) (text.length & 0xFF);
        box[10] = (byte) ((LANGUAGE_UNDETERMINED >> 8) & 0xFF);
        box[11] = (byte) (LANGUAGE_UNDETERMINED & 0xFF);
        System.arraycopy(text, 0, box, HEADER + TEXT_PREFIX, text.length);
        return box;
    }

    /** A text field's text, or null if it is malformed. */
    @Nullable
    private static String textOf(@NonNull byte[] box) {
        if (box.length < HEADER + TEXT_PREFIX) {
            return null;
        }
        int length = ((box[8] & 0xFF) << 8) | (box[9] & 0xFF);
        if (HEADER + TEXT_PREFIX + length > box.length) {
            return null;
        }
        return new String(box, HEADER + TEXT_PREFIX, length, StandardCharsets.UTF_8);
    }

    /** The box type for a tag: the copyright sign and its three characters. */
    @NonNull
    private static String keyOf(@NonNull Tag tag) {
        return (char) COPYRIGHT_SIGN + tag.mKey;
    }

    /** ISO 6709 as QuickTime writes it: signed, to four decimal places, ending in a solidus. */
    @VisibleForTesting
    @NonNull
    static String iso6709(double latitude, double longitude) {
        return String.format(Locale.US, "%+08.4f%+09.4f/", latitude, longitude);
    }

    private static long readInt(@NonNull byte[] data, int at) {
        return ((long) (data[at] & 0xFF) << 24) | ((data[at + 1] & 0xFF) << 16)
                | ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
    }

    /** Four bytes as characters, each byte its own code point, as box types are compared. */
    @NonNull
    private static String type(@NonNull byte[] data, int at) {
        return "" + (char) (data[at] & 0xFF) + (char) (data[at + 1] & 0xFF)
                + (char) (data[at + 2] & 0xFF) + (char) (data[at + 3] & 0xFF);
    }

    private static void writeInt(@NonNull byte[] out, int at, int value) {
        out[at] = (byte) ((value >> 24) & 0xFF);
        out[at + 1] = (byte) ((value >> 16) & 0xFF);
        out[at + 2] = (byte) ((value >> 8) & 0xFF);
        out[at + 3] = (byte) (value & 0xFF);
    }

    /** Writes a four-character type, the © as its single byte. */
    private static void writeType(@NonNull byte[] out, int at, @NonNull String type) {
        for (int i = 0; i < 4; i++) {
            out[at + i] = (byte) type.charAt(i);
        }
    }
}
