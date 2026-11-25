package com.xlythe.view.camera;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37)
@LooperMode(LooperMode.Mode.PAUSED)
public class CameraViewTest {

    private Context context;
    private CameraView cameraView;

    @Before
    public void setup() {
        context = RuntimeEnvironment.application;
        cameraView = new CameraView(context);
    }

    @Test
    public void testInitialProperties() {
        assertNotNull(cameraView);
        assertEquals(CameraView.Quality.HIGH, cameraView.getQuality());
        assertTrue(cameraView.isPinchToZoomEnabled());
    }

    @Test
    public void programmaticConstructionCreatesPreviewViews() {
        assertEquals(3, cameraView.getChildCount());
        assertNotNull(cameraView.asTextureView());
    }

    @Test
    public void tapsClickButDragsDoNot() {
        AtomicInteger clicks = new AtomicInteger();
        cameraView.setOnClickListener(view -> clicks.incrementAndGet());
        cameraView.layout(0, 0, 200, 200);
        long now = SystemClock.uptimeMillis();

        cameraView.onTouchEvent(MotionEvent.obtain(now, now,
                MotionEvent.ACTION_DOWN, 50, 50, 0));
        cameraView.onTouchEvent(MotionEvent.obtain(now, now + 10,
                MotionEvent.ACTION_UP, 50, 50, 0));
        assertEquals(1, clicks.get());

        cameraView.onTouchEvent(MotionEvent.obtain(now, now + 20,
                MotionEvent.ACTION_DOWN, 50, 50, 0));
        cameraView.onTouchEvent(MotionEvent.obtain(now, now + 30,
                MotionEvent.ACTION_MOVE, 150, 150, 0));
        cameraView.onTouchEvent(MotionEvent.obtain(now, now + 40,
                MotionEvent.ACTION_UP, 150, 150, 0));
        assertEquals(1, clicks.get());
    }

    @Test
    public void pinchScaleSpansTheWholeZoomRange() {
        // A pinch that has not moved stays at the wide end, and a fully spread one has to reach
        // the maximum rather than stopping short of it.
        assertEquals(0, CameraView.zoomLevelForScale(1f, 5f, 100));
        assertEquals(100, CameraView.zoomLevelForScale(5f, 5f, 100));
        assertEquals(50, CameraView.zoomLevelForScale(3f, 5f, 100));

        // Values outside the pinch range clamp instead of running past the camera's limits.
        assertEquals(0, CameraView.zoomLevelForScale(0.1f, 5f, 100));
        assertEquals(100, CameraView.zoomLevelForScale(50f, 5f, 100));

        // A camera that cannot zoom, or a degenerate range, stays at zero.
        assertEquals(0, CameraView.zoomLevelForScale(5f, 5f, 0));
        assertEquals(0, CameraView.zoomLevelForScale(5f, 1f, 100));
    }

    @Test
    public void testPropertySetters() {
        cameraView.setQuality(CameraView.Quality.MAX);
        assertEquals(CameraView.Quality.MAX, cameraView.getQuality());

        cameraView.setPinchToZoomEnabled(false);
        assertFalse(cameraView.isPinchToZoomEnabled());
    }

    @Test
    public void optionalCameraControlsRoundTrip() {
        cameraView.setDynamicRange(CameraView.DynamicRange.HDR);
        cameraView.setExtension(CameraView.Extension.NIGHT);
        cameraView.setFrontVideoMirroringEnabled(false);
        cameraView.setPreviewStabilizationEnabled(true);
        cameraView.setVideoStabilizationEnabled(true);
        cameraView.setExposureCompensation(2);
        cameraView.setTorchEnabled(true);
        cameraView.setLowLightBoostEnabled(true);

        assertEquals(CameraView.DynamicRange.HDR, cameraView.getDynamicRange());
        assertEquals(CameraView.Extension.NIGHT, cameraView.getExtension());
        assertFalse(cameraView.isFrontVideoMirroringEnabled());
        assertTrue(cameraView.isPreviewStabilizationEnabled());
        assertTrue(cameraView.isVideoStabilizationEnabled());
        assertEquals(2, cameraView.getExposureCompensation());
        assertTrue(cameraView.isTorchEnabled());
        assertTrue(cameraView.isLowLightBoostEnabled());
        assertFalse(cameraView.isDynamicRangeSupported(CameraView.DynamicRange.HDR));
        assertFalse(cameraView.isExtensionSupported(CameraView.Extension.NIGHT));
        assertTrue(cameraView.isExtensionSupported(CameraView.Extension.NONE));
        assertFalse(cameraView.isPreviewStabilizationSupported());
        assertFalse(cameraView.isVideoStabilizationSupported());
        assertFalse(cameraView.isExposureCompensationSupported());
        assertFalse(cameraView.isLowLightBoostSupported());
    }
    @Test
    public void lifecycleTracksWaitingAndClosedInternally() throws Exception {
        assertEquals("CLOSED", lifecycleState(cameraView));
        cameraView.open();
        assertEquals("WAITING_FOR_SURFACE", lifecycleState(cameraView));
        assertFalse(cameraView.isOpen());

        cameraView.open();
        cameraView.close();
        assertEquals("CLOSED", lifecycleState(cameraView));
    }

    @Test
    public void backgroundLifecycleCallsAreSerializedOnMainLooper() throws Exception {
        Thread thread = new Thread(cameraView::open);
        thread.start();
        thread.join();

        assertEquals("CLOSED", lifecycleState(cameraView));
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals("WAITING_FOR_SURFACE", lifecycleState(cameraView));
    }

    private static String lifecycleState(CameraView view) throws Exception {
        java.lang.reflect.Field state = CameraView.class.getDeclaredField("mStatus");
        state.setAccessible(true);
        return state.get(view).toString();
    }
}
