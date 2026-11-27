package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.MeteringRectangle;
import android.media.ImageReader;
import android.util.Size;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.shadow.ShadowAvailableTextureView;
import com.xlythe.view.camera.shadow.ShadowCallbackCameraCaptureSession;
import com.xlythe.view.camera.shadow.ShadowInjectableImageReader;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowCameraCharacteristics;
import org.robolectric.shadow.api.Shadow;

import java.io.File;
import java.util.List;

/**
 * Full-resolution stills from a sensor that bins its pixels by default.
 *
 * <p>Such a sensor publishes a second set of sizes, read out unbinned, from API 31. A MAX picture
 * takes the largest of them, and the capture has to ask for maximum resolution mode and measure
 * its regions against the unbinned array, or the camera rejects it.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {31, 37}, qualifiers = "w360dp-h800dp-xxxhdpi", shadows = {
        ShadowAvailableTextureView.class,
        ShadowCallbackCameraCaptureSession.class,
        ShadowInjectableImageReader.class})
public class MaximumResolutionTest {
    private Context context;
    private FakeCamera2.TestCameraView cameraView;
    private Camera2Module module;

    @Before
    public void setUp() {
        ShadowCallbackCameraCaptureSession.reset();
        context = RuntimeEnvironment.getApplication();
    }

    @After
    public void tearDown() {
        if (module == null) return;
        module.close();
        FakeCamera2.idleMainLooper();
        cameraView.release();
    }

    private void open(boolean capability, CameraView.Quality quality) {
        FakeCamera2.addUltraHighResolutionCamera(context, capability);
        cameraView = FakeCamera2.laidOutCameraView(context, 1080, 1920);
        module = new Camera2Module(cameraView);
        module.setQuality(quality);
        module.open();
        FakeCamera2.idleMainLooper();
    }

    @Test
    public void aMaxStillTakesTheUnbinnedSize() {
        open(true /* capability */, CameraView.Quality.MAX);

        ImageReader reader = ShadowInjectableImageReader.latestReader();
        assertNotNull(reader);
        assertEquals(FakeCamera2.SIZE_UNBINNED,
                new Size(reader.getWidth(), reader.getHeight()));
        assertEquals(ImageFormat.JPEG, reader.getImageFormat());
    }

    /** The capture asks for the unbinned readout, which is the only mode that stream works in. */
    @Test
    public void theCaptureAsksForMaximumResolutionMode() {
        open(true /* capability */, CameraView.Quality.MAX);

        module.takePicture(new File(context.getCacheDir(), "unbinned.jpg"));
        FakeCamera2.idleMainLooper();

        CaptureRequest request = lastCapture();
        assertEquals(Integer.valueOf(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION),
                request.get(CaptureRequest.SENSOR_PIXEL_MODE));
    }

    /** Zoom and metering are measured against the unbinned array in that mode. */
    @Test
    public void regionsMoveOntoTheUnbinnedArray() {
        open(true /* capability */, CameraView.Quality.MAX);
        // The shadow answers a focus request with an empty result, which nothing can read.
        ShadowCallbackCameraCaptureSession.setDeliverCallbacks(false);
        module.setZoomLevel(module.getMaxZoomLevel());
        module.focus(new Rect(-100, -100, 100, 100), new Rect(-100, -100, 100, 100));
        FakeCamera2.idleMainLooper();

        module.takePicture(new File(context.getCacheDir(), "unbinned.jpg"));
        FakeCamera2.idleMainLooper();

        CaptureRequest request = lastCapture();
        Rect crop = request.get(CaptureRequest.SCALER_CROP_REGION);
        assertNotNull("the zoom should reach the still", crop);
        assertTrue("the crop should sit inside the unbinned array, not the binned one: " + crop,
                FakeCamera2.UNBINNED_ACTIVE_ARRAY.contains(crop) && crop.right > 4000);
        MeteringRectangle[] regions = request.get(CaptureRequest.CONTROL_AF_REGIONS);
        assertNotNull("the focus point should reach the still", regions);
        assertTrue("the focus point should be centred on the unbinned array: " + regions[0],
                regions[0].getRect().contains(4000, 3000));
    }

    @Test
    public void otherQualitiesStayBinned() {
        open(true /* capability */, CameraView.Quality.HIGH);

        ImageReader reader = ShadowInjectableImageReader.latestReader();
        assertNotNull(reader);
        assertEquals(FakeCamera2.SIZE_1080P, new Size(reader.getWidth(), reader.getHeight()));

        module.takePicture(new File(context.getCacheDir(), "binned.jpg"));
        FakeCamera2.idleMainLooper();
        assertNull(lastCapture().get(CaptureRequest.SENSOR_PIXEL_MODE));
    }

    /** A map published without the capability is a placeholder nothing guarantees. */
    @Test
    public void anUnbinnedMapWithoutTheCapabilityIsIgnored() {
        open(false /* capability */, CameraView.Quality.MAX);

        ImageReader reader = ShadowInjectableImageReader.latestReader();
        assertNotNull(reader);
        assertEquals(FakeCamera2.SIZE_1080P, new Size(reader.getWidth(), reader.getHeight()));
        assertNull(module.getMaximumResolutionMap());
    }

    /** The front camera has no unbinned mode, so switching to it drops back to binned sizes. */
    @Test
    public void aCameraWithoutAnUnbinnedModeKeepsItsSizes() {
        open(true /* capability */, CameraView.Quality.MAX);

        module.setLensFacing(CameraView.LensFacing.FRONT);
        FakeCamera2.idleMainLooper();

        ImageReader reader = ShadowInjectableImageReader.latestReader();
        assertNotNull(reader);
        assertEquals(FakeCamera2.SIZE_1080P, new Size(reader.getWidth(), reader.getHeight()));
    }

    /** The preview probe asks about the still the session will actually configure. */
    @Test
    public void theProbeUsesTheUnbinnedStill() throws Exception {
        open(true /* capability */, CameraView.Quality.MAX);
        CameraCharacteristics characteristics = context.getSystemService(CameraManager.class)
                .getCameraCharacteristics(FakeCamera2.BACK_ID);

        assertEquals(FakeCamera2.SIZE_UNBINNED, module.largestStillSize(characteristics));
        module.setQuality(CameraView.Quality.HIGH);
        assertEquals(FakeCamera2.SIZE_1080P, module.largestStillSize(characteristics));
    }

    @Test
    public void theCapabilityDecidesWhetherTheMapCounts() {
        CameraCharacteristics none = ShadowCameraCharacteristics.newCameraCharacteristics();
        assertNull("no capabilities at all", Camera2Module.maximumResolutionMap(none));

        CameraCharacteristics placeholder = ShadowCameraCharacteristics.newCameraCharacteristics();
        ShadowCameraCharacteristics shadow = Shadow.extract(placeholder);
        shadow.set(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
                new int[] {CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE});
        assertNull("a map without the capability", Camera2Module.maximumResolutionMap(placeholder));
    }

    @Test
    public void regionsScaleBetweenArrays() {
        Rect binned = new Rect(0, 0, 4000, 3000);
        Rect unbinned = new Rect(0, 0, 8000, 6000);

        assertEquals(new Rect(1000, 750, 3000, 2250),
                Camera2Module.scaleRegion(new Rect(500, 375, 1500, 1125), binned, unbinned));
        assertEquals(unbinned, Camera2Module.scaleRegion(binned, binned, unbinned));
        assertEquals("an offset array keeps its origin", new Rect(16, 8, 8016, 6008),
                Camera2Module.scaleRegion(new Rect(8, 4, 4008, 3004),
                        new Rect(8, 4, 4008, 3004), new Rect(16, 8, 8016, 6008)));
        assertNull(Camera2Module.scaleRegion(binned, null, unbinned));
        assertNull(Camera2Module.scaleRegion(binned, binned, null));
        assertNull(Camera2Module.scaleRegion(binned, new Rect(), unbinned));
    }

    private static CaptureRequest lastCapture() {
        List<CaptureRequest> requests = ShadowCallbackCameraCaptureSession.getCaptures();
        assertFalse("no still was requested", requests.isEmpty());
        return requests.get(requests.size() - 1);
    }
}
