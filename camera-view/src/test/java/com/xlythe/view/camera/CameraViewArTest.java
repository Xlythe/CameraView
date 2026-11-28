package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;

import androidx.annotation.NonNull;

import com.xlythe.view.camera.gl.FrameRenderer;
import com.xlythe.view.camera.gl.GlFrame;
import com.xlythe.view.camera.shadow.ShadowAvailableTextureView;
import com.xlythe.view.camera.shadow.ShadowCallbackCameraCaptureSession;
import com.xlythe.view.camera.shadow.ShadowFrameSurfaceTexture;
import com.xlythe.view.camera.shadow.ShadowInjectableImageReader;
import com.xlythe.view.camera.shadow.ShadowRecordingGles20;
import com.xlythe.view.camera.v2.FakeArTracker;
import com.xlythe.view.camera.v2.FakeCamera2;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * AR as the view runs it: a camera of its own in place of the app's, every setting carried
 * across and back, tracking reported, and the app's camera back when AR ends either way.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, shadows = {
        ShadowAvailableTextureView.class,
        ShadowCallbackCameraCaptureSession.class,
        ShadowInjectableImageReader.class,
        ShadowFrameSurfaceTexture.class,
        ShadowRecordingGles20.class})
public class CameraViewArTest {
    private CameraView cameraView;
    private FakeArTracker tracker;
    private final List<CameraView.ArTracking> tracking = new ArrayList<>();
    private final CameraView.ArListener listener = tracking::add;
    private CameraView.ArModuleFactory original;

    @Before
    public void setUp() {
        ShadowCallbackCameraCaptureSession.reset();
        ShadowInjectableImageReader.reset();
        ShadowFrameSurfaceTexture.reset();
        ShadowRecordingGles20.reset();
        Context context = RuntimeEnvironment.getApplication();
        Shadows.shadowOf((android.app.Application) context).grantPermissions(
                Manifest.permission.CAMERA);
        FakeCamera2.addBackAndFrontCameras(context);
        cameraView = new CameraView(context);
        cameraView.onFinishInflate();
        cameraView.layout(0, 0, 1080, 1920);
        idle();
        original = CameraView.sArModuleFactory;
        tracker = new FakeArTracker();
        CameraView.sArModuleFactory = view -> FakeArTracker.module(view, tracker);
    }

    @After
    public void tearDown() {
        CameraView.sArModuleFactory = original;
        cameraView.exitAr();
        cameraView.close();
        idle();
    }

    /**
     * Where ARCore is not part of the app, AR is not supported and cannot be entered, and every
     * question about it has the answer for no AR.
     */
    @Test
    public void withoutArcoreThereIsNoAr() {
        CameraView.sArModuleFactory = original;
        assertFalse("the test app does not declare AR", cameraView.isArSupported());
        assertFalse(cameraView.enterAr(listener));
        assertFalse(cameraView.isInAr());
        assertNull(cameraView.getArTracking());
        assertNull(cameraView.addArAnchor(10f, 10f));
        assertFalse(cameraView.isArDepthSupported());
    }

    /**
     * Entering AR with the camera closed sets the app's camera aside, carrying its settings, and
     * the camera opens on AR when it opens.
     */
    @Test
    public void arTakesTheCamerasPlaceWithItsSettings() {
        FrameRenderer renderer = new NothingRenderer();
        cameraView.setFrameRenderer(renderer);
        cameraView.setPictureQuality(73);

        assertTrue(cameraView.enterAr(listener));

        assertTrue(cameraView.isInAr());
        assertEquals(CameraView.ArTracking.INITIALIZING, cameraView.getArTracking());
        assertSame("the renderer is carried across", renderer, cameraView.getFrameRenderer());
        assertEquals(73, cameraView.getPictureQuality());
        assertTrue(cameraView.isArDepthSupported());
        assertFalse("AR does not zoom", cameraView.isZoomSupported());
        assertThrows(IllegalStateException.class, () -> cameraView.enterAr(listener));

        cameraView.open();
        idle();
        assertTrue(cameraView.isOpen());
        assertTrue("the camera opens through the tracker", tracker.deviceWrapped);
    }

