package com.xlythe.view.camera.v2;

import android.Manifest;
import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.ICameraModule;
import com.xlythe.view.camera.stream.VideoRecorder;

import java.io.File;
import java.util.List;
import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;
import androidx.annotation.RestrictTo;

/**
 * A wrapper around the Camera2 APIs. Camera2 has some peculiarities, such as crashing if you attach
 * too many surfaces (or too large a surface) to a capture session. To get around that, we define
 * {@link Session}s that list out compatible surfaces and creates capture requests for them.
 */
@TargetApi(21)
@RestrictTo(RestrictTo.Scope.LIBRARY)
public class Camera2Module extends ICameraModule {
    private static final int ZOOM_NOT_SUPPORTED = 1;

    private static final String EXTRA_DEVICE_ID = "device_id";

    // It seems like, when Lollipop was released, 1080p was settled on as the 'default' resolution.
    // Camera2 has a long table of different combinations of preview, picture, and video with the
    // repeated requirement "Up to either 1080p, or the largest size supported by the device.
    // Whichever is smaller." To go past 1080p requires limiting another column on the table. As a
    // general rule, we don't go past 1080p unless we test on a wide range of devices.
    static final Size MAX_SUPPORTED_SIZE = new Size(1920, 1080);

    /**
     * This is how we'll talk to the camera.
     */
    @NonNull
    private final CameraManager mCameraManager;

    /**
     * This is the id of the camera (eg. front or back facing) that we're currently using.
     */
    @Nullable
    private String mActiveCamera;

    /**
     * The current capture session. There is one capture session per {@link Session}.
     */
    @Nullable
    private CameraCaptureSession mCaptureSession;

    /**
     * The currently active camera. This may be a front facing camera or a back facing one.
     */
    @Nullable
    private CameraDevice mCameraDevice;

    /**
     * A handler to receive callbacks from the camera on.
     */
    @NonNull
    private final Handler mHandler;

    /**
     * The currently active session. See {@link PictureSession} and {@link VideoSession}.
     */
    @Nullable
    private Session mActiveSession;

    /**
     * The current zoom level, from 0 to {@link #getMaxZoomLevel()}.
     */
    private int mZoomLevel;

    /**
     * If true, the camera is currently open.
     */
    private boolean mIsOpen = false;

    /**
     * If true, the preview should be paused.
     */
    private boolean mIsPaused = false;

    /**
     * If true, we are currently recording.
     */
    private boolean mIsRecording = false;

    /**
     *
     */
    private boolean mIsAttemptingToReopen = false;

    /**
     * Callbacks for when the camera is available / unavailable
     */
    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            if (!mIsOpen) {
                Log.w(TAG, "Camera was opened after CameraView was closed. Disconnecting from the camera.");
                close();
                return;
            }

            // The camera has opened. Start the preview now.
            mCameraDevice = cameraDevice;
            setSession(new PictureSession(Camera2Module.this));

            // Once we've successfully opened, we clean up any flags.
            mIsAttemptingToReopen = false;
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            Log.w(TAG, "Camera disconnected");

            // The camera has disconnected or errored out and we need to close ourselves to reset state.
            // We'll try one time to reconnect before accepting defeat.

            // Cache if the CameraView wants us to be open. We could have disconnected because
            // we were closed.
            boolean isCameraViewOpen = mIsOpen;

            // Close to clean up our state.
            close();

