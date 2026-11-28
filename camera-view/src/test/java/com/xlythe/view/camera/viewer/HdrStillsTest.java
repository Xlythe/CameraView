package com.xlythe.view.camera.viewer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Pictures whose own pixels are HDR: which colour space each names, and what it decodes into. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37)
public class HdrStillsTest {
    /** H.273: BT.2020 primaries, the PQ transfer, an identity matrix, full range. */
    private static final byte[] CICP_PQ = {9, 16, 0, 1};

    /** H.273: BT.2020 primaries, the HLG transfer. */
    private static final byte[] CICP_HLG = {9, 18, 0, 1};

    /** H.273: BT.709 primaries, the sRGB transfer. */
    private static final byte[] CICP_SRGB = {1, 13, 0, 1};

    // ---------- The PNG cICP chunk ----------

    @Test
    public void aCicpChunkBeforeThePixelsIsRead() throws IOException {
        byte[] png = png(chunk("IHDR", new byte[13]), chunk("gAMA", new byte[4]),
                chunk("cICP", CICP_PQ), chunk("IDAT", new byte[8]), chunk("IEND", new byte[0]));
        assertArrayEquals(new int[] {9, 16}, HdrStills.readPngCicp(stream(png)));
    }

    /** The PNG specification puts cICP before the pixels; one after them is not looked for. */
    @Test
    public void aCicpChunkAfterThePixelsIsNot() throws IOException {
        byte[] png = png(chunk("IHDR", new byte[13]), chunk("IDAT", new byte[8]),
                chunk("cICP", CICP_PQ), chunk("IEND", new byte[0]));
        assertNull(HdrStills.readPngCicp(stream(png)));
    }

    @Test
    public void aPngWithoutOneHasNone() throws IOException {
        byte[] png = png(chunk("IHDR", new byte[13]), chunk("IDAT", new byte[8]),
                chunk("IEND", new byte[0]));
        assertNull(HdrStills.readPngCicp(stream(png)));
    }

