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
import androidx.annotation.NonNull;

import com.xlythe.view.camera.stream.VideoRecorder;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.List;
import java.util.concurrent.TimeUnit;

public abstract class ICameraModule {
    public static final String TAG = CameraView.TAG;
    public static final boolean DEBUG = CameraView.DEBUG;
    /** Maximum wait for a backend to detach a custom encoder surface on the main thread. */
    private static final long SURFACE_DETACH_TIMEOUT_SECONDS = 2;

    /** Owning CameraView used for view state and callbacks. */
    private final CameraView mView;
    /** Requested image and video quality. */
    private CameraView.Quality mQuality = CameraView.Quality.HIGH;

    /** JPEG compression for still captures. See {@link #setPictureQuality}. */
    private int mPictureQuality = DEFAULT_PICTURE_QUALITY;
    /** Requested maximum video duration in milliseconds. */
    private long mMaxVideoDuration = CameraView.INDEFINITE_VIDEO_DURATION;
    /** Requested maximum video size in bytes. */
    private long mMaxVideoSize = CameraView.INDEFINITE_VIDEO_SIZE;
    /** Requested still-image flash behavior. */
    private CameraView.Flash mFlash = CameraView.Flash.AUTO;
    /** Requested capture dynamic range. */
    private CameraView.DynamicRange mDynamicRange = CameraView.DynamicRange.SDR;
    /** Requested CameraX photo extension. */
    private CameraView.Extension mExtension = CameraView.Extension.NONE;
    /** Whether front-camera video output should be mirrored. */
    private boolean mFrontVideoMirroringEnabled = true;
    /** Whether preview stabilization is requested. */
    private boolean mPreviewStabilizationEnabled;
    /** Whether video stabilization is requested. */
    private boolean mVideoStabilizationEnabled;
    /** Requested exposure compensation index. */
    private int mExposureCompensation;
    /** Whether continuous torch illumination is requested. */
    private boolean mTorchEnabled;
    /** Whether CameraX low-light boost is requested. */
    private boolean mLowLightBoostEnabled;
    /** Listener receiving still-image terminal callbacks. */
    private CameraView.OnImageCapturedListener mOnImageCapturedListener;
    /** Listener receiving video terminal callbacks. */
    private CameraView.OnVideoCapturedListener mOnVideoCapturedListener;

    /** Whether pictures and videos should match the preview aspect ratio. */
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

    protected boolean startBarcodeScanner(
            CameraView.BarcodeDetectorListener listener,
            @Barcode.Format int format,
            @Barcode.Format int... formats) {
        return false;
    }

