package com.xlythe.view.camera.viewer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import com.xlythe.view.camera.viewer.GifTestFiles.Frame;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;

/**
 * How the library's own GIF decoder composes frames. Real files are checked against Pillow
 * 13.0's frames, as CRC32s of each frame's ARGB pixels with every transparent pixel as 0; files
 * written here check each rule pixel by pixel, the way browsers and Android 9's decoder apply
 * them.
 */
public class GifDecoderTest {
    private static final int RED = 0xFFE01E1E;
    private static final int GREEN = 0xFF1EC83C;
    private static final int BLUE = 0xFF1E5AFA;
    private static final int WHITE = 0xFFFAFAFA;
    private static final int PURPLE = 0xFF7800A0;
    private static final int YELLOW = 0xFFF0C814;
    private static final int[] PALETTE = {0xE01E1E, 0x1EC83C, 0x1E5AFA, 0xFAFAFA};

    /** An interlaced 640x479 GIF whose later frames are partly transparent, against Pillow. */
    @Test
    public void anInterlacedGifMatchesPillow() throws IOException {
        GifDecoder decoder = GifDecoder.parse(resource("skia_test640x479_static.gif"));
        assertEquals(640, decoder.getWidth());
        assertEquals(479, decoder.getHeight());
        assertArrayEquals(new int[] {200, 200, 200, 200}, decoder.getDelays());
        long[] pillow = {1244084424L, 1408267687L, 3583277086L, 2241068989L};
        for (long frame : pillow) {
            assertEquals(frame, crc(decoder.advance()));
        }
    }

    /**
     * A screen of no size takes its first frame's; a later frame wholly off it is cut away, as
     * browsers do. Pillow grows the screen for that frame too, so only the first is compared.
     */
    @Test
    public void aFirstFrameLargerThanTheScreenGrowsItAndLaterOnesAreCut() throws IOException {
        GifDecoder decoder = GifDecoder.parse(resource("skia_xOffsetTooBig.gif"));
        assertEquals(100, decoder.getWidth());
        assertEquals(90, decoder.getHeight());
        assertEquals(3275106624L, crc(decoder.advance()));
        assertEquals(3275106624L, crc(decoder.advance()));
    }

    /** A frame that fills the code table, cleared or used on full, matches Pillow and its input. */
    @Test
    public void aFullCodeTableIsClearedOrUsedOnFull() throws IOException {
        byte[] noise = GifTestFiles.noise(96 * 96, 7);
        for (boolean clear : new boolean[] {true, false}) {
            Frame frame = new Frame(0, 0, 96, 96, noise);
            if (!clear) {
                frame.deferredClear();
            }
            int[] pixels = GifDecoder.parse(GifTestFiles.gif(96, 96, GifTestFiles.greys(), frame))
                    .advance();
            assertEquals(3048577306L, crc(pixels));
            for (int i = 0; i < noise.length; i++) {
                int grey = noise[i] & 0xFF;
                assertEquals(0xFF000000 | grey << 16 | grey << 8 | grey, pixels[i]);
            }
        }
    }

    /** Each disposal, on a picture with a band along its top, so a flip would show. */
    @Test
    public void eachFrameIsDisposedOfAsItAsks() throws IOException {
        GifDecoder decoder = GifDecoder.parse(GifTestFiles.everyDisposal());
        assertEquals(5, decoder.getFrameCount());

        // Before anything is disposed of, Pillow agrees.
        int[] first = decoder.advance().clone();
        assertEquals(1170968155L, crc(first));
        assertEquals(GREEN, first[at(0, 0)]);
        assertEquals(GREEN, first[at(15, 1)]);
        assertEquals(RED, first[at(0, 2)]);
        assertEquals(RED, first[at(15, 11)]);

        int[] second = decoder.advance().clone();
        assertEquals(2918811559L, crc(second));
        assertEquals(BLUE, second[at(3, 2)]);
        assertEquals(BLUE, second[at(7, 5)]);
        assertEquals(RED, second[at(8, 5)]);

        // The blue block is cleared to transparent, not to a colour, once shown. The next frame
        // brings its own colours, interlaced, with a hole that leaves what is under it.
        int[] third = decoder.advance().clone();
        assertEquals(0, third[at(3, 2)]);
        assertEquals(0, third[at(7, 5)]);
        assertEquals(RED, third[at(2, 2)]);
        assertEquals(RED, third[at(9, 6)]);
        assertEquals(YELLOW, third[at(10, 7)]);
        assertEquals(PURPLE, third[at(12, 9)]);
        assertEquals(PURPLE, third[at(10, 6)]);

        // Restore-previous puts back what was under it; a green corner goes on top.
        int[] fourth = decoder.advance().clone();
        assertEquals(RED, fourth[at(12, 9)]);
        assertEquals(RED, fourth[at(10, 7)]);
        assertEquals(0, fourth[at(3, 2)]);
        assertEquals(GREEN, fourth[at(12, 2)]);
        assertEquals(GREEN, fourth[at(15, 2)]);

        // Disposal 4, which some encoders write for restore-previous, takes the corner back off.
        int[] fifth = decoder.advance();
        assertEquals(RED, fifth[at(12, 2)]);
        assertEquals(GREEN, fifth[at(12, 1)]);
        assertEquals(BLUE, fifth[at(0, 10)]);
        assertEquals(BLUE, fifth[at(1, 11)]);
        assertEquals(RED, fifth[at(2, 11)]);
    }