    @Test
    public void anythingButAPngHasNone() throws IOException {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0, 16, 'E', 'x', 'i',
                'f', 0, 0, 0, 0, 0, 0};
        assertNull(HdrStills.readPngCicp(stream(jpeg)));
    }

    /** A chunk claiming more than the file holds ends the search rather than reading on. */
    @Test
    public void aTruncatedPngNamesNoColourSpace() {
        byte[] png = png(chunk("IHDR", new byte[13]));
        byte[] truncated = new byte[png.length + 8];
        System.arraycopy(png, 0, truncated, 0, png.length);
        // A chunk of 4 GB less one byte, of a type nothing reads.
        truncated[png.length] = (byte) 0xFF;
        truncated[png.length + 1] = (byte) 0xFF;
        truncated[png.length + 2] = (byte) 0xFF;
        truncated[png.length + 3] = (byte) 0xFE;
        truncated[png.length + 4] = 't';
        truncated[png.length + 5] = 'E';
        truncated[png.length + 6] = 'X';
        truncated[png.length + 7] = 't';
        assertNull(HdrStills.declaredHdrColorSpace(stream(truncated)));
    }

    // ---------- The colour space it names ----------

    @Test
    public void aPqChunkNamesBt2020Pq() {
        byte[] png = png(chunk("IHDR", new byte[13]), chunk("cICP", CICP_PQ),
                chunk("IDAT", new byte[8]));
        assertEquals(ColorSpace.get(ColorSpace.Named.BT2020_PQ),
                HdrStills.declaredHdrColorSpace(stream(png)));
    }

    @Test
    public void anHlgChunkNamesBt2020Hlg() {
        byte[] png = png(chunk("IHDR", new byte[13]), chunk("cICP", CICP_HLG),
                chunk("IDAT", new byte[8]));
        assertEquals(ColorSpace.get(ColorSpace.Named.BT2020_HLG),
                HdrStills.declaredHdrColorSpace(stream(png)));
    }

    /** An SDR colour space is the decoder's business, and needs nothing applied. */
    @Test
    public void anSdrChunkNamesNone() {
        byte[] png = png(chunk("IHDR", new byte[13]), chunk("cICP", CICP_SRGB),
                chunk("IDAT", new byte[8]));
        assertNull(HdrStills.declaredHdrColorSpace(stream(png)));
    }

    @Test
    public void onlyBt2020WithPqOrHlgIsHdr() {
        assertEquals(ColorSpace.get(ColorSpace.Named.BT2020_PQ), HdrStills.namedHdr(9, 16));
        assertEquals(ColorSpace.get(ColorSpace.Named.BT2020_HLG), HdrStills.namedHdr(9, 18));
        assertNull("BT.709 primaries", HdrStills.namedHdr(1, 16));
        assertNull("BT.2020 in SDR", HdrStills.namedHdr(9, 14));
    }

    /** Android names no HDR colour space before 14, so it decodes such a picture to SDR. */
    @Test
    @Config(sdk = 33)
    public void beforeAndroid14NothingIsDeclared() {
        byte[] png = png(chunk("IHDR", new byte[13]), chunk("cICP", CICP_PQ),
                chunk("IDAT", new byte[8]));
        assertNull(HdrStills.declaredHdrColorSpace(stream(png)));
        assertFalse(HdrStills.isHdr(bounds(null, Bitmap.Config.RGBA_1010102)));
        assertEquals(Bitmap.Config.ARGB_8888,
                HdrStills.config(bounds(null, Bitmap.Config.RGBA_1010102)));
    }

    // ---------- What it decodes into ----------

    @Test
    public void theDecodersHdrColourSpacesAreHdr() {
        assertTrue(HdrStills.isHdr(ColorSpace.get(ColorSpace.Named.BT2020_HLG)));
        assertTrue(HdrStills.isHdr(ColorSpace.get(ColorSpace.Named.BT2020_PQ)));
        assertFalse(HdrStills.isHdr(ColorSpace.get(ColorSpace.Named.BT2020)));
        assertFalse(HdrStills.isHdr(ColorSpace.get(ColorSpace.Named.DISPLAY_P3)));
        assertFalse(HdrStills.isHdr((ColorSpace) null));
        assertTrue(HdrStills.isHdr(
                bounds(ColorSpace.get(ColorSpace.Named.BT2020_PQ), Bitmap.Config.RGBA_1010102)));
        assertFalse(HdrStills.isHdr(
                bounds(ColorSpace.get(ColorSpace.Named.SRGB), Bitmap.Config.ARGB_8888)));
    }

    /** Ten bits a channel for an HDR picture stored with ten, which costs what eight would. */
    @Test
    public void aTenBitHdrPictureDecodesToTenBits() {
        assertEquals(Bitmap.Config.RGBA_1010102, HdrStills.config(
                bounds(ColorSpace.get(ColorSpace.Named.BT2020_HLG), Bitmap.Config.RGBA_1010102)));
    }

    @Test
    public void everythingElseDecodesToEightBits() {
        assertEquals("an 8-bit HDR picture", Bitmap.Config.ARGB_8888, HdrStills.config(
                bounds(ColorSpace.get(ColorSpace.Named.BT2020_PQ), Bitmap.Config.ARGB_8888)));
        assertEquals("a 10-bit SDR picture", Bitmap.Config.ARGB_8888, HdrStills.config(
                bounds(ColorSpace.get(ColorSpace.Named.BT2020), Bitmap.Config.RGBA_1010102)));
        assertEquals("a picture that names nothing", Bitmap.Config.ARGB_8888,
                HdrStills.config(new BitmapFactory.Options()));
    }

    // ---------- Helpers ----------

    private static BitmapFactory.Options bounds(ColorSpace colorSpace, Bitmap.Config config) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.outColorSpace = colorSpace;
        options.outConfig = config;
        return options;
    }

    private static ByteArrayInputStream stream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }

    /** A PNG of these chunks, after the signature. */
    private static byte[] png(byte[]... chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'}, 0, 8);
        for (byte[] chunk : chunks) {
            out.write(chunk, 0, chunk.length);
        }
        return out.toByteArray();
    }

    /** One chunk: its length, type, data and a CRC, which nothing here checks. */
    private static byte[] chunk(String type, byte[] data) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(data.length);
            out.write(type.getBytes(StandardCharsets.US_ASCII));
            out.write(data);
            out.writeInt(0);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
