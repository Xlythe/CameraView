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
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraExtensionCharacteristics;
import android.os.Parcelable;
import android.util.SparseArray;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xlythe.view.camera.gl.BackgroundBlur;
import com.xlythe.view.camera.gl.BackgroundReplacer;
import com.xlythe.view.camera.gl.CameraTextureProvider;
import com.xlythe.view.camera.gl.FrameRenderer;
import com.xlythe.view.camera.shadow.ShadowAvailableTextureView;
import com.xlythe.view.camera.shadow.ShadowCallbackCameraCaptureSession;
import com.xlythe.view.camera.shadow.ShadowExtensionCameraDevice;
import com.xlythe.view.camera.shadow.ShadowCameraExtensionCharacteristics;
import com.xlythe.view.camera.shadow.ShadowFrameSurfaceTexture;
import com.xlythe.view.camera.shadow.ShadowInjectableImageReader;
import com.xlythe.view.camera.shadow.ShadowRecordingGles20;
import com.xlythe.view.camera.stream.VideoRecorder;
import com.xlythe.view.camera.v2.FakeCamera2;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowCamera;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Photo modes as a caller sees them: what can be asked for, what a mode refuses while it is on,
 * what it refuses to start beside, and the library's own background blur standing in where the
 * camera has none.
 *
 * <p>Run against the Camera2 backend, whose camera here lists background blur on the back lens
 * only, with ML Kit's segmenter built for real, which it can be off a device though it cannot
 * segment there.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37, qualifiers = "w360dp-h800dp-xxxhdpi", shadows = {
        ShadowAvailableTextureView.class,
        ShadowCallbackCameraCaptureSession.class,
        ShadowExtensionCameraDevice.class,
        ShadowFrameSurfaceTexture.class,
        ShadowInjectableImageReader.class,
        ShadowRecordingGles20.class})
public class PhotoModeTest {
    private Context context;
    private FakeCamera2.TestCameraView cameraView;
    private final AtomicInteger videoFailures = new AtomicInteger();

