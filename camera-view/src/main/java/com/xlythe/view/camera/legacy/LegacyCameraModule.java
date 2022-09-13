package com.xlythe.view.camera.legacy;

import android.Manifest;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.location.Location;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.ICameraModule;
import com.xlythe.view.camera.LocationProvider;
import com.xlythe.view.camera.PermissionChecker;
import com.xlythe.view.camera.stream.VideoRecorder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;
import androidx.collection.ArrayMap;

@RestrictTo(RestrictTo.Scope.LIBRARY)
public class LegacyCameraModule extends ICameraModule {
    private static final int INVALID_CAMERA_ID = -1;

    private static final long STALE_LOCATION_MILLIS = 2 * 60 * 60 * 1000;
    private static final long GPS_TIMEOUT_MILLIS = 10;

    private int mActiveCamera = INVALID_CAMERA_ID;
    private Camera mCamera;
    @Nullable private Camera.Size mPreviewSize;

    private MediaRecorder mVideoRecorder;
    private File mVideoFile;

    private final Map<VideoRecorder.SurfaceProvider, LegacySurfaceHolder> mSurfaceProviders = new ArrayMap<>();

    public LegacyCameraModule(CameraView view) {
        super(view);
    }

    @Override
    public void open() {
        Log.d(TAG, "onOpen() activeCamera="+getActiveCamera());
        try {
            mCamera = Camera.open(getActiveCamera());
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to open camera", e);
            return;
        }

        try {
            mCamera.setPreviewTexture(getSurfaceTexture());

            Camera.Parameters parameters = mCamera.getParameters();
            int cameraOrientation = getRelativeCameraOrientation();
            mCamera.setDisplayOrientation(cameraOrientation);
            mPreviewSize = chooseOptimalPreviewSize(mCamera.getParameters().getSupportedPreviewSizes(), getWidth(), getHeight());
            parameters.setPreviewSize(getPreviewWidth(), getPreviewHeight());
            parameters.setPictureFormat(ImageFormat.JPEG);
            mCamera.setParameters(parameters);
            transformPreview(getWidth(), getHeight(), getPreviewWidth(), getPreviewHeight(), cameraOrientation);

            mCamera.startPreview();
        } catch (IOException e) {
            Log.e(TAG, "Failed to properly initialize camera", e);
        }
    }

