package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

/**
 * A registered availability callback has to be told about the cameras that already exist, which
 * is how a caller that waits to be told rather than polling learns anything at all.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, shadows = {com.xlythe.view.camera.shadow.ShadowAnnouncingCameraManager.class})
public class AvailabilityCallbackTest {
    @Test
    public void registeringAnAvailabilityCallbackReportsTheCameras() {
        Context context = RuntimeEnvironment.getApplication();
        FakeCamera2.addBackAndFrontCameras(context);
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

        List<String> available = new ArrayList<>();
        manager.registerAvailabilityCallback(new CameraManager.AvailabilityCallback() {
            @Override public void onCameraAvailable(String cameraId) { available.add(cameraId); }
        }, new Handler(Looper.getMainLooper()));
        ShadowLooper.shadowMainLooper().idle();

        assertEquals("callbacks received", 2, available.size());
    }
}
