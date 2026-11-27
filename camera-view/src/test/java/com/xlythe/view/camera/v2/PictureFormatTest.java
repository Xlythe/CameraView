package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Application;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.shadow.ShadowAvailableTextureView;
import com.xlythe.view.camera.shadow.ShadowInjectableImageReader;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/** Which file a picture is written as. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37},
        shadows = {ShadowAvailableTextureView.class, ShadowInjectableImageReader.class})
public class PictureFormatTest {
    private CameraView cameraView;

    @Before
    public void setUp() {
        Application context = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(context).grantPermissions(
                Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO);
        FakeCamera2.addBackAndFrontCameras(context);
        cameraView = new CameraView(context);
        cameraView.onFinishInflate();
        cameraView.layout(0, 0, 1080, 1920);
        ShadowLooper.idleMainLooper();
    }

    @Test
    public void theDefaultIsJpeg() {
        assertEquals(CameraView.PictureFormat.JPEG, cameraView.getPictureFormat());
        assertTrue(cameraView.isPictureFormatSupported(CameraView.PictureFormat.JPEG));
    }

    /** The fake camera lists no JPEG_R sizes, so a request for Ultra HDR is dropped. */
    @Test
    public void aCameraThatDoesNotListJpegRKeepsJpeg() {
        cameraView.open();
        ShadowLooper.idleMainLooper();

        assertFalse(cameraView.isPictureFormatSupported(CameraView.PictureFormat.ULTRA_HDR));
        cameraView.setPictureFormat(CameraView.PictureFormat.ULTRA_HDR);
        ShadowLooper.idleMainLooper();

        assertEquals(CameraView.PictureFormat.JPEG, cameraView.getPictureFormat());
        assertTrue(cameraView.isOpen());
    }

    /** Nor does it report RAW, so a request for a DNG is dropped too. */
    @Test
    public void aCameraWithoutRawKeepsJpeg() {
        cameraView.open();
        ShadowLooper.idleMainLooper();

        for (CameraView.PictureFormat format : new CameraView.PictureFormat[] {
                CameraView.PictureFormat.RAW, CameraView.PictureFormat.RAW_JPEG}) {
            assertFalse(format.name(), cameraView.isPictureFormatSupported(format));
            cameraView.setPictureFormat(format);
            ShadowLooper.idleMainLooper();
            assertEquals(CameraView.PictureFormat.JPEG, cameraView.getPictureFormat());
        }
    }
}
