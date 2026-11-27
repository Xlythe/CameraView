package com.xlythe.view.camera;

import static com.xlythe.view.camera.MediaFixtures.ascii;
import static com.xlythe.view.camera.MediaFixtures.concat;
import static com.xlythe.view.camera.MediaFixtures.file;
import static com.xlythe.view.camera.MediaFixtures.png;
import static com.xlythe.view.camera.MediaFixtures.read;
import static com.xlythe.view.camera.MediaFixtures.riff;
import static com.xlythe.view.camera.MediaFixtures.riffChunk;
import static com.xlythe.view.camera.MediaFixtures.tiff;
import static com.xlythe.view.camera.MediaFixtures.utf8;
import static com.xlythe.view.camera.MediaFixtures.webp;
import static com.xlythe.view.camera.MediaFixtures.webpLossless;
import static com.xlythe.view.camera.MediaFixtures.xmp;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.location.Location;

import androidx.exifinterface.media.ExifInterface;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/** PNG, WebP and TIFF: Exif in a block of their own, and each format's other places. */
@RunWith(RobolectricTestRunner.class)
public class MediaTagsImageTest {
    // ---------- PNG ----------

    @Test
    public void pngKeepsTextFieldsAsItsOwnKeywords() throws IOException {
        File file = file(png(), ".png");
        MediaTags tags = MediaTags.open(file);
        assertEquals(MediaTags.FORMAT_PNG, tags.getFormat());
        assertTrue(tags.setTitle("Sunset"));
        assertTrue(tags.setAuthor("Łukasz"));
        assertTrue(tags.setDescription("Over the sea"));
        assertTrue(tags.setCopyright("2026"));
        assertTrue(tags.setSoftware("App"));
        assertTrue(tags.setMake("Maker"));
        assertTrue(tags.setCaptureTime(1_700_000_000_000L));
        assertTrue(tags.setOrientation(180, false));
        tags.save();

        byte[] bytes = read(file);
        assertValidPng(bytes);
        String text = MediaTagsJpegTest.text(bytes);
        assertTrue(text.contains("tEXtTitle\u0000Sunset"));
        // Text beyond Latin-1 goes in an international chunk.
        assertTrue(text.contains("iTXtAuthor"));
        assertTrue(text.indexOf("eXIf") < text.indexOf("IDAT"));
        MediaTags read = MediaTags.open(file);
        assertEquals("Sunset", read.getTitle());
        assertEquals("Łukasz", read.getAuthor());
        assertEquals("Over the sea", read.getDescription());
        assertEquals("2026", read.getCopyright());
        assertEquals("App", read.getSoftware());
        assertEquals("Maker", read.getMake());
        assertEquals(1_700_000_000_000L, read.getCaptureTime());
        assertEquals(180, read.getRotation());

        // Changed again, each keyword appears once.
        read.setTitle("Dawn");
        read.save();
        text = MediaTagsJpegTest.text(read(file));
        assertEquals(text.indexOf("Title\u0000"), text.lastIndexOf("Title\u0000"));
        assertEquals("Dawn", MediaTags.open(file).getTitle());
    }

    @Test
    public void pngReadsEveryKindOfTextChunk() throws IOException {
        byte[] compressed = deflate(ascii("Squeezed"));
        byte[] png = png(PngContainer.chunk("zTXt", concat(ascii("Title\u0000\u0000"),
                compressed)), PngContainer.chunk("iTXt", concat(ascii("Author\u0000\u0001\u0000"
                + "en\u0000Auteur\u0000"), deflate(utf8("Zoë")))),
                PngContainer.chunk("tEXt", ascii("Creation Time\u0000Mon, 13 Nov 2023 22:13:20 "
                + "GMT")));
        MediaTags tags = MediaTags.read(png);
        assertEquals("Squeezed", tags.getTitle());
        assertEquals("Zoë", tags.getAuthor());
        assertEquals(1_699_913_600_000L, tags.getCaptureTime());
        assertNull(PngContainer.readText("tEXt", ascii("NoTerminator")));
        assertNull(PngContainer.readText("zTXt", ascii("Title\u0000\u0001junk")));
        assertNull(PngContainer.readText("iTXt", ascii("Title\u0000")));
        assertEquals(-1, PngContainer.parseCreationTime(null));
        assertEquals(1_699_913_600_000L,
                PngContainer.parseCreationTime(PngContainer.formatCreationTime(1_699_913_600_000L)));
    }

    @Test
    public void pngUpdatesItsCreationTimeKeywordWhereItHasOne() throws IOException {
        File file = file(png(PngContainer.chunk("tEXt", ascii("Creation Time\u0000old"))),
                ".png");
        MediaTags tags = MediaTags.open(file);
        tags.setCaptureTime(1_699_913_600_000L);
        tags.save();
        assertTrue(MediaTagsJpegTest.text(read(file))
                .contains("Creation Time\u0000Mon, 13 Nov 2023 22:13:20 GMT"));
    }

