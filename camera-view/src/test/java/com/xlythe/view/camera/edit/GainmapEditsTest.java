package com.xlythe.view.camera.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * The arithmetic of carrying a gain map through an edit. Off a device there is no native gain
 * map to make, so the device suite carries a real one.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 37)
public class GainmapEditsTest {
    @Test
    public void aGainOfOneSitsWhereTheLogScaleSays() {
        // Gains from 1 to 4: a gain of one is the bottom of the range.
        assertEquals(0, GainmapEdits.neutral(1f, 4f, 1f));
        // From a quarter to 4: the middle.
        assertEquals(128, GainmapEdits.neutral(0.25f, 4f, 1f));
        // Raised to the map's gamma.
        assertEquals(Math.round(Math.pow(0.5, 2.2) * 255), GainmapEdits.neutral(0.25f, 4f, 2.2f));
        // A range that only brightens from 2 never reaches one; it clamps.
        assertEquals(0, GainmapEdits.neutral(2f, 8f, 1f));
        // A range with no width has nowhere to put it.
        assertEquals(0, GainmapEdits.neutral(2f, 2f, 1f));
    }

    @Test
    public void whatCoversThePicturePullsTheGainTowardsNeutral() {
        Bitmap single = Bitmap.createBitmap(3, 1, Bitmap.Config.ALPHA_8);
        single.eraseColor(Color.argb(200, 0, 0, 0));
        Bitmap coverage = Bitmap.createBitmap(3, 1, Bitmap.Config.ALPHA_8);
        coverage.setPixel(1, 0, Color.argb(255, 0, 0, 0));
        coverage.setPixel(2, 0, Color.argb(128, 0, 0, 0));
        GainmapEdits.neutralize(single, coverage, new int[] {10, 10, 10});
        assertEquals(200, Color.alpha(single.getPixel(0, 0)));
        assertEquals(10, Color.alpha(single.getPixel(1, 0)));
        assertEquals(105, Color.alpha(single.getPixel(2, 0)), 1);

        Bitmap color = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888);
        color.eraseColor(Color.rgb(200, 100, 50));
        Bitmap covered = Bitmap.createBitmap(2, 1, Bitmap.Config.ALPHA_8);
        covered.setPixel(1, 0, Color.argb(255, 0, 0, 0));
        GainmapEdits.neutralize(color, covered, new int[] {0, 20, 40});
        assertEquals(Color.rgb(200, 100, 50), color.getPixel(0, 0));
        assertEquals(Color.rgb(0, 20, 40), color.getPixel(1, 0));
    }

    @Test
    public void aPictureWithoutAGainMapGetsNone() {
        Bitmap picture = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        Bitmap output = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        GainmapEdits.carry(picture, output, new Matrix(), null, null);
        assertTrue(!output.hasGainmap());
    }
}
