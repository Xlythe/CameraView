package com.xlythe.view.camera.viewer;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Writes GIFs for tests, frame by frame, with real LZW compression. */
final class GifTestFiles {
    private GifTestFiles() {}

    /** One frame to write. */
    static final class Frame {
        /** Where it goes across. */
        final int x;

        /** Where it goes down. */
        final int y;

        /** Its width. */
        final int width;

        /** Its height. */
        final int height;

        /** Its colour indices, a row at a time, top to bottom as shown. */
        final byte[] indices;

        /** The disposal field as written, 0 to 7. */
        int disposal;

        /** Its delay, in hundredths of a second. */
        int delay;

        /** The colour index drawn as nothing, or -1. */
        int transparent = -1;

        /** Its own colour table, as RGB, or null for the file's. */
        int[] colors;

        /** Whether its rows are written interlaced. */
        boolean interlaced;

        /** Whether it has a graphic control extension at all. */
        boolean control = true;

        /** The LZW minimum code size it is written with. */
        int codeSize = 8;

        /** Whether a full code table is cleared, as encoders do, or used on as it is. */
        boolean clearWhenFull = true;

        Frame(int x, int y, int width, int height, byte[] indices) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.indices = indices;
        }

        Frame disposal(int disposal) {
            this.disposal = disposal;
            return this;
        }

        Frame delay(int hundredths) {
            this.delay = hundredths;
            return this;
        }

        Frame transparent(int index) {
            this.transparent = index;
            return this;
        }

        Frame colors(int... rgb) {
            this.colors = rgb;
            return this;
        }

        Frame interlaced() {
            this.interlaced = true;
            return this;
        }

        Frame withoutControl() {
            this.control = false;
            return this;
        }

        Frame codeSize(int codeSize) {
            this.codeSize = codeSize;
            return this;
        }

