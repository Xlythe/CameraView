package com.xlythe.view.camera.shadow;

import static org.mockito.Mockito.mock;

import android.hardware.camera2.CameraExtensionSession;
import android.hardware.camera2.params.ExtensionSessionConfiguration;

import androidx.annotation.Nullable;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;
import org.robolectric.shadows.ShadowCameraDeviceImpl;

import java.util.ArrayList;
import java.util.List;

/**
 * Adds extension sessions to Robolectric's camera device.
 *
 * <p>The real {@code createExtensionSession} reaches the phone maker's extension service. This
 * configures a mock session instead, on the executor the caller gave, or refuses it as a camera
 * does that listed a mode it cannot run. The session is a Mockito mock, so a test verifies the
 * requests it was sent and plays its callbacks itself.</p>
 */
@Implements(className = "android.hardware.camera2.impl.CameraDeviceImpl", isInAndroidSdk = false)
public class ShadowExtensionCameraDevice extends ShadowCameraDeviceImpl {
    /** Every configuration asked for, oldest first. */
    private static final List<ExtensionSessionConfiguration> sConfigurations = new ArrayList<>();
    /** The session last configured, or null. */
    @Nullable private static CameraExtensionSession sSession;
    /** Whether sessions are refused rather than configured. */
    private static boolean sRefusing;

    /** Refuses every extension session from now on, or configures them again. */
    public static void setRefusing(boolean refusing) {
        sRefusing = refusing;
    }

    /** Every extension session configuration asked for since the last reset. */
    public static List<ExtensionSessionConfiguration> getConfigurations() {
        return new ArrayList<>(sConfigurations);
    }

    /** The session last configured, or null if none was. */
    @Nullable
    public static CameraExtensionSession getSession() {
        return sSession;
    }

    @Resetter
    public static void reset() {
        sConfigurations.clear();
        sSession = null;
        sRefusing = false;
    }

    @Implementation
    protected void createExtensionSession(ExtensionSessionConfiguration configuration) {
        sConfigurations.add(configuration);
        CameraExtensionSession session = mock(CameraExtensionSession.class);
        if (sRefusing) {
            configuration.getExecutor().execute(
                    () -> configuration.getStateCallback().onConfigureFailed(session));
            return;
        }
        sSession = session;
        configuration.getExecutor().execute(
                () -> configuration.getStateCallback().onConfigured(session));
    }
}
