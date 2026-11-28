package com.xlythe.view.camera.viewer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.widget.FrameLayout;

import androidx.exifinterface.media.ExifInterface;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Random;

/**
 * The large-image viewer, over pictures that decode into solid tiles: placing, zooming, panning
 * and, above all, the memory it holds while doing so.
 */
@RunWith(RobolectricTestRunner.class)
public class LargeImageViewTest {
    /** A phone screen. */
    private static final int VIEW_WIDTH = 1080;
    private static final int VIEW_HEIGHT = 2400;

    /** A 200 MP picture: 16384 x 12288. */
    private static final int HUGE_WIDTH = 16384;
    private static final int HUGE_HEIGHT = 12288;

    private Activity mActivity;
    private FrameLayout mParent;
    private Canvas mCanvas;
    private int mLoaded;
    private int mFailed;

    @Before
    public void setUp() {
        mActivity = Robolectric.buildActivity(Activity.class).setup().get();
        mParent = new FrameLayout(mActivity);
        mActivity.setContentView(mParent);
        mCanvas = new Canvas(Bitmap.createBitmap(VIEW_WIDTH, VIEW_HEIGHT, Bitmap.Config.ARGB_8888));
        mLoaded = 0;
        mFailed = 0;
    }

    /**
     * A 200 MP picture, zoomed from all the way out to all the way in and panned all over,
     * never has more than the stated budget decoded at once: five times the view's pixels.
     */
    @Test
    public void a200MegapixelPictureStaysWithinTheBudget() {
        FakeRegionSource source = new FakeRegionSource(HUGE_WIDTH, HUGE_HEIGHT, Color.RED);
        LargeImageView view = show(new LargeImageView(mActivity), source,
                ExifInterface.ORIENTATION_NORMAL, null);
        long budget = 5L * VIEW_WIDTH * VIEW_HEIGHT;
        assertEquals(budget, view.getBudgetPixels());
        assertEquals(1, mLoaded);

        // The low-resolution copy is no larger than the view.
        FakeRegionSource.Decode base = source.decodes().get(0);
        assertEquals(16, base.sampleSize);
        assertEquals(HUGE_WIDTH, base.region.width());

        Viewport viewport = view.mViewport;
        Random random = new Random(1);
        float scale = viewport.getMinScale();
        while (scale <= viewport.getMaxScale()) {
            viewport.scaleTo(scale, VIEW_WIDTH / 2f, VIEW_HEIGHT / 2f);
            for (int i = 0; i < 4; i++) {
                viewport.moveTo(-random.nextFloat() * viewport.panRangeX(),
                        -random.nextFloat() * viewport.panRangeY());
                settle(view, source);
                assertTrue("Holding " + source.livePixels() + " at scale " + scale,
                        source.livePixels() <= budget);
                // Tiles at the detail the scale needs, or one level coarser when that will not
                // fit, are what was asked for last.
                if (Tiles.sampleSize(scale) < 16) {
                    int asked = source.decodes().get(source.decodeCount() - 1).sampleSize;
                    assertTrue("Asked for 1/" + asked + " at scale " + scale,
                            asked == Tiles.sampleSize(scale)
                                    || asked == 2 * Tiles.sampleSize(scale));
                    assertTrue(view.getTileCount() > 0);
                }
            }
            scale *= 1.37f;
        }
        assertTrue(source.peakLivePixels() <= budget);
        assertTrue(view.getPeakPixels() <= budget);
        // It did hold tiles, rather than staying within the budget by showing nothing.
        assertTrue(source.peakLivePixels() > budget / 4);
    }

