package com.xlythe.view.camera;

import static com.xlythe.view.camera.MediaFixtures.ascii;
import static com.xlythe.view.camera.MediaFixtures.concat;
import static com.xlythe.view.camera.MediaFixtures.file;
import static com.xlythe.view.camera.MediaFixtures.flac;
import static com.xlythe.view.camera.MediaFixtures.flacBlock;
import static com.xlythe.view.camera.MediaFixtures.id3;
import static com.xlythe.view.camera.MediaFixtures.infoList;
import static com.xlythe.view.camera.MediaFixtures.mp3Frames;
import static com.xlythe.view.camera.MediaFixtures.oggOpus;
import static com.xlythe.view.camera.MediaFixtures.oggVorbis;
import static com.xlythe.view.camera.MediaFixtures.read;
import static com.xlythe.view.camera.MediaFixtures.riffChunk;
import static com.xlythe.view.camera.MediaFixtures.textFrame;
import static com.xlythe.view.camera.MediaFixtures.utf8;
import static com.xlythe.view.camera.MediaFixtures.vorbisComments;
import static com.xlythe.view.camera.MediaFixtures.wav;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.location.Location;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.TimeZone;

/** The sound formats: ID3, FLAC, Ogg and WAV. */
@RunWith(RobolectricTestRunner.class)
public class MediaTagsAudioTest {
    private TimeZone mZone;

