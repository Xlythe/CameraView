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

/**
 * The preview with a caller's stream beside it - frames for analysis, or an encoder for a live
 * stream - and a still output, so a picture can be taken while either runs.
 *
 * <p>The still is the same picture a {@link PictureSession} takes, less what a camera promises
 * only beside a preview alone: RAW, Ultra HDR and an unbinned readout, which also stalls the
 * frames the stream is waiting on. A camera that will not configure the three outputs together
 * gets this session again without the still, which is how it was before a stream could have
 * one.</p>
 */
@RequiresApi(21)
class StreamSession extends PictureSession {

    /** The caller's stream, sized once the preview has a size. */
    private final StreamSurface mStreamSurface;

    /** Whether this session carries a still output beside the stream. */
    private final boolean mWithStill;

    StreamSession(Camera2Module camera2Module, VideoRecorder.SurfaceProvider surfaceProvider,
            boolean withStill) {
        super(camera2Module);
        mStreamSurface = new StreamSurface(camera2Module, surfaceProvider, getPreviewSurface());
        mWithStill = withStill;
    }

    public VideoRecorder.SurfaceProvider getSurfaceProvider() {
        return mStreamSurface.mSurfaceProvider;
    }

    @Override
    protected void onPreviewInitialized(@NonNull StreamConfigurationMap map) {
        mStreamSurface.initialize(map);
    }

    @Override
    protected boolean hasStillCapture() {
        return mWithStill;
    }

    @Override
    protected boolean allowsUltraHdr() {
        return false;
    }

    @Override
    protected boolean allowsRaw() {
        return false;
    }

    @Override
    protected boolean allowsMaximumResolution() {
        return false;
    }

    /**
     * A still is taken as the moment stands, as a recording's snapshot is: the stream's
     * repeating request runs without the flash strategy a pre-flash would be metered against.
     */
    @Override
    protected boolean metersForFlash() {
        return false;
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
