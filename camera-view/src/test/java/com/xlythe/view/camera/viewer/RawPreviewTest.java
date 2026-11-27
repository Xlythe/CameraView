package com.xlythe.view.camera.viewer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
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
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/** The JPEGs camera raws carry, found by their own structure, the largest chosen. */
@RunWith(RobolectricTestRunner.class)
public class RawPreviewTest {
    /** A JPEG's header of a size and frame marker, padded to a length. */
    static byte[] jpeg(int width, int height, int frame, int length) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8);
        // A fill byte, as some writers put between markers.
        out.write(0xFF);
        out.write(0xFF);
        out.write(0xE0);
        int padding = Math.max(16, length - 4 - 19 - 2 - 2);
        out.write((padding + 2) >> 8);
        out.write(padding + 2);
        out.write(new byte[padding], 0, padding);
        out.write(0xFF);
        out.write(frame);
        out.write(0);
        out.write(17);
        out.write(8);
        out.write(height >> 8);
        out.write(height);
        out.write(width >> 8);
        out.write(width);
        out.write(new byte[10], 0, 10);
        out.write(0xFF);
        out.write(0xD9);
        return out.toByteArray();
    }

    /** Builds a TIFF-like file a piece at a time, with offsets resolved as it goes. */
    static final class Tiff {
        /** The bytes so far. */
        final ByteArrayOutputStream mOut = new ByteArrayOutputStream();

        /** Whether numbers are little endian. */
        final boolean mLittle;

        Tiff(String magic, boolean little) {
            mLittle = little;
            write(magic.getBytes(StandardCharsets.US_ASCII));
            put32(8);
        }

        int at() {
            return mOut.size();
        }

        void write(byte[] bytes) {
            mOut.write(bytes, 0, bytes.length);
        }

        void put16(int value) {
            write(mLittle ? new byte[] {(byte) value, (byte) (value >> 8)}
                    : new byte[] {(byte) (value >> 8), (byte) value});
        }

        void put32(long value) {
            byte[] b = {(byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8),
                    (byte) value};
            if (mLittle) {
                b = new byte[] {b[3], b[2], b[1], b[0]};
            }
            write(b);
        }

        /** Writes an IFD of entries, each {tag, type, count, value}, and the next IFD. */
        void ifd(long next, long[]... entries) {
            put16(entries.length);
            for (long[] entry : entries) {
                put16((int) entry[0]);
                put16((int) entry[1]);
                put32(entry[2]);
                if (entry[1] == 3 && entry[2] == 1) {
                    put16((int) entry[3]);
                    put16(0);
                } else {
                    put32(entry[3]);
                }
            }
            put32(next);
        }

        byte[] bytes() {
            return mOut.toByteArray();
        }
    }

    private static RawPreview.Found find(byte[] file) throws IOException {
        File temp = File.createTempFile("raw", ".bin");
        temp.deleteOnExit();
        Files.write(temp.toPath(), file);
        try (FileInputStream in = new FileInputStream(temp)) {
            return RawPreview.find(in.getChannel());
        }
    }

    /** A Canon CR2: a full-size JPEG as IFD0's strip, a thumbnail, and lossless sensor data. */
    static byte[] cr2() {
        byte[] full = jpeg(3000, 2000, 0xC0, 4000);
        byte[] thumbnail = jpeg(160, 120, 0xC0, 1500);
        byte[] lossless = jpeg(4000, 3000, 0xC3, 5000);
        Tiff tiff = new Tiff("II*\0", true);
        int ifd0 = 8;
        int ifd1 = ifd0 + 2 + 4 * 12 + 4;
        int ifd3 = ifd1 + 2 + 2 * 12 + 4;
        int data = ifd3 + 2 + 3 * 12 + 4;
        tiff.ifd(ifd1, new long[] {0x103, 3, 1, 6}, new long[] {0x111, 4, 1, data},
                new long[] {0x117, 4, 1, full.length}, new long[] {0x14A, 4, 1, ifd3});
        tiff.ifd(0, new long[] {0x201, 4, 1, data + full.length},
                new long[] {0x202, 4, 1, thumbnail.length});
        tiff.ifd(0, new long[] {0x103, 3, 1, 6},
                new long[] {0x111, 4, 1, data + full.length + thumbnail.length},
                new long[] {0x117, 4, 1, lossless.length});
        tiff.write(full);
        tiff.write(thumbnail);
        tiff.write(lossless);
        return tiff.bytes();
    }

    @Test
    public void aCr2ShowsItsFullSizeJpegNotItsSensorData() throws IOException {
        RawPreview.Found found = find(cr2());
        assertNotNull(found);
        assertEquals(3000, found.width);
        assertEquals(2000, found.height);
    }

    @Test
    public void aNefKeepsItsPreviewInASubIfd() throws IOException {
        byte[] preview = jpeg(3008, 2000, 0xC0, 3000);
        byte[] smaller = jpeg(640, 424, 0xC0, 1200);
        Tiff tiff = new Tiff("MM\0*", false);
        int ifd0 = 8;
        int array = ifd0 + 2 + 2 * 12 + 4;
        int sub0 = array + 8;
        int sub1 = sub0 + 2 + 2 * 12 + 4;
        int exif = sub1 + 2 + 2 * 12 + 4;
        int data = exif + 2 + 12 + 4;
        tiff.ifd(0, new long[] {0x14A, 4, 2, array}, new long[] {0x8769, 4, 1, exif});
        tiff.put32(sub0);
        tiff.put32(sub1);
        tiff.ifd(0, new long[] {0x201, 4, 1, data}, new long[] {0x202, 4, 1, preview.length});
        tiff.ifd(0, new long[] {0x201, 4, 1, data + preview.length},
                new long[] {0x202, 4, 1, smaller.length});
        // An IFD pointer of its own type, which points back at IFD0 and is not walked twice.
        tiff.ifd(0, new long[] {0x9999, 13, 1, ifd0});
        tiff.write(preview);
        tiff.write(smaller);
        RawPreview.Found found = find(tiff.bytes());
        assertNotNull(found);
        assertEquals(3008, found.width);
    }

    @Test
    public void anOrfKeepsItsPreviewInItsMakerNote() throws IOException {
        byte[] preview = jpeg(3200, 2400, 0xC0, 2500);
        Tiff tiff = new Tiff("IIRO", true);
        int ifd0 = 8;
        int exif = ifd0 + 2 + 12 + 4;
        int note = exif + 2 + 12 + 4;
        // The note: its signature, then an IFD whose offsets count from the note's start.
        int noteIfd = 12;
        int settings = noteIfd + 2 + 12 + 4;
        int previewAt = settings + 2 + 3 * 12 + 4;
        int noteLength = previewAt + preview.length;
        tiff.ifd(0, new long[] {0x8769, 4, 1, exif});
        tiff.ifd(0, new long[] {0x927C, 7, noteLength, note});
        tiff.write("OLYMPUS\0II".getBytes(StandardCharsets.US_ASCII));
        tiff.put16(3);
        tiff.ifd(0, new long[] {0x2020, 13, 1, settings});
        tiff.ifd(0, new long[] {0x100, 4, 1, 1}, new long[] {0x101, 4, 1, previewAt},
                new long[] {0x102, 4, 1, preview.length});
        tiff.write(preview);
        RawPreview.Found found = find(tiff.bytes());
        assertNotNull(found);
        assertEquals(3200, found.width);
        assertEquals(note + previewAt, found.offset);
    }

    @Test
    public void anRw2KeepsAWholeJpegAsOneTag() throws IOException {
        byte[] preview = jpeg(1920, 1080, 0xC0, 2000);
        Tiff tiff = new Tiff("IIU\0", true);
        int data = 8 + 2 + 12 + 4;
        tiff.ifd(0, new long[] {0x2E, 7, preview.length, data});
        tiff.write(preview);
        RawPreview.Found found = find(tiff.bytes());
        assertNotNull(found);
        assertEquals(1920, found.width);
        assertEquals(data, found.offset);
    }

    @Test
    public void aRafSaysWhereItsJpegIsInItsHeader() throws IOException {
        byte[] preview = jpeg(2048, 1536, 0xC0, 1800);
        byte[] header = new byte[148];
        byte[] magic = "FUJIFILMCCD-RAW 0201".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, header, 0, magic.length);
        header[84 + 3] = (byte) 148;
        header[88 + 2] = (byte) (preview.length >> 8);
        header[88 + 3] = (byte) preview.length;
        byte[] file = Arrays.copyOf(header, header.length + preview.length);
        System.arraycopy(preview, 0, file, header.length, preview.length);
        RawPreview.Found found = find(file);
        assertNotNull(found);
        assertEquals(2048, found.width);
    }

    private static byte[] box(String type, byte[]... contents) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] content : contents) {
            body.write(content, 0, content.length);
        }
        byte[] data = body.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int size = data.length + 8;
        out.write(new byte[] {(byte) (size >> 24), (byte) (size >> 16), (byte) (size >> 8),
                (byte) size}, 0, 4);
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        out.write(name, 0, 4);
        out.write(data, 0, data.length);
        return out.toByteArray();
    }

    private static byte[] be32(long value) {
        return new byte[] {(byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8),
                (byte) value};
    }

    /** A Canon CR3: a track whose first sample is the full-size JPEG, and a PRVW preview. */
    static byte[] cr3(boolean wide) {
        byte[] full = jpeg(6000, 4000, 0xC0, 3000);
        byte[] small = jpeg(1620, 1080, 0xC0, 1500);
        byte[] ftyp = box("ftyp", "crx ".getBytes(StandardCharsets.US_ASCII), new byte[4]);
        byte[] prvw = box("PRVW", new byte[12], be32(small.length), small);
        byte[] uuid = box("uuid", new byte[16], new byte[8], prvw);
        // The moov is built twice: the second time knows where the mdat's sample lands.
        byte[] moov = null;
        for (int pass = 0; pass < 2; pass++) {
            long sample = moov == null ? 0 : ftyp.length + moov.length + uuid.length + 8;
            byte[] offsets = wide
                    ? box("co64", new byte[4], be32(1), be32(0), be32(sample))
                    : box("stco", new byte[4], be32(1), be32(sample));
            byte[] sizes = wide
                    ? box("stsz", new byte[4], be32(full.length), be32(1))
                    : box("stsz", new byte[4], be32(0), be32(1), be32(full.length));
            moov = box("moov", box("trak", box("mdia", box("minf", box("stbl", offsets,
                    sizes)))), box("trak", box("tkhd", new byte[20])));
        }
        return concat(ftyp, moov, uuid, box("mdat", full));
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }

    @Test
    public void aCr3ShowsTheJpegItsFirstTrackHolds() throws IOException {
        for (boolean wide : new boolean[] {false, true}) {
            RawPreview.Found found = find(cr3(wide));
            assertNotNull(found);
            assertEquals(6000, found.width);
        }
    }

    @Test
    public void aCr3WithOnlyItsPreviewShowsThat() throws IOException {
        byte[] small = jpeg(1620, 1080, 0xC0, 1500);
        byte[] prvw = box("PRVW", new byte[12], be32(small.length), small);
        byte[] file = concat(box("ftyp", "crx ".getBytes(StandardCharsets.US_ASCII)),
                box("uuid", new byte[16], new byte[8], prvw));
        RawPreview.Found found = find(file);
        assertNotNull(found);
        assertEquals(1620, found.width);
    }

    @Test
    public void findsNothingWhereThereIsNothing() throws IOException {
        assertNull("empty", find(new byte[0]));
        assertNull("not a raw", find(concat(new byte[] {(byte) 0x89, 'P', 'N', 'G'},
                new byte[40])));
        Tiff tiff = new Tiff("II*\0", true);
        // A strip that is not a JPEG, and one that runs past the end.
        tiff.ifd(0, new long[] {0x103, 3, 1, 7}, new long[] {0x111, 4, 1, 8},
                new long[] {0x117, 4, 1, 2000}, new long[] {0x201, 4, 1, 8},
                new long[] {0x202, 4, 1, 99999});
        tiff.write(new byte[2100]);
        assertNull(find(tiff.bytes()));
        byte[] boxes = concat(box("ftyp", "crx ".getBytes(StandardCharsets.US_ASCII)),
                new byte[] {0, 0, 0, 4, 'b', 'a', 'd', '!'});
        assertNull("a box too small to be one", find(boxes));
    }

    @Test
    public void readsAJpegsSizeFromItsFrame() {
        assertArrayEquals(new int[] {640, 480}, RawPreview.jpegSize(jpeg(640, 480, 0xC2, 100)));
        assertNull("lossless", RawPreview.jpegSize(jpeg(640, 480, 0xC3, 100)));
        assertNull("lossless, arithmetic", RawPreview.jpegSize(jpeg(640, 480, 0xCB, 100)));
        byte[] cut = Arrays.copyOf(jpeg(640, 480, 0xC0, 100), 30);
        assertArrayEquals(new int[2], RawPreview.jpegSize(cut));
        assertArrayEquals(new int[2], RawPreview.jpegSize(new byte[] {(byte) 0xFF,
                (byte) 0xD8, 0, 0, 0, 0}));
        assertArrayEquals("scan data before a frame", new int[2], RawPreview.jpegSize(
                new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xDA, 0, 2, 0, 0}));
    }

    @Test
    public void theViewerCopiesThePreviewOnce() throws IOException {
        Context context = ApplicationProvider.getApplicationContext();
        File raw = File.createTempFile("raw", ".cr2");
        raw.deleteOnExit();
        byte[] file = cr2();
        Files.write(raw.toPath(), file);
        File preview = ImageSources.rawPreview(context, ImageLocation.of(raw));
        assertNotNull(preview);
        byte[] copied = Files.readAllBytes(preview.toPath());
        assertEquals(jpeg(3000, 2000, 0xC0, 4000).length, copied.length);
        assertEquals(0xD8, copied[1] & 0xFF);
        long made = preview.lastModified();
        assertEquals(preview, ImageSources.rawPreview(context, ImageLocation.of(raw)));
        assertEquals(made, preview.lastModified());

        File notRaw = File.createTempFile("plain", ".bin");
        notRaw.deleteOnExit();
        Files.write(notRaw.toPath(), new byte[64]);
        assertNull(ImageSources.rawPreview(context, ImageLocation.of(notRaw)));
        assertTrue(preview.delete());
    }
}
