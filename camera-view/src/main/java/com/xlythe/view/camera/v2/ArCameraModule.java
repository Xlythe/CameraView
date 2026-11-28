package com.xlythe.view.camera.v2;

import android.Manifest;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;

import com.xlythe.view.camera.ArAnchor;
import com.xlythe.view.camera.Barcode;
import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.ar.ArCoreSession;
import com.xlythe.view.camera.ar.ArMath;
import com.xlythe.view.camera.ar.ArTracker;
import com.xlythe.view.camera.gl.FrameRenderThread;
import com.xlythe.view.camera.gl.GlMath;
import com.xlythe.view.camera.stream.VideoRecorder;

import java.io.File;
import java.util.List;

/**
 * Camera2 with ARCore following the camera through the room.
 *
 * <p>The camera is shared with ARCore rather than handed to it, so this is Camera2 with four
 * seams changed: the camera ARCore chose, its callbacks wrapped around this module's, its surfaces
 * in the capture session, and the frames drawn from the texture it copies them into. The frames
 * always go through the renderer, which is what puts the pose and the depth on every
 * {@link com.xlythe.view.camera.gl.GlFrame}.</p>
 *
 * <p>ARCore owns the camera's requests while it tracks, so what would change them is not offered:
 * zoom, flash and torch, exposure, focus on a point, faces, stabilization and low-light boost. Nor
 * is anything that needs a session of its own - a recording, a stream, frame analysis or another
 * lens. Pictures are drawn from the frames ARCore tracks with.</p>
 */
@RequiresApi(24)
@RestrictTo(RestrictTo.Scope.LIBRARY)
public class ArCameraModule extends Camera2Module {
    /** What follows the camera through the room, kept while AR runs so anchors outlive a close. */
    private final ArTracker mArSession;

    /**
     * Where the camera's capture results arrive while AR runs, or null while closed. Off the main
     * thread, so that asking the renderer for a frame never waits behind the main thread.
     */
    @Nullable
    private HandlerThread mFrameRequestThread;

