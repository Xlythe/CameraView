package com.xlythe.view.camera.legacy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.shadow.ShadowAvailableTextureView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.MediaCodecInfoBuilder;
import org.robolectric.shadows.ShadowCamera;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowMediaCodecList;
import org.robolectric.util.ReflectionHelpers;

/**
 * Motion photos on the old camera API, which has no surface to give an encoder and so feeds it the
 * preview frames its one callback hands out.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, shadows = {ShadowAvailableTextureView.class})
public class LegacyMotionPhotoTest {
    private TestCameraView cameraView;
    private LegacyCameraModule module;

    /** Supplies the SurfaceTexture a TextureView never produces without a hardware layer. */
    private static class TestCameraView extends CameraView {
        private final SurfaceTexture mSurfaceTexture = new SurfaceTexture(0);

        TestCameraView(Context context) {
            super(context);
        }

        @Override
        public SurfaceTexture getSurfaceTexture() {
            return mSurfaceTexture;
        }

        void release() {
            mSurfaceTexture.release();
        }
    }

    @Before
    public void setUp() {
        ShadowMediaCodecList.reset();
        Context context = RuntimeEnvironment.getApplication();
        Shadows.shadowOf((android.app.Application) context).grantPermissions(
                Manifest.permission.CAMERA);
        Camera.CameraInfo info = new Camera.CameraInfo();
        info.facing = Camera.CameraInfo.CAMERA_FACING_BACK;
        info.orientation = 90;
        info.canDisableShutterSound = true;
        ShadowCamera.addCameraInfo(0, info);
        cameraView = new TestCameraView(context);
        cameraView.layout(0, 0, 1080, 1920);
        module = new LegacyCameraModule(cameraView);
    }

    @After
    public void tearDown() {
        module.setMotionPhotoEnabled(false);
        module.close();
        ShadowLooper.shadowMainLooper().idle();
        cameraView.release();
        ShadowMediaCodecList.reset();
    }

    /** Lists an encoder that takes frames copied in, which is what this backend needs. */
    private static void addByteEncoder() {
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder()
                .setName("c2.android.avc.encoder")
                .setIsEncoder(true)
                .setIsSoftwareOnly(true)
                .setCapabilities(MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                        .setMediaFormat(MediaFormat.createVideoFormat(
                                MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080))
                        .setIsEncoder(true)
                        .setColorFormats(new int[] {
                                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible})
                        .build())
                .build());
    }

    private Camera.PreviewCallback previewCallback() {
        Camera camera = ReflectionHelpers.getField(module, "mCamera");
        return ReflectionHelpers.getField(Shadows.shadowOf(camera), "previewCallback");
    }

    @Test
    public void withoutAnEncoderThereAreNoMotionPhotos() {
        assertFalse(module.isMotionPhotoSupported());

        module.setMotionPhotoEnabled(true);
        module.open();

        assertTrue("asked for, though it cannot be had", module.isMotionPhotoEnabled());
        assertNull("no frames are read for an encoder there is not", previewCallback());
    }

    @Test
    public void theEncoderIsFedFromThePreviewCallback() {
        addByteEncoder();
        assertTrue(module.isMotionPhotoSupported());

        module.setMotionPhotoEnabled(true);
        module.open();

        assertNotNull(previewCallback());
    }

    @Test
    public void turningItOffStopsReadingFrames() {
        addByteEncoder();
        module.open();
        assertNull(previewCallback());

        module.setMotionPhotoEnabled(true);
        assertNotNull(previewCallback());
        module.setMotionPhotoEnabled(false);
        assertNull(previewCallback());
    }
}
