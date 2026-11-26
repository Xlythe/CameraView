package com.xlythe.view.camera.v2;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.util.List;

/**
 * The one capture session a shared-camera AR session will tolerate.
 *
 * <p>A preview session in shape, and separate from {@link PreviewSession} because the three
 * things ARCore requires are all things that session gets right for the plain camera and wrong
 * for this one: the request has to come from {@code TEMPLATE_RECORD}, every surface ARCore asked
 * for has to be a target of it, and the size has to be the one chosen for tracking rather than
 * one chosen for the view.</p>
 *
 * <p>The camera writes into the renderer rather than into the view. That is what lets ARCore see
 * the frame - it reads the camera from a texture, and the renderer owns the only one - and the
 * preview is drawn from there, which is why there is no preview surface in here at all.</p>
 */
@RequiresApi(24)
final class ArCaptureSession extends SessionImpl {
    private final ArCameraModule mModule;
    private final Size mPreviewSize;

    ArCaptureSession(@NonNull ArCameraModule module, @NonNull Size previewSize) {
        super(module);
        mModule = module;
        mPreviewSize = previewSize;
    }

    /**
     * Nothing to choose, and nothing to transform.
     *
     * <p>The size was settled before the camera opened, because ARCore works the camera
     * configuration out from the surface list and had to be told it first. And the preview is
     * drawn by the renderer at the size of the view with the crop ARCore asked for, so the view
     * transform the plain Camera2 path needs would correct a second time; the module clears
     * it.</p>
     */
    @Override
    public void initialize(@NonNull StreamConfigurationMap map) throws CameraAccessException {}

    @Override
    public void onLayoutChanged() throws CameraAccessException {
        mModule.onPreviewLayoutChanged();
    }

    /**
     * ARCore's surfaces, in the order it gave them, and nothing else.
     *
     * <p>Nothing else because there is nothing else to add: the camera image reaches the
     * preview through the texture ARCore reads it into, not through a surface of ours. A third
     * surface would also be one more than some devices can scale to - this phone starves an
     * output rather than refusing the session, which is a failure with no error in it.</p>
     */
    @NonNull
    @Override
    public List<Surface> getSurfaces() {
        List<Surface> surfaces = super.getSurfaces();
        surfaces.addAll(mModule.getArSurfaces());
        return surfaces;
    }

    /**
     * Builds the one request ARCore accepts.
     *
     * <p>{@code TEMPLATE_RECORD} because tracking needs a steady frame rate and the preview
     * template lets the camera drop to half of it in low light, which ARCore reads as the device
     * having moved oddly. Every surface is a target, ARCore's included: a surface in the session
     * that no request writes to gives a session that configures and then tracks nothing.</p>
     *
     * <p>Nothing else is set on it. Flash, metering, zoom and face detection are all either
     * refused by a shared-camera session or quietly break tracking, and the plain Camera2 path
     * is where an app that wants them should be.</p>
     */
    private CaptureRequest createCaptureRequest(@NonNull CameraDevice device)
            throws CameraAccessException {
        CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        for (Surface surface : getSurfaces()) {
            builder.addTarget(surface);
        }
        return builder.build();
    }

    /**
     * Asks for a capture result per frame, which is what drives the preview.
     *
     * <p>The base session returns null here whenever nothing needs results, because a camera
     * that does not have to deliver them is one fewer thing happening per frame. AR does need
     * them: the renderer is not the consumer of the camera, so a capture result is the only
     * notice that a new frame exists, and without one the preview never advances.</p>
     *
     * <p>ARCore has a callback of its own for the same purpose, which the module registers, and
     * both are kept. Once ARCore takes the repeating request over this one stops, and until it
     * does ARCore's has nothing to report - so each covers what the other does not, and an
     * extra draw in between costs one blit.</p>
     */
    @NonNull
    @Override
    CameraCaptureSession.CaptureCallback repeatingCallback() {
        return new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                    @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                mModule.onFrameCaptured(result);
            }
        };
    }

    @Override
    public void onAvailable(@NonNull CameraDevice device, @NonNull CameraCaptureSession session)
            throws CameraAccessException {
        session.setRepeatingRequest(createCaptureRequest(device), repeatingCallback(), getHandler());
        // Only now, with frames on their way into its surfaces, will ARCore resume.
        mModule.onCaptureSessionActive();
    }

    @Override
    public void onInvalidate(@NonNull CameraDevice device, @NonNull CameraCaptureSession session)
            throws CameraAccessException {
        session.setRepeatingRequest(createCaptureRequest(device), repeatingCallback(), getHandler());
    }
}