    @Before
    public void setUp() {
        ShadowCallbackCameraCaptureSession.reset();
        ShadowExtensionCameraDevice.reset();
        ShadowCameraExtensionCharacteristics.reset();
        ShadowInjectableImageReader.reset();
        ShadowFrameSurfaceTexture.reset();
        ShadowRecordingGles20.reset();
        ICameraModule.setBlurFallbackForced(false);
        context = RuntimeEnvironment.getApplication();
        MlKitForTests.bringUp();
        Shadows.shadowOf((android.app.Application) context)
                .grantPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO);
        FakeCamera2.addBackAndFrontCameras(context);
        ShadowCameraExtensionCharacteristics.setExtensions(FakeCamera2.BACK_ID,
                new int[] {CameraExtensionCharacteristics.EXTENSION_BOKEH},
                Collections.emptySet(), null);
        cameraView = FakeCamera2.laidOutCameraView(context, 1080, 1920);
        cameraView.setOnVideoCapturedListener(new CameraView.OnVideoCapturedListener() {
            @Override public void onVideoConfirmation() {}
            @Override public void onVideoCaptured(File file) {}
            @Override public void onFailure() { videoFailures.incrementAndGet(); }
        });
    }

    @After
    public void tearDown() {
        ICameraModule.setBlurFallbackForced(false);
        cameraView.close();
        idle();
        cameraView.release();
        ShadowCamera.clearCameraInfo();
        MlKitForTests.takeDown();
    }

    @Test
    public void theModeStartsAtNoneAndNoneIsAlwaysSupported() {
        assertEquals(CameraView.PhotoMode.NONE, cameraView.getPhotoMode());
        assertTrue(cameraView.isPhotoModeSupported(CameraView.PhotoMode.NONE));
        assertFalse(cameraView.isPhotoModeSupported(-1));
        assertFalse(cameraView.isPhotoModeSupported(6));
        assertThrows(IllegalArgumentException.class, () -> cameraView.setPhotoMode(6));
        assertThrows(IllegalArgumentException.class, () -> cameraView.setPhotoMode(-1));
    }

    /** The deprecated extensions name the photo modes, BOKEH being background blur. */
    @Test
    @SuppressWarnings("deprecation")
    public void theDeprecatedExtensionsAreThePhotoModes() {
        cameraView.setExtension(CameraView.Extension.BOKEH);
        assertEquals(CameraView.PhotoMode.BACKGROUND_BLUR, cameraView.getPhotoMode());
        assertEquals(CameraView.Extension.BOKEH, cameraView.getExtension());

        cameraView.setPhotoMode(CameraView.PhotoMode.NIGHT);
        assertEquals(CameraView.Extension.NIGHT, cameraView.getExtension());
        for (CameraView.Extension extension : CameraView.Extension.values()) {
            assertEquals(cameraView.isPhotoModeSupported(extension.photoMode),
                    cameraView.isExtensionSupported(extension));
        }
        assertFalse(cameraView.isExtensionSupported(null));
        assertThrows(NullPointerException.class, () -> cameraView.setExtension(null));

        ICameraModule module = module();
        module.setExtension(CameraView.Extension.HDR);
        assertEquals(CameraView.PhotoMode.HDR, module.getPhotoMode());
        assertEquals(CameraView.Extension.HDR, module.getExtension());
        assertFalse(module.isExtensionSupported(null));
        assertThrows(NullPointerException.class, () -> module.setExtension(null));
    }

    /** A mode is kept across a configuration change. */
    @Test
    public void theModeSurvivesSaveAndRestore() {
        cameraView.setId(1);
        cameraView.setPhotoMode(CameraView.PhotoMode.NIGHT);

        SparseArray<Parcelable> state = new SparseArray<>();
        cameraView.saveHierarchyState(state);
        FakeCamera2.TestCameraView restored = new FakeCamera2.TestCameraView(context);
        restored.setId(1);
        restored.restoreHierarchyState(state);

        assertEquals(CameraView.PhotoMode.NIGHT, restored.getPhotoMode());
        restored.release();
    }

    /**
     * While a mode is on the camera takes pictures and nothing else: a recording fails, analysis,
     * scanning and replacement are refused, a stream throws, and pictures are JPEGs.
     */
    @Test
    public void aModeRefusesEverythingButPictures() {
        open();
        cameraView.setPhotoMode(CameraView.PhotoMode.NIGHT); // not on this camera, so not on
        assertFalse(module().isPhotoModeOn());
        cameraView.setPhotoMode(CameraView.PhotoMode.BACKGROUND_BLUR);
        idle();
        assertTrue(module().isPhotoModeOn());

        cameraView.startRecording(new File(context.getCacheDir(), "refused.mp4"));
        idle();
        assertEquals("a recording fails", 1, videoFailures.get());
        assertFalse(cameraView.isRecording());

        assertFalse(cameraView.startFrameAnalysis(frame -> {}));
        assertFalse(cameraView.enterBarcodeScanner(barcodes -> {}, Barcode.Format.QR_CODE));
        assertFalse(cameraView.enterBackgroundReplacement(new BackgroundReplacer()));
        assertFalse(cameraView.isReplacingBackground());
        assertThrows(IllegalStateException.class, cameraView::stream);

        assertFalse(cameraView.isPictureFormatSupported(CameraView.PictureFormat.RAW));
        assertFalse(cameraView.isPictureFormatSupported(CameraView.PictureFormat.RAW_JPEG));
        assertTrue(cameraView.isPictureFormatSupported(CameraView.PictureFormat.JPEG));

        cameraView.setPhotoMode(CameraView.PhotoMode.NONE);
        idle();
        assertFalse(module().isPhotoModeOn());
        assertTrue("analysis comes back once the mode is off",
                cameraView.startFrameAnalysis(frame -> {}));
        cameraView.stopFrameAnalysis();
    }

    /** A mode refuses to start beside frame analysis, and is free to once it has stopped. */
    @Test
    public void aModeWillNotStartBesideAnalysis() {
        open();
        assertTrue(cameraView.startFrameAnalysis(frame -> {}));

        cameraView.setPhotoMode(CameraView.PhotoMode.BACKGROUND_BLUR);
        assertEquals(CameraView.PhotoMode.NONE, cameraView.getPhotoMode());

        cameraView.stopFrameAnalysis();
        cameraView.setPhotoMode(CameraView.PhotoMode.BACKGROUND_BLUR);
        assertEquals(CameraView.PhotoMode.BACKGROUND_BLUR, cameraView.getPhotoMode());
    }

    @Test
    public void aModeWillNotStartBesideABackgroundReplacement() {
        open();
        assertTrue(cameraView.enterBackgroundReplacement(new BackgroundReplacer()));

        cameraView.setPhotoMode(CameraView.PhotoMode.BACKGROUND_BLUR);
        assertEquals(CameraView.PhotoMode.NONE, cameraView.getPhotoMode());

        cameraView.exitBackgroundReplacement();
    }

    /**
     * A stream a caller attached is counted as it is attached, so a mode set on the next line is
     * refused, and counted out as it is detached. The library's own streams never count.
     */
    @Test
    public void aModeWillNotStartBesideAStream() {
        open();
        VideoRecorder.SurfaceProvider provider = new NothingProvider();
        module().getCanvas().attachSurface(provider);
        assertTrue(module().hasCallerStreams());

        cameraView.setPhotoMode(CameraView.PhotoMode.HDR);
        assertEquals(CameraView.PhotoMode.NONE, cameraView.getPhotoMode());

        module().getCanvas().detachSurface(provider);
        idle();
        assertFalse(module().hasCallerStreams());
        module().getLibraryCanvas().attachSurface(provider);
        assertFalse("the library's own streams are not the caller's", module().hasCallerStreams());
        module().getLibraryCanvas().detachSurface(provider);
        idle();

        cameraView.setPhotoMode(CameraView.PhotoMode.HDR);
        assertEquals(CameraView.PhotoMode.HDR, cameraView.getPhotoMode());
    }

    /** Turning a mode off is never refused, whatever is running. */
    @Test
    public void turningAModeOffIsNeverRefused() {
        open();
        cameraView.setPhotoMode(CameraView.PhotoMode.HDR);
        module().getCanvas().attachSurface(new NothingProvider());

        cameraView.setPhotoMode(CameraView.PhotoMode.NONE);

        assertEquals(CameraView.PhotoMode.NONE, cameraView.getPhotoMode());
    }

    /**
     * On a lens without a blur of its own, the library blurs the background, drawing in place of
     * the caller's renderer and texture provider, which are kept and come back once it stops.
     */
    @Test
    public void theLibraryBlursWhereTheCameraCannot() {
        FrameRenderer renderer = frame -> false;
        CameraTextureProvider provider = new NothingTextureProvider();
        cameraView.setFrameRenderer(renderer);
        cameraView.setCameraTextureProvider(provider);
        cameraView.setLensFacing(CameraView.LensFacing.FRONT);
        open();

        cameraView.setPhotoMode(CameraView.PhotoMode.BACKGROUND_BLUR);
        idle();

        assertTrue(module().isBlurringBackground());
        assertTrue(module().getDrawingRenderer() instanceof BackgroundBlur);
        assertNull("the provider is kept but not called", module().getDrawingTextureProvider());
        assertSame("the caller's renderer is kept", renderer, cameraView.getFrameRenderer());
        assertSame(provider, cameraView.getCameraTextureProvider());
        assertNotNull("the segmenter runs", segmentationStream());
        assertEquals(CameraView.PhotoMode.BACKGROUND_BLUR, module().getPhotoModeInUse());
        assertEquals(CameraView.PhotoMode.NONE, module().getOwnPhotoMode());

        cameraView.setPhotoMode(CameraView.PhotoMode.NONE);
        idle();

        assertFalse(module().isBlurringBackground());
        assertSame("the caller's renderer draws again", renderer, module().getDrawingRenderer());
        assertSame(provider, module().getDrawingTextureProvider());
        assertNull("and the segmenter has stopped", segmentationStream());
    }

    /** The library's blur stops with the camera, and starts again when it reopens. */
    @Test
    public void theLibrarysBlurStopsAndStartsWithTheCamera() {
        cameraView.setLensFacing(CameraView.LensFacing.FRONT);
        open();
        cameraView.setPhotoMode(CameraView.PhotoMode.BACKGROUND_BLUR);
        idle();
        assertTrue(module().isBlurringBackground());

        cameraView.close();
        idle();
        assertFalse(module().isBlurringBackground());
        assertNull(segmentationStream());

        open();
        assertTrue(module().isBlurringBackground());
        assertNotNull(segmentationStream());
    }

    /**
     * A lens with a blur of its own uses it rather than the library's, and switching to a lens
     * without one hands over to the library's, and back.
     */
    @Test
    public void theCamerasOwnBlurIsPreferredAndTheLibrarysFillsIn() {
        open();
        cameraView.setPhotoMode(CameraView.PhotoMode.BACKGROUND_BLUR);
        idle();
        assertFalse(module().isBlurringBackground());
        assertEquals(CameraView.PhotoMode.BACKGROUND_BLUR, module().getOwnPhotoMode());
        assertEquals(1, ShadowExtensionCameraDevice.getConfigurations().size());

        cameraView.setLensFacing(CameraView.LensFacing.FRONT);
        idle();
        assertTrue(module().isBlurringBackground());

        cameraView.setLensFacing(CameraView.LensFacing.BACK);
        idle();
        assertFalse(module().isBlurringBackground());
    }

    /** A test can have the library blur even on a lens with a blur of its own. */
    @Test
    public void theLibrarysBlurCanBeForced() {
        ICameraModule.setBlurFallbackForced(true);
        open();
        cameraView.setPhotoMode(CameraView.PhotoMode.BACKGROUND_BLUR);
        idle();

        assertTrue(module().isBlurringBackground());
        assertTrue(cameraView.isPhotoModeSupported(CameraView.PhotoMode.BACKGROUND_BLUR));
        assertTrue(ShadowExtensionCameraDevice.getConfigurations().isEmpty());
    }

    /** The old camera API draws no frames, so it offers no mode at all, not even the blur. */
    @Test
    public void theOldCameraApiOffersNoMode() {
        android.hardware.Camera.CameraInfo info = new android.hardware.Camera.CameraInfo();
        info.facing = android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK;
        info.orientation = 90;
        ShadowCamera.addCameraInfo(0, info);
        CameraView.setDefaultBackend(CameraView.Backend.LEGACY);
        try {
            CameraView legacy = new CameraView(context);
            assertTrue(legacy.isPhotoModeSupported(CameraView.PhotoMode.NONE));
            for (int mode = CameraView.PhotoMode.BACKGROUND_BLUR; mode <= CameraView.PhotoMode.AUTO;
                    mode++) {
                assertFalse(legacy.isPhotoModeSupported(mode));
            }
            legacy.setPhotoMode(CameraView.PhotoMode.NIGHT);
            assertEquals("kept, as on any camera without it",
                    CameraView.PhotoMode.NIGHT, legacy.getPhotoMode());
        } finally {
            CameraView.setDefaultBackend(CameraView.Backend.AUTO);
        }
    }

    private void open() {
        cameraView.open();
        idle();
        assertTrue("the fake camera should open", cameraView.isOpen());
    }

    private ICameraModule module() {
        return ReflectionHelpers.getField(cameraView, "mCameraModule");
    }

    /** The module's segmentation stream, which it keeps private. */
    @Nullable
    private FrameStream segmentationStream() {
        return ReflectionHelpers.getField(module(), "mSegmentationStream");
    }

    private static void idle() {
        ShadowLooper.shadowMainLooper().idle();
    }

    /** A stream whose frames go nowhere. */
    private static final class NothingProvider implements VideoRecorder.SurfaceProvider {
        /** Where the camera writes the stream's frames. */
        private final Surface mSurface = new Surface(new SurfaceTexture(0));

        @Override
        public Surface getSurface(int width, int height, int orientation, boolean flipped) {
            return mSurface;
        }
    }

    /** A texture provider that is never called while a mode is on. */
    private static final class NothingTextureProvider implements CameraTextureProvider {
        @Override
        public int onCreateCameraTexture() {
            return 0;
        }

        @Override
        public boolean onCameraFrameAvailable(@NonNull SurfaceTexture texture) {
            return false;
        }

        @Override
        public void onReleaseCameraTexture(int textureName) {}
    }
}
