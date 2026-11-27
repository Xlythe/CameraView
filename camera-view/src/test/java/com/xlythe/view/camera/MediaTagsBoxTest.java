package com.xlythe.view.camera;

import static com.xlythe.view.camera.MediaFixtures.ascii;
import static com.xlythe.view.camera.MediaFixtures.audioTrack;
import static com.xlythe.view.camera.MediaFixtures.box;
import static com.xlythe.view.camera.MediaFixtures.colr;
import static com.xlythe.view.camera.MediaFixtures.concat;
import static com.xlythe.view.camera.MediaFixtures.file;
import static com.xlythe.view.camera.MediaFixtures.ftyp;
import static com.xlythe.view.camera.MediaFixtures.fullBox;
import static com.xlythe.view.camera.MediaFixtures.hdlr;
import static com.xlythe.view.camera.MediaFixtures.mvhd;
import static com.xlythe.view.camera.MediaFixtures.read;
import static com.xlythe.view.camera.MediaFixtures.tkhd;
import static com.xlythe.view.camera.MediaFixtures.u16;
import static com.xlythe.view.camera.MediaFixtures.u32;
import static com.xlythe.view.camera.MediaFixtures.utf8;
import static com.xlythe.view.camera.MediaFixtures.videoTrack;
import static com.xlythe.view.camera.MediaFixtures.visualEntry;
import static com.xlythe.view.camera.MediaFixtures.xmp;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.location.Location;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.TimeZone;

/** The box formats: HEIF and AVIF pictures, and MP4, QuickTime, 3GP and M4A files. */
@RunWith(RobolectricTestRunner.class)
public class MediaTagsBoxTest {
    /** A trimmed HEVC decoder configuration whose parameter set says PQ, from Chromium's test data. */
    private static final String HVCC_PQ = "0102200000009000000000005df000fcfdfafa00000f03"
            + "200001001840010c01ffff02200000030090000003000003005d959809"
            + "210001002d42010102200000030090000003000003005da00280802d1365959a4932bc05a848"
            + "804f0800001f480003a98040"
            + "22000100074401c172b46240";

    /** What stands for the picture data in every HEIF fixture. */
    private static final byte[] PIXELS = ascii("PIXELS-HERE");

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

    // ---------- HEIF ----------

    /**
     * A HEIF picture: one image item rotated by irot, an Exif item that says Orientation 6 as
     * well, extra properties, and an optional tail after the data.
     */
    static byte[] heif(int irotQuarters, String extraItemType, byte[] auxC, byte[] tail) {
        byte[] exif = concat(new byte[] {0, 0, 0, 6}, ExifImageContainer.EXIF_IDENTIFIER,
                MediaTagsImageTest.exifWithOrientation(6));
        byte[] ftyp = ftyp("heic", "mif1", "heic");
        byte[] meta = heifMeta(0, 0, exif.length, irotQuarters, extraItemType, auxC);
        long data = ftyp.length + meta.length + 8;
        meta = heifMeta(data, data + PIXELS.length, exif.length, irotQuarters, extraItemType,
                auxC);
        return concat(ftyp, meta, box("mdat", PIXELS, exif), tail);
    }

    private static byte[] heifMeta(long pixels, long exifAt, int exifLength, int irot,
            String extraType, byte[] auxC) {
        int items = extraType == null ? 2 : 3;
        byte[] iinf = fullBox("iinf", 0, 0, u16(items), infe(1, "hvc1"), infe(2, "Exif"),
                extraType == null ? new byte[0] : infe(3, extraType));
        byte[] iloc = fullBox("iloc", 1, 0, new byte[] {0x44, 0x00}, u16(2),
                u16(1), u16(0), u16(0), u16(1), u32(pixels), u32(PIXELS.length),
                u16(2), u16(0), u16(0), u16(1), u32(exifAt), u32(exifLength));
        byte[] iref = fullBox("iref", 0, 0, box("cdsc", u16(2), u16(1), u16(1)));
        byte[] ispe = fullBox("ispe", 0, 0, u32(64), u32(48));
        byte[] irotBox = box("irot", new byte[] {(byte) irot});
        byte[] ipco = box("ipco", ispe, irotBox, auxC == null ? new byte[0] : auxC);
        byte[] associations = auxC == null ? new byte[] {0, 1, 2, 0x01, (byte) 0x82}
                : new byte[] {0, 1, 2, 0x01, (byte) 0x82, 0, 3, 1, (byte) 0x83};
        byte[] ipma = fullBox("ipma", 0, 0, u32(auxC == null ? 1 : 2), associations);
        return fullBox("meta", 0, 0, hdlr("pict"), fullBox("pitm", 0, 0, u16(1)), iinf, iloc,
                iref, box("iprp", ipco, ipma));
    }

