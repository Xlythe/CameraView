package com.xlythe.view.camera;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The Motion Photo writer, checked by reading its output back the way a gallery would.
 *
 * <p>Every file is read back through {@link MediaTags}, which finds the clip the way MediaView and
 * the editors do, and the directory through {@link Xmp}, so a test passes because the file is right
 * rather than because the writer agrees with itself.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class MotionPhotoWriterTest {
    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void declaresEveryPropertyTheFormatDefines() throws Exception {
        byte[] jpeg = new Jpeg().jfif().exif().scan().build();

        ReadBack parsed = read(
                appendVideo(MotionPhotoWriter.withMotionPhotoXmp(jpeg, 4242, 1_500_000), 4242));

        assertEquals("1", parsed.camera("MotionPhoto"));
        assertEquals("1", parsed.camera("MotionPhotoVersion"));
        assertEquals("1500000", parsed.camera("MotionPhotoPresentationTimestampUs"));
        assertEquals(2, parsed.items.size());

        Map<String, String> primary = parsed.items.get(0);
        assertEquals("image/jpeg", primary.get(Xmp.NS_ITEM + "Mime"));
        assertEquals("Primary", primary.get(Xmp.NS_ITEM + "Semantic"));
        assertEquals("the primary item's length is found by parsing it", 0L,
                Long.parseLong(primary.get(Xmp.NS_ITEM + "Length")));
        assertEquals(0L, Long.parseLong(primary.get(Xmp.NS_ITEM + "Padding")));

        Map<String, String> video = parsed.items.get(1);
        assertEquals("video/mp4", video.get(Xmp.NS_ITEM + "Mime"));
        assertEquals("MotionPhoto", video.get(Xmp.NS_ITEM + "Semantic"));
        assertEquals(4242L, Long.parseLong(video.get(Xmp.NS_ITEM + "Length")));
        assertNull("only the first item may carry padding", video.get(Xmp.NS_ITEM + "Padding"));
    }

    @Test
    public void writesThePrefixesEveryReaderKnows() throws Exception {
        String xmp = MotionPhotoWriter.buildXmp(null, 10, 0);

        assertTrue(xmp, xmp.contains("GCamera:MotionPhoto=\"1\""));
        assertTrue(xmp, xmp.contains("Container:Directory"));
        assertTrue(xmp, xmp.contains("Item:Semantic=\"MotionPhoto\""));
        assertTrue("wrapped as an XMP packet", xmp.startsWith("<?xpacket begin="));
        assertTrue(xmp.endsWith("<?xpacket end=\"w\"?>"));
    }

    @Test
    public void putsTheXmpRightAfterTheExifSegment() throws Exception {
        Jpeg builder = new Jpeg().jfif().exif();
        int afterExif = builder.size();
        byte[] jpeg = builder.quantization().scan().build();

        byte[] described = MotionPhotoWriter.withMotionPhotoXmp(jpeg, 100, 0);

        assertEquals("an APP1 marker", 0xFF, described[afterExif] & 0xFF);
        assertEquals(0xE1, described[afterExif + 1] & 0xFF);
        assertTrue(startsWith(described, afterExif + 4, MotionPhotoWriter.XMP_SIGNATURE));
        int length = ((described[afterExif + 2] & 0xFF) << 8) | (described[afterExif + 3] & 0xFF);
        assertArrayEquals("everything before it is untouched",
                Arrays.copyOfRange(jpeg, 0, afterExif),
                Arrays.copyOfRange(described, 0, afterExif));
        assertArrayEquals("and everything after it",
                Arrays.copyOfRange(jpeg, afterExif, jpeg.length),
                Arrays.copyOfRange(described, afterExif + 2 + length, described.length));
    }

    @Test
    public void putsTheXmpFirstInAJpegWithNoHeaderSegments() throws Exception {
        byte[] jpeg = new Jpeg().quantization().scan().build();

        byte[] described = MotionPhotoWriter.withMotionPhotoXmp(jpeg, 100, 0);

        assertEquals(0xE1, described[3] & 0xFF);
        assertTrue(startsWith(described, 6, MotionPhotoWriter.XMP_SIGNATURE));
    }

    @Test
    public void theVideoIsTheLastLengthBytesAndFollowsTheImage() throws Exception {
        File jpeg = write("picture.MP.jpg", new Jpeg().jfif().exif().quantization().scan().build());
        byte[] mp4 = fakeMp4(9000);
        File video = write("clip.mp4", mp4);

        MotionPhotoWriter.write(jpeg, video, 1_250_000);

        byte[] file = Files.readAllBytes(jpeg.toPath());
        MediaTags.MotionPhoto clip = MediaTags.open(jpeg).getMotionPhoto();
        assertNotNull("MediaTags finds the clip", clip);
        assertEquals(MediaTags.MotionPhoto.FORMAT_MOTION_PHOTO, clip.getFormat());
        assertEquals(1, clip.getVersion());
        assertEquals(1_250_000L, clip.getPresentationTimestampUs());
        assertEquals(file.length - mp4.length, clip.getVideoOffset());
        assertEquals(mp4.length, clip.getVideoLength());
        assertArrayEquals(mp4, Arrays.copyOfRange(file, (int) clip.getVideoOffset(),
                (int) (clip.getVideoOffset() + clip.getVideoLength())));
        assertFalse("nothing is left behind", new File(jpeg.getPath() + ".motion").exists());
    }

    @Test
    public void aFailedWriteLeavesTheStillAsItWas() throws Exception {
        byte[] original = new Jpeg().exif().scan().build();
        File jpeg = write("picture.MP.jpg", original);
        File missing = new File(folder.getRoot(), "missing.mp4");

        try {
            MotionPhotoWriter.write(jpeg, missing, 0);
            fail("there is no video to append");
        } catch (IOException expected) {
            // The point is what is left on disk.
        }

        assertArrayEquals(original, Files.readAllBytes(jpeg.toPath()));
        assertFalse(new File(jpeg.getPath() + ".motion").exists());
    }

    /**
     * An Ultra HDR picture already has XMP, a container directory listing its gain map, and a
     * Multi-Picture Format segment pointing at the gain map. All three have to survive.
     */
    @Test
    public void mergesIntoAnUltraHdrPicture() throws Exception {
        String existing = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core\">"
                + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description rdf:about=\"\""
                + " xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\""
                + " xmlns:Container=\"http://ns.google.com/photos/1.0/container/\""
                + " xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\""
                + " hdrgm:Version=\"1.0\">"
                + "<Container:Directory><rdf:Seq>"
                + "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Semantic=\"Primary\""
                + " Item:Mime=\"image/jpeg\"/></rdf:li>"
                + "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Semantic=\"GainMap\""
                + " Item:Mime=\"image/jpeg\" Item:Length=\"1234\"/></rdf:li>"
                + "</rdf:Seq></Container:Directory>"
                + "</rdf:Description></rdf:RDF></x:xmpmeta>";
        Jpeg builder = new Jpeg().exif().xmp(existing);
        int mpfStart = builder.size();
        builder.mpf();
        byte[] primary = builder.quantization().scan().build();
        byte[] gainMap = new Jpeg().scan().build();
        byte[] ultraHdr = concat(primary, gainMap);
        int gainMapFromMpf = primary.length - mpfStart;

        byte[] described = MotionPhotoWriter.withMotionPhotoXmp(ultraHdr, 5000, 1_000_000);

        ReadBack parsed = read(appendVideo(described, 5000));
        assertEquals("the gain map metadata stays", "1.0",
                parsed.xmp.get(Xmp.NS_HDR_GAIN_MAP, "Version"));
        assertEquals(3, parsed.items.size());
        assertEquals("Primary", parsed.items.get(0).get(Xmp.NS_ITEM + "Semantic"));
        assertEquals("GainMap", parsed.items.get(1).get(Xmp.NS_ITEM + "Semantic"));
        assertEquals(1234L, Long.parseLong(parsed.items.get(1).get(Xmp.NS_ITEM + "Length")));
        assertEquals("the video comes after the gain map", "MotionPhoto",
                parsed.items.get(2).get(Xmp.NS_ITEM + "Semantic"));
        assertEquals(1, parsed.xmpSegments);

        int newMpfStart = indexOf(described, "MPF".getBytes(StandardCharsets.US_ASCII)) - 4;
        int newGainMapStart = described.length - gainMap.length;
        assertEquals("the gain map is exactly as far from the segment that locates it",
                gainMapFromMpf, newGainMapStart - newMpfStart);
        assertArrayEquals(gainMap, Arrays.copyOfRange(described, newGainMapStart,
                described.length));
    }

    @Test
    public void refusesToMoveAGainMapItsLocatorDoesNot() throws Exception {
        byte[] jpeg = new Jpeg().exif().mpf().xmp("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
                + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"/>"
                + "</x:xmpmeta>").scan().build();

        try {
            MotionPhotoWriter.withMotionPhotoXmp(jpeg, 10, 0);
            fail("the XMP follows the segment that locates the gain map");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Multi-Picture"));
        }
    }

    @Test
    public void aNewXmpGoesAheadOfTheGainMapLocator() throws Exception {
        Jpeg builder = new Jpeg().jfif();
        int beforeMpf = builder.size();
        byte[] jpeg = builder.mpf().exif().scan().build();

        byte[] described = MotionPhotoWriter.withMotionPhotoXmp(jpeg, 10, 0);

        assertTrue("inserted where the locator was", startsWith(described, beforeMpf + 4,
                MotionPhotoWriter.XMP_SIGNATURE));
    }

    @Test
    public void replacesAnEarlierMotionPhotoAndTheRetiredMicroVideo() throws Exception {
        String existing = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
                + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description rdf:about=\"\""
                + " xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\""
                + " xmlns:Container=\"http://ns.google.com/photos/1.0/container/\""
                + " xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\""
                + " GCamera:MicroVideo=\"1\" GCamera:MicroVideoVersion=\"1\""
                + " GCamera:MicroVideoOffset=\"999\""
                + " GCamera:MicroVideoPresentationTimestampUs=\"5\""
                + " GCamera:MotionPhoto=\"0\">"
                + "<Container:Directory><rdf:Seq>"
                + "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Semantic=\"Primary\""
                + " Item:Mime=\"image/jpeg\"/></rdf:li>"
                + "<rdf:li rdf:parseType=\"Resource\"><Container:Item"
                + " Item:Semantic=\"MotionPhoto\" Item:Mime=\"video/mp4\" Item:Length=\"999\"/>"
                + "</rdf:li></rdf:Seq></Container:Directory>"
                + "</rdf:Description></rdf:RDF></x:xmpmeta>";

        ReadBack parsed = read(appendVideo(
                MotionPhotoWriter.withMotionPhotoXmp(
                        new Jpeg().xmp(existing).scan().build(), 77, 9),
                77));

        assertFalse(parsed.hasCamera("MicroVideo"));
        assertFalse(parsed.hasCamera("MicroVideoOffset"));
        assertFalse(parsed.hasCamera("MicroVideoVersion"));
        assertFalse(parsed.hasCamera("MicroVideoPresentationTimestampUs"));
        assertEquals("1", parsed.camera("MotionPhoto"));
        assertEquals("9", parsed.camera("MotionPhotoPresentationTimestampUs"));
        assertEquals("one video, the new one", 2, parsed.items.size());
        assertEquals(77L, Long.parseLong(parsed.items.get(1).get(Xmp.NS_ITEM + "Length")));
    }

    /** RDF lets a property be a child element as well as an attribute; both are replaced. */
    @Test
    public void replacesPropertiesWrittenAsElements() throws Exception {
        String existing = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
                + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description rdf:about=\"\""
                + " xmlns:Camera=\"http://ns.google.com/photos/1.0/camera/\">"
                + "<Camera:MotionPhoto>0</Camera:MotionPhoto>"
                + "<Camera:MicroVideoOffset>12</Camera:MicroVideoOffset>"
                + "</rdf:Description></rdf:RDF></x:xmpmeta>";

        String xmp = MotionPhotoWriter.buildXmp(existing, 10, 3);

        assertFalse(xmp, xmp.contains("<Camera:MotionPhoto>"));
        assertFalse(xmp, xmp.contains("MicroVideoOffset"));
        ReadBack parsed = read(
                appendVideo(MotionPhotoWriter.withMotionPhotoXmp(
                        new Jpeg().xmp(existing).scan().build(), 10, 3), 10));
        assertEquals("1", parsed.camera("MotionPhoto"));
    }

    @Test
    public void aDirectoryWithoutAPrimaryGainsOneFirst() throws Exception {
        String existing = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
                + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description rdf:about=\"\""
                + " xmlns:Container=\"http://ns.google.com/photos/1.0/container/\">"
                + "<Container:Directory/>"
                + "</rdf:Description></rdf:RDF></x:xmpmeta>";

        ReadBack parsed = read(appendVideo(
                MotionPhotoWriter.withMotionPhotoXmp(
                        new Jpeg().xmp(existing).scan().build(), 10, 0),
                10));

        assertEquals(2, parsed.items.size());
        assertEquals("Primary", parsed.items.get(0).get(Xmp.NS_ITEM + "Semantic"));
        assertEquals("MotionPhoto", parsed.items.get(1).get(Xmp.NS_ITEM + "Semantic"));
    }

    @Test
    public void anEmptyRdfGainsADescription() throws Exception {
        String existing = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
                + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"/>"
                + "</x:xmpmeta>";

        String xmp = MotionPhotoWriter.buildXmp(existing, 10, 0);

        assertTrue(xmp, xmp.contains("rdf:Description"));
        assertTrue(xmp, xmp.contains("GCamera:MotionPhoto=\"1\""));
    }

    @Test
    public void refusesWhatItCannotDescribe() {
        assertRefused("not a JPEG", new byte[] {1, 2, 3, 4}, 10);
        assertRefused("no video", new Jpeg().scan().build(), 0);
        byte[] truncated = new Jpeg().exif().build();
        assertRefused("a segment longer than the file",
                Arrays.copyOf(truncated, truncated.length - 3), 10);
        assertRefused("a byte where a marker belongs",
                new byte[] {(byte) 0xFF, (byte) 0xD8, 0x12, 0x34, 0x00, 0x00}, 10);
        assertRefused("XMP that is not XML", new Jpeg().xmp("<x:xmpmeta").scan().build(), 10);
        assertRefused("XMP without RDF",
                new Jpeg().xmp("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>").scan().build(), 10);
    }

    @Test
    public void refusesXmpTooLargeForOneSegment() {
        // An existing packet that just fits in the 64K a JPEG segment allows, which the merge
        // then grows past it. The writer has to refuse rather than write a length that wraps.
        StringBuilder text = new StringBuilder();
        while (text.length() < 65_200) {
            text.append("padding ");
        }
        String existing = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
                + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description rdf:about=\"\">" + text + "</rdf:Description>"
                + "</rdf:RDF></x:xmpmeta>";
        byte[] jpeg = new Jpeg().xmp(existing).scan().build();

        assertRefused("a packet over 64K", jpeg, 10);
    }

    @Test
    public void skipsFillBytesAndStandaloneMarkers() throws Exception {
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        jpeg.write(new byte[] {(byte) 0xFF, (byte) 0xD8});
        jpeg.write(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xD0});
        jpeg.write(new byte[] {(byte) 0xFF, 0x01});
        byte[] rest = new Jpeg().exif().scan().build();
        jpeg.write(rest, 2, rest.length - 2);

        ReadBack parsed = read(appendVideo(
                MotionPhotoWriter.withMotionPhotoXmp(jpeg.toByteArray(), 10, 0), 10));

        assertEquals("1", parsed.camera("MotionPhoto"));
    }

    /**
     * The metadata track's one sample, laid out by hand from the descriptor syntax: a tag, a
     * size, and the fields, nested as the format lists them.
     */
    @Test
    public void theMetadataTrackSampleFollowsTheDescriptorSyntax() {
        long timestamp = 0x0102030405060708L;
        byte[] expected = ByteBuffer.allocate(36)
                .put((byte) 0xC0).put((byte) 34)           // MotionPhotoMetadataDescriptor
                .put((byte) 0xC1).put((byte) 24)           // MotionPhotoScoreDescriptor
                .putInt(0)                                 // modelVersion: nothing scored
                .put((byte) 0xC3).put((byte) 12)           // primaryImageFrameScoreDescr
                .putFloat(0f)                              // score
                .putLong(timestamp)                        // presentationTimestampUs
                .put((byte) 0xC2).put((byte) 4)            // highResTrackScoreDescr
                .putInt(0)                                 // numScoredFrames
                .put((byte) 0xC4).put((byte) 6)            // MotionPhotoFlagsDescriptor
                .put((byte) 0xC5).put((byte) 1).put((byte) 0)   // low res: not stabilized
                .put((byte) 0xC5).put((byte) 1).put((byte) 0)   // high res: not stabilized
                .array();

        assertArrayEquals(expected, MotionPhotoWriter.metadataTrackSample(timestamp));
    }

    @Test
    public void aLargeDescriptorSizeTakesSeveralBytes() {
        byte[] small = MotionPhotoWriter.descriptor(0xC0, new byte[127]);
        assertEquals(127, small[1]);
        assertEquals(129, small.length);

        byte[] large = MotionPhotoWriter.descriptor(0xC0, new byte[200]);
        assertEquals("seven bits at a time, high bit set on all but the last",
                0x81, large[1] & 0xFF);
        assertEquals(200 - 128, large[2]);
        assertEquals(203, large.length);

        byte[] larger = MotionPhotoWriter.descriptor(0xC0, new byte[20_000]);
        assertEquals(0x81, larger[1] & 0xFF);
        assertEquals(0x9C, larger[2] & 0xFF);
        assertEquals(0x20, larger[3] & 0xFF);
    }



    /**
     * What a reader finds in a file: the clip {@link MediaTags} locates, and the XMP packet with
     * its container directory.
     */
    private final class ReadBack {
        /** The clip MediaTags found, or null. */
        final MediaTags.MotionPhoto motionPhoto;
        /** The first XMP packet in the file. */
        final Xmp xmp;
        /** The container directory's items, in order. */
        final List<Map<String, String>> items;
        /** How many XMP segments the file has. */
        final int xmpSegments;

        ReadBack(byte[] file) throws IOException {
            File written = write("readback" + (++mReads) + ".jpg", file);
            motionPhoto = MediaTags.open(written).getMotionPhoto();
            int segments = 0;
            byte[] packet = null;
            for (int at = 0; (at = indexOf(file, MotionPhotoWriter.XMP_SIGNATURE, at)) >= 0;
                    at++) {
                if (packet == null) {
                    int length = ((file[at - 2] & 0xFF) << 8) | (file[at - 1] & 0xFF);
                    packet = Arrays.copyOfRange(file, at + MotionPhotoWriter.XMP_SIGNATURE.length,
                            at - 2 + length);
                }
                segments++;
            }
            xmpSegments = segments;
            xmp = Xmp.parse(packet == null ? new byte[0] : packet);
            items = xmp.getStructs(Xmp.NS_CONTAINER, "Directory");
        }

        String camera(String property) {
            return xmp.get(Xmp.NS_CAMERA, property);
        }

        boolean hasCamera(String property) {
            return xmp.get(Xmp.NS_CAMERA, property) != null;
        }
    }

    /** How many files {@link #read} has written, to name the next. */
    private int mReads;

    /** Reads a file back, and checks MediaTags locates its clip where the directory says. */
    private ReadBack read(byte[] file) throws IOException {
        ReadBack readBack = new ReadBack(file);
        assertNotNull("MediaTags finds no clip", readBack.motionPhoto);
        long length = Long.parseLong(readBack.items.get(readBack.items.size() - 1)
                .get(Xmp.NS_ITEM + "Length"));
        assertEquals(length, readBack.motionPhoto.getVideoLength());
        assertEquals(file.length - length, readBack.motionPhoto.getVideoOffset());
        return readBack;
    }

    private void assertRefused(String why, byte[] jpeg, long videoLength) {
        try {
            MotionPhotoWriter.withMotionPhotoXmp(jpeg, videoLength, 0);
            fail("accepted " + why);
        } catch (IOException expected) {
            // Refused, as it should be.
        }
    }

    private File write(String name, byte[] data) throws IOException {
        File file = new File(folder.getRoot(), name);
        try (OutputStream output = new FileOutputStream(file)) {
            output.write(data);
        }
        return file;
    }

    private static byte[] appendVideo(byte[] jpeg, int length) {
        return concat(jpeg, fakeMp4(length));
    }

    /** Bytes that start the way an MP4 does, which is all a container check looks at. */
    private static byte[] fakeMp4(int length) {
        byte[] video = new byte[length];
        if (length >= 8) {
            ByteBuffer.wrap(video).putInt(Math.min(length, 24)).put("ftyp".getBytes(
                    StandardCharsets.US_ASCII));
        }
        for (int i = 8; i < length; i++) {
            video[i] = (byte) (i * 31);
        }
        return video;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    private static boolean startsWith(byte[] data, int offset, byte[] prefix) {
        return offset + prefix.length <= data.length
                && Arrays.equals(Arrays.copyOfRange(data, offset, offset + prefix.length), prefix);
    }

    private static int indexOf(byte[] data, byte[] needle) {
        return indexOf(data, needle, 0);
    }

    private static int indexOf(byte[] data, byte[] needle, int from) {
        for (int i = from; i + needle.length <= data.length; i++) {
            if (startsWith(data, i, needle)) {
                return i;
            }
        }
        return -1;
    }

    /** Builds the segment structure of a JPEG, which is all the writer reads. */
    private static final class Jpeg {
        private final ByteArrayOutputStream mBytes = new ByteArrayOutputStream();

        Jpeg() {
            mBytes.write(0xFF);
            mBytes.write(0xD8);
        }

        int size() {
            return mBytes.size();
        }

        Jpeg jfif() {
            return segment(0xE0, "JFIF\0".getBytes(StandardCharsets.US_ASCII),
                    new byte[] {1, 2, 0, 0, 1, 0, 1, 0, 0});
        }

        Jpeg exif() {
            return segment(0xE1, new byte[] {'E', 'x', 'i', 'f', 0, 0},
                    new byte[] {'M', 'M', 0, 42, 0, 0, 0, 8, 0, 0});
        }

        Jpeg xmp(String packet) {
            return segment(0xE1, MotionPhotoWriter.XMP_SIGNATURE,
                    packet.getBytes(StandardCharsets.UTF_8));
        }

        Jpeg mpf() {
            return segment(0xE2, new byte[] {'M', 'P', 'F', 0},
                    new byte[] {'M', 'M', 0, 42, 0, 0, 0, 8});
        }

        Jpeg quantization() {
            byte[] table = new byte[65];
            for (int i = 1; i < table.length; i++) {
                table[i] = (byte) i;
            }
            return segment(0xDB, new byte[0], table);
        }

        /** A scan header, some entropy-coded data with a stuffed 0xFF, and the end of image. */
        Jpeg scan() {
            segment(0xDA, new byte[0], new byte[] {1, 1, 0, 0, 63, 0});
            mBytes.write(0x12);
            mBytes.write(0xFF);
            mBytes.write(0x00);
            mBytes.write(0x34);
            mBytes.write(0xFF);
            mBytes.write(0xD9);
            return this;
        }

        byte[] build() {
            return mBytes.toByteArray();
        }

        private Jpeg segment(int marker, byte[] identifier, byte[] body) {
            int length = 2 + identifier.length + body.length;
            mBytes.write(0xFF);
            mBytes.write(marker);
            mBytes.write(length >> 8);
            mBytes.write(length & 0xFF);
            mBytes.write(identifier, 0, identifier.length);
            mBytes.write(body, 0, body.length);
            return this;
        }
    }
}
