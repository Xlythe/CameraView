package com.xlythe.view.camera.shadow;

import android.hardware.camera2.params.SessionConfiguration;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;
import org.robolectric.shadows.ShadowCameraDeviceImpl;

import java.util.ArrayList;
import java.util.List;

/**
 * Adds {@code isSessionConfigurationSupported} to Robolectric's camera device shadow.
 *
 * <p>Robolectric shadows {@code createCaptureSession} but not the query that asks whether a
 * configuration would succeed, so a caller that probes before committing has no way to exercise
 * either answer. The real method reaches the camera service and throws off-device, which pushes
 * every test down the "could not be determined" path.</p>
 *
 * <p>The answer defaults to false, matching a device that cannot be asked, and
 * {@link #setSessionConfigurationSupported} makes it true. Every configuration passed to the
 * query is recorded, so a test can assert what was actually probed rather than only what came
 * back.</p>
 */
@Implements(className = "android.hardware.camera2.impl.CameraDeviceImpl", isInAndroidSdk = false)
public class ShadowConfigurableCameraDevice extends ShadowCameraDeviceImpl {
    private static boolean sSupported = false;
    private static final List<SessionConfiguration> sProbed = new ArrayList<>();

    /** Sets what the camera will answer for any configuration it is asked about. */
    public static void setSessionConfigurationSupported(boolean supported) {
        sSupported = supported;
    }

    /** Every configuration the code under test probed, oldest first. */
    public static List<SessionConfiguration> getProbedConfigurations() {
        return new ArrayList<>(sProbed);
    }

    @Resetter
    public static void reset() {
        sSupported = false;
        sProbed.clear();
    }

    @Implementation
    protected boolean isSessionConfigurationSupported(SessionConfiguration configuration) {
        sProbed.add(configuration);
        return sSupported;
    }
}
