package com.xlythe.view.camera;

import static com.xlythe.view.camera.MediaFixtures.ascii;
import static com.xlythe.view.camera.MediaFixtures.concat;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Matroska and WebM: what the segment, its tracks, tags and attachments say. */
@RunWith(RobolectricTestRunner.class)
public class MatroskaContainerTest {
    /** An element: its ID's bytes, a size, and its data. */
    static byte[] element(int id, byte[]... data) {
        byte[] body = concat(data);
        int idLength = id > 0xFFFFFF ? 4 : id > 0xFFFF ? 3 : id > 0xFF ? 2 : 1;
        byte[] header = new byte[idLength];
        for (int i = 0; i < idLength; i++) {
            header[i] = (byte) (id >> (8 * (idLength - 1 - i)));
        }
        return concat(header, size(body.length), body);
    }

    /** A size as eight bytes, which every reader must accept as well as the shortest form. */
    static byte[] size(long size) {
        if (size < 0x7F) {
            return new byte[] {(byte) (0x80 | size)};
        }
        byte[] out = new byte[8];
        out[0] = 0x01;
        for (int i = 1; i < 8; i++) {
            out[i] = (byte) (size >> (8 * (7 - i)));
        }
        return out;
    }

    static byte[] uint(int id, long value) {
        return element(id, new byte[] {(byte) (value >> 8), (byte) value});
    }

