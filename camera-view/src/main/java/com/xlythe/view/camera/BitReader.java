package com.xlythe.view.camera;

import androidx.annotation.NonNull;

/**
 * Reads a video parameter set a bit at a time, most significant first, with the Exp-Golomb
 * codes H.264 and H.265 use. Reading past the end throws {@link IndexOutOfBoundsException}.
 */
final class BitReader {
    /** The bytes. */
    @NonNull
    private final byte[] mData;

    /** The next bit to read, counted from the first byte's top bit. */
    private int mPosition;

    BitReader(@NonNull byte[] data) {
        mData = data;
    }

    /** The next bits as an unsigned number, up to 31 of them. */
    int read(int count) {
        int value = 0;
        for (int i = 0; i < count; i++) {
            int bit = (mData[mPosition >> 3] >> (7 - (mPosition & 7))) & 1;
            value = (value << 1) | bit;
            mPosition++;
        }
        return value;
    }

    /** Whether the next bit is set. */
    boolean flag() {
        return read(1) == 1;
    }

    void skip(int count) {
        mPosition += count;
        if (mPosition > mData.length * 8) {
            throw new IndexOutOfBoundsException("Past the end of the parameter set");
        }
    }

    /** An unsigned Exp-Golomb code. */
    int ue() {
        int zeros = 0;
        while (read(1) == 0) {
            zeros++;
            if (zeros > 31) {
                throw new IndexOutOfBoundsException("Not an Exp-Golomb code");
            }
        }
        return zeros == 0 ? 0 : (1 << zeros) - 1 + read(zeros);
    }

    /** A signed Exp-Golomb code. */
    int se() {
        int code = ue();
        return (code & 1) == 1 ? (code + 1) / 2 : -(code / 2);
    }

    /** A NAL unit's payload with its emulation prevention bytes taken out. */
    @NonNull
    static byte[] unescape(@NonNull byte[] data, int start, int end) {
        byte[] out = new byte[end - start];
        int length = 0;
        int zeros = 0;
        for (int i = start; i < end; i++) {
            int value = data[i] & 0xFF;
            if (zeros >= 2 && value == 3) {
                zeros = 0;
                continue;
            }
            zeros = value == 0 ? zeros + 1 : 0;
            out[length++] = (byte) value;
        }
        byte[] trimmed = new byte[length];
        System.arraycopy(out, 0, trimmed, 0, length);
        return trimmed;
    }
}
