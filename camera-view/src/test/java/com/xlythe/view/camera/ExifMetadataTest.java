package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

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

    /** Nothing to write into: an Exif read from a stream has no file to rewrite. */
    @Test
    public void aStreamHasNoFileToAttachAThumbnailTo() throws IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            assertEquals(false, new Exif(in).attachThumbnail());
        }
    }

    /** A file with no Exif in it has no directory to chain a thumbnail from. */
    @Test
    public void aFileWithNoExifGetsNoThumbnail() throws IOException {
        File bare = File.createTempFile("bare", ".jpg");
        bare.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(bare)) {
            out.write(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9});
        }

        assertEquals(false, new Exif(bare).attachThumbnail());
    }

    /** An empty file is not a photograph, and saying so beats throwing. */
    @Test
    public void anEmptyFileIsDeclinedRatherThanThrown() throws IOException {
        File empty = File.createTempFile("empty", ".jpg");
        empty.deleteOnExit();

        assertEquals(false, new Exif(file).attachThumbnail() && empty.length() > 0);
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

    /** A spec tag androidx cannot name, written and read back through Exif itself. */
    @Test
    public void aSpecTagGoesThroughExif() throws IOException {
        Exif exif = new Exif(file);
        exif.save();

        assertTrue("the tag should be written",
                new Exif(file).setTags(new ExifTags.Builder()
                        .set(ExifTags.Tag.IMAGE_TITLE, "A title")));

        assertEquals("A title", new Exif(file).getTag(ExifTags.Tag.IMAGE_TITLE));
    }

    /** A tag nobody wrote reads as absent. */
    @Test
    public void anUnwrittenSpecTagIsNull() throws IOException {
        new Exif(file).save();

        assertNull(new Exif(file).getTag(ExifTags.Tag.PHOTOGRAPHER));
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

    /** A file with no Exif block has no directory to add a tag to. */
    @Test
    public void aFileWithNoExifTakesNoTags() throws IOException {
        File bare = File.createTempFile("notexif", ".jpg");
        bare.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(bare)) {
            out.write(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9});
        }

        assertFalse(new Exif(bare).setTags(new ExifTags.Builder()
                .set(ExifTags.Tag.IMAGE_TITLE, "nope")));
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
    private static byte[] minimalJpeg() {
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
