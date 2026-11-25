package com.xlythe.view.camera;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the descriptive metadata an MP4 can carry, which the platform will not write for you.
 *
 * <h2>What a recording comes out with</h2>
 *
 * <p>Measured by dumping the boxes of a real recording rather than reasoning about it: the file
 * is structurally correct ISO base media format - {@code ftyp}, {@code moov} with a sane
 * {@code mvhd} whose creation and modification times are set, a video {@code trak} with a valid
 * identity matrix and the right dimensions, an audio {@code trak}, sample tables, {@code mdat} -
 * and it carries <em>no {@code udta} box at all</em>. Nothing records which camera took it, what
 * wrote it, or when, in any field a person or a tool reads. {@code MediaRecorder.setLocation}
 * will add a location, and takes floats, so it is the one piece of metadata we do write and it
 * is written at less precision than the fix had.</p>
 *
 * <p>Unlike Exif there is no table of mandatory tags to satisfy. ISO/IEC 14496-12 defines the
 * structural boxes, which the muxer gets right, and defines {@code udta} as a container without
 * requiring anything in it. The descriptive fields come from convention: QuickTime's
 * {@code ©mak}, {@code ©mod}, {@code ©swr}, {@code ©day} and {@code ©xyz}, which is also the set
 * Android's own muxer and most readers understand.</p>
 *
 * <h2>Why it can be appended at all</h2>
 *
 * <p>Chunk offsets in {@code stco} and {@code co64} are absolute file positions into
 * {@code mdat}. Growing {@code moov} by inserting bytes would move {@code mdat} and silently
 * invalidate every one of them - a file that still plays until it seeks. The muxer leaves a
 * large {@code free} box between {@code moov} and {@code mdat} precisely so this does not have
 * to happen: {@code moov} grows into it and {@code free} shrinks by the same amount, the total
 * length never changes, and not one offset moves.</p>
 *
 * <p>Where there is no {@code free} box, or not enough of it, nothing is written. Rewriting the
 * offset tables is possible and is a much larger thing to get wrong than a missing manufacturer
 * name is to live without.</p>
 */
public final class VideoMetadata {
    /** The copyright sign that begins QuickTime's user-data keys, as a byte. */
    private static final int COPYRIGHT_SIGN = 0xA9;

    private static final String BOX_MOOV = "moov";
    private static final String BOX_FREE = "free";
    private static final String BOX_SKIP = "skip";
    private static final String BOX_UDTA = "udta";

    /** Eight bytes of box header, which every box pays. */
    private static final int HEADER = 8;

    private static final String TAG = "VideoMetadata";

    private VideoMetadata() {}

    /** One user-data field: a four-character key and the text it carries. */
    @VisibleForTesting
    static final class Field {
        final String key;
        final String value;

        Field(@NonNull String key, @NonNull String value) {
            this.key = key;
            this.value = value;
        }
    }

    /** The fields to write, built by the caller and handed over in one go. */
    public static final class Builder {
        private final List<Field> mFields = new ArrayList<>();

        /** The manufacturer of the device that recorded this, as {@code ©mak}. */
        @NonNull
        public Builder setMake(@Nullable String make) {
            return set("mak", make);
        }

        /** The model of that device, as {@code ©mod}. */
        @NonNull
        public Builder setModel(@Nullable String model) {
            return set("mod", model);
        }

        /** What wrote the file, as {@code ©swr}. */
        @NonNull
        public Builder setSoftware(@Nullable String software) {
            return set("swr", software);
        }

        /**
         * When it was recorded, as {@code ©day}.
         *
         * <p>Expected to be ISO 8601. The box carries text, so nothing here can enforce that,
         * and a reader meeting something else will show it verbatim.</p>
         */
        @NonNull
        public Builder setCreationDate(@Nullable String iso8601) {
            return set("day", iso8601);
        }

        /**
         * Where it was recorded, as {@code ©xyz} in ISO 6709.
         *
         * <p>Written from doubles rather than the floats {@code MediaRecorder.setLocation}
         * takes, which lose roughly a metre of precision at these magnitudes.</p>
         */
        @NonNull
        public Builder setLocation(double latitude, double longitude) {
            return set("xyz", iso6709(latitude, longitude));
        }

        @NonNull
        private Builder set(@NonNull String key, @Nullable String value) {
            if (value != null && !value.isEmpty()) {
                mFields.add(new Field(key, value));
            }
            return this;
        }

        @NonNull
        @VisibleForTesting
        List<Field> fields() {
            return mFields;
        }
    }

    /**
     * ISO 6709 as QuickTime writes it: signed, zero-padded, and terminated with a solidus.
     *
     * <p>Four decimal places is about eleven metres and is what the format is usually seen
     * with; the precision lost here is far less than the precision lost by taking a float.</p>
     */
    @VisibleForTesting
    @NonNull
    static String iso6709(double latitude, double longitude) {
        return String.format(java.util.Locale.US, "%+08.4f%+09.4f/", latitude, longitude);
    }

