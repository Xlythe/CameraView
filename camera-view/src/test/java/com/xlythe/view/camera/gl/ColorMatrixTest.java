package com.xlythe.view.camera.gl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * What a filter does to a colour.
 *
 * <p>The shader that applies these is four lines and hard to get wrong. The coefficients are the
 * opposite: a transposed matrix, a luminance weight in the wrong channel or a contrast scaled
 * about black instead of mid-grey all produce an image that looks filtered, just not the way it
 * was meant to, and nothing short of looking at it would say so. So the arithmetic is pinned
 * here against colours whose answers are known.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class ColorMatrixTest {
    private static final float TOLERANCE = 0.001f;

    private static final float[] WHITE = {1, 1, 1, 1};
    private static final float[] BLACK = {0, 0, 0, 1};
    private static final float[] MID_GREY = {0.5f, 0.5f, 0.5f, 1};
    private static final float[] RED = {1, 0, 0, 1};

    /** The matrix that changes nothing changes nothing. */
    @Test
    public void identityLeavesAColourAlone() {
        assertColour(RED, ColorMatrix.apply(ColorMatrix.identity(), RED));
        assertColour(MID_GREY, ColorMatrix.apply(ColorMatrix.identity(), MID_GREY));
    }

    /**
     * Grey is the luminance of the pixel, and the weights are what make that believable.
     *
     * <p>Pure red at Rec. 601 weights is 0.299, which is dark. The naive average that is the
     * usual mistake gives 0.333 and makes red and blue come out the same shade, which is the
     * thing to notice.</p>
     */
    @Test
    public void grayscaleUsesLuminanceRatherThanAnAverage() {
        float[] grey = ColorMatrix.apply(ColorMatrix.grayscale(), RED);

        assertEquals(0.299f, grey[0], TOLERANCE);
        assertEquals("every channel the same, which is what grey means", grey[0], grey[1],
                TOLERANCE);
        assertEquals(grey[1], grey[2], TOLERANCE);

        float[] blue = ColorMatrix.apply(ColorMatrix.grayscale(), new float[] {0, 0, 1, 1});
        assertTrue("blue is darker than red, which an average would miss",
                blue[0] < grey[0]);
    }

    /** Saturation of one is the identity, and the two ends behave. */
    @Test
    public void saturationSpansFromGreyToExaggerated() {
        assertColour(RED, ColorMatrix.apply(ColorMatrix.saturation(1), RED));
        assertArrayEquals("fully desaturated is grayscale",
                ColorMatrix.apply(ColorMatrix.grayscale(), RED),
                ColorMatrix.apply(ColorMatrix.saturation(0), RED), TOLERANCE);

        float[] vivid = ColorMatrix.apply(ColorMatrix.saturation(1.5f),
                new float[] {0.6f, 0.4f, 0.4f, 1});
        assertTrue("the dominant channel should pull away", vivid[0] > 0.6f);
        assertTrue("and the others should fall back", vivid[1] < 0.4f);
    }

    /** Grey stays grey however hard the contrast is pushed, which is what "about mid" means. */
    @Test
    public void contrastPivotsAboutMidGrey() {
        assertColour(MID_GREY, ColorMatrix.apply(ColorMatrix.contrast(2f), MID_GREY));
        assertColour(MID_GREY, ColorMatrix.apply(ColorMatrix.contrast(0.5f), MID_GREY));

        float[] lighter = ColorMatrix.apply(ColorMatrix.contrast(2f), new float[] {0.6f, 0.6f, 0.6f, 1});
        assertTrue("above mid goes up", lighter[0] > 0.6f);
        float[] darker = ColorMatrix.apply(ColorMatrix.contrast(2f), new float[] {0.4f, 0.4f, 0.4f, 1});
        assertTrue("below mid goes down", darker[0] < 0.4f);
    }

    /** Flattening all the way lands on grey rather than on black. */
    @Test
    public void noContrastIsGreyNotBlack() {
        assertColour(MID_GREY, ColorMatrix.apply(ColorMatrix.contrast(0), WHITE));
        assertColour(MID_GREY, ColorMatrix.apply(ColorMatrix.contrast(0), BLACK));
    }

    /** Brightness moves every channel by the same amount, and the result is clamped. */
    @Test
    public void brightnessShiftsAndClamps() {
        float[] lifted = ColorMatrix.apply(ColorMatrix.brightness(0.25f), MID_GREY);
        assertEquals(0.75f, lifted[0], TOLERANCE);

        assertColour(WHITE, ColorMatrix.apply(ColorMatrix.brightness(0.5f), WHITE));
        assertColour(BLACK, ColorMatrix.apply(ColorMatrix.brightness(-0.5f), BLACK));
    }

    /** Sepia warms a grey, which is the whole point of it. */
    @Test
    public void sepiaWarmsNeutralColours() {
        float[] warmed = ColorMatrix.apply(ColorMatrix.sepia(), MID_GREY);

        assertTrue("red leads", warmed[0] > warmed[1]);
        assertTrue("and blue trails", warmed[1] > warmed[2]);
    }

    /** Inverting twice is where you started. */
    @Test
    public void invertingTwiceIsTheIdentity() {
        float[] once = ColorMatrix.apply(ColorMatrix.invert(), RED);
        float[] twice = ColorMatrix.apply(ColorMatrix.invert(), once);

        assertColour(RED, twice);
    }

    /** A tint scales channels against each other without shifting them. */
    @Test
    public void aTintScalesRatherThanShifts() {
        float[] cooled = ColorMatrix.apply(ColorMatrix.tint(0.9f, 1f, 1.1f), MID_GREY);

        assertEquals(0.45f, cooled[0], TOLERANCE);
        assertEquals(0.5f, cooled[1], TOLERANCE);
        assertEquals(0.55f, cooled[2], TOLERANCE);
        assertColour(BLACK, ColorMatrix.apply(ColorMatrix.tint(0.9f, 1f, 1.1f), BLACK));
    }

    /**
     * Combining applies the first matrix first, which is the order it is easy to get backwards.
     *
     * <p>Checked with two operations that do not commute: brightening then flattening is not
     * the same as flattening then brightening, so if the order were reversed this would say
     * so.</p>
     */
    @Test
    public void combiningAppliesTheFirstMatrixFirst() {
        float[] brighten = ColorMatrix.brightness(0.2f);
        float[] flatten = ColorMatrix.contrast(0.5f);

        float[] brightenThenFlatten = ColorMatrix.apply(
                ColorMatrix.concat(brighten, flatten), BLACK);
        float[] stepwise = ColorMatrix.apply(flatten, ColorMatrix.apply(brighten, BLACK));

        assertArrayEquals("concat(a, b) must mean a and then b",
                stepwise, brightenThenFlatten, TOLERANCE);

        float[] theOtherWay = ColorMatrix.apply(ColorMatrix.concat(flatten, brighten), BLACK);
        assertTrue("and the two orders must differ, or this proves nothing",
                Math.abs(theOtherWay[0] - brightenThenFlatten[0]) > TOLERANCE);
    }

    /** Every matrix is the sixteen floats GL expects. */
    @Test
    public void everyMatrixIsTheRightSize() {
        assertEquals(16, ColorMatrix.identity().length);
        assertEquals(16, ColorMatrix.sepia().length);
        assertEquals(16, ColorMatrix.saturation(0.5f).length);
        assertEquals(16, ColorMatrix.contrast(1.2f).length);
        assertEquals(16, ColorMatrix.brightness(0.1f).length);
        assertEquals(16, ColorMatrix.invert().length);
        assertEquals(16, ColorMatrix.tint(1, 1, 1).length);
    }

    private static void assertColour(float[] expected, float[] actual) {
        assertArrayEquals(expected, actual, TOLERANCE);
    }
}
