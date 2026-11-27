package com.xlythe.view.camera;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;

/** Numbers and text at offsets in byte arrays, in either byte order, for the format readers. */
final class Bytes {
    /** Text in most metadata. Looked up by name, since StandardCharsets arrived in API 19. */
    static final Charset UTF_8 = Charset.forName("UTF-8");

    /** Single-byte text, as PNG's plain text chunks and older tags hold it. */
    static final Charset LATIN_1 = Charset.forName("ISO-8859-1");

    /** Two-byte text with its own byte order mark, as ID3 holds it. */
    static final Charset UTF_16 = Charset.forName("UTF-16");

    /** Two-byte text, most significant byte first, with no mark. */
    static final Charset UTF_16BE = Charset.forName("UTF-16BE");

    /** Two-byte text, least significant byte first, with no mark. */
    static final Charset UTF_16LE = Charset.forName("UTF-16LE");

    private Bytes() {}

    static int u8(@NonNull byte[] data, int at) {
        return data[at] & 0xFF;
    }

    static int u16(@NonNull byte[] data, int at) {
        return (u8(data, at) << 8) | u8(data, at + 1);
    }

    static int u16le(@NonNull byte[] data, int at) {
        return (u8(data, at + 1) << 8) | u8(data, at);
    }

    static int u24(@NonNull byte[] data, int at) {
        return (u8(data, at) << 16) | (u8(data, at + 1) << 8) | u8(data, at + 2);
    }

    static int u24le(@NonNull byte[] data, int at) {
        return (u8(data, at + 2) << 16) | (u8(data, at + 1) << 8) | u8(data, at);
    }

    static long u32(@NonNull byte[] data, int at) {
        return ((long) u8(data, at) << 24) | (u8(data, at + 1) << 16) | (u8(data, at + 2) << 8)
                | u8(data, at + 3);
    }

    static long u32le(@NonNull byte[] data, int at) {
        return ((long) u8(data, at + 3) << 24) | (u8(data, at + 2) << 16)
                | (u8(data, at + 1) << 8) | u8(data, at);
    }

    static long u64(@NonNull byte[] data, int at) {
        return (u32(data, at) << 32) | u32(data, at + 4);
    }

    static long u64le(@NonNull byte[] data, int at) {
        return (u32le(data, at + 4) << 32) | u32le(data, at);
    }

    /** An unsigned big-endian number of one to eight bytes. */
    static long uint(@NonNull byte[] data, int at, int size) {
        long value = 0;
        for (int i = 0; i < size; i++) {
            value = (value << 8) | u8(data, at + i);
        }
        return value;
    }

    static void put16(@NonNull byte[] out, int at, int value) {
        out[at] = (byte) (value >> 8);
        out[at + 1] = (byte) value;
    }

    static void put16le(@NonNull byte[] out, int at, int value) {
        out[at] = (byte) value;
        out[at + 1] = (byte) (value >> 8);
    }

    static void put24(@NonNull byte[] out, int at, int value) {
        out[at] = (byte) (value >> 16);
        out[at + 1] = (byte) (value >> 8);
        out[at + 2] = (byte) value;
    }

    static void put24le(@NonNull byte[] out, int at, int value) {
        out[at] = (byte) value;
        out[at + 1] = (byte) (value >> 8);
        out[at + 2] = (byte) (value >> 16);
    }

    static void put32(@NonNull byte[] out, int at, long value) {
        out[at] = (byte) (value >> 24);
        out[at + 1] = (byte) (value >> 16);
        out[at + 2] = (byte) (value >> 8);
        out[at + 3] = (byte) value;
    }

    static void put32le(@NonNull byte[] out, int at, long value) {
        out[at] = (byte) value;
        out[at + 1] = (byte) (value >> 8);
        out[at + 2] = (byte) (value >> 16);
        out[at + 3] = (byte) (value >> 24);
    }

    static void put64(@NonNull byte[] out, int at, long value) {
        put32(out, at, value >>> 32);
        put32(out, at + 4, value);
    }

    /** An unsigned big-endian number in one to eight bytes. */
    static void putUint(@NonNull byte[] out, int at, int size, long value) {
        for (int i = size - 1; i >= 0; i--) {
            out[at + i] = (byte) value;
            value >>>= 8;
        }
    }

    /** Four bytes as characters, each byte its own code point, as box and chunk types are. */
    @NonNull
    static String fourCc(@NonNull byte[] data, int at) {
        return new String(new char[] {(char) u8(data, at), (char) u8(data, at + 1),
                (char) u8(data, at + 2), (char) u8(data, at + 3)});
    }

    /** A four-character type as its bytes, each character one byte. */
    @NonNull
    static byte[] fourCc(@NonNull String type) {
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            bytes[i] = (byte) type.charAt(i);
        }
        return bytes;
    }

    /** Whether the bytes at an offset are these. */
    static boolean startsWith(@NonNull byte[] data, int at, @NonNull byte[] prefix) {
        if (at < 0 || at + prefix.length > data.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[at + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /** Whether the bytes at an offset spell this ASCII text. */
    static boolean startsWith(@NonNull byte[] data, int at, @NonNull String ascii) {
        return startsWith(data, at, ascii.getBytes(LATIN_1));
    }

    /** Where some bytes first occur from an offset, or -1. */
    static int indexOf(@NonNull byte[] data, @NonNull byte[] target, int from) {
        outer:
        for (int i = Math.max(0, from); i <= data.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (data[i + j] != target[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** Where some bytes last occur, or -1. */
    static int lastIndexOf(@NonNull byte[] data, @NonNull byte[] target) {
        outer:
        for (int i = data.length - target.length; i >= 0; i--) {
            for (int j = 0; j < target.length; j++) {
                if (data[i + j] != target[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** Where the first zero byte is from an offset, or the end. */
    static int terminator(@NonNull byte[] data, int from, int end) {
        for (int i = from; i < end; i++) {
            if (data[i] == 0) {
                return i;
            }
        }
        return end;
    }

    /** Several arrays end to end. */
    @NonNull
    static byte[] concat(@NonNull byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }

    /** Whether text fits in single bytes. */
    static boolean isLatin1(@NonNull String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0xFF) {
                return false;
            }
        }
        return true;
    }
}
