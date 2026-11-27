package com.xlythe.view.camera;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads and writes the metadata an MP4 carries about itself, in both of the forms QuickTime
 * defines inside {@code moov}: the {@code ©}-prefixed user-data boxes in {@code udta}, and the
 * keyed metadata ({@code com.apple.quicktime.make} and the like) in {@code meta}, which many
 * players and galleries read instead.
 *
 * <pre>{@code
 * VideoMetadata.write(file, new VideoMetadata.Builder()
 *         .set(VideoMetadata.Tag.TITLE, "Birthday")
 *         .set(VideoMetadata.Tag.COPYRIGHT, "2026 Example Films"));
 * String title = VideoMetadata.read(file, VideoMetadata.Tag.TITLE);
 * }</pre>
 *
 * <p>A write merges into the metadata already there, replacing a field it sets again. Only
 * {@code moov} changes size, by growing into or giving back to the {@code free} box after it, or
 * by changing the file's length when it is the last box, so {@code mdat} stays where it is and
 * every chunk offset still points at its sample. Without that room nothing is written.</p>
 */
public final class VideoMetadata {
    private static final String TAG = VideoMetadata.class.getSimpleName();

    /** Text in the boxes. Looked up by name, since StandardCharsets arrived in API 19. */
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    /** The copyright sign that begins QuickTime's user-data text keys. */
    private static final int COPYRIGHT_SIGN = 0xA9;

    private static final String BOX_MOOV = "moov";
    private static final String BOX_FREE = "free";
    private static final String BOX_SKIP = "skip";
    private static final String BOX_UDTA = "udta";
    private static final String BOX_META = "meta";
    private static final String BOX_HDLR = "hdlr";
    private static final String BOX_KEYS = "keys";
    private static final String BOX_ILST = "ilst";
    private static final String BOX_DATA = "data";

    /** The handler a meta box of keyed metadata names, and the namespace of each of its keys. */
    private static final String MDTA = "mdta";

    /** Eight bytes of box header: a 32-bit size and a four-character type. */
    private static final int HEADER = 8;

    /** A text field's two-byte length and two-byte language code, ahead of its text. */
    private static final int TEXT_PREFIX = 4;

    /** A data box's type and locale, ahead of its value. */
    private static final int DATA_PREFIX = 8;

    /** "und", the packed ISO 639-2 code for no particular language. */
    private static final int LANGUAGE_UNDETERMINED = 0x55C4;

    /** The largest moov this reads into memory: far more than any recording's metadata. */
    private static final long MAX_MOOV_BYTES = 64L * 1024 * 1024;

    /** QuickTime's well-known data types, which say how a keyed value's bytes are read. */
    private static final int TYPE_UTF8 = 1;
    private static final int TYPE_JPEG = 13;
    private static final int TYPE_PNG = 14;
    private static final int TYPE_SIGNED = 21;
    private static final int TYPE_UNSIGNED = 22;
    private static final int TYPE_FLOAT32 = 23;
    private static final int TYPE_FLOAT64 = 24;
    private static final int TYPE_BMP = 27;
    private static final int TYPE_SIGNED_8 = 65;
    private static final int TYPE_SIGNED_16 = 66;
    private static final int TYPE_SIGNED_32 = 67;
    private static final int TYPE_SIGNED_64 = 74;
    private static final int TYPE_UNSIGNED_8 = 75;
    private static final int TYPE_UNSIGNED_16 = 76;
    private static final int TYPE_UNSIGNED_32 = 77;
    private static final int TYPE_UNSIGNED_64 = 78;

    /** The keyed-metadata key for {@link Builder#setArtwork}. */
    private static final String KEY_ARTWORK = "com.apple.quicktime.artwork";

