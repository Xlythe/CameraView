package com.xlythe.view.camera.viewer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** QuickTime movies with no file type box, given one so Android plays them. */
@RunWith(RobolectricTestRunner.class)
public class QuickTimeCopyTest {
    private static byte[] be32(long value) {
        return new byte[] {(byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8),
                (byte) value};
    }

    private static byte[] box(String type, byte[]... contents) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] content : contents) {
            body.write(content, 0, content.length);
        }
        byte[] data = body.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] size = be32(data.length + 8);
        out.write(size, 0, 4);
        out.write(type.getBytes(StandardCharsets.US_ASCII), 0, 4);
        out.write(data, 0, data.length);
        return out.toByteArray();
    }

    /**
     * A movie that opens with a wide box, then its media, then a moov whose two tracks point
     * at the media: one by a 32-bit chunk offset, one by a 64-bit one. A large-size box and an
     * open-ended last box follow, if asked for.
     */
    static byte[] movie(boolean awkwardBoxes) {
        byte[] wide = box("wide");
        byte[] mdat = box("mdat", "SAMPLE-ONESAMPLE-TWO".getBytes(StandardCharsets.US_ASCII));
        long first = wide.length + 8;
        long second = first + 10;
        byte[] stco = box("stco", new byte[4], be32(1), be32(first));
        byte[] co64 = box("co64", new byte[4], be32(1), be32(0), be32(second));
        byte[] moov = box("moov", box("mvhd", new byte[20]),
                box("trak", box("mdia", box("minf", box("stbl", stco)))),
                box("trak", box("mdia", box("minf", box("stbl", co64)))));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(wide, 0, wide.length);
        out.write(mdat, 0, mdat.length);
        out.write(moov, 0, moov.length);
        if (awkwardBoxes) {
            byte[] large = {0, 0, 0, 1, 'f', 'r', 'e', 'e', 0, 0, 0, 0, 0, 0, 0, 20, 0, 0, 0, 0};
            out.write(large, 0, large.length);
            byte[] openEnded = {0, 0, 0, 0, 'f', 'r', 'e', 'e', 1, 2, 3};
            out.write(openEnded, 0, openEnded.length);
        }
        return out.toByteArray();
    }

    private static String at(byte[] data, long offset) {
        return new String(data, (int) offset, 10, StandardCharsets.US_ASCII);
    }

    private static long u32(byte[] data, int at) {
        return ((data[at] & 0xFFL) << 24) | ((data[at + 1] & 0xFFL) << 16)
                | ((data[at + 2] & 0xFFL) << 8) | (data[at + 3] & 0xFFL);
    }

    @Test
    public void tellsAMovieThatNeedsACopy() {
        assertTrue(QuickTimeCopy.needsCopy(box("wide")));
        assertTrue(QuickTimeCopy.needsCopy(box("moov")));
        assertTrue(QuickTimeCopy.needsCopy(box("mdat")));
        assertFalse(QuickTimeCopy.needsCopy(box("ftyp")));
        assertFalse(QuickTimeCopy.needsCopy(box("RIFF")));
    }

    @Test
    public void everyChunkOffsetMovesWithTheFileType() {
        for (boolean awkward : new boolean[] {false, true}) {
            byte[] movie = movie(awkward);
            byte[] fixed = QuickTimeCopy.withFileType(movie);
            assertEquals("ftyp", new String(fixed, 4, 4, StandardCharsets.US_ASCII));
            assertEquals(movie.length + 20, fixed.length);
            String text = new String(fixed, StandardCharsets.ISO_8859_1);
            int stco = text.indexOf("stco");
            assertEquals("SAMPLE-ONE", at(fixed, u32(fixed, stco + 12)));
            int co64 = text.indexOf("co64");
            assertEquals("SAMPLE-TWO", at(fixed, u32(fixed, co64 + 16)));
        }
    }

    @Test
    public void aCutShortTableIsLeftAsFarAsItGoes() {
        byte[] stco = box("stco", new byte[4], be32(5), be32(8));
        byte[] movie = concat(box("wide"), box("moov", box("trak", box("mdia", box("minf",
                box("stbl", stco))))), new byte[] {0, 0, 0, 99, 'j', 'u', 'n', 'k'});
        byte[] fixed = QuickTimeCopy.withFileType(movie);
        int at = new String(fixed, StandardCharsets.ISO_8859_1).indexOf("stco");
        assertEquals(28, u32(fixed, at + 12));
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }

    @Test
    public void makesTheCopyOnceAndKeepsIt() throws IOException {
        Context context = ApplicationProvider.getApplicationContext();
        File movie = File.createTempFile("quicktime", ".mov");
        movie.deleteOnExit();
        Files.write(movie.toPath(), movie(false));
        File copy = QuickTimeCopy.playable(context, ImageLocation.of(movie));
        assertNotNull(copy);
        assertEquals("ftyp", new String(Files.readAllBytes(copy.toPath()), 4, 4,
                StandardCharsets.US_ASCII));
        long made = copy.lastModified();
        assertEquals(copy, QuickTimeCopy.playable(context, ImageLocation.of(movie)));
        assertEquals(made, copy.lastModified());

        File branded = File.createTempFile("branded", ".mp4");
        branded.deleteOnExit();
        Files.write(branded.toPath(), concat(box("ftyp", "qt  ".getBytes(
                StandardCharsets.US_ASCII)), box("moov")));
        assertNull(QuickTimeCopy.playable(context, ImageLocation.of(branded)));
        File tiny = File.createTempFile("tiny", ".mov");
        tiny.deleteOnExit();
        Files.write(tiny.toPath(), new byte[4]);
        assertNull(QuickTimeCopy.playable(context, ImageLocation.of(tiny)));
        assertTrue(copy.delete());
    }
}