    /** Asks the renderer for a frame whenever the camera captures one. */
    private final CameraCaptureSession.CaptureCallback mFrameRequests =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    FrameRenderThread effect = getEffect();
                    if (effect != null) {
                        effect.requestFrame();
                    }
                }
            };

    @VisibleForTesting
    ArCameraModule(@NonNull CameraView view, @NonNull ArTracker tracker) {
        super(view);
        mArSession = tracker;
        tracker.setListener(this::onArTrackingChanged);
    }

    /**
     * A module for this view, or null where ARCore will not start a session.
     *
     * <p>The session is started here rather than when the camera opens, so a device or an app
     * that cannot do AR is found out while the caller can still be told.</p>
     */
    @MainThread
    @Nullable
    public static ArCameraModule create(@NonNull CameraView view) {
        ArCoreSession session = ArCoreSession.create(view.getContext());
        return session == null ? null : new ArCameraModule(view, session);
    }

    @Override
    public boolean isArDepthSupported() {
        return mArSession.isDepthSupported();
    }

    @MainThread
    @Nullable
    @Override
    public ArAnchor addArAnchor(float x, float y) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0 || !isCameraReady()) {
            return null;
        }
        Matrix viewToContent = new Matrix();
        if (!getTransform(new Matrix()).invert(viewToContent)) {
            return null;
        }
        float[] values = new float[9];
        viewToContent.getValues(values);
        float[] buffer = ArMath.viewToBuffer(values, width, height,
                GlMath.cameraRequest(getSensorOrientation(), false), x, y);
        return mArSession.hitTest(buffer[0], buffer[1]);
    }

    @Override
    protected void release() {
        mArSession.close();
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    @Override
    public void open() {
        if (mFrameRequestThread == null) {
            HandlerThread thread = new HandlerThread("CameraViewArFrames");
            thread.start();
            mFrameRequestThread = thread;
        }
        super.open();
        if (!isFrameEffectSupported()) {
            // ARCore hands its frames over as a texture, so without GL there is nothing to draw.
            Log.w(TAG, "AR needs GL, which will not run here");
            onArTrackingChanged(CameraView.ArTracking.STOPPED);
        }
    }

    /** Stops tracking before the camera stops, as ARCore requires. */
    @Override
    public void close() {
        mArSession.pause();
        super.close();
        HandlerThread thread = mFrameRequestThread;
        mFrameRequestThread = null;
        if (thread != null) {
            thread.quitSafely();
        }
    }

    @Override
    public void pause() {
        mArSession.pause();
        super.pause();
    }

    @Override
    protected void onCameraError(Throwable error) {
        super.onCameraError(error);
        onArTrackingChanged(CameraView.ArTracking.STOPPED);
    }

    // The seams ARCore changes.

    @NonNull
    @Override
    String getActiveCamera() {
        return mArSession.getCameraId();
    }

    @Override
    boolean wantsEffect() {
        return true;
    }

    /** ARCore's texture in place of the camera's own, and its pose in place of the lens's. */
    @Override
    void configureEffect(@NonNull FrameRenderThread effect) {
        effect.setExternalCameraTexture(mArSession);
        effect.setFrameRenderer(getFrameRenderer());
        effect.setGeometrySource(mArSession);
    }

    @NonNull
    @Override
    Session createIdleSession() {
        return new ArCaptureSession(this, mArSession);
    }

    @NonNull
    @Override
    CameraDevice.StateCallback wrapDeviceCallback(@NonNull CameraDevice.StateCallback callback) {
        return mArSession.wrap(callback, getHandler());
    }

    /**
     * Configures the session through ARCore, and resumes tracking once it is active.
     *
     * <p>Without a colour space: ARCore's callbacks wrap the older form of the call, and AR
     * records in sRGB.</p>
     */
    @Override
    void createCaptureSession(@NonNull List<Surface> surfaces,
            @NonNull CameraCaptureSession.StateCallback callback) throws CameraAccessException {
        CameraDevice device = getCameraDevice();
        if (device == null) {
            throw new IllegalStateException("Camera has no device to configure");
        }
        CameraCaptureSession.StateCallback resuming = new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(@NonNull CameraCaptureSession session) {
                callback.onConfigured(session);
            }

            @Override
            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                callback.onConfigureFailed(session);
            }

            @Override
            public void onActive(@NonNull CameraCaptureSession session) {
                // Only once frames are flowing will ARCore resume, so not in onConfigured.
                if (mArSession.isResumed()) {
                    return;
                }
                if (!mArSession.resume()) {
                    onArTrackingChanged(CameraView.ArTracking.STOPPED);
                    return;
                }
                // ARCore's requests from here on, so its capture results are what say a frame
                // may be waiting: nothing lands on a SurfaceTexture the renderer could listen to.
                HandlerThread thread = mFrameRequestThread;
                mArSession.setCaptureCallback(mFrameRequests,
                        thread == null ? getHandler() : new Handler(thread.getLooper()));
            }
        };
        device.createCaptureSession(surfaces, mArSession.wrap(resuming, getHandler()),
                getHandler());
    }

    @Override
    public void takePicture(File file) {
        if (file == null) {
            throw new IllegalArgumentException("Picture destination cannot be null");
        }
        Session session = getActiveSession();
        if (!(session instanceof ArCaptureSession) || !isCameraReady()) {
            Log.w(TAG, "Cannot take a picture before AR is drawing frames");
            onImageFailed();
            return;
        }
        ((ArCaptureSession) session).takePicture(file);
    }

    // What ARCore leaves this module unable to do.

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @Override
    public void startRecording(File file) {
        Log.w(TAG, "A recording cannot be made while AR is running");
        onVideoFailed();
    }

    @Override
    protected void attachSurface(VideoRecorder.SurfaceProvider surfaceProvider) {
        Log.w(TAG, "Frames cannot be streamed while AR is running");
    }

    @Override
    protected void detachSurface(VideoRecorder.SurfaceProvider surfaceProvider) {}

    @Override
    protected boolean startBarcodeScanner(CameraView.BarcodeDetectorListener listener,
            @Barcode.Format int format, @Barcode.Format int... formats) {
        return false;
    }

    @Override
    protected boolean startSegmentation(CameraView.SegmentationListener listener) {
        return false;
    }

    @Override
    public boolean isFrameAnalysisSupported() {
        return false;
    }

    @Override
    public boolean hasFrontFacingCamera() {
        return false;
    }

    @Override
    public boolean isUsingFrontFacingCamera() {
        return false;
    }

    @Override
    public void toggleCamera() {
        Log.w(TAG, "The lens cannot be changed while AR is running");
    }

    @Override
    public void setLensFacing(CameraView.LensFacing lensFacing) {
        if (lensFacing != CameraView.LensFacing.BACK) {
            Log.w(TAG, "The lens cannot be changed while AR is running");
        }
    }

    @Override
    public boolean isFocusSupported() {
        return false;
    }

    @Override
    public void focus(Rect focus, Rect metering) {}

    @Override
    public void setZoomRatio(float zoomRatio) {}

    @Override
    public float getMinZoomRatio() {
        return 1f;
    }

    @Override
    public float getMaxZoomRatio() {
        return 1f;
    }

    @Override
    public float[] getZoomPresets() {
        return new float[] {1f};
    }

    @Override
    public boolean hasFlash() {
        return false;
    }

    @Override
    public void setTorchEnabled(boolean enabled) {
        if (enabled) {
            Log.w(TAG, "The torch cannot be lit while AR is running");
        }
    }

    @Override
    public boolean isExposureCompensationSupported() {
        return false;
    }

    @Override
    public int getMinExposureCompensation() {
        return 0;
    }

    @Override
    public int getMaxExposureCompensation() {
        return 0;
    }

    @Override
    public float getExposureCompensationStep() {
        return 0f;
    }

    @Override
    public boolean isStabilizationSupported() {
        return false;
    }

    @Override
    public boolean isLowLightBoostSupported() {
        return false;
    }

    @Override
    public boolean isFaceDetectionSupported() {
        return false;
    }

    @Override
    protected boolean setFaceDetectionEnabled(boolean enabled) {
        return !enabled;
    }

    @Override
    public boolean isSnapshotWhileRecordingSupported() {
        return false;
    }

    @Override
    public boolean isPictureFormatSupported(CameraView.PictureFormat format) {
        return format == CameraView.PictureFormat.JPEG;
    }

    @Override
    public boolean isColorSpaceSupported(CameraView.ColorSpace colorSpace) {
        return colorSpace == CameraView.ColorSpace.SRGB;
    }

    @Override
    public synchronized boolean isDynamicRangeSupported(CameraView.DynamicRange dynamicRange) {
        return dynamicRange == CameraView.DynamicRange.SDR;
    }

    @Override
    public synchronized boolean isExtensionSupported(CameraView.Extension extension) {
        return extension == CameraView.Extension.NONE;
    }
}
