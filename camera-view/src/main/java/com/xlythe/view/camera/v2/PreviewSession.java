package com.xlythe.view.camera.v2;

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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.xlythe.view.camera.gl.FrameRenderThread;
import com.xlythe.view.camera.gl.GlMath;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import static com.xlythe.view.camera.ICameraModule.DEBUG;
import static com.xlythe.view.camera.ICameraModule.TAG;

@RequiresApi(21)
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

    @Override
    public void onLayoutChanged() throws CameraAccessException {
        transformPreview(mPreviewSurface.getWidth(), mPreviewSurface.getHeight());
    }

    private CaptureRequest createCaptureRequest(@NonNull CameraDevice device) throws CameraAccessException {
        return newPreviewRequest(device).build();
    }

    /** The preview's request, which a one-off request such as a metering trigger starts from. */
    @NonNull
    CaptureRequest.Builder newPreviewRequest(@NonNull CameraDevice device)
            throws CameraAccessException {
        CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        applyAeMode(builder, true /* withFlashStrategy */);
        applyExposureCompensation(builder);
        if (mMeteringRectangle != null) {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{mMeteringRectangle});
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{mMeteringRectangle});
        }
        applyZoom(builder);
        applyStabilization(builder);
        applyFaceDetection(builder);
        builder.addTarget(mPreviewSurface.getSurface());
        return builder;
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
        surfaces.add(mPreviewSurface.getSurface());
        return surfaces;
    }

    @CallSuper
    @Override
    public void close() {
        super.close();
        mPreviewSurface.close();
    }

    private static final class PreviewSurface extends CameraSurface {
        private Size chooseOptimalSize(List<Size> choices, int viewWidth, int viewHeight) {
            if (DEBUG) {
                Log.d(TAG, "Choosing from sizes " + choices);
            }
            if (choices.isEmpty()) {
                throw new IllegalStateException("Camera reports no preview sizes");
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
            Size[] supported = map.getOutputSizes(SurfaceTexture.class);
            return filter(supported, mCameraView.getPreviewSizeCap(Arrays.asList(supported)));
        }

        private Surface mSurface;

        /** Whether the camera is writing into the renderer rather than into the view. */
        private boolean mThroughGl;

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

            FrameRenderThread effect = mCameraView.getEffect();
            if (effect != null) {
                mThroughGl = initializeThroughGl(effect, texture);
                if (mThroughGl) {
                    return;
                }
                // The renderer came up and then would not give us a texture, which leaves the
                // camera with nowhere to write. Straight to the preview is better than nothing.
                Log.w(TAG, "The renderer has no input, so this preview goes round it");
            }

            // This is the output Surface we need to start the preview.
            mSurface = new Surface(texture);
        }

        /**
         * Points the camera at the renderer, and the renderer at the view.
         *
         * <p>The same size for both, and the frame drawn with the transform the camera itself
         * reports - which is what the preview would have been given had it come straight from
         * the camera. So the view transform that was already orienting the preview still does,
         * and going through GL changes nothing about where the picture sits.</p>
         *
         * @return whether the camera has somewhere to write
         */
        private boolean initializeThroughGl(@NonNull FrameRenderThread effect,
                @NonNull SurfaceTexture preview) {
            SurfaceTexture input = effect.getInputSurfaceTexture();
            if (input == null) {
                return false;
            }
            Size size = new Size(getWidth(), getHeight());
            effect.setInputBufferSize(size);
            mSurface = new Surface(input);
            effect.addOutput(this, new Surface(preview), size, size,
                    GlMath.ScaleType.FILL_CENTER,
                    (surfaceTextureMatrix, into) ->
                            System.arraycopy(surfaceTextureMatrix, 0, into, 0, into.length));
            return true;
        }

        @Override
        Surface getSurface() {
            return mSurface;
        }

        @Override
        void close() {
            if (mThroughGl) {
                FrameRenderThread effect = mCameraView.getEffect();
                if (effect != null) {
                    // Before the surface goes, because it is being drawn into, and removing an
                    // output releases the one it was drawing into.
                    effect.removeOutput(this);
                }
                mThroughGl = false;
            }
            if (mSurface != null) {
                mSurface.release();
                mSurface = null;
            }
        }
    }
}
