package com.xlythe.view.camera.v2;

import android.Manifest;
import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.location.Location;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.LocationProvider;
import com.xlythe.view.camera.PermissionChecker;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static com.xlythe.view.camera.ICameraModule.DEBUG;
import static com.xlythe.view.camera.ICameraModule.TAG;

@TargetApi(21)
abstract class SessionImpl implements Camera2Module.Session {
    private static final long STALE_LOCATION_MILLIS = 2 * 60 * 60 * 1000;
    private static final long GPS_TIMEOUT_MILLIS = 10;

    private final Camera2Module mCamera2Module;

    @Nullable
    MeteringRectangle mMeteringRectangle;

    @Nullable
    Rect mCropRegion;

    SessionImpl(Camera2Module camera2Module) {
        mCamera2Module = camera2Module;
    }

    void transformPreview(int width, int height) throws CameraAccessException {
        mCamera2Module.transformPreview(width, height);
    }

    @NonNull
    Handler getBackgroundHandler() {
        return mCamera2Module.getBackgroundHandler();
    }

    boolean hasFlash() {
        return mCamera2Module.hasFlash();
    }

    CameraView.Flash getFlash() {
        return mCamera2Module.getFlash();
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

        CameraView.Quality getQuality() {
            return mCameraView.getQuality();
        }

        abstract void initialize(StreamConfigurationMap map);

        void initialize(Size size) {
            if (DEBUG) {
                Log.d(TAG, String.format("Initializing %s with width=%s and height=%s", getClass().getSimpleName(), size.getWidth(), size.getHeight()));
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

        int getCameraId() {
            try {
                String cameraId = mCameraView.getActiveCamera();

                // Usually an integer, but has the potential to be a random string. We convert to
                // an integer because Camcorder requires it
                return Integer.parseInt(cameraId);
            } catch (Exception e) {
                Log.e(TAG, "Failed to get camera id", e);

                // The default camera always has id 0
                return 0;
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

        Size chooseSize(List<Size> choices, Size recommendedSize) {
            if (DEBUG) {
                Log.d(TAG, "Choosing from sizes " + choices);
            }
            List<Size> availableSizes;
            switch (getQuality()) {
                case LOW:
                    availableSizes = getSizes(choices, CameraView.Quality.LOW, recommendedSize);
                    if (!availableSizes.isEmpty()) {
                        return Collections.max(availableSizes, new CompareSizesByArea());
                    }
                    if (DEBUG) Log.e(TAG, "Couldn't find a low quality size with the same aspect ratio as the preview");
                    availableSizes = getSizes(choices, CameraView.Quality.LOW);
                    if (!availableSizes.isEmpty()) {
                        return Collections.max(availableSizes, new CompareSizesByArea());
                    }
                    if (DEBUG) Log.e(TAG, "Couldn't find a low quality size");
                case MEDIUM:
                    availableSizes = getSizes(choices, CameraView.Quality.MEDIUM, recommendedSize);
                    if (!availableSizes.isEmpty()) {
                        return Collections.max(availableSizes, new CompareSizesByArea());
                    }
                    if (DEBUG) Log.e(TAG, "Couldn't find a medium quality size with the same aspect ratio as the preview");
                    availableSizes = getSizes(choices, CameraView.Quality.MEDIUM);
                    if (!availableSizes.isEmpty()) {
                        return Collections.max(availableSizes, new CompareSizesByArea());
                    }
                    if (DEBUG) Log.e(TAG, "Couldn't find a medium quality size");
                case HIGH:
                    availableSizes = getSizes(choices, CameraView.Quality.HIGH, recommendedSize);
                    if (!availableSizes.isEmpty()) {
                        return Collections.max(availableSizes, new CompareSizesByArea());
                    }
                    if (DEBUG) Log.e(TAG, "Couldn't find a high quality size with the same aspect ratio as the preview");
                    availableSizes = getSizes(choices, CameraView.Quality.HIGH);
                    if (!availableSizes.isEmpty()) {
                        return Collections.max(availableSizes, new CompareSizesByArea());
                    }
                    if (DEBUG) Log.e(TAG, "Couldn't find a high quality size");
                case MAX:
                    return Collections.max(choices, new CompareSizesByArea());
                default:
                    Log.e(TAG, "Couldn't find a suitable size");
                    return Collections.max(choices, new CompareSizesByArea());
            }
        }

        static List<Size> getSizes(List<Size> choices, CameraView.Quality quality) {
            return getSizes(choices, quality, null);
        }

        static List<Size> getSizes(List<Size> choices, CameraView.Quality quality, @Nullable Size recommendedSize) {
            List<Size> availableSizes = new ArrayList<>(choices.size());
            for (Size size : choices) {
                if (quality == CameraView.Quality.MEDIUM && size.getHeight() > 720) {
                    continue;
                }
                if (quality == CameraView.Quality.LOW && size.getHeight() > 420) {
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
                Log.d(TAG, "Found available picture sizes: " + availableSizes);
            }
            return availableSizes;
        }

        static boolean sameAspectRatio(Size a, Size b) {
            return 1000 * a.getWidth() / a.getHeight() == 1000 * b.getWidth() / b.getHeight();
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

        @SuppressWarnings({"MissingPermission"})
        @Nullable
        static Location getLocation(Context context) {
            if (PermissionChecker.hasPermissions(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
                // Our GPS timeout is purposefully low. We're not intending to wait until GPS is acquired
                // but we want a last known location for the next time a picture is taken.
                return LocationProvider.getGPSLocation(context, STALE_LOCATION_MILLIS, GPS_TIMEOUT_MILLIS);
            }
            return null;
        }
    }
}