    private static byte[] infe(int id, String type) {
        return fullBox("infe", 2, 0, u16(id), u16(0), ascii(type), new byte[1]);
    }

    @Test
    public void heifReadsItsExifItemAndAppliesOnlyItsRotation() throws IOException {
        MediaTags tags = MediaTags.read(heif(1, null, null, new byte[0]));
        assertEquals(MediaTags.FORMAT_HEIF, tags.getFormat());
        // irot turns a quarter anticlockwise; the Exif's own 6 is not applied on top.
        assertEquals(270, tags.getRotation());
        assertFalse(tags.isMirrored());
        assertEquals(0, MediaTags.read(heif(0, null, null, new byte[0])).getRotation());
    }

    @Test
    public void heifWritesExifAndXmpItemsAndKeepsEveryOffsetRight() throws IOException {
        byte[] video = MediaTagsFixtures.mp4Clip(300);
        byte[] original = heif(1, null, null, video);
        File file = file(original, ".heic");
        MediaTags tags = MediaTags.open(file);
        assertTrue(tags.setTitle("Spatial"));
        assertTrue(tags.setPanorama(MediaTagsFixtures.everyGPanoField()));
        tags.save();

        MediaTags read = MediaTags.open(file);
        assertEquals("Spatial", read.getTitle());
        assertEquals(MediaTagsFixtures.everyGPanoField(), read.getPanorama());
        assertEquals(270, read.getRotation());
        byte[] bytes = read(file);
        // The picture data is still where its location says, and what follows still ends the
        // file, so a motion photo's clip stays at the end.
        assertArrayEquals(PIXELS, HeifContainer.itemData(bytes, 1));
        assertArrayEquals(video, Arrays.copyOfRange(bytes, bytes.length - video.length,
                bytes.length));

        // Again, now that both items exist.
        read.setTitle("Spatial 2");
        read.setXmp("http://example.com/ns/", "Rating", "1");
        read.save();
        read = MediaTags.open(file);
        assertEquals("Spatial 2", read.getTitle());
        assertEquals("1", read.getXmp("http://example.com/ns/", "Rating"));
        assertArrayEquals(PIXELS, HeifContainer.itemData(read(file), 1));
    }

    @Test
    public void heifWritesItsOrientationAsTransforms() throws IOException {
        File file = file(heif(1, null, null, new byte[0]), ".heic");
        for (int rotation = 0; rotation < 360; rotation += 90) {
            for (boolean mirrored : new boolean[] {false, true}) {
                MediaTags tags = MediaTags.open(file);
                assertTrue(tags.setOrientation(rotation, mirrored));
                tags.save();
                MediaTags read = MediaTags.open(file);
                assertEquals(rotation, read.getRotation());
                assertEquals(mirrored, read.isMirrored());
                assertArrayEquals(PIXELS, HeifContainer.itemData(read(file), 1));
            }
        }
        assertEquals(1, HeifContainer.exifOrientationOf(new int[] {2, 0, 0, 2}));
    }

    @Test
    public void heifFindsGainMaps() throws IOException {
        assertEquals(MediaTags.GAIN_MAP_ISO_21496_1,
                MediaTags.read(heif(0, "tmap", null, new byte[0])).getGainMap());
        byte[] apple = fullBox("auxC", 0, 0, ascii("urn:com:apple:photo:2020:aux:hdrgainmap"),
                new byte[1]);
        assertEquals(MediaTags.GAIN_MAP_APPLE,
                MediaTags.read(heif(0, "hvc1", apple, new byte[0])).getGainMap());
        assertEquals(MediaTags.GAIN_MAP_NONE,
                MediaTags.read(heif(0, null, null, new byte[0])).getGainMap());
    }

