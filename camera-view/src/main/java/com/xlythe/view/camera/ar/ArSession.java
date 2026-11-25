package com.xlythe.view.camera.ar;

import android.content.Context;
import android.media.Image;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Session;
import com.google.ar.core.SharedCamera;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.NotYetAvailableException;
import com.google.ar.core.exceptions.UnavailableException;

import java.util.EnumSet;
import java.util.List;

/**
 * An ARCore session that shares the camera, and the depth it can report.
 *
 * <p>Shared rather than exclusive because this is a camera library: an app using it already has
 * a preview, a recording and a still, and ARCore taking the camera for itself would end all
 * three. {@link Session.Feature#SHARED_CAMERA} is the documented way to have both, and it is a
 * Camera2 arrangement - ARCore hands over its own surfaces and wraps the device and session
 * callbacks, and the app opens the camera with them. There is no CameraX equivalent; every
 * sample and every line of the reference uses Camera2.</p>
 *
 * <h2>What this is and is not</h2>
 *
 * <p>This owns the session and the depth. It does not open the camera: the surfaces from
 * {@link #getSurfaces()} and the callback wrappers belong in whichever backend configures the
 * capture session, and that is Camera2's job rather than this class's. Keeping the two apart
 * means the session can be created, configured and asked about depth on a device without any
 * of the camera plumbing existing yet, which is what makes any of it testable.</p>
 *
 * <p>Two rules from the documentation that are easy to break and expensive to debug. The
 * capture request must use {@code TEMPLATE_RECORD}, and while ARCore is resumed the app must
 * not call {@code setRepeatingRequest} itself - either one produces a session that looks like
 * it is working and tracks nothing.</p>
 */
@RequiresApi(24)
public final class ArSession implements AutoCloseable {
    private static final String TAG = "ArSession";

    private final Session mSession;
    private final SharedCamera mSharedCamera;
    private final String mCameraId;
    private final boolean mDepthSupported;

    private ArSession(@NonNull Session session, @NonNull SharedCamera sharedCamera,
            @NonNull String cameraId, boolean depthSupported) {
        mSession = session;
        mSharedCamera = sharedCamera;
        mCameraId = cameraId;
        mDepthSupported = depthSupported;
    }

    /**
     * Creates a session that shares the camera, or returns null if this device will not.
     *
     * <p>Null rather than an exception for every ordinary reason - ARCore absent, out of date,
     * declined, or the device never supported it. None of those is a programming error and all
     * of them are things an app has to carry on without.</p>
     *
     * <p>No availability check first. Constructing the session is itself the check, and it
     * throws the specific reason rather than the summary {@link ArAvailability} can give, so
     * asking twice would only add a blocking call to a method an app may reasonably call on
     * the main thread. {@link ArAvailability} is for deciding whether to offer AR at all.</p>
     */
    @Nullable
    public static ArSession create(@NonNull Context context) {
        Session session = null;
        try {
            session = new Session(context, EnumSet.of(Session.Feature.SHARED_CAMERA));
            SharedCamera sharedCamera = session.getSharedCamera();
            String cameraId = session.getCameraConfig().getCameraId();

            Config config = session.getConfig();
            // Depth is what decides whether something can be drawn behind a real object, and it
            // is off unless asked for. A device that cannot do it still tracks perfectly well.
            boolean depthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC);
            if (depthSupported) {
                config.setDepthMode(Config.DepthMode.AUTOMATIC);
            }
            // The camera is driven by the app's capture session, so ARCore must not also be
            // trying to pace it.
            config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
            session.configure(config);
            return new ArSession(session, sharedCamera, cameraId, depthSupported);
        } catch (UnavailableException | RuntimeException error) {
            Log.w(TAG, "Unable to start a shared-camera AR session", error);
            if (session != null) {
                session.close();
            }
            return null;
        }
    }

    /** The camera ARCore chose, which is the one the capture session has to open. */
    @NonNull
    public String getCameraId() {
        return mCameraId;
    }

    /**
     * The surfaces ARCore needs in the capture session, in the order it guarantees.
     *
     * <p>All of them, and the surfaces of the app after them. Leaving one out gives a session
     * that configures and then tracks nothing.</p>
     *
     * <p><b>Only while the camera device is open.</b> ARCore builds these readers from the
     * opened device, so this has to be called from {@code onOpened} or later - and calling it
     * earlier does not throw, it aborts the process from native code with a JNI error naming
     * {@code nativeSharedCameraGetImageReaderMotionTracking}. There is nothing a caller can
     * catch, so the order is the whole contract: open the camera with the callbacks from
     * {@link #getSharedCamera()}, then ask for these, then configure the capture session.</p>
     */
    @NonNull
    public List<android.view.Surface> getSurfaces() {
        return mSharedCamera.getArCoreSurfaces();
    }

    /** The shared camera, whose callback wrappers the capture session has to be built with. */
    @NonNull
    public SharedCamera getSharedCamera() {
        return mSharedCamera;
    }

    /** Whether this device can report depth, and so whether occlusion is possible at all. */
    public boolean isDepthSupported() {
        return mDepthSupported;
    }

    /** Starts tracking. The capture session must already be configured and running. */
    public boolean resume() {
        try {
            mSession.resume();
            return true;
        } catch (CameraNotAvailableException | RuntimeException error) {
            Log.w(TAG, "Unable to resume the AR session", error);
            return false;
        }
    }

    /** Stops tracking, leaving the camera to the app. */
    public void pause() {
        try {
            mSession.pause();
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to pause the AR session", error);
        }
    }

    /**
     * The newest frame, or null if there is not one yet.
     *
     * <p>A session that has just resumed has nothing to give for the first few frames, and one
     * that is not tracking has nothing worth having, so both answer null rather than making the
     * caller check two things.</p>
     */
    @Nullable
    public Frame update() {
        try {
            Frame frame = mSession.update();
            return frame.getCamera().getTrackingState() == TrackingState.TRACKING ? frame : null;
        } catch (CameraNotAvailableException | RuntimeException error) {
            Log.w(TAG, "Unable to read an AR frame", error);
            return null;
        }
    }

    /**
     * How far away the scene is, for the frame given, in the coordinates of a view.
     *
     * <p>The smoothed depth rather than the raw: it fills the gaps a sparse reading leaves and
     * is reused from an earlier frame when a new one is not ready, both of which suit deciding
     * what is in front of what better than a reading that is absent half the time.</p>
     *
     * @return a lookup, or null when this frame has no depth to give
     */
    @Nullable
    public DepthLookup depthFor(@NonNull Frame frame, int viewWidth, int viewHeight) {
        if (!mDepthSupported) {
            return null;
        }
        try (Image depth = frame.acquireDepthImage16Bits()) {
            Image.Plane plane = depth.getPlanes()[0];
            return new DepthLookup(plane.getBuffer(), depth.getWidth(), depth.getHeight(),
                    plane.getRowStride(), viewWidth, viewHeight);
        } catch (NotYetAvailableException notYet) {
            // Ordinary for the first frames after a resume, and not worth logging every time.
            return null;
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to read depth from this frame", error);
            return null;
        }
    }

    @Override
    public void close() {
        try {
            mSession.close();
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to close the AR session", error);
        }
    }
}
