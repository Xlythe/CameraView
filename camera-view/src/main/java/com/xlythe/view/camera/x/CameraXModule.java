package com.xlythe.view.camera.x;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.location.Location;
import android.os.AsyncTask;
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

import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.CameraEffect;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.ExperimentalZeroShutterLag;
import androidx.camera.core.DynamicRange;
import androidx.camera.core.ExposureState;
import androidx.camera.core.CameraInfoUnavailableException;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.CameraState;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.FocusMeteringResult;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
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
import androidx.camera.extensions.ExtensionMode;
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
import com.xlythe.view.camera.DngWriter;
import com.xlythe.view.camera.Exif;
import com.xlythe.view.camera.ExifCaptureParameters;
import com.xlythe.view.camera.ExifNoRoomException;
import com.xlythe.view.camera.Face;
import com.xlythe.view.camera.CameraXBarcodeAnalyzer;
import com.xlythe.view.camera.CameraXFrameAnalyzer;
import com.xlythe.view.camera.CameraXSegmentationAnalyzer;
import com.xlythe.view.camera.Barcode;
import com.xlythe.view.camera.Burst;
import com.xlythe.view.camera.gl.CameraTextureProvider;
import com.xlythe.view.camera.gl.FrameRenderer;
import com.xlythe.view.camera.ICameraModule;
import com.xlythe.view.camera.PendingPicture;
import com.xlythe.view.camera.PermissionChecker;
import com.xlythe.view.camera.RawCapturePairer;
import com.xlythe.view.camera.TransformMath;
import com.xlythe.view.camera.ZoomMath;
import com.xlythe.view.camera.v2.Camera2Module;
import com.xlythe.view.camera.stream.VideoRecorder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Executor;

