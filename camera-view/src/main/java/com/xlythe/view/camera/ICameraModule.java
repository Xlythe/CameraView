package com.xlythe.view.camera;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.location.Location;
import android.media.MediaActionSound;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.RawRes;
import androidx.annotation.RequiresApi;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.UiThread;

import com.xlythe.view.camera.stream.VideoRecorder;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.List;
import java.util.concurrent.TimeUnit;

public abstract class ICameraModule {
    public static final String TAG = CameraView.TAG;
    public static final boolean DEBUG = CameraView.DEBUG;
    /** Maximum wait for a backend to detach a custom encoder surface on the main thread. */
    private static final long SURFACE_DETACH_TIMEOUT_SECONDS = 2;

    /** The location role that says a video was recorded there, rather than that it shows it. */
    private static final String LOCATION_ROLE_SHOOTING = "0";

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
    /** Whether low-light boost is requested. */
    private boolean mLowLightBoostEnabled;
    /** Whether the camera's sounds are wanted. Set from any thread, read on the camera's. */
    private volatile boolean mShutterSoundEnabled = true;
    /** The click and recording tones, for a backend the platform plays none for. */
    private final CameraSounds mSounds = new CameraSounds();
    /** Listener receiving still-image terminal callbacks. */
    private CameraView.OnImageCapturedListener mOnImageCapturedListener;
    /** Listener receiving video terminal callbacks. */
    private CameraView.OnVideoCapturedListener mOnVideoCapturedListener;

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

    /** Receives preview frames as the original camera API delivers them. */
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    public interface PreviewFrameListener {
        /**
         * Called on the main thread with each preview frame.
         *
         * @param nv21 the frame, which the listener may keep
         * @param rotationDegrees the clockwise turn that stands the frame upright
         * @param frontFacing whether the frame came from a lens facing the user
         */
        @UiThread
        void onPreviewFrame(@NonNull byte[] nv21, int width, int height, int rotationDegrees,
                boolean frontFacing);
    }

    /**
     * Hands every preview frame to a listener as NV21 bytes, or stops when given null.
     *
     * <p>For a backend whose frames cannot reach an ImageReader, which is how a sweep reads the
     * original camera API. Backends built on camera2 hand out a surface instead, and say no.</p>
     *
     * @return whether frames will be delivered
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    protected boolean setPreviewFrameListener(@Nullable PreviewFrameListener listener) {
        return false;
    }

    @RequiresApi(18)
    public VideoRecorder.Canvas getCanvas() {
        return new VideoRecorder.Canvas() {
            @Override
            public void attachSurface(VideoRecorder.SurfaceProvider surfaceProvider) {
                new Handler(Looper.getMainLooper()).post(() -> ICameraModule.this.attachSurface(surfaceProvider));
            }

            @Override
            public void detachSurface(VideoRecorder.SurfaceProvider surfaceProvider) {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    ICameraModule.this.detachSurface(surfaceProvider);
                    return;
                }
                CountDownLatch detached = new CountDownLatch(1);
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        ICameraModule.this.detachSurface(surfaceProvider);
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

    /**
     * Shows a photo that wrote more than one file, as RAW_JPEG writes a JPEG and a DNG.
     *
     * @param file the file the photo is reported as
     * @param files every file the photo wrote, in the order they are filed in the gallery
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    public void showImageConfirmation(@NonNull File file, @NonNull List<File> files) {
        mView.showImageConfirmation(file, files);
    }

    public void onImageFailed() {
        mView.onImageFailed();
    }

    /** Called as a picture is exposed: the click plays, and the listener hears of it. */
    public void onShutter() {
        if (playsOwnClick()) {
            mSounds.play(getContext(), MediaActionSound.SHUTTER_CLICK, mShutterSoundEnabled);
        }
        mView.onShutter();
    }

    /**
     * Called just before the recorder starts. The tone plays first so that as little of it as
     * possible ends up in the recording.
     */
    public void onRecordingStarting() {
        if (!playsSystemSounds()) {
            mSounds.play(getContext(), MediaActionSound.START_VIDEO_RECORDING,
                    mShutterSoundEnabled);
        }
    }

    /** Called once the recorder has stopped, so the tone is not in the recording. */
    public void onRecordingStopped() {
        if (!playsSystemSounds()) {
            mSounds.play(getContext(), MediaActionSound.STOP_VIDEO_RECORDING,
                    mShutterSoundEnabled);
        }
    }

    /** Whether the platform plays this backend's camera sounds, as it does for the old API. */
    protected boolean playsSystemSounds() {
        return false;
    }

    /**
     * Whether the library plays the shutter click. A backend whose platform plays its own may
     * still hand the click over, to play the app's sound in its place.
     */
    protected boolean playsOwnClick() {
        return !playsSystemSounds();
    }

    /** Sets a sound of the app's own for the shutter, or 0 for the platform's click. */
    public void setShutterSound(@RawRes int soundRes) {
        mSounds.setShutterSound(soundRes);
    }

