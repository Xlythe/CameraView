package com.xlythe.view.camera.v2;

import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.HdrSurface;
import com.xlythe.view.camera.TransformMath;
import com.xlythe.view.camera.gl.FrameRenderThread;
import com.xlythe.view.camera.gl.GlMath;

import androidx.annotation.CallSuper;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;

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
        mPreviewSurface.initialize(map, recordsHdr(), framesHdr());
        positionPreview();
    }

    @Override
    public void onLayoutChanged() throws CameraAccessException {
        positionPreview();
    }

    @Override
    public boolean awaitSurfaces(@NonNull Runnable whenReady) {
        return mPreviewSurface.awaitSurface(() -> {
            try {
                positionPreview();
            } catch (CameraAccessException e) {
                Log.w(TAG, "Unable to position the HDR preview", e);
            }
            whenReady.run();
        });
    }

    @Override
    public long getDynamicRangeProfile(@NonNull Surface surface) {
        return surface == mPreviewSurface.getSurface()
                ? mPreviewSurface.getDynamicRangeProfile() : super.getDynamicRangeProfile(surface);
    }

    /**
     * Whether the session records in HDR, which the preview then streams in too, shown as HDR
     * where the display can and tone-mapped where it cannot.
     */
    protected boolean recordsHdr() {
        return false;
    }

    /**
     * Whether what this session captures is HDR, so that the preview is worth showing in HDR on a
     * display that can: an HDR recording is being framed, or an Ultra HDR picture.
     */
    protected boolean framesHdr() {
        return getModule().recordsHdr();
    }

    /** Places the preview in the view, on whichever surface it is drawn. */
    private void positionPreview() throws CameraAccessException {
        if (!mPreviewSurface.hasSize()) {
            return;
        }
        HdrSurface.Lease lease = mPreviewSurface.getLease();
        if (lease == null) {
            transformPreview(mPreviewSurface.getWidth(), mPreviewSurface.getHeight());
            return;
        }
        // The compositor turns and mirrors a camera's stream on a SurfaceView itself, so only its
        // shape in the view is left to settle.
        Camera2Module module = getModule();
        lease.setTransform(TransformMath.surfaceViewTransform(mPreviewSurface.getWidth(),
                mPreviewSurface.getHeight(), module.getRelativeCameraOrientation(),
                module.getWidth(), module.getHeight()));
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

    /** Whether the preview is drawn on the HDR surface, for tests. */
    @VisibleForTesting
    boolean isOnHdrSurface() {
        return mPreviewSurface.getLease() != null;
    }

    private static final class PreviewSurface extends CameraSurface implements HdrSurface.Listener {
        /**
         * How long a session waits for the HDR surface before it shows the preview as SDR
         * instead. A surface that has not come in this long is in a window that is not showing.
         */
        private static final long HDR_SURFACE_TIMEOUT_MILLIS = 2_000;
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

        /** The dynamic range profile the camera streams the preview in. */
        private long mProfile = Camera2Module.STANDARD_PROFILE;

        /** Whether the session records in HDR, so the preview has to stream the same profile. */
        private boolean mRecordsHdr;

        /** The claim on the HDR surface while the preview is shown there, or null. */
        @Nullable
        private HdrSurface.Lease mLease;

        /** Whether {@link #mSurface} is the HDR surface, which belongs to its view, not to us. */
        private boolean mSurfaceIsHdr;

        /** Runs once the HDR surface is ready, while a session waits for it. */
        @Nullable
        private Runnable mWhenReady;

        /** Posts the timeout on the HDR surface. */
        private final Handler mHandler = new Handler(Looper.getMainLooper());

        /** Gives up waiting for the HDR surface, and shows the preview as SDR instead. */
        private final Runnable mTimeout = this::showOnTexture;

        PreviewSurface(Camera2Module cameraView) {
            super(cameraView);
        }

        @Override
        void initialize(StreamConfigurationMap map) {
            initialize(map, false, false);
        }

        /**
         * @param recordsHdr whether the session records in HDR, which the preview then streams in
         * @param framesHdr whether the session captures HDR, which makes the preview worth showing
         *     in HDR where the display can
         */
        void initialize(StreamConfigurationMap map, boolean recordsHdr, boolean framesHdr) {
            if (DEBUG) Log.d(TAG, "Initializing PreviewSession");
            mRecordsHdr = recordsHdr;
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

            if (Build.VERSION.SDK_INT >= 33) {
                mProfile = chooseProfile(recordsHdr, framesHdr);
                if (mProfile != Camera2Module.STANDARD_PROFILE && mCameraView.showsHdrPreview()) {
                    mLease = mCameraView.hdrSurface().acquire(getWidth(), getHeight(), this);
                    return;
                }
            }

            // This is the output Surface we need to start the preview.
            mSurface = new Surface(texture);
        }

        /**
         * The profile to stream the preview in: the recording's while the session records HDR,
         * HDR where HDR is captured and the display shows it, and otherwise 8-bit.
         */
        @RequiresApi(33)
        private long chooseProfile(boolean recordsHdr, boolean framesHdr) {
            long hdr = Camera2Module.hdrProfile(mCameraView.getCameraCharacteristics());
            if (hdr == Camera2Module.STANDARD_PROFILE) {
                return hdr;
            }
            if (recordsHdr) {
                return hdr;
            }
            if (!framesHdr || !mCameraView.showsHdrPreview()) {
                return Camera2Module.STANDARD_PROFILE;
            }
            int stillFormat = mCameraView.getPictureFormat() == CameraView.PictureFormat.ULTRA_HDR
                    ? ImageFormat.JPEG_R : ImageFormat.JPEG;
            if (!mCameraView.supportsHdrPreview(new Size(getWidth(), getHeight()), stillFormat)) {
                Log.d(TAG, "This camera cannot stream an HDR preview beside its still");
                return Camera2Module.STANDARD_PROFILE;
            }
            return hdr;
        }

        /** The profile the preview streams in. */
        long getDynamicRangeProfile() {
            return mProfile;
        }

        /** The claim on the HDR surface, or null while the preview is on the TextureView. */
        @Nullable
        HdrSurface.Lease getLease() {
            return mLease;
        }

        /**
         * Whether the preview has somewhere to go; if not, {@code whenReady} runs once it does.
         */
        @MainThread
        boolean awaitSurface(@NonNull Runnable whenReady) {
            if (mSurface != null || mThroughGl || mLease == null) {
                return true;
            }
            mWhenReady = whenReady;
            mHandler.postDelayed(mTimeout, HDR_SURFACE_TIMEOUT_MILLIS);
            return false;
        }

        @MainThread
        @Override
        public void onSurfaceReady(@NonNull Surface surface) {
            if (mLease == null) {
                return;
            }
            if (mSurface != null) {
                // The window hid the surface and showed it again; the session that wrote into
                // the old one is built again around this one.
                return;
            }
            mHandler.removeCallbacks(mTimeout);
            mSurface = surface;
            mSurfaceIsHdr = true;
            runWhenReady();
        }

        @MainThread
        @Override
        public void onSurfaceLost() {
            if (mLease == null || !mSurfaceIsHdr) {
                return;
            }
            mCameraView.onHdrSurfaceLost();
        }

        /** Shows the preview on the TextureView after all, when the HDR surface never came. */
        @MainThread
        private void showOnTexture() {
            if (mSurface != null || mLease == null) {
                return;
            }
            Log.w(TAG, "The HDR preview surface never came, so the preview shows as SDR");
            mLease.release();
            mLease = null;
            SurfaceTexture texture = mCameraView.getSurfaceTexture();
            if (texture == null) {
                return;
            }
            mSurface = new Surface(texture);
            // A recording shares the preview's profile, so it stays HDR, tone-mapped on screen.
            if (!mRecordsHdr) {
                mProfile = Camera2Module.STANDARD_PROFILE;
            }
            runWhenReady();
        }

        @MainThread
        private void runWhenReady() {
            Runnable whenReady = mWhenReady;
            mWhenReady = null;
            if (whenReady != null) {
                whenReady.run();
            }
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
            mHandler.removeCallbacks(mTimeout);
            mWhenReady = null;
            if (mSurface != null) {
                // The HDR surface belongs to its view, which releases it.
                if (!mSurfaceIsHdr) {
                    mSurface.release();
                }
                mSurface = null;
            }
            mSurfaceIsHdr = false;
            if (mLease != null) {
                mLease.release();
                mLease = null;
            }
            mProfile = Camera2Module.STANDARD_PROFILE;
        }
    }
}
