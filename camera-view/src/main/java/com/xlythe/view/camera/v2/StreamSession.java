package com.xlythe.view.camera.v2;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
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

    private CaptureRequest createCaptureRequest(@NonNull CameraDevice device) throws CameraAccessException {
        CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        if (mMeteringRectangle != null) {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{mMeteringRectangle});
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{mMeteringRectangle});
        }
        if (mCropRegion != null) {
            builder.set(CaptureRequest.SCALER_CROP_REGION, mCropRegion);
        }
        builder.addTarget(getPreviewSurface().getSurface());
        builder.addTarget(mStreamSurface.getSurface());
        return builder.build();
    }

    @Override
    public void onAvailable(@NonNull CameraDevice device, @NonNull CameraCaptureSession session) throws CameraAccessException {
        session.setRepeatingRequest(createCaptureRequest(device), null /* callback */, getHandler());
    }

    @Override
    public void onInvalidate(@NonNull CameraDevice device, @NonNull CameraCaptureSession session) throws CameraAccessException {
        session.setRepeatingRequest(createCaptureRequest(device), null /* callback */, getHandler());
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
            return mSurfaceProvider.getSurface(getWidth(), getHeight(), mCameraView.getSensorOrientation());
        }

        @Override
        void close() {
            mPreviewSurface.close();
        }
    }
}