    /**
     * A field, named for what QuickTime defines it to hold. Each is written in every form
     * QuickTime gives it: a user-data box, a keyed-metadata key, or both, and read from
     * whichever the file has, preferring the key.
     */
    public enum Tag {
        ARRANGER("arg"),
        ARRANGER_KEYWORDS("ark"),
        COMPOSER_KEYWORDS("cok"),
        COMPOSER("com"),
        COPYRIGHT("cpy", "com.apple.quicktime.copyright"),
        /** When the content was created, in ISO 8601. */
        CREATION_DATE("day", "com.apple.quicktime.creationdate"),
        DIRECTOR("dir", "com.apple.quicktime.director"),
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
        INFORMATION("inf", "com.apple.quicktime.information"),
        ISRC("isr"),
        RECORD_LABEL("lab"),
        RECORD_LABEL_URL("lal"),
        /** The manufacturer of the device that made the file. */
        MAKE("mak", "com.apple.quicktime.make"),
        MAKE_URL("mal"),
        /** The model of the device that made the file. */
        MODEL("mod", "com.apple.quicktime.model"),
        TITLE_KEYWORDS("nak"),
        TITLE("nam", "com.apple.quicktime.title"),
        PRODUCER_KEYWORDS("pdk"),
        /** The recording copyright, usually written after a ℗. */
        PHONOGRAM_COPYRIGHT("phg"),
        PRODUCER("prd", "com.apple.quicktime.producer"),
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
        SOFTWARE("swr", "com.apple.quicktime.software"),
        WRITER("wrt"),
        /** Where the content was recorded, in ISO 6709. See {@link Builder#setLocation}. */
        LOCATION("xyz", "com.apple.quicktime.location.ISO6709"),

        // The keys QuickTime defines no user-data box for.
        ALBUM(null, "com.apple.quicktime.album"),
        ARTIST(null, "com.apple.quicktime.artist"),
        AUTHOR(null, "com.apple.quicktime.author"),
        COMMENT(null, "com.apple.quicktime.comment"),
        DESCRIPTION(null, "com.apple.quicktime.description"),
        GENRE(null, "com.apple.quicktime.genre"),
        KEYWORDS(null, "com.apple.quicktime.keywords"),
        PUBLISHER(null, "com.apple.quicktime.publisher"),
        /** The year the content was created or recorded. */
        YEAR(null, "com.apple.quicktime.year"),
        /** A collection the user put the movie in. */
        COLLECTION(null, "com.apple.quicktime.collection.user"),
        /** The user's rating, from 0.0 to 5.0, where 0.0 is unrated. */
        RATING(null, "com.apple.quicktime.rating.user", TYPE_FLOAT32),
        /** The name of the {@link #LOCATION}, such as a city. */
        LOCATION_NAME(null, "com.apple.quicktime.location.name"),
        /** The astronomical body the {@link #LOCATION} is on; Earth when absent. */
        LOCATION_BODY(null, "com.apple.quicktime.location.body"),
        LOCATION_NOTE(null, "com.apple.quicktime.location.note"),
        /**
         * What the {@link #LOCATION} is: 0 where it was shot, 1 a real place it depicts, 2 a
         * fictional one.
         */
        LOCATION_ROLE(null, "com.apple.quicktime.location.role", TYPE_UNSIGNED),
        /** When the {@link #LOCATION} was measured, in ISO 8601. */
        LOCATION_DATE(null, "com.apple.quicktime.location.date"),
        /** Which way the camera faced, such as "+20.34M/-5.3" for 20.34° magnetic, 5.3° down. */
        DIRECTION_FACING(null, "com.apple.quicktime.direction.facing"),
        /** Which way the camera moved, written as {@link #DIRECTION_FACING} is. */
        DIRECTION_MOTION(null, "com.apple.quicktime.direction.motion"),

        // Keys Android's own recorder writes.
        /** The Android release that recorded the file. */
        ANDROID_VERSION(null, "com.android.version"),
        /** The frame rate a slow-motion recording was captured at, before it plays back slower. */
        CAPTURE_FPS(null, "com.android.capture.fps", TYPE_FLOAT32);

        /** The three characters after the ©, or null for a field with no user-data box. */
        @Nullable private final String mKey;

        /** The keyed-metadata key, or null for a field with only a user-data box. */
        @Nullable private final String mMetadataKey;

        /** How the keyed value is stored: text, or one of the number types. */
        private final int mMetadataType;

        Tag(@NonNull String key) {
            this(key, null, TYPE_UTF8);
        }

        Tag(@Nullable String key, @NonNull String metadataKey) {
            this(key, metadataKey, TYPE_UTF8);
        }

        Tag(@Nullable String key, @Nullable String metadataKey, int metadataType) {
            mKey = key;
            mMetadataKey = metadataKey;
            mMetadataType = metadataType;
        }