    /**
     * iOS 18 keeps Apple's own gain map and adds an ISO 21496-1 tmap item beside it; the
     * standard one is what is reported.
     */
    @Test
    public void heifWithBothGainMapsReportsTheStandardOne() throws IOException {
        byte[] apple = fullBox("auxC", 0, 0, ascii("urn:com:apple:photo:2020:aux:hdrgainmap"),
                new byte[1]);
        assertEquals(MediaTags.GAIN_MAP_ISO_21496_1,
                MediaTags.read(heif(0, "tmap", apple, new byte[0])).getGainMap());
    }

    @Test
    public void heifFindsAMotionPhotoAtItsEndAndKeepsItThere() throws IOException {
        byte[] video = MediaTagsFixtures.mp4Clip(400);
        File file = file(heif(0, null, null, box("mpvd", video)), ".heic");
        // The directory is a structure, which goes in as a packet of its own.
        HeifContainer container;
        try (ByteSource source = ByteSource.of(file)) {
            container = HeifContainer.read(source, true);
        }
        assertTrue(container.xmp().merge(Xmp.parse(MediaTagsFixtures.motionPhotoXmp(
                video.length, 8, true)), java.util.Collections.<String>emptySet()));
        container.xmpChanged();
        container.save(file);

        MediaTags.MotionPhoto motion = MediaTags.open(file).getMotionPhoto();
        byte[] bytes = read(file);
        assertEquals(bytes.length - video.length, motion.getVideoOffset());
        assertEquals(video.length, motion.getVideoLength());

        MediaTags tags = MediaTags.open(file);
        tags.setTitle("Still a motion photo");
        tags.save();
        motion = MediaTags.open(file).getMotionPhoto();
        bytes = read(file);
        assertEquals(bytes.length - video.length, motion.getVideoOffset());
        assertArrayEquals(video, Arrays.copyOfRange(bytes, (int) motion.getVideoOffset(),
                bytes.length));
    }

    @Test
    public void detectsHeifAndAvifBrands() {
        assertEquals(MediaTags.FORMAT_HEIF, MediaTags.detect(ftyp("mif1", "mif1", "heic")));
        assertEquals(MediaTags.FORMAT_HEIF, MediaTags.detect(ftyp("avif", "avif", "mif1")));
        assertEquals(MediaTags.FORMAT_MP4, MediaTags.detect(ftyp("isom", "isom", "avc1")));
    }

    // ---------- MP4 ----------

    /** A movie: rotated video, a colour box, and a sample in an mdat after the moov. */
    static byte[] movie(byte[] afterMoov, byte[]... sampleEntryChildren) {
        byte[] ftyp = ftyp("isom", "isom", "mp42");
        byte[] entry = visualEntry("avc1", sampleEntryChildren);
        byte[] moov = box("moov", mvhd(3_000_000_000L), videoTrack(
                tkhd(0, 0x10000, -0x10000, 0, 640, 480), entry, 0), audioTrack());
        long sample = ftyp.length + moov.length + afterMoov.length + 8;
        moov = box("moov", mvhd(3_000_000_000L), videoTrack(
                tkhd(0, 0x10000, -0x10000, 0, 640, 480), entry, sample), audioTrack());
        return concat(ftyp, moov, afterMoov, box("mdat", ascii("SAMPLE")));
    }

    /** The bytes the first video track's chunk offset points at, which must stay "SAMPLE". */
    private static String sample(byte[] file) {
        int at = Bytes.indexOf(file, ascii("stco"), 0);
        long offset = Bytes.u32(file, at + 12);
        return new String(file, (int) offset, 6, StandardCharsets.ISO_8859_1);
    }

