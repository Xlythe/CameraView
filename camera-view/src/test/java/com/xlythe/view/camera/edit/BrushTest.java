package com.xlythe.view.camera.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Brushes and how they answer pressure, lean and speed, as Muse's do. */
public class BrushTest {
    @Test
    public void aDeviceThatCannotTellDrawsTheRestingWidth() {
        // Not a made-up middle value: the full-pressure, upright, still width.
        assertEquals(3f, Brush.PEN.widthFor(Float.NaN, Float.NaN, Float.NaN), 0f);
        assertEquals(Brush.INKING_PEN.getWidth(),
                Brush.INKING_PEN.widthFor(Float.NaN, Float.NaN, Float.NaN), 0f);
    }

    @Test
    public void pressureFollowsTheCurve() {
        Brush pen = Brush.PEN;
        assertEquals(0.75f, pen.widthFor(0f, Float.NaN, Float.NaN), 1e-5f);
        assertEquals(3f, pen.widthFor(1f, Float.NaN, Float.NaN), 1e-5f);
        assertEquals(3f, pen.widthFor(7f, Float.NaN, Float.NaN), 1e-5f);
        Brush firm = new Brush.Builder().setPressureResponse(0f, 1f, 2f).setWidth(10).build();
        assertEquals(2.5f, firm.widthFor(0.5f, Float.NaN, Float.NaN), 1e-4f);
    }

    @Test
    public void aLeanedPencilBroadensAndPales() {
        Brush pencil = Brush.PENCIL;
        float lean = pencil.leanOf(60f, 0f);
        assertEquals(1f, lean, 1e-6f);
        assertEquals(pencil.getWidth() * 2.4f, pencil.widthFor(1f, lean, Float.NaN), 1e-4f);
        assertEquals(0.85f * 0.55f, pencil.alphaFor(lean), 1e-5f);
        assertEquals(0.85f, pencil.alphaFor(Float.NaN), 1e-6f);
        assertTrue(Float.isNaN(pencil.leanOf(Float.NaN, 3f)));
        assertEquals(0.5f, pencil.leanOf(18f, 24f), 1e-5f);
    }

    @Test
    public void aFlickedInkingPenTapers() {
        Brush pen = Brush.INKING_PEN;
        float still = pen.widthFor(1f, Float.NaN, 0f);
        float fast = pen.widthFor(1f, Float.NaN, 10f);
        assertEquals(pen.getWidth() * 0.55f, fast, 1e-4f);
        assertTrue(fast < still);
    }

    @Test
    public void grainIsTheSameEverywhereItIsDrawn() {
        Brush charcoal = Brush.CHARCOAL;
        assertEquals(charcoal.coverageAt(12.5f, 7.25f, 0.3f),
                charcoal.coverageAt(12.5f, 7.25f, 0.3f), 0f);
        assertEquals(1f, Brush.PEN.coverageAt(1, 2, 0), 0f);
        float noise = Brush.noise(3.7f, 9.1f, Brush.DEFAULT_SEED);
        assertTrue(noise >= 0f && noise <= 1f);
        // A dry brush runs out towards the end of its stroke.
        float start = 0;
        float end = 0;
        for (int i = 0; i < 400; i++) {
            start += Brush.DRY_BRUSH.coverageAt(i * 0.37f, i * 0.91f, 0f);
            end += Brush.DRY_BRUSH.coverageAt(i * 0.37f, i * 0.91f, 1f);
        }
        assertTrue(end < start);
        Brush speckle = new Brush.Builder().setGrain(Brush.GRAIN_SPECKLE, 0.5f, 2f, 7).build();
        float specks = speckle.coverageAt(1.3f, 2.9f, 0f);
        assertTrue(specks == 0f || specks == 1f);
        assertTrue(speckle.isStamped());
    }

    @Test
    public void theStockBrushesAreWhatTheySay() {
        assertTrue(Brush.ERASER.isErasing());
        assertFalse(Brush.PEN.isErasing());
        assertTrue(Brush.PENCIL.isStamped());
        assertFalse(Brush.PEN.isStamped());
        assertEquals(Brush.TYPE_MARKER, Brush.MARKER.getType());
        assertEquals(0.4f, Brush.MARKER.getOpacity(), 0f);
        assertEquals(Brush.GRAIN_DRY, Brush.DRY_BRUSH.getGrain());
        assertTrue(Brush.PENCIL.maxRadius() > Brush.PENCIL.getWidth() / 2f);
    }

    @Test
    public void copiesChangeOnlyWhatTheySay() {
        Brush red = Brush.PENCIL.withColor(0xFFFF0000);
        assertEquals(0xFFFF0000, red.getColor());
        assertEquals(Brush.PENCIL.getGrainDepth(), red.getGrainDepth(), 0f);
        assertEquals(Brush.PENCIL.getTiltFlatScale(), red.getTiltFlatScale(), 0f);
        assertEquals(9f, red.withWidth(9f).getWidth(), 0f);
        assertEquals(0.3f, red.withOpacity(0.3f).getOpacity(), 0f);
        assertEquals(Brush.PENCIL, new Brush.Builder(Brush.PENCIL).build());
        assertEquals(Brush.PENCIL.hashCode(), new Brush.Builder(Brush.PENCIL).build().hashCode());
        assertNotEquals(Brush.PENCIL, red);
        assertNotEquals(Brush.PENCIL, "pencil");
        assertTrue(red.toString().contains("ffff0000"));
    }

    @Test
    public void nonsenseIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Brush.Builder().setWidth(0));
        assertThrows(IllegalArgumentException.class, () -> new Brush.Builder().setOpacity(2));
        assertThrows(IllegalArgumentException.class,
                () -> new Brush.Builder().setPressureResponse(1, 0.5f, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new Brush.Builder().setTiltResponse(1, 1, 1, 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Brush.Builder().setVelocityResponse(1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Brush.Builder().setSpacing(-1));
        assertThrows(IllegalArgumentException.class, () -> new Brush.Builder().setFlow(-0.1f));
        assertThrows(IllegalArgumentException.class,
                () -> new Brush.Builder().setGrain(Brush.GRAIN_CHALK, 0.5f, 0f, 1));
    }
}