    @Override
    public void close() {
        Log.d(TAG, "onClose() activeCamera="+getActiveCamera());
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
            mPreviewSize = null;
        }
    }

    @Override
    public void onLayoutChanged() {
        if (mPreviewSize == null) {
            return;
        }

        int cameraOrientation = getRelativeCameraOrientation();
        transformPreview(getWidth(), getHeight(), getPreviewWidth(), getPreviewHeight(), cameraOrientation);
    }

    private void transformPreview(int viewWidth, int viewHeight, int previewWidth, int previewHeight, int cameraOrientation) {
        if (DEBUG) {
            Log.d(TAG, String.format("Configuring SurfaceView matrix: "
                            + "viewWidth=%s, viewHeight=%s, previewWidth=%s, previewHeight=%s, cameraOrientation=%s",
                    viewWidth, viewHeight, previewWidth, previewHeight, cameraOrientation));
        }

        Matrix matrix = new Matrix();

        // Because the camera already rotates the preview for us (@see Camera.setDisplayOrientation(int)},
        // we need to flip the width/height to the dimensions we'll actually be given.
        if (cameraOrientation == 90 || cameraOrientation == 270) {
            int temp = previewWidth;
            previewWidth = previewHeight;
            previewHeight = temp;
        }

        // We want to maintain aspect ratio, but we also want both sides to be >= the view's width and height.
        // Otherwise, there will be blank space around our preview.
        double aspectRatio = (double) previewHeight / (double) previewWidth;
        int newWidth, newHeight;
        if (getHeight() > viewWidth * aspectRatio) {
            newWidth = (int) (viewHeight / aspectRatio);
            newHeight = viewHeight;
        } else {
            newWidth = viewWidth;
            newHeight = (int) (viewWidth * aspectRatio);
        }

        // We scale the image up so that it definitely fits (or overflows) our bounds
        matrix.setScale((float) newWidth / (float) viewWidth, (float) newHeight / (float) viewHeight);

        // And then we reposition it so that it's centered
        matrix.postTranslate((viewWidth - newWidth) / 2f, (viewHeight - newHeight) / 2f);

        // And once we're done, we apply our changes.
        setTransform(matrix);
    }

    @Override
    public void takePicture(File file) {
        mCamera.takePicture(null, null, new LegacyPictureListener(
                file, getRelativeCameraOrientation(false /* isPreview */), isUsingFrontFacingCamera(), this));
    }

    @Override
    public void startRecording(File file) {
        mVideoFile = file;
        mVideoRecorder = new MediaRecorder();

        mCamera.unlock();
        mVideoRecorder.setCamera(mCamera);

        mVideoRecorder.setAudioSource(MediaRecorder.AudioSource.DEFAULT);
        mVideoRecorder.setVideoSource(MediaRecorder.VideoSource.DEFAULT);
        switch (getQuality()) {
            case MAX:
                if (CamcorderProfile.hasProfile(mActiveCamera, CamcorderProfile.QUALITY_HIGH)) {
                    mVideoRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH));
                    break;
                }
                // Fall-through
            case HIGH:
                if (CamcorderProfile.hasProfile(mActiveCamera, CamcorderProfile.QUALITY_1080P)) {
                    mVideoRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_1080P));
                    break;
                }
                // Fall-through
            case MEDIUM:
                if (CamcorderProfile.hasProfile(mActiveCamera, CamcorderProfile.QUALITY_720P)) {
                    mVideoRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_720P));
                    break;
                }
                // Fall-through
            case LOW:
                if (CamcorderProfile.hasProfile(mActiveCamera, CamcorderProfile.QUALITY_480P)) {
                    mVideoRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_480P));
                    break;
                }
                // Fall-through
            default:
                mVideoRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_LOW));
                break;
        }
        mVideoRecorder.setOutputFile(file.getAbsolutePath());
        mVideoRecorder.setMaxDuration((int) getMaxVideoDuration());
        mVideoRecorder.setMaxFileSize(getMaxVideoSize());
        mVideoRecorder.setMaxDuration((int) getMaxVideoDuration());
        mVideoRecorder.setMaxFileSize(getMaxVideoSize());
        mVideoRecorder.setOrientationHint(getRelativeCameraOrientation(false /* isPreview */));
        mVideoRecorder.setOnInfoListener((mr, what, extra) -> {
            switch (what) {
                case MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED:
                    Log.w(TAG, "Max duration for recording reached");
                    break;
                case MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED:
                    Log.w(TAG, "Max filesize for recording reached");
                    break;
            }
        });

        Location location = getLocation(getContext());
        if (location != null) {
            mVideoRecorder.setLocation((float) location.getLatitude(), (float) location.getLongitude());
        }

        try {
            mVideoRecorder.prepare();
            mVideoRecorder.start();
        } catch (IOException | RuntimeException e) {
            e.printStackTrace();
            mVideoRecorder = null;
            onVideoFailed();
        }
    }

    @Override
    public void stopRecording() {
        if (mVideoRecorder != null) {
            try {
                mVideoRecorder.stop();
                showVideoConfirmation(mVideoFile);
            } catch (RuntimeException e) {
                Log.e(TAG, "Failed to stop video recorder. This can happen if a video is stopped too quickly. :(", e);
                onVideoFailed();
            }
            mVideoRecorder = null;
        } else {
            onVideoFailed();
        }
    }

    @Override
    public boolean isRecording() {
        return mVideoRecorder != null;
    }

    @Override
    public void focus(Rect focus, Rect metering) {
        if (DEBUG) {
            Log.d(TAG, String.format("Focus: focus=%s, metering=%s", focus, metering));
        }
        if (mCamera != null) {
            mCamera.cancelAutoFocus();

            Camera.Parameters parameters = mCamera.getParameters();
            if (!parameters.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                Log.w(TAG, "Focus not available on this camera");
                return;
            }

            parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);

            if (parameters.getMaxNumFocusAreas() > 0) {
                parameters.setFocusAreas(Collections.singletonList(new Camera.Area(focus, 1000)));
            }

            if (parameters.getMaxNumMeteringAreas() > 0) {
                parameters.setMeteringAreas(Collections.singletonList(new Camera.Area(metering, 1000)));
            }

            mCamera.setParameters(parameters);
            mCamera.autoFocus((success, camera) -> {
                if (DEBUG) {
                    Log.d(TAG, "AutoFocus: " + success);
                }
            });
        } else {
            Log.w(TAG, "Attempted to set focus but no camera found");
        }
    }

    @Override
    public void setZoomLevel(int zoomLevel) {
        if (mCamera != null) {
            Camera.Parameters parameters = mCamera.getParameters();
            parameters.setZoom(zoomLevel);
            mCamera.setParameters(parameters);
        } else {
            Log.w(TAG, "Attempted to set zoom level to " + zoomLevel + " but no camera found");
        }
    }

    @Override
    public int getZoomLevel() {
        return mCamera.getParameters().getZoom();
    }

    @Override
    public int getMaxZoomLevel() {
        return mCamera.getParameters().getMaxZoom();
    }

    @Override
    public boolean isZoomSupported() {
        return mCamera.getParameters().isZoomSupported();
    }

    @Override
    public void toggleCamera() {
        boolean shouldOpen = getView().isOpen();
        close();
        mActiveCamera = (mActiveCamera + 1) % Camera.getNumberOfCameras();
        if (shouldOpen) {
            open();
        }
    }

    @Override
    public void setLensFacing(CameraView.LensFacing lensFacing) {
        boolean shouldOpen = getView().isOpen();
        close();

        // Search for the properly facing camera
        int numberOfCameras = Camera.getNumberOfCameras();
        for (int i = 0; i < numberOfCameras; i++) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(i, info);
            if ((info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT && lensFacing.equals(CameraView.LensFacing.FRONT))
                    || (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK && lensFacing.equals(CameraView.LensFacing.BACK))) {
                mActiveCamera = i;
                break;
            }
        }

        if (shouldOpen) {
            open();
        }
    }

    @Override
    public boolean hasFrontFacingCamera() {
        // Search for the front facing camera
        int numberOfCameras = Camera.getNumberOfCameras();
        for (int i = 0; i < numberOfCameras; i++) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isUsingFrontFacingCamera() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(getActiveCamera(), info);
        return info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT;
    }

    @Override
    protected int getRelativeCameraOrientation() {
        return getRelativeCameraOrientation(true /* isPreview */);
    }

    @RequiresApi(18)
    @Override
    protected void attachSurface(VideoRecorder.SurfaceProvider surfaceProvider) {
        if (mCamera != null && mPreviewSize != null) {
            LegacySurfaceHolder surfaceHolder = new LegacySurfaceHolder(getContext(), surfaceProvider, mPreviewSize.width, mPreviewSize.height, getSensorOrientation());
            mSurfaceProviders.put(surfaceProvider, surfaceHolder);
            mCamera.setPreviewCallback((data, camera) -> {
                Canvas canvas = surfaceHolder.lockCanvas();
                canvas.drawBitmap(toBitmap(data, camera), new Matrix(), new Paint());
                surfaceHolder.unlockCanvasAndPost(canvas);
            });
        }
    }

    @RequiresApi(18)
    @Override
    protected void detachSurface(VideoRecorder.SurfaceProvider surfaceProvider) {
        LegacySurfaceHolder surfaceHolder = mSurfaceProviders.remove(surfaceProvider);
        if (surfaceHolder != null) {
            surfaceHolder.close();
            mCamera.setPreviewCallback(null);
        }
    }

    private Bitmap toBitmap(byte[] data, Camera camera) {
        Camera.Parameters parameters = camera.getParameters();
        int width = parameters.getPreviewSize().width;
        int height = parameters.getPreviewSize().height;

        YuvImage yuv = new YuvImage(data, parameters.getPreviewFormat(), width, height, null);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuv.compressToJpeg(new Rect(0, 0, width, height), 50, out);

        byte[] bytes = out.toByteArray();
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }

    private int getRelativeCameraOrientation(boolean isPreview) {
        return getRelativeImageOrientation(getDisplayRotation(), getSensorOrientation(), isUsingFrontFacingCamera(), isPreview);
    }

    private int getSensorOrientation() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(getActiveCamera(), info);
        return info.orientation;
    }

    private int getActiveCamera() {
        if (mActiveCamera != INVALID_CAMERA_ID) {
            return mActiveCamera;
        }

        int numberOfCameras = Camera.getNumberOfCameras();
        if (numberOfCameras == 0) {
            return INVALID_CAMERA_ID;
        }

        for (int i = 0; i < numberOfCameras; i++) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                mActiveCamera = i;
                return mActiveCamera;
            }
        }

        mActiveCamera = 0;
        return mActiveCamera;
    }

    private int getPreviewWidth() {
        if (mPreviewSize == null) {
            return 0;
        }

        return mPreviewSize.width;
    }

    private int getPreviewHeight() {
        if (mPreviewSize == null) {
            return 0;
        }

        return mPreviewSize.height;
    }

    private static Camera.Size chooseOptimalPreviewSize(List<Camera.Size> choices, int width, int height) {
        if (DEBUG) {
            Log.d(TAG, String.format("Initializing PreviewSurface with width=%s and height=%s", width, height));
        }
        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Camera.Size> bigEnough = new ArrayList<>();
        for (Camera.Size option : choices) {
            if (option.width >= width && option.height >= height) {
                bigEnough.add(option);
            }
        }

        // Pick the smallest of those, assuming we found any
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices.get(0);
        }
    }

    private static class CompareSizesByArea implements Comparator<Camera.Size> {
        @Override
        public int compare(Camera.Size lhs, Camera.Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.width * lhs.height -
                    (long) rhs.width * rhs.height);
        }
    }

    @SuppressWarnings({"MissingPermission"})
    @Nullable
    static Location getLocation(Context context) {
        if (PermissionChecker.hasPermissions(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
            // Our GPS timeout is purposefully low. We're not intending to wait until GPS is acquired
            // but we want a last known location for the next time a picture is taken.
            return LocationProvider.getGPSLocation(context, STALE_LOCATION_MILLIS, GPS_TIMEOUT_MILLIS);
        }
        return null;
    }
}
