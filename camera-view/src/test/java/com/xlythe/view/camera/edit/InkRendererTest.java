package com.xlythe.view.camera.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.Collections;

/** Strokes drawn for real, and read back pixel by pixel. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 37)
public class InkRendererTest {
    @Test
    public void aTranslucentStrokeDoesNotDarkenWhereItsSegmentsOverlap() {
        // A half-transparent marker doubling back on itself, many short segments.
        Brush marker = new Brush.Builder().setColor(Color.BLUE).setOpacity(0.5f).setWidth(20)
                .setPressureResponse(1, 1, 1).build();
        Stroke.Builder builder = new Stroke.Builder(marker, "m");
        for (int i = 0; i <= 40; i++) {
            builder.add(10 + i * 2, 50, Float.NaN, Float.NaN, Float.NaN, i);
        }
        for (int i = 40; i >= 0; i--) {
            builder.add(10 + i * 2, 52, Float.NaN, Float.NaN, Float.NaN, 80 - i);
        }
        Bitmap bitmap = draw(builder.build(), 100, 100);
        int joint = Color.alpha(bitmap.getPixel(50, 51));
        assertEquals("one coat of half-transparent ink", 128, joint, 3);
    }

    @Test
    public void pressureChangesTheWidth() {
        Brush pen = Brush.PEN.withColor(Color.BLACK).withWidth(20);
        Stroke.Builder builder = new Stroke.Builder(pen, "p");
        builder.add(10, 50, 0f, Float.NaN, Float.NaN, 0);
        builder.add(50, 50, 0f, Float.NaN, Float.NaN, 10);
        builder.add(90, 50, 1f, Float.NaN, Float.NaN, 20);
        Bitmap bitmap = draw(builder.build(), 100, 100);
        // At no pressure the pen is a quarter of its width: 5 across, so 4 below is clear.
        assertEquals(0, Color.alpha(bitmap.getPixel(30, 55)));
        // At full pressure, 20 across.
        assertEquals(255, Color.alpha(bitmap.getPixel(85, 58)));
    }

    @Test
    public void aTapIsADot() {
        Stroke.Builder builder = new Stroke.Builder(Brush.PEN.withWidth(10), "dot");
        builder.add(50, 50, Float.NaN, Float.NaN, Float.NaN, 0);
        Bitmap bitmap = draw(builder.build(), 100, 100);
        assertEquals(255, Color.alpha(bitmap.getPixel(50, 50)));
        assertEquals(0, Color.alpha(bitmap.getPixel(50, 58)));
    }

    @Test
    public void aStampedBrushGrainsTheSameEveryTime() {
        Stroke stroke = line(Brush.CHARCOAL.withWidth(16));
        Bitmap first = draw(stroke, 100, 100);
        Bitmap second = draw(stroke, 100, 100);
        int[] a = pixels(first);
        int[] b = pixels(second);
        assertTrue(Arrays.equals(a, b));
        int covered = 0;
        int partial = 0;
        for (int pixel : a) {
            if (Color.alpha(pixel) > 0) {
                covered++;
                if (Color.alpha(pixel) < 250) {
                    partial++;
                }
            }
        }
        assertTrue("it draws", covered > 200);
        assertTrue("its grain lets the paper through", partial > covered / 4);
    }

    @Test
    public void anEraserClearsInkNotWhatItIsDrawnOver() {
        Bitmap bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        Stroke ink = line(Brush.PEN.withColor(Color.RED).withWidth(10));
        Stroke.Builder eraser = new Stroke.Builder(Brush.ERASER.withWidth(10), "e");
        eraser.add(50, 30, Float.NaN, Float.NaN, Float.NaN, 0);
        eraser.add(50, 70, Float.NaN, Float.NaN, Float.NaN, 10);
        InkRenderer.draw(canvas, Arrays.asList(ink, eraser.build()), null);
        assertEquals(Color.RED, bitmap.getPixel(20, 50));
        assertEquals("the white beneath shows", Color.WHITE, bitmap.getPixel(50, 50));
    }

    @Test
    public void aLeanedPencilGoesPale() {
        Brush pencil = new Brush.Builder(Brush.PENCIL).setSpacing(0).setGrain(Brush.GRAIN_NONE, 0,
                1, 0).setOpacity(1).setColor(Color.BLACK).setWidth(10).build();
        Stroke.Builder upright = new Stroke.Builder(pencil, "u");
        Stroke.Builder leaned = new Stroke.Builder(pencil, "l");
        for (int i = 0; i < 5; i++) {
            upright.add(10 + i * 20, 50, 1f, 0.1f, 0f, i * 8L);
            leaned.add(10 + i * 20, 50, 1f, 60f, 0f, i * 8L);
        }
        int uprightAlpha = Color.alpha(draw(upright.build(), 100, 100).getPixel(50, 50));
        int leanedAlpha = Color.alpha(draw(leaned.build(), 100, 100).getPixel(50, 50));
        assertTrue(uprightAlpha + " > " + leanedAlpha, uprightAlpha > leanedAlpha + 60);
    }

    private static Stroke line(Brush brush) {
        Stroke.Builder builder = new Stroke.Builder(brush, "line");
        for (int i = 0; i <= 8; i++) {
            builder.add(10 + i * 10, 50, 1f, Float.NaN, Float.NaN, i * 8L);
        }
        return builder.build();
    }

    private static Bitmap draw(Stroke stroke, int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        InkRenderer.draw(new Canvas(bitmap), Collections.singletonList(stroke), null);
        return bitmap;
    }

    private static int[] pixels(Bitmap bitmap) {
        int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(),
                bitmap.getHeight());
        return pixels;
    }
}
