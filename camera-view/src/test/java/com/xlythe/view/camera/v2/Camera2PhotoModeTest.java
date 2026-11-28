package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraExtensionCharacteristics;
import android.hardware.camera2.CameraExtensionSession;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.ExtensionSessionConfiguration;
import android.media.FakeImage;
import android.media.ImageReader;
import android.os.Build;
import android.util.Range;

import androidx.exifinterface.media.ExifInterface;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.Exif;
import com.xlythe.view.camera.shadow.ShadowAvailableTextureView;
import com.xlythe.view.camera.shadow.ShadowCallbackCameraCaptureSession;
import com.xlythe.view.camera.shadow.ShadowExtensionCameraDevice;
import com.xlythe.view.camera.shadow.ShadowCameraExtensionCharacteristics;
import com.xlythe.view.camera.shadow.ShadowInjectableImageReader;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowCamera;
import org.robolectric.shadows.ShadowPausedAsyncTask;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The phone maker's photo modes on the Camera2 backend: which a camera offers, the session of its
 * own each runs in, the pictures it takes, and the ordinary session that takes over when a mode
 * is turned off or will not run.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {31, 37}, qualifiers = "w360dp-h800dp-xxxhdpi", shadows = {
        ShadowAvailableTextureView.class,
        ShadowCallbackCameraCaptureSession.class,
        ShadowExtensionCameraDevice.class,
        ShadowInjectableImageReader.class})
public class Camera2PhotoModeTest {
    /** The smallest JPEG that Exif can be written into. */
    private static final byte[] EMPTY_JPEG = {
            (byte) 0xFF, (byte) 0xD8, // SOI
            (byte) 0xFF, (byte) 0xD9, // EOI
    };

    /** The request keys the fake modes read, from API 33: focus, but not zoom. */
    private static final Set<CaptureRequest.Key> FOCUS_KEYS = new HashSet<>(Arrays.asList(
            CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_REGIONS));

    private Context context;
    private FakeCamera2.TestCameraView cameraView;
    private Camera2Module module;
    private File output;
    private final AtomicReference<File> captured = new AtomicReference<>();
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicInteger shutters = new AtomicInteger();

