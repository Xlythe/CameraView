package com.xlythe.view.camera;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * QuickTime's keyed metadata, in moov/meta beside the user data: written in both forms where a
 * field has both, merged into what Android's recorder already put there, and read back.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class VideoMetadataKeysTest {
    private static final String MAKE_KEY = "com.apple.quicktime.make";

    /** A field with both forms is written as both, and the media still does not move. */
    @Test
    public void bothFormsAreWritten() throws IOException {
        byte[] mp4 = mp4(null, 1024);
        File file = fileOf(mp4);

        assertTrue(VideoMetadata.write(file, new VideoMetadata.Builder()
                .set(VideoMetadata.Tag.MAKE, "Acme")
                .set(VideoMetadata.Tag.MODEL, "Phone 1")));

        byte[] out = Files.readAllBytes(file.toPath());
        assertEquals(mp4.length, out.length);
        assertEquals(indexOf(mp4, "mdat"), indexOf(out, "mdat"));
        assertEquals("a user-data box", 1, count(out, "©mak"));
        assertEquals("and a key", 1, count(out, MAKE_KEY));
        assertEquals(1, count(out, "com.apple.quicktime.model"));
        assertEquals("Acme", VideoMetadata.read(file, VideoMetadata.Tag.MAKE));
        assertEquals("Phone 1", VideoMetadata.read(file, VideoMetadata.Tag.MODEL));
    }

    /** A field only the keys can hold is written as a key alone. */
    @Test
    public void aKeyOnlyFieldIsWrittenAsAKey() throws IOException {
        File file = fileOf(mp4(null, 1024));

        assertTrue(VideoMetadata.write(file,
                new VideoMetadata.Builder().set(VideoMetadata.Tag.ALBUM, "Holidays")));

        byte[] out = Files.readAllBytes(file.toPath());
        assertEquals("no user data was needed", 0, count(out, "udta"));
        assertEquals("Holidays", VideoMetadata.read(file, VideoMetadata.Tag.ALBUM));
    }

    /**
     * Android's recorder leaves its own keys. They keep their numbers, so nothing that already
     * points at them changes, and ours are added after.
     */
    @Test
    public void androidsOwnKeysAreKept() throws IOException {
        File file = fileOf(mp4(androidMeta(false), 1024));

        assertTrue(VideoMetadata.write(file, new VideoMetadata.Builder()
                .set(VideoMetadata.Tag.MAKE, "Acme")));

        byte[] out = Files.readAllBytes(file.toPath());
        assertEquals("one meta box", 1, count(out, "meta"));
        assertEquals("16", VideoMetadata.read(file, VideoMetadata.Tag.ANDROID_VERSION));
        assertEquals("240.0", VideoMetadata.read(file, VideoMetadata.Tag.CAPTURE_FPS));
        assertEquals("Acme", VideoMetadata.read(file, VideoMetadata.Tag.MAKE));
        int version = indexOf(out, "com.android.version");
        int fps = indexOf(out, "com.android.capture.fps");
        int make = indexOf(out, MAKE_KEY);
        assertTrue("the keys keep their order, and so their numbers",
                version < fps && fps < make);
    }

    /** The same, in a meta box laid out as ISO has it, with a version and flags. */
    @Test
    public void anIsoMetaBoxIsMergedToo() throws IOException {
        File file = fileOf(mp4(androidMeta(true), 1024));

        assertTrue(VideoMetadata.write(file, new VideoMetadata.Builder()
                .set(VideoMetadata.Tag.SOFTWARE, "Camera 1.0")));

        assertEquals("Camera 1.0", VideoMetadata.read(file, VideoMetadata.Tag.SOFTWARE));
        assertEquals("16", VideoMetadata.read(file, VideoMetadata.Tag.ANDROID_VERSION));
    }

    /** Setting a key again replaces its value under the same key. */
    @Test
    public void aKeySetAgainIsReplaced() throws IOException {
        File file = fileOf(mp4(null, 1024));
        VideoMetadata.write(file, new VideoMetadata.Builder().set(VideoMetadata.Tag.TITLE, "A"));

        assertTrue(VideoMetadata.write(file,
                new VideoMetadata.Builder().set(VideoMetadata.Tag.TITLE, "B")));

        byte[] out = Files.readAllBytes(file.toPath());
        assertEquals(1, count(out, "com.apple.quicktime.title"));
        assertEquals("B", VideoMetadata.read(file, VideoMetadata.Tag.TITLE));
    }

    /** A key that has lost its value is given one again. */
    @Test
    public void aKeyWithoutAValueGetsOne() {
        byte[] moov = moov(meta(false, "mdta", new String[] {MAKE_KEY}, new byte[0][]));

        byte[] merged = VideoMetadata.withMetadata(moov, Collections.singletonMap(MAKE_KEY,
                new VideoMetadata.Builder().set(VideoMetadata.Tag.MAKE, "Acme").metadata()
                        .get(MAKE_KEY)));

        assertEquals(1, count(merged, MAKE_KEY));
        assertTrue(indexOf(merged, "Acme") > 0);
    }

    /** A meta box of another kind has no keys to add to, so the user data goes in alone. */
    @Test
    public void aMetaBoxOfAnotherKindIsLeftAlone() throws IOException {
        byte[] mp4 = mp4(meta(false, "ID32", new String[0], new byte[0][]), 1024);
        File file = fileOf(mp4);

        assertFalse("not everything could be written", VideoMetadata.write(file,
                new VideoMetadata.Builder().set(VideoMetadata.Tag.MAKE, "Acme")));

        byte[] out = Files.readAllBytes(file.toPath());
        assertEquals(0, count(out, MAKE_KEY));
        assertEquals("but the user data was", "Acme",
                VideoMetadata.read(file, VideoMetadata.Tag.MAKE));
    }

    /** Room for the user data but not the keys writes the user data, and says it fell short. */
    @Test
    public void roomForOnlyTheUserDataWritesTheUserData() throws IOException {
        File file = fileOf(mp4(null, 64));

        assertFalse(VideoMetadata.write(file,
                new VideoMetadata.Builder().set(VideoMetadata.Tag.MAKE, "Acme")));

        byte[] out = Files.readAllBytes(file.toPath());
        assertEquals(0, count(out, "meta"));
        assertEquals("Acme", VideoMetadata.read(file, VideoMetadata.Tag.MAKE));
    }

    /** A key-only field with no room has nothing to fall back to, and the file is untouched. */
    @Test
    public void aKeyOnlyFieldWithoutRoomWritesNothing() throws IOException {
        byte[] mp4 = mp4(null, 0);
        File file = fileOf(mp4);

        assertFalse(VideoMetadata.write(file,
                new VideoMetadata.Builder().set(VideoMetadata.Tag.GENRE, "Documentary")));
        assertArrayEquals(mp4, Files.readAllBytes(file.toPath()));
    }

    /** A rating is a float and a location role a byte, and both read back as text. */
    @Test
    public void numbersAreStoredAsNumbers() throws IOException {
        File file = fileOf(mp4(null, 1024));

        assertTrue(VideoMetadata.write(file, new VideoMetadata.Builder()
                .set(VideoMetadata.Tag.RATING, "4.5")
                .set(VideoMetadata.Tag.LOCATION_ROLE, "0")));

        assertEquals("4.5", VideoMetadata.read(file, VideoMetadata.Tag.RATING));
        assertEquals("0", VideoMetadata.read(file, VideoMetadata.Tag.LOCATION_ROLE));
    }

    /** Text where a number belongs is refused when it is set, not when it is written. */
    @Test
    public void aNumberFieldRefusesText() {
        VideoMetadata.Builder builder = new VideoMetadata.Builder();

        assertThrows(IllegalArgumentException.class,
                () -> builder.set(VideoMetadata.Tag.RATING, "five stars"));
        assertThrows(IllegalArgumentException.class,
                () -> builder.set(VideoMetadata.Tag.LOCATION_ROLE, "300"));
        assertThrows(IllegalArgumentException.class,
                () -> builder.set(VideoMetadata.Tag.LOCATION_ROLE, "-1"));
    }

    /** The artwork is stored as it was encoded, and its type is told from its first bytes. */
    @Test
    public void artworkRoundTrips() throws IOException {
        byte[][] images = {
                {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2},
                {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10},
                {'B', 'M', 0, 0, 0, 0},
        };
        for (byte[] image : images) {
            File file = fileOf(mp4(null, 1024));

            assertTrue(VideoMetadata.write(file, new VideoMetadata.Builder().setArtwork(image)));

            assertArrayEquals(image, VideoMetadata.readArtwork(file));
        }
    }

    /** Anything else is not artwork QuickTime can describe. */
    @Test
    public void artworkOfAnotherKindIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new VideoMetadata.Builder().setArtwork(new byte[] {'G', 'I', 'F', '8'}));
        assertThrows(IllegalArgumentException.class,
                () -> new VideoMetadata.Builder().setArtwork(new byte[] {(byte) 0xFF}));
        assertThrows(IllegalArgumentException.class,
                () -> new VideoMetadata.Builder().setArtwork(new byte[] {(byte) 0x89, 'P'}));
        assertThrows(IllegalArgumentException.class,
                () -> new VideoMetadata.Builder().setArtwork(new byte[] {'B'}));
        assertThrows(IllegalArgumentException.class, () -> new VideoMetadata.Builder()
                .setArtwork(new byte[] {(byte) 0xFF, 0, (byte) 0xFF}));
        assertThrows(IllegalArgumentException.class, () -> new VideoMetadata.Builder()
                .setArtwork(new byte[] {(byte) 0xFF, (byte) 0xD8, 0}));
        assertThrows(IllegalArgumentException.class, () -> new VideoMetadata.Builder()
                .setArtwork(new byte[] {(byte) 0x89, 'X', 'N', 'G'}));
        assertThrows(IllegalArgumentException.class, () -> new VideoMetadata.Builder()
                .setArtwork(new byte[] {(byte) 0x89, 'P', 'X', 'G'}));
        assertThrows(IllegalArgumentException.class, () -> new VideoMetadata.Builder()
                .setArtwork(new byte[] {(byte) 0x89, 'P', 'N', 'X'}));
        assertThrows(IllegalArgumentException.class, () -> new VideoMetadata.Builder()
                .setArtwork(new byte[] {'B', 'X'}));
        assertTrue("null leaves the file's as it is",
                new VideoMetadata.Builder().setArtwork(null).isEmpty());
    }

    /** A file without artwork, or without a moov, has none to read. */
    @Test
    public void absentArtworkReadsAsNull() throws IOException {
        assertNull(VideoMetadata.readArtwork(fileOf(mp4(null, 1024))));
        assertNull(VideoMetadata.readArtwork(fileOf(new byte[] {1, 2, 3})));
        assertNull(VideoMetadata.readArtwork(new File("/no/such/file.mp4")));
    }

    /** A key the file lacks reads as null, and a field with both forms falls back to the box. */
    @Test
    public void anAbsentKeyReadsAsNull() throws IOException {
        File file = fileOf(mp4(androidMeta(false), 1024));

        assertNull(VideoMetadata.read(file, VideoMetadata.Tag.ALBUM));
        assertNull(VideoMetadata.read(file, VideoMetadata.Tag.TITLE));
        assertNull(VideoMetadata.read(new File("/no/such/file.mp4"), VideoMetadata.Tag.TITLE));
    }

    /** Every number type QuickTime names reads back as its value. */
    @Test
    public void everyNumberTypeIsRead() {
        assertEquals("-2", VideoMetadata.valueOf(data(21, new byte[] {(byte) 0xFE})));
        assertEquals("254", VideoMetadata.valueOf(data(22, new byte[] {(byte) 0xFE})));
        assertEquals("-1", VideoMetadata.valueOf(data(65, new byte[] {(byte) 0xFF})));
        assertEquals("-2", VideoMetadata.valueOf(data(66, new byte[] {(byte) 0xFF, (byte) 0xFE})));
        assertEquals("3", VideoMetadata.valueOf(data(67, new byte[] {0, 0, 0, 3})));
        assertEquals("-1", VideoMetadata.valueOf(data(74, new byte[] {(byte) 0xFF, (byte) 0xFF,
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF})));
        assertEquals("200", VideoMetadata.valueOf(data(75, new byte[] {(byte) 200})));
        assertEquals("65535",
                VideoMetadata.valueOf(data(76, new byte[] {(byte) 0xFF, (byte) 0xFF})));
        assertEquals("4294967295", VideoMetadata.valueOf(data(77, new byte[] {(byte) 0xFF,
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF})));
        assertEquals("18446744073709551615", VideoMetadata.valueOf(data(78, new byte[] {
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                (byte) 0xFF, (byte) 0xFF})));
        assertEquals("120.0", VideoMetadata.valueOf(
                data(23, ByteBuffer.allocate(4).putFloat(120f).array())));
        assertEquals("0.5", VideoMetadata.valueOf(
                data(24, ByteBuffer.allocate(8).putDouble(0.5).array())));
        assertEquals("Café", VideoMetadata.valueOf(
                data(1, "Café".getBytes(StandardCharsets.UTF_8))));
    }

    /** A value of the wrong length, or a type that is not text or a number, is not read. */
    @Test
    public void anUnreadableValueIsNull() {
        assertNull("an image", VideoMetadata.valueOf(data(13, new byte[] {1})));
        assertNull(VideoMetadata.valueOf(data(23, new byte[] {1, 2})));
        assertNull(VideoMetadata.valueOf(data(24, new byte[] {1, 2})));
        assertNull(VideoMetadata.valueOf(data(67, new byte[0])));
        assertNull(VideoMetadata.valueOf(data(77, new byte[9])));
        assertNull(VideoMetadata.valueOf(new byte[] {0, 0, 0, 12, 'd', 'a', 't', 'a', 0, 0, 0, 1}));
    }

    /** Keyed metadata that does not add up is not rewritten. */
    @Test
    public void malformedKeyedMetadataIsRefused() {
        Map<String, byte[]> value = new VideoMetadata.Builder()
                .set(VideoMetadata.Tag.MAKE, "Acme").metadata();
        byte[] mvhdOverflow = {0, 0, 0, 20, 'm', 'o', 'o', 'v', 0, 0, 0, 99, 'm', 'v', 'h', 'd',
                0, 0, 0, 0};
        assertNull("a moov that does not add up", VideoMetadata.withMetadata(mvhdOverflow, value));

        byte[] badMeta = moov(box("meta", new byte[] {0, 0, 0, 99, 'h', 'd', 'l', 'r'}));
        assertNull("a meta that does not add up", VideoMetadata.withMetadata(badMeta, value));

        byte[] noHandler = moov(box("meta", box("keys", new byte[8])));
        assertNull("a meta without a handler", VideoMetadata.withMetadata(noHandler, value));

        byte[] shortHandler = moov(box("meta", box("hdlr", new byte[4])));
        assertNull("a handler too short to name itself",
                VideoMetadata.withMetadata(shortHandler, value));

        byte[] keysOverflow = concat(handler("mdta"),
                box("keys", new byte[] {0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 99, 'm', 'd', 't', 'a'}));
        assertNull("a key that runs past its box",
                VideoMetadata.withMetadata(moov(box("meta", keysOverflow)), value));

        byte[] keysShort = concat(handler("mdta"),
                box("keys", new byte[] {0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, 8, 'm', 'd', 't', 'a'}));
        assertNull("fewer keys than counted",
                VideoMetadata.withMetadata(moov(box("meta", keysShort)), value));

        byte[] tinyKeys = concat(handler("mdta"), box("keys", new byte[2]));
        assertNull("a keys box with no count",
                VideoMetadata.withMetadata(moov(box("meta", tinyKeys)), value));

        byte[] badIlst = concat(handler("mdta"), box("keys", new byte[8]),
                box("ilst", new byte[] {0, 0, 0, 99, 0, 0, 0, 1}));
        assertNull("an ilst that does not add up",
                VideoMetadata.withMetadata(moov(box("meta", badIlst)), value));
    }

    /** Malformed keyed metadata in a file reads as absent rather than throwing. */
    @Test
    public void malformedKeyedMetadataReadsAsAbsent() throws IOException {
        byte[] keysOverflow = concat(handler("mdta"),
                box("keys", new byte[] {0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 99, 'm', 'd', 't', 'a'}),
                box("ilst", new byte[0]));
        assertNull(VideoMetadata.read(fileOf(mp4(box("meta", keysOverflow), 64)),
                VideoMetadata.Tag.ALBUM));

        byte[] badIlst = concat(handler("mdta"), box("keys", new byte[8]),
                box("ilst", new byte[] {0, 0, 0, 99, 0, 0, 0, 1}));
        assertNull(VideoMetadata.read(fileOf(mp4(box("meta", badIlst), 64)),
                VideoMetadata.Tag.ALBUM));

        byte[] noIlst = concat(handler("mdta"), box("keys", new byte[8]));
        assertNull(VideoMetadata.read(fileOf(mp4(box("meta", noIlst), 64)),
                VideoMetadata.Tag.ALBUM));
        assertNull(VideoMetadata.readArtwork(fileOf(mp4(box("meta", noIlst), 64))));
    }

    /** Artwork whose data box is cut short is not artwork. */
    @Test
    public void truncatedArtworkReadsAsNull() throws IOException {
        byte[] meta = meta(false, "mdta", new String[] {"com.apple.quicktime.artwork"},
                new byte[][] {box("data", new byte[4])});

        assertNull(VideoMetadata.readArtwork(fileOf(mp4(meta, 64))));
    }

    /** A key in another namespace is not ours, and ours is added beside it. */
    @Test
    public void aKeyInAnotherNamespaceIsLeftAlone() {
        ByteArrayOutputStream keys = new ByteArrayOutputStream();
        writeInt(keys, 0);
        writeInt(keys, 1);
        byte[] name = MAKE_KEY.getBytes(StandardCharsets.UTF_8);
        writeInt(keys, 8 + name.length);
        keys.write('u');
        keys.write('d');
        keys.write('t');
        keys.write('a');
        keys.write(name, 0, name.length);
        byte[] moov = moov(box("meta", concat(handler("mdta"), box("keys", keys.toByteArray()),
                box("ilst", new byte[0]))));

        byte[] merged = VideoMetadata.withMetadata(moov,
                new VideoMetadata.Builder().set(VideoMetadata.Tag.MAKE, "Acme").metadata());

        assertEquals(2, count(merged, MAKE_KEY));
    }

    /** A meta box too small to hold anything has no handler, so no keys go in it. */
    @Test
    public void anEmptyMetaBoxIsLeftAlone() {
        assertNull(VideoMetadata.withMetadata(moov(box("meta", new byte[0])),
                new VideoMetadata.Builder().set(VideoMetadata.Tag.MAKE, "Acme").metadata()));
    }

    /** Keyed metadata without its keys, or with a key too short to name itself, is unread. */
    @Test
    public void keyedMetadataWithoutKeysReadsAsAbsent() throws IOException {
        byte[] noKeys = concat(handler("mdta"), box("ilst", new byte[0]));
        assertNull(VideoMetadata.read(fileOf(mp4(box("meta", noKeys), 64)),
                VideoMetadata.Tag.ALBUM));

        byte[] shortKey = concat(handler("mdta"),
                box("keys", new byte[] {0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 4, 'm', 'd', 't', 'a'}),
                box("ilst", new byte[0]));
        assertNull(VideoMetadata.read(fileOf(mp4(box("meta", shortKey), 64)),
                VideoMetadata.Tag.ALBUM));

        byte[] brokenMeta = {0, 0, 0, 99, 'h', 'd', 'l', 'r'};
        assertNull(VideoMetadata.read(fileOf(mp4(box("meta", brokenMeta), 64)),
                VideoMetadata.Tag.ALBUM));
    }

    /** A builder with nothing in it writes nothing. */
    @Test
    public void anEmptyBuilderWritesNothing() throws IOException {
        assertFalse(VideoMetadata.write(fileOf(mp4(null, 1024)), new VideoMetadata.Builder()));
    }

    /**
     * A meta box as Android's MPEG4Writer leaves it: the handler, then com.android.version as
     * text and com.android.capture.fps as a float.
     */
    private static byte[] androidMeta(boolean isoLayout) {
        return meta(isoLayout, "mdta",
                new String[] {"com.android.version", "com.android.capture.fps"},
                new byte[][] {
                        data(1, "16".getBytes(StandardCharsets.UTF_8)),
                        data(23, ByteBuffer.allocate(4).putFloat(240f).array()),
                });
    }

    private static byte[] meta(boolean isoLayout, String handlerType, String[] keys,
            byte[][] values) {
        ByteArrayOutputStream entries = new ByteArrayOutputStream();
        writeInt(entries, 0);
        writeInt(entries, keys.length);
        for (String key : keys) {
            byte[] name = key.getBytes(StandardCharsets.UTF_8);
            writeInt(entries, 8 + name.length);
            entries.write('m');
            entries.write('d');
            entries.write('t');
            entries.write('a');
            entries.write(name, 0, name.length);
        }
        ByteArrayOutputStream items = new ByteArrayOutputStream();
        for (int i = 0; i < values.length; i++) {
            byte[] item = new byte[8 + values[i].length];
            item[0] = (byte) (item.length >> 24);
            item[1] = (byte) (item.length >> 16);
            item[2] = (byte) (item.length >> 8);
            item[3] = (byte) item.length;
            item[7] = (byte) (i + 1);
            System.arraycopy(values[i], 0, item, 8, values[i].length);
            items.write(item, 0, item.length);
        }
        byte[] body = concat(isoLayout ? new byte[4] : new byte[0], handler(handlerType),
                box("keys", entries.toByteArray()), box("ilst", items.toByteArray()));
        return box("meta", body);
    }

    private static byte[] handler(String type) {
        byte[] body = new byte[25];
        for (int i = 0; i < 4; i++) {
            body[8 + i] = (byte) type.charAt(i);
        }
        return box("hdlr", body);
    }

    /** A data box: its type, the default locale, then the value. */
    private static byte[] data(int type, byte[] value) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeInt(body, type);
        writeInt(body, 0);
        body.write(value, 0, value.length);
        return box("data", body.toByteArray());
    }

    /** ftyp, a moov holding mvhd and the given meta, a free box of the given size, then mdat. */
    private static byte[] mp4(byte[] meta, int freeSize) {
        byte[] moov = meta == null ? moov() : moov(meta);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] ftyp = box("ftyp", new byte[] {'i', 's', 'o', 'm'});
        out.write(ftyp, 0, ftyp.length);
        out.write(moov, 0, moov.length);
        if (freeSize > 0) {
            byte[] free = box("free", new byte[freeSize - 8]);
            out.write(free, 0, free.length);
        }
        byte[] mdat = box("mdat", new byte[64]);
        out.write(mdat, 0, mdat.length);
        return out.toByteArray();
    }

    private static byte[] moov(byte[]... children) {
        return box("moov", concat(box("mvhd", new byte[92]), concat(children)));
    }

    private static byte[] box(String type, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 8 + body.length);
        for (int i = 0; i < 4; i++) {
            out.write(type.charAt(i));
        }
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >> 24) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private File fileOf(byte[] contents) throws IOException {
        File file = File.createTempFile("videokeys", ".mp4");
        file.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(contents);
        }
        return file;
    }

    private static int indexOf(byte[] data, String text) {
        byte[] needle = text.getBytes(StandardCharsets.ISO_8859_1);
        outer:
        for (int i = 0; i + needle.length <= data.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static int count(byte[] data, String text) {
        byte[] needle = text.getBytes(StandardCharsets.ISO_8859_1);
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
}
