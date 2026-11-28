package com.xlythe.view.camera.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.graphics.RectF;

import com.xlythe.view.camera.MediaTags;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37)
public class PanoramasTest {
    /** A 3000x1000 strip in the middle of a 6000x3000 sphere, lower than its middle. */
    private static MediaTags.Panorama strip() {
        return new MediaTags.Panorama.Builder()
                .setProjectionType(MediaTags.Panorama.PROJECTION_EQUIRECTANGULAR)
                .setUsePanoramaViewer(true)
                .setPoseHeadingDegrees(45)
                .setFullPanoWidthPixels(6000)
                .setFullPanoHeightPixels(3000)
                .setCroppedAreaImageWidthPixels(3000)
                .setCroppedAreaImageHeightPixels(1000)
                .setCroppedAreaLeftPixels(1500)
                .setCroppedAreaTopPixels(1200)
                .setLargestValidInteriorRectLeft(100)
                .setLargestValidInteriorRectTop(50)
                .setLargestValidInteriorRectWidth(2800)
                .setLargestValidInteriorRectHeight(900)
                .setInitialViewHeadingDegrees(90)
                .build();
    }

    @Test
    public void aCropMovesWhereThePictureSitsOnTheSphere() {
        PictureEdits edits = new PictureEdits.Builder()
                .setCrop(new RectF(0.5f, 0.5f, 1f, 1f)).build();
        MediaTags.Panorama edited = Panoramas.edited(strip(), 3000, 1000, edits, 1500, 500);
        assertNotNull(edited);
        assertEquals(Integer.valueOf(6000), edited.getFullPanoWidthPixels());
        assertEquals(Integer.valueOf(3000), edited.getFullPanoHeightPixels());
        assertEquals(Integer.valueOf(3000), edited.getCroppedAreaLeftPixels());
        assertEquals(Integer.valueOf(1700), edited.getCroppedAreaTopPixels());
        assertEquals(Integer.valueOf(1500), edited.getCroppedAreaImageWidthPixels());
        assertEquals(Integer.valueOf(500), edited.getCroppedAreaImageHeightPixels());
        // The valid rectangle is cut down to the part kept, measured from the crop.
        assertEquals(Integer.valueOf(0), edited.getLargestValidInteriorRectLeft());
        assertEquals(Integer.valueOf(0), edited.getLargestValidInteriorRectTop());
        assertEquals(Integer.valueOf(1400), edited.getLargestValidInteriorRectWidth());
        assertEquals(Integer.valueOf(450), edited.getLargestValidInteriorRectHeight());
        // What is not about position is kept.
        assertEquals(MediaTags.Panorama.PROJECTION_EQUIRECTANGULAR, edited.getProjectionType());
        assertEquals(Boolean.TRUE, edited.getUsePanoramaViewer());
        assertEquals(45f, edited.getPoseHeadingDegrees(), 0f);
        assertEquals(Integer.valueOf(90), edited.getInitialViewHeadingDegrees());
    }

    @Test
    public void aSmallerPictureScalesTheSphereWithIt() {
        // The whole strip, written at half size.
        MediaTags.Panorama edited = Panoramas.edited(strip(), 3000, 1000,
                new PictureEdits.Builder().build(), 1500, 500);
        assertNotNull(edited);
        assertEquals(Integer.valueOf(3000), edited.getFullPanoWidthPixels());
        assertEquals(Integer.valueOf(1500), edited.getFullPanoHeightPixels());
        assertEquals(Integer.valueOf(750), edited.getCroppedAreaLeftPixels());
        assertEquals(Integer.valueOf(600), edited.getCroppedAreaTopPixels());
        assertEquals(Integer.valueOf(50), edited.getLargestValidInteriorRectLeft());
        assertEquals(Integer.valueOf(1400), edited.getLargestValidInteriorRectWidth());
    }

    @Test
    public void metadataInOtherPixelsIsMeasuredInItsOwn() {
        // The metadata describes the stored 1500x500 picture as 3000x1000.
        PictureEdits edits = new PictureEdits.Builder()
                .setCrop(new RectF(0f, 0f, 0.5f, 1f)).build();
        MediaTags.Panorama edited = Panoramas.edited(strip(), 1500, 500, edits, 750, 500);
        assertNotNull(edited);
        assertEquals(Integer.valueOf(3000), edited.getFullPanoWidthPixels());
        assertEquals(Integer.valueOf(750), edited.getCroppedAreaLeftPixels());
        assertEquals(Integer.valueOf(750), edited.getCroppedAreaImageWidthPixels());
    }

    @Test
    public void aCropOutsideTheValidRectangleDropsIt() {
        PictureEdits edits = new PictureEdits.Builder()
                .setCrop(new RectF(0f, 0f, 0.02f, 0.02f)).build();
        MediaTags.Panorama edited = Panoramas.edited(strip(), 3000, 1000, edits, 60, 20);
        assertNotNull(edited);
        assertNull(edited.getLargestValidInteriorRectLeft());
        assertNull(edited.getLargestValidInteriorRectHeight());
    }

    @Test
    public void aWholeSphereWithOnlyAProjectionGetsItsSizeSaid() {
        MediaTags.Panorama sphere = new MediaTags.Panorama.Builder()
                .setProjectionType(MediaTags.Panorama.PROJECTION_EQUIRECTANGULAR).build();
        PictureEdits edits = new PictureEdits.Builder()
                .setCrop(new RectF(0f, 0.25f, 1f, 0.75f)).build();
        MediaTags.Panorama edited = Panoramas.edited(sphere, 4000, 2000, edits, 4000, 1000);
        assertNotNull(edited);
        assertEquals(Integer.valueOf(4000), edited.getFullPanoWidthPixels());
        assertEquals(Integer.valueOf(2000), edited.getFullPanoHeightPixels());
        assertEquals(Integer.valueOf(0), edited.getCroppedAreaLeftPixels());
        assertEquals(Integer.valueOf(500), edited.getCroppedAreaTopPixels());
    }

    @Test
    public void aTurnedFlippedOrStraightenedPanoramaIsNoLongerOne() {
        assertNull(Panoramas.edited(strip(), 3000, 1000,
                new PictureEdits.Builder().setRotationDegrees(90).build(), 1000, 3000));
        assertNull(Panoramas.edited(strip(), 3000, 1000,
                new PictureEdits.Builder().setFlippedHorizontally(true).build(), 3000, 1000));
        assertNull(Panoramas.edited(strip(), 3000, 1000,
                new PictureEdits.Builder().setStraightenDegrees(1).build(), 3000, 1000));
        // Both flips are a half turn, which is no more a panorama than a quarter one.
        assertNull(Panoramas.edited(strip(), 3000, 1000, new PictureEdits.Builder()
                .setFlippedHorizontally(true).setFlippedVertically(true).build(), 3000, 1000));
    }
}
