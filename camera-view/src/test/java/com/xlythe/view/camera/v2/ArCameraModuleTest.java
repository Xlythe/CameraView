package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.media.FakeImage;
import android.media.ImageReader;
import android.net.Uri;
import android.util.Size;

import androidx.annotation.NonNull;

import com.xlythe.view.camera.ArAnchor;
import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.gl.FrameRenderThread;
import com.xlythe.view.camera.gl.FrameRenderer;
import com.xlythe.view.camera.gl.GlFrame;
import com.xlythe.view.camera.shadow.ShadowCallbackCameraCaptureSession;
import com.xlythe.view.camera.shadow.ShadowFrameSurfaceTexture;
import com.xlythe.view.camera.shadow.ShadowInjectableImageReader;
import com.xlythe.view.camera.shadow.ShadowRecordingGles20;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowPausedAsyncTask;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The camera shared with something that tracks the room: opened through the tracker's callbacks,
 * configured with its surfaces, drawing the frames it fills, and refusing what would take the
 * camera's requests away from it.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {29, 37}, qualifiers = "w360dp-h800dp-xxxhdpi", shadows = {
        ShadowCallbackCameraCaptureSession.class,
        ShadowInjectableImageReader.class,
        ShadowFrameSurfaceTexture.class,
        ShadowRecordingGles20.class})
public class ArCameraModuleTest {
    /** The picture's colour. */
    private static final byte[] MAGENTA = {(byte) 200, 40, (byte) 200, (byte) 0xFF};

    private FakeCamera2.TestCameraView cameraView;
    private FakeArTracker tracker;
    private ArCameraModule module;
    private File output;
    private final AtomicReference<File> captured = new AtomicReference<>();
    private final AtomicBoolean failed = new AtomicBoolean();
    private final AtomicInteger shutters = new AtomicInteger();
    private final AtomicInteger videoFailures = new AtomicInteger();

