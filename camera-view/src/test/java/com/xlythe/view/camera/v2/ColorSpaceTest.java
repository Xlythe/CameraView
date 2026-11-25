package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.ColorSpaceProfiles;

import com.xlythe.view.camera.CameraView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * Wide-gamut capture.
 *
 * <p>A modern sensor sees more than sRGB can describe and a modern screen can show it, which is
 * why a manufacturer's own camera app saves wide-gamut stills. It needs API 34 and a camera that
 * reports the profile, and a picture saved in a colour space the viewer ignores looks
 * oversaturated rather than better - so the whole point is that the request is refused when it
 * cannot be honoured, rather than accepted and quietly producing sRGB.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class ColorSpaceTest {
    private Context context;
    private FakeCamera2.TestCameraView cameraView;
    private Camera2Module module;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        FakeCamera2.addBackAndFrontCameras(context);
        cameraView = FakeCamera2.laidOutCameraView(context, 1080, 1920);
        module = new Camera2Module(cameraView);
    }

    @After
    public void tearDown() {
        if (module == null) return;
        module.close();
        FakeCamera2.idleMainLooper();
        cameraView.release();
    }

    @Test
    @Config(sdk = 37)
    public void everyCameraCanDoSrgb() {
        assertTrue(module.isColorSpaceSupported(CameraView.ColorSpace.SRGB));
        assertEquals("and it is what you get without asking",
                CameraView.ColorSpace.SRGB, module.getColorSpace());
    }

    /** A camera that reports no profiles cannot do wide gamut, whatever the platform. */
    @Test
    @Config(sdk = 37)
    public void aCameraWithoutProfilesDeclinesWideGamut() {
        assertFalse(module.isColorSpaceSupported(CameraView.ColorSpace.DISPLAY_P3));
    }

    /** A declined request leaves the colour space alone rather than half-applying it. */
    @Test
    @Config(sdk = 37)
    public void aDeclinedRequestChangesNothing() {
        module.setColorSpace(CameraView.ColorSpace.DISPLAY_P3);

        assertEquals(CameraView.ColorSpace.SRGB, module.getColorSpace());
    }

    /** With the profile reported, the request is honoured. */
    @Test
    @Config(sdk = 37)
    public void aCameraThatReportsTheProfileAcceptsIt() {
        givenDisplayP3();

        assertTrue(module.isColorSpaceSupported(CameraView.ColorSpace.DISPLAY_P3));
        module.setColorSpace(CameraView.ColorSpace.DISPLAY_P3);
        FakeCamera2.idleMainLooper();

        assertEquals(CameraView.ColorSpace.DISPLAY_P3, module.getColorSpace());
    }

    /** And it can be put back, because sRGB is what most things still want. */
    @Test
    @Config(sdk = 37)
    public void itCanBePutBack() {
        givenDisplayP3();
        module.setColorSpace(CameraView.ColorSpace.DISPLAY_P3);
        FakeCamera2.idleMainLooper();

        module.setColorSpace(CameraView.ColorSpace.SRGB);
        FakeCamera2.idleMainLooper();

        assertEquals(CameraView.ColorSpace.SRGB, module.getColorSpace());
    }

    /**
     * Before API 34 the request is refused however capable the camera is.
     *
     * <p>A session could not be told about a colour space at all, so accepting the request would
     * mean promising wide gamut and writing sRGB.</p>
     */
    @Test
    @Config(sdk = 33)
    public void anOlderPlatformDeclinesWideGamut() {
        assertFalse(module.isColorSpaceSupported(CameraView.ColorSpace.DISPLAY_P3));
        assertTrue("sRGB still works", module.isColorSpaceSupported(CameraView.ColorSpace.SRGB));
    }

    @Test
    @Config(sdk = 37)
    public void theEnumIdsAreStable() {
        assertEquals(CameraView.ColorSpace.SRGB, CameraView.ColorSpace.valueOf("SRGB"));
        assertEquals(CameraView.ColorSpace.DISPLAY_P3,
                CameraView.ColorSpace.valueOf("DISPLAY_P3"));
        assertEquals(2, CameraView.ColorSpace.values().length);
    }

    /** Teaches the fake back camera the Display P3 profile for JPEG. */
    private void givenDisplayP3() {
        CameraCharacteristics characteristics;
        try {
            characteristics = ((android.hardware.camera2.CameraManager)
                    context.getSystemService(Context.CAMERA_SERVICE))
                    .getCameraCharacteristics("0");
        } catch (android.hardware.camera2.CameraAccessException error) {
            throw new AssertionError(error);
        }
        org.robolectric.shadows.ShadowCameraCharacteristics shadow =
                Shadows.shadowOf(characteristics);
        shadow.set(CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES,
                displayP3Profiles());
    }

    /**
     * A ColorSpaceProfiles saying Display P3 is available for JPEG.
     *
     * <p>Built from the packed array the platform constructor takes - three longs per entry:
     * the colour space ordinal, the image format, and the dynamic range profile.</p>
     */
    private static ColorSpaceProfiles displayP3Profiles() {
        long[] packed = {
                android.graphics.ColorSpace.Named.DISPLAY_P3.ordinal(),
                ImageFormat.JPEG,
                ColorSpaceProfiles.UNSPECIFIED,
        };
        return new ColorSpaceProfiles(packed);
    }
}
