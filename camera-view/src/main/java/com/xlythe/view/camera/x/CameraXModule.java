package com.xlythe.view.camera.x;

import android.Manifest;
import android.annotation.TargetApi;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.util.Log;
import android.util.Rational;
import android.util.Size;
import android.view.Surface;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.ICameraModule;

import java.io.File;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;
import androidx.annotation.RestrictTo;
import androidx.camera.core.CameraInfoUnavailableException;
import androidx.camera.core.CameraX;
import androidx.camera.core.FlashMode;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureConfig;
import androidx.camera.core.Preview;
import androidx.camera.core.PreviewConfig;
import androidx.camera.core.VideoCapture;
import androidx.camera.core.VideoCaptureConfig;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

/**
 * A wrapper around the CameraX APIs.
 */
@TargetApi(21)
@RestrictTo(RestrictTo.Scope.LIBRARY)
public class CameraXModule extends ICameraModule implements LifecycleOwner {

    /**
     * The CameraView we're attached to.
     */
    private final CameraView mView;

    /**
     * This is the id of the camera (eg. front or back facing) that we're currently using.
     */
    @Nullable
    private String mActiveCamera;

    /** The preview that draws to the texture surface. Non-null while open. */
    @Nullable private Preview mPreview;

    /** A video capture that captures what's visible on the screen. Non-null while taking a video. */
    @Nullable private VideoCapture mVideoCapture;

    /**
     * True if using the front facing camera. False otherwise.
     */
    private boolean mIsFrontFacing = false;

    /**
     * The current zoom level, from 0 to {@link #getMaxZoomLevel()}.
     */
    private int mZoomLevel;

    /**
     * True if the preview is currently paused.
     */
    private boolean mIsPaused = false;