            // If we should be open, and if we haven't attempted to reopen before, try opening again now.
            if (isCameraViewOpen && !mIsAttemptingToReopen) {
                mIsAttemptingToReopen = true;
                open();
            }
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            Log.e(TAG, "Camera crashed: " + Camera2Module.toString(error));
            onDisconnected(cameraDevice);
        }
    };

    private static String toString(int error) {
        switch (error) {
            case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE:
                return "ERROR_CAMERA_DEVICE";
            case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:
                return "ERROR_CAMERA_DISABLED";
            case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
                return "ERROR_CAMERA_IN_USE";
            case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE:
                return "ERROR_CAMERA_SERVICE";
            case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
                return "ERROR_MAX_CAMERAS_IN_USE";
        }
        return String.format(Locale.US, "UNKNOWN_ERROR(%d)", error);
    }

    public Camera2Module(CameraView cameraView) {
        super(cameraView);
        mCameraManager = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
        mHandler = new Handler(Looper.getMainLooper());
    }

    private void setSession(final Session session) {
        if (mCameraDevice == null) {
            if (DEBUG) Log.w(TAG, "Cannot start a session without a CameraDevice");
            return;
        }

        try {
            // Clean up any previous sessions
            boolean hasPreviousState = false;
            if (mCaptureSession != null) {
                mCaptureSession.stopRepeating();
                try {
                    mCaptureSession.abortCaptures();
                } catch (CameraAccessException e) {
                    Log.e(TAG, "Failed to abort captures", e);
                }
                mCaptureSession.close();
                mCaptureSession = null;
                hasPreviousState = true;
            }
            if (mActiveSession != null) {
                // Restore state from the previous session
                session.setMeteringRectangle(mActiveSession.getMeteringRectangle());
                session.setCropRegion(mActiveSession.getCropRegion());

                mActiveSession.close();
                mActiveSession = null;
                hasPreviousState = true;
            }
            if (hasPreviousState) {
                mHandler.post(() -> setSession(session));
                return;
            }

            // Assume this is a brand new session that's never been set up. Initialize it so that
            // it can decide what size to set its surfaces to.
            CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(getActiveCamera());
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            session.initialize(map);

            // Now, with all of our surfaces, we'll ask for a new session
            mCameraDevice.createCaptureSession(session.getSurfaces(), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    if (mCameraDevice == null) {
                        return;
                    }

                    try {
                        mCaptureSession = cameraCaptureSession;
                        mActiveSession = session;
                        if (!mIsPaused) {
                            session.onAvailable(mCameraDevice, mCaptureSession);
                        }
                        if (mZoomLevel != 0) {
                            setZoomLevel(mZoomLevel);
                        }
                    } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
                        Log.e(TAG, "Failed to start session " + session.getClass().getSimpleName(), e);
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    Log.e(TAG, "Configure failed");
                }
            }, mHandler);
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            // Crashes if the Camera is interacted with while still loading
            Log.e(TAG, "Failed to create capture session", e);
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    @Override
    public void open() {
        mIsOpen = true;
        try {
            mActiveCamera = getActiveCamera();
            if (DEBUG) Log.d(TAG, "Opening camera " + mActiveCamera);
            mCameraManager.openCamera(mActiveCamera, mStateCallback, mHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to open camera", e);
        }
    }

    @Override
    public void close() {
        if (mCaptureSession != null) {
            try {
                mCaptureSession.close();
            } catch (CameraAccessException e) {
                Log.e(TAG, "Failed to close camera", e);
            }
            mCaptureSession = null;
        }
        if (mActiveSession != null) {
            mActiveSession.close();
            mActiveSession = null;
        }
        if (mCameraDevice != null) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
        mIsPaused = false;
        mIsOpen = false;
    }

    @Override
    public boolean hasFrontFacingCamera() {
        try {
            for (String cameraId : mCameraManager.getCameraIdList()) {
                if (isFrontFacing(cameraId)) return true;
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to query camera", e);
        }
        return false;
    }

    @Override
    public boolean isUsingFrontFacingCamera() {
        try {
            return isFrontFacing(getActiveCamera());
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to query camera", e);
        }
        return false;
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    @Override
    public void toggleCamera() {
        int position = 0;

        try {
            for (String cameraId : mCameraManager.getCameraIdList()) {
                if (cameraId.equals(getActiveCamera())) {
                    break;
                }
                position++;
            }

            // Close the old camera and open with the new camera id, but only if it was already
            // open before toggle was requested.
            boolean shouldOpen = mIsOpen;
            close();
            mActiveCamera = mCameraManager.getCameraIdList()[(position + 1) % mCameraManager.getCameraIdList().length];
            if (shouldOpen) {
                open();
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to query camera", e);
        }
    }

    @Override
    public void setLensFacing(CameraView.LensFacing lensFacing) {
        int position = 0;

        try {
            for (String cameraId : mCameraManager.getCameraIdList()) {
                boolean isFrontFacing = isFrontFacing(cameraId);
                if ((isFrontFacing && lensFacing.equals(CameraView.LensFacing.FRONT))
                    || (!isFrontFacing && lensFacing.equals(CameraView.LensFacing.BACK))) {
                    break;
                }
                position++;
            }

            // Close the old camera and open with the new camera id, but only if it was already
            // open before toggle was requested.
            boolean shouldOpen = mIsOpen;
            close();
            mActiveCamera = mCameraManager.getCameraIdList()[position % mCameraManager.getCameraIdList().length];
            if (shouldOpen) {
                open();
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to query camera", e);
        }
    }

    @Override
    public void focus(Rect focus, Rect metering) {
        if (mCaptureSession == null) {
            if (DEBUG) Log.w(TAG, "Cannot focus. No capture session.");
            return;
        }

        if (mActiveSession == null) {
            if (DEBUG) Log.w(TAG, "Cannot focus. No active session.");
            return;
        }

        if (mCameraDevice == null) {
            if (DEBUG) Log.w(TAG, "Cannot focus. No camera device.");
            return;
        }

        try {
            if (!supportsFocus(getActiveCamera())) {
                Log.w(TAG, "Focus not available on this camera");
                return;
            }

            if (mActiveSession == null) {
                Log.w(TAG, "No active session available");
                return;
            }

            // Our metering Rect ranges from -1000 to 1000. We need to remap it to fit the camera dimensions (0 to width).
            CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(getActiveCamera());
            Rect arraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (arraySize == null) {
                Log.w(TAG, "Unable to load the active array size");
                return;
            }
            resize(metering, arraySize.width(), arraySize.height());

            // Now we can update our request
            mActiveSession.setMeteringRectangle(new MeteringRectangle(metering, MeteringRectangle.METERING_WEIGHT_MAX));
            if (!mIsPaused) {
                mActiveSession.onInvalidate(mCameraDevice, mCaptureSession);
            }
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            // Crashes if the Camera is interacted with while still loading
            Log.e(TAG, "Failed to focus", e);
        }
    }

    /**
     * Resizes a Rect from its original dimensions of -1000 to 1000 to 0 to width/height.
     */
    private static void resize(Rect metering, int maxWidth, int maxHeight) {
        // We can calculate the new width by scaling it to its new dimensions
        int newWidth = metering.width() * maxWidth / 2000;
        int newHeight = metering.height() * maxHeight / 2000;

        // Then we calculate how far from the top/left corner it should be
        int leftOffset = (metering.left + 1000) * maxWidth / 2000;
        int topOffset = (metering.top + 1000) * maxHeight / 2000;

        // And now we can resize the Rect to its new dimensions
        metering.left = leftOffset;
        metering.top = topOffset;
        metering.right = metering.left + newWidth;
        metering.bottom = metering.top + newHeight;
    }

    @Override
    public void setZoomLevel(int zoomLevel) {
        mZoomLevel = zoomLevel;

        if (mCameraDevice == null || mCaptureSession == null || mActiveSession == null) {
            Log.w(TAG, "No active session available");
            return;
        }

        try {
            CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(getActiveCamera());
            Rect m = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (m == null) {
                Log.w(TAG, "Zoom not supported");
                return;
            }

            int maxZoom = getMaxZoomLevel();

            int minW = m.width() / maxZoom;
            int minH = m.height() / maxZoom;
            int difW = m.width() - minW;
            int difH = m.height() - minH;
            int cropW = difW * zoomLevel / maxZoom;
            int cropH = difH * zoomLevel / maxZoom;
            Rect cropRegion = new Rect(cropW, cropH, m.width() - cropW, m.height() - cropH);

            if (cropRegion.left > cropRegion.right || cropRegion.top > cropRegion.bottom) {
                Log.w(TAG, "Crop Region has inverted, ignoring further zoom levels");
                return;
            }
            mActiveSession.setCropRegion(cropRegion);
            if (!mIsPaused) {
                mActiveSession.onInvalidate(mCameraDevice, mCaptureSession);
            }
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            // Crashes if the Camera is interacted with while still loading
            Log.e(TAG, "Failed to zoom", e);
        }
    }

    @Override
    public int getZoomLevel() {
        return mZoomLevel;
    }

    @Override
    public int getMaxZoomLevel() {
        try {
            CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(getActiveCamera());
            Float maxZoom = (characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM));
            if (maxZoom == null) {
                return ZOOM_NOT_SUPPORTED;
            }

            // We scale the max zoom (which is a float) by 10 so that we can use ints. However,
            // there's no need to do that if we can't even zoom.
            if (maxZoom == ZOOM_NOT_SUPPORTED) {
                return ZOOM_NOT_SUPPORTED;
            }

            return (int) (maxZoom * 10);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to query camera", e);
        }

        return ZOOM_NOT_SUPPORTED;
    }

    @Override
    public boolean hasFlash() {
        try {
            CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(getActiveCamera());
            Boolean hasFlash = (characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));
            if (hasFlash == null) {
                return false;
            }
            return hasFlash;
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to query camera", e);
        }

        return false;
    }

    @Override
    public boolean isZoomSupported() {
        return getMaxZoomLevel() != ZOOM_NOT_SUPPORTED;
    }

    @Override
    public void pause() {
        if (mCaptureSession == null) {
            if (DEBUG) Log.w(TAG, "Cannot pause. No capture session.");
            return;
        }

        if (!mIsPaused) {
            try {
                mCaptureSession.stopRepeating();
            } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
                Log.e(TAG, "Failed to pause the camera", e);
            }
            mIsPaused = true;
        } else {
            if (DEBUG) Log.w(TAG, "Cannot pause. Was never unpaused.");
        }
    }

    @Override
    public void resume() {
        try {
            if (mCaptureSession == null) {
                if (DEBUG) Log.w(TAG, "Cannot pause. No capture session.");
                return;
            }

            if (mActiveSession == null) {
                if (DEBUG) Log.w(TAG, "Cannot pause. No active session.");
                return;
            }

            if (mCameraDevice == null) {
                if (DEBUG) Log.w(TAG, "Cannot pause. No camera device.");
                return;
            }

            if (mIsPaused) {
                try {
                    mActiveSession.onAvailable(mCameraDevice, mCaptureSession);
                } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
                    Log.e(TAG, "Failed to resume the camera", e);
                }
            } else {
                if (DEBUG) {
                    Log.w(TAG, "Cannot resume. Was never paused.");
                }
            }
        } finally {
            mIsPaused = false;
        }
    }

    @Override
    public boolean isPaused() {
        return mIsPaused;
    }

    @Override
    public void setQuality(CameraView.Quality quality) {
        super.setQuality(quality);

        // When quality changes, we need to update our session with the new dimensions
        if (mActiveSession != null) {
            if (mActiveSession instanceof PictureSession) {
                setSession(new PictureSession(this));
            } else if (mActiveSession instanceof VideoSession) {
                setSession(new VideoSession(this, ((VideoSession) mActiveSession).getFile()));
            } else if (mActiveSession instanceof StreamSession) {
                // Ignored. Streams cannot be restarted so we'll update quality once the stream ends.
            }
        }
    }

    @Override
    public void takePicture(File file) {
        if (mCaptureSession == null) {
            if (DEBUG) Log.w(TAG, "Cannot take picture. No capture session.");
            return;
        }

        if (mActiveSession == null) {
            if (DEBUG) Log.w(TAG, "Cannot take picture. No active session.");
            return;
        }

        if (mCameraDevice == null) {
            if (DEBUG) Log.w(TAG, "Cannot take picture. No camera device.");
            return;
        }

        if (mActiveSession instanceof PictureSession) {
            PictureSession pictureSession = (PictureSession) mActiveSession;
            pictureSession.takePicture(file, mCameraDevice, mCaptureSession);
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @Override
    public void startRecording(File file) {
        // Quick fail if the CameraDevice was never created.
        if (mCameraDevice == null) {
            onVideoFailed();
            return;
        }

        mIsRecording = true;
        setSession(new VideoSession(this, file));
    }

    @Override
    public void stopRecording() {
        mIsRecording = false;
        setSession(new PictureSession(this));
    }

    @Override
    public boolean isRecording() {
        return mIsRecording;
    }

    @Override
    public void showVideoConfirmation(File file) {
        super.showVideoConfirmation(file);
        mIsRecording = false;
    }

    @Override
    public void onVideoFailed() {
        super.onVideoFailed();
        mIsRecording = false;
    }

    @Override
    protected void attachSurface(VideoRecorder.SurfaceProvider surfaceProvider) {
        setSession(new StreamSession(this, surfaceProvider));
    }

    @Override
    protected void detachSurface(VideoRecorder.SurfaceProvider surfaceProvider) {
        setSession(new PictureSession(this));
    }

    void transformPreview(int previewWidth, int previewHeight) throws CameraAccessException {
        int viewWidth = getWidth();
        int viewHeight = getHeight();
        int displayOrientation = getDisplayRotation();
        int cameraOrientation = getSensorOrientation(getActiveCamera());

        // Camera2 rotates the preview to always face in portrait mode, even if the phone is
        // currently in landscape. This is great for portrait mode, because there's less work to be done.
        // It's less great for landscape, because we have to undo it. Without any matrix modifications,
        // the preview will be smushed into the aspect ratio of the view.
        Matrix matrix = new Matrix();

        // Camera2 reverses the preview width/height.
        if (cameraOrientation != 0 && cameraOrientation != 180) {
            int temp = previewWidth;
            previewWidth = previewHeight;
            previewHeight = temp;
        }

        // We want to find the aspect ratio of the preview. Our goal is to stretch the image in
        // our SurfaceView to match this ratio, so that the image doesn't looked smushed.
        // This means the edges of the preview will be cut off.
        float aspectRatio = (float) previewHeight / (float) previewWidth;
        int newWidth, newHeight;
        if (viewHeight > viewWidth * aspectRatio) {
            newWidth = (int) Math.ceil(viewHeight / aspectRatio);
            newHeight = viewHeight;
        } else {
            newWidth = viewWidth;
            newHeight = (int) Math.ceil(viewWidth * aspectRatio);
        }

        // For portrait, we've already been mostly stretched. For landscape, our image is rotated 90 degrees.
        // Think of it as a sideways squished photo. We want to first streeeetch the height of the photo
        // until it matches the aspect ratio we originally expected. Now we're no longer stretched
        // (although we're wildly off screen, with only the far left sliver of the photo still
        // visible on the screen, and our picture is still sideways).
        float scaleX = (float) newWidth / (float) viewWidth;
        float scaleY = (float) newHeight / (float) viewHeight;

        // However, we've actually stretched too much. The height of the picture is currently the
        // width of our screen. When we rotate the picture, it'll be too large and we'll end up
        // cropping a lot of the picture. That's what this step is for. We scale down the image so
        // that the height of the photo (currently the width of the phone) becomes the height we
        // want (the height of the phone, or slightly bigger, depending on aspect ratio).
        float scale = 1f;
        if (displayOrientation == 90 || displayOrientation == 270) {
            boolean cropHeight = viewWidth > newHeight * viewHeight / newWidth;
            if (cropHeight) {
                // If we're cropping the top/bottom, then we want the widths to be exact
                scale = (float) viewWidth / newHeight;
            } else {
                // If we're cropping the left/right, then we want the heights to be exact
                scale = (float) viewHeight / newWidth;
            }
            newWidth = (int) Math.ceil(newWidth * scale);
            newHeight = (int) Math.ceil(newHeight * scale);
            scaleX *= scale;
            scaleY *= scale;
        }

        // Because we scaled the preview beyond the bounds of the view, we need to crop some of it.
        // By translating the photo over, we'll move it into the center.
        int translateX = (int) Math.ceil((viewWidth - newWidth) / 2d);
        int translateY = (int) Math.ceil((viewHeight - newHeight) / 2d);

        // Due to the direction of rotation (90 vs 270), a 1 pixel offset can either put us
        // exactly where we want to be, or it can put us 1px lower than we wanted. This is error
        // correction for that.
        if (displayOrientation == 270) {
            translateX = (int) Math.floor((viewWidth - newWidth) / 2d);
            translateY = (int) Math.floor((viewHeight - newHeight) / 2d);
        }

        // Finally, with our photo scaled and centered, we apply a rotation.
        int rotation = -displayOrientation;

        matrix.setScale(scaleX, scaleY);
        matrix.postTranslate(translateX, translateY);
        matrix.postRotate(rotation, (int) Math.ceil(viewWidth / 2d), (int) Math.ceil(viewHeight / 2d));

        if (DEBUG) {
            Log.d(TAG, String.format("transformPreview: displayOrientation=%s, cameraOrientation=%s, "
                            + "viewWidth=%s, viewHeight=%s, viewAspectRatio=%s, previewWidth=%s, previewHeight=%s, previewAspectRatio=%s, "
                            + "newWidth=%s, newHeight=%s, scaleX=%s, scaleY=%s, scale=%s, "
                            + "translateX=%s, translateY=%s, rotation=%s",
                    displayOrientation, cameraOrientation, viewWidth, viewHeight,
                    ((float) viewHeight / (float) viewWidth), previewWidth, previewHeight, aspectRatio,
                    newWidth, newHeight, scaleX, scaleY, scale, translateX, translateY, rotation));
        }

        setTransform(matrix);
    }

    @Override
    protected int getRelativeCameraOrientation() {
        try {
            return getRelativeImageOrientation(getDisplayRotation(), getSensorOrientation(getActiveCamera()), isUsingFrontFacingCamera(), false);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to query camera", e);
            return 0;
        }
    }

    @NonNull
    String getActiveCamera() throws CameraAccessException {
        return mActiveCamera == null ? getDefaultCamera() : mActiveCamera;
    }

    private String getDefaultCamera() throws CameraAccessException {
        for (String cameraId : mCameraManager.getCameraIdList()) {
            if (isBackFacing(cameraId)) {
                return cameraId;
            }
        }
        return mCameraManager.getCameraIdList()[0];
    }

    private boolean supportsFocus(String cameraId) throws CameraAccessException {
        CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(cameraId);
        Integer maxRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
        return maxRegions != null && maxRegions >= 1;
    }

    private boolean isFrontFacing(String cameraId) throws CameraAccessException {
        CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(cameraId);
        Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
        return facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
    }

    private boolean isBackFacing(String cameraId) throws CameraAccessException {
        CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(cameraId);
        Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
        return facing != null && facing == CameraCharacteristics.LENS_FACING_BACK;
    }

    private int getSensorOrientation(String cameraId) throws CameraAccessException {
        CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(cameraId);
        Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        return orientation == null ? 0 : orientation;
    }

    protected int getSensorOrientation() {
        try {
            return getSensorOrientation(getActiveCamera());
        } catch (CameraAccessException e) {
            return 0;
        }
    }

    Handler getHandler() {
        return mHandler;
    }

    @Override
    public Parcelable onSaveInstanceState() {
        Bundle state = new Bundle();
        state.putString(EXTRA_DEVICE_ID, mActiveCamera);
        return state;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        mActiveCamera = ((Bundle) state).getString(EXTRA_DEVICE_ID);
    }

    @Override
    public void onLayoutChanged() {
        if (mActiveSession != null) {
            try {
                mActiveSession.onLayoutChanged();
            } catch (CameraAccessException e) {
                Log.e(TAG, "Failed to adjust the camera after a layout change", e);
            }
        }
    }

    /**
     * A session has multiple surfaces for the camera to draw to.
     */
    interface Session {
        void initialize(@NonNull StreamConfigurationMap map) throws CameraAccessException;
        void onLayoutChanged() throws CameraAccessException;
        @NonNull List<Surface> getSurfaces();
        void setMeteringRectangle(@Nullable MeteringRectangle meteringRectangle);
        @Nullable MeteringRectangle getMeteringRectangle();
        void setCropRegion(@Nullable Rect region);
        @Nullable Rect getCropRegion();
        void onAvailable(@NonNull CameraDevice cameraDevice, @NonNull CameraCaptureSession session) throws CameraAccessException;
        void onInvalidate(@NonNull CameraDevice cameraDevice, @NonNull CameraCaptureSession session) throws CameraAccessException;
        void close();
    }
}
