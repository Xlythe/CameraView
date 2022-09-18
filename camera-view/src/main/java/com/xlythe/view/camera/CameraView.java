package com.xlythe.view.camera;

import android.Manifest;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
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

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;
import androidx.annotation.UiThread;

import com.xlythe.view.camera.legacy.LegacyCameraModule;
import com.xlythe.view.camera.v2.Camera2Module;
import com.xlythe.view.camera.x.CameraXModule;

import java.io.File;

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

    // When enabled, CameraX will be used. It's currently unstable.
    // Notable bugs:
    // * Pictures taken with CameraX may be rotated. CameraX does apply exif rotation itself, but
    //   incorrectly (eg. Samsung Fold 3 front facing camera). Attempts to overwrite the exif
    //   metadata manually to fix this failed (and I'm not sure why...).
    // * Videos taken with CameraX appear squished.
    static final boolean USE_CAMERA_X = false;

    // When enabled, CameraV2 will be used. It's currently stable.
    static final boolean USE_CAMERA_V2 = true;

    public static final int INDEFINITE_VIDEO_DURATION = -1;
    public static final int INDEFINITE_VIDEO_SIZE = -1;

    public static final String ACTION_CAMERA_STATE_CHANGED = "com.xlythe.view.camera.CAMERA_STATE_CHANGED";

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

    private enum Status {
        OPEN, CLOSED, AWAITING_TEXTURE
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

    /**
     * {@link TextureView.SurfaceTextureListener} handles several lifecycle events on a
     * {@link TextureView}.
     */
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            if (DEBUG) Log.v(TAG, "Surface Texture now available.");
            synchronized (CameraView.this) {
                if (getStatus() == Status.AWAITING_TEXTURE) {
                    setStatus(Status.OPEN);
                }
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {}

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            synchronized (CameraView.this) {
                if (getStatus() == Status.OPEN) {
                    Log.w(TAG, "Surface destroyed but was not closed.");
                    close();
                }
            }
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {}
    };

    private Status mStatus = Status.CLOSED;
    private OnImageCapturedListener mOnImageCapturedListener;
    private OnVideoCapturedListener mOnVideoCapturedListener;

    // For overriding onTouch
    private final int mTouchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
    private final int mLongPressTimeout = ViewConfiguration.getLongPressTimeout();
    private final Handler mHandler = new Handler();
    private float mInitialMotionEventX;
    private float mInitialMotionEventY;
    private boolean mIsLongPressMotionEvent;

    // For tap-to-focus
    private final Rect mFocusingRect = new Rect();
    private final Rect mMeteringRect = new Rect();

    // For pinch-to-zoom
    private PinchToZoomGestureDetector mScaleDetector;
    private boolean mIsPinchToZoomEnabled = true;

    private ICameraModule mCameraModule;

    @Nullable
    private OnCameraStateChangedListener mOnCameraStateChangedListener;

    private TextureView mCameraView;
    private ImageView mImagePreview;
    private VideoView mVideoPreview;

    private File mImagePendingConfirmation;
    private File mVideoPendingConfirmation;

    private boolean mIsImageConfirmationEnabled;
    private boolean mIsVideoConfirmationEnabled;

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

    private void init(Context context, @Nullable AttributeSet attrs) {
        if (USE_CAMERA_X && Build.VERSION.SDK_INT >= 21) {
            mCameraModule = new CameraXModule(this);
        } else if (USE_CAMERA_V2 && Build.VERSION.SDK_INT >= 21) {
            mCameraModule = new Camera2Module(this);
        } else {
            mCameraModule = new LegacyCameraModule(this);
        }

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
            a.recycle();
        }

        if (getBackground() == null) {
            setBackgroundColor(0xFF111111);
        }

        mScaleDetector = new PinchToZoomGestureDetector(context);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
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

    private synchronized void setStatus(Status status) {
        if (mStatus == status) {
            return;
        }

        if (DEBUG) {
            Log.v(TAG, "Camera state set to " + status.name());
        }
        mStatus = status;

        switch (mStatus) {
            case OPEN:
                onOpen();
                break;
            case CLOSED:
                onClose();
                break;
        }
    }

    /*
     * Opens the camera and starts displaying a preview. You are in charge of checking if the
     * phone has PackageManager.FEATURE_CAMERA_ANY and, if you are targeting Android M+, that
     * the phone has the following permissions:
     *       Manifest.permission.CAMERA
     *       Manifest.permission.RECORD_AUDIO
     *       Manifest.permission.WRITE_EXTERNAL_STORAGE
     */
    @RequiresPermission(allOf = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    })
    public synchronized void open() {
        if (mCameraView.isAvailable()) {
            setStatus(Status.OPEN);
        } else {
            setStatus(Status.AWAITING_TEXTURE);
        }
    }

    /*
     * Closes the camera.
     */
    public synchronized void close() {
        setStatus(Status.CLOSED);
    }

    /**
     * @return True if camera is currently open
     */
    public synchronized boolean isOpen() {
        return getStatus() == Status.OPEN;
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

        if (mOnCameraStateChangedListener != null) {
            mOnCameraStateChangedListener.onCameraOpened();
        }
    }

    protected void onClose() {
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
        if (isImageConfirmationEnabled()) {
            mCameraModule.pause();
        }

        mCameraModule.takePicture(file);
    }

    /**
     * Confirms a picture that is currently being displayed on the preview.
     */
    public void confirmPicture() {
        if (isImageConfirmationEnabled()) {
            mCameraModule.resume();
        }

        if (mImagePendingConfirmation == null) {
            throw new IllegalStateException("confirmPicture() called, but no picture was awaiting confirmation");
        }
        Image.clear(mImagePreview);
        mImagePreview.setVisibility(View.GONE);
        getOnImageCapturedListener().onImageCaptured(mImagePendingConfirmation);
        mImagePendingConfirmation = null;
    }

    /**
     * Rejects a picture that is currently being displayed on the preview.
     */
    public void rejectPicture() {
        if (isImageConfirmationEnabled()) {
            mCameraModule.resume();
        }

        if (mImagePendingConfirmation == null) {
            throw new IllegalStateException("rejectPicture() called, but no picture was awaiting confirmation");
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
     * @param file The destination.
     */
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
        if (isVideoConfirmationEnabled()) {
            mCameraModule.resume();
        }

        if (mVideoPendingConfirmation == null) {
            throw new IllegalStateException("confirmVideo() called, but no video was awaiting confirmation");
        }
        mVideoPreview.pause();
        mVideoPreview.setVisibility(View.GONE);
        getOnVideoCapturedListener().onVideoCaptured(mVideoPendingConfirmation);
        mVideoPendingConfirmation = null;
    }

    /**
     * Rejects a video that is currently being displayed on the preview.
     */
    public void rejectVideo() {
        if (isVideoConfirmationEnabled()) {
            mCameraModule.resume();
        }

        if (mVideoPendingConfirmation == null) {
            throw new IllegalStateException("rejectVideo() called, but no video was awaiting confirmation");
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

    protected int getRelativeCameraOrientation() {
        return mCameraModule.getRelativeCameraOrientation();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed) {
            mCameraModule.onLayoutChanged();
        }
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
                mHandler.postDelayed(() -> {
                    if (Build.VERSION.SDK_INT >= 24) {
                        performLongClick(mInitialMotionEventX, mInitialMotionEventY);
                    } else {
                        performLongClick();
                    }
                    mIsLongPressMotionEvent = true;
                }, mLongPressTimeout);
                break;
            case MotionEvent.ACTION_CANCEL:
                mHandler.removeCallbacksAndMessages(null);
                break;
            case MotionEvent.ACTION_UP:
                float distanceX = event.getX() - mInitialMotionEventX;
                float distanceY = event.getY() - mInitialMotionEventY;
                float distanceMoved = (float) Math.sqrt(distanceX * distanceX + distanceY * distanceY);
                if (distanceMoved > mTouchSlop && !mIsLongPressMotionEvent) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1
                            && hasOnClickListeners()) {
                        performClick();
                    } else {
                        calculateTapArea(mFocusingRect, event.getX(), event.getY(), 1f);
                        calculateTapArea(mMeteringRect, event.getX(), event.getY(), 1.5f);
                        if (area(mFocusingRect) != 0 && area(mMeteringRect) != 0) {
                            focus(mFocusingRect, mMeteringRect);
                        }
                    }
                    mHandler.removeCallbacksAndMessages(null);
                }
                break;
        }
        return true;
    }

    /**
     * Returns the width * height of the given rect
     */
    private int area(Rect rect) {
        return rect.width() * rect.height();
    }

    /**
     * The area must be between -1000,-1000 and 1000,1000
     */
    private void calculateTapArea(Rect rect, float x, float y, float coefficient) {
        int max = 1000;
        int min = -1000;

        // Default to 300 (1/6th the total area) and scale by the coefficient
        int areaSize = Float.valueOf(300 * coefficient).intValue();

        // Rotate the coordinates if the camera orientation is different
        int width = getWidth();
        int height = getHeight();

        int relativeCameraOrientation = getRelativeCameraOrientation();
        int temp = -1;
        float tempf = -1f;
        switch (relativeCameraOrientation) {
            case 90:
                // Fall-through
            case 270:
                // We're horizontal. Swap width/height. Swap x/y.
                temp = width;
                width = height;
                height = temp;

                tempf = x;
                x = y;
                y = tempf;
                break;
        }
        switch (relativeCameraOrientation) {
            case 180:
                // Fall-through
            case 270:
                // We're upside down. Fix x/y.
                x = width - x;
                y = height - y;
                break;
        }

        // Grab the x, y position from within the View and normalize it to -1000 to 1000
        x = min + distance(max, min) * (x / width);
        y = min + distance(max, min) * (y / height);


        // Modify the rect to the bounding area
        rect.top = (int) y - areaSize / 2;
        rect.left = (int) x - areaSize / 2;
        rect.bottom = rect.top + areaSize;
        rect.right = rect.left + areaSize;

        // Cap at -1000 to 1000
        rect.top = rangeLimit(rect.top, max, min);
        rect.left = rangeLimit(rect.left, max, min);
        rect.bottom = rangeLimit(rect.bottom, max, min);
        rect.right = rangeLimit(rect.right, max, min);
    }

    private int rangeLimit(int val, int max, int min) {
        return Math.min(Math.max(val, min), max);
    }

    private int distance(int a, int b) {
        return Math.abs(a - b);
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

            // y = (x-1) * (maxZoom/maxScale)
            setZoomLevel(rangeLimit((int) ((mScaleFactor-1) * getMaxZoomLevel() / MAX_SCALE), getMaxZoomLevel(), 0));
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
