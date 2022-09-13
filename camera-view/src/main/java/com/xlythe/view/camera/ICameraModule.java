package com.xlythe.view.camera;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.xlythe.view.camera.stream.VideoRecorder;

import java.io.File;

public abstract class ICameraModule {
    public static final String TAG = CameraView.TAG;
    public static final boolean DEBUG = CameraView.DEBUG;

    private final CameraView mView;
    private CameraView.Quality mQuality = CameraView.Quality.HIGH;
    private long mMaxVideoDuration = CameraView.INDEFINITE_VIDEO_DURATION;
    private long mMaxVideoSize = CameraView.INDEFINITE_VIDEO_SIZE;
    private CameraView.Flash mFlash = CameraView.Flash.AUTO;
    private CameraView.OnImageCapturedListener mOnImageCapturedListener;
    private CameraView.OnVideoCapturedListener mOnVideoCapturedListener;

    // When true, pictures and videos will try to match the aspect ratio of the preview
    private boolean mMatchPreviewAspectRatio = true;

    public ICameraModule(CameraView view) {
        mView = view;
    }

    public Context getContext() {
        return mView.getContext();
    }

    public CameraView getView() {
        return mView;
    }

    public int getWidth() {
        return mView.getWidth();
    }

    public int getHeight() {
        return mView.getHeight();
    }

    protected void attachSurface(VideoRecorder.SurfaceProvider surfaceProvider) {

    }

    protected void detachSurface(VideoRecorder.SurfaceProvider surfaceProvider) {

    }

    @RequiresApi(18)
    public VideoRecorder.Canvas getCanvas() {
        return new VideoRecorder.Canvas() {
            @Override
            public void attachSurface(VideoRecorder.SurfaceProvider surfaceProvider) {
                new Handler(Looper.getMainLooper()).post(() -> ICameraModule.this.attachSurface(surfaceProvider));
            }

            @Override
            public void detachSurface(VideoRecorder.SurfaceProvider surfaceProvider) {
                new Handler(Looper.getMainLooper()).post(() -> ICameraModule.this.detachSurface(surfaceProvider));
            }
        };
    }

    public int getDisplayRotation() {
        return mView.getDisplayRotation();
    }

    public SurfaceTexture getSurfaceTexture() {
        return mView.getSurfaceTexture();
    }

    protected Matrix getTransform(Matrix matrix) {
        return mView.getTransform(matrix);
    }

    protected void setTransform(Matrix matrix) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mView.post(() -> setTransform(matrix));
        } else {
            mView.setTransform(matrix);
        }
    }

    /*
     * Opens the camera and starts displaying a preview. You are in charge of checking if the
     * phone has PackageManager.FEATURE_CAMERA_ANY and, if you are targeting Android M+, that
     * the phone has the following permissions:
     *       Manifest.permission.CAMERA
     *       Manifest.permission.RECORD_AUDIO
     *       Manifest.permission.WRITE_EXTERNAL_STORAGE
     */
    public abstract void open();

    /*
     * Closes the camera.
     */
    public abstract void close();

    /**
     * Takes a picture. Set a OnImageCapturedListener to be
     * notified of when the picture has finished saving.
     */
    public abstract void takePicture(File file);

    /**
     * Informs the CameraView to pause and show the taken photo
     */
    public void showImageConfirmation(File file) {
        mView.showImageConfirmation(file);
    }

    public void onImageFailed() {
        mView.onImageFailed();
    }

    /**
     * Records a video. Set a OnVideoCapturedListener to be notified of when
     * the video has finished saving.
     */
    public abstract void startRecording(File file);

    /**
     * Stops recording the video. It's recommended that you set a timeout when recording to avoid
     * excessively large files.
     */
    public abstract void stopRecording();

    /**
     * Returns true if recording.
     */
    public abstract boolean isRecording();

    /**
     * Informs the CameraView to pause and show the taken video
     */
    public void showVideoConfirmation(File file) {
        mView.showVideoConfirmation(file);
    }

    public void onVideoFailed() {
        mView.onVideoFailed();
    }

    public abstract void setLensFacing(CameraView.LensFacing lensFacing);

    public abstract void toggleCamera();

    public abstract boolean hasFrontFacingCamera();

    public abstract boolean isUsingFrontFacingCamera();

    public abstract void focus(Rect focus, Rect metering);

    public abstract void setZoomLevel(int zoomLevel);

    public abstract int getZoomLevel();

    public abstract int getMaxZoomLevel();

    public abstract boolean isZoomSupported();

    protected abstract int getRelativeCameraOrientation();

    public void setQuality(CameraView.Quality quality) {
        mQuality = quality;
    }

    public CameraView.Quality getQuality() {
        return mQuality;
    }

    public void setMaxVideoDuration(long duration) {
        mMaxVideoDuration = duration;
    }

    public long getMaxVideoDuration() {
        return mMaxVideoDuration;
    }

    public void setMaxVideoSize(long size) {
        mMaxVideoSize = size;
    }

    public long getMaxVideoSize() {
        return mMaxVideoSize;
    }

    public void setFlash(CameraView.Flash flash) {
        mFlash = flash;
    }

    public CameraView.Flash getFlash() {
        return mFlash;
    }

    public boolean hasFlash() {
        return false;
    }

    public void setMatchPreviewAspectRatio(boolean enabled) {
        mMatchPreviewAspectRatio = enabled;
    }

    public boolean isMatchPreviewAspectRatioEnabled() {
        return mMatchPreviewAspectRatio;
    }

    public void pause() {}

    public void resume() {}

    public boolean isPaused() {
        return false;
    }

    public Parcelable onSaveInstanceState() {
        return null;
    }

    public void onRestoreInstanceState(Parcelable savedState) {}

    public void onLayoutChanged() {}

    public void setOnImageCapturedListener(CameraView.OnImageCapturedListener l) {
        mOnImageCapturedListener = l;
    }

    public CameraView.OnImageCapturedListener getOnImageCapturedListener() {
        return mOnImageCapturedListener;
    }

    public void setOnVideoCapturedListener(CameraView.OnVideoCapturedListener l) {
        mOnVideoCapturedListener = l;
    }

    public CameraView.OnVideoCapturedListener getOnVideoCapturedListener() {
        return mOnVideoCapturedListener;
    }

    public static int getRelativeImageOrientation(int displayRotation, int sensorOrientation, boolean isFrontFacing, boolean compensateForMirroring) {
        int result;
        if (isFrontFacing) {
            result = (sensorOrientation + displayRotation) % 360;
            if (compensateForMirroring) {
                result = (360 - result) % 360;
            }
        } else {
            result = (sensorOrientation - displayRotation + 360) % 360;
        }
        if (DEBUG) {
            Log.d(TAG, String.format("getRelativeImageOrientation: displayRotation=%s, "
                            + "sensorOrientation=%s, isFrontFacing=%s, compensateForMirroring=%s, "
                            + "result=%s",
                    displayRotation, sensorOrientation, isFrontFacing, compensateForMirroring, result));
        }
        return result;
    }
}
