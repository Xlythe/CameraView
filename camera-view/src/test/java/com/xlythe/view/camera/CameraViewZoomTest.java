package com.xlythe.view.camera;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.os.Looper;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.MotionEvent;

import com.xlythe.view.camera.shadow.ShadowAvailableTextureView;
import com.xlythe.view.camera.shadow.ShadowInjectableImageReader;
import com.xlythe.view.camera.v2.FakeCamera2;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

/**
 * Zoom through CameraView, the way an app drives it: by ratio, by level, and with a listener
 * keeping preset buttons in step.
 *
 * <p>The fake back camera crops up to 4x, and its front camera does too.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37},
        shadows = {ShadowAvailableTextureView.class, ShadowInjectableImageReader.class})
public class CameraViewZoomTest {
    private Context context;
    private CameraView cameraView;
    /** Every ratio the listener heard, in order. */
    private final List<Float> heard = new ArrayList<>();

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Shadows.shadowOf((android.app.Application) context).grantPermissions(
                Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO);
    }

    @After
    public void tearDown() {
        if (cameraView != null) {
            cameraView.close();
            idle();
        }
    }

    private void open(boolean logicalBackCamera) throws Exception {
        if (logicalBackCamera) {
            FakeCamera2.addLogicalBackCamera(context);
        } else {
            FakeCamera2.addBackAndFrontCameras(context);
        }
        cameraView = new CameraView(context);
        cameraView.onFinishInflate();
        cameraView.layout(0, 0, 1080, 1920);
        cameraView.setOnZoomChangedListener(ratio -> {
            assertTrue("delivered on the main thread", Looper.myLooper() == Looper.getMainLooper());
            heard.add(ratio);
        });
        cameraView.open();
        idle();
    }

    /** Each change is heard once, and a request that changes nothing is not heard at all. */
    @Test
    public void theListenerHearsEachChangeOnce() throws Exception {
        open(false /* logicalBackCamera */);

        cameraView.setZoomRatio(2f);
        idle();
        cameraView.setZoomRatio(2f);
        idle();
        cameraView.setZoomRatio(3f);
        idle();
        cameraView.setZoomRatio(40f);
        idle();
        cameraView.setZoomRatio(50f);
        idle();

        assertEquals("2x, 3x, and 40x held to the 4x top; the repeats are not news",
                List.of(2f, 3f, 4f), heard);
    }

    /** A level is the same zoom as a ratio, on a log scale across the camera's range. */
    @Test
    public void aLevelIsAPositionInTheRatioRange() throws Exception {
        open(false /* logicalBackCamera */);
        assertEquals(1f, cameraView.getMinZoomRatio(), 0f);
        assertEquals(4f, cameraView.getMaxZoomRatio(), 0f);

        cameraView.setZoomLevel(50);
        idle();

        assertEquals("halfway from 1x to 4x on a log scale is 2x",
                2f, cameraView.getZoomRatio(), 0.0001f);
        assertEquals(50, cameraView.getZoomLevel());
        assertEquals(List.of(cameraView.getZoomRatio()), heard);

        cameraView.setZoomRatio(4f);
        idle();
        assertEquals(cameraView.getMaxZoomLevel(), cameraView.getZoomLevel());
    }

    /** A lens switch that cannot keep the zoom is a change like any other. */
    @Test
    public void aLensSwitchThatMovesTheZoomIsHeard() throws Exception {
        open(true /* logicalBackCamera */);
        cameraView.setZoomRatio(cameraView.getMaxZoomRatio());
        idle();
        heard.clear();

        cameraView.toggleCamera();
        idle();

        assertEquals("the front camera crops to 4x at most", List.of(4f), heard);
        assertEquals(4f, cameraView.getZoomRatio(), 0f);
    }

    /** A camera with one lens and nothing to crop to 2x offers only 1x. */
    @Test
    public void aSingleLensHasOnePreset() throws Exception {
        open(false /* logicalBackCamera */);

        assertArrayEquals(new float[] {1f}, cameraView.getZoomPresets(), 0f);
    }

    /** A ratio that is not one is refused rather than handed to the camera. */
    @Test
    public void anImpossibleRatioIsIgnored() throws Exception {
        open(false /* logicalBackCamera */);
        cameraView.setZoomRatio(2f);
        idle();

        cameraView.setZoomRatio(Float.NaN);
        cameraView.setZoomRatio(0f);
        cameraView.setZoomRatio(-1f);
        cameraView.setZoomRatio(Float.POSITIVE_INFINITY);
        idle();

        assertEquals(2f, cameraView.getZoomRatio(), 0f);
        assertEquals(List.of(2f), heard);
    }

    /** A pinch multiplies the zoom it started from by how far the fingers spread. */
    @Test
    public void aPinchMultipliesTheZoom() throws Exception {
        open(false /* logicalBackCamera */);
        cameraView.setZoomRatio(1.5f);
        idle();

        pinch(400, 800);
        assertEquals("twice as far apart from 1.5x", 3f, cameraView.getZoomRatio(), 0.001f);

        pinch(800, 400);
        assertEquals("and back to half", 1.5f, cameraView.getZoomRatio(), 0.001f);
        assertEquals("each step of the pinch is heard",
                1.5f, heard.get(heard.size() - 1), 0.001f);
    }

    /** A pinch past the end of the range turns around the moment the fingers do. */
    @Test
    public void aPinchThatOvershootsTurnsAroundAtOnce() throws Exception {
        open(false /* logicalBackCamera */);
        cameraView.setZoomRatio(1.5f);
        idle();

        // 3.5 times as far apart would be 5.25x, which the 4x top of the range holds back.
        pinch(300, 1050, 525);

        assertEquals("held at 4x, then halved", 2f, cameraView.getZoomRatio(), 0.001f);
    }

    /** With pinch-to-zoom turned off, a pinch leaves the zoom alone. */
    @Test
    public void aDisabledPinchDoesNotZoom() throws Exception {
        open(false /* logicalBackCamera */);
        cameraView.setPinchToZoomEnabled(false);

        pinch(400, 800);

        assertEquals(1f, cameraView.getZoomRatio(), 0f);
        assertTrue(heard.isEmpty());
    }

    /** The zoom survives a configuration change as the ratio it was. */
    @Test
    public void theZoomSurvivesSaveAndRestore() throws Exception {
        open(false /* logicalBackCamera */);
        cameraView.setZoomRatio(2.5f);
        idle();

        cameraView.setId(1);
        SparseArray<Parcelable> container = new SparseArray<>();
        cameraView.saveHierarchyState(container);
        CameraView restored = new CameraView(context);
        restored.setId(1);
        restored.restoreHierarchyState(container);

        assertEquals(2.5f, restored.getZoomRatio(), 0f);
    }

    /**
     * Pinches across the middle of the view through a series of finger spans, in pixels.
     *
     * <p>The detector only starts once the span has moved past its slop, and measures from the
     * span it started at, so the fingers come down a little inside the first span and the scale is
     * each span over the one before it.</p>
     */
    private void pinch(float... spans) {
        long downTime = SystemClock.uptimeMillis();
        float centre = cameraView.getWidth() / 2f;
        float y = cameraView.getHeight() / 2f;
        float down = spans[0] - 100;
        touch(downTime, MotionEvent.ACTION_DOWN, centre, y, down, 1);
        touch(downTime, MotionEvent.ACTION_POINTER_DOWN
                | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), centre, y, down, 2);
        for (float span : spans) {
            touch(downTime, MotionEvent.ACTION_MOVE, centre, y, span, 2);
        }
        float last = spans[spans.length - 1];
        touch(downTime, MotionEvent.ACTION_POINTER_UP
                | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), centre, y, last, 2);
        touch(downTime, MotionEvent.ACTION_UP, centre, y, last, 1);
        idle();
    }

    /** Sends one event with one or two fingers either side of a point, a span apart. */
    private void touch(long downTime, int action, float x, float y, float span, int fingers) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[fingers];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[fingers];
        for (int i = 0; i < fingers; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = i == 0 ? x - span / 2 : x + span / 2;
            coords[i].y = y;
            coords[i].pressure = 1f;
            coords[i].size = 1f;
        }
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                fingers, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0);
        cameraView.onTouchEvent(event);
        event.recycle();
    }

    private static void idle() {
        ShadowLooper.idleMainLooper();
    }
}