        /** The user-data box type, the copyright sign and three characters, or null. */
        @Nullable
        String userDataType() {
            return mKey == null ? null : keyOf(this);
        }

        /** The keyed-metadata key, or null. */
        @Nullable
        String metadataKey() {
            return mMetadataKey;
        }
    }

    /** The fields to write, applied together by {@link #write}. */
    public static final class Builder {
        /** Each tag's new value, in the order set; a tag set twice keeps the last value. */
        private final Map<Tag, String> mFields = new LinkedHashMap<>();

        /** The cover image, already encoded, or null to leave the file's as it is. */
        @Nullable private byte[] mArtwork;

        /**
         * Sets a field. Null or empty leaves it as it is in the file. A field that holds a
         * number, such as {@link Tag#RATING}, takes it as text.
         *
         * @throws IllegalArgumentException if the field holds a number and this is not one
         */
        @NonNull
        public Builder set(@NonNull Tag tag, @Nullable String value) {
            if (value != null && !value.isEmpty()) {
                // Encoded now, so a value that cannot be stored fails here rather than in write.
                metadataValue(tag, value);
                mFields.put(tag, value);
            }
            return this;
        }

        /**
         * Sets {@link Tag#LOCATION} from a latitude and longitude, to four decimal places. From
         * doubles, because the floats {@code MediaRecorder.setLocation} takes lose about a metre.
         *
         * @deprecated Use {@link MediaTags#setLocation}, which also writes the altitude and
         *     works on every format.
         */
        @Deprecated
        @NonNull
        public Builder setLocation(double latitude, double longitude) {
            return set(Tag.LOCATION, iso6709(latitude, longitude));
        }

        /**
         * Sets the image that stands for the movie, such as a poster, as an encoded JPEG, PNG or
         * BMP. Null leaves the file's as it is.
         *
         * @throws IllegalArgumentException if the image is none of those
         * @deprecated Use {@link MediaTags#setCoverArt}, which works on every format that
         *     keeps a cover picture.
         */
        @Deprecated
        @NonNull
        public Builder setArtwork(@Nullable byte[] image) {
            if (image != null) {
                imageType(image);
            }
            mArtwork = image;
            return this;
        }

        @NonNull
        @VisibleForTesting
        Map<Tag, String> fields() {
            return mFields;
        }

        /** Every value to write as keyed metadata: the key, then its type and bytes. */
        @NonNull
        Map<String, byte[]> metadata() {
            Map<String, byte[]> metadata = new LinkedHashMap<>();
            for (Map.Entry<Tag, String> field : mFields.entrySet()) {
                if (field.getKey().mMetadataKey != null) {
                    metadata.put(field.getKey().mMetadataKey,
                            metadataValue(field.getKey(), field.getValue()));
                }
            }
            if (mArtwork != null) {
                metadata.put(KEY_ARTWORK, typed(imageType(mArtwork), mArtwork));
            }
            return metadata;
        }

        boolean isEmpty() {
            return mFields.isEmpty() && mArtwork == null;
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
            long[] moovBox = findMoov(raf);
            if (moovBox == null) {
                return null;
            }
            byte[] moov = readBox(raf, moovBox[0], moovBox[1]);
            if (tag.mMetadataKey != null) {
                byte[] data = findMetadata(moov, tag.mMetadataKey);
                String value = data == null ? null : valueOf(data);
                if (value != null) {
                    return value;
                }
            }
            if (tag.mKey == null) {
                return null;
            }
            byte[] udta = findChild(moov, BOX_UDTA);
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
     * Reads the image that stands for the movie, as it was encoded.
     *
     * @return the JPEG, PNG or BMP, or null when the file has none or is not an MP4
     * @deprecated Use {@link MediaTags#getCoverArt}, which works on every format that keeps a
     *     cover picture.
     */
    @Deprecated
    @Nullable
    public static byte[] readArtwork(@NonNull File file) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long[] moovBox = findMoov(raf);
            if (moovBox == null) {
                return null;
            }
            byte[] data = findMetadata(readBox(raf, moovBox[0], moovBox[1]), KEY_ARTWORK);
            if (data == null || data.length < HEADER + DATA_PREFIX) {
                return null;
            }
            byte[] image = new byte[data.length - HEADER - DATA_PREFIX];
            System.arraycopy(data, HEADER + DATA_PREFIX, image, 0, image.length);
            return image;
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "Unable to read the artwork from " + file, error);
            return null;
        }
    }

