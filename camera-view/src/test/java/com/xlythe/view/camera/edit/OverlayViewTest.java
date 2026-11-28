package com.xlythe.view.camera.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stickers and text, placed, moved, pinched, flipped, reordered and removed. The view is 400x200
 * over a 800x400 picture, so a mistake in the mapping between them shows.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 37)
public class OverlayViewTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    private OverlayView view;
    private final List<List<Overlay>> changes = new ArrayList<>();
    private final List<Overlay> selections = new ArrayList<>();

    @Before
    public void setUp() {
        view = new OverlayView(RuntimeEnvironment.getApplication());
        view.layout(0, 0, 400, 200);
        view.setCanvasSize(800, 400);
        view.setOnOverlayChangedListener(new OverlayView.OnOverlayChangedListener() {
            @Override
            public void onOverlaysChanged(List<Overlay> overlays) {
                changes.add(overlays);
            }

            @Override
            public void onSelectionChanged(Overlay overlay) {
                selections.add(overlay);
            }
        });
    }

    @Test
    public void aStickerIsAddedChosenAndSizedToItsPicture() throws IOException {
        Overlay added = addSticker(ring(200, 100), new PointF(200, 100));
        Overlay shown = view.getOverlays().get(0);
        assertEquals(added.getId(), shown.getId());
        assertEquals(added.getId(), view.getSelectedId());
        assertEquals(200f, shown.getCenterX(), 0f);
        assertEquals("it keeps its picture's proportions", 2f,
                shown.getWidth() / shown.getHeight(), 0.01f);
        assertFalse(view.isAnimated());
    }

    @Test
    public void aStickerIsMovedByDraggingIt() throws IOException {
        addSticker(solid(100, 100, Color.RED), new PointF(200, 200));
        // The sticker's middle is at (100, 100) in the view; drag it 50 right.
        drag(100, 100, 150, 100);
        Overlay moved = view.getOverlays().get(0);
        assertEquals("50 view pixels are 100 of the picture's", 300f, moved.getCenterX(), 1f);
        view.undo();
        assertEquals("the drag is one step", 200f, view.getOverlays().get(0).getCenterX(), 0f);
        view.redo();
        assertEquals(300f, view.getOverlays().get(0).getCenterX(), 1f);
    }

    @Test
    public void whereTheFingerLiftsIsWhereTheStickerStays() throws IOException {
        addSticker(solid(100, 100, Color.RED), new PointF(200, 200));
        view.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 100, 100, 0));
        view.dispatchTouchEvent(MotionEvent.obtain(0, 10, MotionEvent.ACTION_MOVE, 120, 100, 0));
        view.dispatchTouchEvent(MotionEvent.obtain(0, 20, MotionEvent.ACTION_UP, 150, 110, 0));
        Overlay moved = view.getOverlays().get(0);
        assertEquals(300f, moved.getCenterX(), 1f);
        assertEquals(220f, moved.getCenterY(), 1f);
        view.undo();
        assertEquals("still one step", 200f, view.getOverlays().get(0).getCenterX(), 0f);
        int steps = changes.size();
        tap(100, 100);
        assertEquals("a tap moves nothing", steps, changes.size());
    }

    @Test
    public void aTouchOnATransparentPartPassesThrough() throws IOException {
        // A ring, clear in its middle.
        addSticker(ring(100, 100), new PointF(400, 200));
        assertNull("the hole is not the sticker", view.itemAt(200, 100));
        assertNotNull("the ring is", view.itemAt(200 - 22, 100));
        assertFalse(view.dispatchTouchEvent(MotionEvent.obtain(0, 0,
                MotionEvent.ACTION_DOWN, 200, 100, 0)));
        assertNull("touching nothing chooses nothing", view.getSelectedId());
    }

    @Test
    public void theTopmostStickerIsTouchedAndTheOrderCanChange() throws IOException {
        Overlay bottom = addSticker(solid(100, 100, Color.RED), new PointF(400, 200));
        Overlay top = addSticker(solid(100, 100, Color.BLUE), new PointF(400, 200));
        assertSame(top.getId(), view.itemAt(200, 100).getId());
        view.sendToBack(top.getId());
        assertEquals(top.getId(), view.getOverlays().get(0).getId());
        assertSame(bottom.getId(), view.itemAt(200, 100).getId());
        view.bringToFront(top.getId());
        assertEquals(top.getId(), view.getOverlays().get(1).getId());
        view.moveOverlay(top.getId(), 0);
        assertEquals(top.getId(), view.getOverlays().get(0).getId());
    }

    @Test
    public void twoFingersScaleAndTurnASticker() throws IOException {
        addSticker(solid(100, 100, Color.RED), new PointF(400, 200));
        MotionEvent.PointerProperties[] properties = properties(2);
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, properties, 1, 190, 100, 0, 0));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_POINTER_DOWN
                | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), properties, 2, 190, 100, 210,
                100));
        // Twice as far apart, and turned a quarter.
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, properties, 2, 200, 80, 200,
                120));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_POINTER_UP
                | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), properties, 2, 200, 80, 200,
                120));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, properties, 1, 200, 80, 0, 0));
        Overlay pinched = view.getOverlays().get(0);
        assertEquals(2f, pinched.getScale(), 0.05f);
        assertEquals(90f, pinched.getRotationDegrees(), 1f);
    }

    @Test
    public void theHandlesFlipAndRemoveAndStayAtTheirCorners() throws IOException {
        Overlay sticker = addSticker(solid(100, 100, Color.RED), new PointF(400, 200));
        // 100 picture pixels square around (400, 200) is 50 view pixels around (200, 100).
        tap(175, 75);
        assertTrue(view.getOverlays().get(0).isFlippedHorizontally());
        // Flipped, the remove handle is still at the top right.
        tap(225, 75);
        assertTrue(view.getOverlays().isEmpty());
        view.undo();
        assertEquals(sticker.getId(), view.getOverlays().get(0).getId());
    }

    @Test
    public void theCornerHandleScalesAndTurns() throws IOException {
        addSticker(solid(100, 100, Color.RED), new PointF(400, 200));
        // From the bottom right corner out to twice as far, turned a quarter clockwise.
        drag(225, 125, 150, 150);
        Overlay turned = view.getOverlays().get(0);
        assertEquals(2f, turned.getScale(), 0.05f);
        assertEquals(90f, turned.getRotationDegrees(), 1f);
    }

    @Test
    public void textIsLaidOutAndCanChange() {
        Overlay text = view.addText("Hello", Color.YELLOW);
        assertEquals(Overlay.KIND_TEXT, text.getKind());
        assertTrue(text.getWidth() > text.getHeight());
        view.updateOverlay(new Overlay.Builder(view.getOverlays().get(0))
                .setText("Hello, world").setBackground(Overlay.BACKGROUND_SOLID, Color.BLACK)
                .build());
        Overlay longer = view.getOverlays().get(0);
        assertEquals("Hello, world", longer.getText().toString());
        assertTrue(longer.getWidth() > text.getWidth());
        Bitmap drawn = view.toBitmap(800, 400, 0);
        assertTrue("its block is drawn", Color.alpha(drawn.getPixel(400, 200)) > 0);
    }

    @Test
    public void anAnimatedPngShowsTheFrameForTheMoment() throws IOException {
        byte[] png = AnimationsTest.resource("media/apng_bouncing_beach_ball.png");
        Animation frames = Animations.decodePng(png, Long.MAX_VALUE);
        assertNotNull(frames);
        File file = folder.newFile("ball.png");
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(png);
        }
        view.addImage(Uri.fromFile(file), "image/png", new PointF(400, 200));
        awaitDecode();
        assertTrue(view.isAnimated());
        long second = Math.max(1, frames.delays[0]);
        Bitmap first = view.toBitmap(800, 400, 0);
        Bitmap later = view.toBitmap(800, 400, second);
        assertFalse("the ball moved", first.sameAs(later));
        // In a video, it plays from when it starts showing.
        view.updateOverlay(new Overlay.Builder(view.getOverlays().get(0))
                .setTimeRangeMillis(1000, Overlay.UNSET_TIME).build());
        assertTrue(first.sameAs(view.toBitmap(800, 400, 1000)));
        assertTrue(later.sameAs(view.toBitmap(800, 400, 1000 + second)));
    }

    @Test
    public void anAnimatedGifPlaysThroughThePlatform() throws IOException {
        addGif();
        assertTrue(view.isAnimated());
        Overlay overlay = view.getOverlays().get(0);
        assertEquals("it keeps the GIF's proportions", 2f,
                overlay.getWidth() / overlay.getHeight(), 0.01f);
        Bitmap shown = view.toBitmap(800, 400, 0);
        int y = Math.round(overlay.getCenterY());
        int left = Math.round(overlay.getCenterX() - overlay.getWidth() / 4);
        assertEquals("its first frame is red on the left", Color.RED,
                shown.getPixel(left, y) | 0xFF000000);
    }

    @Test
    @Config(sdk = 27)
    public void beforeAndroid9AGifShowsStill() throws IOException {
        addGif();
        assertFalse(view.isAnimated());
        Overlay overlay = view.getOverlays().get(0);
        Bitmap shown = view.toBitmap(800, 400, 0);
        int y = Math.round(overlay.getCenterY());
        int left = Math.round(overlay.getCenterX() - overlay.getWidth() / 4);
        assertEquals(Color.RED, shown.getPixel(left, y) | 0xFF000000);
    }

    private void addGif() throws IOException {
        File gif = folder.newFile("two.gif");
        try (OutputStream out = new FileOutputStream(gif)) {
            out.write(AnimationsTest.resource("editor/two_frames.gif"));
        }
        view.addImage(Uri.fromFile(gif), "image/gif", new PointF(400, 200));
        awaitDecode();
    }

    @Test
    public void draggingOntoTheDeleteZoneRemovesItAsOneStep() throws IOException {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View zone = new View(activity);
        root.addView(view, new FrameLayout.LayoutParams(400, 200));
        root.addView(zone, new FrameLayout.LayoutParams(50, 50, Gravity.TOP | Gravity.START));
        ((FrameLayout.LayoutParams) zone.getLayoutParams()).leftMargin = 300;
        ((FrameLayout.LayoutParams) zone.getLayoutParams()).topMargin = 120;
        activity.setContentView(root);
        ShadowLooper.idleMainLooper();
        view.setDeleteZone(zone);
        Overlay sticker = addSticker(solid(100, 100, Color.RED), new PointF(200, 200));
        int before = changes.size();
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        // From the sticker's middle to the zone's, as the screen sees it.
        dragOnScreen(at, 100, 100, 325, 145);
        assertTrue(view.getOverlays().isEmpty());
        assertFalse("the zone is left as it was", zone.isActivated());
        view.undo();
        assertEquals("one undo puts it back where it was", 200f,
                view.getOverlays().get(0).getCenterX(), 0f);
        assertEquals(sticker.getId(), view.getOverlays().get(0).getId());
        assertTrue(changes.size() > before);
    }

    @Test
    public void theDrawingGoesBeneathTheStickers() throws IOException {
        addSticker(solid(100, 100, Color.RED), new PointF(400, 200));
        // A blue line across the middle of a drawing a tenth the picture's size.
        Drawing drawing = new Drawing(80, 40).withStrokes(Collections.singletonList(
                DrawingTest.stroke(Brush.MARKER.withColor(Color.BLUE).withWidth(4), "line",
                        0, 20, 80, 20)));
        Bitmap flat = view.toBitmap(80, 40, 0, drawing);
        assertEquals("the sticker is over the ink", Color.RED, flat.getPixel(40, 20));
        assertEquals("the ink fills the picture", Color.BLUE,
                flat.getPixel(10, 20) | 0xFF000000);
        assertEquals(0, Color.alpha(flat.getPixel(10, 5)));
    }

    @Test
    public void aMomentOfAVideoShowsOnlyTheItemsShownThen() throws IOException {
        addSticker(solid(100, 100, Color.RED), new PointF(400, 200));
        view.updateOverlay(new Overlay.Builder(view.getOverlays().get(0))
                .setTimeRangeMillis(1000, 2000).build());
        assertEquals(0, Color.alpha(view.toBitmap(80, 40, 500).getPixel(40, 20)));
        assertEquals(Color.RED, view.toBitmap(80, 40, 1500).getPixel(40, 20));
        assertEquals(0, Color.alpha(view.toBitmap(80, 40, 2000).getPixel(40, 20)));
    }

    @Test
    public void aStickerThatWillNotDecodeIsRemoved() throws IOException {
        File junk = folder.newFile("junk.png");
        try (OutputStream out = new FileOutputStream(junk)) {
            out.write(new byte[] {1, 2, 3});
        }
        view.addImage(Uri.fromFile(junk), null, null);
        awaitDecode();
        assertTrue(view.getOverlays().isEmpty());
    }

    @Test
    public void theLimitsAreKept() throws IOException {
        view.setLimits(1, 64);
        addSticker(solid(100, 100, Color.RED), null);
        view.addText("no room", Color.WHITE);
        assertEquals(1, view.getOverlays().size());
    }

    @Test
    public void itemsCanBeShownAndRemovedAndSaidToChange() throws IOException {
        Overlay sticker = addSticker(solid(10, 10, Color.RED), null);
        int before = changes.size();
        view.remove(sticker.getId());
        assertEquals(before + 1, changes.size());
        assertNull(view.getSelectedId());
        view.setOverlays(Collections.singletonList(sticker));
        assertEquals(1, view.getOverlays().size());
        assertFalse("shown items have nothing to undo", view.canUndo());
        view.select(sticker.getId());
        assertEquals(sticker.getId(), selections.get(selections.size() - 1).getId());
        view.select("no such item");
        assertNull(view.getSelectedId());
    }

    @Test
    public void anItemIsPlacedByItsMiddleScaleTurnAndFlip() {
        Overlay overlay = Overlay.Builder.text("x").setCenter(10, 20).setSize(4, 2)
                .setScale(2).setRotationDegrees(-90).setFlippedVertically(true)
                .setTimeRangeMillis(1000, 2000).build();
        assertEquals(270f, overlay.getRotationDegrees(), 0f);
        assertTrue(overlay.isShownAt(1500));
        assertFalse(overlay.isShownAt(2000));
        float[] corner = {0, 0};
        overlay.matrix().mapPoints(corner);
        assertEquals(new Overlay.Builder(overlay).build(), overlay);
        assertThrows(IllegalArgumentException.class,
                () -> Overlay.Builder.text("x").setTimeRangeMillis(5, 5));
        assertThrows(IllegalArgumentException.class,
                () -> Overlay.Builder.text("x").setSize(0, 1));
    }

    @Test
    public void itemsAndTheChoiceSurviveAConfigurationChange() {
        Overlay text = view.addText("kept", Color.YELLOW);
        Overlay image = Overlay.Builder.image(Uri.parse("content://stickers/star"), "image/gif")
                .setCenter(100, 50).setSize(30, 40).setScale(2).setRotationDegrees(30)
                .setFlippedHorizontally(true).setTimeRangeMillis(5, 9).build();
        Parcel single = Parcel.obtain();
        image.writeToParcel(single, 0);
        single.setDataPosition(0);
        assertEquals(image, Overlay.CREATOR.createFromParcel(single));
        single.recycle();

        Parcelable state = view.onSaveInstanceState();
        Parcel parcel = Parcel.obtain();
        parcel.writeParcelable(state, 0);
        parcel.setDataPosition(0);
        Parcelable read = parcel.readParcelable(OverlayView.class.getClassLoader());
        parcel.recycle();
        OverlayView restored = new OverlayView(RuntimeEnvironment.getApplication());
        restored.layout(0, 0, 400, 200);
        restored.setCanvasSize(800, 400);
        restored.onRestoreInstanceState(read);
        assertEquals(view.getOverlays(), restored.getOverlays());
        assertEquals(text.getId(), restored.getSelectedId());
        assertTrue(restored.isDecoded());
    }

    private Overlay addSticker(Bitmap bitmap, PointF at) throws IOException {
        File file = folder.newFile();
        try (OutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        Overlay overlay = view.addImage(Uri.fromFile(file), "image/png", at);
        awaitDecode();
        // A square sticker of 100 picture pixels, whatever share of the picture it was given.
        Overlay shown = view.getOverlays().isEmpty() ? overlay
                : view.getOverlays().get(view.getOverlays().size() - 1);
        if (!view.getOverlays().isEmpty()) {
            float side = Math.max(bitmap.getWidth(), bitmap.getHeight());
            view.updateOverlay(new Overlay.Builder(shown)
                    .setSize(bitmap.getWidth() * 100f / side, bitmap.getHeight() * 100f / side)
                    .build());
            if (at == null) {
                return view.getOverlays().get(view.getOverlays().size() - 1);
            }
        }
        return overlay;
    }

    private void awaitDecode() {
        long until = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < until) {
            ShadowLooper.idleMainLooper();
            if (view.isDecoded()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void drag(float x0, float y0, float x1, float y1) {
        view.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x0, y0, 0));
        view.dispatchTouchEvent(MotionEvent.obtain(0, 10, MotionEvent.ACTION_MOVE,
                (x0 + x1) / 2, (y0 + y1) / 2, 0));
        view.dispatchTouchEvent(MotionEvent.obtain(0, 20, MotionEvent.ACTION_MOVE, x1, y1, 0));
        view.dispatchTouchEvent(MotionEvent.obtain(0, 30, MotionEvent.ACTION_UP, x1, y1, 0));
    }

    /** A drag with each event at its place on the screen, offset into the view as delivered. */
    private void dragOnScreen(int[] at, float x0, float y0, float x1, float y1) {
        float[][] points = {{x0, y0}, {(x0 + x1) / 2, (y0 + y1) / 2}, {x1, y1}, {x1, y1}};
        int[] actions = {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP};
        for (int i = 0; i < points.length; i++) {
            MotionEvent event = MotionEvent.obtain(0, i * 10L, actions[i],
                    at[0] + points[i][0], at[1] + points[i][1], 0);
            event.offsetLocation(-at[0], -at[1]);
            view.dispatchTouchEvent(event);
            event.recycle();
        }
    }

    private void tap(float x, float y) {
        view.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0));
        view.dispatchTouchEvent(MotionEvent.obtain(0, 30, MotionEvent.ACTION_UP, x, y, 0));
    }

    private static MotionEvent.PointerProperties[] properties(int count) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
        for (int i = 0; i < count; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
        }
        return properties;
    }

    private static MotionEvent event(int action, MotionEvent.PointerProperties[] properties,
            int count, float x0, float y0, float x1, float y1) {
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
        float[][] points = {{x0, y0}, {x1, y1}};
        for (int i = 0; i < count; i++) {
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = points[i][0];
            coords[i].y = points[i][1];
            coords[i].pressure = 1;
        }
        MotionEvent.PointerProperties[] used = new MotionEvent.PointerProperties[count];
        System.arraycopy(properties, 0, used, 0, count);
        return MotionEvent.obtain(0, 0, action, count, used, coords, 0, 0, 1, 1, 0, 0, 0, 0);
    }

    private static Bitmap solid(int width, int height, int color) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        return bitmap;
    }

    private static Bitmap ring(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.min(width, height) / 5f);
        paint.setColor(Color.GREEN);
        new Canvas(bitmap).drawCircle(width / 2f, height / 2f, Math.min(width, height) / 2.5f,
                paint);
        return bitmap;
    }
}