    @Before
    public void setUp() {
        mZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @After
    public void tearDown() {
        TimeZone.setDefault(mZone);
    }

    private static Location somewhere() {
        Location location = new Location("test");
        location.setLatitude(51.5);
        location.setLongitude(-0.12);
        return location;
    }

    // ---------- ID3 ----------

    @Test
    public void id3FieldsRoundTripInPlace() throws IOException {
        byte[] audio = mp3Frames();
        File file = file(concat(id3(3, 4096, textFrame("TIT2", "Old")), audio), ".mp3");
        MediaTags tags = MediaTags.open(file);
        assertEquals(MediaTags.FORMAT_MP3, tags.getFormat());
        assertEquals(MediaTags.KIND_AUDIO, tags.getKind());
        assertEquals("Old", tags.getTitle());
        assertTrue(tags.setTitle("Song"));
        assertTrue(tags.setAuthor("Zoë"));
        assertTrue(tags.setDescription("Notes"));
        assertTrue(tags.setCopyright("2026"));
        assertTrue(tags.setSoftware("Encoder"));
        assertTrue(tags.setCaptureTime(1_700_000_000_000L));
        assertTrue(tags.setCoverArt(MediaFixtures.png()));
        assertFalse(tags.setCoverArt(ascii("GIF89a")));
        assertFalse(tags.setOrientation(90, false));
        // No native place for a location, so XMP in a PRIV frame.
        assertTrue(tags.setLocation(somewhere()));
        long length = read(file).length;
        tags.save();

        byte[] bytes = read(file);
        assertEquals(length, bytes.length);
        assertArrayEquals(audio, Arrays.copyOfRange(bytes, bytes.length - audio.length,
                bytes.length));
        MediaTags read = MediaTags.open(file);
        assertEquals("Song", read.getTitle());
        assertEquals("Zoë", read.getAuthor());
        assertEquals("Notes", read.getDescription());
        assertEquals("2026", read.getCopyright());
        assertEquals("Encoder", read.getSoftware());
        // TYER, TDAT and TIME hold the minute.
        assertEquals(1_700_000_000_000L / 60_000 * 60_000, read.getCaptureTime());
        assertArrayEquals(MediaFixtures.png(), read.getCoverArt());
        assertEquals(51.5, read.getLocation().getLatitude(), 1e-6);
    }

    @Test
    public void id3GrowsByRewritingTheFile() throws IOException {
        byte[] audio = mp3Frames();
        File file = file(concat(id3(4, 0, textFrame("TIT2", "Short")), audio), ".mp3");
        MediaTags tags = MediaTags.open(file);
        char[] long_ = new char[3000];
        Arrays.fill(long_, 'a');
        assertTrue(tags.setDescription(new String(long_)));
        assertTrue(tags.setCaptureTime(1_700_000_000_000L));
        tags.save();
        byte[] bytes = read(file);
        assertArrayEquals(audio, Arrays.copyOfRange(bytes, bytes.length - audio.length,
                bytes.length));
        MediaTags read = MediaTags.open(file);
        assertEquals("Short", read.getTitle());
        assertEquals(new String(long_), read.getDescription());
        // 2.4's TDRC holds the second.
        assertEquals(1_700_000_000_000L, read.getCaptureTime());
        assertEquals(4, bytes[3]);
    }

    @Test
    public void id3KeepsTheOldTagAtTheEndInLine() throws IOException {
        byte[] v1 = new byte[128];
        System.arraycopy(ascii("TAGOld title"), 0, v1, 0, 12);
        System.arraycopy(ascii("Old artist"), 0, v1, 33, 10);
        System.arraycopy(ascii("1999"), 0, v1, 93, 4);
        v1[126] = 3;
        File file = file(concat(mp3Frames(), v1), ".mp3");
        MediaTags tags = MediaTags.open(file);
        assertEquals("Old title", tags.getTitle());
        assertEquals("Old artist", tags.getAuthor());
        assertTrue(tags.getCaptureTime() > 0);
        tags.setTitle("New title");
        tags.save();
        byte[] bytes = read(file);
        assertEquals("New title", new String(bytes, bytes.length - 125, 9,
                java.nio.charset.StandardCharsets.ISO_8859_1));
        assertEquals("ID3", new String(bytes, 0, 3, java.nio.charset.StandardCharsets.ISO_8859_1));
        assertEquals("New title", MediaTags.open(file).getTitle());
    }

    @Test
    public void id3ReadsOlderAndUnsynchronisedTags() throws IOException {
        // Version 2.2: three-character frames and three-byte sizes.
        byte[] frame = concat(ascii("TT2"), new byte[] {0, 0, 6, 0}, ascii("Title"));
        byte[] header = {'I', 'D', '3', 2, 0, 0, 0, 0, 0, (byte) frame.length};
        assertEquals("Title", MediaTags.read(concat(header, frame, mp3Frames())).getTitle());

        // 2.3 with the whole tag unsynchronised: every 0xFF is followed by a zero.
        byte[] text = concat(new byte[] {1, (byte) 0xFF, 0x00, (byte) 0xFE},
                "Hé".getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
        byte[] tit2 = concat(ascii("TIT2"), MediaFixtures.u32(text.length - 1), new byte[2],
                text);
        byte[] unsynced = {'I', 'D', '3', 3, 0, (byte) 0x80, 0, 0, 0, (byte) tit2.length};
        assertEquals("Hé", MediaTags.read(concat(unsynced, tit2, mp3Frames())).getTitle());
        assertArrayEquals(new byte[] {(byte) 0xFF, 1}, Id3Container.resync(
                new byte[] {(byte) 0xFF, 0, 1}));
    }

    @Test
    public void id3CanSitInFrontOfAacAndFlac() throws IOException {
        byte[] adts = {(byte) 0xFF, (byte) 0xF1, 0x50, (byte) 0x80, 0, 0x1F, (byte) 0xFC};
        MediaTags aac = MediaTags.read(concat(id3(3, 0, textFrame("TIT2", "AAC")), adts));
        assertEquals(MediaTags.FORMAT_AAC, aac.getFormat());
        assertEquals("AAC", aac.getTitle());
        assertEquals(MediaTags.FORMAT_AAC, MediaTags.read(adts).getFormat());

        MediaTags flac = MediaTags.read(concat(id3(3, 0, textFrame("TIT2", "那么爱你为什么"),
                textFrame("TPE1", "阿强")), flac()));
        assertEquals(MediaTags.FORMAT_FLAC, flac.getFormat());
        assertEquals("那么爱你为什么", flac.getTitle());
        assertEquals("阿强", flac.getAuthor());
    }

    // ---------- FLAC ----------

    @Test
    public void flacKeepsVorbisCommentsAndAPicture() throws IOException {
        File file = file(flac(flacBlock(4, vorbisComments("title=Old", "COMMENT=Note"), false),
                flacBlock(1, new byte[2000], false)), ".flac");
        MediaTags tags = MediaTags.open(file);
        assertEquals(MediaTags.FORMAT_FLAC, tags.getFormat());
        assertEquals("Old", tags.getTitle());
        assertEquals("Note", tags.getDescription());
        long length = read(file).length;
        assertTrue(tags.setTitle("New"));
        assertTrue(tags.setDescription("Described"));
        assertTrue(tags.setAuthor("Ada"));
        assertTrue(tags.setCaptureTime(1_700_000_000_000L));
        assertTrue(tags.setCoverArt(MediaFixtures.png()));
        // FLAC keeps no XMP, and has no place for a location.
        assertFalse(tags.setLocation(somewhere()));
        assertFalse(tags.setMake("Maker"));
        tags.save();
        assertEquals(length, read(file).length);
        MediaTags read = MediaTags.open(file);
        assertEquals("New", read.getTitle());
        assertEquals("Described", read.getDescription());
        assertEquals("Ada", read.getAuthor());
        assertEquals(1_700_000_000_000L, read.getCaptureTime());
        assertArrayEquals(MediaFixtures.png(), read.getCoverArt());

        // Too large for the padding, so the file is rewritten.
        byte[] big = new byte[5000];
        big[0] = (byte) 0xFF;
        big[1] = (byte) 0xD8;
        read.setCoverArt(big);
        read.save();
        assertArrayEquals(big, MediaTags.open(file).getCoverArt());
        assertTrue(read(file).length > length);
    }

    @Test
    public void flacWithoutCommentsGainsThem() throws IOException {
        File file = file(flac(), ".flac");
        MediaTags tags = MediaTags.open(file);
        tags.setTitle("First");
        tags.save();
        assertEquals("First", MediaTags.open(file).getTitle());
        assertNull(MediaTags.open(file).getCoverArt());
    }

    // ---------- Ogg ----------

    @Test
    public void oggVorbisCommentsRoundTripAndEveryPageStaysValid() throws IOException {
        File file = file(oggVorbis("TITLE=Old"), ".ogg");
        MediaTags tags = MediaTags.open(file);
        assertEquals(MediaTags.FORMAT_OGG, tags.getFormat());
        assertEquals("Old", tags.getTitle());
        char[] long_ = new char[70_000];
        Arrays.fill(long_, 'x');
        assertTrue(tags.setDescription(new String(long_)));
        assertTrue(tags.setCoverArt(MediaFixtures.png()));
        assertFalse(tags.setLocation(somewhere()));
        tags.save();
        assertValidOgg(read(file));
        MediaTags read = MediaTags.open(file);
        assertEquals("Old", read.getTitle());
        assertEquals(new String(long_), read.getDescription());
        assertArrayEquals(MediaFixtures.png(), read.getCoverArt());
    }

    @Test
    public void opusCommentsRoundTrip() throws IOException {
        File file = file(oggOpus("ARTIST=Someone"), ".opus");
        MediaTags tags = MediaTags.open(file);
        assertEquals(MediaTags.FORMAT_OPUS, tags.getFormat());
        assertEquals("Someone", tags.getAuthor());
        tags.setCaptureTime(1_700_000_000_000L);
        tags.setSoftware("Encoder");
        tags.save();
        assertValidOgg(read(file));
        MediaTags read = MediaTags.open(file);
        assertEquals(1_700_000_000_000L, read.getCaptureTime());
        assertEquals("Encoder", read.getSoftware());
    }

    /** Every page has its capture pattern, its own sequence number, and a correct checksum. */
    private static void assertValidOgg(byte[] ogg) {
        int at = 0;
        long sequence = 0;
        while (at < ogg.length) {
            assertEquals("OggS", new String(ogg, at, 4, java.nio.charset.StandardCharsets.ISO_8859_1));
            assertEquals(sequence++, Bytes.u32le(ogg, at + 18));
            int segments = ogg[at + 26] & 0xFF;
            int length = 27 + segments;
            for (int i = 0; i < segments; i++) {
                length += ogg[at + 27 + i] & 0xFF;
            }
            byte[] page = Arrays.copyOfRange(ogg, at, at + length);
            assertEquals(Bytes.u32le(page, 22), OggContainer.crc(page));
            at += length;
        }
        assertEquals(ogg.length, at);
    }

    // ---------- WAV ----------

    @Test
    public void wavKeepsInfoAndXmpAndNeverMovesItsAudio() throws IOException {
        File file = file(wav(infoList("INAM", "Old", "IART", "Ada")), ".wav");
        MediaTags tags = MediaTags.open(file);
        assertEquals(MediaTags.FORMAT_WAV, tags.getFormat());
        assertEquals("Old", tags.getTitle());
        assertEquals("Ada", tags.getAuthor());
        assertTrue(tags.setTitle("New"));
        assertTrue(tags.setDescription("Comment"));
        assertTrue(tags.setCaptureTime(1_700_000_000_000L));
        assertTrue(tags.setLocation(somewhere()));
        assertFalse(tags.setCoverArt(MediaFixtures.png()));
        tags.save();
        byte[] bytes = read(file);
        assertEquals(bytes.length - 8, Bytes.u32le(bytes, 4));
        MediaTags read = MediaTags.open(file);
        assertEquals("New", read.getTitle());
        assertEquals("Comment", read.getDescription());
        // ICRD keeps the day.
        assertEquals(1_699_920_000_000L, read.getCaptureTime());
        assertEquals(51.5, read.getLocation().getLatitude(), 1e-6);
        assertEquals("2023-11-14", WavContainer.date(1_700_000_000_000L));
    }

    @Test
    public void wavTurnsOldTagsAheadOfItsAudioIntoJunk() throws IOException {
        byte[] fmt = new byte[16];
        Bytes.put16le(fmt, 0, 1);
        byte[] wav = MediaFixtures.riff("WAVE", riffChunk("fmt ", fmt), infoList("INAM", "Old"),
                riffChunk("data", new byte[32]));
        File file = file(wav, ".wav");
        MediaTags tags = MediaTags.open(file);
        tags.setTitle("New");
        tags.save();
        byte[] bytes = read(file);
        assertTrue(Bytes.indexOf(bytes, ascii("JUNK"), 0) > 0);
        assertEquals("New", MediaTags.open(file).getTitle());
    }

    @Test
    public void wavReadsBroadcastAndId3Chunks() throws IOException {
        byte[] bext = new byte[602];
        System.arraycopy(ascii("Scene 1"), 0, bext, 0, 7);
        System.arraycopy(ascii("2020-01-05"), 0, bext, 320, 10);
        System.arraycopy(ascii("12:56:18"), 0, bext, 330, 8);
        byte[] tag = id3(3, 0, textFrame("TIT2", "From ID3"),
                new Id3Container.Frame("APIC", 0, concat(new byte[] {0}, ascii("image/png"),
                        new byte[] {0, 3, 0}, MediaFixtures.png())));
        MediaTags tags = MediaTags.read(wav(riffChunk("bext", bext), riffChunk("id3 ", tag)));
        assertEquals("Scene 1", tags.getDescription());
        assertEquals("From ID3", tags.getTitle());
        assertTrue(tags.getCaptureTime() > 0);
        assertArrayEquals(MediaFixtures.png(), tags.getCoverArt());
    }

    @Test
    public void rf64IsReadButNotWritten() throws IOException {
        byte[] wav = wav(infoList("INAM", "Big"));
        System.arraycopy(ascii("RF64"), 0, wav, 0, 4);
        File file = file(wav, ".wav");
        MediaTags tags = MediaTags.open(file);
        assertEquals("Big", tags.getTitle());
        assertFalse(tags.setTitle("Bigger"));
    }

    // ---------- Across formats ----------

    @Test
    public void copiesAcrossFormats() throws IOException {
        File mp3 = file(concat(id3(3, 0, textFrame("TIT2", "Song"), textFrame("TPE1", "Ada")),
                mp3Frames()), ".mp3");
        MediaTags source = MediaTags.open(mp3);
        source.setCoverArt(MediaFixtures.png());
        source.save();
        File flac = file(flac(), ".flac");
        MediaTags destination = MediaTags.open(flac);
        assertTrue(MediaTags.open(mp3).copyTo(destination));
        destination.save();
        MediaTags read = MediaTags.open(flac);
        assertEquals("Song", read.getTitle());
        assertEquals("Ada", read.getAuthor());
        assertArrayEquals(MediaFixtures.png(), read.getCoverArt());

        // A location has nowhere to go in a FLAC, so the copy is incomplete.
        source = MediaTags.open(mp3);
        source.setLocation(somewhere());
        source.save();
        assertFalse(MediaTags.open(mp3).copyTo(MediaTags.open(flac)));

        // A picture's fields into a movie.
        File jpeg = file(ExifFixtures.exifJpeg(false), ".jpg");
        MediaTags picture = MediaTags.open(jpeg);
        picture.setTitle("Frame");
        picture.setLocation(somewhere());
        picture.save();
        File movie = file(MediaTagsBoxTest.movie(new byte[0]), ".mp4");
        MediaTags video = MediaTags.open(movie);
        MediaTags.open(jpeg).copyTo(video);
        video.save();
        assertEquals("Frame", MediaTags.open(movie).getTitle());
        assertEquals(51.5, MediaTags.open(movie).getLocation().getLatitude(), 1e-4);
    }

    @Test
    public void vorbisCommentsHandleCaseAndMalformedBlocks() {
        VorbisComments comments = VorbisComments.parse(vorbisComments("Title=A", "TITLE=B"), 0);
        assertEquals("A", comments.get("title"));
        comments.set("title", "C");
        assertEquals("C", comments.get("TITLE"));
        comments.set("TITLE", null);
        assertNull(comments.get("Title"));
        assertNull(VorbisComments.parse(new byte[] {1, 0, 0, 0}, 0));
        assertNull(VorbisComments.pictureData(new byte[8]));
        assertNull(VorbisComments.pictureBlock(ascii("GIF89a")));
        assertEquals(-1, VorbisComments.pictureType(new byte[2]));
        byte[] block = VorbisComments.pictureBlock(MediaFixtures.png());
        assertArrayEquals(MediaFixtures.png(), VorbisComments.pictureData(block));
        assertArrayEquals(utf8(""), new byte[0]);
    }
}
