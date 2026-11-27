package com.xlythe.view.camera.viewer;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Writes a DNG the way a camera does, a Bayer mosaic of 16-bit sensor readings with the colour
 * matrix that says what they mean, so a test knows what colour each part of it should come out.
 *
 * <p>The sensor here sees linear sRGB: its colour matrix is the one from CIE XYZ to linear sRGB,
 * and white is equal on all three channels. The left half is red, the right half blue, and the
 * top-right quarter green, so both the colours and which way up the picture is can be told.</p>
 */
final class SyntheticDng {
    private static final short BYTE = 1;
    private static final short ASCII = 2;
    private static final short SHORT = 3;
    private static final short LONG = 4;
    private static final short RATIONAL = 5;
    private static final short SRATIONAL = 10;

    /** XYZ to linear sRGB, in ten-thousandths. */
    private static final int[] XYZ_TO_SRGB = {
            32406, -15372, -4986,
            -9689, 18758, 415,
            557, -2040, 10570,
    };

    private SyntheticDng() {}

    /** The colour, as linear red, green and blue from 0 to 1, at a stored pixel. */
    static float[] colorAt(int x, int y, int width, int height) {
        if (x < width / 2) {
            return new float[] {0.5f, 0.04f, 0.04f};
        }
        if (y < height / 2) {
            return new float[] {0.04f, 0.5f, 0.04f};
        }
        return new float[] {0.04f, 0.04f, 0.5f};
    }

    /**
     * Writes the DNG.
     *
     * @param orientation the Exif orientation to tag it with
     */
    static void write(@NonNull File file, int width, int height, int orientation)
            throws IOException {
        ByteArrayOutputStream extra = new ByteArrayOutputStream();
        // Laid out as: header, IFD, then everything too large for an entry, then the pixels.
        int entryCount = 23;
        int ifdSize = 2 + entryCount * 12 + 4;
        int extraStart = 8 + ifdSize;

        ByteBuffer ifd = ByteBuffer.allocate(ifdSize).order(ByteOrder.LITTLE_ENDIAN);
        ifd.putShort((short) entryCount);
        // The values written after the IFD, which entries point into.
        byte[] make = ascii("CameraView");
        byte[] model = ascii("Synthetic");
        byte[] unique = ascii("CameraView Synthetic");
        ByteBuffer matrix = ByteBuffer.allocate(9 * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : XYZ_TO_SRGB) {
            matrix.putInt(value).putInt(10000);
        }
        ByteBuffer neutral = ByteBuffer.allocate(3 * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 3; i++) {
            neutral.putInt(1).putInt(1);
        }
        int makeOffset = extraStart + extra.size();
        extra.write(make);
        pad(extra);
        int modelOffset = extraStart + extra.size();
        extra.write(model);
        pad(extra);
        int uniqueOffset = extraStart + extra.size();
        extra.write(unique);
        pad(extra);
        int matrixOffset = extraStart + extra.size();
        extra.write(matrix.array());
        int neutralOffset = extraStart + extra.size();
        extra.write(neutral.array());
        int pixelOffset = extraStart + extra.size();
        int pixelBytes = width * height * 2;

        entry(ifd, 254, LONG, 1, 0);
        entry(ifd, 256, LONG, 1, width);
        entry(ifd, 257, LONG, 1, height);
        entry(ifd, 258, SHORT, 1, 16);
        entry(ifd, 259, SHORT, 1, 1);
        entry(ifd, 262, SHORT, 1, 32803);
        entry(ifd, 271, ASCII, make.length, makeOffset);
        entry(ifd, 272, ASCII, model.length, modelOffset);
        entry(ifd, 273, LONG, 1, pixelOffset);
        entry(ifd, 274, SHORT, 1, orientation);
        entry(ifd, 277, SHORT, 1, 1);
        entry(ifd, 278, LONG, 1, height);
        entry(ifd, 279, LONG, 1, pixelBytes);
        entry(ifd, 284, SHORT, 1, 1);
        // CFARepeatPatternDim 2 x 2, packed as two shorts.
        entry(ifd, 33421, SHORT, 2, 2 | (2 << 16));
        // CFAPattern RGGB, packed as four bytes.
        entry(ifd, 33422, BYTE, 4, 0 | (1 << 8) | (1 << 16) | (2 << 24));
        entry(ifd, 50706, BYTE, 4, 1 | (4 << 8));
        entry(ifd, 50707, BYTE, 4, 1 | (1 << 8));
        entry(ifd, 50708, ASCII, unique.length, uniqueOffset);
        entry(ifd, 50717, LONG, 1, 65535);
        entry(ifd, 50721, SRATIONAL, 9, matrixOffset);
        entry(ifd, 50728, RATIONAL, 3, neutralOffset);
        entry(ifd, 50778, SHORT, 1, 21);
        ifd.putInt(0);

        ByteBuffer pixels = ByteBuffer.allocate(pixelBytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float[] color = colorAt(x, y, width, height);
                // RGGB: red at even rows and columns, blue at odd ones, green between.
                int channel = (y % 2 == 0) ? (x % 2 == 0 ? 0 : 1) : (x % 2 == 0 ? 1 : 2);
                pixels.putShort((short) Math.round(color[channel] * 65535));
            }
        }

        try (FileOutputStream out = new FileOutputStream(file)) {
            ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            header.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
            out.write(header.array());
            out.write(ifd.array());
            out.write(extra.toByteArray());
            out.write(pixels.array());
        }
    }

    private static void entry(ByteBuffer ifd, int tag, short type, int count, int value) {
        ifd.putShort((short) tag).putShort(type).putInt(count).putInt(value);
    }

    private static byte[] ascii(String text) {
        byte[] bytes = new byte[text.length() + 1];
        for (int i = 0; i < text.length(); i++) {
            bytes[i] = (byte) text.charAt(i);
        }
        return bytes;
    }

    /** Keeps the next value on a word boundary, as TIFF asks. */
    private static void pad(ByteArrayOutputStream out) {
        if (out.size() % 2 != 0) {
            out.write(0);
        }
    }
}
