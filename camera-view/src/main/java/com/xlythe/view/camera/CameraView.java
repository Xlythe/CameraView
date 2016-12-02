package com.xlythe.view.camera;

import android.Manifest;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.ParcelFileDescriptor;
import android.support.annotation.RequiresPermission;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.ViewConfiguration;
import android.view.WindowManager;

import com.xlythe.view.camera.legacy.LegacyCameraModule;
import com.xlythe.view.camera.v2.Camera2Module;

import java.io.File;

public class CameraView extends TextureView {
    static final String TAG = CameraView.class.getSimpleName();
    static final boolean DEBUG = true;

    private enum Status {
        OPEN, CLOSED, AWAITING_TEXTURE
    }

    public enum Quality {
        HIGH(0), MEDIUM(1), LOW(2);

        int id;

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
     * {@link SurfaceTextureListener} handles several lifecycle events on a
     * {@link TextureView}.
     */
    private final SurfaceTextureListener mSurfaceTextureListener = new SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            Log.v(TAG, "Surface Texture now available.");
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
    private final Rect mFocusingRect = new Rect();
    private final Rect mMeteringRect = new Rect();

    private final ICameraModule mCameraModule;

    public CameraView(Context context) {
        this(context, null);
    }

    public CameraView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CameraView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        setSurfaceTextureListener(mSurfaceTextureListener);
        if (android.os.Build.VERSION.SDK_INT >= 21 && Camera2Module.READY) {
            mCameraModule = new Camera2Module(this);
        } else {
           mCameraModule = new LegacyCameraModule(this);
        }

        if (attrs != null) {
            final TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.CameraView, 0, 0);
            setQuality(Quality.fromId(a.getInteger(R.styleable.CameraView_quality, getQuality().id)));
            setMaxVideoDuration(a.getInteger(R.styleable.CameraView_maxVideoDuration, getMaxVideoDuration()));
            setMaxVideoSize(a.getInteger(R.styleable.CameraView_maxVideoSize, getMaxVideoSize()));
            a.recycle();
        }
    }

    @TargetApi(21)
    public CameraView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        setSurfaceTextureListener(mSurfaceTextureListener);
        if (android.os.Build.VERSION.SDK_INT >= 21 && Camera2Module.READY) {
            mCameraModule = new Camera2Module(this);
        } else {
            mCameraModule = new LegacyCameraModule(this);
        }
    }

    public synchronized Status getStatus() {
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
        if (isAvailable()) {
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

    protected int getDisplayRotation() {
        Display display;
        if (android.os.Build.VERSION.SDK_INT >= 17) {
            display = getDisplay();
        } else {
            display = ((WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        }
        return display.getRotation();
    }

    public void setQuality(Quality quality) {
        mCameraModule.setQuality(quality);
    }

    public Quality getQuality() {
        return mCameraModule.getQuality();
    }

    public void setMaxVideoDuration(int duration) {
        mCameraModule.setMaxVideoDuration(duration);
    }

    public int getMaxVideoDuration() {
        return mCameraModule.getMaxVideoDuration();
    }

    public void setMaxVideoSize(int size) {
        mCameraModule.setMaxVideoSize(size);
    }

    public int getMaxVideoSize() {
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

    public void startRecording(File file) {
        mCameraModule.startRecording(file);
    }

    public void stopRecording() {
        mCameraModule.stopRecording();
    }

    public boolean isRecording() {
        return mCameraModule.isRecording();
    }

    @TargetApi(21)
    public void startStreaming(ParcelFileDescriptor pfd) {
        mCameraModule.startStreaming(pfd);
    }

    @TargetApi(21)
    public void stopStreaming() {
        mCameraModule.stopStreaming();
    }

    @TargetApi(21)
    public boolean isStreaming() {
        return mCameraModule.isStreaming();
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

    private long start;

    private long delta() {
        return System.currentTimeMillis() - start;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                start = System.currentTimeMillis();
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
        x = -1000 + 2000 * (x / width);
        y = -1000 + 2000 * (y / height);


        // Modify the rect to the bounding area
        rect.top = (int) y - areaSize / 2;
        rect.left = (int) x - areaSize / 2;
        rect.bottom = rect.top + areaSize;
        rect.right = rect.left + areaSize;

        // Cap at -1000 to 1000
        rect.top = rangeLimit(rect.top);
        rect.left = rangeLimit(rect.left);
        rect.bottom = rangeLimit(rect.bottom);
        rect.right = rangeLimit(rect.right);

        return rect;
    }

    private int rangeLimit(int val) {
        int floor = Math.max(val, -1000);
        int ceiling = Math.min(floor, 1000);
        return ceiling;
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
        void onImageCaptured(File file);
        void onFailure();
    }

    public interface OnVideoCapturedListener {
        void onVideoCaptured(File file);
        void onFailure();
    }
}
