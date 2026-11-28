package com.xlythe.view.camera.edit;

import static com.xlythe.view.camera.edit.InkEvents.event;
import static com.xlythe.view.camera.edit.InkEvents.eraser;
import static com.xlythe.view.camera.edit.InkEvents.finger;
import static com.xlythe.view.camera.edit.InkEvents.move;
import static com.xlythe.view.camera.edit.InkEvents.pen;
import static com.xlythe.view.camera.edit.InkEvents.pointerAction;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Parcelable;
import android.view.MotionEvent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.ArrayList;
import java.util.List;

/**
 * The drawing surface, fed events as a pen, a finger and an eraser would make them. The view is
 * 400x200 and its drawing 800x400, so a mistake in the mapping between them shows.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 37)
public class DrawingViewTest {
    private DrawingView view;
    private final List<Drawing> changes = new ArrayList<>();

    @Before
    public void setUp() {
        view = new DrawingView(RuntimeEnvironment.getApplication());
        view.layout(0, 0, 400, 200);
        view.setDrawingSize(800, 400);
        view.setStabilization(0f);
        view.setOnDrawingChangedListener(changes::add);
    }

    @Test
    public void aPenStrokeIsKeptInTheDrawingsUnits() {
        stroke(pen(10, 20, 0.5f), pen(100, 20, 0.9f), pen(200, 50, 0.9f));
        Drawing drawing = view.getDrawing();
        assertEquals(1, drawing.getStrokes().size());
        Stroke stroke = drawing.getStrokes().get(0);
        assertEquals(20f, stroke.getX(0), 0.5f);
        assertEquals(40f, stroke.getY(0), 0.5f);
        assertEquals(400f, stroke.getX(stroke.size() - 1), 0.5f);
        assertEquals("pressure comes through", 0.5f, stroke.getPressure(0), 1e-3f);
        // The brush's width is on screen, so in the drawing's units it is twice as wide.
        float density = RuntimeEnvironment.getApplication().getResources().getDisplayMetrics()
                .density;
        assertEquals(Brush.PEN.getWidth() * density * 2, stroke.getBrush().getWidth(), 1e-3f);
        assertEquals(1, changes.size());
        assertTrue(view.canUndo());
        assertFalse(view.canRedo());
    }

    @Test
    public void undoAndRedoStepThroughStrokes() {
        stroke(pen(10, 10, 0.5f), pen(50, 10, 0.5f));
        stroke(pen(10, 50, 0.5f), pen(50, 50, 0.5f));
        assertEquals(2, view.getDrawing().getStrokes().size());
        view.undo();
        assertEquals(1, view.getDrawing().getStrokes().size());
        assertTrue(view.canRedo());
        view.redo();
        assertEquals(2, view.getDrawing().getStrokes().size());
        view.clear();
        assertTrue(view.getDrawing().isEmpty());
        view.undo();
        assertEquals("a clear can be undone", 2, view.getDrawing().getStrokes().size());
        assertEquals(6, changes.size());
    }

    @Test
    public void aCancelledStrokeLeavesNothingToUndo() {
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 0, finger(0, 10, 10)));
        view.dispatchTouchEvent(move(5, finger(0, 30, 30)));
        view.dispatchTouchEvent(event(pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1), 8,
                finger(0, 30, 30), finger(1, 200, 100)));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_CANCEL, 9, finger(0, 30, 30)));
        assertTrue(view.getDrawing().isEmpty());
        assertFalse(view.canUndo());
        assertTrue(changes.isEmpty());
    }

    @Test
    public void theEraserEndErasesOnlyWhatItTouches() {
        view.setBrush(Brush.PEN.withColor(Color.RED).withWidth(10));
        stroke(pen(20, 100, 1f), pen(380, 100, 1f));
        stroke(eraser(200, 80), eraser(200, 120));
        Drawing drawing = view.getDrawing();
        assertEquals(2, drawing.getStrokes().size());
        assertTrue(drawing.getStrokes().get(1).getBrush().isErasing());
        Bitmap shown = render();
        assertEquals(Color.RED, shown.getPixel(60, 100) | 0xFF000000);
        assertEquals("the eraser cleared the ink", 0, Color.alpha(shown.getPixel(200, 100)));
    }

    @Test
    public void erasingWholeStrokesIsOneStepHoweverLong() {
        stroke(pen(20, 50, 1f), pen(380, 50, 1f));
        stroke(pen(20, 150, 1f), pen(380, 150, 1f));
        stroke(pen(20, 190, 1f), pen(380, 190, 1f));
        view.setTool(DrawingView.TOOL_ERASE);
        view.setEraseMode(DrawingView.ERASE_WHOLE_STROKE);
        stroke(pen(200, 40, 1f), pen(200, 100, 1f), pen(200, 160, 1f));
        assertEquals("the two it crossed are gone", 1, view.getDrawing().getStrokes().size());
        view.undo();
        assertEquals("one undo brings both back", 3, view.getDrawing().getStrokes().size());
    }

    @Test
    public void aBarrelButtonErasesUnlessToldNotTo() {
        stroke(pen(20, 100, 1f), pen(380, 100, 1f));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 0,
                MotionEvent.BUTTON_STYLUS_PRIMARY, 0, pen(200, 80, 1f)));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 5,
                MotionEvent.BUTTON_STYLUS_PRIMARY, 0, pen(200, 120, 1f)));
        assertTrue(view.getDrawing().getStrokes().get(1).getBrush().isErasing());

        view.setStylusButtonAction(DrawingView.BUTTON_NONE);
        assertEquals(DrawingView.BUTTON_NONE, view.getStylusButtonAction());
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 10,
                MotionEvent.BUTTON_STYLUS_PRIMARY, 0, pen(200, 80, 1f)));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 15,
                MotionEvent.BUTTON_STYLUS_PRIMARY, 0, pen(200, 120, 1f)));
        assertFalse(view.getDrawing().getStrokes().get(2).getBrush().isErasing());
    }

    @Test
    public void fingersPassThroughWhenTheyDoNotDraw() {
        view.setFingerDrawingEnabled(false);
        assertFalse(view.isFingerDrawingEnabled());
        assertFalse(view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 0,
                finger(0, 10, 10))));
        stroke(pen(10, 10, 1f), pen(20, 20, 1f));
        assertEquals("a pen still draws", 1, view.getDrawing().getStrokes().size());
    }

    @Test
    public void aDrawingCanBeShownAndIsKeptAcrossAConfigurationChange() {
        stroke(pen(10, 10, 0.5f), pen(50, 10, 0.5f));
        Parcelable state = view.onSaveInstanceState();
        assertNotNull(state);
        DrawingView restored = new DrawingView(RuntimeEnvironment.getApplication());
        restored.layout(0, 0, 400, 200);
        restored.onRestoreInstanceState(state);
        assertEquals(1, restored.getDrawing().getStrokes().size());
        assertEquals(800f, restored.getDrawing().getWidth(), 0f);
        assertFalse("a shown drawing has nothing to undo", restored.canUndo());
    }

    @Test
    public void settingsAreKept() {
        view.setBrush(Brush.MARKER);
        assertSame(Brush.MARKER, view.getBrush());
        view.setTool(DrawingView.TOOL_ERASE);
        assertEquals(DrawingView.TOOL_ERASE, view.getTool());
        view.setEraseMode(DrawingView.ERASE_WHOLE_STROKE);
        assertEquals(DrawingView.ERASE_WHOLE_STROKE, view.getEraseMode());
        view.setStabilization(2f);
        assertEquals(1f, view.getStabilization(), 0f);
    }

    @Test
    public void aDisabledSurfaceDoesNotDraw() {
        view.setEnabled(false);
        assertFalse(view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 0, pen(1, 1, 1f))));
    }

    @Test
    public void aHoveringPenShowsTheBrushWithoutDrawing() {
        view.setBrush(Brush.PEN.withWidth(40));
        float radius = 20 * RuntimeEnvironment.getApplication().getResources()
                .getDisplayMetrics().density;
        assertTrue(view.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 0,
                pen(100, 100, 0f))));
        Bitmap shown = render();
        assertTrue("a ring is drawn round the pen",
                Color.alpha(shown.getPixel(100 + Math.round(radius), 100)) > 0);
        assertEquals("inside the ring is clear", 0, Color.alpha(shown.getPixel(100, 100)));
        assertTrue(view.getDrawing().isEmpty());
        view.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_EXIT, 1, pen(100, 100, 0f)));
        assertEquals(0, Color.alpha(render().getPixel(100 + Math.round(radius), 100)));
    }

    private void stroke(InkEvents.Pointer... samples) {
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 0, samples[0]));
        for (int i = 1; i < samples.length - 1; i++) {
            view.dispatchTouchEvent(move(i * 10, samples[i]));
        }
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, samples.length * 10,
                samples[samples.length - 1]));
    }

    private Bitmap render() {
        Bitmap bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        return bitmap;
    }
}
