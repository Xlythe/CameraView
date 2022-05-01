package com.xlythe.view.camera.x;

import android.Manifest;
import android.annotation.TargetApi;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import com.google.common.util.concurrent.ListenableFuture;
import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.ICameraModule;

import java.io.File;
import java.util.concurrent.ExecutionException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;
import androidx.annotation.RestrictTo;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfoUnavailableException;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.core.SurfaceOrientedMeteringPointFactory;
import androidx.camera.core.VideoCapture;
import androidx.camera.core.ZoomState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

/**
 * A wrapper around the CameraX APIs.
 */
@TargetApi(21)
@RestrictTo(RestrictTo.Scope.LIBRARY)
public class CameraXModule extends ICameraModule implements LifecycleOwner {
    /**
     * The library can crash and fail to return events (even #onError).
     * To work around that, we'll assume an error after this timeout.
     */
    private static final long LIB_TIMEOUT_MILLIS = 1000;

    /**
     * The CameraView we're attached to.
     */
    private final CameraView mView;

    /**
     * A future handle to the CameraX library. Until this is loaded, we cannot use CameraX.
     */
    private final ListenableFuture<ProcessCameraProvider> mCameraProviderFuture;

    /**
     * A handle to the CameraX library. You must wait until {@link #mCameraProviderFuture} has
     * loaded before this becomes non-null.
     */
    @Nullable
    private ProcessCameraProvider mCameraProvider;

    /**
     * True if the module has been opened. This is checked when async operations haven't finished
     * yet, as a way to release resources if we were closed before they loaded.
     */
    private boolean mIsOpen;

    /**
     * The currently active Camera. Null if the camera isn't open.
     */
    @Nullable private Camera mActiveCamera;

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
        mCameraProviderFuture = ProcessCameraProvider.getInstance(cameraView.getContext());
        mCameraProviderFuture.addListener(() -> {
            try {
                mCameraProvider = mCameraProviderFuture.get();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Unable to load camera", e);
            }
        }, ContextCompat.getMainExecutor(getContext()));
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return ((LifecycleOwner) getContext()).getLifecycle();
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    @Override
    public void open() {
        mIsOpen = true;
        loadPreview();
    }

    private void loadPreview() {
        if (!mIsOpen) {
            Log.v(TAG, "Ignoring call to load preview. The camera is no longer open.");
            return;
        }

        // We haven't loaded our CameraProvider yet. We'll delay until we are loaded, and try again.
        if (mCameraProvider == null) {
            mCameraProviderFuture.addListener(this::loadPreview, ContextCompat.getMainExecutor(getContext()));
            return;
        }

        mPreview = new Preview.Builder()
                .setTargetResolution(getTargetResolution())
                .setTargetRotation(getTargetRotation())
                .build();
        mPreview.setSurfaceProvider(request -> {
            SurfaceTexture surfaceTexture = mView.getSurfaceTexture();
            if (surfaceTexture == null) {
                // Can happen if there's a race condition, and the texture is closed before we're
                // asked to provide it.
                request.willNotProvideSurface();
                return;
            }

            Surface surface = new Surface(surfaceTexture);
            request.provideSurface(surface, ContextCompat.getMainExecutor(getContext()), result -> {
                // ignored
            });

            Log.d(TAG, "Width="+request.getResolution().getWidth());
            Log.d(TAG, "Height="+request.getResolution().getHeight());
            Log.d(TAG, "RotationDegrees="+0);
            transformPreview(request.getResolution().getWidth(), request.getResolution().getHeight(), 0);
        });
        mActiveCamera = mCameraProvider.bindToLifecycle(this, getCameraSelector(), mPreview);
    }

    @Override
    public void close() {
        mIsOpen = false;

        if (mCameraProvider == null) {
            return;
        }

        if (mPreview == null) {
            return;
        }

        mCameraProvider.unbind(mPreview);
        mPreview = null;
        mActiveCamera = null;
    }

    @Override
    public boolean hasFrontFacingCamera() {
        if (mCameraProvider == null) {
            return false;
        }

        try {
            return mCameraProvider.hasCamera(new CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_FRONT).build());
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
        close();
        open();
    }

    @Override
    public void focus(Rect focus, Rect metering) {
        if (mActiveCamera == null) {
            return;
        }

        SurfaceOrientedMeteringPointFactory factory = new SurfaceOrientedMeteringPointFactory(getWidth(), getHeight());
        mActiveCamera.getCameraControl().startFocusAndMetering(new FocusMeteringAction.Builder(factory.createPoint(focus.centerX(), focus.centerY())).build());
    }

