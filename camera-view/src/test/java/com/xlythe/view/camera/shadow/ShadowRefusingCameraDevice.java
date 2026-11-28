package com.xlythe.view.camera.shadow;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.os.Handler;
import android.view.Surface;

import androidx.annotation.Nullable;

import org.mockito.Mockito;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;
import org.robolectric.shadows.ShadowCameraDeviceImpl;

import java.util.List;

/**
 * A camera device that refuses some sessions, as hardware does when asked for a combination of
 * outputs it cannot run.
 *
 * <p>Robolectric's device configures every session it is asked for. This one answers
 * {@code onConfigureFailed} instead for a session with more outputs than a limit, or with a
 * particular surface among them, and configures every other session as Robolectric does.</p>
 */
@Implements(className = "android.hardware.camera2.impl.CameraDeviceImpl", isInAndroidSdk = false)
public class ShadowRefusingCameraDevice extends ShadowCameraDeviceImpl {
    /** The most outputs a session may have and still be configured. */
    private static int sMaxOutputs = Integer.MAX_VALUE;
    /** A surface no session is configured with, or null. */
    @Nullable private static Surface sRefusedSurface;
    /** How many sessions have been refused. */
    private static int sRefusals;

    /** Refuses any session with more than this many outputs. */
    public static void setMaxOutputs(int maxOutputs) {
        sMaxOutputs = maxOutputs;
    }

    /** Refuses any session with this surface among its outputs. */
    public static void setRefusedSurface(@Nullable Surface surface) {
        sRefusedSurface = surface;
    }

    /** How many sessions have been refused since the last reset. */
    public static int getRefusals() {
        return sRefusals;
    }

    @Resetter
    public static void reset() {
        sMaxOutputs = Integer.MAX_VALUE;
        sRefusedSurface = null;
        sRefusals = 0;
    }

    @Implementation
    @Override
    protected void createCaptureSession(List<Surface> outputs,
            CameraCaptureSession.StateCallback callback, Handler handler)
            throws CameraAccessException {
        if (outputs.size() > sMaxOutputs || outputs.contains(sRefusedSurface)) {
            sRefusals++;
            CameraCaptureSession refused = Mockito.mock(CameraCaptureSession.class);
            handler.post(() -> callback.onConfigureFailed(refused));
            return;
        }
        super.createCaptureSession(outputs, callback, handler);
    }
}
