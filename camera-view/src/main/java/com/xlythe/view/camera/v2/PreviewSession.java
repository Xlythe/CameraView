package com.xlythe.view.camera.v2;

import android.annotation.TargetApi;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import androidx.annotation.NonNull;

import static com.xlythe.view.camera.ICameraModule.DEBUG;
import static com.xlythe.view.camera.ICameraModule.TAG;

@TargetApi(21)
class PreviewSession extends SessionImpl {
    /**
     * The preview must always be limited to, at most, 1080p. Without that limit, max-resolution
     * pictures will break and high-resolution pictures will crash on the Nexus 5X (due to a lack
     * of CTS tests for this combination).
     */
    private final PreviewSurface mPreviewSurface;

    PreviewSession(Camera2Module camera2Module) {
        super(camera2Module);
        mPreviewSurface = new PreviewSurface(camera2Module);
    }

    CameraSurface getPreviewSurface() {
        return mPreviewSurface;
    }

    @Override
    public void initialize(@NonNull StreamConfigurationMap map) throws CameraAccessException {
        mPreviewSurface.initialize(map);
        transformPreview(mPreviewSurface.getWidth(), mPreviewSurface.getHeight());
    }

    private CaptureRequest createCaptureRequest(@NonNull CameraDevice device) throws CameraAccessException {
        CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        if (mMeteringRectangle != null) {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{mMeteringRectangle});
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{mMeteringRectangle});
        }
        if (mCropRegion != null) {
            builder.set(CaptureRequest.SCALER_CROP_REGION, mCropRegion);
        }
        builder.addTarget(mPreviewSurface.getSurface());
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
        surfaces.add(mPreviewSurface.getSurface());
        return surfaces;
    }

    private static final class PreviewSurface extends CameraSurface {
        private Size chooseOptimalSize(List<Size> choices, int viewWidth, int viewHeight) {
            if (DEBUG) {
                Log.d(TAG, "Choosing from sizes " + choices);
            }

            // These sizes are all larger than our view port, so we won't have to scale the image up.
            List<Size> availableSizes = new ArrayList<>(choices.size());
            for (Size size : choices) {
                if (size.getWidth() >= viewWidth && size.getHeight() >= viewHeight) {
                    availableSizes.add(size);
                }
            }

            if (DEBUG) {
                Log.d(TAG, "Filtered the choices down to: " + availableSizes);
            }

            if (availableSizes.isEmpty()) {
                Log.e(TAG, "Couldn't find a suitable size");
                availableSizes.add(Collections.max(choices, new CompareSizesByArea()));
            }

            return Collections.min(availableSizes, new CompareSizesByArea());
        }

        private List<Size> getSizes(StreamConfigurationMap map) {
            return filter(map.getOutputSizes(SurfaceTexture.class));
        }

        private Surface mSurface;

        PreviewSurface(Camera2Module cameraView) {
            super(cameraView);
        }

        @Override
        void initialize(StreamConfigurationMap map) {
            if (DEBUG) Log.d(TAG, "Initializing PreviewSession");
            super.initialize(chooseOptimalSize(getSizes(map), mCameraView.getWidth(), mCameraView.getHeight()));

            SurfaceTexture texture = mCameraView.getSurfaceTexture();
            if (texture == null) {
                // This will be caught in Camera2Module.setSession
                throw new IllegalStateException(
                        "Expected a SurfaceTexture to exist, but none does. "
                            + "Was the SurfaceTexture already closed?");
            }
            texture.setDefaultBufferSize(getWidth(), getHeight());

            // This is the output Surface we need to start the preview.
            mSurface = new Surface(texture);
        }

        @Override
        Surface getSurface() {
            return mSurface;
        }

        @Override
        void close() {}
    }
}
