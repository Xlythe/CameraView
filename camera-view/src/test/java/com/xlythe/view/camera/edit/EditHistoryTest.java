package com.xlythe.view.camera.edit;

import static com.xlythe.view.camera.edit.InkEvents.event;
import static com.xlythe.view.camera.edit.InkEvents.finger;
import static com.xlythe.view.camera.edit.InkEvents.move;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Color;
import android.view.MotionEvent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** One undo across a drawing and its stickers, in the order the steps were taken. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 37)
public class EditHistoryTest {
    private DrawingView drawing;
    private OverlayView overlays;
    private final EditHistory history = new EditHistory();
    private int changes;

    @Before
    public void setUp() {
        drawing = new DrawingView(RuntimeEnvironment.getApplication());
        drawing.layout(0, 0, 400, 200);
        drawing.setDrawingSize(800, 400);
        overlays = new OverlayView(RuntimeEnvironment.getApplication());
        overlays.layout(0, 0, 400, 200);
        overlays.setCanvasSize(800, 400);
        history.attach(drawing);
        history.attach(overlays);
        history.setOnHistoryChangedListener(() -> changes++);
    }

    @Test
    public void undoTakesBackTheLastStepWhicheverViewTookIt() {
        stroke(20);
        overlays.addText("first", Color.WHITE);
        stroke(60);
        assertTrue(history.canUndo());
        assertFalse(history.canRedo());

        history.undo();
        assertEquals("the second stroke went first", 1, drawing.getDrawing().getStrokes().size());
        assertEquals(1, overlays.getOverlays().size());
        history.undo();
        assertEquals("then the text", 0, overlays.getOverlays().size());
        assertEquals(1, drawing.getDrawing().getStrokes().size());
        history.undo();
        assertEquals(0, drawing.getDrawing().getStrokes().size());
        assertFalse(history.canUndo());

        history.redo();
        history.redo();
        assertEquals(1, drawing.getDrawing().getStrokes().size());
        assertEquals("the text came back second", 1, overlays.getOverlays().size());
        history.redo();
        assertEquals(2, drawing.getDrawing().getStrokes().size());
        assertFalse(history.canRedo());
        assertTrue(changes >= 9);
    }

    @Test
    public void aNewStepLeavesNothingToRedo() {
        stroke(20);
        overlays.addText("text", Color.WHITE);
        history.undo();
        assertTrue(history.canRedo());
        stroke(60);
        assertFalse(history.canRedo());
        history.redo();
        assertEquals("a redo with nothing to redo does nothing", 0,
                overlays.getOverlays().size());
    }

    @Test
    public void undoingOnAViewItselfKeepsTheOrder() {
        stroke(20);
        overlays.addText("text", Color.WHITE);
        drawing.undo();
        assertEquals(0, drawing.getDrawing().getStrokes().size());
        history.undo();
        assertEquals("the text is next", 0, overlays.getOverlays().size());
        assertFalse(history.canUndo());
        history.redo();
        assertEquals(1, overlays.getOverlays().size());
        history.redo();
        assertEquals(1, drawing.getDrawing().getStrokes().size());
    }

    @Test
    public void showingAViewSomethingNewForgetsItsSteps() {
        stroke(20);
        overlays.addText("text", Color.WHITE);
        drawing.setDrawingSize(800, 400);
        history.undo();
        assertEquals(0, overlays.getOverlays().size());
        assertFalse("the drawing's stroke went with its steps", history.canUndo());
    }

    private void stroke(float y) {
        drawing.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 0, finger(0, 20, y)));
        drawing.dispatchTouchEvent(move(10, finger(0, 120, y)));
        drawing.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 20, finger(0, 220, y)));
    }
}
