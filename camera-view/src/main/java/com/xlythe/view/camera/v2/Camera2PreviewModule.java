package com.xlythe.view.camera.v2;

import android.Manifest;
import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresPermission;
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
class Camera2PreviewModule extends ICameraModule {

    enum State {
        PREVIEW,
        RECORDING,
        STREAMING,
        PICTURE_TAKEN,
    }

    private final Context mContext;
    private final CameraManager mCameraManager;
    private String mActiveCamera;
    private CameraCaptureSession mCaptureSession;
    private CameraDevice mCameraDevice;
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;
    private State mState = State.PREVIEW;
    private PreviewSurface mPreviewSurface = new PreviewSurface(this);

    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            // The camera has opened. Start the preview now.
            mCameraDevice = cameraDevice;

            try {
                // We want to define all our surfaces (preview, picture, video, etc) in our capture session
                List<Surface> surfaces = new ArrayList<>();
                for (CameraSurface surface : getCameraSurfaces()) {
                    surfaces.add(surface.getSurface());
                }

                // Clean up any legacy sessions we forgot about
                if (mCaptureSession != null) {
                    mCaptureSession.close();
                    mCaptureSession = null;
                }

                // Now, with all of our surfaces, we'll ask for a new session
                mCameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                        // When the session is ready, we'll display the preview.
                        mCaptureSession = cameraCaptureSession;
                        startPreview();
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                        Log.e(TAG, "Configure failed");
                    }
                }, mBackgroundHandler);
            } catch (CameraAccessException | IllegalStateException | NullPointerException e) {
                // Crashes if the Camera is interacted with while still loading
                e.printStackTrace();
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            Log.w(TAG, "Camera disconnected");
            cameraDevice.close();
            mCameraDevice = null;
            mBackgroundHandler.removeCallbacksAndMessages(null);
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            Log.e(TAG, "Camera crashed: " + error);
            cameraDevice.close();
            mCameraDevice = null;
            mBackgroundHandler.removeCallbacksAndMessages(null);
        }
    };

    Camera2PreviewModule(CameraView view) {
        super(view);
        mContext = view.getContext();
        mCameraManager = (CameraManager) view.getContext().getSystemService(Context.CAMERA_SERVICE);
    }

    private Context getContext() {
        return mContext;
    }

    synchronized void setState(State state) {
        Log.d(TAG, "Setting State: " + state.name());
        mState = state;
    }

    synchronized State getState() {
        return mState;
    }

    @RequiresPermission(Manifest.permission.CAMERA)
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

            transformPreview(getWidth(), getHeight(), mPreviewSurface.getWidth(), mPreviewSurface.getHeight(), getDisplayRotation(), getSensorOrientation(getActiveCamera()));

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

    protected void transformPreview(int viewWidth, int viewHeight, int previewWidth, int previewHeight, int displayOrientation, int cameraOrientation) {
        if (DEBUG) {
            Log.d(TAG, String.format("Configuring SurfaceView matrix: "
                            + "viewWidth=%s, viewHeight=%s, previewWidth=%s, previewHeight=%s, displayOrientation=%s, cameraOrientation=%s",
                    viewWidth, viewHeight, previewWidth, previewHeight, displayOrientation, cameraOrientation));
        }

        Matrix matrix = new Matrix();
        getTransform(matrix);

        // Camera2 tries to be smart, and will rotate the display automatically to portrait mode.
        // It, unfortunately, forgets that phones may also be held sideways.
        // We'll reverse the preview width/height if the camera did end up being rotated.
        if ((displayOrientation == 90 || displayOrientation == 270)
                && (cameraOrientation != 0 && cameraOrientation != 180)) {
            int temp = previewWidth;
            previewWidth = previewHeight;
            previewHeight = temp;
        }

        double aspectRatio = (double) previewHeight / (double) previewWidth;
        int newWidth, newHeight;
        if (getHeight() > viewWidth * aspectRatio) {
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
            Log.d(TAG, String.format("Result: scaleX=%s, scaleY=%s, translateX=%s, translateY=%s, rotation=%s",
                    scaleX, scaleY, translateX, translateY, rotation));
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

    CameraDevice getCameraDevice() {
        return mCameraDevice;
    }

    CameraCaptureSession getCaptureSession() {
        return mCaptureSession;
    }

    CameraSurface getPreviewSurface() {
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
        try {
            if (getState() != State.PREVIEW) {
                Log.w(TAG, "Focus is only available in preview mode");
                return;
            }

            if (!supportsFocus(getActiveCamera())) {
                Log.w(TAG, "Focus not available on this camera");
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
            CaptureRequest.Builder previewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            previewBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[] { new MeteringRectangle(metering, MeteringRectangle.METERING_WEIGHT_MAX) });
            previewBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[] { new MeteringRectangle(metering, MeteringRectangle.METERING_WEIGHT_MAX) });
            previewBuilder.addTarget(mPreviewSurface.getSurface());
            mCaptureSession.setRepeatingRequest(previewBuilder.build(), null /* callback */, mBackgroundHandler);
        } catch (CameraAccessException e) {
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

    void startPreview() {
        Log.v(TAG, "startPreview");
        try {
            setState(State.PREVIEW);
            CaptureRequest.Builder previewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            previewBuilder.addTarget(mPreviewSurface.getSurface());
            mCaptureSession.setRepeatingRequest(previewBuilder.build(), null /* callback */, mBackgroundHandler);
        } catch (CameraAccessException | IllegalStateException | NullPointerException e) {
            // Crashes if the Camera is interacted with while still loading
            e.printStackTrace();
        }
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

    static abstract class CameraSurface {
        final Camera2PreviewModule mCameraView;

        Size mSize;
        private final Context mContext;

        CameraSurface(Camera2PreviewModule cameraView) {
            mCameraView = cameraView;
            mContext = cameraView.getContext();
        }

        Context getContext() {
            return mContext;
        }

        CameraView.Quality getQuality() {
            return mCameraView.getQuality();
        }

        abstract void initialize(StreamConfigurationMap map);

        void initialize(Size size) {
            if (DEBUG) {
                Log.d(TAG, String.format("Initializing %s with width=%s and height=%s", getClass().getSimpleName(), size.getWidth(), size.getHeight()));
            }
            mSize = size;
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
}
