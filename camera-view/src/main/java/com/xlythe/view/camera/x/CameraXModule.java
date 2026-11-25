package com.xlythe.view.camera.x;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.util.SizeF;
import android.view.Surface;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;
import androidx.annotation.RestrictTo;
import androidx.camera.core.Camera;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.CameraEffect;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.DynamicRange;
import androidx.camera.core.ExposureState;
import androidx.camera.core.CameraInfoUnavailableException;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.CameraState;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.FocusMeteringResult;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MirrorMode;
import androidx.camera.core.Preview;
import androidx.camera.core.SurfaceOrientedMeteringPointFactory;
import androidx.camera.core.SurfaceRequest;
import androidx.camera.core.SessionConfig;
import androidx.camera.core.UseCase;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ViewPort;
import androidx.camera.core.ZoomState;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapabilities;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.extensions.ExtensionsManager;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewTreeLifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;
import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.Face;
import com.xlythe.view.camera.CameraXBarcodeAnalyzer;
import com.xlythe.view.camera.Barcode;
import com.xlythe.view.camera.gl.CameraTextureProvider;
import com.xlythe.view.camera.gl.FrameRenderer;
import com.xlythe.view.camera.ICameraModule;
import com.xlythe.view.camera.LocationProvider;
import com.xlythe.view.camera.PermissionChecker;
import com.xlythe.view.camera.TransformMath;
import com.xlythe.view.camera.stream.VideoRecorder;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Executor;

