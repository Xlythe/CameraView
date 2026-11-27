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

/** The directory that carries a thumbnail, checked byte by byte. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class ExifThumbnailTest {
    private static final int ENTRY_BYTES = 12;
    private static final int TAG_COMPRESSION = 0x0103;
    private static final int TAG_JPEG_OFFSET = 0x0201;
    private static final int TAG_JPEG_LENGTH = 0x0202;

    /** The case the writer exists for: a file with no second directory gets one. */
    @Test
    public void aThumbnailIsAppendedAndPointedAt() {
        byte[] jpeg = exifJpeg(0, 0, false);
        byte[] thumbnail = fakeThumbnail(512);

        byte[] out = ExifThumbnail.attach(jpeg, thumbnail, 1);

        assertNotNull("a well-formed Exif JPEG should accept a thumbnail", out);
        Ifd1 ifd1 = readIfd1(out);
        assertNotNull("IFD0 should now lead somewhere", ifd1);
        assertEquals("the thumbnail's length has to survive", thumbnail.length,
                ifd1.value(TAG_JPEG_LENGTH));
        assertEquals("and it has to say it is a JPEG", 6, ifd1.value(TAG_COMPRESSION));
    }

    /**
     * The entry count matches the entries. A count one short still parses, but the next-IFD
     * pointer lands on the last entry and that tag is lost.
     */
    @Test
    public void theEntryCountMatchesTheEntriesWritten() {
        byte[] out = ExifThumbnail.attach(exifJpeg(0, 0, false), fakeThumbnail(256), 1);
        assertNotNull(out);

        Ifd1 ifd1 = readIfd1(out);
        assertNotNull(ifd1);
        assertTrue("the last entry written must be inside the count",
                ifd1.has(TAG_JPEG_LENGTH));
        assertEquals("a directory ends with a zero pointer, not with an entry", 0, ifd1.next);
    }

    /** A thumbnail already named and already declared is left exactly as it is. */
    @Test
    public void aDeclaredThumbnailIsNotTouched() {
        byte[] jpeg = exifJpeg(600, 400, true);

        assertNull("nothing to repair", ExifThumbnail.declareExistingThumbnail(jpeg, 1));
    }

    /**
     * A thumbnail nothing declares, as a rewritten Exif block leaves one, is named again without
     * being moved or re-encoded.
     */
    @Test
    public void anUndeclaredThumbnailIsDeclaredWhereItLies() {
        byte[] jpeg = exifJpeg(600, 400, false);
        int offsetBefore = readIfd1(jpeg).value(TAG_JPEG_OFFSET);

        byte[] out = ExifThumbnail.declareExistingThumbnail(jpeg, 1);

        assertNotNull("an undeclared thumbnail should be repaired", out);
        Ifd1 ifd1 = readIfd1(out);
        assertEquals("the bytes should not have moved", offsetBefore, ifd1.value(TAG_JPEG_OFFSET));
        assertEquals("the length should carry over", 400, ifd1.value(TAG_JPEG_LENGTH));
        assertEquals("and now it says what it is", 6, ifd1.value(TAG_COMPRESSION));
    }

    /** A segment with no room left refuses rather than overflowing its own length field. */
    @Test
    public void aFullSegmentRefusesAThumbnail() {
        byte[] jpeg = exifJpeg(0, 0, false, ExifSegment.MAX_APP1_PAYLOAD - 64);

        assertEquals("almost nothing should be on offer", -1,
                ExifThumbnail.roomForThumbnail(jpeg));
        assertNull("and attaching should refuse",
                ExifThumbnail.attach(jpeg, fakeThumbnail(4096), 1));
    }

    /** Anything that is not an Exif JPEG is declined rather than mangled. */
    @Test
    public void somethingThatIsNotExifIsLeftAlone() {
        assertNull(ExifThumbnail.attach(new byte[] {0, 1, 2, 3}, fakeThumbnail(8), 1));
        assertNull(ExifThumbnail.attach(
                new byte[] {(byte) 0xFF, (byte) 0xD8}, fakeThumbnail(8), 1));
    }

    /** The room on offer shrinks as the Exif block grows, and is what attach will accept. */
    @Test
    public void theRoomOnOfferIsWhatCanActuallyBeWritten() {
        byte[] jpeg = exifJpeg(0, 0, false, 1024);

        int room = ExifThumbnail.roomForThumbnail(jpeg);
        assertTrue("a small Exif block leaves plenty", room > 60000);
        assertNotNull("a thumbnail inside the room fits",
                ExifThumbnail.attach(jpeg, fakeThumbnail(room), 1));
        assertNull("one byte over does not",
                ExifThumbnail.attach(jpeg, fakeThumbnail(room + 1), 1));
    }

    /** A directory read back out of a file, so the assertions are about what was written. */
    private static final class Ifd1 {
        final int[] tags;
        final int[] values;
        final int next;

        Ifd1(int[] tags, int[] values, int next) {
            this.tags = tags;
            this.values = values;
            this.next = next;
        }

        boolean has(int tag) {
            for (int candidate : tags) {
                if (candidate == tag) return true;
            }
            return false;
        }

        int value(int tag) {
            for (int i = 0; i < tags.length; i++) {
                if (tags[i] == tag) return values[i];
            }
            throw new AssertionError("no tag " + Integer.toHexString(tag) + " in IFD1");
        }
    }

    private static Ifd1 readIfd1(byte[] jpeg) {
        int tiff = 12;
        int ifd0 = tiff + readInt(jpeg, tiff + 4);
        int count = readShort(jpeg, ifd0);
        int next = readInt(jpeg, ifd0 + 2 + count * ENTRY_BYTES);
        if (next == 0) return null;
        int ifd1 = tiff + next;
        int entries = readShort(jpeg, ifd1);
        int[] tags = new int[entries];
        int[] values = new int[entries];
        for (int i = 0; i < entries; i++) {
            int entry = ifd1 + 2 + i * ENTRY_BYTES;
            tags[i] = readShort(jpeg, entry);
            int type = readShort(jpeg, entry + 2);
            values[i] = type == 3 ? readShort(jpeg, entry + 8) : readInt(jpeg, entry + 8);
        }
        return new Ifd1(tags, values, readInt(jpeg, ifd1 + 2 + entries * ENTRY_BYTES));
    }

    private static int readShort(byte[] data, int at) {
        return ((data[at + 1] & 0xFF) << 8) | (data[at] & 0xFF);
    }

    private static int readInt(byte[] data, int at) {
        return ((data[at + 3] & 0xFF) << 24) | ((data[at + 2] & 0xFF) << 16)
                | ((data[at + 1] & 0xFF) << 8) | (data[at] & 0xFF);
    }

    private static byte[] fakeThumbnail(int length) {
        byte[] thumbnail = new byte[length];
        thumbnail[0] = (byte) 0xFF;
        thumbnail[1] = (byte) 0xD8;
        return thumbnail;
    }

    private static byte[] exifJpeg(int thumbLength, int declaredLength, boolean declared) {
        return exifJpeg(thumbLength, declaredLength, declared, 0);
    }

    /** A minimal little-endian Exif JPEG, optionally carrying a thumbnail and padding. */
    private static byte[] exifJpeg(int thumbLength, int declaredLength, boolean declared,
            int padding) {
        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        writeShort(tiff, 0x4949);
        writeShort(tiff, 42);
        writeInt(tiff, 8);

        // IFD0: one entry, then a pointer to IFD1 if there is one.
        boolean hasThumb = thumbLength > 0;
        writeShort(tiff, 1);
        writeEntry(tiff, 0x0112, 3, 1, 1);
        int ifd0End = 8 + 2 + ENTRY_BYTES + 4;
        int ifd1At = ifd0End + padding;
        writeInt(tiff, hasThumb ? ifd1At : 0);
        for (int i = 0; i < padding; i++) {
            tiff.write(0);
        }
        if (hasThumb) {
            int entries = declared ? 3 : 2;
            int thumbAt = ifd1At + 2 + entries * ENTRY_BYTES + 4;
            writeShort(tiff, entries);
            writeEntry(tiff, TAG_JPEG_OFFSET, 4, 1, thumbAt);
            writeEntry(tiff, TAG_JPEG_LENGTH, 4, 1, declaredLength);
            if (declared) {
                writeEntry(tiff, TAG_COMPRESSION, 3, 1, 6);
            }
            writeInt(tiff, 0);
            for (int i = 0; i < thumbLength; i++) {
                tiff.write(i == 0 ? 0xFF : (i == 1 ? 0xD8 : 0));
            }
        }

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
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }
}
