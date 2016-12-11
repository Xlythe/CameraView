package com.xlythe.view.camera.v2;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;

import com.xlythe.view.camera.CameraView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.xlythe.view.camera.ICameraModule.DEBUG;
import static com.xlythe.view.camera.ICameraModule.TAG;

@TargetApi(21)
abstract class SessionImpl implements Camera2Module.Session {
    static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

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

    Handler getBackgroundHandler() {
        return mCamera2Module.getBackgroundHandler();
    }

    int getDisplayRotation() {
        return mCamera2Module.getDisplayRotation();
    }

    CameraView.OnImageCapturedListener getOnImageCapturedListener() {
        return mCamera2Module.getOnImageCapturedListener();
    }

    CameraView.OnVideoCapturedListener getOnVideoCapturedListener() {
        return mCamera2Module.getOnVideoCapturedListener();
    }

    @Override
    public void setMeteringRectangle(MeteringRectangle meteringRectangle) {
        mMeteringRectangle = meteringRectangle;
    }

    @Override
    public MeteringRectangle getMeteringRectangle() {
        return mMeteringRectangle;
    }

    @Override
    public void setCropRegion(Rect region) {
        mCropRegion = region;
    }

    @Override
    public Rect getCropRegion() {
        return mCropRegion;
    }

    @Override
    public List<Surface> getSurfaces() {
        return new ArrayList<>();
    }

    @Override
    public void close() {}


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

        int getWidth() {
            return mSize.getWidth();
        }

        int getHeight() {
            return mSize.getHeight();
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
    }
}