    @Before
    public void setUp() {
        ShadowCallbackCameraCaptureSession.reset();
        ShadowExtensionCameraDevice.reset();
        ShadowCameraExtensionCharacteristics.reset();
        ShadowInjectableImageReader.reset();
        context = RuntimeEnvironment.getApplication();
        // Cameras that write RAW, so that a mode can be seen to refuse it.
        FakeCamera2.addBackAndFrontCameras(context, FakeCamera2.RawSupport.FULL);
        // The back camera has a night mode and portrait blur; the front one has none.
        ShadowCameraExtensionCharacteristics.setExtensions(FakeCamera2.BACK_ID,
                new int[] {CameraExtensionCharacteristics.EXTENSION_NIGHT,
                        CameraExtensionCharacteristics.EXTENSION_BOKEH, 99 /* unnamed */},
                FOCUS_KEYS, new Range<>(1f, 2f));
        cameraView = FakeCamera2.laidOutCameraView(context, 1080, 1920);
        cameraView.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
            @Override public void onShutter() { shutters.incrementAndGet(); }
            @Override public void onImageConfirmation() {}
            @Override public void onImageCaptured(File file) { captured.set(file); }
            @Override public void onFailure() { failures.incrementAndGet(); }
        });
        ShadowPausedAsyncTask.overrideExecutor(Runnable::run);
        module = new Camera2Module(cameraView);
        output = new File(context.getCacheDir(), "photo-mode-test.jpg");
    }

    @After
    public void tearDown() {
        module.close();
        cameraView.close();
        FakeCamera2.idleMainLooper();
        cameraView.release();
        ShadowCamera.clearCameraInfo();
        if (output.exists() && !output.delete()) {
            output.deleteOnExit();
        }
    }

    /** A camera offers the modes it lists, named, and asks the extension service once. */
    @Test
    public void aCameraOffersTheModesItLists() throws Exception {
        assertTrue(module.isPhotoModeSupported(CameraView.PhotoMode.NONE));
        assertTrue(module.isPhotoModeSupported(CameraView.PhotoMode.NIGHT));
        assertFalse(module.isPhotoModeSupported(CameraView.PhotoMode.HDR));
        assertFalse(module.isPhotoModeSupported(CameraView.PhotoMode.AUTO));
        assertFalse(module.isPhotoModeSupported(CameraView.PhotoMode.FACE_RETOUCH));
        assertEquals("asked once, however often it is asked about",
                1, ShadowCameraExtensionCharacteristics.getQueries());
    }

    /** A mode that lists no size for a preview or a still could not run, so is not offered. */
    @Test
    public void aModeWithoutSizesIsNotOffered() throws Exception {
        ShadowCameraExtensionCharacteristics.setSizelessExtension(
                FakeCamera2.BACK_ID, CameraExtensionCharacteristics.EXTENSION_HDR);

        assertFalse(module.isPhotoModeSupported(CameraView.PhotoMode.HDR));
    }

    /** The mode runs in a session of its own, over a preview and a still and nothing else. */
    @Test
    public void aModeRunsInASessionOfItsOwn() throws Exception {
        module.setPhotoMode(CameraView.PhotoMode.NIGHT);
        open();

        assertTrue(activeSession() instanceof ExtensionSession);
        ExtensionSessionConfiguration configuration = lastConfiguration();
        assertEquals(CameraExtensionCharacteristics.EXTENSION_NIGHT,
                configuration.getExtension());
        assertEquals("a preview and a still", 2, configuration.getOutputConfigurations().size());
        assertTrue(module.isCameraReady());
        verify(session(), atLeastOnce()).setRepeatingRequest(
                any(CaptureRequest.class), any(Executor.class),
                any(CameraExtensionSession.ExtensionCaptureCallback.class));
    }

    /** A picture goes through the mode, and is written, tagged and reported as any other. */
    @Test
    public void aPictureIsTakenThroughTheMode() throws Exception {
        module.setPhotoMode(CameraView.PhotoMode.NIGHT);
        open();

        module.takePicture(output);
        FakeCamera2.idleMainLooper();

        ArgumentCaptor<CaptureRequest> request = ArgumentCaptor.forClass(CaptureRequest.class);
        ArgumentCaptor<CameraExtensionSession.ExtensionCaptureCallback> callback =
                ArgumentCaptor.forClass(CameraExtensionSession.ExtensionCaptureCallback.class);
        verify(session()).capture(request.capture(), any(Executor.class), callback.capture());
        assertEquals(Byte.valueOf((byte) module.getPictureQuality()),
                request.getValue().get(CaptureRequest.JPEG_QUALITY));
        callback.getValue().onCaptureStarted(session(), request.getValue(), 0L);
        assertEquals("the shutter is heard as the mode starts its capture", 1, shutters.get());

        deliverStill(FakeImage.jpeg(4000, 2250, EMPTY_JPEG));

        assertEquals(output, captured.get());
        assertEquals(0, failures.get());
        Exif exif = new Exif(output);
        assertEquals("a night mode is a night scene", "3",
                exif.getAttribute(ExifInterface.TAG_SCENE_CAPTURE_TYPE));
    }

    /** A capture the mode fails, or aborts, is reported once as a failure. */
    @Test
    public void aFailedCaptureIsReportedOnce() throws Exception {
        module.setPhotoMode(CameraView.PhotoMode.NIGHT);
        open();

        module.takePicture(output);
        FakeCamera2.idleMainLooper();
        ArgumentCaptor<CameraExtensionSession.ExtensionCaptureCallback> callback =
                ArgumentCaptor.forClass(CameraExtensionSession.ExtensionCaptureCallback.class);
        verify(session()).capture(any(CaptureRequest.class), any(Executor.class),
                callback.capture());
        callback.getValue().onCaptureFailed(session(), null);
        callback.getValue().onCaptureSequenceAborted(session(), 1);

        assertEquals(1, failures.get());
        assertNull(captured.get());
    }

    /** A second picture asked for while the mode is still working on the first is refused. */
    @Test
    public void aPictureWhileTheModeIsBusyIsRefused() throws Exception {
        module.setPhotoMode(CameraView.PhotoMode.NIGHT);
        open();

        module.takePicture(output);
        module.takePicture(new File(context.getCacheDir(), "second.jpg"));
        FakeCamera2.idleMainLooper();

        assertEquals(1, failures.get());
    }

    /** A mode writes a JPEG, so a picture asked for as RAW is refused rather than mislabelled. */
    @Test
    public void aModeRefusesRaw() throws Exception {
        module.setPictureFormat(CameraView.PictureFormat.RAW);
        assertEquals(CameraView.PictureFormat.RAW, module.getPictureFormat());
        module.setPhotoMode(CameraView.PhotoMode.NIGHT);
        open();

        assertFalse(module.isPictureFormatSupported(CameraView.PictureFormat.RAW));
        assertFalse(module.isPictureFormatSupported(CameraView.PictureFormat.ULTRA_HDR));
        assertTrue(module.isPictureFormatSupported(CameraView.PictureFormat.JPEG));
        module.takePicture(output);
        FakeCamera2.idleMainLooper();

        assertEquals(1, failures.get());
        verify(session(), never()).capture(any(CaptureRequest.class), any(Executor.class),
                any(CameraExtensionSession.ExtensionCaptureCallback.class));
    }

    /** Focus goes on the mode's preview wherever it reads focus; zoom only where it reads zoom. */
    @Test
    public void focusAndZoomGoWhereTheModeReadsThem() throws Exception {
        module.setPhotoMode(CameraView.PhotoMode.NIGHT);
        open();

        module.focus(new Rect(-100, -100, 100, 100), new Rect(-100, -100, 100, 100));
        module.setZoomRatio(2f);
        FakeCamera2.idleMainLooper();

        ArgumentCaptor<CaptureRequest> request = ArgumentCaptor.forClass(CaptureRequest.class);
        verify(session(), atLeastOnce()).setRepeatingRequest(request.capture(),
                any(Executor.class), any(CameraExtensionSession.ExtensionCaptureCallback.class));
        CaptureRequest last = request.getValue();
        assertNotNull("focus regions are read", last.get(CaptureRequest.CONTROL_AF_REGIONS));
        if (Build.VERSION.SDK_INT >= 33) {
            assertNull("and the zoom is not, so it is left out",
                    last.get(CaptureRequest.SCALER_CROP_REGION));
            assertNull(last.get(CaptureRequest.CONTROL_AE_REGIONS));
        } else {
            assertNotNull("below API 33 a mode cannot say, so everything goes on",
                    last.get(CaptureRequest.SCALER_CROP_REGION));
            assertNotNull(last.get(CaptureRequest.CONTROL_AE_REGIONS));
        }
    }

    /** From API 35 a mode lists its own zoom range, which the zoom is held to while it is on. */
    @Test
    public void theZoomIsHeldToTheModesRange() throws Exception {
        module.setPhotoMode(CameraView.PhotoMode.NIGHT);
        open();
        module.setZoomRatio(100f);

        if (Build.VERSION.SDK_INT >= 35) {
            assertEquals(2f, module.getMaxZoomRatio(), 0f);
            assertEquals(2f, module.getZoomRatio(), 0f);
        } else {
            assertEquals("the camera's own range", 4f, module.getMaxZoomRatio(), 0f);
        }
    }

    /** Pausing stops the mode's preview, and resuming sends it again. */
    @Test
    public void pausingStopsTheModesPreview() throws Exception {
        module.setPhotoMode(CameraView.PhotoMode.NIGHT);
        open();

        module.pause();
        verify(session()).stopRepeating();
        assertTrue(module.isPaused());
        module.resume();
        assertFalse(module.isPaused());
        verify(session(), atLeastOnce()).setRepeatingRequest(any(CaptureRequest.class),
                any(Executor.class), any(CameraExtensionSession.ExtensionCaptureCallback.class));
    }

    /** Turning the mode off goes back to an ordinary session, closing the mode's. */
    @Test
    public void turningTheModeOffGoesBackToAnOrdinarySession() throws Exception {
        module.setPhotoMode(CameraView.PhotoMode.NIGHT);
        open();
        CameraExtensionSession night = session();

        module.setPhotoMode(CameraView.PhotoMode.NONE);
        FakeCamera2.idleMainLooper();

        assertTrue(activeSession() instanceof PictureSession);
        verify(night).close();
        assertNotNull(ShadowCallbackCameraCaptureSession.getLastRepeatingRequest());
    }

    /**
     * The mode's outputs outlive its session until the camera says it has closed: the phone
     * maker's processing can still be setting the session up, and an output closed under it ends
     * the app from a thread of the platform's.
     */
    @Test
    public void theModesOutputsWaitForItsSessionToClose() throws Exception {
        module.setPhotoMode(CameraView.PhotoMode.NIGHT);
        open();
        ExtensionSession night = (ExtensionSession) activeSession();
        CameraExtensionSession session = session();
        ExtensionSessionConfiguration configuration = lastConfiguration();

        module.setPhotoMode(CameraView.PhotoMode.NONE);
        FakeCamera2.idleMainLooper();

        verify(session).close();
        assertFalse("kept until the session has closed", night.areOutputsReleased());
        configuration.getStateCallback().onClosed(session);
        assertTrue(night.areOutputsReleased());
    }

    /** A session the camera configures after it was closed is closed, and then its outputs go. */
    @Test
    public void aSessionConfiguredAfterItsCloseIsClosedToo() throws Exception {
        module.setPhotoMode(CameraView.PhotoMode.NIGHT);
        open();
        ExtensionSession night = (ExtensionSession) activeSession();
        ExtensionSessionConfiguration configuration = lastConfiguration();
        CameraExtensionSession late = org.mockito.Mockito.mock(CameraExtensionSession.class);

        night.close();
        configuration.getStateCallback().onConfigured(late);
        verify(late).close();
        configuration.getStateCallback().onConfigureFailed(late);
        assertTrue("a refusal after the close lets the outputs go", night.areOutputsReleased());
    }

    /** A lens without the mode takes ordinary pictures, and the lens with it runs it again. */
    @Test
    public void theModeFollowsTheLens() throws Exception {
        module.setPhotoMode(CameraView.PhotoMode.NIGHT);
        open();
        assertTrue(activeSession() instanceof ExtensionSession);

        module.setLensFacing(CameraView.LensFacing.FRONT);
        FakeCamera2.idleMainLooper();
        assertTrue(activeSession() instanceof PictureSession);
        assertFalse(module.isPhotoModeSupported(CameraView.PhotoMode.NIGHT));
        assertEquals("kept for the next lens that has it",
                CameraView.PhotoMode.NIGHT, module.getPhotoMode());

        module.setLensFacing(CameraView.LensFacing.BACK);
        FakeCamera2.idleMainLooper();
        assertTrue(activeSession() instanceof ExtensionSession);
    }

    /**
     * A mode the camera listed but will not configure is forgotten for that camera, and the
     * camera carries on in an ordinary session rather than failing.
     */
    @Test
    public void aModeThatWillNotConfigureIsForgotten() throws Exception {
        ShadowExtensionCameraDevice.setRefusing(true);
        module.setPhotoMode(CameraView.PhotoMode.NIGHT);
        open();

        assertEquals(1, ShadowExtensionCameraDevice.getConfigurations().size());
        assertTrue(activeSession() instanceof PictureSession);
        assertTrue("the camera is still usable", module.isCameraReady());
        assertFalse(module.isPhotoModeSupported(CameraView.PhotoMode.NIGHT));
    }

    /** While a mode is on the flash never fires: the picture's Exif says it was suppressed. */
    @Test
    public void theFlashIsSuppressedWhileAModeIsOn() throws Exception {
        module.setFlash(CameraView.Flash.ON);
        module.setPhotoMode(CameraView.PhotoMode.NIGHT);
        open();

        assertEquals(CameraView.Flash.OFF, module.getEffectiveFlash());
        assertEquals(CameraView.Flash.ON, module.getFlash());
        try (FileOutputStream jpeg = new FileOutputStream(output)) {
            jpeg.write(EMPTY_JPEG);
        }
        Exif exif = new Exif(output);
        module.exifCaptureParameters().applyTo(exif);
        assertEquals("compulsory suppression", "16", exif.getAttribute(ExifInterface.TAG_FLASH));
        assertEquals("3", exif.getAttribute(ExifInterface.TAG_SCENE_CAPTURE_TYPE));
    }

    /** A mode the camera does not have is kept, and the camera takes ordinary pictures. */
    @Test
    public void aModeTheCameraLacksDoesNothing() throws Exception {
        module.setPhotoMode(CameraView.PhotoMode.HDR);
        open();

        assertEquals(CameraView.PhotoMode.HDR, module.getPhotoMode());
        assertTrue(activeSession() instanceof PictureSession);
        assertTrue(ShadowExtensionCameraDevice.getConfigurations().isEmpty());
        assertFalse(module.isInPhotoMode());
        assertEquals(CameraView.Flash.AUTO, module.getEffectiveFlash());
    }

    /** What the extension service numbers each mode, and back. */
    @Test
    public void theModesHaveThePlatformsNumbers() throws Exception {
        int[] modes = {CameraView.PhotoMode.AUTO, CameraView.PhotoMode.FACE_RETOUCH,
                CameraView.PhotoMode.BACKGROUND_BLUR, CameraView.PhotoMode.HDR,
                CameraView.PhotoMode.NIGHT};
        for (int mode : modes) {
            assertEquals(Integer.valueOf(mode),
                    ExtensionSupport.fromPlatform(ExtensionSupport.toPlatform(mode)));
        }
        assertEquals(ExtensionSupport.NO_EXTENSION,
                ExtensionSupport.toPlatform(CameraView.PhotoMode.NONE));
        assertNull(ExtensionSupport.fromPlatform(42));
    }

    private void open() {
        module.open();
        FakeCamera2.idleMainLooper();
        assertNotNull("expected a session", activeSession());
    }

    private static CameraExtensionSession session() {
        CameraExtensionSession session = ShadowExtensionCameraDevice.getSession();
        assertNotNull("expected an extension session", session);
        return session;
    }

    private static ExtensionSessionConfiguration lastConfiguration() {
        List<ExtensionSessionConfiguration> configurations =
                ShadowExtensionCameraDevice.getConfigurations();
        assertFalse("expected an extension session to be asked for", configurations.isEmpty());
        return configurations.get(configurations.size() - 1);
    }

    private void deliverStill(FakeImage image) {
        ImageReader reader = ShadowInjectableImageReader.latestReader(ImageFormat.JPEG);
        assertNotNull("the mode's session should have made a reader", reader);
        ShadowInjectableImageReader.offerImage(reader, image);
        FakeCamera2.idleMainLooper();
        FakeCamera2.idleMainLooper();
    }

    /** Reaches the module's active session, which it keeps private. */
    private Camera2Module.Session activeSession() {
        try {
            Field field = Camera2Module.class.getDeclaredField("mActiveSession");
            field.setAccessible(true);
            return (Camera2Module.Session) field.get(module);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }
}