        Frame deferredClear() {
            this.clearWhenFull = false;
            return this;
        }
    }

    /** A frame filled with one index. */
    static Frame fill(int x, int y, int width, int height, int index) {
        byte[] indices = new byte[width * height];
        Arrays.fill(indices, (byte) index);
        return new Frame(x, y, width, height, indices);
    }

    /** A GIF of a screen size, a global colour table as RGB or none, and frames. */
    static byte[] gif(int width, int height, int[] global, Frame... frames) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, "GIF89a");
        writeU16(out, width);
        writeU16(out, height);
        out.write(global == null ? 0 : 0x80 | 0x70 | tableBits(global.length));
        out.write(0);
        out.write(0);
        if (global != null) {
            writeTable(out, global);
        }
        // Loop forever.
        out.write(new byte[] {0x21, (byte) 0xFF, 11}, 0, 3);
        writeAscii(out, "NETSCAPE2.0");
        out.write(new byte[] {3, 1, 0, 0, 0}, 0, 5);
        for (Frame frame : frames) {
            if (frame.control) {
                out.write(0x21);
                out.write(0xF9);
                out.write(4);
                out.write((frame.disposal & 7) << 2 | (frame.transparent >= 0 ? 1 : 0));
                writeU16(out, frame.delay);
                out.write(Math.max(0, frame.transparent));
                out.write(0);
            }
            out.write(0x2C);
            writeU16(out, frame.x);
            writeU16(out, frame.y);
            writeU16(out, frame.width);
            writeU16(out, frame.height);
            int flags = (frame.interlaced ? 0x40 : 0);
            if (frame.colors != null) {
                flags |= 0x80 | tableBits(frame.colors.length);
            }
            out.write(flags);
            if (frame.colors != null) {
                writeTable(out, frame.colors);
            }
            out.write(frame.codeSize);
            byte[] data = lzw(frame.interlaced ? interlace(frame) : frame.indices, frame.codeSize,
                    frame.clearWhenFull);
            for (int at = 0; at < data.length; at += 255) {
                int length = Math.min(255, data.length - at);
                out.write(length);
                out.write(data, at, length);
            }
            out.write(0);
        }
        out.write(0x3B);
        return out.toByteArray();
    }

    /** The rows of an interlaced frame in the order they are stored: by pass. */
    private static byte[] interlace(Frame frame) {
        byte[] stored = new byte[frame.indices.length];
        int at = 0;
        int[][] passes = {{0, 8}, {4, 8}, {2, 4}, {1, 2}};
        for (int[] pass : passes) {
            for (int row = pass[0]; row < frame.height; row += pass[1]) {
                System.arraycopy(frame.indices, row * frame.width, stored, at, frame.width);
                at += frame.width;
            }
        }
        return stored;
    }

    /** LZW, as GIF writes it: least significant bit first, codes growing to 12 bits. */
    static byte[] lzw(byte[] indices, int minimum, boolean clearWhenFull) {
        Bits bits = new Bits();
        int clear = 1 << minimum;
        int end = clear + 1;
        int codeSize = minimum + 1;
        int next = clear + 2;
        Map<Integer, Integer> table = new HashMap<>();
        bits.write(clear, codeSize);
        if (indices.length == 0) {
            bits.write(end, codeSize);
            return bits.bytes();
        }
        int prefix = indices[0] & 0xFF;
        for (int i = 1; i < indices.length; i++) {
            int index = indices[i] & 0xFF;
            Integer known = table.get(prefix << 8 | index);
            if (known != null) {
                prefix = known;
                continue;
            }
            bits.write(prefix, codeSize);
            // The decoder's table is a code behind, so the size grows on what it will hold.
            if (next >= (1 << codeSize) && codeSize < 12) {
                codeSize++;
            }
            if (next < 4096) {
                table.put(prefix << 8 | index, next++);
            } else if (clearWhenFull) {
                bits.write(clear, codeSize);
                table.clear();
                next = clear + 2;
                codeSize = minimum + 1;
            }
            prefix = index;
        }
        bits.write(prefix, codeSize);
        if (next >= (1 << codeSize) && codeSize < 12) {
            codeSize++;
        }
        bits.write(end, codeSize);
        return bits.bytes();
    }

    /** Packs codes least significant bit first. */
    private static final class Bits {
        private final ByteArrayOutputStream mOut = new ByteArrayOutputStream();
        private int mBuffer;
        private int mCount;

        void write(int code, int size) {
            mBuffer |= code << mCount;
            mCount += size;
            while (mCount >= 8) {
                mOut.write(mBuffer & 0xFF);
                mBuffer >>>= 8;
                mCount -= 8;
            }
        }

        byte[] bytes() {
            if (mCount > 0) {
                mOut.write(mBuffer & 0xFF);
                mBuffer = 0;
                mCount = 0;
            }
            return mOut.toByteArray();
        }
    }

    private static int tableBits(int colors) {
        int bits = 0;
        while ((2 << bits) < colors) {
            bits++;
        }
        return bits;
    }

    private static void writeTable(ByteArrayOutputStream out, int[] rgb) {
        int size = 2 << tableBits(rgb.length);
        for (int i = 0; i < size; i++) {
            int color = i < rgb.length ? rgb[i] : 0;
            out.write(color >> 16 & 0xFF);
            out.write(color >> 8 & 0xFF);
            out.write(color & 0xFF);
        }
    }

    private static void writeU16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write(value >> 8 & 0xFF);
    }

    private static void writeAscii(ByteArrayOutputStream out, String text) {
        for (int i = 0; i < text.length(); i++) {
            out.write(text.charAt(i));
        }
    }

    /** Every index from 0 to 255 in an order that leaves LZW little to share, from a seed. */
    static byte[] noise(int count, long seed) {
        byte[] indices = new byte[count];
        long state = seed;
        for (int i = 0; i < count; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            indices[i] = (byte) (state >>> 56);
        }
        return indices;
    }

    /** A grey ramp of 256 colours, index i being (i, i, i). */
    static int[] greys() {
        int[] colors = new int[256];
        for (int i = 0; i < 256; i++) {
            colors[i] = i << 16 | i << 8 | i;
        }
        return colors;
    }

    /** Frames of every kind of disposal, for {@link GifDecoderTest}'s cross-check with Pillow. */
    static byte[] everyDisposal() {
        int[] global = {0xE01E1E, 0x1EC83C, 0x1E5AFA, 0xFAFAFA};
        List<Frame> frames = new ArrayList<>();
        // A green band along the top over red, so a flip shows.
        byte[] first = new byte[16 * 12];
        for (int i = 0; i < first.length; i++) {
            first[i] = (byte) (i < 16 * 2 ? 1 : 0);
        }
        frames.add(new Frame(0, 0, 16, 12, first).disposal(1).delay(5));
        // Cleared once shown. It names a transparent index it does not use: Pillow, the
        // reference, clears to the background colour where a frame names none, and browsers
        // always clear to transparent.
        frames.add(fill(3, 2, 5, 4, 2).disposal(2).delay(0).transparent(3));
        // Its own colours, interlaced, with a transparent hole in its top-left corner.
        Frame third = fill(9, 6, 4, 4, 2).disposal(3).delay(12).transparent(0)
                .colors(0x000000, 0xF0C814, 0x7800A0, 0xFFFFFF).interlaced();
        third.indices[0] = 0;
        third.indices[5] = 1;
        frames.add(third);
        frames.add(fill(12, 0, 4, 3, 1).disposal(4).delay(20));
        frames.add(fill(0, 10, 2, 2, 2).delay(3).codeSize(2));
        return gif(16, 12, global, frames.toArray(new Frame[0]));
    }
}
