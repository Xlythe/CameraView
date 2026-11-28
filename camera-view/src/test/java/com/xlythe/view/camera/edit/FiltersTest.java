package com.xlythe.view.camera.edit;

import static org.junit.Assert.assertEquals;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;

import com.xlythe.view.camera.gl.ColorMatrix;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * A still drawn with a filter comes out as the live filter's own arithmetic says, for each of
 * the filters the library makes.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 37)
public class FiltersTest {
    /** Colours chosen so no two channels agree. */
    private static final int[] COLORS = {Color.rgb(200, 40, 90), Color.rgb(10, 220, 130),
            Color.rgb(250, 250, 5), Color.rgb(64, 128, 192)};

    @Test
    public void everyFilterDrawsAsTheLiveOneComputes() {
        float[][] matrices = {ColorMatrix.grayscale(), ColorMatrix.sepia(),
                ColorMatrix.invert(), ColorMatrix.contrast(1.5f), ColorMatrix.brightness(0.2f),
                ColorMatrix.saturation(1.8f), ColorMatrix.tint(1f, 0.8f, 0.6f),
                ColorMatrix.concat(ColorMatrix.sepia(), ColorMatrix.contrast(1.2f))};
        for (float[] matrix : matrices) {
            for (int color : COLORS) {
                float[] expected = ColorMatrix.apply(matrix, new float[] {Color.red(color) / 255f,
                        Color.green(color) / 255f, Color.blue(color) / 255f, 1f});
                int drawn = draw(color, matrix);
                assertEquals(clamp(expected[0]), Color.red(drawn), 2);
                assertEquals(clamp(expected[1]), Color.green(drawn), 2);
                assertEquals(clamp(expected[2]), Color.blue(drawn), 2);
                assertEquals(255, Color.alpha(drawn));
            }
        }
    }

    @Test
    public void transparencyIsCarriedThrough() {
        Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint();
        paint.setColorFilter(new ColorMatrixColorFilter(Filters.toAndroid(ColorMatrix.invert())));
        Bitmap source = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.argb(0, 255, 255, 255));
        new Canvas(bitmap).drawBitmap(source, 0, 0, paint);
        assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)));
    }

    private static int draw(int color, float[] matrix) {
        Bitmap source = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        source.eraseColor(color);
        Bitmap target = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint();
        paint.setColorFilter(new ColorMatrixColorFilter(Filters.toAndroid(matrix)));
        new Canvas(target).drawBitmap(source, 0, 0, paint);
        return target.getPixel(0, 0);
    }

    private static int clamp(float channel) {
        return Math.round(Math.max(0f, Math.min(1f, channel)) * 255f);
    }
}
