package com.xlythe.view.camera.edit;

import static com.xlythe.view.camera.edit.InkEvents.event;
import static com.xlythe.view.camera.edit.InkEvents.pen;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The brush panel, tapped and dragged, keeping a drawing surface's brush in step. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 37)
public class BrushPanelViewTest {
    private BrushPanelView panel;
    private DrawingView drawing;
    private final List<Brush> chosen = new ArrayList<>();

    @Before
    public void setUp() {
        panel = new BrushPanelView(RuntimeEnvironment.getApplication());
        panel.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.AT_MOST));
        panel.layout(0, 0, 600, panel.getMeasuredHeight());
        drawing = new DrawingView(RuntimeEnvironment.getApplication());
        drawing.layout(0, 0, 400, 200);
        panel.setDrawingView(drawing);
        panel.setOnBrushChangedListener(chosen::add);
    }

    @Test
    public void tappingABrushChoosesItInTheChosenColour() {
        tap(row(0), 2.5f / 6 * 600);
        assertEquals(Brush.TYPE_MARKER, panel.getBrush().getType());
        assertSame(panel.getBrush(), drawing.getBrush());
        assertEquals(1, chosen.size());
        // The marker keeps its translucency in the panel's colour.
        assertEquals(Color.alpha(Brush.MARKER.getColor()), Color.alpha(panel.getBrush()
                .getColor()));
    }

    @Test
    public void tappingAColourRecoloursTheBrush() {
        tap(row(1), 2.5f / 8 * 600);
        assertEquals(0xFFE53935, panel.getBrush().getColor());
        assertEquals(0xFFE53935, drawing.getBrush().getColor());
    }

    @Test
    public void draggingAlongTheStrokeSetsTheWidth() {
        View size = ((ViewGroup) row(2)).getChildAt(0);
        tap(size, size.getWidth() - 1);
        assertEquals(BrushPanelView.MAX_WIDTH_DP, panel.getBrush().getWidth(), 0.5f);
        tap(size, 0);
        assertEquals(BrushPanelView.MIN_WIDTH_DP, panel.getBrush().getWidth(), 0.01f);
        assertEquals(1f, BrushPanelView.widthAt(0f), 1e-4f);
        assertEquals(0.5f, BrushPanelView.fractionOf(BrushPanelView.widthAt(0.5f)), 1e-4f);
    }

    @Test
    public void draggingAlongTheBarSetsTheOpacity() {
        View opacity = row(3);
        tap(opacity, opacity.getWidth() / 2f);
        assertEquals(0.5f, panel.getBrush().getOpacity(), 0.05f);
    }

    @Test
    public void aBrushSetOnTheSurfaceIsShownByThePanel() {
        drawing.setBrush(Brush.PENCIL);
        assertEquals(Brush.TYPE_PENCIL, panel.getBrush().getType());
        assertTrue("showing it is not choosing it", chosen.isEmpty());
    }

    @Test
    public void thePressureControlAppearsOnceAPenReportsPressure() {
        View pressure = ((ViewGroup) row(2)).getChildAt(1);
        assertEquals(View.GONE, pressure.getVisibility());
        drawing.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 0, pen(10, 10, 0.5f)));
        assertEquals(View.VISIBLE, pressure.getVisibility());
        tap(pressure, 5);
        assertEquals("pressure off: an even width", panel.getBrush().getPressureMaxScale(),
                panel.getBrush().getPressureMinScale(), 0f);
        tap(pressure, 5);
        assertNotEquals(panel.getBrush().getPressureMaxScale(),
                panel.getBrush().getPressureMinScale(), 0f);
    }

    @Test
    public void theBrushesAndColoursOfferedCanBeChanged() {
        panel.setBrushes(Arrays.asList(Brush.CHARCOAL, Brush.ERASER));
        assertEquals(2, panel.getBrushes().size());
        assertEquals(Brush.TYPE_PENCIL, panel.getBrush().getType());
        panel.setColors(new int[] {Color.CYAN});
        assertEquals(1, panel.getColors().length);
        assertThrows(IllegalArgumentException.class, () -> panel.setBrushes(new ArrayList<>()));
        assertThrows(IllegalArgumentException.class, () -> panel.setColors(new int[0]));
    }

    @Test
    public void eachRowCanBeHidden() {
        panel.setBrushControlShown(false);
        panel.setColorControlShown(false);
        panel.setSizeControlShown(false);
        panel.setOpacityControlShown(false);
        for (int i = 0; i < 4; i++) {
            assertEquals(View.GONE, row(i).getVisibility());
        }
    }

    @Test
    public void thePanelDrawsWithoutText() {
        Bitmap bitmap = Bitmap.createBitmap(600, panel.getHeight(), Bitmap.Config.ARGB_8888);
        panel.draw(new Canvas(bitmap));
        int inked = 0;
        for (int y = 0; y < bitmap.getHeight(); y += 2) {
            for (int x = 0; x < bitmap.getWidth(); x += 2) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 0) {
                    inked++;
                }
            }
        }
        assertTrue("it draws its controls", inked > 1000);
    }

    private View row(int index) {
        return panel.getChildAt(index);
    }

    private static void tap(View view, float x) {
        float y = view.getHeight() / 2f;
        view.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0));
        view.dispatchTouchEvent(MotionEvent.obtain(0, 10, MotionEvent.ACTION_UP, x, y, 0));
    }
}
