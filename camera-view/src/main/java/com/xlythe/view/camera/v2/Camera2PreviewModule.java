package com.xlythe.view.camera.v2;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.ICameraModule;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

@TargetApi(21)
public class Camera2PreviewModule extends ICameraModule {

    protected enum State {
        PREVIEW,
        RECORDING,
        WAITING_LOCK,
        WAITING_PRECAPTURE,
        WAITING_NON_PRECAPTURE,
        PICTURE_TAKEN,
        WAITING_UNLOCK;
    }

    private final CameraManager mCameraManager;
    private String mActiveCamera;
    private CameraCaptureSession mCaptureSession;
    private CameraDevice mCameraDevice;
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;
    private State mState = State.PREVIEW;
    private CaptureRequest mPreviewRequest;
    private PreviewSurface mPreviewSurface = new PreviewSurface(this);

    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            // The camera has opened. Start the preview now.
            mCameraDevice = cameraDevice;
            startPreview();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            Log.w(TAG, "Camera disconnected");
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            Log.e(TAG, "Camera crashed: " + error);
            cameraDevice.close();
            mCameraDevice = null;
        }
    };

    Camera2PreviewModule(CameraView view) {
        super(view);
        mCameraManager = (CameraManager) view.getContext().getSystemService(Context.CAMERA_SERVICE);
    }

    protected synchronized void setState(State state) {
        Log.d(TAG, "Setting State: " + state.name());
        mState = state;
    }

    protected synchronized State getState() {
        return mState;
    }

    @SuppressWarnings({"MissingPermission"})
    @Override
    public void open() {
        startBackgroundThread();

        try {
            mActiveCamera = getActiveCamera();
            if (mCameraDevice != null) {
                mCameraDevice.close();
                mCameraDevice = null;
            }

            CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(mActiveCamera);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            for (CameraSurface surface : getCameraSurfaces()) {
                surface.initialize(map);
            }

            int cameraOrientation = getRelativeCameraOrientation();
            configureTransform(getWidth(), getHeight(), mPreviewSurface.getWidth(), mPreviewSurface.getHeight(), cameraOrientation);

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
        if (mCameraDevice != null) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
        for (CameraSurface surface : getCameraSurfaces()) {
            surface.close();
        }

        stopBackgroundThread();
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

    protected String getActiveCamera() throws CameraAccessException {
        return mActiveCamera == null ? getDefaultCamera() : mActiveCamera;
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

    protected Handler getBackgroundHandler() {
        return mBackgroundHandler;
    }

    protected CameraDevice getCameraDevice() {
        return mCameraDevice;
    }

    protected CameraCaptureSession getCaptureSession() {
        return mCaptureSession;
    }

    protected CameraSurface getPreviewSurface() {
        return mPreviewSurface;
    }

    protected List<Camera2PreviewModule.CameraSurface> getCameraSurfaces() {
        LinkedList<Camera2PreviewModule.CameraSurface> list = new LinkedList<>();
        list.add(mPreviewSurface);
        return list;
    }

    @Override
    public void takePicture(File file) {
        throw new RuntimeException("Unsupported Operation");
    }

    @Override
    public void startRecording(File file) {
        throw new RuntimeException("Unsupported Operation");
    }

    @Override
    public void stopRecording() {
        throw new RuntimeException("Unsupported Operation");
    }

    @Override
    public boolean isRecording() {
        throw new RuntimeException("Unsupported Operation");
    }

    @Override
    public void focus(Rect focus, Rect metering) {
        // TODO
    }

    protected void startPreview() {
        try {
            SurfaceTexture texture = getSurfaceTexture();
            texture.setDefaultBufferSize(mPreviewSurface.getWidth(), mPreviewSurface.getHeight());

            final CaptureRequest.Builder previewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            List<Surface> surfaces = new ArrayList<>();

            // The preview is, well, the preview. This surface draws straight to the CameraView
            surfaces.add(mPreviewSurface.getSurface());
            previewBuilder.addTarget(mPreviewSurface.getSurface());
            previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            createCaptureSession(surfaces, previewBuilder.build());
        } catch (CameraAccessException | IllegalStateException e) {
            // Crashes if the Camera is interacted with while still loading
            e.printStackTrace();
        }
    }

    protected void createCaptureSession(
            final List<Surface> surfaces,
            final CaptureRequest request) throws CameraAccessException {
        createCaptureSession(surfaces, request, null);
    }

    protected void createCaptureSession(
            final List<Surface> surfaces,
            final CaptureRequest request,
            final Callback callback) throws CameraAccessException {
        createCaptureSession(surfaces, request, null, callback);
    }

    protected void createCaptureSession(
            final List<Surface> surfaces,
            final CaptureRequest request,
            final CameraCaptureSession.CaptureCallback captureCallback,
            final Callback callback) throws CameraAccessException {
        if (mCaptureSession != null) {
            mCaptureSession.close();
            mCaptureSession = null;
        }
        mCameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                // When the session is ready, we start displaying the preview.
                mCaptureSession = cameraCaptureSession;
                try {
                    mPreviewRequest = request;
                    mCaptureSession.setRepeatingRequest(mPreviewRequest, captureCallback, mBackgroundHandler);
                    if (callback != null) {
                        callback.onComplete(cameraCaptureSession);
                    }
                } catch (CameraAccessException | IllegalStateException e) {
                    // Crashes on rotation. However, it does restore itself.
                    e.printStackTrace();
                }
            }

            @Override
            public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                Log.e(TAG, "Configure failed");
            }
        }, mBackgroundHandler);
    }

    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    private String getDefaultCamera() throws CameraAccessException {
        for (String cameraId : mCameraManager.getCameraIdList()) {
            if (isBackFacing(cameraId)) {
                return cameraId;
            }
        }
        return mCameraManager.getCameraIdList()[0];
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
        return characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
    }

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

    protected static abstract class CameraSurface {
        final Camera2PreviewModule mCameraView;

        Size mSize;
        private boolean mInitialized = false;

        CameraSurface(Camera2PreviewModule cameraView) {
            mCameraView = cameraView;
        }

        abstract void initialize(StreamConfigurationMap map);

        void initialize(Size size) {
            mSize = size;
            mInitialized = true;
        }

        boolean isInitialized() {
            return mInitialized;
        }

        int getWidth() {
            return mSize.getWidth();
        }

        int getHeight() {
            return mSize.getHeight();
        }

        abstract void close();

        abstract Surface getSurface();
    }

    private static final class PreviewSurface extends CameraSurface {
        private static Size chooseOptimalSize(Size[] choices, int width, int height) {
            if (DEBUG) {
                Log.d(TAG, String.format("Initializing PreviewSurface with width=%s and height=%s", width, height));
            }
            // Collect the supported resolutions that are at least as big as the preview Surface
            List<Size> bigEnough = new ArrayList<>();
            for (Size option : choices) {
                if (option.getWidth() >= width && option.getHeight() >= height) {
                    bigEnough.add(option);
                }
            }

            // Pick the smallest of those, assuming we found any
            if (bigEnough.size() > 0) {
                return Collections.min(bigEnough, new CompareSizesByArea());
            } else {
                Log.e(TAG, "Couldn't find any suitable preview size");
                return choices[0];
            }
        }

        private Surface mSurface;

        PreviewSurface(Camera2PreviewModule cameraView) {
            super(cameraView);
        }

        void initialize(StreamConfigurationMap map) {
            super.initialize(chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), mCameraView.getWidth(), mCameraView.getHeight()));

            SurfaceTexture texture = mCameraView.getSurfaceTexture();
            texture.setDefaultBufferSize(getWidth(), getHeight());

            // This is the output Surface we need to start preview.
            mSurface = new Surface(texture);
        }

        @Override
        Surface getSurface() {
            return mSurface;
        }

        @Override
        void close() {

        }
    }

    public interface Callback {
        void onComplete(CameraCaptureSession cameraCaptureSession);
    }
}