    @Test
    public void mp4ReadsWhatItsBoxesSay() throws IOException {
        MediaTags tags = MediaTags.read(movie(new byte[0], colr(16)));
        assertEquals(MediaTags.FORMAT_MP4, tags.getFormat());
        assertEquals(MediaTags.KIND_VIDEO, tags.getKind());
        assertEquals(90, tags.getRotation());
        assertEquals(MediaTags.TRANSFER_PQ, tags.getVideoTransfer());
        assertArrayEquals(new int[] {480, 640}, tags.getVideoSize());
        assertEquals((3_000_000_000L - Mp4Container.SECONDS_1904_TO_1970) * 1000,
                tags.getCaptureTime());
        assertEquals(MediaTags.TRANSFER_HLG,
                MediaTags.read(movie(new byte[0], colr(18))).getVideoTransfer());
        assertEquals(MediaTags.TRANSFER_SDR,
                MediaTags.read(movie(new byte[0], colr(1))).getVideoTransfer());
        assertEquals(MediaTags.TRANSFER_UNKNOWN,
                MediaTags.read(movie(new byte[0])).getVideoTransfer());
    }

    @Test
    public void mp4FindsHdrInTheStreamWhenNoColourBoxSaysSo() throws IOException {
        byte[] hvcC = box("hvcC", new BigInteger(HVCC_PQ, 16).toByteArray());
        assertEquals(16, HevcParameters.transferCharacteristics(hvcC, 8, hvcC.length));
        assertEquals(MediaTags.TRANSFER_PQ,
                MediaTags.read(movie(new byte[0], hvcC)).getVideoTransfer());
        // VP9's configuration names it outright.
        byte[] vpcC = fullBox("vpcC", 1, 0, new byte[] {2, 10, (byte) 0xA0, 9, 18, 9, 0, 0});
        assertEquals(MediaTags.TRANSFER_HLG,
                MediaTags.read(movie(new byte[0], vpcC)).getVideoTransfer());
        // Dolby Vision's configuration, compatible with HLG or not.
        byte[] dvvC = box("dvvC", new byte[] {1, 0, 0x10, 0x35, 0x40, 0, 0, 0});
        assertEquals(MediaTags.TRANSFER_HLG,
                MediaTags.read(movie(new byte[0], dvvC)).getVideoTransfer());
        byte[] dvcC = box("dvcC", new byte[] {1, 0, 0x0A, 0x35, 0x00, 0, 0, 0});
        assertEquals(MediaTags.TRANSFER_PQ,
                MediaTags.read(movie(new byte[0], dvcC)).getVideoTransfer());
        assertEquals(-1, HevcParameters.transferCharacteristics(new byte[30], 0, 30));
    }

    @Test
    public void mp4WritesEveryFieldAndMovesTheMoovWithoutMovingSamples() throws IOException {
        File file = file(movie(new byte[0]), ".mp4");
        MediaTags tags = MediaTags.open(file);
        assertTrue(tags.setTitle("Holiday"));
        assertTrue(tags.setDescription("Beach"));
        assertTrue(tags.setAuthor("Ada"));
        assertTrue(tags.setCopyright("2026"));
        assertTrue(tags.setMake("Maker"));
        assertTrue(tags.setModel("Model"));
        assertTrue(tags.setSoftware("App"));
        Location location = new Location("gps");
        location.setLatitude(37.3317);
        location.setLongitude(-122.0302);
        location.setAltitude(12.345);
        assertTrue(tags.setLocation(location));
        assertTrue(tags.setHeading(200));
        assertTrue(tags.setCaptureTime(1_700_000_000_000L));
        assertTrue(tags.setOrientation(180, false));
        assertFalse(tags.setOrientation(180, true));
        assertTrue(tags.setCoverArt(MediaFixtures.png()));
        assertFalse(tags.setCoverArt(ascii("not a picture")));
        assertTrue(tags.setVideoTag(VideoMetadata.Tag.RATING, "4.5"));
        assertFalse(tags.setVideoTag(VideoMetadata.Tag.RATING, "great"));
        assertTrue(tags.setAmbientConditions(new AmbientConditions.Builder()
                .setTemperature(30).build()));
        tags.save();

        byte[] bytes = read(file);
        assertEquals("SAMPLE", sample(bytes));
        MediaTags read = MediaTags.open(file);
        assertEquals("Holiday", read.getTitle());
        assertEquals("Beach", read.getDescription());
        assertEquals("Ada", read.getAuthor());
        assertEquals("2026", read.getCopyright());
        assertEquals("Maker", read.getMake());
        assertEquals("Model", read.getModel());
        assertEquals("App", read.getSoftware());
        assertEquals(37.3317, read.getLocation().getLatitude(), 1e-4);
        assertEquals(12.345, read.getLocation().getAltitude(), 1e-3);
        assertEquals(200f, read.getHeading(), 0.01f);
        assertEquals(1_700_000_000_000L, read.getCaptureTime());
        assertEquals(180, read.getRotation());
        assertArrayEquals(MediaFixtures.png(), read.getCoverArt());
        assertEquals("4.5", read.getVideoTag(VideoMetadata.Tag.RATING));
        // No native place for the conditions in a movie, so XMP keeps them.
        assertEquals(30, read.getAmbientConditions().getTemperature(), 1e-9);
        assertNotNull(read.getXmp(Xmp.NS_EXIF_EX, "Temperature"));
        // What QuickTime readers know still reads the same.
        assertEquals("Holiday", VideoMetadata.read(file, VideoMetadata.Tag.TITLE));

        // Removed, and the movie header's own time with it.
        read.setTitle(null);
        read.setCaptureTime(-1);
        read.setCoverArt(null);
        read.save();
        read = MediaTags.open(file);
        assertNull(read.getTitle());
        assertEquals(-1, read.getCaptureTime());
        assertNull(read.getCoverArt());
        assertEquals("SAMPLE", sample(read(file)));
    }