    /** At its sharpest the view decodes only what is on screen, at full detail. */
    @Test
    public void zoomedAllTheWayInOnlyTheScreenIsDecoded() {
        FakeRegionSource source = new FakeRegionSource(HUGE_WIDTH, HUGE_HEIGHT, Color.RED);
        LargeImageView view = show(new LargeImageView(mActivity), source,
                ExifInterface.ORIENTATION_NORMAL, null);
        view.mViewport.scaleTo(1f, VIEW_WIDTH / 2f, VIEW_HEIGHT / 2f);
        settle(view, source);

        List<FakeRegionSource.Decode> decodes = source.decodes();
        FakeRegionSource.Decode last = decodes.get(decodes.size() - 1);
        assertEquals(1, last.sampleSize);
        // The screen at 1:1 is 1080 x 2400, which takes 3 x 5 tiles, or 4 x 6 off the grid.
        assertTrue(view.getTileCount(1) >= 15 && view.getTileCount(1) <= 24);
    }

    /** Leaving the window lets go of every pixel and the file; coming back shows it again. */
    @Test
    public void detachingReleasesEverything() {
        FakeRegionSource source = new FakeRegionSource(HUGE_WIDTH, HUGE_HEIGHT, Color.RED);
        LargeImageView view = show(new LargeImageView(mActivity), source,
                ExifInterface.ORIENTATION_NORMAL, null);
        view.mViewport.scaleTo(0.5f, 0, 0);
        settle(view, source);
        assertTrue(source.livePixels() > 0);

        mParent.removeView(view);
        idle();
        assertEquals(0, source.livePixels());
        assertTrue(source.isClosed());
        assertEquals(0, view.getHeldPixels());

        // Opened again from the file, as a new source.
        FakeRegionSource reopened = new FakeRegionSource(HUGE_WIDTH, HUGE_HEIGHT, Color.RED);
        view.setOpener((context, location) ->
                new ImageSources.Opened(reopened, ExifInterface.ORIENTATION_NORMAL, null));
        mParent.addView(view, new FrameLayout.LayoutParams(VIEW_WIDTH, VIEW_HEIGHT));
        idle();
        view.layout(0, 0, VIEW_WIDTH, VIEW_HEIGHT);
        idle();
        assertTrue(view.getHeldPixels() > 0);
        // Reported once, not again for the same picture.
        assertEquals(1, mLoaded);
    }

    /** A new picture throws out the old one's tiles, and late tiles for it are dropped. */
    @Test
    public void aNewPictureReplacesTheOld() {
        FakeRegionSource first = new FakeRegionSource(HUGE_WIDTH, HUGE_HEIGHT, Color.RED);
        final FakeRegionSource second = new FakeRegionSource(4000, 3000, Color.BLUE);
        LargeImageView view = show(new LargeImageView(mActivity), first,
                ExifInterface.ORIENTATION_NORMAL, null);
        view.mViewport.scaleTo(0.5f, 0, 0);
        settle(view, first);

        view.setOpener((context, location) ->
                new ImageSources.Opened(second, ExifInterface.ORIENTATION_NORMAL, null));
        view.setFile(new File("second.jpg"));
        idle();
        assertEquals(0, first.livePixels());
        assertTrue(first.isClosed());
        assertTrue(second.livePixels() > 0);
        assertEquals(2, mLoaded);
        // The same picture again changes nothing.
        int decodes = second.decodeCount();
        view.setFile(new File("second.jpg"));
        idle();
        assertEquals(decodes, second.decodeCount());

        view.setFile(null);
        idle();
        assertEquals(0, second.livePixels());
    }

    /** A picture that cannot be opened, or whose copy cannot be decoded, is reported. */
    @Test
    public void reportsAFailure() {
        LargeImageView view = new LargeImageView(mActivity);
        view.setExecutor(Runnable::run);
        view.setImageLoadListener(listener());
        view.setOpener((context, location) -> {
            throw new IOException("Not a picture");
        });
        attach(view);
        view.setFile(new File("missing.jpg"));
        idle();
        assertEquals(1, mFailed);
        assertEquals(0, mLoaded);

        FakeRegionSource broken = new FakeRegionSource(100, 100, Color.RED);
        broken.setFailing(true);
        view.setOpener((context, location) ->
                new ImageSources.Opened(broken, ExifInterface.ORIENTATION_NORMAL, null));
        view.setFile(new File("broken.jpg"));
        idle();
        assertEquals(2, mFailed);
    }

