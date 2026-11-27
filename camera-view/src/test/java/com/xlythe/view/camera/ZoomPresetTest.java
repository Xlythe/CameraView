package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.Locale;

/** A preset keeps the exact ratio to zoom to beside the rounded label to show. */
public class ZoomPresetTest {
    /** The S25 Ultra's telephoto reads 3 and still zooms to 2.74. */
    @Test
    public void theLabelIsRoundedAndTheRatioIsNot() {
        List<ZoomPreset> presets =
                ZoomPreset.of(new float[] {0.6f, 1f, 2.7408f, 5.0612f}, Locale.US);

        assertEquals(4, presets.size());
        assertEquals("0.6", presets.get(0).getLabel());
        assertEquals("1", presets.get(1).getLabel());
        assertEquals("3", presets.get(2).getLabel());
        assertEquals(2.7408f, presets.get(2).getRatio(), 0f);
        assertEquals("5", presets.get(3).getLabel());
        assertEquals(5.0612f, presets.get(3).getRatio(), 0f);
    }

    /** The label is a number in the locale's own style. */
    @Test
    public void theLabelFollowsTheLocale() {
        List<ZoomPreset> presets = ZoomPreset.of(new float[] {0.6f, 0.33f, 0.3f}, Locale.GERMANY);

        assertEquals("0,6", presets.get(0).getLabel());
        assertEquals("0,33", presets.get(1).getLabel());
        assertEquals("0,3", presets.get(2).getLabel());
    }

    /** The list handed out cannot be changed under the camera. */
    @Test
    public void theListIsReadOnly() {
        List<ZoomPreset> presets = ZoomPreset.of(new float[] {1f}, Locale.US);

        assertThrows(UnsupportedOperationException.class, () -> presets.add(presets.get(0)));
    }

    /** Two presets are equal when both their ratio and their label are. */
    @Test
    public void equalityCoversTheRatioAndTheLabel() {
        ZoomPreset three = new ZoomPreset(2.74f, "3");

        assertEquals(three, three);
        assertEquals(three, new ZoomPreset(2.74f, "3"));
        assertEquals(three.hashCode(), new ZoomPreset(2.74f, "3").hashCode());
        assertNotEquals(three, new ZoomPreset(3f, "3"));
        assertNotEquals(three, new ZoomPreset(2.74f, "2.7"));
        assertNotEquals(three, "3");
        assertNotEquals(three, null);
        assertTrue(three.toString().contains("2.74"));
        assertTrue(three.toString().contains("label=3"));
    }
}
