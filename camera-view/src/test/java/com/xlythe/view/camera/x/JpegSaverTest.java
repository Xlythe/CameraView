package com.xlythe.view.camera.x;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.graphics.Rect;
import android.os.Build;

import androidx.camera.core.ImageInfo;
import androidx.camera.core.ImageProxy;
import androidx.exifinterface.media.ExifInterface;

import com.xlythe.view.camera.Exif;
import com.xlythe.view.camera.ExifCaptureParameters;
import com.xlythe.view.camera.ExifFixtures;
import com.xlythe.view.camera.ExifMetadataTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * The JPEG half of a CameraX RAW_JPEG capture, which the library writes itself rather than
 * through CameraX, and so has to tag itself.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class JpegSaverTest {
    /** The size the fake image claims, which nothing crops. */
    private static final int SIZE = 16;

    /** The device, the app and how the picture was taken, as every other backend's JPEG has. */
    @Test
    public void theJpegCarriesWhatEveryOtherJpegDoes() throws IOException {
        Context context = RuntimeEnvironment.getApplication();
        File file = new File(context.getCacheDir(), "IMG_1.jpg");
        JpegSaver saver = new JpegSaver(context, image(ExifMetadataTest.minimalJpeg()),
                90 /* quality */, false /* mirrored */, file,
                new ExifCaptureParameters().setZoomRatio(1f));

        assertTrue(saver.doInBackground());

        ExifInterface saved = new ExifInterface(file.getAbsolutePath());
        assertEquals(Build.MANUFACTURER, saved.getAttribute(ExifInterface.TAG_MAKE));
        assertNotNull("the app is the software", saved.getAttribute(ExifInterface.TAG_SOFTWARE));
        assertEquals("how it was taken", 1.0,
                saved.getAttributeDouble(ExifInterface.TAG_DIGITAL_ZOOM_RATIO, 0), 0.0001);
    }

    /**
     * A JPEG whose Exif has no room for the camera's own tags is still written and reported,
     * with the rest of its metadata.
     */
    @Test
    public void aJpegIsKeptWhenItsTagsDoNotFit() throws IOException {
        Context context = RuntimeEnvironment.getApplication();
        File file = new File(context.getCacheDir(), "IMG_2.jpg");
        JpegSaver saver = new JpegSaver(context, image(ExifFixtures.overfullCapture()),
                90 /* quality */, false /* mirrored */, file, new ExifCaptureParameters());

        assertTrue(saver.doInBackground());

        assertTrue(file.isFile());
        Exif saved = new Exif(file);
        assertNotNull(saved.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL));
        assertNull(saved.getTag(ExifFixtures.TAG_UNDEFINED_BY_ANY_STANDARD));
    }

    /** An uncropped, upright image holding these bytes as its JPEG. */
    private static ImageProxy image(byte[] jpeg) {
        ImageProxy.PlaneProxy plane = mock(ImageProxy.PlaneProxy.class);
        when(plane.getBuffer()).thenReturn(ByteBuffer.wrap(jpeg));
        ImageInfo info = mock(ImageInfo.class);
        when(info.getRotationDegrees()).thenReturn(0);
        ImageProxy image = mock(ImageProxy.class);
        when(image.getPlanes()).thenReturn(new ImageProxy.PlaneProxy[] {plane});
        when(image.getCropRect()).thenReturn(new Rect(0, 0, SIZE, SIZE));
        when(image.getWidth()).thenReturn(SIZE);
        when(image.getHeight()).thenReturn(SIZE);
        when(image.getImageInfo()).thenReturn(info);
        return image;
    }
}
