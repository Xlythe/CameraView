package com.xlythe.view.camera.viewer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/** How AnimationView draws the platform's animated drawables. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AnimationViewTest {
    /** A view 100 by 200, the picture fitted into its middle band. */
    private static final int VIEW_WIDTH = 100;
    private static final int VIEW_HEIGHT = 200;
    private static final RectF FITTED = new RectF(0, 75, 100, 125);

    @Test
    public void aDrawableIsDrawnIntoItsFittedBounds() {
        Bitmap view = draw(new Picture(false));

        assertFitted(view);
    }

    /**
     * Android 9's AnimatedImageDrawable draws at its own size in the corner whatever its bounds
     * say, so it is fitted by the canvas instead.
     */
    @Test
    public void aDrawableThatIgnoresItsBoundsIsStillFitted() {
        Bitmap view = draw(new Picture(true));

        assertFitted(view);
    }

    @Test
    public void theDrawableIsLeftAtItsOwnSize() {
        Picture picture = new Picture(false);

        draw(picture);

        assertEquals(new Rect(0, 0, Picture.WIDTH, Picture.HEIGHT), picture.getBounds());
    }

    /**
     * Before Android 9 an animated GIF plays through the library's own decoder, a frame at a
     * time: the band along the top of its first frame, then the block its second frame adds.
     */
    @Test
    @Config(sdk = 27)
    public void aGifPlaysThroughTheLibrarysDecoderBeforeAndroid9() throws IOException {
        AnimationView view = gifView(GifTestFiles.everyDisposal());
        assertTrue(view.isAnimated());
        assertTrue(view.isPlaying());

        // 16x12 fitted into 160x120: each pixel of the GIF is 10 of the view.
        Bitmap first = drawView(view);
        assertEquals(0xFF1EC83C, first.getPixel(5, 5));
        assertEquals(0xFFE01E1E, first.getPixel(5, 115));
        assertEquals(0xFFE01E1E, first.getPixel(45, 35));

        // The first frame shows for 50 ms; the next is composed off the main thread.
        ShadowLooper.idleMainLooper(60, TimeUnit.MILLISECONDS);
        drawView(view);
        ShadowLooper.idleMainLooper();
        Bitmap second = drawView(view);
        assertEquals(0xFF1E5AFA, second.getPixel(45, 35));
        assertEquals(0xFF1EC83C, second.getPixel(5, 5));

        // Paused, it stays on its frame.
        assertTrue(view.pause());
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS);
        drawView(view);
        ShadowLooper.idleMainLooper();
        assertEquals(0xFF1E5AFA, drawView(view).getPixel(45, 35));
        view.release();
        assertFalse(view.isAnimated());
    }

    /** A GIF of one frame shows still. */
    @Test
    @Config(sdk = 27)
    public void aGifOfOneFrameShowsStill() throws IOException {
        AnimationView view = gifView(GifTestFiles.gif(2, 2, new int[] {0xFF0000, 0x00FF00},
                GifTestFiles.fill(0, 0, 2, 2, 1)));
        assertFalse(view.isAnimated());
        assertFalse(view.isPlaying());
    }

    /** An AnimationView on a GIF, laid out at 160x120, decoded on the calling thread. */
    private static AnimationView gifView(byte[] gif) throws IOException {
        File file = File.createTempFile("animation", ".gif");
        file.deleteOnExit();
        Files.write(file.toPath(), gif);
        AnimationView view = new AnimationView(ApplicationProvider.getApplicationContext(),
                Runnable::run);
        boolean[] loaded = new boolean[1];
        view.setImageLoadListener(new ImageLoadListener() {
            @Override
            public void onImageLoaded() {
                loaded[0] = true;
            }

            @Override
            public void onImageFailed() {}
        });
        view.measure(View.MeasureSpec.makeMeasureSpec(160, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 160, 120);
        view.setLocation(ImageLocation.of(file));
        ShadowLooper.idleMainLooper();
        assertTrue(loaded[0]);
        return view;
    }

    private static Bitmap drawView(View view) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(),
                Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        return bitmap;
    }

    private static Bitmap draw(Drawable drawable) {
        Bitmap view = Bitmap.createBitmap(VIEW_WIDTH, VIEW_HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(view);
        canvas.drawColor(Color.BLACK);
        AnimationView.drawFitted(canvas, drawable, FITTED);
        return view;
    }

    /** The picture's red top-left quarter and blue rest fill the fitted band, and nothing else. */
    private static void assertFitted(Bitmap view) {
        // Inside: the top-left quarter of the band is red, the far corner blue.
        assertEquals(Color.RED, view.getPixel(10, 80));
        assertEquals(Color.BLUE, view.getPixel(90, 120));
        assertEquals(Color.BLUE, view.getPixel(90, 80));
        // Outside: the band above and below is untouched, the corner included.
        assertEquals(Color.BLACK, view.getPixel(5, 5));
        assertEquals(Color.BLACK, view.getPixel(50, 60));
        assertEquals(Color.BLACK, view.getPixel(50, 140));
    }

    /**
     * A 40 by 20 picture, red in its top-left quarter and blue elsewhere, drawn into its bounds or,
     * as Android 9's animated drawable is, at its own size from the corner.
     */
    private static final class Picture extends Drawable {
        static final int WIDTH = 40;
        static final int HEIGHT = 20;

        /** Whether it draws at its own size from the corner, whatever its bounds. */
        private final boolean mIgnoresBounds;
        private final Paint mPaint = new Paint();

        Picture(boolean ignoresBounds) {
            mIgnoresBounds = ignoresBounds;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect area = mIgnoresBounds ? new Rect(0, 0, WIDTH, HEIGHT) : getBounds();
            mPaint.setColor(Color.BLUE);
            canvas.drawRect(area, mPaint);
            mPaint.setColor(Color.RED);
            canvas.drawRect(area.left, area.top, area.left + area.width() / 2f,
                    area.top + area.height() / 2f, mPaint);
        }

        @Override
        public int getIntrinsicWidth() {
            return WIDTH;
        }

        @Override
        public int getIntrinsicHeight() {
            return HEIGHT;
        }

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {}

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }
}