    /** After the last frame comes the first again, drawn on a clear screen. */
    @Test
    public void itLoopsFromAClearScreen() throws IOException {
        GifDecoder decoder = GifDecoder.parse(GifTestFiles.everyDisposal());
        int[] first = decoder.advance().clone();
        for (int i = 1; i < decoder.getFrameCount(); i++) {
            decoder.advance();
        }
        assertArrayEquals(first, decoder.advance());
    }

    /** Seeking forward composes the frames between; back, it starts again from the first. */
    @Test
    public void seeksForwardAndBack() throws IOException {
        byte[] gif = GifTestFiles.everyDisposal();
        GifDecoder walked = GifDecoder.parse(gif);
        int[][] frames = new int[5][];
        for (int i = 0; i < frames.length; i++) {
            frames[i] = walked.advance().clone();
        }
        GifDecoder decoder = GifDecoder.parse(gif);
        assertArrayEquals(frames[3], decoder.seek(3));
        assertSame(decoder.seek(3), decoder.seek(3));
        assertArrayEquals(frames[1], decoder.seek(1));
        assertArrayEquals(frames[4], decoder.seek(4));
        try {
            decoder.seek(5);
            fail("There is no sixth frame");
        } catch (IndexOutOfBoundsException expected) {
            // As it should.
        }
    }

    /** An interlaced frame's rows go back in order, each pass a different colour of row. */
    @Test
    public void interlacedRowsGoBackInOrder() throws IOException {
        byte[] rows = new byte[2 * 11];
        for (int row = 0; row < 11; row++) {
            rows[2 * row] = (byte) (row % 4);
            rows[2 * row + 1] = (byte) ((row + 1) % 4);
        }
        int[] pixels = GifDecoder.parse(GifTestFiles.gif(2, 11, PALETTE,
                new Frame(0, 0, 2, 11, rows).interlaced())).advance();
        int[] colors = {RED, GREEN, BLUE, WHITE};
        for (int row = 0; row < 11; row++) {
            assertEquals("row " + row, colors[row % 4], pixels[2 * row]);
            assertEquals("row " + row, colors[(row + 1) % 4], pixels[2 * row + 1]);
        }
    }

    /**
     * A frame's own colour table is used in place of the file's; an index past the end of a
     * table is opaque black; and a frame with no table at all draws nothing.
     */
    @Test
    public void colourTables() throws IOException {
        byte[] indices = {0, 1, 2, 3, 7, 0};
        Frame own = new Frame(0, 0, 6, 1, indices).colors(0x102030, 0x405060);
        int[] pixels = GifDecoder.parse(GifTestFiles.gif(6, 1, PALETTE, own)).advance();
        assertEquals(0xFF102030, pixels[0]);
        assertEquals(0xFF405060, pixels[1]);
        // A two-colour table holds indices 0 and 1 only.
        assertEquals(0xFF000000, pixels[2]);
        assertEquals(0xFF000000, pixels[4]);

        Frame none = new Frame(0, 0, 6, 1, indices).codeSize(3);
        pixels = GifDecoder.parse(GifTestFiles.gif(6, 1, null, none)).advance();
        assertArrayEquals(new int[6], pixels);
    }

