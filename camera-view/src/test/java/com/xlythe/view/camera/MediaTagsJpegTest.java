package com.xlythe.view.camera;

import static com.xlythe.view.camera.MediaFixtures.ascii;
import static com.xlythe.view.camera.MediaFixtures.concat;
import static com.xlythe.view.camera.MediaFixtures.exifSegment;
import static com.xlythe.view.camera.MediaFixtures.file;
import static com.xlythe.view.camera.MediaFixtures.jpeg;
import static com.xlythe.view.camera.MediaFixtures.read;
import static com.xlythe.view.camera.MediaFixtures.segment;
import static com.xlythe.view.camera.MediaFixtures.utf8;
import static com.xlythe.view.camera.MediaFixtures.xmp;
import static com.xlythe.view.camera.MediaFixtures.xmpElements;
import static com.xlythe.view.camera.MediaFixtures.xmpSegment;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.location.Location;
import android.net.Uri;

import androidx.exifinterface.media.ExifInterface;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.TimeZone;

/** Reading and writing a JPEG's metadata: Exif natively, XMP for the rest. */
@RunWith(RobolectricTestRunner.class)
public class MediaTagsJpegTest {
    private TimeZone mZone;

    @Before
    public void setUp() {
        mZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
    }

    @After
    public void tearDown() {
        TimeZone.setDefault(mZone);
    }

    private static File exifJpeg() throws IOException {
        return file(ExifFixtures.exifJpeg(false), ".jpg");
    }

    @Test
    public void detectsTheFormatFromTheBytes() throws IOException {
        MediaTags tags = MediaTags.open(file(jpeg(), ".mp4"));
        assertEquals(MediaTags.FORMAT_JPEG, tags.getFormat());
        assertEquals(MediaTags.KIND_IMAGE, tags.getKind());
        assertFalse(tags.isReadOnly());
    }

