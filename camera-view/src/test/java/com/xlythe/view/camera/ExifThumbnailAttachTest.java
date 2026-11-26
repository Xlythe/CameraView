package com.xlythe.view.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Attaching a thumbnail to a file that has none.
 *
 * <p>Exif keeps a small copy of the picture so a gallery can draw a grid without decoding every
 * photograph, two of the three backends write none, and no Android API will add one. The segment
 * this writes is pinned by ExifThumbnailTest, and that a real capture comes out of the camera
 * with a thumbnail on it is pinned on a device - encoding one needs a decoder, which no unit test
 * has. What is pinned here is the other half of the contract: a capture that has already
 * succeeded is never failed by this, so every way of having nothing to write returns false
 * rather than throwing.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class ExifThumbnailAttachTest {
    private File file;

    @Before
    public void setUp() throws IOException {
        file = File.createTempFile("thumbnail", ".jpg");
        file.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(minimalJpeg());
        }
    }


    /** Exif read from a stream has no file to write into, and says so rather than throwing. */
    @Test
    public void aStreamHasNoFileToAttachTo() throws IOException {
        Exif exif = new Exif(new ByteArrayInputStream(minimalJpeg()));

        assertFalse(exif.attachThumbnail());
    }

    /** A file deleted between the capture and the thumbnail is not a failed capture. */
    @Test
    public void aFileThatHasGoneDoesNotFailTheCapture() throws IOException {
        Exif exif = new Exif(file);
        exif.save();
        assertTrue("deleted", file.delete());

        assertFalse(exif.attachThumbnail());
    }

    /** Nor is a file with no Exif block at all, which leaves nowhere to declare a thumbnail. */
    @Test
    public void aFileWithNoExifBlockIsLeftAlone() throws IOException {
        Exif exif = new Exif(file);

        assertFalse(exif.attachThumbnail());
    }



    /** The shortest JPEG ExifInterface will write tags into: a quantisation table and nothing. */
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
