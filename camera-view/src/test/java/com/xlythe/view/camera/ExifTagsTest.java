package com.xlythe.view.camera;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * The tags the standard defines and {@code ExifInterface} cannot name, and the directory they go
 * into: in ascending order, with the right count, every existing entry still resolving, and IFD0
 * pointing at the new copy.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class ExifTagsTest {
    private static final int ENTRY_BYTES = 12;
    private static final int TAG_EXIF_IFD_POINTER = 0x8769;
    /** An existing SubIFD tag the fixture carries, so replacement can be told from addition. */
    private static final int TAG_EXIF_VERSION = 0x9000;
    private static final int TYPE_SHORT = 3;
    private static final int TYPE_LONG = 4;
    private static final int TYPE_UNDEFINED = 7;

    /** A big-endian file, as ExifInterface saves one, whose counts must be read big-endian. */
    @Test
    public void aBigEndianFileWorksTheSameWay() {
        byte[] jpeg = exifJpeg(false);

        byte[] out = write(jpeg, new ExifTags.Builder()
                .set(ExifTags.Tag.IMAGE_TITLE, "Motorola order")
                .set(ExifTags.Tag.HUMIDITY, "44.5"));

        assertNotNull("a big-endian file should accept tags too", out);
        assertEquals("Motorola order", read(out, ExifTags.Tag.IMAGE_TITLE));
        assertEquals("44500/1000", read(out, ExifTags.Tag.HUMIDITY));
    }

    /** The case it exists for: a tag androidx has no constant for, written and read back. */
    @Test
    public void aTagAndroidxCannotNameIsWrittenAndReadBack() {
        byte[] jpeg = exifJpeg();

        byte[] out = write(jpeg, new ExifTags.Builder()
                .set(ExifTags.Tag.PHOTOGRAPHER, "A. Photographer"));

        assertNotNull("a well-formed Exif JPEG should accept a tag", out);
        assertEquals("A. Photographer", read(out, ExifTags.Tag.PHOTOGRAPHER));
    }

    /** Several at once, of different types, all of them legible afterwards. */
    @Test
    public void tagsOfEveryTypeSurvive() {
        byte[] out = write(exifJpeg(), new ExifTags.Builder()
                .set(ExifTags.Tag.IMAGE_TITLE, "Harbour at dawn")
                .set(ExifTags.Tag.NOISE_REDUCTION, "1")
                .set(ExifTags.Tag.HUMIDITY, "61.5")
                .set(ExifTags.Tag.CAMERA_ELEVATION_ANGLE, "-12.25")
                .set(ExifTags.Tag.LEARNING_OPT_OUT_IN, "1"));

        assertNotNull(out);
        assertEquals("Harbour at dawn", read(out, ExifTags.Tag.IMAGE_TITLE));
        assertEquals("a short", "1", read(out, ExifTags.Tag.NOISE_REDUCTION));
        assertEquals("a rational", "61500/1000", read(out, ExifTags.Tag.HUMIDITY));
        assertEquals("a signed rational going the other way",
                "-12250/1000", read(out, ExifTags.Tag.CAMERA_ELEVATION_ANGLE));
        assertEquals("bytes", "1", read(out, ExifTags.Tag.LEARNING_OPT_OUT_IN));
    }

    /** A rational written as a fraction is stored as that fraction, not re-derived. */
    @Test
    public void aFractionIsStoredAsWritten() {
        byte[] out = write(exifJpeg(), new ExifTags.Builder()
                .set(ExifTags.Tag.PRESSURE, "10132/10"));

        assertNotNull(out);
        assertEquals("10132/10", read(out, ExifTags.Tag.PRESSURE));
    }

    /**
     * The directory comes back in ascending tag order, which TIFF requires, since a reader may
     * binary search it.
     */
    @Test
    public void entriesStayInAscendingTagOrder() {
        byte[] out = write(exifJpeg(), new ExifTags.Builder()
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

        byte[] out = write(jpeg, new ExifTags.Builder()
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
        byte[] once = write(exifJpeg(), new ExifTags.Builder()
                .set(ExifTags.Tag.IMAGE_TITLE, "First"));
        assertNotNull(once);

        byte[] twice = write(once, new ExifTags.Builder()
                .set(ExifTags.Tag.IMAGE_TITLE, "Second"));

        assertNotNull(twice);
        assertEquals("Second", read(twice, ExifTags.Tag.IMAGE_TITLE));
        int titles = 0;
        for (int tag : subIfdTags(twice)) {
            if (tag == ExifTags.Tag.IMAGE_TITLE.getId()) titles++;
        }
        assertEquals("one entry per tag, always", 1, titles);
    }

    /** A null value removes the tag, which is how a caller takes one back out. */
    @Test
    public void aNullValueRemovesTheTag() {
        byte[] withIt = write(exifJpeg(), new ExifTags.Builder()
                .set(ExifTags.Tag.PHOTOGRAPHER, "A. Photographer"));
        assertNotNull(withIt);

        byte[] without = write(withIt, new ExifTags.Builder()
                .set(ExifTags.Tag.PHOTOGRAPHER, null));

        assertNotNull(without);
        assertNull(read(without, ExifTags.Tag.PHOTOGRAPHER));
    }

    /**
     * Writing none of these tags clears those the file had, since what is written is the whole
     * set, and leaves every other entry where it was.
     */
    @Test
    public void writingNoneOfThemClearsThemAndNothingElse() {
        byte[] withIt = write(exifJpeg(), new ExifTags.Builder()
                .set(ExifTags.Tag.PHOTOGRAPHER, "A. Photographer"));
        assertNotNull(withIt);

        byte[] without = ExifTags.write(withIt,
                Collections.<ExifTags.Tag, ExifTags.Value>emptyMap());

        assertNotNull(without);
        assertTrue(ExifTags.readAll(without).isEmpty());
        assertArrayEquals(new int[] {TAG_EXIF_VERSION}, subIfdTags(without));
    }

    /** A word handed to a numeric tag writes nothing, rather than a field full of rubbish. */
    @Test
    public void anUnparseableNumberIsRefused() {
        assertNull(write(exifJpeg(), new ExifTags.Builder()
                .set(ExifTags.Tag.NOISE_REDUCTION, "quite a lot")));
    }

    /** Nor does a number too big for its type, or a fraction over nothing. */
    @Test
    public void aNumberTheTypeCannotHoldIsRefused() {
        Map<ExifTags.Tag, ExifTags.Value> values = new EnumMap<>(ExifTags.Tag.class);
        assertFalse("a short holds no more than 65535", ExifTags.apply(new ExifTags.Builder()
                .set(ExifTags.Tag.NOISE_REDUCTION, "65536"), values));
        assertFalse("nor anything negative", ExifTags.apply(new ExifTags.Builder()
                .set(ExifTags.Tag.NOISE_REDUCTION, "-1"), values));
        assertFalse("an unsigned rational takes no minus sign", ExifTags.apply(
                new ExifTags.Builder().set(ExifTags.Tag.HUMIDITY, "-3/1"), values));
        assertFalse("a denominator of zero is no number", ExifTags.apply(
                new ExifTags.Builder().set(ExifTags.Tag.PRESSURE, "1013/0"), values));
        assertFalse("bytes have to be some bytes", ExifTags.apply(
                new ExifTags.Builder().set(ExifTags.Tag.LEARNING_OPT_OUT_IN, ""), values));
        assertTrue(values.isEmpty());
    }

    /** One bad value refuses the whole builder, so what was there before stays. */
    @Test
    public void oneBadValueChangesNothing() {
        Map<ExifTags.Tag, ExifTags.Value> values = ExifTags.readAll(write(exifJpeg(),
                new ExifTags.Builder().set(ExifTags.Tag.IMAGE_TITLE, "Before")));

        assertFalse(ExifTags.apply(new ExifTags.Builder()
                .set(ExifTags.Tag.IMAGE_TITLE, "After")
                .set(ExifTags.Tag.NOISE_REDUCTION, "quite a lot"), values));

        assertEquals("Before", values.get(ExifTags.Tag.IMAGE_TITLE).text());
        assertNull(values.get(ExifTags.Tag.NOISE_REDUCTION));
    }

    /** Anything that is not an Exif JPEG is declined rather than mangled. */
    @Test
    public void somethingThatIsNotExifIsLeftAlone() {
        assertNull(write(new byte[] {0, 1, 2, 3},
                new ExifTags.Builder().set(ExifTags.Tag.IMAGE_TITLE, "nope")));
        assertTrue(ExifTags.readAll(new byte[] {0, 1, 2, 3}).isEmpty());
    }

    /** The tags in a file that never had them read as absent rather than as anything else. */
    @Test
    public void anAbsentTagReadsAsNull() {
        assertNull(read(exifJpeg(), ExifTags.Tag.WATER_DEPTH));
    }

    /**
     * A value read from a file of one byte order and written into a file of the other says the
     * same thing there, a rational's two halves each turned around.
     */
    @Test
    public void aValueMovesBetweenByteOrders() {
        byte[] little = write(exifJpeg(true), new ExifTags.Builder()
                .set(ExifTags.Tag.CAMERA_ELEVATION_ANGLE, "-12.25")
                .set(ExifTags.Tag.NOISE_REDUCTION, "513")
                .set(ExifTags.Tag.IMAGE_TITLE, "Either way"));
        assertNotNull(little);
        Map<ExifTags.Tag, ExifTags.Value> values = ExifTags.readAll(little);

        byte[] big = ExifTags.write(exifJpeg(false), values);

        assertNotNull(big);
        assertEquals("-12250/1000", read(big, ExifTags.Tag.CAMERA_ELEVATION_ANGLE));
        assertEquals("513", read(big, ExifTags.Tag.NOISE_REDUCTION));
        assertEquals("Either way", read(big, ExifTags.Tag.IMAGE_TITLE));
    }

    /**
     * A file's own value is written back as it was stored, whatever its type and however many
     * values it holds, rather than as the text it reads as: two shorts in one entry, a long where
     * the standard says short, and bytes that are not text at all.
     */
    @Test
    public void aFilesOwnValueIsWrittenBackExactly() {
        byte[] sources = {0, 3, 0, 2};
        byte[] composite = {0, 0, 0, 2};
        byte[] exposures = {0, 0, 0, 1, (byte) 0xFF, (byte) 0xFE, (byte) 0x80, 0x7F, 0, 1};
        byte[] jpeg = exifJpeg(false,
                new int[] {ExifTags.Tag.COMPOSITE_IMAGE.getId(), TYPE_LONG, 1},
                composite,
                new int[] {ExifTags.Tag.SOURCE_IMAGE_NUMBER_OF_COMPOSITE_IMAGE.getId(),
                        TYPE_SHORT, 2},
                sources,
                new int[] {ExifTags.Tag.SOURCE_EXPOSURE_TIMES_OF_COMPOSITE_IMAGE.getId(),
                        TYPE_UNDEFINED, exposures.length},
                exposures);
        Map<ExifTags.Tag, ExifTags.Value> values = ExifTags.readAll(jpeg);
        assertEquals("the fixture should carry all three", 3, values.size());

        byte[] out = ExifTags.write(exifJpeg(true), values);

        assertNotNull(out);
        Map<ExifTags.Tag, ExifTags.Value> written = ExifTags.readAll(out);
        assertStored(written.get(ExifTags.Tag.COMPOSITE_IMAGE), TYPE_LONG, 1, composite);
        assertStored(written.get(ExifTags.Tag.SOURCE_IMAGE_NUMBER_OF_COMPOSITE_IMAGE),
                TYPE_SHORT, 2, sources);
        assertStored(written.get(ExifTags.Tag.SOURCE_EXPOSURE_TIMES_OF_COMPOSITE_IMAGE),
                TYPE_UNDEFINED, exposures.length, exposures);
        assertEquals("a long reads as its number", "2",
                written.get(ExifTags.Tag.COMPOSITE_IMAGE).text());
    }

    /** An entry whose value points outside the block is no value, rather than stray bytes. */
    @Test
    public void aValueOutsideTheBlockIsSkipped() {
        byte[] jpeg = exifJpeg(true,
                new int[] {ExifTags.Tag.IMAGE_TITLE.getId(), 2, 64}, new byte[0]);

        assertTrue(ExifTags.readAll(jpeg).isEmpty());
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
        assertNull("a tag androidx does name is not one of these",
                ExifTags.Tag.forId(TAG_EXIF_VERSION));
    }

    /** The file with the builder's changes applied, as a save applies them, or null. */
    private static byte[] write(byte[] jpeg, ExifTags.Builder builder) {
        Map<ExifTags.Tag, ExifTags.Value> values = ExifTags.readAll(jpeg);
        if (!ExifTags.apply(builder, values)) {
            return null;
        }
        return ExifTags.write(jpeg, values);
    }

    /** A tag's value in the file, as text. */
    private static String read(byte[] jpeg, ExifTags.Tag tag) {
        ExifTags.Value value = ExifTags.readAll(jpeg).get(tag);
        return value == null ? null : value.text();
    }

    private static void assertStored(ExifTags.Value value, int type, int count, byte[] bytes) {
        assertNotNull(value);
        assertEquals("type", type, value.type);
        assertEquals("count", count, value.count);
        assertArrayEquals("bytes", bytes, value.bytes);
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

    /** A little-endian Exif JPEG with an IFD0 pointing at a one-entry private directory. */
    private static byte[] exifJpeg() {
        return exifJpeg(true);
    }

    /**
     * An Exif JPEG with an IFD0 pointing at a private directory that carries ExifVersion and then
     * any further entries, given as pairs of {tag, type, count} and big-endian value bytes, in
     * ascending tag order. A value of more than four bytes follows the directory; an empty one
     * leaves the entry pointing past the end of the block.
     */
    private static byte[] exifJpeg(boolean little, Object... extra) {
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
        int entries = 1 + extra.length / 2;
        writeShort(tiff, entries);
        writeEntry(tiff, TAG_EXIF_VERSION, 7, 4, 0x32333230);
        ByteArrayOutputStream values = new ByteArrayOutputStream();
        int valuesAt = subIfd + 2 + entries * ENTRY_BYTES + 4;
        for (int i = 0; i < extra.length; i += 2) {
            int[] entry = (int[]) extra[i];
            byte[] bytes = inOrder((byte[]) extra[i + 1], entry[1], little);
            writeShort(tiff, entry[0]);
            writeShort(tiff, entry[1]);
            writeInt(tiff, entry[2]);
            if (bytes.length == 0) {
                writeInt(tiff, 0x7FFF);
            } else if (bytes.length <= 4) {
                tiff.write(bytes, 0, bytes.length);
                for (int pad = bytes.length; pad < 4; pad++) tiff.write(0);
            } else {
                writeInt(tiff, valuesAt + values.size());
                values.write(bytes, 0, bytes.length);
            }
        }
        writeInt(tiff, 0);
        byte[] valueBytes = values.toByteArray();
        tiff.write(valueBytes, 0, valueBytes.length);

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

    /** Big-endian value bytes in the fixture's order: each short or long turned around. */
    private static byte[] inOrder(byte[] bigEndian, int type, boolean little) {
        byte[] bytes = bigEndian.clone();
        int unit = type == TYPE_SHORT ? 2 : type == TYPE_LONG ? 4 : 1;
        if (little) {
            for (int start = 0; start + unit <= bytes.length; start += unit) {
                for (int i = start, j = start + unit - 1; i < j; i++, j--) {
                    byte swap = bytes[i];
                    bytes[i] = bytes[j];
                    bytes[j] = swap;
                }
            }
        }
        return bytes;
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