    /**
     * Writes the fields into the file's {@code moov}, using its {@code free} box for room.
     *
     * <p>Edits the file in place rather than rewriting it. A recording is routinely hundreds of
     * megabytes and the whole point of using the {@code free} box is that nothing after it
     * moves, so the only bytes that change are the user data, the free box's header and
     * {@code moov}'s size field. Reading the file into memory to write forty bytes would be an
     * out-of-memory waiting for a long enough video.</p>
     *
     * @return true if the metadata was written
     */
    public static boolean write(@NonNull File file, @NonNull Builder builder) {
        List<Field> fields = builder.fields();
        if (fields.isEmpty()) {
            return false;
        }
        byte[] udta = buildUdta(fields);
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "rw");
            long length = raf.length();
            long moovAt = -1;
            long moovSize = 0;
            long at = 0;
            byte[] header = new byte[HEADER];
            while (at + HEADER <= length) {
                raf.seek(at);
                raf.readFully(header);
                long size = readInt(header, 0);
                if (size < HEADER || at + size > length) {
                    return false;
                }
                if (BOX_MOOV.equals(type(header))) {
                    moovAt = at;
                    moovSize = size;
                    break;
                }
                at += size;
            }
            if (moovAt < 0) {
                return false;
            }
            if (hasUserData(raf, moovAt, moovSize)) {
                // Two udta boxes is not a file anyone should have to read, and merging into one
                // already there is a different and larger job.
                return false;
            }

            long freeAt = moovAt + moovSize;
            if (freeAt + HEADER > length) {
                return false;
            }
            raf.seek(freeAt);
            raf.readFully(header);
            String freeType = type(header);
            if (!BOX_FREE.equals(freeType) && !BOX_SKIP.equals(freeType)) {
                // No padding to grow into. Everything after moov would have to move, and every
                // chunk offset in the sample tables would have to be rewritten to match.
                return false;
            }
            long freeSize = readInt(header, 0);
            if (freeSize - udta.length < HEADER) {
                return false;
            }

            // The user data takes the front of the free box, and what is left stays free. The
            // bytes beyond are padding either way, so they need no tidying.
            raf.seek(freeAt);
            raf.write(udta);
            byte[] newFree = new byte[HEADER];
            writeInt(newFree, 0, (int) (freeSize - udta.length));
            writeType(newFree, 4, BOX_FREE);
            raf.write(newFree);

            // And moov now owns what the free box gave up.
            byte[] size = new byte[4];
            writeInt(size, 0, (int) (moovSize + udta.length));
            raf.seek(moovAt);
            raf.write(size);
            return true;
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "Unable to write the recording's metadata", error);
            return false;
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException ignored) {
                    // Nothing useful to do about a failed close on a file we have finished with.
                }
            }
        }
    }

    /**
     * Whether moov already carries user data, read without loading the file.
     *
     * <p>A moov whose children cannot be walked answers true, which reads oddly and is the
     * careful answer: the question being asked is "may I append to this", and the honest reply
     * to a container this does not understand is no. Treating unparseable as empty is how a
     * writer puts a second udta into a file that already had one.</p>
     */
    private static boolean hasUserData(@NonNull RandomAccessFile raf, long moovAt, long moovSize)
            throws IOException {
        long at = moovAt + HEADER;
        long end = moovAt + moovSize;
        byte[] header = new byte[HEADER];
        while (at + HEADER <= end) {
            raf.seek(at);
            raf.readFully(header);
            long size = readInt(header, 0);
            if (size < HEADER || at + size > end) {
                return true;
            }
            if (BOX_UDTA.equals(type(header))) {
                return true;
            }
            at += size;
        }
        return false;
    }

    private static long readInt(@NonNull byte[] data, int at) {
        return ((long) (data[at] & 0xFF) << 24) | ((data[at + 1] & 0xFF) << 16)
                | ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
    }

    @NonNull
    private static String type(@NonNull byte[] header) {
        return "" + (char) (header[4] & 0xFF) + (char) (header[5] & 0xFF)
                + (char) (header[6] & 0xFF) + (char) (header[7] & 0xFF);
    }

    /** A udta box holding one box per field. */
    @VisibleForTesting
    @NonNull
    static byte[] buildUdta(@NonNull List<Field> fields) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Field field : fields) {
            byte[] text = utf8(field.value);
            // QuickTime text: a two-byte length and a two-byte language code, then the bytes.
            int size = HEADER + 4 + text.length;
            writeInt(body, size);
            body.write(COPYRIGHT_SIGN);
            for (int i = 0; i < 3; i++) {
                body.write(field.key.charAt(i));
            }
            body.write((text.length >> 8) & 0xFF);
            body.write(text.length & 0xFF);
            // 0x55C4 is "und", the language code used when none is meant.
            body.write(0x55);
            body.write(0xC4);
            body.write(text, 0, text.length);
        }
        byte[] inner = body.toByteArray();
        byte[] udta = new byte[HEADER + inner.length];
        writeInt(udta, 0, udta.length);
        writeType(udta, 4, BOX_UDTA);
        System.arraycopy(inner, 0, udta, HEADER, inner.length);
        return udta;
    }

    private static void writeInt(@NonNull byte[] out, int at, int value) {
        out[at] = (byte) ((value >> 24) & 0xFF);
        out[at + 1] = (byte) ((value >> 16) & 0xFF);
        out[at + 2] = (byte) ((value >> 8) & 0xFF);
        out[at + 3] = (byte) (value & 0xFF);
    }

    private static void writeInt(@NonNull ByteArrayOutputStream out, int value) {
        out.write((value >> 24) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeType(@NonNull byte[] out, int at, @NonNull String type) {
        for (int i = 0; i < 4; i++) {
            out[at + i] = (byte) type.charAt(i);
        }
    }

    @NonNull
    private static byte[] utf8(@NonNull String value) {
        try {
            return value.getBytes("UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            // Every JVM has UTF-8. Named rather than swallowed so the next reader knows why.
            throw new AssertionError(impossible);
        }
    }
}