    protected void stopBarcodeScanner() {}
    @RequiresApi(18)
    public VideoRecorder.Canvas getCanvas() {
        return new VideoRecorder.Canvas() {
            @Override
            public void attachSurface(VideoRecorder.SurfaceProvider surfaceProvider) {
                new Handler(Looper.getMainLooper()).post(() -> ICameraModule.this.attachSurface(surfaceProvider));
            }

            @Override
            public void detachSurface(VideoRecorder.SurfaceProvider surfaceProvider) {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    ICameraModule.this.detachSurface(surfaceProvider);
                    return;
                }
                CountDownLatch detached = new CountDownLatch(1);
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        ICameraModule.this.detachSurface(surfaceProvider);
                    } finally {
                        detached.countDown();
                    }
                });
                try {
                    if (!detached.await(SURFACE_DETACH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        Log.w(TAG, "Timed out detaching custom camera surface");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    Log.w(TAG, "Interrupted while detaching custom camera surface", error);
                }
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
     * phone has PackageManager.FEATURE_CAMERA_ANY and, if you are targeting Android M+, that the
     * phone has granted Manifest.permission.CAMERA. The microphone belongs to
     * startRecording(File), not here.
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

    protected void onCameraError(Throwable error) {
        mView.onCameraError(error);
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

    /** The JPEG quality every backend is asked for unless the app says otherwise. */
    public static final int DEFAULT_PICTURE_QUALITY = 95;

    /**
     * How hard a still capture is compressed, from 1 to 100.
     *
     * <p>Left alone, every backend takes whatever the device chose, which is not the same number
     * on any two phones and on some is low enough to show. 95 is where a JPEG stops being
     * visibly different from the uncompressed frame while still being a fraction of the size of
     * one encoded at 100, and it is what the manufacturers' own apps settle around.</p>
     */
    public void setPictureQuality(int percent) {
        mPictureQuality = Math.max(1, Math.min(percent, 100));
    }

    public int getPictureQuality() {
        return mPictureQuality;
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

    public synchronized void setDynamicRange(CameraView.DynamicRange dynamicRange) {
        if (dynamicRange == null) throw new NullPointerException("dynamicRange cannot be null");
        mDynamicRange = dynamicRange;
    }

    public synchronized CameraView.DynamicRange getDynamicRange() {
        return mDynamicRange;
    }

    public synchronized boolean isDynamicRangeSupported(CameraView.DynamicRange dynamicRange) {
        return dynamicRange == CameraView.DynamicRange.SDR;
    }

    public synchronized void setExtension(CameraView.Extension extension) {
        if (extension == null) throw new NullPointerException("extension cannot be null");
        mExtension = extension;
    }

    public synchronized CameraView.Extension getExtension() {
        return mExtension;
    }

    public synchronized boolean isExtensionSupported(CameraView.Extension extension) {
        return extension == CameraView.Extension.NONE;
    }
    public synchronized void setFrontVideoMirroringEnabled(boolean enabled) {
        mFrontVideoMirroringEnabled = enabled;
    }

    public synchronized boolean isFrontVideoMirroringEnabled() {
        return mFrontVideoMirroringEnabled;
    }

    public synchronized void setPreviewStabilizationEnabled(boolean enabled) {
        mPreviewStabilizationEnabled = enabled;
    }

    public synchronized boolean isPreviewStabilizationEnabled() {
        return mPreviewStabilizationEnabled;
    }

    public synchronized boolean isPreviewStabilizationSupported() {
        return false;
    }

    public synchronized void setVideoStabilizationEnabled(boolean enabled) {
        mVideoStabilizationEnabled = enabled;
    }

    public synchronized boolean isVideoStabilizationEnabled() {
        return mVideoStabilizationEnabled;
    }

    public synchronized boolean isVideoStabilizationSupported() {
        return false;
    }

    public synchronized void setExposureCompensation(int exposureCompensation) {
        mExposureCompensation = exposureCompensation;
    }

    public synchronized int getExposureCompensation() {
        return mExposureCompensation;
    }

    public synchronized int getMinExposureCompensation() {
        return 0;
    }

    public synchronized int getMaxExposureCompensation() {
        return 0;
    }

    public synchronized boolean isExposureCompensationSupported() {
        return false;
    }

    public synchronized void setTorchEnabled(boolean enabled) {
        mTorchEnabled = enabled;
    }

    public synchronized boolean isTorchEnabled() {
        return mTorchEnabled;
    }

    public synchronized void setLowLightBoostEnabled(boolean enabled) {
        mLowLightBoostEnabled = enabled;
    }

    public synchronized boolean isLowLightBoostEnabled() {
        return mLowLightBoostEnabled;
    }

    public synchronized boolean isLowLightBoostSupported() {
        return false;
    }

    /** The colour range pictures are recorded in. See {@link CameraView.ColorSpace}. */
    private CameraView.ColorSpace mColorSpace = CameraView.ColorSpace.SRGB;

    /**
     * Whether this camera can record in a given colour range.
     *
     * <p>sRGB is always supported, because it is what a camera that has never heard of colour
     * spaces produces.</p>
     */
    public boolean isColorSpaceSupported(CameraView.ColorSpace colorSpace) {
        return colorSpace == CameraView.ColorSpace.SRGB;
    }

    public void setColorSpace(CameraView.ColorSpace colorSpace) {
        if (!isColorSpaceSupported(colorSpace)) {
            Log.w(TAG, "This camera cannot record in " + colorSpace);
            return;
        }
        mColorSpace = colorSpace;
    }

    public CameraView.ColorSpace getColorSpace() {
        return mColorSpace;
    }

    /**
     * Whether a picture can be taken while a recording is running.
     *
     * <p>Not every camera can hold a preview, a recording and a still at once; a LEGACY camera2
     * device guarantees nothing of the sort. Where it cannot, recording keeps working and the
     * picture is refused rather than the whole session being lost.</p>
     */
    public boolean isSnapshotWhileRecordingSupported() {
        return false;
    }

    /**
     * Whether the camera is configured and can be used right now.
     *
     * <p>A backend that opens synchronously is ready as soon as open() returns and says so here.
     * One that opens a device and configures a session - which is both of the modern ones - is
     * not, and reports through {@link #onCameraReady()} when it is. The difference matters
     * because it is what CameraView tells an app to enable its shutter button on.</p>
     */
    public boolean isCameraReady() {
        return true;
    }

    /** Tells the view the camera can be used, which promotes it to open. */
    protected final void onCameraReady() {
        getView().onCameraReady();
    }

    /** Whether this camera can report the faces it is already tracking. */
    public boolean isFaceDetectionSupported() {
        return false;
    }

    /**
     * Asks the backend to start or stop reporting faces.
     *
     * @return whether the backend is now in the requested state. A backend with no face detection
     *     refuses to start, which CameraView reports to the caller rather than leaving a listener
     *     that never fires; stopping always succeeds.
     */
    protected boolean setFaceDetectionEnabled(boolean enabled) {
        return !enabled;
    }

    /**
     * Reports the faces the camera is tracking.
     *
     * @param faceToView maps a face's bounds onto the view, since the camera's coordinates are
     *     neither the view's nor the same across backends
     */
    protected final void onFacesDetected(
            @NonNull List<Face> faces, @NonNull Matrix faceToView) {
        // Backends read faces on whichever thread the camera delivers results on, and the
        // listener is documented as arriving on the main one. Posting through a Handler rather
        // than View.post matters: a detached View queues its runnables until it is attached
        // again, which for a camera that is closing means never.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            getView().onFacesDetected(faces, faceToView);
            return;
        }
        new Handler(Looper.getMainLooper()).post(
                () -> getView().onFacesDetected(faces, faceToView));
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
