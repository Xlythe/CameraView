package com.xlythe.view.camera.gl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.util.Size;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public final class GlMathTest {
    /** A sensor-oriented 16:9 frame, which is what a camera hands out for a 16:9 preview. */
    private static final Size LANDSCAPE_FRAME = new Size(1920, 1080);

    /** A portrait view of that frame, which is the shape a phone held upright asks for. */
    private static final Size PORTRAIT_OUTPUT = new Size(1080, 1920);

    /** Scales x by two and translates y by three, column-major as GL reads it. */
    private static final float[] SCALE_X_TRANSLATE_Y = {
            2f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 3f, 0f, 1f,
    };

    /** Flips s, which is the mirror a front camera's transform carries. */
    private static final float[] FLIP_S = {
            -1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            1f, 0f, 0f, 1f,
    };

    @Test
    public void identityIsTheOnlyMatrixThatChangesNothing() {
        assertArrayEquals(new float[] {
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f,
        }, GlMath.identity(), 0f);
    }

    @Test
    public void identityIsAFreshArrayEachTime() {
        float[] first = GlMath.identity();
        first[0] = 7f;
        assertEquals(1f, GlMath.identity()[0], 0f);
    }

    @Test
    public void multiplyingByIdentityLeavesAMatrixAlone() {
        assertArrayEquals(SCALE_X_TRANSLATE_Y,
                GlMath.multiply(SCALE_X_TRANSLATE_Y, GlMath.identity()), 0f);
        assertArrayEquals(SCALE_X_TRANSLATE_Y,
                GlMath.multiply(GlMath.identity(), SCALE_X_TRANSLATE_Y), 0f);
    }

    /**
     * The right matrix acts first, which is the order that matters: a mirror applied before a
     * scale is not the same frame as one applied after, and getting it the wrong way round is how
     * a front camera comes out backwards.
     */
    @Test
    public void multiplyAppliesTheRightMatrixFirst() {
        float[] mirrorThenScale = GlMath.multiply(SCALE_X_TRANSLATE_Y, FLIP_S);
        float[] scaleThenMirror = GlMath.multiply(FLIP_S, SCALE_X_TRANSLATE_Y);

        // Column three is the translation, and it is where the two orders differ: the scale
        // doubles the mirror's translation only when the mirror came first.
        assertEquals(2f, mirrorThenScale[12], 0f);
        assertEquals(1f, scaleThenMirror[12], 0f);
    }

    @Test
    public void multiplyRefusesAMatrixThatIsNotFourByFour() {
        assertThrows(IllegalArgumentException.class,
                () -> GlMath.multiply(new float[9], GlMath.identity()));
        assertThrows(IllegalArgumentException.class,
                () -> GlMath.multiply(GlMath.identity(), new float[0]));
    }

    @Test
    public void multiplyLeavesItsArgumentsAlone() {
        float[] left = GlMath.identity();
        float[] right = SCALE_X_TRANSLATE_Y.clone();
        GlMath.multiply(left, right);
        assertArrayEquals(GlMath.identity(), left, 0f);
        assertArrayEquals(SCALE_X_TRANSLATE_Y, right, 0f);
    }

    @Test
    public void aFrameTheShapeOfItsOutputFillsItExactly() {
        for (GlMath.ScaleType scaleType : GlMath.ScaleType.values()) {
            assertArrayEquals("under " + scaleType,
                    new int[] {0, 0, 1080, 1920},
                    GlMath.viewport(scaleType, PORTRAIT_OUTPUT, PORTRAIT_OUTPUT));
        }
    }

    /**
     * A landscape frame in a portrait output is the ordinary case for a phone, and the one the two
     * scale types disagree about.
     */
    @Test
    public void fillCenterOverscansAndFitCenterLetterboxes() {
        int[] filled = GlMath.viewport(
                GlMath.ScaleType.FILL_CENTER, LANDSCAPE_FRAME, PORTRAIT_OUTPUT);
        // Scaled by 1920/1080 to cover the height, which leaves it wider than the output.
        assertArrayEquals(new int[] {-1166, 0, 3413, 1920}, filled);
        assertTrue("fill center must cover the output", filled[2] >= PORTRAIT_OUTPUT.getWidth());
        assertTrue("fill center must cover the output", filled[3] >= PORTRAIT_OUTPUT.getHeight());

        int[] fitted = GlMath.viewport(
                GlMath.ScaleType.FIT_CENTER, LANDSCAPE_FRAME, PORTRAIT_OUTPUT);
        // Scaled by 1080/1920 to fit the width, which leaves bands above and below.
        assertArrayEquals(new int[] {0, 656, 1080, 608}, fitted);
        assertTrue("fit center must stay inside the output", fitted[2] <= PORTRAIT_OUTPUT.getWidth());
        assertTrue("fit center must stay inside the output", fitted[3] <= PORTRAIT_OUTPUT.getHeight());
    }

    @Test
    public void bothScaleTypesKeepTheFramesAspectRatio() {
        for (GlMath.ScaleType scaleType : GlMath.ScaleType.values()) {
            int[] viewport = GlMath.viewport(scaleType, LANDSCAPE_FRAME, PORTRAIT_OUTPUT);
            assertEquals("under " + scaleType,
                    LANDSCAPE_FRAME.getWidth() / (float) LANDSCAPE_FRAME.getHeight(),
                    viewport[2] / (float) viewport[3], 0.01f);
        }
    }

    /** Within a pixel, because an odd margin cannot be split evenly between two edges. */
    @Test
    public void bothScaleTypesCenterTheFrame() {
        for (GlMath.ScaleType scaleType : GlMath.ScaleType.values()) {
            int[] viewport = GlMath.viewport(scaleType, LANDSCAPE_FRAME, PORTRAIT_OUTPUT);
            int trailingX = PORTRAIT_OUTPUT.getWidth() - (viewport[0] + viewport[2]);
            int trailingY = PORTRAIT_OUTPUT.getHeight() - (viewport[1] + viewport[3]);
            assertTrue("left and right margins differ under " + scaleType,
                    Math.abs(viewport[0] - trailingX) <= 1);
            assertTrue("top and bottom margins differ under " + scaleType,
                    Math.abs(viewport[1] - trailingY) <= 1);
        }
    }

    /** A frame whose size is not known yet gets the whole output, not a zero-sized viewport. */
    @Test
    public void anUnmeasuredFrameTakesTheWholeOutput() {
        assertArrayEquals(new int[] {0, 0, 1080, 1920},
                GlMath.viewport(GlMath.ScaleType.FILL_CENTER, new Size(0, 0), PORTRAIT_OUTPUT));
        assertArrayEquals(new int[] {0, 0, 1080, 1920},
                GlMath.viewport(GlMath.ScaleType.FIT_CENTER, new Size(-1, 100), PORTRAIT_OUTPUT));
        assertArrayEquals(new int[] {0, 0, 1080, 1920},
                GlMath.viewport(GlMath.ScaleType.FIT_CENTER, new Size(100, -1), PORTRAIT_OUTPUT));
    }

    /** An output with no size yet cannot be drawn into, and must not produce a negative one. */
    @Test
    public void anUnmeasuredOutputProducesNothingToDrawInto() {
        assertArrayEquals(new int[] {0, 0, 0, 0},
                GlMath.viewport(GlMath.ScaleType.FILL_CENTER, LANDSCAPE_FRAME, new Size(0, 0)));
        assertArrayEquals(new int[] {0, 0, 0, 0},
                GlMath.viewport(GlMath.ScaleType.FIT_CENTER, LANDSCAPE_FRAME, new Size(-4, -4)));
    }

    /**
     * Four vertices, interleaved as position and texture coordinate, covering all of clip space
     * and all of the texture. Everything else about the frame arrives as the matrix, so a change
     * to this array is a change to every frame the library draws.
     */
    @Test
    public void theQuadCoversClipSpaceAndTheWholeTexture() {
        float[] quad = GlMath.fullFrameQuad();
        assertEquals(16, quad.length);
        for (int vertex = 0; vertex < 4; vertex++) {
            float x = quad[vertex * 4];
            float y = quad[vertex * 4 + 1];
            float s = quad[vertex * 4 + 2];
            float t = quad[vertex * 4 + 3];
            assertEquals("vertex " + vertex + " is not on a corner of clip space", 1f, Math.abs(x), 0f);
            assertEquals("vertex " + vertex + " is not on a corner of clip space", 1f, Math.abs(y), 0f);
            // The texture coordinate of a corner is that corner mapped from [-1, 1] to [0, 1],
            // which is what makes an untransformed draw show the whole frame the right way round.
            assertEquals("vertex " + vertex + " samples the wrong corner", (x + 1f) / 2f, s, 0f);
            assertEquals("vertex " + vertex + " samples the wrong corner", (y + 1f) / 2f, t, 0f);
        }
    }

    @Test
    public void theQuadIsAFreshArrayEachTime() {
        float[] first = GlMath.fullFrameQuad();
        first[0] = 7f;
        assertEquals(-1f, GlMath.fullFrameQuad()[0], 0f);
    }

    /** A frame a moment old on a phone that has slept for fifteen minutes since it booted. */
    @Test
    public void aTimestampIsOnWhicheverClockItIsNearer() {
        long monotonicNow = 5_000_000_000L;
        long realtimeNow = 905_000_000_000L;
        assertFalse(GlMath.isOnRealtimeClock(4_990_000_000L, monotonicNow, realtimeNow));
        assertTrue(GlMath.isOnRealtimeClock(904_990_000_000L, monotonicNow, realtimeNow));
    }

    /** A phone that has never slept has one clock, and nothing to move. */
    @Test
    public void clocksThatAgreeAreTakenAsMonotonic() {
        assertFalse(GlMath.isOnRealtimeClock(4_990_000_000L, 5_000_000_000L, 5_000_000_000L));
    }
}
