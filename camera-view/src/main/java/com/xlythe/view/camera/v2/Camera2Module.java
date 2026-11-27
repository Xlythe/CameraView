package com.xlythe.view.camera.v2;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
import android.view.Surface;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.Face;
import com.xlythe.view.camera.ICameraModule;
import com.xlythe.view.camera.TransformMath;
import com.xlythe.view.camera.stream.VideoRecorder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;
import androidx.annotation.RestrictTo;

import static java.lang.String.format;

/**
 * A wrapper around the Camera2 APIs. Camera2 has some peculiarities, such as crashing if you attach
 * too many surfaces (or too large a surface) to a capture session. To get around that, we define
 * {@link Session}s that list out compatible surfaces and creates capture requests for them.
 */
@TargetApi(21)
@RestrictTo(RestrictTo.Scope.LIBRARY)
public class Camera2Module extends ICameraModule {
    /** The zoom scale CameraView publishes, matching the CameraX backend so the two agree. */
    private static final int MAX_ZOOM_LEVEL = 100;
    /** Maximum automatic reopen attempts before reporting a terminal Camera2 failure. */
    private static final int MAX_REOPEN_ATTEMPTS = 1;

    private static final String EXTRA_DEVICE_ID = "device_id";

    // It seems like, when Lollipop was released, 1080p was settled on as the 'default' resolution.
    // Camera2 has a long table of different combinations of preview, picture, and video with the
    // repeated requirement "Up to either 1080p, or the largest size supported by the device.
    // Whichever is smaller." To go past 1080p requires limiting another column on the table. As a
    // general rule, we don't go past 1080p unless we test on a wide range of devices.
    static final Size MAX_SUPPORTED_SIZE = new Size(1920, 1080);

    /**
     * The largest preview the camera is guaranteed to manage alongside a full-size still capture.
     *
     * <p>Camera2's guaranteed stream combinations define PREVIEW as the largest output at most
     * 1920x1080 and at most the display size, and every level of that table is written in terms
     * of it, so exceeding either half leaves guaranteed territory. We only ever applied the 1080p
     * half, which over-asks on a device whose panel is smaller.</p>
     *
     * <p>When the device can be asked directly, through isSessionConfigurationSupported from API
     * 29, a larger preview is used if it says yes. Anything else, including an older API, a
     * refusal or a failure to answer, keeps the guaranteed size: the worst case is exactly the
     * behavior that shipped.</p>
     */
    @NonNull
    Size getPreviewSizeCap(@NonNull List<Size> candidates) {
        Size guaranteed = guaranteedPreviewCap(displaySize());
        if (largestWithin(candidates, guaranteed) == null) {
            // Every output this camera offers is larger than the panel, which happens on a small
            // screen paired with a normal sensor. Admit the smallest one rather than nothing.
            Size smallest = smallestOf(candidates);
            return smallest == null ? guaranteed : smallest;
        }

        Size largest = largestWithin(candidates, null);
        if (largest == null || !exceeds(largest, guaranteed)) {
            return guaranteed;
        }
        if (isPreviewSizeSupported(largest)) {
            if (DEBUG) Log.d(TAG, "Camera confirmed a preview larger than the guaranteed size");
            return largest;
        }
        return guaranteed;
    }

    /** The smallest candidate by area, or null when there are none. */
    @Nullable
    static Size smallestOf(@NonNull List<Size> candidates) {
        Size best = null;
        for (Size size : candidates) {
            if (best == null
                    || (long) size.getWidth() * size.getHeight()
                    < (long) best.getWidth() * best.getHeight()) {
                best = size;
            }
        }
        return best;
    }

    /** Intersects the two halves of the table's PREVIEW definition. */
    @NonNull
    static Size guaranteedPreviewCap(@Nullable Size displaySize) {
        if (displaySize == null) {
            return MAX_SUPPORTED_SIZE;
        }
        // The display is reported in its current orientation, and camera sizes are always
        // landscape, so compare against its long and short edges rather than width and height.
        int longEdge = Math.max(displaySize.getWidth(), displaySize.getHeight());
        int shortEdge = Math.min(displaySize.getWidth(), displaySize.getHeight());
        return new Size(
                Math.min(MAX_SUPPORTED_SIZE.getWidth(), longEdge),
                Math.min(MAX_SUPPORTED_SIZE.getHeight(), shortEdge));
    }

    /** True when either dimension of a size is past the cap. */
    static boolean exceeds(@NonNull Size size, @NonNull Size cap) {
        return size.getWidth() > cap.getWidth() || size.getHeight() > cap.getHeight();
    }

    /** The largest candidate within an optional cap, by area. */
    @Nullable
    static Size largestWithin(@NonNull List<Size> candidates, @Nullable Size cap) {
        Size best = null;
        for (Size size : candidates) {
            if (cap != null && exceeds(size, cap)) {
                continue;
            }
            if (best == null
                    || (long) size.getWidth() * size.getHeight()
                    > (long) best.getWidth() * best.getHeight()) {
                best = size;
            }
        }
        return best;
    }

