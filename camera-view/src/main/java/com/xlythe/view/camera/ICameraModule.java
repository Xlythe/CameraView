package com.xlythe.view.camera;

import android.Manifest;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.location.Location;
import android.media.Image;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.util.Log;
import android.util.Size;

import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.RequiresApi;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;
import androidx.annotation.RestrictTo;

import com.xlythe.view.camera.stream.VideoRecorder;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.List;
import java.util.concurrent.TimeUnit;

public abstract class ICameraModule {
    public static final String TAG = CameraView.TAG;
    public static final boolean DEBUG = CameraView.DEBUG;
    /**
     * The largest frame the subject tracker asks for. It averages anything larger down to a few
     * hundred pixels before reading it, so a bigger frame only costs the camera bandwidth.
     */
    private static final int LUMA_FRAME_MAX_WIDTH = 640;
    /** See {@link #LUMA_FRAME_MAX_WIDTH}. */
    private static final int LUMA_FRAME_MAX_HEIGHT = 480;
    /** Maximum wait for a backend to detach a custom encoder surface on the main thread. */
    private static final long SURFACE_DETACH_TIMEOUT_SECONDS = 2;

    /** Owning CameraView used for view state and callbacks. */
    private final CameraView mView;
    /** Requested image and video quality. */
    private CameraView.Quality mQuality = CameraView.Quality.HIGH;

    /** JPEG compression for still captures. See {@link #setPictureQuality}. */
    private int mPictureQuality = DEFAULT_PICTURE_QUALITY;
    /** Requested maximum video duration in milliseconds. */
    private long mMaxVideoDuration = CameraView.INDEFINITE_VIDEO_DURATION;
    /** Requested maximum video size in bytes. */
    private long mMaxVideoSize = CameraView.INDEFINITE_VIDEO_SIZE;
    /** Requested still-image flash behavior. */
    private CameraView.Flash mFlash = CameraView.Flash.AUTO;
    /** Requested capture dynamic range. */
    private CameraView.DynamicRange mDynamicRange = CameraView.DynamicRange.SDR;
    /** Requested CameraX photo extension. */
    private CameraView.Extension mExtension = CameraView.Extension.NONE;
    /** Whether a steadied picture is requested, of the preview and of a recording alike. */
    private boolean mStabilizationEnabled;
    /** Requested exposure compensation index. */
    private int mExposureCompensation;
    /** Whether continuous torch illumination is requested. */
    private boolean mTorchEnabled;
    /** Whether CameraX low-light boost is requested. */
    private boolean mLowLightBoostEnabled;
    /** Listener receiving still-image terminal callbacks. */
    private CameraView.OnImageCapturedListener mOnImageCapturedListener;
    /** Listener receiving video terminal callbacks. */
    private CameraView.OnVideoCapturedListener mOnVideoCapturedListener;

    /**
     * The frames {@link #startLumaFrames} reads, while they run. Held as a Closeable because
     * FrameStream needs API 19 and this class must still load below it.
     */
    @Nullable
    private Closeable mLumaFrames;

    /** Whether pictures and videos should match the preview aspect ratio. */
    private boolean mMatchPreviewAspectRatio = true;

    public ICameraModule(CameraView view) {
        mView = view;
    }

    public Context getContext() {
        return mView.getContext();
    }

    public CameraView getView() {
        return mView;
    }

    public int getWidth() {
        return mView.getWidth();
    }

    public int getHeight() {
        return mView.getHeight();
    }

    /**
     * Where to tag a capture, or null when that is unknown or the app holds no location
     * permission.
     *
     * <p>Never waits for a fix, so a capture can ask from whichever thread it finishes on.</p>
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    @AnyThread
    @Nullable
    public final Location getLocation() {
        return mView.getLocationTracker().getLocation();
    }

    protected void attachSurface(VideoRecorder.SurfaceProvider surfaceProvider) {

    }

    protected void detachSurface(VideoRecorder.SurfaceProvider surfaceProvider) {

    }

    protected boolean startBarcodeScanner(
            CameraView.BarcodeDetectorListener listener,
            @Barcode.Format int format,
            @Barcode.Format int... formats) {
        return false;
    }

    protected void stopBarcodeScanner() {}

    /**
     * Attaches a surface whose frames are only read, never encoded or shown.
     *
     * <p>Separate from {@link #attachSurface} so that a backend can carry it beside a still
     * capture rather than in place of one, as the subject tracker needs: tracking a subject and
     * then photographing it is the point. A backend that makes no such distinction attaches it
     * like any other surface.</p>
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    protected void attachAnalysisSurface(VideoRecorder.SurfaceProvider surfaceProvider) {
        attachSurface(surfaceProvider);
    }

    /** Detaches a surface attached by {@link #attachAnalysisSurface}. */
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    protected void detachAnalysisSurface(VideoRecorder.SurfaceProvider surfaceProvider) {
        detachSurface(surfaceProvider);
    }

