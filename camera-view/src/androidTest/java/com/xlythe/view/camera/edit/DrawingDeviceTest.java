package com.xlythe.view.camera.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The drawing surface and brush panel on a device, fed through the system's own input path:
 * a pen stroke pressing harder as it goes and leaning over, a finger stroke, the pen's eraser
 * end, a brush and colour picked on the panel, and undo and redo. Paced so a screen recording
 * of the run shows each step. What the screen shows is written to the test's external files,
 * in editor-proof.
 */
@RunWith(AndroidJUnit4.class)
public final class DrawingDeviceTest {
    private static final String TAG = "DrawingDevice";

    /** How long each stroke takes, so a recording shows it being drawn. */
    private static final long STROKE_MILLIS = 1200;

    /** How many samples each stroke is made of. */
    private static final int SAMPLES = 60;

    @Test
    public void aPenAFingerAndAnEraserDrawOnAPicture() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<ViewerTestActivity> scenario =
                     ActivityScenario.launch(ViewerTestActivity.class)) {
            AtomicReference<DrawingView> drawing = new AtomicReference<>();
            AtomicReference<BrushPanelView> panel = new AtomicReference<>();
            scenario.onActivity(activity -> {
                ViewGroup root = (ViewGroup) ((ViewGroup) activity.findViewById(
                        android.R.id.content)).getChildAt(0);
                LinearLayout column = new LinearLayout(activity);
                column.setOrientation(LinearLayout.VERTICAL);
                FrameLayout stage = new FrameLayout(activity);
                ImageView picture = new ImageView(activity);
                picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
                picture.setImageBitmap(quarters(800, 600));
                DrawingView view = new DrawingView(activity);
                view.setDrawingSize(800, 600);
                stage.addView(picture);
                stage.addView(view);
                BrushPanelView brushes = new BrushPanelView(activity);
                brushes.setBackgroundColor(0xFF202020);
                brushes.setDrawingView(view);
                column.addView(stage, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
                column.addView(brushes, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                root.addView(column);
                drawing.set(view);
                panel.set(brushes);
            });
            instrumentation.waitForIdleSync();
            SystemClock.sleep(800);
            DrawingView view = drawing.get();
            int[] at = new int[2];
            view.getLocationOnScreen(at);
            float left = at[0];
            float top = at[1];
            float width = view.getWidth();
            float height = view.getHeight();
            screenshot(instrumentation, "ink-0-empty");

            // A pen pressing harder as it goes, leaning over at the end.
            stroke(instrumentation, MotionEvent.TOOL_TYPE_STYLUS, InputDevice.SOURCE_STYLUS,
                    left + width * 0.15f, top + height * 0.3f, left + width * 0.85f,
                    top + height * 0.35f, 0.1f, 1f, true);
            instrumentation.waitForIdleSync();
            Drawing afterPen = onMain(scenario, drawing);
            assertEquals(1, afterPen.getStrokes().size());
            Stroke pen = afterPen.getStrokes().get(0);
            assertTrue("the digitiser's samples came through: " + pen.size(), pen.size() >= 20);
            float first = pen.getPressure(1);
            float last = pen.getPressure(pen.size() - 2);
            Log.i(TAG, "Pen stroke of " + pen.size() + " samples, pressure " + first + " to "
                    + last + ", tilt at the end " + pen.getTiltX(pen.size() - 2) + ","
                    + pen.getTiltY(pen.size() - 2));
            assertTrue("pressure rose: " + first + " to " + last, last > first + 0.3f);
            assertEquals(StylusInput.PRESENT, view.stylus().pressure());
            screenshot(instrumentation, "ink-1-pen");

            // The panel's marker, in red, with a finger.
            BrushPanelView brushes = panel.get();
            tapChild(instrumentation, brushes, 0, 2.5f / 6f);
            tapChild(instrumentation, brushes, 1, 2.5f / 8f);
            stroke(instrumentation, MotionEvent.TOOL_TYPE_FINGER, InputDevice.SOURCE_TOUCHSCREEN,
                    left + width * 0.2f, top + height * 0.7f, left + width * 0.8f,
                    top + height * 0.6f, 1f, 1f, false);
            instrumentation.waitForIdleSync();
            Drawing afterFinger = onMain(scenario, drawing);
            assertEquals(2, afterFinger.getStrokes().size());
            Stroke marker = afterFinger.getStrokes().get(1);
            assertEquals(Brush.TYPE_MARKER, marker.getBrush().getType());
            assertEquals(0xE53935, marker.getBrush().getColor() & 0xFFFFFF);
            assertTrue("a finger has no pressure", Float.isNaN(marker.getPressure(0)));
            screenshot(instrumentation, "ink-2-finger-marker");

            // The pen's eraser end, down through both.
            stroke(instrumentation, MotionEvent.TOOL_TYPE_ERASER, InputDevice.SOURCE_STYLUS,
                    left + width * 0.5f, top + height * 0.15f, left + width * 0.5f,
                    top + height * 0.85f, 0.5f, 0.5f, false);
            instrumentation.waitForIdleSync();
            Drawing afterErase = onMain(scenario, drawing);
            assertEquals(3, afterErase.getStrokes().size());
            assertTrue(afterErase.getStrokes().get(2).getBrush().isErasing());
            screenshot(instrumentation, "ink-3-erased");

            // Undo the erase, then redo it.
            scenario.onActivity(activity -> drawing.get().undo());
            instrumentation.waitForIdleSync();
            SystemClock.sleep(700);
            assertEquals(2, onMain(scenario, drawing).getStrokes().size());
            screenshot(instrumentation, "ink-4-undone");
            scenario.onActivity(activity -> drawing.get().redo());
            instrumentation.waitForIdleSync();
            SystemClock.sleep(700);
            assertEquals(3, onMain(scenario, drawing).getStrokes().size());

            // What it saves to is Muse's format, and reads back the same.
            Drawing saved = Drawing.fromJson(onMain(scenario, drawing).toJson());
            assertEquals(3, saved.getStrokes().size());
            assertEquals(pen.getPressure(5), saved.getStrokes().get(0).getPressure(5), 1e-6f);
            Bitmap ink = saved.toBitmap(800, 600);
            proof("ink-drawing-800x600", ink);
            assertFalse("the eraser cleared the middle of the pen stroke",
                    Color.alpha(ink.getPixel(400, 195)) > 0
                            && Color.alpha(ink.getPixel(400, 200)) > 0
                            && Color.alpha(ink.getPixel(400, 205)) > 0);
            Log.i(TAG, "Prediction " + view.stylus().prediction() + ", tilt "
                    + view.stylus().tilt() + ", buttons " + view.stylus().buttons());
            SystemClock.sleep(800);
        }
    }

    /** A stroke from one point to another along a gentle curve, through the system's input. */
    private static void stroke(@NonNull Instrumentation instrumentation, int tool, int source,
            float x0, float y0, float x1, float y1, float pressureFrom, float pressureTo,
            boolean leanAtEnd) {
        long down = SystemClock.uptimeMillis();
        for (int i = 0; i <= SAMPLES; i++) {
            float t = i / (float) SAMPLES;
            float x = x0 + (x1 - x0) * t;
            float y = y0 + (y1 - y0) * t + (float) Math.sin(t * Math.PI * 2) * 40f;
            float pressure = pressureFrom + (pressureTo - pressureFrom) * t;
            float tilt = leanAtEnd ? (float) Math.toRadians(50 * t) : 0f;
            int action = i == 0 ? MotionEvent.ACTION_DOWN
                    : i == SAMPLES ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
            long time = down + i * STROKE_MILLIS / SAMPLES;
            MotionEvent event = event(down, time, action, tool, source, x, y, pressure, tilt);
            instrumentation.sendPointerSync(event);
            event.recycle();
            SystemClock.sleep(STROKE_MILLIS / SAMPLES);
        }
        SystemClock.sleep(500);
    }

    @NonNull
    private static MotionEvent event(long down, long time, int action, int tool, int source,
            float x, float y, float pressure, float tilt) {
        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
        properties.id = 0;
        properties.toolType = tool;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = x;
        coords.y = y;
        coords.pressure = pressure;
        coords.size = 0.1f;
        coords.setAxisValue(MotionEvent.AXIS_TILT, tilt);
        coords.orientation = 0f;
        return MotionEvent.obtain(down, time, action, 1,
                new MotionEvent.PointerProperties[] {properties},
                new MotionEvent.PointerCoords[] {coords}, 0, 0, 1f, 1f, 0, 0, source, 0);
    }

    /** Taps a row of the brush panel at a fraction of its width. */
    private static void tapChild(@NonNull Instrumentation instrumentation,
            @NonNull BrushPanelView panel, int row, float fraction) {
        View child = panel.getChildAt(row);
        int[] at = new int[2];
        child.getLocationOnScreen(at);
        float x = at[0] + child.getWidth() * fraction;
        float y = at[1] + child.getHeight() / 2f;
        long down = SystemClock.uptimeMillis();
        MotionEvent press = event(down, down, MotionEvent.ACTION_DOWN,
                MotionEvent.TOOL_TYPE_FINGER, InputDevice.SOURCE_TOUCHSCREEN, x, y, 1f, 0f);
        instrumentation.sendPointerSync(press);
        press.recycle();
        MotionEvent release = event(down, down + 60, MotionEvent.ACTION_UP,
                MotionEvent.TOOL_TYPE_FINGER, InputDevice.SOURCE_TOUCHSCREEN, x, y, 1f, 0f);
        instrumentation.sendPointerSync(release);
        release.recycle();
        SystemClock.sleep(500);
    }

    @NonNull
    private static Drawing onMain(@NonNull ActivityScenario<ViewerTestActivity> scenario,
            @NonNull AtomicReference<DrawingView> view) {
        AtomicReference<Drawing> drawing = new AtomicReference<>();
        scenario.onActivity(activity -> drawing.set(view.get().getDrawing()));
        assertNotNull(drawing.get());
        return drawing.get();
    }

    @NonNull
    private static Bitmap quarters(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        int[] colors = {0xFFE0E0E0, 0xFFB0C4DE, 0xFFF5DEB3, 0xFFC1E1C1};
        for (int i = 0; i < 4; i++) {
            paint.setColor(colors[i]);
            canvas.drawRect((i % 2) * width / 2f, (i / 2) * height / 2f,
                    (i % 2 + 1) * width / 2f, (i / 2 + 1) * height / 2f, paint);
        }
        return bitmap;
    }

    private static void screenshot(@NonNull Instrumentation instrumentation,
            @NonNull String name) throws IOException {
        SystemClock.sleep(300);
        Bitmap screen = instrumentation.getUiAutomation().takeScreenshot();
        if (screen != null) {
            proof(name, screen);
        }
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
