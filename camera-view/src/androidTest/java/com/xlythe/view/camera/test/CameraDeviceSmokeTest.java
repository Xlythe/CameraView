package com.xlythe.view.camera.test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraManager;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;

import com.xlythe.view.camera.CameraView;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public final class CameraDeviceSmokeTest {
    @Rule
    public final GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
    );

    /** Skips, rather than fails, a test that the emulator's camera HAL stalled under. */
    @Rule
    public final TestRule cameraHal = DeviceState.skipWhenTheEmulatorCameraHalStalls();

    @Test
    public void advertisedCameraFeatureHasAUsableCameraId() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        Assume.assumeTrue(context.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_CAMERA_ANY));

        CameraManager cameraManager = (CameraManager) context.getSystemService(
                Context.CAMERA_SERVICE);
        assertNotNull(cameraManager);
        assertTrue("Android advertises a camera feature but exposes no camera IDs",
                cameraManager.getCameraIdList().length > 0);
    }

    @Test
    public void inflatedCameraViewOpensAndClosesAcrossActivityLifecycle() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        Assume.assumeTrue(context.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_CAMERA_ANY));

        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);

        try (ActivityScenario<CameraTestActivity> scenario =
                     ActivityScenario.launch(CameraTestActivity.class)) {
            scenario.onActivity(activity -> {
                CameraView cameraView = activity.getCameraView();
                assertNotNull(cameraView);
                assertFalse(cameraView.isOpen());
                cameraView.setOnCameraStateChangedListener(
                        new CameraView.OnCameraStateChangedListener() {
                            @Override
                            public void onCameraOpened() {
                                opened.countDown();
                            }

                            @Override
                            public void onCameraClosed() {
                                closed.countDown();
                            }
                        });
                cameraView.open();
            });

            assertTrue("CameraView did not reach OPEN within 15 seconds",
                    opened.await(15, TimeUnit.SECONDS));
            scenario.onActivity(activity -> {
                assertTrue(activity.getCameraView().isOpen());
                activity.getCameraView().close();
            });
            assertTrue("CameraView did not reach CLOSED within 15 seconds",
                    closed.await(15, TimeUnit.SECONDS));
            scenario.onActivity(activity -> assertFalse(activity.getCameraView().isOpen()));
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertNotNull(activity.getCameraView());
                assertFalse(activity.getCameraView().isOpen());
            });
        }
    }
}