    @RequiresApi(18)
    public VideoRecorder.Canvas getCanvas() {
        return createCanvas(this::attachSurface, this::detachSurface);
    }

    /** A canvas for a surface that only reads frames. See {@link #attachAnalysisSurface}. */
    @RequiresApi(18)
    private VideoRecorder.Canvas getAnalysisCanvas() {
        return createCanvas(this::attachAnalysisSurface, this::detachAnalysisSurface);
    }

    /** Something done to a surface provider on the main thread. */
    private interface SurfaceAction {
        void run(VideoRecorder.SurfaceProvider surfaceProvider);
    }

    /** A canvas that attaches and detaches on the main thread, where every backend expects it. */
    @RequiresApi(18)
    private VideoRecorder.Canvas createCanvas(
            @NonNull SurfaceAction attach, @NonNull SurfaceAction detach) {
        return new VideoRecorder.Canvas() {
            @Override
            public void attachSurface(VideoRecorder.SurfaceProvider surfaceProvider) {
                new Handler(Looper.getMainLooper()).post(() -> attach.run(surfaceProvider));
            }

            @Override
            public void detachSurface(VideoRecorder.SurfaceProvider surfaceProvider) {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    detach.run(surfaceProvider);
                    return;
                }
                CountDownLatch detached = new CountDownLatch(1);
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        detach.run(surfaceProvider);
                    } finally {
                        detached.countDown();
                    }
                });
                try {
                    if (!detached.await(SURFACE_DETACH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        Log.w(TAG, "Timed out detaching custom camera surface");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    Log.w(TAG, "Interrupted while detaching custom camera surface", error);
                }
            }
        };
    }

    public int getDisplayRotation() {
        return mView.getDisplayRotation();
    }

    public SurfaceTexture getSurfaceTexture() {
        return mView.getSurfaceTexture();
    }

    protected Matrix getTransform(Matrix matrix) {
        return mView.getTransform(matrix);
    }

    protected void setTransform(Matrix matrix) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mView.post(() -> setTransform(matrix));
        } else {
            mView.setTransform(matrix);
        }
    }

    /*
     * Opens the camera and starts displaying a preview. You are in charge of checking if the
     * phone has PackageManager.FEATURE_CAMERA_ANY and, if you are targeting Android M+, that the
     * phone has granted Manifest.permission.CAMERA. The microphone belongs to
     * startRecording(File), not here.
     */
    public abstract void open();

    /*
     * Closes the camera.
     */
    public abstract void close();

    /**
     * Takes a picture. Set a OnImageCapturedListener to be
     * notified of when the picture has finished saving.
     */
    public abstract void takePicture(File file);

    /**
     * Informs the CameraView to pause and show the taken photo
     */
    public void showImageConfirmation(File file) {
        mView.showImageConfirmation(file);
    }

    public void onImageFailed() {
        mView.onImageFailed();
    }

    /**
     * Records a video. Set a OnVideoCapturedListener to be notified of when
     * the video has finished saving.
     */
    public abstract void startRecording(File file);

    /**
     * Stops recording the video. It's recommended that you set a timeout when recording to avoid
     * excessively large files.
     */
    public abstract void stopRecording();

    /**
     * Returns true if recording.
     */
    public abstract boolean isRecording();

    /**
     * Informs the CameraView to pause and show the taken video
     */
    public void showVideoConfirmation(File file) {
        mView.showVideoConfirmation(file);
    }

    public void onVideoFailed() {
        mView.onVideoFailed();
    }

    protected void onCameraError(Throwable error) {
        mView.onCameraError(error);
    }

    public abstract void setLensFacing(CameraView.LensFacing lensFacing);

    public abstract void toggleCamera();

    public abstract boolean hasFrontFacingCamera();

    public abstract boolean isUsingFrontFacingCamera();

    public abstract void focus(Rect focus, Rect metering);

    public abstract void setZoomLevel(int zoomLevel);

    public abstract int getZoomLevel();

    public abstract int getMaxZoomLevel();

    public abstract boolean isZoomSupported();

    protected abstract int getRelativeCameraOrientation();

    public void setQuality(CameraView.Quality quality) {
        mQuality = quality;
    }

    /** The JPEG quality every backend is asked for unless the app says otherwise. */
    public static final int DEFAULT_PICTURE_QUALITY = 95;

    /**
     * How hard a still capture is compressed, from 1 to 100.
     *
     * <p>Left alone, every backend takes whatever the device chose, which is not the same number
     * on any two phones and on some is low enough to show. 95 is where a JPEG stops being
     * visibly different from the uncompressed frame while still being a fraction of the size of
     * one encoded at 100, and it is what the manufacturers' own apps settle around.</p>
     */
    public void setPictureQuality(int percent) {
        mPictureQuality = Math.max(1, Math.min(percent, 100));
    }

    public int getPictureQuality() {
        return mPictureQuality;
    }

    public CameraView.Quality getQuality() {
        return mQuality;
    }

    public void setMaxVideoDuration(long duration) {
        mMaxVideoDuration = duration;
    }

    public long getMaxVideoDuration() {
        return mMaxVideoDuration;
    }

    public void setMaxVideoSize(long size) {
        mMaxVideoSize = size;
    }

    public long getMaxVideoSize() {
        return mMaxVideoSize;
    }

    public void setFlash(CameraView.Flash flash) {
        mFlash = flash;
    }

    public CameraView.Flash getFlash() {
        return mFlash;
    }

    public synchronized void setDynamicRange(CameraView.DynamicRange dynamicRange) {
        if (dynamicRange == null) throw new NullPointerException("dynamicRange cannot be null");
        mDynamicRange = dynamicRange;
    }

    public synchronized CameraView.DynamicRange getDynamicRange() {
        return mDynamicRange;
    }

    public synchronized boolean isDynamicRangeSupported(CameraView.DynamicRange dynamicRange) {
        return dynamicRange == CameraView.DynamicRange.SDR;
    }

    public synchronized void setExtension(CameraView.Extension extension) {
        if (extension == null) throw new NullPointerException("extension cannot be null");
        mExtension = extension;
    }

    public synchronized CameraView.Extension getExtension() {
        return mExtension;
    }

    public synchronized boolean isExtensionSupported(CameraView.Extension extension) {
        return extension == CameraView.Extension.NONE;
    }

    /**
     * Whether a recording from the user-facing lens is written mirrored, as its preview shows it.
     * False where the backend cannot yet flip the frames it encodes.
     */
    public boolean isFrontVideoMirrored() {
        return false;
    }

    public synchronized void setStabilizationEnabled(boolean enabled) {
        mStabilizationEnabled = enabled;
    }

    public synchronized boolean isStabilizationEnabled() {
        return mStabilizationEnabled;
    }

    public synchronized boolean isStabilizationSupported() {
        return false;
    }

    public synchronized void setExposureCompensation(int exposureCompensation) {
        mExposureCompensation = exposureCompensation;
    }

    public synchronized int getExposureCompensation() {
        return mExposureCompensation;
    }

    public synchronized int getMinExposureCompensation() {
        return 0;
    }

    public synchronized int getMaxExposureCompensation() {
        return 0;
    }

    public synchronized boolean isExposureCompensationSupported() {
        return false;
    }

    public synchronized void setTorchEnabled(boolean enabled) {
        mTorchEnabled = enabled;
    }

    public synchronized boolean isTorchEnabled() {
        return mTorchEnabled;
    }

    public synchronized void setLowLightBoostEnabled(boolean enabled) {
        mLowLightBoostEnabled = enabled;
    }

    public synchronized boolean isLowLightBoostEnabled() {
        return mLowLightBoostEnabled;
    }

    public synchronized boolean isLowLightBoostSupported() {
        return false;
    }

    /** The colour range pictures are recorded in. See {@link CameraView.ColorSpace}. */
    private CameraView.ColorSpace mColorSpace = CameraView.ColorSpace.SRGB;

    /** The file a picture is written as. See {@link CameraView.PictureFormat}. */
    private CameraView.PictureFormat mPictureFormat = CameraView.PictureFormat.JPEG;

    /**
     * Whether this camera can record in a given colour range.
     *
     * <p>sRGB is always supported, because it is what a camera that has never heard of colour
     * spaces produces.</p>
     */
    public boolean isColorSpaceSupported(CameraView.ColorSpace colorSpace) {
        return colorSpace == CameraView.ColorSpace.SRGB;
    }

    public void setColorSpace(CameraView.ColorSpace colorSpace) {
        if (!isColorSpaceSupported(colorSpace)) {
            Log.w(TAG, "This camera cannot record in " + colorSpace);
            return;
        }
        mColorSpace = colorSpace;
    }

    public CameraView.ColorSpace getColorSpace() {
        return mColorSpace;
    }

    /** Whether this camera can write pictures in a given format. JPEG it always can. */
    public boolean isPictureFormatSupported(CameraView.PictureFormat format) {
        return format == CameraView.PictureFormat.JPEG;
    }

    public void setPictureFormat(CameraView.PictureFormat format) {
        if (!isPictureFormatSupported(format)) {
            Log.w(TAG, "This camera cannot write " + format);
            return;
        }
        mPictureFormat = format;
    }

    public CameraView.PictureFormat getPictureFormat() {
        return mPictureFormat;
    }

    /**
     * Whether a picture can be taken while a recording is running.
     *
     * <p>Not every camera can hold a preview, a recording and a still at once; a LEGACY camera2
     * device guarantees nothing of the sort. Where it cannot, recording keeps working and the
     * picture is refused rather than the whole session being lost.</p>
     */
    public boolean isSnapshotWhileRecordingSupported() {
        return false;
    }

    /**
     * Whether the camera is configured and can be used right now.
     *
     * <p>A backend that opens synchronously is ready as soon as open() returns and says so here.
     * One that opens a device and configures a session - which is both of the modern ones - is
     * not, and reports through {@link #onCameraReady()} when it is. The difference matters
     * because it is what CameraView tells an app to enable its shutter button on.</p>
     */
    public boolean isCameraReady() {
        return true;
    }

    /** Tells the view the camera can be used, which promotes it to open. */
    protected final void onCameraReady() {
        getView().onCameraReady();
    }

    /**
     * The horizontal angle of view, in degrees, of the camera as it is currently configured.
     *
     * <p>Zero when the camera does not publish enough to work it out. Includes the effect of
     * zoom, because a zoomed camera sees less.</p>
     */
    public float getHorizontalFieldOfView() {
        return 0f;
    }

    /** The vertical angle of view, on the same terms as {@link #getHorizontalFieldOfView}. */
    public float getVerticalFieldOfView() {
        return 0f;
    }

    /**
     * Whether this backend can deliver frames to an analyzer.
     *
     * <p>True for the backends that can hand out a camera output surface, which is every one
     * built on camera2. The original Camera API cannot: it reports preview frames as bytes to a
     * callback, and the only way this library has to forward them is to draw them onto a
     * surface, which an ImageReader expecting YUV planes cannot accept.</p>
     */
    public boolean isFrameAnalysisSupported() {
        return true;
    }

    /** Whether this camera can report the faces it is already tracking. */
    public boolean isFaceDetectionSupported() {
        return false;
    }

    /**
     * Asks the backend to start or stop reporting faces.
     *
     * @return whether the backend is now in the requested state. A backend with no face detection
     *     refuses to start, which CameraView reports to the caller rather than leaving a listener
     *     that never fires; stopping always succeeds.
     */
    protected boolean setFaceDetectionEnabled(boolean enabled) {
        return !enabled;
    }

    /**
     * Reports the faces the camera is tracking.
     *
     * @param faceToView maps a face's bounds onto the view, since the camera's coordinates are
     *     neither the view's nor the same across backends
     */
    protected final void onFacesDetected(
            @NonNull List<Face> faces, @NonNull Matrix faceToView) {
        onMainThread(() -> getView().onFacesDetected(faces, faceToView));
    }

    /** Whether this camera can be told what to focus on. */
    public boolean isFocusSupported() {
        return false;
    }

    /**
     * Reports a focus request the camera accepted.
     *
     * <p>Called by a backend once it has handed the request to the camera, rather than when one
     * arrives, so that a request a camera refused is never reported as made.</p>
     *
     * @param focusArea the area asked for, in the space {@link #focus} takes it in
     */
    protected final void onFocusRequested(@NonNull Rect focusArea) {
        // Translated here rather than in the view, because the orientation that relates the two
        // coordinate spaces is the backend's to answer and can change with the lens.
        RectF onView = TransformMath.viewArea(
                focusArea, getWidth(), getHeight(), getRelativeCameraOrientation());
        onMainThread(() -> getView().onFocusRequested(onView));
    }

    /**
     * Reports what the camera made of the request last announced by {@link #onFocusRequested}.
     *
     * @param locked whether the camera reported that it converged
     */
    protected final void onFocusCompleted(boolean locked) {
        onMainThread(() -> getView().onFocusCompleted(locked));
    }

    /**
     * Whether this camera can follow a subject: it needs preview frames to find the subject in,
     * and an area to focus or meter on to keep it sharp and exposed.
     */
    public boolean isSubjectTrackingSupported() {
        return false;
    }

    /**
     * Starts handing the subject tracker each preview frame's brightness, on a background thread.
     *
     * <p>Frames come from frame analysis by default, attached as a surface that is only read so
     * that a backend can keep its still capture alongside it. A backend whose frames reach it some
     * other way overrides this.</p>
     *
     * @return whether frames will arrive
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    @RequiresPermission(Manifest.permission.CAMERA)
    @MainThread
    protected boolean startLumaFrames(@NonNull LumaFrame.Listener listener) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP || !isFrameAnalysisSupported()) {
            return false;
        }
        stopLumaFrames();
        FrameStream frames = new FrameStream(getAnalysisCanvas(), getView(),
                new FrameStream.Params().setMaxSize(new Size(
                        LUMA_FRAME_MAX_WIDTH, LUMA_FRAME_MAX_HEIGHT)),
                frame -> {
                    Image.Plane luma = frame.getImage().getPlanes()[0];
                    listener.onFrame(new LumaFrame(luma.getBuffer(), frame.getWidth(),
                            frame.getHeight(), luma.getRowStride(), frame.getRotationDegrees(),
                            frame.isMirrored()));
                });
        mLumaFrames = frames;
        frames.start();
        return true;
    }

    /** Stops the frames started by {@link #startLumaFrames}. */
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    @MainThread
    protected void stopLumaFrames() {
        Closeable frames = mLumaFrames;
        mLumaFrames = null;
        if (frames != null) {
            try {
                frames.close();
            } catch (IOException error) {
                Log.w(TAG, "Unable to stop the subject tracker's frames", error);
            }
        }
    }

    /**
     * Keeps focus and exposure on an area that moves, in the space {@link #focus} takes it in.
     *
     * <p>Unlike {@link #focus}, nothing is announced: the area follows a subject many times a
     * second, and the app hears about the subject instead. Null hands the choice back to the
     * camera.</p>
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    @MainThread
    protected void setSubjectArea(@Nullable Rect area) {}

    /**
     * Runs something on the main thread, where every listener is documented to arrive.
     *
     * <p>Backends read results on whichever thread the camera delivers them on. Posting through a
     * Handler rather than View.post matters: a detached View queues its runnables until it is
     * attached again, which for a camera that is closing means never.</p>
     */
    private void onMainThread(@NonNull Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
            return;
        }
        new Handler(Looper.getMainLooper()).post(action);
    }

    public boolean hasFlash() {
        return false;
    }

    public void setMatchPreviewAspectRatio(boolean enabled) {
        mMatchPreviewAspectRatio = enabled;
    }

    public boolean isMatchPreviewAspectRatioEnabled() {
        return mMatchPreviewAspectRatio;
    }

    public void pause() {}

    public void resume() {}

    public boolean isPaused() {
        return false;
    }

    public Parcelable onSaveInstanceState() {
        return null;
    }

    public void onRestoreInstanceState(Parcelable savedState) {}

    /**
     * Re-aims the preview at the view's new bounds.
     *
     * <p>Called for every layout pass that changed the size, so it has to be cheap: adjusting a
     * transform, not reconfiguring a camera. Anything that costs a session belongs in
     * {@link #onLayoutSettled}.</p>
     */
    public void onLayoutChanged() {}

    /**
     * Called once the view's size has stopped changing, when reconfiguring is worth its cost.
     *
     * <p>A rotation, a window being dragged to a new size, a foldable opening and a Compose
     * layout arriving at its answer all produce a run of layout passes at different sizes.</p>
     */
    public void onLayoutSettled() {}

    public void setOnImageCapturedListener(CameraView.OnImageCapturedListener l) {
        mOnImageCapturedListener = l;
    }

    public CameraView.OnImageCapturedListener getOnImageCapturedListener() {
        return mOnImageCapturedListener;
    }

    public void setOnVideoCapturedListener(CameraView.OnVideoCapturedListener l) {
        mOnVideoCapturedListener = l;
    }

    public CameraView.OnVideoCapturedListener getOnVideoCapturedListener() {
        return mOnVideoCapturedListener;
    }

    public static int getRelativeImageOrientation(int displayRotation, int sensorOrientation, boolean isFrontFacing, boolean compensateForMirroring) {
        int result;
        if (isFrontFacing) {
            result = (sensorOrientation + displayRotation) % 360;
            if (compensateForMirroring) {
                result = (360 - result) % 360;
            }
        } else {
            result = (sensorOrientation - displayRotation + 360) % 360;
        }
        if (DEBUG) {
            Log.d(TAG, String.format("getRelativeImageOrientation: displayRotation=%s, "
                            + "sensorOrientation=%s, isFrontFacing=%s, compensateForMirroring=%s, "
                            + "result=%s",
                    displayRotation, sensorOrientation, isFrontFacing, compensateForMirroring, result));
        }
        return result;
    }
}