    public CameraXModule(CameraView cameraView) {
        super(cameraView);
        mView = cameraView;
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return ((LifecycleOwner) getContext()).getLifecycle();
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    @Override
    public void open() {
        mPreview = new Preview(new PreviewConfig.Builder()
                .setTargetAspectRatio(getTargetAspectRatio())
                .setTargetResolution(getTargetResolution())
                .setTargetRotation(getTargetRotation())
                .setLensFacing(getLensFacing())
                .build());
        mPreview.setOnPreviewOutputUpdateListener(output -> {
            mView.asTextureView().setSurfaceTexture(output.getSurfaceTexture());
            Log.d(TAG, "Width="+output.getTextureSize().getWidth());
            Log.d(TAG, "Height="+output.getTextureSize().getHeight());
            Log.d(TAG, "RotationDegrees="+output.getRotationDegrees());
            transformPreview(output.getTextureSize().getWidth(), output.getTextureSize().getHeight(), output.getRotationDegrees());
        });
        CameraX.bindToLifecycle(this, mPreview);
    }

    @Override
    public void close() {
        if (mPreview == null) {
            return;
        }

        CameraX.unbind(mPreview);
        mPreview = null;
    }

    @Override
    public boolean hasFrontFacingCamera() {
        try {
            return CameraX.getCameraWithLensFacing(CameraX.LensFacing.FRONT) != null;
        } catch (CameraInfoUnavailableException e) {
            Log.e(TAG, "Failed to query for front facing camera", e);
            return false;
        }
    }

    @Override
    public boolean isUsingFrontFacingCamera() {
        return mIsFrontFacing;
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    @Override
    public void toggleCamera() {
        mIsFrontFacing = !mIsFrontFacing;
        try {
            mActiveCamera = CameraX.getCameraWithLensFacing(getLensFacing());
        } catch (CameraInfoUnavailableException e) {
            Log.e(TAG, "Failed to query for camera", e);
        }
        close();
        open();
    }

    @Override
    public void focus(Rect focus, Rect metering) {
        if (mPreview == null) {
            return;
        }
        mPreview.focus(focus, metering);
    }

    @Override
    public void setZoomLevel(int zoomLevel) {
        mZoomLevel = zoomLevel;
        if (mPreview == null) {
            return;
        }

        int maxZoom = getMaxZoomLevel();

        int minW = getWidth() / maxZoom;
        int minH = getHeight() / maxZoom;
        int difW = getWidth() - minW;
        int difH = getHeight() - minH;
        int cropW = difW * zoomLevel / maxZoom;
        int cropH = difH * zoomLevel / maxZoom;
        Rect cropRegion = new Rect(cropW, cropH, getWidth() - cropW, getHeight() - cropH);
        mPreview.zoom(cropRegion);
    }

    @Override
    public int getZoomLevel() {
        return mZoomLevel;
    }

    @Override
    public int getMaxZoomLevel() {
        return 10;
    }

    @Override
    public boolean isZoomSupported() {
        return true;
    }

    @Override
    public boolean hasFlash() {
        return true;
    }

    @NonNull
    private String getActiveCamera() {
        return mActiveCamera == null ? getDefaultCamera() : mActiveCamera;
    }

    private String getDefaultCamera() {
        try {
            return CameraX.getCameraWithLensFacing(CameraX.LensFacing.BACK);
        } catch (CameraInfoUnavailableException e) {
            Log.e(TAG, "Failed to get active camera");
            return "";
        }
    }

    private int getSensorOrientation(String cameraId) {
        try {
            return CameraX.getCameraInfo(cameraId).getSensorRotationDegrees();
        } catch (IllegalArgumentException | CameraInfoUnavailableException e) {
            Log.e(TAG, "Failed to get sensor orientation state from camera " + cameraId);
            return 0;
        }
    }

    @Override
    public void pause() {
        close();
        mIsPaused = true;
    }

    @Override
    public void resume() {
        open();
        mIsPaused = false;
    }

    @Override
    public boolean isPaused() {
        return mIsPaused;
    }

    @Override
    public void takePicture(File file) {
        ImageCapture imageCapture = new ImageCapture(new ImageCaptureConfig.Builder()
                .setTargetAspectRatio(getTargetAspectRatio())
                .setTargetRotation(getTargetRotation())
                .setLensFacing(getLensFacing())
                .setFlashMode(getFlashMode())
                .setCaptureMode(getCaptureMode())
                .build());
        try {
            CameraX.bindToLifecycle(this, imageCapture);
        } catch (IllegalArgumentException e) {
            onImageFailed();
            return;
        }

        imageCapture.takePicture(file, new ImageCapture.OnImageSavedListener() {
            @Override
            public void onImageSaved(@NonNull File file) {
                showImageConfirmation(file);
                CameraX.unbind(imageCapture);
            }

            @Override
            public void onError(@NonNull ImageCapture.UseCaseError useCaseError, @NonNull String message, @Nullable Throwable cause) {
                onImageFailed();
                CameraX.unbind(imageCapture);
            }
        });
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @Override
    public void startRecording(File file) {
        VideoCapture videoCapture = new VideoCapture(new VideoCaptureConfig.Builder()
                .setTargetAspectRatio(getTargetAspectRatio())
                .setTargetRotation(getTargetRotation())
                .setLensFacing(getLensFacing())
                .build());
        try {
            CameraX.bindToLifecycle(this, videoCapture);
        } catch (IllegalArgumentException e) {
            onVideoFailed();
            return;
        }

        videoCapture.startRecording(file, new VideoCapture.OnVideoSavedListener() {
            @Override
            public void onVideoSaved(File file) {
                showVideoConfirmation(file);
                stopRecording();
            }

            @Override
            public void onError(VideoCapture.UseCaseError useCaseError, String message, @Nullable Throwable cause) {
                onVideoFailed();
                stopRecording();
            }
        });
        mVideoCapture = videoCapture;
    }

    @Override
    public void stopRecording() {
        if (mVideoCapture == null) {
            return;
        }
        mVideoCapture.stopRecording();
        CameraX.unbind(mVideoCapture);
        mVideoCapture = null;
    }

    @Override
    public boolean isRecording() {
        return mVideoCapture != null;
    }

    @Override
    protected int getRelativeCameraOrientation() {
        return getRelativeImageOrientation(getDisplayRotation(), getSensorOrientation(getActiveCamera()), isUsingFrontFacingCamera(), false);
    }

    private Rational getTargetAspectRatio() {
        return new Rational(getWidth(), getHeight());
    }

    private Size getTargetResolution() {
        return new Size(getWidth(), getHeight());
    }

    private int getTargetRotation() {
        switch (getDisplayRotation()) {
            case 0:
                return Surface.ROTATION_0;
            case 90:
                return Surface.ROTATION_90;
            case 180:
                return Surface.ROTATION_180;
            case 270:
                return Surface.ROTATION_270;
        }
        return Surface.ROTATION_0;
    }

    private CameraX.LensFacing getLensFacing() {
        return mIsFrontFacing ? CameraX.LensFacing.FRONT : CameraX.LensFacing.BACK;
    }

    private FlashMode getFlashMode() {
        switch (getFlash()) {
            case ON:
                return FlashMode.ON;
            case OFF:
                return FlashMode.OFF;
            case AUTO:
                return FlashMode.AUTO;
        }
        return FlashMode.AUTO;
    }

    private ImageCapture.CaptureMode getCaptureMode() {
        switch (getQuality()) {
            case MAX:
                return ImageCapture.CaptureMode.MAX_QUALITY;
            default:
                return ImageCapture.CaptureMode.MIN_LATENCY;
        }
    }

    private void transformPreview(int previewWidth, int previewHeight, int rotation) {
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
        rotation = -displayOrientation;

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
}
