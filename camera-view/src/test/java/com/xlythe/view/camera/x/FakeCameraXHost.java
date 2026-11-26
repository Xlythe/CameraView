package com.xlythe.view.camera.x;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.view.ViewGroup;

import androidx.camera.lifecycle.ProcessCameraProvider;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.v2.FakeCamera2;

import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;

/**
 * Stands CameraX up against Robolectric's camera2 shadows.
 *
 * <p>From 1.7 camera-camera2 is built on camera2-pipe, which still reaches the platform through
 * the CameraManager, CameraDevice and CameraCaptureSession the Camera2 tests already drive, so the
 * module can be exercised without a device. Four things have to be arranged that a real app gets
 * for free:</p>
 *
 * <ul>
 *   <li>the backend has to be named, because CameraX discovers it through manifest metadata a
 *       unit test does not merge;</li>
 *   <li>the camera has to report characteristics, which the shadows leave empty;</li>
 *   <li>the view has to be attached to a window, because the module will not bind to a host that
 *       is not showing;</li>
 *   <li>and an availability callback has to actually be called, which
 *       {@code ShadowAnnouncingCameraManager} arranges. Pipe discovers cameras by waiting to be
 *       told, so without it discovery never completes.</li>
 * </ul>
 */
public final class FakeCameraXHost {
    private static boolean sConfigured;

    private final ActivityController<Activity> mController;
    private final HostedCameraView mCameraView;

    /** A CameraView whose SurfaceTexture is always available. */
    public static class HostedCameraView extends CameraView {
        private final SurfaceTexture mSurfaceTexture = new SurfaceTexture(0);

        HostedCameraView(Context context) {
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

    public FakeCameraXHost() {
        Context application = org.robolectric.RuntimeEnvironment.getApplication();
        ShadowPackageManager packageManager = Shadows.shadowOf(application.getPackageManager());
        packageManager.setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true);
        packageManager.setSystemFeature(PackageManager.FEATURE_CAMERA, true);
        packageManager.setSystemFeature(PackageManager.FEATURE_CAMERA_FRONT, true);
        FakeCamera2.addBackAndFrontCameras(application);
        addVideoEncoder();
        configureOnce();

        mController = Robolectric.buildActivity(Activity.class).setup();
        Activity activity = mController.get();
        mCameraView = new HostedCameraView(activity);
        activity.setContentView(mCameraView, new ViewGroup.LayoutParams(1080, 1920));
        mCameraView.layout(0, 0, 1080, 1920);
        idle();
    }

    /**
     * Gives the fake device an H.264 encoder.
     *
     * <p>Robolectric starts with no codecs at all, and CameraX resolves encoder capabilities while
     * working out what it can record: with an empty codec list
     * {@code MediaCodecList.getInfoFor} throws out of bounds from inside
     * DefaultEncoderProfilesProvider. Every real device has an AVC encoder, so the fake has one
     * too.</p>
     *
     * <p>It has to be described through a MediaFormat rather than
     * {@code CodecCapabilities.createFromProfileLevel}, which reports no video capabilities at
     * all: CameraX asks the encoder which widths, heights and frame rates it supports before it
     * will agree to record, and a codec that cannot answer that is worse than no codec.</p>
     */
    private static void addVideoEncoder() {
        org.robolectric.shadows.ShadowMediaCodecList.reset();
        MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 17_000_000);
        MediaCodecInfo.CodecProfileLevel profileLevel = new MediaCodecInfo.CodecProfileLevel();
        profileLevel.profile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh;
        profileLevel.level = MediaCodecInfo.CodecProfileLevel.AVCLevel51;
        MediaCodecInfo.CodecCapabilities capabilities =
                org.robolectric.shadows.MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                        .setMediaFormat(format)
                        .setIsEncoder(true)
                        .setProfileLevels(
                                new MediaCodecInfo.CodecProfileLevel[] {profileLevel})
                        .setColorFormats(new int[] {
                                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                        })
                        .build();
        org.robolectric.shadows.ShadowMediaCodecList.addCodec(
                org.robolectric.shadows.MediaCodecInfoBuilder.newBuilder()
                        .setName("fake.avc.encoder")
                        .setIsEncoder(true)
                        .setIsHardwareAccelerated(true)
                        .setCapabilities(capabilities)
                        .build());
    }

    /**
     * Names CameraX's backend, once per process. Configuration is process-wide and refuses to be
     * repeated, while Robolectric reuses the process across the methods of a test class.
     */
    private static void configureOnce() {
        if (sConfigured) {
            return;
        }
        sConfigured = true;
        ProcessCameraProvider.configureInstance(
                androidx.camera.camera2.Camera2Config.defaultConfig());
    }

    public HostedCameraView getCameraView() {
        return mCameraView;
    }

    /** Runs everything posted to the main looper, which is where the module keeps its state. */
    public static void idle() {
        ShadowLooper.shadowMainLooper().idle();
    }

    /**
     * Idles until the module reports a bound camera, or gives up.
     *
     * <p>Pipe does its discovery on its own coroutine dispatchers, which are backed by real
     * threads here, so this yields between passes rather than spinning: idling the main looper
     * alone would never let that work progress.</p>
     *
     * @return whether a camera was bound
     */
    public static boolean awaitBind(CameraXModule module) {
        for (int attempt = 0; attempt < 100; attempt++) {
            idle();
            if (module.isCameraBoundForTesting()) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    public void release() {
        mCameraView.release();
        mController.pause().stop().destroy();
        idle();
    }
}
