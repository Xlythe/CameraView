package com.xlythe.view.camera.v2;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.location.Location;
import android.media.Image;
import android.media.ImageReader;
import android.os.AsyncTask;
import android.os.Build;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.Exif;
import com.xlythe.view.camera.stream.VideoRecorder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import static com.xlythe.view.camera.ICameraModule.DEBUG;
import static com.xlythe.view.camera.ICameraModule.TAG;

@TargetApi(21)
class StreamSession extends PreviewSession {

    private final StreamSurface mStreamSurface;

    StreamSession(Camera2Module camera2Module, VideoRecorder.SurfaceProvider surfaceProvider) {
        super(camera2Module);
        mStreamSurface = new StreamSurface(camera2Module, surfaceProvider, getPreviewSurface());
    }

    public VideoRecorder.SurfaceProvider getSurfaceProvider() {
        return mStreamSurface.mSurfaceProvider;
    }

    @Override
    public void initialize(@NonNull StreamConfigurationMap map) throws CameraAccessException {
        super.initialize(map);
        mStreamSurface.initialize(map);
    }

    @NonNull
    @Override
    public List<Surface> getSurfaces() {
        List<Surface> surfaces = super.getSurfaces();
        surfaces.add(mStreamSurface.getSurface());
        return surfaces;
    }

    @Override
    public void close() {
        super.close();
        mStreamSurface.close();
    }

    private static final class StreamSurface extends CameraSurface {
        private final VideoRecorder.SurfaceProvider mSurfaceProvider;
        private final CameraSurface mPreviewSurface;

        StreamSurface(Camera2Module camera2Module, VideoRecorder.SurfaceProvider surfaceProvider, CameraSurface previewSurface) {
            super(camera2Module);
            mSurfaceProvider = surfaceProvider;
            mPreviewSurface = previewSurface;
        }

        @Override
        void initialize(StreamConfigurationMap map) {
            if (DEBUG) Log.d(TAG, "Initializing StreamSession");
            super.initialize(mPreviewSurface.mSize);
        }

        @Override
        Surface getSurface() {
            Log.d(TAG, "Created stream surface");
            return mSurfaceProvider.getSurface(getWidth(), getHeight(), mCameraView.getSensorOrientation());
        }

        @Override
        void close() {
        }
    }
}
