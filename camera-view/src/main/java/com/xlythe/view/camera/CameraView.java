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
import android.os.Parcelable;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresPermission;
import android.support.annotation.UiThread;
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

import com.xlythe.view.camera.legacy.LegacyCameraModule;
import com.xlythe.view.camera.v2.Camera2Module;

import java.io.File;

public class CameraView extends FrameLayout {
    static final String TAG = CameraView.class.getSimpleName();
    static final boolean DEBUG = false;

    public static final int INDEFINITE_VIDEO_DURATION = -1;
    public static final int INDEFINITE_VIDEO_SIZE = -1;

    private static final String EXTRA_SUPER = "super";
    private static final String EXTRA_MODULE = "module";
    private static final String EXTRA_QUALITY = "quality";
    private static final String EXTRA_ZOOM_LEVEL = "zoom_level";
    private static final String EXTRA_PINCH_TO_ZOOM_ENABLED = "pinch_to_zoom_enabled";
    private static final String EXTRA_PINCH_TO_ZOOM_SCALE_FACTOR = "pinch_to_zoom_scale_factor";
    private static final String EXTRA_FLASH = "flash";
    private static final String EXTRA_MAX_VIDEO_DURATION = "max_video_duration";
    private static final String EXTRA_MAX_VIDEO_SIZE = "max_video_size";
    private static final String EXTRA_CONFIRM_IMAGE = "confirm_image";
    private static final String EXTRA_CONFIRM_VIDEO = "confirm_video";
    private static final String EXTRA_PENDING_IMAGE_FILE_PATH = "pending_image_file_path";
    private static final String EXTRA_PENDING_VIDEO_FILE_PATH = "pending_video_file_path";

    private enum Status {
        OPEN, CLOSED, AWAITING_TEXTURE
    }

    public enum Quality {
        MAX(0), HIGH(1), MEDIUM(2), LOW(3);

        private final int id;

        Quality(int id) {
            this.id = id;
        }

        static Quality fromId(int id) {
            for (Quality f : values()) {
                if (f.id == id) return f;
            }
            throw new IllegalArgumentException();
        }
    }

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
                    onOpen();
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

    // For tap-to-focus
    private long mDownEventTimestamp;
    private final Rect mFocusingRect = new Rect();
    private final Rect mMeteringRect = new Rect();

    // For pinch-to-zoom
    private PinchToZoomGestureDetector mScaleDetector;
    private boolean mIsPinchToZoomEnabled = true;

    private ICameraModule mCameraModule;

    private TextureView mCameraView;
    private ImageView mImagePreview;
    private VideoView mVideoPreview;

    private File mImagePendingConfirmation;
    private File mVideoPendingConfirmation;

