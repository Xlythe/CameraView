package com.xlythe.view.camera;

import android.Manifest;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
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
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;
import androidx.annotation.UiThread;

import com.xlythe.view.camera.gl.CameraTextureProvider;
import com.xlythe.view.camera.gl.BackgroundReplacer;
import com.xlythe.view.camera.gl.FrameRenderer;
import com.xlythe.view.camera.gl.SegmentationMask;
import com.xlythe.view.camera.legacy.LegacyCameraModule;
import com.xlythe.view.camera.v2.Camera2Module;

import java.io.File;
import java.util.List;

/**
 * A {@link View} that displays a preview of the camera with methods {@link #takePicture(File)},
 * {@link #startRecording(File)} and {@link #stopRecording()}.
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

    private static final String EXTRA_SUPER = "super";
    private static final String EXTRA_MODULE = "module";
    private static final String EXTRA_QUALITY = "quality";
    private static final String EXTRA_ZOOM_LEVEL = "zoom_level";
    private static final String EXTRA_PINCH_TO_ZOOM_ENABLED = "pinch_to_zoom_enabled";
    private static final String EXTRA_PINCH_TO_ZOOM_SCALE_FACTOR = "pinch_to_zoom_scale_factor";
    private static final String EXTRA_FLASH = "flash";
    private static final String EXTRA_LENS_FACING = "lens_facing";
    private static final String EXTRA_MAX_VIDEO_DURATION = "max_video_duration";
    private static final String EXTRA_MAX_VIDEO_SIZE = "max_video_size";
    private static final String EXTRA_CONFIRM_IMAGE = "confirm_image";
    private static final String EXTRA_CONFIRM_VIDEO = "confirm_video";
    private static final String EXTRA_PENDING_IMAGE_FILE_PATH = "pending_image_file_path";
    private static final String EXTRA_PENDING_VIDEO_FILE_PATH = "pending_video_file_path";
    private static final String EXTRA_MATCH_PREVIEW_ASPECT_RATIO = "match_preview_aspect_ratio";
    /** Saved-state key for dynamic range. */
    private static final String EXTRA_DYNAMIC_RANGE = "dynamic_range";
    /** Saved-state key for the CameraX photo extension. */
    private static final String EXTRA_EXTENSION = "extension";
    /** Saved-state key for front-video mirroring. */
    private static final String EXTRA_FRONT_VIDEO_MIRRORING = "front_video_mirroring";
    /** Saved-state key for front-photo mirroring. */
    private static final String EXTRA_FRONT_PHOTO_MIRRORING = "front_photo_mirroring";
    /** Saved-state key for stabilization. */
    private static final String EXTRA_STABILIZATION = "stabilization";
    /** Saved-state key for exposure compensation. */
    private static final String EXTRA_EXPOSURE_COMPENSATION = "exposure_compensation";
    /** Saved-state key for torch state. */
    private static final String EXTRA_TORCH = "torch";
    /** Saved-state key for low-light boost state. */
    private static final String EXTRA_LOW_LIGHT_BOOST = "low_light_boost";

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
    /**
     * The file a picture is written as.
     *
     * <p>{@link #JPEG} everywhere. {@link #ULTRA_HDR} is a JPEG carrying a gain map beside the
     * image, which a display capable of it uses to light the bright parts beyond what the base
     * image can reach; anything that does not understand the gain map reads the base image and
     * shows an ordinary photo, so the file stays universally readable.</p>
     *
     * <p>{@link #RAW} is the sensor's own reading, before the camera's processing has made any
     * of its decisions about it - far more information than a JPEG carries, and the right answer
     * to wanting better stills than JPEG. It is also several times the size, needs an editor
     * rather than a gallery to open, and {@link #RAW_JPEG} exists because of that: both files
     * from one shutter press, so something is viewable immediately.</p>
     *
     * <p>Ask {@link #isPictureFormatSupported} first. All three need a camera that offers the
     * format, and {@link #ULTRA_HDR} needs the CameraX backend as well - its gain map is composed
     * by CameraX, and the camera2 backend writes the JPEG the HAL produced. {@link #RAW} and
     * {@link #RAW_JPEG} work on either backend, on a camera that reports the RAW capability.</p>
     *
     * <p>A DNG is not a JPEG, so {@link #RAW_JPEG} writes two files from one shutter press: the
     * JPEG at the destination given to {@link #takePicture(File)}, the DNG beside it under the
     * same name. The capture is still reported once, with the JPEG, because it was one picture.</p>
     */
    public enum PictureFormat {
        JPEG(0), ULTRA_HDR(1), RAW(2), RAW_JPEG(3);

        /** Stable XML and saved-state identifier. */
        private final int id;

        PictureFormat(int id) {
            this.id = id;
        }

        static PictureFormat fromId(int id) {
            for (PictureFormat format : values()) {
                if (format.id == id) return format;
            }
            throw new IllegalArgumentException();
        }
    }

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

    // For pinch-to-zoom
    private PinchToZoomGestureDetector mScaleDetector;
    private boolean mIsPinchToZoomEnabled = true;

    private ICameraModule mCameraModule;

    /** Lets a burst of layout passes finish before the camera is reconfigured for the last one. */
    private final Runnable mLayoutSettledRunnable = this::onLayoutSettled;

    @Nullable
    private OnCameraStateChangedListener mOnCameraStateChangedListener;

    private TextureView mCameraView;
    private ImageView mImagePreview;
    private VideoView mVideoPreview;

    private File mImagePendingConfirmation;
    private File mVideoPendingConfirmation;

    private boolean mIsImageConfirmationEnabled;
    private boolean mIsVideoConfirmationEnabled;

    @Nullable
    private BarcodeScanner mBarcodeScanner;
    /** Whether the active backend owns a native barcode analysis use case. */
    private boolean mUsingModuleBarcodeScanner;
    /** Whether enterBackgroundReplacement is in force. */
    private boolean mReplacingBackground;

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
            setDynamicRange(DynamicRange.fromId(a.getInteger(R.styleable.CameraView_dynamicRange, getDynamicRange().id)));
            setExtension(Extension.fromId(a.getInteger(R.styleable.CameraView_extension, getExtension().id)));
            setFrontVideoMirroringEnabled(a.getBoolean(R.styleable.CameraView_frontVideoMirroringEnabled, isFrontVideoMirroringEnabled()));
            setFrontPhotoMirroringEnabled(a.getBoolean(R.styleable.CameraView_frontPhotoMirroringEnabled, isFrontPhotoMirroringEnabled()));
            setStabilizationEnabled(a.getBoolean(R.styleable.CameraView_stabilizationEnabled, isStabilizationEnabled()));
            setExposureCompensation(a.getInteger(R.styleable.CameraView_exposureCompensation, getExposureCompensation()));
            setTorchEnabled(a.getBoolean(R.styleable.CameraView_torchEnabled, isTorchEnabled()));
            setLowLightBoostEnabled(a.getBoolean(R.styleable.CameraView_lowLightBoostEnabled, isLowLightBoostEnabled()));
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
        state.putInt(EXTRA_ZOOM_LEVEL, getZoomLevel());
        state.putBoolean(EXTRA_PINCH_TO_ZOOM_ENABLED, isPinchToZoomEnabled());
        state.putFloat(EXTRA_PINCH_TO_ZOOM_SCALE_FACTOR, mScaleDetector.getCumulativeScaleFactor());
        state.putInt(EXTRA_FLASH, getFlash().id);
        state.putInt(EXTRA_LENS_FACING, getLensFacing().id);
        state.putLong(EXTRA_MAX_VIDEO_DURATION, getMaxVideoDuration());
        state.putLong(EXTRA_MAX_VIDEO_SIZE, getMaxVideoSize());
        state.putBoolean(EXTRA_CONFIRM_IMAGE, isImageConfirmationEnabled());
        state.putBoolean(EXTRA_CONFIRM_VIDEO, isVideoConfirmationEnabled());
        state.putBoolean(EXTRA_MATCH_PREVIEW_ASPECT_RATIO, isMatchPreviewAspectRatioEnabled());
        state.putInt(EXTRA_DYNAMIC_RANGE, getDynamicRange().id);
        state.putInt(EXTRA_EXTENSION, getExtension().id);
        state.putBoolean(EXTRA_FRONT_VIDEO_MIRRORING, isFrontVideoMirroringEnabled());
        state.putBoolean(EXTRA_FRONT_PHOTO_MIRRORING, isFrontPhotoMirroringEnabled());
        state.putBoolean(EXTRA_STABILIZATION, isStabilizationEnabled());
        state.putInt(EXTRA_EXPOSURE_COMPENSATION, getExposureCompensation());
        state.putBoolean(EXTRA_TORCH, isTorchEnabled());
        state.putBoolean(EXTRA_LOW_LIGHT_BOOST, isLowLightBoostEnabled());
        if (mImagePendingConfirmation != null) {
            state.putString(EXTRA_PENDING_IMAGE_FILE_PATH, mImagePendingConfirmation.getAbsolutePath());
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
            setZoomLevel(state.getInt(EXTRA_ZOOM_LEVEL));
            setPinchToZoomEnabled(state.getBoolean(EXTRA_PINCH_TO_ZOOM_ENABLED));
            mScaleDetector.setCumulativeScaleFactor(state.getFloat(EXTRA_PINCH_TO_ZOOM_SCALE_FACTOR, mScaleDetector.getCumulativeScaleFactor()));
            setFlash(Flash.fromId(state.getInt(EXTRA_FLASH)));
            setLensFacing(LensFacing.fromId(state.getInt(EXTRA_LENS_FACING)));
            setMaxVideoDuration(state.getLong(EXTRA_MAX_VIDEO_DURATION));
            setMaxVideoSize(state.getLong(EXTRA_MAX_VIDEO_SIZE));
            setImageConfirmationEnabled(state.getBoolean(EXTRA_CONFIRM_IMAGE));
            setVideoConfirmationEnabled(state.getBoolean(EXTRA_CONFIRM_VIDEO));
            setMatchPreviewAspectRatio(state.getBoolean(EXTRA_MATCH_PREVIEW_ASPECT_RATIO));
            setDynamicRange(DynamicRange.fromId(state.getInt(EXTRA_DYNAMIC_RANGE, DynamicRange.SDR.id)));
            setExtension(Extension.fromId(state.getInt(EXTRA_EXTENSION, Extension.NONE.id)));
            setFrontVideoMirroringEnabled(state.getBoolean(EXTRA_FRONT_VIDEO_MIRRORING, true));
            setFrontPhotoMirroringEnabled(state.getBoolean(EXTRA_FRONT_PHOTO_MIRRORING, true));
            setStabilizationEnabled(state.getBoolean(EXTRA_STABILIZATION));
            setExposureCompensation(state.getInt(EXTRA_EXPOSURE_COMPENSATION));
            setTorchEnabled(state.getBoolean(EXTRA_TORCH));
            setLowLightBoostEnabled(state.getBoolean(EXTRA_LOW_LIGHT_BOOST));

            if (state.containsKey(EXTRA_PENDING_IMAGE_FILE_PATH)) {
                File file = new File(state.getString(EXTRA_PENDING_IMAGE_FILE_PATH));
                if (file.exists()) {
                    showImageConfirmation(file);
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
     * by the methods that record or stream audio, which ask for it themselves, and outputs are
     * written to the File you pass in, so no storage permission is involved.
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
     * {@link OnImageCapturedListener#onImageCaptured(File)} is triggered. A preview of the image
     * will be displayed in CameraView's preview.
     */
    public void setImageConfirmationEnabled(boolean enabled) {
        mIsImageConfirmationEnabled = enabled;
    }

    /**
     * When true, {@link #takePicture(File)} will trigger the
     * {@link OnImageCapturedListener#onImageConfirmation()} callback. To continue,  call
     * {@link #confirmPicture()}.
     */
    public boolean isImageConfirmationEnabled() {
        return mIsImageConfirmationEnabled;
    }

    /**
     * When enabled, requires videos to be confirmed before
     * {@link OnVideoCapturedListener#onVideoCaptured(File)} is triggered. A preview of the video
     * will be displayed in CameraView's preview.
     */
    public void setVideoConfirmationEnabled(boolean enabled) {
        mIsVideoConfirmationEnabled = enabled;
    }

    /**
     * When true, {@link #startRecording(File)} will trigger the
     * {@link OnVideoCapturedListener#onVideoConfirmation()} callback. To continue,  call
     * {@link #confirmVideo()}.
     */
    public boolean isVideoConfirmationEnabled() {
        return mIsVideoConfirmationEnabled;
    }

    void showImageConfirmation(File file) {
        Log.d(TAG, "Saved the picture to " + file);
        if (isImageConfirmationEnabled()) {
            mCameraModule.pause();
            mImagePreview.setVisibility(View.VISIBLE);
            Image.with(getContext()).load(file).into(mImagePreview);
            mImagePendingConfirmation = file;

            if (getOnImageCapturedListener() != null) {
                getOnImageCapturedListener().onImageConfirmation();
            }
        } else {
            if (getOnImageCapturedListener() != null) {
                getOnImageCapturedListener().onImageCaptured(file);
            }
        }
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
            mVideoPreview.setShouldMirror(isUsingFrontFacingCamera());
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
            if (getOnVideoCapturedListener() != null) {
                getOnVideoCapturedListener().onVideoCaptured(file);
            }
        }
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
     * Gets the current quality for image and video outputs.
     */
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

    protected void onOpen() {
        mCameraModule.open();
    }

    protected void onClose() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            exitBarcodeScanner();
            stopFrameAnalysis();
        }
        mCameraModule.close();

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
        if (getOnImageCapturedListener() != null) {
            getOnImageCapturedListener().onImageCaptured(mImagePendingConfirmation);
        }
        mImagePendingConfirmation = null;
    }

    /**
     * Rejects a picture that is currently being displayed on the preview.
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
        if (!mImagePendingConfirmation.delete()) {
            Log.w(TAG, "Attempted to clean up pending image file, but failed");
        }
        mImagePendingConfirmation = null;
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
        if (getOnVideoCapturedListener() != null) {
            getOnVideoCapturedListener().onVideoCaptured(mVideoPendingConfirmation);
        }
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
     *     this device - it is delivered through Play services and an app can ship without the
     *     initializer that sets it up, so asking for a scanner is a request that can be declined
     *     rather than a programming error.
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
            // returning anything when ML Kit was never initialised. Letting that out would mean
            // an app crashing in a camera library because a vision dependency is missing. The
            // CameraX backend already declines the same failure in startBarcodeScanner; this is
            // the path the other two backends take.
            Log.w(TAG, "Barcode scanning is unavailable on this device", error);
            mBarcodeScanner = null;
            return false;
        }
        mBarcodeScanner.start();
        return true;
    }

    /**
     * What a segmenter reports: which parts of each frame are the subject.
     *
     * <p>Delivered on the main thread, as often as the model manages, which is slower than the
     * frame rate and is expected to be. A listener that holds onto a mask is holding onto a
     * description of a moment that has passed.</p>
     */
    public interface SegmentationListener {
        void onSegmentation(@NonNull SegmentationMask mask);
    }

    /**
     * Starts segmenting frames and feeding the result to a renderer that composites with it.
     *
     * <p>The two halves are separate on purpose and can be used apart: a caller with their own
     * model can drive {@link BackgroundReplacer#setMask} themselves, and a caller who only
     * wants to know where the subject is can take the masks and draw nothing. This is the
     * convenience for the common case of wanting a different background.</p>
     *
     * <p>Only the CameraX backend can do it, because it needs a frame stream alongside a
     * running preview. It also takes the one analysis slot, so it cannot run at the same time
     * as the barcode scanner.</p>
     *
     * @return false where the backend cannot segment or the model is unavailable
     */
    @RequiresApi(21)
    @RequiresPermission(Manifest.permission.CAMERA)
    public boolean enterBackgroundReplacement(@NonNull BackgroundReplacer replacer) {
        if (!isOpen()) {
            throw new IllegalStateException(
                    "Camera must be open before replacing the background");
        }
        if (!isFrameEffectSupported()) {
            Log.w(TAG, "This backend draws no effects, so there is nothing to composite with");
            return false;
        }
        setFrameRenderer(replacer);
        if (!mCameraModule.startSegmentation(replacer::setMask)) {
            setFrameRenderer(null);
            return false;
        }
        mReplacingBackground = true;
        return true;
    }

    /** Stops segmenting and puts the camera frame back as it was. */
    @RequiresApi(21)
    public void exitBackgroundReplacement() {
        if (!mReplacingBackground) {
            return;
        }
        mReplacingBackground = false;
        mCameraModule.stopSegmentation();
        setFrameRenderer(null);
    }

    /** Whether a background is currently being replaced. */
    public boolean isReplacingBackground() {
        return mReplacingBackground;
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
     * @throws IllegalStateException if the camera is not open, or analysis is already running
     */
    @RequiresApi(19)
    @RequiresPermission(Manifest.permission.CAMERA)
    public void startFrameAnalysis(@NonNull FrameStream.Analyzer analyzer) {
        startFrameAnalysis(analyzer, new FrameStream.Params());
    }

    /**
     * {@link #startFrameAnalysis(FrameStream.Analyzer)} with a say over the frames themselves.
     *
     * @see FrameStream.Params#setMaxSize
     */
    @RequiresApi(19)
    @RequiresPermission(Manifest.permission.CAMERA)
    public void startFrameAnalysis(
            @NonNull FrameStream.Analyzer analyzer, @NonNull FrameStream.Params params) {
        if (!isOpen()) {
            throw new IllegalStateException("Camera must be open before analyzing frames");
        }
        if (mFrameStream != null) {
            throw new IllegalStateException("Frame analysis cannot be started twice");
        }
        if (!isFrameAnalysisSupported()) {
            // Refused rather than accepted and never honoured. The old backend has no way to
            // produce the frames an analyzer reads, and a caller waiting on frames that cannot
            // come has no way to tell that from a camera that is merely slow.
            throw new UnsupportedOperationException("The " + getBackend()
                    + " backend cannot analyze frames; check isFrameAnalysisSupported() first");
        }
        mFrameStream = new FrameStream(mCameraModule.getCanvas(), this, params, analyzer);
        mFrameStream.start();
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

    /**
     * Whether {@link #startFrameAnalysis} will work on the backend in use.
     *
     * <p>Every camera2-based backend can; {@link Backend#LEGACY} cannot, because the original
     * Camera API hands out preview bytes rather than a surface an analyzer can read.</p>
     */
    public boolean isFrameAnalysisSupported() {
        return mCameraModule.isFrameAnalysisSupported();
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

    /**
     * Draws every camera frame through a caller's renderer on its way to the preview, the
     * recording and the stills.
     *
     * <p>Unopinionated on purpose: the library puts the frames through GL and says nothing at all
     * about what comes out. A tint, a watermark burnt into the recording, a replaced background, a
     * face drawn over a face, something registered to a point in the world - they are all this one
     * hook, and which of them a camera does is the app's decision rather than this library's.</p>
     *
     * <p>What makes it worth going through GL instead of drawing a View over the preview is that
     * the frame is drawn into every output: the still and the recording come out the way the
     * screen looked, not the way the camera looked.</p>
     *
     * <p>Ask {@link #isFrameEffectSupported()} first. A renderer set on a backend with no effects
     * is accepted and ignored rather than refused, because an effect is usually decoration and a
     * camera that keeps working beats one that throws - but a caller whose renderer is the whole
     * feature needs to know, and the probe is how.</p>
     *
     * <p>The renderer is called on the GL thread, never this one. See {@link FrameRenderer}.</p>
     *
     * @param renderer what draws each frame, or null to go back to the camera's own frame
     * @see #startFrameAnalysis for reading frames rather than drawing them
     */
    public void setFrameRenderer(@Nullable FrameRenderer renderer) {
        mCameraModule.setFrameRenderer(renderer);
    }

    /** @see #setFrameRenderer */
    @Nullable
    public FrameRenderer getFrameRenderer() {
        return mCameraModule.getFrameRenderer();
    }

    /**
     * Hands the camera a texture the caller created, and tells the caller when a frame lands on
     * it.
     *
     * <p>The other half of the seam, and the half that cannot be worked around. A renderer draws
     * a texture this library made; a consumer that has to be given the texture name rather than
     * handed one - because it owns the context the texture must live in, or because its own API
     * takes a name - cannot use that at all. So this exists: set a provider and the camera renders
     * into its texture instead of ours.</p>
     *
     * <p>ARCore's non-shared session is the example to hand:
     * {@code Session.setCameraTextureName(int)} takes a texture the caller made, and until it has
     * one {@code Session.update()} never returns a frame.</p>
     *
     * <p>The texture is created once, when the camera starts rendering, so changing the provider
     * reconfigures the camera where changing the renderer does not.</p>
     *
     * @param provider who creates the texture and consumes its frames, or null for the library's
     *     own texture
     */
    public void setCameraTextureProvider(@Nullable CameraTextureProvider provider) {
        mCameraModule.setCameraTextureProvider(provider);
    }

    /** @see #setCameraTextureProvider */
    @Nullable
    public CameraTextureProvider getCameraTextureProvider() {
        return mCameraModule.getCameraTextureProvider();
    }

    /**
     * Whether {@link #setFrameRenderer} and {@link #setCameraTextureProvider} do anything on the
     * backend in use.
     *
     * <p>{@link Backend#CAMERA_X} can, because androidx.camera has a seam for putting a processor
     * between the camera and its outputs. The other two deliver camera buffers straight to the
     * surfaces that consume them, so there is nowhere for a frame to be drawn on the way, and they
     * report false rather than accepting a renderer that would never be called.</p>
     *
     * <p>False also once GL has failed on this device, which is reported here rather than thrown
     * so that the camera carries on working without the effect.</p>
     */
    public boolean isFrameEffectSupported() {
        return mCameraModule.isFrameEffectSupported();
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
     * Whether this camera will record above its normal frame rate, for slow-motion playback.
     *
     * <p>Far from universal: it needs a camera that publishes the configurations, and the session
     * that runs them arrived in API 23.</p>
     */
    public boolean isHighSpeedRecordingSupported() {
        return mCameraModule.isHighSpeedRecordingSupported();
    }

    /**
     * Every frame size and frame rate this camera records high-speed video at.
     *
     * <p>The pairs are the camera's own: a phone that records 240fps at 720p often manages only
     * 120fps at 1080p, so a frame rate on its own is not a choice this library can offer. Empty
     * where {@link #isHighSpeedRecordingSupported()} is false.</p>
     *
     * <p>Worth asking once the camera is open. The CameraX backend has no camera to ask until it
     * has bound one, so it answers with nothing before then.</p>
     */
    @NonNull
    public List<HighSpeedProfile> getSupportedHighSpeedProfiles() {
        return mCameraModule.getSupportedHighSpeedProfiles();
    }

    /**
     * Records at one of the published high-speed choices, or at ordinary speed when given null.
     *
     * <p>Takes effect on the next {@link #startRecording}; the preview keeps running at its
     * ordinary rate until then. A high-speed session carries only the preview and the recording,
     * so {@link #takePicture} reports a failure while one is running and the recording continues.
     * </p>
     *
     * <p>{@link #setMaxVideoDuration} is measured against the file rather than the clock, since
     * that is what the recorder counts: a ten second limit stops a 240fps recording after about
     * a second and a quarter of filming.</p>
     *
     * @return whether the camera is now set to record at the requested choice. False means the
     *     camera does not publish it, and the previous choice stands.
     */
    public boolean setHighSpeedProfile(@Nullable HighSpeedProfile profile) {
        return mCameraModule.setHighSpeedProfile(profile);
    }

    /** The high-speed choice recordings are made at, or null for ordinary speed. */
    @Nullable
    public HighSpeedProfile getHighSpeedProfile() {
        return mCameraModule.getHighSpeedProfile();
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
        mCameraModule.toggleCamera();
    }

    /**
     * Sets which camera (front or back) to use.
     */
    public void setLensFacing(LensFacing lensFacing) {
        if (getLensFacing().equals(lensFacing)) {
            return;
        }

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
        if (mFocusListener != null) {
            mFocusListener.onFocusCompleted(area, locked);
        }
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

    /** Controls whether front-camera video files are mirrored. */
    public void setFrontVideoMirroringEnabled(boolean enabled) {
        mCameraModule.setFrontVideoMirroringEnabled(enabled);
    }

    /** Returns whether front-camera video files are mirrored. */
    public boolean isFrontVideoMirroringEnabled() {
        return mCameraModule.isFrontVideoMirroringEnabled();
    }

    /**
     * Whether a picture taken with the user-facing lens is mirrored.
     *
     * <p>True by default, which is what every backend did before this could be asked: a selfie
     * comes out the way round the preview showed it. Turn it off to save the scene the way the
     * world actually is, as a rear lens would.</p>
     *
     * <p>Separate from {@link #setFrontVideoMirroringEnabled}, because a caller who wants a
     * recording un-mirrored does not necessarily want the stills un-mirrored too - and before
     * this existed, setting that one left the stills mirrored anyway with no way to say
     * otherwise.</p>
     */
    public void setFrontPhotoMirroringEnabled(boolean enabled) {
        mCameraModule.setFrontPhotoMirroringEnabled(enabled);
    }

    /**
     * Writes pictures in this format, where the camera can.
     *
     * <p>Takes effect on the next capture. An unsupported format is declined and the previous one
     * kept, so check {@link #isPictureFormatSupported} rather than assuming - a request that
     * cannot be honoured would otherwise look like it had been.</p>
     */
    public void setPictureFormat(PictureFormat format) {
        mCameraModule.setPictureFormat(format);
    }

    /** @see #setPictureFormat */
    public PictureFormat getPictureFormat() {
        return mCameraModule.getPictureFormat();
    }

    /** Whether the camera in use can write this format. */
    public boolean isPictureFormatSupported(PictureFormat format) {
        return mCameraModule.isPictureFormatSupported(format);
    }

    /** @see #setFrontPhotoMirroringEnabled */
    public boolean isFrontPhotoMirroringEnabled() {
        return mCameraModule.isFrontPhotoMirroringEnabled();
    }

    /**
     * The backend currently driving this view.
     *
     * <p>Package-private: which implementation is in use is {@link #getBackend}'s answer, and the
     * module itself is the library's business. This is how the library's own tests reach a
     * decision a backend makes without a camera having to produce a file to prove it.</p>
     */
    ICameraModule getCameraModule() {
        return mCameraModule;
    }

    /**
     * Steadies the picture, in the preview and in a recording, where the camera can.
     *
     * <p>One switch rather than two. A camera may be able to steady one and not the other,
     * and each is then steadied where it can be; which of the two a given camera manages is
     * not something a caller can act on.</p>
     */
    public void setStabilizationEnabled(boolean enabled) {
        mCameraModule.setStabilizationEnabled(enabled);
    }

    /** Returns whether stabilization is requested. */
    public boolean isStabilizationEnabled() {
        return mCameraModule.isStabilizationEnabled();
    }

    /** Returns whether the active camera can steady either the preview or a recording. */
    public boolean isStabilizationSupported() {
        return mCameraModule.isStabilizationSupported();
    }

    /** Sets the exposure compensation index, clamped to the active camera's range. */
    public void setExposureCompensation(int exposureCompensation) {
        mCameraModule.setExposureCompensation(exposureCompensation);
    }

    /** Returns the last accepted exposure compensation index. */
    public int getExposureCompensation() {
        return mCameraModule.getExposureCompensation();
    }

    /** Returns the active camera's minimum exposure compensation index. */
    public int getMinExposureCompensation() {
        return mCameraModule.getMinExposureCompensation();
    }

    /** Returns the active camera's maximum exposure compensation index. */
    public int getMaxExposureCompensation() {
        return mCameraModule.getMaxExposureCompensation();
    }

    /** Returns whether the active camera supports exposure compensation. */
    public boolean isExposureCompensationSupported() {
        return mCameraModule.isExposureCompensationSupported();
    }

    /** Enables continuous torch illumination when supported. */
    public void setTorchEnabled(boolean enabled) {
        mCameraModule.setTorchEnabled(enabled);
    }

    /** Returns whether torch illumination is requested. */
    public boolean isTorchEnabled() {
        return mCameraModule.isTorchEnabled();
    }

    /** Enables CameraX low-light boost when supported. */
    public void setLowLightBoostEnabled(boolean enabled) {
        mCameraModule.setLowLightBoostEnabled(enabled);
    }

    /** Returns whether low-light boost is requested. */
    public boolean isLowLightBoostEnabled() {
        return mCameraModule.isLowLightBoostEnabled();
    }

    /** Returns whether the active camera supports low-light boost. */
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
     * Maps a cumulative pinch scale onto a zoom level.
     *
     * <p>The scale starts at 1 rather than 0, so the usable range of a pinch is maxScale - 1. A
     * fully spread pinch has to land on the camera's maximum zoom; dividing by maxScale instead
     * stopped at four fifths of it and left the last of the range unreachable.</p>
     *
     * @param scaleFactor the cumulative scale, between 1 and maxScale
     * @param maxScale the scale a fully spread pinch reaches
     * @param maxZoomLevel the zoom level that scale maps to
     */
    static int zoomLevelForScale(float scaleFactor, float maxScale, int maxZoomLevel) {
        if (maxScale <= 1f || maxZoomLevel <= 0) {
            return 0;
        }
        float clamped = Math.max(1f, Math.min(scaleFactor, maxScale));
        int zoomLevel = (int) ((clamped - 1f) * maxZoomLevel / (maxScale - 1f));
        return Math.min(Math.max(zoomLevel, 0), maxZoomLevel);
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
     * Sets the current zoom level, from 0 to {@link #getMaxZoomLevel()}.
     */
    public void setZoomLevel(int zoomLevel) {
        mCameraModule.setZoomLevel(zoomLevel);
    }

    /**
     * @return The current zoom level.
     */
    public int getZoomLevel() {
        return mCameraModule.getZoomLevel();
    }

    /**
     * @return The maximum zoom level.
     */
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

    public interface OnImageCapturedListener {
        void onImageConfirmation();
        void onImageCaptured(File file);
        void onFailure();
    }

    public interface OnVideoCapturedListener {
        void onVideoConfirmation();
        void onVideoCaptured(File file);
        void onFailure();
    }

    public interface OnCameraStateChangedListener {
        void onCameraOpened();
        void onCameraClosed();
    }

    private class PinchToZoomGestureDetector extends ScaleGestureDetector implements ScaleGestureDetector.OnScaleGestureListener {
        final float MAX_SCALE = 5f;
        float mScaleFactor = 1f;

        PinchToZoomGestureDetector(Context context) {
            this(context, new S());
        }

        PinchToZoomGestureDetector(Context context, S s) {
            super(context, s);
            s.setRealGestureDetector(this);
        }

        float getCumulativeScaleFactor() {
            return mScaleFactor;
        }

        void setCumulativeScaleFactor(float scaleFactor) {
            mScaleFactor = scaleFactor;
        }

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            mScaleFactor *= detector.getScaleFactor();

            // Don't let the object get too small or too large.
            mScaleFactor = Math.max(1f, Math.min(mScaleFactor, MAX_SCALE));

            setZoomLevel(zoomLevelForScale(mScaleFactor, MAX_SCALE, getMaxZoomLevel()));
            return true;
        }

        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            return false;
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
        public boolean onScale(ScaleGestureDetector detector) {
            return listener.onScale(detector);
        }
    }
}