    @RawRes
    public int getShutterSound() {
        return mSounds.getShutterSound();
    }

    public void setShutterSoundEnabled(boolean enabled) {
        mShutterSoundEnabled = enabled;
    }

    public boolean isShutterSoundEnabled() {
        return mShutterSoundEnabled;
    }

    /** Loads the sounds as the camera opens, so the first picture's click is not late. */
    void loadSounds() {
        if (playsOwnClick() && (mShutterSoundEnabled || CameraSounds.isRequired())) {
            mSounds.load(getContext());
        }
    }

    void releaseSounds() {
        mSounds.release();
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
        attachVideoMetadata(file);
        mView.showVideoConfirmation(file);
    }

    /**
     * Writes which device recorded the video, when, and where if the app may know, which
     * MediaRecorder leaves out, as both user data and keyed metadata. Done before the caller is
     * told, so it is handed a finished file.
     */
    private void attachVideoMetadata(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        long now = System.currentTimeMillis();
        VideoMetadata.Builder metadata = new VideoMetadata.Builder()
                .set(VideoMetadata.Tag.MAKE, Build.MANUFACTURER)
                .set(VideoMetadata.Tag.MODEL, Build.MODEL)
                .set(VideoMetadata.Tag.SOFTWARE, Exif.softwareName(getContext()))
                .set(VideoMetadata.Tag.CREATION_DATE, isoDate(now))
                .set(VideoMetadata.Tag.YEAR, year(now));
        Location location = getLocation();
        if (location != null) {
            metadata.setLocation(location.getLatitude(), location.getLongitude())
                    .set(VideoMetadata.Tag.LOCATION_ROLE, LOCATION_ROLE_SHOOTING);
            if (location.getTime() > 0) {
                metadata.set(VideoMetadata.Tag.LOCATION_DATE, isoDate(location.getTime()));
            }
        }
        VideoMetadata.write(file, metadata);
    }

    /** ISO 8601, which is what a reader expects to find in the creation-date box. */
    private static String isoDate(long timestamp) {
        SimpleDateFormat format =
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US);
        return format.format(new Date(timestamp));
    }

    /** The year, in the timezone the recording was made in. */
    private static String year(long timestamp) {
        return new SimpleDateFormat("yyyy", Locale.US).format(new Date(timestamp));
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

    /**
     * Zooms to a ratio in the camera's own terms, where 1 is the main lens, held to
     * {@link #getMinZoomRatio} and {@link #getMaxZoomRatio}. A backend calls
     * {@link #onZoomChanged} whenever the ratio it answers {@link #getZoomRatio} with changes.
     */
    public abstract void setZoomRatio(float zoomRatio);

    public abstract float getZoomRatio();

    /** Below 1 on a camera that can switch to an ultra-wide lens, and otherwise 1. */
    public abstract float getMinZoomRatio();

    /** 1 on a camera that cannot zoom. */
    public abstract float getMaxZoomRatio();

    /**
     * The exact ratios worth a button, in increasing order. See {@link ZoomMath#presets}.
     * CameraView labels them.
     */
    @NonNull
    public abstract float[] getZoomPresets();

    /**
     * Zooms to a position in the camera's range, from 0 to {@link #getMaxZoomLevel}.
     *
     * <p>Worked out from the ratio here rather than by each backend, so that a level is the same
     * zoom on all of them.</p>
     */
    public final void setZoomLevel(int zoomLevel) {
        setZoomRatio(ZoomMath.ratioForLevel(zoomLevel, getMinZoomRatio(), getMaxZoomRatio()));
    }

    public final int getZoomLevel() {
        return ZoomMath.levelForRatio(getZoomRatio(), getMinZoomRatio(), getMaxZoomRatio());
    }

    public final int getMaxZoomLevel() {
        return ZoomMath.MAX_ZOOM_LEVEL;
    }

    public final boolean isZoomSupported() {
        return getMaxZoomRatio() > getMinZoomRatio();
    }

    /** Tells the view the zoom is now at this ratio. */
    protected final void onZoomChanged(float zoomRatio) {
        onMainThread(() -> getView().onZoomChanged(zoomRatio));
    }

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

    /** How many EV one exposure compensation index is worth, or 0 where there is none. */
    public synchronized float getExposureCompensationStep() {
        return 0f;
    }

    public synchronized boolean isExposureCompensationSupported() {
        return false;
    }

    /**
     * Turns low-light boost off when the torch goes on, on every backend: each needs the
     * auto-exposure mode to itself.
     */
    public synchronized void setTorchEnabled(boolean enabled) {
        mTorchEnabled = enabled;
        if (enabled) {
            mLowLightBoostEnabled = false;
        }
    }

    public synchronized boolean isTorchEnabled() {
        return mTorchEnabled;
    }

    /** Turns the torch off when low-light boost goes on, for the same reason. */
    public synchronized void setLowLightBoostEnabled(boolean enabled) {
        mLowLightBoostEnabled = enabled;
        if (enabled) {
            mTorchEnabled = false;
        }
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
