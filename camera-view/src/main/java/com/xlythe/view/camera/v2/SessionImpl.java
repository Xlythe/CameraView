package com.xlythe.view.camera.v2;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.ExifCaptureParameters;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import static com.xlythe.view.camera.ICameraModule.DEBUG;
import static com.xlythe.view.camera.ICameraModule.TAG;

@TargetApi(21)
abstract class SessionImpl implements Camera2Module.Session {
    static final Size SIZE_4K = new Size(3840, 2160);
    static final Size SIZE_1080P = new Size(1920, 1080);
    static final Size SIZE_720P = new Size(1280, 720);
    static final Size SIZE_480P = new Size(640, 480);

    private final Camera2Module mCamera2Module;

    @Nullable
    MeteringRectangle mMeteringRectangle;

    @Nullable
    Rect mCropRegion;

    /** Zoom as a ratio across the camera's whole range, which can switch physical lenses. */
    @Nullable
    Float mZoomRatio;

    SessionImpl(Camera2Module camera2Module) {
        mCamera2Module = camera2Module;
    }

    void transformPreview(int width, int height) throws CameraAccessException {
        mCamera2Module.transformPreview(width, height);
    }

    @NonNull
    Handler getHandler() {
        return mCamera2Module.getHandler();
    }

    boolean hasFlash() {
        return mCamera2Module.hasFlash();
    }

    CameraView.Flash getFlash() {
        return mCamera2Module.getFlash();
    }

    /**
     * Applies the requested flash strategy to a capture request.
     *
     * <p>Camera2 drives the flash through CONTROL_AE_MODE; FLASH_MODE is ignored unless the
     * auto-exposure routine has been told not to manage the flash itself. The mode also has to be
     * on the repeating preview request, because that is the only stream auto-exposure can converge
     * over before a still capture. AOSP's Camera2Basic applies it to both builders for the same
     * reason.</p>
     */
    /**
     * Asks the camera for the faces it is tracking, when something wants them.
     *
     * <p>Face detection is a mode on the request rather than a one-off query, so it has to be on
     * the repeating preview request; the results then arrive with each completed frame.</p>
     */
    /**
     * The sizes that fit inside a bound, or all of them when none do.
     *
     * <p>Falling back to all of them rather than to nothing matters: a bound no size fits is a
     * reason to pick badly, not a reason to have no output at all.</p>
     */
    @NonNull
    static List<Size> within(@NonNull List<Size> sizes, @Nullable Size maxSize) {
        if (maxSize == null) {
            return sizes;
        }
        List<Size> fitting = new ArrayList<>(sizes.size());
        for (Size size : sizes) {
            if (size.getWidth() <= maxSize.getWidth() && size.getHeight() <= maxSize.getHeight()) {
                fitting.add(size);
            }
        }
        return fitting.isEmpty() ? sizes : fitting;
    }

