package com.xlythe.view.camera.edit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.RectF;

import com.xlythe.view.camera.gl.ColorMatrix;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 37)
public class PictureEditsTest {
    @Test
    public void nothingIsChangedUntilSomethingIsSet() {
        PictureEdits edits = new PictureEdits.Builder().build();
        assertEquals(0, edits.getRotationDegrees());
        assertFalse(edits.isFlippedHorizontally());
        assertFalse(edits.isFlippedVertically());
        assertEquals(0f, edits.getStraightenDegrees(), 0f);
        assertNull(edits.getCrop());
        assertNull(edits.getColorMatrix());
        assertNull(edits.getOverlay());
        assertFalse(edits.isOverlayFiltered());
        assertTrue(edits.isEmpty());
        assertTrue(edits.changesOnlyOrientation());
        assertTrue(edits.keepsFraming());
    }

    @Test
    public void turnsAreQuarterTurnsCountedClockwise() {
        assertEquals(270, new PictureEdits.Builder().setRotationDegrees(-90).build()
                .getRotationDegrees());
        assertEquals(90, new PictureEdits.Builder().setRotationDegrees(450).build()
                .getRotationDegrees());
        assertThrows(IllegalArgumentException.class,
                () -> new PictureEdits.Builder().setRotationDegrees(45));
    }

    @Test
    public void turnsAndFlipsChangeOnlyTheOrientation() {
        PictureEdits edits = new PictureEdits.Builder()
                .setRotationDegrees(90)
                .setFlippedHorizontally(true)
                .setFlippedVertically(true)
                .build();
        assertFalse(edits.isEmpty());
        assertTrue(edits.changesOnlyOrientation());
        assertTrue(edits.keepsFraming());
    }

    @Test
    public void aCropOrStraighteningChangesTheFraming() {
        PictureEdits cropped = new PictureEdits.Builder()
                .setCrop(new RectF(0.1f, 0.2f, 0.9f, 0.8f)).build();
        assertFalse(cropped.keepsFraming());
        assertFalse(cropped.changesOnlyOrientation());
        assertEquals(new RectF(0.1f, 0.2f, 0.9f, 0.8f), cropped.getCrop());

        PictureEdits straightened = new PictureEdits.Builder().setStraightenDegrees(-3).build();
        assertFalse(straightened.keepsFraming());
        assertEquals(-3f, straightened.getStraightenDegrees(), 0f);
    }

    @Test
    public void aCropOfEverythingIsNoCrop() {
        assertNull(new PictureEdits.Builder().setCrop(new RectF(0, 0, 1, 1)).build().getCrop());
        assertNull(new PictureEdits.Builder().setCrop(null).build().getCrop());
    }

    @Test
    public void aCropMustBeWithinThePicture() {
        assertThrows(IllegalArgumentException.class,
                () -> new PictureEdits.Builder().setCrop(new RectF(-0.1f, 0, 1, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> new PictureEdits.Builder().setCrop(new RectF(0, 0, 1.1f, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> new PictureEdits.Builder().setCrop(new RectF(0.5f, 0, 0.5f, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> new PictureEdits.Builder().setCrop(new RectF(0, 0.6f, 1, 0.4f)));
    }

    @Test
    public void straighteningIsASmallAngle() {
        assertThrows(IllegalArgumentException.class,
                () -> new PictureEdits.Builder().setStraightenDegrees(46));
        assertThrows(IllegalArgumentException.class,
                () -> new PictureEdits.Builder().setStraightenDegrees(Float.NaN));
        assertEquals(0f, new PictureEdits.Builder().setStraightenDegrees(-0f).build()
                .getStraightenDegrees(), 0f);
        assertTrue(new PictureEdits.Builder().setStraightenDegrees(-0f).build().keepsFraming());
    }

    @Test
    public void aColorMatrixIsCopiedAndTheIdentityIsNone() {
        float[] sepia = ColorMatrix.sepia();
        PictureEdits edits = new PictureEdits.Builder().setColorMatrix(sepia).build();
        sepia[0] = 99;
        assertArrayEquals(ColorMatrix.sepia(), edits.getColorMatrix(), 0f);
        edits.getColorMatrix()[0] = 99;
        assertArrayEquals(ColorMatrix.sepia(), edits.getColorMatrix(), 0f);
        assertFalse(edits.changesOnlyOrientation());

        assertNull(new PictureEdits.Builder().setColorMatrix(ColorMatrix.identity()).build()
                .getColorMatrix());
        assertThrows(IllegalArgumentException.class,
                () -> new PictureEdits.Builder().setColorMatrix(new float[20]));
    }

    @Test
    public void anOverlayIsKeptAsGiven() {
        Bitmap overlay = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        PictureEdits edits = new PictureEdits.Builder()
                .setOverlay(overlay)
                .setOverlayFiltered(true)
                .build();
        assertSame(overlay, edits.getOverlay());
        assertTrue(edits.isOverlayFiltered());
        assertFalse(edits.changesOnlyOrientation());
        assertTrue(edits.keepsFraming());
    }

    @Test
    public void aBuilderStartsFromOtherEdits() {
        PictureEdits edits = new PictureEdits.Builder()
                .setRotationDegrees(180)
                .setFlippedVertically(true)
                .setStraightenDegrees(2)
                .setCrop(new RectF(0, 0, 0.5f, 0.5f))
                .setColorMatrix(ColorMatrix.grayscale())
                .build();
        PictureEdits copy = new PictureEdits.Builder(edits).build();
        assertEquals(edits, copy);
        assertEquals(edits.hashCode(), copy.hashCode());
        assertEquals(edits.toString(), copy.toString());

        PictureEdits changed = new PictureEdits.Builder(edits).setFlippedHorizontally(true)
                .build();
        assertNotEquals(edits, changed);
        assertNotEquals(edits, null);
        assertNotEquals(edits, "edits");
    }

    @Test
    public void theCropReturnedIsACopy() {
        PictureEdits edits = new PictureEdits.Builder()
                .setCrop(new RectF(0, 0, 0.5f, 0.5f)).build();
        edits.getCrop().set(0, 0, 1, 1);
        assertEquals(new RectF(0, 0, 0.5f, 0.5f), edits.getCrop());
    }
}
