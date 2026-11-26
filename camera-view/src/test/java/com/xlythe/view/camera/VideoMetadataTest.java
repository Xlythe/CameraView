package com.xlythe.view.camera;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.Nullable;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Writing user data into an MP4 without moving the media: mdat keeps its position, or every
 * chunk offset would point at the wrong sample.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class VideoMetadataTest {

    /** The file keeps its length, mdat its place, and the fields read back. */
    @Test
    public void theMediaDoesNotMove() throws IOException {
        byte[] mp4 = mp4(512, false);
        File file = fileOf(mp4);

        assertTrue(VideoMetadata.write(file, new VideoMetadata.Builder()
                .set(VideoMetadata.Tag.MAKE, "Acme")
                .set(VideoMetadata.Tag.MODEL, "Phone 1")));

        byte[] out = Files.readAllBytes(file.toPath());
        assertEquals("the file must not change length", mp4.length, out.length);
        assertEquals("and mdat must not move", indexOf(mp4, "mdat"), indexOf(out, "mdat"));
        assertEquals("Acme", VideoMetadata.read(file, VideoMetadata.Tag.MAKE));
        assertEquals("Phone 1", VideoMetadata.read(file, VideoMetadata.Tag.MODEL));
    }

    /** moov grows by exactly what the free box gives up, so the two still meet. */
    @Test
    public void moovGrowsByWhatFreeGivesUp() throws IOException {
        byte[] mp4 = mp4(512, false);
        File file = fileOf(mp4);

        assertTrue(VideoMetadata.write(file,
                new VideoMetadata.Builder().set(VideoMetadata.Tag.TITLE, "A title")));

        byte[] out = Files.readAllBytes(file.toPath());
        int moovGrew = sizeOf(out, "moov") - sizeOf(mp4, "moov");
        int freeShrank = sizeOf(mp4, "free") - sizeOf(out, "free");
        assertTrue(moovGrew > 0);
        assertEquals("what moov gained, free lost", moovGrew, freeShrank);
    }

    /**
     * A file that already has user data, as MediaRecorder leaves one it was given a location,
     * keeps what it had, has a field set again replaced, and gains the rest.
     */
    @Test
    public void existingUserDataIsMergedRatherThanDuplicated() throws IOException {
        byte[] mp4 = mp4(512, true);
        File file = fileOf(mp4);

        assertTrue(VideoMetadata.write(file, new VideoMetadata.Builder()
                .setLocation(37.4220, -122.0841)
                .set(VideoMetadata.Tag.MAKE, "Acme")));

        byte[] out = Files.readAllBytes(file.toPath());
        assertEquals(mp4.length, out.length);
        assertEquals(1, count(out, "udta"));
        assertEquals("+37.4220-122.0841/", VideoMetadata.read(file, VideoMetadata.Tag.LOCATION));
        assertEquals("Acme", VideoMetadata.read(file, VideoMetadata.Tag.MAKE));
        assertEquals("a field it did not set is kept", "Old title",
                VideoMetadata.read(file, VideoMetadata.Tag.TITLE));
    }

    /** A moov at the end of the file grows the file instead of needing padding. */
    @Test
    public void aMoovAtTheEndGrowsTheFile() throws IOException {
        byte[] mp4 = mp4AtEnd();
        File file = fileOf(mp4);

        assertTrue(VideoMetadata.write(file,
                new VideoMetadata.Builder().set(VideoMetadata.Tag.SOFTWARE, "Camera 1.0")));

        byte[] out = Files.readAllBytes(file.toPath());
        assertTrue(out.length > mp4.length);
        assertEquals(indexOf(mp4, "mdat"), indexOf(out, "mdat"));
        assertEquals("Camera 1.0", VideoMetadata.read(file, VideoMetadata.Tag.SOFTWARE));
    }

    /** With no padding to grow into, nothing is written rather than everything being moved. */
    @Test
    public void aFileWithNoRoomIsLeftAlone() throws IOException {
        byte[] mp4 = mp4(0, false);
        File file = fileOf(mp4);

        assertFalse(VideoMetadata.write(file,
                new VideoMetadata.Builder().set(VideoMetadata.Tag.MAKE, "Acme")));
        assertArrayEquals(mp4, Files.readAllBytes(file.toPath()));
    }

    @Test
    public void aFreeBoxTooSmallIsLeftAlone() throws IOException {
        File file = fileOf(mp4(12, false));

        assertFalse(VideoMetadata.write(file, new VideoMetadata.Builder()
                .set(VideoMetadata.Tag.DIRECTOR, "Someone with a long name")));
    }

    @Test
    public void noFieldsWritesNothing() throws IOException {
        File file = fileOf(mp4(512, false));

        assertFalse(VideoMetadata.write(file,
                new VideoMetadata.Builder().set(VideoMetadata.Tag.MAKE, null)));
    }

    @Test
    public void anAbsentFieldReadsAsNull() throws IOException {
        File file = fileOf(mp4(512, false));

        assertNull(VideoMetadata.read(file, VideoMetadata.Tag.TITLE));
        assertNull(VideoMetadata.read(fileOf(new byte[] {1, 2, 3}), VideoMetadata.Tag.TITLE));
    }

    @Test
    public void locationIsWrittenAsIso6709() {
        assertEquals("+37.4220-122.0841/", VideoMetadata.iso6709(37.422, -122.0841));
        assertEquals("-33.8688+151.2093/", VideoMetadata.iso6709(-33.8688, 151.2093));
    }

    /** A text field is its length, the undetermined language, then UTF-8. */
    @Test
    public void textIsStoredAsUtf8() {
        byte[] box = VideoMetadata.textBox(VideoMetadata.Tag.TITLE, "Café");

        assertEquals(box.length, sizeAt(box, 0));
        assertEquals(0xA9, box[4] & 0xFF);
        assertEquals("nam", new String(box, 5, 3, StandardCharsets.US_ASCII));
        assertEquals(5, ((box[8] & 0xFF) << 8) | (box[9] & 0xFF));
        assertEquals(0x55, box[10] & 0xFF);
        assertEquals(0xC4, box[11] & 0xFF);
        assertEquals("Café", new String(box, 12, 5, StandardCharsets.UTF_8));
    }

    /** A moov whose children do not add up is not rewritten. */
    @Test
    public void aMalformedMoovIsRefused() {
        byte[] moov = {0, 0, 0, 20, 'm', 'o', 'o', 'v', 0, 0, 0, 99, 'm', 'v', 'h', 'd',
                0, 0, 0, 0};

        assertNull(VideoMetadata.withUserData(moov,
                new VideoMetadata.Builder().set(VideoMetadata.Tag.MAKE, "Acme").fields()));
    }

    private File fileOf(byte[] contents) throws IOException {
        File file = File.createTempFile("videometadata", ".mp4");
        file.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(contents);
        }
        return file;
    }

    private static int sizeAt(byte[] data, int at) {
        return ((data[at] & 0xFF) << 24) | ((data[at + 1] & 0xFF) << 16)
                | ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
    }

    private static int sizeOf(byte[] data, String type) {
        return sizeAt(data, indexOf(data, type) - 4);
    }

    private static int indexOf(byte[] data, String type) {
        byte[] needle = type.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i + needle.length <= data.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static int count(byte[] data, String type) {
        byte[] needle = type.getBytes(StandardCharsets.US_ASCII);
        int found = 0;
        outer:
        for (int i = 0; i + needle.length <= data.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) continue outer;
            }
            found++;
        }
        return found;
    }

    /**
     * ftyp, a moov, a free box of the given size (none for zero), then mdat.
     *
     * @param withUserData whether moov already holds a title and a float location
     */
    private static byte[] mp4(int freeSize, boolean withUserData) {
        ByteArrayOutputStream moov = new ByteArrayOutputStream();
        box(moov, "mvhd", new byte[92]);
        if (withUserData) {
            ByteArrayOutputStream udta = new ByteArrayOutputStream();
            byte[] title = VideoMetadata.textBox(VideoMetadata.Tag.TITLE, "Old title");
            udta.write(title, 0, title.length);
            byte[] location = VideoMetadata.textBox(VideoMetadata.Tag.LOCATION, "+37.42-122.08/");
            udta.write(location, 0, location.length);
            box(moov, "udta", udta.toByteArray());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        box(out, "ftyp", new byte[] {'i', 's', 'o', 'm'});
        box(out, "moov", moov.toByteArray());
        if (freeSize > 0) {
            box(out, "free", new byte[freeSize - 8]);
        }
        box(out, "mdat", new byte[64]);
        return out.toByteArray();
    }

    /** ftyp, mdat, then moov last, as a muxer that writes moov when it finishes lays it out. */
    private static byte[] mp4AtEnd() {
        ByteArrayOutputStream moov = new ByteArrayOutputStream();
        box(moov, "mvhd", new byte[92]);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        box(out, "ftyp", new byte[] {'i', 's', 'o', 'm'});
        box(out, "mdat", new byte[64]);
        box(out, "moov", moov.toByteArray());
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
