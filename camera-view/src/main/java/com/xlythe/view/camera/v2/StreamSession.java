package com.xlythe.view.camera.v2;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.xlythe.view.camera.stream.VideoRecorder;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.xlythe.view.camera.ICameraModule.DEBUG;
import static com.xlythe.view.camera.ICameraModule.TAG;

@RequiresApi(21)
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
        applyAeMode(builder, false /* withFlashStrategy */);
        applyExposureCompensation(builder);
        applyExposureLock(builder);
        if (mMeteringRectangle != null) {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{mMeteringRectangle});
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{mMeteringRectangle});
        }
        applyZoom(builder);
        applyStabilization(builder);
        applyFaceDetection(builder);
        builder.addTarget(getPreviewSurface().getSurface());
        builder.addTarget(mStreamSurface.getSurface());
        return builder.build();
    }

    @Override
    boolean feedsAnEncoder() {
        return true;
    }

    @Override
    public void onAvailable(@NonNull CameraDevice device, @NonNull CameraCaptureSession session) throws CameraAccessException {
        session.setRepeatingRequest(createCaptureRequest(device), repeatingCallback(), getHandler());
    }

    @Override
    public void onInvalidate(@NonNull CameraDevice device, @NonNull CameraCaptureSession session) throws CameraAccessException {
        session.setRepeatingRequest(createCaptureRequest(device), repeatingCallback(), getHandler());
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

            Size maxSize = mSurfaceProvider.getMaxSize();
            if (maxSize == null) {
                super.initialize(mPreviewSurface.mSize);
                return;
            }

            // The caller is sending this somewhere the preview resolution will not fit. Take the
            // largest size the camera can feed MediaCodec within the bound, since an arbitrary
            // size is not guaranteed to configure.
            Size[] supported = map.getOutputSizes(MediaCodec.class);
            List<Size> candidates = filter(supported, maxSize);
            if (!candidates.isEmpty()) {
                super.initialize(Collections.max(candidates, new CompareSizesByArea()));
                return;
            }

            // Nothing fits. The cap is there because of a bandwidth ceiling, so overshoot it by as
            // little as possible rather than falling back to the preview, which is larger still.
            if (supported.length > 0) {
                Size smallest = Collections.min(Arrays.asList(supported), new CompareSizesByArea());
                Log.w(TAG, "No stream size within " + maxSize + ", using " + smallest);
                super.initialize(smallest);
                return;
            }
            Log.w(TAG, "Camera reports no MediaCodec sizes, falling back to the preview");
            super.initialize(mPreviewSurface.mSize);
        }

        @Override
        Surface getSurface() {
            return mSurfaceProvider.getSurface(getWidth(), getHeight(), mCameraView.getSensorOrientation(), mCameraView.isUsingFrontFacingCamera());
        }

        @Override
        void close() {
            // The Surface belongs to the VideoRecorder that handed it over, and the preview
            // Surface belongs to PreviewSession, which closes it from super.close(). Closing
            // the preview from here only closed it a second time and never released this one.
        }
    }
}
