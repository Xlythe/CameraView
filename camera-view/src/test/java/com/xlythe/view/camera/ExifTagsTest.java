package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;

/**
 * The tags the standard defines and {@code ExifInterface} cannot name.
 *
 * <p>These go in by hand, which means the directory has to come back out intact: in ascending
 * tag order, with the right count, with every entry that was already there still resolving to
 * the value it resolved to before, and with the pointer in IFD0 leading to the new copy rather
 * than the old one. Those are the things asserted here, because they are the things that make
 * a file readable rather than merely written.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class ExifTagsTest {
    private static final int ENTRY_BYTES = 12;
    private static final int TAG_EXIF_IFD_POINTER = 0x8769;
    /** An existing SubIFD tag the fixture carries, so replacement can be told from addition. */
    private static final int TAG_EXIF_VERSION = 0x9000;

    /**
     * A big-endian file, which is what androidx actually writes.
     *
     * <p>Every fixture here was little-endian until this one, and that hid a real bug: the
     * entry count was read with the byte order hardcoded, so a nine-entry directory in a file
     * saved by ExifInterface came back as 2304 entries and every write was refused. Exif
     * permits both orders and a reader does not get to prefer one.</p>
     */
    @Test
    public void aBigEndianFileWorksTheSameWay() {
        byte[] jpeg = exifJpeg(false);

        byte[] out = ExifTags.write(jpeg, new ExifTags.Builder()
                .set(ExifTags.Tag.IMAGE_TITLE, "Motorola order")
                .set(ExifTags.Tag.HUMIDITY, "44.5"));

        assertNotNull("a big-endian file should accept tags too", out);
        assertEquals("Motorola order", ExifTags.read(out, ExifTags.Tag.IMAGE_TITLE));
        assertEquals("44500/1000", ExifTags.read(out, ExifTags.Tag.HUMIDITY));
    }

    /** The case it exists for: a tag androidx has no constant for, written and read back. */
    @Test
    public void aTagAndroidxCannotNameIsWrittenAndReadBack() {
        byte[] jpeg = exifJpeg();

        byte[] out = ExifTags.write(jpeg, new ExifTags.Builder()
                .set(ExifTags.Tag.PHOTOGRAPHER, "A. Photographer"));

        assertNotNull("a well-formed Exif JPEG should accept a tag", out);
        assertEquals("A. Photographer", ExifTags.read(out, ExifTags.Tag.PHOTOGRAPHER));
    }

    /** Several at once, of different types, all of them legible afterwards. */
    @Test
    public void tagsOfEveryTypeSurvive() {
        byte[] out = ExifTags.write(exifJpeg(), new ExifTags.Builder()
                .set(ExifTags.Tag.IMAGE_TITLE, "Harbour at dawn")
                .set(ExifTags.Tag.NOISE_REDUCTION, "1")
                .set(ExifTags.Tag.HUMIDITY, "61.5")
                .set(ExifTags.Tag.CAMERA_ELEVATION_ANGLE, "-12.25"));

        assertNotNull(out);
        assertEquals("Harbour at dawn", ExifTags.read(out, ExifTags.Tag.IMAGE_TITLE));
        assertEquals("a short", "1", ExifTags.read(out, ExifTags.Tag.NOISE_REDUCTION));
        assertEquals("a rational", "61500/1000", ExifTags.read(out, ExifTags.Tag.HUMIDITY));
        assertEquals("a signed rational going the other way",
                "-12250/1000", ExifTags.read(out, ExifTags.Tag.CAMERA_ELEVATION_ANGLE));
    }

    /** A rational written as a fraction is stored as that fraction, not re-derived. */
    @Test
    public void aFractionIsStoredAsWritten() {
        byte[] out = ExifTags.write(exifJpeg(), new ExifTags.Builder()
                .set(ExifTags.Tag.PRESSURE, "10132/10"));

        assertNotNull(out);
        assertEquals("10132/10", ExifTags.read(out, ExifTags.Tag.PRESSURE));
    }

    /**
     * The directory comes back in ascending tag order, which TIFF requires.
     *
     * <p>Not cosmetic: a reader is entitled to binary search a directory, and one written out
     * of order answers "absent" for tags that are present.</p>
     */
    @Test
    public void entriesStayInAscendingTagOrder() {
        byte[] out = ExifTags.write(exifJpeg(), new ExifTags.Builder()
                .set(ExifTags.Tag.COMPOSITE_IMAGE, "2")
                .set(ExifTags.Tag.TEMPERATURE, "21.5")
                .set(ExifTags.Tag.IMAGE_TITLE, "Out of order on purpose"));

        assertNotNull(out);
        int[] tags = subIfdTags(out);
        for (int i = 1; i < tags.length; i++) {
            assertTrue("tag 0x" + Integer.toHexString(tags[i]) + " follows 0x"
                            + Integer.toHexString(tags[i - 1]),
                    tags[i] > tags[i - 1]);
        }
    }

    /** What was already in the directory is still there, and still says what it said. */
    @Test
    public void theEntriesAlreadyThereAreKept() {
        byte[] jpeg = exifJpeg();

        byte[] out = ExifTags.write(jpeg, new ExifTags.Builder()
                .set(ExifTags.Tag.IMAGE_TITLE, "Added later"));

        assertNotNull(out);
        int[] tags = subIfdTags(out);
        boolean found = false;
        for (int tag : tags) {
            if (tag == TAG_EXIF_VERSION) found = true;
        }
        assertTrue("the version the fixture carried should have survived", found);
        assertEquals("and the new one should be there too", 2, tags.length);
    }

    /** Setting a tag that is already present replaces it rather than writing it twice. */
    @Test
    public void settingATagTwiceLeavesOneOfIt() {
        byte[] once = ExifTags.write(exifJpeg(), new ExifTags.Builder()
                .set(ExifTags.Tag.IMAGE_TITLE, "First"));
        assertNotNull(once);

        byte[] twice = ExifTags.write(once, new ExifTags.Builder()
                .set(ExifTags.Tag.IMAGE_TITLE, "Second"));

        assertNotNull(twice);
        assertEquals("Second", ExifTags.read(twice, ExifTags.Tag.IMAGE_TITLE));
        int titles = 0;
        for (int tag : subIfdTags(twice)) {
            if (tag == ExifTags.Tag.IMAGE_TITLE.getId()) titles++;
        }
        assertEquals("one entry per tag, always", 1, titles);
    }

    /** A null value removes the tag, which is how a caller takes one back out. */
    @Test
    public void aNullValueRemovesTheTag() {
        byte[] withIt = ExifTags.write(exifJpeg(), new ExifTags.Builder()
                .set(ExifTags.Tag.PHOTOGRAPHER, "A. Photographer"));
        assertNotNull(withIt);

        byte[] without = ExifTags.write(withIt, new ExifTags.Builder()
                .set(ExifTags.Tag.PHOTOGRAPHER, null));

        assertNotNull(without);
        assertNull(ExifTags.read(without, ExifTags.Tag.PHOTOGRAPHER));
    }

    /** A word handed to a numeric tag writes nothing, rather than a field full of rubbish. */
    @Test
    public void anUnparseableNumberIsRefused() {
        assertNull(ExifTags.write(exifJpeg(), new ExifTags.Builder()
                .set(ExifTags.Tag.NOISE_REDUCTION, "quite a lot")));
    }

    /** Anything that is not an Exif JPEG is declined rather than mangled. */
    @Test
    public void somethingThatIsNotExifIsLeftAlone() {
        assertNull(ExifTags.write(new byte[] {0, 1, 2, 3},
                new ExifTags.Builder().set(ExifTags.Tag.IMAGE_TITLE, "nope")));
        assertNull(ExifTags.read(new byte[] {0, 1, 2, 3}, ExifTags.Tag.IMAGE_TITLE));
    }

    /** Asking for nothing writes nothing. */
    @Test
    public void noTagsWritesNothing() {
        assertNull(ExifTags.write(exifJpeg(), new ExifTags.Builder()));
    }

    /** The tags in a file that never had them read as absent rather than as anything else. */
    @Test
    public void anAbsentTagReadsAsNull() {
        assertNull(ExifTags.read(exifJpeg(), ExifTags.Tag.WATER_DEPTH));
    }

    /** Every tag in the enum carries the id the standard assigns it. */
    @Test
    public void theTagIdsAreTheOnesTheStandardAssigns() {
        assertEquals(0x9287, ExifTags.Tag.LEARNING_OPT_OUT_IN.getId());
        assertEquals(0x9400, ExifTags.Tag.TEMPERATURE.getId());
        assertEquals(0x9405, ExifTags.Tag.CAMERA_ELEVATION_ANGLE.getId());
        assertEquals(0xA40F, ExifTags.Tag.DISTORTION_CORRECTION.getId());
        assertEquals(0xA436, ExifTags.Tag.IMAGE_TITLE.getId());
        assertEquals(0xA43C, ExifTags.Tag.METADATA_EDITING_SOFTWARE.getId());
        assertEquals(0xA462, ExifTags.Tag.SOURCE_EXPOSURE_TIMES_OF_COMPOSITE_IMAGE.getId());
    }

    /** The tags of the Exif private directory, in the order they are stored. */
    private static int[] subIfdTags(byte[] jpeg) {
        int tiff = 12;
        int ifd0 = tiff + readInt(jpeg, tiff + 4);
        int count = readShort(jpeg, ifd0);
        int subIfd = -1;
        for (int i = 0; i < count; i++) {
            int entry = ifd0 + 2 + i * ENTRY_BYTES;
            if (readShort(jpeg, entry) == TAG_EXIF_IFD_POINTER) {
                subIfd = tiff + readInt(jpeg, entry + 8);
            }
        }
        if (subIfd < 0) return new int[0];
        int entries = readShort(jpeg, subIfd);
        int[] tags = new int[entries];
        for (int i = 0; i < entries; i++) {
            tags[i] = readShort(jpeg, subIfd + 2 + i * ENTRY_BYTES);
        }
        return tags;
    }

    /** The order the fixture last wrote, so the reader below matches it. */
    private static boolean sLittleEndian = true;

    private static int readShort(byte[] data, int at) {
        return sLittleEndian
                ? ((data[at + 1] & 0xFF) << 8) | (data[at] & 0xFF)
                : ((data[at] & 0xFF) << 8) | (data[at + 1] & 0xFF);
    }

    private static int readInt(byte[] data, int at) {
        return sLittleEndian
                ? ((data[at + 3] & 0xFF) << 24) | ((data[at + 2] & 0xFF) << 16)
                        | ((data[at + 1] & 0xFF) << 8) | (data[at] & 0xFF)
                : ((data[at] & 0xFF) << 24) | ((data[at + 1] & 0xFF) << 16)
                        | ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
    }

    /**
     * A little-endian Exif JPEG with an IFD0 pointing at a one-entry private directory.
     *
     * <p>Built rather than loaded: a committed binary fixture is a file nobody can read in a
     * review, and everything asserted here is structural.</p>
     */
    private static byte[] exifJpeg() {
        return exifJpeg(true);
    }

    private static byte[] exifJpeg(boolean little) {
        sLittleEndian = little;
        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        writeShort(tiff, little ? 0x4949 : 0x4D4D);
        writeShort(tiff, 42);
        writeInt(tiff, 8);

        // IFD0: orientation, then the pointer to the private directory.
        int ifd0 = 8;
        int ifd0Size = 2 + 2 * ENTRY_BYTES + 4;
        int subIfd = ifd0 + ifd0Size;
        writeShort(tiff, 2);
        writeEntry(tiff, 0x0112, 3, 1, 1);
        writeEntry(tiff, TAG_EXIF_IFD_POINTER, 4, 1, subIfd);
        writeInt(tiff, 0);

        // The private directory, carrying one tag so replacement differs from addition.
        writeShort(tiff, 1);
        writeEntry(tiff, TAG_EXIF_VERSION, 7, 4, 0x32333230);
        writeInt(tiff, 0);

        byte[] tiffBytes = tiff.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8);
        out.write(0xFF);
        out.write(0xE1);
        int payload = 6 + tiffBytes.length;
        out.write(((payload + 2) >> 8) & 0xFF);
        out.write((payload + 2) & 0xFF);
        for (byte b : new byte[] {0x45, 0x78, 0x69, 0x66, 0x00, 0x00}) {
            out.write(b);
        }
        out.write(tiffBytes, 0, tiffBytes.length);
        out.write(0xFF);
        out.write(0xD9);
        return out.toByteArray();
    }

    private static void writeEntry(ByteArrayOutputStream out, int tag, int type, int count,
            int value) {
        writeShort(out, tag);
        writeShort(out, type);
        writeInt(out, count);
        if (type == 3) {
            writeShort(out, value);
            writeShort(out, 0);
        } else {
            writeInt(out, value);
        }
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        if (sLittleEndian) {
            out.write(value & 0xFF);
            out.write((value >> 8) & 0xFF);
        } else {
            out.write((value >> 8) & 0xFF);
            out.write(value & 0xFF);
        }
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        if (sLittleEndian) {
            out.write(value & 0xFF);
            out.write((value >> 8) & 0xFF);
            out.write((value >> 16) & 0xFF);
            out.write((value >> 24) & 0xFF);
        } else {
            out.write((value >> 24) & 0xFF);
            out.write((value >> 16) & 0xFF);
            out.write((value >> 8) & 0xFF);
            out.write(value & 0xFF);
        }
    }
}