    @Test
    public void mp4GrowsIntoTheFreeBoxAfterItsMoov() throws IOException {
        byte[] original = movie(box("free", new byte[2000]));
        File file = file(original, ".mp4");
        MediaTags tags = MediaTags.open(file);
        tags.setTitle("In place");
        tags.save();
        byte[] bytes = read(file);
        assertEquals(original.length, bytes.length);
        assertEquals("SAMPLE", sample(bytes));
        assertEquals("In place", MediaTags.open(file).getTitle());
    }

    @Test
    public void mp4WithItsMoovLastSimplyGrows() throws IOException {
        byte[] ftyp = ftyp("isom", "isom");
        byte[] mdat = box("mdat", ascii("SAMPLE"));
        byte[] entry = visualEntry("avc1");
        byte[] moov = box("moov", mvhd(0), videoTrack(tkhd(0x10000, 0, 0, 0x10000, 2, 2),
                entry, ftyp.length + 8));
        File file = file(concat(ftyp, mdat, moov), ".mp4");
        MediaTags tags = MediaTags.open(file);
        tags.setXmp("http://example.com/ns/", "Rating", "2");
        tags.setTitle("Last");
        tags.save();
        MediaTags read = MediaTags.open(file);
        assertEquals("Last", read.getTitle());
        assertEquals("2", read.getXmp("http://example.com/ns/", "Rating"));
        assertEquals("SAMPLE", sample(read(file)));
        assertEquals(-1, read.getCaptureTime());

        read.setXmp("http://example.com/ns/", "Rating", null);
        read.save();
        assertNull(MediaTags.open(file).getXmp("http://example.com/ns/", "Rating"));
    }

    /** Fragments that count their data from themselves are pushed along when the moov grows. */
    @Test
    public void aFragmentedMovieIsPushedAlongRatherThanMoved() throws IOException {
        byte[] ftyp = ftyp("isom", "isom");
        byte[] moov = box("moov", mvhd(0), box("mvex", fullBox("trex", 0, 0, new byte[20])));
        byte[] moof = box("moof", box("traf", fullBox("tfhd", 0, 0x020000, u32(1))));
        byte[] fragment = concat(moof, box("mdat", ascii("FRAGMENT")));
        // A random access index, which says where each fragment starts, at the end.
        long moofAt = ftyp.length + moov.length;
        byte[] tfra = fullBox("tfra", 1, 0, u32(1), u32(0), u32(1), u32(0), u32(0),
                u32(moofAt >> 32), u32(moofAt), new byte[3]);
        byte[] mfra = box("mfra", tfra, fullBox("mfro", 0, 0, u32(0)));
        File file = file(concat(ftyp, moov, fragment, mfra), ".mp4");
        MediaTags tags = MediaTags.open(file);
        assertTrue(tags.setTitle("Too big for its place"));
        tags.save();

        assertEquals("Too big for its place", MediaTags.open(file).getTitle());
        byte[] saved = read(file);
        int newMoof = Bytes.indexOf(saved, ascii("moof"), 0) - 4;
        // The moov still leads the fragments, and the fragment is whole, just further along.
        assertTrue(Bytes.indexOf(saved, ascii("moov"), 0) < newMoof);
        assertEquals(new String(fragment, StandardCharsets.ISO_8859_1),
                new String(saved, newMoof, fragment.length,
                        StandardCharsets.ISO_8859_1));
        int entry = Bytes.indexOf(saved, ascii("tfra"), 0) + 4 + 16 + 8;
        assertEquals(newMoof, (Bytes.u32(saved, entry) << 32) | Bytes.u32(saved, entry + 4));
    }

