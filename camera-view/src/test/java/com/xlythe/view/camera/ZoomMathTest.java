package com.xlythe.view.camera;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The zoom arithmetic every backend shares.
 *
 * <p>The phone used throughout is a Galaxy S25 Ultra, from what its camera publishes: a logical
 * back camera with a 0.6x to 10x range in front of a 6.3mm lens on a 9.792mm-wide sensor, and
 * physical lenses of 2.2mm on 5.712mm, 7.9mm on 4.48mm and 18.6mm on 5.712mm.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class ZoomMathTest {
    private static final float TOLERANCE = 0.0001f;

    /** The ends of the slider are the ends of the range, exactly. */
    @Test
    public void theLevelScaleSpansTheWholeRange() {
        assertEquals(0.6f, ZoomMath.ratioForLevel(0, 0.6f, 10f), 0f);
        assertEquals(10f, ZoomMath.ratioForLevel(ZoomMath.MAX_ZOOM_LEVEL, 0.6f, 10f), 0f);

        // Out-of-range levels clamp rather than running past the camera's limits.
        assertEquals(0.6f, ZoomMath.ratioForLevel(-20, 0.6f, 10f), 0f);
        assertEquals(10f, ZoomMath.ratioForLevel(500, 0.6f, 10f), 0f);
    }

    /**
     * Halfway along the slider is the geometric middle of the range, not the arithmetic one, so
     * equal travel multiplies the zoom by the same amount wherever it happens.
     */
    @Test
    public void theLevelScaleIsLogarithmic() {
        assertEquals("1x to 4x has 2x in the middle",
                2f, ZoomMath.ratioForLevel(50, 1f, 4f), TOLERANCE);
        assertEquals("and 0.5x to 8x has 2x there too",
                2f, ZoomMath.ratioForLevel(50, 0.5f, 8f), TOLERANCE);
        assertEquals("a quarter of the way is the square root of the middle",
                (float) Math.sqrt(2), ZoomMath.ratioForLevel(25, 1f, 4f), TOLERANCE);

        // On a phone with an ultra-wide, 1x is about a fifth of the way along rather than 4%.
        assertEquals(18, ZoomMath.levelForRatio(1f, 0.6f, 10f));
    }

    /** Every level comes back as itself, so a slider never jumps when it is read back. */
    @Test
    public void theInverseIsExact() {
        float[][] ranges = {{0.6f, 10f}, {1f, 4f}, {1f, 8f}, {0.5f, 100f}, {1f, 1.01f}};
        for (float[] range : ranges) {
            for (int level = 0; level <= ZoomMath.MAX_ZOOM_LEVEL; level++) {
                float ratio = ZoomMath.ratioForLevel(level, range[0], range[1]);
                assertEquals("level " + level + " of " + range[0] + "-" + range[1],
                        level, ZoomMath.levelForRatio(ratio, range[0], range[1]));
            }
        }

        // A ratio outside the range reads as the end it is beyond.
        assertEquals(0, ZoomMath.levelForRatio(0.1f, 0.6f, 10f));
        assertEquals(0, ZoomMath.levelForRatio(0.6f, 0.6f, 10f));
        assertEquals(ZoomMath.MAX_ZOOM_LEVEL, ZoomMath.levelForRatio(10f, 0.6f, 10f));
        assertEquals(ZoomMath.MAX_ZOOM_LEVEL, ZoomMath.levelForRatio(50f, 0.6f, 10f));
    }

    /** A camera that cannot zoom has one ratio, and every level means it. */
    @Test
    public void aCameraThatCannotZoomStaysAtItsOneRatio() {
        assertEquals(1f, ZoomMath.ratioForLevel(50, 1f, 1f), 0f);
        assertEquals(0, ZoomMath.levelForRatio(1f, 1f, 1f));

        // Nor does a range that makes no sense invent one.
        assertEquals(0f, ZoomMath.ratioForLevel(50, 0f, 4f), 0f);
        assertEquals(0, ZoomMath.levelForRatio(2f, 0f, 4f));
        assertEquals(0, ZoomMath.levelForRatio(Float.NaN, 1f, 4f));
    }

    @Test
    public void clampingHoldsARatioInsideTheRange() {
        assertEquals(0.6f, ZoomMath.clamp(0.1f, 0.6f, 10f), 0f);
        assertEquals(10f, ZoomMath.clamp(40f, 0.6f, 10f), 0f);
        assertEquals(3f, ZoomMath.clamp(3f, 0.6f, 10f), 0f);
    }

    /**
     * A lens sits where its field of view does, which needs the sensor behind it as well as its
     * focal length: the S25's ultra-wide is a third of the main lens's focal length, but sits at
     * 0.6x because its sensor is smaller.
     */
    @Test
    public void aLensSitsWhereItsFieldOfViewDoes() {
        assertEquals(1f, ZoomMath.lensStop(6.3f, 9.792f, 6.3f, 9.792f), TOLERANCE);
        assertEquals(0.599f, ZoomMath.lensStop(2.2f, 5.712f, 6.3f, 9.792f), 0.001f);
        assertEquals(2.741f, ZoomMath.lensStop(7.9f, 4.48f, 6.3f, 9.792f), 0.001f);
        assertEquals(5.061f, ZoomMath.lensStop(18.6f, 5.712f, 6.3f, 9.792f), 0.001f);
    }

    /** A camera that does not publish its optics has no stop worked out for it. */
    @Test
    public void unpublishedOpticsHaveNoStop() {
        assertEquals(0f, ZoomMath.lensStop(0f, 5.712f, 6.3f, 9.792f), 0f);
        assertEquals(0f, ZoomMath.lensStop(2.2f, 0f, 6.3f, 9.792f), 0f);
        assertEquals(0f, ZoomMath.lensStop(2.2f, 5.712f, 0f, 9.792f), 0f);
        assertEquals(0f, ZoomMath.lensStop(2.2f, 5.712f, 6.3f, 0f), 0f);
    }

    /** 2x is only a stop where the main sensor has twice the pixels of the largest picture. */
    @Test
    public void twoTimesNeedsTwiceThePixels() {
        assertTrue("a 200MP sensor binned to 12MP", ZoomMath.canCropTo2x(16320, 4080));
        assertTrue("exactly twice is enough", ZoomMath.canCropTo2x(8160, 4080));
        assertFalse("a 12MP sensor would have to upscale", ZoomMath.canCropTo2x(4080, 4080));
        assertFalse("and no pictures means no answer", ZoomMath.canCropTo2x(4080, 0));
    }

    /** The S25 Ultra: an ultra-wide at the bottom of the range, and two telephotos. */
    @Test
    public void presetsComeFromTheLenses() {
        float[] lenses = {
                ZoomMath.lensStop(2.2f, 5.712f, 6.3f, 9.792f),
                ZoomMath.lensStop(6.3f, 9.792f, 6.3f, 9.792f),
                ZoomMath.lensStop(7.9f, 4.48f, 6.3f, 9.792f),
                ZoomMath.lensStop(18.6f, 5.712f, 6.3f, 9.792f),
        };

        float[] presets = ZoomMath.presets(0.6f, 10f, lenses, false /* cropStop */);

        // The ultra-wide works out a hair under the range, so the wide end stands for it, and the
        // main lens is 1x itself.
        assertArrayEquals(new float[] {0.6f, 1f, 2.741f, 5.061f}, presets, 0.001f);
    }

    /** With no lenses to go on, 1x and the wide end are still stops. */
    @Test
    public void oneTimesAndTheWideEndAreAlwaysStops() {
        assertArrayEquals(new float[] {1f}, ZoomMath.presets(1f, 8f, new float[0], false), 0f);
        assertArrayEquals(new float[] {0.6f, 1f},
                ZoomMath.presets(0.6f, 10f, new float[0], false), 0f);
        assertArrayEquals("a camera that cannot zoom has the one stop",
                new float[] {1f}, ZoomMath.presets(1f, 1f, new float[0], true), 0f);

        // A lens that did not publish its optics is left out rather than becoming a 0x stop.
        assertArrayEquals(new float[] {1f}, ZoomMath.presets(1f, 8f, new float[] {0f}, false), 0f);
    }

    /** 2x is added for a sensor that can crop to it, unless a lens is already about there. */
    @Test
    public void twoTimesIsAStopWhereNoLensIs() {
        assertArrayEquals(new float[] {0.6f, 1f, 2f, 5f},
                ZoomMath.presets(0.6f, 10f, new float[] {5f}, true), 0f);
        assertArrayEquals("a 2.1x lens is close enough to stand for it",
                new float[] {0.6f, 1f, 2.1f, 5f},
                ZoomMath.presets(0.6f, 10f, new float[] {2.1f, 5f}, true), 0f);
        assertArrayEquals("as is a 1.9x one",
                new float[] {1f, 1.9f}, ZoomMath.presets(1f, 10f, new float[] {1.9f}, true), 0f);
        assertArrayEquals("but a 2.74x lens is not",
                new float[] {1f, 2f, 2.74f}, ZoomMath.presets(1f, 10f, new float[] {2.74f}, true),
                0f);
    }

    /**
     * Stops within ten percent of one another are one stop, and the ratios the camera reaches
     * exactly, 1x and the wide end, win.
     */
    @Test
    public void nearbyStopsMerge() {
        assertArrayEquals(new float[] {0.6f, 1f, 3f},
                ZoomMath.presets(0.6f, 10f, new float[] {0.63f, 1.05f, 3f, 3.1f}, false), 0f);
        assertArrayEquals("eleven percent apart is two stops",
                new float[] {1f, 3f, 3.33f},
                ZoomMath.presets(1f, 10f, new float[] {3.33f, 3f}, false), 0.001f);
    }

    /** A lens reads as phone camera apps label it: whole numbers from 2x, a decimal below. */
    @Test
    public void labelsRoundLikeACameraApp() {
        assertArrayEquals("the S25 Ultra's lenses", new float[] {0.6f, 1f, 3f, 5f},
                ZoomMath.labels(new float[] {0.6f, 1f, 2.7408f, 5.0612f}), 0f);
        assertArrayEquals("halves round up", new float[] {1.5f, 2f, 3f, 8f},
                ZoomMath.labels(new float[] {1.5f, 1.95f, 2.5f, 7.99f}), 0f);
        assertArrayEquals(new float[0], ZoomMath.labels(new float[0]), 0f);
    }

    /** Two stops that would round to one label each get another decimal place. */
    @Test
    public void labelsThatWouldMatchGetADecimal() {
        assertArrayEquals("a 2x crop beside a 2.3x lens", new float[] {1f, 2f, 2.3f},
                ZoomMath.labels(new float[] {1f, 2f, 2.3f}), 0f);
        assertArrayEquals("eleven percent apart", new float[] {1f, 3f, 3.3f},
                ZoomMath.labels(new float[] {1f, 3f, 3.33f}), 0f);
        assertArrayEquals("two ultra-wides need two places", new float[] {0.3f, 0.33f},
                ZoomMath.labels(new float[] {0.3f, 0.33f}), 0f);
        assertArrayEquals("and identical stops stop at two places",
                new float[] {2f, 2f}, ZoomMath.labels(new float[] {2f, 2f}), 0f);
    }

    /** A stop outside the range is pulled into it, where it merges with the end it reached. */
    @Test
    public void stopsAreClampedToTheRange() {
        assertArrayEquals(new float[] {1f, 4f},
                ZoomMath.presets(1f, 4f, new float[] {0.6f, 5f}, false), 0f);
        assertArrayEquals("2x on a camera that stops at 1.5x becomes the top of the range",
                new float[] {1f, 1.5f}, ZoomMath.presets(1f, 1.5f, new float[0], true), 0f);
        assertArrayEquals("and 1x on a camera that starts above it becomes the bottom",
                new float[] {1.2f}, ZoomMath.presets(1.2f, 4f, new float[0], false), 0f);
    }
}