    @Test
    public void pngKeepsExifInItsOwnChunkAndXmpInText() throws IOException {
        byte[] packet = utf8(xmp("xmlns:GPano=\"" + Xmp.NS_GPANO + "\"",
                "GPano:ProjectionType=\"equirectangular\""));
        File file = file(png(PngContainer.chunk("eXIf", tiff()), PngContainer.chunk("iTXt",
                PngContainer.textChunk(PngContainer.XMP_KEYWORD, new String(packet,
                        java.nio.charset.StandardCharsets.UTF_8), true))), ".png");
        MediaTags tags = MediaTags.open(file);
        assertEquals("equirectangular", tags.getPanorama().getProjectionType());
        assertTrue(tags.setPanorama(MediaTagsFixtures.everyGPanoField()));
        Location location = new Location("test");
        location.setLatitude(1);
        location.setLongitude(2);
        assertTrue(tags.setLocation(location));
        assertTrue(tags.setExifTag(ExifFixtures.TAG_UNDEFINED_BY_ANY_STANDARD, Exif.TYPE_SHORT,
                "3"));
        tags.save();
        assertValidPng(read(file));
        MediaTags read = MediaTags.open(file);
        assertEquals(MediaTagsFixtures.everyGPanoField(), read.getPanorama());
        assertEquals(1, read.getLocation().getLatitude(), 1e-6);
        assertEquals("3", read.getExifTag(ExifFixtures.TAG_UNDEFINED_BY_ANY_STANDARD));
        String text = MediaTagsJpegTest.text(read(file));
        assertEquals(text.indexOf("eXIf"), text.lastIndexOf("eXIf"));
        assertEquals(text.indexOf(PngContainer.XMP_KEYWORD),
                text.lastIndexOf(PngContainer.XMP_KEYWORD));
    }

    @Test
    public void pngReadsImageMagicksHexadecimalProfiles() throws IOException {
        byte[] exif = concat(ExifImageContainer.EXIF_IDENTIFIER, exifWithOrientation(6));
        StringBuilder hex = new StringBuilder("\nexif\n    " + exif.length + "\n");
        for (byte b : exif) {
            hex.append(String.format("%02x", b & 0xFF));
        }
        File file = file(png(PngContainer.chunk("tEXt", concat(ascii(
                PngContainer.RAW_PROFILE + "exif\u0000"), ascii(hex.toString())))), ".png");
        MediaTags tags = MediaTags.open(file);
        assertEquals(90, tags.getRotation());
        tags.setOrientation(0, false);
        tags.save();
        String text = MediaTagsJpegTest.text(read(file));
        // The old profile goes once the Exif is written where PNG keeps it now.
        assertFalse(text.contains(PngContainer.RAW_PROFILE));
        assertEquals(0, MediaTags.open(file).getRotation());
        assertNull(PngContainer.rawProfile("exif"));
        assertNull(PngContainer.rawProfile("\nexif\n  x\n00"));
        assertNull(PngContainer.rawProfile("\nexif\n  4\n00"));
    }

    // ---------- WebP ----------

    @Test
    public void simpleWebpGainsAnExtendedHeader() throws IOException {
        File file = file(webp(640, 480), ".webp");
        MediaTags tags = MediaTags.open(file);
        assertEquals(MediaTags.FORMAT_WEBP, tags.getFormat());
        assertTrue(tags.setTitle("Lossy"));
        assertTrue(tags.setXmp("http://example.com/ns/", "Rating", "3"));
        tags.save();

        byte[] bytes = read(file);
        assertEquals("VP8X", MediaTagsJpegTest.text(bytes).substring(12, 16));
        int flags = bytes[20];
        assertEquals(WebpContainer.FLAG_EXIF | WebpContainer.FLAG_XMP,
                flags & (WebpContainer.FLAG_EXIF | WebpContainer.FLAG_XMP));
        assertEquals(639, Bytes.u24le(bytes, 24));
        assertEquals(479, Bytes.u24le(bytes, 27));
        assertEquals(bytes.length - 8, Bytes.u32le(bytes, 4));
        MediaTags read = MediaTags.open(file);
        assertEquals("Lossy", read.getTitle());
        assertEquals("3", read.getXmp("http://example.com/ns/", "Rating"));

        read.setXmp("http://example.com/ns/", "Rating", null);
        read.save();
        assertEquals(0, read(file)[20] & WebpContainer.FLAG_XMP);
        assertEquals("Lossy", MediaTags.open(file).getTitle());
    }