    /** Fragments that count their data from the file's start cannot move, so none is written. */
    @Test
    public void aFragmentedMovieThatCannotMoveIsReadOnly() throws IOException {
        byte[] ftyp = ftyp("isom", "isom");
        byte[] moov = box("moov", mvhd(0), box("mvex", fullBox("trex", 0, 0, new byte[20])));
        byte[] moof = box("moof", box("traf", fullBox("tfhd", 0, 0x000001, u32(1),
                u32(0), u32(0))));
        File file = file(concat(ftyp, moov, moof, box("mdat", new byte[8])), ".mp4");
        MediaTags tags = MediaTags.open(file);
        assertTrue(tags.isReadOnly());
        assertFalse(tags.setTitle("Nowhere to go"));

        byte[] indexed = concat(ftyp, box("sidx", new byte[24]), moov,
                box("moof", new byte[8]), box("mdat", new byte[8]));
        assertTrue(MediaTags.open(file(indexed, ".mp4")).isReadOnly());
    }

    @Test
    public void mp4ReadsSphericalVideoInBothVersions() throws IOException {
        byte[] prhd = fullBox("prhd", 0, 0, u32(10 << 16), u32(-5 << 16), u32(0));
        byte[] equi = fullBox("equi", 0, 0, u32(0x40000000L), u32(0), u32(0), u32(0));
        byte[] sv3d = box("sv3d", box("proj", prhd, equi));
        byte[] st3d = fullBox("st3d", 0, 0, new byte[] {1});
        MediaTags.SphericalVideo v2 = MediaTags.read(movie(new byte[0], sv3d, st3d))
                .getSphericalVideo();
        assertEquals(MediaTags.SphericalVideo.PROJECTION_EQUIRECTANGULAR, v2.getProjection());
        assertEquals(MediaTags.SphericalVideo.STEREO_TOP_BOTTOM, v2.getStereoMode());
        assertEquals(10f, v2.getPoseYawDegrees(), 1e-4);
        assertEquals(-5f, v2.getPosePitchDegrees(), 1e-4);
        assertEquals(0.25, v2.getCropTop(), 1e-6);
        assertTrue(v2.toString().contains("stereo=1"));

        String rdf = "<rdf:SphericalVideo xmlns:rdf=\"" + Xmp.NS_RDF + "\" xmlns:GSpherical=\""
                + Xmp.NS_SPHERICAL + "\"><GSpherical:Spherical>true</GSpherical:Spherical>"
                + "<GSpherical:Stitched>true</GSpherical:Stitched>"
                + "<GSpherical:ProjectionType>equirectangular</GSpherical:ProjectionType>"
                + "<GSpherical:StereoMode>left-right</GSpherical:StereoMode>"
                + "<GSpherical:FullPanoWidthPixels>4000</GSpherical:FullPanoWidthPixels>"
                + "<GSpherical:FullPanoHeightPixels>2000</GSpherical:FullPanoHeightPixels>"
                + "<GSpherical:CroppedAreaImageWidthPixels>4000"
                + "</GSpherical:CroppedAreaImageWidthPixels>"
                + "<GSpherical:CroppedAreaImageHeightPixels>1000"
                + "</GSpherical:CroppedAreaImageHeightPixels>"
                + "<GSpherical:CroppedAreaLeftPixels>0</GSpherical:CroppedAreaLeftPixels>"
                + "<GSpherical:CroppedAreaTopPixels>500</GSpherical:CroppedAreaTopPixels>"
                + "<GSpherical:InitialViewHeadingDegrees>90</GSpherical:InitialViewHeadingDegrees>"
                + "</rdf:SphericalVideo>";
        byte[] ftyp = ftyp("isom", "isom");
        byte[] uuid = IsoBoxes.uuidBox(Mp4Container.SPHERICAL_UUID, utf8(rdf));
        byte[] track = box("trak", tkhd(0x10000, 0, 0, 0x10000, 4, 2), uuid,
                box("mdia", hdlr("vide"), box("minf", box("stbl", fullBox("stsd", 0, 0,
                        u32(1), visualEntry("avc1"))))));
        MediaTags.SphericalVideo v1 = MediaTags.read(concat(ftyp, box("moov", mvhd(0),
                track))).getSphericalVideo();
        assertEquals(MediaTags.SphericalVideo.STEREO_LEFT_RIGHT, v1.getStereoMode());
        assertEquals(0.25, v1.getCropTop(), 1e-6);
        assertEquals(0.25, v1.getCropBottom(), 1e-6);
        assertEquals(90f, v1.getPoseYawDegrees(), 0f);
        assertNull(MediaTags.read(movie(new byte[0])).getSphericalVideo());
    }

