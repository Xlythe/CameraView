package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Writing user data into an MP4 without moving a single byte of the media.
 *
 * <p>The danger this guards against is specific. Chunk offsets in the sample tables are absolute
 * positions into {@code mdat}; grow {@code moov} by inserting bytes and every one of them is
 * silently wrong, giving a file that opens, reports its duration, and then fails the moment
 * anything seeks. So the invariant these tests care about most is not that the metadata is
 * written but that the file is exactly as long as it was and {@code mdat} begins where it
 * began.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class VideoMetadataTest {

    /** The invariant: same length, same mdat position, metadata nonetheless present. */
    @Test
    public void theMediaDoesNotMove() throws IOException {
        byte[] mp4 = mp4WithFreeBox(512);
        int mdatBefore = indexOf(mp4, "mdat");

        byte[] out = write(mp4, new VideoMetadata.Builder().setMake("Acme").setModel("Phone 1"));

        assertNotNull("a file with room should accept metadata", out);
        assertEquals("the file must not change length", mp4.length, out.length);
        assertEquals("and mdat must not move", mdatBefore, indexOf(out, "mdat"));
        assertTrue("the user data should be there", indexOf(out, "udta") > 0);
    }

    /** moov grows by exactly what the free box gives up, so the two still meet. */
    @Test
    public void moovGrowsByWhatFreeGivesUp() throws IOException {
        byte[] mp4 = mp4WithFreeBox(512);
        int moovBefore = boxSizeAt(mp4, indexOf(mp4, "moov") - 4);
        int freeBefore = boxSizeAt(mp4, indexOf(mp4, "free") - 4);

        byte[] out = write(mp4, new VideoMetadata.Builder().setMake("Acme"));

        assertNotNull(out);
        int moovAfter = boxSizeAt(out, indexOf(out, "moov") - 4);
        int freeAfter = boxSizeAt(out, indexOf(out, "free") - 4);
        assertEquals("what moov gained, free lost",
                moovAfter - moovBefore, freeBefore - freeAfter);
    }

    /** With no padding to grow into, nothing is written rather than everything being moved. */
    @Test
    public void aFileWithNoFreeBoxIsLeftAlone() throws IOException {
        assertNull("moving mdat is never worth a manufacturer name",
                write(mp4WithoutFreeBox(), new VideoMetadata.Builder().setMake("Acme")));
    }

    /** Nor is a free box too small to give up what is being asked of it. */
    @Test
    public void aFreeBoxTooSmallIsLeftAlone() throws IOException {
        assertNull(write(mp4WithFreeBox(16), new VideoMetadata.Builder()
                .setMake("A rather long manufacturer name indeed")
                .setModel("And a long model name to go with it")));
    }

    /** A file that already carries user data is not given a second helping. */
    @Test
    public void anExistingUdtaIsNotDuplicated() throws IOException {
        byte[] once = write(mp4WithFreeBox(512), new VideoMetadata.Builder().setMake("Acme"));
        assertNotNull(once);

        assertNull("twice would be two udta boxes in one moov",
                write(once, new VideoMetadata.Builder().setModel("Phone 1")));
    }

    /** Nothing to say means nothing written, rather than an empty box. */
    @Test
    public void noFieldsWritesNothing() throws IOException {
        assertNull(write(mp4WithFreeBox(512), new VideoMetadata.Builder()));
        assertNull("and a blank value is not a value",
                write(mp4WithFreeBox(512), new VideoMetadata.Builder()
                        .setMake("").setModel(null)));
    }

    /** ISO 6709, signed and zero-padded, which is what a reader expects to parse. */
    @Test
    public void locationIsWrittenAsIso6709() {
        assertEquals("+37.4220-122.0840/", VideoMetadata.iso6709(37.422, -122.084));
        assertEquals("the sign is always there, north or south",
                "-33.8688+151.2093/", VideoMetadata.iso6709(-33.8688, 151.2093));
        assertEquals("and zero is positive", "+00.0000+000.0000/",
                VideoMetadata.iso6709(0, 0));
    }

    /** Each field becomes one box whose declared size matches what it occupies. */
    @Test
    public void everyFieldBoxDeclaresItsOwnLength() {
        VideoMetadata.Builder builder = new VideoMetadata.Builder()
                .setMake("Acme").setModel("Phone 1").setSoftware("CameraView");

        byte[] udta = VideoMetadata.buildUdta(builder.fields());

        int declared = boxSizeAt(udta, 0);
        assertEquals("the udta box has to span exactly what was built", udta.length, declared);
        int at = 8;
        int boxes = 0;
        while (at < udta.length) {
            int size = boxSizeAt(udta, at);
            assertTrue("a field box inside the udta must fit in it", at + size <= udta.length);
            at += size;
            boxes++;
        }
        assertEquals("one box per field, and no stray bytes", 3, boxes);
        assertEquals("which must land exactly on the end", udta.length, at);
    }

    /** Text goes in as UTF-8, so a name outside ASCII survives the trip. */
    @Test
    public void textIsStoredAsUtf8() {
        byte[] udta = VideoMetadata.buildUdta(
                new VideoMetadata.Builder().setMake("Ünïcôde").fields());

        String written = new String(udta, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue("the text should survive as itself", written.contains("Ünïcôde"));
    }

    /**
     * Runs the real writer over a file, which is the only version there is.
     *
     * <p>These tests used to drive a second, byte-array implementation of the same algorithm
     * that existed only for them. Two implementations means the tested one and the shipped one
     * can drift, and the shipped one was the untested one - so the array version is gone and
     * this writes the bytes to a file, calls what the library calls, and reads them back.</p>
     *
     * @return the file's contents afterwards, or null if nothing was written
     */
    @Nullable
    private byte[] write(byte[] mp4, VideoMetadata.Builder builder) throws IOException {
        File file = temporaryFile();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(mp4);
        }
        if (!VideoMetadata.write(file, builder)) {
            return null;
        }
        byte[] back = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int read = 0;
            while (read < back.length) {
                int count = in.read(back, read, back.length - read);
                if (count < 0) break;
                read += count;
            }
        }
        return back;
    }

    private File temporaryFile() throws IOException {
        File file = File.createTempFile("videometadata", ".mp4");
        file.deleteOnExit();
        return file;
    }

    private static int boxSizeAt(byte[] data, int at) {
        return ((data[at] & 0xFF) << 24) | ((data[at + 1] & 0xFF) << 16)
                | ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
    }

    private static int indexOf(byte[] data, String type) {
        byte[] needle = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i + needle.length <= data.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /** ftyp, a minimal moov, a free box of the given size, then mdat. */
    private static byte[] mp4WithFreeBox(int freeSize) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        box(out, "ftyp", new byte[] {'i', 's', 'o', 'm'});
        box(out, "moov", mvhd());
        byte[] free = new byte[freeSize - 8];
        box(out, "free", free);
        box(out, "mdat", new byte[64]);
        return out.toByteArray();
    }

    private static byte[] mp4WithoutFreeBox() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        box(out, "ftyp", new byte[] {'i', 's', 'o', 'm'});
        box(out, "moov", mvhd());
        box(out, "mdat", new byte[64]);
        return out.toByteArray();
    }

    /** A moov's children have to be walkable, so the fixture gives it a real one. */
    private static byte[] mvhd() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        box(out, "mvhd", new byte[92]);
        return out.toByteArray();
    }

    private static void box(ByteArrayOutputStream out, String type, byte[] body) {
        int size = 8 + body.length;
        out.write((size >> 24) & 0xFF);
        out.write((size >> 16) & 0xFF);
        out.write((size >> 8) & 0xFF);
        out.write(size & 0xFF);
        for (int i = 0; i < 4; i++) {
            out.write(type.charAt(i));
        }
        out.write(body, 0, body.length);
    }
}
