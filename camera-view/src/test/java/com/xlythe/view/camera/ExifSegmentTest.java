package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;

/** Turning a tag written as text back into the bytes the standard defines it as. */
public class ExifSegmentTest {
    private static final int TYPE_ASCII = 2;
    private static final int TYPE_UNDEFINED = 7;
    private static final int FILE_SOURCE = 0xA300;
    private static final int COMPONENTS_CONFIGURATION = 0x9101;

    /** A one-byte tag, written as that byte and a terminator, both inside the entry. */
    @Test
    public void aShortValueIsRetypedInPlace() {
        for (boolean little : new boolean[] {false, true}) {
            byte[] jpeg = jpegWithEntry(little, FILE_SOURCE, TYPE_ASCII, 2, new byte[] {3, 0});
            ExifSegment.Segment segment = ExifSegment.find(jpeg);
            assertNotNull(segment);

            int entry = ExifSegment.retypeAsUndefined(jpeg, segment, FILE_SOURCE, 1);

            assertTrue(entry > 0);
            assertEquals(TYPE_UNDEFINED, ExifSegment.u16(jpeg, entry + 2, little));
            assertEquals(1, ExifSegment.u32(jpeg, entry + 4, little));
            assertEquals(3, jpeg[entry + 8]);
            assertEquals("the terminator goes", 0, jpeg[entry + 9]);
        }
    }

    /** Four bytes and a terminator lived elsewhere, and now fit in the entry itself. */
    @Test
    public void aFourByteValueMovesIntoTheEntry() {
        byte[] jpeg = jpegWithEntry(false, COMPONENTS_CONFIGURATION, TYPE_ASCII, 5,
                new byte[] {1, 2, 3, 0, 0});
        ExifSegment.Segment segment = ExifSegment.find(jpeg);
        assertNotNull(segment);

        int entry = ExifSegment.retypeAsUndefined(jpeg, segment, COMPONENTS_CONFIGURATION, 4);

        assertTrue(entry > 0);
        assertEquals(TYPE_UNDEFINED, ExifSegment.u16(jpeg, entry + 2, false));
        assertEquals(4, ExifSegment.u32(jpeg, entry + 4, false));
        assertEquals(1, jpeg[entry + 8]);
        assertEquals(2, jpeg[entry + 9]);
        assertEquals(3, jpeg[entry + 10]);
        assertEquals(0, jpeg[entry + 11]);
    }

    /** A tag already written as bytes is left as it is. */
    @Test
    public void anUndefinedTagIsLeftAlone() {
        byte[] jpeg = jpegWithEntry(false, FILE_SOURCE, TYPE_UNDEFINED, 1, new byte[] {3});

        assertEquals(-1, ExifSegment.retypeAsUndefined(
                jpeg, ExifSegment.find(jpeg), FILE_SOURCE, 1));
    }

    /** Text of another length is not this tag's bytes, and is not guessed at. */
    @Test
    public void textOfTheWrongLengthIsLeftAlone() {
        byte[] jpeg = jpegWithEntry(false, FILE_SOURCE, TYPE_ASCII, 3, new byte[] {'3', '3', 0});

        assertEquals(-1, ExifSegment.retypeAsUndefined(
                jpeg, ExifSegment.find(jpeg), FILE_SOURCE, 1));
    }

    /** A tag nobody wrote has nothing to retype. */
    @Test
    public void anAbsentTagIsLeftAlone() {
        byte[] jpeg = jpegWithEntry(false, FILE_SOURCE, TYPE_ASCII, 2, new byte[] {3, 0});

        assertEquals(-1, ExifSegment.retypeAsUndefined(
                jpeg, ExifSegment.find(jpeg), COMPONENTS_CONFIGURATION, 4));
    }

    /** An offset that points past the file is a broken file, and is not followed. */
    @Test
    public void anOffsetPastTheEndIsLeftAlone() {
        byte[] jpeg = jpegWithEntry(false, COMPONENTS_CONFIGURATION, TYPE_ASCII, 5,
                new byte[] {1, 2, 3, 0, 0});
        ExifSegment.Segment segment = ExifSegment.find(jpeg);
        assertNotNull(segment);
        int entry = ExifSegment.findEntry(
                jpeg, segment, ExifSegment.exifIfd(jpeg, segment), COMPONENTS_CONFIGURATION);
        ExifSegment.putU32(jpeg, entry + 8, jpeg.length, false);

        assertEquals(-1, ExifSegment.retypeAsUndefined(
                jpeg, segment, COMPONENTS_CONFIGURATION, 4));
        assertEquals("nothing changed", TYPE_ASCII, ExifSegment.u16(jpeg, entry + 2, false));
    }

    /**
     * A JPEG holding only an Exif segment: IFD0 with a pointer to the Exif directory, which has
     * one entry, and the entry's value after it when it is longer than four bytes.
     */
    private static byte[] jpegWithEntry(
            boolean little, int tag, int type, int count, byte[] value) {
        // TIFF offsets: header 0-7, IFD0 at 8 (one entry: 2 + 12 + 4 = 18 bytes), Exif IFD at
        // 26 (one entry, 18 bytes), and any out-of-line value at 44.
        byte[] tiff = new byte[44 + (value.length > 4 ? value.length : 0)];
        if (little) {
            tiff[0] = 'I';
            tiff[1] = 'I';
        } else {
            tiff[0] = 'M';
            tiff[1] = 'M';
        }
        ExifSegment.putU16(tiff, 2, 42, little);
        ExifSegment.putU32(tiff, 4, 8, little);
        ExifSegment.putU16(tiff, 8, 1, little);
        ExifSegment.putU16(tiff, 10, ExifSegment.TAG_EXIF_IFD_POINTER, little);
        ExifSegment.putU16(tiff, 12, 4 /* LONG */, little);
        ExifSegment.putU32(tiff, 14, 1, little);
        ExifSegment.putU32(tiff, 18, 26, little);
        ExifSegment.putU16(tiff, 26, 1, little);
        ExifSegment.putU16(tiff, 28, tag, little);
        ExifSegment.putU16(tiff, 30, type, little);
        ExifSegment.putU32(tiff, 32, count, little);
        if (value.length <= 4) {
            System.arraycopy(value, 0, tiff, 36, value.length);
        } else {
            ExifSegment.putU32(tiff, 36, 44, little);
            System.arraycopy(value, 0, tiff, 44, value.length);
        }

        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        jpeg.write(0xFF);
        jpeg.write(0xD8);
        jpeg.write(0xFF);
        jpeg.write(0xE1);
        int length = 2 + 6 + tiff.length;
        jpeg.write((length >> 8) & 0xFF);
        jpeg.write(length & 0xFF);
        jpeg.write(new byte[] {'E', 'x', 'i', 'f', 0, 0}, 0, 6);
        jpeg.write(tiff, 0, tiff.length);
        jpeg.write(0xFF);
        jpeg.write(0xD9);
        return jpeg.toByteArray();
    }
}
