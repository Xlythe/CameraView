package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
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

/** A thumbnail encoded from a real picture, which needs real JPEG decoding and encoding. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 37)
public class ExifThumbnailEncodingTest {

    /** A picture with no thumbnail gets one no larger than the 160x120 the standard suggests. */
    @Test
    public void aPictureWithNoThumbnailGetsOne() throws IOException {
        File file = picture(1280, 960);
        new Exif(file).save();

        assertTrue(new Exif(file).attachThumbnail());

        ExifInterface saved = new ExifInterface(file.getPath());
        assertTrue(saved.hasThumbnail());
        Bitmap thumbnail = saved.getThumbnailBitmap();
        assertNotNull(thumbnail);
        assertTrue(thumbnail.getWidth() <= 160 && thumbnail.getHeight() <= 120);
    }

    /** A picture that already has one is left alone. */
    @Test
    public void aSecondThumbnailIsNotAdded() throws IOException {
        File file = picture(640, 480);
        new Exif(file).save();
        assertTrue(new Exif(file).attachThumbnail());

        assertFalse(new Exif(file).attachThumbnail());
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
