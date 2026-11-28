package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import android.Manifest;
import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.DynamicRangeProfiles;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.util.Size;
import android.view.Display;
import android.view.SurfaceView;
import android.view.ViewGroup;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.shadow.ShadowCallbackCameraCaptureSession;
import com.xlythe.view.camera.shadow.ShadowConfigurableCameraDevice;
import com.xlythe.view.camera.shadow.ShadowGeotaggingMediaRecorder;
import com.xlythe.view.camera.shadow.ShadowSizedSurfaceView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.MediaCodecInfoBuilder;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowMediaCodecList;
import org.robolectric.shadows.ShadowMediaRecorder;

import java.io.File;
import java.time.Duration;
import java.util.List;

/**
 * HDR on the Camera2 backend: a recording in HLG, encoded as HEVC Main 10, and a preview that
 * streams HDR too and is shown as HDR on a display that can.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37, shadows = {
        ShadowConfigurableCameraDevice.class,
        ShadowSizedSurfaceView.class,
        ShadowCallbackCameraCaptureSession.class,
        ShadowGeotaggingMediaRecorder.class},
        qualifiers = "w360dp-h800dp-xxxhdpi")
public class HdrCamera2Test {
    private Activity activity;
    private FakeCamera2.TestCameraView cameraView;
    private Camera2Module module;
    private File output;

    @Before
    public void setUp() {
        ShadowConfigurableCameraDevice.reset();
        ShadowSizedSurfaceView.reset();
        ShadowCallbackCameraCaptureSession.reset();
        ShadowGeotaggingMediaRecorder.forgetLocation();
        ShadowMediaCodecList.reset();
        Camera2Module.resetMain10Level();
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        Shadows.shadowOf(activity.getApplication()).grantPermissions(Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO);
        FakeCamera2.addBackAndFrontCameras(activity);
        cameraView = new FakeCamera2.TestCameraView(activity);
        activity.setContentView(cameraView, new ViewGroup.LayoutParams(1080, 1920));
        cameraView.layout(0, 0, 1080, 1920);
        module = new Camera2Module(cameraView);
        output = new File(activity.getCacheDir(), "hdr-recording.mp4");
    }

    @After
    public void tearDown() {
        module.close();
        FakeCamera2.idleMainLooper();
        cameraView.release();
        Camera2Module.resetMain10Level();
        if (output.exists() && !output.delete()) {
            output.deleteOnExit();
        }
    }

    // ---------- What it supports ----------

    @Test
    public void aCameraWithHlgAndAMain10EncoderRecordsHdr() {
        givenTenBitCameras();
        givenMain10Encoder();
        assertTrue(module.isDynamicRangeSupported(CameraView.DynamicRange.HDR));
        assertTrue(module.isDynamicRangeSupported(CameraView.DynamicRange.SDR));
    }

    @Test
    public void aCameraWithoutA10BitStreamDoesNot() {
        givenMain10Encoder();
        assertFalse(module.isDynamicRangeSupported(CameraView.DynamicRange.HDR));
        assertFalse(module.isHdrPreviewSupported());
    }

    @Test
    public void norDoesOneWithoutAnEncoderForIt() {
        givenTenBitCameras();
        assertFalse(module.isDynamicRangeSupported(CameraView.DynamicRange.HDR));
    }

    /** Dynamic range profiles arrived in Android 13. */
    @Test
    @Config(sdk = 32)
    public void norDoesAnOlderRelease() {
        assertFalse(module.isDynamicRangeSupported(CameraView.DynamicRange.HDR));
        assertFalse(module.isHdrPreviewSupported());
    }

    @Test
    public void anHdrPreviewNeedsAnHdrDisplay() {
        givenTenBitCameras();
        assertFalse(module.isHdrPreviewSupported());
        makeTheDisplayHdr();
        assertTrue(module.isHdrPreviewSupported());
    }

    @Test
    public void theProfileIsHlg() {
        givenTenBitCameras();
        assertEquals(DynamicRangeProfiles.HLG10,
                Camera2Module.hdrProfile(module.getCameraCharacteristics()));
        assertEquals(Camera2Module.STANDARD_PROFILE, Camera2Module.hdrProfile(null));
    }

    // ---------- The preview ----------

    @Test
    public void anSdrDisplayKeepsTheSdrPreview() {
        givenTenBitCameras();
        givenMain10Encoder();
        module.setDynamicRange(CameraView.DynamicRange.HDR);
        module.open();
        settle();

        assertFalse(module.hdrSurface().isShowing());
        for (ShadowConfigurableCameraDevice.Session session
                : ShadowConfigurableCameraDevice.getSessions()) {
            assertNoTenBitOutput(session);
        }
    }

    @Test
    public void framingHdrVideoOnAnHdrDisplayShowsTheHdrPreview() {
        givenTenBitCameras();
        givenMain10Encoder();
        makeTheDisplayHdr();
        ShadowConfigurableCameraDevice.setSessionConfigurationSupported(true);
        module.setDynamicRange(CameraView.DynamicRange.HDR);
        module.open();
        settle();

        assertTrue(module.hdrSurface().isShowing());
        assertEquals(ActivityInfo.COLOR_MODE_HDR, activity.getWindow().getColorMode());
        ShadowConfigurableCameraDevice.Session session = lastSession();
        assertEquals(DynamicRangeProfiles.HLG10, (long) session.profiles.get(0));
        assertEquals("the preview goes to the HDR surface",
                module.hdrSurface().getSurfaceView().getHolder().getSurface(),
                session.surfaces.get(0));
    }

    /** The SurfaceView is scaled to cover the view, which the compositor will not do for it. */
    @Test
    public void theHdrPreviewCoversTheView() {
        givenTenBitCameras();
        givenMain10Encoder();
        makeTheDisplayHdr();
        ShadowConfigurableCameraDevice.setSessionConfigurationSupported(true);
        module.setDynamicRange(CameraView.DynamicRange.HDR);
        module.open();
        settle();

        SurfaceView surfaceView = module.hdrSurface().getSurfaceView();
        float shownWidth = surfaceView.getWidth() * surfaceView.getScaleX();
        float shownHeight = surfaceView.getHeight() * surfaceView.getScaleY();
        assertTrue(shownWidth >= 1080 - 0.5f && shownHeight >= 1920 - 0.5f);
        assertEquals(540f, surfaceView.getTranslationX() + shownWidth / 2, 1f);
        assertEquals(960f, surfaceView.getTranslationY() + shownHeight / 2, 1f);
    }

    /** A camera that cannot stream HDR beside its still keeps the preview SDR. */
    @Test
    public void aCameraThatRefusesTheCombinationKeepsTheSdrPreview() {
        givenTenBitCameras();
        givenMain10Encoder();
        makeTheDisplayHdr();
        ShadowConfigurableCameraDevice.setSessionConfigurationSupported(false);
        module.setDynamicRange(CameraView.DynamicRange.HDR);
        module.open();
        settle();

        assertFalse(module.hdrSurface().isShowing());
        assertNoTenBitOutput(lastSession());
    }

    @Test
    public void turningHdrPreviewOffKeepsItSdr() {
        givenTenBitCameras();
        givenMain10Encoder();
        makeTheDisplayHdr();
        ShadowConfigurableCameraDevice.setSessionConfigurationSupported(true);
        module.setDynamicRange(CameraView.DynamicRange.HDR);
        module.open();
        settle();
        assertTrue(module.hdrSurface().isShowing());

        module.setHdrPreviewEnabled(false);
        settle();
        assertFalse(module.isHdrPreviewEnabled());
        assertFalse(module.hdrSurface().isShowing());
        assertNoTenBitOutput(lastSession());
        assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, activity.getWindow().getColorMode());

        module.setHdrPreviewEnabled(true);
        settle();
        assertTrue(module.hdrSurface().isShowing());
    }

    @Test
    public void goingBackToSdrPutsTheTextureViewBack() {
        givenTenBitCameras();
        givenMain10Encoder();
        makeTheDisplayHdr();
        ShadowConfigurableCameraDevice.setSessionConfigurationSupported(true);
        module.setDynamicRange(CameraView.DynamicRange.HDR);
        module.open();
        settle();

        module.setDynamicRange(CameraView.DynamicRange.SDR);
        settle();
        assertFalse(module.hdrSurface().isShowing());
        assertNoTenBitOutput(lastSession());
        assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, activity.getWindow().getColorMode());
    }

    /** An Ultra HDR picture is HDR too, so it is framed in HDR beside its JPEG_R output. */
    @Test
    public void framingUltraHdrShowsTheHdrPreview() {
        givenTenBitCameras();
        givenJpegR();
        makeTheDisplayHdr();
        ShadowConfigurableCameraDevice.setSessionConfigurationSupported(true);
        module.setPictureFormat(CameraView.PictureFormat.ULTRA_HDR);
        module.open();
        settle();

        assertTrue(module.hdrSurface().isShowing());
        assertEquals(DynamicRangeProfiles.HLG10,
                (long) lastSession().profiles.get(0));
        List<SessionConfiguration> probed = ShadowConfigurableCameraDevice.getProbedConfigurations();
        boolean askedAboutJpegR = false;
        for (SessionConfiguration configuration : probed) {
            for (OutputConfiguration output : configuration.getOutputConfigurations()) {
                if (output.getDynamicRangeProfile() == DynamicRangeProfiles.HLG10) {
                    askedAboutJpegR = true;
                }
            }
        }
        assertTrue("the camera was asked about an HDR preview beside JPEG_R", askedAboutJpegR);
    }

    /** A surface that never comes, in a window that never shows, gives way to the SDR preview. */
    @Test
    public void anHdrSurfaceThatNeverComesGivesWayToSdr() {
        givenTenBitCameras();
        givenMain10Encoder();
        makeTheDisplayHdr();
        ShadowConfigurableCameraDevice.setSessionConfigurationSupported(true);
        ShadowSizedSurfaceView.setReportsSurfaces(false);
        try {
            module.setDynamicRange(CameraView.DynamicRange.HDR);
            module.open();
            FakeCamera2.idleMainLooper();
            assertTrue("no session while the HDR surface is awaited",
                    ShadowConfigurableCameraDevice.getSessions().isEmpty()
                            || !hasTenBitOutput(lastSession()));
            ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(3));

            assertFalse(module.hdrSurface().isShowing());
            assertNoTenBitOutput(lastSession());
        } finally {
            ShadowSizedSurfaceView.setReportsSurfaces(true);
        }
    }

    // ---------- The recording ----------

    @Test
    public void anHdrRecordingIsHevcMain10InHlg() {
        givenTenBitCameras();
        givenMain10Encoder();
        module.setDynamicRange(CameraView.DynamicRange.HDR);
        module.open();
        settle();

        module.startRecording(output);
        settle();
        int tenBit = 0;
        for (long profile : lastSession().profiles) {
            if (profile == DynamicRangeProfiles.HLG10) {
                tenBit++;
            }
        }
        assertEquals("the preview and the recording stream HLG", 2, tenBit);
        assertEquals(MediaRecorder.VideoEncoder.HEVC, lastRecorder().getVideoEncoder());
        assertEquals(MediaRecorder.OutputFormat.MPEG_4, lastRecorder().getOutputFormat());
    }

    @Test
    public void anSdrRecordingKeepsItsProfile() {
        givenTenBitCameras();
        givenMain10Encoder();
        module.open();
        settle();

        module.startRecording(output);
        settle();
        assertNoTenBitOutput(lastSession());
        assertTrue(lastRecorder().getVideoEncoder() != MediaRecorder.VideoEncoder.HEVC
                || lastRecorder().getVideoEncoder() == profileEncoder());
    }

    /** The recorder is set up as setProfile would, apart from the encoder. */
    @Test
    public void theHdrProfileMatchesTheCamcorderProfileButForTheEncoder() throws Exception {
        CamcorderProfile profile = CamcorderProfile.get(0, CamcorderProfile.QUALITY_1080P);
        MediaRecorder recorder = new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        VideoSession.VideoSurface.setHdrProfile(recorder, profile,
                MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51);
        ShadowMediaRecorder shadow = Shadows.shadowOf(recorder);
        assertEquals(MediaRecorder.VideoEncoder.HEVC, shadow.getVideoEncoder());
        assertEquals(profile.videoFrameRate, shadow.getVideoFrameRate());
        assertEquals(profile.videoBitRate, shadow.getVideoEncodingBitRate());
        assertEquals(profile.audioCodec, shadow.getAudioEncoder());
        assertEquals(profile.audioSampleRate, shadow.getAudioSamplingRate());
        recorder.release();
    }

    @Test
    public void theMain10LevelIsTheHighestAnEncoderOffers() {
        assertEquals(0, Camera2Module.main10Level());
        // The platform lists its codecs once, so the list is read afresh with the encoder in it.
        ShadowMediaCodecList.reset();
        givenMain10Encoder();
        assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51,
                Camera2Module.main10Level());
    }

    // ---------- Helpers ----------

    private static int profileEncoder() {
        return CamcorderProfile.get(0, CamcorderProfile.QUALITY_1080P).videoCodec;
    }

    private ShadowMediaRecorder lastRecorder() {
        MediaRecorder recorder = ShadowGeotaggingMediaRecorder.getLastRecorder();
        assertNotNull("no recorder was made", recorder);
        return Shadows.shadowOf(recorder);
    }

    private void givenTenBitCameras() {
        // HLG10, combinable with STANDARD, with no extra latency; STANDARD is always there.
        DynamicRangeProfiles profiles = new DynamicRangeProfiles(new long[] {
                DynamicRangeProfiles.HLG10,
                DynamicRangeProfiles.STANDARD | DynamicRangeProfiles.HLG10, 0,
        });
        for (String id : new String[] {FakeCamera2.BACK_ID, FakeCamera2.FRONT_ID}) {
            FakeCamera2.setCharacteristic(activity, id,
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES, new int[] {
                            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE,
                            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT});
            FakeCamera2.setCharacteristic(activity, id,
                    CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES, profiles);
        }
    }

    /**
     * Has both fake cameras list a JPEG_R size. Each map is a spy of the real one, because
     * Robolectric's builder has no JPEG_R configuration to add a size to.
     */
    private void givenJpegR() {
        for (String id : new String[] {FakeCamera2.BACK_ID, FakeCamera2.FRONT_ID}) {
            try {
                StreamConfigurationMap map = activity.getSystemService(CameraManager.class)
                        .getCameraCharacteristics(id)
                        .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                StreamConfigurationMap withJpegR = spy(map);
                doReturn(new Size[] {FakeCamera2.SIZE_1080P})
                        .when(withJpegR).getOutputSizes(ImageFormat.JPEG_R);
                FakeCamera2.setCharacteristic(activity, id,
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP, withJpegR);
            } catch (CameraAccessException error) {
                throw new AssertionError(error);
            }
        }
    }

    private static void givenMain10Encoder() {
        MediaCodecInfo.CodecProfileLevel main10 = new MediaCodecInfo.CodecProfileLevel();
        main10.profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10;
        main10.level = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51;
        MediaCodecInfo.CodecProfileLevel main = new MediaCodecInfo.CodecProfileLevel();
        main.profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain;
        main.level = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel62;
        MediaFormat format = new MediaFormat();
        format.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_HEVC);
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder()
                .setName("test.hevc.encoder")
                .setIsEncoder(true)
                .setCapabilities(MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                        .setMediaFormat(format)
                        .setIsEncoder(true)
                        .setProfileLevels(new MediaCodecInfo.CodecProfileLevel[] {main, main10})
                        .setColorFormats(new int[] {
                                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface})
                        .build())
                .build());
        Camera2Module.resetMain10Level();
    }

    private void makeTheDisplayHdr() {
        Display display = cameraView.getDisplay();
        Shadows.shadowOf(display).setDisplayHdrCapabilities(display.getDisplayId(),
                1000f, 500f, 0.0005f, Display.HdrCapabilities.HDR_TYPE_HLG,
                Display.HdrCapabilities.HDR_TYPE_HDR10);
    }

    /** The newest session, whose first output is the preview. */
    private static ShadowConfigurableCameraDevice.Session lastSession() {
        List<ShadowConfigurableCameraDevice.Session> sessions =
                ShadowConfigurableCameraDevice.getSessions();
        assertFalse("no session was built", sessions.isEmpty());
        return sessions.get(sessions.size() - 1);
    }

    private static boolean hasTenBitOutput(ShadowConfigurableCameraDevice.Session session) {
        for (long profile : session.profiles) {
            if (profile != DynamicRangeProfiles.STANDARD) {
                return true;
            }
        }
        return false;
    }

    private static void assertNoTenBitOutput(ShadowConfigurableCameraDevice.Session session) {
        assertFalse("an output streams 10-bit", hasTenBitOutput(session));
    }

    private static void settle() {
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(1));
        FakeCamera2.idleMainLooper();
    }
}