/** A CameraView backend implemented with CameraX. */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public class CameraXModule extends ICameraModule implements LifecycleOwner {
    /** The time for which a cached GPS location remains useful. */
    private static final long STALE_LOCATION_MILLIS = 2 * 60 * 60 * 1000;
    /** A short timeout used only to refresh and read the last known GPS location. */
    private static final long GPS_TIMEOUT_MILLIS = 10;
    /** The public CameraView zoom range is represented as one hundred linear steps. */
    private static final int MAX_ZOOM_LEVEL = 100;
    /** Maximum time CameraX may take to report a still-image terminal callback. */
    private static final long IMAGE_CAPTURE_TIMEOUT_MILLIS = 15_000;
    /** Maximum time CameraX may take to emit the recording Start event. */
    private static final long RECORDING_START_TIMEOUT_MILLIS = 15_000;
    /** Maximum time CameraX may take to emit Finalize after a stop request. */
    private static final long RECORDING_FINALIZE_TIMEOUT_MILLIS = 10_000;
    /** Delay allowing a removed stream surface to settle before restoring stabilization. */
    private static final long STREAM_RECONFIGURE_DELAY_MILLIS = 500;

    /** Internal CameraX lifecycle states, serialized on the main thread. */
    private enum State { CLOSED, SUSPENDED, WAITING_FOR_PROVIDER, BINDING, OPEN, RECORDING, STOPPING, ERROR }

    /** Main-thread handler used for all mutable backend state and recording timeouts. */
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    /** Monitor guarding snapshots read from arbitrary caller threads. */
    private final Object mSnapshotLock = new Object();
    /** Main-thread executor required by CameraX binding and callback APIs. */
    private final Executor mMainExecutor;
    /** Stable lifecycle owner used even when CameraView is hosted by a plain Activity. */
    private final LifecycleRegistry mLifecycleRegistry = new LifecycleRegistry(this);
    /** Receives attachment changes so an open request cannot retain a detached camera. */
    private final View.OnAttachStateChangeListener mAttachStateListener =
            new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) {
                    onHostAttachmentChanged(true);
                }

                @Override public void onViewDetachedFromWindow(View view) {
                    onHostAttachmentChanged(false);
                }
            };
    /** Receives host lifecycle transitions when a view-tree lifecycle owner is available. */
    private final LifecycleEventObserver mHostLifecycleObserver =
            (source, event) -> onHostLifecycleChanged(source);
    /** Tracks start, stop, and destruction for a plain Activity without a LifecycleOwner. */
    private final Application.ActivityLifecycleCallbacks mActivityLifecycleCallbacks =
            new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(
                        @NonNull Activity activity, @Nullable Bundle state) {}
                @Override public void onActivityStarted(@NonNull Activity activity) {
                    onHostActivityStartedChanged(activity, true);
                }
                @Override public void onActivityResumed(@NonNull Activity activity) {}
                @Override public void onActivityPaused(@NonNull Activity activity) {}
                @Override public void onActivityStopped(@NonNull Activity activity) {
                    onHostActivityStartedChanged(activity, false);
                }
                @Override public void onActivitySaveInstanceState(
                        @NonNull Activity activity, @NonNull Bundle state) {}
                @Override public void onActivityDestroyed(@NonNull Activity activity) {
                    if (activity != mHostActivity) return;
                    onHostActivityStartedChanged(activity, false);
                    activity.getApplication().unregisterActivityLifecycleCallbacks(this);
                    mHostActivity = null;
                    closeOnMainThread();
                    destroyOnMainThread();
                }
            };

    /** The single provider future and single listener used for this backend instance. */
    private final ListenableFuture<ProcessCameraProvider> mCameraProviderFuture;
    /** Ordered custom stream providers requested by VideoStream instances. */
    private final Set<VideoRecorder.SurfaceProvider> mSurfaceProviders = new LinkedHashSet<>();
    /** Custom Preview use cases in the currently bound atomic use-case group. */
    private final Map<VideoRecorder.SurfaceProvider, Preview> mBoundStreamPreviews = new LinkedHashMap<>();
    /** Every use case currently bound by this module, and therefore safe for it to unbind. */
    private final List<UseCase> mBoundUseCases = new ArrayList<>();
    /** Plain Activity containing CameraView when it does not expose a LifecycleOwner. */
    @Nullable private Activity mHostActivity;
    /** Last started/stopped state reported by the plain Activity host. */
    private boolean mHostActivityStarted = true;
    /** Current view-tree lifecycle owner, or null for plain Activity/application hosts. */
    @Nullable private LifecycleOwner mHostLifecycleOwner;
    /** CameraState source currently observed for runtime failures. */
    @Nullable private LiveData<CameraState> mObservedCameraState;
    /** Generation-scoped observer installed on the active CameraState source. */
    @Nullable private Observer<CameraState> mCameraStateObserver;
    /** CameraX provider after its asynchronous initialization completes. */
    @Nullable private ProcessCameraProvider mCameraProvider;
    /** Asynchronous CameraX extension-manager initialization. */
    @Nullable private ListenableFuture<ExtensionsManager> mExtensionsManagerFuture;
    /** CameraX vendor extension manager after initialization. */
    @Nullable private ExtensionsManager mExtensionsManager;
    /** Camera returned by the latest successful atomic bind. */
    @Nullable private Camera mActiveCamera;
    /** Display Preview in the latest successful bind. */
    @Nullable private Preview mPreview;
    /** Still-image use case in the latest successful bind. */
    @Nullable private ImageCapture mImageCapture;
    /** Native barcode-analysis use case in the latest successful bind. */
    @Nullable private ImageAnalysis mImageAnalysis;
    /** Native CameraX barcode analyzer while scanning is requested. */
    @Nullable private CameraXBarcodeAnalyzer mBarcodeAnalyzer;
    /** Single-thread executor owned by the active native barcode analyzer. */
    @Nullable private ExecutorService mAnalysisExecutor;
    /** Video use case while a recording is being prepared, recorded, or finalized. */
    @Nullable private VideoCapture<Recorder> mVideoCapture;
    /** Active CameraX recording; cleared only by its Finalize event. */
    @Nullable private Recording mVideoRecording;
    /** Output file associated with the active recording. */
    @Nullable private File mVideoFile;
    /** Latest CameraX preview transformation metadata. */
    @Nullable private SurfaceRequest.TransformationInfo mTransformationInfo;
    /** The GL effect every frame passes through, while a caller has asked for one. */
    @Nullable private FrameEffect mFrameEffect;

    /** Whether the bound configuration can take a still while it records. */
    private boolean mSnapshotWhileRecording;

    /** Whether the caller asked for faces, which the bound request has to carry.
     *
     * <p>CameraX has no face detection of its own, so this is a camera2 request option and
     * session callback added through interop, both of which are fixed at bind time.</p> */
    private boolean mFaceDetectionRequested;

    /** How many faces the last report had, so an empty camera is not reported every frame. */
    private int mLastFaceCount;
    /** Resolution of the SurfaceTexture buffer for the latest display Preview request. */
    @Nullable private Size mPreviewResolution;
    /** Main-thread state of this CameraX backend. */
    private State mState = State.CLOSED;
    /** Whether the CameraView is attached to a window. */
    private boolean mViewAttached;
    /** Whether the optional view-tree lifecycle owner is at least STARTED. */
    private boolean mHostStarted;
    /** Last critical CameraState error already reported, preventing duplicate callbacks. */
    @Nullable private Integer mLastCriticalCameraError;
    /** Whether a critical runtime camera failure requires an explicit reopen. */
    private boolean mCriticalCameraFailure;
    /** Lens requested while recording, applied after CameraX emits Finalize. */
    @Nullable private Boolean mPendingFrontFacing;
    /** Whether the host is gone, after which the lifecycle registry cannot be driven again. */
    private boolean mDestroyed;
    /** Whether CameraView currently wants this backend open. */
    private boolean mOpenRequested;
    /** Whether confirmation UI has intentionally paused CameraX use cases. */
    private boolean mPaused;
    /** Requested lens; false is back-facing and true is front-facing. */
    private boolean mFrontFacing;
    /** Sensor orientation for the requested lens, available before CameraX finishes binding. */
    private int mSensorOrientation;
    /** Last accepted public zoom level in the range 0..100. */
    private int mZoomLevel;
    /** Last non-zero CameraView layout width used to choose resolutions and ViewPort. */
    private int mLastWidth;
    /** Last non-zero CameraView layout height used to choose resolutions and ViewPort. */
    private int mLastHeight;
    /** Set when a layout settles during a capture, so the rebind it asks for is owed, not lost. */
    private boolean mSizeRebindPending;
    /** Monotonic token invalidating stale provider, surface, and binding callbacks. */
    private long mGeneration;
    /** Monotonic token ensuring an image request reports at most one terminal result. */
    private long mImageOperationId;
    /** Monotonic token ensuring a video request reports at most one terminal result. */
    private long mVideoOperationId;
    /** Usable media duration reported by CameraX for the active recording. */
    private long mRecordedVideoDurationMillis;
    /** Pending image destination while a terminal callback is outstanding. */
    @Nullable private File mPendingImageFile;
    /** Pending maximum-duration stop task for the active recording. */
    @Nullable private Runnable mDurationStopTask;
    /** Pending still-image timeout task. */
    @Nullable private Runnable mImageTimeoutTask;
    /** Pending recording-start timeout task. */
    @Nullable private Runnable mRecordingStartTimeoutTask;
    /** Pending recording-finalize timeout task. */
    @Nullable private Runnable mRecordingFinalizeTimeoutTask;
    /** Snapshot of requested lens facing guarded by mSnapshotLock. */
    private boolean mSnapshotFrontFacing;
    /** Snapshot of pause state guarded by mSnapshotLock. */
    private boolean mSnapshotPaused;
    /** Snapshot of recording state guarded by mSnapshotLock. */
    private boolean mSnapshotRecording;
    /** Snapshot of accepted zoom level guarded by mSnapshotLock. */
    private int mSnapshotZoomLevel;
    /** Snapshot of front-camera availability guarded by mSnapshotLock. */
    private boolean mSnapshotHasFrontCamera;
    /** Snapshot of flash availability guarded by mSnapshotLock. */
    private boolean mSnapshotHasFlash;
    /** Snapshot of zoom availability guarded by mSnapshotLock. */
    private boolean mSnapshotZoomSupported;
    /** Snapshot of HDR capture availability guarded by mSnapshotLock. */
    private boolean mSnapshotHdrSupported;
    /** Snapshot of preview stabilization availability guarded by mSnapshotLock. */
    private boolean mSnapshotPreviewStabilizationSupported;
    /** Snapshot of video stabilization availability guarded by mSnapshotLock. */
    private boolean mSnapshotVideoStabilizationSupported;
    /** Snapshot of exposure-control availability guarded by mSnapshotLock. */
    private boolean mSnapshotExposureSupported;
    /** Snapshot of minimum exposure compensation guarded by mSnapshotLock. */
    private int mSnapshotMinExposure;
    /** Snapshot of maximum exposure compensation guarded by mSnapshotLock. */
    private int mSnapshotMaxExposure;
    /** Snapshot of low-light boost availability guarded by mSnapshotLock. */
    private boolean mSnapshotLowLightBoostSupported;
    /** Snapshot of supported vendor extensions guarded by mSnapshotLock. */
    private Set<CameraView.Extension> mSnapshotExtensions = Collections.singleton(CameraView.Extension.NONE);

    public CameraXModule(CameraView cameraView) {
        super(cameraView);
        mMainExecutor = ContextCompat.getMainExecutor(getContext());
        mLifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
        mViewAttached = getView().isAttachedToWindow();
        getView().addOnAttachStateChangeListener(mAttachStateListener);
        mHostActivity = findActivity(getContext());
        if (mHostActivity != null) {
            mHostActivity.getApplication()
                    .registerActivityLifecycleCallbacks(mActivityLifecycleCallbacks);
        }
        updateHostLifecycleOwner();
        mCameraProviderFuture = ProcessCameraProvider.getInstance(getContext());
        mCameraProviderFuture.addListener(this::onProviderReady, mMainExecutor);
    }

    @NonNull @Override public Lifecycle getLifecycle() { return mLifecycleRegistry; }

    private void publishSnapshot() {
        boolean hasFrontCamera = false;
        Set<CameraView.Extension> supportedExtensions = EnumSet.of(CameraView.Extension.NONE);
        ProcessCameraProvider provider = mCameraProvider;
        if (provider != null) {
            try {
                hasFrontCamera = provider.hasCamera(selectorFor(CameraSelector.LENS_FACING_FRONT));
                ExtensionsManager extensionsManager = mExtensionsManager;
                if (extensionsManager != null) {
                    CameraSelector selector = selectorFor(mFrontFacing
                            ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK);
                    for (CameraView.Extension extension : CameraView.Extension.values()) {
                        if (extension != CameraView.Extension.NONE
                                && extensionsManager.isExtensionAvailable(selector, toExtensionMode(extension))) {
                            supportedExtensions.add(extension);
                        }
                    }
                }
            } catch (CameraInfoUnavailableException | RuntimeException error) {
                Log.w(TAG, "Unable to refresh CameraX capabilities", error);
            }
        }
        Camera camera = mActiveCamera;
        CameraInfo cameraInfo = camera == null ? null : camera.getCameraInfo();
        boolean hasFlash = cameraInfo != null && cameraInfo.hasFlashUnit();
        boolean zoomSupported = false;
        boolean hdrSupported = false;
        boolean previewStabilizationSupported = false;
        boolean videoStabilizationSupported = false;
        boolean exposureSupported = false;
        int minExposure = 0;
        int maxExposure = 0;
        boolean lowLightBoostSupported = false;
        if (cameraInfo != null) {
            // Each of these asks the camera a separate question, and a camera that cannot answer
            // one throws rather than declining: Recorder.getVideoCapabilities, for instance,
            // fails outright on a camera with no encoder profiles. This runs from lifecycle
            // callbacks and from CameraX's own listeners, so an escaping exception takes the host
            // down. Ask each question on its own and treat a failure as unsupported, which is
            // what an unanswerable capability means anyway.
            zoomSupported = query(cameraInfo, "zoom", info -> {
                ZoomState zoom = info.getZoomState().getValue();
                return zoom != null && zoom.getMaxZoomRatio() > zoom.getMinZoomRatio();
            }, false);
            hdrSupported = query(cameraInfo, "HDR", CameraXModule::supportsHdr, false);
            previewStabilizationSupported = query(cameraInfo, "preview stabilization",
                    info -> Preview.getPreviewCapabilities(info).isStabilizationSupported(), false);
            videoStabilizationSupported = query(cameraInfo, "video stabilization",
                    info -> Recorder.getVideoCapabilities(info).isStabilizationSupported(), false);
            exposureSupported = query(cameraInfo, "exposure",
                    info -> info.getExposureState().isExposureCompensationSupported(), false);
            minExposure = query(cameraInfo, "minimum exposure",
                    info -> info.getExposureState().getExposureCompensationRange().getLower(), 0);
            maxExposure = query(cameraInfo, "maximum exposure",
                    info -> info.getExposureState().getExposureCompensationRange().getUpper(), 0);
            lowLightBoostSupported = query(cameraInfo, "low-light boost",
                    CameraInfo::isLowLightBoostSupported, false);
        }
        synchronized (mSnapshotLock) {
            mSnapshotFrontFacing = mFrontFacing;
            mSnapshotPaused = mPaused;
            mSnapshotRecording = mVideoRecording != null;
            mSnapshotZoomLevel = mZoomLevel;
            mSnapshotHasFrontCamera = hasFrontCamera;
            mSnapshotHasFlash = hasFlash;
            mSnapshotZoomSupported = zoomSupported;
            mSnapshotHdrSupported = hdrSupported;
            mSnapshotPreviewStabilizationSupported = previewStabilizationSupported;
            mSnapshotVideoStabilizationSupported = videoStabilizationSupported;
            mSnapshotExposureSupported = exposureSupported;
            mSnapshotMinExposure = minExposure;
            mSnapshotMaxExposure = maxExposure;
            mSnapshotLowLightBoostSupported = lowLightBoostSupported;
            mSnapshotExtensions = Collections.unmodifiableSet(EnumSet.copyOf(supportedExtensions));
        }
    }

    /** One capability question, with its own failure handled. */
    private interface CapabilityQuery<T> {
        T ask(@NonNull CameraInfo cameraInfo);
    }

    /**
     * Asks the camera one question, answering with {@code fallback} when it cannot say.
     *
     * <p>A capability query that throws is reported as unsupported rather than propagated,
     * because this is called from lifecycle and CameraX callbacks where nothing can handle it.</p>
     */
    private static <T> T query(@NonNull CameraInfo cameraInfo, @NonNull String name,
            @NonNull CapabilityQuery<T> capabilityQuery, T fallback) {
        try {
            return capabilityQuery.ask(cameraInfo);
        } catch (RuntimeException error) {
            if (DEBUG) Log.d(TAG, "Camera cannot report " + name, error);
            return fallback;
        }
    }

    private void onHostAttachmentChanged(boolean attached) {
        mViewAttached = attached;
        updateHostLifecycleOwner();
        updateBackendLifecycle();
    }

    private void updateHostLifecycleOwner() {
        LifecycleOwner owner = ViewTreeLifecycleOwner.get(getView());
        if (owner == mHostLifecycleOwner) {
            mHostStarted = (mHostActivity == null || mHostActivityStarted)
                    && (owner == null || owner.getLifecycle().getCurrentState()
                    .isAtLeast(Lifecycle.State.STARTED));
            return;
        }
        if (mHostLifecycleOwner != null) {
            mHostLifecycleOwner.getLifecycle().removeObserver(mHostLifecycleObserver);
        }
        mHostLifecycleOwner = owner;
        if (owner == null) {
            mHostStarted = mHostActivity == null || mHostActivityStarted;
        } else {
            owner.getLifecycle().addObserver(mHostLifecycleObserver);
            mHostStarted = (mHostActivity == null || mHostActivityStarted)
                    && owner.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED);
        }
    }

    private void onHostLifecycleChanged(@NonNull LifecycleOwner owner) {
        if (owner != mHostLifecycleOwner) return;
        if (owner.getLifecycle().getCurrentState() == Lifecycle.State.DESTROYED) {
            closeOnMainThread();
            destroyOnMainThread();
            return;
        }
        mHostStarted = (mHostActivity == null || mHostActivityStarted)
                && owner.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED);
        updateBackendLifecycle();
    }

    /**
     * Retires this backend once its host is gone.
     *
     * <p>ProcessCameraProvider observes the lifecycle this module owns, and the module observes
     * the host's, so leaving the registry short of DESTROYED kept both sides registered after the
     * host went away. The registry is one-way, so everything that would drive it afterwards has
     * to stop as well.</p>
     */
    private void destroyOnMainThread() {
        if (mDestroyed) return;
        mDestroyed = true;

        if (mHostLifecycleOwner != null) {
            mHostLifecycleOwner.getLifecycle().removeObserver(mHostLifecycleObserver);
            mHostLifecycleOwner = null;
        }
        getView().removeOnAttachStateChangeListener(mAttachStateListener);
        releaseFrameEffect();
        mLifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
        mState = State.CLOSED;
    }

    private void onHostActivityStartedChanged(@NonNull Activity activity, boolean started) {

        if (activity != mHostActivity) return;
        mHostActivityStarted = started;
        mHostStarted = started && (mHostLifecycleOwner == null
                || mHostLifecycleOwner.getLifecycle().getCurrentState()
                .isAtLeast(Lifecycle.State.STARTED));
        updateBackendLifecycle();
    }

    @Nullable private static Activity findActivity(@NonNull Context initialContext) {
        Context context = initialContext;
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context baseContext = ((ContextWrapper) context).getBaseContext();
            if (baseContext == context) break;
            context = baseContext;
        }
        return context instanceof Activity ? (Activity) context : null;
    }

    private boolean isHostActive() {
        boolean hasLifecycleHost = mHostLifecycleOwner != null || mHostActivity != null;
        return mViewAttached && (!hasLifecycleHost || mHostStarted);
    }

    @SuppressLint("MissingPermission")
    private void updateBackendLifecycle() {
        if (mDestroyed) return;
        if (!mOpenRequested) {
            mLifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
            return;
        }
        if (isHostActive()) {
            mLifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
            if (mCameraProvider != null && mVideoRecording == null
                    && mBoundUseCases.isEmpty() && !mPaused) {
                reconcileUseCases(true);
            }
            return;
        }
        if (mVideoRecording != null) {
            stopRecordingOnMainThread();
            return;
        }
        unbindCurrentUseCases();
        mLifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
        mState = State.SUSPENDED;
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    @Override public void open() { runOnMainThread(this::openOnMainThread); }

    @SuppressLint("MissingPermission")
    private void openOnMainThread() {
        if (mDestroyed) return;
        if (mOpenRequested && mState != State.ERROR) return;
        mOpenRequested = true;
        mPaused = false;
        mLastCriticalCameraError = null;
        mCriticalCameraFailure = false;
        mPendingFrontFacing = null;
        mGeneration++;
        updateBackendLifecycle();
        publishSnapshot();
    }

    @Override public void close() { runOnMainThread(this::closeOnMainThread); }

    private void closeOnMainThread() {
        if (mDestroyed) return;
        if (!mOpenRequested && mState == State.CLOSED) return;
        mOpenRequested = false;
        mPaused = false;
        mPendingFrontFacing = null;
        mGeneration++;
        failPendingImage("Camera closed before image capture completed");
        cancelRecordingTimeouts();
        cancelDurationStop();
        publishSnapshot();
        if (mVideoRecording != null) {
            stopRecordingOnMainThread();
            return;
        }
        releaseAllUseCases();
        mLifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
        mState = State.CLOSED;
        publishSnapshot();
    }

    private void onProviderReady() {
        try {
            mCameraProvider = mCameraProviderFuture.get();
            initializeExtensions();
            publishSnapshot();
            getContext().sendBroadcast(new Intent(CameraView.ACTION_CAMERA_STATE_CHANGED)
                    .setPackage(getContext().getPackageName()));
            if (mOpenRequested) updateBackendLifecycle();
        } catch (ExecutionException e) {
            failProvider(e.getCause() == null ? e : e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failProvider(e);
        } catch (RuntimeException e) {
            failProvider(e);
        }
    }

    private void initializeExtensions() {
        if (mExtensionsManagerFuture != null || mCameraProvider == null) return;
        mExtensionsManagerFuture = ExtensionsManager.getInstanceAsync(getContext(), mCameraProvider);
        mExtensionsManagerFuture.addListener(() -> {
            try {
                mExtensionsManager = mExtensionsManagerFuture.get();
                if (!isExtensionAvailableInternal(getExtension())) {
                    super.setExtension(CameraView.Extension.NONE);
                }
                publishSnapshot();
                if (mOpenRequested && mVideoRecording == null && !mPaused && isHostActive()) {
                    reconcileUseCases(true);
                }
            } catch (ExecutionException error) {
                Log.w(TAG, "CameraX extensions are unavailable", error.getCause());
                super.setExtension(CameraView.Extension.NONE);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "CameraX extension initialization was interrupted", error);
                super.setExtension(CameraView.Extension.NONE);
            }
        }, mMainExecutor);
    }
    private void failProvider(@NonNull Throwable error) {
        mState = State.ERROR;
        Log.e(TAG, "Unable to initialize CameraX", error);
        if (mOpenRequested) onCameraError(error);
    }

    private void observeCameraState(@NonNull Camera camera) {
        if (mObservedCameraState != null && mCameraStateObserver != null) {
            mObservedCameraState.removeObserver(mCameraStateObserver);
        }
        long generation = mGeneration;
        LiveData<CameraState> source = camera.getCameraInfo().getCameraState();
        Observer<CameraState> observer = cameraState -> {
            if (generation != mGeneration || camera != mActiveCamera
                    || source != mObservedCameraState) {
                return;
            }
            onCameraStateChanged(cameraState);
        };
        mObservedCameraState = source;
        mCameraStateObserver = observer;
        source.observe(this, observer);
    }

    private void onCameraStateChanged(@NonNull CameraState cameraState) {
        CameraState.StateError error = cameraState.getError();
        if (error == null) {
            mLastCriticalCameraError = null;
            return;
        }
        if (error.getType() == CameraState.ErrorType.RECOVERABLE) {
            Log.w(TAG, "CameraX is recovering from camera error " + error.getCode(),
                    error.getCause());
            return;
        }
        if (mLastCriticalCameraError != null && mLastCriticalCameraError.equals(error.getCode())) return;
        mLastCriticalCameraError = error.getCode();
        mCriticalCameraFailure = true;
        mState = State.ERROR;
        onCameraError(new IllegalStateException(
                "CameraX critical camera error " + error.getCode(), error.getCause()));
    }

    @Override public boolean hasFrontFacingCamera() {
        synchronized (mSnapshotLock) {
            return mSnapshotHasFrontCamera;
        }
    }

    @Override public boolean isUsingFrontFacingCamera() {
        synchronized (mSnapshotLock) {
            return mSnapshotFrontFacing;
        }
    }

    @SuppressLint("MissingPermission")
    @Override public void toggleCamera() {
        runOnMainThread(() -> {
            boolean requested = mPendingFrontFacing == null ? mFrontFacing : mPendingFrontFacing;
            setLensFacingOnMainThread(!requested);
        });
    }

    @SuppressLint("MissingPermission")
    @Override public void setLensFacing(CameraView.LensFacing lensFacing) {
        if (lensFacing == null) throw new NullPointerException("lensFacing cannot be null");
        runOnMainThread(() -> setLensFacingOnMainThread(lensFacing == CameraView.LensFacing.FRONT));
    }

    @SuppressLint("MissingPermission")
    private void setLensFacingOnMainThread(boolean frontFacing) {
        if (!isLensAvailable(frontFacing)) return;
        if (mVideoRecording != null) {
            mPendingFrontFacing = frontFacing;
            Log.i(TAG, "Deferring CameraX lens switch until recording finalizes");
            return;
        }
        if (frontFacing == mFrontFacing) return;
        mFrontFacing = frontFacing;
        mGeneration++;
        publishSnapshot();
        if (mOpenRequested && mVideoRecording == null) reconcileUseCases(true);
    }

    private boolean isLensAvailable(boolean frontFacing) {
        ProcessCameraProvider provider = mCameraProvider;
        if (provider == null) return true;
        try {
            int facing = frontFacing ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
            if (provider.hasCamera(selectorFor(facing))) return true;
            Log.w(TAG, "Ignoring lens switch because the requested camera is unavailable");
        } catch (CameraInfoUnavailableException | RuntimeException error) {
            Log.w(TAG, "Unable to validate requested camera", error);
        }
        return false;
    }

    private void applyPendingLensFacing() {
        Boolean frontFacing = mPendingFrontFacing;
        mPendingFrontFacing = null;
        if (frontFacing == null || frontFacing == mFrontFacing || !isLensAvailable(frontFacing)) return;
        mFrontFacing = frontFacing;
        mGeneration++;
    }
    @Override public void focus(Rect focus, Rect metering) {
        if (focus == null || metering == null) return;
        runOnMainThread(() -> focusOnMainThread(focus, metering));
    }

    private void focusOnMainThread(@NonNull Rect focus, @NonNull Rect metering) {
        Camera camera = mActiveCamera;
        Preview preview = mPreview;
        int width = getWidth();
        int height = getHeight();
        if (camera == null || preview == null || width <= 0 || height <= 0) return;
        SurfaceOrientedMeteringPointFactory factory = new SurfaceOrientedMeteringPointFactory(width, height, preview);
        MeteringPoint focusPoint = factory.createPoint(
                CameraXMath.toSurfaceCoordinate(focus.centerX(), width),
                CameraXMath.toSurfaceCoordinate(focus.centerY(), height),
                CameraXMath.toMeteringSize(focus));
        MeteringPoint meteringPoint = factory.createPoint(
                CameraXMath.toSurfaceCoordinate(metering.centerX(), width),
                CameraXMath.toSurfaceCoordinate(metering.centerY(), height),
                CameraXMath.toMeteringSize(metering));
        FocusMeteringAction action = new FocusMeteringAction.Builder(focusPoint, FocusMeteringAction.FLAG_AF)
                .addPoint(meteringPoint, FocusMeteringAction.FLAG_AE | FocusMeteringAction.FLAG_AWB)
                .build();
        if (!camera.getCameraInfo().isFocusMeteringSupported(action)) {
            Log.d(TAG, "Focus/metering action is not supported by this camera");
            return;
        }
        ListenableFuture<FocusMeteringResult> result = camera.getCameraControl().startFocusAndMetering(action);
        onFocusRequested(focus);
        result.addListener(() -> {
            boolean locked = false;
            try {
                locked = result.get().isFocusSuccessful();
                Log.d(TAG, locked ? "Successfully focused" : "Focus completed without a lock");
            } catch (ExecutionException e) {
                Log.w(TAG, "Focus/metering failed", e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "Focus/metering interrupted", e);
            }
            onFocusCompleted(locked);
        }, mMainExecutor);
    }

    @Override public boolean isFocusSupported() {
        Camera camera = mActiveCamera;
        Preview preview = mPreview;
        int width = getWidth();
        int height = getHeight();
        if (camera == null || preview == null || width <= 0 || height <= 0) return false;
        // Asked with a point rather than in the abstract, because that is the only question
        // CameraX answers: support depends on the lens the point would land on.
        MeteringPoint center = new SurfaceOrientedMeteringPointFactory(width, height, preview)
                .createPoint(0.5f, 0.5f);
        return camera.getCameraInfo().isFocusMeteringSupported(
                new FocusMeteringAction.Builder(center, FocusMeteringAction.FLAG_AF).build());
    }

    @Override public void setZoomLevel(int zoomLevel) {
        runOnMainThread(() -> setZoomOnMainThread(zoomLevel));
    }

    private void setZoomOnMainThread(int zoomLevel) {
        int clamped = Math.max(0, Math.min(zoomLevel, MAX_ZOOM_LEVEL));
        Camera camera = mActiveCamera;
        if (camera == null) {
            mZoomLevel = clamped;
            publishSnapshot();
            return;
        }
        ListenableFuture<Void> result = camera.getCameraControl().setLinearZoom(clamped / (float) MAX_ZOOM_LEVEL);
        result.addListener(() -> {
            try {
                result.get();
                mZoomLevel = clamped;
                publishSnapshot();
            } catch (ExecutionException e) {
                Log.w(TAG, "Zoom request failed", e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "Zoom request interrupted", e);
            }
        }, mMainExecutor);
    }

    @Override public int getZoomLevel() {
        synchronized (mSnapshotLock) {
            return mSnapshotZoomLevel;
        }
    }
    @Override public int getMaxZoomLevel() { return MAX_ZOOM_LEVEL; }
    @Override public boolean isZoomSupported() {
        synchronized (mSnapshotLock) {
            return mSnapshotZoomSupported;
        }
    }
    @Override public boolean hasFlash() {
        synchronized (mSnapshotLock) {
            return mSnapshotHasFlash;
        }
    }
    @Override public void setFlash(CameraView.Flash flash) {
        super.setFlash(flash);
        runOnMainThread(() -> { if (mImageCapture != null) mImageCapture.setFlashMode(getFlashMode()); });
    }

    @Override public void setDynamicRange(CameraView.DynamicRange dynamicRange) {
        if (dynamicRange == null) throw new NullPointerException("dynamicRange cannot be null");
        if (dynamicRange == CameraView.DynamicRange.HDR && mActiveCamera != null
                && !supportsHdr(mActiveCamera.getCameraInfo())) {
            Log.w(TAG, "Ignoring HDR request because the active camera does not support it");
            return;
        }
        super.setDynamicRange(dynamicRange);
        if (dynamicRange == CameraView.DynamicRange.HDR && isLowLightBoostEnabled()) {
            setLowLightBoostEnabled(false);
        }
        rebindConfiguration();
    }

    @Override public boolean isDynamicRangeSupported(CameraView.DynamicRange dynamicRange) {
        if (dynamicRange == null) return false;
        if (dynamicRange == CameraView.DynamicRange.SDR) return true;
        synchronized (mSnapshotLock) {
            return mSnapshotHdrSupported;
        }
    }

    @Override public void setExtension(CameraView.Extension extension) {
        if (extension == null) throw new NullPointerException("extension cannot be null");
        runOnMainThread(() -> {
            if (extension != CameraView.Extension.NONE && mExtensionsManager != null
                    && !isExtensionAvailableInternal(extension)) {
                Log.w(TAG, "Ignoring unsupported CameraX extension " + extension);
                return;
            }
            super.setExtension(extension);
            if (mOpenRequested && mVideoRecording == null && !mPaused && isHostActive()) {
                reconcileUseCases(true);
            }
        });
    }

    @Override public boolean isExtensionSupported(CameraView.Extension extension) {
        if (extension == null) return false;
        synchronized (mSnapshotLock) {
            return mSnapshotExtensions.contains(extension);
        }
    }

    private boolean isExtensionAvailableInternal(@NonNull CameraView.Extension extension) {
        if (extension == CameraView.Extension.NONE) return true;
        ExtensionsManager manager = mExtensionsManager;
        if (manager == null || mCameraProvider == null) return false;
        CameraSelector selector = selectorFor(mFrontFacing
                ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK);
        try {
            return manager.isExtensionAvailable(selector, toExtensionMode(extension));
        } catch (IllegalArgumentException | IllegalStateException error) {
            Log.w(TAG, "Unable to query CameraX extension " + extension, error);
            return false;
        }
    }

    private static int toExtensionMode(@NonNull CameraView.Extension extension) {
        switch (extension) {
            case BOKEH: return androidx.camera.extensions.ExtensionMode.BOKEH;
            case HDR: return androidx.camera.extensions.ExtensionMode.HDR;
            case NIGHT: return androidx.camera.extensions.ExtensionMode.NIGHT;
            case FACE_RETOUCH: return androidx.camera.extensions.ExtensionMode.FACE_RETOUCH;
            case AUTO: return androidx.camera.extensions.ExtensionMode.AUTO;
            case NONE:
            default: return androidx.camera.extensions.ExtensionMode.NONE;
        }
    }
    @Override public void setFrontVideoMirroringEnabled(boolean enabled) {
        super.setFrontVideoMirroringEnabled(enabled);
    }

    @Override public void setPreviewStabilizationEnabled(boolean enabled) {
        if (enabled && mActiveCamera != null && !isPreviewStabilizationSupported()) {
            Log.w(TAG, "Ignoring preview stabilization because the active camera does not support it");
            return;
        }
        super.setPreviewStabilizationEnabled(enabled);
        rebindConfiguration();
    }

    @Override public boolean isPreviewStabilizationSupported() {
        synchronized (mSnapshotLock) {
            return mSnapshotPreviewStabilizationSupported;
        }
    }

    @Override public void setVideoStabilizationEnabled(boolean enabled) {
        if (enabled && mActiveCamera != null && !isVideoStabilizationSupported()) {
            Log.w(TAG, "Ignoring video stabilization because the active camera does not support it");
            return;
        }
        super.setVideoStabilizationEnabled(enabled);
    }

    @Override public boolean isVideoStabilizationSupported() {
        synchronized (mSnapshotLock) {
            return mSnapshotVideoStabilizationSupported;
        }
    }

    @Override public void setExposureCompensation(int exposureCompensation) {
        runOnMainThread(() -> setExposureCompensationOnMainThread(exposureCompensation));
    }

    private void setExposureCompensationOnMainThread(int exposureCompensation) {
        Camera camera = mActiveCamera;
        if (camera == null) {
            super.setExposureCompensation(exposureCompensation);
            return;
        }
        ExposureState exposure = camera.getCameraInfo().getExposureState();
        if (!exposure.isExposureCompensationSupported()) {
            Log.w(TAG, "Ignoring exposure request because the active camera does not support it");
            return;
        }
        Range<Integer> range = exposure.getExposureCompensationRange();
        int clamped = Math.max(range.getLower(), Math.min(exposureCompensation, range.getUpper()));
        ListenableFuture<Integer> result = camera.getCameraControl().setExposureCompensationIndex(clamped);
        result.addListener(() -> {
            try {
                super.setExposureCompensation(result.get());
            } catch (ExecutionException error) {
                Log.w(TAG, "Exposure request failed", error.getCause());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "Exposure request interrupted", error);
            }
        }, mMainExecutor);
    }

    @Override public int getMinExposureCompensation() {
        synchronized (mSnapshotLock) {
            return mSnapshotMinExposure;
        }
    }

    @Override public int getMaxExposureCompensation() {
        synchronized (mSnapshotLock) {
            return mSnapshotMaxExposure;
        }
    }

    @Override public boolean isExposureCompensationSupported() {
        synchronized (mSnapshotLock) {
            return mSnapshotExposureSupported;
        }
    }

    @Override public void setTorchEnabled(boolean enabled) {
        runOnMainThread(() -> setTorchEnabledOnMainThread(enabled));
    }

    private void setTorchEnabledOnMainThread(boolean enabled) {
        Camera camera = mActiveCamera;
        if (enabled && camera != null && !camera.getCameraInfo().hasFlashUnit()) {
            Log.w(TAG, "Ignoring torch request because the active camera has no flash unit");
            return;
        }
        if (enabled && isLowLightBoostEnabled()) {
            super.setLowLightBoostEnabled(false);
            if (camera != null && camera.getCameraInfo().isLowLightBoostSupported()) {
                camera.getCameraControl().enableLowLightBoostAsync(false);
            }
        }
        super.setTorchEnabled(enabled);
        if (camera == null) return;
        ListenableFuture<Void> result = camera.getCameraControl().enableTorch(enabled);
        reportControlFailure("Torch", result);
    }

    @Override public void setLowLightBoostEnabled(boolean enabled) {
        runOnMainThread(() -> setLowLightBoostEnabledOnMainThread(enabled));
    }

    private void setLowLightBoostEnabledOnMainThread(boolean enabled) {
        Camera camera = mActiveCamera;
        if (enabled && getDynamicRange() == CameraView.DynamicRange.HDR) {
            Log.w(TAG, "Ignoring low-light boost because HDR is enabled");
            return;
        }
        if (enabled && camera != null && !camera.getCameraInfo().isLowLightBoostSupported()) {
            Log.w(TAG, "Ignoring low-light boost because the active camera does not support it");
            return;
        }
        if (enabled && isTorchEnabled()) {
            super.setTorchEnabled(false);
            if (camera != null) camera.getCameraControl().enableTorch(false);
        }
        super.setLowLightBoostEnabled(enabled);
        if (camera == null || !camera.getCameraInfo().isLowLightBoostSupported()) return;
        ListenableFuture<Void> result = camera.getCameraControl().enableLowLightBoostAsync(enabled);
        reportControlFailure("Low-light boost", result);
    }

    @Override public boolean isLowLightBoostSupported() {
        synchronized (mSnapshotLock) {
            return mSnapshotLowLightBoostSupported;
        }
    }

    private void reportControlFailure(@NonNull String name, @NonNull ListenableFuture<?> result) {
        result.addListener(() -> {
            try {
                result.get();
            } catch (ExecutionException error) {
                Log.w(TAG, name + " request failed", error.getCause());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                Log.w(TAG, name + " request interrupted", error);
            }
        }, mMainExecutor);
    }

    private void rebindConfiguration() {
        runOnMainThread(() -> {
            if (mOpenRequested && mVideoRecording == null && !mPaused && isHostActive()) {
                reconcileUseCases(true);
            }
        });
    }
    @Override public void setMaxVideoDuration(long duration) {
        super.setMaxVideoDuration(duration);
        runOnMainThread(() -> {
            if (mVideoRecording != null) scheduleDurationStop(mVideoOperationId);
        });
    }

    @Override public void setQuality(CameraView.Quality quality) {
        super.setQuality(quality);
        runOnMainThread(() -> {
            if (mOpenRequested && mVideoRecording == null && !mPaused) reconcileUseCases(true);
        });
    }

    @Override public void setMatchPreviewAspectRatio(boolean enabled) {
        super.setMatchPreviewAspectRatio(enabled);
        runOnMainThread(() -> {
            if (mOpenRequested && mVideoRecording == null && !mPaused) reconcileUseCases(true);
        });
    }

    /**
     * Marks the backend paused without stopping the camera, which is as far as CameraX goes.
     *
     * <p>Camera2 stops its repeating request here, so the preview freezes while the still-capture
     * surface stays configured. CameraX has no public equivalent: the only lever is unbinding the
     * use cases, and that cannot be used here. CameraView calls pause() immediately before
     * takePicture() when confirmation is enabled, so unbinding would drop the ImageCapture the
     * capture is about to use, and it calls pause() before stopRecording(), where unbinding would
     * destroy a recording that has not finalized.</p>
     *
     * <p>Nothing visible depends on it, because the confirmation preview is drawn over the
     * TextureView either way. The cost is power: the camera keeps streaming while the user looks
     * at a captured frame, where Camera2's would have stopped. Closing that gap needs CameraX to
     * expose something narrower than unbind.</p>
     */
    @Override public void pause() {
        runOnMainThread(() -> {
            mPaused = true;
            publishSnapshot();
        });
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    @Override public void resume() {
        runOnMainThread(() -> {
            if (!mPaused) return;
            mPaused = false;
            updateBackendLifecycle();
            publishSnapshot();
        });
    }

    @Override public boolean isPaused() {
        synchronized (mSnapshotLock) {
            return mSnapshotPaused;
        }
    }

    @Override protected boolean startBarcodeScanner(
            CameraView.BarcodeDetectorListener listener,
            @Barcode.Format int format,
            @Barcode.Format int... formats) {
        if (listener == null) throw new NullPointerException("listener cannot be null");
        CameraXBarcodeAnalyzer analyzer;
        try {
            analyzer = new CameraXBarcodeAnalyzer(listener, mMainExecutor, format, formats);
        } catch (RuntimeException | LinkageError error) {
            // ML Kit is initialized by a manifest provider and its detector is delivered by Play
            // services, so building the client can fail on a device that has neither. Reporting it
            // here lets CameraView fall back to the shared scanner; the client used to be built
            // inside the posted task below, where the same failure took the whole app down.
            Log.w(TAG, "CameraX cannot scan barcodes on this device", error);
            return false;
        }
        runOnMainThread(() -> startBarcodeScannerOnMainThread(analyzer));
        return true;
    }

    private void startBarcodeScannerOnMainThread(@NonNull CameraXBarcodeAnalyzer analyzer) {
        if (mBarcodeAnalyzer != null) {
            Log.w(TAG, "CameraX barcode analysis is already active");
            analyzer.close();
            return;
        }
        mAnalysisExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "CameraView-BarcodeAnalysis");
            thread.setDaemon(true);
            return thread;
        });
        mBarcodeAnalyzer = analyzer;
        if (mOpenRequested && mVideoRecording == null && !mPaused && isHostActive()) {
            reconcileUseCases(true);
        }
    }

    @Override protected void stopBarcodeScanner() {
        runOnMainThread(this::stopBarcodeScannerOnMainThread);
    }

    private void stopBarcodeScannerOnMainThread() {
        releaseBarcodeAnalysisResources();
        if (mOpenRequested && mVideoRecording == null && !mPaused && isHostActive()) {
            reconcileUseCases(false);
        }
    }

    private void releaseBarcodeAnalysisResources() {
        CameraXBarcodeAnalyzer analyzer = mBarcodeAnalyzer;
        mBarcodeAnalyzer = null;
        if (analyzer != null) analyzer.close();
        ExecutorService executor = mAnalysisExecutor;
        mAnalysisExecutor = null;
        if (executor != null) executor.shutdownNow();
    }
    @Override public void takePicture(File file) {
        if (file == null) throw new NullPointerException("file cannot be null");
        runOnMainThread(() -> takePictureOnMainThread(file));
    }

    private void takePictureOnMainThread(@NonNull File file) {
        ImageCapture imageCapture = mImageCapture;
        if (!mOpenRequested || imageCapture == null || isRecording() || mPendingImageFile != null) {
            Log.w(TAG, "Cannot take a picture in the current CameraX state: " + mState);
            onImageFailed();
            return;
        }
        long operationId = ++mImageOperationId;
        mPendingImageFile = file;
        scheduleImageTimeout(operationId);
        ImageCapture.Metadata metadata = new ImageCapture.Metadata();
        metadata.setLocation(getLocation());
        // The setting as well as the lens. This used to be mFrontFacing alone, so a caller who
        // turned video mirroring off got un-mirrored video and still-mirrored photos out of the
        // same session, with nothing to say so.
        metadata.setReversedHorizontal(isMirroringThisPicture());
        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(file)
                .setMetadata(metadata).build();
        imageCapture.takePicture(options, mMainExecutor, new ImageCapture.OnImageSavedCallback() {
            @Override public void onImageSaved(@NonNull ImageCapture.OutputFileResults results) {
                if (!completePendingImage(operationId)) return;
                showImageConfirmation(file);
            }
            @Override public void onError(@NonNull ImageCaptureException error) {
                if (!completePendingImage(operationId)) return;
                Log.w(TAG, "Image capture failed", error);
                onImageFailed();
            }
        });
    }

    private void scheduleImageTimeout(long operationId) {
        cancelImageTimeout();
        mImageTimeoutTask = () -> {
            if (operationId != mImageOperationId || mPendingImageFile == null) return;
            failPendingImage("CameraX image capture timed out");
        };
        mMainHandler.postDelayed(mImageTimeoutTask, IMAGE_CAPTURE_TIMEOUT_MILLIS);
    }

    private boolean completePendingImage(long operationId) {
        if (operationId != mImageOperationId || mPendingImageFile == null) return false;
        mImageOperationId++;
        mPendingImageFile = null;
        cancelImageTimeout();
        // Posted rather than called: this runs inside a CameraX callback, and a rebind from in
        // there would tear down the pipeline that is still reporting.
        mMainHandler.post(this::rebindForSettledSize);
        return true;
    }

    private void failPendingImage(@NonNull String reason) {
        if (mPendingImageFile == null) return;
        Log.e(TAG, reason);
        mImageOperationId++;
        mPendingImageFile = null;
        cancelImageTimeout();
        onImageFailed();
        mMainHandler.post(this::rebindForSettledSize);
    }

    private void cancelImageTimeout() {
        if (mImageTimeoutTask == null) return;
        mMainHandler.removeCallbacks(mImageTimeoutTask);
        mImageTimeoutTask = null;
    }
    /**
     * Where the device already knows it is, for the paths that run on the main thread.
     *
     * <p>{@link #getLocation} waits for a fix, briefly, which is fine from a background thread
     * and not from this one. Starting a recording happens on the main thread because CameraX
     * binds there, so it takes what is already known or nothing at all.</p>
     */
    @SuppressLint("MissingPermission")
    @Nullable private Location getLastKnownLocation() {
        if (!PermissionChecker.hasPermissions(getContext(), Manifest.permission.ACCESS_FINE_LOCATION)) return null;
        return LocationProvider.getLastKnownGPSLocation(getContext(), STALE_LOCATION_MILLIS);
    }

    @SuppressLint("MissingPermission")
    @Nullable private Location getLocation() {
        if (!PermissionChecker.hasPermissions(getContext(), Manifest.permission.ACCESS_FINE_LOCATION)) return null;
        return LocationProvider.getGPSLocation(getContext(), STALE_LOCATION_MILLIS, GPS_TIMEOUT_MILLIS);
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @Override public void startRecording(File file) {
        if (file == null) throw new NullPointerException("file cannot be null");
        runOnMainThread(() -> startRecordingOnMainThread(file));
    }

    @SuppressLint("MissingPermission")
    private void startRecordingOnMainThread(@NonNull File file) {
        if (!mOpenRequested || mPaused || mState != State.OPEN || mVideoRecording != null) {
            Log.w(TAG, "Cannot start recording in the current CameraX state: " + mState);
            onVideoFailed();
            return;
        }
        long operationId = ++mVideoOperationId;
        mRecordedVideoDurationMillis = 0;
        Recorder recorder = new Recorder.Builder().setQualitySelector(createQualitySelector()).build();
        CameraInfo cameraInfo = mActiveCamera == null ? null : mActiveCamera.getCameraInfo();
        VideoCapture.Builder<Recorder> videoBuilder = new VideoCapture.Builder<>(recorder)
                .setMirrorMode(isFrontVideoMirroringEnabled()
                        ? MirrorMode.MIRROR_MODE_ON_FRONT_ONLY : MirrorMode.MIRROR_MODE_OFF)
                .setTargetRotation(getTargetRotation());
        if (cameraInfo != null) {
            videoBuilder.setDynamicRange(selectedDynamicRange(cameraInfo));
            if (isVideoStabilizationEnabled()
                    && Recorder.getVideoCapabilities(cameraInfo).isStabilizationSupported()) {
                videoBuilder.setVideoStabilizationEnabled(true);
            }
        }
        mVideoCapture = videoBuilder.build();
        if (!reconcileUseCases(false)) {
            mVideoCapture = null;
            reconcileUseCases(false);
            Log.w(TAG, "CameraX could not bind Preview and VideoCapture together");
            onVideoFailed();
            return;
        }
        try {
            FileOutputOptions.Builder output = new FileOutputOptions.Builder(file);
            if (getMaxVideoSize() != CameraView.INDEFINITE_VIDEO_SIZE) output.setFileSizeLimit(getMaxVideoSize());
            Location where = getLastKnownLocation();
            if (where != null) {
                // Camera2 and the old backend have always recorded where a video was taken and
                // this one did not, so the same recording came out of CameraX with no location at
                // all - the sort of difference between backends nobody goes looking for.
                output.setLocation(where);
            }
            PendingRecording pending = recorder.prepareRecording(getContext(), output.build());
            if (PermissionChecker.hasPermissions(getContext(), Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled();
            mVideoFile = file;
            mState = State.RECORDING;
            mVideoRecording = pending.start(mMainExecutor, event -> handleVideoEvent(operationId, file, event));
            scheduleRecordingStartTimeout(operationId);
            publishSnapshot();
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to start CameraX recording", error);
            mVideoCapture = null;
            mVideoFile = null;
            mVideoRecording = null;
            cancelRecordingTimeouts();
            mState = State.OPEN;
            reconcileUseCases(false);
            publishSnapshot();
            onVideoFailed();
        }
    }

    private void handleVideoEvent(long operationId, @NonNull File file, @NonNull VideoRecordEvent event) {
        if (operationId != mVideoOperationId) return;
        mRecordedVideoDurationMillis = Math.max(mRecordedVideoDurationMillis,
                event.getRecordingStats().getRecordedDurationNanos() / 1_000_000L);
        if (event instanceof VideoRecordEvent.Start) {
            cancelRecordingStartTimeout();
            mState = State.RECORDING;
            publishSnapshot();
            return;
        }
        if (event instanceof VideoRecordEvent.Status) {
            scheduleDurationStop(operationId);
            return;
        }
        if (!(event instanceof VideoRecordEvent.Finalize)) return;
        VideoRecordEvent.Finalize finalized = (VideoRecordEvent.Finalize) event;
        cancelDurationStop();
        cancelRecordingTimeouts();
        mVideoOperationId++;
        mVideoRecording = null;
        mVideoCapture = null;
        mVideoFile = null;
        applyPendingLensFacing();
        boolean success = isSuccessfulFinalize(finalized) && file.exists();
        if (mCriticalCameraFailure) {
            releaseAllUseCases();
            mLifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
            mState = State.ERROR;
        } else if (mOpenRequested && isHostActive()) {
            mState = State.OPEN;
            if (!mPaused) reconcileUseCases(false);
        } else {
            releaseAllUseCases();
            mLifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
            mState = mOpenRequested ? State.SUSPENDED : State.CLOSED;
        }
        publishSnapshot();
        if (success) {
            showVideoConfirmation(file);
        } else {
            Log.e(TAG, "CameraX recording failed: " + describeFinalize(finalized), finalized.getCause());
            onVideoFailed();
        }
    }

    private static boolean isSuccessfulFinalize(@NonNull VideoRecordEvent.Finalize event) {
        switch (event.getError()) {
            case VideoRecordEvent.Finalize.ERROR_NONE:
            case VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED:
            case VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED:
                return true;
            default:
                return false;
        }
    }

    @NonNull private static String describeFinalize(@NonNull VideoRecordEvent.Finalize event) {
        return String.format(Locale.US, "error=%d, stats=%s", event.getError(), event.getRecordingStats());
    }

    @Override public void stopRecording() { runOnMainThread(this::stopRecordingOnMainThread); }

    private void stopRecordingOnMainThread() {
        Recording recording = mVideoRecording;
        if (recording == null || mState == State.STOPPING) return;
        cancelDurationStop();
        mState = State.STOPPING;
        try {
            recording.stop();
            scheduleRecordingFinalizeTimeout(mVideoOperationId);
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to stop CameraX recording", error);
            File file = mVideoFile;
            mVideoOperationId++;
            mVideoRecording = null;
            mVideoCapture = null;
            mVideoFile = null;
            cancelRecordingTimeouts();
            applyPendingLensFacing();
            if (mOpenRequested && isHostActive() && !mCriticalCameraFailure) {
                mState = State.OPEN;
                if (!mPaused) reconcileUseCases(false);
            } else {
                releaseAllUseCases();
                mLifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
                mState = mCriticalCameraFailure ? State.ERROR
                        : (mOpenRequested ? State.SUSPENDED : State.CLOSED);
            }
            publishSnapshot();
            if (file != null) onVideoFailed();
        }
    }

    @Override public boolean isRecording() {
        synchronized (mSnapshotLock) {
            return mSnapshotRecording;
        }
    }

    private void scheduleRecordingStartTimeout(long operationId) {
        cancelRecordingStartTimeout();
        mRecordingStartTimeoutTask = () -> {
            if (operationId != mVideoOperationId || mVideoRecording == null) return;
            Log.e(TAG, "CameraX recording did not start before the timeout");
            stopRecordingOnMainThread();
        };
        mMainHandler.postDelayed(mRecordingStartTimeoutTask, RECORDING_START_TIMEOUT_MILLIS);
    }

    private void scheduleRecordingFinalizeTimeout(long operationId) {
        cancelRecordingFinalizeTimeout();
        mRecordingFinalizeTimeoutTask = () -> {
            if (operationId != mVideoOperationId || mVideoRecording == null) return;
            Log.e(TAG, "CameraX recording did not finalize before the timeout");
            Recording recording = mVideoRecording;
            mVideoOperationId++;
            mVideoRecording = null;
            mVideoCapture = null;
            mVideoFile = null;
            cancelDurationStop();
            cancelRecordingTimeouts();
            try {
                recording.close();
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to close timed-out CameraX recording", error);
            }
            applyPendingLensFacing();
            if (mOpenRequested && isHostActive() && !mCriticalCameraFailure) {
                mState = State.OPEN;
                if (!mPaused) reconcileUseCases(false);
            } else {
                releaseAllUseCases();
                mLifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
                mState = mCriticalCameraFailure ? State.ERROR
                        : (mOpenRequested ? State.SUSPENDED : State.CLOSED);
            }
            publishSnapshot();
            onVideoFailed();
        };
        mMainHandler.postDelayed(mRecordingFinalizeTimeoutTask, RECORDING_FINALIZE_TIMEOUT_MILLIS);
    }

    private void cancelRecordingStartTimeout() {
        if (mRecordingStartTimeoutTask == null) return;
        mMainHandler.removeCallbacks(mRecordingStartTimeoutTask);
        mRecordingStartTimeoutTask = null;
    }

    private void cancelRecordingFinalizeTimeout() {
        if (mRecordingFinalizeTimeoutTask == null) return;
        mMainHandler.removeCallbacks(mRecordingFinalizeTimeoutTask);
        mRecordingFinalizeTimeoutTask = null;
    }

    private void cancelRecordingTimeouts() {
        cancelRecordingStartTimeout();
        cancelRecordingFinalizeTimeout();
    }
    private void scheduleDurationStop(long operationId) {
        cancelDurationStop();
        long remaining = remainingDurationMillis(
                getMaxVideoDuration(), mRecordedVideoDurationMillis);
        if (remaining < 0) return;
        mDurationStopTask = () -> {
            if (operationId == mVideoOperationId && mVideoRecording != null) {
                stopRecordingOnMainThread();
            }
        };
        if (remaining == 0) {
            mDurationStopTask.run();
        } else {
            mMainHandler.postDelayed(mDurationStopTask, remaining);
        }
    }

    static long remainingDurationMillis(long maximum, long recorded) {
        if (maximum == CameraView.INDEFINITE_VIDEO_DURATION
                || maximum <= 0 || recorded <= 0) {
            return -1;
        }
        return Math.max(0, maximum - recorded);
    }

    private void cancelDurationStop() {
        if (mDurationStopTask != null) {
            mMainHandler.removeCallbacks(mDurationStopTask);
            mDurationStopTask = null;
        }
    }

    private static boolean supportsHdr(@NonNull CameraInfo cameraInfo) {
        Set<DynamicRange> cameraRanges = cameraInfo.querySupportedDynamicRanges(
                Collections.singleton(DynamicRange.HDR_UNSPECIFIED_10_BIT));
        Set<DynamicRange> videoRanges = Recorder.getVideoCapabilities(cameraInfo)
                .getSupportedDynamicRanges();
        for (DynamicRange range : videoRanges) {
            if (range.getEncoding() != DynamicRange.ENCODING_SDR && cameraRanges.contains(range)) {
                return true;
            }
        }
        return false;
    }

    @NonNull private DynamicRange selectedDynamicRange(@NonNull CameraInfo cameraInfo) {
        if (getDynamicRange() == CameraView.DynamicRange.SDR) return DynamicRange.SDR;
        Set<DynamicRange> cameraRanges = cameraInfo.querySupportedDynamicRanges(
                Collections.singleton(DynamicRange.HDR_UNSPECIFIED_10_BIT));
        VideoCapabilities capabilities = Recorder.getVideoCapabilities(cameraInfo);
        for (DynamicRange range : capabilities.getSupportedDynamicRanges()) {
            if (range.getEncoding() != DynamicRange.ENCODING_SDR && cameraRanges.contains(range)) {
                return range;
            }
        }
        Log.w(TAG, "Falling back to SDR because HDR is unavailable for this lens");
        return DynamicRange.SDR;
    }
    @NonNull private QualitySelector createQualitySelector() {
        List<Quality> priorities = videoQualityPriority(getQuality());
        Quality lowestPreference = priorities.get(priorities.size() - 1);
        return QualitySelector.fromOrderedList(priorities, FallbackStrategy.lowerQualityOrHigherThan(lowestPreference));
    }

    @NonNull static List<Quality> videoQualityPriority(@NonNull CameraView.Quality quality) {
        List<Quality> qualities = new ArrayList<>();
        switch (quality) {
            case MAX:
                qualities.add(Quality.UHD);
                qualities.add(Quality.FHD);
                qualities.add(Quality.HD);
                qualities.add(Quality.SD);
                break;
            case HIGH:
                qualities.add(Quality.FHD);
                qualities.add(Quality.HD);
                qualities.add(Quality.SD);
                break;
            case MEDIUM:
                qualities.add(Quality.HD);
                qualities.add(Quality.SD);
                break;
            case LOW:
                qualities.add(Quality.SD);
                break;
        }
        return qualities;
    }
    @SuppressLint("MissingPermission")
    private boolean reconcileUseCases(boolean reportFailure) {
        if (mDestroyed) return false;
        if (!mOpenRequested || mPaused) return false;
        if (!isHostActive()) {
            mState = State.SUSPENDED;
            return false;
        }
        ProcessCameraProvider provider = mCameraProvider;
        if (provider == null) {
            mState = State.WAITING_FOR_PROVIDER;
            return false;
        }
        if (mVideoRecording != null) return true;
        long generation = ++mGeneration;
        mState = State.BINDING;
        CameraSelector selector = getCameraSelector();
        try {
            if (!provider.hasCamera(selector)) throw new IllegalStateException("No camera matches the requested lens facing");
            CameraInfo cameraInfo = provider.getCameraInfo(selector);
            mSensorOrientation = cameraInfo.getSensorRotationDegrees();
            Preview displayPreview = createDisplayPreview(generation, cameraInfo);
            // A still output is wanted alongside a recording, not instead of it: that is the
            // combination a camera app's video-snapshot button uses. Barcode analysis is the one
            // thing that genuinely takes the slot, because it needs the frames itself.
            ImageCapture imageCapture = mBarcodeAnalyzer == null
                    ? createImageCapture(cameraInfo) : null;
            ImageAnalysis imageAnalysis = mVideoCapture == null && mBarcodeAnalyzer != null
                    ? createImageAnalysis() : null;
            Map<VideoRecorder.SurfaceProvider, Preview> streamPreviews = createStreamPreviews(generation);
            List<UseCase> requested = new ArrayList<>();
            requested.add(displayPreview);
            if (mVideoCapture != null) requested.add(mVideoCapture);
            if (imageCapture != null) requested.add(imageCapture);
            else if (imageAnalysis != null) requested.add(imageAnalysis);
            requested.addAll(streamPreviews.values());

            // Not every camera can hold a preview, a recording and a still at once. Asked for one
            // that cannot, the bind fails and the recording is lost with it, so the still is the
            // part that gives way.
            if (mVideoCapture != null && imageCapture != null
                    && !cameraInfo.isSessionConfigSupported(createSessionConfig(requested))) {
                Log.d(TAG, "This camera cannot take a picture while recording");
                requested.remove(imageCapture);
                imageCapture = null;
            }
            mSnapshotWhileRecording = mVideoCapture != null && imageCapture != null;
            if (!streamPreviews.isEmpty()
                    && !provider.getCameraInfo(selector).isSessionConfigSupported(createSessionConfig(requested))) {
                if (isPreviewStabilizationEnabled()) {
                    displayPreview = createDisplayPreview(generation, cameraInfo, false);
                    requested.set(0, displayPreview);
                    Log.w(TAG, "Preview stabilization is unavailable while a custom stream is attached");
                }
                if (!provider.getCameraInfo(selector)
                        .isSessionConfigSupported(createSessionConfig(requested))) {
                    // Some CameraX backends report false for a second Preview even though the real
                    // bind succeeds. Keep this as diagnostics and let the atomic bind decide.
                    Log.w(TAG, "CameraX could not preflight the requested streaming combination");
                }
            }
            ensureFrameEffect();
            // CameraX matches an effect to the use cases its targets name, and it cannot tell two
            // Previews apart, so an effect and a custom stream cannot be bound together. The
            // stream wins: it has a caller waiting on bytes, where an effect's caller is drawing.
            CameraEffect effect = streamPreviews.isEmpty() ? mFrameEffect : null;
            if (effect == null && mFrameEffect != null) {
                Log.w(TAG, "Camera frames bypass GL while a custom stream is attached");
            }
            unbindCurrentUseCases();
            Camera camera = bindAtomically(selector, requested, effect);
            if (generation != mGeneration || !mOpenRequested || mPaused) {
                provider.unbind(requested.toArray(new UseCase[0]));
                return false;
            }
            mPreview = displayPreview;
            mImageCapture = imageCapture;
            mImageAnalysis = imageAnalysis;
            mBoundStreamPreviews.clear();
            mBoundStreamPreviews.putAll(streamPreviews);
            mBoundUseCases.clear();
            mBoundUseCases.addAll(requested);
            mActiveCamera = camera;
            observeCameraState(camera);
            mState = mVideoCapture == null ? State.OPEN : State.BINDING;
            publishSnapshot();
            // Bound, which is the first moment a capture can succeed.
            onCameraReady();
            if (mZoomLevel > 0) setZoomOnMainThread(mZoomLevel);
            if (getExposureCompensation() != 0) {
                setExposureCompensationOnMainThread(getExposureCompensation());
            }
            if (isTorchEnabled()) setTorchEnabledOnMainThread(true);
            if (isLowLightBoostEnabled()) setLowLightBoostEnabledOnMainThread(true);
            return true;
        } catch (CameraInfoUnavailableException | IllegalArgumentException | IllegalStateException error) {
            unbindCurrentUseCases();
            mState = State.ERROR;
            Log.e(TAG, "Unable to bind CameraX use cases", error);
            if (reportFailure) onCameraError(error);
            return false;
        }
    }

    @NonNull private Camera bindAtomically(@NonNull CameraSelector selector,
            @NonNull List<UseCase> useCases, @Nullable CameraEffect effect) {
        int width = getWidth();
        int height = getHeight();
        boolean hasViewPort = isMatchPreviewAspectRatioEnabled() && width > 0 && height > 0;
        if (!hasViewPort && effect == null) {
            return mCameraProvider.bindToLifecycle(this, selector, useCases.toArray(new UseCase[0]));
        }
        // An effect only reaches the camera through a UseCaseGroup, so a bind that carries one
        // goes this way whether or not there is a view port to set.
        UseCaseGroup.Builder group = new UseCaseGroup.Builder();
        if (hasViewPort) group.setViewPort(createViewPort(width, height));
        for (UseCase useCase : useCases) group.addUseCase(useCase);
        if (effect != null) group.addEffect(effect);
        return mCameraProvider.bindToLifecycle(this, selector, group.build());
    }

    @Override public boolean isFrameEffectSupported() {
        return true;
    }

    @Override public void setFrameRenderer(@Nullable FrameRenderer renderer) {
        super.setFrameRenderer(renderer);
        runOnMainThread(this::applyFrameEffect);
    }

    @Override public void setCameraTextureProvider(@Nullable CameraTextureProvider provider) {
        super.setCameraTextureProvider(provider);
        runOnMainThread(this::applyFrameEffect);
    }

    /**
     * Brings the effect into line with what the caller has asked for.
     *
     * <p>Rebinds only when it has to. Changing what draws is a field on a renderer that is already
     * running, and rebinding for it would drop frames and restart the context for nothing;
     * changing whether frames go through GL at all, or whose texture they go through, is a
     * different pipeline and does need one.</p>
     */
    private void applyFrameEffect() {
        if (mDestroyed) return;
        FrameRenderer renderer = getFrameRenderer();
        CameraTextureProvider textureProvider = getCameraTextureProvider();
        FrameEffect effect = mFrameEffect;
        boolean wanted = renderer != null || textureProvider != null;
        if (effect != null && wanted && effect.usesTextureFrom(textureProvider)) {
            effect.setFrameRenderer(renderer);
            return;
        }
        if (effect == null && !wanted) {
            return;
        }
        releaseFrameEffect();
        reconcileUseCases(false);
    }

    /**
     * Creates the effect, once, for as long as a caller wants frames drawn.
     *
     * <p>Nothing goes through GL until somebody asks. An effect across the preview, the recording
     * and the still makes CameraX share one camera stream between all three, which costs the still
     * the resolution it would otherwise have had, so a library that did it unasked would be
     * charging every app for a feature most of them never use.</p>
     */
    private void ensureFrameEffect() {
        if (mFrameEffect != null) return;
        FrameRenderer renderer = getFrameRenderer();
        CameraTextureProvider textureProvider = getCameraTextureProvider();
        if (renderer == null && textureProvider == null) return;
        mFrameEffect = FrameEffect.create(FrameEffect.ALL_TARGETS, mMainExecutor, renderer,
                textureProvider, this::onFrameEffectFailed);
    }

    private void releaseFrameEffect() {
        FrameEffect effect = mFrameEffect;
        if (effect == null) return;
        mFrameEffect = null;
        effect.release();
    }

    /** What CameraX reports through the effect's own error listener, which is off the main thread. */
    private void onFrameEffectFailed(@NonNull Throwable error) {
        Log.e(TAG, "CameraX could not run the frame effect", error);
    }

    @NonNull private SessionConfig createSessionConfig(@NonNull List<UseCase> useCases) {
        SessionConfig.Builder builder = new SessionConfig.Builder(useCases);
        int width = getWidth();
        int height = getHeight();
        if (isMatchPreviewAspectRatioEnabled() && width > 0 && height > 0) {
            builder.setViewPort(createViewPort(width, height));
        }
        return builder.build();
    }

    @NonNull private ViewPort createViewPort(int width, int height) {
        return new ViewPort.Builder(new Rational(width, height), getTargetRotation())
                .setScaleType(ViewPort.FILL_CENTER).build();
    }

    @NonNull private Preview createDisplayPreview(long generation, @NonNull CameraInfo cameraInfo) {
        return createDisplayPreview(generation, cameraInfo, true);
    }

    @NonNull private Preview createDisplayPreview(
            long generation, @NonNull CameraInfo cameraInfo, boolean allowStabilization) {
        Preview.Builder builder = new Preview.Builder()
                .setResolutionSelector(createResolutionSelector())
                .setTargetRotation(getTargetRotation());
        if (allowStabilization && isPreviewStabilizationEnabled()
                && Preview.getPreviewCapabilities(cameraInfo).isStabilizationSupported()) {
            builder.setPreviewStabilizationEnabled(true);
        }
        applyFaceDetection(builder, cameraInfo);
        Preview preview = builder.build();
        preview.setSurfaceProvider(mMainExecutor, request -> provideDisplaySurface(generation, request));
        return preview;
    }

    @Override public boolean isCameraReady() {
        return mActiveCamera != null;
    }

    @Override public boolean isSnapshotWhileRecordingSupported() {
        return mSnapshotWhileRecording;
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
     * The angle of view, read from the camera2 characteristics underneath CameraX.
     *
     * <p>CameraX does not publish the optics itself, so this goes through Camera2CameraInfo to
     * the same two values the camera2 backend reads. The zoom comes from CameraX's own zoom
     * state rather than from a request, since CameraX owns the requests here.</p>
     */
    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private float fieldOfView(boolean horizontal) {
        Camera camera = mActiveCamera;
        if (camera == null) {
            return 0f;
        }
        try {
            Camera2CameraInfo info = Camera2CameraInfo.from(camera.getCameraInfo());
            SizeF sensor = info.getCameraCharacteristic(
                    CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            float[] focalLengths = info.getCameraCharacteristic(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            if (sensor == null || focalLengths == null || focalLengths.length == 0) {
                return 0f;
            }
            // The first focal length, for the reason the camera2 backend records: a zoom ratio
            // of one is the lens the camera uses by default, which is the one this array leads
            // with, and the shortest is the ultra-wide the ratio reaches by going below one.
            float full = TransformMath.fieldOfViewDegrees(
                    horizontal ? sensor.getWidth() : sensor.getHeight(), focalLengths[0]);

            ZoomState zoom = camera.getCameraInfo().getZoomState().getValue();
            if (zoom == null) {
                return full;
            }
            return TransformMath.zoomedFieldOfViewDegrees(full, zoom.getZoomRatio());
        } catch (IllegalArgumentException | IllegalStateException | LinkageError error) {
            Log.w(TAG, "Could not work out the field of view", error);
            return 0f;
        }
    }

    @Override public boolean isFaceDetectionSupported() {
        Camera camera = mActiveCamera;
        return camera != null && faceDetectMode(camera.getCameraInfo()) != null;
    }

    @Override protected boolean setFaceDetectionEnabled(boolean enabled) {
        if (!enabled) {
            boolean wasEnabled = mFaceDetectionRequested;
            mFaceDetectionRequested = false;
            if (wasEnabled) {
                runOnMainThread(() -> reconcileUseCases(false));
            }
            return true;
        }
        if (!isFaceDetectionSupported()) {
            return false;
        }
        mFaceDetectionRequested = true;
        runOnMainThread(() -> reconcileUseCases(false));
        return true;
    }

    /**
     * Asks the camera for the faces it is tracking, through Camera2.
     *
     * <p>CameraX has no face detection of its own, so this goes straight to the camera2 request
     * the pipeline is going to issue anyway. That is a request option and a session callback, both
     * fixed when a use case is bound, which is why turning detection on rebinds.</p>
     */
    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private void applyFaceDetection(
            @NonNull Preview.Builder builder, @Nullable CameraInfo cameraInfo) {
        if (!mFaceDetectionRequested) {
            return;
        }
        Integer mode = faceDetectMode(cameraInfo);
        if (mode == null) {
            return;
        }
        new Camera2Interop.Extender<>(builder)
                .setCaptureRequestOption(CaptureRequest.STATISTICS_FACE_DETECT_MODE, mode)
                .setSessionCaptureCallback(new CameraCaptureSession.CaptureCallback() {
                    @Override public void onCaptureCompleted(
                            @NonNull CameraCaptureSession session,
                            @NonNull CaptureRequest request,
                            @NonNull TotalCaptureResult result) {
                        onFacesCaptured(result);
                    }
                });
    }

    /** The richest face detection mode the bound camera supports, or null if it has none. */
    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    @Nullable
    private Integer faceDetectMode(@Nullable CameraInfo cameraInfo) {
        if (cameraInfo == null) {
            return null;
        }
        try {
            Camera2CameraInfo camera2 = Camera2CameraInfo.from(cameraInfo);
            int[] modes = camera2.getCameraCharacteristic(
                    CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);
            Integer maxFaces = camera2.getCameraCharacteristic(
                    CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT);
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
        } catch (RuntimeException | LinkageError error) {
            // A camera CameraX is not driving through camera2 cannot answer this at all.
            if (DEBUG) Log.d(TAG, "Camera cannot report its face detection modes", error);
            return null;
        }
    }

    /** Translates a capture result's faces and hands them to the view, on the main thread. */
    private void onFacesCaptured(@NonNull TotalCaptureResult result) {
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
        onFacesDetected(faces, faceToViewTransform(result));
    }

    /**
     * Maps face bounds onto the view.
     *
     * <p>Faces are reported in active-array coordinates, so the region being previewed is the crop
     * region the request ended up with. The rotation is the one CameraX publishes for the stream:
     * its transformation info already states the turn from the sensor to the target rotation,
     * which is exactly what sensor coordinates need to become view coordinates.</p>
     */
    @NonNull
    private Matrix faceToViewTransform(@NonNull TotalCaptureResult result) {
        Rect region = result.get(CaptureResult.SCALER_CROP_REGION);
        if (region == null) {
            region = activeArraySize();
        }
        SurfaceRequest.TransformationInfo information = mTransformationInfo;
        if (region == null || information == null) {
            return new Matrix();
        }
        return TransformMath.centerCrop(new RectF(region),
                CameraXMath.within360(information.getRotationDegrees()),
                false /* mirroring is the caller's decision */,
                getView().getWidth(), getView().getHeight());
    }

    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    @Nullable
    private Rect activeArraySize() {
        Camera camera = mActiveCamera;
        if (camera == null) {
            return null;
        }
        CameraInfo cameraInfo = camera.getCameraInfo();
        try {
            return Camera2CameraInfo.from(cameraInfo).getCameraCharacteristic(
                    CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        } catch (RuntimeException | LinkageError error) {
            if (DEBUG) Log.d(TAG, "Camera cannot report its active array", error);
            return null;
        }
    }

    private void provideDisplaySurface(long generation, @NonNull SurfaceRequest request) {
        SurfaceTexture texture = getSurfaceTexture();
        if (texture == null || generation != mGeneration || !mOpenRequested) {
            request.willNotProvideSurface();
            return;
        }
        Size resolution = request.getResolution();
        texture.setDefaultBufferSize(resolution.getWidth(), resolution.getHeight());
        request.setTransformationInfoListener(mMainExecutor, information -> {
            if (generation != mGeneration) return;
            mPreviewResolution = resolution;
            mTransformationInfo = information;
            applyPreviewTransform();
        });
        Surface surface = new Surface(texture);
        request.provideSurface(surface, mMainExecutor, result -> {
            request.clearTransformationInfoListener();
            surface.release();
            if (DEBUG) Log.d(TAG, "Display surface released with result " + result.getResultCode());
        });
    }

    @NonNull private ImageCapture createImageCapture(@NonNull CameraInfo cameraInfo) {
        ImageCapture.Builder builder = new ImageCapture.Builder()
                .setResolutionSelector(createImageResolutionSelector())
                .setTargetRotation(getTargetRotation())
                .setFlashMode(getFlashMode())
                .setCaptureMode(getCaptureMode())
                .setJpegQuality(getPictureQuality());
        // The format belongs to the use case rather than to a request, so asking for a different
        // one is a rebind - which is why setPictureFormat goes through reconcileUseCases.
        Integer outputFormat = outputFormatFor(getPictureFormat());
        if (outputFormat != null && isPictureFormatSupported(getPictureFormat())) {
            builder.setOutputFormat(outputFormat);
        }
        return builder.build();
    }

    /** The CameraX constant for a format, or null for one it has no constant for. */
    @Nullable
    private static Integer outputFormatFor(@NonNull CameraView.PictureFormat format) {
        switch (format) {
            case ULTRA_HDR:
                return ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR;
            case RAW:
                return ImageCapture.OUTPUT_FORMAT_RAW;
            case RAW_JPEG:
                return ImageCapture.OUTPUT_FORMAT_RAW_JPEG;
            case JPEG:
                return ImageCapture.OUTPUT_FORMAT_JPEG;
            default:
                return null;
        }
    }

    @Override
    public boolean isPictureFormatSupported(@NonNull CameraView.PictureFormat format) {
        if (format == CameraView.PictureFormat.JPEG) {
            return true;
        }
        Camera camera = mActiveCamera;
        Integer outputFormat = outputFormatFor(format);
        if (camera == null || outputFormat == null) {
            return false;
        }
        try {
            // Asked of the camera that is bound, because a gain map needs the sensor and the
            // pipeline behind it, not just a version of CameraX that knows the constant.
            return ImageCapture.getImageCaptureCapabilities(camera.getCameraInfo())
                    .getSupportedOutputFormats().contains(outputFormat);
        } catch (RuntimeException | LinkageError error) {
            Log.w(TAG, "Could not ask which picture formats this camera writes", error);
            return false;
        }
    }

    @Override
    public synchronized void setPictureFormat(@NonNull CameraView.PictureFormat format) {
        CameraView.PictureFormat before = getPictureFormat();
        super.setPictureFormat(format);
        if (getPictureFormat() != before) {
            runOnMainThread(() -> reconcileUseCases(false));
        }
    }

    @NonNull private ImageAnalysis createImageAnalysis() {
        ExecutorService executor = mAnalysisExecutor;
        CameraXBarcodeAnalyzer analyzer = mBarcodeAnalyzer;
        if (executor == null || analyzer == null) {
            throw new IllegalStateException("Barcode analysis requested without an analyzer executor");
        }
        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setResolutionSelector(createResolutionSelector())
                .setTargetRotation(getTargetRotation())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build();
        analysis.setAnalyzer(executor, analyzer);
        return analysis;
    }
    @NonNull private Map<VideoRecorder.SurfaceProvider, Preview> createStreamPreviews(long generation) {
        Map<VideoRecorder.SurfaceProvider, Preview> previews = new LinkedHashMap<>();
        for (VideoRecorder.SurfaceProvider surfaceProvider : mSurfaceProviders) {
            Size maxSize = surfaceProvider.getMaxSize();
            Preview preview = new Preview.Builder()
                    .setResolutionSelector(maxSize == null
                            ? createResolutionSelector()
                            : createStreamResolutionSelector(maxSize))
                    .setTargetRotation(getTargetRotation())
                    .build();
            preview.setSurfaceProvider(mMainExecutor, request -> {
                if (generation != mGeneration || !mOpenRequested) {
                    request.willNotProvideSurface();
                    return;
                }
                try {
                    Size resolution = request.getResolution();
                    Surface surface = surfaceProvider.getSurface(
                            resolution.getWidth(), resolution.getHeight(), getSensorOrientation(), mFrontFacing);
                    request.provideSurface(surface, mMainExecutor, result -> {
                        if (DEBUG) Log.d(TAG, "Stream surface released with result " + result.getResultCode());
                    });
                } catch (RuntimeException error) {
                    Log.e(TAG, "Unable to provide CameraX stream surface", error);
                    request.willNotProvideSurface();
                }
            });
            previews.put(surfaceProvider, preview);
        }
        return previews;
    }

    private void unbindCurrentUseCases() {
        if (mCameraProvider != null && !mBoundUseCases.isEmpty()) {
            try {
                mCameraProvider.unbind(mBoundUseCases.toArray(new UseCase[0]));
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to unbind CameraX use cases", error);
            }
        }
        mBoundUseCases.clear();
        mBoundStreamPreviews.clear();
        if (mObservedCameraState != null && mCameraStateObserver != null) {
            mObservedCameraState.removeObserver(mCameraStateObserver);
        }
        mObservedCameraState = null;
        mCameraStateObserver = null;
        mActiveCamera = null;
        mPreview = null;
        mImageCapture = null;
        mImageAnalysis = null;
        mPreviewResolution = null;
        mTransformationInfo = null;
        publishSnapshot();
    }

    private void releaseAllUseCases() {
        unbindCurrentUseCases();
        mVideoCapture = null;
        mVideoRecording = null;
        mVideoFile = null;
        releaseBarcodeAnalysisResources();
        // After unbinding, because the renderer is drawing into surfaces those use cases own. The
        // GL context does not survive the camera being let go: a renderer kept running against no
        // camera holds a thread and a context for as long as the view exists.
        releaseFrameEffect();
        setTransform(new Matrix());
    }

    @NonNull private ResolutionSelector createResolutionSelector() {
        ResolutionSelector.Builder builder = new ResolutionSelector.Builder().setAspectRatioStrategy(getAspectRatioStrategy());
        int width = getWidth();
        int height = getHeight();
        if (width > 0 && height > 0) {
            builder.setResolutionStrategy(new ResolutionStrategy(
                    new Size(width, height), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER));
        }
        return builder.build();
    }

    /**
     * Resolution for a custom stream whose caller has capped it, because it is leaving the device
     * over a link the preview resolution would not fit through. Prefers the largest supported
     * size at or below the cap, and only exceeds it when the camera offers nothing smaller.
     */
    @NonNull private ResolutionSelector createStreamResolutionSelector(@NonNull Size maxSize) {
        return new ResolutionSelector.Builder()
                .setAspectRatioStrategy(getAspectRatioStrategy())
                .setResolutionStrategy(new ResolutionStrategy(
                        maxSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build();
    }

    @NonNull private ResolutionSelector createImageResolutionSelector() {
        return new ResolutionSelector.Builder()
                .setAspectRatioStrategy(getAspectRatioStrategy())
                .setResolutionStrategy(new ResolutionStrategy(imageTargetSize(getQuality()),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build();
    }

    @NonNull static Size imageTargetSize(@NonNull CameraView.Quality quality) {
        switch (quality) {
            case MAX: return new Size(3840, 2160);
            case HIGH: return new Size(1920, 1080);
            case MEDIUM: return new Size(1280, 720);
            case LOW:
            default: return new Size(720, 480);
        }
    }
    @NonNull private AspectRatioStrategy getAspectRatioStrategy() {
        int width = Math.max(getWidth(), getHeight());
        int height = Math.min(getWidth(), getHeight());
        if (width > 0 && height > 0 && (float) width / height > 1.55f) {
            return AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY;
        }
        return AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY;
    }

    @NonNull private CameraSelector getCameraSelector() {
        CameraSelector selector = selectorFor(mFrontFacing
                ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK);
        CameraView.Extension extension = getExtension();
        ExtensionsManager manager = mExtensionsManager;
        if (mVideoCapture == null && extension != CameraView.Extension.NONE && manager != null) {
            try {
                int extensionMode = toExtensionMode(extension);
                boolean analysisCompatible = mBarcodeAnalyzer == null
                        || manager.isImageAnalysisSupported(selector, extensionMode);
                if (analysisCompatible && manager.isExtensionAvailable(selector, extensionMode)) {
                    return manager.getExtensionEnabledCameraSelector(selector, extensionMode);
                }
            } catch (IllegalArgumentException | IllegalStateException error) {
                Log.w(TAG, "Unable to enable CameraX extension " + extension, error);
            }
        }
        return selector;
    }

    @NonNull private static CameraSelector selectorFor(int lensFacing) {
        return new CameraSelector.Builder().requireLensFacing(lensFacing).build();
    }

    private int getFlashMode() {
        switch (getFlash()) {
            case ON: return ImageCapture.FLASH_MODE_ON;
            case OFF: return ImageCapture.FLASH_MODE_OFF;
            case AUTO:
            default: return ImageCapture.FLASH_MODE_AUTO;
        }
    }

    private int getCaptureMode() {
        switch (getQuality()) {
            case MAX: return ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY;
            case HIGH:
            case MEDIUM:
            case LOW:
            default: return ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY;
        }
    }

    private int getTargetRotation() {
        switch (getDisplayRotation()) {
            case 90: return Surface.ROTATION_90;
            case 180: return Surface.ROTATION_180;
            case 270: return Surface.ROTATION_270;
            case 0:
            default: return Surface.ROTATION_0;
        }
    }

    private int getSensorOrientation() {
        Camera camera = mActiveCamera;
        return camera == null ? mSensorOrientation : camera.getCameraInfo().getSensorRotationDegrees();
    }

    @Override protected int getRelativeCameraOrientation() {
        return getRelativeImageOrientation(getDisplayRotation(), getSensorOrientation(), mFrontFacing, false);
    }
    @Override protected void attachSurface(VideoRecorder.SurfaceProvider surfaceProvider) {
        if (surfaceProvider == null) return;
        runOnMainThread(() -> {
            if (!mSurfaceProviders.add(surfaceProvider)) return;
            if (mOpenRequested && !mPaused && mVideoRecording == null
                    && mCameraProvider != null && !reconcileUseCases(false)) {
                mSurfaceProviders.remove(surfaceProvider);
                reconcileUseCases(false);
                Log.e(TAG, "Device rejected the additional CameraX streaming Preview");
            }
        });
    }

    @Override protected void detachSurface(VideoRecorder.SurfaceProvider surfaceProvider) {
        if (surfaceProvider == null) return;
        runOnMainThread(() -> {
            if (!mSurfaceProviders.remove(surfaceProvider)) return;
            Preview streamPreview = mBoundStreamPreviews.remove(surfaceProvider);
            if (streamPreview != null && mCameraProvider != null) {
                try {
                    mCameraProvider.unbind(streamPreview);
                    mBoundUseCases.remove(streamPreview);
                    mState = State.OPEN;
                    publishSnapshot();
                } catch (RuntimeException error) {
                    Log.w(TAG, "Unable to unbind the CameraX stream Preview", error);
                    reconcileUseCases(false);
                    return;
                }
            }
            if (mSurfaceProviders.isEmpty() && isPreviewStabilizationEnabled()
                    && mOpenRequested && !mPaused && mVideoRecording == null) {
                long generation = mGeneration;
                mMainHandler.postDelayed(() -> {
                    if (generation == mGeneration && mSurfaceProviders.isEmpty()
                            && mOpenRequested && !mPaused && mVideoRecording == null) {
                        reconcileUseCases(false);
                    }
                }, STREAM_RECONFIGURE_DELAY_MILLIS);
            }
        });
    }

    @Override public void onLayoutChanged() {
        runOnMainThread(() -> {
            applyPreviewTransform();
            if (mPreview != null) mPreview.setTargetRotation(getTargetRotation());
            if (mImageCapture != null) mImageCapture.setTargetRotation(getTargetRotation());
            if (mImageAnalysis != null) mImageAnalysis.setTargetRotation(getTargetRotation());
            if (mVideoCapture != null) mVideoCapture.setTargetRotation(getTargetRotation());
        });
    }

    /**
     * Rebinds the use cases for the size the view settled at.
     *
     * <p>CameraX resolves the resolution of every use case when they are bound, so a new size is
     * only honoured by binding them again - which tears down and reconfigures the capture
     * session. That is the one thing here that cannot happen per layout pass, and the reason this
     * is separate from {@link #onLayoutChanged}: a rotation or a foldable opening delivers a run
     * of sizes, and the transform applied on each of them keeps the preview honest until the last
     * one arrives.</p>
     */
    @Override public void onLayoutSettled() {
        runOnMainThread(() -> {
            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || height <= 0 || (width == mLastWidth && height == mLastHeight)) return;
            mLastWidth = width;
            mLastHeight = height;
            if (!mOpenRequested || mPaused || mVideoRecording != null) return;
            // Rebinding unbinds the use cases, and unbinding an ImageCapture aborts what it has in
            // flight with "Camera is closed" - so a view that settles a moment after the shutter
            // would lose the picture. The size is worth a session rebuild and a photo is worth
            // more, so the rebind waits for the capture rather than being dropped.
            if (mPendingImageFile != null) {
                mSizeRebindPending = true;
                return;
            }
            reconcileUseCases(true);
        });
    }

    /** Rebinds for a size that settled while a capture was in flight, if one did. */
    private void rebindForSettledSize() {
        if (!mSizeRebindPending) return;
        mSizeRebindPending = false;
        if (mOpenRequested && !mPaused && mVideoRecording == null && mPendingImageFile == null) {
            reconcileUseCases(true);
        }
    }

    private void applyPreviewTransform() {
        Size resolution = mPreviewResolution;
        SurfaceRequest.TransformationInfo information = mTransformationInfo;
        if (resolution == null || information == null) return;
        setTransform(CameraXMath.createTextureTransform(
                resolution, information.getCropRect(), information.getRotationDegrees(),
                information.isMirroring(), information.hasCameraTransform(),
                getSensorOrientation(), getWidth(), getHeight()));
    }

    /** Returns whether an atomic CameraX bind is active; used only by device validation. */
    boolean isCameraBoundForTesting() {
        return mActiveCamera != null && !mBoundUseCases.isEmpty();
    }

    private void runOnMainThread(@NonNull Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else mMainHandler.post(action);
    }
}