    @Nullable
    private Size displaySize() {
        try {
            DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
            if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
                return null;
            }
            return new Size(metrics.widthPixels, metrics.heightPixels);
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to read the display size", error);
            return null;
        }
    }

    /**
     * Asks the camera whether it would configure a preview of this size next to a full-size
     * still capture. Returns false whenever the answer is anything but a clear yes.
     */
    private boolean isPreviewSizeSupported(@NonNull Size previewSize) {
        CameraDevice device = mCameraDevice;
        if (device == null || Build.VERSION.SDK_INT < 29) {
            return false;
        }

        ImageReader probeReader = null;
        try {
            CameraCharacteristics characteristics =
                    mCameraManager.getCameraCharacteristics(getActiveCamera());
            StreamConfigurationMap map =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size stillSize = map == null ? null
                    : largestWithin(Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)), null);
            if (stillSize == null) {
                return false;
            }

            // A throwaway reader stands in for the still-capture stream, so the combination can
            // be checked without building, and then tearing down, the real session.
            probeReader = ImageReader.newInstance(stillSize.getWidth(), stillSize.getHeight(),
                    ImageFormat.JPEG, 1);
            List<OutputConfiguration> outputs = Arrays.asList(
                    new OutputConfiguration(previewSize, SurfaceTexture.class),
                    new OutputConfiguration(probeReader.getSurface()));
            SessionConfiguration configuration = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR, outputs, mHandler::post,
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(@NonNull CameraCaptureSession session) {}
                        @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) {}
                    });
            return device.isSessionConfigurationSupported(configuration);
        } catch (CameraAccessException | RuntimeException error) {
            // Anything but a clear yes keeps the guaranteed size, including a device that cannot
            // answer the question at all.
            if (DEBUG) Log.d(TAG, "Camera could not confirm the preview size", error);
            return false;
        } finally {
            if (probeReader != null) {
                probeReader.close();
            }
        }
    }

    /**
     * This is how we'll talk to the camera.
     */
    @NonNull
    private final CameraManager mCameraManager;

    /**
     * This is the id of the camera (eg. front or back facing) that we're currently using.
     */
    @Nullable
    private String mActiveCamera;

    /**
     * The current capture session. There is one capture session per {@link Session}.
     */
    @Nullable
    private CameraCaptureSession mCaptureSession;

    /**
     * The currently active camera. This may be a front facing camera or a back facing one.
     */
    @Nullable
    private CameraDevice mCameraDevice;

    /**
     * A handler to receive callbacks from the camera on.
     */
    @NonNull
    private final Handler mHandler;

    /**
     * A session that's attempting, but has not yet become, the active session.
     */
    @Nullable
    private Session mRegisteringSession;

    /**
     * The currently active session. See {@link PictureSession} and {@link VideoSession}.
     */
    @Nullable
    private Session mActiveSession;

    /** The face detection mode the repeating request carries, or null when faces are not wanted. */
    @Nullable
    private Integer mFaceDetectMode;

    /** How many faces the last report had, so an empty camera is not reported every frame. */
    private int mLastFaceCount;

    /** Whether a focus request is still waiting on the camera to report what came of it. */
    private boolean mIsFocusPending;

    /** Readers of preview frames, which the still-capture session carries beside its outputs. */
    private final Set<VideoRecorder.SurfaceProvider> mAnalysisProviders = new LinkedHashSet<>();

    /**
     * The current zoom level, from 0 to {@link #getMaxZoomLevel()}.
     */
    private int mZoomLevel;

    /**
     * If true, the camera is currently open.
     */
    private boolean mIsOpen = false;

    /**
     * If true, the preview should be paused.
     */
    private boolean mIsPaused = false;

    /**
     * If true, we are currently recording.
     */
    private boolean mIsRecording = false;

    /**
     * Incremented for every open/close cycle so callbacks from an obsolete camera request can be
     * rejected without disturbing the current device.
     */
    private int mGeneration;

    /** Number of consecutive reopen attempts since the last usable capture session. */
    private int mReopenAttempts;

    /**
     * Generation to open after the previous Camera2 device reports that it has fully closed, or
     * zero when no lens switch is waiting for device release.
     */
    private int mPendingOpenGeneration;

    /** Camera2 device whose onClosed callback is allowed to complete a pending lens switch. */
    @Nullable
    private CameraDevice mPendingCloseDevice;

    private CameraDevice.StateCallback createStateCallback(final int generation) {
        return new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice cameraDevice) {
                if (!mIsOpen || generation != mGeneration) {
                    Log.w(TAG, "Ignoring a stale Camera2 open callback");
                    cameraDevice.close();
                    return;
                }

                mCameraDevice = cameraDevice;
                setSession(new PictureSession(Camera2Module.this));
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice cameraDevice) {
                handleCameraFailure(cameraDevice, generation, "Camera disconnected");
            }

            @Override
            public void onError(@NonNull CameraDevice cameraDevice, int error) {
                handleCameraFailure(cameraDevice, generation,
                        "Camera device error: " + Camera2Module.toString(error));
            }

            @Override
            public void onClosed(@NonNull CameraDevice cameraDevice) {
                int pendingGeneration = mPendingOpenGeneration;
                if (pendingGeneration == 0 || cameraDevice != mPendingCloseDevice) {
                    return;
                }
                mPendingOpenGeneration = 0;
                mPendingCloseDevice = null;
                if (mIsOpen && pendingGeneration == mGeneration) {
                    openCamera(pendingGeneration);
                }
            }
        };
    }

    @SuppressLint("MissingPermission")
    private void openCamera(int generation) {
        try {
            mActiveCamera = getActiveCamera();
            if (DEBUG) Log.d(TAG, "Opening camera " + mActiveCamera);
            mCameraManager.openCamera(mActiveCamera, createStateCallback(generation), mHandler);
        } catch (CameraAccessException | RuntimeException e) {
            mIsOpen = false;
            closeCameraResources();
            onCameraError(new IllegalStateException("Failed to open Camera2 device", e));
        }
    }

    private void handleCameraFailure(@NonNull CameraDevice cameraDevice, int generation,
            String message) {
        Log.e(TAG, message);
        cameraDevice.close();
        if (generation != mGeneration) {
            Log.w(TAG, "Ignoring a stale Camera2 failure callback");
            return;
        }

        boolean shouldReopen = mIsOpen && mReopenAttempts < MAX_REOPEN_ATTEMPTS;
        closeCameraResources();
        if (shouldReopen) {
            mReopenAttempts++;
            int retryGeneration = ++mGeneration;
            mHandler.post(() -> {
                if (mIsOpen && retryGeneration == mGeneration) {
                    openCamera(retryGeneration);
                }
            });
        } else if (mIsOpen) {
            mIsOpen = false;
            onCameraError(new IllegalStateException(message));
        }
    }

    static String toString(int error) {
        switch (error) {
            case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE:
                return "ERROR_CAMERA_DEVICE";
            case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:
                return "ERROR_CAMERA_DISABLED";
            case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
                return "ERROR_CAMERA_IN_USE";
            case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE:
                return "ERROR_CAMERA_SERVICE";
            case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
                return "ERROR_MAX_CAMERAS_IN_USE";
        }
        return format(Locale.US, "UNKNOWN_ERROR(%d)", error);
    }

    public Camera2Module(CameraView cameraView) {
        super(cameraView);
        mCameraManager = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
        mHandler = new Handler(Looper.getMainLooper());
    }

    private void setSession(final Session session) {
        if (!mIsOpen || mCameraDevice == null) {
            if (DEBUG) Log.w(TAG, "Cannot start a session without an open CameraDevice");
            closeSession(session);
            return;
        }

        try {
            // Clean up any previous sessions
            boolean hasPreviousState = false;
            if (mCaptureSession != null) {
                mCaptureSession.stopRepeating();
                try {
                    mCaptureSession.abortCaptures();
                } catch (CameraAccessException | RuntimeException e) {
                    Log.e(TAG, "Failed to abort captures", e);
                }
                mCaptureSession.close();
                mCaptureSession = null;
                hasPreviousState = true;
            }
            if (mRegisteringSession != null) {
                Log.d(TAG, format("%s is replacing %s", session.getClass().getSimpleName(), mRegisteringSession.getClass().getSimpleName()));

                // Restore state from the previous session
                session.setMeteringRectangle(mRegisteringSession.getMeteringRectangle());
                session.setCropRegion(mRegisteringSession.getCropRegion());

                mRegisteringSession.close();
                mRegisteringSession = null;
                hasPreviousState = true;
            }
            if (mActiveSession != null) {
                // Restore state from the previous session
                session.setMeteringRectangle(mActiveSession.getMeteringRectangle());
                session.setCropRegion(mActiveSession.getCropRegion());

                mActiveSession.close();
                mActiveSession = null;
                hasPreviousState = true;
            }
            if (hasPreviousState) {
                mHandler.post(() -> setSession(session));
                return;
            }

            // Assume this is a brand new session that's never been set up. Claim ownership before
            // initialization so any failure can clean up this exact session.
            mRegisteringSession = session;
            CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(getActiveCamera());
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                throw new IllegalStateException("Camera has no stream configuration map");
            }
            session.initialize(map);

            // Now, with all of our surfaces, we'll ask for a new session
            createCaptureSession(session.getSurfaces(), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    if (!mIsOpen || mCameraDevice == null || mRegisteringSession != session) {
                        cameraCaptureSession.close();
                        return;
                    }

                    try {
                        mCaptureSession = cameraCaptureSession;
                        mActiveSession = session;
                        if (mRegisteringSession == session) {
                            mRegisteringSession = null;
                        }
                        mReopenAttempts = 0;
                        if (!mIsPaused) {
                            session.onAvailable(mCameraDevice, mCaptureSession);
                        }
                        // The session is configured and targeted, which is the first moment a
                        // capture can succeed.
                        onCameraReady();
                        if (mZoomLevel != 0) {
                            setZoomLevel(mZoomLevel);
                        }
                    } catch (CameraAccessException | RuntimeException e) {
                        failSession(session, cameraCaptureSession,
                                "Failed to start " + session.getClass().getSimpleName(), e);
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    if (!mIsOpen || mRegisteringSession != session) {
                        cameraCaptureSession.close();
                        return;
                    }
                    failSession(session, cameraCaptureSession,
                            "Failed to configure " + session.getClass().getSimpleName(), null);
                }
            });
        } catch (CameraAccessException | RuntimeException e) {
            failSession(session, null, "Failed to create capture session", e);
        }
    }

    /**
     * Asks the device for a session, naming a colour space when one was asked for.
     *
     * <p>A colour space belongs to the session configuration, which means the newer
     * SessionConfiguration form of createCaptureSession and API 34. The older call is kept for
     * everything else rather than replaced: it is the path every device this library has ever run
     * on uses, and it has nothing to gain from being rewritten for a feature that is not in
     * play.</p>
     */
    private void createCaptureSession(@NonNull List<Surface> surfaces,
            @NonNull CameraCaptureSession.StateCallback callback) throws CameraAccessException {
        CameraDevice device = mCameraDevice;
        if (device == null) {
            throw new IllegalStateException("Camera has no device to configure");
        }
        if (Build.VERSION.SDK_INT < 34 || getColorSpace() == CameraView.ColorSpace.SRGB) {
            device.createCaptureSession(surfaces, callback, mHandler);
            return;
        }
        android.graphics.ColorSpace.Named named = namedColorSpace(getColorSpace());
        if (named == null) {
            device.createCaptureSession(surfaces, callback, mHandler);
            return;
        }

        List<OutputConfiguration> outputs = new ArrayList<>(surfaces.size());
        for (Surface surface : surfaces) {
            outputs.add(new OutputConfiguration(surface));
        }
        SessionConfiguration configuration = new SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR, outputs, mHandler::post, callback);
        configuration.setColorSpace(named);
        device.createCaptureSession(configuration);
    }

    private void failSession(Session session, @Nullable CameraCaptureSession captureSession,
            String message, @Nullable Throwable cause) {
        Log.e(TAG, message, cause);
        boolean ownsSession = mRegisteringSession == session || mActiveSession == session
                || (captureSession != null && mCaptureSession == captureSession);
        if (captureSession != null) {
            captureSession.close();
        }
        if (!ownsSession) {
            closeSession(session);
            return;
        }
        if (mCaptureSession == captureSession) {
            mCaptureSession = null;
        }
        if (mRegisteringSession == session) {
            mRegisteringSession = null;
        }
        if (mActiveSession == session) {
            mActiveSession = null;
        }
        closeSession(session);
        if (mIsOpen) {
            mIsOpen = false;
            mGeneration++;
            closeCameraResources();
            onCameraError(new IllegalStateException(message, cause));
        }
    }

    private void closeSession(@Nullable Session session) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to close Camera2 session resources", e);
        }
    }

    private void closeCameraResources() {
        CameraCaptureSession captureSession = mCaptureSession;
        Session registeringSession = mRegisteringSession;
        Session activeSession = mActiveSession;
        CameraDevice cameraDevice = mCameraDevice;
        mCaptureSession = null;
        mRegisteringSession = null;
        mActiveSession = null;
        mCameraDevice = null;

        if (captureSession != null) {
            try {
                captureSession.close();
            } catch (RuntimeException e) {
                Log.e(TAG, "Failed to close Camera2 capture session", e);
            }
        }
        closeSession(registeringSession);
        if (activeSession != registeringSession) {
            closeSession(activeSession);
        }
        if (cameraDevice != null) {
            try {
                cameraDevice.close();
            } catch (RuntimeException e) {
                Log.e(TAG, "Failed to close Camera2 device", e);
            }
        }
        mIsPaused = false;
        mIsRecording = false;
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    @Override
    public void open() {
        if (mIsOpen) {
            return;
        }
        closeCameraResources();
        mIsOpen = true;
        mReopenAttempts = 0;
        mPendingOpenGeneration = 0;
        mPendingCloseDevice = null;
        openCamera(++mGeneration);
    }

    @Override
    public void close() {
        mIsOpen = false;
        mReopenAttempts = 0;
        mPendingOpenGeneration = 0;
        mPendingCloseDevice = null;
        mIsFocusPending = false;
        mGeneration++;
        closeCameraResources();
    }

    @Override
    public boolean hasFrontFacingCamera() {
        try {
            for (String cameraId : mCameraManager.getCameraIdList()) {
                if (isFrontFacing(cameraId)) return true;
            }
        } catch (CameraAccessException | RuntimeException e) {
            Log.e(TAG, "Failed to query camera", e);
        }
        return false;
    }

    @Override
    public boolean isUsingFrontFacingCamera() {
        try {
            return isFrontFacing(getActiveCamera());
        } catch (CameraAccessException | RuntimeException e) {
            Log.e(TAG, "Failed to query camera", e);
        }
        return false;
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    @Override
    public void toggleCamera() {
        try {
            String[] cameraIds = getAvailableCameraIds();
            if (cameraIds.length < 2) {
                Log.w(TAG, "Cannot toggle camera: only one camera is available");
                return;
            }

            String activeCamera = getActiveCamera();
            int position = 0;
            while (position < cameraIds.length && !cameraIds[position].equals(activeCamera)) {
                position++;
            }

            switchCamera(cameraIds[(position + 1) % cameraIds.length]);
        } catch (CameraAccessException | RuntimeException e) {
            Log.e(TAG, "Failed to toggle camera", e);
        }
    }

    @Override
    @SuppressLint("MissingPermission")
    public void setLensFacing(CameraView.LensFacing lensFacing) {
        try {
            String matchingCamera = preferredCamera(getAvailableCameraIds(),
                    lensFacing == CameraView.LensFacing.FRONT
                            ? CameraCharacteristics.LENS_FACING_FRONT
                            : CameraCharacteristics.LENS_FACING_BACK);
            if (matchingCamera == null) {
                Log.w(TAG, "No camera found for lens facing " + lensFacing);
                return;
            }
            if (matchingCamera.equals(getActiveCamera())) {
                return;
            }

            switchCamera(matchingCamera);
        } catch (CameraAccessException | RuntimeException e) {
            Log.e(TAG, "Failed to change lens facing", e);
        }
    }

    private void switchCamera(String cameraId) {
        boolean shouldReopen = mIsOpen;
        CameraDevice deviceToClose = mCameraDevice;
        boolean mustAwaitDeviceClose = deviceToClose != null;
        mIsOpen = false;
        mReopenAttempts = 0;
        mPendingOpenGeneration = 0;
        mPendingCloseDevice = null;
        mGeneration++;
        closeCameraResources();
        mActiveCamera = cameraId;
        if (shouldReopen) {
            mIsOpen = true;
            int nextGeneration = ++mGeneration;
            if (mustAwaitDeviceClose) {
                mPendingOpenGeneration = nextGeneration;
                mPendingCloseDevice = deviceToClose;
            } else {
                openCamera(nextGeneration);
            }
        }
    }

    @Override
    public void focus(Rect focus, Rect metering) {
        if (mCaptureSession == null) {
            if (DEBUG) Log.w(TAG, "Cannot focus. No capture session.");
            return;
        }

        if (mActiveSession == null) {
            if (DEBUG) Log.w(TAG, "Cannot focus. No active session.");
            return;
        }

        if (mCameraDevice == null) {
            if (DEBUG) Log.w(TAG, "Cannot focus. No camera device.");
            return;
        }

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
            // Watched before the request goes out, because the result that answers it can arrive
            // on the very first frame the new request produces.
            mIsFocusPending = true;
            if (!mIsPaused) {
                mActiveSession.onInvalidate(mCameraDevice, mCaptureSession);
            }
            onFocusRequested(focus);
        } catch (CameraAccessException | RuntimeException e) {
            // Crashes if the Camera is interacted with while still loading
            Log.e(TAG, "Failed to focus", e);
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
        mZoomLevel = rangeLimit(zoomLevel);

        if (mCameraDevice == null || mCaptureSession == null || mActiveSession == null) {
            if (DEBUG) Log.w(TAG, "No active session available");
            return;
        }

        try {
            CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(getActiveCamera());
            Range<Float> ratioRange = zoomRatioRange(characteristics);
            if (ratioRange != null) {
                // The whole optical range, so the low end can reach an ultra-wide lens and the
                // high end a telephoto, with the camera switching between them as it crosses the
                // thresholds. A crop region could only ever zoom within one lens.
                mActiveSession.setZoomRatio(
                        zoomRatioFor(ratioRange.getLower(), ratioRange.getUpper(), mZoomLevel));
                mActiveSession.setCropRegion(null);
            } else {
                Rect activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                Float maxDigitalZoom = characteristics.get(
                        CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
                if (activeArray == null || maxDigitalZoom == null || maxDigitalZoom <= 1f) {
                    mZoomLevel = 0;
                    Log.w(TAG, "Zoom not supported");
                    return;
                }
                mActiveSession.setZoomRatio(null);
                mActiveSession.setCropRegion(
                        calculateCropRegion(activeArray, maxDigitalZoom, mZoomLevel));
            }
            if (!mIsPaused) {
                mActiveSession.onInvalidate(mCameraDevice, mCaptureSession);
            }
        } catch (CameraAccessException | RuntimeException e) {
            Log.e(TAG, "Failed to zoom", e);
        }
    }

    /**
     * The camera's usable zoom ratio range, or null when the control is unavailable.
     *
     * <p>CONTROL_ZOOM_RATIO_RANGE arrives in API 30. A lower bound below one means the logical
     * camera has a wider physical lens to switch to.</p>
     */
    @Nullable
    private static Range<Float> zoomRatioRange(@NonNull CameraCharacteristics characteristics) {
        if (Build.VERSION.SDK_INT < 30) {
            return null;
        }
        Range<Float> range = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
        if (range == null || range.getUpper() <= range.getLower()) {
            return null;
        }
        return range;
    }

    /** Maps a zoom level onto a ratio range, linearly from the widest lens to the longest. */
    static float zoomRatioFor(float minRatio, float maxRatio, int zoomLevel) {
        float fraction = (float) rangeLimit(zoomLevel) / MAX_ZOOM_LEVEL;
        return minRatio + (maxRatio - minRatio) * fraction;
    }

    /** The zoom a level maps onto when the camera zooms by cropping rather than by ratio. */
    static float cropZoomFor(float maxDigitalZoom, int zoomLevel) {
        float fraction = (float) rangeLimit(zoomLevel) / MAX_ZOOM_LEVEL;
        return 1f + (Math.max(1f, maxDigitalZoom) - 1f) * fraction;
    }

    static Rect calculateCropRegion(Rect activeArray, float maxDigitalZoom, int zoomLevel) {
        float zoom = cropZoomFor(maxDigitalZoom, zoomLevel);
        int cropWidth = Math.max(1, (int) (activeArray.width() / zoom));
        int cropHeight = Math.max(1, (int) (activeArray.height() / zoom));
        int cropLeft = (activeArray.width() - cropWidth) / 2;
        int cropTop = (activeArray.height() - cropHeight) / 2;
        return new Rect(
                activeArray.left + cropLeft,
                activeArray.top + cropTop,
                activeArray.left + cropLeft + cropWidth,
                activeArray.top + cropTop + cropHeight);
    }

    /** Clamps a caller's zoom level into the range this backend publishes. */
    private static int rangeLimit(int zoomLevel) {
        return Math.max(0, Math.min(zoomLevel, MAX_ZOOM_LEVEL));
    }

    @Override
    public int getZoomLevel() {
        return mZoomLevel;
    }

    /**
     * The zoom scale, which is fixed rather than derived from the camera.
     *
     * <p>A device-dependent maximum made the scale mean something different on every phone, and
     * something different again from the CameraX backend, so a caller could not drive one zoom
     * control across both. The level is a position in the camera's range instead: 0 is the widest
     * lens it has and {@link #MAX_ZOOM_LEVEL} the longest.</p>
     */
    @Override
    public int getMaxZoomLevel() {
        return MAX_ZOOM_LEVEL;
    }

    @Override
    public boolean hasFlash() {
        try {
            CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(getActiveCamera());
            Boolean hasFlash = (characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));
            if (hasFlash == null) {
                return false;
            }
            return hasFlash;
        } catch (CameraAccessException | RuntimeException e) {
            Log.e(TAG, "Failed to query camera", e);
        }

        return false;
    }

    @Override
    public boolean isZoomSupported() {
        try {
            CameraCharacteristics characteristics =
                    mCameraManager.getCameraCharacteristics(getActiveCamera());
            if (zoomRatioRange(characteristics) != null) {
                return true;
            }
            Float maxDigitalZoom =
                    characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            return maxDigitalZoom != null && maxDigitalZoom > 1f;
        } catch (CameraAccessException | RuntimeException e) {
            Log.e(TAG, "Failed to query camera", e);
        }
        return false;
    }

    @Override
    public void pause() {
        if (mCaptureSession == null) {
            if (DEBUG) Log.w(TAG, "Cannot pause. No capture session.");
            return;
        }

        if (!mIsPaused) {
            try {
                mCaptureSession.stopRepeating();
            } catch (CameraAccessException | RuntimeException e) {
                Log.e(TAG, "Failed to pause the camera", e);
            }
            mIsPaused = true;
        } else {
            if (DEBUG) Log.w(TAG, "Cannot pause. Was never unpaused.");
        }
    }

    @Override
    public void resume() {
        try {
            if (mCaptureSession == null) {
                if (DEBUG) Log.w(TAG, "Cannot pause. No capture session.");
                return;
            }

            if (mActiveSession == null) {
                if (DEBUG) Log.w(TAG, "Cannot pause. No active session.");
                return;
            }

            if (mCameraDevice == null) {
                if (DEBUG) Log.w(TAG, "Cannot pause. No camera device.");
                return;
            }

            if (mIsPaused) {
                try {
                    mActiveSession.onAvailable(mCameraDevice, mCaptureSession);
                } catch (CameraAccessException | RuntimeException e) {
                    Log.e(TAG, "Failed to resume the camera", e);
                }
            } else {
                if (DEBUG) {
                    Log.w(TAG, "Cannot resume. Was never paused.");
                }
            }
        } finally {
            mIsPaused = false;
        }
    }

    @Override
    public boolean isPaused() {
        return mIsPaused;
    }

    @Override
    public void setQuality(CameraView.Quality quality) {
        super.setQuality(quality);

        // When quality changes, we need to update our session with the new dimensions
        if (mActiveSession != null) {
            if (mActiveSession instanceof PictureSession) {
                setSession(new PictureSession(this));
            } else if (mActiveSession instanceof VideoSession) {
                setSession(new VideoSession(this, ((VideoSession) mActiveSession).getFile()));
            } else if (mActiveSession instanceof StreamSession) {
                // Ignored. Streams cannot be restarted so we'll update quality once the stream ends.
            }
        }
    }

    @Override
    public void setFlash(CameraView.Flash flash) {
        super.setFlash(flash);

        // The strategy is carried by the repeating request, so the preview has to be reissued for
        // auto-exposure to converge under the new mode before the next capture.
        reissueRepeatingRequest("the flash strategy");
    }

    @Override
    public float getHorizontalFieldOfView() {
        return fieldOfView(true /* horizontal */);
    }

    @Override
    public float getVerticalFieldOfView() {
        return fieldOfView(false /* horizontal */);
    }

    /**
     * The angle of view, worked out from the sensor's size and the lens in front of it.
     *
     * <p>The shortest focal length is the one used: a camera that publishes several is either a
     * zoom lens, where the shortest is the widest view it can give, or a logical camera standing
     * for several physical ones, where the shortest belongs to the widest of them. Zoom is then
     * applied on top, since the request this session is issuing is what decides how much of that
     * view is actually being shown.</p>
     */
    private float fieldOfView(boolean horizontal) {
        try {
            CameraCharacteristics characteristics =
                    mCameraManager.getCameraCharacteristics(getActiveCamera());
            SizeF sensor = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            float[] focalLengths =
                    characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            if (sensor == null || focalLengths == null || focalLengths.length == 0) {
                return 0f;
            }
            // The first focal length, not the shortest: a zoom ratio of one means the lens the
            // camera uses by default, and that is the one this array leads with. On a logical
            // camera the shortest belongs to the ultra-wide, which the ratio reaches by going
            // below one - so taking it here and then applying the ratio would count it twice.
            float full = TransformMath.fieldOfViewDegrees(
                    horizontal ? sensor.getWidth() : sensor.getHeight(), focalLengths[0]);
            return TransformMath.zoomedFieldOfViewDegrees(
                    full, effectiveZoomRatio(characteristics));
        } catch (CameraAccessException | IllegalArgumentException | IllegalStateException error) {
            if (DEBUG) Log.d(TAG, "Could not work out the field of view", error);
            return 0f;
        }
    }

    /**
     * How much this session is actually zoomed, whichever way it is doing it.
     *
     * <p>Two mechanisms, as in setZoomLevel: the zoom ratio control where it exists, which can
     * cross between physical lenses, and a crop of the sensor's active array where it does not.
     * Read here rather than worked out again, so that the angle of view cannot disagree with the
     * request that produced the picture.</p>
     */
    private float effectiveZoomRatio(@NonNull CameraCharacteristics characteristics) {
        Range<Float> ratioRange = zoomRatioRange(characteristics);
        if (ratioRange != null) {
            return zoomRatioFor(ratioRange.getLower(), ratioRange.getUpper(), mZoomLevel);
        }
        Float maxDigitalZoom =
                characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        if (maxDigitalZoom == null || maxDigitalZoom <= 1f) {
            return 1f;
        }
        return cropZoomFor(maxDigitalZoom, mZoomLevel);
    }

    /** Rebuilds the repeating request, for a setting the request itself carries. */
    private void reissueRepeatingRequest(String what) {
        mHandler.post(() -> {
            if (mCameraDevice == null || mCaptureSession == null || mActiveSession == null
                    || mIsPaused) {
                return;
            }
            try {
                mActiveSession.onInvalidate(mCameraDevice, mCaptureSession);
            } catch (CameraAccessException | RuntimeException e) {
                Log.e(TAG, "Failed to apply " + what, e);
            }
        });
    }

    /**
     * Whether a picture can be taken while recording.
     *
     * <p>Camera2's mandatory stream combinations guarantee a preview, a recording and a still
     * together from LIMITED hardware upwards - that combination is what a camera app's
     * video-snapshot button uses - with the still no larger than the recording. LEGACY hardware
     * guarantees nothing of the sort, and a session it cannot configure costs the recording as
     * well as the picture, so there it records without one.</p>
     */
    @Override
    public boolean isCameraReady() {
        return mCaptureSession != null && mActiveSession != null && mCameraDevice != null;
    }

    @Override
    public boolean isColorSpaceSupported(CameraView.ColorSpace colorSpace) {
        if (colorSpace == CameraView.ColorSpace.SRGB) {
            return true;
        }
        if (Build.VERSION.SDK_INT < 34) {
            // The session could not be told about a colour space before API 34, so asking for one
            // would silently produce sRGB anyway.
            return false;
        }
        android.graphics.ColorSpace.Named named = namedColorSpace(colorSpace);
        if (named == null) {
            return false;
        }
        try {
            android.hardware.camera2.params.ColorSpaceProfiles profiles =
                    mCameraManager.getCameraCharacteristics(getActiveCamera())
                            .get(CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES);
            return profiles != null
                    && profiles.getSupportedColorSpaces(ImageFormat.JPEG).contains(named);
        } catch (CameraAccessException | RuntimeException error) {
            if (DEBUG) Log.d(TAG, "Camera cannot report its colour space profiles", error);
            return false;
        }
    }

    @Override
    public void setColorSpace(CameraView.ColorSpace colorSpace) {
        CameraView.ColorSpace before = getColorSpace();
        super.setColorSpace(colorSpace);
        if (getColorSpace() != before) {
            // The colour space belongs to the session, not to a request, so it only takes hold
            // when the session is built again.
            restartActiveSession();
        }
    }

    /**
     * The platform's name for a colour space, or null for one it does not have.
     *
     * <p>Only callable once the platform is known to be new enough: the return type itself only
     * exists from API 26, so a caller that has not checked risks failing to load rather than
     * getting a null.</p>
     */
    @RequiresApi(34)
    @Nullable
    private static android.graphics.ColorSpace.Named namedColorSpace(
            CameraView.ColorSpace colorSpace) {
        switch (colorSpace) {
            case DISPLAY_P3:
                return android.graphics.ColorSpace.Named.DISPLAY_P3;
            case SRGB:
                return android.graphics.ColorSpace.Named.SRGB;
            default:
                return null;
        }
    }

    @Override
    public boolean isPictureFormatSupported(CameraView.PictureFormat format) {
        if (format != CameraView.PictureFormat.ULTRA_HDR) {
            return super.isPictureFormatSupported(format);
        }
        if (Build.VERSION.SDK_INT < 34) {
            return false;
        }
        try {
            return writesUltraHdr(
                    mCameraManager.getCameraCharacteristics(getActiveCamera()), getColorSpace());
        } catch (CameraAccessException | RuntimeException error) {
            if (DEBUG) Log.d(TAG, "Camera cannot report whether it writes Ultra HDR", error);
            return false;
        }
    }

    @Override
    public void setPictureFormat(CameraView.PictureFormat format) {
        CameraView.PictureFormat before = getPictureFormat();
        super.setPictureFormat(format);
        if (getPictureFormat() != before) {
            // The format belongs to the still's reader, which the session is built around.
            restartActiveSession();
        }
    }

    /**
     * Whether a camera writes JPEG_R, in the colour space asked for. The gain map is composed by
     * the camera, so one that does not list the format cannot produce it.
     */
    @RequiresApi(34)
    @VisibleForTesting
    static boolean writesUltraHdr(
            CameraCharacteristics characteristics, CameraView.ColorSpace colorSpace) {
        StreamConfigurationMap map =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.JPEG_R);
        if (sizes == null || sizes.length == 0) {
            return false;
        }
        if (colorSpace == CameraView.ColorSpace.SRGB) {
            return true;
        }
        android.graphics.ColorSpace.Named named = namedColorSpace(colorSpace);
        android.hardware.camera2.params.ColorSpaceProfiles profiles =
                characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES);
        return named != null && profiles != null
                && profiles.getSupportedColorSpaces(ImageFormat.JPEG_R).contains(named);
    }

    /** Rebuilds the session, for a setting the session itself carries. */
    private void restartActiveSession() {
        mHandler.post(() -> {
            Session session = mActiveSession;
            if (mCameraDevice == null || session == null || mIsPaused) {
                return;
            }
            setSession(session instanceof PictureSession && !(session instanceof VideoSession)
                    ? new PictureSession(this) : session);
        });
    }

    @Override
    public boolean isSnapshotWhileRecordingSupported() {
        return isRecordingSnapshotSupported();
    }

    boolean isRecordingSnapshotSupported() {
        try {
            Integer level = mCameraManager.getCameraCharacteristics(getActiveCamera())
                    .get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
            return level != null
                    && level != CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY;
        } catch (CameraAccessException | RuntimeException error) {
            if (DEBUG) Log.d(TAG, "Camera cannot report its hardware level", error);
            return false;
        }
    }

    @Override
    public boolean isFaceDetectionSupported() {
        return faceDetectMode() != null;
    }

    @Override
    protected boolean setFaceDetectionEnabled(boolean enabled) {
        if (!enabled) {
            boolean wasEnabled = mFaceDetectMode != null;
            mFaceDetectMode = null;
            if (wasEnabled) {
                reissueRepeatingRequest("the end of face detection");
            }
            return true;
        }
        Integer mode = faceDetectMode();
        if (mode == null) {
            return false;
        }
        mFaceDetectMode = mode;
        reissueRepeatingRequest("face detection");
        return true;
    }

    /**
     * The face detection mode the repeating request should ask for, or null for none.
     *
     * <p>FULL adds eye and mouth positions and an identity that persists while a face is tracked;
     * SIMPLE gives only the bounds and a score. Both are computed by hardware that is already
     * finding faces to focus and expose for, so the richer mode is preferred where it exists.</p>
     */
    @Nullable
    private Integer faceDetectMode() {
        try {
            CameraCharacteristics characteristics =
                    mCameraManager.getCameraCharacteristics(getActiveCamera());
            int[] modes = characteristics.get(
                    CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);
            Integer maxFaces =
                    characteristics.get(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT);
            if (modes == null || maxFaces == null || maxFaces <= 0) {
                return null;
            }
            boolean simple = false;
            for (int mode : modes) {
                if (mode == CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_FULL) {
                    return CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_FULL;
                }
                if (mode == CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_SIMPLE) {
                    simple = true;
                }
            }
            return simple ? CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_SIMPLE : null;
        } catch (CameraAccessException | RuntimeException error) {
            if (DEBUG) Log.d(TAG, "Camera cannot report its face detection modes", error);
            return null;
        }
    }

    /** The mode the repeating request is carrying, read by the sessions that build it. */
    @Nullable
    Integer getFaceDetectMode() {
        return mFaceDetectMode;
    }

    @Override
    public boolean isFocusSupported() {
        try {
            return supportsFocus(getActiveCamera());
        } catch (CameraAccessException | RuntimeException e) {
            if (DEBUG) Log.d(TAG, "Unable to ask whether this camera can focus", e);
            return false;
        }
    }

    /** Whether a focus request is waiting on the camera to say what came of it. */
    boolean isFocusPending() {
        return mIsFocusPending;
    }

    /**
     * Reads the camera's focus state out of a capture result, and reports it once it settles.
     *
     * <p>The preview runs continuous autofocus, so what a tap does is move the region the camera
     * is already converging on. That shows up as the passive states rather than the locked ones a
     * triggered focus would produce, and both are the camera saying it has finished moving the
     * lens - so both answer the request, while the scanning states are it still working.</p>
     */
    void onFocusCaptured(@NonNull CaptureResult result) {
        if (!mIsFocusPending) {
            return;
        }
        Integer state = result.get(CaptureResult.CONTROL_AF_STATE);
        if (state == null) {
            // A camera that publishes no focus state will never answer, so there is nothing to
            // wait for; the view's timeout reports the request unresolved.
            return;
        }
        switch (state) {
            case CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED:
            case CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED:
                mIsFocusPending = false;
                onFocusCompleted(true);
                break;
            case CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED:
            case CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED:
                mIsFocusPending = false;
                onFocusCompleted(false);
                break;
            default:
                // Inactive or still scanning: the lens is in motion and the answer is not in yet.
                break;
        }
    }

    /**
     * Translates a capture result's faces and hands them to the view, on the main thread.
     *
     * <p>Called for every completed preview frame while face detection is on, so a camera that
     * sees nothing is not reported over and over: an empty result is passed on once, to clear
     * whatever was drawn, and then skipped until a face comes back.</p>
     */
    void onFacesCaptured(@NonNull CaptureResult result) {
        android.hardware.camera2.params.Face[] detected =
                result.get(CaptureResult.STATISTICS_FACES);
        if (detected == null) {
            detected = new android.hardware.camera2.params.Face[0];
        }
        if (detected.length == 0 && mLastFaceCount == 0) {
            return;
        }
        mLastFaceCount = detected.length;

        List<Face> faces = new ArrayList<>(detected.length);
        for (android.hardware.camera2.params.Face face : detected) {
            Rect bounds = face.getBounds();
            if (bounds == null) {
                continue;
            }
            faces.add(Face.create(bounds, face.getScore(), face.getId(),
                    face.getLeftEyePosition(), face.getRightEyePosition(),
                    face.getMouthPosition()));
        }
        onFacesDetected(faces, faceToViewTransform());
    }

    /**
     * Maps face bounds onto the view.
     *
     * <p>Faces are reported in active-array coordinates, so the region the preview is actually
     * showing is the crop region when zoom is being done by cropping, and the whole array when the
     * camera is handling zoom itself through CONTROL_ZOOM_RATIO. Rotation is the sensor's own
     * orientation less the display's, which is what the preview transform applies.</p>
     */
    @NonNull
    private Matrix faceToViewTransform() {
        Rect region = mActiveSession == null ? null : mActiveSession.getCropRegion();
        if (region == null) {
            region = activeArraySize();
        }
        if (region == null) {
            return new Matrix();
        }
        return TransformMath.centerCrop(new RectF(region),
                TransformMath.within360(getSensorOrientation() - getDisplayRotation()),
                false /* the Camera2 preview is not mirrored */,
                getView().getWidth(), getView().getHeight());
    }

    @Nullable
    private Rect activeArraySize() {
        try {
            return mCameraManager.getCameraCharacteristics(getActiveCamera())
                    .get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        } catch (CameraAccessException | RuntimeException error) {
            if (DEBUG) Log.d(TAG, "Camera cannot report its active array", error);
            return null;
        }
    }

    @Override
    public void takePicture(File file) {
        if (file == null) {
            throw new IllegalArgumentException("Picture destination cannot be null");
        }
        if (mCaptureSession == null || mActiveSession == null || mCameraDevice == null) {
            Log.w(TAG, "Cannot take picture before Camera2 preview is ready");
            onImageFailed();
            return;
        }

        if (mActiveSession instanceof PictureSession) {
            PictureSession pictureSession = (PictureSession) mActiveSession;
            pictureSession.takePicture(file, mCameraDevice, mCaptureSession);
        } else {
            onImageFailed();
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @Override
    public void startRecording(File file) {
        if (file == null) {
            throw new IllegalArgumentException("Video destination cannot be null");
        }
        if (mCameraDevice == null || !mIsOpen) {
            onVideoFailed();
            return;
        }
        if (mIsRecording) {
            Log.w(TAG, "Ignoring duplicate Camera2 recording request");
            return;
        }

        mIsRecording = true;
        setSession(new VideoSession(this, file));
    }

    @Override
    public void stopRecording() {
        mIsRecording = false;
        setSession(new PictureSession(this));
    }

    @Override
    public boolean isRecording() {
        return mIsRecording;
    }

    @Override
    public void showVideoConfirmation(File file) {
        super.showVideoConfirmation(file);
        mIsRecording = false;
    }

    @Override
    public void onVideoFailed() {
        super.onVideoFailed();
        mIsRecording = false;
    }

    @Override
    protected void attachSurface(VideoRecorder.SurfaceProvider surfaceProvider) {
        setSession(new StreamSession(this, surfaceProvider));
    }

    @Override
    protected void detachSurface(VideoRecorder.SurfaceProvider surfaceProvider) {
        setSession(new PictureSession(this));
    }

    /**
     * Carried by the still-capture session rather than a session of its own, so a picture can
     * still be taken. A recording session leaves it out, and the next still session picks it up.
     */
    @Override
    protected void attachAnalysisSurface(VideoRecorder.SurfaceProvider surfaceProvider) {
        if (mAnalysisProviders.add(surfaceProvider)) {
            reconfigureStillSession();
        }
    }

    @Override
    protected void detachAnalysisSurface(VideoRecorder.SurfaceProvider surfaceProvider) {
        if (mAnalysisProviders.remove(surfaceProvider)) {
            reconfigureStillSession();
        }
    }

    /** The readers a still-capture session carries. See {@link #attachAnalysisSurface}. */
    @NonNull
    List<VideoRecorder.SurfaceProvider> getAnalysisProviders() {
        return new ArrayList<>(mAnalysisProviders);
    }

    /** Rebuilds the still-capture session, if that is the one running, for a change of readers. */
    private void reconfigureStillSession() {
        Session session = mRegisteringSession != null ? mRegisteringSession : mActiveSession;
        if (mCameraDevice != null && session instanceof PictureSession
                && !(session instanceof VideoSession)) {
            setSession(new PictureSession(this));
        }
    }

    /** Frames come from {@link #attachAnalysisSurface}, and the subject needs a region. */
    @Override
    public boolean isSubjectTrackingSupported() {
        if (mCameraDevice == null) {
            return false;
        }
        try {
            CameraCharacteristics characteristics =
                    mCameraManager.getCameraCharacteristics(getActiveCamera());
            Integer focusRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
            Integer exposureRegions =
                    characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
            return (focusRegions != null && focusRegions > 0)
                    || (exposureRegions != null && exposureRegions > 0);
        } catch (CameraAccessException | RuntimeException e) {
            if (DEBUG) Log.d(TAG, "Unable to ask whether this camera can track", e);
            return false;
        }
    }

    /**
     * Moves the regions continuous autofocus and auto-exposure work within, which follows the
     * subject without restarting a focus search each time. Unlike {@link #focus}, nothing waits
     * on the camera to converge.
     */
    @Override
    protected void setSubjectArea(@Nullable Rect area) {
        if (mCaptureSession == null || mActiveSession == null || mCameraDevice == null) {
            return;
        }
        try {
            MeteringRectangle region = null;
            if (area != null) {
                Rect sensorArea = toSensorRegion(area);
                if (sensorArea == null) {
                    return;
                }
                region = new MeteringRectangle(sensorArea, MeteringRectangle.METERING_WEIGHT_MAX);
            }
            mActiveSession.setMeteringRectangle(region);
            if (!mIsPaused) {
                mActiveSession.onInvalidate(mCameraDevice, mCaptureSession);
            }
        } catch (CameraAccessException | RuntimeException e) {
            Log.w(TAG, "Failed to move the subject's regions", e);
        }
    }

    /**
     * Maps an area from -1000 to 1000 across what the camera shows onto the active array, where
     * its regions are given. A crop zoom shows only the crop, so the area is mapped into that; a
     * zoom ratio leaves the regions' coordinates covering what is shown, so there is nothing to do.
     */
    @Nullable
    private Rect toSensorRegion(@NonNull Rect area) throws CameraAccessException {
        CameraCharacteristics characteristics =
                mCameraManager.getCameraCharacteristics(getActiveCamera());
        Rect activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (activeArray == null) {
            return null;
        }
        Rect shown = new Rect(0, 0, activeArray.width(), activeArray.height());
        Rect crop = mActiveSession == null ? null : mActiveSession.getCropRegion();
        boolean zoomRatio = Build.VERSION.SDK_INT >= 30
                && mActiveSession != null && mActiveSession.getZoomRatio() != null;
        if (crop != null && !zoomRatio) {
            shown = new Rect(crop.left - activeArray.left, crop.top - activeArray.top,
                    crop.right - activeArray.left, crop.bottom - activeArray.top);
        }
        return toRegion(area, shown);
    }

    /** Maps an area from -1000 to 1000 onto a rectangle. */
    @VisibleForTesting
    @NonNull
    static Rect toRegion(@NonNull Rect area, @NonNull Rect shown) {
        Rect region = new Rect(
                shown.left + (area.left + 1000) * shown.width() / 2000,
                shown.top + (area.top + 1000) * shown.height() / 2000,
                shown.left + (area.right + 1000) * shown.width() / 2000,
                shown.top + (area.bottom + 1000) * shown.height() / 2000);
        if (region.isEmpty()) {
            region.right = region.left + 1;
            region.bottom = region.top + 1;
        }
        return region;
    }

    void transformPreview(int previewWidth, int previewHeight) throws CameraAccessException {
        int viewWidth = getWidth();
        int viewHeight = getHeight();
        if (previewWidth <= 0 || previewHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            if (DEBUG) Log.w(TAG, "Skipping preview transform for zero-sized surface");
            return;
        }
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
        int rotation = -displayOrientation;

        matrix.setScale(scaleX, scaleY);
        matrix.postTranslate(translateX, translateY);
        matrix.postRotate(rotation, (int) Math.ceil(viewWidth / 2d), (int) Math.ceil(viewHeight / 2d));

        if (DEBUG) {
            Log.d(TAG, format("transformPreview: displayOrientation=%s, cameraOrientation=%s, "
                            + "viewWidth=%s, viewHeight=%s, viewAspectRatio=%s, previewWidth=%s, previewHeight=%s, previewAspectRatio=%s, "
                            + "newWidth=%s, newHeight=%s, scaleX=%s, scaleY=%s, scale=%s, "
                            + "translateX=%s, translateY=%s, rotation=%s",
                    displayOrientation, cameraOrientation, viewWidth, viewHeight,
                    ((float) viewHeight / (float) viewWidth), previewWidth, previewHeight, aspectRatio,
                    newWidth, newHeight, scaleX, scaleY, scale, translateX, translateY, rotation));
        }

        setTransform(matrix);
    }

    @Override
    protected int getRelativeCameraOrientation() {
        try {
            return getRelativeImageOrientation(getDisplayRotation(), getSensorOrientation(getActiveCamera()), isUsingFrontFacingCamera(), false);
        } catch (CameraAccessException | RuntimeException e) {
            Log.e(TAG, "Failed to query camera", e);
            return 0;
        }
    }

    @NonNull
    String getActiveCamera() throws CameraAccessException {
        String[] cameraIds = getAvailableCameraIds();
        if (mActiveCamera != null) {
            for (String cameraId : cameraIds) {
                if (cameraId.equals(mActiveCamera)) {
                    return cameraId;
                }
            }
            Log.w(TAG, "Previously selected camera is no longer available: " + mActiveCamera);
            mActiveCamera = null;
        }
        String backFacing = preferredCamera(cameraIds, CameraCharacteristics.LENS_FACING_BACK);
        if (backFacing != null) {
            return backFacing;
        }
        String anyCamera = preferredCamera(cameraIds, null);
        return anyCamera == null ? cameraIds[0] : anyCamera;
    }

    /**
     * The camera we would rather open, among those matching a lens facing.
     *
     * <p>Ranked by {@link #devicePreference}, so a built-in sensor wins over a USB webcam or a
     * virtual camera that happens to be listed first or to report the same lens facing.</p>
     *
     * @param lensFacing the facing to match, or null for any
     */
    @Nullable
    private String preferredCamera(@NonNull String[] cameraIds, @Nullable Integer lensFacing) {
        String best = null;
        int bestPreference = Integer.MAX_VALUE;
        for (String cameraId : cameraIds) {
            try {
                if (lensFacing != null) {
                    Integer facing = mCameraManager.getCameraCharacteristics(cameraId)
                            .get(CameraCharacteristics.LENS_FACING);
                    if (facing == null || !facing.equals(lensFacing)) {
                        continue;
                    }
                }
                int preference = devicePreference(cameraId);
                if (preference < bestPreference) {
                    best = cameraId;
                    bestPreference = preference;
                }
            } catch (CameraAccessException | RuntimeException error) {
                Log.w(TAG, "Unable to inspect camera " + cameraId, error);
            }
        }
        return best;
    }

    /**
     * How much we prefer a camera when nothing has been chosen yet, lowest first.
     *
     * <p>INFO_DEVICE_TYPE arrives in API 37 and is the first way to tell a built-in sensor from a
     * USB webcam or a virtual camera. Before it, every entry in the list looked alike, so a
     * plugged-in webcam reporting itself as back-facing could become the default camera. External
     * and virtual cameras stay in the list for {@link #toggleCamera()} to reach; they just stop
     * being picked by default.</p>
     */
    private int devicePreference(@NonNull String cameraId) throws CameraAccessException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) {
            return 0;
        }
        Integer deviceType = mCameraManager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.INFO_DEVICE_TYPE);
        if (deviceType == null) {
            return 1;
        }
        switch (deviceType) {
            case CameraMetadata.INFO_DEVICE_TYPE_BUILT_IN:
                return 0;
            case CameraMetadata.INFO_DEVICE_TYPE_UNKNOWN:
                return 1;
            case CameraMetadata.INFO_DEVICE_TYPE_EXTERNAL:
            case CameraMetadata.INFO_DEVICE_TYPE_VIRTUAL:
            default:
                return 2;
        }
    }

    private String[] getAvailableCameraIds() throws CameraAccessException {
        String[] cameraIds = mCameraManager.getCameraIdList();
        if (cameraIds.length == 0) {
            throw new IllegalStateException("No cameras are available on this device");
        }
        return cameraIds;
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

    protected int getSensorOrientation() {
        try {
            return getSensorOrientation(getActiveCamera());
        } catch (CameraAccessException | RuntimeException e) {
            return 0;
        }
    }

    Handler getHandler() {
        return mHandler;
    }

    @Override
    public Parcelable onSaveInstanceState() {
        Bundle state = new Bundle();
        state.putString(EXTRA_DEVICE_ID, mActiveCamera);
        return state;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        if (state instanceof Bundle) {
            mActiveCamera = ((Bundle) state).getString(EXTRA_DEVICE_ID);
        }
    }

    @Override
    public void onLayoutChanged() {
        if (mActiveSession != null) {
            try {
                mActiveSession.onLayoutChanged();
            } catch (CameraAccessException | RuntimeException e) {
                Log.e(TAG, "Failed to adjust the camera after a layout change", e);
            }
        }
    }

    /**
     * A session has multiple surfaces for the camera to draw to.
     */
    interface Session {
        void initialize(@NonNull StreamConfigurationMap map) throws CameraAccessException;
        void onLayoutChanged() throws CameraAccessException;
        @NonNull List<Surface> getSurfaces();
        void setMeteringRectangle(@Nullable MeteringRectangle meteringRectangle);
        @Nullable MeteringRectangle getMeteringRectangle();
        void setCropRegion(@Nullable Rect region);
        @Nullable Rect getCropRegion();
        void setZoomRatio(@Nullable Float zoomRatio);
        @Nullable Float getZoomRatio();
        void onAvailable(@NonNull CameraDevice cameraDevice, @NonNull CameraCaptureSession session) throws CameraAccessException;
        void onInvalidate(@NonNull CameraDevice cameraDevice, @NonNull CameraCaptureSession session) throws CameraAccessException;
        void close();
    }
}