    /** A picture turned a quarter is laid out turned: tall rather than wide. */
    @Test
    public void respectsTheOrientation() {
        FakeRegionSource source = new FakeRegionSource(4000, 1000, Color.RED);
        LargeImageView view = show(new LargeImageView(mActivity), source,
                ExifInterface.ORIENTATION_ROTATE_90, null);

        // Upright it is 1000 x 4000, so it fits by its height.
        assertEquals((float) VIEW_HEIGHT / 4000, view.mViewport.getScale(), 1e-4f);
        view.mViewport.scaleTo(1f, 0, 0);
        settle(view, source);
        // The top-left of the view shows the upright top-left, which was stored at the
        // bottom-left: its tiles are at the stored picture's left, and its last rows.
        boolean bottomLeft = false;
        for (FakeRegionSource.Decode decode : source.decodes()) {
            if (decode.sampleSize == 1 && decode.region.left == 0 && decode.region.bottom == 1000) {
                bottomLeft = true;
            }
        }
        assertTrue(bottomLeft);
    }

    /** Drags pan, pinches zoom, flings carry on, double taps zoom in and back out. */
    @Test
    public void gesturesMoveThePicture() {
        FakeRegionSource source = new FakeRegionSource(8000, 6000, Color.RED);
        LargeImageView view = show(new LargeImageView(mActivity), source,
                ExifInterface.ORIENTATION_NORMAL, null);
        Viewport viewport = view.mViewport;
        float min = viewport.getMinScale();

        // Pinch out about the middle.
        pinch(view, 400, 600);
        assertTrue(viewport.getScale() > min * 1.3f);

        // Drag left: the picture follows the finger.
        float x = viewport.getX();
        drag(view, 600, 1200, 300, 1200);
        assertTrue(viewport.getX() < x);

        // Fling back the other way: it carries on after the finger lifts.
        x = viewport.getX();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        fling(view);
        float lifted = viewport.getX();
        assertTrue(lifted > x);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
        view.computeScroll();
        assertTrue(viewport.getX() > lifted);

        // Double tap zooms back out from anywhere but all the way out...
        doubleTap(view, 540, 1200);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertEquals(min, viewport.getScale(), 1e-4f);
        // ...and in from there.
        doubleTap(view, 540, 1200);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertEquals(min * 3, viewport.getScale(), 1e-3f);
    }