    private boolean mIsImageConfirmationEnabled;
    private boolean mIsVideoConfirmationEnabled;

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
        if (Build.VERSION.SDK_INT >= 21) {
            mCameraModule = new Camera2Module(this);
        } else {
            mCameraModule = new LegacyCameraModule(this);
        }

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.CameraView);
            setQuality(Quality.fromId(a.getInteger(R.styleable.CameraView_quality, getQuality().id)));
            setFlash(Flash.fromId(a.getInteger(R.styleable.CameraView_flash, getFlash().id)));
            setPinchToZoomEnabled(a.getBoolean(R.styleable.CameraView_pinchToZoomEnabled, isPinchToZoomEnabled()));
            if (a.hasValue(R.styleable.CameraView_maxVideoDuration)) {
                setMaxVideoDuration(a.getInteger(R.styleable.CameraView_maxVideoDuration, INDEFINITE_VIDEO_DURATION));
            }
            if (a.hasValue(R.styleable.CameraView_maxVideoSize)) {
                setMaxVideoSize(a.getInteger(R.styleable.CameraView_maxVideoSize, INDEFINITE_VIDEO_SIZE));
            }
            setImageConfirmationEnabled(a.getBoolean(R.styleable.CameraView_confirmImages, isImageConfirmationEnabled()));
            setVideoConfirmationEnabled(a.getBoolean(R.styleable.CameraView_confirmVideos, isVideoConfirmationEnabled()));
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
        state.putLong(EXTRA_MAX_VIDEO_DURATION, getMaxVideoDuration());
        state.putLong(EXTRA_MAX_VIDEO_SIZE, getMaxVideoSize());
        state.putBoolean(EXTRA_CONFIRM_IMAGE, isImageConfirmationEnabled());
        state.putBoolean(EXTRA_CONFIRM_VIDEO, isVideoConfirmationEnabled());
        if (mImagePendingConfirmation != null) {
            state.putString(EXTRA_PENDING_IMAGE_FILE_PATH, mImagePendingConfirmation.getAbsolutePath());
        }
        if (mVideoPendingConfirmation != null) {
            state.putString(EXTRA_PENDING_VIDEO_FILE_PATH, mVideoPendingConfirmation.getAbsolutePath());
        }
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
            setMaxVideoDuration(state.getLong(EXTRA_MAX_VIDEO_DURATION));
            setMaxVideoSize(state.getLong(EXTRA_MAX_VIDEO_SIZE));
            setImageConfirmationEnabled(state.getBoolean(EXTRA_CONFIRM_IMAGE));
            setVideoConfirmationEnabled(state.getBoolean(EXTRA_CONFIRM_VIDEO));

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
            onOpen();
        } else {
            setStatus(Status.AWAITING_TEXTURE);
        }
    }

    /*
     * Closes the camera.
     */
    public synchronized void close() {
        setStatus(Status.CLOSED);
        onClose();
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

    public void setImageConfirmationEnabled(boolean enabled) {
        mIsImageConfirmationEnabled = enabled;
    }

    public boolean isImageConfirmationEnabled() {
        return mIsImageConfirmationEnabled;
    }

    public void setVideoConfirmationEnabled(boolean enabled) {
        mIsVideoConfirmationEnabled = enabled;
    }

    public boolean isVideoConfirmationEnabled() {
        return mIsVideoConfirmationEnabled;
    }

    void showImageConfirmation(File file) {
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
        if (isImageConfirmationEnabled()) {
            mCameraModule.resume();
        }

        if (getOnImageCapturedListener() != null) {
            getOnImageCapturedListener().onFailure();
        }
    }

    void showVideoConfirmation(File file) {
        if (isVideoConfirmationEnabled()) {
            mCameraModule.pause();

            mVideoPreview.setVisibility(View.VISIBLE);
            mVideoPreview.setShouldMirror(isUsingFrontFacingCamera());
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
        if (isVideoConfirmationEnabled()) {
            mCameraModule.resume();
        }

        if (getOnVideoCapturedListener() != null) {
            getOnVideoCapturedListener().onFailure();
        }
    }

    public void setQuality(Quality quality) {
        mCameraModule.setQuality(quality);
    }

    public Quality getQuality() {
        return mCameraModule.getQuality();
    }

    public void setMaxVideoDuration(long duration) {
        mCameraModule.setMaxVideoDuration(duration);
    }

    public long getMaxVideoDuration() {
        return mCameraModule.getMaxVideoDuration();
    }

    public void setMaxVideoSize(long size) {
        mCameraModule.setMaxVideoSize(size);
    }

    public long getMaxVideoSize() {
        return mCameraModule.getMaxVideoSize();
    }

    protected void onOpen() {
        mCameraModule.open();
    }

    protected void onClose() {
        mCameraModule.close();

        // Destroy the TextureView we used for the previous round of camera activity. This is
        // because the TextureView will continue to show a bitmap of the old view until the camera
        // is able to draw to it again. We'd rather clear the TextureView, but since there's no such
        // way, we destroy it instead.
        removeView(mCameraView);
        addView(mCameraView = new TextureView(getContext()), 0);
        mCameraView.setSurfaceTextureListener(mSurfaceTextureListener);
    }

    public void takePicture(File file) {
        if (isImageConfirmationEnabled()) {
            mCameraModule.pause();
        }

        mCameraModule.takePicture(file);
    }

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

    public void startRecording(File file) {
        mCameraModule.startRecording(file);
    }

    public void stopRecording() {
        if (isVideoConfirmationEnabled()) {
            mCameraModule.pause();
        }

        mCameraModule.stopRecording();
    }

    public boolean isRecording() {
        return mCameraModule.isRecording();
    }

    public void confirmVideo() {
        if (isVideoConfirmationEnabled()) {
            mCameraModule.resume();
        }

        if (mVideoPendingConfirmation == null) {
            throw new IllegalStateException("confirmVideo() called, but no video was awaiting confirmation");
        }
        mVideoPreview.setVisibility(View.GONE);
        getOnVideoCapturedListener().onVideoCaptured(mVideoPendingConfirmation);
        mVideoPendingConfirmation = null;
    }

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

    public boolean hasFrontFacingCamera() {
        return mCameraModule.hasFrontFacingCamera();
    }

    public boolean isUsingFrontFacingCamera() {
        return mCameraModule.isUsingFrontFacingCamera();
    }

    public void toggleCamera() {
        mCameraModule.toggleCamera();
    }

    public void focus(Rect focus, Rect metering) {
        mCameraModule.focus(focus, metering);
    }

    public void setFlash(Flash flashMode) {
        mCameraModule.setFlash(flashMode);
    }

    public Flash getFlash() {
        return mCameraModule.getFlash();
    }

    public boolean hasFlash() {
        return mCameraModule.hasFlash();
    }

    protected int getRelativeCameraOrientation() {
        return mCameraModule.getRelativeCameraOrientation();
    }

    private long delta() {
        return System.currentTimeMillis() - mDownEventTimestamp;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Disable pinch-to-zoom while a preview is visible.
        if (mImagePendingConfirmation != null
                || mVideoPendingConfirmation != null
                || mCameraModule.isPaused()) {
            return false;
        }

        mScaleDetector.onTouchEvent(event);
        if (event.getPointerCount() == 2 && isPinchToZoomEnabled() && isZoomSupported()) {
            return true;
        }

        // Camera focus
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mDownEventTimestamp = System.currentTimeMillis();
                break;
            case MotionEvent.ACTION_UP:
                if (delta() < ViewConfiguration.getLongPressTimeout()) {
                    calculateTapArea(mFocusingRect, event.getX(), event.getY(), 1f);
                    calculateTapArea(mMeteringRect, event.getX(), event.getY(), 1.5f);
                    if (area(mFocusingRect) == 0 || area(mMeteringRect) == 0) {
                        break;
                    }
                    focus(mFocusingRect, mMeteringRect);
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
    private Rect calculateTapArea(Rect rect, float x, float y, float coefficient) {
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

        return rect;
    }

    private int rangeLimit(int val, int max, int min) {
        return Math.min(Math.max(val, min), max);
    }

    private int distance(int a, int b) {
        return Math.abs(a - b);
    }

    public void setPinchToZoomEnabled(boolean enabled) {
        mIsPinchToZoomEnabled = enabled;
    }

    public boolean isPinchToZoomEnabled() {
        return mIsPinchToZoomEnabled;
    }

    public void setZoomLevel(int zoomLevel) {
        mCameraModule.setZoomLevel(zoomLevel);
    }

    public int getZoomLevel() {
        return mCameraModule.getZoomLevel();
    }

    public int getMaxZoomLevel() {
        return mCameraModule.getMaxZoomLevel();
    }

    public boolean isZoomSupported() {
        return mCameraModule.isZoomSupported();
    }

    public void setOnImageCapturedListener(OnImageCapturedListener l) {
        mOnImageCapturedListener = l;
        mCameraModule.setOnImageCapturedListener(l);
    }

    protected OnImageCapturedListener getOnImageCapturedListener() {
        return mOnImageCapturedListener;
    }

    public void setOnVideoCapturedListener(OnVideoCapturedListener l) {
        mOnVideoCapturedListener = l;
        mCameraModule.setOnVideoCapturedListener(l);
    }

    protected OnVideoCapturedListener getOnVideoCapturedListener() {
        return mOnVideoCapturedListener;
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
