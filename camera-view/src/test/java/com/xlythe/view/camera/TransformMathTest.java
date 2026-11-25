package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Size;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The center-crop mapping shared by the preview and the frame analyzers.
 *
 * <p>Stated as properties rather than expected matrix values, because what callers depend on is
 * that a point on a face lands on that face: the view is covered, nothing is distorted, and the
 * rotation goes the right way round.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class TransformMathTest {
    private static final float TOLERANCE = 0.01f;

    /**
     * The angle a lens sees across a sensor, which is what turns a bearing into a screen position.
     *
     * <p>Checked against arithmetic that can be done by hand: a 36mm-wide frame behind a 50mm
     * lens is the classic case, and 2*atan(36/100) is 39.6 degrees.</p>
     */
    @Test
    public void aFieldOfViewComesFromTheSensorAndTheLens() {
        assertEquals(39.60f,
                TransformMath.fieldOfViewDegrees(36f /* mm */, 50f /* mm */), 0.05f);
        assertEquals("the same frame is narrower the short way across",
                26.99f, TransformMath.fieldOfViewDegrees(24f, 50f), 0.05f);

        // A phone: a small sensor behind a very short lens, which is why it sees so much.
        assertEquals(67.63f, TransformMath.fieldOfViewDegrees(5.76f, 4.3f), 0.05f);

        // A longer lens on the same sensor sees less.
        assertTrue("a longer lens must see less",
                TransformMath.fieldOfViewDegrees(5.76f, 6f)
                        < TransformMath.fieldOfViewDegrees(5.76f, 4.3f));
    }

    /** A camera that will not say how big its sensor is gets no answer invented for it. */
    @Test
    public void anUnknownSensorOrLensHasNoFieldOfView() {
        assertEquals(0f, TransformMath.fieldOfViewDegrees(0f, 50f), 0f);
        assertEquals(0f, TransformMath.fieldOfViewDegrees(36f, 0f), 0f);
        assertEquals(0f, TransformMath.fieldOfViewDegrees(-36f, 50f), 0f);
        assertEquals(0f, TransformMath.fieldOfViewDegrees(36f, -50f), 0f);
    }

    /**
     * Zooming narrows the view, but not in proportion to the zoom.
     *
     * <p>This is the whole reason the method exists. Doubling the zoom halves the width of what
     * is visible, which halves the tangent of the half-angle rather than the angle - so a 60
     * degree view at 2x is 32.2 degrees, not 30. Anything drawing by bearing and dividing
     * instead would put its marker in the wrong place, and more wrongly the further from centre
     * it is.</p>
     */
    @Test
    public void zoomNarrowsTheViewByTangentAndNotByRatio() {
        assertEquals(32.20f, TransformMath.zoomedFieldOfViewDegrees(60f, 2f), 0.05f);
        assertTrue("dividing the angle would have given 30",
                TransformMath.zoomedFieldOfViewDegrees(60f, 2f) > 30f);

        assertEquals("no zoom is no change",
                60f, TransformMath.zoomedFieldOfViewDegrees(60f, 1f), 0.001f);
        assertTrue("more zoom is always less view",
                TransformMath.zoomedFieldOfViewDegrees(60f, 4f)
                        < TransformMath.zoomedFieldOfViewDegrees(60f, 2f));

        // A logical camera can report a ratio below one, which is a wider view than its own 1x.
        assertTrue("a ratio below one widens the view",
                TransformMath.zoomedFieldOfViewDegrees(60f, 0.5f) > 60f);
    }

    /** And nothing to narrow, or no ratio to narrow it by, answers nothing. */
    @Test
    public void anUnknownZoomOrViewHasNoAngle() {
        assertEquals(0f, TransformMath.zoomedFieldOfViewDegrees(0f, 2f), 0f);
        assertEquals(0f, TransformMath.zoomedFieldOfViewDegrees(60f, 0f), 0f);
        assertEquals(0f, TransformMath.zoomedFieldOfViewDegrees(60f, -2f), 0f);
    }

    @Test
    public void rotationsNormalizeIntoACircle() {
        assertEquals(0, TransformMath.within360(0));
        assertEquals(90, TransformMath.within360(90));
        assertEquals(270, TransformMath.within360(-90));
        assertEquals(90, TransformMath.within360(450));
        assertEquals(180, TransformMath.within360(-900));
    }

    @Test
    public void onlyQuarterTurnsTransposeASize() {
        Size size = new Size(640, 480);
        assertEquals(size, TransformMath.rotateSize(size, 0));
        assertEquals(size, TransformMath.rotateSize(size, 180));
        assertEquals(new Size(480, 640), TransformMath.rotateSize(size, 90));
        assertEquals(new Size(480, 640), TransformMath.rotateSize(size, -90));
    }

    /** A frame already the shape of the view maps onto it exactly. */
    @Test
    public void aMatchingAspectRatioFillsTheViewWithoutCropping() {
        Matrix transform = TransformMath.centerCrop(
                new RectF(0f, 0f, 640f, 480f), 0, false, 1280, 960);

        assertPointMapsTo(transform, 0f, 0f, 0f, 0f);
        assertPointMapsTo(transform, 640f, 480f, 1280f, 960f);
        assertPointMapsTo(transform, 320f, 240f, 640f, 480f);
    }

    /**
     * A quarter turn puts the frame upright, and the frame's corners land where the rotation says.
     *
     * <p>A 640x480 sensor frame rotated 90 degrees is 480 wide by 640 tall, which is exactly the
     * shape of a portrait view, so nothing is cropped and the top-left of the frame becomes the
     * top-right of the view.</p>
     */
    @Test
    public void aQuarterTurnPutsTheFrameUpright() {
        Matrix transform = TransformMath.centerCrop(
                new RectF(0f, 0f, 640f, 480f), 90, false, 480, 640);

        assertPointMapsTo(transform, 0f, 0f, 480f, 0f);
        assertPointMapsTo(transform, 640f, 0f, 480f, 640f);
        assertPointMapsTo(transform, 0f, 480f, 0f, 0f);
        assertPointMapsTo(transform, 320f, 240f, 240f, 320f);
    }

    /** Covering the view costs the overflow, split evenly, and never costs the aspect ratio. */
    @Test
    public void aWiderFrameIsCroppedEvenlyRatherThanSquashed()  {
        Matrix transform = TransformMath.centerCrop(
                new RectF(0f, 0f, 1600f, 800f), 0, false, 400, 400);

        float[] values = new float[9];
        transform.getValues(values);
        assertEquals("the scale has to be the same on both axes",
                values[Matrix.MSCALE_X], values[Matrix.MSCALE_Y], TOLERANCE);

        // Scaled to cover a 400x400 view, the frame is 800 wide, so 200 is lost from each side.
        assertPointMapsTo(transform, 0f, 0f, -200f, 0f);
        assertPointMapsTo(transform, 1600f, 800f, 600f, 400f);
        // And the center of the frame is still the center of the view.
        assertPointMapsTo(transform, 800f, 400f, 200f, 200f);
    }

    @Test
    public void mirroringReflectsAcrossTheViewRatherThanMovingTheFrame() {
        RectF frame = new RectF(0f, 0f, 640f, 480f);
        Matrix plain = TransformMath.centerCrop(frame, 0, false, 640, 480);
        Matrix mirrored = TransformMath.centerCrop(frame, 0, true, 640, 480);

        assertPointMapsTo(plain, 0f, 100f, 0f, 100f);
        assertPointMapsTo(mirrored, 0f, 100f, 640f, 100f);
        assertPointMapsTo(mirrored, 640f, 100f, 0f, 100f);
    }

    /** A crop that is a region of the frame, as CameraX reports, maps that region onto the view. */
    @Test
    public void onlyTheCropIsShown() {
        Matrix transform = TransformMath.centerCrop(
                new RectF(160f, 120f, 480f, 360f), 0, false, 320, 240);

        assertPointMapsTo(transform, 160f, 120f, 0f, 0f);
        assertPointMapsTo(transform, 480f, 360f, 320f, 240f);
    }

    @Test
    public void nothingToMapGivesTheIdentity() {
        assertTrue(TransformMath.centerCrop(new RectF(), 0, false, 100, 100).isIdentity());
        assertTrue(TransformMath.centerCrop(
                new RectF(0f, 0f, 10f, 10f), 0, false, 0, 100).isIdentity());
        assertTrue(TransformMath.centerCrop(
                new RectF(0f, 0f, 10f, 10f), 0, false, 100, 0).isIdentity());
    }

    /**
     * A TextureView has already stretched the buffer over itself, so a mapping that only stretches
     * the buffer the same way has nothing left to do.
     */
    @Test
    public void aTextureTransformIsRelativeToTheStretchTextureViewAlreadyApplied() {
        Size buffer = new Size(640, 480);
        Matrix bufferToView = TransformMath.centerCrop(
                new RectF(0f, 0f, 640f, 480f), 0, false, 1280, 960);

        Matrix textureTransform =
                TransformMath.asTextureTransform(bufferToView, buffer, 1280, 960);

        assertTrue("a plain stretch needs no texture transform", textureTransform.isIdentity());
    }

    /** And the rotation survives the change of frame of reference. */
    @Test
    public void aRotatedTextureTransformStillMapsTheViewOntoItself() {
        Size buffer = new Size(640, 480);
        Matrix bufferToView = TransformMath.centerCrop(
                new RectF(0f, 0f, 640f, 480f), 90, false, 480, 640);

        Matrix textureTransform =
                TransformMath.asTextureTransform(bufferToView, buffer, 480, 640);

        // The stretch maps the frame's top-left to the view's top-left, and the transform then has
        // to put it where the rotation says it belongs.
        assertPointMapsTo(textureTransform, 0f, 0f, 480f, 0f);
        assertPointMapsTo(textureTransform, 240f, 320f, 240f, 320f);
    }

    @Test
    public void anEmptyBufferOrViewGivesTheIdentityTextureTransform() {
        Matrix bufferToView = new Matrix();
        bufferToView.setScale(2f, 2f);

        assertTrue(TransformMath.asTextureTransform(
                bufferToView, new Size(0, 480), 100, 100).isIdentity());
        assertTrue(TransformMath.asTextureTransform(
                bufferToView, new Size(640, 480), 0, 100).isIdentity());
    }

    /**
     * A touch becomes the area the camera is told to focus on, in the camera's own coordinates.
     *
     * <p>The middle of the view is the middle of that space whichever way round the camera is,
     * which is the one case where every orientation has to agree.</p>
     */
    @Test
    public void aTouchInTheMiddleMetersTheMiddleAtEveryOrientation() {
        for (int rotation : new int[] {0, 90, 180, 270}) {
            Rect area = TransformMath.meteringArea(
                    500f, 1000f, 1000, 2000, rotation, 1f /* coefficient */);

            assertEquals("centered horizontally at " + rotation,
                    0, area.centerX());
            assertEquals("centered vertically at " + rotation, 0, area.centerY());
            assertEquals("a sixth of the frame across at " + rotation, 300, area.width());
        }
    }

    /** The coefficient is what makes a metering area larger than the focus area inside it. */
    @Test
    public void theCoefficientScalesTheArea() {
        Rect focus = TransformMath.meteringArea(500f, 1000f, 1000, 2000, 0, 1f);
        Rect metering = TransformMath.meteringArea(500f, 1000f, 1000, 2000, 0, 1.5f);

        assertEquals(300, focus.width());
        assertEquals(450, metering.width());
        assertTrue("the larger area has to contain the smaller", metering.contains(focus));
    }

    /**
     * Which corner of the sensor a corner of the view is depends on how the camera is turned.
     *
     * <p>This is the part that is easy to get wrong and impossible to notice: a camera focusing
     * on the opposite corner from the one that was touched still focuses on something.</p>
     */
    @Test
    public void theOrientationDecidesWhichCornerOfTheSensorATouchLandsOn() {
        assertEquals("upright, the top left of the view is the top left of the sensor",
                new Rect(-1000, -1000, -850, -850),
                TransformMath.meteringArea(0f, 0f, 1000, 2000, 0, 1f));

        assertEquals("upside down, it is the far corner",
                new Rect(850, 850, 1000, 1000),
                TransformMath.meteringArea(0f, 0f, 1000, 2000, 180, 1f));

        // Sideways the axes are transposed, so a touch at the left edge, a quarter of the way
        // down, meters a quarter of the way across and at the top.
        assertEquals(new Rect(-650, -1000, -350, -850),
                TransformMath.meteringArea(0f, 500f, 1000, 2000, 90, 1f));
    }

    /** A view with no size yet is not a reason to meter on the middle of the sensor. */
    @Test
    public void aViewWithNoSizeProducesNoArea() {
        assertTrue(TransformMath.meteringArea(10f, 10f, 0, 1000, 0, 1f).isEmpty());
        assertTrue(TransformMath.meteringArea(10f, 10f, 1000, 0, 0, 1f).isEmpty());
        assertTrue(TransformMath.viewArea(
                new Rect(-100, -100, 100, 100), 0, 1000, 0).isEmpty());
        assertTrue(TransformMath.viewArea(
                new Rect(-100, -100, 100, 100), 1000, 0, 0).isEmpty());
    }

    /**
     * An area the camera was given maps back onto the view it came from.
     *
     * <p>Which is what an app drawing a focus affordance needs: it is told where the camera is
     * looking, not where the finger was, and the two have to be the same place.</p>
     */
    @Test
    public void aMeteringAreaMapsBackOntoThePointItCameFrom() {
        for (int rotation : new int[] {0, 90, 180, 270, -90, 450}) {
            for (float[] point : new float[][] {{500f, 1000f}, {250f, 400f}, {900f, 1800f}}) {
                Rect area = TransformMath.meteringArea(
                        point[0], point[1], 1000, 2000, rotation, 1f);

                RectF onView = TransformMath.viewArea(area, 1000, 2000, rotation);

                String where = "(" + point[0] + ", " + point[1] + ") at " + rotation;
                assertEquals("x of " + where, point[0], onView.centerX(), 1f);
                assertEquals("y of " + where, point[1], onView.centerY(), 1f);
                assertTrue("and an area, not a point, at " + where,
                        onView.width() > 0 && onView.height() > 0);
            }
        }
    }

    /**
     * An area clamped into the camera's range maps back to where the camera is really looking.
     *
     * <p>A touch in the corner cannot be the middle of a metering area, because half of that area
     * would be off the sensor. Reporting the clamped area rather than the touch is the honest
     * answer, and it keeps the reported area inside the view.</p>
     */
    @Test
    public void anAreaClampedAtAnEdgeStaysInsideTheView() {
        Rect area = TransformMath.meteringArea(0f, 0f, 1000, 2000, 0, 1f);

        RectF onView = TransformMath.viewArea(area, 1000, 2000, 0);

        assertEquals(0f, onView.left, TOLERANCE);
        assertEquals(0f, onView.top, TOLERANCE);
        assertTrue("the corner of the view is the corner of the area",
                onView.width() > 0 && onView.width() < 1000f);
        assertTrue(onView.height() > 0 && onView.height() < 2000f);
    }

    private static void assertPointMapsTo(
            Matrix transform, float x, float y, float expectedX, float expectedY) {
        float[] point = {x, y};
        transform.mapPoints(point);
        PointF mapped = new PointF(point[0], point[1]);
        assertEquals("x of (" + x + ", " + y + ") -> " + mapped,
                expectedX, mapped.x, TOLERANCE);
        assertEquals("y of (" + x + ", " + y + ") -> " + mapped,
                expectedY, mapped.y, TOLERANCE);
    }
}