    /** Entering AR with the camera open closes it and opens it again on AR. */
    @Test
    public void enteringArReopensAnOpenCamera() {
        List<String> states = new ArrayList<>();
        cameraView.setOnCameraStateChangedListener(new CameraView.OnCameraStateChangedListener() {
            @Override public void onCameraOpened() { states.add("opened"); }
            @Override public void onCameraClosed() { states.add("closed"); }
        });
        cameraView.open();
        idle();

        assertTrue(cameraView.enterAr(listener));
        idle();

        assertTrue(cameraView.isOpen());
        assertTrue(tracker.deviceWrapped);
        assertTrue("tracking resumes once the session is active", tracker.isResumed());
        assertEquals("[opened, closed, opened]", states.toString());
    }

    /** Tracking is reported to the listener, and read back from the view. */
    @Test
    public void trackingIsReported() {
        assertTrue(cameraView.enterAr(listener));
        tracker.report(CameraView.ArTracking.TRACKING);
        idle();
        assertEquals(CameraView.ArTracking.TRACKING, cameraView.getArTracking());
        assertEquals(1, tracking.size());
        assertEquals(CameraView.ArTracking.TRACKING, tracking.get(0));
    }

    /**
     * Leaving AR gives the app's camera back with whatever was set meanwhile, ends tracking for
     * good, and tells nobody about tracking again.
     */
    @Test
    public void leavingArGivesTheCameraBack() {
        assertTrue(cameraView.enterAr(listener));
        FrameRenderer renderer = new NothingRenderer();
        cameraView.setFrameRenderer(renderer);

        cameraView.exitAr();

        assertFalse(cameraView.isInAr());
        assertNull(cameraView.getArTracking());
        assertEquals(1, tracker.closeCount());
        assertSame("set during AR, kept after it", renderer, cameraView.getFrameRenderer());
        assertTrue("the app's camera zooms again", cameraView.isZoomSupported());

        tracker.report(CameraView.ArTracking.TRACKING);
        idle();
        assertTrue("a module set aside is not listened to", tracking.isEmpty());
        cameraView.exitAr();
        assertEquals("leaving twice is leaving once", 1, tracker.closeCount());
    }

    /** AR that stops on its own gives the camera back and says so. */
    @Test
    public void arThatStopsGivesTheCameraBack() {
        cameraView.open();
        idle();
        assertTrue(cameraView.enterAr(listener));
        idle();

        tracker.report(CameraView.ArTracking.STOPPED);
        idle();

        assertFalse(cameraView.isInAr());
        assertEquals(1, tracker.closeCount());
        assertEquals(1, tracking.size());
        assertEquals(CameraView.ArTracking.STOPPED, tracking.get(0));
        assertTrue("the app's camera is open again", cameraView.isOpen());
    }

    /** An anchor is placed through the AR camera. */
    @Test
    public void anAnchorIsPlacedInAr() {
        cameraView.open();
        idle();
        assertTrue(cameraView.enterAr(listener));
        idle();

        ArAnchor anchor = cameraView.addArAnchor(540f, 960f);

        assertNotNull(anchor);
        assertEquals(0.5f, tracker.hitS, 0.01f);
    }

    /** AR is refused while frames are read, since AR's camera cannot share them. */
    @Test
    public void arIsRefusedWhileFramesAreAnalyzed() {
        cameraView.open();
        idle();
        assertTrue(cameraView.startFrameAnalysis(frame -> {}));

        assertFalse(cameraView.enterAr(listener));
        assertFalse(cameraView.isInAr());

        cameraView.stopFrameAnalysis();
    }

    /** What AR's camera cannot share is refused while it runs. */
    @Test
    public void framesCannotBeReadOrStreamedInAr() {
        cameraView.open();
        idle();
        assertTrue(cameraView.enterAr(listener));
        idle();

        assertFalse(cameraView.startFrameAnalysis(frame -> {}));
        assertFalse(cameraView.enterBarcodeScanner(barcodes -> {}, Barcode.Format.QR_CODE));
        assertThrows(IllegalStateException.class, () -> cameraView.stream());
    }

    private static void idle() {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    /** A renderer that draws the camera's own frames. */
    private static final class NothingRenderer implements FrameRenderer {
        @Override
        public boolean onDrawFrame(@NonNull GlFrame frame) {
            return false;
        }
    }
}