    /** Delays as browsers show them: none, or 10 ms or less, is 100 ms. */
    @Test
    public void delaysAsBrowsersShowThem() throws IOException {
        byte[] gif = GifTestFiles.gif(1, 1, PALETTE,
                GifTestFiles.fill(0, 0, 1, 1, 0).delay(0),
                GifTestFiles.fill(0, 0, 1, 1, 1).delay(1),
                GifTestFiles.fill(0, 0, 1, 1, 2).delay(2),
                GifTestFiles.fill(0, 0, 1, 1, 3).delay(70),
                GifTestFiles.fill(0, 0, 1, 1, 0).withoutControl());
        assertArrayEquals(new int[] {100, 100, 20, 700, 100},
                GifDecoder.parse(gif).getDelays());
    }

    /** A transparent index leaves what is under it. */
    @Test
    public void aTransparentIndexLeavesWhatIsUnder() throws IOException {
        byte[] top = {1, 3, 3, 1};
        int[] pixels = GifDecoder.parse(GifTestFiles.gif(2, 2, PALETTE,
                GifTestFiles.fill(0, 0, 2, 2, 2),
                new Frame(0, 0, 2, 2, top).transparent(3))).seek(1);
        assertArrayEquals(new int[] {GREEN, BLUE, BLUE, GREEN}, pixels);
    }

    /** A file cut short shows what it has: the pixels it never reached stay as they were. */
    @Test
    public void aFileCutShortShowsWhatItHas() throws IOException {
        byte[] whole = GifTestFiles.gif(16, 16, GifTestFiles.greys(),
                GifTestFiles.fill(0, 0, 16, 16, 255),
                new Frame(0, 0, 16, 16, GifTestFiles.noise(256, 3)));
        byte[] cut = new byte[whole.length - 60];
        System.arraycopy(whole, 0, cut, 0, cut.length);
        GifDecoder decoder = GifDecoder.parse(cut);
        assertEquals(2, decoder.getFrameCount());
        int[] pixels = decoder.seek(1);
        // The first rows decoded; the last are still the white of the frame before.
        assertEquals(0xFFFFFFFF, pixels[pixels.length - 1]);
        int[] full = GifDecoder.parse(whole).seek(1);
        assertEquals(full[0], pixels[0]);
        assertNotNull(pixels);
    }

    /** What is no GIF, holds no frame, or is too large to hold is refused. */
    @Test
    public void refusesWhatItCannotShow() {
        assertRefused(new byte[] {'P', 'N', 'G', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        assertRefused(GifTestFiles.gif(4, 4, PALETTE));
        assertRefused(GifTestFiles.gif(0, 0, PALETTE, GifTestFiles.fill(0, 0, 0, 0, 1)));
        // 60000 x 60000 is more than any frame is held at.
        assertRefused(GifTestFiles.gif(60000, 60000, PALETTE, GifTestFiles.fill(0, 0, 1, 1, 1)));
    }

    /** A frame of no size covers the screen. */
    @Test
    public void aFrameOfNoSizeCoversTheScreen() throws IOException {
        GifDecoder decoder = GifDecoder.parse(GifTestFiles.gif(3, 2, PALETTE,
                GifTestFiles.fill(0, 0, 3, 2, 0), new Frame(0, 0, 0, 0, new byte[0])));
        assertEquals(2, decoder.getFrameCount());
        assertEquals(RED, decoder.seek(1)[5]);
    }

    private static void assertRefused(byte[] gif) {
        try {
            GifDecoder.parse(gif);
            fail("Not refused");
        } catch (IOException expected) {
            // As it should be.
        }
    }

    /** Where a pixel of the 16-wide fixture is. */
    private static int at(int x, int y) {
        return y * 16 + x;
    }

    /** A frame's CRC32, as tools take it: each pixel as big-endian ARGB, transparent as 0. */
    private static long crc(int[] pixels) {
        byte[] bytes = new byte[pixels.length * 4];
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i] >>> 24 == 0 ? 0 : pixels[i];
            bytes[4 * i] = (byte) (pixel >>> 24);
            bytes[4 * i + 1] = (byte) (pixel >>> 16);
            bytes[4 * i + 2] = (byte) (pixel >>> 8);
            bytes[4 * i + 3] = (byte) pixel;
        }
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return crc.getValue();
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = GifDecoderTest.class.getResourceAsStream("/media/" + name)) {
            if (in == null) {
                throw new IOException("No test file " + name);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }
}
