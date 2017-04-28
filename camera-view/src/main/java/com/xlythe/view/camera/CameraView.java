package com.xlythe.view.camera;

import android.Manifest;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Build;
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

import com.squareup.picasso.Picasso;
import com.xlythe.view.camera.legacy.LegacyCameraModule;
import com.xlythe.view.camera.v2.Camera2Module;

import java.io.File;

public class CameraView extends FrameLayout {
    static final String TAG = CameraView.class.getSimpleName();
    static final boolean DEBUG = false;

    public static final int INDEFINITE_VIDEO_DURATION = -1;
    public static final int INDEFINITE_VIDEO_SIZE = -1;

    private enum Status {
        OPEN, CLOSED, AWAITING_TEXTURE
    }

    public enum Quality {
        HIGH(0), MEDIUM(1), LOW(2);

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
        ON, OFF, AUTO;
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
    private ScaleGestureDetector mScaleDetector;

    private ICameraModule mCameraModule;

    private TextureView mCameraView;
    private ImageView mImagePreview;
    private VideoView mVideoPreview;

    private File mImagePendingConfirmation;
    private File mVideoPendingConfirmation;

    private boolean mIsImageConfirmationEnabled = false;
    private boolean mIsVideoConfirmationEnabled = false;

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
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.CameraView, 0, 0);
            setQuality(Quality.fromId(a.getInteger(R.styleable.CameraView_quality, getQuality().id)));
            if (a.hasValue(R.styleable.CameraView_maxVideoDuration)) {
                setMaxVideoDuration(a.getInteger(R.styleable.CameraView_maxVideoDuration, INDEFINITE_VIDEO_DURATION));
            }
            if (a.hasValue(R.styleable.CameraView_maxVideoSize)) {
                setMaxVideoSize(a.getInteger(R.styleable.CameraView_maxVideoSize, INDEFINITE_VIDEO_SIZE));
            }
            a.recycle();
        }

        mScaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private final float MAX_SCALE = 5f;
            private float mScaleFactor = 1f;

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                mScaleFactor *= detector.getScaleFactor();

                // Don't let the object get too small or too large.
                mScaleFactor = Math.max(1f, Math.min(mScaleFactor, MAX_SCALE));

                // y = (x-1) * (maxZoom/maxScale)
                setZoomLevel(rangeLimit((int) ((mScaleFactor-1) * getMaxZoomLevel() / MAX_SCALE), getMaxZoomLevel(), 0));
                return true;
            }
        });
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        addView(mCameraView = new TextureView(getContext()));
        addView(mImagePreview = new ImageView(getContext()));
        addView(mVideoPreview = new VideoView(getContext()));
        mImagePreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mImagePreview.setVisibility(View.GONE);

        mCameraView.setSurfaceTextureListener(mSurfaceTextureListener);
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
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

    public void setVideoConfirmationEnabled(boolean enabled) {
        mIsVideoConfirmationEnabled = enabled;
    }

    void showImageConfirmation(File file) {
        if (mIsImageConfirmationEnabled) {
            mImagePreview.setVisibility(View.VISIBLE);
            Picasso.with(getContext()).load(file).into(mImagePreview);
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

    void showVideoConfirmation(final File file) {
        if (mIsVideoConfirmationEnabled) {
            mVideoPreview.setVisibility(View.VISIBLE);
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
    }

    public void takePicture(File file) {
        mCameraModule.takePicture(file);
    }

    public void confirmPicture() {
        if (mImagePendingConfirmation == null) {
            throw new IllegalStateException("confirmPicture() called, but no picture was awaiting confirmation");
        }
        mImagePreview.setVisibility(View.GONE);
        getOnImageCapturedListener().onImageCaptured(mImagePendingConfirmation);
        mImagePendingConfirmation = null;
    }

    public void rejectPicture() {
        if (mImagePendingConfirmation == null) {
            throw new IllegalStateException("rejectPicture() called, but no picture was awaiting confirmation");
        }
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
        mCameraModule.stopRecording();
    }

    public boolean isRecording() {
        return mCameraModule.isRecording();
    }

    public void confirmVideo() {
        if (mVideoPendingConfirmation == null) {
            throw new IllegalStateException("confirmVideo() called, but no video was awaiting confirmation");
        }
        mVideoPreview.setVisibility(View.GONE);
        getOnVideoCapturedListener().onVideoCaptured(mVideoPendingConfirmation);
        mVideoPendingConfirmation = null;
    }

    public void rejectVideo() {
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
        mScaleDetector.onTouchEvent(event);
        if (event.getPointerCount() == 2 && isZoomSupported()) {
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
                // Fall through
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
                // Fall through
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
}
