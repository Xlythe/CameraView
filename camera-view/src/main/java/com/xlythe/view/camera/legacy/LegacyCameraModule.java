package com.xlythe.view.camera.legacy;

import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.util.Log;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.ICameraModule;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@SuppressWarnings("deprecation")
public class LegacyCameraModule extends ICameraModule {
    private static final int INVALID_CAMERA_ID = -1;

    private int mActiveCamera = INVALID_CAMERA_ID;
    private Camera mCamera;

    private MediaRecorder mVideoRecorder;
    private File mVideoFile;

    public LegacyCameraModule(CameraView view) {
        super(view);
    }

    @Override
    public void open() {
        Log.d(TAG, "onOpen() activeCamera="+getActiveCamera());
        mCamera = Camera.open(getActiveCamera());

        try {
            mCamera.setPreviewTexture(getSurfaceTexture());

            Camera.Parameters parameters = mCamera.getParameters();
            int cameraOrientation = getRelativeCameraOrientation();
            mCamera.setDisplayOrientation(cameraOrientation);
            Camera.Size previewSize = chooseOptimalPreviewSize(mCamera.getParameters().getSupportedPreviewSizes(), getWidth(), getHeight());
            parameters.setPreviewSize(previewSize.width, previewSize.height);
            parameters.setPictureFormat(ImageFormat.JPEG);
            mCamera.setParameters(parameters);
            transformPreview(getWidth(), getHeight(), previewSize.width, previewSize.height, cameraOrientation);

            mCamera.startPreview();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void close() {
        Log.d(TAG, "onClose() activeCamera="+getActiveCamera());
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }
    }

    private void transformPreview(int viewWidth, int viewHeight, int previewWidth, int previewHeight, int cameraOrientation) {
        if (DEBUG) {
            Log.d(TAG, String.format("Configuring SurfaceView matrix: "
                            + "viewWidth=%s, viewHeight=%s, previewWidth=%s, previewHeight=%s, cameraOrientation=%s",
                    viewWidth, viewHeight, previewWidth, previewHeight, cameraOrientation));
        }

        Matrix matrix = new Matrix();
        getTransform(matrix);

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
        matrix.postTranslate((viewWidth - newWidth) / 2, (viewHeight - newHeight) / 2);

        // And once we're done, we apply our changes.
        setTransform(matrix);
    }

    @Override
    public void takePicture(File file) {
        mCamera.takePicture(null, null, new LegacyPictureListener(file, getRelativeCameraOrientation(false /* isPreview */), getOnImageCapturedListener()));
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
            case HIGH:
                mVideoRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH));
                break;
            case MEDIUM:
                mVideoRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_720P));
                break;
            case LOW:
                mVideoRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_LOW));
                break;
        }
        mVideoRecorder.setOutputFile(file.getAbsolutePath());
        mVideoRecorder.setMaxDuration(getMaxVideoDuration());
        mVideoRecorder.setMaxFileSize(getMaxVideoSize());
        mVideoRecorder.setOrientationHint(getRelativeCameraOrientation(false /* isPreview */));
        mVideoRecorder.setOnInfoListener(new MediaRecorder.OnInfoListener() {
            @Override
            public void onInfo(MediaRecorder mr, int what, int extra) {
                switch (what) {
                    case MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED:
                        Log.w(TAG, "Max duration for recording reached");
                        break;
                    case MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED:
                        Log.w(TAG, "Max filesize for recording reached");
                        break;
                }
            }
        });

        try {
            mVideoRecorder.prepare();
            mVideoRecorder.start();
        } catch (IOException | RuntimeException e) {
            e.printStackTrace();
            mVideoRecorder = null;
        }
    }

    @Override
    public void stopRecording() {
        if (mVideoRecorder != null) {
            try {
                mVideoRecorder.stop();
            } catch (RuntimeException e) {
                Log.e(TAG, "Failed to stop video recorder. This can happen if a video is stopped too quickly. :(", e);
            }
            mVideoRecorder = null;
        }
        if (getOnVideoCapturedListener() != null) {
            getOnVideoCapturedListener().onVideoCaptured(mVideoFile);
        }
    }

    @Override
    public boolean isRecording() {
        return mVideoRecorder != null;
    }

    @Override
    public void toggleCamera() {
        close();
        mActiveCamera = (mActiveCamera + 1) % Camera.getNumberOfCameras();
        open();
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
            mCamera.autoFocus(new Camera.AutoFocusCallback() {
                @Override
                public void onAutoFocus(boolean success, Camera camera) {
                    if (DEBUG) {
                        Log.d(TAG, "AutoFocus: " + success);
                    }
                }
            });
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
}