    @Before
    public void setUp() {
        ShadowCallbackCameraCaptureSession.reset();
        ShadowInjectableImageReader.reset();
        ShadowFrameSurfaceTexture.reset();
        ShadowRecordingGles20.reset();

        Context context = RuntimeEnvironment.getApplication();
        FakeCamera2.addBackAndFrontCameras(context);
        cameraView = FakeCamera2.laidOutCameraView(context, 1080, 1920);
        cameraView.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
            @Override public void onShutter() { shutters.incrementAndGet(); }
            @Override public void onImageConfirmation() {}
            @Override public void onImageCaptured(File file) { captured.set(file); }
            @Override public void onImageSaved(@NonNull Uri uri) {}
            @Override public void onFailure() { failed.set(true); }
        });
        cameraView.setOnVideoCapturedListener(new CameraView.OnVideoCapturedListener() {
            @Override public void onVideoConfirmation() {}
            @Override public void onVideoCaptured(File file) {}
            @Override public void onFailure() { videoFailures.incrementAndGet(); }
        });
        ShadowPausedAsyncTask.overrideExecutor(Runnable::run);
        tracker = new FakeArTracker();
        module = new ArCameraModule(cameraView, tracker);
        output = new File(context.getCacheDir(), "ar-picture.jpg");
    }

    @After
    public void tearDown() {
        module.close();
        FakeCamera2.idleMainLooper();
        cameraView.release();
        if (output.exists() && !output.delete()) {
            output.deleteOnExit();
        }
    }

    private void open() {
        module.open();
        FakeCamera2.idleMainLooper();
        FakeCamera2.idleMainLooper();
    }

    /**
     * The camera opens through the tracker: its camera, its device and session callbacks, its
     * surfaces in the session, its texture in the renderer - and tracking resumes once the
     * session is active, after the one request that starts the camera writing.
     */
    @Test
    public void theCameraOpensThroughTheTracker() {
        open();

        assertTrue(tracker.deviceWrapped);
        assertTrue(tracker.sessionWrapped);
        assertTrue("tracking resumes once the session is active", tracker.resumed);
        assertNotNull("its capture results say when a frame is waiting", tracker.captureCallback);
        FrameRenderThread effect = module.getEffect();
        assertNotNull("the frames always go through the renderer", effect);
        assertEquals(1, tracker.created.get());
        assertNull("the tracker fills the texture, not a SurfaceTexture of ours",
                effect.getInputSurfaceTexture());
        assertTrue(module.isCameraReady());

        assertEquals("one request, before the tracker takes over", 1,
                ShadowCallbackCameraCaptureSession.getRepeatingRequests().size());
    }

    /** A capture the tracker hears of has the renderer draw the frame the tracker filled. */
    @Test
    public void eachCaptureDrawsTheTrackersFrame() {
        RecordingRenderer renderer = new RecordingRenderer();
        module.setFrameRenderer(renderer);
        open();
        FrameRenderThread effect = module.getEffect();
        assertNotNull(effect);

        tracker.captureCallback.onCaptureCompleted(null, null, null);
        ShadowFrameSurfaceTexture.flush(effect.getHandler());

        assertEquals(1, tracker.advanced.get());
        assertEquals("the preview, drawn the way it is shown", 1, renderer.frames.get());
        assertTrue("with the tracker's projection", renderer.projected.get());
    }

    /**
     * Once tracking runs, settings that would reissue the camera's request leave it alone, since
     * the request is the tracker's.
     */
    @Test
    public void theTrackersRequestIsLeftAlone() {
        open();
        int requests = ShadowCallbackCameraCaptureSession.getRepeatingRequests().size();

        module.setTorchEnabled(true);
        module.setExposureCompensation(2);
        module.setZoomRatio(2f);
        module.focus(new Rect(0, 0, 10, 10), new Rect(0, 0, 10, 10));
        FakeCamera2.idleMainLooper();

        assertEquals(requests, ShadowCallbackCameraCaptureSession.getRepeatingRequests().size());
    }

    /** Pausing stops tracking before the camera; resuming starts it again. */
    @Test
    public void pausingStopsTrackingFirst() {
        open();
        module.pause();
        assertFalse(tracker.resumed);
        assertEquals(1, tracker.pauses.get());

        module.resume();
        FakeCamera2.idleMainLooper();
        assertEquals("the camera writes into the tracker's surfaces again", 2,
                ShadowCallbackCameraCaptureSession.getRepeatingRequests().size());
    }

    /** Closing pauses tracking and keeps the session for the next open; releasing ends it. */
    @Test
    public void closingPausesAndReleasingEnds() {
        open();
        module.close();
        FakeCamera2.idleMainLooper();
        assertFalse(tracker.resumed);
        assertEquals(0, tracker.closes.get());
        assertEquals("the texture is given back with the renderer", 1, tracker.released.get());

        module.release();
        assertEquals(1, tracker.closes.get());
    }

    /**
     * A picture is the next frame the renderer draws, upright, at the size the tracker's frames
     * arrive, written from what it drew.
     */
    @Test
    public void aPictureIsTheNextFrameDrawn() {
        open();
        FrameRenderThread effect = module.getEffect();
        assertNotNull(effect);

        module.takePicture(output);
        FakeCamera2.idleMainLooper();
        ShadowFrameSurfaceTexture.flush(effect.getHandler());
        tracker.captureCallback.onCaptureCompleted(null, null, null);
        ShadowFrameSurfaceTexture.flush(effect.getHandler());
        FakeCamera2.idleMainLooper();

        ImageReader drawn = ShadowInjectableImageReader.latestReader();
        assertNotNull("the picture is read back from a reader of its own", drawn);
        assertEquals(PixelFormat.RGBA_8888, drawn.getImageFormat());
        Size upright = new Size(FakeArTracker.TEXTURE_SIZE.getHeight(),
                FakeArTracker.TEXTURE_SIZE.getWidth());
        assertEquals(upright.getWidth(), drawn.getWidth());
        assertEquals(upright.getHeight(), drawn.getHeight());
        assertEquals("the shutter is reported as the frame is drawn", 1, shutters.get());

        ShadowInjectableImageReader.offerImage(drawn,
                FakeImage.rgba(upright.getWidth(), upright.getHeight(), 0, MAGENTA));
        FakeCamera2.idleMainLooper();
        FakeCamera2.idleMainLooper();

        assertFalse("should not have reported a failure", failed.get());
        assertEquals(output, captured.get());
        assertTrue(output.length() > 0);
    }

    /** A picture asked for before the camera is ready fails rather than waiting for ever. */
    @Test
    public void aPictureBeforeTheCameraIsReadyFails() {
        module.takePicture(output);
        assertTrue(failed.get());
    }

    /** A recording needs a session of its own, which the tracker cannot share, so it is refused. */
    @Test
    public void aRecordingIsRefused() {
        open();
        module.startRecording(new File(output.getParentFile(), "ar.mp4"));
        FakeCamera2.idleMainLooper();
        assertEquals(1, videoFailures.get());
        assertFalse(module.isRecording());
    }

    /** What would take the camera's requests from the tracker reports itself unsupported. */
    @Test
    public void whatTheTrackerOwnsIsNotOffered() {
        open();
        assertFalse(module.isFocusSupported());
        assertFalse(module.hasFlash());
        assertFalse(module.isExposureCompensationSupported());
        assertEquals(0, module.getMinExposureCompensation());
        assertEquals(0, module.getMaxExposureCompensation());
        assertEquals(0f, module.getExposureCompensationStep(), 0f);
        assertFalse(module.isStabilizationSupported());
        assertFalse(module.isLowLightBoostSupported());
        assertFalse(module.isFaceDetectionSupported());
        assertFalse(module.isFrameAnalysisSupported());
        assertFalse(module.isSnapshotWhileRecordingSupported());
        assertFalse(module.hasFrontFacingCamera());
        assertFalse(module.isUsingFrontFacingCamera());
        assertFalse(module.isZoomSupported());
        assertEquals(1f, module.getMinZoomRatio(), 0f);
        assertEquals(1f, module.getMaxZoomRatio(), 0f);
        assertArrayEquals(new float[] {1f}, module.getZoomPresets(), 0f);
        assertTrue(module.isPictureFormatSupported(CameraView.PictureFormat.JPEG));
        assertFalse(module.isPictureFormatSupported(CameraView.PictureFormat.RAW));
        assertTrue(module.isColorSpaceSupported(CameraView.ColorSpace.SRGB));
        assertFalse(module.isColorSpaceSupported(CameraView.ColorSpace.DISPLAY_P3));
        assertTrue(module.isDynamicRangeSupported(CameraView.DynamicRange.SDR));
        assertFalse(module.isDynamicRangeSupported(CameraView.DynamicRange.HDR));
        assertTrue(module.isExtensionSupported(CameraView.Extension.NONE));
        assertFalse(module.isExtensionSupported(CameraView.Extension.HDR));
        assertFalse(module.startSegmentation(mask -> {}));
        assertFalse(module.startBarcodeScanner(barcodes -> {}, 0));
        assertFalse("asking for no faces is no change", module.setFaceDetectionEnabled(true));
        assertTrue(module.setFaceDetectionEnabled(false));

        module.toggleCamera();
        module.setLensFacing(CameraView.LensFacing.FRONT);
        module.setLensFacing(CameraView.LensFacing.BACK);
        module.attachSurface(null);
        module.detachSurface(null);
        FakeCamera2.idleMainLooper();
        assertFalse("the lens stays the tracker's", module.isUsingFrontFacingCamera());
        assertEquals(1, ShadowCallbackCameraCaptureSession.getRepeatingRequests().size());
    }

    /** Depth is the tracker's to report. */
    @Test
    public void depthIsWhatTheTrackerMeasures() {
        assertTrue(module.isArDepthSupported());
        tracker.depth = false;
        assertFalse(module.isArDepthSupported());
    }

    /**
     * An anchor is placed where the tracker finds the scene under a point of the view: the middle
     * of the view is the middle of the buffer.
     */
    @Test
    public void anAnchorIsPlacedThroughTheViewsPoint() {
        assertNull("nothing is placed before the camera opens", module.addArAnchor(540f, 960f));
        open();

        ArAnchor anchor = module.addArAnchor(540f, 960f);

        assertSame(tracker.anchor, anchor);
        assertEquals(0.5f, tracker.hitS, 1e-3f);
        assertEquals(0.5f, tracker.hitT, 1e-3f);
    }

    /** Tracking that will not resume is reported as AR having stopped. */
    @Test
    public void trackingThatWillNotResumeStopsAr() {
        tracker.resumeFails = true;
        open();
        assertEquals(1, tracker.resumes.get());
        assertFalse(tracker.resumed);
        assertNull("nothing follows the captures", tracker.captureCallback);
    }

    /** A renderer that remembers how many frames it was handed, and whether it could project. */
    private static final class RecordingRenderer implements FrameRenderer {
        final AtomicInteger frames = new AtomicInteger();
        final AtomicBoolean projected = new AtomicBoolean();

        @Override
        public boolean onDrawFrame(@NonNull GlFrame frame) {
            frames.incrementAndGet();
            projected.set(frame.getProjectionMatrix(new float[16], 0.1f, 10f));
            return false;
        }
    }
}