    @Override
    public void setZoomLevel(int zoomLevel) {
        if (mActiveCamera == null) {
            return;
        }

        ZoomState zoomState = mActiveCamera.getCameraInfo().getZoomState().getValue();
        if (zoomState == null) {
            return;
        }

        float minRatio = zoomState.getMinZoomRatio();
        float maxRatio = zoomState.getMaxZoomRatio();
        float steps = (maxRatio - minRatio) / getMaxZoomLevel();

        // Note: Because we're dealing with floats, we'll use Math.min to ensure we don't
        // accidentally exceed the max with our multiplication.
        float zoom = Math.min(minRatio + steps * zoomLevel, maxRatio);
        mActiveCamera.getCameraControl().setZoomRatio(zoom);
        mZoomLevel = zoomLevel;
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
        if (mActiveCamera == null) {
            return false;
        }

        return mActiveCamera.getCameraInfo().hasFlashUnit();
    }

    private int getSensorOrientation() {
        if (mActiveCamera == null) {
            return 0;
        }

        return mActiveCamera.getCameraInfo().getSensorRotationDegrees();
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
        if (mCameraProvider == null) {
            onImageFailed();
            return;
        }

        ImageCapture imageCapture = new ImageCapture.Builder()
                .setTargetResolution(getTargetResolution())
                .setTargetRotation(getTargetRotation())
                .setFlashMode(getFlashMode())
                .setCaptureMode(getCaptureMode())
                .build();

        try {
            mCameraProvider.bindToLifecycle(this, getCameraSelector(), imageCapture);
        } catch (IllegalArgumentException e) {
            onImageFailed();
            return;
        }

        Handler cancellationHandler = new Handler();
        cancellationHandler.postDelayed(() -> {
            onImageFailed();
            mCameraProvider.unbind(imageCapture);
        }, LIB_TIMEOUT_MILLIS);

        imageCapture.takePicture(new ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(getContext()), new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                showImageConfirmation(file);
                mCameraProvider.unbind(imageCapture);
                cancellationHandler.removeCallbacksAndMessages(null);
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                onImageFailed();
                mCameraProvider.unbind(imageCapture);
                cancellationHandler.removeCallbacksAndMessages(null);
            }
        });
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @Override
    public void startRecording(File file) {
        if (mCameraProvider == null) {
            onVideoFailed();
            return;
        }

        VideoCapture videoCapture = new VideoCapture.Builder()
                .setTargetResolution(getTargetResolution())
                .setTargetRotation(getTargetRotation())
                .build();

        try {
            mCameraProvider.bindToLifecycle(this, getCameraSelector(), videoCapture);
        } catch (IllegalArgumentException e) {
            onVideoFailed();
            return;
        }

        videoCapture.startRecording(new VideoCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(getContext()), new VideoCapture.OnVideoSavedCallback() {
            @Override
            public void onVideoSaved(@NonNull VideoCapture.OutputFileResults outputFileResults) {
                showVideoConfirmation(file);
                stopRecording();
            }

            @Override
            public void onError(int videoCaptureError, @NonNull String message, @Nullable Throwable cause) {
                Log.e(TAG, "Failed to start video recording. Error[" + videoCaptureError + "] " + message, cause);
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
        mCameraProvider.unbind(mVideoCapture);
        mVideoCapture = null;
    }

    @Override
    public boolean isRecording() {
        return mVideoCapture != null;
    }

    @Override
    protected int getRelativeCameraOrientation() {
        return getRelativeImageOrientation(getDisplayRotation(), getSensorOrientation(), isUsingFrontFacingCamera(), false);
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

    private CameraSelector getCameraSelector() {
        return new CameraSelector.Builder().requireLensFacing(getLensFacing()).build();
    }

    private int getLensFacing() {
        return mIsFrontFacing ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
    }

    private int getFlashMode() {
        switch (getFlash()) {
            case ON:
                return ImageCapture.FLASH_MODE_ON;
            case OFF:
                return ImageCapture.FLASH_MODE_OFF;
            case AUTO:
                return ImageCapture.FLASH_MODE_AUTO;
        }
        return ImageCapture.FLASH_MODE_AUTO;
    }

    private int getCaptureMode() {
        switch (getQuality()) {
            case MAX:
                return ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY;
            default:
                return ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY;
        }
    }

    private void transformPreview(int previewWidth, int previewHeight, int rotation) {
        int viewWidth = getWidth();
        int viewHeight = getHeight();
        int displayOrientation = getDisplayRotation();
        int cameraOrientation = getSensorOrientation();

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
