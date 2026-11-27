package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import androidx.exifinterface.media.ExifInterface;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

/** Thumbnails encoded from a real picture, which needs real JPEG decoding and encoding. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 37)
public class ExifThumbnailEncodingTest {
    private static final int TAG_ORIENTATION = 0x0112;

    /**
     * A thumbnail asked for before the rest of the metadata is set is written by the save, no
     * larger than the 160x120 the standard suggests, and turned the way the picture ends up.
     */
    @Test
    public void aThumbnailAskedForBeforeSavingIsWritten() throws IOException {
        File file = picture(1280, 960);

        Exif exif = new Exif(file);
        exif.attachThumbnail();
        exif.rotate(90);
        exif.save();

        assertTrue(exif.hasThumbnail());
        ExifInterface saved = new ExifInterface(file.getPath());
        assertTrue(saved.hasThumbnail());
        Bitmap thumbnail = saved.getThumbnailBitmap();
        assertNotNull(thumbnail);
        assertTrue(thumbnail.getWidth() <= 160 && thumbnail.getHeight() <= 120);
        byte[] jpeg = readAll(file);
        assertTrue("it says it is a JPEG", existing(jpeg).declared);
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, thumbnailOrientation(jpeg));
    }

    /** A plain save, with no thumbnail asked for, keeps the one a file already has. */
    @Test
    public void aPlainSaveKeepsTheThumbnailAFileHas() throws IOException {
        File file = thumbnailed(picture(640, 480));

        Exif exif = new Exif(file);
        exif.setDescription("saved again");
        exif.save();

        assertTrue(exif.hasThumbnail());
        assertOneDeclaredThumbnail(file);
    }

    /**
     * A thumbnail whose directory lost its Compression tag, as some cameras write one, is
     * declared again by a plain save rather than left for readers to guess at.
     */
    @Test
    public void aPlainSaveDeclaresAThumbnailThatLostItsCompression() throws IOException {
        File file = thumbnailed(picture(640, 480));
        ExifInterface camera = new ExifInterface(file.getPath());
        camera.setAttribute(ExifInterface.TAG_COMPRESSION, null);
        camera.saveAttributes();
        assertFalse("the fixture should start undeclared", existing(readAll(file)).declared);

        new Exif(file).save();

        assertOneDeclaredThumbnail(file);
    }

    /** Saving twice leaves one thumbnail, not one per save. */
    @Test
    public void savingTwiceKeepsOneThumbnail() throws IOException {
        File file = picture(640, 480);
        Exif exif = new Exif(file);
        exif.attachThumbnail();
        exif.save();

        exif.save();

        assertTrue(exif.hasThumbnail());
        assertOneDeclaredThumbnail(file);
    }

    /** Nor does asking again, from a second Exif, add a second. */
    @Test
    public void askingAgainAddsNoSecondThumbnail() throws IOException {
        File file = thumbnailed(picture(640, 480));

        Exif again = new Exif(file);
        again.attachThumbnail();
        again.save();

        assertTrue(again.hasThumbnail());
        assertOneDeclaredThumbnail(file);
    }

    /**
     * A block with too little room left for a thumbnail saves without one, and the file stays a
     * picture whose metadata reads back whole.
     */
    @Test
    public void aBlockWithNoRoomSavesWithoutAThumbnail() throws IOException {
        File file = picture(640, 480);
        new Exif(file).save();
        int used = ExifSegment.find(readAll(file)).payloadLength;
        // A maker note that leaves the segment a few hundred bytes short of its limit, which no
        // JPEG fits in.
        char[] filler = new char[ExifSegment.MAX_APP1_PAYLOAD - used - 400];
        Arrays.fill(filler, 'x');
        String makerNote = new String(filler);

        Exif exif = new Exif(file);
        exif.setAttribute(ExifInterface.TAG_MAKER_NOTE, makerNote);
        exif.attachThumbnail();
        exif.save();

        assertFalse(exif.hasThumbnail());
        ExifInterface saved = new ExifInterface(file.getPath());
        assertFalse(saved.hasThumbnail());
        assertEquals(makerNote, saved.getAttribute(ExifInterface.TAG_MAKER_NOTE));
        assertTrue(ExifSegment.find(readAll(file)).payloadLength <= ExifSegment.MAX_APP1_PAYLOAD);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        assertEquals(640, bounds.outWidth);
        assertEquals(480, bounds.outHeight);
    }

    /**
     * Spec tags and a thumbnail asked for together are both written, and a later plain save
     * keeps both, in a block that still parses and stays inside its 64KB.
     */
    @Test
    public void specTagsAndAThumbnailAreBothKept() throws IOException {
        File file = picture(640, 480);
        Exif exif = new Exif(file);
        assertTrue(exif.setTags(new ExifTags.Builder()
                .set(ExifTags.Tag.IMAGE_TITLE, "Both")
                .set(ExifTags.Tag.TEMPERATURE, "21.5")));
        exif.attachThumbnail();
        exif.save();

        assertTagsAndThumbnail(file);

        Exif again = new Exif(file);
        again.setDescription("saved again");
        again.save();

        assertTagsAndThumbnail(file);
        assertEquals("saved again", new Exif(file).getDescription());
    }

    /** A thumbnailed file given tags later keeps the thumbnail it had. */
    @Test
    public void specTagsSetLaterKeepTheThumbnail() throws IOException {
        File file = thumbnailed(picture(640, 480));

        Exif exif = new Exif(file);
        exif.setTags(new ExifTags.Builder()
                .set(ExifTags.Tag.IMAGE_TITLE, "Both")
                .set(ExifTags.Tag.TEMPERATURE, "21.5"));
        exif.save();

        assertTagsAndThumbnail(file);
    }

    /**
     * The file carries the tags the two tests above set and one declared thumbnail, in an Exif
     * segment that parses, fits its length field, and leaves the picture decodable.
     */
    private static void assertTagsAndThumbnail(File file) throws IOException {
        assertOneDeclaredThumbnail(file);
        Exif saved = new Exif(file);
        assertTrue(saved.hasThumbnail());
        assertEquals("Both", saved.getTag(ExifTags.Tag.IMAGE_TITLE));
        assertEquals("21500/1000", saved.getTag(ExifTags.Tag.TEMPERATURE));
        ExifSegment.Segment segment = ExifSegment.find(readAll(file));
        assertNotNull("the Exif segment should still parse", segment);
        assertTrue(segment.payloadLength <= ExifSegment.MAX_APP1_PAYLOAD);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        assertEquals(640, bounds.outWidth);
        assertEquals(480, bounds.outHeight);
    }

    /** A file with no picture in it to shrink saves without a thumbnail. */
    @Test
    public void aFileThatWillNotDecodeSavesWithoutAThumbnail() throws IOException {
        File file = File.createTempFile("undecodable", ".jpg");
        file.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(ExifMetadataTest.minimalJpeg());
        }

        Exif exif = new Exif(file);
        exif.attachThumbnail();
        exif.save();

        assertFalse(exif.hasThumbnail());
        assertFalse(new ExifInterface(file.getPath()).hasThumbnail());
    }

    /** With no tag giving the size, saving measures the picture itself. */
    @Test
    public void savingMeasuresAPictureNoTagDescribes() throws IOException {
        File file = picture(320, 240);

        new Exif(file).save();

        ExifInterface saved = new ExifInterface(file.getPath());
        assertEquals(320, saved.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0));
        assertEquals(240, saved.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0));
    }

    /** The file carries exactly one thumbnail, which says it is a JPEG and decodes. */
    private static void assertOneDeclaredThumbnail(File file) throws IOException {
        byte[] jpeg = readAll(file);
        assertEquals("thumbnails in the Exif block", 1, jpegsIn(jpeg));
        assertTrue("it says it is a JPEG", existing(jpeg).declared);
        ExifInterface saved = new ExifInterface(file.getPath());
        assertTrue(saved.hasThumbnail());
        assertNotNull("and it decodes", saved.getThumbnailBitmap());
    }

    /** The file, given a thumbnail the way a capture gets one. */
    private static File thumbnailed(File file) throws IOException {
        Exif exif = new Exif(file);
        exif.attachThumbnail();
        exif.save();
        assertTrue("the fixture should start with a thumbnail",
                new ExifInterface(file.getPath()).hasThumbnail());
        return file;
    }

    /** The thumbnail IFD1 names. */
    private static ExifThumbnail.Existing existing(byte[] jpeg) {
        ExifSegment.Segment segment = ExifSegment.find(jpeg);
        assertNotNull("no Exif block", segment);
        ExifThumbnail.Existing existing = ExifThumbnail.findExistingThumbnail(jpeg, segment);
        assertNotNull("no thumbnail", existing);
        return existing;
    }

    /** The orientation IFD1 gives the thumbnail. */
    private static int thumbnailOrientation(byte[] jpeg) {
        ExifSegment.Segment segment = ExifSegment.find(jpeg);
        int next = ExifSegment.nextPointerOffset(jpeg, segment, ExifSegment.ifd0(jpeg, segment));
        int ifd1 = segment.tiffOffset + (int) ExifSegment.u32(jpeg, next, segment.littleEndian);
        int entry = ExifSegment.findEntry(jpeg, segment, ifd1, TAG_ORIENTATION);
        assertTrue("IFD1 gives no orientation", entry >= 0);
        return ExifSegment.u16(jpeg, entry + 8, segment.littleEndian);
    }

    /** How many JPEGs begin inside the Exif block: a start-of-image marker, then any marker. */
    private static int jpegsIn(byte[] jpeg) {
        ExifSegment.Segment segment = ExifSegment.find(jpeg);
        int count = 0;
        for (int at = segment.tiffOffset; at + 2 < segment.end(); at++) {
            if ((jpeg[at] & 0xFF) == 0xFF && (jpeg[at + 1] & 0xFF) == 0xD8
                    && (jpeg[at + 2] & 0xFF) == 0xFF) {
                count++;
            }
        }
        return count;
    }

    private static byte[] readAll(File file) throws IOException {
        return Files.readAllBytes(file.toPath());
    }

    /** A JPEG of the given size, encoded from a gradient so it is not trivially compressible. */
    private static File picture(int width, int height) throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < height; y += 8) {
            for (int x = 0; x < width; x += 8) {
                bitmap.setPixel(x, y, Color.rgb(x % 256, y % 256, (x + y) % 256));
            }
        }
        File file = File.createTempFile("thumbnail", ".jpg");
        file.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
        }
        return file;
    }
}
