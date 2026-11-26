package com.xlythe.view.camera.ar;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;

import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Session;
import com.google.ar.core.SharedCamera;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;
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
 *
 * <h2>Not part of the API</h2>
 *
 * <p>Restricted to the library, because its methods hand out ARCore types and nothing this
 * library offers should require an app to compile against ARCore. What an app uses is
 * {@link ArAvailability} to decide whether to offer AR at all, the AR backend to run it, and
 * {@link ArFrame} to be told where the camera is - none of which name a type ARCore
 * defines.</p>
 *
 * @hide
 */
@RequiresApi(24)
@RestrictTo(RestrictTo.Scope.LIBRARY)
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
     * Takes the newest camera frame and advances tracking with it.
     *
     * <p>This is what consumes the texture from {@link #setCameraTextureName}, so it stands in
     * for {@link android.graphics.SurfaceTexture#updateTexImage} rather than being called
     * alongside it - calling both skips a frame. It has to run on the thread that owns the GL
     * context, with that context current.</p>
     *
     * <p>Whether the frame is being tracked is a separate question, and the caller has to ask
     * it: a frame arrives throughout, and the first ones after a resume have a pose that is not
     * yet worth drawing from. Null means no frame at all, which is a session that is not
     * resumed or a camera that has gone away.</p>
     */
    @Nullable
    public Frame update() {
        try {
            return mSession.update();
        } catch (CameraNotAvailableException | RuntimeException error) {
            Log.w(TAG, "Unable to read an AR frame", error);
            return null;
        }
    }

    /** Whether a frame is tracked, and so whether its pose means anything. */
    public static boolean isTracking(@NonNull Frame frame) {
        return frame.getCamera().getTrackingState() == TrackingState.TRACKING;
    }

    /**
     * Names the texture the camera image is to be read from, which tracking cannot start without.
     *
     * <p>ARCore does not make this texture: it reads the camera out of one the caller created,
     * in the caller's own context, which is why it has to be told the name. Until it has one,
     * {@link #update()} never returns a frame - a session that configures and then tracks
     * nothing is usually this call missing.</p>
     *
     * <p>Must be called on the thread that owns the GL context, with that context current.</p>
     */
    public void setCameraTextureName(int textureName) {
        try {
            mSession.setCameraTextureName(textureName);
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to give the AR session a camera texture", error);
        }
    }

    /**
     * Tells the session what it is being drawn into, which is what makes the pose line up.
     *
     * <p>The projection matrix is built for a particular rotation and shape of viewport, so a
     * session that has not been told about a rotation reports poses for the wrong one and
     * everything drawn from them sits at an angle. Call it when the view is laid out and again
     * whenever the display rotates.</p>
     *
     * @param rotation the display rotation, as {@link android.view.Display#getRotation} gives it
     */
    public void setDisplayGeometry(int rotation, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        try {
            mSession.setDisplayGeometry(rotation, width, height);
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to tell the AR session about the display", error);
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
