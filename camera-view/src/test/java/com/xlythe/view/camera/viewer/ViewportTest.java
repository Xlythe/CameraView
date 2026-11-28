package com.xlythe.view.camera.viewer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Placing a picture in a view: zoom, pan, limits and wrapping. */
public class ViewportTest {
    private static final float EPSILON = 1e-3f;

    @Test
    public void notReadyWithoutBothSizes() {
        Viewport viewport = new Viewport();
        assertFalse(viewport.isReady());
        viewport.setViewSize(100, 100);
        assertFalse(viewport.isReady());
        viewport.setImage(10, 10, false);
        assertTrue(viewport.isReady());
        viewport.setViewSize(0, 100);
        assertFalse(viewport.isReady());
        // Moving an unready viewport changes nothing it cannot account for.
        viewport.moveTo(5, 5);
        assertEquals(5, viewport.getX(), 0);
    }

    /** Zoomed all the way out, a picture fits and is centred on its shorter axis. */
    @Test
    public void fitsAndCentres() {
        Viewport viewport = viewport(1000, 2000, 4000, 3000, false);
        assertEquals(0.25f, viewport.fitScale(), EPSILON);
        viewport.reset(viewport.fitScale(), 2000, 1500);

        assertEquals(0, viewport.getX(), EPSILON);
        assertEquals((2000 - 750) / 2f, viewport.getY(), EPSILON);
        assertEquals(Viewport.MAX_PIXEL_SCALE, viewport.getMaxScale(), EPSILON);
        assertFalse(viewport.canPan());
        assertEquals(0, viewport.panRangeX(), 0);
        assertEquals(0, viewport.panRangeY(), 0);
    }

    /** A small picture may still be zoomed to four times its fit. */
    @Test
    public void aSmallPictureZoomsFourTimesItsFit() {
        Viewport viewport = viewport(1000, 1000, 100, 100, false);
        viewport.reset(viewport.fitScale(), 50, 50);
        assertEquals(10f, viewport.getMinScale(), EPSILON);
        assertEquals(40f, viewport.getMaxScale(), EPSILON);
    }

    /** Zooming keeps the picture's pixel under the fingers where it is, within the limits. */
    @Test
    public void zoomsAboutAPoint() {
        Viewport viewport = viewport(1000, 1000, 4000, 4000, false);
        viewport.reset(viewport.fitScale(), 2000, 2000);

        // The picture's pixel (1000, 1000) is under view point (250, 250).
        viewport.scaleBy(2f, 250, 250);
        assertEquals(0.5f, viewport.getScale(), EPSILON);
        assertEquals(250, viewport.getX() + 1000 * viewport.getScale(), EPSILON);
        assertEquals(250, viewport.getY() + 1000 * viewport.getScale(), EPSILON);
        assertTrue(viewport.canPan());

        viewport.scaleBy(100f, 250, 250);
        assertEquals(Viewport.MAX_PIXEL_SCALE, viewport.getScale(), EPSILON);
        viewport.scaleTo(0.001f, 0, 0);
        assertEquals(0.25f, viewport.getScale(), EPSILON);
    }

    /** Panning stops at the picture's edges. */
    @Test
    public void pansWithinTheEdges() {
        Viewport viewport = viewport(1000, 1000, 4000, 4000, false);
        viewport.reset(1f, 2000, 2000);

        viewport.panBy(100000, 100000);
        assertEquals(0, viewport.getX(), EPSILON);
        assertEquals(0, viewport.getY(), EPSILON);
        viewport.panBy(-100000, -100000);
        assertEquals(-3000, viewport.getX(), EPSILON);
        assertEquals(-3000, viewport.getY(), EPSILON);
        assertEquals(3000, viewport.panRangeX(), EPSILON);
        assertEquals(3000, viewport.panRangeY(), EPSILON);

        double[] visible = viewport.visible(0);
        assertArrayEquals(new double[] {3000, 3000, 4000, 4000}, visible, 1e-6);
        assertEquals(0, viewport.lastCopy());
    }

    /** A panorama fills the height and repeats across, with no edge to stop at. */
    @Test
    public void aWrappingPictureRepeats() {
        Viewport viewport = viewport(1000, 500, 3000, 500, true);
        assertEquals(1f, viewport.fillHeightScale(), EPSILON);
        viewport.reset(viewport.fillHeightScale(), 1500, 250);
        assertTrue(viewport.wraps());
        assertTrue(viewport.canPan());
        // The middle is in the middle of the view.
        assertEquals(-1000, viewport.getX(), EPSILON);

        // Up to the right edge: the end of the picture, then its start again.
        viewport.panBy(-1500, 0);
        assertEquals(-2500, viewport.getX(), EPSILON);
        assertEquals(1, viewport.lastCopy());
        assertArrayEquals(new double[] {2500, 0, 3000, 500}, viewport.visible(0), 1e-6);
        assertArrayEquals(new double[] {0, 0, 500, 500}, viewport.visible(1), 1e-6);

        // Past it, the offset comes back round within one repeat.
        viewport.panBy(-1000, 0);
        assertEquals(-500, viewport.getX(), EPSILON);
        assertEquals(0, viewport.lastCopy());

        // And far round the other way.
        viewport.panBy(3000 * 7 + 250, 0);
        assertEquals(-250, viewport.getX(), EPSILON);
    }

    /** A wrapping picture narrower than the view shows as many repeats as it takes. */
    @Test
    public void aNarrowWrappingPictureRepeatsAcrossTheView() {
        Viewport viewport = viewport(1000, 100, 300, 100, true);
        viewport.reset(1f, 150, 50);
        // The first repeat starts 250 left of the view, and four more reach past its right.
        assertEquals(-250, viewport.getX(), EPSILON);
        assertEquals(4, viewport.lastCopy());
        assertArrayEquals(new double[] {0, 0, 50, 100}, viewport.visible(4), 1e-6);
    }

    /** A repeat entirely off screen shows nothing. */
    @Test
    public void aRepeatOffScreenIsEmpty() {
        Viewport viewport = viewport(100, 100, 1000, 100, false);
        viewport.reset(1f, 50, 50);
        double[] visible = viewport.visible(5);
        assertEquals(visible[0], visible[2], 0);
    }

    private static Viewport viewport(int viewWidth, int viewHeight, int imageWidth,
            int imageHeight, boolean wraps) {
        Viewport viewport = new Viewport();
        viewport.setViewSize(viewWidth, viewHeight);
        viewport.setImage(imageWidth, imageHeight, wraps);
        return viewport;
    }
}
