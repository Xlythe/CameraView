package com.xlythe.view.camera.ar;

import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.os.Handler;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.WorkerThread;

import com.xlythe.view.camera.ArAnchor;
import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.gl.FrameRenderThread;

import java.util.List;

/**
 * What a camera module needs from something that follows the camera through the room and shares
 * it while doing so. {@link ArCoreSession} is the one there is; the module depends on this so that
 * it can be driven without ARCore's native code.
 *
 * <p>The texture and the geometry are the render thread's, and arrive through the two interfaces
 * this extends. The rest is called on the main thread.</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public interface ArTracker
        extends FrameRenderThread.ExternalCameraTexture, FrameRenderThread.GeometrySource {
    /** Hears how tracking is going. */
    interface Listener {
        /** Called on the render thread whenever tracking changes. */
        @WorkerThread
        void onTrackingChanged(@NonNull CameraView.ArTracking tracking);
    }

    /** Who hears how tracking is going, or null for nobody. */
    @MainThread
    void setListener(@Nullable Listener listener);

    /** The camera the tracker chose, which is the one to open. */
    @NonNull
    String getCameraId();

    /** The size frames arrive at. */
    @NonNull
    Size getTextureSize();

    /** Whether depth is measured, so that a frame can hide what is behind the scene. */
    boolean isDepthSupported();

    /** The device callback to open the camera with, wrapped in the tracker's. */
    @NonNull
    CameraDevice.StateCallback wrap(@NonNull CameraDevice.StateCallback callback,
            @NonNull Handler handler);

    /** The session callback to configure the capture session with, wrapped in the tracker's. */
    @NonNull
    CameraCaptureSession.StateCallback wrap(@NonNull CameraCaptureSession.StateCallback callback,
            @NonNull Handler handler);

    /** The surfaces the tracker needs in the capture session, once the camera is open. */
    @MainThread
    @NonNull
    List<Surface> getSurfaces();

    /**
     * Who hears of each frame the camera captures while tracking runs, which is when there may be
     * a new frame to {@link #advance} to. Set once tracking has resumed.
     */
    @MainThread
    void setCaptureCallback(@NonNull CameraCaptureSession.CaptureCallback callback,
            @NonNull Handler handler);

    /** Starts tracking, once the capture session is active. */
    @MainThread
    boolean resume();

    /** Whether tracking is running, in which case the camera's requests are the tracker's. */
    @MainThread
    boolean isResumed();

    /** Stops tracking, which comes before the camera stops. */
    @MainThread
    void pause();

    /** Ends tracking for good, letting go of every anchor. The render thread has stopped. */
    @MainThread
    void close();

    /**
     * Places an anchor on the first surface found through a point of the buffer.
     *
     * @param s across the buffer from its left, from 0 to 1
     * @param t up the buffer from its bottom, from 0 to 1
     * @return the anchor, or null where nothing was hit or nothing is tracked
     */
    @MainThread
    @Nullable
    ArAnchor hitTest(float s, float t);
}