/** A CameraView backend implemented with CameraX. */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public class CameraXModule extends ICameraModule implements LifecycleOwner {
    /** Maximum time CameraX may take to report a still-image terminal callback. */
    private static final long IMAGE_CAPTURE_TIMEOUT_MILLIS = 15_000;
    /**
     * How many burst pictures are handed to CameraX at once. It takes them one after another, and
     * with a few queued the next is always waiting when one is captured, while it is still saved.
     * A drawn burst asks for one at a time, since each drawing is a full-size bitmap until it is
     * written.
     */
    static final int BURST_PICTURES_IN_FLIGHT = 3;
    /**
     * How many RAW frames, and how many results, may wait to be paired. Captures are taken one at
     * a time here, so the second slot only covers one left behind by a capture CameraX abandoned.
     */
    private static final int MAX_PENDING_RAW = 2;
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
    @Nullable private CameraXFrameAnalyzer mFrameAnalyzer;
    /** Single-thread executor owned by the active native barcode analyzer. */
    @Nullable private ExecutorService mAnalysisExecutor;
    /** Video use case while a recording is being prepared, recorded, or finalized. */
    @Nullable private VideoCapture<Recorder> mVideoCapture;
    /** Active CameraX recording; cleared only by its Finalize event. */
    @Nullable private Recording mVideoRecording;
    /** Output file associated with the active recording. */
    @Nullable private File mVideoFile;
    /** Whether the active recording reached its Start event, so its end deserves a tone. */
    private boolean mVideoStarted;
    /** Latest CameraX preview transformation metadata. */
    @Nullable private SurfaceRequest.TransformationInfo mTransformationInfo;
    /** The GL effect every frame passes through, while a caller has asked for one. */
    @Nullable private FrameEffect mFrameEffect;

    /**
     * Set once GL has failed here, after which this backend reports no effects and stops trying.
     *
     * <p>Latched rather than retried because the two ways it fails are both properties of the
     * device: a driver that will not give us a context, and a shader the driver will not compile.
     * Neither changes between one bind and the next, and retrying would pay the start-up timeout
     * on the main thread again on every rotation.</p>
     */
    private boolean mGlUnavailable;

    /** Whether the bound configuration can take a still while it records. */
    private boolean mSnapshotWhileRecording;

    /**
     * Pairs each RAW frame CameraX hands over with the camera2 result that describes it, which
     * arrives separately through interop. Kept across binds, and cleared whenever one ends.
     */
    private final RawCapturePairer<TotalCaptureResult> mRawPairer =
            new RawCapturePairer<>(MAX_PENDING_RAW);

    /** The RAW capture whose images are still arriving, or null. Main thread only. */
    @Nullable private RawCapture mRawCapture;

    /**
     * Hands still-capture results to {@link #mRawPairer}, on the camera's own thread.
     *
     * <p>Every request the session issues reports here, preview frames included, and only a still
     * can be the other half of a DNG. Letting the rest in would push the still's result out of the
     * pairer before its frame arrived.</p>
     */
    private final CameraCaptureSession.CaptureCallback mRawCaptureCallback =
            new CameraCaptureSession.CaptureCallback() {
                @WorkerThread
                @Override public void onCaptureCompleted(
                        @NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request,
                        @NonNull TotalCaptureResult result) {
                    Integer intent = request.get(CaptureRequest.CONTROL_CAPTURE_INTENT);
                    Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
                    if (intent == null || timestamp == null
                            || intent != CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE) {
                        return;
                    }
                    RawCapturePairer.Pairing<TotalCaptureResult> pairing =
                            mRawPairer.offerMetadata(result, timestamp);
                    if (pairing != null) {
                        mMainHandler.post(() -> writeDng(pairing));
                    }
                }
            };

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
    /**
     * The zoom asked for, held to the bound camera's range, which is what getZoomRatio reports.
     * Applied again on every bind and whenever the camera opens, since CameraX starts both at 1x
     * and turns away a request made before the camera is running.
     */
    private float mZoomRatio = 1f;
    /** The last zoom the camera confirmed, which a request it fails goes back to. */
    private float mConfirmedZoomRatio = 1f;
    /** The camera, and the range, {@link #mZoomPresets} was worked out for. */
    @Nullable private String mZoomPresetsKey;
    /** The bound camera's zoom presets, kept because each lens they come from costs a lookup. */
    @NonNull private float[] mZoomPresets = {1f};
    /**
     * The exposure index last asked for, which ICameraModule's is not: that one is what the camera
     * confirmed. Applied again after every bind. Main thread only.
     */
    private int mRequestedExposureCompensation;
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
    /** The burst being taken, or null. Rebinding waits for it, as it does for a picture. */
    @Nullable private Burst mBurst;
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
    /** Snapshot of the zoom ratio guarded by mSnapshotLock. */
    private float mSnapshotZoomRatio = 1f;
    /** Snapshot of the bound camera's minimum zoom ratio guarded by mSnapshotLock. */
    private float mSnapshotMinZoomRatio = 1f;
    /** Snapshot of the bound camera's maximum zoom ratio guarded by mSnapshotLock. */
    private float mSnapshotMaxZoomRatio = 1f;
    /** Snapshot of the bound camera's zoom presets guarded by mSnapshotLock. */
    @NonNull private float[] mSnapshotZoomPresets = {1f};
    /** Snapshot of front-camera availability guarded by mSnapshotLock. */
    private boolean mSnapshotHasFrontCamera;
    /** Snapshot of flash availability guarded by mSnapshotLock. */
    private boolean mSnapshotHasFlash;
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
    /** Snapshot of the EV one exposure compensation index is worth guarded by mSnapshotLock. */
    private float mSnapshotExposureStep;
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
        float minZoomRatio = 1f;
        float maxZoomRatio = 1f;
        float[] zoomPresets = {1f};
        boolean hdrSupported = false;
        boolean previewStabilizationSupported = false;
        boolean videoStabilizationSupported = false;
        boolean exposureSupported = false;
        int minExposure = 0;
        int maxExposure = 0;
        float exposureStep = 0f;
        boolean lowLightBoostSupported = false;
        if (cameraInfo != null) {
            // Each of these asks the camera a separate question, and a camera that cannot answer
            // one throws rather than declining: Recorder.getVideoCapabilities, for instance,
            // fails outright on a camera with no encoder profiles. This runs from lifecycle
            // callbacks and from CameraX's own listeners, so an escaping exception takes the host
            // down. Ask each question on its own and treat a failure as unsupported, which is
            // what an unanswerable capability means anyway.
            ZoomState zoom = query(cameraInfo, "zoom",
                    info -> info.getZoomState().getValue(), null);
            if (zoom != null && zoom.getMaxZoomRatio() > zoom.getMinZoomRatio()) {
                minZoomRatio = zoom.getMinZoomRatio();
                maxZoomRatio = zoom.getMaxZoomRatio();
            }
            float min = minZoomRatio;
            float max = maxZoomRatio;
            zoomPresets = query(cameraInfo, "zoom presets",
                    info -> zoomPresets(info, min, max), zoomPresets);
            hdrSupported = query(cameraInfo, "HDR", CameraXModule::supportsHdr, false);
            previewStabilizationSupported = query(cameraInfo, "preview stabilization",
                    info -> Preview.getPreviewCapabilities(info).isStabilizationSupported(), false);
            videoStabilizationSupported = query(cameraInfo, "video stabilization",
                    info -> Recorder.getVideoCapabilities(info).isStabilizationSupported(), false);
            exposureSupported = query(cameraInfo, "exposure",
                    info -> info.getExposureState().isExposureCompensationSupported(), false);
            // A camera that cannot compensate reports no range, whatever it publishes, so that a
            // control sized by it offers nothing the camera refuses.
            if (exposureSupported) {
                minExposure = query(cameraInfo, "minimum exposure",
                        info -> info.getExposureState().getExposureCompensationRange().getLower(),
                        0);
                maxExposure = query(cameraInfo, "maximum exposure",
                        info -> info.getExposureState().getExposureCompensationRange().getUpper(),
                        0);
                exposureStep = query(cameraInfo, "exposure step",
                        info -> info.getExposureState().getExposureCompensationStep().floatValue(),
                        0f);
            }
            lowLightBoostSupported = query(cameraInfo, "low-light boost",
                    CameraInfo::isLowLightBoostSupported, false);
        }
        synchronized (mSnapshotLock) {
            mSnapshotFrontFacing = mFrontFacing;
            mSnapshotPaused = mPaused;
            mSnapshotRecording = mVideoRecording != null;
            mSnapshotZoomRatio = mZoomRatio;
            mSnapshotMinZoomRatio = minZoomRatio;
            mSnapshotMaxZoomRatio = maxZoomRatio;
            mSnapshotZoomPresets = zoomPresets;
            mSnapshotHasFrontCamera = hasFrontCamera;
            mSnapshotHasFlash = hasFlash;
            mSnapshotHdrSupported = hdrSupported;
            mSnapshotPreviewStabilizationSupported = previewStabilizationSupported;
            mSnapshotVideoStabilizationSupported = videoStabilizationSupported;
            mSnapshotExposureSupported = exposureSupported;
            mSnapshotMinExposure = minExposure;
            mSnapshotMaxExposure = maxExposure;
            mSnapshotExposureStep = exposureStep;
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

    /**
     * Moves this module's lifecycle, unless it is destroyed. A recording the host's end stopped
     * still reports afterwards, and the registry throws rather than leave DESTROYED.
     */
    @MainThread
    private void moveLifecycleTo(@NonNull Lifecycle.State state) {
        if (mDestroyed) return;
        mLifecycleRegistry.setCurrentState(state);
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
            moveLifecycleTo(Lifecycle.State.CREATED);
            return;
        }
        if (isHostActive()) {
            moveLifecycleTo(Lifecycle.State.STARTED);
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
        moveLifecycleTo(Lifecycle.State.CREATED);
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
        if (mBurst != null) mBurst.end();
        cancelRecordingTimeouts();
        cancelDurationStop();
        publishSnapshot();
        if (mVideoRecording != null) {
            stopRecordingOnMainThread();
            return;
        }
        releaseAllUseCases();
        moveLifecycleTo(Lifecycle.State.CREATED);
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
        CameraStateSinceBind sinceBind = new CameraStateSinceBind(source.getValue());
        Observer<CameraState> observer = cameraState -> {
            if (generation != mGeneration || camera != mActiveCamera
                    || source != mObservedCameraState) {
                return;
            }
            if (!sinceBind.isAboutTheBind(cameraState)) {
                if (cameraState.getError() != null) {
                    Log.d(TAG, "Ignoring camera error " + cameraState.getError().getCode()
                            + " left over from before the use cases were bound");
                }
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
            if (cameraState.getType() == CameraState.Type.OPEN) {
                // The first moment the camera takes a zoom, so one asked for while it was opening
                // is asked for again.
                applyZoomOnMainThread();
            }
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

    @Override public void setZoomRatio(float zoomRatio) {
        runOnMainThread(() -> {
            mZoomRatio = zoomRatio;
            applyZoomOnMainThread();
        });
    }

    /**
     * Asks the bound camera for the zoom.
     *
     * <p>Reported straight away, as the other backends report it, rather than once the camera
     * confirms it: CameraX cancels every request a newer one replaces, which during a pinch is
     * every step but the last. A cancelled request is left for the next one, or for the camera
     * opening, to carry. Only one the camera fails goes back to the last zoom it confirmed.</p>
     */
    private void applyZoomOnMainThread() {
        Camera camera = mActiveCamera;
        ZoomState zoom = camera == null ? null : camera.getCameraInfo().getZoomState().getValue();
        if (zoom != null) {
            mZoomRatio = ZoomMath.clamp(
                    mZoomRatio, zoom.getMinZoomRatio(), zoom.getMaxZoomRatio());
        }
        reportZoomRatio(mZoomRatio);
        if (camera == null || zoom == null) {
            // Nothing bound to ask, so the next bind applies it.
            return;
        }

        float requested = mZoomRatio;
        ListenableFuture<Void> result = camera.getCameraControl().setZoomRatio(requested);
        result.addListener(() -> {
            try {
                result.get();
                mConfirmedZoomRatio = requested;
            } catch (ExecutionException e) {
                if (e.getCause() instanceof CameraControl.OperationCanceledException) {
                    return;
                }
                Log.w(TAG, "Zoom request failed", e.getCause());
                if (mZoomRatio == requested) {
                    mZoomRatio = mConfirmedZoomRatio;
                    reportZoomRatio(mZoomRatio);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "Zoom request interrupted", e);
            }
        }, mMainExecutor);
    }

    private void reportZoomRatio(float zoomRatio) {
        // Only the ratio, rather than a whole snapshot: a pinch asks for one every frame.
        synchronized (mSnapshotLock) {
            mSnapshotZoomRatio = zoomRatio;
        }
        onZoomChanged(zoomRatio);
    }

    @Override public float getZoomRatio() {
        synchronized (mSnapshotLock) {
            return mSnapshotZoomRatio;
        }
    }

    @Override public float getMinZoomRatio() {
        synchronized (mSnapshotLock) {
            return mSnapshotMinZoomRatio;
        }
    }

    @Override public float getMaxZoomRatio() {
        synchronized (mSnapshotLock) {
            return mSnapshotMaxZoomRatio;
        }
    }

    @NonNull @Override public float[] getZoomPresets() {
        synchronized (mSnapshotLock) {
            return mSnapshotZoomPresets.clone();
        }
    }

    /**
     * The zoom stops a camera's lenses suggest, read as the camera2 backend reads them.
     *
     * <p>Worked out again only when the camera or its range changes: each lens costs a lookup,
     * and a snapshot is published far more often than that.</p>
     */
    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    @NonNull
    private float[] zoomPresets(@NonNull CameraInfo cameraInfo, float minRatio, float maxRatio) {
        String cameraId = Camera2CameraInfo.from(cameraInfo).getCameraId();
        String key = cameraId + ':' + minRatio + ':' + maxRatio;
        if (!key.equals(mZoomPresetsKey)) {
            try {
                CameraManager cameraManager =
                        (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
                mZoomPresets = Camera2Module.zoomPresets(
                        cameraManager, cameraId, minRatio, maxRatio);
            } catch (CameraAccessException error) {
                Log.w(TAG, "Unable to read the lenses of camera " + cameraId, error);
                mZoomPresets = ZoomMath.presets(minRatio, maxRatio, new float[0], false);
            }
            mZoomPresetsKey = key;
        }
        return mZoomPresets;
    }

    @Override public boolean hasFlash() {
        synchronized (mSnapshotLock) {
            return mSnapshotHasFlash;
        }
    }

    @Override public void setFlash(CameraView.Flash flash) {
        super.setFlash(flash);
        // A burst keeps the flash off, and puts this mode back once it finishes.
        runOnMainThread(() -> {
            if (mImageCapture != null && mBurst == null) mImageCapture.setFlashMode(getFlashMode());
        });
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
            case BOKEH: return ExtensionMode.BOKEH;
            case HDR: return ExtensionMode.HDR;
            case NIGHT: return ExtensionMode.NIGHT;
            case FACE_RETOUCH: return ExtensionMode.FACE_RETOUCH;
            case AUTO: return ExtensionMode.AUTO;
            case NONE:
            default: return ExtensionMode.NONE;
        }
    }

    @Override public boolean isFrontVideoMirrored() {
        return true;
    }

    @Override public void setStabilizationEnabled(boolean enabled) {
        if (enabled && mActiveCamera != null && !isStabilizationSupported()) {
            Log.w(TAG, "Ignoring stabilization because the active camera does not support it");
            return;
        }
        super.setStabilizationEnabled(enabled);
        rebindConfiguration();
    }

    /** True when the camera can steady either the preview or a recording. */
    @Override public boolean isStabilizationSupported() {
        synchronized (mSnapshotLock) {
            return mSnapshotPreviewStabilizationSupported || mSnapshotVideoStabilizationSupported;
        }
    }


    @Override public void setExposureCompensation(int exposureCompensation) {
        runOnMainThread(() -> setExposureCompensationOnMainThread(exposureCompensation));
    }

    /**
     * Asks the bound camera for an exposure index.
     *
     * <p>The index is reported as accepted once the camera confirms it, which it does through a
     * later capture result. A rebind that lands first cancels the request without the camera
     * having refused anything, so the request is kept in {@link #mRequestedExposureCompensation}
     * and applied again once the next camera is bound. Only a refusal gives it up.</p>
     */
    @MainThread
    private void setExposureCompensationOnMainThread(int exposureCompensation) {
        Camera camera = mActiveCamera;
        if (camera == null) {
            mRequestedExposureCompensation = exposureCompensation;
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
        mRequestedExposureCompensation = clamped;
        ListenableFuture<Integer> result = camera.getCameraControl().setExposureCompensationIndex(clamped);
        result.addListener(() -> {
            try {
                super.setExposureCompensation(result.get());
            } catch (CancellationException error) {
                // Cancelled rather than refused, so the request stands.
                if (DEBUG) Log.d(TAG, "Exposure request cancelled", error);
            } catch (ExecutionException error) {
                if (error.getCause() instanceof CameraControl.OperationCanceledException) {
                    // A rebind or a newer request, not a refusal, so the request stands.
                    if (DEBUG) Log.d(TAG, "Exposure request cancelled", error.getCause());
                    return;
                }
                Log.w(TAG, "Exposure request failed", error.getCause());
                if (mRequestedExposureCompensation == clamped) {
                    mRequestedExposureCompensation = getExposureCompensation();
                }
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

    @Override public float getExposureCompensationStep() {
        synchronized (mSnapshotLock) {
            return mSnapshotExposureStep;
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
        startFrameAnalyzerOnMainThread(analyzer, "Barcode");
    }

    /**
     * Takes the analysis slot, which there is only one of.
     *
     * @param what names the thread, so a stack trace says which model is holding it
     */
    private void startFrameAnalyzerOnMainThread(@NonNull CameraXFrameAnalyzer analyzer,
            @NonNull String what) {
        if (mFrameAnalyzer != null) {
            Log.w(TAG, "CameraX frame analysis is already active");
            analyzer.close();
            return;
        }
        mAnalysisExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "CameraView-" + what + "Analysis");
            thread.setDaemon(true);
            return thread;
        });
        mFrameAnalyzer = analyzer;
        if (mOpenRequested && mVideoRecording == null && !mPaused && isHostActive()) {
            reconcileUseCases(true);
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    @Override protected boolean startSegmentation(CameraView.SegmentationListener listener) {
        if (listener == null) throw new NullPointerException("listener cannot be null");
        CameraXSegmentationAnalyzer analyzer;
        try {
            analyzer = new CameraXSegmentationAnalyzer(listener, mMainExecutor);
        } catch (RuntimeException | LinkageError error) {
            // The model is delivered by Play services and its client is built here, so a device
            // without it fails at construction. Reported rather than thrown, for the same reason
            // the barcode client is: a camera library may not take an app down because a vision
            // dependency is missing.
            Log.w(TAG, "CameraX cannot segment on this device", error);
            return false;
        }
        runOnMainThread(() -> startFrameAnalyzerOnMainThread(analyzer, "Segmentation"));
        return true;
    }

    @Override protected void stopSegmentation() {
        runOnMainThread(this::stopBarcodeScannerOnMainThread);
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
        CameraXFrameAnalyzer analyzer = mFrameAnalyzer;
        mFrameAnalyzer = null;
        if (analyzer != null) analyzer.close();
        ExecutorService executor = mAnalysisExecutor;
        mAnalysisExecutor = null;
        if (executor != null) executor.shutdownNow();
    }

    @Override public void takePicture(File file) {
        if (file == null) throw new NullPointerException("file cannot be null");
        runOnMainThread(() -> takePictureOnMainThread(file));
    }

    /**
     * Whether a picture may be taken now. A recording does not stand in the way: the still output
     * bound beside it is the one a snapshot uses, and it is bound exactly when
     * {@link #isSnapshotWhileRecordingSupported} says so.
     */
    @AnyThread
    @VisibleForTesting
    static boolean acceptsPicture(boolean open, boolean hasStillOutput, boolean picturePending,
            boolean recording, boolean snapshotWhileRecording) {
        return open && hasStillOutput && !picturePending
                && (!recording || snapshotWhileRecording);
    }

    private void takePictureOnMainThread(@NonNull File file) {
        ImageCapture imageCapture = mImageCapture;
        if (!acceptsPicture(mOpenRequested, imageCapture != null, isCapturing(),
                isRecording(), mSnapshotWhileRecording)) {
            Log.w(TAG, "Cannot take a picture in the current CameraX state: " + mState);
            onImageFailed();
            return;
        }
        boolean raw = writesRaw(getPictureFormat());
        if (raw && !writesRaw(imageCapture)) {
            // The format changed to one this camera cannot write, as a lens switch can leave it,
            // or frames are drawn, which makes the still a JPEG. A caller who asked for a DNG and
            // quietly got a JPEG would have no way to tell.
            Log.w(TAG, "The bound camera cannot capture " + getPictureFormat());
            onImageFailed();
            return;
        }
        long operationId = ++mImageOperationId;
        mPendingImageFile = file;
        scheduleImageTimeout(operationId);
        if (raw) {
            takeRawPicture(imageCapture, file, operationId);
            return;
        }
        FrameEffect effect = mFrameEffect;
        if (effect != null) {
            takeDrawnPicture(imageCapture, effect, file, operationId);
            return;
        }
        Location location = getLocation();
        ExifCaptureParameters exifParameters = exifCaptureParameters();
        imageCapture.takePicture(outputOptions(file, location), mMainExecutor,
                new ImageCapture.OnImageSavedCallback() {
            @Override public void onCaptureStarted() {
                onShutter();
            }
            @Override public void onImageSaved(@NonNull ImageCapture.OutputFileResults results) {
                if (!completePendingImage(operationId)) return;
                finishExif(file, location, exifParameters,
                        () -> showImageConfirmation(file));
            }
            @Override public void onError(@NonNull ImageCaptureException error) {
                if (!completePendingImage(operationId)) return;
                Log.w(TAG, "Image capture failed", error);
                onImageFailed();
            }
        });
    }

    /**
     * Where a picture is written, tagged with where it was taken and flipped as the preview is.
     * Shared by a single picture and every picture of a burst, so the two are written alike.
     */
    @NonNull private ImageCapture.OutputFileOptions outputOptions(
            @NonNull File file, @Nullable Location location) {
        ImageCapture.Metadata metadata = new ImageCapture.Metadata();
        metadata.setLocation(location);
        metadata.setReversedHorizontal(mFrontFacing);
        return new ImageCapture.OutputFileOptions.Builder(file).setMetadata(metadata).build();
    }

    /** Whether a picture or a burst is in flight, which a rebind would abort. */
    private boolean isCapturing() {
        return mPendingImageFile != null || mBurst != null;
    }

    @Override protected void startBurst(@NonNull Burst burst) {
        runOnMainThread(() -> startBurstOnMainThread(burst));
    }

    /**
     * Hands CameraX a few pictures at a time, and another as each is saved. Its own queue takes
     * them back to back, each written and tagged as a single picture is. While frames are drawn,
     * each picture is drawn and written as a single one is before the next is asked for. The
     * flash is off for the burst, and comes back once the last is saved.
     */
    private void startBurstOnMainThread(@NonNull Burst burst) {
        ImageCapture imageCapture = mImageCapture;
        if (!mOpenRequested || mPaused || imageCapture == null || isRecording() || isCapturing()) {
            Log.w(TAG, "Cannot take a burst in the current CameraX state: " + mState);
            burst.end();
            return;
        }
        mBurst = burst;
        imageCapture.setFlashMode(ImageCapture.FLASH_MODE_OFF);
        burst.whenFinished(() -> {
            if (mBurst != burst) return;
            mBurst = null;
            imageCapture.setFlashMode(getFlashMode());
            // Posted, because the burst finishes from inside a CameraX callback.
            mMainHandler.post(this::rebindForSettledSize);
        });
        int inFlight = mFrameEffect == null ? BURST_PICTURES_IN_FLIGHT : 1;
        for (int i = 0; i < inFlight; i++) {
            requestBurstPicture(burst, imageCapture);
        }
    }

    /**
     * Asks for the burst's next picture, unless it has all it is going to take. Its Exif is
     * finished on the burst's own thread, so the pictures reach the app in the order taken.
     */
    private void requestBurstPicture(@NonNull Burst burst, @NonNull ImageCapture imageCapture) {
        File file = burst.nextFile();
        if (file == null) return;
        ExifCaptureParameters exifParameters =
                exifCaptureParameters().setFlash(CameraView.Flash.OFF, hasFlash());
        FrameEffect effect = mFrameEffect;
        if (effect != null) {
            requestDrawnBurstPicture(burst, imageCapture, effect, file, exifParameters);
            return;
        }
        Location location = getLocation();
        imageCapture.takePicture(outputOptions(file, location), mMainExecutor,
                new ImageCapture.OnImageSavedCallback() {
            @Override public void onCaptureStarted() {
                onBurstShutter();
            }
            @Override public void onImageSaved(@NonNull ImageCapture.OutputFileResults results) {
                requestBurstPicture(burst, imageCapture);
                finishExif(file, location, exifParameters, burst.getExecutor(),
                        () -> burst.onPictureTaken(file));
            }
            @Override public void onError(@NonNull ImageCaptureException error) {
                Log.w(TAG, "Burst capture failed", error);
                burst.onPictureFailed(file);
            }
        });
    }

    /**
     * Asks for a burst picture drawn as {@link #takeDrawnPicture} draws a single one, and for the
     * next once it is written. Waiting for the write rather than the capture keeps one full-size
     * drawing in memory, not a queue of them, and the pictures in order.
     */
    @MainThread
    private void requestDrawnBurstPicture(@NonNull Burst burst,
            @NonNull ImageCapture imageCapture, @NonNull FrameEffect effect, @NonNull File file,
            @NonNull ExifCaptureParameters exifParameters) {
        boolean mirrored = mFrontFacing;
        int quality = getPictureQuality();
        imageCapture.takePicture(mMainExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override public void onCaptureStarted() {
                onBurstShutter();
            }

            @Override public void onCaptureSuccess(@NonNull ImageProxy image) {
                DrawnStill still;
                try {
                    still = new DrawnStill(getContext(), image, mirrored, effect, file, quality,
                            exifParameters, getLocation(),
                            written -> mMainHandler.post(() -> onDrawnBurstPictureWritten(
                                    burst, file, written,
                                    () -> requestBurstPicture(burst, imageCapture))));
                } catch (RuntimeException | OutOfMemoryError error) {
                    Log.e(TAG, "Failed to copy the burst picture to draw", error);
                    burst.onPictureFailed(file);
                    return;
                }
                burst.getExecutor().execute(still);
            }

            @Override public void onError(@NonNull ImageCaptureException error) {
                Log.w(TAG, "Burst capture failed", error);
                burst.onPictureFailed(file);
            }
        });
    }

    /**
     * Reports a drawn burst picture, and asks for the next only once it is on disk. A picture that
     * could not be drawn or written ends the burst, as a failed capture does.
     */
    @MainThread
    @VisibleForTesting
    static void onDrawnBurstPictureWritten(@NonNull Burst burst, @NonNull File file,
            boolean written, @NonNull Runnable requestNext) {
        if (!written) {
            burst.onPictureFailed(file);
            return;
        }
        burst.onPictureTaken(file);
        requestNext.run();
    }

    /**
     * Takes a RAW or RAW_JPEG picture in memory and writes it ourselves.
     *
     * <p>CameraX's own file writer cannot be trusted with a DNG. It sizes the RAW stream from the
     * resolution selector without regard to the sensor, and DngCreator answers a frame of the
     * wrong size with an AssertionError that its writer does not catch, on a thread of CameraX's
     * where it ends the process. It also describes every DNG after the first with the first
     * capture's result. So the frames come back in memory, are paired with their own results, and
     * go through the same writer the camera2 backend uses. They are copied out as they arrive,
     * because CameraX frees an image's buffer when the camera closes, open or not.</p>
     *
     * <p>The capture is over, as far as the camera is concerned, once every image has been handed
     * to a writer. Closing the camera after that lets the writes finish, and the caller hears from
     * the writers.</p>
     */
    private void takeRawPicture(
            @NonNull ImageCapture imageCapture, @NonNull File file, long operationId) {
        boolean jpeg = getPictureFormat() == CameraView.PictureFormat.RAW_JPEG;
        // RAW writes only the DNG, so it takes the file it was given whatever it is called.
        File dng = jpeg ? DngWriter.dngBeside(file) : file;
        List<File> files = new ArrayList<>(2);
        if (jpeg) files.add(file);
        files.add(dng);
        PendingPicture picture = new PendingPicture(file, files, new PendingPicture.Callback() {
            @Override public void onSaved(@NonNull File saved) {
                showImageConfirmation(saved, files);
            }

            @Override public void onFailed() {
                onImageFailed();
            }
        });
        CameraCharacteristics characteristics = cameraCharacteristics(mActiveCamera);
        if (characteristics == null) {
            Log.w(TAG, "The bound camera cannot describe itself to DngCreator");
            completePendingImage(operationId);
            picture.fail();
            return;
        }
        // Anything still waiting belongs to a capture that was abandoned.
        mRawPairer.clear();
        RawCapture capture = new RawCapture(operationId, jpeg ? file : null, dng, picture,
                characteristics, mFrontFacing, exifCaptureParameters());
        mRawCapture = capture;
        int quality = getPictureQuality();
        imageCapture.takePicture(mMainExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override public void onCaptureStarted() {
                if (mRawCapture == capture) onShutter();
            }

            @Override public void onCaptureSuccess(@NonNull ImageProxy image) {
                if (mRawCapture != capture) {
                    image.close();
                    return;
                }
                if (image.getFormat() != ImageFormat.RAW_SENSOR) {
                    saveJpeg(capture, image, quality);
                    return;
                }
                CopiedFrame frame;
                try {
                    frame = CopiedFrame.copyOf(image);
                    capture.rotation = image.getImageInfo().getRotationDegrees();
                } catch (RuntimeException | OutOfMemoryError error) {
                    Log.e(TAG, "Failed to copy the raw frame", error);
                    capture.dngDelivered = true;
                    capture.picture.onWritten(capture.dng, false);
                    onRawImageDelivered(capture);
                    return;
                } finally {
                    image.close();
                }
                RawCapturePairer.Pairing<TotalCaptureResult> pairing = mRawPairer.offerFrame(frame);
                if (pairing != null) writeDng(pairing);
            }

            @Override public void onError(@NonNull ImageCaptureException error) {
                if (mRawCapture != capture) return;
                Log.w(TAG, "Image capture failed", error);
                abandonRawCapture();
                completePendingImage(operationId);
            }
        });
    }

    /**
     * Takes a picture at the camera's still resolution and draws it through the renderer, as
     * Camera2 does. The effect is bound to the preview and the recording only, since CameraX
     * would otherwise draw the still from the stream they share, at the preview's resolution.
     */
    @MainThread
    private void takeDrawnPicture(@NonNull ImageCapture imageCapture,
            @NonNull FrameEffect effect, @NonNull File file, long operationId) {
        boolean mirrored = mFrontFacing;
        int quality = getPictureQuality();
        ExifCaptureParameters exifParameters = exifCaptureParameters();
        imageCapture.takePicture(mMainExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override public void onCaptureStarted() {
                if (operationId == mImageOperationId) onShutter();
            }

            @Override public void onCaptureSuccess(@NonNull ImageProxy image) {
                if (operationId != mImageOperationId || mPendingImageFile == null) {
                    image.close();
                    return;
                }
                DrawnStill still;
                try {
                    // Read now rather than on the writer's thread, because the camera can close
                    // before the writer runs and the fix it was following goes with it.
                    still = new DrawnStill(getContext(), image, mirrored, effect, file, quality,
                            exifParameters, getLocation(),
                            written -> mMainHandler.post(() -> {
                                if (!completePendingImage(operationId)) return;
                                if (written) showImageConfirmation(file);
                                else onImageFailed();
                            }));
                } catch (RuntimeException | OutOfMemoryError error) {
                    Log.e(TAG, "Failed to copy the still to draw", error);
                    if (completePendingImage(operationId)) onImageFailed();
                    return;
                }
                AsyncTask.THREAD_POOL_EXECUTOR.execute(still);
            }

            @Override public void onError(@NonNull ImageCaptureException error) {
                if (!completePendingImage(operationId)) return;
                Log.w(TAG, "Image capture failed", error);
                onImageFailed();
            }
        });
    }

    /** Writes the JPEG half of a RAW_JPEG capture. */
    @MainThread
    private void saveJpeg(@NonNull RawCapture capture, @NonNull ImageProxy image, int quality) {
        File file = capture.jpeg;
        if (file == null || capture.jpegDelivered) {
            image.close();
            return;
        }
        capture.jpegDelivered = true;
        // Read now rather than on the writer's thread, because the camera can close before the
        // writer runs and the fix it was following goes with it.
        Location location = getLocation();
        JpegSaver saver;
        try {
            saver = new JpegSaver(getContext(), image, quality, capture.mirrored, file,
                    capture.exifParameters) {
                @Nullable
                @Override
                protected Location getLocation() {
                    return location;
                }

                @Override
                protected void onPostExecute(Boolean saved) {
                    capture.picture.onWritten(file, Boolean.TRUE.equals(saved));
                }
            };
        } catch (RuntimeException | OutOfMemoryError error) {
            Log.e(TAG, "Failed to copy the JPEG", error);
            capture.picture.onWritten(file, false);
            onRawImageDelivered(capture);
            return;
        }
        saver.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        onRawImageDelivered(capture);
    }

    /** Hands a paired frame to the writer, or closes it if no capture is waiting for it. */
    @MainThread
    private void writeDng(@NonNull RawCapturePairer.Pairing<TotalCaptureResult> pairing) {
        RawCapture capture = mRawCapture;
        if (capture == null || capture.dngDelivered) {
            pairing.frame.close();
            return;
        }
        capture.dngDelivered = true;
        // Read now rather than on the writer's thread, because the camera can close before the
        // writer runs and the fix it was following goes with it.
        Location location = getLocation();
        new DngWriter(capture.characteristics, pairing,
                DngWriter.exifOrientation(capture.rotation, capture.mirrored), capture.dng) {
            @Nullable
            @Override
            protected Location getLocation() {
                return location;
            }

            @Override
            protected void onPostExecute(Boolean saved) {
                capture.picture.onWritten(capture.dng, Boolean.TRUE.equals(saved));
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        onRawImageDelivered(capture);
    }

    /** Ends the camera's part of a capture once every image it owes has reached a writer. */
    @MainThread
    private void onRawImageDelivered(@NonNull RawCapture capture) {
        if (mRawCapture != capture || !capture.dngDelivered
                || (capture.jpeg != null && !capture.jpegDelivered)) {
            return;
        }
        mRawCapture = null;
        completePendingImage(capture.operationId);
    }

    /**
     * Gives up on whatever the capture in flight has not yet handed to a writer. Its picture
     * reports the failure once anything already being written has finished.
     *
     * @return whether there was a capture to give up on
     */
    @MainThread
    private boolean abandonRawCapture() {
        RawCapture capture = mRawCapture;
        mRawCapture = null;
        mRawPairer.clear();
        if (capture == null) return false;
        if (capture.jpeg != null && !capture.jpegDelivered) {
            capture.picture.onWritten(capture.jpeg, false);
        }
        if (!capture.dngDelivered) {
            capture.picture.onWritten(capture.dng, false);
        }
        return true;
    }

    /** A RAW or RAW_JPEG capture whose images have not all reached a writer yet. */
    private static final class RawCapture {
        /** The operation the capture belongs to, ended once every image is delivered. */
        final long operationId;
        /** Where the JPEG goes, or null for a capture that writes only the DNG. */
        @Nullable final File jpeg;
        /** Where the DNG goes. */
        @NonNull final File dng;
        /** The shutter press, told as each file is written or lost. */
        @NonNull final PendingPicture picture;
        /** The bound camera's, which DngCreator reads the sensor's description from. */
        @NonNull final CameraCharacteristics characteristics;
        /** Whether the picture is mirrored, as a front lens's preview is. */
        final boolean mirrored;
        /** How the picture was taken, described as it was asked for, for the JPEG's Exif. */
        @NonNull final ExifCaptureParameters exifParameters;
        /** How far the frame is turned, which only arrives with the frame. */
        int rotation;
        /** Whether the JPEG has been handed to its writer. */
        boolean jpegDelivered;
        /** Whether the DNG has been handed to its writer, or given up on. */
        boolean dngDelivered;

        RawCapture(long operationId, @Nullable File jpeg, @NonNull File dng,
                @NonNull PendingPicture picture, @NonNull CameraCharacteristics characteristics,
                boolean mirrored, @NonNull ExifCaptureParameters exifParameters) {
            this.operationId = operationId;
            this.jpeg = jpeg;
            this.dng = dng;
            this.picture = picture;
            this.characteristics = characteristics;
            this.mirrored = mirrored;
            this.exifParameters = exifParameters;
        }
    }

    /**
     * Writes the Exif CameraX leaves out (timezone offsets, sub-second times, pixel dimensions,
     * what the fix knows beyond its coordinate, how the picture was taken, and a thumbnail), as
     * the other two backends do, then runs {@code then} on the main thread. The caller is told
     * only after, so it never reads a file being rewritten.
     */
    private void finishExif(@NonNull File file, @Nullable Location location,
            @NonNull ExifCaptureParameters exifParameters, @NonNull Runnable then) {
        finishExif(file, location, exifParameters, AsyncTask.THREAD_POOL_EXECUTOR, then);
    }

    /** {@link #finishExif}, writing on {@code executor}. */
    private void finishExif(@NonNull File file, @Nullable Location location,
            @NonNull ExifCaptureParameters exifParameters, @NonNull Executor executor,
            @NonNull Runnable then) {
        executor.execute(() -> {
            writeExif(getContext(), file, location, exifParameters);
            mMainHandler.post(then);
        });
    }

    /** The Exif {@link #finishExif} writes, which never fails the capture. */
    @VisibleForTesting
    @WorkerThread
    static void writeExif(@NonNull Context context, @NonNull File file,
            @Nullable Location location, @NonNull ExifCaptureParameters exifParameters) {
        try {
            Exif exif = new Exif(file);
            exif.attachTimestamp();
            exif.attachCaptureDevice(context);
            exif.attachThumbnail();
            exifParameters.applyTo(exif);
            if (location != null) {
                exif.attachLocation(location);
            }
            exif.save();
        } catch (ExifNoRoomException error) {
            // The picture keeps everything else, and is never failed over its metadata.
            Log.w(TAG, "Saved the picture without some of its Exif tags", error);
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "Unable to finish the Exif on a CameraX capture", error);
        }
    }

    /**
     * The Exif a picture taken now should carry about how it was taken: the camera's optics,
     * read through the camera2 characteristics underneath, and the zoom, exposure compensation,
     * flash and extension CameraX was asked for.
     */
    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    @NonNull
    private ExifCaptureParameters exifCaptureParameters() {
        Camera camera = mActiveCamera;
        if (camera == null) {
            return new ExifCaptureParameters();
        }
        CameraInfo info = camera.getCameraInfo();
        ExifCaptureParameters parameters;
        try {
            parameters = ExifCaptureParameters.forCamera(
                    (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE),
                    Camera2CameraInfo.from(info).getCameraId());
        } catch (RuntimeException | LinkageError error) {
            Log.w(TAG, "Unable to describe the camera for a picture's Exif", error);
            parameters = new ExifCaptureParameters();
        }
        ZoomState zoom = info.getZoomState().getValue();
        if (zoom != null) {
            parameters.setZoomRatio(zoom.getZoomRatio());
        }
        return parameters
                .setExposureCompensation(info.getExposureState().getExposureCompensationIndex())
                .setFlash(getFlash(), info.hasFlashUnit())
                .setExtension(getExtension());
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
        // Posted, because a rebind from inside this CameraX callback would tear down the
        // pipeline that is still reporting.
        mMainHandler.post(this::rebindForSettledSize);
        return true;
    }

    private void failPendingImage(@NonNull String reason) {
        if (mPendingImageFile == null) return;
        Log.e(TAG, reason);
        mImageOperationId++;
        mPendingImageFile = null;
        cancelImageTimeout();
        // A RAW capture reports through its picture, once whatever it already handed to a writer
        // has finished.
        if (!abandonRawCapture()) onImageFailed();
        mMainHandler.post(this::rebindForSettledSize);
    }

    private void cancelImageTimeout() {
        if (mImageTimeoutTask == null) return;
        mMainHandler.removeCallbacks(mImageTimeoutTask);
        mImageTimeoutTask = null;
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
                .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
                .setTargetRotation(getTargetRotation());
        if (cameraInfo != null) {
            videoBuilder.setDynamicRange(selectedDynamicRange(cameraInfo));
            if (isStabilizationEnabled()
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
            Location where = getLocation();
            if (where != null) {
                output.setLocation(where);
            }
            PendingRecording pending = recorder.prepareRecording(getContext(), output.build());
            if (PermissionChecker.hasPermissions(getContext(), Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled();
            mVideoFile = file;
            mVideoStarted = false;
            mState = State.RECORDING;
            onRecordingStarting();
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
            mVideoStarted = true;
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
        boolean success = isSuccessfulFinalize(finalized) && file.exists();
        if (!success) {
            Log.e(TAG, "CameraX recording failed: " + describeFinalize(finalized),
                    finalized.getCause());
        }
        onRecordingFinalized(file, success);
    }

    /**
     * Settles the camera once a recording has ended, and tells the caller how it went. It can
     * arrive after the camera closed, and after the host is gone, since a close stops the
     * recording rather than waiting for it.
     */
    @MainThread
    @VisibleForTesting
    void onRecordingFinalized(@NonNull File file, boolean success) {
        if (mVideoStarted) {
            mVideoStarted = false;
            onRecordingStopped();
        }
        cancelDurationStop();
        cancelRecordingTimeouts();
        mVideoOperationId++;
        mVideoRecording = null;
        mVideoCapture = null;
        mVideoFile = null;
        applyPendingLensFacing();
        if (mCriticalCameraFailure) {
            releaseAllUseCases();
            moveLifecycleTo(Lifecycle.State.CREATED);
            mState = State.ERROR;
        } else if (mOpenRequested && isHostActive()) {
            mState = State.OPEN;
            if (!mPaused) reconcileUseCases(false);
        } else {
            releaseAllUseCases();
            moveLifecycleTo(Lifecycle.State.CREATED);
            mState = mOpenRequested ? State.SUSPENDED : State.CLOSED;
        }
        publishSnapshot();
        if (success) {
            showVideoConfirmation(file);
        } else {
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
                moveLifecycleTo(Lifecycle.State.CREATED);
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
                moveLifecycleTo(Lifecycle.State.CREATED);
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
            Map<VideoRecorder.SurfaceProvider, Preview> streamPreviews = createStreamPreviews(generation);
            // Before the still, which is built as a JPEG whenever frames are drawn.
            boolean drawing = prepareFrameEffect(!streamPreviews.isEmpty());
            // A still output is wanted alongside a recording, not instead of it: that is the
            // combination a camera app's video-snapshot button uses. Analysis is bound beside the
            // still, which every camera can hold with a preview, and gives way to a recording.
            boolean analyzing = mVideoCapture == null && mFrameAnalyzer != null;
            ImageCapture imageCapture = createImageCapture(cameraInfo, drawing, analyzing);
            ImageAnalysis imageAnalysis = analyzing ? createImageAnalysis() : null;
            List<UseCase> requested = new ArrayList<>();
            requested.add(displayPreview);
            if (mVideoCapture != null) requested.add(mVideoCapture);
            requested.add(imageCapture);
            if (imageAnalysis != null) requested.add(imageAnalysis);
            requested.addAll(streamPreviews.values());

            // A preview, a still and analysis are guaranteed together, but not beside a stream
            // as well. Asked for more than it can hold, the bind fails and takes the analysis
            // with it, so the still is the part that gives way, as it does to a recording.
            if (imageAnalysis != null && !streamPreviews.isEmpty()
                    && !cameraInfo.isSessionConfigSupported(createSessionConfig(requested))) {
                Log.d(TAG, "This camera cannot take a picture while it analyzes a stream");
                requested.remove(imageCapture);
                imageCapture = null;
            }

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
                if (isStabilizationEnabled()) {
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
            unbindCurrentUseCases();
            CameraXFrameAnalyzer analyzer = mFrameAnalyzer;
            if (imageAnalysis != null && analyzer != null) {
                // After unbinding, so no frame from the last lens arrives labelled as this one's.
                analyzer.setMirrored(mFrontFacing);
            }
            // After unbinding, so that an effect being let go is not closing outputs CameraX is
            // still drawing into, and before binding, because the group carries the effect.
            CameraEffect effect = reconcileFrameEffect(drawing);
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
            updateFrameEffectOrientation();
            observeCameraState(camera);
            mState = mVideoCapture == null ? State.OPEN : State.BINDING;
            publishSnapshot();
            // Bound, which is the first moment a capture can succeed.
            onCameraReady();
            // A new bind starts at 1x, and a new lens may not reach the zoom asked for.
            mConfirmedZoomRatio = 1f;
            applyZoomOnMainThread();
            if (mRequestedExposureCompensation != 0) {
                setExposureCompensationOnMainThread(mRequestedExposureCompensation);
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
        return !mGlUnavailable;
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
     * <p>Nothing goes through GL until somebody asks. An effect makes CameraX share one camera
     * stream between the preview and the recording and draw every frame of it, and a still is
     * drawn after it is taken, so a library that did it unasked would be charging every app for a
     * feature most of them never use.</p>
     */
    private void ensureFrameEffect() {
        if (mFrameEffect != null || mGlUnavailable) return;
        FrameRenderer renderer = getFrameRenderer();
        CameraTextureProvider textureProvider = getCameraTextureProvider();
        if (renderer == null && textureProvider == null) return;
        mFrameEffect = FrameEffect.create(FrameEffect.TARGETS, mMainExecutor, renderer,
                textureProvider, this::onFrameEffectFailed);
        if (mFrameEffect == null) {
            // The renderer logged why. The camera goes on being bound without an effect, which is
            // a camera that works and an effect that does not, rather than no camera at all.
            Log.w(TAG, "Showing the camera without effects; this device will not give us GL");
            mGlUnavailable = true;
        }
    }

    /**
     * Whether an effect can be part of a bind that also carries a custom stream, which it cannot.
     *
     * <p>A custom stream needs a second Preview, and CameraX picks the use cases an effect applies
     * to by target type - it cannot tell two Previews apart. The effect gives way, because the
     * stream was asked for on purpose where an effect may only be decorating.</p>
     */
    static boolean canBindFrameEffect(boolean hasCustomStream) {
        return !hasCustomStream;
    }

    /**
     * The format the still use case is built for: the one asked for, or JPEG while frames are
     * drawn.
     *
     * <p>A drawn still is the JPEG the renderer's drawing is encoded as, and a raw or Ultra HDR
     * still cannot be drawn. So the effect wins, as it does on Camera2: the still is a drawn JPEG,
     * Ultra HDR falls back to it, and a capture asked for as RAW is refused rather than quietly
     * written as something else.</p>
     */
    @NonNull
    static CameraView.PictureFormat boundPictureFormat(
            @NonNull CameraView.PictureFormat requested, boolean drawing) {
        return drawing ? CameraView.PictureFormat.JPEG : requested;
    }

    /**
     * Brings the effect up ahead of the use cases built around it, if one is wanted and can be
     * bound. Letting one go waits for {@link #reconcileFrameEffect}, after the unbind.
     *
     * @return whether the coming bind puts frames through GL
     */
    private boolean prepareFrameEffect(boolean hasCustomStream) {
        if (!canBindFrameEffect(hasCustomStream)) {
            if (mFrameEffect != null || getFrameRenderer() != null
                    || getCameraTextureProvider() != null) {
                Log.w(TAG, "Camera frames bypass GL: a custom stream is attached");
            }
            return false;
        }
        ensureFrameEffect();
        return mFrameEffect != null;
    }

    /**
     * The effect to bind with, letting it go when the bind does not draw frames, so that the bind
     * is the one that would have happened had nobody asked for an effect.
     */
    @Nullable private CameraEffect reconcileFrameEffect(boolean drawing) {
        if (drawing) {
            return mFrameEffect;
        }
        releaseFrameEffect();
        return null;
    }

    /**
     * Tells the effect how frames are shown, which is how it turns a renderer's drawing to match.
     * Called once a camera is bound and whenever the display turns, the two things it rests on.
     */
    @MainThread
    private void updateFrameEffectOrientation() {
        FrameEffect effect = mFrameEffect;
        if (effect != null) {
            effect.setShownOrientation(getRelativeCameraOrientation(), mFrontFacing);
        }
    }

    private void releaseFrameEffect() {
        FrameEffect effect = mFrameEffect;
        if (effect == null) return;
        mFrameEffect = null;
        effect.release();
    }

    /**
     * What CameraX reports through the effect's own error listener, from its own thread.
     *
     * <p>Reaching here means CameraX accepted the effect and then could not run it, which leaves a
     * camera that is bound but showing nothing. Letting the effect go and binding again without
     * one puts the preview back; latching stops the next bind walking into the same wall.</p>
     */
    private void onFrameEffectFailed(@NonNull Throwable error) {
        Log.e(TAG, "Showing the camera without effects; CameraX could not run one", error);
        runOnMainThread(() -> {
            if (mDestroyed) return;
            mGlUnavailable = true;
            releaseFrameEffect();
            reconcileUseCases(false);
        });
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
        if (allowStabilization && isStabilizationEnabled()
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

    /**
     * @param drawing whether the bind puts frames through GL, which makes every still a JPEG
     * @param analyzing whether analysis is bound beside the still. A camera promises a RAW or
     *     Ultra HDR still, and the frames zero shutter lag keeps, beside a preview alone, so the
     *     still is then a JPEG taken as it is asked for, as it is beside a Camera2 stream.
     */
    @NonNull private ImageCapture createImageCapture(
            @NonNull CameraInfo cameraInfo, boolean drawing, boolean analyzing) {
        boolean plain = drawing || analyzing;
        CameraView.PictureFormat format = boundPictureFormat(getPictureFormat(), plain);
        ImageCapture.Builder builder = new ImageCapture.Builder()
                .setResolutionSelector(createImageResolutionSelector())
                .setTargetRotation(getTargetRotation())
                .setFlashMode(getFlashMode())
                .setCaptureMode(getCaptureMode(cameraInfo, format, analyzing))
                .setJpegQuality(getPictureQuality());
        // Checked against this camera as well as on request, since the lens may have changed.
        if (format == CameraView.PictureFormat.ULTRA_HDR && writesUltraHdr(cameraInfo)) {
            builder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR);
        }
        Size sensorSize = DngWriter.sensorSize(cameraCharacteristics(cameraInfo));
        if (writesRaw(format) && writesRaw(cameraInfo, format) && sensorSize != null) {
            builder.setOutputFormat(outputFormatFor(format));
            // DngCreator only writes a frame the sensor's size, so that is the one size asked for.
            // Should CameraX not offer it after all, its own choice stands and a frame of the
            // wrong size fails the capture rather than the bind.
            builder.setResolutionSelector(new ResolutionSelector.Builder()
                    .setResolutionFilter((sizes, rotation) -> sizes.contains(sensorSize)
                            ? Collections.singletonList(sensorSize) : sizes)
                    .build());
            applyRawCaptureCallback(builder);
        }
        return builder.build();
    }

    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private void applyRawCaptureCallback(@NonNull ImageCapture.Builder builder) {
        new Camera2Interop.Extender<>(builder).setSessionCaptureCallback(mRawCaptureCallback);
    }

    /** Whether a format writes a DNG, with or without a JPEG beside it. */
    private static boolean writesRaw(@NonNull CameraView.PictureFormat format) {
        return format == CameraView.PictureFormat.RAW
                || format == CameraView.PictureFormat.RAW_JPEG;
    }

    /** Whether a bound use case was configured to capture RAW. */
    private static boolean writesRaw(@NonNull ImageCapture imageCapture) {
        int format = imageCapture.getOutputFormat();
        return format == ImageCapture.OUTPUT_FORMAT_RAW
                || format == ImageCapture.OUTPUT_FORMAT_RAW_JPEG;
    }

    /**
     * Whether this camera can be asked for a DNG in a given format: CameraX has to offer the
     * output format, and the camera a RAW output at a size DngCreator accepts.
     */
    private boolean writesRaw(
            @NonNull CameraInfo cameraInfo, @NonNull CameraView.PictureFormat format) {
        try {
            return ImageCapture.getImageCaptureCapabilities(cameraInfo).getSupportedOutputFormats()
                    .contains(outputFormatFor(format))
                    && DngWriter.sensorSize(cameraCharacteristics(cameraInfo)) != null;
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not ask whether this camera writes RAW", error);
            return false;
        }
    }

    private static int outputFormatFor(@NonNull CameraView.PictureFormat format) {
        switch (format) {
            case RAW:
                return ImageCapture.OUTPUT_FORMAT_RAW;
            case RAW_JPEG:
                return ImageCapture.OUTPUT_FORMAT_RAW_JPEG;
            case ULTRA_HDR:
                return ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR;
            case JPEG:
                // Fall-through
            default:
                return ImageCapture.OUTPUT_FORMAT_JPEG;
        }
    }

    @Nullable private CameraCharacteristics cameraCharacteristics(@Nullable Camera camera) {
        return camera == null ? null : cameraCharacteristics(camera.getCameraInfo());
    }

    /**
     * A camera's characteristics, whole, through the camera2 camera underneath CameraX.
     *
     * <p>DngCreator reads them whole rather than a key at a time, which Camera2CameraInfo is
     * limited to, so they come from CameraManager by the id it reports.</p>
     */
    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    @Nullable private CameraCharacteristics cameraCharacteristics(@NonNull CameraInfo cameraInfo) {
        try {
            CameraManager manager =
                    (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
            return manager.getCameraCharacteristics(
                    Camera2CameraInfo.from(cameraInfo).getCameraId());
        } catch (CameraAccessException | RuntimeException | LinkageError error) {
            if (DEBUG) Log.d(TAG, "Camera cannot report its characteristics", error);
            return null;
        }
    }

    private static boolean writesUltraHdr(@NonNull CameraInfo cameraInfo) {
        try {
            return ImageCapture.getImageCaptureCapabilities(cameraInfo).getSupportedOutputFormats()
                    .contains(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR);
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not ask which picture formats this camera writes", error);
            return false;
        }
    }

    @Override public boolean isPictureFormatSupported(CameraView.PictureFormat format) {
        Camera camera = mActiveCamera;
        if (writesRaw(format)) {
            // A drawn picture is a JPEG, so there is no sensor frame to write a DNG from.
            return camera != null && !isDrawingFrames()
                    && writesRaw(camera.getCameraInfo(), format);
        }
        if (format != CameraView.PictureFormat.ULTRA_HDR) {
            return super.isPictureFormatSupported(format);
        }
        return camera != null && !isDrawingFrames() && writesUltraHdr(camera.getCameraInfo());
    }

    @Override protected boolean isDrawingFrames() {
        return mFrameEffect != null;
    }

    @Override public void setPictureFormat(CameraView.PictureFormat format) {
        CameraView.PictureFormat before = getPictureFormat();
        super.setPictureFormat(format);
        if (getPictureFormat() != before) {
            // The format belongs to the use case, not to a capture.
            rebindConfiguration();
        }
    }

    @NonNull private ImageAnalysis createImageAnalysis() {
        ExecutorService executor = mAnalysisExecutor;
        CameraXFrameAnalyzer analyzer = mFrameAnalyzer;
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
        mRawPairer.clear();
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
        return imageResolutionSelector(getQuality(), getAspectRatioStrategy());
    }

    /**
     * How a still is sized for a quality.
     *
     * <p>MAX takes the largest picture the camera gives, whatever its shape: the view port crops
     * every output to the view, and a crop of the whole sensor holds more than a frame already
     * cut to the view's shape. The higher-resolution mode adds the sizes a camera only delivers
     * slowly, which is the trade a MAX picture makes.</p>
     */
    @NonNull static ResolutionSelector imageResolutionSelector(
            @NonNull CameraView.Quality quality, @NonNull AspectRatioStrategy aspectRatioStrategy) {
        ResolutionSelector.Builder builder =
                new ResolutionSelector.Builder().setAspectRatioStrategy(aspectRatioStrategy);
        if (quality == CameraView.Quality.MAX) {
            return builder
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .setResolutionFilter((sizes, rotationDegrees) -> largestFirst(sizes))
                    .setAllowedResolutionMode(
                            ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                    .build();
        }
        return builder
                .setResolutionStrategy(new ResolutionStrategy(imageTargetSize(quality),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build();
    }

    /** The sizes by area, largest first, which is the order CameraX then tries them in. */
    @NonNull static List<Size> largestFirst(@NonNull List<Size> sizes) {
        List<Size> sorted = new ArrayList<>(sizes);
        Collections.sort(sorted, (a, b) -> Long.compare(
                (long) b.getWidth() * b.getHeight(), (long) a.getWidth() * a.getHeight()));
        return sorted;
    }

    /** The bound a still is held to at every quality but MAX, which has none. */
    @NonNull static Size imageTargetSize(@NonNull CameraView.Quality quality) {
        switch (quality) {
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
                boolean analysisCompatible = mFrameAnalyzer == null
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

    /**
     * MAX waits for the best frame. Below it, a picture is a frame the camera already has where
     * it keeps them, which CameraX itself gives up whenever the flash is on or on auto.
     *
     * <p>RAW never comes from the buffer: CameraX takes such a picture by reprocessing a kept
     * frame, and a reprocessed capture writes only JPEG or YUV, never a RAW_SENSOR frame.</p>
     *
     * <p>A drawn still is taken like any other. The still is not one of the effect's outputs, so
     * the frames kept for it are the still's own, and the picture is drawn after it is taken.
     * Taken otherwise, it would not be the picture the camera takes undrawn: on the API 37
     * emulator CameraX keeps frames at 1856x1392 and takes a picture without them at 1280x720.</p>
     *
     * <p>A still beside analysis is not: a camera promises a still beside a preview and analysis,
     * not with the surfaces frames are kept and reprocessed through as well.</p>
     *
     * @param analyzing whether analysis is bound beside the still
     */
    @OptIn(markerClass = ExperimentalZeroShutterLag.class)
    private int getCaptureMode(@NonNull CameraInfo cameraInfo,
            @NonNull CameraView.PictureFormat format, boolean analyzing) {
        return captureMode(getQuality(), cameraInfo.isZslSupported() && !analyzing, format);
    }

    /**
     * See {@link #getCaptureMode}.
     *
     * @param zslSupported whether the camera keeps frames to take a picture from
     */
    @OptIn(markerClass = ExperimentalZeroShutterLag.class)
    static int captureMode(@NonNull CameraView.Quality quality, boolean zslSupported,
            @NonNull CameraView.PictureFormat format) {
        if (quality == CameraView.Quality.MAX) {
            return ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY;
        }
        return zslSupported && !writesRaw(format)
                ? ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG
                : ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY;
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
            if (mSurfaceProviders.isEmpty() && isStabilizationEnabled()
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
            updateFrameEffectOrientation();
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
            // Unbinding an ImageCapture aborts the capture in flight, so the rebind waits for it.
            if (isCapturing()) {
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
        if (mOpenRequested && !mPaused && mVideoRecording == null && !isCapturing()) {
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

    /** The flash mode the bound still capture would use, or -1 for none; used only by tests. */
    int imageCaptureFlashModeForTesting() {
        return mImageCapture == null ? -1 : mImageCapture.getFlashMode();
    }

    private void runOnMainThread(@NonNull Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else mMainHandler.post(action);
    }
}