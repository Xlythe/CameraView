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
import android.os.HandlerThread;
import android.os.Parcelable;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresPermission;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.ICameraModule;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * A wrapper around the Camera2 APIs. Camera2 has some peculiarities, such as crashing if you attach
 * too many surfaces (or too large a surface) to a capture session. To get around that, we define
 * {@link Session}s that list out compatible surfaces and creates capture requests for them.
 */
@TargetApi(21)
public class Camera2Module extends ICameraModule {
    private static final int ZOOM_NOT_SUPPORTED = 1;

    private static final String EXTRA_DEVICE_ID = "device_id";

    // TODO Figure out why camera crashes when we use a size higher than 1080
    static final Size MAX_SUPPORTED_SIZE = new Size(1920, 1080);

    /**
     * This is how we'll talk to the camera.
     */
    private final CameraManager mCameraManager;

    /**
     * This is the id of the camera (eg. front or back facing) that we're currently using.
     */
    private String mActiveCamera;

    /**
     * The current capture session. There is one capture session per {@link Session}.
     */
    private CameraCaptureSession mCaptureSession;

    /**
     * The currently active camera. This may be a front facing camera or a back facing one.
     */
    private CameraDevice mCameraDevice;

    /**
     * A background thread to receive callbacks from the camera on.
     */
    private HandlerThread mBackgroundThread;

    /**
     * A handler pointing to the background thread.
     */
    private Handler mBackgroundHandler;

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
     * If true, the preview should be paused.
     */
    private boolean mIsPaused = false;

