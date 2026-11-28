package com.xlythe.view.camera.v2;

import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraExtensionCharacteristics;
import android.hardware.camera2.CameraExtensionSession;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.ExtensionSessionConfiguration;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.AsyncTask;
import android.os.Build;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.UiThread;
import androidx.annotation.VisibleForTesting;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.ExifCaptureParameters;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static com.xlythe.view.camera.ICameraModule.DEBUG;
import static com.xlythe.view.camera.ICameraModule.TAG;

/**
 * Runs one of the phone maker's photo modes - their night mode, portrait blur and so on - behind
 * a preview and stills.
 *
 * <p>The mode runs in a session type of its own, which takes exactly two outputs, a preview and
 * a still, and reads only the request keys it lists. So this session carries nothing else: no
 * recording, no stream, and no flash, torch or exposure settings, since the mode decides on its
 * own light. Focus and zoom go on its requests wherever the mode says it reads them, which below
 * API 33 it has no way to say, so there they go on every request.</p>
 */
@RequiresApi(31)
class ExtensionSession extends SessionImpl {
    /** Hears whether the extension session could be configured. Called on the main thread. */
    interface Callback {
        @MainThread
        void onConfigured();

        @MainThread
        void onConfigureFailed();
    }

    /** The module, for the results each preview frame reports. */
    @NonNull
    private final Camera2Module mCamera2Module;

    /** The photo mode this runs. */
    @CameraView.PhotoMode
    private final int mPhotoMode;

    /** The platform's number for {@link #mPhotoMode}. */
    @ExtensionSupport.PlatformExtension
    private final int mExtension;

    /** What the camera says about the mode: its sizes, its zoom and the keys it reads. */
    @NonNull
    private final CameraExtensionCharacteristics mCharacteristics;

    /**
     * The request keys the mode reads, asked for once, or null below API 33, where it cannot say
     * and every key is sent.
     */
    @Nullable
    private final Set<CaptureRequest.Key> mRequestKeys;

    /** The preview's output. */
    @NonNull
    private final PreviewOutput mPreview;

    /** The still's output. */
    @NonNull
    private final StillOutput mStill;

    /** The extension session once the camera has configured it, and null before and after. */
    @Nullable
    private CameraExtensionSession mSession;

    /** Whether this session was closed, so that one the camera configures late is closed too. */
    private boolean mClosed;

    /** Whether the camera has been asked for the extension session and has not yet answered. */
    private boolean mConfiguring;

    /** Whether the preview and still outputs have been let go. */
    private boolean mOutputsReleased;

    /** Hears each preview frame's result, for the faces and focus it reports, from API 33. */
    private final CameraExtensionSession.ExtensionCaptureCallback mPreviewCallback =
            new CameraExtensionSession.ExtensionCaptureCallback() {
                @Override
                public void onCaptureResultAvailable(@NonNull CameraExtensionSession session,
                        @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    if (mCamera2Module.getFaceDetectMode() != null) {
                        mCamera2Module.onFacesCaptured(result);
                    }
                    mCamera2Module.onFocusCaptured(result);
                }
            };

    ExtensionSession(@NonNull Camera2Module camera2Module, @CameraView.PhotoMode int photoMode,
            @NonNull CameraExtensionCharacteristics characteristics) {
        super(camera2Module);
        mCamera2Module = camera2Module;
        mPhotoMode = photoMode;
        mExtension = ExtensionSupport.toPlatform(photoMode);
        mCharacteristics = characteristics;
        mRequestKeys = Build.VERSION.SDK_INT >= 33
                ? characteristics.getAvailableCaptureRequestKeys(mExtension)
                : null;
        mPreview = new PreviewOutput(camera2Module,
                characteristics.getExtensionSupportedSizes(mExtension, SurfaceTexture.class));
        List<Size> jpegSizes =
                characteristics.getExtensionSupportedSizes(mExtension, ImageFormat.JPEG);
        if (jpegSizes.isEmpty()) {
            // JPEG is what nearly every mode writes. One that only hands back YUV is compressed
            // here, the way a YUV still from an ordinary session would be.
            mStill = new StillOutput(camera2Module, mPreview, ImageFormat.YUV_420_888,
                    characteristics.getExtensionSupportedSizes(
                            mExtension, ImageFormat.YUV_420_888));
        } else {
            mStill = new StillOutput(camera2Module, mPreview, ImageFormat.JPEG, jpegSizes);
        }
    }

    /** The photo mode this runs. */
    @CameraView.PhotoMode
    int getPhotoMode() {
        return mPhotoMode;
    }

