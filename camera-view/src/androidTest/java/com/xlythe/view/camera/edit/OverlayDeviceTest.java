package com.xlythe.view.camera.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.xlythe.view.camera.viewer.ViewerTestActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stickers and text over a picture on a device, moved by the system's own input: an animated
 * GIF sticker that plays, dragged, pinched and turned with two fingers, flipped by its handle; a
 * stroke drawn through the stickers onto the drawing beneath; a still sticker dragged in from a
 * sticker cell by the system's drag and drop; text; the layer order; a sticker dragged onto the
 * delete zone; one undo across the drawing and the stickers; and the result flattened over the
 * picture by PictureEditor. Paced for a screen recording; what the screen shows is written to
 * editor-proof.
 *
 * <p>Once a sticker animates, the main thread is never idle, so work on it goes through
 * {@link Instrumentation#runOnMainSync} rather than {@link ActivityScenario#onActivity}, which
 * waits for idle first.</p>
 */
@RunWith(AndroidJUnit4.class)
public final class OverlayDeviceTest {
    private static final String TAG = "OverlayDevice";

    @Test
    public void stickersAndTextAreMovedPinchedFlippedAndSaved() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = ApplicationProvider.getApplicationContext();
        File star = copy("editor/spinning_star.gif", "star.gif");
        File heart = copy("editor/heart.png", "heart.png");
        try (ActivityScenario<ViewerTestActivity> scenario =
                     ActivityScenario.launch(ViewerTestActivity.class)) {
            AtomicReference<OverlayView> holder = new AtomicReference<>();
            AtomicReference<DrawingView> drawingHolder = new AtomicReference<>();
            AtomicReference<View> zoneHolder = new AtomicReference<>();
            AtomicReference<View> cellHolder = new AtomicReference<>();
            EditHistory history = new EditHistory();
            Bitmap picture = picture(800, 600);
            scenario.onActivity(activity -> {
                ViewGroup root = (ViewGroup) ((ViewGroup) activity.findViewById(
                        android.R.id.content)).getChildAt(0);
                LinearLayout column = new LinearLayout(activity);
                column.setOrientation(LinearLayout.VERTICAL);
                FrameLayout stage = new FrameLayout(activity);
                ImageView image = new ImageView(activity);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                image.setImageBitmap(picture);
                DrawingView drawing = new DrawingView(activity);
                drawing.setDrawingSize(800, 600);
                drawing.setBrush(Brush.MARKER.withColor(0xFFFFEB3B).withWidth(12));
                OverlayView overlays = new OverlayView(activity);
                overlays.setCanvasSize(800, 600);
                overlays.setDropTargetEnabled(true);
                View zone = new View(activity);
                zone.setBackgroundColor(0x66000000);
                overlays.setDeleteZone(zone);
                stage.addView(image);
                stage.addView(drawing);
                stage.addView(overlays);
                int side = (int) (64 * activity.getResources().getDisplayMetrics().density);
                FrameLayout.LayoutParams zoneParams = new FrameLayout.LayoutParams(side, side,
                        Gravity.BOTTOM | Gravity.END);
                stage.addView(zone, zoneParams);
                ImageView cell = new ImageView(activity);
                cell.setImageBitmap(BitmapFactory.decodeFile(heart.getPath()));
                cell.setOnLongClickListener(v -> OverlayView.startDrag(v, Uri.fromFile(heart),
                        "image/png"));
                column.addView(stage, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
                column.addView(cell, new LinearLayout.LayoutParams(side * 2, side * 2));
                root.addView(column);
                history.attach(drawing);
                history.attach(overlays);
                holder.set(overlays);
                drawingHolder.set(drawing);
                zoneHolder.set(zone);
                cellHolder.set(cell);
            });
            instrumentation.waitForIdleSync();
            SystemClock.sleep(600);
            OverlayView view = holder.get();
            int[] at = new int[2];
            view.getLocationOnScreen(at);
            float scale = Math.min(view.getWidth() / 800f, view.getHeight() / 600f);
            float originX = at[0] + (view.getWidth() - 800 * scale) / 2f;
            float originY = at[1] + (view.getHeight() - 600 * scale) / 2f;

            onMain(() -> holder.get().addImage(Uri.fromFile(star),
                    "image/gif", new PointF(250, 250)));
            waitFor(scenario, holder, 1);
            SystemClock.sleep(500);
            assertTrue("the GIF animates", onMainAnimated(scenario, holder));
            Bitmap a = screenshot(instrumentation, "overlay-0-star");
            SystemClock.sleep(330);
            Bitmap b = screenshot(instrumentation, "overlay-1-star-later");
            assertFalse("the star turned between the two", a.sameAs(b));

            // Drag it right and down.
            drag(instrumentation, originX + 250 * scale, originY + 250 * scale,
                    originX + 420 * scale, originY + 330 * scale);
            Overlay moved = overlays(scenario, holder).get(0);
            Log.i(TAG, "Moved to " + moved.getCenterX() + "," + moved.getCenterY());
            assertEquals(420f, moved.getCenterX(), 25f);
            screenshot(instrumentation, "overlay-2-moved");

            // Pinch it to twice the size and turn it a quarter.
            float cx = originX + moved.getCenterX() * scale;
            float cy = originY + moved.getCenterY() * scale;
            pinch(instrumentation, cx, cy, 40, 80, 0, 90);
            Overlay pinched = overlays(scenario, holder).get(0);
            Log.i(TAG, "Pinched to scale " + pinched.getScale() + ", turned "
                    + pinched.getRotationDegrees());
            assertEquals(2f, pinched.getScale(), 0.4f);
            assertEquals(90f, pinched.getRotationDegrees(), 15f);
            screenshot(instrumentation, "overlay-3-pinched");

            // A tap on the handle at its top left, as seen, flips it.
            float[] corner = {0, 0};
            new Overlay.Builder(pinched).setFlippedHorizontally(false)
                    .setFlippedVertically(false).build().matrix().mapPoints(corner);
            tap(instrumentation, originX + corner[0] * scale, originY + corner[1] * scale);
            Overlay flipped = overlays(scenario, holder).get(0);
            assertTrue("the handle flipped it", flipped.isFlippedHorizontally());
            assertEquals(pinched.getCenterX(), flipped.getCenterX(), 1f);
            screenshot(instrumentation, "overlay-3b-flipped");

            // A drag where there is no sticker draws on the drawing beneath.
            drag(instrumentation, originX + 60 * scale, originY + 540 * scale,
                    originX + 300 * scale, originY + 560 * scale);
            assertEquals("the stroke went through to the drawing", 1,
                    onMain(scenario, () -> drawingHolder.get().getDrawing().getStrokes().size())
                            .intValue());
            assertEquals("the sticker stayed put", flipped, overlays(scenario, holder).get(0));
            screenshot(instrumentation, "overlay-3c-stroke-beneath");

            // A heart dragged in from its cell by the system's drag and drop.
            int[] cellAt = new int[2];
            cellHolder.get().getLocationOnScreen(cellAt);
            float cellX = cellAt[0] + cellHolder.get().getWidth() / 2f;
            float cellY = cellAt[1] + cellHolder.get().getHeight() / 2f;
            longPressDrag(instrumentation, cellX, cellY, originX + 600 * scale,
                    originY + 150 * scale);
            waitFor(scenario, holder, 2);
            List<Overlay> withHeart = overlays(scenario, holder);
            Log.i(TAG, "After the drop: " + withHeart);
            assertEquals("the heart was dropped in", 2, withHeart.size());
            assertEquals(600f, withHeart.get(1).getCenterX(), 30f);
            assertEquals(150f, withHeart.get(1).getCenterY(), 30f);
            screenshot(instrumentation, "overlay-4-heart-dropped");

            // Some text on a block, then the star brought above it and sent back.
            onMain(() -> {
                OverlayView overlays = holder.get();
                Overlay text = overlays.addText("CameraView", Color.WHITE);
                overlays.updateOverlay(new Overlay.Builder(text)
                        .setBackground(Overlay.BACKGROUND_SOLID, 0xCC1E88E5)
                        .setCenter(400, 520).build());
                overlays.bringToFront(flipped.getId());
            });
            SystemClock.sleep(600);
            assertEquals("the star is on top", flipped.getId(),
                    overlays(scenario, holder).get(2).getId());
            onMain(() -> holder.get().sendToBack(flipped.getId()));
            assertEquals("and at the bottom", flipped.getId(),
                    overlays(scenario, holder).get(0).getId());
            screenshot(instrumentation, "overlay-5-text-order");

            // The heart dragged onto the delete zone goes.
            int[] zoneAt = new int[2];
            zoneHolder.get().getLocationOnScreen(zoneAt);
            Overlay dropped = overlays(scenario, holder).get(1);
            drag(instrumentation, originX + dropped.getCenterX() * scale,
                    originY + dropped.getCenterY() * scale,
                    zoneAt[0] + zoneHolder.get().getWidth() / 2f,
                    zoneAt[1] + zoneHolder.get().getHeight() / 2f);
            assertEquals("the delete zone removed the heart", 2,
                    overlays(scenario, holder).size());
            assertFalse(onMain(scenario, () -> zoneHolder.get().isActivated()));
            screenshot(instrumentation, "overlay-6-deleted");

            // One undo across both views, newest first: the heart comes back before the stroke
            // drawn before it went.
            onMain(() -> history.undo());
            assertEquals("undo brought the heart back", 3, overlays(scenario, holder).size());
            SystemClock.sleep(500);
            screenshot(instrumentation, "overlay-7-undone");
            int undos = 0;
            while (onMain(scenario, () -> drawingHolder.get().getDrawing().getStrokes().size())
                    > 0) {
                assertTrue(onMain(scenario, history::canUndo));
                int before = overlays(scenario, holder).size();
                onMain(() -> history.undo());
                undos++;
                Log.i(TAG, "Undo " + undos + ": " + before + " -> "
                        + overlays(scenario, holder).size() + " items");
            }
            assertEquals("the stroke went only once the heart dropped after it went", 1,
                    overlays(scenario, holder).size());
            onMain(() -> {
                while (history.canRedo()) {
                    history.redo();
                }
            });
            assertEquals(1, onMain(scenario,
                    () -> drawingHolder.get().getDrawing().getStrokes().size()).intValue());
            assertEquals(2, overlays(scenario, holder).size());
            SystemClock.sleep(500);
            screenshot(instrumentation, "overlay-8-redone");

            // Flattened over the picture, at a moment of the star's turn.
            AtomicReference<Bitmap> flattened = new AtomicReference<>();
            onMain(() -> flattened.set(holder.get().toBitmap(800, 600, 0)));
            File source = new File(context.getCacheDir(), "overlay-source.jpg");
            try (OutputStream out = new FileOutputStream(source)) {
                picture.compress(Bitmap.CompressFormat.JPEG, 95, out);
            }
            File saved = new File(context.getCacheDir(), "overlay-saved.jpg");
            edit(source, saved, new PictureEdits.Builder().setOverlay(flattened.get()).build());
            Bitmap result = BitmapFactory.decodeFile(saved.getPath());
            assertNotNull(result);
            proof("overlay-9-saved", result);
            SystemClock.sleep(800);
        }
    }

    /** Edits a picture, waiting for the editor's report. */
    private static void edit(@NonNull File source, @NonNull File output,
            @NonNull PictureEdits edits) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            PictureEditor editor = new PictureEditor(ApplicationProvider.getApplicationContext(),
                    Uri.fromFile(source), output, edits);
            editor.setOnPictureEditedListener(new PictureEditor.OnPictureEditedListener() {
                @Override
                public void onPictureEdited(@NonNull File file) {
                    done.countDown();
                }

                @Override
                public void onFailure() {
                    done.countDown();
                }
            });
            editor.start();
        });
        assertTrue(done.await(60, TimeUnit.SECONDS));
        assertTrue(output.isFile());
    }

    /** Runs something on the main thread, without waiting for it to be idle. */
    private static void onMain(@NonNull Runnable runnable) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(runnable);
    }

    /** Runs something on the main thread and hands back what it answers. */
    private static <T> T onMain(@NonNull ActivityScenario<ViewerTestActivity> scenario,
            @NonNull Callable<T> question) {
        AtomicReference<T> answer = new AtomicReference<>();
        onMain(() -> {
            try {
                answer.set(question.call());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        return answer.get();
    }

    @NonNull
    private static List<Overlay> overlays(@NonNull ActivityScenario<ViewerTestActivity> scenario,
            @NonNull AtomicReference<OverlayView> holder) {
        AtomicReference<List<Overlay>> items = new AtomicReference<>();
        onMain(() -> items.set(holder.get().getOverlays()));
        return items.get();
    }

    private static boolean onMainAnimated(@NonNull ActivityScenario<ViewerTestActivity> scenario,
            @NonNull AtomicReference<OverlayView> holder) {
        AtomicReference<Boolean> animated = new AtomicReference<>();
        onMain(() -> animated.set(holder.get().isAnimated()));
        return animated.get();
    }

    private static void waitFor(@NonNull ActivityScenario<ViewerTestActivity> scenario,
            @NonNull AtomicReference<OverlayView> holder, int count) {
        long until = SystemClock.uptimeMillis() + 10_000;
        while (SystemClock.uptimeMillis() < until) {
            if (overlays(scenario, holder).size() >= count && decoded(scenario, holder)) {
                return;
            }
            SystemClock.sleep(100);
        }
    }

    private static boolean decoded(@NonNull ActivityScenario<ViewerTestActivity> scenario,
            @NonNull AtomicReference<OverlayView> holder) {
        AtomicReference<Boolean> done = new AtomicReference<>();
        onMain(() -> done.set(holder.get().isDecoded()));
        return done.get();
    }

    private static void drag(@NonNull Instrumentation instrumentation, float x0, float y0,
            float x1, float y1) {
        long down = SystemClock.uptimeMillis();
        int steps = 30;
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            int action = i == 0 ? MotionEvent.ACTION_DOWN
                    : i == steps ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
            MotionEvent event = MotionEvent.obtain(down, down + i * 25L, action,
                    x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            instrumentation.sendPointerSync(event);
            event.recycle();
            SystemClock.sleep(25);
        }
        SystemClock.sleep(400);
    }

    private static void tap(@NonNull Instrumentation instrumentation, float x, float y) {
        long down = SystemClock.uptimeMillis();
        MotionEvent press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        press.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        instrumentation.sendPointerSync(press);
        press.recycle();
        SystemClock.sleep(60);
        MotionEvent release = MotionEvent.obtain(down, down + 60, MotionEvent.ACTION_UP, x, y, 0);
        release.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        instrumentation.sendPointerSync(release);
        release.recycle();
        SystemClock.sleep(500);
    }

    /**
     * Holds a finger down long enough to start a drag, then carries it somewhere and lets go.
     * Injected through UiAutomation: once the drag starts, the events go to the system's drag
     * window, which Android 9 refuses this app's own injection into.
     */
    private static void longPressDrag(@NonNull Instrumentation instrumentation, float x0, float y0,
            float x1, float y1) {
        UiAutomation automation = instrumentation.getUiAutomation();
        long down = SystemClock.uptimeMillis();
        MotionEvent press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x0, y0, 0);
        press.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        assertTrue(automation.injectInputEvent(press, true));
        press.recycle();
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 400L);
        int steps = 40;
        for (int i = 1; i <= steps; i++) {
            float t = i / (float) steps;
            int action = i == steps ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
            MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            automation.injectInputEvent(event, true);
            event.recycle();
            SystemClock.sleep(25);
        }
        SystemClock.sleep(800);
    }

    /** Two fingers about a point, from one span and angle to another. */
    private static void pinch(@NonNull Instrumentation instrumentation, float cx, float cy,
            float span0, float span1, float angle0, float angle1) {
        long down = SystemClock.uptimeMillis();
        int steps = 30;
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
        for (int i = 0; i < 2; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
        }
        for (int i = 0; i <= steps + 1; i++) {
            float t = Math.min(1f, i / (float) steps);
            float span = span0 + (span1 - span0) * t;
            double angle = Math.toRadians(angle0 + (angle1 - angle0) * t);
            MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];
            for (int p = 0; p < 2; p++) {
                coords[p] = new MotionEvent.PointerCoords();
                float sign = p == 0 ? -1 : 1;
                coords[p].x = cx + sign * span * (float) Math.cos(angle);
                coords[p].y = cy + sign * span * (float) Math.sin(angle);
                coords[p].pressure = 1;
                coords[p].size = 0.1f;
            }
            int action;
            int count = 2;
            if (i == 0) {
                action = MotionEvent.ACTION_DOWN;
                count = 1;
            } else if (i == 1) {
                action = MotionEvent.ACTION_POINTER_DOWN
                        | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
            } else if (i == steps + 1) {
                action = MotionEvent.ACTION_POINTER_UP
                        | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
            } else {
                action = MotionEvent.ACTION_MOVE;
            }
            send(instrumentation, down, down + i * 25L, action, count, properties, coords);
            SystemClock.sleep(25);
        }
        MotionEvent.PointerCoords[] last = {new MotionEvent.PointerCoords()};
        double angle = Math.toRadians(angle1);
        last[0].x = cx - span1 * (float) Math.cos(angle);
        last[0].y = cy - span1 * (float) Math.sin(angle);
        last[0].pressure = 1;
        send(instrumentation, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 1,
                properties, last);
        SystemClock.sleep(400);
    }

    private static void send(@NonNull Instrumentation instrumentation, long down, long time,
            int action, int count, @NonNull MotionEvent.PointerProperties[] properties,
            @NonNull MotionEvent.PointerCoords[] coords) {
        MotionEvent.PointerProperties[] usedProperties = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] usedCoords = new MotionEvent.PointerCoords[count];
        System.arraycopy(properties, 0, usedProperties, 0, count);
        System.arraycopy(coords, 0, usedCoords, 0, count);
        MotionEvent event = MotionEvent.obtain(down, time, action, count, usedProperties,
                usedCoords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        instrumentation.sendPointerSync(event);
        event.recycle();
    }

    @NonNull
    private static Bitmap picture(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        for (int y = 0; y < height; y += 40) {
            for (int x = 0; x < width; x += 40) {
                paint.setColor(((x + y) / 40) % 2 == 0 ? 0xFF2E7D32 : 0xFF388E3C);
                canvas.drawRect(x, y, x + 40, y + 40, paint);
            }
        }
        paint.setColor(0xFF90CAF9);
        canvas.drawRect(0, 0, width, height / 3f, paint);
        return bitmap;
    }

    @NonNull
    private static File copy(@NonNull String asset, @NonNull String name) throws IOException {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        File file = new File(ApplicationProvider.getApplicationContext().getCacheDir(), name);
        try (InputStream input = context.getAssets().open(asset);
                OutputStream output = new FileOutputStream(file)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                bytes.write(buffer, 0, count);
            }
            output.write(bytes.toByteArray());
        }
        return file;
    }

    @NonNull
    private static Bitmap screenshot(@NonNull Instrumentation instrumentation,
            @NonNull String name) throws IOException {
        Bitmap screen = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(screen);
        proof(name, screen);
        return screen;
    }

    private static void proof(@NonNull String name, @NonNull Bitmap bitmap) throws IOException {
        Context context = ApplicationProvider.getApplicationContext();
        File root = context.getExternalFilesDir(null);
        File folder = new File(root == null ? context.getFilesDir() : root, "editor-proof");
        assertTrue(folder.isDirectory() || folder.mkdirs());
        try (OutputStream out = new FileOutputStream(new File(folder, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
}