    void applyFaceDetection(@NonNull CaptureRequest.Builder builder) {
        Integer mode = mCamera2Module.getFaceDetectMode();
        if (mode != null) {
            builder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, mode);
        }
    }

    /**
     * The callback the repeating request needs, or null when nothing is listening.
     *
     * <p>A null callback is what the session passes by default: the camera then does not have to
     * deliver results at all, which is one fewer thing happening per frame.</p>
     */
    @Nullable
    CameraCaptureSession.CaptureCallback repeatingCallback() {
        if (mCamera2Module.getFaceDetectMode() == null && !mCamera2Module.isFocusPending()) {
            return null;
        }
        return new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                    @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                if (mCamera2Module.getFaceDetectMode() != null) {
                    mCamera2Module.onFacesCaptured(result);
                }
                mCamera2Module.onFocusCaptured(result);
            }
        };
    }

    int getPictureQuality() {
        return mCamera2Module.getPictureQuality();
    }

    @NonNull
    ExifCaptureParameters exifCaptureParameters() {
        return mCamera2Module.exifCaptureParameters();
    }

    void applyFlash(@NonNull CaptureRequest.Builder builder) {
        if (!hasFlash()) {
            return;
        }
        switch (getFlash()) {
            case AUTO:
                builder.set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                break;
            case ON:
                builder.set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
                break;
            case OFF:
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
                break;
        }
    }

    int getRelativeCameraOrientation() {
        return mCamera2Module.getRelativeCameraOrientation();
    }

    CameraView.OnImageCapturedListener getOnImageCapturedListener() {
        return mCamera2Module.getOnImageCapturedListener();
    }

    CameraView.OnVideoCapturedListener getOnVideoCapturedListener() {
        return mCamera2Module.getOnVideoCapturedListener();
    }

    @Override
    public void setMeteringRectangle(@Nullable MeteringRectangle meteringRectangle) {
        mMeteringRectangle = meteringRectangle;
    }

    @Nullable
    @Override
    public MeteringRectangle getMeteringRectangle() {
        return mMeteringRectangle;
    }

    /**
     * Applies zoom to a capture request.
     *
     * <p>Prefers CONTROL_ZOOM_RATIO, which is the only control that lets the camera switch
     * between the physical lenses behind a logical camera, so a ratio below one reaches an
     * ultra-wide and a high ratio reaches a telephoto. SCALER_CROP_REGION only ever crops the
     * sensor of the lens already in use, so it is the fallback for older devices.</p>
     */
    void applyZoom(@NonNull CaptureRequest.Builder builder) {
        if (Build.VERSION.SDK_INT >= 30 && mZoomRatio != null) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, mZoomRatio);
        } else if (mCropRegion != null) {
            builder.set(CaptureRequest.SCALER_CROP_REGION, mCropRegion);
        }
    }

    @Override
    public void setZoomRatio(@Nullable Float zoomRatio) {
        mZoomRatio = zoomRatio;
    }

    @Nullable
    @Override
    public Float getZoomRatio() {
        return mZoomRatio;
    }

    @Override
    public void setCropRegion(@Nullable Rect region) {
        mCropRegion = region;
    }

    @Nullable
    @Override
    public Rect getCropRegion() {
        return mCropRegion;
    }

    @NonNull
    @Override
    public List<Surface> getSurfaces() {
        return new ArrayList<>();
    }

    @Override
    public void close() {}

    void onImageFailed() {
        mCamera2Module.onImageFailed();
    }

    void onVideoFailed() {
        mCamera2Module.onVideoFailed();
    }

    static abstract class CameraSurface {
        final Camera2Module mCameraView;

        Size mSize;

        CameraSurface(Camera2Module cameraView) {
            mCameraView = cameraView;
        }

        Context getContext() {
            return mCameraView.getContext();
        }

        /** Where to tag a capture. Never waits for a fix. */
        @AnyThread
        @Nullable
        Location getLocation() {
            return mCameraView.getLocation();
        }

        CameraView.Quality getQuality() {
            return mCameraView.getQuality();
        }

        abstract void initialize(StreamConfigurationMap map);

        void initialize(Size size) {
            if (DEBUG) {
                Log.d(TAG, String.format("Initialized %s with width=%s and height=%s", getClass().getSimpleName(), size.getWidth(), size.getHeight()));
            }
            mSize = size;
        }

        void showImageConfirmation(File file) {
            mCameraView.showImageConfirmation(file);
        }

        void onImageFailed() {
            mCameraView.onImageFailed();
        }

        void showVideoConfirmation(File file) {
            mCameraView.showVideoConfirmation(file);
        }

        void onVideoFailed() {
            mCameraView.onVideoFailed();
        }

        int getWidth() {
            return mSize.getWidth();
        }

        int getHeight() {
            return mSize.getHeight();
        }

        boolean isUsingFrontFacingCamera() {
            return mCameraView.isUsingFrontFacingCamera();
        }

        public boolean isMatchPreviewAspectRatioEnabled() {
            return mCameraView.isMatchPreviewAspectRatioEnabled();
        }

        String getCameraId() {
            try {
                return mCameraView.getActiveCamera();
            } catch (CameraAccessException e) {
                Log.e(TAG, "Failed to get camera id", e);

                // The default camera always has id 0
                return "0";
            }
        }

        abstract void close();

        abstract Surface getSurface();

        static class CompareSizesByArea implements Comparator<Size> {
            @Override
            public int compare(Size lhs, Size rhs) {
                // We cast here to ensure the multiplications won't overflow
                return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                        (long) rhs.getWidth() * rhs.getHeight());
            }
        }

        /**
         * How much resolution matching the preview's shape may cost. A real difference in shape
         * costs about a third at most, 4:3 against 16:9, so losing more than half means the view
         * was laid out oddly rather than shaped differently.
         */
        private static final double MIN_MATCHED_PIXEL_FRACTION = 0.5;

        /**
         * The best size of the shape the preview is, unless that costs too much resolution.
         *
         * @param matched the sizes of the preview's shape, which may be empty
         * @param all every size on offer, which is not
         * @return the size to record at
         */
        @VisibleForTesting
        static Size chooseLargestMatching(
                @NonNull List<Size> matched, @NonNull List<Size> all,
                @NonNull Comparator<Size> comparator) {
            Size best = Collections.max(all, comparator);
            if (matched.isEmpty()) {
                return best;
            }
            Size bestMatching = Collections.max(matched, comparator);
            long matchedPixels = (long) bestMatching.getWidth() * bestMatching.getHeight();
            long bestPixels = (long) best.getWidth() * best.getHeight();
            if (matchedPixels < bestPixels * MIN_MATCHED_PIXEL_FRACTION) {
                Log.w(TAG, "Ignoring the preview's aspect ratio: it would record "
                        + bestMatching + " where " + best + " is available");
                return best;
            }
            return bestMatching;
        }

        Size chooseSize(List<Size> choices, Size recommendedSize) {
            if (choices == null || choices.isEmpty()) {
                Log.e(TAG, "No available sizes to choose from");
                throw new IllegalStateException("No available sizes to choose from");
            }
            if (DEBUG) {
                Log.d(TAG, "Choosing from sizes " + choices);
            }
            Comparator<Size> comparator = new CompareSizesByArea();
            List<Size> availableSizes;
            switch (getQuality()) {
                case LOW:
                    if (isMatchPreviewAspectRatioEnabled()) {
                        availableSizes = getSizes(choices, CameraView.Quality.LOW, recommendedSize);
                        if (!availableSizes.isEmpty()) {
                            return chooseLargestMatching(availableSizes,
                                    getSizes(choices, CameraView.Quality.LOW), comparator);
                        }
                        if (DEBUG)
                            Log.e(TAG, "Couldn't find a low quality size with the same aspect ratio as the preview");
                    }
                    availableSizes = getSizes(choices, CameraView.Quality.LOW);
                    if (!availableSizes.isEmpty()) {
                        return Collections.max(availableSizes, comparator);
                    }
                    if (DEBUG) Log.e(TAG, "Couldn't find a low quality size");
                    // Fall-through
                case MEDIUM:
                    if (isMatchPreviewAspectRatioEnabled()) {
                        availableSizes = getSizes(choices, CameraView.Quality.MEDIUM, recommendedSize);
                        if (!availableSizes.isEmpty()) {
                            return chooseLargestMatching(availableSizes,
                                    getSizes(choices, CameraView.Quality.MEDIUM), comparator);
                        }
                        if (DEBUG)
                            Log.e(TAG, "Couldn't find a medium quality size with the same aspect ratio as the preview");
                    }
                    availableSizes = getSizes(choices, CameraView.Quality.MEDIUM);
                    if (!availableSizes.isEmpty()) {
                        return Collections.max(availableSizes, comparator);
                    }
                    if (DEBUG) Log.e(TAG, "Couldn't find a medium quality size");
                    // Fall-through
                case HIGH:
                    if (isMatchPreviewAspectRatioEnabled()) {
                        availableSizes = getSizes(choices, CameraView.Quality.HIGH, recommendedSize);
                        if (!availableSizes.isEmpty()) {
                            return chooseLargestMatching(availableSizes,
                                    getSizes(choices, CameraView.Quality.HIGH), comparator);
                        }
                        if (DEBUG)
                            Log.e(TAG, "Couldn't find a high quality size with the same aspect ratio as the preview");
                    }
                    availableSizes = getSizes(choices, CameraView.Quality.HIGH);
                    if (!availableSizes.isEmpty()) {
                        return Collections.max(availableSizes, comparator);
                    }
                    if (DEBUG) Log.e(TAG, "Couldn't find a high quality size");
                    // Fall-through
                case MAX:
                    if (isMatchPreviewAspectRatioEnabled()) {
                        availableSizes = getSizes(choices, CameraView.Quality.MAX, recommendedSize);
                        if (!availableSizes.isEmpty()) {
                            return chooseLargestMatching(availableSizes,
                                    getSizes(choices, CameraView.Quality.MAX), comparator);
                        }
                        if (DEBUG)
                            Log.e(TAG, "Couldn't find a max quality size with the same aspect ratio as the preview");
                    }
                    availableSizes = getSizes(choices, CameraView.Quality.MAX);
                    if (!availableSizes.isEmpty()) {
                        return Collections.max(availableSizes, comparator);
                    }
                    if (DEBUG) Log.e(TAG, "Couldn't find a max quality size");
                    return Collections.max(choices, comparator);
                default:
                    Log.e(TAG, "Couldn't find a suitable size, returning max possible size");
                    return Collections.max(choices, comparator);
            }
        }

        static List<Size> getSizes(List<Size> choices, CameraView.Quality quality) {
            return getSizes(choices, quality, null);
        }

        static List<Size> getSizes(List<Size> choices, CameraView.Quality quality, @Nullable Size recommendedSize) {
            List<Size> availableSizes = new ArrayList<>(choices.size());
            for (Size size : choices) {
                if (quality == CameraView.Quality.HIGH
                        && (size.getWidth() > SIZE_1080P.getWidth()
                        || size.getHeight() > SIZE_1080P.getHeight())) {
                    continue;
                }
                if (quality == CameraView.Quality.MEDIUM
                        && (size.getWidth() > SIZE_720P.getWidth()
                        || size.getHeight() > SIZE_720P.getHeight())) {
                    continue;
                }
                if (quality == CameraView.Quality.LOW
                        && (size.getWidth() > SIZE_480P.getWidth()
                        || size.getHeight() > SIZE_480P.getHeight())) {
                    continue;
                }

                if (recommendedSize == null) {
                    availableSizes.add(size);
                } else if (sameAspectRatio(size, recommendedSize)) {
                    // Only look at sizes that match the aspect ratio of the preview, because that's what
                    // the user sees when they take the picture.
                    availableSizes.add(size);
                }
            }
            if (DEBUG) {
                Log.d(TAG, "Filtered the choices down to: " + availableSizes);
            }
            return availableSizes;
        }

        static boolean sameAspectRatio(Size a, Size b) {
            return Math.abs((float) a.getWidth() / a.getHeight() - (float) b.getWidth() / b.getHeight()) < 0.01f;
        }

        static List<Size> filter(Size[] sizes) {
            return filter(sizes, Camera2Module.MAX_SUPPORTED_SIZE);
        }

        static List<Size> filter(Size[] sizes, Size maxSize) {
            List<Size> availableSizes = new ArrayList<>(sizes.length);
            for (Size size : sizes) {
                if (size.getWidth() > maxSize.getWidth()
                        || size.getHeight() > maxSize.getHeight()) {
                    continue;
                }
                availableSizes.add(size);
            }
            return availableSizes;
        }

        static <T> T[] concat(T[] first, T[] second) {
            T[] result = Arrays.copyOf(first, first.length + second.length);
            System.arraycopy(second, 0, result, first.length, second.length);
            return result;
        }
    }
}
