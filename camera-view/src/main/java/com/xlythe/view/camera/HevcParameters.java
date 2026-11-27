package com.xlythe.view.camera;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

/**
 * Reads how an HEVC stream encodes brightness from its sequence parameter set, for a video
 * whose container says nothing about it. The decoder configuration's own bit depth is not
 * trusted: some writers fill it in wrongly, and HDR shows only in the parameter set's video
 * usability information.
 */
final class HevcParameters {
    /** The NAL unit type of a sequence parameter set. */
    private static final int SPS = 33;

    /** Where the arrays of parameter sets start in a decoder configuration record. */
    private static final int ARRAYS = 22;

    private HevcParameters() {}

    /**
     * The H.273 transfer characteristics a decoder configuration record's sequence parameter set
     * gives, or -1 if it gives none or cannot be read.
     */
    static int transferCharacteristics(@NonNull byte[] data, int start, int end) {
        if (end - start <= ARRAYS) {
            return -1;
        }
        int arrays = Bytes.u8(data, start + ARRAYS);
        int at = start + ARRAYS + 1;
        for (int a = 0; a < arrays && at + 3 <= end; a++) {
            int type = Bytes.u8(data, at) & 0x3F;
            int count = Bytes.u16(data, at + 1);
            at += 3;
            for (int n = 0; n < count && at + 2 <= end; n++) {
                int length = Bytes.u16(data, at);
                at += 2;
                if (at + length > end) {
                    return -1;
                }
                if (type == SPS && length > 2) {
                    try {
                        int transfer = spsTransfer(unescape(data, at + 2, at + length));
                        if (transfer >= 0) {
                            return transfer;
                        }
                    } catch (IndexOutOfBoundsException truncated) {
                        // A parameter set cut short says nothing.
                    }
                }
                at += length;
            }
        }
        return -1;
    }

    /** A NAL unit's payload with its emulation prevention bytes taken out. */
    @NonNull
    private static byte[] unescape(@NonNull byte[] data, int start, int end) {
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

    /** The transfer characteristics in a sequence parameter set's payload, or -1. */
    @VisibleForTesting
    static int spsTransfer(@NonNull byte[] sps) {
        Bits bits = new Bits(sps);
        bits.skip(4);
        int maxSubLayers = bits.read(3);
        bits.skip(1);
        profileTierLevel(bits, maxSubLayers);
        bits.ue();
        int chroma = bits.ue();
        if (chroma == 3) {
            bits.skip(1);
        }
        bits.ue();
        bits.ue();
        if (bits.read(1) == 1) {
            bits.ue();
            bits.ue();
            bits.ue();
            bits.ue();
        }
        bits.ue();
        bits.ue();
        int pocBits = bits.ue() + 4;
        boolean ordering = bits.read(1) == 1;
        for (int i = ordering ? 0 : maxSubLayers; i <= maxSubLayers; i++) {
            bits.ue();
            bits.ue();
            bits.ue();
        }
        for (int i = 0; i < 6; i++) {
            bits.ue();
        }
        if (bits.read(1) == 1 && bits.read(1) == 1) {
            scalingListData(bits);
        }
        bits.skip(2);
        if (bits.read(1) == 1) {
            bits.skip(8);
            bits.ue();
            bits.ue();
            bits.skip(1);
        }
        int sets = bits.ue();
        int[] deltaPocs = new int[sets];
        for (int i = 0; i < sets; i++) {
            deltaPocs[i] = shortTermRefPicSet(bits, i, deltaPocs);
        }
        if (bits.read(1) == 1) {
            int longTerm = bits.ue();
            for (int i = 0; i < longTerm; i++) {
                bits.skip(pocBits + 1);
            }
        }
        bits.skip(2);
        if (bits.read(1) == 0) {
            return -1;
        }
        // Video usability information.
        if (bits.read(1) == 1 && bits.read(8) == 255) {
            bits.skip(32);
        }
        if (bits.read(1) == 1) {
            bits.skip(1);
        }
        if (bits.read(1) == 0) {
            return -1;
        }
        bits.skip(4);
        if (bits.read(1) == 0) {
            return -1;
        }
        bits.skip(8);
        return bits.read(8);
    }

    private static void profileTierLevel(@NonNull Bits bits, int maxSubLayers) {
        bits.skip(2 + 1 + 5 + 32 + 48 + 8);
        boolean[] profile = new boolean[maxSubLayers];
        boolean[] level = new boolean[maxSubLayers];
        for (int i = 0; i < maxSubLayers; i++) {
            profile[i] = bits.read(1) == 1;
            level[i] = bits.read(1) == 1;
        }
        if (maxSubLayers > 0) {
            bits.skip(2 * (8 - maxSubLayers));
        }
        for (int i = 0; i < maxSubLayers; i++) {
            if (profile[i]) {
                bits.skip(88);
            }
            if (level[i]) {
                bits.skip(8);
            }
        }
    }

    private static void scalingListData(@NonNull Bits bits) {
        for (int size = 0; size < 4; size++) {
            for (int matrix = 0; matrix < 6; matrix += size == 3 ? 3 : 1) {
                if (bits.read(1) == 0) {
                    bits.ue();
                } else {
                    int coefficients = Math.min(64, 1 << (4 + (size << 1)));
                    if (size > 1) {
                        bits.ue();
                    }
                    for (int i = 0; i < coefficients; i++) {
                        bits.ue();
                    }
                }
            }
        }
    }

    /** Skips one short-term reference picture set and returns how many pictures it holds. */
    private static int shortTermRefPicSet(@NonNull Bits bits, int index, @NonNull int[] counts) {
        if (index != 0 && bits.read(1) == 1) {
            bits.skip(1);
            bits.ue();
            int pictures = 0;
            for (int j = 0; j <= counts[index - 1]; j++) {
                boolean used = bits.read(1) == 1;
                boolean delta = used || bits.read(1) == 1;
                if (delta) {
                    pictures++;
                }
            }
            return pictures;
        }
        int negative = bits.ue();
        int positive = bits.ue();
        for (int i = 0; i < negative + positive; i++) {
            bits.ue();
            bits.skip(1);
        }
        return negative + positive;
    }

    /** Reads bits most significant first, and Exp-Golomb codes. */
    private static final class Bits {
        /** The bytes. */
        @NonNull
        private final byte[] mData;

        /** The next bit to read, counted from the first byte's top bit. */
        private int mPosition;

        Bits(@NonNull byte[] data) {
            mData = data;
        }

        int read(int count) {
            int value = 0;
            for (int i = 0; i < count; i++) {
                int bit = (mData[mPosition >> 3] >> (7 - (mPosition & 7))) & 1;
                value = (value << 1) | bit;
                mPosition++;
            }
            return value;
        }

        void skip(int count) {
            mPosition += count;
            if (mPosition > mData.length * 8) {
                throw new IndexOutOfBoundsException("Past the end of the parameter set");
            }
        }

        /** An unsigned Exp-Golomb code; a signed one is skipped the same way. */
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
    }
}