    @Test
    public void losslessWebpKeepsItsAlpha() throws IOException {
        File file = file(webpLossless(100, 50), ".webp");
        MediaTags tags = MediaTags.open(file);
        assertTrue(tags.setOrientation(90, false));
        tags.save();
        byte[] bytes = read(file);
        assertEquals(0x10, bytes[20] & 0x10);
        assertEquals(99, Bytes.u24le(bytes, 24));
        assertEquals(49, Bytes.u24le(bytes, 27));
        assertEquals(90, MediaTags.open(file).getRotation());
    }

    @Test
    public void webpReadsExifWrittenWithItsJpegIdentifier() throws IOException {
        byte[] vp8x = new byte[10];
        vp8x[0] = WebpContainer.FLAG_EXIF;
        byte[] webp = riff("WEBP", riffChunk("VP8X", vp8x), riffChunk("VP8 ", new byte[30]),
                riffChunk("EXIF", concat(ExifImageContainer.EXIF_IDENTIFIER,
                        exifWithOrientation(8))));
        assertEquals(270, MediaTags.read(webp).getRotation());
        try {
            MediaTags.read(riff("WEBP", riffChunk("ALPH", new byte[3])));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test(expected = IOException.class)
    public void aWebpWithNoPictureCannotGainAHeader() throws IOException {
        File file = file(riff("WEBP", riffChunk("ALPH", new byte[3])), ".webp");
        MediaTags tags = MediaTags.open(file);
        tags.setTitle("Nowhere to put it");
        tags.save();
    }

    // ---------- TIFF ----------

    @Test
    public void tiffIsReadOnly() throws IOException {
        byte[] tiff = exifWithOrientation(3);
        File file = file(tiff, ".tif");
        MediaTags tags = MediaTags.open(file);
        assertEquals(MediaTags.FORMAT_TIFF, tags.getFormat());
        assertEquals(180, tags.getRotation());
        assertTrue(tags.isReadOnly());
        assertFalse(tags.setTitle("x"));
        assertFalse(tags.setXmp("http://example.com/ns/", "a", "b"));
        assertFalse(tags.setExifTag(ExifFixtures.TAG_UNDEFINED_BY_ANY_STANDARD,
                Exif.TYPE_SHORT, "1"));
    }

    @Test
    public void rawHeadersAreTiff() {
        assertEquals(MediaTags.FORMAT_TIFF, MediaTags.detect(ascii("IIRO\u0008\u0000\u0000\u0000")));
        assertEquals(MediaTags.FORMAT_TIFF, MediaTags.detect(ascii("IIU\u0000\u0008\u0000\u0000")));
        assertEquals(MediaTags.FORMAT_TIFF, MediaTags.detect(ascii("FUJIFILMCCD-RAW 0201")));
    }

    // ---------- Helpers ----------

    /** A TIFF block whose IFD0 has only an orientation. */
    static byte[] exifWithOrientation(int orientation) {
        byte[] tiff = new byte[8 + 2 + 12 + 4];
        tiff[0] = 'M';
        tiff[1] = 'M';
        tiff[3] = 42;
        Bytes.put32(tiff, 4, 8);
        Bytes.put16(tiff, 8, 1);
        Bytes.put16(tiff, 10, 0x0112);
        Bytes.put16(tiff, 12, 3);
        Bytes.put32(tiff, 14, 1);
        Bytes.put16(tiff, 18, orientation);
        return tiff;
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[256];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        return out.toByteArray();
    }

    /** Every chunk's length fits and its CRC is right. */
    private static void assertValidPng(byte[] png) {
        int at = 8;
        while (at < png.length) {
            int length = (int) Bytes.u32(png, at);
            CRC32 crc = new CRC32();
            crc.update(png, at + 4, length + 4);
            assertEquals(crc.getValue(), Bytes.u32(png, at + 8 + length));
            at += 12 + length;
        }
        assertEquals(png.length, at);
    }

    @Test
    public void exifIsUnwrappedAndRecognised() {
        assertNull(ExifImageContainer.tiffOf(null));
        assertNull(ExifImageContainer.tiffOf(ascii("Exif\u0000\u0000xx")));
        assertNotNull(ExifImageContainer.tiffOf(exifWithOrientation(1)));
        assertArrayEquals(exifWithOrientation(1), ExifImageContainer.unwrap(
                ExifImageContainer.wrap(exifWithOrientation(1))));
        assertNull(ExifImageContainer.unwrap(new byte[] {(byte) 0xFF, (byte) 0xD8}));
        assertEquals(ExifInterface.ORIENTATION_TRANSVERSE, MediaTags.toExifOrientation(90, true));
    }
}