    static byte[] text(int id, String value) {
        return element(id, value.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] real(int id, float value) {
        int bits = Float.floatToIntBits(value);
        return element(id, new byte[] {(byte) (bits >> 24), (byte) (bits >> 16),
                (byte) (bits >> 8), (byte) bits});
    }

    static byte[] real8(int id, double value) {
        long bits = Double.doubleToLongBits(value);
        byte[] out = new byte[8];
        for (int i = 0; i < 8; i++) {
            out[i] = (byte) (bits >> (8 * (7 - i)));
        }
        return element(id, out);
    }

    static byte[] simpleTag(String name, String value) {
        return element(MatroskaContainer.ID_SIMPLE_TAG, text(MatroskaContainer.ID_TAG_NAME, name),
                text(MatroskaContainer.ID_TAG_STRING, value));
    }

    static byte[] header(String docType) {
        return element(MatroskaContainer.ID_EBML, text(MatroskaContainer.ID_DOC_TYPE, docType));
    }

    /** A 360° WebM: a top and bottom equirectangular video, HDR, with tags and a cover. */
    static byte[] sphericalWebm() {
        long date = (1_500_000_000_000L - 978_307_200_000L) * 1_000_000L;
        byte[] dateBytes = new byte[8];
        for (int i = 0; i < 8; i++) {
            dateBytes[i] = (byte) (date >> (8 * (7 - i)));
        }
        byte[] info = element(MatroskaContainer.ID_INFO, text(MatroskaContainer.ID_TITLE, "Lake"),
                element(MatroskaContainer.ID_DATE_UTC, dateBytes),
                text(MatroskaContainer.ID_MUXING_APP, "libwebm"),
                text(MatroskaContainer.ID_WRITING_APP, "Camera"));
        byte[] projectionPrivate = concat(new byte[4], MediaFixtures.u32(0x40000000L),
                MediaFixtures.u32(0x40000000L), MediaFixtures.u32(0), MediaFixtures.u32(0));
        byte[] video = element(MatroskaContainer.ID_VIDEO,
                uint(MatroskaContainer.ID_PIXEL_WIDTH, 1920),
                uint(MatroskaContainer.ID_PIXEL_HEIGHT, 1920),
                uint(MatroskaContainer.ID_STEREO_MODE, 3),
                element(MatroskaContainer.ID_COLOUR, uint(MatroskaContainer.ID_TRANSFER, 16)),
                element(MatroskaContainer.ID_PROJECTION,
                        uint(MatroskaContainer.ID_PROJECTION_TYPE, 1),
                        element(MatroskaContainer.ID_PROJECTION_PRIVATE, projectionPrivate),
                        real(MatroskaContainer.ID_POSE_YAW, 10f),
                        real8(MatroskaContainer.ID_POSE_PITCH, 20),
                        real(MatroskaContainer.ID_POSE_ROLL, 30f)));
        byte[] tracks = element(MatroskaContainer.ID_TRACKS,
                element(MatroskaContainer.ID_TRACK_ENTRY, uint(MatroskaContainer.ID_TRACK_TYPE, 2)),
                element(MatroskaContainer.ID_TRACK_ENTRY, uint(MatroskaContainer.ID_TRACK_TYPE, 1),
                        video),
                element(MatroskaContainer.ID_TRACK_ENTRY, uint(MatroskaContainer.ID_TRACK_TYPE, 1)),
                uint(0xEC, 0));
        byte[] tags = element(MatroskaContainer.ID_TAGS,
                element(MatroskaContainer.ID_TAG, simpleTag("ARTIST", "Ana"),
                        simpleTag("artist", "Not first"),
                        element(MatroskaContainer.ID_SIMPLE_TAG,
                                text(MatroskaContainer.ID_TAG_NAME, "COMMENT"),
                                text(MatroskaContainer.ID_TAG_STRING, "At dawn"),
                                simpleTag("COPYRIGHT", "CC0")),
                        simpleTag("DATE_RECORDED", "2016-03-12 10:20:30"),
                        simpleTag("EMPTY", "")),
                uint(0xEC, 0));
        byte[] attachments = element(MatroskaContainer.ID_ATTACHMENTS,
                element(MatroskaContainer.ID_ATTACHED_FILE,
                        text(MatroskaContainer.ID_FILE_NAME, "notes.txt"),
                        text(MatroskaContainer.ID_FILE_MEDIA_TYPE, "text/plain"),
                        element(MatroskaContainer.ID_FILE_DATA, ascii("words"))),
                element(MatroskaContainer.ID_ATTACHED_FILE,
                        text(MatroskaContainer.ID_FILE_NAME, "back.png"),
                        text(MatroskaContainer.ID_FILE_MEDIA_TYPE, "image/png"),
                        element(MatroskaContainer.ID_FILE_DATA, ascii("BACK"))),
                element(MatroskaContainer.ID_ATTACHED_FILE,
                        text(MatroskaContainer.ID_FILE_NAME, "cover.jpg"),
                        text(MatroskaContainer.ID_FILE_MEDIA_TYPE, "image/jpeg"),
                        element(MatroskaContainer.ID_FILE_DATA, ascii("COVER"))),
                element(MatroskaContainer.ID_ATTACHED_FILE,
                        text(MatroskaContainer.ID_FILE_NAME, "other.jpg"),
                        text(MatroskaContainer.ID_FILE_MEDIA_TYPE, "image/jpeg"),
                        element(MatroskaContainer.ID_FILE_DATA, ascii("OTHER"))));
        byte[] cluster = element(MatroskaContainer.ID_CLUSTER, new byte[300]);
        return concat(header("webm"), element(MatroskaContainer.ID_SEGMENT, info, tracks,
                cluster, tags, attachments));
    }

    @Test
    public void readsWhatTheSegmentSays() throws IOException {
        MediaTags tags = MediaTags.read(sphericalWebm());
        assertEquals(MediaTags.FORMAT_MATROSKA, tags.getFormat());
        assertEquals(MediaTags.KIND_VIDEO, tags.getKind());
        assertEquals("Lake", tags.getTitle());
        assertEquals("Ana", tags.getAuthor());
        assertEquals("At dawn", tags.getDescription());
        assertEquals("CC0", tags.getCopyright());
        assertEquals("Camera", tags.getSoftware());
        assertEquals(MatroskaContainer.parseDate("2016-03-12 10:20:30"), tags.getCaptureTime());
        assertArrayEquals(ascii("COVER"), tags.getCoverArt());
        assertArrayEquals(new int[] {1920, 1920}, tags.getVideoSize());
        assertEquals(MediaTags.TRANSFER_PQ, tags.getVideoTransfer());
        assertTrue(tags.isReadOnly());
        assertFalse(tags.setTitle("Anything"));
    }

    @Test
    public void readsTheProjection() throws IOException {
        MediaTags.SphericalVideo sphere = MediaTags.read(sphericalWebm()).getSphericalVideo();
        assertNotNull(sphere);
        assertEquals(MediaTags.SphericalVideo.PROJECTION_EQUIRECTANGULAR, sphere.getProjection());
        assertEquals(MediaTags.SphericalVideo.STEREO_TOP_BOTTOM, sphere.getStereoMode());
        assertEquals(10f, sphere.getPoseYawDegrees(), 1e-6f);
        assertEquals(20f, sphere.getPosePitchDegrees(), 1e-6f);
        assertEquals(30f, sphere.getPoseRollDegrees(), 1e-6f);
        assertEquals(0.25, sphere.getCropTop(), 1e-9);
        assertEquals(0.25, sphere.getCropBottom(), 1e-9);
        assertEquals(0, sphere.getCropLeft(), 1e-9);
    }

    @Test
    public void everyProjectionKindAndStereoMode() throws IOException {
        int[][] cases = {{1, MediaTags.SphericalVideo.STEREO_LEFT_RIGHT},
                {11, MediaTags.SphericalVideo.STEREO_LEFT_RIGHT},
                {2, MediaTags.SphericalVideo.STEREO_TOP_BOTTOM},
                {0, MediaTags.SphericalVideo.STEREO_MONO}};
        for (int[] stereo : cases) {
            assertEquals(stereo[1], MediaTags.read(video(stereo[0], 1)).getSphericalVideo()
                    .getStereoMode());
        }
        assertEquals(MediaTags.SphericalVideo.PROJECTION_CUBEMAP,
                MediaTags.read(video(0, 2)).getSphericalVideo().getProjection());
        assertEquals(MediaTags.SphericalVideo.PROJECTION_MESH,
                MediaTags.read(video(0, 3)).getSphericalVideo().getProjection());
        assertNull("a flat projection", MediaTags.read(video(0, 0)).getSphericalVideo());
    }

    /** A video of a stereo mode and projection type, with nothing else. */
    private static byte[] video(int stereo, int projection) {
        byte[] video = element(MatroskaContainer.ID_VIDEO,
                uint(MatroskaContainer.ID_STEREO_MODE, stereo),
                element(MatroskaContainer.ID_PROJECTION,
                        uint(MatroskaContainer.ID_PROJECTION_TYPE, projection),
                        element(MatroskaContainer.ID_PROJECTION_PRIVATE, new byte[4])));
        return concat(header("matroska"), element(MatroskaContainer.ID_SEGMENT,
                element(MatroskaContainer.ID_TRACKS, element(MatroskaContainer.ID_TRACK_ENTRY,
                        uint(MatroskaContainer.ID_TRACK_TYPE, 1), video))));
    }

    @Test
    public void soundAloneIsAudioAndTheInfoFillsIn() throws IOException {
        byte[] info = element(MatroskaContainer.ID_INFO,
                text(MatroskaContainer.ID_MUXING_APP, "libmkv"),
                element(MatroskaContainer.ID_DATE_UTC, new byte[4]));
        byte[] tracks = element(MatroskaContainer.ID_TRACKS,
                element(MatroskaContainer.ID_TRACK_ENTRY, uint(MatroskaContainer.ID_TRACK_TYPE, 2)));
        byte[] tags = element(MatroskaContainer.ID_TAGS, uint(0xEC, 0),
                element(MatroskaContainer.ID_TAG, simpleTag("TITLE", "Song"),
                        simpleTag("DIRECTOR", "Bo"), simpleTag("DESCRIPTION", "Long"),
                        simpleTag("ENCODER", "Lavf")));
        MediaTags read = MediaTags.read(concat(header("matroska"),
                element(MatroskaContainer.ID_SEGMENT, info, tracks, tags)));
        assertEquals(MediaTags.KIND_AUDIO, read.getKind());
        assertEquals("Song", read.getTitle());
        assertEquals("Bo", read.getAuthor());
        assertEquals("Long", read.getDescription());
        assertEquals("Lavf", read.getSoftware());
        assertEquals(-1, read.getCaptureTime());
        assertNull(read.getVideoSize());
        assertNull(read.getCoverArt());
        assertEquals(MediaTags.TRANSFER_UNKNOWN, read.getVideoTransfer());

        MediaTags bare = MediaTags.read(concat(header("matroska"),
                element(MatroskaContainer.ID_SEGMENT, element(MatroskaContainer.ID_INFO,
                        text(MatroskaContainer.ID_MUXING_APP, "libmkv")))));
        assertEquals("with no tracks, taken for a video", MediaTags.KIND_VIDEO, bare.getKind());
        assertEquals("libmkv", bare.getSoftware());
    }

    @Test
    public void theMuxingDateStandsInForARecordingDate() throws IOException {
        MediaTags tags = MediaTags.read(concat(header("webm"), element(
                MatroskaContainer.ID_SEGMENT, element(MatroskaContainer.ID_INFO,
                        element(MatroskaContainer.ID_DATE_UTC, new byte[8])))));
        assertEquals(978_307_200_000L, tags.getCaptureTime());
    }

    @Test
    public void stopsWhereSizesStopMakingSense() throws IOException {
        // A segment of unknown size runs to the end of the file; a cluster of unknown size
        // cannot be skipped, so nothing after it is read.
        byte[] unknownCluster = concat(new byte[] {0x1F, 0x43, (byte) 0xB6, 0x75, (byte) 0xFF},
                new byte[20]);
        byte[] unknownSegment = concat(new byte[] {0x18, 0x53, (byte) 0x80, 0x67, (byte) 0xFF},
                element(MatroskaContainer.ID_INFO, text(MatroskaContainer.ID_TITLE, "Live")),
                unknownCluster, element(MatroskaContainer.ID_TAGS,
                        element(MatroskaContainer.ID_TAG, simpleTag("ARTIST", "Unseen"))));
        MediaTags live = MediaTags.read(concat(header("webm"), unknownSegment));
        assertEquals("Live", live.getTitle());
        assertNull(live.getAuthor());

        // An element of unknown size before the segment ends the search.
        byte[] unknownFirst = concat(new byte[] {(byte) 0xEC, (byte) 0xFF}, new byte[4]);
        assertNull(MediaTags.read(concat(header("webm"), unknownFirst)).getTitle());
        // So does an ID too long to be one.
        assertNull(MediaTags.read(concat(header("webm"), new byte[] {0, 0, 0})).getTitle());
        // And nothing after the header at all.
        assertEquals(MediaTags.FORMAT_MATROSKA, MediaTags.read(header("webm")).getFormat());
    }

    @Test
    public void readsHeadersOfEveryLength() {
        MatroskaContainer.Header one = MatroskaContainer.header(new byte[] {(byte) 0x83,
                (byte) 0x81, 1}, 0, 3);
        assertEquals(0x83, one.id);
        assertEquals(2, one.data);
        assertEquals(1, one.size);
        MatroskaContainer.Header four = MatroskaContainer.header(new byte[] {0x1A, 0x45,
                (byte) 0xDF, (byte) 0xA3, 0x42, (byte) 0x86}, 0, 6);
        assertEquals(MatroskaContainer.ID_EBML, four.id);
        assertEquals(0x286, four.size);
        assertNull("nothing there", MatroskaContainer.header(new byte[0], 0, 0));
        assertNull("an ID with no size after it",
                MatroskaContainer.header(new byte[] {(byte) 0x83}, 0, 1));
        assertNull("a size of no length", MatroskaContainer.header(new byte[] {(byte) 0x83, 0},
                0, 2));
        assertNull("a size cut short", MatroskaContainer.header(new byte[] {(byte) 0x83, 0x40},
                0, 2));
    }

    @Test
    public void parsesTheDatesTagsUse() {
        assertEquals(1457778030000L, MatroskaContainer.parseDate("2016-03-12 10:20:30"));
        assertEquals(1457778030000L, MatroskaContainer.parseDate("2016-03-12T10:20:30"));
        assertEquals(1457778000000L, MatroskaContainer.parseDate("2016-03-12 10:20"));
        assertEquals(1457740800000L, MatroskaContainer.parseDate(" 2016-03-12 "));
        assertEquals(1456790400000L, MatroskaContainer.parseDate("2016-03"));
        assertEquals(1451606400000L, MatroskaContainer.parseDate("2016"));
        assertEquals(-1, MatroskaContainer.parseDate("yesterday"));
        assertEquals(-1, MatroskaContainer.parseDate(null));
    }

    @Test
    public void readsFromAFileAndNeverWrites() throws IOException {
        File file = File.createTempFile("matroska", ".webm");
        file.deleteOnExit();
        Files.write(file.toPath(), sphericalWebm());
        MediaTags tags = MediaTags.open(file);
        assertEquals("Lake", tags.getTitle());
        assertFalse(tags.setTitle("New"));
        assertFalse(tags.setCaptureTime(0));
        assertTrue(tags.isReadOnly());
        tags.save();
    }
}