    /**
     * Callbacks for when the camera is available / unavailable
     */
    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            // The camera has opened. Start the preview now.
            synchronized (Camera2Module.this) {
                mCameraDevice = cameraDevice;
                setSession(new PictureSession(Camera2Module.this));
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            Log.w(TAG, "Camera disconnected");
            synchronized (Camera2Module.this) {
                cameraDevice.close();
                mCameraDevice = null;
                mBackgroundHandler.removeCallbacksAndMessages(null);
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
    }

    private synchronized void setSession(final Session session) {
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
                mBackgroundHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        setSession(session);
                    }
                });
                return;
            }

            // Assume this is a brand new session that's never been set up. Initialize it so that
            // it can decide what size to set its surfaces to.
            CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(mActiveCamera);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            session.initialize(map);

            // Now, with all of our surfaces, we'll ask for a new session
            mCameraDevice.createCaptureSession(session.getSurfaces(), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    synchronized (Camera2Module.this) {
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
                        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException | NullPointerException e) {
                            Log.e(TAG, "Failed to start session", e);
                        }
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    Log.e(TAG, "Configure failed");
                }
            }, mBackgroundHandler);
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException | NullPointerException e) {
            // Crashes if the Camera is interacted with while still loading
            Log.e(TAG, "Failed to create capture session", e);
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    @Override
    public void open() {
        startBackgroundThread();

        try {
            mActiveCamera = getActiveCamera();
            if (DEBUG) Log.d(TAG, "Opening camera " + mActiveCamera);
            mCameraManager.openCamera(mActiveCamera, mStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to open camera", e);
        }
    }

    @Override
    public void close() {
        if (mCaptureSession != null) {
            mCaptureSession.close();
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
        stopBackgroundThread();
    }

    @Override
    public boolean hasFrontFacingCamera() {
        try {
            for (String cameraId : mCameraManager.getCameraIdList()) {
                boolean frontFacing = isFrontFacing(cameraId);
                if (frontFacing) return true;
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
                if (cameraId.equals(mActiveCamera)) {
                    break;
                }
                position++;
            }
            close();
            mActiveCamera = mCameraManager.getCameraIdList()[(position + 1) % mCameraManager.getCameraIdList().length];
            open();
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to query camera", e);
        }
    }

    @Override
    public void focus(Rect focus, Rect metering) {
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
            mActiveSession.onInvalidate(mCameraDevice, mCaptureSession);
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException | NullPointerException e) {
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

        if (mActiveSession == null) {
            Log.w(TAG, "No active session available");
            return;
        }

        try {
            CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(mActiveCamera);
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
            mActiveSession.onInvalidate(mCameraDevice, mCaptureSession);
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException | NullPointerException e) {
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
            CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(mActiveCamera);
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
            CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(mActiveCamera);
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
        if (supportsPause() && !mIsPaused) {
            try {
                mCaptureSession.stopRepeating();
            } catch (CameraAccessException e) {
                Log.e(TAG, "Failed to pause the camera", e);
            }
            mIsPaused = true;
        }
    }

    @Override
    public void resume() {
        if (supportsPause() && mIsPaused) {
            try {
                mActiveSession.onAvailable(mCameraDevice, mCaptureSession);
            } catch (CameraAccessException e) {
                Log.e(TAG, "Failed to pause the camera", e);
            }
            mIsPaused = false;
        }
    }

    @Override
    public boolean supportsPause() {
        return mCaptureSession != null && mActiveSession != null;
    }

    @Override
    public void takePicture(File file) {
        if (mActiveSession != null && mActiveSession instanceof PictureSession) {
            PictureSession pictureSession = (PictureSession) mActiveSession;
            pictureSession.takePicture(file, mCameraDevice, mCaptureSession);
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @Override
    public void startRecording(File file) {
        // Quick fail if the CameraDevice was never created.
        if (mCameraDevice == null) {
            CameraView.OnVideoCapturedListener l = getOnVideoCapturedListener();
            if (l != null) {
                l.onFailure();
            }
            return;
        }

        setSession(new VideoSession(this, file));
    }

    @Override
    public void stopRecording() {
        setSession(new PictureSession(this));
    }

    @Override
    public boolean isRecording() {
        return mActiveSession != null && mActiveSession instanceof VideoSession;
    }

    void transformPreview(int previewWidth, int previewHeight) throws CameraAccessException {
        int viewWidth = getWidth();
        int viewHeight = getHeight();
        int displayOrientation = getDisplayRotation();
        int cameraOrientation = getSensorOrientation(getActiveCamera());
        if (DEBUG) {
            Log.d(TAG, String.format("Configuring SurfaceView matrix: "
                            + "viewWidth=%s, viewHeight=%s, previewWidth=%s, previewHeight=%s, displayOrientation=%s, cameraOrientation=%s",
                    viewWidth, viewHeight, previewWidth, previewHeight, displayOrientation, cameraOrientation));
        }

        // Camera2 rotates the preview to always face in portrait mode, even if the phone is
        // currently in landscape. This is great for portrait mode, because there's less work to be done.
        // It's less great for landscape, because we have to undo it. Without any matrix modifications,
        // the preview will be smushed into the aspect ratio of the view.
        Matrix matrix = new Matrix();
        getTransform(matrix);

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
            newWidth = (int) (viewHeight / aspectRatio);
            newHeight = viewHeight;
        } else {
            newWidth = viewWidth;
            newHeight = (int) (viewWidth * aspectRatio);
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
        int translateX = (int) (viewWidth - newWidth) / 2;
        int translateY = (int) (viewHeight - newHeight) / 2;

        // Finally, with our photo scaled and centered, we apply a rotation.
        int rotation = -displayOrientation;

        matrix.setScale(scaleX, scaleY);
        matrix.postTranslate(translateX, translateY);
        matrix.postRotate(rotation, viewWidth / 2, viewHeight / 2);

        if (DEBUG) {
            Log.d(TAG, String.format("Result: viewAspectRatio=%s, previewAspectRatio=%s, "
                            + "newWidth=%s, newHeight=%s, scaleX=%s, scaleY=%s, scale=%s, "
                            + "translateX=%s, translateY=%s, rotation=%s",
                    ((float) viewHeight / (float) viewWidth), aspectRatio, newWidth, newHeight,
                    scaleX, scaleY, scale, translateX, translateY, rotation));
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

    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (mBackgroundThread == null) {
            return;
        }
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            Log.e(TAG, "Failed to join background thread", e);
            Thread.currentThread().interrupt();
        }
    }

    Handler getBackgroundHandler() {
        return mBackgroundHandler;
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

    /**
     * A session has multiple surfaces for the camera to draw to.
     */
    interface Session {
        void initialize(@NonNull StreamConfigurationMap map) throws CameraAccessException;
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
