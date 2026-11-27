package com.xlythe.view.camera;

import static com.xlythe.view.camera.ExifFixtures.TAG_EXIF_VERSION;
import static com.xlythe.view.camera.ExifFixtures.TAG_UNDEFINED_BY_ANY_STANDARD;
import static com.xlythe.view.camera.ExifFixtures.exifJpeg;
import static com.xlythe.view.camera.ExifFixtures.privateTags;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.exifinterface.media.ExifInterface;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * The entries of the Exif private directory, by number: read as the bytes a file stores, and
 * written into a copy of the directory in ascending order, with the right count, every existing
 * entry still resolving, and IFD0 pointing at the copy.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class ExifTagsTest {
    private static final int TAG_IMAGE_TITLE = 0xA436;
    private static final int TAG_PHOTOGRAPHER = 0xA437;
    private static final int TAG_HUMIDITY = 0x9401;
    private static final int TAG_PRESSURE = 0x9402;
    private static final int TAG_CAMERA_ELEVATION_ANGLE = 0x9405;
    private static final int TAG_NOISE_REDUCTION = 0xA412;
    private static final int TAG_COMPOSITE_IMAGE = 0xA460;
    private static final int TAG_SOURCE_IMAGE_NUMBER = 0xA461;
    private static final int TAG_SOURCE_EXPOSURE_TIMES = 0xA462;

    /** A big-endian file, as ExifInterface saves one, whose counts must be read big-endian. */
    @Test
    public void aBigEndianFileWorksTheSameWay() {
        byte[] out = write(exifJpeg(false),
                TAG_IMAGE_TITLE, Exif.TYPE_ASCII, "Motorola order",
                TAG_HUMIDITY, Exif.TYPE_RATIONAL, "44.5");

        assertNotNull("a big-endian file should accept tags too", out);
        assertEquals("Motorola order", read(out, TAG_IMAGE_TITLE));
        assertEquals("44500/1000", read(out, TAG_HUMIDITY));
    }

    /** A tag no edition of the standard defines is written and read back like any other. */
    @Test
    public void aTagNoStandardDefinesIsWrittenAndReadBack() {
        byte[] out = write(exifJpeg(true),
                TAG_UNDEFINED_BY_ANY_STANDARD, Exif.TYPE_ASCII, "From the future");

        assertNotNull(out);
        assertEquals("From the future", read(out, TAG_UNDEFINED_BY_ANY_STANDARD));
    }

    /** Values of every type survive, several of a number in one entry where the type allows. */
    @Test
    public void valuesOfEveryTypeSurvive() {
        byte[] out = write(exifJpeg(true),
                0xB001, Exif.TYPE_BYTE, "1,255",
                0xB002, Exif.TYPE_ASCII, "Harbour at dawn",
                0xB003, Exif.TYPE_SHORT, "1,65535",
                0xB004, Exif.TYPE_LONG, "4294967295",
                0xB006, Exif.TYPE_RATIONAL, "61.5,1/3",
                0xB007, Exif.TYPE_SBYTE, "-128,127",
                0xB008, Exif.TYPE_UNDEFINED, "1",
                0xB009, Exif.TYPE_SSHORT, "-2",
                0xB00A, Exif.TYPE_SLONG, "-70000",
                0xB00B, Exif.TYPE_SRATIONAL, "-12.25",
                0xB00C, Exif.TYPE_FLOAT, "1.5,-0.25",
                0xB00D, Exif.TYPE_DOUBLE, "3.25",
                0xB00E, Exif.TYPE_UTF8, "Café");

        assertNotNull(out);
        assertEquals("1,255", read(out, 0xB001));
        assertEquals("Harbour at dawn", read(out, 0xB002));
        assertEquals("1,65535", read(out, 0xB003));
        assertEquals("4294967295", read(out, 0xB004));
        assertEquals("61500/1000,1/3", read(out, 0xB006));
        assertEquals("-128,127", read(out, 0xB007));
        assertEquals("1", read(out, 0xB008));
        assertEquals("-2", read(out, 0xB009));
        assertEquals("-70000", read(out, 0xB00A));
        assertEquals("-12250/1000", read(out, 0xB00B));
        assertEquals("1.5,-0.25", read(out, 0xB00C));
        assertEquals("3.25", read(out, 0xB00D));
        assertEquals("Café", read(out, 0xB00E));
    }

    /** Each type reads back as the first number it holds, or NaN for one that holds none. */
    @Test
    public void eachTypeReadsAsANumber() {
        assertEquals(-12.25, ExifTags.encode(Exif.TYPE_SRATIONAL, "-12.25").number(), 1e-9);
        assertEquals(65535, ExifTags.encode(Exif.TYPE_SHORT, "65535,1").number(), 0);
        assertEquals(-2, ExifTags.encode(Exif.TYPE_SSHORT, "-2").number(), 0);
        assertEquals(255, ExifTags.encode(Exif.TYPE_BYTE, "255").number(), 0);
        assertEquals(-1, ExifTags.encode(Exif.TYPE_SBYTE, "-1").number(), 0);
        assertEquals(4294967295.0, ExifTags.encode(Exif.TYPE_LONG, "4294967295").number(), 0);
        assertEquals(-70000, ExifTags.encode(Exif.TYPE_SLONG, "-70000").number(), 0);
        assertEquals(1.5, ExifTags.encode(Exif.TYPE_FLOAT, "1.5").number(), 0);
        assertEquals(3.25, ExifTags.encode(Exif.TYPE_DOUBLE, "3.25").number(), 0);
        assertTrue(Double.isNaN(ExifTags.encode(Exif.TYPE_ASCII, "12").number()));
        assertTrue("a zero denominator is no number", Double.isNaN(
                new ExifTags.Value(Exif.TYPE_RATIONAL, 1, new byte[] {0, 0, 0, 1, 0, 0, 0, 0})
                        .number()));
    }

    /** A rational written as a fraction is stored as that fraction, not re-derived. */
    @Test
    public void aFractionIsStoredAsWritten() {
        byte[] out = write(exifJpeg(true), TAG_PRESSURE, Exif.TYPE_RATIONAL, "10132/10");

        assertNotNull(out);
        assertEquals("10132/10", read(out, TAG_PRESSURE));
    }

    /** A decimal too big for thousandths is written as finely as the type can hold it. */
    @Test
    public void aLargeDecimalIsWrittenCoarser() {
        assertEquals("4000000000/1",
                ExifTags.encode(Exif.TYPE_RATIONAL, "4000000000").text());
        assertEquals("980665000/100", ExifTags.rational(9806650, false).text());
        assertNull("nor anything past what one number holds",
                ExifTags.encode(Exif.TYPE_RATIONAL, "5000000000"));
    }

    /** Text goes in as ASCII when it can, and as UTF-8 when it holds anything more. */
    @Test
    public void textTakesThePlainestTypeThatHoldsIt() {
        assertEquals(Exif.TYPE_ASCII, ExifTags.text("Plain").type);
        assertEquals(Exif.TYPE_UTF8, ExifTags.text("Café").type);
        assertEquals("Café", ExifTags.text("Café").text());
    }

    /**
     * The directory comes back in ascending tag order, which TIFF requires, since a reader may
     * binary search it.
     */
    @Test
    public void entriesStayInAscendingTagOrder() {
        byte[] out = write(exifJpeg(true),
                TAG_COMPOSITE_IMAGE, Exif.TYPE_SHORT, "2",
                0x9400, Exif.TYPE_SRATIONAL, "21.5",
                TAG_IMAGE_TITLE, Exif.TYPE_ASCII, "Out of order on purpose");

        assertNotNull(out);
        int[] tags = privateTags(out);
        for (int i = 1; i < tags.length; i++) {
            assertTrue("tag 0x" + Integer.toHexString(tags[i]) + " follows 0x"
                            + Integer.toHexString(tags[i - 1]),
                    tags[i] > tags[i - 1]);
        }
    }

    /** What was already in the directory is still there, and still says what it said. */
    @Test
    public void theEntriesAlreadyThereAreKept() {
        byte[] out = write(exifJpeg(true), TAG_IMAGE_TITLE, Exif.TYPE_ASCII, "Added later");

        assertNotNull(out);
        assertArrayEquals(new int[] {TAG_EXIF_VERSION, TAG_IMAGE_TITLE}, privateTags(out));
        assertEquals("0232", read(out, TAG_EXIF_VERSION));
    }

    /** Setting a tag that is already present replaces it rather than writing it twice. */
    @Test
    public void settingATagTwiceLeavesOneOfIt() {
        byte[] once = write(exifJpeg(true), TAG_IMAGE_TITLE, Exif.TYPE_ASCII, "First");
        assertNotNull(once);

        byte[] twice = write(once, TAG_IMAGE_TITLE, Exif.TYPE_ASCII, "Second");

        assertNotNull(twice);
        assertEquals("Second", read(twice, TAG_IMAGE_TITLE));
        assertArrayEquals(new int[] {TAG_EXIF_VERSION, TAG_IMAGE_TITLE}, privateTags(twice));
    }

    /**
     * Removing takes entries out where they are, the others moving up and nothing added, which is
     * why it needs no room.
     */
    @Test
    public void removingTakesEntriesOutInPlace() {
        byte[] jpeg = write(exifJpeg(true),
                TAG_HUMIDITY, Exif.TYPE_RATIONAL, "44.5",
                TAG_IMAGE_TITLE, Exif.TYPE_ASCII, "Going",
                TAG_PHOTOGRAPHER, Exif.TYPE_ASCII, "Staying");
        assertNotNull(jpeg);
        int length = jpeg.length;

        assertTrue(ExifTags.remove(jpeg, Collections.singleton(TAG_IMAGE_TITLE)));

        assertEquals("nothing is added", length, jpeg.length);
        assertArrayEquals(new int[] {TAG_EXIF_VERSION, TAG_HUMIDITY, TAG_PHOTOGRAPHER},
                privateTags(jpeg));
        assertEquals("Staying", read(jpeg, TAG_PHOTOGRAPHER));
        assertEquals("44500/1000", read(jpeg, TAG_HUMIDITY));
        assertFalse("and removing what isn't there changes nothing",
                ExifTags.remove(jpeg, Collections.singleton(TAG_IMAGE_TITLE)));
    }

    /** A word handed to a numeric type writes nothing, rather than a field full of rubbish. */
    @Test
    public void anUnparseableNumberIsRefused() {
        assertNull(ExifTags.encode(Exif.TYPE_SHORT, "quite a lot"));
        assertNull(ExifTags.encode(Exif.TYPE_RATIONAL, "1/"));
        assertNull(ExifTags.encode(Exif.TYPE_FLOAT, "one and a half"));
        assertNull("nor one in a list", ExifTags.encode(Exif.TYPE_LONG, "1,,2"));
        assertNull("nor a type the standard doesn't define", ExifTags.encode(99, "1"));
    }

    /** Nor does a number too big for its type, a fraction over nothing, or no number at all. */
    @Test
    public void aNumberTheTypeCannotHoldIsRefused() {
        assertNull("a short holds no more than 65535", ExifTags.encode(Exif.TYPE_SHORT, "65536"));
        assertNull("nor anything negative", ExifTags.encode(Exif.TYPE_SHORT, "-1"));
        assertNull("a byte holds no more than 255", ExifTags.encode(Exif.TYPE_BYTE, "256"));
        assertNull(ExifTags.encode(Exif.TYPE_SBYTE, "128"));
        assertNull(ExifTags.encode(Exif.TYPE_SSHORT, "32768"));
        assertNull(ExifTags.encode(Exif.TYPE_SLONG, "2147483648"));
        assertNull("an unsigned rational takes no minus sign",
                ExifTags.encode(Exif.TYPE_RATIONAL, "-3/1"));
        assertNull("a denominator of zero is no number",
                ExifTags.encode(Exif.TYPE_RATIONAL, "1013/0"));
        assertNull(ExifTags.encode(Exif.TYPE_SRATIONAL, "NaN"));
        assertNull(ExifTags.rational(Double.POSITIVE_INFINITY, true));
        assertNull("bytes have to be some bytes", ExifTags.encode(Exif.TYPE_UNDEFINED, ""));
        assertNull(ExifTags.undefined(new byte[0]));
    }

    /** Anything that is not an Exif JPEG is declined rather than mangled. */
    @Test
    public void somethingThatIsNotExifIsLeftAlone() {
        byte[] junk = {0, 1, 2, 3};
        Map<Integer, ExifTags.Value> title = new TreeMap<>();
        title.put(TAG_IMAGE_TITLE, ExifTags.text("nope"));

        assertNull(ExifTags.write(junk, title));
        assertFalse(ExifTags.remove(junk, Collections.singleton(TAG_IMAGE_TITLE)));
        assertTrue(ExifTags.readAll(junk).isEmpty());
    }

    /** A tag a file never had reads as absent rather than as anything else. */
    @Test
    public void anAbsentTagReadsAsNull() {
        assertNull(read(exifJpeg(true), 0x9403));
    }

    /**
     * A value read from a file of one byte order and written into a file of the other says the
     * same thing there, each number turned around.
     */
    @Test
    public void aValueMovesBetweenByteOrders() {
        byte[] little = write(exifJpeg(true),
                TAG_CAMERA_ELEVATION_ANGLE, Exif.TYPE_SRATIONAL, "-12.25",
                TAG_NOISE_REDUCTION, Exif.TYPE_SHORT, "513",
                0xB00C, Exif.TYPE_FLOAT, "1.5",
                0xB00D, Exif.TYPE_DOUBLE, "-3.25",
                TAG_IMAGE_TITLE, Exif.TYPE_ASCII, "Either way");
        assertNotNull(little);
        Map<Integer, ExifTags.Value> values = ExifTags.readAll(little);
        values.remove(TAG_EXIF_VERSION);

        byte[] big = ExifTags.write(exifJpeg(false), values);

        assertNotNull(big);
        assertEquals("-12250/1000", read(big, TAG_CAMERA_ELEVATION_ANGLE));
        assertEquals("513", read(big, TAG_NOISE_REDUCTION));
        assertEquals("1.5", read(big, 0xB00C));
        assertEquals("-3.25", read(big, 0xB00D));
        assertEquals("Either way", read(big, TAG_IMAGE_TITLE));
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
                new int[] {TAG_COMPOSITE_IMAGE, Exif.TYPE_LONG, 1}, composite,
                new int[] {TAG_SOURCE_IMAGE_NUMBER, Exif.TYPE_SHORT, 2}, sources,
                new int[] {TAG_SOURCE_EXPOSURE_TIMES, Exif.TYPE_UNDEFINED, exposures.length},
                exposures);
        Map<Integer, ExifTags.Value> values = ExifTags.readAll(jpeg);
        values.remove(TAG_EXIF_VERSION);
        assertEquals("the fixture should carry all three", 3, values.size());

        byte[] out = ExifTags.write(exifJpeg(true), values);

        assertNotNull(out);
        Map<Integer, ExifTags.Value> written = ExifTags.readAll(out);
        assertStored(written.get(TAG_COMPOSITE_IMAGE), Exif.TYPE_LONG, 1, composite);
        assertStored(written.get(TAG_SOURCE_IMAGE_NUMBER), Exif.TYPE_SHORT, 2, sources);
        assertStored(written.get(TAG_SOURCE_EXPOSURE_TIMES), Exif.TYPE_UNDEFINED,
                exposures.length, exposures);
        assertEquals("a long reads as its number", "2", written.get(TAG_COMPOSITE_IMAGE).text());
        assertEquals("two shorts read as both", "3,2",
                written.get(TAG_SOURCE_IMAGE_NUMBER).text());
    }

    /** An entry whose value points outside the block is no value, rather than stray bytes. */
    @Test
    public void aValueOutsideTheBlockIsSkipped() {
        byte[] jpeg = exifJpeg(true, new int[] {TAG_IMAGE_TITLE, Exif.TYPE_ASCII, 64}, new byte[0]);

        assertArrayEquals(new Object[] {TAG_EXIF_VERSION},
                ExifTags.readAll(jpeg).keySet().toArray());
    }

    /** An entry of a type the standard doesn't define is no value either. */
    @Test
    public void aTypeWithNoSizeIsSkipped() {
        byte[] jpeg = exifJpeg(true, new int[] {TAG_IMAGE_TITLE, 99, 1}, new byte[] {1});

        assertNull(read(jpeg, TAG_IMAGE_TITLE));
    }

    /**
     * Tags are known by the numbers the standard assigns them, apart from those ExifInterface
     * reads and writes by name.
     */
    @Test
    public void theTagNumbersAreTheOnesTheStandardAssigns() {
        assertArrayEquals(new int[] {0x9287, Exif.TYPE_UNDEFINED},
                ExifTags.standardTag("LearningOptOutIn"));
        assertArrayEquals(new int[] {0x9400, Exif.TYPE_SRATIONAL},
                ExifTags.standardTag("Temperature"));
        assertArrayEquals(new int[] {0xA435, Exif.TYPE_ASCII},
                ExifTags.standardTag(ExifInterface.TAG_LENS_SERIAL_NUMBER));
        assertArrayEquals(new int[] {0xA436, Exif.TYPE_ASCII},
                ExifTags.standardTag("ImageTitle"));
        assertArrayEquals(new int[] {0xA462, Exif.TYPE_UNDEFINED},
                ExifTags.standardTag("SourceExposureTimesOfCompositeImage"));
        assertNull("a name ExifInterface reads is not one of these",
                ExifTags.standardTag(ExifInterface.TAG_F_NUMBER));
        assertEquals(0x829D, ExifTags.attributeTag(ExifInterface.TAG_F_NUMBER));
        assertEquals(ExifInterface.TAG_GAMMA, ExifTags.attributeName(0xA500));
        assertEquals(-1, ExifTags.attributeTag("ImageTitle"));
        assertNull(ExifTags.attributeName(TAG_UNDEFINED_BY_ANY_STANDARD));
        assertEquals("ImageTitle (0xA436)", ExifTags.describe(TAG_IMAGE_TITLE));
        assertEquals("FNumber (0x829D)", ExifTags.describe(0x829D));
        assertEquals("0xA4F0", ExifTags.describe(TAG_UNDEFINED_BY_ANY_STANDARD));
    }

    /** The file with each {tag, type, text} triple set, as a save sets them, or null. */
    private static byte[] write(byte[] jpeg, Object... triples) {
        Map<Integer, ExifTags.Value> values = new TreeMap<>();
        for (int i = 0; i < triples.length; i += 3) {
            ExifTags.Value value =
                    ExifTags.encode((Integer) triples[i + 1], (String) triples[i + 2]);
            assertNotNull("the fixture's value should encode", value);
            values.put((Integer) triples[i], value);
        }
        return ExifTags.write(jpeg, values);
    }

    /** A tag's value in the file, as text. */
    private static String read(byte[] jpeg, int tag) {
        ExifTags.Value value = ExifTags.readAll(jpeg).get(tag);
        return value == null ? null : value.text();
    }

    private static void assertStored(ExifTags.Value value, int type, int count, byte[] bytes) {
        assertNotNull(value);
        assertEquals("type", type, value.type);
        assertEquals("count", count, value.count);
        assertArrayEquals("bytes", bytes, value.bytes);
    }
}
