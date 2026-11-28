package com.xlythe.view.camera.v2;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ColorSpace;
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
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.ColorSpaceProfiles;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.util.SizeF;
import android.view.Surface;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.DngWriter;
import com.xlythe.view.camera.ExifCaptureParameters;
import com.xlythe.view.camera.Face;
import com.xlythe.view.camera.FrameStream;
import com.xlythe.view.camera.ICameraModule;
import com.xlythe.view.camera.SegmentationAnalyzer;
import com.xlythe.view.camera.TransformMath;
import com.xlythe.view.camera.ZoomMath;
import com.xlythe.view.camera.gl.CameraTextureProvider;
import com.xlythe.view.camera.gl.FrameRenderThread;
import com.xlythe.view.camera.gl.FrameRenderer;
import com.xlythe.view.camera.stream.MotionPhotoRecorder;
import com.xlythe.view.camera.stream.VideoRecorder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
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
@RequiresApi(21)
@RestrictTo(RestrictTo.Scope.LIBRARY)
public class Camera2Module extends ICameraModule {
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
            Size stillSize = largestStillSize(
                    mCameraManager.getCameraCharacteristics(getActiveCamera()));
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
     * The largest still a session may put beside the preview: the unbinned one where a MAX
     * picture would use it, so that the probe asks about the pair the session configures.
     */
    @VisibleForTesting
    @Nullable
    Size largestStillSize(@NonNull CameraCharacteristics characteristics) {
        StreamConfigurationMap map =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size stillSize = map == null ? null
                : largestWithin(Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)), null);
        if (Build.VERSION.SDK_INT >= 31 && getQuality() == CameraView.Quality.MAX) {
            StreamConfigurationMap unbinned = maximumResolutionMap(characteristics);
            Size[] sizes = unbinned == null ? null : unbinned.getOutputSizes(ImageFormat.JPEG);
            Size largest = sizes == null ? null : largestWithin(Arrays.asList(sizes), null);
            if (largest != null && (stillSize == null || exceeds(largest, stillSize))) {
                stillSize = largest;
            }
        }
        return stillSize;
    }

    /**
     * The stream configurations of this camera's sensor read out unbinned, or null when it has
     * none a still can use. See {@link #maximumResolutionMap}.
     */
    @Nullable
    StreamConfigurationMap getMaximumResolutionMap() {
        if (Build.VERSION.SDK_INT < 31) {
            return null;
        }
        try {
            return maximumResolutionMap(
                    mCameraManager.getCameraCharacteristics(getActiveCamera()));
        } catch (CameraAccessException | RuntimeException error) {
            if (DEBUG) Log.d(TAG, "Camera cannot report its unbinned sizes", error);
            return null;
        }
    }

    /**
     * A sensor that combines neighbouring pixels by default can read every pixel out instead,
     * through a second stream configuration map.
     *
     * <p>The map can be present on a camera without the ultra high resolution capability, as a
     * placeholder whose stream combinations nothing guarantees, so the capability decides.</p>
     */
    @RequiresApi(31)
    @VisibleForTesting
    @Nullable
    static StreamConfigurationMap maximumResolutionMap(
            @NonNull CameraCharacteristics characteristics) {
        int[] capabilities =
                characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (capabilities == null) {
            return null;
        }
        for (int capability : capabilities) {
            if (capability
                    == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR) {
                return characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION);
            }
        }
        return null;
    }

    /**
     * Moves a region of the active array onto the unbinned sensor's, which is what a request in
     * maximum resolution mode reads its regions against. Null when either array is unknown,
     * since a region left in the binned coordinates lands on the wrong part of the scene.
     */
    @RequiresApi(31)
    @Nullable
    Rect toMaximumResolution(@Nullable Rect region) {
        if (region == null) {
            return null;
        }
        try {
            CameraCharacteristics characteristics =
                    mCameraManager.getCameraCharacteristics(getActiveCamera());
            return scaleRegion(region,
                    characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
                    characteristics.get(
                            CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION));
        } catch (CameraAccessException | RuntimeException error) {
            if (DEBUG) Log.d(TAG, "Camera cannot report its unbinned array", error);
            return null;
        }
    }

    /** Scales a region from one array onto another, or null when either is unknown or empty. */
    @VisibleForTesting
    @Nullable
    static Rect scaleRegion(@NonNull Rect region, @Nullable Rect from, @Nullable Rect to) {
        if (from == null || to == null || from.width() <= 0 || from.height() <= 0) {
            return null;
        }
        float scaleX = (float) to.width() / from.width();
        float scaleY = (float) to.height() / from.height();
        return new Rect(
                to.left + Math.round((region.left - from.left) * scaleX),
                to.top + Math.round((region.top - from.top) * scaleY),
                to.left + Math.round((region.right - from.left) * scaleX),
                to.top + Math.round((region.bottom - from.top) * scaleY));
    }

    /**
     * This is how we'll talk to the camera.
     */
    @NonNull
    private final CameraManager mCameraManager;

    /**
     * The GL pipeline the camera frames go through, or null when nothing is drawing them.
     *
     * <p>Only up while a renderer or a texture provider is set, because it costs a thread, an
     * EGL context and a texture, and because routing frames through it changes what the camera
     * is asked to write to - a camera that nothing is drawing should go straight to the
     * preview as it always has.</p>
     */
    @Nullable
    private FrameRenderThread mEffect;

    /** Set once GL has failed here, so the camera carries on without offering effects again. */
    private boolean mGlUnavailable;

    /**
     * Whether the recording being made, or the last one made, is mirrored in the file.
     *
     * <p>Decided by each recording rather than once for the camera, because a front recording
     * is only mirrored when it went through GL. It outlives the recording on purpose: the
     * confirmation that plays the file back asks after the recording has stopped.</p>
     */
    private boolean mRecordingMirrored;

    /**
     * Whether frames can be put through GL on the way out.
     *
     * <p>True, which it was not: the frames of a Camera2 session go to the surfaces in that
     * session, and those surfaces can be the renderer's as easily as the preview's. What makes
     * it work for the photograph as well as the preview is that a still is taken through a
     * second input at the camera's own resolution rather than off the preview stream - an
     * effect that drew the preview into the photograph would quietly turn a twelve megapixel
     * camera into a two megapixel one.</p>
     */
    @Override
    public boolean isFrameEffectSupported() {
        return !mGlUnavailable;
    }

    @Override
    public void setFrameRenderer(@Nullable FrameRenderer renderer) {
        super.setFrameRenderer(renderer);
        mHandler.post(this::applyFrameEffect);
    }

    @Override
    public void setCameraTextureProvider(@Nullable CameraTextureProvider provider) {
        super.setCameraTextureProvider(provider);
        mHandler.post(this::applyFrameEffect);
    }

    /**
     * Brings the pipeline into line with what the caller has asked for.
     *
     * <p>Rebuilds the session only when the pipeline comes up or goes down, because that is
     * when the surfaces the camera writes to change. Changing what is drawn is a field on a
     * renderer that is already running, and reconfiguring the camera for it would drop frames
     * for nothing.</p>
     */
    private void applyFrameEffect() {
        if (!isFrameEffectSupported()) {
            // A subclass that draws the frames itself, which the AR backend does.
            return;
        }
        boolean wanted = getFrameRenderer() != null || getCameraTextureProvider() != null;
        boolean running = mEffect != null;
        if (wanted == running) {
            if (mEffect != null) {
                mEffect.setFrameRenderer(getFrameRenderer());
            }
            return;
        }
        if (wanted) {
            FrameRenderThread effect = new FrameRenderThread("CameraViewCamera2Renderer");
            effect.setCameraTextureProvider(getCameraTextureProvider());
            effect.setFrameRenderer(getFrameRenderer());
            if (!effect.start()) {
                // A device whose driver will not give us a context is a device the camera still
                // has to work on, so the effect is refused and not offered again.
                mGlUnavailable = true;
                return;
            }
            mEffect = effect;
        } else {
            FrameRenderThread effect = mEffect;
            mEffect = null;
            if (effect != null) {
                effect.stop();
            }
        }
        restartActiveSession();
    }

    /** The pipeline, for the sessions that have to point the camera at it. */
    @Nullable
    FrameRenderThread getEffect() {
        return mEffect;
    }

    /** Whether GL is still worth trying here, which it is until it has failed once. */
    @MainThread
    boolean isGlAvailable() {
        return !mGlUnavailable;
    }

    /**
     * Brings up a renderer for a recording to have to itself, while no effect is running.
     *
     * <p>A failure here counts against GL as a failed effect does, so neither a later recording
     * nor an effect tries it again.</p>
     *
     * @return the renderer, running, or null when GL will not run here
     */
    @MainThread
    @Nullable
    FrameRenderThread startRecordingRenderer() {
        if (mGlUnavailable) {
            return null;
        }
        FrameRenderThread renderer = new FrameRenderThread("CameraViewCamera2Recorder");
        if (!renderer.start()) {
            mGlUnavailable = true;
            return null;
        }
        return renderer;
    }

    /** Called by a recording once it knows whether its file is mirrored. */
    @MainThread
    void setRecordingMirrored(boolean mirrored) {
        mRecordingMirrored = mirrored;
    }

    /**
     * True for a front recording that went through GL, which mirrors it as its preview is
     * mirrored, and false for one that could not, which is left for the confirmation to flip.
     */
    @Override
    public boolean isFrontVideoMirrored() {
        return mRecordingMirrored;
    }

    @Override
    protected boolean isDrawingFrames() {
        return mEffect != null;
    }

    /** The frames being segmented, or null when nothing is. */
    @Nullable
    private FrameStream mSegmentationStream;

    /** The model the segmented frames go to, or null when nothing is segmenting. */
    @Nullable
    private SegmentationAnalyzer mSegmentationAnalyzer;

    /**
     * Starts segmenting frames, through a stream of this session's own.
     *
     * <p>Camera2 has no analysis use case to borrow, so the frames come from a
     * {@link FrameStream} - the same one an app would use for its own detection - which adds a
     * reader to the session alongside whatever else is in it. That is a second stream, not a
     * third: while an effect is running the camera writes one stream into the renderer, so the
     * reader brings the total to two.</p>
     *
     * <p>It takes the frames the way a caller's own analyzer would, so the two cannot both run.
     * Said here rather than discovered later: an app that wants to segment and scan barcodes at
     * once needs its own model for one of them.</p>
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    @Override
    protected boolean startSegmentation(CameraView.SegmentationListener listener) {
        if (listener == null) {
            throw new NullPointerException("listener cannot be null");
        }
        if (!isFrameAnalysisSupported()) {
            return false;
        }
        stopSegmentation();
        SegmentationAnalyzer analyzer;
        try {
            analyzer = new SegmentationAnalyzer(listener, mHandler::post,
                    isUsingFrontFacingCamera());
        } catch (RuntimeException | LinkageError error) {
            // The model is delivered by Play services and its client is built here, so a device
            // without it fails at construction. Reported rather than thrown: a camera library
            // may not take an app down because a vision dependency is missing.
            Log.w(TAG, "This device cannot segment", error);
            return false;
        }
        try {
            FrameStream stream = FrameStream.forLibrary(
                    getCanvas(), new FrameStream.Params(), analyzer);
            stream.start();
            mSegmentationStream = stream;
            mSegmentationAnalyzer = analyzer;
            return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to start segmenting frames", error);
            analyzer.close();
            return false;
        }
    }

    @Override
    protected void stopSegmentation() {
        FrameStream stream = mSegmentationStream;
        SegmentationAnalyzer analyzer = mSegmentationAnalyzer;
        mSegmentationStream = null;
        mSegmentationAnalyzer = null;
        if (stream != null) {
            stream.close();
        }
        if (analyzer != null) {
            // After the stream, so no frame arrives for a model that has been released.
            analyzer.close();
        }
    }

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

    /**
     * The zoom, as a ratio in the camera's own terms. Held to the active camera's range, and
     * written into every session's requests, so it survives a session change, a lens switch and a
     * reopen.
     */
    private float mZoomRatio = 1f;

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

    /** How long a capture waits for the session its format needs before it is failed. */
    private static final long PENDING_PICTURE_TIMEOUT_MILLIS = 5000;

    /** True between a picture format change and the session built for it being configured. */
    private boolean mAwaitingFormatSession;

    /** A capture waiting for that session, or null. */
    @Nullable
    private File mPendingPictureFile;

    /** Fails {@link #mPendingPictureFile} if the session never configures. */
    @Nullable
    private Runnable mPendingPictureTimeout;

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
                        clampExposureCompensation();
                        // Before the first request goes out, so the preview starts at the zoom it
                        // was asked for rather than showing a frame at 1x first.
                        clampZoomToActiveCamera();
                        applyZoom(session);
                        if (!mIsPaused) {
                            session.onAvailable(mCameraDevice, mCaptureSession);
                        }
                        // The session is configured and targeted, which is the first moment a
                        // capture can succeed.
                        onCameraReady();
                        mAwaitingFormatSession = false;
                        capturePendingPicture();
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
                    if (session instanceof PictureSession
                            && ((PictureSession) session).hasMotionSurface()) {
                        // No combination of outputs is promised beyond the tables, and some
                        // cameras turn down one that is. Pictures matter more than their clips.
                        Log.w(TAG, "The camera will not feed a motion photo beside its preview"
                                + " and still, so pictures are taken without clips");
                        refuseMotionPhotos();
                        cameraCaptureSession.close();
                        mRegisteringSession = null;
                        closeSession(session);
                        setSession(new PictureSession(Camera2Module.this));
                        return;
                    }
                    mAwaitingFormatSession = false;
                    cancelPendingPicture();
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
        ColorSpace.Named named = namedColorSpace(getColorSpace());
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
        // Brought back up if a renderer is still set, since close() took it down: an app that
        // sets a renderer once and then rotates the screen expects it to still be there.
        if (mEffect == null && !mGlUnavailable) {
            applyFrameEffect();
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
        mAwaitingFormatSession = false;
        cancelPendingPicture();
        stopSegmentation();
        FrameRenderThread effect = mEffect;
        mEffect = null;
        if (effect != null) {
            // Before the session, because it is drawing into surfaces the session owns and
            // stopping it blocks until it has.
            effect.stop();
        }
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
        clampZoomToActiveCamera();
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
    public void setZoomRatio(float zoomRatio) {
        Range<Float> range = zoomRange();
        float clamped = ZoomMath.clamp(zoomRatio, range.getLower(), range.getUpper());
        if (clamped == mZoomRatio) {
            // Every session already carries it, which spares a pinch held at either end of the
            // range from reissuing the same request on every frame.
            return;
        }
        mZoomRatio = clamped;
        onZoomChanged(clamped);

        if (mCameraDevice == null || mCaptureSession == null || mActiveSession == null) {
            if (DEBUG) Log.w(TAG, "No active session available");
            return;
        }

        try {
            applyZoom(mActiveSession);
            if (!mIsPaused) {
                mActiveSession.onInvalidate(mCameraDevice, mCaptureSession);
            }
        } catch (CameraAccessException | RuntimeException e) {
            Log.e(TAG, "Failed to zoom", e);
        }
    }

    @Override
    public float getZoomRatio() {
        return mZoomRatio;
    }

    @Override
    public float getMinZoomRatio() {
        return zoomRange().getLower();
    }

    @Override
    public float getMaxZoomRatio() {
        return zoomRange().getUpper();
    }

    @NonNull
    @Override
    public float[] getZoomPresets() {
        Range<Float> range = zoomRange();
        try {
            return zoomPresets(mCameraManager, getActiveCamera(),
                    range.getLower(), range.getUpper());
        } catch (CameraAccessException | RuntimeException e) {
            if (DEBUG) Log.d(TAG, "Camera cannot report its lenses", e);
            return ZoomMath.presets(range.getLower(), range.getUpper(), new float[0], false);
        }
    }

    /**
     * The zoom stops a camera's lenses suggest. See {@link ZoomMath#presets}.
     *
     * <p>Public for the CameraX backend, which reaches the same characteristics through the camera
     * id underneath it.</p>
     *
     * @param cameraId the camera a zoom ratio of 1 belongs to, which on a phone with several lenses
     *     is the logical camera standing for all of them
     */
    @NonNull
    public static float[] zoomPresets(@NonNull CameraManager cameraManager,
            @NonNull String cameraId, float minRatio, float maxRatio)
            throws CameraAccessException {
        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
        List<CameraCharacteristics> lenses = new ArrayList<>();
        for (String physicalId : physicalCameraIds(characteristics)) {
            try {
                lenses.add(cameraManager.getCameraCharacteristics(physicalId));
            } catch (CameraAccessException | IllegalArgumentException e) {
                // One lens that cannot be read costs its own stop, not the others'.
                Log.w(TAG, "Unable to inspect physical camera " + physicalId, e);
            }
        }
        return zoomPresets(characteristics, lenses, minRatio, maxRatio);
    }

    /**
     * The zoom stops a camera and the physical lenses behind it suggest.
     *
     * @param lenses the physical cameras behind a logical one, or none for a single lens
     */
    @NonNull
    static float[] zoomPresets(@NonNull CameraCharacteristics characteristics,
            @NonNull List<CameraCharacteristics> lenses, float minRatio, float maxRatio) {
        float focalLength = defaultFocalLength(characteristics);
        float sensorWidth = sensorWidth(characteristics);
        List<Float> lensStops = new ArrayList<>();
        for (CameraCharacteristics lens : lenses) {
            float[] focalLengths =
                    lens.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            if (focalLengths == null) {
                continue;
            }
            for (float lensFocalLength : focalLengths) {
                lensStops.add(ZoomMath.lensStop(
                        lensFocalLength, sensorWidth(lens), focalLength, sensorWidth));
            }
        }

        float[] stops = new float[lensStops.size()];
        for (int i = 0; i < stops.length; i++) {
            stops[i] = lensStops.get(i);
        }
        return ZoomMath.presets(minRatio, maxRatio, stops, ZoomMath.canCropTo2x(
                largestSensorWidth(characteristics), largestPictureWidth(characteristics)));
    }

    /**
     * The physical cameras behind a logical one, which is how a phone with several lenses on one
     * side publishes them.
     */
    @NonNull
    private static Set<String> physicalCameraIds(@NonNull CameraCharacteristics characteristics) {
        if (Build.VERSION.SDK_INT < 28) {
            return Collections.emptySet();
        }
        int[] capabilities =
                characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (capabilities == null) {
            return Collections.emptySet();
        }
        for (int capability : capabilities) {
            if (capability
                    == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) {
                return characteristics.getPhysicalCameraIds();
            }
        }
        return Collections.emptySet();
    }

    /** The lens a zoom ratio of 1 means, which is the first a camera lists. See fieldOfView. */
    private static float defaultFocalLength(@NonNull CameraCharacteristics characteristics) {
        float[] focalLengths =
                characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        return focalLengths == null || focalLengths.length == 0 ? 0f : focalLengths[0];
    }

    /** The width of a camera's sensor in millimetres, or 0 when it does not say. */
    private static float sensorWidth(@NonNull CameraCharacteristics characteristics) {
        SizeF sensor = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        return sensor == null ? 0f : sensor.getWidth();
    }

    /**
     * The width of a camera's sensor in pixels, at the highest resolution it can read out.
     *
     * <p>A sensor that bins its pixels for ordinary use publishes the binned size as its pixel
     * array, and from API 31 the full size separately.</p>
     */
    private static int largestSensorWidth(@NonNull CameraCharacteristics characteristics) {
        Size pixels = null;
        if (Build.VERSION.SDK_INT >= 31) {
            pixels = characteristics.get(
                    CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION);
        }
        if (pixels == null) {
            pixels = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        }
        return pixels == null ? 0 : pixels.getWidth();
    }

    /** The width of the largest JPEG a camera offers, or 0 when it offers none. */
    private static int largestPictureWidth(@NonNull CameraCharacteristics characteristics) {
        StreamConfigurationMap map =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.JPEG);
        Size largest = sizes == null ? null : largestWithin(Arrays.asList(sizes), null);
        return largest == null ? 0 : largest.getWidth();
    }

    /** The active camera's zoom range; 1 to 1 when it cannot zoom or cannot be read. */
    @NonNull
    private Range<Float> zoomRange() {
        try {
            return zoomRange(mCameraManager.getCameraCharacteristics(getActiveCamera()));
        } catch (CameraAccessException | RuntimeException e) {
            if (DEBUG) Log.d(TAG, "Camera cannot report its zoom range", e);
            return new Range<>(1f, 1f);
        }
    }

    /**
     * A camera's zoom range, whichever way it zooms.
     *
     * <p>The zoom ratio control where the camera has it, which spans every lens a logical camera
     * can switch between. Otherwise a crop of the active array, where the ratio is how much of the
     * array is cropped away, up to the camera's maximum digital zoom.</p>
     */
    @NonNull
    static Range<Float> zoomRange(@NonNull CameraCharacteristics characteristics) {
        Range<Float> ratioRange = zoomRatioRange(characteristics);
        if (ratioRange != null) {
            return ratioRange;
        }
        Rect activeArray =
                characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        Float maxDigitalZoom =
                characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        if (activeArray == null || maxDigitalZoom == null || maxDigitalZoom <= 1f) {
            return new Range<>(1f, 1f);
        }
        return new Range<>(1f, maxDigitalZoom);
    }

    /** Pulls the zoom into the active camera's range, which a lens switch can move. */
    private void clampZoomToActiveCamera() {
        Range<Float> range = zoomRange();
        float clamped = ZoomMath.clamp(mZoomRatio, range.getLower(), range.getUpper());
        if (clamped != mZoomRatio) {
            mZoomRatio = clamped;
            onZoomChanged(clamped);
        }
    }

    /**
     * Writes the zoom into a session's requests, without issuing them.
     *
     * <p>Prefers the zoom ratio control, which lets the camera switch physical lenses as it crosses
     * from one to the next. A crop region could only ever zoom within one lens.</p>
     */
    private void applyZoom(@NonNull Session session) throws CameraAccessException {
        CameraCharacteristics characteristics =
                mCameraManager.getCameraCharacteristics(getActiveCamera());
        if (zoomRatioRange(characteristics) != null) {
            session.setZoomRatio(mZoomRatio);
            session.setCropRegion(null);
            return;
        }
        Rect activeArray =
                characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        session.setZoomRatio(null);
        session.setCropRegion(
                activeArray == null ? null : calculateCropRegion(activeArray, mZoomRatio));
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

    /** The centred crop of the active array that zooms it by a ratio. */
    static Rect calculateCropRegion(Rect activeArray, float zoomRatio) {
        float zoom = Math.max(1f, zoomRatio);
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

    /**
     * Whether the camera can hand back a frame it already has for a picture, rather than
     * exposing a new one after the request arrives.
     */
    boolean isZeroShutterLagSupported() {
        if (Build.VERSION.SDK_INT < 26) {
            return false;
        }
        try {
            return mCameraManager.getCameraCharacteristics(getActiveCamera())
                    .getAvailableCaptureRequestKeys().contains(CaptureRequest.CONTROL_ENABLE_ZSL);
        } catch (CameraAccessException | RuntimeException e) {
            Log.w(TAG, "Failed to ask whether the camera has zero shutter lag", e);
            return false;
        }
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

        // The dimensions belong to the session, so it is rebuilt with them - except while
        // recording or streaming, which a rebuild would end. The session after it is built with
        // the new quality.
        if (mActiveSession != null && !isRecordingOrStreaming()) {
            setSession(new PictureSession(this));
        }
    }

    @Override
    public void setFlash(CameraView.Flash flash) {
        super.setFlash(flash);

        // The strategy is carried by the repeating request, so the preview has to be reissued for
        // auto-exposure to converge under the new mode before the next capture.
        reissueRepeatingRequest("the flash strategy");
    }

    /**
     * Lights the flash continuously, on every request the session makes.
     *
     * <p>Refused only while a camera without a flash is open. A request made before then is kept,
     * and lights the torch on whichever camera opens if it has one.</p>
     *
     * <p>Turns low-light boost off, as {@link ICameraModule#setTorchEnabled} does on every
     * backend: it is an auto-exposure mode of its own, and the torch needs plain ON.</p>
     */
    @AnyThread
    @Override
    public void setTorchEnabled(boolean enabled) {
        if (enabled && mCameraDevice != null && !hasFlash()) {
            Log.w(TAG, "Ignoring torch request because the active camera has no flash unit");
            return;
        }
        boolean changed = enabled != isTorchEnabled() || (enabled && isLowLightBoostEnabled());
        super.setTorchEnabled(enabled);
        if (changed) {
            reissueRepeatingRequest("the torch");
        }
    }

    /**
     * Brightens the preview and recordings in the dark, through the auto-exposure mode
     * CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY.
     *
     * <p>Refused only while a camera without the mode is open, as on CameraX, and turns the torch
     * off. The camera itself falls back to plain ON for a session it cannot boost, which the
     * platform documents as anything beyond a preview and one encoder.</p>
     */
    @AnyThread
    @Override
    public void setLowLightBoostEnabled(boolean enabled) {
        if (enabled && mCameraDevice != null && !isLowLightBoostSupported()) {
            Log.w(TAG, "Ignoring low-light boost because the active camera does not support it");
            return;
        }
        boolean changed = enabled != isLowLightBoostEnabled() || (enabled && isTorchEnabled());
        super.setLowLightBoostEnabled(enabled);
        if (changed) {
            reissueRepeatingRequest("low-light boost");
        }
    }

    @Override
    public boolean isLowLightBoostSupported() {
        return offersLowLightBoost(getCameraCharacteristics());
    }

    /** Whether a camera lists the low-light boost auto-exposure mode. */
    static boolean offersLowLightBoost(@Nullable CameraCharacteristics characteristics) {
        return Build.VERSION.SDK_INT >= 35 && characteristics != null && contains(
                characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES),
                CameraCharacteristics.CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY);
    }

    /**
     * Sets the exposure compensation index, in the camera's own steps.
     *
     * <p>With no camera open there is no range to hold the index to, so it is kept as given and
     * clamped once a session is configured. An open camera that cannot compensate refuses it.</p>
     */
    @AnyThread
    @Override
    public void setExposureCompensation(int exposureCompensation) {
        if (mCameraDevice == null) {
            super.setExposureCompensation(exposureCompensation);
            return;
        }
        Range<Integer> range = exposureCompensationRange(getCameraCharacteristics());
        if (range == null) {
            Log.w(TAG, "Ignoring exposure request because the active camera does not support it");
            return;
        }
        int clamped = range.clamp(exposureCompensation);
        boolean changed = clamped != getExposureCompensation();
        super.setExposureCompensation(clamped);
        if (changed) {
            reissueRepeatingRequest("exposure compensation");
        }
    }

    /** 0 for a camera that cannot compensate, whatever range it publishes. */
    @Override
    public int getMinExposureCompensation() {
        Range<Integer> range = exposureCompensationRange(getCameraCharacteristics());
        return range == null ? 0 : range.getLower();
    }

    /** 0 for a camera that cannot compensate, whatever range it publishes. */
    @Override
    public int getMaxExposureCompensation() {
        Range<Integer> range = exposureCompensationRange(getCameraCharacteristics());
        return range == null ? 0 : range.getUpper();
    }

    /** CONTROL_AE_COMPENSATION_STEP, or 0 for a camera that cannot compensate. */
    @Override
    public float getExposureCompensationStep() {
        CameraCharacteristics characteristics = getCameraCharacteristics();
        if (exposureCompensationRange(characteristics) == null) {
            return 0f;
        }
        Rational step = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        return step == null ? 0f : step.floatValue();
    }

    @Override
    public boolean isExposureCompensationSupported() {
        return exposureCompensationRange(getCameraCharacteristics()) != null;
    }

    /**
     * The exposure index every request carries, and so every picture's Exif: the one asked for,
     * held to the active camera's range, or null for a camera that cannot compensate.
     */
    @Nullable
    Integer getRequestedExposureCompensation() {
        Range<Integer> range = exposureCompensationRange(getCameraCharacteristics());
        return range == null ? null : range.clamp(getExposureCompensation());
    }

    /** Holds the index to the range of the camera now configured, for one set before it was. */
    private void clampExposureCompensation() {
        Range<Integer> range = exposureCompensationRange(getCameraCharacteristics());
        if (range != null) {
            super.setExposureCompensation(range.clamp(getExposureCompensation()));
        }
    }

    /**
     * The exposure compensation range a camera can apply, or null for one that cannot.
     *
     * <p>A range that stops at zero on either side counts as none, which is where every backend
     * draws the line. The platform publishes 0 to 0 for a camera without compensation, and a
     * range that can only go one way is a camera that does not keep to that.</p>
     */
    @Nullable
    static Range<Integer> exposureCompensationRange(
            @Nullable CameraCharacteristics characteristics) {
        Range<Integer> range = characteristics == null ? null
                : characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        if (range == null || range.getLower() == 0 || range.getUpper() == 0) {
            return null;
        }
        return range;
    }

    /**
     * Steadies the preview and a recording, each as far as the camera can.
     *
     * <p>Refused only while a camera that can steady neither is open. The mode is a session
     * parameter on many cameras, so a change rebuilds the session rather than reissuing a request,
     * except while recording or streaming: those keep the mode they started with, and the session
     * after them is built with the new one.</p>
     */
    @AnyThread
    @Override
    public void setStabilizationEnabled(boolean enabled) {
        if (enabled && mCameraDevice != null && !isStabilizationSupported()) {
            Log.w(TAG, "Ignoring stabilization because the active camera does not support it");
            return;
        }
        boolean changed = enabled != isStabilizationEnabled();
        super.setStabilizationEnabled(enabled);
        if (changed) {
            restartActiveSession();
        }
    }

    /**
     * True when the camera can steady either the preview or a recording digitally.
     *
     * <p>Optical stabilization is used where a camera has it but does not count here, because the
     * CameraX backend does not count it either, and a camera with only OIS would otherwise be
     * supported on one backend and not the other.</p>
     */
    @Override
    public boolean isStabilizationSupported() {
        CameraCharacteristics characteristics = getCameraCharacteristics();
        return offersPreviewStabilization(characteristics)
                || offersVideoStabilization(characteristics);
    }

    /** Whether a camera can steady the preview, and with it every other stream it outputs. */
    static boolean offersPreviewStabilization(@Nullable CameraCharacteristics characteristics) {
        return Build.VERSION.SDK_INT >= 33 && characteristics != null && contains(
                characteristics.get(
                        CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES),
                CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION);
    }

    /** Whether a camera can steady what it sends to an encoder. */
    static boolean offersVideoStabilization(@Nullable CameraCharacteristics characteristics) {
        return characteristics != null && contains(
                characteristics.get(
                        CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES),
                CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON);
    }

    /** Whether a camera has a lens it can steady optically. */
    static boolean offersOpticalStabilization(@Nullable CameraCharacteristics characteristics) {
        return characteristics != null && contains(
                characteristics.get(
                        CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION),
                CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON);
    }

    /**
     * Whether a camera lists a mode.
     *
     * <p>The lists are searched rather than read as enumerations, because they need not hold only
     * values the platform defines: Samsung's front cameras list auto-exposure modes 101, 102 and
     * 104 beside the standard ones.</p>
     */
    static boolean contains(@Nullable int[] modes, int mode) {
        if (modes == null) {
            return false;
        }
        for (int candidate : modes) {
            if (candidate == mode) {
                return true;
            }
        }
        return false;
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
            // The ratio means the same thing whether the camera zooms by ratio or by crop, so the
            // one every request carries is the one the picture was taken at.
            return TransformMath.zoomedFieldOfViewDegrees(full, mZoomRatio);
        } catch (CameraAccessException | IllegalArgumentException | IllegalStateException error) {
            if (DEBUG) Log.d(TAG, "Could not work out the field of view", error);
            return 0f;
        }
    }

    /**
     * The Exif a picture taken now should carry about how it was taken: the active camera's
     * optics, and the zoom, exposure compensation and flash this session requests.
     */
    @NonNull
    ExifCaptureParameters exifCaptureParameters() {
        ExifCaptureParameters parameters;
        try {
            parameters = ExifCaptureParameters.forCamera(mCameraManager, getActiveCamera())
                    .setZoomRatio(mZoomRatio);
        } catch (CameraAccessException | RuntimeException error) {
            Log.w(TAG, "Unable to describe the camera for a picture's Exif", error);
            parameters = new ExifCaptureParameters();
        }
        Integer exposureCompensation = getRequestedExposureCompensation();
        return parameters
                .setExposureCompensation(exposureCompensation == null ? 0 : exposureCompensation)
                .setFlash(getFlash(), hasFlash());
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
        ColorSpace.Named named = namedColorSpace(colorSpace);
        if (named == null) {
            return false;
        }
        try {
            ColorSpaceProfiles profiles =
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
    private static ColorSpace.Named namedColorSpace(CameraView.ColorSpace colorSpace) {
        switch (colorSpace) {
            case DISPLAY_P3:
                return ColorSpace.Named.DISPLAY_P3;
            case SRGB:
                return ColorSpace.Named.SRGB;
            default:
                return null;
        }
    }

    @Override
    public boolean isPictureFormatSupported(CameraView.PictureFormat format) {
        if (format == CameraView.PictureFormat.RAW
                || format == CameraView.PictureFormat.RAW_JPEG) {
            // A drawn picture is a JPEG, so there is no sensor frame to write a DNG from.
            return !isDrawingFrames() && writesRaw(getCameraCharacteristics());
        }
        if (format != CameraView.PictureFormat.ULTRA_HDR) {
            return super.isPictureFormatSupported(format);
        }
        if (Build.VERSION.SDK_INT < 34 || isDrawingFrames()) {
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
            // The format belongs to the still's reader, which the session is built around. Until
            // the camera configures the new session, a capture in this format has nowhere to go.
            mAwaitingFormatSession = true;
            restartActiveSession();
        }
    }

    /**
     * Whether a camera can be asked for a DNG.
     *
     * <p>It has to say so twice: the RAW capability, which promises the metadata needed to
     * interpret the sensor's readings, and a RAW_SENSOR output at a size DngCreator accepts. A
     * camera can publish either without the other.</p>
     */
    @VisibleForTesting
    static boolean writesRaw(@Nullable CameraCharacteristics characteristics) {
        if (characteristics == null) {
            return false;
        }
        int[] capabilities =
                characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (capabilities == null) {
            return false;
        }
        for (int capability : capabilities) {
            if (capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) {
                return DngWriter.sensorSize(characteristics) != null;
            }
        }
        return false;
    }

    /**
     * The active camera's characteristics, or null when they cannot be read.
     *
     * <p>DngCreator needs them whole rather than a key at a time, to describe the frame it
     * writes.</p>
     */
    @Nullable
    CameraCharacteristics getCameraCharacteristics() {
        try {
            return mCameraManager.getCameraCharacteristics(getActiveCamera());
        } catch (CameraAccessException | RuntimeException error) {
            if (DEBUG) Log.d(TAG, "Camera cannot report its characteristics", error);
            return null;
        }
    }

    /**
     * Holds a capture until the session being built for its format is configured.
     *
     * <p>One at a time: a second request replaces the first, failing it. The timeout is for a
     * session that never configures.</p>
     */
    private void awaitSessionThenCapture(@NonNull File file) {
        cancelPendingPicture();
        mPendingPictureFile = file;
        mPendingPictureTimeout = () -> {
            mPendingPictureTimeout = null;
            if (mPendingPictureFile != null) {
                Log.w(TAG, "The session for this picture format never configured");
                mPendingPictureFile = null;
                onImageFailed();
            }
        };
        mHandler.postDelayed(mPendingPictureTimeout, PENDING_PICTURE_TIMEOUT_MILLIS);
    }

    /** Takes the picture that was waiting for this session, if there was one. */
    private void capturePendingPicture() {
        File file = mPendingPictureFile;
        if (file == null) {
            return;
        }
        mPendingPictureFile = null;
        if (mPendingPictureTimeout != null) {
            mHandler.removeCallbacks(mPendingPictureTimeout);
            mPendingPictureTimeout = null;
        }
        takePicture(file);
    }

    /** Drops a waiting capture, reporting the failure the caller is owed. */
    private void cancelPendingPicture() {
        if (mPendingPictureTimeout != null) {
            mHandler.removeCallbacks(mPendingPictureTimeout);
            mPendingPictureTimeout = null;
        }
        if (mPendingPictureFile != null) {
            mPendingPictureFile = null;
            onImageFailed();
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
        ColorSpace.Named named = namedColorSpace(colorSpace);
        ColorSpaceProfiles profiles =
                characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES);
        return named != null && profiles != null
                && profiles.getSupportedColorSpaces(ImageFormat.JPEG_R).contains(named);
    }

    /**
     * Rebuilds the session, for a setting the session itself carries, unless
     * {@link #isRecordingOrStreaming}.
     */
    private void restartActiveSession() {
        mHandler.post(() -> {
            if (mCameraDevice == null || mActiveSession == null || mIsPaused
                    || isRecordingOrStreaming()) {
                // No session is coming, or none until the recording or stream ends, so a capture
                // waiting for one goes to the session there is.
                mAwaitingFormatSession = false;
                capturePendingPicture();
                return;
            }
            setSession(new PictureSession(this));
        });
    }

    /**
     * Whether a recording or a stream owns the session, or is about to.
     *
     * <p>Neither can be rebuilt without ending it, so a setting the session carries waits for the
     * session built after it. That is the library's rule: a recording is never cut short by a
     * settings change.</p>
     */
    private boolean isRecordingOrStreaming() {
        return mIsRecording
                || mActiveSession instanceof VideoSession || mActiveSession instanceof StreamSession
                || mRegisteringSession instanceof VideoSession
                || mRegisteringSession instanceof StreamSession;
    }

    /**
     * The cameras that turned down a session feeding a motion photo's encoder, by id, so that
     * they are not asked again while this module lives.
     */
    private final Set<String> mMotionPhotoRefusals = new HashSet<>();

    /** Remembers that the active camera will not feed a motion photo. */
    private void refuseMotionPhotos() {
        try {
            mMotionPhotoRefusals.add(getActiveCamera());
        } catch (CameraAccessException | RuntimeException error) {
            if (DEBUG) Log.d(TAG, "Camera cannot report its id", error);
        }
    }

    /**
     * Whether this camera can take motion photos.
     *
     * <p>From LIMITED hardware upwards a camera promises a preview, an encoder and a still at once,
     * which is the combination a motion photo needs; LEGACY hardware promises nothing of the sort.
     * A front camera's clip is mirrored through GL, so it also needs GL to run here.</p>
     */
    @Override
    public boolean isMotionPhotoSupported() {
        if (!MotionPhotoRecorder.isSupported()) {
            return false;
        }
        if (isUsingFrontFacingCamera() && !isGlAvailable()) {
            return false;
        }
        try {
            String cameraId = getActiveCamera();
            if (mMotionPhotoRefusals.contains(cameraId)) {
                return false;
            }
            Integer level = mCameraManager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
            return level != null
                    && level != CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY;
        } catch (CameraAccessException | RuntimeException error) {
            if (DEBUG) Log.d(TAG, "Camera cannot report its hardware level", error);
            return false;
        }
    }

    @Override
    protected void onMotionPhotoChanged() {
        restartActiveSession();
    }

    /** The encoder for the session being built to feed, or null for none. */
    @MainThread
    @Nullable
    MotionPhotoRecorder motionPhotoRecorder() {
        return getMotionPhotoRecorder();
    }

    /**
     * The largest still a session feeding a motion photo's encoder may ask for, or null for any.
     *
     * <p>FULL hardware promises a preview, a second preview-sized output and a still at the
     * sensor's maximum together. LIMITED hardware promises the still only at the recording size,
     * so there it is capped to the largest a recording could be.</p>
     */
    @Nullable
    Size motionPhotoPictureCap() {
        CameraCharacteristics characteristics = getCameraCharacteristics();
        Integer level = characteristics == null
                ? null : characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        if (level == null || level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL
                || level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3) {
            return null;
        }
        StreamConfigurationMap map =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] recording = map == null ? null : map.getOutputSizes(MediaRecorder.class);
        return largestOf(recording);
    }

    /** The largest of a list of sizes by area, or null for none. */
    @VisibleForTesting
    @Nullable
    static Size largestOf(@Nullable Size[] sizes) {
        Size largest = null;
        if (sizes != null) {
            for (Size size : sizes) {
                if (largest == null || (long) size.getWidth() * size.getHeight()
                        > (long) largest.getWidth() * largest.getHeight()) {
                    largest = size;
                }
            }
        }
        return largest;
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
        // A format change rebuilds the session, and the camera configures the new one on its own
        // schedule, so a caller who sets a format and takes a picture on the next line arrives
        // before the session that can honour it exists. It waits rather than failing for a reason
        // the caller cannot see.
        if (mAwaitingFormatSession) {
            awaitSessionThenCapture(file);
            return;
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

        FrameRenderThread effect = mEffect;
        if (effect != null) {
            // The preview is turned for the display here, so this is also where how every frame
            // is shown changes, and with it which way round a renderer's drawing goes.
            effect.setShownOrientation(getRelativeCameraOrientation(), isUsingFrontFacingCamera());
        }
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