    /**
     * Writes fields into a recording, in place: a recording can be hundreds of megabytes, and
     * only its moov changes.
     *
     * @return true if every field was written. Where there is room for the user-data boxes but
     *     not the keyed metadata, or the file's meta box holds something else, the fields that
     *     have a user-data box are written there alone, and this returns false.
     */
    public static boolean write(@NonNull File file, @NonNull Builder builder) {
        if (builder.isEmpty()) {
            return false;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            long[] moovBox = findMoov(raf);
            if (moovBox == null) {
                return false;
            }
            long moovAt = moovBox[0];
            long moovSize = moovBox[1];
            byte[] original = readBox(raf, moovAt, moovSize);
            byte[] userData = withUserData(original, builder.fields());
            if (userData == null) {
                return false;
            }
            Map<String, byte[]> metadata = builder.metadata();
            byte[] complete = metadata.isEmpty() ? userData : withMetadata(userData, metadata);
            if (complete != null && place(raf, moovAt, moovSize, complete)) {
                return true;
            }
            // Without room for the keyed metadata, or with a meta box that holds something else,
            // the user-data boxes alone, which is where most of these fields have always gone.
            if (complete != userData && userData != original) {
                place(raf, moovAt, moovSize, userData);
            }
            return false;
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "Unable to write metadata into " + file, error);
            return false;
        }
    }

    /**
     * Writes a new moov over the old one, if the file has room for it without moving anything
     * after it.
     *
     * @return true if it was written
     */
    private static boolean place(@NonNull RandomAccessFile raf, long moovAt, long moovSize,
            @NonNull byte[] moov) throws IOException {
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
            boolean large = size == 1;
            if (large && at + 2 * HEADER <= length) {
                // A 64-bit size follows the type, as a recording over 4 GB has for its mdat.
                byte[] largeSize = new byte[HEADER];
                raf.readFully(largeSize);
                size = (readInt(largeSize, 0) << 32) | readInt(largeSize, 4);
            } else if (size == 0) {
                // The last box, running to the end of the file.
                size = length - at;
            }
            if (size < HEADER || at + size > length) {
                return null;
            }
            if (BOX_MOOV.equals(type(header, 4))) {
                // The moov is edited as a box with the usual eight-byte header.
                return size > MAX_MOOV_BYTES || large ? null : new long[] {at, size};
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
     * where it stands, and the rest are added. A moov is returned as it was when none of the
     * fields has a user-data box, and null when its children cannot be walked.
     */
    @VisibleForTesting
    @Nullable
    static byte[] withUserData(@NonNull byte[] moov, @NonNull Map<Tag, String> fields) {
        List<byte[]> children = children(moov, HEADER);
        if (children == null) {
            return null;
        }
        Map<Tag, String> remaining = new LinkedHashMap<>();
        for (Map.Entry<Tag, String> field : fields.entrySet()) {
            if (field.getKey().mKey != null) {
                remaining.put(field.getKey(), field.getValue());
            }
        }
        if (remaining.isEmpty()) {
            return moov;
        }
        int udtaIndex = indexOfChild(children, BOX_UDTA);
        List<byte[]> userData = new ArrayList<>();
        if (udtaIndex >= 0) {
            List<byte[]> existing = children(children.get(udtaIndex), HEADER);
            if (existing == null) {
                return null;
            }
            userData.addAll(existing);
        }
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

    /**
     * The moov with these values merged into its keyed metadata: a key already there has its
     * value replaced, and the rest are added under new keys, so the keys already numbered keep
     * their numbers. A moov with no meta box gains one. Null when the moov or its meta box
     * cannot be walked, or its meta box holds something other than keyed metadata.
     *
     * @param values each key, and its value as a data box's type and bytes
     */
    @VisibleForTesting
    @Nullable
    static byte[] withMetadata(@NonNull byte[] moov, @NonNull Map<String, byte[]> values) {
        List<byte[]> children = children(moov, HEADER);
        if (children == null) {
            return null;
        }
        int metaIndex = indexOfChild(children, BOX_META);
        byte[] meta = metaIndex >= 0 ? children.get(metaIndex) : emptyMeta();
        int start = metaStart(meta);
        List<byte[]> parts = children(meta, start);
        if (parts == null) {
            return null;
        }
        // A meta box of another kind, such as an ID3 tag, has no keys to add to.
        int handlerIndex = indexOfChild(parts, BOX_HDLR);
        if (handlerIndex < 0 || parts.get(handlerIndex).length < HEADER + 12
                || !MDTA.equals(type(parts.get(handlerIndex), HEADER + 8))) {
            return null;
        }
        int keysIndex = indexOfChild(parts, BOX_KEYS);
        if (keysIndex < 0) {
            parts.add(keysBox(new byte[4], new ArrayList<>()));
            keysIndex = parts.size() - 1;
        }
        int ilstIndex = indexOfChild(parts, BOX_ILST);
        if (ilstIndex < 0) {
            parts.add(box(BOX_ILST, new ArrayList<>()));
            ilstIndex = parts.size() - 1;
        }
        byte[] keys = parts.get(keysIndex);
        List<byte[]> entries = keyEntries(keys);
        List<byte[]> items = children(parts.get(ilstIndex), HEADER);
        if (entries == null || items == null) {
            return null;
        }
        for (Map.Entry<String, byte[]> value : values.entrySet()) {
            int index = indexOfKey(entries, value.getKey());
            if (index < 0) {
                entries.add(keyEntry(value.getKey()));
                index = entries.size();
            }
            byte[] item = itemBox(index, value.getValue());
            int itemIndex = indexOfItem(items, index);
            if (itemIndex >= 0) {
                items.set(itemIndex, item);
            } else {
                items.add(item);
            }
        }
        byte[] versionAndFlags = new byte[4];
        System.arraycopy(keys, HEADER, versionAndFlags, 0, versionAndFlags.length);
        parts.set(keysIndex, keysBox(versionAndFlags, entries));
        parts.set(ilstIndex, box(BOX_ILST, items));

        byte[] prefix = new byte[start - HEADER];
        System.arraycopy(meta, HEADER, prefix, 0, prefix.length);
        List<byte[]> metaBody = new ArrayList<>();
        metaBody.add(prefix);
        metaBody.addAll(parts);
        byte[] updated = box(BOX_META, metaBody);
        if (metaIndex >= 0) {
            children.set(metaIndex, updated);
        } else {
            children.add(updated);
        }
        return box(BOX_MOOV, children);
    }

    /**
     * Where a meta box's children begin. QuickTime's is a plain container; ISO's carries a
     * version and flags first, which read as a size of zero no child can have.
     */
    private static int metaStart(@NonNull byte[] meta) {
        return meta.length >= HEADER + 4 && readInt(meta, HEADER) == 0 ? HEADER + 4 : HEADER;
    }

    /** A meta box of keyed metadata with nothing in it yet, laid out as QuickTime writes one. */
    @NonNull
    private static byte[] emptyMeta() {
        List<byte[]> parts = new ArrayList<>();
        parts.add(handlerBox());
        return box(BOX_META, parts);
    }

    /**
     * The handler that says a meta box holds keyed metadata: version and flags, a predefined
     * zero, the handler type, twelve reserved bytes and an empty name.
     */
    @NonNull
    private static byte[] handlerBox() {
        byte[] body = new byte[4 + 4 + 4 + 12 + 1];
        writeType(body, 8, MDTA);
        List<byte[]> parts = new ArrayList<>();
        parts.add(body);
        return box(BOX_HDLR, parts);
    }

    /** The value stored under a key in a moov's keyed metadata, as its data box, or null. */
    @Nullable
    static byte[] findMetadata(@NonNull byte[] moov, @NonNull String key) {
        byte[] meta = findChild(moov, BOX_META);
        if (meta == null) {
            return null;
        }
        byte[] keys = findChild(meta, metaStart(meta), BOX_KEYS);
        byte[] ilst = findChild(meta, metaStart(meta), BOX_ILST);
        if (keys == null || ilst == null) {
            return null;
        }
        List<byte[]> entries = keyEntries(keys);
        List<byte[]> items = children(ilst, HEADER);
        if (entries == null || items == null) {
            return null;
        }
        int index = indexOfKey(entries, key);
        int itemIndex = index < 0 ? -1 : indexOfItem(items, index);
        return itemIndex < 0 ? null : findChild(items.get(itemIndex), BOX_DATA);
    }

    /** A keys box's entries, each its size, namespace and key, or null if they do not fit. */
    @Nullable
    private static List<byte[]> keyEntries(@NonNull byte[] keys) {
        if (keys.length < HEADER + 8) {
            return null;
        }
        long count = readInt(keys, HEADER + 4);
        List<byte[]> entries = new ArrayList<>();
        int at = HEADER + 8;
        for (long i = 0; i < count; i++) {
            if (at + HEADER > keys.length) {
                return null;
            }
            long size = readInt(keys, at);
            if (size < HEADER || at + size > keys.length) {
                return null;
            }
            byte[] entry = new byte[(int) size];
            System.arraycopy(keys, at, entry, 0, entry.length);
            entries.add(entry);
            at += size;
        }
        return entries;
    }

    @NonNull
    private static byte[] keysBox(@NonNull byte[] versionAndFlags, @NonNull List<byte[]> entries) {
        byte[] count = new byte[4];
        writeInt(count, 0, entries.size());
        List<byte[]> body = new ArrayList<>();
        body.add(versionAndFlags);
        body.add(count);
        body.addAll(entries);
        return box(BOX_KEYS, body);
    }

    /** One key: its size, the mdta namespace, and the key without a terminator. */
    @NonNull
    private static byte[] keyEntry(@NonNull String key) {
        byte[] name = key.getBytes(UTF_8);
        byte[] entry = new byte[HEADER + name.length];
        writeInt(entry, 0, entry.length);
        writeType(entry, 4, MDTA);
        System.arraycopy(name, 0, entry, HEADER, name.length);
        return entry;
    }

    /** The number, counted from one, of the mdta key with this name, or -1. */
    private static int indexOfKey(@NonNull List<byte[]> entries, @NonNull String key) {
        for (int i = 0; i < entries.size(); i++) {
            byte[] entry = entries.get(i);
            if (MDTA.equals(type(entry, 4)) && key.equals(
                    new String(entry, HEADER, entry.length - HEADER, UTF_8))) {
                return i + 1;
            }
        }
        return -1;
    }

    /** Where the item for a key's number is among an ilst's children, or -1. */
    private static int indexOfItem(@NonNull List<byte[]> items, int index) {
        for (int i = 0; i < items.size(); i++) {
            if (readInt(items.get(i), 4) == index) {
                return i;
            }
        }
        return -1;
    }

    /** An ilst item: a box whose type is the key's number, around one data box. */
    @NonNull
    private static byte[] itemBox(int index, @NonNull byte[] value) {
        byte[] data = new byte[HEADER + value.length];
        writeInt(data, 0, data.length);
        writeType(data, 4, BOX_DATA);
        System.arraycopy(value, 0, data, HEADER, value.length);
        byte[] item = new byte[HEADER + data.length];
        writeInt(item, 0, item.length);
        writeInt(item, 4, index);
        System.arraycopy(data, 0, item, HEADER, data.length);
        return item;
    }

    /**
     * A keyed value as a data box holds it: its type, the default locale, then its bytes.
     *
     * @throws IllegalArgumentException if the tag holds a number and the value is not one
     */
    @NonNull
    static byte[] metadataValue(@NonNull Tag tag, @NonNull String value) {
        switch (tag.mMetadataType) {
            case TYPE_FLOAT32:
                return typed(TYPE_FLOAT32,
                        ByteBuffer.allocate(4).putFloat(Float.parseFloat(value)).array());
            case TYPE_UNSIGNED:
                int number = Integer.parseInt(value);
                if (number < 0 || number > 0xFF) {
                    throw new IllegalArgumentException(tag + " holds 0 to 255, not " + value);
                }
                return typed(TYPE_UNSIGNED, new byte[] {(byte) number});
            default:
                return typed(TYPE_UTF8, value.getBytes(UTF_8));
        }
    }

    @NonNull
    static byte[] typed(int type, @NonNull byte[] value) {
        byte[] typed = new byte[DATA_PREFIX + value.length];
        writeInt(typed, 0, type);
        System.arraycopy(value, 0, typed, DATA_PREFIX, value.length);
        return typed;
    }

    /**
     * The data type of an encoded image, from its first bytes.
     *
     * @throws IllegalArgumentException if it is not a JPEG, PNG or BMP
     */
    static int imageType(@NonNull byte[] image) {
        if (image.length >= 3 && (image[0] & 0xFF) == 0xFF && (image[1] & 0xFF) == 0xD8
                && (image[2] & 0xFF) == 0xFF) {
            return TYPE_JPEG;
        }
        if (image.length >= 4 && (image[0] & 0xFF) == 0x89 && image[1] == 'P'
                && image[2] == 'N' && image[3] == 'G') {
            return TYPE_PNG;
        }
        if (image.length >= 2 && image[0] == 'B' && image[1] == 'M') {
            return TYPE_BMP;
        }
        throw new IllegalArgumentException("Artwork must be a JPEG, PNG or BMP");
    }

    /** A data box's value as text, or null for a type that is not text or a number. */
    @VisibleForTesting
    @Nullable
    static String valueOf(@NonNull byte[] data) {
        if (data.length < HEADER + DATA_PREFIX) {
            return null;
        }
        // The first byte of the type is its namespace, which is zero for the well-known types.
        int type = (int) readInt(data, HEADER);
        int at = HEADER + DATA_PREFIX;
        int length = data.length - at;
        ByteBuffer value = ByteBuffer.wrap(data, at, length);
        switch (type) {
            case TYPE_UTF8:
                return new String(data, at, length, UTF_8);
            case TYPE_FLOAT32:
                return length == 4 ? Float.toString(value.getFloat()) : null;
            case TYPE_FLOAT64:
                return length == 8 ? Double.toString(value.getDouble()) : null;
            case TYPE_SIGNED:
            case TYPE_SIGNED_8:
            case TYPE_SIGNED_16:
            case TYPE_SIGNED_32:
            case TYPE_SIGNED_64:
                return integer(data, at, length, true);
            case TYPE_UNSIGNED:
            case TYPE_UNSIGNED_8:
            case TYPE_UNSIGNED_16:
            case TYPE_UNSIGNED_32:
            case TYPE_UNSIGNED_64:
                return integer(data, at, length, false);
            default:
                return null;
        }
    }

    /** A big-endian integer of one to eight bytes, as text, or null for another length. */
    @Nullable
    private static String integer(@NonNull byte[] data, int at, int length, boolean signed) {
        if (length < 1 || length > 8) {
            return null;
        }
        if (!signed && length == 8) {
            // Past what a long holds once its top bit is set.
            return new BigInteger(1, Arrays.copyOfRange(data, at, at + length)).toString();
        }
        long value = signed ? data[at] : data[at] & 0xFF;
        for (int i = 1; i < length; i++) {
            value = (value << 8) | (data[at + i] & 0xFF);
        }
        return Long.toString(value);
    }

    /** A container's child boxes from an offset, or null if they do not fill it exactly. */
    @Nullable
    private static List<byte[]> children(@NonNull byte[] container, int start) {
        List<byte[]> children = new ArrayList<>();
        int at = start;
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

    private static int indexOfChild(@NonNull List<byte[]> children, @NonNull String type) {
        for (int i = 0; i < children.size(); i++) {
            if (type.equals(type(children.get(i), 4))) {
                return i;
            }
        }
        return -1;
    }

    /** The first child of a container with this type, or null. */
    @Nullable
    private static byte[] findChild(@NonNull byte[] container, @NonNull String type) {
        return findChild(container, HEADER, type);
    }

    @Nullable
    private static byte[] findChild(@NonNull byte[] container, int start, @NonNull String type) {
        List<byte[]> children = children(container, start);
        if (children == null) {
            return null;
        }
        int index = indexOfChild(children, type);
        return index < 0 ? null : children.get(index);
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
        byte[] text = value.getBytes(UTF_8);
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
    static String textOf(@NonNull byte[] box) {
        if (box.length < HEADER + TEXT_PREFIX) {
            return null;
        }
        int length = ((box[8] & 0xFF) << 8) | (box[9] & 0xFF);
        if (HEADER + TEXT_PREFIX + length > box.length) {
            return null;
        }
        return new String(box, HEADER + TEXT_PREFIX, length, UTF_8);
    }

    /** The box type for a tag: the copyright sign and its three characters. */
    @NonNull
    static String keyOf(@NonNull Tag tag) {
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
