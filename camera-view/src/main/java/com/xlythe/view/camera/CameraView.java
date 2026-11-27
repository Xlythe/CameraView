package com.xlythe.view.camera;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RawRes;
import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;
import androidx.annotation.UiThread;
import androidx.annotation.VisibleForTesting;

import com.xlythe.view.camera.legacy.LegacyCameraModule;
import com.xlythe.view.camera.v2.Camera2Module;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * A {@link View} that displays a preview of the camera with methods {@link #takePicture()},
 * {@link #startRecording()} and {@link #stopRecording()}.
 *
 * Because the Camera is a limited resource and consumes a high amount of power, CameraView must be
 * opened/closed. It's recommended to call {@link #open()} in
 * {@link android.app.Activity#onStart()} and {@link #close()} in
 * {@link android.app.Activity#onStop()}.
 */
public class CameraView extends FrameLayout {
    static final String TAG = CameraView.class.getSimpleName();
    static final boolean DEBUG = false;

    /**
     * Which implementation of the camera a {@link CameraView} drives.
     *
     * <p>Camera2 is the default because it is what this library has been shipping and validating
     * on devices. CameraX owns more behaviour - it carries a database of per-device quirks, and it
     * is the only backend that can reach a manufacturer's own Extensions - but it is opt-in until
     * it has had the same validation here.</p>
     *
     * @see #setDefaultBackend
     */
    public enum Backend {
        /** The newest implementation the device supports. */
        AUTO(0),
        /** {@code android.hardware.camera2}, the default. */
        CAMERA2(1),
        /**
         * {@code androidx.camera}.
         *
         * <p>Needs the androidx.camera dependencies on the classpath. An app that excluded them
         * falls back to {@link #CAMERA2}.</p>
         */
        CAMERA_X(2),
        /** {@code android.hardware.Camera}, for devices that predate camera2. */
        LEGACY(3);

        final int id;

        Backend(int id) {
            this.id = id;
        }

        static Backend fromId(int id) {
            for (Backend backend : values()) {
                if (backend.id == id) {
                    return backend;
                }
            }
            return AUTO;
        }
    }

    /** The backend a CameraView uses when its layout does not name one. */
    private static volatile Backend sDefaultBackend = Backend.AUTO;

    /**
     * Chooses the backend for every CameraView created from now on.
     *
     * <p>Set this before the view is inflated - in {@code Application.onCreate}, say - because a
     * view picks its backend once, when it is created. A single view can be told directly with
     * {@code app:backend} in its layout.</p>
     */
    public static void setDefaultBackend(@NonNull Backend backend) {
        sDefaultBackend = backend;
    }

    @NonNull
    public static Backend getDefaultBackend() {
        return sDefaultBackend;
    }

    // When enabled, CameraV2 will be used. It's currently stable.
    static final boolean USE_CAMERA_V2 = true;

    public static final int INDEFINITE_VIDEO_DURATION = -1;
    public static final int INDEFINITE_VIDEO_SIZE = -1;

    public static final String ACTION_CAMERA_STATE_CHANGED = "com.xlythe.view.camera.CAMERA_STATE_CHANGED";

    /**
     * How still layout has to be before the camera is reconfigured for the size it reached.
     *
     * <p>Long enough to cover a rotation, a split-screen drag, a foldable opening or a Compose
     * layout taking several passes to settle - each of which arrives as a run of layout passes at
     * different sizes - and short enough that it is over before a user has finished looking at
     * the new shape of the window.</p>
     */
    private static final long LAYOUT_SETTLE_DELAY_MILLIS = 150;

    /**
     * How long a focus request is given before it is reported as having not converged.
     *
     * <p>A camera is not obliged to answer. It can be closed, or switched to another lens, with a
     * request outstanding, and an app drawing a reticle has no other way to learn that the answer
     * is never coming.</p>
     */
    private static final long FOCUS_TIMEOUT_MILLIS = 3000;

    /**
     * How long an animated {@link #setZoomRatio(float, boolean)} takes. About what a phone's own
     * camera app takes on a tap of a zoom button: long enough to see the lenses change, short
     * enough that nobody waits for it.
     */
    private static final long ZOOM_ANIMATION_MILLIS = 300;

    /**
     * The cache directory {@link #takePicture()} and {@link #startRecording()} write to. A capture
     * that arrives from inside it is one of ours to file rather than a file the caller chose,
     * which is also how a pending confirmation is recognised again after state is restored.
     */
    private static final String CAPTURE_DIRECTORY = "com.xlythe.view.camera";

    /**
     * How a capture without a file is named, which is the name the gallery shows. Milliseconds,
     * because two captures inside one second is an ordinary double tap, and dashes, because
     * MediaStore rejects a colon in a display name.
     */
    private static final String CAPTURE_NAME = "yyyy-MM-dd-HH-mm-ss-SSS";

    private static final String EXTRA_SUPER = "super";
    private static final String EXTRA_MODULE = "module";
    private static final String EXTRA_QUALITY = "quality";
    /**
     * Saved-state key for the zoom. A ratio rather than a level, because a level is only a zoom
     * once the camera's range is known, and on restore the camera may not be open yet.
     */
    private static final String EXTRA_ZOOM_RATIO = "zoom_ratio";
    private static final String EXTRA_PINCH_TO_ZOOM_ENABLED = "pinch_to_zoom_enabled";
    private static final String EXTRA_FLASH = "flash";
    private static final String EXTRA_LENS_FACING = "lens_facing";
    private static final String EXTRA_MAX_VIDEO_DURATION = "max_video_duration";
    private static final String EXTRA_MAX_VIDEO_SIZE = "max_video_size";
    private static final String EXTRA_CONFIRM_IMAGE = "confirm_image";
    private static final String EXTRA_CONFIRM_VIDEO = "confirm_video";
    private static final String EXTRA_PENDING_IMAGE_FILE_PATH = "pending_image_file_path";
    /** Saved-state key for every file the picture awaiting confirmation wrote. */
    private static final String EXTRA_PENDING_IMAGE_FILE_PATHS = "pending_image_file_paths";
    private static final String EXTRA_PENDING_VIDEO_FILE_PATH = "pending_video_file_path";
    private static final String EXTRA_MATCH_PREVIEW_ASPECT_RATIO = "match_preview_aspect_ratio";
    /** Saved-state key for dynamic range. */
    private static final String EXTRA_DYNAMIC_RANGE = "dynamic_range";
    /** Saved-state key for the CameraX photo extension. */
    private static final String EXTRA_EXTENSION = "extension";
    /** Saved-state key for stabilization. */
    private static final String EXTRA_STABILIZATION = "stabilization";
    /** Saved-state key for exposure compensation. */
    private static final String EXTRA_EXPOSURE_COMPENSATION = "exposure_compensation";
    /** Saved-state key for torch state. */
    private static final String EXTRA_TORCH = "torch";
    /** Saved-state key for low-light boost state. */
    private static final String EXTRA_LOW_LIGHT_BOOST = "low_light_boost";
    /** Saved-state key for whether the camera's sounds play. */
    private static final String EXTRA_SHUTTER_SOUND = "shutter_sound";
    /** Saved-state key for the app's own shutter sound. */
    private static final String EXTRA_SHUTTER_SOUND_RES = "shutter_sound_res";

    /**
     * The lifecycle state of the camera and its preview surface.
     *
     * <p>Status changes are serialized on the main thread.</p>
     */
    private enum Status {
        CLOSED,
        WAITING_FOR_SURFACE,
        OPENING,
        OPEN,
        CLOSING,
        ERROR
    }

    /**
     * Determines the resolution of CameraView's outputs. All resolutions are best attempts, and
     * will fall to lower qualities if the Android device cannot support them. Resolutions also may
     * change in the future (if, say, Android adds 8k resolution).
     *
     * (*) {@link Quality#MAX} will output at 4k.
     * (*) {@link Quality#HIGH} will output at 1080p.
     * (*) {@link Quality#MEDIUM} will output at 720.
     * (*) {@link Quality#LOW} will output at 480.
     */
    public enum Quality {
        MAX(0), HIGH(1), MEDIUM(2), LOW(3);

        private final int id;

        Quality(int id) {
            this.id = id;
        }

        static Quality fromId(int id) {
            for (Quality q : values()) {
                if (q.id == id) return q;
            }
            throw new IllegalArgumentException();
        }
    }

    /**
     * Determines the use of the camera's flash when taking pictures.
     */
    public enum Flash {
        AUTO(0), ON(1), OFF(2);

        private final int id;

        Flash(int id) {
            this.id = id;
        }

        static Flash fromId(int id) {
            for (Flash f : values()) {
                if (f.id == id) return f;
            }
            throw new IllegalArgumentException();
        }
    }

    /**
     * Adjusts which camera to use.
     */
    public enum LensFacing {
        BACK(0), FRONT(1);

        private final int id;

        LensFacing(int id) {
            this.id = id;
        }

        static LensFacing fromId(int id) {
            for (LensFacing lf : values()) {
                if (lf.id == id) return lf;
            }
            throw new IllegalArgumentException();
        }
    }

    /** Selects an optional CameraX vendor photo-processing extension. */
    public enum Extension {
        NONE(0), BOKEH(1), HDR(2), NIGHT(3), FACE_RETOUCH(4), AUTO(5);

        /** Stable XML and saved-state identifier. */
        private final int id;

        Extension(int id) {
            this.id = id;
        }

        static Extension fromId(int id) {
            for (Extension extension : values()) {
                if (extension.id == id) return extension;
            }
            throw new IllegalArgumentException();
        }
    }

    /** Selects standard or high dynamic range video when supported by the active backend. */
    public enum DynamicRange {
        SDR(0), HDR(1);

        /** Stable XML and saved-state identifier. */
        private final int id;

        DynamicRange(int id) {
            this.id = id;
        }

        static DynamicRange fromId(int id) {
            for (DynamicRange dynamicRange : values()) {
                if (dynamicRange.id == id) return dynamicRange;
            }
            throw new IllegalArgumentException();
        }
    }

    /**
     * The range of colours a picture is recorded in.
     *
     * <p>sRGB is what every camera has always produced and what every viewer understands. A
     * modern sensor can see more than sRGB can describe, and a modern screen can show more, which
     * is why a manufacturer's own camera app saves wide-gamut stills - the difference is visible
     * in saturated reds and greens, where sRGB clips.</p>
     *
     * <p>Wide gamut is not free: a viewer that ignores the colour profile shows the picture
     * oversaturated, so it is opt-in rather than the default.</p>
     */
    public enum ColorSpace {
        /** What every camera has always produced. */
        SRGB(0),
        /** Wider than sRGB, and what Android and Apple both use for wide-gamut images. */
        DISPLAY_P3(1);

        /** Stable XML and saved-state identifier. */
        private final int id;

        ColorSpace(int id) {
            this.id = id;
        }

        static ColorSpace fromId(int id) {
            for (ColorSpace colorSpace : values()) {
                if (colorSpace.id == id) return colorSpace;
            }
            throw new IllegalArgumentException();
        }
    }

    /**
     * The file a picture is written as.
     *
     * <p>{@link #ULTRA_HDR} is a JPEG carrying a gain map: an HDR display uses it to brighten the
     * highlights, and anything else shows the JPEG beneath.</p>
     *
     * <p>{@link #RAW} is a DNG of the sensor's own readings, before the camera has processed them.
     * It is several times the size of a JPEG and needs an editor rather than a gallery to open,
     * which is what {@link #RAW_JPEG} is for: the JPEG at the file given to
     * {@link #takePicture(File)}, and the DNG beside it under the same name, so {@code IMG_1.jpg}
     * gets {@code IMG_1.dng}. Either way the capture is reported once, after every file it writes
     * is on disk. {@link #takePicture()} files both in the gallery, under the same name.</p>
     */
    public enum PictureFormat {
        /** What every camera writes. */
        JPEG,
        ULTRA_HDR,
        RAW,
        RAW_JPEG
    }

    /**
     * {@link TextureView.SurfaceTextureListener} handles several lifecycle events on a
     * {@link TextureView}.
     */
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            if (DEBUG) Log.v(TAG, "Surface Texture now available.");
            if (getStatus() == Status.WAITING_FOR_SURFACE) {
                openOnMainThread();
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {}

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            Status state = getStatus();
            if (state == Status.OPEN || state == Status.OPENING
                    || state == Status.WAITING_FOR_SURFACE) {
                Log.w(TAG, "Surface destroyed while camera was active.");
                close();
            }
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {}
    };

    /** The current internal camera lifecycle status. Guarded by this CameraView monitor. */
    private Status mStatus = Status.CLOSED;
    private OnImageCapturedListener mOnImageCapturedListener;
    private OnVideoCapturedListener mOnVideoCapturedListener;

    // For overriding onTouch
    private final int mTouchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
    private final int mLongPressTimeout = ViewConfiguration.getLongPressTimeout();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private float mInitialMotionEventX;
    private float mInitialMotionEventY;
    private boolean mIsLongPressMotionEvent;
    /** Dispatches the pending long-press gesture without clearing unrelated handler work. */
    private final Runnable mLongPressRunnable = () -> {
        if (Build.VERSION.SDK_INT >= 24) {
            performLongClick(mInitialMotionEventX, mInitialMotionEventY);
        } else {
            performLongClick();
        }
        mIsLongPressMotionEvent = true;
    };

    // For tap-to-focus
    @Nullable
    private FocusListener mFocusListener;
    /** Where the outstanding focus request was aimed, in view coordinates, or null for none. */
    @Nullable
    private RectF mPendingFocusArea;
    /** Answers a request the camera never came back about, so every request is answered once. */
    private final Runnable mFocusTimeoutRunnable = () -> onFocusCompleted(false);
    /** Drawn over the preview where a focus request lands. */
    @Nullable
    private FocusReticle mFocusReticle;
    private boolean mFocusReticleEnabled = true;
    @ColorInt
    private int mFocusReticleColor = FocusReticle.DEFAULT_FOCUSING_COLOR;
    @ColorInt
    private int mFocusReticleLockedColor = FocusReticle.DEFAULT_LOCKED_COLOR;

    // For pinch-to-zoom
    private PinchToZoomGestureDetector mScaleDetector;
    private boolean mIsPinchToZoomEnabled = true;

    /** Told of every zoom change. See {@link #setOnZoomChangedListener}. */
    @Nullable
    private OnZoomChangedListener mOnZoomChangedListener;
    /**
     * The ratio the zoom listener was last told about, so a backend reporting a ratio twice is
     * passed on once. Kept with no listener too, so one set later hears only real changes.
     */
    private float mLastReportedZoomRatio = Float.NaN;
    /** Eases the zoom over to {@link #mZoomAnimationTarget}, or null while no animation runs. */
    @Nullable
    private ValueAnimator mZoomAnimator;
    /**
     * Where the running zoom animation lands. Saved in place of the zoom it has reached, so a
     * restore lands there too.
     */
    private float mZoomAnimationTarget;

    private ICameraModule mCameraModule;

    /** Follows the device's location while the camera is open, for the backend to tag captures. */
    private final LocationTracker mLocationTracker = new LocationTracker(getContext());

    /** Lets a burst of layout passes finish before the camera is reconfigured for the last one. */
    private final Runnable mLayoutSettledRunnable = this::onLayoutSettled;

    @Nullable
    private OnCameraStateChangedListener mOnCameraStateChangedListener;

    private TextureView mCameraView;
    private ImageView mImagePreview;
    private VideoView mVideoPreview;

    private File mImagePendingConfirmation;
    /**
     * Every file the picture awaiting confirmation wrote, {@link #mImagePendingConfirmation}
     * first. More than one for {@link PictureFormat#RAW_JPEG}, and empty when nothing is pending.
     */
    private List<File> mImagePendingFiles = Collections.emptyList();
    private File mVideoPendingConfirmation;

    private boolean mIsImageConfirmationEnabled;
    private boolean mIsVideoConfirmationEnabled;

    @Nullable
    private BarcodeScanner mBarcodeScanner;
    /** Whether the active backend owns a native barcode analysis use case. */
    private boolean mUsingModuleBarcodeScanner;

    /** The caller's own frame analyzer, if one is running. */
    @Nullable
    private FrameStream mFrameStream;

    /** The caller's face listener, if the camera agreed to report faces. */
    @Nullable
    private FaceDetectionListener mFaceDetectionListener;

    // When true, avoid adding/removing views. While usually harmless (although it can cause state
    // loss), it's especially important since calling removeView(TextureView) after
    // onSaveInstanceState() will trigger a NPE on Android N.
    private boolean mHasSavedState = false;

    public CameraView(Context context) {
        this(context, null);
    }

    public CameraView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CameraView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context, attrs);
    }

    @TargetApi(21)
    public CameraView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context, attrs);
    }

    /**
     * Builds the backend, falling back when the one asked for cannot run here.
     *
     * <p>CameraX is created reflectively so that androidx.camera is a dependency an app can
     * exclude: a build that never selects CameraX never loads the class, and one that excluded
     * the dependency and asks for it anyway gets Camera2 and a warning rather than a
     * NoClassDefFoundError out of a constructor.</p>
     */
    @NonNull
    private ICameraModule createModule(@NonNull Backend backend) {
        if (Build.VERSION.SDK_INT < 21) {
            return new LegacyCameraModule(this);
        }
        switch (backend) {
            case LEGACY:
                return new LegacyCameraModule(this);
            case CAMERA_X:
                ICameraModule cameraX = createCameraXModule();
                if (cameraX != null) {
                    return cameraX;
                }
                Log.w(TAG, "androidx.camera is not on the classpath; using Camera2 instead");
                return new Camera2Module(this);
            case AUTO:
            case CAMERA2:
            default:
                return USE_CAMERA_V2 ? new Camera2Module(this) : new LegacyCameraModule(this);
        }
    }

    @Nullable
    private ICameraModule createCameraXModule() {
        try {
            return (ICameraModule) Class.forName("com.xlythe.view.camera.x.CameraXModule")
                    .getConstructor(CameraView.class)
                    .newInstance(this);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            if (DEBUG) Log.d(TAG, "CameraX is unavailable", error);
            return null;
        }
    }

    /** Which implementation this view ended up driving. */
    @NonNull
    public Backend getBackend() {
        if (mCameraModule instanceof LegacyCameraModule) {
            return Backend.LEGACY;
        }
        if (mCameraModule instanceof Camera2Module) {
            return Backend.CAMERA2;
        }
        return Backend.CAMERA_X;
    }

    private void init(Context context, @Nullable AttributeSet attrs) {
        Backend backend = sDefaultBackend;
        if (attrs != null) {
            TypedArray chosen = context.obtainStyledAttributes(attrs, R.styleable.CameraView);
            backend = Backend.fromId(
                    chosen.getInteger(R.styleable.CameraView_backend, backend.id));
            chosen.recycle();
        }
        mCameraModule = createModule(backend);

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.CameraView);
            setQuality(Quality.fromId(a.getInteger(R.styleable.CameraView_quality, getQuality().id)));
            setFlash(Flash.fromId(a.getInteger(R.styleable.CameraView_flash, getFlash().id)));
            setLensFacing(LensFacing.fromId(a.getInteger(R.styleable.CameraView_lensFacing, getLensFacing().id)));
            setPinchToZoomEnabled(a.getBoolean(R.styleable.CameraView_pinchToZoomEnabled, isPinchToZoomEnabled()));
            if (a.hasValue(R.styleable.CameraView_maxVideoDuration)) {
                setMaxVideoDuration(a.getInteger(R.styleable.CameraView_maxVideoDuration, INDEFINITE_VIDEO_DURATION));
            }
            if (a.hasValue(R.styleable.CameraView_maxVideoSize)) {
                setMaxVideoSize(a.getInteger(R.styleable.CameraView_maxVideoSize, INDEFINITE_VIDEO_SIZE));
            }
            setImageConfirmationEnabled(a.getBoolean(R.styleable.CameraView_confirmImages, isImageConfirmationEnabled()));
            setVideoConfirmationEnabled(a.getBoolean(R.styleable.CameraView_confirmVideos, isVideoConfirmationEnabled()));
            setMatchPreviewAspectRatio(a.getBoolean(R.styleable.CameraView_matchPreviewAspectRatio, isMatchPreviewAspectRatioEnabled()));
            setShutterSoundEnabled(a.getBoolean(R.styleable.CameraView_shutterSoundEnabled, isShutterSoundEnabled()));
            setShutterSound(a.getResourceId(R.styleable.CameraView_shutterSound, getShutterSound()));
            setDynamicRange(DynamicRange.fromId(a.getInteger(R.styleable.CameraView_dynamicRange, getDynamicRange().id)));
            setExtension(Extension.fromId(a.getInteger(R.styleable.CameraView_extension, getExtension().id)));
            setStabilizationEnabled(a.getBoolean(R.styleable.CameraView_stabilizationEnabled, isStabilizationEnabled()));
            setExposureCompensation(a.getInteger(R.styleable.CameraView_exposureCompensation, getExposureCompensation()));
            setTorchEnabled(a.getBoolean(R.styleable.CameraView_torchEnabled, isTorchEnabled()));
            setLowLightBoostEnabled(a.getBoolean(R.styleable.CameraView_lowLightBoostEnabled, isLowLightBoostEnabled()));
            mFocusReticleEnabled = a.getBoolean(R.styleable.CameraView_focusReticleEnabled, true);
            mFocusReticleColor = a.getColor(
                    R.styleable.CameraView_focusReticleColor, FocusReticle.DEFAULT_FOCUSING_COLOR);
            mFocusReticleLockedColor = a.getColor(
                    R.styleable.CameraView_focusReticleLockedColor, FocusReticle.DEFAULT_LOCKED_COLOR);
            a.recycle();
        }

        if (getBackground() == null) {
            setBackgroundColor(0xFF111111);
        }

        mScaleDetector = new PinchToZoomGestureDetector(context);
        ensurePreviewViews();
    }

    @Override
    public void onFinishInflate() {
        super.onFinishInflate();
        ensurePreviewViews();
    }

    private void ensurePreviewViews() {
        if (mCameraView != null) {
            return;
        }
        addView(mCameraView = new TextureView(getContext()));
        addView(mImagePreview = new ImageView(getContext()));
        addView(mVideoPreview = new VideoView(getContext()));
        addView(mFocusReticle = new FocusReticle(getContext()));
        mFocusReticle.setColors(mFocusReticleColor, mFocusReticleLockedColor);
        mImagePreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mImagePreview.setVisibility(View.GONE);
        mVideoPreview.setVisibility(View.GONE);
        mCameraView.setSurfaceTextureListener(mSurfaceTextureListener);
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        Bundle state = new Bundle();
        state.putParcelable(EXTRA_SUPER, super.onSaveInstanceState());
        state.putParcelable(EXTRA_MODULE, mCameraModule.onSaveInstanceState());
        state.putInt(EXTRA_QUALITY, getQuality().id);
        state.putFloat(EXTRA_ZOOM_RATIO,
                mZoomAnimator != null ? mZoomAnimationTarget : getZoomRatio());
        state.putBoolean(EXTRA_PINCH_TO_ZOOM_ENABLED, isPinchToZoomEnabled());
        state.putInt(EXTRA_FLASH, getFlash().id);
        state.putInt(EXTRA_LENS_FACING, getLensFacing().id);
        state.putLong(EXTRA_MAX_VIDEO_DURATION, getMaxVideoDuration());
        state.putLong(EXTRA_MAX_VIDEO_SIZE, getMaxVideoSize());
        state.putBoolean(EXTRA_CONFIRM_IMAGE, isImageConfirmationEnabled());
        state.putBoolean(EXTRA_CONFIRM_VIDEO, isVideoConfirmationEnabled());
        state.putBoolean(EXTRA_MATCH_PREVIEW_ASPECT_RATIO, isMatchPreviewAspectRatioEnabled());
        state.putInt(EXTRA_DYNAMIC_RANGE, getDynamicRange().id);
        state.putInt(EXTRA_EXTENSION, getExtension().id);
        state.putBoolean(EXTRA_STABILIZATION, isStabilizationEnabled());
        state.putInt(EXTRA_EXPOSURE_COMPENSATION, getExposureCompensation());
        state.putBoolean(EXTRA_TORCH, isTorchEnabled());
        state.putBoolean(EXTRA_LOW_LIGHT_BOOST, isLowLightBoostEnabled());
        state.putBoolean(EXTRA_SHUTTER_SOUND, isShutterSoundEnabled());
        state.putInt(EXTRA_SHUTTER_SOUND_RES, getShutterSound());
        if (mImagePendingConfirmation != null) {
            state.putString(EXTRA_PENDING_IMAGE_FILE_PATH, mImagePendingConfirmation.getAbsolutePath());
            String[] paths = new String[mImagePendingFiles.size()];
            for (int i = 0; i < paths.length; i++) {
                paths[i] = mImagePendingFiles.get(i).getAbsolutePath();
            }
            state.putStringArray(EXTRA_PENDING_IMAGE_FILE_PATHS, paths);
        }
        if (mVideoPendingConfirmation != null) {
            state.putString(EXTRA_PENDING_VIDEO_FILE_PATH, mVideoPendingConfirmation.getAbsolutePath());
        }
        mHasSavedState = true;
        return state;
    }

    @Override
    protected void onRestoreInstanceState(Parcelable savedState) {
        if (savedState instanceof Bundle) {
            Bundle state = (Bundle) savedState;
            super.onRestoreInstanceState(state.getParcelable(EXTRA_SUPER));
            mCameraModule.onRestoreInstanceState(state.getParcelable(EXTRA_MODULE));
            setQuality(Quality.fromId(state.getInt(EXTRA_QUALITY)));
            setZoomRatio(state.getFloat(EXTRA_ZOOM_RATIO, 1f));
            setPinchToZoomEnabled(state.getBoolean(EXTRA_PINCH_TO_ZOOM_ENABLED));
            setFlash(Flash.fromId(state.getInt(EXTRA_FLASH)));
            setLensFacing(LensFacing.fromId(state.getInt(EXTRA_LENS_FACING)));
            setMaxVideoDuration(state.getLong(EXTRA_MAX_VIDEO_DURATION));
            setMaxVideoSize(state.getLong(EXTRA_MAX_VIDEO_SIZE));
            setImageConfirmationEnabled(state.getBoolean(EXTRA_CONFIRM_IMAGE));
            setVideoConfirmationEnabled(state.getBoolean(EXTRA_CONFIRM_VIDEO));
            setMatchPreviewAspectRatio(state.getBoolean(EXTRA_MATCH_PREVIEW_ASPECT_RATIO));
            setDynamicRange(DynamicRange.fromId(state.getInt(EXTRA_DYNAMIC_RANGE, DynamicRange.SDR.id)));
            setExtension(Extension.fromId(state.getInt(EXTRA_EXTENSION, Extension.NONE.id)));
            setStabilizationEnabled(state.getBoolean(EXTRA_STABILIZATION));
            setExposureCompensation(state.getInt(EXTRA_EXPOSURE_COMPENSATION));
            setTorchEnabled(state.getBoolean(EXTRA_TORCH));
            setLowLightBoostEnabled(state.getBoolean(EXTRA_LOW_LIGHT_BOOST));
            setShutterSoundEnabled(state.getBoolean(EXTRA_SHUTTER_SOUND, true));
            setShutterSound(state.getInt(EXTRA_SHUTTER_SOUND_RES));

            if (state.containsKey(EXTRA_PENDING_IMAGE_FILE_PATH)) {
                File file = new File(state.getString(EXTRA_PENDING_IMAGE_FILE_PATH));
                if (file.exists()) {
                    showImageConfirmation(file,
                            filesOf(state.getStringArray(EXTRA_PENDING_IMAGE_FILE_PATHS), file));
                }
            }
            if (state.containsKey(EXTRA_PENDING_VIDEO_FILE_PATH)) {
                File file = new File(state.getString(EXTRA_PENDING_VIDEO_FILE_PATH));
                if (file.exists()) {
                    showVideoConfirmation(file);
                }
            }
        } else {
            super.onRestoreInstanceState(savedState);
        }
        mHasSavedState = false;
    }

    protected synchronized Status getStatus() {
        return mStatus;
    }

    @UiThread
    private synchronized void setStatus(Status status) {
        if (mStatus == status) {
            return;
        }
        if (DEBUG) {
            Log.v(TAG, "Camera status set to " + status.name());
        }
        mStatus = status;
    }

    private void runOnMainThread(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            mHandler.post(runnable);
        }
    }

    /*
     * Opens the camera and starts displaying a preview. You are in charge of checking if the
     * phone has PackageManager.FEATURE_CAMERA_ANY and, if you are targeting Android M+, that the
     * phone has granted Manifest.permission.CAMERA.
     *
     * Showing a preview and taking pictures need nothing else. The microphone is only required
     * by the methods that record or stream audio, which ask for it themselves. Outputs are written
     * to the File you pass in, or filed where the gallery looks by {@link #takePicture()} and
     * {@link #startRecording()}, which below API 29 needs
     * {@link Manifest.permission#WRITE_EXTERNAL_STORAGE}.
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    public synchronized void open() {
        runOnMainThread(this::openOnMainThread);
    }

    @UiThread
    private void openOnMainThread() {
        Status state = getStatus();
        if (state == Status.OPEN || state == Status.OPENING) {
            return;
        }
        if (mCameraView == null || !mCameraView.isAvailable()) {
            setStatus(Status.WAITING_FOR_SURFACE);
            return;
        }

        setStatus(Status.OPENING);
        try {
            onOpen();
            // A backend that opens asynchronously reports for itself through onCameraReady, so
            // only one that has already finished is promoted here. Announcing it any earlier is
            // what the fix in this method's history was about: onCameraOpened is the signal an
            // app enables its shutter button on, and a camera2 session is not configured yet when
            // open() returns.
            if (getStatus() == Status.OPENING && mCameraModule.isCameraReady()) {
                onCameraReady();
            }
        } catch (RuntimeException e) {
            onCameraError(e);
        }
    }

    /**
     * Called by the active backend once the camera can actually be used.
     *
     * <p>Which is later than open() returning, for every backend that configures a session: the
     * device has to open and the session has to be configured, both asynchronously. An app that
     * enabled its shutter on the earlier signal lost its first capture.</p>
     */
    void onCameraReady() {
        runOnMainThread(() -> {
            if (getStatus() != Status.OPENING) {
                return;
            }
            setStatus(Status.OPEN);
            if (mOnCameraStateChangedListener != null) {
                mOnCameraStateChangedListener.onCameraOpened();
            }
        });
    }

    /*
     * Closes the camera.
     */
    public synchronized void close() {
        runOnMainThread(this::closeOnMainThread);
    }

    @UiThread
    private void closeOnMainThread() {
        Status state = getStatus();
        if (state == Status.CLOSED || state == Status.CLOSING) {
            return;
        }

        setStatus(Status.CLOSING);
        try {
            onClose();
        } finally {
            setStatus(Status.CLOSED);
            // A camera that is gone will not answer the focus it was in the middle of, and an
            // app should not be left holding a reticle until the timeout to find that out.
            onFocusCompleted(false);
        }
    }

    /**
     * @return True if camera is currently open
     */
    public synchronized boolean isOpen() {
        return getStatus() == Status.OPEN;
    }

    void onCameraError(Throwable error) {
        runOnMainThread(() -> {
            Log.e(TAG, "Camera operation failed", error);
            setStatus(Status.ERROR);

        });
    }

    /**
     * @return One of 0, 90, 180, 270.
     */
    protected int getDisplayRotation() {
        Display display;
        if (Build.VERSION.SDK_INT >= 17) {
            display = getDisplay();
        } else {
            display = ((WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        }

        // Null when the View is detached. If we were in the middle of a background operation,
        // better to not NPE. When the background operation finishes, it'll realize that the camera
        // was closed.
        if (display == null) {
            return 0;
        }

        int displayRotation = display.getRotation();
        switch (displayRotation) {
            case Surface.ROTATION_0:
                displayRotation = 0;
                break;
            case Surface.ROTATION_90:
                displayRotation = 90;
                break;
            case Surface.ROTATION_180:
                displayRotation = 180;
                break;
            case Surface.ROTATION_270:
                displayRotation = 270;
                break;
        }
        return displayRotation;
    }

    public TextureView asTextureView() {
        return mCameraView;
    }

    @UiThread
    public SurfaceTexture getSurfaceTexture() {
        return mCameraView.getSurfaceTexture();
    }

    @UiThread
    protected Matrix getTransform(Matrix matrix) {
        return mCameraView.getTransform(matrix);
    }

    @UiThread
    protected void setTransform(final Matrix matrix) {
        mCameraView.setTransform(matrix);
    }

    /**
     * When enabled, requires images to be confirmed before
     * {@link OnImageCapturedListener#onImageCaptured(File)} or
     * {@link OnImageCapturedListener#onImageSaved(Uri)} is triggered. A preview of the image will
     * be displayed in CameraView's preview.
     */
    public void setImageConfirmationEnabled(boolean enabled) {
        mIsImageConfirmationEnabled = enabled;
    }

    /**
     * When true, {@link #takePicture()} and {@link #takePicture(File)} will trigger the
     * {@link OnImageCapturedListener#onImageConfirmation()} callback. To continue,  call
     * {@link #confirmPicture()}.
     */
    public boolean isImageConfirmationEnabled() {
        return mIsImageConfirmationEnabled;
    }

    /**
     * When enabled, requires videos to be confirmed before
     * {@link OnVideoCapturedListener#onVideoCaptured(File)} or
     * {@link OnVideoCapturedListener#onVideoSaved(Uri)} is triggered. A preview of the video will
     * be displayed in CameraView's preview.
     */
    public void setVideoConfirmationEnabled(boolean enabled) {
        mIsVideoConfirmationEnabled = enabled;
    }

    /**
     * When true, {@link #startRecording()} and {@link #startRecording(File)} will trigger the
     * {@link OnVideoCapturedListener#onVideoConfirmation()} callback. To continue,  call
     * {@link #confirmVideo()}.
     */
    public boolean isVideoConfirmationEnabled() {
        return mIsVideoConfirmationEnabled;
    }

    void showImageConfirmation(File file) {
        showImageConfirmation(file, Collections.singletonList(file));
    }

    /**
     * Shows or hands over a finished picture.
     *
     * @param file the file the picture is reported as
     * @param files every file the picture wrote, such as the JPEG and the DNG of
     *     {@link PictureFormat#RAW_JPEG}. They are filed in this order after {@code file}, and
     *     rejecting the picture deletes them all.
     */
    @UiThread
    void showImageConfirmation(File file, List<File> files) {
        Log.d(TAG, "Saved the picture to " + file);
        List<File> written = reportedFirst(file, files);
        if (isImageConfirmationEnabled()) {
            mCameraModule.pause();
            mImagePreview.setVisibility(View.VISIBLE);
            Image.with(getContext()).load(file).into(mImagePreview);
            mImagePendingConfirmation = file;
            mImagePendingFiles = written;

            if (getOnImageCapturedListener() != null) {
                getOnImageCapturedListener().onImageConfirmation();
            }
        } else {
            deliverPicture(file, written);
        }
    }

    /** {@code files} with {@code file} at the front, once, so it is filed and reported first. */
    private static List<File> reportedFirst(File file, List<File> files) {
        List<File> ordered = new ArrayList<>(files.size() + 1);
        ordered.add(file);
        for (File each : files) {
            if (!each.equals(file)) {
                ordered.add(each);
            }
        }
        return Collections.unmodifiableList(ordered);
    }

    /** The files saved with a pending picture, or the picture alone for state that has none. */
    private static List<File> filesOf(@Nullable String[] paths, File file) {
        if (paths == null) {
            return Collections.singletonList(file);
        }
        List<File> files = new ArrayList<>(paths.length);
        for (String path : paths) {
            files.add(new File(path));
        }
        return files;
    }

    /**
     * Hands over a finished picture: filed and reported by uri if it is ours, as a file if not.
     * A picture given a file is reported by that file alone, since the caller named it and the
     * others are documented as lying beside it.
     */
    private void deliverPicture(File file, List<File> files) {
        if (isOwnCapture(file)) {
            fileCapture(files, false /* isVideo */);
        } else if (getOnImageCapturedListener() != null) {
            getOnImageCapturedListener().onImageCaptured(file);
        }
    }

    void onShutter() {
        runOnMainThread(() -> {
            if (getOnImageCapturedListener() != null) {
                getOnImageCapturedListener().onShutter();
            }
        });
    }

    void onImageFailed() {
        Log.d(TAG, "Failed to take a picture");
        if (isImageConfirmationEnabled()) {
            mCameraModule.resume();
        }

        if (getOnImageCapturedListener() != null) {
            getOnImageCapturedListener().onFailure();
        }
    }

    void showVideoConfirmation(File file) {
        Log.d(TAG, "Saved the video to " + file);
        if (isVideoConfirmationEnabled()) {
            mCameraModule.pause();

            mVideoPreview.setVisibility(View.VISIBLE);
            // Mirrored for display only where the file is not, so the confirmation looks like
            // the preview either way.
            mVideoPreview.setShouldMirror(
                    isUsingFrontFacingCamera() && !mCameraModule.isFrontVideoMirrored());
            mVideoPreview.setShouldLoop(true);
            mVideoPreview.setVolume(0.3f);
            mVideoPreview.setFile(file);
            if (!mVideoPreview.play()) {
                Log.w(TAG, "Failed to play video preview");
            }

            mVideoPendingConfirmation = file;

            if (getOnVideoCapturedListener() != null) {
                getOnVideoCapturedListener().onVideoConfirmation();
            }
        } else {
            deliverVideo(file);
        }
    }

    /** Hands over a finished video: filed and reported by uri if it is ours, as a file if not. */
    private void deliverVideo(File file) {
        if (isOwnCapture(file)) {
            fileCapture(Collections.singletonList(file), true /* isVideo */);
        } else if (getOnVideoCapturedListener() != null) {
            getOnVideoCapturedListener().onVideoCaptured(file);
        }
    }

    /** Whether a capture was written by {@link #takePicture()} or {@link #startRecording()}. */
    private boolean isOwnCapture(File file) {
        return captureDirectory(getContext()).equals(file.getParentFile());
    }

    /**
     * Files the files of a capture of ours where the gallery looks and reports where each went,
     * in order, once all of them are filed, or that they could not be. A capture that loses any
     * of its files is filed nowhere. The cache copies go either way, since the caller never had
     * them.
     */
    private void fileCapture(List<File> captures, boolean isVideo) {
        MediaStoreSaver.save(getContext(), captures, isVideo, uris -> {
            if (isVideo) {
                OnVideoCapturedListener listener = getOnVideoCapturedListener();
                if (listener == null) {
                    return;
                }
                if (uris == null) {
                    listener.onFailure();
                } else {
                    for (Uri uri : uris) {
                        listener.onVideoSaved(uri);
                    }
                }
            } else {
                OnImageCapturedListener listener = getOnImageCapturedListener();
                if (listener == null) {
                    return;
                }
                if (uris == null) {
                    listener.onFailure();
                } else {
                    for (Uri uri : uris) {
                        listener.onImageSaved(uri);
                    }
                }
            }
        });
    }

    /** Where {@link #newCaptureFile} puts captures. */
    @VisibleForTesting
    static File captureDirectory(Context context) {
        return new File(context.getCacheDir(), CAPTURE_DIRECTORY);
    }

    /**
     * A cache file for a capture that has no destination of its own, named for the moment.
     *
     * @param extension what the capture is written as, such as {@code ".jpg"}, which MediaStore
     *     checks the filed type against
     */
    @VisibleForTesting
    static File newCaptureFile(Context context, String extension) {
        File directory = captureDirectory(context);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            Log.w(TAG, "Could not create " + directory);
        }
        String name = new SimpleDateFormat(CAPTURE_NAME, Locale.US).format(new Date());
        return new File(directory, name + extension);
    }

    /**
     * What a picture without a destination is first written as. RAW writes only a DNG, so its
     * cache file is named as one. RAW_JPEG writes its DNG beside the JPEG under the same name.
     */
    @VisibleForTesting
    static String pictureExtension(PictureFormat format) {
        return format == PictureFormat.RAW ? ".dng" : ".jpg";
    }

    void onVideoFailed() {
        Log.d(TAG, "Failed to record a video");
        if (isVideoConfirmationEnabled()) {
            mCameraModule.resume();
        }

        if (getOnVideoCapturedListener() != null) {
            getOnVideoCapturedListener().onFailure();
        }
    }

    /**
     * Attempts to match image and video outputs to the preview aspect ratio.
     */
    @TargetApi(21)
    public void setMatchPreviewAspectRatio(boolean enabled) {
        mCameraModule.setMatchPreviewAspectRatio(enabled);
    }

    /**
     * When enabled, image and video outputs will attempt to match the same aspect ratio used in
     * the preview. It's advised that this is enabled when using
     * {@link #setImageConfirmationEnabled(boolean)} and
     * {@link #setVideoConfirmationEnabled(boolean)} as it provides the least amount of jank when
     * showing the preview. Having this enabled also ensures that what the user sees is what the
     * picture/video output.
     */
    public boolean isMatchPreviewAspectRatioEnabled() {
        return mCameraModule.isMatchPreviewAspectRatioEnabled();
    }

    /**
     * Sets the quality for image and video outputs.
     */
    public void setQuality(Quality quality) {
        mCameraModule.setQuality(quality);
    }

    /**
     * How hard a still capture is compressed, from 1 to 100, 95 by default.
     *
     * <p>Without this every device picks its own number, which is not the same on any two phones.
     * </p>
     */
    public void setPictureQuality(int percent) {
        mCameraModule.setPictureQuality(percent);
    }

    public int getPictureQuality() {
        return mCameraModule.getPictureQuality();
    }

    /**
     * Gets the current quality for image and video outputs.
     */
    public Quality getQuality() {
        return mCameraModule.getQuality();
    }

    /**
     * Sets the maximum video duration before {@link OnVideoCapturedListener#onVideoCaptured(File)}
     * is called automatically. Use {@link #INDEFINITE_VIDEO_DURATION} to disable the timeout.
     */
    public void setMaxVideoDuration(long duration) {
        mCameraModule.setMaxVideoDuration(duration);
    }

    /**
     * Returns the maximum duration of videos, or {@link #INDEFINITE_VIDEO_DURATION} if there is
     * no timeout.
     */
    public long getMaxVideoDuration() {
        return mCameraModule.getMaxVideoDuration();
    }

    /**
     * Sets the maximum video size in bytes before
     * {@link OnVideoCapturedListener#onVideoCaptured(File)} is called automatically. Use
     * {@link #INDEFINITE_VIDEO_SIZE} to disable the size restriction.
     */
    public void setMaxVideoSize(long size) {
        mCameraModule.setMaxVideoSize(size);
    }

    /**
     * Returns the maximum size of videos in bytes, or {@link #INDEFINITE_VIDEO_SIZE} if there is
     * no timeout.
     */
    public long getMaxVideoSize() {
        return mCameraModule.getMaxVideoSize();
    }

    /** Read by {@link ICameraModule#getLocation}, for whichever backend is tagging a capture. */
    @NonNull
    LocationTracker getLocationTracker() {
        return mLocationTracker;
    }

    protected void onOpen() {
        mCameraModule.loadSounds();
        mCameraModule.open();
        // After the camera, so that one that throws on opening leaves nothing listening.
        mLocationTracker.start();
    }

    /**
     * Picks up a location permission granted while the camera is open. A permission dialog takes
     * the window's focus and gives it back once answered, as returning from Settings does.
     */
    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus) {
            mLocationTracker.refresh();
        }
    }

    protected void onClose() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            exitBarcodeScanner();
            stopFrameAnalysis();
        }
        mLocationTracker.stop();
        finishZoomAnimation();
        mCameraModule.close();
        mCameraModule.releaseSounds();

        if (!mHasSavedState) {
            // Destroy the TextureView we used for the previous round of camera activity. This is
            // because the TextureView will continue to show a bitmap of the old view until the camera
            // is able to draw to it again. We'd rather clear the TextureView, but since there's no such
            // way, we destroy it instead.
            removeView(mCameraView);
            addView(mCameraView = new TextureView(getContext()), 0 /* view position */);
            mCameraView.setSurfaceTextureListener(mSurfaceTextureListener);
        }

        if (mOnCameraStateChangedListener != null) {
            mOnCameraStateChangedListener.onCameraClosed();
        }
    }

    /**
     * Takes a picture and files it where the gallery looks, under Pictures, then calls
     * {@link OnImageCapturedListener#onImageSaved(Uri)} with where it went. Only this overload
     * files into MediaStore; {@link #takePicture(File)} leaves the picture where it was written.
     *
     * <p>{@link PictureFormat#RAW} is filed as a DNG. {@link PictureFormat#RAW_JPEG} files both
     * the JPEG and the DNG under the same name, and calls
     * {@link OnImageCapturedListener#onImageSaved(Uri)} for each.</p>
     *
     * <p>Below API 29 that means writing to shared storage, which needs
     * {@link Manifest.permission#WRITE_EXTERNAL_STORAGE}. Without it the picture cannot be filed,
     * and {@link OnImageCapturedListener#onFailure()} is called instead.</p>
     */
    public void takePicture() {
        takePicture(newCaptureFile(getContext(), pictureExtension(getPictureFormat())));
    }

    /**
     * Takes a picture and calls {@link OnImageCapturedListener#onImageCaptured(File)} when done.
     * @param file The destination.
     */
    public void takePicture(File file) {
        Log.v(TAG, "Taking a picture");
        // Freezing the preview to show the picture is right for a still, and wrong in the middle
        // of a recording: the recording would freeze with it.
        if (isImageConfirmationEnabled() && !isRecording()) {
            mCameraModule.pause();
        }

        mCameraModule.takePicture(file);
    }

    /**
     * Whether a picture is waiting to be confirmed or rejected.
     *
     * <p>Worth asking before either: a confirmation can be cleared from under the UI showing it -
     * by the camera closing, or by state being restored - and {@link #confirmPicture()} and
     * {@link #rejectPicture()} both throw when there is nothing pending, which from a button's
     * click handler is a crash.</p>
     */
    public boolean isImageConfirmationPending() {
        return mImagePendingConfirmation != null;
    }

    /** Whether a video is waiting to be confirmed or rejected. */
    public boolean isVideoConfirmationPending() {
        return mVideoPendingConfirmation != null;
    }

    /**
     * Confirms a picture that is currently being displayed on the preview.
     */
    public void confirmPicture() {
        if (mImagePendingConfirmation == null) {
            throw new IllegalStateException("confirmPicture() called, but no picture was awaiting confirmation");
        }
        if (isImageConfirmationEnabled()) {
            mCameraModule.resume();
        }

        Image.clear(mImagePreview);
        mImagePreview.setVisibility(View.GONE);
        deliverPicture(mImagePendingConfirmation, mImagePendingFiles);
        mImagePendingConfirmation = null;
        mImagePendingFiles = Collections.emptyList();
    }

    /**
     * Rejects a picture that is currently being displayed on the preview, and deletes every file
     * it wrote.
     */
    public void rejectPicture() {
        if (mImagePendingConfirmation == null) {
            throw new IllegalStateException("rejectPicture() called, but no picture was awaiting confirmation");
        }
        if (isImageConfirmationEnabled()) {
            mCameraModule.resume();
        }

        Image.clear(mImagePreview);
        mImagePreview.setVisibility(View.GONE);
        for (File file : mImagePendingFiles) {
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Attempted to clean up pending image file " + file + ", but failed");
            }
        }
        mImagePendingConfirmation = null;
        mImagePendingFiles = Collections.emptyList();
    }

    /**
     * Takes a video and files it where the gallery looks, under Movies, then calls
     * {@link OnVideoCapturedListener#onVideoSaved(Uri)} with where it went. Only this overload
     * files into MediaStore; {@link #startRecording(File)} leaves the video where it was written.
     *
     * <p>Records audio alongside the video, so this is where the microphone is needed rather
     * than at {@link #open()}. Below API 29, filing means writing to shared storage, which needs
     * {@link Manifest.permission#WRITE_EXTERNAL_STORAGE}. Without it the video cannot be filed,
     * and {@link OnVideoCapturedListener#onFailure()} is called instead.</p>
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    public void startRecording() {
        startRecording(newCaptureFile(getContext(), ".mp4"));
    }

    /**
     * Takes a video and calls {@link OnVideoCapturedListener#onVideoCaptured(File)} when done.
     *
     * <p>Records audio alongside the video, so this is where the microphone is needed rather
     * than at {@link #open()}.</p>
     *
     * @param file The destination.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    public void startRecording(File file) {
        Log.v(TAG, "Recording a video");
        mCameraModule.startRecording(file);
    }

    /**
     * Stops an in progress video.
     */
    public void stopRecording() {
        Log.v(TAG, "Stopped recording a video");
        if (isVideoConfirmationEnabled()) {
            mCameraModule.pause();
        }

        mCameraModule.stopRecording();
    }

    /**
     * @return True if currently recording.
     */
    public boolean isRecording() {
        return mCameraModule.isRecording();
    }

    /**
     * Confirms a video that is currently being displayed on the preview.
     */
    public void confirmVideo() {
        if (mVideoPendingConfirmation == null) {
            throw new IllegalStateException("confirmVideo() called, but no video was awaiting confirmation");
        }
        if (isVideoConfirmationEnabled()) {
            mCameraModule.resume();
        }

        mVideoPreview.pause();
        mVideoPreview.setVisibility(View.GONE);
        deliverVideo(mVideoPendingConfirmation);
        mVideoPendingConfirmation = null;
    }

    /**
     * Rejects a video that is currently being displayed on the preview.
     */
    public void rejectVideo() {
        if (mVideoPendingConfirmation == null) {
            throw new IllegalStateException("rejectVideo() called, but no video was awaiting confirmation");
        }
        if (isVideoConfirmationEnabled()) {
            mCameraModule.resume();
        }

        mVideoPreview.pause();
        mVideoPreview.setVisibility(View.GONE);
        if (!mVideoPendingConfirmation.delete()) {
            Log.w(TAG, "Attempted to clean up pending video file, but failed");
        }
        mVideoPendingConfirmation = null;
    }

    /**
     * Starts a video stream. The stream will continue until {@link VideoStream#close} is called.
     *
     * @return A stream that can be shared to a remote device.
     */
    @RequiresPermission(allOf = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
    })
    @RequiresApi(18)
    public VideoStream stream() {
        return stream(new VideoStream.Params.Builder().build());
    }

    /**
     * Starts a video stream. The stream will continue until {@link VideoStream#close} is called.
     *
     * @return A stream that can be shared to a remote device.
     */
    @RequiresPermission(allOf = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
    })
    @RequiresApi(18)
    public VideoStream stream(VideoStream.Params params) {
        if (params.isVideoEnabled() && !isOpen()) {
            throw new IllegalStateException("Camera must be open before starting a video stream");
        }

        return new VideoStream.Builder().attach(mCameraModule).setParams(params).build();
    }

    public interface BarcodeDetectorListener {
        void onBarcodeFound(List<Barcode> barcodes);
    }

    /**
     * Starts reporting the barcodes the camera can see.
     *
     * @return whether scanning started. False when ML Kit's barcode model is not available on
     *     this device.
     */
    @RequiresApi(19)
    @RequiresPermission(Manifest.permission.CAMERA)
    public boolean enterBarcodeScanner(BarcodeDetectorListener listener, @Barcode.Format int format, @Barcode.Format int... formats) {
        if (!isOpen()) {
            throw new IllegalStateException("Camera must be open before scanning for barcodes");
        }

        if (mBarcodeScanner != null || mUsingModuleBarcodeScanner) {
            throw new IllegalStateException("Barcode scanner cannot be started twice");
        }

        mUsingModuleBarcodeScanner = mCameraModule.startBarcodeScanner(listener, format, formats);
        if (mUsingModuleBarcodeScanner) {
            return true;
        }
        try {
            mBarcodeScanner = new BarcodeScanner(
                    mCameraModule.getCanvas(), this, listener, format, formats);
        } catch (RuntimeException | LinkageError error) {
            // Building the client is what reaches for the model, and it throws rather than
            // returning anything when ML Kit was never initialised.
            Log.w(TAG, "Barcode scanning is unavailable on this device", error);
            mBarcodeScanner = null;
            return false;
        }
        mBarcodeScanner.start();
        return true;
    }

    @RequiresApi(19)
    public void exitBarcodeScanner() {
        if (mBarcodeScanner != null) {
            mBarcodeScanner.stop();
            mBarcodeScanner = null;
        }
        if (mUsingModuleBarcodeScanner) {
            mCameraModule.stopBarcodeScanner();
            mUsingModuleBarcodeScanner = false;
        }
    }

    /**
     * Hands every camera frame to an analyzer, for detection the library does not do itself.
     *
     * <p>The analyzer is called on a background thread with the most recent frame, so one that
     * takes longer than a frame interval drops frames instead of falling behind. Each frame says
     * how to turn it upright and where its pixels land on this view, which is what an overlay
     * drawn over the preview needs.</p>
     *
     * <p>This runs alongside the preview and recording, and stays attached across a lens switch.
     * Call {@link #stopFrameAnalysis()} to let the camera give the buffers back.</p>
     *
     * @return whether analysis started. False on a backend that cannot produce frames to read.
     * @throws IllegalStateException if the camera is not open, or analysis is already running
     */
    @RequiresApi(19)
    @RequiresPermission(Manifest.permission.CAMERA)
    public boolean startFrameAnalysis(@NonNull FrameStream.Analyzer analyzer) {
        return startFrameAnalysis(analyzer, new FrameStream.Params());
    }

    /**
     * {@link #startFrameAnalysis(FrameStream.Analyzer)} with a say over the frames themselves.
     *
     * @see FrameStream.Params#setMaxSize
     */
    @RequiresApi(19)
    @RequiresPermission(Manifest.permission.CAMERA)
    public boolean startFrameAnalysis(
            @NonNull FrameStream.Analyzer analyzer, @NonNull FrameStream.Params params) {
        if (!isOpen()) {
            throw new IllegalStateException("Camera must be open before analyzing frames");
        }
        if (mFrameStream != null) {
            throw new IllegalStateException("Frame analysis cannot be started twice");
        }
        if (!mCameraModule.isFrameAnalysisSupported()) {
            Log.w(TAG, "The " + getBackend() + " backend cannot analyze frames");
            return false;
        }
        mFrameStream = new FrameStream(mCameraModule.getCanvas(), this, params, analyzer);
        mFrameStream.start();
        return true;
    }

    /**
     * The horizontal angle of view, in degrees, that the camera can currently see.
     *
     * <p>This is what turns a direction in the world into a position on screen, so anything drawn
     * over the preview to point at something - a marker on a landmark, an arrow towards a place -
     * needs it. A bearing that is half the angle of view away from where the camera is pointed sits
     * at the edge of the preview; one at a quarter sits halfway out.</p>
     *
     * <p>It narrows as the camera zooms in, so read it again after changing the zoom. Use it with
     * {@link #getRelativeCameraOrientation} to know which way the frame is turned.</p>
     *
     * @return the angle in degrees, or 0 if the camera is closed or does not publish its optics
     */
    public float getHorizontalFieldOfView() {
        return mCameraModule.getHorizontalFieldOfView();
    }

    /** The vertical angle of view, on the same terms as {@link #getHorizontalFieldOfView}. */
    public float getVerticalFieldOfView() {
        return mCameraModule.getVerticalFieldOfView();
    }

    /** Stops the analyzer started by {@link #startFrameAnalysis}. */
    @RequiresApi(19)
    public void stopFrameAnalysis() {
        if (mFrameStream != null) {
            mFrameStream.close();
            mFrameStream = null;
        }
    }

    /** Whether an analyzer is receiving frames. */
    @RequiresApi(19)
    public boolean isAnalyzingFrames() {
        return mFrameStream != null && mFrameStream.isAnalyzing();
    }

    /** Receives the faces the camera itself is tracking. */
    public interface FaceDetectionListener {
        /**
         * Called on the main thread whenever the camera reports its faces, which is roughly once
         * per preview frame. An empty list means it has stopped seeing any.
         *
         * @param faces the faces, in the camera's own coordinates
         * @param faceToView maps a face's bounds onto this view, for drawing over the preview
         */
        void onFacesDetected(@NonNull List<Face> faces, @NonNull Matrix faceToView);
    }

    /**
     * Whether this camera can record in a given colour range.
     *
     * <p>Worth asking before offering the choice: wide gamut needs API 34 and a camera that
     * reports the profile, and a picture saved in a colour space the viewer ignores looks
     * oversaturated rather than better.</p>
     */
    public boolean isColorSpaceSupported(ColorSpace colorSpace) {
        return mCameraModule.isColorSpaceSupported(colorSpace);
    }

    /**
     * The colour range pictures are recorded in, sRGB by default.
     *
     * <p>Takes effect the next time the camera configures its session, so set it before opening
     * or expect a brief reconfiguration.</p>
     */
    public void setColorSpace(ColorSpace colorSpace) {
        mCameraModule.setColorSpace(colorSpace);
    }

    public ColorSpace getColorSpace() {
        return mCameraModule.getColorSpace();
    }

    /** Whether this camera can write pictures in a given format. */
    public boolean isPictureFormatSupported(PictureFormat format) {
        return mCameraModule.isPictureFormatSupported(format);
    }

    /**
     * The format pictures are written in, JPEG by default. A format this camera cannot write is
     * logged and dropped.
     */
    public void setPictureFormat(PictureFormat format) {
        mCameraModule.setPictureFormat(format);
    }

    public PictureFormat getPictureFormat() {
        return mCameraModule.getPictureFormat();
    }

    /**
     * Whether a picture can be taken while a recording is running.
     *
     * <p>The video-snapshot button every camera app has. Not every camera can hold a preview, a
     * recording and a still at once, so this is worth asking before showing the control: where it
     * is false, {@link #takePicture} during a recording reports a failure and the recording
     * continues.</p>
     */
    public boolean isSnapshotWhileRecordingSupported() {
        return mCameraModule.isSnapshotWhileRecordingSupported();
    }

    /**
     * Whether this camera can report the faces it is tracking.
     *
     * <p>Almost every camera can: it needs faces to decide what to focus and expose for, so the
     * work is already being done in hardware. A camera that cannot is usually an external or
     * virtual one.</p>
     */
    public boolean isFaceDetectionSupported() {
        return mCameraModule.isFaceDetectionSupported();
    }

    /**
     * Reports the faces the camera is tracking, or stops when given null.
     *
     * <p>This is the camera's own detector, the one the manufacturer's app draws boxes from. It is
     * free, it arrives with the preview, and it finds faces rather than describing them - for
     * anything beyond where a face is, run a detector over
     * {@link #startFrameAnalysis(FrameStream.Analyzer)} instead.</p>
     *
     * @return whether the camera took it on. False means this camera cannot report faces, and the
     *     listener will never be called.
     */
    public boolean setFaceDetectionListener(@Nullable FaceDetectionListener listener) {
        mFaceDetectionListener = listener;
        boolean enabled = mCameraModule.setFaceDetectionEnabled(listener != null);
        if (!enabled) {
            mFaceDetectionListener = null;
        }
        return enabled;
    }

    /** Called by the active backend, on the main thread. */
    void onFacesDetected(@NonNull List<Face> faces, @NonNull Matrix faceToView) {
        FaceDetectionListener listener = mFaceDetectionListener;
        if (listener != null) {
            listener.onFacesDetected(faces, faceToView);
        }
    }

    /**
     * @return True if the device supports a front facing camera.
     */
    public boolean hasFrontFacingCamera() {
        return mCameraModule.hasFrontFacingCamera();
    }

    /**
     * @return True if CameraView is currently using a front facing camera.
     */
    public boolean isUsingFrontFacingCamera() {
        return mCameraModule.isUsingFrontFacingCamera();
    }

    /**
     * Toggles the active camera to the next available camera. Typically, this toggles between
     * the front and back facing cameras.
     */
    public void toggleCamera() {
        cancelZoomAnimation();
        mCameraModule.toggleCamera();
    }

    /**
     * Sets which camera (front or back) to use.
     */
    public void setLensFacing(LensFacing lensFacing) {
        if (getLensFacing().equals(lensFacing)) {
            return;
        }

        cancelZoomAnimation();
        mCameraModule.setLensFacing(lensFacing);
    }

    /**
     * Returns which camera (front or back) is being used.
     */
    public LensFacing getLensFacing() {
        return mCameraModule.isUsingFrontFacingCamera() ? LensFacing.FRONT : LensFacing.BACK;
    }

    /**
     * Focuses the camera on the given area. Limited from -1000 to 1000.
     */
    public void focus(Rect focus, Rect metering) {
        mCameraModule.focus(focus, metering);
    }

    /**
     * Whether this camera can be told what to focus on.
     *
     * <p>Worth asking before offering a tap-to-focus affordance: a fixed-focus camera, which is
     * most front cameras and every external one, accepts no focus area, and
     * {@link #focus(Rect, Rect)} on one does nothing rather than failing.</p>
     */
    public boolean isFocusSupported() {
        return mCameraModule.isFocusSupported();
    }

    /**
     * Follows a focus request from the moment the camera takes it to the moment it answers.
     *
     * <p>This library draws nothing over the preview, so an app that wants the reticle a camera
     * app shows draws its own. What it needs from here is when a focus actually started, where it
     * was aimed, and what came of it - the last of those because a reticle that confirms a lock
     * the camera never reported is telling the user something untrue.</p>
     *
     * @see #setFocusListener
     */
    public interface FocusListener {
        /**
         * Called on the main thread once the camera has taken a focus request.
         *
         * <p>Not called for a request a camera declined, so there is nothing to draw for a camera
         * that cannot focus.</p>
         *
         * @param area where the camera is focusing, in this view's coordinates
         */
        void onFocusRequested(@NonNull RectF area);

        /**
         * Called on the main thread with what became of the matching request, exactly once.
         *
         * @param area the area from the {@link #onFocusRequested} this answers
         * @param locked whether the camera reported that it converged. False covers a camera that
         *     finished without a lock and one that never answered at all.
         */
        void onFocusCompleted(@NonNull RectF area, boolean locked);
    }

    /**
     * Reports focus requests and their outcomes, or stops when given null.
     *
     * <p>Both a tap on the preview and a {@link #focus(Rect, Rect)} of your own are reported, so
     * an affordance driven from here follows the camera rather than the touch.</p>
     */
    public void setFocusListener(@Nullable FocusListener listener) {
        mFocusListener = listener;
    }

    /** Called by the active backend, on the main thread. */
    @UiThread
    void onFocusRequested(@NonNull RectF area) {
        // One focus at a time: a second request supersedes the first rather than queueing behind
        // it, because that is what the camera itself does with the region.
        mHandler.removeCallbacks(mFocusTimeoutRunnable);
        mPendingFocusArea = area;
        mHandler.postDelayed(mFocusTimeoutRunnable, FOCUS_TIMEOUT_MILLIS);
        if (mFocusReticleEnabled && mFocusReticle != null) {
            mFocusReticle.showAt(area);
        }
        if (mFocusListener != null) {
            mFocusListener.onFocusRequested(new RectF(area));
        }
    }

    /** Called by the active backend, on the main thread. */
    @UiThread
    void onFocusCompleted(boolean locked) {
        RectF area = mPendingFocusArea;
        if (area == null) {
            // A camera reports what it is doing continuously, whether or not anything asked it
            // to, and only the answer to a request this view announced is one to pass on.
            return;
        }
        mPendingFocusArea = null;
        mHandler.removeCallbacks(mFocusTimeoutRunnable);
        if (mFocusReticleEnabled && mFocusReticle != null) {
            mFocusReticle.settle(locked);
        }
        if (mFocusListener != null) {
            mFocusListener.onFocusCompleted(area, locked);
        }
    }

    /**
     * Whether to draw a reticle where a focus request lands. On by default; turn it off to draw
     * your own from a {@link FocusListener}.
     */
    public void setFocusReticleEnabled(boolean enabled) {
        mFocusReticleEnabled = enabled;
        if (!enabled && mFocusReticle != null) {
            mFocusReticle.settle(false);
        }
    }

    public boolean isFocusReticleEnabled() {
        return mFocusReticleEnabled;
    }

    /**
     * Plays the platform's camera sounds: a click as a picture is taken, and a tone as a
     * recording starts and stops. On by default, as in the platform's own camera app.
     *
     * <p>Some countries require these sounds, and there they play whatever this is set to.</p>
     */
    public void setShutterSoundEnabled(boolean enabled) {
        mCameraModule.setShutterSoundEnabled(enabled);
    }

    /** Returns whether the camera's sounds are wanted. */
    public boolean isShutterSoundEnabled() {
        return mCameraModule.isShutterSoundEnabled();
    }

    /**
     * Plays a sound of the app's own in place of the platform's shutter click, or the click
     * again for 0. Recordings keep the platform's tones.
     *
     * <p>Where a shutter sound is required this one counts, and plays just as the click would.
     * </p>
     */
    public void setShutterSound(@RawRes int soundRes) {
        mCameraModule.setShutterSound(soundRes);
    }

    /** Returns the app's own shutter sound, or 0 for the platform's click. */
    @RawRes
    public int getShutterSound() {
        return mCameraModule.getShutterSound();
    }

    /**
     * Sets the active flash strategy.
     */
    public void setFlash(Flash flashMode) {
        mCameraModule.setFlash(flashMode);
    }

    /**
     * Gets the active flash strategy.
     */
    public Flash getFlash() {
        return mCameraModule.getFlash();
    }

    /**
     * @return True if the camera supports flash.
     */
    public boolean hasFlash() {
        return mCameraModule.hasFlash();
    }

    /** Sets the requested video dynamic range. */
    public void setDynamicRange(DynamicRange dynamicRange) {
        mCameraModule.setDynamicRange(dynamicRange);
    }

    /** Returns the requested video dynamic range. */
    public DynamicRange getDynamicRange() {
        return mCameraModule.getDynamicRange();
    }

    /** Returns whether the active backend and lens support the requested dynamic range. */
    public boolean isDynamicRangeSupported(DynamicRange dynamicRange) {
        return mCameraModule.isDynamicRangeSupported(dynamicRange);
    }

    /** Sets the optional CameraX photo-processing extension. */
    public void setExtension(Extension extension) {
        mCameraModule.setExtension(extension);
    }

    /** Returns the requested CameraX photo-processing extension. */
    public Extension getExtension() {
        return mCameraModule.getExtension();
    }

    /** Returns whether the active backend and lens support the extension. */
    public boolean isExtensionSupported(Extension extension) {
        return mCameraModule.isExtensionSupported(extension);
    }

    /**
     * Turns stabilization on or off: the camera steadies the picture against hand shake, in the
     * preview and in recordings, as far as it can.
     *
     * <p>One switch rather than two. A camera may be able to steady one and not the other, and
     * each is then steadied where it can be; which of the two a given camera manages is not
     * something a caller can act on. Steadying digitally can narrow the view slightly.</p>
     *
     * <p>It takes a camera that can steady at least one of the two
     * ({@link #isStabilizationSupported()}). Turning it on while a camera that cannot is open
     * does nothing. A request made before the camera opens is kept, and so is one across a lens
     * switch: it applies on whichever camera can steady.</p>
     *
     * <p>Changing it reconfigures the camera, which can interrupt the preview for a moment. During
     * a recording the change waits: the recording keeps the setting it started with, and the
     * change applies once the recording ends.</p>
     */
    public void setStabilizationEnabled(boolean enabled) {
        mCameraModule.setStabilizationEnabled(enabled);
    }

    /**
     * Returns whether stabilization is turned on. It reports the request, so it can be true on a
     * camera that cannot steady: one switched to after it was turned on, or one opened after it
     * was asked for. See {@link #setStabilizationEnabled}.
     */
    public boolean isStabilizationEnabled() {
        return mCameraModule.isStabilizationEnabled();
    }

    /**
     * Returns whether the active camera can steady either the preview or a recording. Cameras on
     * one device can differ, so read it once the camera has opened, and again after a lens switch.
     */
    public boolean isStabilizationSupported() {
        return mCameraModule.isStabilizationSupported();
    }

    /**
     * Shifts the camera's automatic exposure brighter or darker than it would choose by itself.
     *
     * <p>This biases auto-exposure rather than replacing it: the camera keeps metering the scene
     * and setting its own exposure time and sensitivity, then aims above (positive) or below
     * (negative) the exposure it metered. The shift applies to the preview, to pictures and to
     * recordings alike.</p>
     *
     * <p>The value is an index, not a number of stops. 0 is no compensation, and each step is
     * worth {@link #getExposureCompensationStep()} EV, so the shift in EV is the index times the
     * step. Cameras typically step in 1/3 or 1/6 EV and reach about 2 to 4 EV either way: with a
     * step of 1/6, index 6 is +1 EV and index -3 is -0.5 EV.</p>
     *
     * <p>The camera accepts indexes from {@link #getMinExposureCompensation()} to
     * {@link #getMaxExposureCompensation()} inclusive, and clamps one outside that range to the
     * nearer end. Where {@link #isExposureCompensationSupported()} is false, both ends are 0 and
     * the call does nothing while that camera is open.</p>
     *
     * <p>The range and the step belong to the active camera, so read them once it has opened. An
     * index set before then is kept as given, and clamped to the range of the camera that opens.
     * A lens switch keeps the index, clamped to the new camera's range, and switching back does
     * not undo that clamp. Cameras can step differently, so the same index can be a different
     * number of EV on the new lens. A camera that cannot compensate leaves the index unapplied,
     * and the next camera that can applies it.</p>
     *
     * <p>A new index takes effect within a frame or two, during a recording as well: the recording
     * carries on, and its exposure changes from that point.</p>
     *
     * @param exposureCompensation the index, where 0 is the exposure the camera would choose by
     *     itself
     */
    public void setExposureCompensation(int exposureCompensation) {
        mCameraModule.setExposureCompensation(exposureCompensation);
    }

    /**
     * Returns the exposure compensation index the camera is using, or with no camera open, the one
     * it will apply, held to its range, once it opens. 0 is no compensation.
     *
     * <p>A camera can take a frame or two to accept a new index, and this reports the previous
     * one until it has. On a camera that cannot compensate, it is the index kept for the next
     * camera that can. Multiply it by {@link #getExposureCompensationStep()} for the shift in EV.
     * See {@link #setExposureCompensation}.</p>
     */
    public int getExposureCompensation() {
        return mCameraModule.getExposureCompensation();
    }

    /**
     * Returns the lowest exposure compensation index the active camera accepts, the one that
     * darkens the picture the most; times {@link #getExposureCompensationStep()}, it is the
     * furthest in EV the exposure can go below what the camera would choose. 0 where
     * {@link #isExposureCompensationSupported()} is false.
     *
     * <p>Read it once the camera has opened, and again after a lens switch. See
     * {@link #setExposureCompensation}.</p>
     */
    public int getMinExposureCompensation() {
        return mCameraModule.getMinExposureCompensation();
    }

    /**
     * Returns the highest exposure compensation index the active camera accepts, the one that
     * brightens the picture the most; times {@link #getExposureCompensationStep()}, it is the
     * furthest in EV the exposure can go above what the camera would choose. 0 where
     * {@link #isExposureCompensationSupported()} is false.
     *
     * <p>Read it once the camera has opened, and again after a lens switch. See
     * {@link #setExposureCompensation}.</p>
     */
    public int getMaxExposureCompensation() {
        return mCameraModule.getMaxExposureCompensation();
    }

    /**
     * Returns how many EV (stops of light) one exposure compensation index is worth on the active
     * camera, or 0 where {@link #isExposureCompensationSupported()} is false.
     *
     * <p>Cameras usually step in thirds or sixths of a stop. Multiply an index by the step for the
     * shift it makes in EV: with a step of 1/3, index -2 darkens by 2/3 EV, and with a step of 1/6,
     * index 6 brightens by 1 EV. That is the number to show a user, since an index on its own
     * means something different on each camera.</p>
     *
     * <p>The step belongs to the active camera, so read it once the camera has opened, and again
     * after a lens switch: two cameras on one device can step differently.</p>
     */
    public float getExposureCompensationStep() {
        return mCameraModule.getExposureCompensationStep();
    }

    /**
     * Returns whether the active camera can shift its automatic exposure through
     * {@link #setExposureCompensation}. Where it cannot, the minimum, the maximum and the step are
     * all 0, and an index set while that camera is open does nothing.
     *
     * <p>Cameras on one device can differ, so read it once the camera has opened, and again after
     * a lens switch.</p>
     */
    public boolean isExposureCompensationSupported() {
        return mCameraModule.isExposureCompensationSupported();
    }

    /**
     * Turns the flash on as a steady light, or off.
     *
     * <p>A lit torch stays on until it is turned off: through the preview, while a picture is
     * taken and while recording. Closing the camera puts it out, and the camera lights it again
     * when it reopens. It is separate from {@link #setFlash}, which fires the flash for a
     * picture.</p>
     *
     * <p>It takes a camera with a flash ({@link #hasFlash()}), which front cameras usually lack.
     * Turning it on while a camera without one is open does nothing. A request made before the
     * camera opens is kept, and so is one across a lens switch: the torch stays dark on a camera
     * without a flash and lights on the next one with. It can be turned on and off during a
     * recording.</p>
     *
     * <p>The torch and low-light boost cannot be on together, since each takes over the camera's
     * automatic exposure, so turning the torch on turns low-light boost off.</p>
     */
    public void setTorchEnabled(boolean enabled) {
        mCameraModule.setTorchEnabled(enabled);
    }

    /**
     * Returns whether the torch is turned on. It reports the request, so it can be true while the
     * torch is dark: before the camera opens, or on a lens without a flash that was switched to
     * after it was lit. See {@link #setTorchEnabled}.
     */
    public boolean isTorchEnabled() {
        return mCameraModule.isTorchEnabled();
    }

    /**
     * Turns low-light boost on or off. In a dark scene the camera then brightens the preview and
     * recordings beyond what its usual automatic exposure reaches, for which it may lower the
     * frame rate.
     *
     * <p>It takes a camera that offers it ({@link #isLowLightBoostSupported()}), which many do
     * not. Turning it on while a camera without it is open does nothing. A request made before the
     * camera opens is kept, and so is one across a lens switch: it applies on whichever camera
     * offers it. It can be turned on and off during a recording.</p>
     *
     * <p>Low-light boost and the torch cannot be on together, since each takes over the camera's
     * automatic exposure, so turning low-light boost on turns the torch off.</p>
     */
    public void setLowLightBoostEnabled(boolean enabled) {
        mCameraModule.setLowLightBoostEnabled(enabled);
    }

    /**
     * Returns whether low-light boost is turned on. It reports the request, so it can be true on a
     * camera without it: one switched to after it was turned on, or one opened after it was asked
     * for. See {@link #setLowLightBoostEnabled}.
     */
    public boolean isLowLightBoostEnabled() {
        return mCameraModule.isLowLightBoostEnabled();
    }

    /**
     * Returns whether the active camera offers low-light boost. Cameras on one device can differ,
     * so read it once the camera has opened, and again after a lens switch.
     */
    public boolean isLowLightBoostSupported() {
        return mCameraModule.isLowLightBoostSupported();
    }

    protected int getRelativeCameraOrientation() {
        return mCameraModule.getRelativeCameraOrientation();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed) {
            // The transform is re-aimed on every pass, so the preview keeps showing the scene at
            // the right shape throughout. Reconfiguring the camera waits: a resize arrives as a
            // run of layout passes, and a backend that rebuilds its session for each one pays for
            // a whole camera session per frame of an animation.
            onLayoutChanged();
            mHandler.removeCallbacks(mLayoutSettledRunnable);
            mHandler.postDelayed(mLayoutSettledRunnable, LAYOUT_SETTLE_DELAY_MILLIS);
        }
    }

    /** Re-aims the preview at the bounds of a layout pass. Called for every one that changed. */
    protected void onLayoutChanged() {
        mCameraModule.onLayoutChanged();
    }

    /**
     * Lets the camera reconfigure itself for the size the view settled at.
     *
     * <p>Called once {@link #LAYOUT_SETTLE_DELAY_MILLIS} has passed with no further change, so a
     * resize costs one reconfiguration rather than one per layout pass.</p>
     */
    protected void onLayoutSettled() {
        mCameraModule.onLayoutSettled();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // Nothing to reconfigure for a view that is no longer on screen, and the next layout pass
        // after it comes back will ask again.
        mHandler.removeCallbacks(mLayoutSettledRunnable);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Disable pinch-to-zoom while a preview is visible.
        if (mImagePendingConfirmation != null
                || mVideoPendingConfirmation != null
                || mCameraModule.isPaused()) {
            return super.onTouchEvent(event);
        }

        mScaleDetector.onTouchEvent(event);
        if (event.getPointerCount() == 2 && isPinchToZoomEnabled() && isZoomSupported()) {
            return true;
        }

        // Camera focus
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mInitialMotionEventX = event.getX();
                mInitialMotionEventY = event.getY();
                mIsLongPressMotionEvent = false;
                mHandler.removeCallbacks(mLongPressRunnable);
                mHandler.postDelayed(mLongPressRunnable, mLongPressTimeout);
                break;
            case MotionEvent.ACTION_MOVE:
                if (movedBeyondTouchSlop(event)) {
                    mHandler.removeCallbacks(mLongPressRunnable);
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                mHandler.removeCallbacks(mLongPressRunnable);
                break;
            case MotionEvent.ACTION_UP:
                mHandler.removeCallbacks(mLongPressRunnable);
                if (!movedBeyondTouchSlop(event) && !mIsLongPressMotionEvent) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1
                            && hasOnClickListeners()) {
                        performClick();
                    } else {
                        Rect focusArea = meteringArea(event.getX(), event.getY(), 1f);
                        Rect meteringArea = meteringArea(event.getX(), event.getY(), 1.5f);
                        if (area(focusArea) != 0 && area(meteringArea) != 0) {
                            focus(focusArea, meteringArea);
                        }
                    }
                }
                break;
        }
        return true;
    }

    private boolean movedBeyondTouchSlop(MotionEvent event) {
        float distanceX = event.getX() - mInitialMotionEventX;
        float distanceY = event.getY() - mInitialMotionEventY;
        return Math.hypot(distanceX, distanceY) > mTouchSlop;
    }

    /**
     * Returns the width * height of the given rect
     */
    private int area(Rect rect) {
        return rect.width() * rect.height();
    }

    /** The area a camera should focus or meter on for a touch at this point on the view. */
    private Rect meteringArea(float x, float y, float coefficient) {
        return TransformMath.meteringArea(x, y, getWidth(), getHeight(),
                getRelativeCameraOrientation(), coefficient);
    }

    /**
     * When enabled, the user can pinch the camera to zoom in/out.
     */
    public void setPinchToZoomEnabled(boolean enabled) {
        mIsPinchToZoomEnabled = enabled;
    }

    /**
     * @return True if pinch to zoom is enabled.
     */
    public boolean isPinchToZoomEnabled() {
        return mIsPinchToZoomEnabled;
    }

    /**
     * Zooms to a ratio in the camera's own terms, where 1 is the main lens.
     *
     * <p>Held to {@link #getMinZoomRatio()} and {@link #getMaxZoomRatio()}. On a phone with
     * several lenses the camera switches between them as the ratio crosses from one to the next,
     * so the whole range is one smooth zoom.</p>
     */
    public void setZoomRatio(float zoomRatio) {
        setZoomRatio(zoomRatio, false /* animate */);
    }

    /**
     * Zooms to a ratio, easing over to it when animate is true rather than jumping, as a phone's
     * camera app does on a tap of a zoom button.
     *
     * <p>The animation passes through every ratio in between, on a log scale so that 0.6x to 1x
     * moves like 1x to 3x. A phone with several lenses switches between them along the way, and
     * {@link #setOnZoomChangedListener} hears each step. A pinch, a zoom that is not animated or
     * a lens switch stops it where it is; closing the camera lands it on the ratio it was heading
     * for. Before the camera is open there is nothing to watch, so the zoom is set at once.</p>
     */
    @UiThread
    public void setZoomRatio(float zoomRatio, boolean animate) {
        if (!(zoomRatio > 0f) || Float.isInfinite(zoomRatio)) {
            Log.w(TAG, "Ignoring a zoom ratio of " + zoomRatio);
            return;
        }
        if (!animate || !isOpen() || !isZoomSupported()) {
            cancelZoomAnimation();
            mCameraModule.setZoomRatio(zoomRatio);
            return;
        }

        // Held to the range first, so the animation spends its whole length moving rather than
        // reaching the end of the range early and sitting there.
        float target = ZoomMath.clamp(zoomRatio, getMinZoomRatio(), getMaxZoomRatio());
        if (mZoomAnimator != null && target == mZoomAnimationTarget) {
            // Already on its way there. Starting again would only slow it down.
            return;
        }
        cancelZoomAnimation();
        float from = getZoomRatio();
        if (from == target) {
            return;
        }

        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(ZOOM_ANIMATION_MILLIS);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        // Each step goes to the module rather than to setZoomRatio, which would cancel this
        // animation.
        animator.addUpdateListener(animation -> mCameraModule.setZoomRatio(
                ZoomMath.interpolate(from, target, (Float) animation.getAnimatedValue())));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (mZoomAnimator == animation) {
                    mZoomAnimator = null;
                }
            }
        });
        mZoomAnimator = animator;
        mZoomAnimationTarget = target;
        animator.start();
    }

    /** Stops a running zoom animation wherever it has got to. */
    private void cancelZoomAnimation() {
        ValueAnimator animator = mZoomAnimator;
        if (animator == null) {
            return;
        }
        mZoomAnimator = null;
        animator.cancel();
    }

    /** Stops a running zoom animation at the ratio it was heading for. */
    private void finishZoomAnimation() {
        if (mZoomAnimator == null) {
            return;
        }
        float target = mZoomAnimationTarget;
        cancelZoomAnimation();
        mCameraModule.setZoomRatio(target);
    }

    /** The current zoom. See {@link #setZoomRatio}. */
    public float getZoomRatio() {
        return mCameraModule.getZoomRatio();
    }

    /** The widest the camera can see: below 1 on a phone with an ultra-wide lens, otherwise 1. */
    public float getMinZoomRatio() {
        return mCameraModule.getMinZoomRatio();
    }

    /** The furthest the camera can zoom in, or 1 if it cannot zoom. */
    public float getMaxZoomRatio() {
        return mCameraModule.getMaxZoomRatio();
    }

    /**
     * The zoom stops worth a button each, in increasing order, such as 0.6, 1, 3 and 5. Each has
     * the exact ratio to zoom to and a rounded label to show, so a 2.74x lens is labelled "3".
     *
     * <p>Android does not publish the stops a phone's own camera app offers, so these are worked
     * out from its lenses: always 1, the wide end when it is below 1, one stop per lens, and 2
     * where the main sensor can crop to it without upscaling. They belong to the lens in use, so
     * read them again after switching.</p>
     */
    @NonNull
    public List<ZoomPreset> getZoomPresets() {
        return ZoomPreset.of(mCameraModule.getZoomPresets(), Locale.getDefault());
    }

    /**
     * Zooms to a position from 0 to {@link #getMaxZoomLevel()}, for a slider.
     *
     * <p>The levels span the same range as {@link #setZoomRatio}, from the minimum ratio at 0 to
     * the maximum at the top, on a log scale so that equal travel multiplies the zoom by the same
     * amount.</p>
     */
    public void setZoomLevel(int zoomLevel) {
        cancelZoomAnimation();
        mCameraModule.setZoomLevel(zoomLevel);
    }

    /** The current zoom, as a level. See {@link #setZoomLevel}. */
    public int getZoomLevel() {
        return mCameraModule.getZoomLevel();
    }

    /** The top of the zoom level scale, which is the same on every camera. */
    public int getMaxZoomLevel() {
        return mCameraModule.getMaxZoomLevel();
    }

    /**
     * @return True if the camera supports zooming.
     */
    public boolean isZoomSupported() {
        return mCameraModule.isZoomSupported();
    }

    /**
     * Reports every change of zoom, however it came about: {@link #setZoomRatio}, each step of an
     * animated one, {@link #setZoomLevel}, a pinch, or a lens switch to a camera that cannot reach
     * the old zoom. This is what keeps preset buttons in step with a pinch.
     */
    public void setOnZoomChangedListener(@Nullable OnZoomChangedListener listener) {
        mOnZoomChangedListener = listener;
    }

    /** Passes a zoom change on to the listener, unless it is the ratio last passed on. */
    @UiThread
    void onZoomChanged(float zoomRatio) {
        if (zoomRatio == mLastReportedZoomRatio) {
            return;
        }
        mLastReportedZoomRatio = zoomRatio;
        if (mOnZoomChangedListener != null) {
            mOnZoomChangedListener.onZoomChanged(zoomRatio);
        }
    }

    /**
     * Enables listening for image-related callbacks.
     */
    public void setOnImageCapturedListener(OnImageCapturedListener l) {
        mOnImageCapturedListener = l;
        mCameraModule.setOnImageCapturedListener(l);
    }

    protected OnImageCapturedListener getOnImageCapturedListener() {
        return mOnImageCapturedListener;
    }

    /**
     * Enables listening for video-related callbacks.
     */
    public void setOnVideoCapturedListener(OnVideoCapturedListener l) {
        mOnVideoCapturedListener = l;
        mCameraModule.setOnVideoCapturedListener(l);
    }

    protected OnVideoCapturedListener getOnVideoCapturedListener() {
        return mOnVideoCapturedListener;
    }

    /**
     * Enables listening for callbacks about the camera being opened or closed.
     */
    public void setOnCameraStateChangedListener(OnCameraStateChangedListener l) {
        mOnCameraStateChangedListener = l;
    }

    protected OnCameraStateChangedListener getOnCameraStateChangedListener() {
        return mOnCameraStateChangedListener;
    }

    /**
     * Hears how a picture turned out. One taken with {@link CameraView#takePicture(File)}
     * arrives at {@link #onImageCaptured(File)}, and one taken with
     * {@link CameraView#takePicture()} at {@link #onImageSaved(Uri)}, so an implementation
     * overrides whichever its pictures use.
     */
    public interface OnImageCapturedListener {
        /**
         * Called as the picture is exposed, before it is saved. This is the moment for a shutter
         * animation, or for a sound of the app's own with {@link #setShutterSoundEnabled} off.
         */
        default void onShutter() {}

        /** A picture is showing in the preview, waiting for confirmPicture or rejectPicture. */
        void onImageConfirmation();

        /** A picture from {@link CameraView#takePicture(File)} has been written to that file. */
        default void onImageCaptured(File file) {}

        /**
         * A picture from {@link CameraView#takePicture()} has been filed in the gallery.
         *
         * <p>Called once for each file filed. {@link PictureFormat#RAW_JPEG} files two, and this
         * hears the JPEG first and then the DNG, only once both are filed.</p>
         */
        default void onImageSaved(@NonNull Uri uri) {}

        /**
         * The picture could not be taken, or could not be filed. A picture of two files that
         * loses either is filed as neither.
         */
        void onFailure();
    }

    /**
     * Hears how a video turned out. One recorded with {@link CameraView#startRecording(File)}
     * arrives at {@link #onVideoCaptured(File)}, and one recorded with
     * {@link CameraView#startRecording()} at {@link #onVideoSaved(Uri)}, so an implementation
     * overrides whichever its videos use.
     */
    public interface OnVideoCapturedListener {
        /** A video is playing in the preview, waiting for confirmVideo or rejectVideo. */
        void onVideoConfirmation();

        /** A video from {@link CameraView#startRecording(File)} has been written to that file. */
        default void onVideoCaptured(File file) {}

        /**
         * A video from {@link CameraView#startRecording()} has been filed in the gallery.
         * {@link VideoView#setUri(Uri)} plays it.
         */
        default void onVideoSaved(@NonNull Uri uri) {}

        /** The video could not be recorded, or could not be filed. */
        void onFailure();
    }

    public interface OnCameraStateChangedListener {
        void onCameraOpened();
        void onCameraClosed();
    }

    /** Hears every change of zoom. See {@link #setOnZoomChangedListener}. */
    public interface OnZoomChangedListener {
        /** Called on the main thread, once per change. */
        @UiThread
        void onZoomChanged(float zoomRatio);
    }

    private class PinchToZoomGestureDetector extends ScaleGestureDetector implements ScaleGestureDetector.OnScaleGestureListener {
        /**
         * The zoom the pinch has reached, which each step of it multiplies.
         *
         * <p>Kept here rather than read back from the camera each step: the old camera API snaps
         * to the nearest entry of its zoom table, and the small steps of a pinch multiplied onto a
         * snapped zoom would never leave it.</p>
         */
        private float mZoomRatio = 1f;
        /** The bottom of the range the pinch is held to, read once as it starts. */
        private float mMinZoomRatio = 1f;
        /** The top of the range the pinch is held to, read once as it starts. */
        private float mMaxZoomRatio = 1f;

        PinchToZoomGestureDetector(Context context) {
            this(context, new S());
        }

        PinchToZoomGestureDetector(Context context, S s) {
            super(context, s);
            s.setRealGestureDetector(this);
        }

        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            if (!isPinchToZoomEnabled() || !isZoomSupported()) {
                return false;
            }
            // The fingers take over from wherever an animation has got to, and its next frame
            // would otherwise move the zoom out from under them.
            cancelZoomAnimation();
            mZoomRatio = getZoomRatio();
            mMinZoomRatio = getMinZoomRatio();
            mMaxZoomRatio = getMaxZoomRatio();
            return true;
        }

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            // Multiplied, so spreading the fingers twice as far apart zooms in twice as far from
            // wherever the zoom started. Held to the range at every step rather than once at the
            // end, so a pinch that overshoots turns around as soon as the fingers do.
            mZoomRatio = ZoomMath.clamp(
                    mZoomRatio * detector.getScaleFactor(), mMinZoomRatio, mMaxZoomRatio);
            setZoomRatio(mZoomRatio);
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {}
    }

    private static class S extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        private ScaleGestureDetector.OnScaleGestureListener listener;

        void setRealGestureDetector(ScaleGestureDetector.OnScaleGestureListener l) {
            listener = l;
        }

        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            return listener.onScaleBegin(detector);
        }

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            return listener.onScale(detector);
        }
    }
}
