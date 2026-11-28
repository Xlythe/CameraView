package com.xlythe.view.camera.shadow;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.os.Build;
import android.os.Handler;
import android.view.Surface;

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
 * back. So is every configuration a session was built from.</p>
 */
@Implements(className = "android.hardware.camera2.impl.CameraDeviceImpl", isInAndroidSdk = false)
public class ShadowConfigurableCameraDevice extends ShadowCameraDeviceImpl {
    private static boolean sSupported = false;
    private static final List<SessionConfiguration> sProbed = new ArrayList<>();
    private static final List<SessionConfiguration> sCreated = new ArrayList<>();
    private static final List<Session> sSessions = new ArrayList<>();

    /** One session the code under test built: its surfaces, and the profile each streams in. */
    public static final class Session {
        /** The surfaces, in the order given. */
        public final List<Surface> surfaces = new ArrayList<>();
        /** The dynamic range profile of each surface, 1 (STANDARD) for a plain surface. */
        public final List<Long> profiles = new ArrayList<>();
    }

    /** Sets what the camera will answer for any configuration it is asked about. */
    public static void setSessionConfigurationSupported(boolean supported) {
        sSupported = supported;
    }

    /** Every configuration the code under test probed, oldest first. */
    public static List<SessionConfiguration> getProbedConfigurations() {
        return new ArrayList<>(sProbed);
    }

    /** Every configuration a session was built from, oldest first. */
    public static List<SessionConfiguration> getCreatedConfigurations() {
        return new ArrayList<>(sCreated);
    }

    /** Every session built, from a configuration or a list of surfaces, oldest first. */
    public static List<Session> getSessions() {
        return new ArrayList<>(sSessions);
    }

    @Resetter
    public static void reset() {
        sSupported = false;
        sProbed.clear();
        sCreated.clear();
        sSessions.clear();
    }

    @Implementation
    @Override
    protected void createCaptureSession(List<Surface> outputs,
            CameraCaptureSession.StateCallback callback, Handler handler)
            throws CameraAccessException {
        Session session = new Session();
        for (Surface surface : outputs) {
            session.surfaces.add(surface);
            session.profiles.add(1L);
        }
        sSessions.add(session);
        super.createCaptureSession(outputs, callback, handler);
    }

    @Implementation
    @Override
    protected void createCaptureSession(SessionConfiguration configuration)
            throws CameraAccessException {
        sCreated.add(configuration);
        Session session = new Session();
        for (OutputConfiguration output : configuration.getOutputConfigurations()) {
            session.surfaces.add(output.getSurface());
            session.profiles.add(Build.VERSION.SDK_INT >= 33 ? output.getDynamicRangeProfile() : 1L);
        }
        sSessions.add(session);
        super.createCaptureSession(configuration);
    }

    @Implementation
    protected boolean isSessionConfigurationSupported(SessionConfiguration configuration) {
        sProbed.add(configuration);
        return sSupported;
    }
}