    @Test
    public void everyTextFieldRoundTrips() throws IOException {
        File file = exifJpeg();
        MediaTags tags = MediaTags.open(file);
        assertTrue(tags.setTitle("Birthday"));
        assertTrue(tags.setDescription("A cake"));
        assertTrue(tags.setAuthor("Ada"));
        assertTrue(tags.setCopyright("2026 Ada"));
        assertTrue(tags.setMake("Maker"));
        assertTrue(tags.setModel("Model 1"));
        assertTrue(tags.setSoftware("App 2"));
        tags.save();

        MediaTags read = MediaTags.open(file);
        assertEquals("Birthday", read.getTitle());
        assertEquals("A cake", read.getDescription());
        assertEquals("Ada", read.getAuthor());
        assertEquals("2026 Ada", read.getCopyright());
        assertEquals("Maker", read.getMake());
        assertEquals("Model 1", read.getModel());
        assertEquals("App 2", read.getSoftware());
        // Natively, so with no XMP written for them.
        assertNull(read.getXmp(Xmp.NS_DC, "title"));
        Exif exif = new Exif(file);
        assertEquals("A cake", exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION));
        assertEquals("Ada", exif.getAttribute(ExifInterface.TAG_ARTIST));
    }

    @Test
    public void textBeyondAsciiGoesToXmpAndLeavesNothingStaleBehind() throws IOException {
        File file = exifJpeg();
        MediaTags tags = MediaTags.open(file);
        assertTrue(tags.setDescription("Before"));
        tags.save();
        tags = MediaTags.open(file);
        assertTrue(tags.setDescription("Gâteau d'anniversaire 🎂"));
        assertTrue(tags.setTitle("Fête"));
        assertTrue(tags.setAuthor("Zoë"));
        tags.save();

        MediaTags read = MediaTags.open(file);
        assertEquals("Gâteau d'anniversaire 🎂", read.getDescription());
        assertEquals("Gâteau d'anniversaire 🎂", read.getXmp(Xmp.NS_DC, "description"));
        assertNull(new Exif(file).getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION));
        // The title and photographer tags hold UTF-8 themselves.
        assertEquals("Fête", read.getTitle());
        assertNull(read.getXmp(Xmp.NS_DC, "title"));
        assertEquals("Zoë", read.getAuthor());
        assertNull(new Exif(file).getAttribute(ExifInterface.TAG_ARTIST));
    }

    @Test
    public void removesFields() throws IOException {
        File file = exifJpeg();
        MediaTags tags = MediaTags.open(file);
        tags.setTitle("Gone soon");
        tags.setCopyright("Gone soon");
        tags.save();
        tags = MediaTags.open(file);
        assertTrue(tags.setTitle(null));
        assertTrue(tags.setCopyright("  "));
        tags.save();
        MediaTags read = MediaTags.open(file);
        assertNull(read.getTitle());
        assertNull(read.getCopyright());
    }

    @Test
    public void captureTimeKeepsItsOffset() throws IOException {
        File file = exifJpeg();
        MediaTags tags = MediaTags.open(file);
        long time = 1_777_000_000_123L;
        assertTrue(tags.setCaptureTime(time));
        tags.save();
        assertEquals(time, MediaTags.open(file).getCaptureTime());
        Exif exif = new Exif(file);
        assertEquals("-04:00", exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL));
        assertEquals("123", exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL));

        // Read anywhere, the instant is the same.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
        assertEquals(time, MediaTags.open(file).getCaptureTime());

        tags = MediaTags.open(file);
        assertTrue(tags.setCaptureTime(-1));
        tags.save();
        assertEquals(-1, MediaTags.open(file).getCaptureTime());
    }

    @Test
    public void aTimeWithoutAnOffsetIsTheDevicesOwn() {
        long time = ExifImageContainer.parseExifTime("2020:01:02 03:04:05", "5", null);
        TimeZone zone = TimeZone.getDefault();
        assertEquals(1577934245000L + 500 - zone.getOffset(1577934245000L), time, 3600_000L);
        assertEquals(-1, ExifImageContainer.parseExifTime("0000:00:00 00:00:00", null, null));
        assertEquals(-1, ExifImageContainer.parseExifTime("garbage", null, null));
        assertEquals(-1, ExifImageContainer.parseExifTime(null, null, null));
        assertEquals(ExifImageContainer.parseExifTime("2020:01:02 03:04:05", null, "+01:00"),
                ExifImageContainer.parseExifTime("2020-01-02T03:04:05", null, "+0100"));
        assertNull(ExifImageContainer.parseOffset("+25:00"));
        assertNull(ExifImageContainer.parseOffset("1"));
        assertNull(ExifImageContainer.parseOffset("+1:2:3"));
        assertNotNull(ExifImageContainer.parseOffset("Z"));
        assertNotNull(ExifImageContainer.parseOffset("-08"));
    }

    @Test
    public void aFileThatSaysOnlyWhenItChangedKeepsThatAsItsCaptureTime() throws IOException {
        File file = exifJpeg();
        Exif exif = new Exif(file);
        exif.setAttribute(ExifInterface.TAG_DATETIME, "2014:04:05 16:19:02");
        exif.save();
        long before = MediaTags.open(file).getCaptureTime();
        assertTrue(before > 0);

        MediaTags tags = MediaTags.open(file);
        tags.setTitle("Edited");
        tags.save();
        // The save stamped DateTime with now, but the capture time stayed.
        assertEquals(before, MediaTags.open(file).getCaptureTime());
    }

    @Test
    public void locationKeepsAltitudeAndTheHeadingSurvivesIt() throws IOException {
        File file = exifJpeg();
        MediaTags tags = MediaTags.open(file);
        assertTrue(tags.setHeading(123.45f));
        Location location = new Location("gps");
        location.setLatitude(-33.8568);
        location.setLongitude(151.2153);
        location.setAltitude(-12.5);
        assertTrue(tags.setLocation(location));
        tags.save();

        MediaTags read = MediaTags.open(file);
        assertEquals(-33.8568, read.getLocation().getLatitude(), 1e-4);
        assertEquals(151.2153, read.getLocation().getLongitude(), 1e-4);
        assertEquals(-12.5, read.getLocation().getAltitude(), 1e-3);
        assertEquals(123.45f, read.getHeading(), 0.01f);

        tags = MediaTags.open(file);
        assertTrue(tags.setLocation(null));
        assertTrue(tags.setHeading(-30));
        tags.save();
        read = MediaTags.open(file);
        assertNull(read.getLocation());
        assertEquals(330f, read.getHeading(), 0.01f);

        tags = MediaTags.open(file);
        assertTrue(tags.setHeading(Float.NaN));
        tags.save();
        assertTrue(Float.isNaN(MediaTags.open(file).getHeading()));
    }

    @Test
    public void everyOrientationRoundTrips() throws IOException {
        File file = exifJpeg();
        for (int rotation = 0; rotation < 360; rotation += 90) {
            for (boolean mirrored : new boolean[] {false, true}) {
                MediaTags tags = MediaTags.open(file);
                assertTrue(tags.setOrientation(rotation, mirrored));
                tags.save();
                MediaTags read = MediaTags.open(file);
                assertEquals(rotation, read.getRotation());
                assertEquals(mirrored, read.isMirrored());
            }
        }
        MediaTags tags = MediaTags.open(file);
        assertTrue(tags.setOrientation(-90, false));
        assertFalse(tags.setOrientation(45, false));
        tags.save();
        assertEquals(270, MediaTags.open(file).getRotation());
    }

    @Test
    public void anOrientationOfZeroIsUpright() throws IOException {
        File file = exifJpeg();
        Exif exif = new Exif(file);
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, "0");
        exif.save();
        MediaTags tags = MediaTags.open(file);
        assertEquals(0, tags.getRotation());
        assertFalse(tags.isMirrored());
    }

    @Test
    public void ambientConditionsRoundTrip() throws IOException {
        File file = exifJpeg();
        MediaTags tags = MediaTags.open(file);
        AmbientConditions conditions = new AmbientConditions.Builder()
                .setTemperature(21.5).setHumidity(40).setCameraElevationAngle(-10).build();
        assertTrue(tags.setAmbientConditions(conditions));
        tags.save();
        assertEquals(conditions, MediaTags.open(file).getAmbientConditions());
        tags = MediaTags.open(file);
        assertTrue(tags.setAmbientConditions(null));
        tags.save();
        assertNull(MediaTags.open(file).getAmbientConditions());
    }

    @Test
    public void reachesAnyExifTagByNumber() throws IOException {
        File file = exifJpeg();
        MediaTags tags = MediaTags.open(file);
        assertTrue(tags.setExifTag(ExifFixtures.TAG_UNDEFINED_BY_ANY_STANDARD,
                Exif.TYPE_SHORT, "7"));
        assertFalse(tags.setExifTag(ExifFixtures.TAG_UNDEFINED_BY_ANY_STANDARD,
                Exif.TYPE_SHORT, "seven"));
        tags.save();
        assertEquals("7", MediaTags.open(file).getExifTag(
                ExifFixtures.TAG_UNDEFINED_BY_ANY_STANDARD));
        assertNull(MediaTags.open(file).getVideoTag(VideoMetadata.Tag.TITLE));
        assertFalse(MediaTags.open(file).setVideoTag(VideoMetadata.Tag.TITLE, "x"));
    }

    @Test
    public void reachesAnyXmpProperty() throws IOException {
        File file = exifJpeg();
        MediaTags tags = MediaTags.open(file);
        assertTrue(tags.setXmp("http://example.com/ns/", "Rating", "5"));
        assertFalse(tags.setXmp("http://example.com/ns/", "not a name", "5"));
        assertFalse(tags.setXmp("", "Rating", "5"));
        assertFalse(tags.setXmp(Xmp.NS_RDF, "about", "x"));
        tags.save();
        MediaTags read = MediaTags.open(file);
        assertEquals("5", read.getXmp("http://example.com/ns/", "Rating"));
        byte[] bytes = read(file);
        // The packet goes straight after the Exif segment.
        int exif = indexOf(bytes, ascii("Exif\u0000\u0000"));
        int xmp = indexOf(bytes, JpegContainer.XMP_IDENTIFIER);
        assertTrue(exif > 0 && xmp > exif);
        tags = MediaTags.open(file);
        assertTrue(tags.setXmp("http://example.com/ns/", "Rating", null));
        tags.save();
        assertNull(MediaTags.open(file).getXmp("http://example.com/ns/", "Rating"));
        assertEquals(-1, indexOf(read(file), JpegContainer.XMP_IDENTIFIER));
    }

    @Test
    public void aPacketTooLargeForItsSegmentIsRefused() throws IOException {
        File file = exifJpeg();
        MediaTags tags = MediaTags.open(file);
        char[] huge = new char[70_000];
        Arrays.fill(huge, 'x');
        assertFalse(tags.setXmp("http://example.com/ns/", "Big", new String(huge)));
        assertTrue(tags.setXmp("http://example.com/ns/", "Small", "fits"));
    }

    @Test
    public void nativeWinsWhenExifAndXmpDisagree() throws IOException {
        Exif exif = new Exif(exifJpeg());
        File file = exifJpeg();
        exif = new Exif(file);
        exif.setAttribute(ExifInterface.TAG_MAKE, "Exif make");
        exif.save();
        JpegContainer.writeXmp(file, utf8(xmp("xmlns:tiff=\"" + Xmp.NS_TIFF + "\"",
                "tiff:Make=\"XMP make\" tiff:Model=\"XMP model\"")));

        MediaTags tags = MediaTags.open(file);
        assertEquals("Exif make", tags.getMake());
        // XMP fills in what Exif leaves out.
        assertEquals("XMP model", tags.getModel());

        // A native write brings the XMP copy into line.
        assertTrue(tags.setMake("New make"));
        tags.save();
        MediaTags read = MediaTags.open(file);
        assertEquals("New make", read.getMake());
        assertEquals("New make", read.getXmp(Xmp.NS_TIFF, "Make"));
    }

    @Test
    public void xmpFallbacksAreReadForEveryField() throws IOException {
        String packet = xmpElements("xmlns:dc=\"" + Xmp.NS_DC + "\" xmlns:exif=\""
                + Xmp.NS_EXIF + "\" xmlns:exifEX=\"" + Xmp.NS_EXIF_EX + "\" xmlns:tiff=\""
                + Xmp.NS_TIFF + "\" xmlns:xmp=\"" + Xmp.NS_XMP + "\"",
                "<dc:title><rdf:Alt><rdf:li xml:lang=\"fr\">Titre</rdf:li>"
                + "<rdf:li xml:lang=\"x-default\">Title</rdf:li></rdf:Alt></dc:title>"
                + "<dc:creator><rdf:Seq><rdf:li>Ada</rdf:li><rdf:li>Bob</rdf:li></rdf:Seq>"
                + "</dc:creator>"
                + "<exif:GPSLatitude>41,24.3N</exif:GPSLatitude>"
                + "<exif:GPSLongitude>2,10,30W</exif:GPSLongitude>"
                + "<exif:GPSAltitude>100/1</exif:GPSAltitude>"
                + "<exif:GPSAltitudeRef>1</exif:GPSAltitudeRef>"
                + "<exif:GPSImgDirection>90/1</exif:GPSImgDirection>"
                + "<exif:DateTimeOriginal>2016-04-17T12:45:06+01:00</exif:DateTimeOriginal>"
                + "<exifEX:Temperature>-5/1</exifEX:Temperature>"
                + "<tiff:Orientation>6</tiff:Orientation>"
                + "<xmp:CreatorTool>Tool</xmp:CreatorTool>");
        MediaTags tags = MediaTags.read(jpeg(xmpSegment(packet)));
        assertEquals("Title", tags.getTitle());
        assertEquals("Ada", tags.getAuthor());
        assertEquals(41.405, tags.getLocation().getLatitude(), 1e-6);
        assertEquals(-(2 + 10 / 60.0 + 30 / 3600.0), tags.getLocation().getLongitude(), 1e-6);
        assertEquals(-100, tags.getLocation().getAltitude(), 1e-6);
        assertEquals(90f, tags.getHeading(), 0f);
        assertEquals(1460893506000L, tags.getCaptureTime());
        assertEquals(-5, tags.getAmbientConditions().getTemperature(), 1e-9);
        assertEquals(90, tags.getRotation());
        assertEquals("Tool", tags.getSoftware());
        assertTrue(tags.isReadOnly());
        assertFalse(tags.setTitle("x"));
        assertFalse(tags.setPanorama(null));
        assertFalse(tags.setExifTag(1, Exif.TYPE_ASCII, "x"));
    }

    @Test
    public void readsEarlyStreetViewDates() {
        assertEquals(1460893506000L, MediaTags.parseIsoTime("2016:04:17 12:45:06+01:00"));
        assertEquals(MediaTags.parseIsoTime("2016-04-17T12:45:06.5Z"),
                MediaTags.parseIsoTime("20160417T124506.5Z"));
        assertEquals(-1, MediaTags.parseIsoTime("17 April"));
        assertEquals(-1, MediaTags.parseIsoTime("2016-13-40"));
        assertEquals(-1, MediaTags.parseIsoTime("2016-01-01T00:00:00+99:00"));
        assertTrue(MediaTags.parseIsoTime("2016") > 0);
    }

    @Test
    public void formatsAndParsesXmpCoordinates() {
        assertEquals("41,24.300000N", MediaTags.formatXmpCoordinate(41.405, 'N', 'S'));
        assertEquals("2,10.500000W", MediaTags.formatXmpCoordinate(-2.175, 'E', 'W'));
        assertTrue(Double.isNaN(MediaTags.parseXmpCoordinate("41.4")));
        assertTrue(Double.isNaN(MediaTags.parseXmpCoordinate(null)));
        assertEquals(-41.405, MediaTags.parseXmpCoordinate("41,24.3S"), 1e-9);
    }

    @Test
    public void panoramaRoundTripsEveryField() throws IOException {
        File file = exifJpeg();
        MediaTags.Panorama panorama = MediaTagsFixtures.everyGPanoField();
        MediaTags tags = MediaTags.open(file);
        assertTrue(tags.setPanorama(panorama));
        tags.save();
        MediaTags.Panorama read = MediaTags.open(file).getPanorama();
        assertEquals(panorama, read);
        assertEquals(panorama.hashCode(), read.hashCode());
        assertTrue(read.toString().contains("CroppedAreaTopPixels=472"));
        String packet = MediaTags.open(file).getXmp(Xmp.NS_GPANO, "UsePanoramaViewer");
        // XMP spells its booleans with a capital.
        assertEquals("True", packet);

        tags = MediaTags.open(file);
        assertTrue(tags.setPanorama(null));
        tags.save();
        assertNull(MediaTags.open(file).getPanorama());
    }

    @Test
    public void readsGPanoAsElementsAndAsASubset() throws IOException {
        String elements = xmpElements("xmlns:GPano=\"" + Xmp.NS_GPANO + "\"",
                "<GPano:ProjectionType>equirectangular</GPano:ProjectionType>"
                + "<GPano:CroppedAreaImageWidthPixels>5376</GPano:CroppedAreaImageWidthPixels>"
                + "<GPano:PoseHeadingDegrees>46.5</GPano:PoseHeadingDegrees>"
                + "<GPano:FirstPhotoDate>2016:04:17 12:45:06+01:00</GPano:FirstPhotoDate>");
        MediaTags.Panorama panorama = MediaTags.read(jpeg(xmpSegment(elements))).getPanorama();
        assertEquals("equirectangular", panorama.getProjectionType());
        assertEquals(Integer.valueOf(5376), panorama.getCroppedAreaImageWidthPixels());
        assertEquals(46.5f, panorama.getPoseHeadingDegrees(), 0f);
        assertEquals(1460893506000L, panorama.getFirstPhotoDate());
        assertNull(panorama.getFullPanoWidthPixels());
        assertTrue(Float.isNaN(panorama.getPosePitchDegrees()));
        assertNull(panorama.getUsePanoramaViewer());
        // A heading this sphere does not have stays absent.
        assertTrue(Float.isNaN(panorama.getInitialHorizontalFovDegrees()));
    }

    @Test
    public void readsExtendedXmp() throws IOException {
        String guid = "0123456789ABCDEF0123456789ABCDEF";
        String main = xmp("xmlns:xmpNote=\"" + Xmp.NS_XMP_NOTE + "\"",
                "xmpNote:HasExtendedXMP=\"" + guid + "\"");
        byte[] extended = utf8(xmp("xmlns:GPano=\"" + Xmp.NS_GPANO + "\"",
                "GPano:ProjectionType=\"equirectangular\""));
        int half = extended.length / 2;
        byte[] first = extendedChunk(guid, extended, 0, half);
        byte[] second = extendedChunk(guid, extended, half, extended.length);
        MediaTags tags = MediaTags.read(jpeg(xmpSegment(main), second, first));
        assertEquals("equirectangular", tags.getPanorama().getProjectionType());
        assertEquals("equirectangular", tags.getXmp(Xmp.NS_GPANO, "ProjectionType"));
    }

    private static byte[] extendedChunk(String guid, byte[] packet, int from, int to) {
        byte[] header = new byte[40];
        System.arraycopy(ascii(guid), 0, header, 0, 32);
        Bytes.put32(header, 32, packet.length);
        Bytes.put32(header, 36, from);
        return segment(JpegContainer.APP1, JpegContainer.EXTENDED_XMP_IDENTIFIER,
                concat(header, Arrays.copyOfRange(packet, from, to)));
    }

    @Test
    public void findsTheMotionPhotoClip() throws IOException {
        byte[] video = MediaTagsFixtures.mp4Clip(500);
        String packet = MediaTagsFixtures.motionPhotoXmp(video.length, 0, true);
        byte[] picture = jpeg(xmpSegment(packet));
        MediaTags tags = MediaTags.read(concat(picture, video));
        MediaTags.MotionPhoto motion = tags.getMotionPhoto();
        assertEquals(MediaTags.MotionPhoto.FORMAT_MOTION_PHOTO, motion.getFormat());
        assertEquals(picture.length, motion.getVideoOffset());
        assertEquals(video.length, motion.getVideoLength());
        assertEquals(1, motion.getVersion());
        assertEquals(1234, motion.getPresentationTimestampUs());
        assertTrue(motion.toString().contains("offset=" + picture.length));
    }

    @Test
    public void findsAMicroVideoTheContainerLeavesOut() throws IOException {
        byte[] video = MediaTagsFixtures.mp4Clip(300);
        String packet = xmp("xmlns:GCamera=\"" + Xmp.NS_CAMERA + "\"",
                "GCamera:MicroVideo=\"1\" GCamera:MicroVideoVersion=\"1\" "
                + "GCamera:MicroVideoOffset=\"" + video.length + "\" "
                + "GCamera:MicroVideoPresentationTimestampUs=\"99\"");
        byte[] picture = jpeg(xmpSegment(packet));
        MediaTags.MotionPhoto motion = MediaTags.read(concat(picture, video)).getMotionPhoto();
        assertEquals(MediaTags.MotionPhoto.FORMAT_MICRO_VIDEO, motion.getFormat());
        assertEquals(picture.length, motion.getVideoOffset());
        assertEquals(99, motion.getPresentationTimestampUs());
    }

    @Test
    public void anOffsetPastTheEndIsNoMotionPhoto() throws IOException {
        String packet = xmp("xmlns:GCamera=\"" + Xmp.NS_CAMERA + "\"",
                "GCamera:MicroVideo=\"1\" GCamera:MicroVideoOffset=\"999999\"");
        assertNull(MediaTags.read(jpeg(xmpSegment(packet))).getMotionPhoto());
        // Nor is a directory whose clip is not an MP4.
        String directory = MediaTagsFixtures.motionPhotoXmp(100, 0, false);
        assertNull(MediaTags.read(concat(jpeg(xmpSegment(directory)), new byte[100]))
                .getMotionPhoto());
    }

    @Test
    public void findsASamsungMotionPhoto() throws IOException {
        byte[] video = MediaTagsFixtures.mp4Clip(200);
        byte[] picture = jpeg();
        byte[] trailer = MediaTagsFixtures.samsungTrailer(video);
        MediaTags.MotionPhoto motion = MediaTags.read(concat(picture, trailer)).getMotionPhoto();
        assertEquals(MediaTags.MotionPhoto.FORMAT_SAMSUNG, motion.getFormat());
        assertEquals(picture.length + 8 + "MotionPhoto_Data".length(), motion.getVideoOffset());
        assertEquals(video.length, motion.getVideoLength());
        // A trailer that does not add up is ignored.
        byte[] broken = trailer.clone();
        Bytes.put32le(broken, broken.length - 8, 99999);
        assertNull(MediaTags.read(concat(picture, broken)).getMotionPhoto());
    }

    @Test
    public void findsAGainMapOnlyWhenItIsThere() throws IOException {
        String hdrgm = xmp("xmlns:hdrgm=\"" + Xmp.NS_HDR_GAIN_MAP + "\"",
                "hdrgm:Version=\"1.0\"");
        assertEquals(MediaTags.GAIN_MAP_ULTRA_HDR,
                MediaTags.read(MediaTagsFixtures.gainMapJpeg(hdrgm, null, false)).getGainMap());
        // The same metadata with the gain map taken off, MPF still pointing past the end.
        assertEquals(MediaTags.GAIN_MAP_NONE,
                MediaTags.read(MediaTagsFixtures.gainMapJpeg(hdrgm, null, true)).getGainMap());
        // ISO 21496-1's own segment.
        assertEquals(MediaTags.GAIN_MAP_ISO_21496_1,
                MediaTags.read(MediaTagsFixtures.gainMapJpeg(null, null, false, true))
                        .getGainMap());
        // Apple's says so in the gain map picture's own XMP, even beside Adobe's metadata.
        String apple = xmp("xmlns:apdi=\"" + Xmp.NS_APPLE_PIXEL_DATA + "\" xmlns:hdrgm=\""
                + Xmp.NS_HDR_GAIN_MAP + "\"", "apdi:AuxiliaryImageType=\"urn:com:apple:photo:"
                + "2020:aux:hdrgainmap\" hdrgm:Version=\"1\"");
        assertEquals(MediaTags.GAIN_MAP_APPLE,
                MediaTags.read(MediaTagsFixtures.gainMapJpeg(null, apple, false)).getGainMap());
        // Samsung's container item, spelled "Gainmap".
        String container = xmpElements("xmlns:Container=\"" + Xmp.NS_CONTAINER + "\" "
                + "xmlns:Item=\"" + Xmp.NS_ITEM + "\"", "<Container:Directory><rdf:Seq>"
                + "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Semantic=\"Primary\" "
                + "Item:Mime=\"image/jpeg\"/></rdf:li><rdf:li rdf:parseType=\"Resource\">"
                + "<Container:Item Item:Semantic=\"Gainmap\" Item:Mime=\"image/jpeg\" "
                + "Item:Length=\"10\"/></rdf:li></rdf:Seq></Container:Directory>");
        assertEquals(MediaTags.GAIN_MAP_ULTRA_HDR,
                MediaTags.read(MediaTagsFixtures.gainMapJpeg(container, null, false))
                        .getGainMap());
        assertEquals(MediaTags.GAIN_MAP_NONE, MediaTags.read(jpeg()).getGainMap());
    }

    @Test
    public void readsMpfOffsetsInEitherByteOrder() {
        assertEquals(Arrays.asList(0L, 1234L), JpegContainer.mpfOffsets(
                MediaTagsFixtures.mpf(1234, true), 0));
        assertEquals(Arrays.asList(0L, 1234L), JpegContainer.mpfOffsets(
                MediaTagsFixtures.mpf(1234, false), 0));
        assertTrue(JpegContainer.mpfOffsets(new byte[4], 0).isEmpty());
    }

    @Test
    public void aBrokenJpegIsRefusedOrReadAsFarAsItGoes() throws IOException {
        try {
            MediaTags.read(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
        } catch (IOException e) {
            throw new AssertionError("A JPEG cut short is read as far as it goes", e);
        }
        // A segment that runs past the end, and fill bytes before a marker.
        MediaTags tags = MediaTags.read(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF,
                (byte) 0xFF, (byte) 0xE1, 0, 40, 1, 2});
        assertNull(tags.getTitle());
        assertNull(MediaTags.read(new byte[] {(byte) 0xFF, (byte) 0xD8, 0x12, 0x34,
                0, 0}).getPanorama());
    }

    @Test
    public void aFileUriOpensToWriteAndBytesToRead() throws IOException {
        File file = exifJpeg();
        MediaTags writable = MediaTags.open(file);
        writable.setTitle("Shared");
        writable.save();
        MediaTags tags = MediaTags.open(ApplicationProvider.getApplicationContext(),
                Uri.fromFile(file));
        assertFalse(tags.isReadOnly());
        assertEquals("Shared", tags.getTitle());
        try {
            MediaTags.read(read(file)).save();
            throw new AssertionError("Bytes in memory cannot be saved");
        } catch (IOException expected) {
            // Opened to read only.
        }
    }

    @Test
    public void copiesEverythingToAnotherPicture() throws IOException {
        File source = exifJpeg();
        // A motion photo, whose description must not follow the picture to a file without
        // the clip.
        JpegContainer.writeXmp(source, utf8(MediaTagsFixtures.motionPhotoXmp(10, 0, true)));
        MediaTags tags = MediaTags.open(source);
        tags.setTitle("Copied");
        tags.setCaptureTime(1_700_000_000_000L);
        tags.setOrientation(90, false);
        tags.setPanorama(MediaTagsFixtures.everyGPanoField());
        tags.setXmp("http://example.com/ns/", "Rating", "4");
        tags.setExifTag(ExifFixtures.TAG_UNDEFINED_BY_ANY_STANDARD, Exif.TYPE_SHORT, "9");
        tags.save();
        assertEquals("1", MediaTags.open(source).getXmp(Xmp.NS_CAMERA, "MotionPhoto"));

        File destination = exifJpeg();
        MediaTags copy = MediaTags.open(destination);
        assertTrue(MediaTags.open(source).copyTo(copy));
        copy.save();
        MediaTags read = MediaTags.open(destination);
        assertEquals("Copied", read.getTitle());
        assertEquals(1_700_000_000_000L, read.getCaptureTime());
        assertEquals(90, read.getRotation());
        assertEquals(MediaTagsFixtures.everyGPanoField(), read.getPanorama());
        assertEquals("4", read.getXmp("http://example.com/ns/", "Rating"));
        assertEquals("9", read.getExifTag(ExifFixtures.TAG_UNDEFINED_BY_ANY_STANDARD));
        assertNull(read.getXmp(Xmp.NS_CAMERA, "MotionPhoto"));
        assertNull(read.getXmp(Xmp.NS_CONTAINER, "Directory"));
        // Nothing can be copied into a file opened to read.
        assertFalse(MediaTags.open(source).copyTo(MediaTags.read(read(destination))));
    }

    @Test
    public void unknownFilesOpenWithNoFields() throws IOException {
        MediaTags tags = MediaTags.open(file(ascii("Hello, world, this is not media"), ".txt"));
        assertEquals(MediaTags.FORMAT_UNKNOWN, tags.getFormat());
        assertEquals(MediaTags.KIND_UNKNOWN, tags.getKind());
        assertTrue(tags.isReadOnly());
        assertFalse(tags.setTitle("x"));
        assertNull(tags.getTitle());
        tags.save();
    }

    @Test
    public void toStringSaysWhatTheFileIs() throws IOException {
        assertTrue(MediaTags.read(jpeg()).toString().startsWith("MediaTags{format=1"));
    }

    private static int indexOf(byte[] bytes, byte[] target) {
        return Bytes.indexOf(bytes, target, 0);
    }

    static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }
}
