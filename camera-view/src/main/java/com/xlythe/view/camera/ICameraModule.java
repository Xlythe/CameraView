package com.xlythe.view.camera;

import android.Manifest.permission;
import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xlythe.view.camera.gl.CameraTextureProvider;
import com.xlythe.view.camera.gl.FrameRenderer;
import com.xlythe.view.camera.stream.VideoRecorder;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

public abstract class ICameraModule {
    public static final String TAG = CameraView.TAG;
    public static final boolean DEBUG = CameraView.DEBUG;
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
    /** Whether front-camera video output should be mirrored. */
    private boolean mFrontVideoMirroringEnabled = true;

    /**
     * Whether a picture from a user-facing lens is mirrored, as the preview of it was.
     *
     * <p>True, which is what the three backends did unconditionally before there was anything to
     * ask. It is separate from the video setting because the two are separate expectations: a
     * selfie usually wants to look like the preview it was framed in, while a recording is often
     * wanted the way round the world actually is.</p>
     */
    private boolean mFrontPhotoMirroringEnabled = true;

    /** JPEG, which every backend and every camera can write. */
    private CameraView.PictureFormat mPictureFormat = CameraView.PictureFormat.JPEG;
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
        attachVideoMetadata(file);
        mView.showVideoConfirmation(file);
    }

    /**
     * Records what took the video, which the platform writes nowhere.
     *
     * <p>A recording comes out of MediaRecorder structurally correct and anonymous: no udta box
     * at all, so nothing says which camera, what wrote it, or when, in any field a person or a
     * tool reads. Checked by dumping the boxes of a real recording rather than assumed.</p>
     *
     * <p>Before the caller is told, not after: a listener handed a file is entitled to find it
     * finished. The write touches a few dozen bytes inside padding the muxer already left, so
     * it costs a seek rather than a copy, and a failure is logged and dropped - a video that
     * does not name the phone that took it is still the video.</p>
     */
    @SuppressLint("MissingPermission")
    private void attachVideoMetadata(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        VideoMetadata.Builder metadata = new VideoMetadata.Builder()
                .setMake(Build.MANUFACTURER)
                .setModel(Build.MODEL)
                .setCreationDate(isoDate(System.currentTimeMillis()));
        // Asked for only where it was granted. A recording that does not say where it was
        // taken is the right outcome for an app that never asked to know.
        Location location =
                PermissionChecker.hasPermissions(getContext(), permission.ACCESS_FINE_LOCATION)
                        ? LocationProvider.getLastKnownGPSLocation(
                                getContext(), STALE_VIDEO_LOCATION_MILLIS)
                        : null;
        if (location != null) {
            metadata.setLocation(location.getLatitude(), location.getLongitude());
        }
        VideoMetadata.write(file, metadata);
    }

    /**
     * How old a fix may be before it is not where this video was taken.
     *
     * <p>Read without waiting, because this runs as a recording finishes and a listener is
     * waiting behind it. A minute is generous for a device that has been pointing at something
     * long enough to film it.</p>
     */
    private static final long STALE_VIDEO_LOCATION_MILLIS = 60 * 1000;

    /** ISO 8601, which is what a reader expects to find in the creation-date box. */
    private static String isoDate(long timestamp) {
        SimpleDateFormat format =
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US);
        return format.format(new Date(timestamp));
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

    public synchronized void setFrontVideoMirroringEnabled(boolean enabled) {
        mFrontVideoMirroringEnabled = enabled;
    }

    public synchronized boolean isFrontVideoMirroringEnabled() {
        return mFrontVideoMirroringEnabled;
    }

    /**
     * Asks for pictures in this format.
     *
     * <p>Declined, leaving the previous format, when the camera cannot write it - a backend that
     * accepted the request and then wrote something else would be worse than one that said no.
     * </p>
     */
    public synchronized void setPictureFormat(CameraView.PictureFormat format) {
        if (!isPictureFormatSupported(format)) {
            Log.w(TAG, "This camera cannot write " + format + "; keeping " + mPictureFormat);
            return;
        }
        mPictureFormat = format;
    }

    public synchronized CameraView.PictureFormat getPictureFormat() {
        return mPictureFormat;
    }

    /** Only JPEG, unless a backend can do better. */
    public boolean isPictureFormatSupported(CameraView.PictureFormat format) {
        return format == CameraView.PictureFormat.JPEG;
    }

    public synchronized void setFrontPhotoMirroringEnabled(boolean enabled) {
        mFrontPhotoMirroringEnabled = enabled;
    }

    public synchronized boolean isFrontPhotoMirroringEnabled() {
        return mFrontPhotoMirroringEnabled;
    }

    /**
     * Whether the picture about to be taken should come out mirrored.
     *
     * <p>Only a user-facing lens produces a mirrored view in the first place, so this is the
     * setting and the lens together - which is the question every backend actually has.</p>
     */
    public final boolean isMirroringThisPicture() {
        return isUsingFrontFacingCamera() && isFrontPhotoMirroringEnabled();
    }

        mPreviewStabilizationEnabled = enabled;
    }

        return mPreviewStabilizationEnabled;
    }

    public synchronized boolean isPreviewStabilizationSupported() {
        return false;
    }

        mVideoStabilizationEnabled = enabled;
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

    /** The high-speed choice recordings are made at, or null for ordinary speed. */
    @Nullable
    private HighSpeedProfile mHighSpeedProfile;

    /**
     * Whether this camera will record above its normal frame rate, for slow-motion playback.
     *
     * <p>False, which is the answer for a backend that cannot ask the camera. There is nothing
     * conservative about a backend guessing yes: a session configured for a frame rate the
     * camera never published fails outright, and the recording is lost with it.</p>
     */
    public boolean isHighSpeedRecordingSupported() {
        return false;
    }

    /**
     * Every frame size and frame rate this camera records high-speed video at.
     *
     * <p>Empty for a backend or a camera with none. The combinations are the camera's own, not a
     * cross product: see {@link HighSpeedProfile}.</p>
     */
    @NonNull
    public List<HighSpeedProfile> getSupportedHighSpeedProfiles() {
        return Collections.emptyList();
    }

    /**
     * Records at one of the published high-speed choices, or at ordinary speed when given null.
     *
     * <p>Declined, leaving the previous choice, when the camera does not publish it - the same
     * way an unsupported picture format is. A backend that accepted the request would fail to
     * configure its next recording session instead, which is a worse place to find out.</p>
     *
     * @return whether the camera is now set to record at the requested choice
     */
    public synchronized boolean setHighSpeedProfile(@Nullable HighSpeedProfile profile) {
        if (profile == null) {
            mHighSpeedProfile = null;
            return true;
        }
        HighSpeedProfile published = HighSpeedProfile.match(
                getSupportedHighSpeedProfiles(), profile.getSize(), profile.getFrameRate());
        if (published == null) {
            Log.w(TAG, "This camera does not record " + profile + "; keeping " + mHighSpeedProfile);
            return false;
        }
        // The camera's published pair rather than the caller's copy of it. A high-speed request
        // is only accepted with a frame rate range the camera published, and the match above
        // compares the size and the rate, so a hand-built range could pass here and then be
        // refused by the camera at the point where the recording is at stake.
        mHighSpeedProfile = published;
        return true;
    }

    @Nullable
    public synchronized HighSpeedProfile getHighSpeedProfile() {
        return mHighSpeedProfile;
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

    /** What draws each camera frame on its way out, or null to leave the frames alone. */
    @Nullable private FrameRenderer mFrameRenderer;

    /** Who owns the texture the camera renders into, or null for the library's own. */
    @Nullable private CameraTextureProvider mCameraTextureProvider;

    /**
     * Whether this backend can put camera frames through GL on their way to the preview, the
     * recording and the stills.
     *
     * <p>False here, because being able to is a property of how a backend gets its frames rather
     * than something every backend has. A backend that reports false ignores the renderer and the
     * texture provider entirely and keeps showing the camera, which is the behaviour a caller
     * whose effect is decoration wants; one whose effect is the feature asks this first.</p>
     */
    public boolean isFrameEffectSupported() {
        return false;
    }

    /**
     * Sets what draws each frame. A backend that supports effects should start putting frames
     * through GL, which usually means reconfiguring the camera.
     */
    public void setFrameRenderer(@Nullable FrameRenderer renderer) {
        mFrameRenderer = renderer;
    }

    @Nullable
    public final FrameRenderer getFrameRenderer() {
        return mFrameRenderer;
    }

    /** Sets who creates the texture the camera renders into. See {@link CameraTextureProvider}. */
    public void setCameraTextureProvider(@Nullable CameraTextureProvider provider) {
        mCameraTextureProvider = provider;
    }

    @Nullable
    public final CameraTextureProvider getCameraTextureProvider() {
        return mCameraTextureProvider;
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