    /** Whether the mode reads a request key. Every mode reads JPEG quality. */
    @VisibleForTesting
    boolean reads(@NonNull CaptureRequest.Key<?> key) {
        return key == CaptureRequest.JPEG_QUALITY || mRequestKeys == null
                || mRequestKeys.contains(key);
    }

    @Override
    public void initialize(@NonNull StreamConfigurationMap map) throws CameraAccessException {
        mPreview.initialize(map);
        transformPreview(mPreview.getWidth(), mPreview.getHeight());
        mStill.initialize(map);
    }

    @Override
    public void onLayoutChanged() throws CameraAccessException {
        transformPreview(mPreview.getWidth(), mPreview.getHeight());
    }

    @NonNull
    @Override
    public List<Surface> getSurfaces() {
        List<Surface> surfaces = super.getSurfaces();
        surfaces.add(mPreview.getSurface());
        surfaces.add(mStill.getSurface());
        return surfaces;
    }

    /**
     * The zoom ratios the mode allows, which from API 35 it can list for itself and which need
     * not be the camera's: a night mode may not reach the ultra-wide. Null where it lists none,
     * for the camera's own range.
     */
    @Nullable
    @Override
    public Range<Float> getZoomRatioRange() {
        if (Build.VERSION.SDK_INT < 35) {
            return null;
        }
        try {
            Range<Float> range = mCharacteristics.get(
                    mExtension, CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            return range == null || range.getUpper() < range.getLower() ? null : range;
        } catch (IllegalArgumentException error) {
            if (DEBUG) Log.d(TAG, "The photo mode does not list its zoom", error);
            return null;
        }
    }

    /** Asks the camera for an extension session over this session's preview and still. */
    @MainThread
    void configure(@NonNull CameraDevice device, @NonNull Callback callback)
            throws CameraAccessException {
        List<OutputConfiguration> outputs = Arrays.asList(
                new OutputConfiguration(mPreview.getSurface()),
                new OutputConfiguration(mStill.getSurface()));
        mConfiguring = true;
        device.createExtensionSession(new ExtensionSessionConfiguration(mExtension, outputs,
                getHandler()::post, new CameraExtensionSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraExtensionSession session) {
                        mConfiguring = false;
                        if (mClosed) {
                            // The outputs go once this one has closed too.
                            closeQuietly(session);
                            return;
                        }
                        mSession = session;
                        callback.onConfigured();
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraExtensionSession session) {
                        mConfiguring = false;
                        if (mClosed) {
                            releaseOutputs();
                            return;
                        }
                        callback.onConfigureFailed();
                    }

                    @Override
                    public void onClosed(@NonNull CameraExtensionSession session) {
                        releaseOutputs();
                    }
                }));
    }

    /** Whether the camera has configured this session and it has not been closed since. */
    boolean isConfigured() {
        return mSession != null;
    }

    /** Starts the preview, or sends it again with whatever has changed since. */
    @MainThread
    void start(@NonNull CameraDevice device) throws CameraAccessException {
        CameraExtensionSession session = mSession;
        if (session == null) {
            throw new IllegalStateException("The extension session is not configured");
        }
        session.setRepeatingRequest(previewRequest(device), getHandler()::post, mPreviewCallback);
    }

    /** Stops the preview. */
    @MainThread
    void stop() throws CameraAccessException {
        CameraExtensionSession session = mSession;
        if (session != null) {
            session.stopRepeating();
        }
    }

    /** Never called: an extension session takes its requests through {@link #start}. */
    @Override
    public void onAvailable(@NonNull CameraDevice device, @NonNull CameraCaptureSession session) {
        throw new UnsupportedOperationException("A photo mode runs its own session");
    }

    /** Never called: an extension session takes its requests through {@link #start}. */
    @Override
    public void onInvalidate(@NonNull CameraDevice device, @NonNull CameraCaptureSession session) {
        throw new UnsupportedOperationException("A photo mode runs its own session");
    }

    /**
     * Takes a picture through the mode. Refused for RAW, which a mode never writes; Ultra HDR
     * falls back to the mode's own JPEG, as it does for a drawn picture.
     */
    @MainThread
    void takePicture(@NonNull File file, @NonNull CameraDevice device) {
        CameraExtensionSession session = mSession;
        if (PictureSession.writesRaw(getPictureFormat())) {
            Log.w(TAG, "A photo mode cannot capture " + getPictureFormat());
            onImageFailed();
            return;
        }
        if (session == null || mStill.isBusy()) {
            Log.w(TAG, "Ignoring a picture asked for while the photo mode is busy");
            onImageFailed();
            return;
        }
        mStill.initializePicture(file, exifCaptureParameters());
        try {
            CaptureRequest.Builder builder =
                    device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(mStill.getSurface());
            builder.set(CaptureRequest.JPEG_QUALITY, (byte) getPictureQuality());
            applyFocusAndZoom(builder);
            session.capture(builder.build(), getHandler()::post, new StillCallback(mStill));
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            Log.e(TAG, "Failed to take a picture through the photo mode", e);
            mStill.fail();
        }
    }

    @NonNull
    private CaptureRequest previewRequest(@NonNull CameraDevice device)
            throws CameraAccessException {
        CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        builder.addTarget(mPreview.getSurface());
        applyFocusAndZoom(builder);
        if (reads(CaptureRequest.STATISTICS_FACE_DETECT_MODE)) {
            applyFaceDetection(builder);
        }
        return builder.build();
    }

    /** Puts focus and zoom on a request, each only where the mode reads it. */
    @VisibleForTesting
    void applyFocusAndZoom(@NonNull CaptureRequest.Builder builder) {
        if (reads(CaptureRequest.CONTROL_AF_MODE)) {
            builder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        }
        if (mMeteringRectangle != null) {
            MeteringRectangle[] regions = {mMeteringRectangle};
            if (reads(CaptureRequest.CONTROL_AF_REGIONS)) {
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, regions);
            }
            if (reads(CaptureRequest.CONTROL_AE_REGIONS)) {
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, regions);
            }
        }
        if (mZoomRatio != null && reads(CaptureRequest.CONTROL_ZOOM_RATIO)) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, mZoomRatio);
        } else if (mCropRegion != null && reads(CaptureRequest.SCALER_CROP_REGION)) {
            builder.set(CaptureRequest.SCALER_CROP_REGION, mCropRegion);
        }
    }

    /**
     * Closes the extension session, and fails a picture still being taken.
     *
     * <p>The outputs outlive the call: the phone maker's processing goes on setting the session up
     * on a thread of its own, and an output closed under it ends the app with an exception thrown
     * on that thread. So they go once the camera says the session has closed, or that it was
     * never configured.</p>
     */
    @Override
    public void close() {
        super.close();
        mClosed = true;
        mStill.fail();
        CameraExtensionSession session = mSession;
        mSession = null;
        if (session != null) {
            closeQuietly(session);
        } else if (!mConfiguring) {
            releaseOutputs();
        }
    }

    /** Lets go of the preview and still outputs, once. */
    @MainThread
    private void releaseOutputs() {
        if (mOutputsReleased) {
            return;
        }
        mOutputsReleased = true;
        mPreview.close();
        mStill.close();
    }

    /** Whether the outputs have been let go, which waits for the session to close. */
    @VisibleForTesting
    boolean areOutputsReleased() {
        return mOutputsReleased;
    }

    private static void closeQuietly(@NonNull CameraExtensionSession session) {
        try {
            session.close();
        } catch (CameraAccessException | RuntimeException e) {
            Log.w(TAG, "Failed to close the extension session", e);
        }
    }

    /** Hears what came of one picture: its shutter, or that it failed. */
    private final class StillCallback extends CameraExtensionSession.ExtensionCaptureCallback {
        /** The output the picture arrives in, failed once if the capture is. */
        @NonNull
        private final StillOutput mOutput;

        StillCallback(@NonNull StillOutput output) {
            mOutput = output;
        }

        @Override
        public void onCaptureStarted(@NonNull CameraExtensionSession session,
                @NonNull CaptureRequest request, long timestamp) {
            onShutter();
        }

        @Override
        public void onCaptureFailed(@NonNull CameraExtensionSession session,
                @NonNull CaptureRequest request) {
            mOutput.fail();
        }

        @Override
        public void onCaptureFailed(@NonNull CameraExtensionSession session,
                @NonNull CaptureRequest request, int failure) {
            mOutput.fail();
        }

        @Override
        public void onCaptureSequenceAborted(@NonNull CameraExtensionSession session,
                int sequenceId) {
            mOutput.fail();
        }
    }

    /** The preview, at a size the mode lists. */
    private static final class PreviewOutput extends CameraSurface {
        /** The sizes the mode can preview at. */
        @NonNull
        private final List<Size> mSizes;

        /** What the camera draws the preview into. */
        @Nullable
        private Surface mSurface;

        PreviewOutput(@NonNull Camera2Module camera2Module, @NonNull List<Size> sizes) {
            super(camera2Module);
            mSizes = sizes;
        }

        /**
         * Chooses as an ordinary preview does - the smallest size that covers the view, up to the
         * same cap - but from the sizes the mode lists, since the stream map's are not the mode's.
         */
        @Override
        void initialize(@NonNull StreamConfigurationMap map) {
            List<Size> candidates = filter(mSizes.toArray(new Size[0]));
            if (candidates.isEmpty()) {
                throw new IllegalStateException("The photo mode lists no preview size");
            }
            List<Size> covering = new ArrayList<>(candidates.size());
            for (Size size : candidates) {
                if (size.getWidth() >= mCameraView.getWidth()
                        && size.getHeight() >= mCameraView.getHeight()) {
                    covering.add(size);
                }
            }
            initialize(covering.isEmpty()
                    ? Collections.max(candidates, new CompareSizesByArea())
                    : Collections.min(covering, new CompareSizesByArea()));

            SurfaceTexture texture = mCameraView.getSurfaceTexture();
            if (texture == null) {
                throw new IllegalStateException(
                        "Expected a SurfaceTexture to exist, but none does");
            }
            texture.setDefaultBufferSize(getWidth(), getHeight());
            mSurface = new Surface(texture);
        }

        @Override
        Surface getSurface() {
            return mSurface;
        }

        @Override
        void close() {
            if (mSurface != null) {
                mSurface.release();
                mSurface = null;
            }
        }
    }

    /** The still, written and reported the way an ordinary session's is. */
    private static final class StillOutput extends CameraSurface {
        /** The preview, whose shape the still matches. */
        @NonNull
        private final CameraSurface mPreview;

        /** The format the mode writes: JPEG, or YUV where it offers nothing else. */
        private final int mFormat;

        /** The sizes the mode can write a still at. */
        @NonNull
        private final List<Size> mSizes;

        /** Where the mode's stills arrive. */
        @Nullable
        private SafeCloseImageReader mImageReader;

        /** Where the picture being taken goes, or null when none is. Main thread. */
        @Nullable
        private File mFile;

        /** How the picture for {@link #mFile} is being taken, for the tags the mode leaves out. */
        @NonNull
        private ExifCaptureParameters mExifParameters = new ExifCaptureParameters();

        StillOutput(@NonNull Camera2Module camera2Module, @NonNull CameraSurface preview,
                int format, @NonNull List<Size> sizes) {
            super(camera2Module);
            mPreview = preview;
            mFormat = format;
            mSizes = sizes;
        }

        @Override
        void initialize(@NonNull StreamConfigurationMap map) {
            super.initialize(chooseSize(mSizes, mPreview.mSize));
            mImageReader = new SafeCloseImageReader(getWidth(), getHeight(), mFormat, 1);
            mImageReader.setOnImageAvailableListener(this::onImageAvailable,
                    mCameraView.getHandler());
        }

        @MainThread
        boolean isBusy() {
            return mFile != null;
        }

        @MainThread
        void initializePicture(@NonNull File file, @NonNull ExifCaptureParameters exifParameters) {
            mFile = file;
            mExifParameters = exifParameters;
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Failed to delete an existing file before a picture");
            }
        }

        /** Reports the picture being taken as failed, once. */
        @MainThread
        void fail() {
            if (mFile != null) {
                mFile = null;
                onImageFailed();
            }
        }

        @MainThread
        private void onImageAvailable(@NonNull SafeCloseImageReader reader) {
            SafeCloseImageReader.Frame frame;
            try {
                frame = reader.acquireLatestImage();
            } catch (IllegalStateException e) {
                Log.e(TAG, "Failed to read the photo mode's picture", e);
                fail();
                return;
            }
            File file = mFile;
            mFile = null;
            if (frame == null || file == null) {
                Log.w(TAG, "The photo mode delivered a picture nobody was waiting for");
                if (frame != null) {
                    frame.close();
                }
                if (file != null) {
                    onImageFailed();
                }
                return;
            }
            new PictureSession.ImageSaver(getContext(), getLocation(), frame,
                    mCameraView.getRelativeCameraOrientation(), isUsingFrontFacingCamera(), file,
                    mExifParameters) {
                @UiThread
                @Override
                protected void onPostExecute(Boolean saved) {
                    if (Boolean.TRUE.equals(saved)) {
                        showImageConfirmation(file);
                    } else {
                        onImageFailed();
                    }
                }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }

        @AnyThread
        @Override
        Surface getSurface() {
            SafeCloseImageReader reader = mImageReader;
            return reader == null ? null : reader.getSurface();
        }

        @Override
        void close() {
            if (mImageReader != null) {
                mImageReader.close();
                mImageReader = null;
            }
            fail();
        }
    }
}
