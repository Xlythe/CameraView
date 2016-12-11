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
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresPermission;
import android.util.Log;
import android.view.Surface;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.ICameraModule;

import java.io.File;
import java.util.List;

/**
 * A wrapper around the Camera2 APIs. Camera2 has some peculiarities, such as crashing if you attach
 * too many surfaces (or too large a surface) to a capture session. To get around that, we define
 * {@link Session}s that list out compatible surfaces and creates capture requests for them.
 */
@TargetApi(21)
public class Camera2Module extends ICameraModule {
    private static final int ZOOM_NOT_SUPPORTED = 1;

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
            Log.e(TAG, "Camera crashed: " + error);
            onDisconnected(cameraDevice);
        }
    };

    public Camera2Module(CameraView cameraView) {
        super(cameraView);
        mCameraManager = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
    }

    private synchronized void setSession(final Session session) {
        if (mCameraDevice == null) {
            return;
        }

        // Clean up any previous sessions
        if (mCaptureSession != null) {
            mCaptureSession.close();
            mCaptureSession = null;
        }
        if (mActiveSession != null) {
            // Restore state from the previous session
            session.setMeteringRectangle(mActiveSession.getMeteringRectangle());
            session.setCropRegion(mActiveSession.getCropRegion());

            mActiveSession.close();
            mActiveSession = null;
        }

        try {
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
                            session.onAvailable(mCameraDevice, mCaptureSession);
                        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException | NullPointerException e) {
                            e.printStackTrace();
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
            e.printStackTrace();
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    @Override
    public void open() {
        startBackgroundThread();

        try {
            mActiveCamera = getActiveCamera();
            mCameraManager.openCamera(mActiveCamera, mStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
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
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public boolean isUsingFrontFacingCamera() {
        try {
            return isFrontFacing(getActiveCamera());
        } catch (CameraAccessException e) {
            e.printStackTrace();
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
            e.printStackTrace();
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
        } catch (CameraAccessException | NullPointerException e) {
            // Crashes if the Camera is interacted with while still loading
            e.printStackTrace();
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

            int minW = (int) (m.width() / maxZoom);
            int minH = (int) (m.height() / maxZoom);
            int difW = m.width() - minW;
            int difH = m.height() - minH;
            int cropW = difW / 100 * zoomLevel;
            int cropH = difH / 100 * zoomLevel;
            cropW -= cropW & 3;
            cropH -= cropH & 3;
            Rect cropRegion = new Rect(cropW, cropH, m.width() - cropW, m.height() - cropH);
            mActiveSession.setCropRegion(cropRegion);
            mActiveSession.onInvalidate(mCameraDevice, mCaptureSession);
        } catch (CameraAccessException | NullPointerException e) {
            // Crashes if the Camera is interacted with while still loading
            e.printStackTrace();
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
            e.printStackTrace();
        }

        return ZOOM_NOT_SUPPORTED;
    }

    @Override
    public boolean isZoomSupported() {
        return getMaxZoomLevel() != ZOOM_NOT_SUPPORTED;
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

        Matrix matrix = new Matrix();
        getTransform(matrix);

        // Camera2 reverses the preview width/height. Why? No idea.
        if (cameraOrientation != 0 && cameraOrientation != 180) {
            int temp = previewWidth;
            previewWidth = previewHeight;
            previewHeight = temp;
        }

        double aspectRatio = (double) previewHeight / (double) previewWidth;
        int newWidth, newHeight;
        if (viewHeight > viewWidth * aspectRatio) {
            newWidth = (int) (viewHeight / aspectRatio);
            newHeight = viewHeight;
        } else {
            newWidth = viewWidth;
            newHeight = (int) (viewWidth * aspectRatio);
        }

        float scaleX = (float) newWidth / (float) viewWidth;
        float scaleY = (float) newHeight / (float) viewHeight;
        int translateX = (viewWidth - newWidth) / 2;
        int translateY = (viewHeight - newHeight) / 2;
        int rotation = -displayOrientation;

        matrix.setScale(scaleX, scaleY);
        matrix.postTranslate(translateX, translateY);
        matrix.postRotate(rotation, viewWidth / 2, viewHeight / 2);

        if (DEBUG) {
            Log.d(TAG, String.format("Result: viewAspectRatio=%s, previewAspectRatio=%s, scaleX=%s, scaleY=%s, translateX=%s, translateY=%s, rotation=%s",
                    ((double) viewHeight / (double) viewWidth), aspectRatio, scaleX, scaleY, translateX, translateY, rotation));
        }

        setTransform(matrix);
    }

    @Override
    protected int getRelativeCameraOrientation() {
        try {
            return getRelativeImageOrientation(getDisplayRotation(), getSensorOrientation(getActiveCamera()), isUsingFrontFacingCamera(), false);
        } catch (CameraAccessException e) {
            e.printStackTrace();
            return 0;
        }
    }

    private String getActiveCamera() throws CameraAccessException {
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
            e.printStackTrace();
        }
    }

    Handler getBackgroundHandler() {
        return mBackgroundHandler;
    }

    /**
     * A session has multiple surfaces for the camera to draw to.
     */
    interface Session {
        void initialize(StreamConfigurationMap map) throws CameraAccessException;
        List<Surface> getSurfaces();
        void setMeteringRectangle(MeteringRectangle meteringRectangle);
        MeteringRectangle getMeteringRectangle();
        void setCropRegion(Rect region);
        Rect getCropRegion();
        void onAvailable(CameraDevice cameraDevice, CameraCaptureSession session) throws CameraAccessException;
        void onInvalidate(CameraDevice cameraDevice, CameraCaptureSession session) throws CameraAccessException;
        void close();
    }
}
