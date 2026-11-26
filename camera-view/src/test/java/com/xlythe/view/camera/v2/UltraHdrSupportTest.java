package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.graphics.ColorSpace;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.ColorSpaceProfiles;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Size;

import com.xlythe.view.camera.CameraView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowCameraCharacteristics;

import java.util.Collections;

/** Which cameras the Camera2 backend writes Ultra HDR from. JPEG_R arrived in API 34. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37)
public class UltraHdrSupportTest {
    @Test
    public void aCameraThatListsJpegRWritesUltraHdr() {
        assertTrue(Camera2Module.writesUltraHdr(
                characteristics(new Size[] {new Size(4000, 3000)}, null),
                CameraView.ColorSpace.SRGB));
    }

    @Test
    public void noJpegRSizesMeansNoUltraHdr() {
        assertFalse(Camera2Module.writesUltraHdr(
                characteristics(new Size[0], null), CameraView.ColorSpace.SRGB));
        assertFalse(Camera2Module.writesUltraHdr(
                characteristics(null, null), CameraView.ColorSpace.SRGB));
    }

    /** Display P3 as well only where the camera lists P3 for JPEG_R. */
    @Test
    public void displayP3NeedsAJpegRProfile() {
        Size[] sizes = {new Size(4000, 3000)};
        ColorSpaceProfiles withP3 = mock(ColorSpaceProfiles.class);
        when(withP3.getSupportedColorSpaces(ImageFormat.JPEG_R))
                .thenReturn(Collections.singleton(ColorSpace.Named.DISPLAY_P3));
        ColorSpaceProfiles withoutP3 = mock(ColorSpaceProfiles.class);
        when(withoutP3.getSupportedColorSpaces(ImageFormat.JPEG_R))
                .thenReturn(Collections.singleton(ColorSpace.Named.SRGB));

        assertTrue(Camera2Module.writesUltraHdr(
                characteristics(sizes, withP3), CameraView.ColorSpace.DISPLAY_P3));
        assertFalse(Camera2Module.writesUltraHdr(
                characteristics(sizes, withoutP3), CameraView.ColorSpace.DISPLAY_P3));
        assertFalse(Camera2Module.writesUltraHdr(
                characteristics(sizes, null), CameraView.ColorSpace.DISPLAY_P3));
    }

    /**
     * A camera reporting these JPEG_R sizes and colour space profiles. The map is mocked because
     * Robolectric's builder has no JPEG_R configurations to add sizes to.
     */
    private static CameraCharacteristics characteristics(
            Size[] jpegRSizes, ColorSpaceProfiles profiles) {
        StreamConfigurationMap map = mock(StreamConfigurationMap.class);
        when(map.getOutputSizes(ImageFormat.JPEG_R)).thenReturn(jpegRSizes);
        CameraCharacteristics characteristics =
                ShadowCameraCharacteristics.newCameraCharacteristics();
        ShadowCameraCharacteristics shadow = Shadow.extract(characteristics);
        shadow.set(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP, map);
        if (profiles != null) {
            shadow.set(CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES, profiles);
        }
        return characteristics;
    }
}
