package com.xlythe.view.camera.shadow;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.os.Handler;

import androidx.annotation.Nullable;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowCameraManager;

import java.util.concurrent.Executor;

/**
 * Reports the cameras that already exist when an availability callback is registered.
 *
 * <p>A real CameraManager invokes {@code onCameraAvailable} for every currently available camera
 * as soon as a callback is registered; that initial burst is how a caller learns what exists
 * without polling. Robolectric's shadow stores the callback and never calls it, so a caller that
 * waits to be told rather than asking {@code getCameraIdList} waits forever.</p>
 *
 * <p>CameraX is exactly such a caller. From 1.7, camera-camera2 is built on camera2-pipe, whose
 * {@code Camera2DeviceCache} exposes the camera list as a Kotlin Flow fed by an availability
 * callback and suspends on it. With no emission the suspend never resumes, so camera discovery
 * never completes and no use case is ever bound, with no error to show for it. Filling this in is
 * what makes the CameraX backend reachable from a unit test at all.</p>
 */
@Implements(CameraManager.class)
public class ShadowAnnouncingCameraManager extends ShadowCameraManager {
    @RealObject private CameraManager realManager;

    @Implementation
    protected void registerAvailabilityCallback(
            CameraManager.AvailabilityCallback callback, Handler handler) {
        super.registerAvailabilityCallback(callback, handler);
        announceExistingCameras(callback, handler == null ? null : handler::post);
    }

    @Implementation
    protected void registerAvailabilityCallback(
            Executor executor, CameraManager.AvailabilityCallback callback) {
        super.registerAvailabilityCallback(executor, callback);
        announceExistingCameras(callback, executor);
    }

    /** Delivers the initial burst a real CameraManager sends on registration. */
    private void announceExistingCameras(
            CameraManager.AvailabilityCallback callback, @Nullable Executor executor) {
        String[] cameraIds;
        try {
            cameraIds = realManager.getCameraIdList();
        } catch (CameraAccessException | RuntimeException error) {
            return;
        }
        for (String cameraId : cameraIds) {
            Runnable announcement = () -> callback.onCameraAvailable(cameraId);
            if (executor != null) {
                executor.execute(announcement);
            } else {
                announcement.run();
            }
        }
    }
}
