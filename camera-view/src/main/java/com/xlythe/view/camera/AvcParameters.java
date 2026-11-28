package com.xlythe.view.camera;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

/**
 * Reads how an H.264 or MPEG-4 Part 2 stream encodes brightness, for a video whose container
 * says nothing about it: from an H.264 sequence parameter set's video usability information, or
 * from an MPEG-4 visual object's video signal type.
 */
final class AvcParameters {
    /** The NAL unit type of a sequence parameter set. */
    private static final int SPS = 7;

    /** The profiles whose sequence parameter sets carry chroma format and bit depth. */
    private static final int[] HIGH_PROFILES = {100, 110, 122, 244, 44, 83, 86, 118, 128, 138,
            139, 134, 135};

    /** The start code of an MPEG-4 visual object. */
    private static final int VISUAL_OBJECT = 0xB5;

    /** The descriptor an esds opens with. */
    private static final int ES_DESCRIPTOR = 3;

    /** The descriptor that describes the decoder. */
    private static final int DECODER_CONFIG = 4;

    /** The descriptor that holds the decoder's own configuration. */
    private static final int DECODER_SPECIFIC = 5;

    private AvcParameters() {}

    /**
     * The H.273 transfer characteristics an avcC's sequence parameter sets give, or -1 if they
     * give none or cannot be read.
     */
    static int transferCharacteristics(@NonNull byte[] data, int start, int end) {
        if (end - start < 7) {
            return -1;
        }
        int sets = Bytes.u8(data, start + 5) & 0x1F;
        int at = start + 6;
        for (int i = 0; i < sets && at + 2 <= end; i++) {
            int length = Bytes.u16(data, at);
            at += 2;
            if (at + length > end) {
                return -1;
            }
            if (length > 1 && (Bytes.u8(data, at) & 0x1F) == SPS) {
                try {
                    int transfer = spsTransfer(BitReader.unescape(data, at + 1, at + length));
                    if (transfer >= 0) {
                        return transfer;
                    }
                } catch (IndexOutOfBoundsException truncated) {
                    // A parameter set cut short says nothing.
                }
            }
            at += length;
        }
        return -1;
    }

    /** The transfer characteristics in an H.264 sequence parameter set's payload, or -1. */
    @VisibleForTesting
    static int spsTransfer(@NonNull byte[] sps) {
        BitReader bits = new BitReader(sps);
        int profile = bits.read(8);
        bits.skip(16);
        bits.ue();
        if (isHigh(profile)) {
            int chroma = bits.ue();
            if (chroma == 3) {
                bits.skip(1);
            }
            bits.ue();
            bits.ue();
            bits.skip(1);
            if (bits.flag()) {
                for (int i = 0; i < (chroma != 3 ? 8 : 12); i++) {
                    if (bits.flag()) {
                        scalingList(bits, i < 6 ? 16 : 64);
                    }
                }
            }
        }
        bits.ue();
        int order = bits.ue();
        if (order == 0) {
            bits.ue();
        } else if (order == 1) {
            bits.skip(1);
            bits.se();
            bits.se();
            int cycle = bits.ue();
            for (int i = 0; i < cycle; i++) {
                bits.se();
            }
        }
        bits.ue();
        bits.skip(1);
        bits.ue();
        bits.ue();
        if (!bits.flag()) {
            bits.skip(1);
        }
        bits.skip(1);
        if (bits.flag()) {
            bits.ue();
            bits.ue();
            bits.ue();
            bits.ue();
        }
        if (!bits.flag()) {
            return -1;
        }
        // Video usability information.
        if (bits.flag() && bits.read(8) == 255) {
            bits.skip(32);
        }
        if (bits.flag()) {
            bits.skip(1);
        }
        if (!bits.flag()) {
            return -1;
        }
        bits.skip(4);
        if (!bits.flag()) {
            return -1;
        }
        bits.skip(8);
        return bits.read(8);
    }

    private static boolean isHigh(int profile) {
        for (int high : HIGH_PROFILES) {
            if (high == profile) {
                return true;
            }
        }
        return false;
    }

    /** Skips a scaling list, which ends early once a delta brings it to zero. */
    private static void scalingList(@NonNull BitReader bits, int size) {
        int last = 8;
        int next = 8;
        for (int i = 0; i < size && next != 0; i++) {
            next = (last + bits.se() + 256) % 256;
            if (next != 0) {
                last = next;
            }
        }
    }

    /**
     * The H.273 transfer characteristics an esds's MPEG-4 visual configuration gives, or -1 if
     * it gives none or cannot be read.
     */
    static int esdsTransfer(@NonNull byte[] data, int start, int end) {
        try {
            // The esds is a full box: its version and flags come first.
            int[] range = descriptor(data, start + 4, end, ES_DESCRIPTOR);
            if (range == null) {
                return -1;
            }
            int at = range[0];
            int flags = Bytes.u8(data, at + 2);
            at += 3;
            if ((flags & 0x80) != 0) {
                at += 2;
            }
            if ((flags & 0x40) != 0) {
                at += 1 + Bytes.u8(data, at);
            }
            if ((flags & 0x20) != 0) {
                at += 2;
            }
            range = descriptor(data, at, range[1], DECODER_CONFIG);
            if (range == null) {
                return -1;
            }
            range = descriptor(data, range[0] + 13, range[1], DECODER_SPECIFIC);
            return range == null ? -1 : visualObjectTransfer(data, range[0], range[1]);
        } catch (IndexOutOfBoundsException truncated) {
            return -1;
        }
    }

    /** Where the payload of the first descriptor of a tag at a place starts and ends, or null. */
    @Nullable
    private static int[] descriptor(@NonNull byte[] data, int start, int end, int tag) {
        int at = start;
        while (at + 2 <= end) {
            int found = Bytes.u8(data, at++);
            int length = 0;
            for (int i = 0; i < 4 && at < end; i++) {
                int b = Bytes.u8(data, at++);
                length = (length << 7) | (b & 0x7F);
                if ((b & 0x80) == 0) {
                    break;
                }
            }
            if (found == tag) {
                return new int[] {at, Math.min(end, at + length)};
            }
            at += length;
        }
        return null;
    }

    /**
     * The transfer characteristics of the visual object an MPEG-4 configuration opens with, or
     * -1 if it has none.
     */
    @VisibleForTesting
    static int visualObjectTransfer(@NonNull byte[] data, int start, int end) {
        for (int at = start; at + 4 < end; at++) {
            if (Bytes.u8(data, at) == 0 && Bytes.u8(data, at + 1) == 0
                    && Bytes.u8(data, at + 2) == 1 && Bytes.u8(data, at + 3) == VISUAL_OBJECT) {
                byte[] payload = new byte[end - at - 4];
                System.arraycopy(data, at + 4, payload, 0, payload.length);
                BitReader bits = new BitReader(payload);
                if (bits.flag()) {
                    bits.skip(7);
                }
                int type = bits.read(4);
                // Only a video or still texture object says how its signal is coded.
                if ((type != 1 && type != 2) || !bits.flag()) {
                    return -1;
                }
                bits.skip(4);
                if (!bits.flag()) {
                    return -1;
                }
                bits.skip(8);
                return bits.read(8);
            }
        }
        return -1;
    }
}
