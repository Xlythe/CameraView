package com.xlythe.view.camera.x;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;
import androidx.annotation.RestrictTo;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfoUnavailableException;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.FocusMeteringResult;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.SurfaceOrientedMeteringPointFactory;
import androidx.camera.core.UseCase;
import androidx.camera.core.ZoomState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.collection.ArrayMap;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.Exif;
import com.xlythe.view.camera.ICameraModule;
import com.xlythe.view.camera.LocationProvider;
import com.xlythe.view.camera.PermissionChecker;
import com.xlythe.view.camera.stream.VideoRecorder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private static final long LIB_TIMEOUT_MILLIS = 5000;

    /** When this flag is off, we attempt to write the file ourselves instead of using CameraX's convenience method. */
    private static final boolean SUPPORTS_WRITE_TO_FILE = true;

    /** CameraX breaks if you unbind the preview while taking a picture. When this flag is off, we won't unbind. */
    private static final boolean SUPPORTS_PICTURE_WITHOUT_PREVIEW = false;

    /** CameraX can get into a bad state when taking pictures. When this is true, we attempt to forcefully get back into a good state. */
    private static final boolean SUPPORTS_FORCE_RESTORE = false;

    /** The time (in milliseconds) that we consider a GPS signal to be relevant. */
    private static final long STALE_LOCATION_MILLIS = 2 * 60 * 60 * 1000;

    /** The time (in milliseconds) to wait for a GPS signal. This runs on the UI thread and so must be short. */
    private static final long GPS_TIMEOUT_MILLIS = 10;

    /** A future handle to the CameraX library. Until this is loaded, we cannot use CameraX. */
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

    /** The currently active Camera. Null if the camera isn't open. */
    @Nullable private Camera mActiveCamera;

    /** The size of the preview. Cached in case we need to adjust our view size. */
    @Nullable private Size mPreviewSize;

    /** The preview that draws to the texture surface. Non-null while open. */
    @Nullable private Preview mPreview;

    /** Allows us to take pictures. Non-null while open, but is temporarily unbound while recording a video. */
    @Nullable private ImageCapture mImageCapture;

    /** A video capture that captures what's visible on the screen. Non-null while taking a video. */
    @Nullable private VideoCapture<Recorder> mVideoCapture;

    /** A helper class for mVideoCapture that allows us to stop the recording. */
    @Nullable private Recording mVideoRecording;

    /** Custom use cases, beyond the typical Preview/Image/Video ones. */
    private final Map<VideoRecorder.SurfaceProvider, UseCase> mCustomUseCases = new ArrayMap<>();

    /** True if using the front facing camera. False otherwise. */
    private boolean mIsFrontFacing = false;

    /** The current zoom level, from 0 to {@link #getMaxZoomLevel()}. */
    private int mZoomLevel;

    /** True if the preview is currently paused. */
    private boolean mIsPaused = false;

    public CameraXModule(CameraView cameraView) {
        super(cameraView);
        mCameraProviderFuture = ProcessCameraProvider.getInstance(getContext());
        mCameraProviderFuture.addListener(() -> {
            try {
                mCameraProvider = mCameraProviderFuture.get();
                getContext().sendBroadcast(new Intent(CameraView.ACTION_CAMERA_STATE_CHANGED).setPackage(getContext().getPackageName()));
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

        if (DEBUG) {
            Log.d(TAG, "CameraXModule opened");
        }
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
                //.setTargetAspectRatio(getTargetAspectRatio())
                .setTargetRotation(getTargetRotation())
                .build();
        mPreview.setSurfaceProvider(request -> {
            SurfaceTexture surfaceTexture = getSurfaceTexture();
            if (surfaceTexture == null) {
                // Can happen if there's a race condition, and the texture is closed before we're
                // asked to provide it.
                request.willNotProvideSurface();
                return;
            }

            int cameraWidth = request.getResolution().getWidth();
            int cameraHeight = request.getResolution().getHeight();
            // Required or the preview will start drawing in odd aspect ratios.
            surfaceTexture.setDefaultBufferSize(cameraWidth, cameraHeight);

            Surface surface = new Surface(surfaceTexture);
            request.provideSurface(surface, ContextCompat.getMainExecutor(getContext()), result -> {
                if (DEBUG) {
                    Log.d(TAG, "Surface no longer needed. Result Code: " + result.getResultCode());
                }
            });

            mPreviewSize = new Size(cameraWidth, cameraHeight);
            transformPreview(cameraWidth, cameraHeight);
        });

        // We previously attempted to register the ImageCapture just in time, while taking a picture.
        // However, CameraX doesn't handle that well and #takePicture would silently fail. Instead,
        // we'll keep it registered whenever the preview is registered.
        mImageCapture = new ImageCapture.Builder()
                .setTargetResolution(getTargetResolution())
                //.setTargetAspectRatio(getTargetAspectRatio())
                .setTargetRotation(getTargetRotation())
                .setFlashMode(getFlashMode())
                .setCaptureMode(getCaptureMode())
                .build();

        if (!bind(mPreview, mImageCapture)) {
            Log.e(TAG, "Failed to load camera preview");
        }

        if (DEBUG) {
            Log.d(TAG, "Preview loaded");
            if (mActiveCamera != null) {
                mActiveCamera.getCameraInfo().getCameraState().observe(this, cameraState -> Log.d(TAG, "CameraState changed: type=" + cameraState.getType() + ", error=" + cameraState.getError()));
            }
        }
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

        if (mImageCapture == null) {
            return;
        }

        if (isRecording()) {
            stopRecording();
        }

        unbind(mPreview, mImageCapture);

        mPreviewSize = null;
        mPreview = null;
        mImageCapture = null;
        mActiveCamera = null;

        if (DEBUG) {
            Log.d(TAG, "CameraXModule closed");
        }
    }

    @Override
    public boolean hasFrontFacingCamera() {
        if (mCameraProvider == null) {
            Log.w(TAG, "Failed to query for front facing camera. CameraProvider is not available yet.");
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

    @SuppressLint("MissingPermission")
    @Override
    public void toggleCamera() {
        mIsFrontFacing = !mIsFrontFacing;

        boolean shouldOpen = mIsOpen;
        close();
        if (shouldOpen) {
            open();
        }
    }

    @SuppressLint("MissingPermission")
    @Override
    public void setLensFacing(CameraView.LensFacing lensFacing) {
        mIsFrontFacing = lensFacing.equals(CameraView.LensFacing.FRONT);

        boolean shouldOpen = mIsOpen;
        close();
        if (shouldOpen) {
            open();
        }
    }

    @SuppressLint("MissingPermission")
    private void attemptToRecover() {
        Log.w(TAG, "Camera is likely in a failed state. Attempting to recover.");

        getView().close();
        getView().open();
    }

    @Override
    public void focus(Rect focus, Rect metering) {
        if (mActiveCamera == null) {
            return;
        }

        SurfaceOrientedMeteringPointFactory factory = new SurfaceOrientedMeteringPointFactory(getWidth(), getHeight());
        ListenableFuture<FocusMeteringResult> result = mActiveCamera.getCameraControl()
                .startFocusAndMetering(new FocusMeteringAction.Builder(factory.createPoint(focus.centerX(), focus.centerY())).build());
        result.addListener(() -> {
            boolean isSuccessful = false;
            try {
                isSuccessful = result.get().isFocusSuccessful();
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
            Log.d(TAG, isSuccessful ? "Successfully focused" : "Failed to focus");
        }, MoreExecutors.directExecutor());
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
        ListenableFuture<Void> result = mActiveCamera.getCameraControl().setZoomRatio(zoom);
        result.addListener(() -> {
            boolean isSuccessful = false;
            try {
                result.get();
                isSuccessful = true;
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
            Log.d(TAG, isSuccessful ? "Successfully zoomed" : "Failed to zoom");
        }, MoreExecutors.directExecutor());
        mZoomLevel = zoomLevel;
    }

    @Override
    public int getZoomLevel() {
        return mZoomLevel;
    }

    @Override
    public int getMaxZoomLevel() {
        return 100;
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
        if (SUPPORTS_PICTURE_WITHOUT_PREVIEW) {
            unbind(mPreview);
        }
        mIsPaused = true;
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    @Override
    public void resume() {
        if (SUPPORTS_PICTURE_WITHOUT_PREVIEW) {
            bind(mPreview);
        }
        mIsPaused = false;
    }

    @Override
    public boolean isPaused() {
        return mIsPaused;
    }

    @Override
    public void takePicture(File file) {
        if (mCameraProvider == null) {
            Log.w(TAG, "Failed to take a picture. CameraProvider is not available yet.");
            onImageFailed();
            return;
        }

        if (mImageCapture == null) {
            Log.w(TAG, "Failed to take a picture. ImageCapture is not available yet.");
            onImageFailed();
            return;
        }

        if (isRecording()) {
            Log.w(TAG, "Failed to take a picture. Pictures cannot be taken while recording.");
            onImageFailed();
            return;
        }

        AtomicBoolean reportedFailure = new AtomicBoolean(false);

        Handler cancellationHandler = new Handler();
        cancellationHandler.postDelayed(() -> {
            Log.w(TAG, "Failed to take a picture. Timed out while waiting for picture callback.");
            if (!reportedFailure.get()) {
                onImageFailed();
                reportedFailure.set(true);
            }

            // Note: We cannot attempt to recover immediately, as unbinding the ImageCapture starts
            // a chain of events internally. A small delay is enough, though.
            if (SUPPORTS_FORCE_RESTORE) {
                unbind(mImageCapture);
                new Handler().postDelayed(this::attemptToRecover, 150);
            }
        }, LIB_TIMEOUT_MILLIS);

        if (SUPPORTS_WRITE_TO_FILE) {
            ImageCapture.Metadata metadata = new ImageCapture.Metadata();
            metadata.setLocation(getLocation());
            metadata.setReversedHorizontal(isUsingFrontFacingCamera());
            ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(file).setMetadata(metadata).build();
            mImageCapture.takePicture(options, ContextCompat.getMainExecutor(getContext()), new ImageCapture.OnImageSavedCallback() {
                @Override
                public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                    showImageConfirmation(file);
                    cancellationHandler.removeCallbacksAndMessages(null);
                }

                @Override
                public void onError(@NonNull ImageCaptureException e) {
                    Log.w(TAG, "Failed to take a picture. ImageCapture failed.", e);
                    if (!reportedFailure.get()) {
                        onImageFailed();
                        reportedFailure.set(true);
                    }
                    cancellationHandler.removeCallbacksAndMessages(null);
                }
            });
        } else {
            mImageCapture.takePicture(ContextCompat.getMainExecutor(getContext()), new ImageCapture.OnImageCapturedCallback() {
                public void onCaptureSuccess(@NonNull ImageProxy image) {
                    if (save(file, image)) {
                        showImageConfirmation(file);
                    } else {
                        if (!reportedFailure.get()) {
                            onImageFailed();
                            reportedFailure.set(true);
                        }
                    }

                    cancellationHandler.removeCallbacksAndMessages(null);
                }

                public void onError(@NonNull final ImageCaptureException e) {
                    Log.w(TAG, "Failed to take a picture. ImageCapture failed.", e);
                    if (!reportedFailure.get()) {
                        onImageFailed();
                        reportedFailure.set(true);
                    }
                    cancellationHandler.removeCallbacksAndMessages(null);
                }
            });
        }
    }

    private boolean save(File file, ImageProxy image) {
        if (image.getFormat() != ImageFormat.JPEG) {
            throw new IllegalArgumentException("Received ImageProxy with unexpected format " + image.getFormat());
        }

        ImageProxy.PlaneProxy planeProxy = image.getPlanes()[0];
        ByteBuffer buffer = planeProxy.getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);

        FileOutputStream output = null;
        try {
            output = new FileOutputStream(file);
            output.write(bytes);
            writeExif(file);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to write the file", e);
        } finally {
            image.close();
            if (output != null) {
                try {
                    output.close();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to close the output stream", e);
                }
            }
        }
        return false;
    }

    private void writeExif(File file) throws IOException {
        Exif exif = new Exif(file);
        exif.attachTimestamp();
        exif.rotate(getSensorOrientation());
        if (isUsingFrontFacingCamera()) {
            exif.flipHorizontally();
        }
        Location location = getLocation();
        if (location != null) {
            exif.attachLocation(location);
        }
        exif.save();
    }

    @SuppressLint("MissingPermission")
    @Nullable
    private Location getLocation() {
        if (PermissionChecker.hasPermissions(getContext(), Manifest.permission.ACCESS_FINE_LOCATION)) {
            // Our GPS timeout is purposefully low. We're not intending to wait until GPS is acquired
            // but we want a last known location for the next time a picture is taken.
            return LocationProvider.getGPSLocation(getContext(), STALE_LOCATION_MILLIS, GPS_TIMEOUT_MILLIS);
        }

        return null;
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @Override
    public void startRecording(File file) {
        if (mCameraProvider == null) {
            Log.w(TAG, "Failed to take a video. CameraProvider is not available yet.");
            onVideoFailed();
            return;
        }

        if (areUseCasesMutuallyExclusive()) {
            // Images and Video are mutually exclusive. We'll unbind the ImageCapture while recording.
            unbind(mImageCapture);
        }

        VideoCapture<Recorder> videoCapture = VideoCapture.withOutput(new Recorder.Builder()
                .setQualitySelector(QualitySelector.fromOrderedList(getVideoQualityPriority()))
                .build());

        if (!bind(videoCapture)) {
            Log.w(TAG, "Failed to take a video. VideoCapture failed.");
            onVideoFailed();
            if (areUseCasesMutuallyExclusive()) {
                bind(mImageCapture);
            }
            return;
        }

        Recorder recorder = videoCapture.getOutput();
        FileOutputOptions.Builder options = new FileOutputOptions.Builder(file);
        if (getMaxVideoSize() != CameraView.INDEFINITE_VIDEO_SIZE) {
            options.setFileSizeLimit(getMaxVideoSize());
        }
        PendingRecording pendingRecording = recorder.prepareRecording(getContext(), options.build());
        Recording recording = pendingRecording.withAudioEnabled().start(ContextCompat.getMainExecutor(getContext()), videoRecordEvent -> {
            if (videoRecordEvent instanceof VideoRecordEvent.Start) {
                Log.d(TAG, "Started video recording");
            } else if (videoRecordEvent instanceof VideoRecordEvent.Pause) {
                Log.d(TAG, "Paused video recording");
            } else if (videoRecordEvent instanceof VideoRecordEvent.Resume) {
                Log.d(TAG, "Resumed video recording");
            } else if (videoRecordEvent instanceof VideoRecordEvent.Status) {
                Log.d(TAG, "Received video recording status update");
            } else if (videoRecordEvent instanceof VideoRecordEvent.Finalize) {
                VideoRecordEvent.Finalize finalizeEvent = (VideoRecordEvent.Finalize) videoRecordEvent;
                switch (finalizeEvent.getError()) {
                    case VideoRecordEvent.Finalize.ERROR_NONE:
                    case VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED:
                    case VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED:
                        // The good cases.
                        showVideoConfirmation(file);
                        stopRecording();
                        break;
                    case VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE:
                        // The possibly good cases.
                        if (file.exists()) {
                            showVideoConfirmation(file);
                            stopRecording();
                            break;
                        }

                        // Fall through on failure
                    case VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED:
                    case VideoRecordEvent.Finalize.ERROR_INVALID_OUTPUT_OPTIONS:
                    case VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA:
                    case VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR:
                    case VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE:
                    case VideoRecordEvent.Finalize.ERROR_RECORDING_GARBAGE_COLLECTED:
                    case VideoRecordEvent.Finalize.ERROR_UNKNOWN:
                    default:
                        // The bad cases
                        Log.e(TAG, "Failed to start video recording: " + toString(finalizeEvent));
                        onVideoFailed();
                        stopRecording();
                        break;
                }
            }
        });

        mVideoCapture = videoCapture;
        mVideoRecording = recording;
    }

    private List<Quality> getVideoQualityPriority() {
        List<Quality> qualities = new ArrayList<>();
        switch (getQuality()) {
            case MAX:
                qualities.add(Quality.UHD);
                // fall through
            case HIGH:
                qualities.add(Quality.FHD);
                // fall through
            case MEDIUM:
                qualities.add(Quality.HD);
                // fall through
            case LOW:
                qualities.add(Quality.SD);
        }
        return qualities;
    }

    private String toString(VideoRecordEvent.Finalize event) {
        switch (event.getError()) {
            case VideoRecordEvent.Finalize.ERROR_NONE:
                return "ERROR_NONE";
            case VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED:
                return "ERROR_FILE_SIZE_LIMIT_REACHED";
            case VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE:
                return "ERROR_INSUFFICIENT_STORAGE";
            case VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED:
                return "ERROR_ENCODING_FAILED";
            case VideoRecordEvent.Finalize.ERROR_INVALID_OUTPUT_OPTIONS:
                return "ERROR_INVALID_OUTPUT_OPTIONS";
            case VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA:
                return "ERROR_NO_VALID_DATA";
            case VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR:
                return "ERROR_RECORDER_ERROR";
            case VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE:
                return "ERROR_SOURCE_INACTIVE";
            case VideoRecordEvent.Finalize.ERROR_UNKNOWN:
            default:
                return String.format(Locale.US, "[%d]ERROR_UNKNOWN", event.getError());
        }
    }

    @Override
    public void stopRecording() {
        if (mVideoCapture == null || mVideoRecording == null) {
            return;
        }

        mVideoRecording.stop();
        unbind(mVideoCapture);
        if (areUseCasesMutuallyExclusive()) {
            bind(mImageCapture);
        }
        mVideoCapture = null;
        mVideoRecording = null;
    }

    @Override
    public boolean isRecording() {
        return mVideoCapture != null;
    }

    private boolean areUseCasesMutuallyExclusive() {
        return !isAtLeastLimited();
    }

    private boolean isAtLeastLimited() {
        switch (getHardwareLevel()) {
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3:
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL:
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL:
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED:
                return true;
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY:
            default:
                return false;
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private int getHardwareLevel() {
        if (mCameraProvider == null) {
            return -1;
        }

        if (mActiveCamera == null) {
            return -1;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY;
        }

        Integer hardwareLevel = Camera2CameraInfo.from(mActiveCamera.getCameraInfo()).getCameraCharacteristic(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        if (hardwareLevel == null) {
            return CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY;
        }

        return hardwareLevel;
    }

    @Override
    protected int getRelativeCameraOrientation() {
        return getRelativeImageOrientation(getDisplayRotation(), getSensorOrientation(), isUsingFrontFacingCamera(), false);
    }

    private Size getTargetResolution() {
        return new Size(getWidth(), getHeight());
    }

    @AspectRatio.Ratio
    private int getTargetAspectRatio() {
        Size resolution = getTargetResolution();

        int width = Math.max(resolution.getWidth(), resolution.getHeight());
        int height = Math.min(resolution.getWidth(), resolution.getHeight());
        float aspectRatio = (float) width / (float) height;
        // 1.55 is halfway between 1.33 (4:3) and 1.77 (16:9). As those are the only
        // supported aspect ratios, we will use the closer one.
        if (aspectRatio > 1.55) {
            return AspectRatio.RATIO_16_9;
        } else {
            return AspectRatio.RATIO_4_3;
        }
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

    @SuppressLint("UnsafeOptInUsageError")
    private int getCaptureMode() {
        switch (getQuality()) {
            case MAX:
                return ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY;
            case HIGH:
                return ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY;
            default:
                return ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG;
        }
    }

    private boolean bind(UseCase... useCases) {
        if (mCameraProvider == null) {
            return false;
        }

        try {
            mActiveCamera = mCameraProvider.bindToLifecycle(this, getCameraSelector(), useCases);
            return true;
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Failed to bind UseCase.", e);
            return false;
        }
    }

    private void unbind(UseCase... useCases) {
        if (mCameraProvider == null) {
            return;
        }

        mCameraProvider.unbind(useCases);
    }

    @Override
    protected void attachSurface(VideoRecorder.SurfaceProvider surfaceProvider) {
        Preview useCase = new Preview.Builder()
                .setTargetResolution(getTargetResolution())
                //.setTargetAspectRatio(getTargetAspectRatio())
                .setTargetRotation(getTargetRotation())
                .build();
        useCase.setSurfaceProvider(request ->
                request.provideSurface(surfaceProvider.getSurface(request.getResolution().getWidth(), request.getResolution().getHeight(), getSensorOrientation(), isUsingFrontFacingCamera()), ContextCompat.getMainExecutor(getContext()), result -> {
            if (DEBUG) {
                Log.d(TAG, "Surface no longer needed. Result Code: " + result.getResultCode());
            }
        }));
        if (bind(useCase)) {
            mCustomUseCases.put(surfaceProvider, useCase);
            Log.d(TAG, "Successfully bound custom surface");
        }
    }

    @Override
    protected void detachSurface(VideoRecorder.SurfaceProvider surfaceProvider) {
        UseCase useCase = mCustomUseCases.remove(surfaceProvider);
        if (useCase != null) {
            unbind(useCase);
        }
    }

    @Override
    public void onLayoutChanged() {
        if (mPreviewSize == null) {
            return;
        }

        transformPreview(mPreviewSize.getWidth(), mPreviewSize.getHeight());
    }

    private void transformPreview(int previewWidth, int previewHeight) {
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
        int rotation = -displayOrientation;

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
