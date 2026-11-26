package com.xlythe.view.camera.x;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;

import com.xlythe.view.camera.Barcode;
import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.test.CameraTestActivity;
import com.xlythe.view.camera.test.DeviceState;
import com.xlythe.view.camera.stream.VideoRecorder;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class CameraXDeviceSmokeTest {
    @Rule
    public final GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO);

    @Test
    public void cameraXBackendBindsAndClosesWithPlainActivityHost() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        Assume.assumeTrue(context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY));

        AtomicReference<CameraXModule> moduleReference = new AtomicReference<>();
        try (ActivityScenario<CameraTestActivity> scenario = ActivityScenario.launch(CameraTestActivity.class)) {
            scenario.onActivity(activity -> {
                CameraView cameraView = activity.getCameraView();
                CameraXModule module = new CameraXModule(cameraView);
                moduleReference.set(module);
                module.open();
            });

            assertTrue("CameraX did not bind Preview and ImageCapture within 20 seconds",
                    waitForBoundState(scenario, moduleReference, true));

            CountDownLatch imageFinished = new CountDownLatch(1);
            AtomicBoolean imageSucceeded = new AtomicBoolean();
            AtomicReference<File> imageFile = new AtomicReference<>();
            scenario.onActivity(activity -> {
                File output = new File(activity.getCacheDir(), "camerax-smoke.jpg");
                imageFile.set(output);
                activity.getCameraView().setImageConfirmationEnabled(false);
                activity.getCameraView().setOnImageCapturedListener(
                        new CameraView.OnImageCapturedListener() {
                            @Override public void onImageConfirmation() {}
                            @Override public void onImageCaptured(File file) {
                                imageSucceeded.set(file.equals(output) && file.length() > 0L);
                                imageFinished.countDown();
                            }
                            @Override public void onFailure() { imageFinished.countDown(); }
                        });
                moduleReference.get().takePicture(output);
            });
            assertTrue("CameraX image capture did not finish within 20 seconds",
                    imageFinished.await(20, TimeUnit.SECONDS));
            assertTrue("CameraX did not produce a non-empty JPEG", imageSucceeded.get());
            assertTrue(imageFile.get().isFile());

            // Scanning reads YUV frames, which some emulators cannot survive.
            if (DeviceState.canFillYuvBuffers()) {
                scenario.onActivity(activity -> moduleReference.get().startBarcodeScanner(
                        barcodes -> {}, Barcode.Format.QR_CODE));
                assertTrue("CameraX ImageAnalysis did not bind for barcode scanning",
                        waitForBoundState(scenario, moduleReference, true));
                scenario.onActivity(activity -> moduleReference.get().stopBarcodeScanner());
                assertTrue("CameraX did not restore ImageCapture after barcode scanning",
                        waitForBoundState(scenario, moduleReference, true));
            }

            scenario.onActivity(activity -> {
                CameraXModule module = moduleReference.get();
                if (module.isStabilizationSupported()) {
                    module.setStabilizationEnabled(true);
                    assertTrue(module.isStabilizationEnabled());
                }
                if (module.isExposureCompensationSupported()) {
                    module.setExposureCompensation(module.getMinExposureCompensation());
                }
                if (module.isLowLightBoostSupported()) {
                    module.setLowLightBoostEnabled(true);
                    assertTrue(module.isLowLightBoostEnabled());
                    module.setLowLightBoostEnabled(false);
                }
                if (module.isDynamicRangeSupported(CameraView.DynamicRange.HDR)) {
                    module.setDynamicRange(CameraView.DynamicRange.HDR);
                    assertEquals(CameraView.DynamicRange.HDR, module.getDynamicRange());
                    module.setDynamicRange(CameraView.DynamicRange.SDR);
                }
                for (CameraView.Extension extension : CameraView.Extension.values()) {
                    if (extension != CameraView.Extension.NONE && module.isExtensionSupported(extension)) {
                        module.setExtension(extension);
                        assertEquals(extension, module.getExtension());
                        module.setExtension(CameraView.Extension.NONE);
                        break;
                    }
                }
            });
            ByteArrayOutputStream streamOutput = new ByteArrayOutputStream();
            AtomicReference<VideoRecorder> streamRecorder = new AtomicReference<>();
            scenario.onActivity(activity -> {
                VideoRecorder recorder = new VideoRecorder(
                        moduleReference.get().getCanvas(), streamOutput);
                streamRecorder.set(recorder);
                recorder.start();
            });
            assertTrue("CameraX custom stream did not produce encoded metadata",
                    waitForStreamBytes(streamOutput));
            streamRecorder.get().stop();
            Thread.sleep(1_000L);

            CountDownLatch videoFinished = new CountDownLatch(1);
            AtomicBoolean videoSucceeded = new AtomicBoolean();
            scenario.onActivity(activity -> {
                File output = new File(activity.getCacheDir(), "camerax-smoke.mp4");
                activity.getCameraView().setVideoConfirmationEnabled(false);
                activity.getCameraView().setOnVideoCapturedListener(
                        new CameraView.OnVideoCapturedListener() {
                            @Override public void onVideoConfirmation() {}
                            @Override public void onVideoCaptured(File file) {
                                videoSucceeded.set(file.equals(output) && file.length() > 0L);
                                videoFinished.countDown();
                            }
                            @Override public void onFailure() { videoFinished.countDown(); }
                        });
                CameraXModule module = moduleReference.get();
                // Seconds rather than a moment, as in BackendMatrixDeviceTest: an encoder behind
                // a software-rendered surface needs that long to produce anything.
                module.setMaxVideoDuration(5_000L);
                module.startRecording(output);
            });
            assertTrue("CameraX timed recording did not finalize within 30 seconds",
                    videoFinished.await(30, TimeUnit.SECONDS));
            // An emulator's software encoder can still finalize with no valid data, which is the
            // encoder's limit rather than the library's. It has to report back like hardware,
            // but only hardware is held to producing the file, and the rest of this test runs.
            assertTrue("CameraX did not produce a non-empty MP4",
                    videoSucceeded.get() || DeviceState.isEmulator());

            scenario.onActivity(activity -> {
                CameraXModule module = moduleReference.get();
                if (module.hasFrontFacingCamera()) {
                    module.toggleCamera();
                    assertTrue(module.isUsingFrontFacingCamera());
                    assertTrue(module.isCameraBoundForTesting());
                    module.toggleCamera();
                    assertFalse(module.isUsingFrontFacingCamera());
                    assertTrue(module.isCameraBoundForTesting());
                }
            });

            scenario.moveToState(Lifecycle.State.CREATED);
            assertTrue("CameraX remained bound after the host stopped",
                    waitForBoundState(scenario, moduleReference, false));
            scenario.moveToState(Lifecycle.State.RESUMED);
            assertTrue("CameraX did not rebind after the host resumed",
                    waitForBoundState(scenario, moduleReference, true));
            scenario.onActivity(activity -> {
                CameraXModule module = moduleReference.get();
                assertEquals(Lifecycle.State.STARTED, module.getLifecycle().getCurrentState());
                module.close();
                assertFalse(module.isCameraBoundForTesting());
                assertEquals(Lifecycle.State.CREATED, module.getLifecycle().getCurrentState());
            });
        }
    }

    private static boolean waitForStreamBytes(ByteArrayOutputStream output)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000L;
        while (System.currentTimeMillis() < deadline) {
            if (output.size() > 0) return true;
            Thread.sleep(100L);
        }
        return false;
    }

    private static boolean waitForBoundState(
            ActivityScenario<CameraTestActivity> scenario,
            AtomicReference<CameraXModule> moduleReference,
            boolean expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000L;
        AtomicBoolean matches = new AtomicBoolean();
        while (System.currentTimeMillis() < deadline) {
            scenario.onActivity(activity -> matches.set(
                    moduleReference.get().isCameraBoundForTesting() == expected));
            if (matches.get()) return true;
            Thread.sleep(100L);
        }
        return false;
    }
}