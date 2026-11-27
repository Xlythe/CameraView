package com.xlythe.view.camera;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.location.Location;
import android.os.Build;

import androidx.exifinterface.media.ExifInterface;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * What a saved file says about itself beyond its orientation: the tags Exif requires of a
 * compressed image, and the rest of what a GPS fix knows.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class ExifMetadataTest {
    private File file;

    @Before
    public void setUp() throws IOException {
        file = File.createTempFile("metadata", ".jpg");
        file.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(minimalJpeg());
        }
    }

    /** The tags the format requires are there after a save, whatever the camera left out. */
    @Test
    public void savingFillsInTheTagsExifRequires() throws IOException {
        new Exif(file).save();

        ExifInterface saved = new ExifInterface(file.getPath());
        assertNotNull("ExifVersion", saved.getAttribute(ExifInterface.TAG_EXIF_VERSION));
        assertNotNull("FlashpixVersion", saved.getAttribute(ExifInterface.TAG_FLASHPIX_VERSION));
        assertEquals("sRGB", 1, saved.getAttributeInt(ExifInterface.TAG_COLOR_SPACE, 0));
        assertEquals("inches", 2, saved.getAttributeInt(ExifInterface.TAG_RESOLUTION_UNIT, 0));
        assertEquals("centered", 1,
                saved.getAttributeInt(ExifInterface.TAG_Y_CB_CR_POSITIONING, 0));
        assertNotEquals("a resolution of zero describes nothing", 0.0,
                saved.getAttributeDouble(ExifInterface.TAG_X_RESOLUTION, 0), 0.0);
        assertNotEquals(0.0, saved.getAttributeDouble(ExifInterface.TAG_Y_RESOLUTION, 0), 0.0);
        assertNotNull("ComponentsConfiguration",
                saved.getAttribute(ExifInterface.TAG_COMPONENTS_CONFIGURATION));
    }

    /** A camera that reported its own knows better than this does, and is left alone. */
    @Test
    public void aCameraThatFilledThemInKeepsItsOwnValues() throws IOException {
        ExifInterface original = new ExifInterface(file.getPath());
        original.setAttribute(ExifInterface.TAG_X_RESOLUTION, "300/1");
        original.setAttribute(ExifInterface.TAG_RESOLUTION_UNIT, "3");
        original.setAttribute(ExifInterface.TAG_COLOR_SPACE, "65535");
        original.saveAttributes();

        new Exif(file).save();

        ExifInterface saved = new ExifInterface(file.getPath());
        assertEquals(300.0, saved.getAttributeDouble(ExifInterface.TAG_X_RESOLUTION, 0), 0.0);
        assertEquals("centimetres, because the camera said so", 3,
                saved.getAttributeInt(ExifInterface.TAG_RESOLUTION_UNIT, 0));
        assertEquals("uncalibrated, because the camera said so", 65535,
                saved.getAttributeInt(ExifInterface.TAG_COLOR_SPACE, 0));
    }

    /** Below sea level is a reference byte, not a minus sign on an unsigned rational. */
    @Test
    public void altitudeBelowSeaLevelIsWrittenAsBelowSeaLevel() throws IOException {
        Exif exif = new Exif(file);
        exif.attachLocation(locationAt(-31.5));
        exif.save();

        ExifInterface saved = new ExifInterface(file.getPath());
        assertEquals("the reference says below", 1,
                saved.getAttributeInt(ExifInterface.TAG_GPS_ALTITUDE_REF, -1));
        assertEquals("and the value itself is positive", 31.5,
                saved.getAttributeDouble(ExifInterface.TAG_GPS_ALTITUDE, 0), 0.01);
        assertEquals("which a reader puts back together as negative", -31.5,
                saved.getAltitude(0), 0.01);
    }

    /** Above sea level still reads as above, which is the case that already worked. */
    @Test
    public void altitudeAboveSeaLevelIsUnchanged() throws IOException {
        Exif exif = new Exif(file);
        exif.attachLocation(locationAt(120.0));
        exif.save();

        ExifInterface saved = new ExifInterface(file.getPath());
        assertEquals(0, saved.getAttributeInt(ExifInterface.TAG_GPS_ALTITUDE_REF, -1));
        assertEquals(120.0, saved.getAltitude(0), 0.01);
    }

    /** Everything else a fix knows, which was being thrown away. */
    @Test
    public void aFixWritesWhatItKnowsBesidesTheCoordinate() throws IOException {
        Exif exif = new Exif(file);
        exif.attachLocation(locationAt(10.0));
        exif.save();

        ExifInterface saved = new ExifInterface(file.getPath());
        assertNotNull("how far out the coordinate might be",
                saved.getAttribute(ExifInterface.TAG_GPS_H_POSITIONING_ERROR));
        assertNotNull("which way the device was moving",
                saved.getAttribute(ExifInterface.TAG_GPS_TRACK));
        assertEquals("relative to true north", "T",
                saved.getAttribute(ExifInterface.TAG_GPS_TRACK_REF));
        assertEquals("the datum the coordinate is in", "WGS-84",
                saved.getAttribute(ExifInterface.TAG_GPS_MAP_DATUM));
        assertNotNull("the GPS tag version",
                saved.getAttribute(ExifInterface.TAG_GPS_VERSION_ID));
    }

    /** And removing the location removes all of it, not just the coordinate. */
    @Test
    public void removingTheLocationRemovesWhatSurroundsIt() throws IOException {
        Exif exif = new Exif(file);
        exif.attachLocation(locationAt(10.0));
        exif.save();

        Exif stripped = new Exif(file);
        stripped.removeLocation();
        stripped.save();

        ExifInterface saved = new ExifInterface(file.getPath());
        assertNull(saved.getAttribute(ExifInterface.TAG_GPS_LATITUDE));
        assertNull("accuracy is still a fact about where you were",
                saved.getAttribute(ExifInterface.TAG_GPS_H_POSITIONING_ERROR));
        assertNull("so is your heading",
                saved.getAttribute(ExifInterface.TAG_GPS_TRACK));
        assertNull(saved.getAttribute(ExifInterface.TAG_GPS_MAP_DATUM));
    }

    /** The IFD0 date is written beside the two in the SubIFD, since removal clears all three. */
    @Test
    public void attachingATimestampWritesTheOneRemovalClears() throws IOException {
        Exif exif = new Exif(file);
        exif.attachTimestamp();
        exif.save();

        ExifInterface saved = new ExifInterface(file.getPath());
        assertNotNull(saved.getAttribute(ExifInterface.TAG_DATETIME));
        assertNotNull(saved.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL));
        assertNotNull(saved.getAttribute(ExifInterface.TAG_OFFSET_TIME));
    }

    /** Halving stops at the first size no larger than a thumbnail in both directions. */
    @Test
    public void theThumbnailIsSampledDownRatherThanScaled() {
        assertEquals("a 4032x3024 capture", 16, Exif.sampleSizeFor(4032, 3024));
        assertEquals("a 1920x1080 capture", 8, Exif.sampleSizeFor(1920, 1080));
        assertEquals("something already small is not sampled at all", 1,
                Exif.sampleSizeFor(200, 150));
        assertEquals("nor is something smaller than a thumbnail", 1, Exif.sampleSizeFor(80, 60));
    }

    /** A file with no image in it cannot be measured, and that must not stop the save. */
    @Test
    public void aFileThatWillNotDecodeStillSaves() throws IOException {
        Exif exif = new Exif(file);
        exif.attachTimestamp();

        exif.save();

        assertNotNull(new ExifInterface(file.getPath())
                .getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL));
    }

    /**
     * An Exif read from a stream has no file to save into, so asking for a thumbnail changes
     * nothing: the save fails as it always has, and writes no thumbnail.
     */
    @Test
    public void aStreamHasNoFileToAttachAThumbnailTo() throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            Exif exif = new Exif(in);
            exif.attachThumbnail();

            assertThrows(IOException.class, exif::save);
            assertFalse(exif.hasThumbnail());
        }
    }

    /** There is room beside a small Exif block, and the writer says how much. */
    @Test
    public void roomIsReportedBeforeAnythingIsEncoded() throws IOException {
        byte[] jpeg = new byte[(int) file.length()];
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            int read = 0;
            while (read < jpeg.length) {
                int count = in.read(jpeg, read, jpeg.length - read);
                if (count < 0) break;
                read += count;
            }
        }

        assertTrue("a file with no Exif offers no room",
                ExifThumbnail.roomForThumbnail(jpeg) <= 0);
    }

    /** Any tag ExifInterface names can be set, saved and read back, and set to null to remove. */
    @Test
    public void anyNamedTagGoesThroughExif() throws IOException {
        Exif exif = new Exif(file);
        exif.setAttribute(ExifInterface.TAG_ARTIST, "Someone");
        exif.setAttribute(ExifInterface.TAG_COPYRIGHT, "2026 Someone");
        exif.save();

        assertEquals("Someone", new Exif(file).getAttribute(ExifInterface.TAG_ARTIST));
        assertEquals("2026 Someone", new Exif(file).getAttribute(ExifInterface.TAG_COPYRIGHT));

        Exif again = new Exif(file);
        again.setAttribute(ExifInterface.TAG_ARTIST, null);
        again.save();
        assertNull(new Exif(file).getAttribute(ExifInterface.TAG_ARTIST));
    }

    /** A capture gains the device's make and model and the app, where the camera left them out. */
    @Test
    public void theCaptureDeviceFillsWhatTheCameraLeftOut() throws IOException {
        Exif exif = new Exif(file);
        exif.setAttribute(ExifInterface.TAG_MODEL, "The camera's own name");
        exif.attachCaptureDevice(RuntimeEnvironment.getApplication());
        exif.save();

        Exif saved = new Exif(file);
        assertEquals(Build.MANUFACTURER, saved.getAttribute(ExifInterface.TAG_MAKE));
        assertEquals("The camera's own name", saved.getAttribute(ExifInterface.TAG_MODEL));
        assertEquals(Exif.softwareName(RuntimeEnvironment.getApplication()),
                saved.getAttribute(ExifInterface.TAG_SOFTWARE));
        assertNotNull(saved.getAttribute(ExifInterface.TAG_SOFTWARE));
    }

    /** Each capture gets an id of its own: 128 bits, as 32 hexadecimal digits. */
    @Test
    public void aCaptureGetsAnIdOfItsOwn() throws IOException {
        Exif exif = new Exif(file);
        exif.attachCaptureDevice(RuntimeEnvironment.getApplication());
        exif.save();

        String id = new Exif(file).getAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID);
        assertNotNull(id);
        assertTrue("not 32 hexadecimal digits: " + id, id.matches("[0-9a-f]{32}"));
        assertNotEquals("two pictures never share an id", Exif.newImageUniqueId(),
                Exif.newImageUniqueId());
    }

    /** A camera that numbered its own pictures keeps its numbering. */
    @Test
    public void aCamerasOwnIdIsKept() throws IOException {
        Exif exif = new Exif(file);
        exif.setAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID, "JK0XLRD00VM");
        exif.attachCaptureDevice(RuntimeEnvironment.getApplication());
        exif.save();

        assertEquals("JK0XLRD00VM",
                new Exif(file).getAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID));
    }

    /** A capture says a camera made it, and photographed the scene directly. */
    @Test
    public void aCaptureSaysACameraTookIt() throws IOException {
        Exif exif = new Exif(file);
        exif.attachCaptureDevice(RuntimeEnvironment.getApplication());
        exif.save();

        ExifInterface saved = new ExifInterface(file.getPath());
        assertArrayEquals("a digital still camera", new byte[] {3},
                saved.getAttributeBytes(ExifInterface.TAG_FILE_SOURCE));
        assertArrayEquals("directly photographed", new byte[] {1},
                saved.getAttributeBytes(ExifInterface.TAG_SCENE_TYPE));
    }

    /** What a camera said of its own source and scene stands. */
    @Test
    public void aCamerasOwnSourceAndSceneAreKept() throws IOException {
        Exif camera = new Exif(file);
        camera.setAttribute(ExifInterface.TAG_FILE_SOURCE, "\u0002");
        camera.setAttribute(ExifInterface.TAG_SCENE_TYPE, "\u0000");
        camera.save();

        Exif exif = new Exif(file);
        exif.attachCaptureDevice(RuntimeEnvironment.getApplication());
        exif.save();

        ExifInterface saved = new ExifInterface(file.getPath());
        assertArrayEquals(new byte[] {2}, saved.getAttributeBytes(ExifInterface.TAG_FILE_SOURCE));
        assertArrayEquals(new byte[] {0}, saved.getAttributeBytes(ExifInterface.TAG_SCENE_TYPE));
    }

    /**
     * The tags the standard defines as bytes are saved as bytes: UNDEFINED, at their own length,
     * rather than as text with a terminator.
     */
    @Test
    public void bytesAreSavedAsBytes() throws IOException {
        Exif exif = new Exif(file);
        exif.attachCaptureDevice(RuntimeEnvironment.getApplication());
        exif.save();

        byte[] jpeg = readAll(file);
        ExifSegment.Segment segment = ExifSegment.find(jpeg);
        assertNotNull(segment);
        assertUndefined(jpeg, segment, 0x9101, 4);
        assertUndefined(jpeg, segment, 0xA300, 1);
        assertUndefined(jpeg, segment, 0xA301, 1);
        assertArrayEquals(new byte[] {1, 2, 3, 0}, new ExifInterface(file.getPath())
                .getAttributeBytes(ExifInterface.TAG_COMPONENTS_CONFIGURATION));
    }

    /** A second save finds them right already and leaves them be. */
    @Test
    public void bytesSurviveASecondSave() throws IOException {
        Exif exif = new Exif(file);
        exif.attachCaptureDevice(RuntimeEnvironment.getApplication());
        exif.save();
        new Exif(file).save();

        byte[] jpeg = readAll(file);
        ExifSegment.Segment segment = ExifSegment.find(jpeg);
        assertNotNull(segment);
        assertUndefined(jpeg, segment, 0xA300, 1);
        assertArrayEquals(new byte[] {3}, new ExifInterface(file.getPath())
                .getAttributeBytes(ExifInterface.TAG_FILE_SOURCE));
    }

    /** A PNG has no JPEG segment to retype tags in, and saves all the same. */
    @Test
    public void aPngSavesWithoutRetyping() throws IOException {
        File png = File.createTempFile("metadata", ".png");
        png.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(png)) {
            out.write(minimalPng());
        }
        Exif exif = new Exif(png);
        exif.attachCaptureDevice(RuntimeEnvironment.getApplication());
        exif.save();

        assertNotNull(new Exif(png).getAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID));
    }

    /** One grey pixel: the signature, a header, the pixel compressed, and the end. */
    private static byte[] minimalPng() throws IOException {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
        pngChunk(png, "IHDR", new byte[] {0, 0, 0, 1, 0, 0, 0, 1, 8, 0, 0, 0, 0});
        Deflater deflater = new Deflater();
        deflater.setInput(new byte[] {0 /* no filter */, (byte) 0x80});
        deflater.finish();
        byte[] compressed = new byte[64];
        int length = deflater.deflate(compressed);
        deflater.end();
        pngChunk(png, "IDAT", Arrays.copyOf(compressed, length));
        pngChunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    private static void pngChunk(ByteArrayOutputStream png, String type, byte[] data)
            throws IOException {
        DataOutputStream out = new DataOutputStream(png);
        out.writeInt(data.length);
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.write(typeBytes);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.writeInt((int) crc.getValue());
    }

    private static void assertUndefined(
            byte[] jpeg, ExifSegment.Segment segment, int tag, int count) {
        int entry = ExifSegment.findEntry(jpeg, segment, ExifSegment.exifIfd(jpeg, segment), tag);
        assertTrue("no entry for " + Integer.toHexString(tag), entry >= 0);
        assertEquals("the type of " + Integer.toHexString(tag), 7,
                ExifSegment.u16(jpeg, entry + 2, segment.littleEndian));
        assertEquals("the count of " + Integer.toHexString(tag), count,
                ExifSegment.u32(jpeg, entry + 4, segment.littleEndian));
    }

    private static byte[] readAll(File file) throws IOException {
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int read = 0;
            while (read < data.length) {
                int count = in.read(data, read, data.length - read);
                if (count < 0) break;
                read += count;
            }
        }
        return data;
    }

    /**
     * Spec tags set before saving, beside the attributes set with them, are written by the save,
     * into an Exif block the file did not have until then.
     */
    @Test
    public void specTagsSetBeforeSavingAreWritten() throws IOException {
        Exif exif = new Exif(file);
        assertTrue(exif.setTags(new ExifTags.Builder()
                .set(ExifTags.Tag.IMAGE_TITLE, "A title")
                .set(ExifTags.Tag.HUMIDITY, "44.5")));
        exif.setDescription("A description");
        exif.save();

        Exif saved = new Exif(file);
        assertEquals("A title", saved.getTag(ExifTags.Tag.IMAGE_TITLE));
        assertEquals("44500/1000", saved.getTag(ExifTags.Tag.HUMIDITY));
        assertEquals("A description", saved.getDescription());
    }

    /** Before the save, a tag reads as it was set, in the shape the file will store it in. */
    @Test
    public void aSpecTagReadsAsSetBeforeItIsSaved() throws IOException {
        Exif exif = new Exif(file);
        exif.setTags(new ExifTags.Builder().set(ExifTags.Tag.HUMIDITY, "44.5"));

        assertEquals("44500/1000", exif.getTag(ExifTags.Tag.HUMIDITY));
        assertNull("nothing is written until the save",
                new Exif(file).getTag(ExifTags.Tag.HUMIDITY));
    }

    /** A tag nobody wrote reads as absent. */
    @Test
    public void anUnwrittenSpecTagIsNull() throws IOException {
        new Exif(file).save();

        assertNull(new Exif(file).getTag(ExifTags.Tag.PHOTOGRAPHER));
    }

    /** The tags a file arrives with survive a save that never mentions them. */
    @Test
    public void aFilesOwnSpecTagsSurviveAPlainSave() throws IOException {
        withTags(file, new ExifTags.Builder()
                .set(ExifTags.Tag.PHOTOGRAPHER, "A. Photographer")
                .set(ExifTags.Tag.COMPOSITE_IMAGE, "2"));

        Exif exif = new Exif(file);
        exif.setAttribute(ExifInterface.TAG_ARTIST, "Someone");
        exif.save();

        Exif saved = new Exif(file);
        assertEquals("A. Photographer", saved.getTag(ExifTags.Tag.PHOTOGRAPHER));
        assertEquals("2", saved.getTag(ExifTags.Tag.COMPOSITE_IMAGE));
        assertEquals("Someone", saved.getAttribute(ExifInterface.TAG_ARTIST));
    }

    /** A tag the file has and setTags replaces takes the new value, and only once. */
    @Test
    public void aReplacedSpecTagTakesTheNewValue() throws IOException {
        withTags(file, new ExifTags.Builder()
                .set(ExifTags.Tag.IMAGE_TITLE, "Old title")
                .set(ExifTags.Tag.PHOTOGRAPHER, "Kept"));

        Exif exif = new Exif(file);
        exif.setTags(new ExifTags.Builder().set(ExifTags.Tag.IMAGE_TITLE, "New title"));
        exif.save();

        Exif saved = new Exif(file);
        assertEquals("New title", saved.getTag(ExifTags.Tag.IMAGE_TITLE));
        assertEquals("Kept", saved.getTag(ExifTags.Tag.PHOTOGRAPHER));
        assertEquals("copies of the new title", 1, occurrences(file, "New title"));
        assertEquals("copies of the old one", 0, occurrences(file, "Old title"));
    }

    /** A tag the file has and setTags removes is gone after the save. */
    @Test
    public void aRemovedSpecTagIsGone() throws IOException {
        withTags(file, new ExifTags.Builder()
                .set(ExifTags.Tag.IMAGE_TITLE, "Going")
                .set(ExifTags.Tag.PHOTOGRAPHER, "Staying"));

        Exif exif = new Exif(file);
        exif.setTags(new ExifTags.Builder().set(ExifTags.Tag.IMAGE_TITLE, null));
        exif.save();

        Exif saved = new Exif(file);
        assertNull(saved.getTag(ExifTags.Tag.IMAGE_TITLE));
        assertEquals("Staying", saved.getTag(ExifTags.Tag.PHOTOGRAPHER));
    }

    /**
     * Saving twice from one Exif, then again from another, leaves one copy of each tag, and the
     * block no bigger than the first save made it.
     */
    @Test
    public void savingTwiceKeepsOneCopyOfEachSpecTag() throws IOException {
        Exif exif = new Exif(file);
        exif.setTags(new ExifTags.Builder()
                .set(ExifTags.Tag.IMAGE_TITLE, "Only once")
                .set(ExifTags.Tag.TEMPERATURE, "21.5"));
        exif.save();
        int first = ExifSegment.find(readAll(file)).payloadLength;

        exif.save();
        assertTrue("the same Exif saving again",
                ExifSegment.find(readAll(file)).payloadLength <= first);
        new Exif(file).save();

        assertTrue("another Exif saving again",
                ExifSegment.find(readAll(file)).payloadLength <= first);
        assertEquals(1, occurrences(file, "Only once"));
        Exif saved = new Exif(file);
        assertEquals("Only once", saved.getTag(ExifTags.Tag.IMAGE_TITLE));
        assertEquals("21500/1000", saved.getTag(ExifTags.Tag.TEMPERATURE));
    }

    /** Tags written beside the ones saved as bytes leave those as bytes. */
    @Test
    public void specTagsLeaveTheBytesAsBytes() throws IOException {
        Exif exif = new Exif(file);
        exif.attachCaptureDevice(RuntimeEnvironment.getApplication());
        exif.setTags(new ExifTags.Builder().set(ExifTags.Tag.IMAGE_TITLE, "A title"));
        exif.save();

        byte[] jpeg = readAll(file);
        ExifSegment.Segment segment = ExifSegment.find(jpeg);
        assertNotNull(segment);
        assertUndefined(jpeg, segment, 0x9101, 4);
        assertUndefined(jpeg, segment, 0xA300, 1);
        assertUndefined(jpeg, segment, 0xA301, 1);
        assertEquals("A title", new Exif(file).getTag(ExifTags.Tag.IMAGE_TITLE));
    }

    /**
     * A block with no room left for the tags fails the save rather than dropping them quietly,
     * and keeps everything else the save wrote.
     */
    @Test
    public void aBlockWithNoRoomForTheSpecTagsFailsTheSave() throws IOException {
        new Exif(file).save();
        int used = ExifSegment.find(readAll(file)).payloadLength;
        // A maker note that leaves the segment a few hundred bytes short of its limit.
        char[] filler = new char[ExifSegment.MAX_APP1_PAYLOAD - used - 400];
        Arrays.fill(filler, 'x');
        String makerNote = new String(filler);
        char[] title = new char[1000];
        Arrays.fill(title, 't');

        Exif exif = new Exif(file);
        exif.setAttribute(ExifInterface.TAG_MAKER_NOTE, makerNote);
        exif.setTags(new ExifTags.Builder().set(ExifTags.Tag.IMAGE_TITLE, new String(title)));

        assertThrows(IOException.class, exif::save);
        Exif saved = new Exif(file);
        assertEquals(makerNote, saved.getAttribute(ExifInterface.TAG_MAKER_NOTE));
        assertNull(saved.getTag(ExifTags.Tag.IMAGE_TITLE));
        assertTrue(ExifSegment.find(readAll(file)).payloadLength <= ExifSegment.MAX_APP1_PAYLOAD);
    }

    /** An Exif read from a stream has no file to write tags into, and says so. */
    @Test
    public void aStreamHasNoFileToWriteTagsInto() throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            Exif exif = new Exif(in);
            assertNull(exif.getTag(ExifTags.Tag.IMAGE_TITLE));
            assertFalse(exif.setTags(new ExifTags.Builder()
                    .set(ExifTags.Tag.IMAGE_TITLE, "nowhere to put this")));
        }
    }

    /** A PNG has no JPEG Exif block to add these to, and says so when they are set. */
    @Test
    public void aPngTakesNoSpecTags() throws IOException {
        File png = File.createTempFile("metadata", ".png");
        png.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(png)) {
            out.write(minimalPng());
        }

        Exif exif = new Exif(png);
        assertFalse(exif.setTags(new ExifTags.Builder()
                .set(ExifTags.Tag.IMAGE_TITLE, "nowhere to put this")));
        assertNull(exif.getTag(ExifTags.Tag.IMAGE_TITLE));
        exif.save();
    }

    /** A value that does not suit its tag is refused when it is set, and changes nothing. */
    @Test
    public void anUnsuitableSpecTagIsRefusedWhenSet() throws IOException {
        Exif exif = new Exif(file);

        assertFalse(exif.setTags(new ExifTags.Builder()
                .set(ExifTags.Tag.IMAGE_TITLE, "A title")
                .set(ExifTags.Tag.NOISE_REDUCTION, "quite a lot")));

        assertNull(exif.getTag(ExifTags.Tag.IMAGE_TITLE));
    }

    /** Writes tags into the file directly, as a camera that knew them would have. */
    private static void withTags(File file, ExifTags.Builder tags) throws IOException {
        ExifInterface camera = new ExifInterface(file.getPath());
        camera.setAttribute(ExifInterface.TAG_EXIF_VERSION, "0232");
        camera.saveAttributes();
        byte[] jpeg = readAll(file);
        Map<ExifTags.Tag, ExifTags.Value> values = ExifTags.readAll(jpeg);
        assertTrue(ExifTags.apply(tags, values));
        byte[] updated = ExifTags.write(jpeg, values);
        assertNotNull("the fixture should take the tags", updated);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(updated);
        }
    }

    /** How many times some text appears in the file's Exif block. */
    private static int occurrences(File file, String text) throws IOException {
        byte[] jpeg = readAll(file);
        byte[] wanted = text.getBytes(StandardCharsets.UTF_8);
        ExifSegment.Segment segment = ExifSegment.find(jpeg);
        assertNotNull(segment);
        int count = 0;
        for (int at = segment.tiffOffset; at + wanted.length <= segment.end(); at++) {
            if (Arrays.equals(wanted, Arrays.copyOfRange(jpeg, at, at + wanted.length))) {
                count++;
            }
        }
        return count;
    }

    private static Location locationAt(double altitude) {
        Location location = new Location("test");
        location.setLatitude(37.422);
        location.setLongitude(-122.084);
        location.setAltitude(altitude);
        location.setAccuracy(6.5f);
        location.setBearing(271.0f);
        location.setSpeed(1.5f);
        location.setTime(1_700_000_000_000L);
        return location;
    }

    /** The smallest thing androidx will treat as a JPEG it can write Exif into. */
    static byte[] minimalJpeg() {
        return new byte[] {
                (byte) 0xFF, (byte) 0xD8,
                (byte) 0xFF, (byte) 0xDB, 0x00, 0x43, 0x00,
                0x08, 0x06, 0x06, 0x07, 0x06, 0x05, 0x08, 0x07, 0x07, 0x07, 0x09, 0x09, 0x08,
                0x0A, 0x0C, 0x14, 0x0D, 0x0C, 0x0B, 0x0B, 0x0C, 0x19, 0x12, 0x13, 0x0F, 0x14,
                0x1D, 0x1A, 0x1F, 0x1E, 0x1D, 0x1A, 0x1C, 0x1C, 0x20, 0x24, 0x2E, 0x27, 0x20,
                0x22, 0x2C, 0x23, 0x1C, 0x1C, 0x28, 0x37, 0x29, 0x2C, 0x30, 0x31, 0x34, 0x34,
                0x34, 0x1F, 0x27, 0x39, 0x3D, 0x38, 0x32, 0x3C, 0x2E, 0x33, 0x34, 0x32,
                (byte) 0xFF, (byte) 0xD9,
        };
    }
}
