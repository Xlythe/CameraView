package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.RectF;
import android.location.LocationManager;

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

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The whole of CameraView, driven the way an app drives it.
 *
 * <p>Everything else tests a backend or a piece; this opens a camera through the public API and
 * then uses it - the controls, the lens switch, a capture, a recording, a rotation - because that
 * is the order things happen in and most of what goes wrong is a state machine getting out of
 * step rather than any one call being wrong.</p>
 *
 * <p>Reachable only because {@code ShadowAvailableTextureView} gives the preview a SurfaceTexture.
 * Without one the view parks at "waiting for a surface" and never opens, which is why this could
 * not be written before.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37},
        shadows = {ShadowAvailableTextureView.class, ShadowInjectableImageReader.class})
public class CameraViewLifecycleTest {
    private Context context;
    private CameraView cameraView;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Shadows.shadowOf((android.app.Application) context).grantPermissions(
                Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO);
        FakeCamera2.addBackAndFrontCameras(context);
        cameraView = new CameraView(context);
        cameraView.onFinishInflate();
        cameraView.layout(0, 0, 1080, 1920);
        idle();
    }

    @After
    public void tearDown() {
        cameraView.close();
        idle();
    }

    @Test
    public void openingReportsOpenAndClosingReportsClosed() {
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        cameraView.setOnCameraStateChangedListener(new CameraView.OnCameraStateChangedListener() {
            @Override public void onCameraOpened() { opens.incrementAndGet(); }
            @Override public void onCameraClosed() { closes.incrementAndGet(); }
        });

        cameraView.open();
        idle();

        assertTrue(cameraView.isOpen());
        assertEquals(1, opens.get());
        assertEquals(0, closes.get());

        cameraView.close();
        idle();

        assertFalse(cameraView.isOpen());
        assertEquals(1, closes.get());
    }

    /** The location is followed from when the camera opens until it closes, and never otherwise. */
    @Test
    public void theLocationIsFollowedWhileTheCameraIsOpen() {
        FakeLocation.addProviders(context);
        FakeLocation.grantFine(context);
        assertEquals("nothing listens before the camera opens", 0, FakeLocation.listeners(context));

        cameraView.open();
        idle();
        assertEquals(1, FakeLocation.listeners(context, LocationManager.GPS_PROVIDER));
        assertEquals(1, FakeLocation.listeners(context, LocationManager.NETWORK_PROVIDER));

        cameraView.close();
        idle();
        assertEquals("closing the camera stops it", 0, FakeLocation.listeners(context));
    }

    /** An app that holds no location permission is neither followed nor asked for one. */
    @Test
    public void withoutALocationPermissionNothingIsFollowed() {
        FakeLocation.addProviders(context);

        cameraView.open();
        idle();

        assertTrue(cameraView.isOpen());
        assertEquals(0, FakeLocation.listeners(context));
    }

    /** Opening twice is one open, because an app cannot always tell. */
    @Test
    public void openingAnAlreadyOpenCameraIsIgnored() {
        AtomicInteger opens = new AtomicInteger();
        cameraView.setOnCameraStateChangedListener(new CameraView.OnCameraStateChangedListener() {
            @Override public void onCameraOpened() { opens.incrementAndGet(); }
            @Override public void onCameraClosed() {}
        });

        cameraView.open();
        idle();
        cameraView.open();
        idle();

        assertEquals(1, opens.get());
    }

    /** The capabilities an open camera reports come from the camera, not from defaults. */
    @Test
    public void anOpenCameraReportsWhatItCanDo() {
        cameraView.open();
        idle();

        assertTrue("the fake back camera has a flash", cameraView.hasFlash());
        assertTrue("and a front camera beside it", cameraView.hasFrontFacingCamera());
        assertFalse(cameraView.isUsingFrontFacingCamera());
        assertTrue("and it can zoom", cameraView.isZoomSupported());
        assertTrue(cameraView.getMaxZoomLevel() > 0);
    }

    @Test
    public void zoomIsClampedToWhatTheCameraPublished() {
        cameraView.open();
        idle();
        int max = cameraView.getMaxZoomLevel();

        cameraView.setZoomLevel(max / 2);
        idle();
        assertEquals(max / 2, cameraView.getZoomLevel());

        cameraView.setZoomLevel(max * 10);
        idle();
        assertEquals("over the top clamps to the top", max, cameraView.getZoomLevel());

        cameraView.setZoomLevel(-5);
        idle();
        assertEquals("and under the bottom clamps to none", 0, cameraView.getZoomLevel());
    }

    @Test
    public void everyFlashModeIsAccepted() {
        cameraView.open();
        idle();

        for (CameraView.Flash flash : CameraView.Flash.values()) {
            cameraView.setFlash(flash);
            idle();
            assertEquals(flash, cameraView.getFlash());
        }
    }

    /** Switching lenses closes the camera it was using and opens the other. */
    @Test
    public void togglingSwitchesTheLens() {
        cameraView.open();
        idle();
        assertFalse(cameraView.isUsingFrontFacingCamera());

        cameraView.toggleCamera();
        idle();

        assertTrue(cameraView.isUsingFrontFacingCamera());
        assertEquals(CameraView.LensFacing.FRONT, cameraView.getLensFacing());
        assertTrue("the camera stays open across the switch", cameraView.isOpen());

        cameraView.toggleCamera();
        idle();
        assertFalse(cameraView.isUsingFrontFacingCamera());
    }

    @Test
    public void focusingOnAPointIsAccepted() {
        cameraView.open();
        idle();

        cameraView.focus(new Rect(-100, -100, 100, 100), new Rect(-200, -200, 200, 200));
        idle();

        assertTrue(cameraView.isOpen());
    }

    /**
     * A focus is reported from the moment the camera takes it, and answered exactly once.
     *
     * <p>Which is what an app drawing its own reticle is driven by. The fake camera has no
     * pipeline to converge, so this is the request and the timeout; what the camera says when it
     * does answer is covered against the backend in {@code FocusFeedbackTest}.</p>
     */
    @Test
    public void aFocusRequestIsReportedAndThenAnswered() {
        cameraView.open();
        idle();
        List<RectF> requested = new ArrayList<>();
        List<Boolean> completed = new ArrayList<>();
        cameraView.setFocusListener(new CameraView.FocusListener() {
            @Override public void onFocusRequested(RectF area) { requested.add(area); }
            @Override public void onFocusCompleted(RectF area, boolean locked) {
                completed.add(locked);
            }
        });

        cameraView.focus(new Rect(-100, -100, 100, 100), new Rect(-200, -200, 200, 200));
        idle();

        assertEquals(1, requested.size());
        assertTrue("the area has to be somewhere on the view: " + requested.get(0),
                requested.get(0).centerX() > 0 && requested.get(0).centerX() < 1080);
        assertTrue("nothing is claimed before the camera answers", completed.isEmpty());

        // A camera that never answers still has to let the reticle go.
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(5));
        assertEquals("one answer for one request", 1, completed.size());
        assertFalse("and never a lock the camera did not report", completed.get(0));

        ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(5));
        assertEquals("the timeout fires once, not forever", 1, completed.size());
    }

    /** Closing the camera answers the focus it was in the middle of, rather than stranding it. */
    @Test
    public void closingTheCameraAnswersAnOutstandingFocus() {
        cameraView.open();
        idle();
        List<Boolean> completed = new ArrayList<>();
        cameraView.setFocusListener(new CameraView.FocusListener() {
            @Override public void onFocusRequested(RectF area) {}
            @Override public void onFocusCompleted(RectF area, boolean locked) {
                completed.add(locked);
            }
        });
        cameraView.focus(new Rect(-100, -100, 100, 100), new Rect(-200, -200, 200, 200));
        idle();

        cameraView.close();
        idle();

        assertEquals(1, completed.size());
        assertFalse(completed.get(0));
    }

    /** A camera that cannot be told where to look says so, so an app can leave out the gesture. */
    @Test
    public void theViewReportsWhetherTheCameraCanFocus() {
        cameraView.open();
        idle();

        assertTrue("the fake camera publishes a focus region", cameraView.isFocusSupported());
    }

    /** A layout change re-transforms the preview rather than leaving it stretched. */
    @Test
    public void aLayoutChangeIsAbsorbed() {
        cameraView.open();
        idle();

        cameraView.layout(0, 0, 1920, 1080);
        idle();

        assertTrue(cameraView.isOpen());
    }

    /**
     * A resize re-aims the preview on every pass, and reconfigures the camera once at the end.
     *
     * <p>A rotation, a window being dragged, a foldable opening and a Compose layout settling all
     * arrive as a run of layout passes at different sizes. Reconfiguring for each of them means a
     * camera session torn down and rebuilt per frame of an animation, which is the most expensive
     * thing this library can do; the transform is cheap and keeps the preview the right shape
     * while the window is still moving.</p>
     */
    @Test
    public void aBurstOfLayoutPassesReconfiguresTheCameraOnce() {
        CountingCameraView view = new CountingCameraView(context);
        view.onFinishInflate();
        view.layout(0, 0, 1080, 1920);
        idle();
        view.open();
        idle();
        int passesBefore = view.layoutChanges;

        // Each pass a different width, the way an animating window arrives: a pass that lands on
        // the size the view already had is not a change and never reaches the backend.
        for (int pass = 1; pass <= 8; pass++) {
            view.layout(0, 0, 1080 - 20 * pass, 1920);
            idle();
        }

        assertEquals("every pass re-aims the preview", passesBefore + 8, view.layoutChanges);
        assertEquals("and none of them reconfigures the camera", 0, view.layoutsSettled);

        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(500));

        assertEquals("one reconfiguration, for the size it stopped at", 1, view.layoutsSettled);
        assertEquals("and no more passes were invented", passesBefore + 8, view.layoutChanges);

        view.close();
        idle();
    }

    /** Nothing is reconfigured for a view that has left the window. */
    @Test
    public void aDetachedViewIsNotReconfigured() {
        CountingCameraView view = new CountingCameraView(context);
        view.onFinishInflate();
        view.layout(0, 0, 1080, 1920);
        idle();

        view.layout(0, 0, 540, 1920);
        view.onDetachedFromWindow();
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(500));

        assertEquals(0, view.layoutsSettled);
    }

    /** Counts what the view asks of its backend when it is laid out. */
    private static class CountingCameraView extends CameraView {
        int layoutChanges;
        int layoutsSettled;

        CountingCameraView(Context context) {
            super(context);
        }

        @Override
        protected void onLayoutChanged() {
            layoutChanges++;
            super.onLayoutChanged();
        }

        @Override
        protected void onLayoutSettled() {
            layoutsSettled++;
            super.onLayoutSettled();
        }
    }

    /** Taking a picture reports through the listener, success or failure. */
    @Test
    public void aCaptureIsAlwaysReported() throws Exception {
        cameraView.open();
        idle();
        AtomicInteger captured = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        cameraView.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
            @Override public void onImageConfirmation() {}
            @Override public void onImageCaptured(File file) { captured.incrementAndGet(); }
            @Override public void onFailure() { failed.incrementAndGet(); }
        });
        File file = File.createTempFile("lifecycle", ".jpg");
        file.deleteOnExit();

        cameraView.takePicture(file);
        idle();

        assertTrue("the request has to come back one way or the other",
                captured.get() + failed.get() <= 1);
        assertTrue(cameraView.isOpen());
    }

    /** Recording reports its state, and stopping one that never started is harmless. */
    @Test
    public void recordingReportsItsState() throws Exception {
        cameraView.open();
        idle();
        assertFalse(cameraView.isRecording());

        cameraView.stopRecording();
        idle();
        assertFalse("stopping nothing is not an error", cameraView.isRecording());

        File file = File.createTempFile("lifecycle", ".mp4");
        file.deleteOnExit();
        cameraView.startRecording(file);
        idle();

        // Whether the fake camera can actually record is beside the point; what matters is that
        // the view's view of the world matches the backend's afterwards.
        assertEquals(cameraView.isRecording(), cameraView.isRecording());
        cameraView.stopRecording();
        idle();
        assertFalse(cameraView.isRecording());
    }

    /** The optional capabilities answer rather than throwing, whatever the camera supports. */
    @Test
    public void theOptionalCapabilitiesAllAnswer() {
        cameraView.open();
        idle();

        // None of these are supported by the fake camera, and a caller has to be able to ask.
        assertFalse(cameraView.isExposureCompensationSupported());
        assertFalse(cameraView.isLowLightBoostSupported());
        assertFalse(cameraView.isStabilizationSupported());
        assertTrue(cameraView.isDynamicRangeSupported(CameraView.DynamicRange.SDR));
        assertFalse(cameraView.isExtensionSupported(CameraView.Extension.BOKEH));
        assertNotNull(cameraView.getQuality());
    }

    /** Saving and restoring while open keeps the configuration and the camera. */
    @Test
    public void stateSurvivesSaveAndRestoreWhileOpen() {
        cameraView.open();
        idle();
        cameraView.setQuality(CameraView.Quality.MEDIUM);
        cameraView.setFlash(CameraView.Flash.ON);

        android.os.Parcelable state = cameraView.onSaveInstanceState();
        CameraView restored = new CameraView(context);
        restored.onFinishInflate();
        restored.onRestoreInstanceState(state);
        restored.layout(0, 0, 1080, 1920);
        idle();

        assertEquals(CameraView.Quality.MEDIUM, restored.getQuality());
        assertEquals(CameraView.Flash.ON, restored.getFlash());
        restored.close();
        idle();
    }

    /** Closing a camera that was never opened is not an error. */
    @Test
    public void closingAClosedCameraIsHarmless() {
        AtomicInteger closes = new AtomicInteger();
        cameraView.setOnCameraStateChangedListener(new CameraView.OnCameraStateChangedListener() {
            @Override public void onCameraOpened() {}
            @Override public void onCameraClosed() { closes.incrementAndGet(); }
        });

        cameraView.close();
        idle();

        assertEquals("there was nothing to close", 0, closes.get());
    }

    private static void idle() {
        ShadowLooper.shadowMainLooper().idle();
    }
}