    @Test
    public void readsQuickTime3gppAndItunesForms() throws IOException {
        byte[] titl = fullBox("titl", 0, 0, u16(0x15C7), utf8("Asset title"), new byte[1]);
        byte[] auth = fullBox("auth", 0, 0, u16(0x15C7), new byte[] {(byte) 0xFE, (byte) 0xFF},
                "Zoë".getBytes(java.nio.charset.StandardCharsets.UTF_16BE), new byte[2]);
        byte[] loci = fullBox("loci", 0, 0, u16(0x15C7), utf8("Home"), new byte[1],
                new byte[] {0}, u32(10 << 16), u32(20 << 16), u32(30 << 16), new byte[2]);
        byte[] udta = box("udta", titl, auth, loci);
        byte[] ftyp = ftyp("3gp4", "3gp4");
        MediaTags tags = MediaTags.read(concat(ftyp, box("moov", mvhd(0), udta)));
        assertEquals(MediaTags.FORMAT_3GP, tags.getFormat());
        assertEquals("Asset title", tags.getTitle());
        assertEquals("Zoë", tags.getAuthor());
        assertEquals(20, tags.getLocation().getLatitude(), 1e-6);
        assertEquals(10, tags.getLocation().getLongitude(), 1e-6);

        // An M4A keeps iTunes items, which an audio player reads.
        File file = file(concat(ftyp("M4A ", "M4A ", "mp42"), box("moov", mvhd(0),
                audioTrack())), ".m4a");
        MediaTags m4a = MediaTags.open(file);
        assertEquals(MediaTags.FORMAT_M4A, m4a.getFormat());
        assertEquals(MediaTags.KIND_AUDIO, m4a.getKind());
        assertFalse(m4a.setOrientation(90, false));
        assertTrue(m4a.setTitle("Song"));
        assertTrue(m4a.setCaptureTime(1_700_000_000_000L));
        assertTrue(m4a.setCoverArt(MediaFixtures.png()));
        m4a.save();
        byte[] bytes = read(file);
        assertTrue(Bytes.indexOf(bytes, ascii("ilst"), 0) > 0);
        assertTrue(Bytes.indexOf(bytes, ascii("covr"), 0) > 0);
        assertEquals("Song", MediaTags.open(file).getTitle());
    }

