package com.xlythe.view.camera.v2;

import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.graphics.Rect;
import android.util.Size;

import com.xlythe.view.camera.CameraView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class Camera2ModuleTest {

    private Context context;
    private CameraView cameraView;
    private Camera2Module module;

    @Before
    public void setup() {
        context = RuntimeEnvironment.application;
        cameraView = new CameraView(context);
        module = new Camera2Module(cameraView);
    }

    @Test
    public void testOpenClose() {
        assertNotNull(module);
        module.close();
    }

    @Test
    public void toggleWhileOpenWaitsForOldCameraToClose() throws Exception {
        CameraManager cameraManager = Mockito.mock(CameraManager.class);
        CameraCharacteristics back = Mockito.mock(CameraCharacteristics.class);
        CameraCharacteristics front = Mockito.mock(CameraCharacteristics.class);
        Mockito.when(cameraManager.getCameraIdList()).thenReturn(new String[]{"back", "front"});
        Mockito.when(cameraManager.getCameraCharacteristics("back")).thenReturn(back);
        Mockito.when(cameraManager.getCameraCharacteristics("front")).thenReturn(front);
        Mockito.when(back.get(CameraCharacteristics.LENS_FACING))
                .thenReturn(CameraCharacteristics.LENS_FACING_BACK);
        Mockito.when(front.get(CameraCharacteristics.LENS_FACING))
                .thenReturn(CameraCharacteristics.LENS_FACING_FRONT);
        Context cameraContext = new ContextWrapper(context) {
            @Override
            public Object getSystemService(String name) {
                return Context.CAMERA_SERVICE.equals(name)
                        ? cameraManager : super.getSystemService(name);
            }
        };
        Camera2Module switchingModule = new Camera2Module(new CameraView(cameraContext));
        org.mockito.ArgumentCaptor<CameraDevice.StateCallback> callback =
                org.mockito.ArgumentCaptor.forClass(CameraDevice.StateCallback.class);
        CameraDevice backDevice = Mockito.mock(CameraDevice.class);

        switchingModule.open();
        Mockito.verify(cameraManager).openCamera(eq("back"),
                callback.capture(), any(Handler.class));
        java.lang.reflect.Field deviceField =
                Camera2Module.class.getDeclaredField("mCameraDevice");
        deviceField.setAccessible(true);
        deviceField.set(switchingModule, backDevice);

        switchingModule.toggleCamera();

        Mockito.verify(backDevice).close();
        Mockito.verify(cameraManager, Mockito.never()).openCamera(eq("front"),
                any(CameraDevice.StateCallback.class), any(Handler.class));
        callback.getValue().onClosed(backDevice);
        Mockito.verify(cameraManager).openCamera(eq("front"),
                any(CameraDevice.StateCallback.class), any(Handler.class));
        assertEquals("front", switchingModule.getActiveCamera());
        switchingModule.close();
    }

    @Test
    public void testLensFacing() {
        assertFalse(module.hasFrontFacingCamera());
    }

    @Test
    public void testFocus() {
        module.focus(new Rect(0, 0, 10, 10), new Rect(0, 0, 10, 10));
    }

    @Test
    public void testZoom() {
        module.setZoomLevel(1);
    }

    @Test
    public void testPauseResume() {
        module.pause();
        assertFalse(module.isPaused());
        module.resume();
        assertFalse(module.isPaused());
    }

    @Test
    public void testRecording() {
        assertFalse(module.isRecording());
        module.stopRecording();
    }

    @Test
    public void negativeZoomIsClamped() {
        module.setZoomLevel(-10);
        assertEquals(0, module.getZoomLevel());
    }

    @Test
    public void zoomCropIsCenteredAndNeverInverts() {
        Rect activeArray = new Rect(100, 200, 4100, 3200);

        // 1x is no crop at all, and a ratio below it cannot crop less than nothing.
        assertEquals(activeArray, Camera2Module.calculateCropRegion(activeArray, 1f));
        assertEquals(activeArray, Camera2Module.calculateCropRegion(activeArray, 0.5f));

        // 4x keeps a quarter of each side, centered.
        assertEquals(new Rect(1600, 1325, 2600, 2075),
                Camera2Module.calculateCropRegion(activeArray, 4f));

        // The ratio divides the sides rather than the area: 2.5x of a 4000x3000 array is a
        // 1600x1200 crop.
        assertEquals(new Rect(1300, 1100, 2900, 2300),
                Camera2Module.calculateCropRegion(activeArray, 2.5f));
    }

    @Test
    public void theZoomScaleIsTheSameOnEveryDevice() {
        // Fixed rather than derived from the camera, so one zoom control works everywhere and
        // matches what the CameraX backend reports.
        assertEquals(100, module.getMaxZoomLevel());
    }

    /**
     * Camera2's guaranteed combinations define PREVIEW as at most 1080p and at most the display,
     * and only the 1080p half was ever applied. A smaller panel now caps it too.
     */
    @Test
    public void theGuaranteedPreviewCapRespectsBothHalvesOfTheRule() {
        // A tall phone panel: its long edge caps the camera's long edge.
        assertEquals(new android.util.Size(1920, 1080),
                Camera2Module.guaranteedPreviewCap(new android.util.Size(1080, 2400)));

        // A panel smaller than 1080p pulls the cap down with it, where it used to be ignored.
        assertEquals(new android.util.Size(1280, 720),
                Camera2Module.guaranteedPreviewCap(new android.util.Size(720, 1280)));

        // A panel larger than 1080p does not raise the guaranteed size.
        assertEquals(new android.util.Size(1920, 1080),
                Camera2Module.guaranteedPreviewCap(new android.util.Size(1440, 3200)));

        // An unreadable display falls back to the documented maximum.
        assertEquals(Camera2Module.MAX_SUPPORTED_SIZE, Camera2Module.guaranteedPreviewCap(null));
    }

    @Test
    public void sizeComparisonsPickTheLargestThatFits() {
        java.util.List<android.util.Size> sizes = java.util.Arrays.asList(
                new android.util.Size(3840, 2160),
                new android.util.Size(1920, 1080),
                new android.util.Size(1280, 720));

        assertEquals(new android.util.Size(3840, 2160),
                Camera2Module.largestWithin(sizes, null));
        assertEquals(new android.util.Size(1920, 1080),
                Camera2Module.largestWithin(sizes, new android.util.Size(1920, 1080)));
        assertEquals(null,
                Camera2Module.largestWithin(sizes, new android.util.Size(320, 240)));

        assertTrue(Camera2Module.exceeds(
                new android.util.Size(3840, 2160), new android.util.Size(1920, 1080)));
        assertFalse(Camera2Module.exceeds(
                new android.util.Size(1920, 1080), new android.util.Size(1920, 1080)));
        // Either dimension past the cap is enough, not just a larger area.
        assertTrue(Camera2Module.exceeds(
                new android.util.Size(4000, 100), new android.util.Size(1920, 1080)));
    }

    @Test
    public void cameraErrorsHaveStableNames() {
        assertEquals("ERROR_CAMERA_IN_USE", Camera2Module.toString(
                android.hardware.camera2.CameraDevice.StateCallback.ERROR_CAMERA_IN_USE));
        assertEquals("UNKNOWN_ERROR(99)", Camera2Module.toString(99));
    }

    @Test
    public void restoreIgnoresMissingModuleState() {
        module.onRestoreInstanceState(null);
        module.close();
    }

    /** The still cap on LIMITED hardware is the largest a recording could be. */
    @Test
    public void largestOfIsByArea() {
        assertEquals(new Size(3840, 2160), Camera2Module.largestOf(new Size[] {
                new Size(1920, 1080), new Size(3840, 2160), new Size(3000, 2000)}));
        assertNull(Camera2Module.largestOf(new Size[0]));
        assertNull(Camera2Module.largestOf(null));
    }
}