    /** A single tap is the view's click. */
    @Test
    public void aTapClicks() {
        FakeRegionSource source = new FakeRegionSource(800, 600, Color.RED);
        LargeImageView view = show(new LargeImageView(mActivity), source,
                ExifInterface.ORIENTATION_NORMAL, null);
        int[] clicks = {0};
        view.setOnClickListener(v -> clicks[0]++);
        long now = SystemClock.uptimeMillis();
        view.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 10, 10, 0));
        view.onTouchEvent(MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, 10, 10, 0));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertEquals(1, clicks[0]);
    }

    /** Before a picture opens, touches are left to the view's ordinary handling. */
    @Test
    public void touchesBeforeAPictureAreIgnored() {
        LargeImageView view = new LargeImageView(mActivity);
        long now = SystemClock.uptimeMillis();
        assertFalse(view.onTouchEvent(
                MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 10, 10, 0)));
    }

    /** A panorama fills the height, opens where it says to and wraps round. */
    @Test
    public void aPanoramaWrapsRound() {
        GPano gpano = GPano.parse(GPanoTest.SWEEP_PANORAMA_XMP, 3032, 532);
        FakeRegionSource source = new FakeRegionSource(3032, 532, Color.RED);
        PanoramaView view = (PanoramaView) show(new PanoramaView(mActivity), source,
                ExifInterface.ORIENTATION_NORMAL, gpano);
        Viewport viewport = view.mViewport;

        assertTrue(viewport.wraps());
        assertEquals((float) VIEW_HEIGHT / 532, viewport.getScale(), 1e-4f);
        // Heading 2 against a middle at 1.599: just right of the picture's middle.
        float centre = (float) ((2 - 1.599 + 180) / 360 * 3032);
        assertEquals(VIEW_WIDTH / 2f, viewport.getX() + centre * viewport.getScale(), 0.5f);
        // The whole strip fits in the budget, so it is the low-resolution copy itself.
        assertEquals(1, source.decodes().get(0).sampleSize);

        // Dragged past its end, the start follows on.
        for (int i = 0; i < 20; i++) {
            drag(view, 900, 1200, 100, 1200);
        }
        assertTrue(viewport.getX() <= 0 && viewport.getX() > -viewport.scaledWidth());
        settle(view, source);
    }

    /** A panorama that does not go all the way round, or says nothing, has ends. */
    @Test
    public void aPartialPanoramaHasEnds() {
        GPano partial = GPano.parse(GPanoTest.xmp("CroppedAreaImageWidthPixels=\"1000\" "
                + "FullPanoWidthPixels=\"4000\" FullPanoHeightPixels=\"2000\" "
                + "InitialViewHeadingDegrees=\"170\""), 1000, 300);
        FakeRegionSource source = new FakeRegionSource(1000, 300, Color.RED);
        PanoramaView view = (PanoramaView) show(new PanoramaView(mActivity), source,
                ExifInterface.ORIENTATION_NORMAL, partial);
        assertFalse(view.mViewport.wraps());
        // The heading it asks to open at is off the picture, so it opens at the middle.
        assertEquals(VIEW_WIDTH / 2f,
                view.mViewport.getX() + 500 * view.mViewport.getScale(), 0.5f);

        PanoramaView plain = (PanoramaView) show(new PanoramaView(mActivity),
                new FakeRegionSource(1000, 300, Color.RED), ExifInterface.ORIENTATION_NORMAL,
                null);
        assertFalse(plain.mViewport.wraps());
    }

    /** Puts a view on screen at a phone's size, showing a source. */
    private LargeImageView show(LargeImageView view, FakeRegionSource source, int orientation,
            GPano gpano) {
        view.setExecutor(Runnable::run);
        view.setImageLoadListener(listener());
        view.setOpener((context, location) ->
                new ImageSources.Opened(source, orientation, gpano));
        attach(view);
        view.setFile(new File("picture.jpg"));
        idle();
        settle(view, source);
        return view;
    }

    private void attach(LargeImageView view) {
        mParent.addView(view, new FrameLayout.LayoutParams(VIEW_WIDTH, VIEW_HEIGHT));
        idle();
        view.measure(exactly(VIEW_WIDTH), exactly(VIEW_HEIGHT));
        view.layout(0, 0, VIEW_WIDTH, VIEW_HEIGHT);
    }

    private static int exactly(int size) {
        return android.view.View.MeasureSpec.makeMeasureSpec(size,
                android.view.View.MeasureSpec.EXACTLY);
    }

    /** Draws and lets decodes land until nothing more is asked for. */
    private void settle(LargeImageView view, FakeRegionSource source) {
        int decodes = -1;
        for (int i = 0; i < 100 && decodes != source.decodeCount(); i++) {
            decodes = source.decodeCount();
            view.draw(mCanvas);
            idle();
        }
    }

    private ImageLoadListener listener() {
        return new ImageLoadListener() {
            @Override
            public void onImageLoaded() {
                mLoaded++;
            }

            @Override
            public void onImageFailed() {
                mFailed++;
            }
        };
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static void drag(LargeImageView view, float fromX, float fromY, float toX,
            float toY) {
        long now = SystemClock.uptimeMillis();
        view.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, fromX, fromY, 0));
        for (int step = 1; step <= 10; step++) {
            float x = fromX + (toX - fromX) * step / 10;
            float y = fromY + (toY - fromY) * step / 10;
            view.onTouchEvent(MotionEvent.obtain(now, now + step * 100L, MotionEvent.ACTION_MOVE,
                    x, y, 0));
        }
        view.onTouchEvent(MotionEvent.obtain(now, now + 1100, MotionEvent.ACTION_UP, toX, toY,
                0));
    }

    private static void fling(LargeImageView view) {
        long now = SystemClock.uptimeMillis();
        // Short and quick: 90 pixels in 15 milliseconds, rightward.
        view.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 300, 1200, 0));
        for (int step = 1; step <= 3; step++) {
            view.onTouchEvent(MotionEvent.obtain(now, now + step * 5L,
                    MotionEvent.ACTION_MOVE, 300 + step * 30, 1200, 0));
        }
        view.onTouchEvent(MotionEvent.obtain(now, now + 16, MotionEvent.ACTION_UP, 390, 1200, 0));
    }

    private static void doubleTap(LargeImageView view, float x, float y) {
        long now = SystemClock.uptimeMillis();
        view.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0));
        view.onTouchEvent(MotionEvent.obtain(now, now + 30, MotionEvent.ACTION_UP, x, y, 0));
        view.onTouchEvent(MotionEvent.obtain(now + 80, now + 80, MotionEvent.ACTION_DOWN, x, y,
                0));
        view.onTouchEvent(MotionEvent.obtain(now + 80, now + 110, MotionEvent.ACTION_UP, x, y,
                0));
    }

    /** Two fingers moving apart about the middle of the view. */
    static void pinch(android.view.View view, float fromSpan, float toSpan) {
        long now = SystemClock.uptimeMillis();
        float cx = 540;
        float cy = 1200;
        MotionEvent.PointerProperties[] properties = {property(0), property(1)};
        view.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 1,
                properties, coords(cx - fromSpan / 2, cy), 0, 0, 1, 1, 0, 0, 0, 0));
        view.onTouchEvent(MotionEvent.obtain(now, now + 10,
                MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2, properties, coords2(cx, cy, fromSpan), 0, 0, 1, 1, 0, 0, 0, 0));
        for (int step = 1; step <= 10; step++) {
            float span = fromSpan + (toSpan - fromSpan) * step / 10;
            view.onTouchEvent(MotionEvent.obtain(now, now + 10 + step * 20L,
                    MotionEvent.ACTION_MOVE, 2, properties, coords2(cx, cy, span), 0, 0, 1, 1,
                    0, 0, 0, 0));
        }
        view.onTouchEvent(MotionEvent.obtain(now, now + 250,
                MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2,
                properties, coords2(cx, cy, toSpan), 0, 0, 1, 1, 0, 0, 0, 0));
        view.onTouchEvent(MotionEvent.obtain(now, now + 260, MotionEvent.ACTION_UP, 1,
                properties, coords(cx - toSpan / 2, cy), 0, 0, 1, 1, 0, 0, 0, 0));
    }

    private static MotionEvent.PointerProperties property(int id) {
        MotionEvent.PointerProperties property = new MotionEvent.PointerProperties();
        property.id = id;
        property.toolType = MotionEvent.TOOL_TYPE_FINGER;
        return property;
    }

    private static MotionEvent.PointerCoords[] coords(float x, float y) {
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = x;
        coords.y = y;
        coords.pressure = 1;
        coords.size = 1;
        return new MotionEvent.PointerCoords[] {coords, coords};
    }

    private static MotionEvent.PointerCoords[] coords2(float cx, float cy, float span) {
        MotionEvent.PointerCoords left = new MotionEvent.PointerCoords();
        left.x = cx - span / 2;
        left.y = cy;
        left.pressure = 1;
        left.size = 1;
        MotionEvent.PointerCoords right = new MotionEvent.PointerCoords();
        right.x = cx + span / 2;
        right.y = cy;
        right.pressure = 1;
        right.size = 1;
        return new MotionEvent.PointerCoords[] {left, right};
    }
}