    @Test
    public void readsALivePhotosIdentifier() throws IOException {
        File file = file(movie(new byte[0]), ".mov");
        MediaTags tags = MediaTags.open(file);
        assertNull(tags.getLivePhotoId());
        // No tag reaches the key, so it is written as Apple's own recorder writes it.
        byte[] moov = box("moov", box("meta", hdlr("mdta"),
                fullBox("keys", 0, 0, u32(1), box("mdta", utf8(
                        Mp4Container.KEY_CONTENT_IDENTIFIER))),
                box("ilst", concat(u32(8 + 8 + 8 + 36), u32(1), box("data", u32(1), u32(0),
                        utf8("1745E022-9712-43C7-B024-F308EC6413CB"))))));
        assertEquals("1745E022-9712-43C7-B024-F308EC6413CB",
                MediaTags.read(concat(ftyp("qt  ", "qt  "), moov)).getLivePhotoId());
        assertEquals(MediaTags.FORMAT_QUICKTIME,
                MediaTags.read(concat(ftyp("qt  ", "qt  "), moov)).getFormat());

        // And the still keeps it in Apple's maker note.
        byte[] note = new byte[16 + 12 + 40];
        System.arraycopy(ascii("Apple iOS\u0000\u0000\u0001MM"), 0, note, 0, 14);
        Bytes.put16(note, 14, 1);
        Bytes.put16(note, 16, 0x0011);
        Bytes.put16(note, 18, 2);
        Bytes.put32(note, 20, 37);
        Bytes.put32(note, 24, 28);
        System.arraycopy(ascii("1745E022-9712-43C7-B024-F308EC6413CB"), 0, note, 28, 36);
        assertEquals("1745E022-9712-43C7-B024-F308EC6413CB",
                MediaContainer.appleContentIdentifier(note));
        assertNull(MediaContainer.appleContentIdentifier(ascii("Nikon\u0000")));
    }

    @Test
    public void placesBoxesAndRepairsALastBoxThatRanToTheEnd() throws IOException {
        byte[] ftyp = ftyp("isom", "isom");
        byte[] moov = box("moov", mvhd(0), videoTrack(tkhd(0x10000, 0, 0, 0x10000, 2, 2),
                visualEntry("avc1"), ftyp.length + 0 + 8 + 2000));
        byte[] mdat = box("mdat", new byte[16]);
        // Its size says it runs to the end of the file.
        Bytes.put32(mdat, 0, 0);
        File file = file(concat(ftyp, moov, mdat), ".mp4");
        MediaTags tags = MediaTags.open(file);
        tags.setTitle("Appended past an open-ended mdat");
        tags.save();
        byte[] bytes = read(file);
        int mdatAt = ftyp.length + moov.length;
        assertEquals(24, Bytes.u32(bytes, mdatAt));
        assertEquals("Appended past an open-ended mdat", MediaTags.open(file).getTitle());
        assertEquals(0, Mp4Container.orientationOf(new int[] {0, 0, 0, 0}));
        assertEquals(2, Mp4Container.orientationOf(new int[] {-1, 0, 0, 1}));
        assertEquals(7, Mp4Container.orientationOf(new int[] {0, -1, -1, 0}));
        assertArrayEquals(new int[] {0, -0x10000, 0x10000, 0}, Mp4Container.rotationMatrix(270));
        assertNull(Mp4Container.parseIso6709("nowhere"));
        assertNull(Mp4Container.parseIso6709("+91.0+000.0/"));
        assertEquals("+37.3317-122.0302/", Mp4Container.formatIso6709(location(37.3317,
                -122.0302)));
    }

    private static Location location(double latitude, double longitude) {
        Location location = new Location("test");
        location.setLatitude(latitude);
        location.setLongitude(longitude);
        return location;
    }

    @Test
    public void brandlessMoviesAreQuickTime() throws IOException {
        byte[] moov = box("moov", mvhd(0), videoTrack(tkhd(0x10000, 0, 0, 0x10000, 2, 2),
                visualEntry("avc1"), 0));
        MediaTags tags = MediaTags.read(concat(box("wide"), moov));
        assertEquals(MediaTags.FORMAT_QUICKTIME, tags.getFormat());
        assertEquals(MediaTags.KIND_VIDEO, tags.getKind());
        assertEquals(MediaTags.FORMAT_UNKNOWN, MediaTags.read(concat(ftyp("crx ", "crx "),
                moov)).getFormat());
        assertEquals(MediaTags.KIND_IMAGE, MediaTags.read(concat(ftyp("crx ", "crx "), moov))
                .getKind());
    }

    @Test
    public void readsXmpKeptInUserData() throws IOException {
        String packet = xmp("xmlns:dc=\"" + Xmp.NS_DC + "\"", "dc:format=\"video/quicktime\"");
        byte[] moov = box("moov", mvhd(0), box("udta", box("XMP_", utf8(packet))));
        assertEquals("video/quicktime", MediaTags.read(concat(ftyp("qt  ", "qt  "), moov))
                .getXmp(Xmp.NS_DC, "format"));
    }
}
