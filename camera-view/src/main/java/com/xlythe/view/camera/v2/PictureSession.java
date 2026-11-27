package com.xlythe.view.camera.v2;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
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

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.Exif;
import com.xlythe.view.camera.ExifCaptureParameters;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import static com.xlythe.view.camera.ICameraModule.DEBUG;
import static com.xlythe.view.camera.ICameraModule.TAG;

@TargetApi(21)
class PictureSession extends PreviewSession {
    /**
     * Supports {@link ImageFormat#JPEG} and {@link ImageFormat#YUV_420_888}. You can support
     * larger sizes with YUV_420_888, at the cost of speed.
     */
    private static final int IMAGE_FORMAT_DEFAULT = ImageFormat.JPEG;
    private static final int IMAGE_FORMAT_LOW = IMAGE_FORMAT_DEFAULT;
    private static final int IMAGE_FORMAT_MEDIUM = IMAGE_FORMAT_DEFAULT;
    private static final int IMAGE_FORMAT_HIGH = IMAGE_FORMAT_DEFAULT;

    /**
     * Note: Historically, we used YUV_420_888 for MAX. JPEG does not play well with other surfaces
     * at high resolutions. However, we found a solution where JPEG does work (up to its max
     * supported resolution); limit the the preview to 1080p. The preview has other reasons for
     * being restricted to 1080p (it crashes the Nexus 5X for one), but this adds yet another reason
     * we mustn't let the preview go any higher.
     */
    private static final int IMAGE_FORMAT_MAX = IMAGE_FORMAT_DEFAULT;

    /** How long a flash picture waits on metering before it is taken regardless. */
    @VisibleForTesting
    static final long METERING_TIMEOUT_MILLIS = 3_000;

    /** Frames after the trigger in which metering has to start, for a camera that skips it. */
    private static final int METERING_START_FRAMES = 3;

    private final PictureSurface mPictureSurface;

    /** A flash picture waiting on auto-exposure to meter, or null. */
    @Nullable
    private volatile Metering mMetering;

    PictureSession(Camera2Module camera2Module) {
        super(camera2Module);
        mPictureSurface = new PictureSurface(camera2Module, getPreviewSurface());
    }

    @Override
    public void initialize(@NonNull StreamConfigurationMap map) throws CameraAccessException {
        super.initialize(map);
        // Both the still size and anything a subclass adds are chosen from the preview's, so the
        // hook goes between the two: a recording session sizes its video here and the still is
        // then capped to it.
        onPreviewInitialized(map);
        if (hasStillCapture()) {
            mPictureSurface.initialize(map, maxPictureSize(), allowsUltraHdr());
        }
    }

    /** Lets a subclass add its own outputs once the preview has a size and before the still does. */
    protected void onPreviewInitialized(@NonNull StreamConfigurationMap map)
            throws CameraAccessException {}

    /**
     * Whether this session carries a still-capture output at all.
     *
     * <p>A session that cannot take a picture does not pay for the reader or the stream slot.</p>
     */
    protected boolean hasStillCapture() {
        return true;
    }

    /**
     * The largest still this session may ask for, or null for the largest the camera offers.
     *
     * <p>Only a recording session needs this: the combination of a preview, a recording and a
     * still is guaranteed by Camera2 at the recording size, not at the sensor's maximum.</p>
     */
    @Nullable
    protected Size maxPictureSize() {
        return null;
    }

    /**
     * Whether the still may be Ultra HDR. Camera2 verifies JPEG_R beside a preview alone, so a
     * session that also feeds an encoder keeps its still as JPEG.
     */
    protected boolean allowsUltraHdr() {
        return true;
    }

    @NonNull
    @Override
    public List<Surface> getSurfaces() {
        List<Surface> surfaces = super.getSurfaces();
        if (hasStillCapture()) {
            surfaces.add(mPictureSurface.getSurface());
        }
        return surfaces;
    }

    @Override
    public void close() {
        super.close();
        Metering metering = mMetering;
        if (metering != null) {
            mMetering = null;
            getHandler().removeCallbacks(metering);
        }
        mPictureSurface.close();
    }

    /** Whether a flash picture may run auto-exposure's metering, with its pre-flash, first. */
    protected boolean metersForFlash() {
        return true;
    }

    /**
     * Whether the flash fires for the next picture: always when it is on, and for auto flash
     * when the preview's exposure has said it needs to.
     */
    @VisibleForTesting
    static boolean flashWillFire(
            boolean hasFlash, @NonNull CameraView.Flash flash, @Nullable Integer aeState) {
        if (!hasFlash) {
            return false;
        }
        switch (flash) {
            case ON:
                return true;
            case AUTO:
                return aeState != null && aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED;
            default:
                return false;
        }
    }

    @Override
    void onPreviewCaptured(@NonNull CaptureResult result) {
        super.onPreviewCaptured(result);
        Metering metering = mMetering;
        if (metering != null && metering.mTriggered) {
            metering.onResult(result);
        }
    }

    void takePicture(@NonNull File file, @NonNull CameraDevice device, @NonNull CameraCaptureSession session) {
        if (!hasStillCapture()) {
            Log.w(TAG, "This camera cannot take a picture in its current configuration");
            onImageFailed();
            return;
        }
        if (mMetering != null) {
            Log.w(TAG, "Ignoring a picture asked for while another meters for its flash");
            onImageFailed();
            return;
        }
        mPictureSurface.initializePicture(file, exifCaptureParameters());
        if (flashWillFire(hasFlash(), getFlash(), getAeState())) {
            if (metersForFlash()) {
                meterForFlash(device, session);
            } else {
                captureStill(device, session, false /* fromBuffer */, false /* metered */);
            }
        } else {
            captureStill(device, session, true /* fromBuffer */, false /* metered */);
        }
    }

    /**
     * Sends the still request.
     *
     * @param fromBuffer whether the picture may be a frame the camera already has, taken as the
     *     request was made rather than after it arrived. Never for a flash picture, whose frame
     *     has to be lit by the flash.
     * @param metered whether a precapture sequence ran for this picture, to be ended after it
     */
    private void captureStill(@NonNull CameraDevice device, @NonNull CameraCaptureSession session,
            boolean fromBuffer, boolean metered) {
        try {
            CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            applyFlash(builder);
            builder.addTarget(mPictureSurface.getSurface());
            if (mMeteringRectangle != null) {
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{mMeteringRectangle});
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{mMeteringRectangle});
            }
            applyZoom(builder);
            builder.set(CaptureRequest.JPEG_QUALITY, (byte) getPictureQuality());
            if (Build.VERSION.SDK_INT >= 26 && isZeroShutterLagSupported()) {
                // Set either way, since some still templates turn it on themselves.
                builder.set(CaptureRequest.CONTROL_ENABLE_ZSL, fromBuffer);
            }
            session.capture(builder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureStarted(@NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request, long timestamp, long frameNumber) {
                    onShutter();
                }

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    if (metered) {
                        endMetering(device, session);
                    }
                }
            }, getHandler());
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            // Crashes if the Camera is interacted with while still loading
            Log.e(TAG, "Failed to create capture request", e);
            onImageFailed();
        }
    }

    /**
     * Starts auto-exposure's precapture sequence, which meters the scene with a pre-flash, and
     * takes the picture once it finishes. Without it the flash fires on an exposure chosen for
     * the dark.
     */
    private void meterForFlash(@NonNull CameraDevice device, @NonNull CameraCaptureSession session) {
        Metering metering = new Metering(device, session);
        try {
            CaptureRequest.Builder builder = newPreviewRequest(device);
            builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            mMetering = metering;
            getHandler().postDelayed(metering, METERING_TIMEOUT_MILLIS);
            session.capture(builder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    metering.mTriggered = true;
                    metering.onResult(result);
                }

                @Override
                public void onCaptureFailed(@NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
                    Log.w(TAG, "Metering for the flash failed; taking the picture without it");
                    metering.finish();
                }
            }, getHandler());
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            Log.e(TAG, "Failed to meter for the flash", e);
            mMetering = null;
            getHandler().removeCallbacks(metering);
            onImageFailed();
        }
    }

    /**
     * Ends a precapture sequence once its picture is taken. A camera may hold the exposure it
     * metered for the flash until told otherwise, which would leave the preview stuck on it.
     */
    private void endMetering(@NonNull CameraDevice device, @NonNull CameraCaptureSession session) {
        if (Build.VERSION.SDK_INT < 23) {
            return;
        }
        try {
            CaptureRequest.Builder builder = newPreviewRequest(device);
            builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL);
            session.capture(builder.build(), null /* callback */, getHandler());
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            Log.w(TAG, "Failed to end metering for the flash", e);
        }
    }

    /**
     * A flash picture waiting on auto-exposure's precapture sequence.
     *
     * <p>Runs as the timeout too: a camera that never finishes metering still takes the
     * picture, late, rather than never.</p>
     */
    private final class Metering implements Runnable {
        private final CameraDevice mDevice;
        private final CameraCaptureSession mSession;

        /** Whether the trigger's own frame is back. Results before it predate the sequence. */
        volatile boolean mTriggered;

        /** Whether auto-exposure has been seen metering. */
        private boolean mStarted;

        /** Frames since the trigger in which metering has not started. */
        private int mFramesWaiting;

        Metering(@NonNull CameraDevice device, @NonNull CameraCaptureSession session) {
            mDevice = device;
            mSession = session;
        }

        void onResult(@NonNull CaptureResult result) {
            Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
            if (aeState == null) {
                // A camera that does not report its exposure cannot be waited on.
                finish();
            } else if (aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                mStarted = true;
            } else if (mStarted || ++mFramesWaiting >= METERING_START_FRAMES) {
                finish();
            }
        }

        @Override
        public void run() {
            if (mMetering == this) {
                Log.w(TAG, "Metering for the flash timed out; taking the picture anyway");
                finish();
            }
        }

        void finish() {
            if (mMetering != this) {
                return;
            }
            mMetering = null;
            getHandler().removeCallbacks(this);
            captureStill(mDevice, mSession, false /* fromBuffer */, true /* metered */);
        }
    }

    /**
     * Package-private, and so is the conversion below it, so that the planes-to-NV21 arithmetic
     * can be asserted directly. It is the kind of code that fails by tinting or striping an
     * image rather than by throwing, which no end-to-end assertion notices.
     */
    static class ImageSaver extends AsyncTask<Void, Void, Boolean> {
        // The application, whose name and version the picture records as its software
        private final Context mContext;

        // Where the picture was taken, or null to leave it untagged
        @Nullable
        private final Location mLocation;

        // The image that was captured
        private final Image mImage;

        // The orientation of the image
        private final int mOrientation;

        // If true, the picture taken is reversed and needs to be flipped.
        // Typical with front facing cameras.
        private final boolean mIsReversed;

        // The file to save the image to
        private final File mFile;

        // How the picture was taken, for the tags the camera's JPEG leaves out
        private final ExifCaptureParameters mExifParameters;

        ImageSaver(Context context, @Nullable Location location, Image image, int orientation,
                boolean reversed, File file, ExifCaptureParameters exifParameters) {
            mContext = context.getApplicationContext();
            mLocation = location;
            mImage = image;
            mOrientation = orientation;
            mIsReversed = reversed;
            mFile = file;
            mExifParameters = exifParameters;
        }

        /** @return true only when the picture reached disk, so a failure is never reported as one. */
        @WorkerThread
        @Override
        protected Boolean doInBackground(Void... params) {
            // Finally, we save the file to disk
            byte[] bytes;
            try {
                bytes = getBytes();
            } finally {
                mImage.close();
            }
            if (bytes == null) {
                Log.e(TAG, "Failed to get bytes from image");
                return false;
            }
            try (FileOutputStream output = new FileOutputStream(mFile)) {
                output.write(bytes);
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "Failed to write the file", e);
                // Leave no truncated file behind for a caller that is told the capture failed.
                if (mFile.exists() && !mFile.delete()) {
                    Log.w(TAG, "Failed to clean up a partially written picture");
                }
                return false;
            }

            try {
                Exif exif = new Exif(mFile);
                exif.attachTimestamp();
                exif.attachCaptureDevice(mContext);
                exif.attachThumbnail();
                mExifParameters.applyTo(exif);
                exif.rotate(mOrientation);
                if (mIsReversed) {
                    exif.flipHorizontally();
                }
                if (mLocation != null) {
                    exif.attachLocation(mLocation);
                }
                exif.save();
            } catch (IOException e) {
                Log.e(TAG, "Failed to update EXIF", e);
            }

            return true;
        }

        private byte[] getBytes() {
            return toByteArray(mImage);
        }

        private static byte[] toByteArray(Image image) {
            byte[] data = null;
            if (image.getFormat() == ImageFormat.JPEG
                    || (Build.VERSION.SDK_INT >= 34 && image.getFormat() == ImageFormat.JPEG_R)) {
                Image.Plane[] planes = image.getPlanes();
                ByteBuffer buffer = planes[0].getBuffer();
                data = new byte[buffer.capacity()];
                buffer.get(data);
                return data;
            } else if (image.getFormat() == ImageFormat.YUV_420_888) {
                data = NV21toJPEG(
                        YUV_420_888toNV21(image),
                        image.getWidth(), image.getHeight());
            } else {
                Log.w(TAG, "Unrecognized image format: " + image.getFormat());
            }
            return data;
        }

        static byte[] YUV_420_888toNV21(Image image) {
            Image.Plane yPlane = image.getPlanes()[0];
            Image.Plane uPlane = image.getPlanes()[1];
            Image.Plane vPlane = image.getPlanes()[2];

            ByteBuffer yBuffer = yPlane.getBuffer();
            ByteBuffer uBuffer = uPlane.getBuffer();
            ByteBuffer vBuffer = vPlane.getBuffer();

            int ySize = yBuffer.remaining();
            int uSize = uBuffer.remaining();
            int vSize = vBuffer.remaining();

            if (DEBUG) {
                Log.d(TAG, String.format("Image{width=%d, height=%d}",
                        image.getWidth(), image.getHeight()));
                Log.d(TAG, String.format("yPlane{size=%d, pixelStride=%d, rowStride=%d}",
                        ySize, yPlane.getPixelStride(), yPlane.getRowStride()));
                Log.d(TAG, String.format("uPlane{size=%d, pixelStride=%d, rowStride=%d}",
                        uSize, uPlane.getPixelStride(), uPlane.getRowStride()));
                Log.d(TAG, String.format("vPlane{size=%d, pixelStride=%d, rowStride=%d}",
                        vSize, vPlane.getPixelStride(), vPlane.getRowStride()));
            }

            int position = 0;
            byte[] nv21 = new byte[ySize + (image.getWidth() * image.getHeight() / 2)];

            // Add the full y buffer to the array. If rowStride > 1, some padding may be skipped.
            for (int row = 0; row < image.getHeight(); row++) {
                yBuffer.get(nv21, position, image.getWidth());
                position += image.getWidth();
                yBuffer.position(Math.min(ySize, yBuffer.position() - image.getWidth() + yPlane.getRowStride()));
            }

            int chromaHeight = image.getHeight() / 2;
            int chromaWidth = image.getWidth() / 2;
            int chromaGap = uPlane.getRowStride() - (chromaWidth * uPlane.getPixelStride());

            if (DEBUG) {
                Log.d(TAG, String.format("chromaHeight=%d", chromaHeight));
                Log.d(TAG, String.format("chromaWidth=%d", chromaWidth));
                Log.d(TAG, String.format("chromaGap=%d", chromaGap));
            }

            // Interleave the u and v frames, filling up the rest of the buffer
            for (int row = 0; row < chromaHeight; row++) {
                for (int col = 0; col < chromaWidth; col++) {
                    vBuffer.get(nv21, position++, 1);
                    uBuffer.get(nv21, position++, 1);
                    vBuffer.position(Math.min(vSize, vBuffer.position() - 1 + vPlane.getPixelStride()));
                    uBuffer.position(Math.min(uSize, uBuffer.position() - 1 + uPlane.getPixelStride()));
                }
                vBuffer.position(Math.min(vSize, vBuffer.position() + chromaGap));
                uBuffer.position(Math.min(uSize, uBuffer.position() + chromaGap));
            }

            if (DEBUG) {
                Log.d(TAG, String.format("nv21{size=%d, position=%d}", nv21.length, position));
            }

            return nv21;
        }

        private static byte[] NV21toJPEG(byte[] nv21, int width, int height) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
            yuv.compressToJpeg(new Rect(0, 0, width, height), 100, out);
            return out.toByteArray();
        }
    }

    private static final class PictureSurface extends CameraSurface {
        private List<Size> getSizes(StreamConfigurationMap map) {
            Size[] sizes = map.getOutputSizes(mImageFormat);

            // Special case for high resolution images (assuming, of course, quality was set to high)
            if (Build.VERSION.SDK_INT >= 23) {
                Size[] highResSizes = map.getHighResolutionOutputSizes(mImageFormat);
                if (highResSizes != null) {
                    sizes = concat(sizes, highResSizes);
                }
            }

            return Arrays.asList(sizes);
        }

        private final ImageReader.OnImageAvailableListener mOnImageAvailableListener = new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(final ImageReader reader) {
                try {
                    if (mFile != null) {
                        Image image = reader.acquireLatestImage();
                        if (image == null) {
                            Log.w(TAG, "OnImageAvailable called but no image available");
                            onImageFailed();
                            return;
                        }
                        final File file = mFile;
                        new ImageSaver(
                                mCameraView.getContext(),
                                getLocation(),
                                image,
                                mCameraView.getRelativeCameraOrientation(),
                                isUsingFrontFacingCamera(),
                                mFile,
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
                        mFile = null;
                    } else {
                        Log.w(TAG, "OnImageAvailable called but no file to write to");
                        onImageFailed();
                    }
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Failed to save image", e);
                    onImageFailed();
                }
            }
        };

        private final CameraSurface mPreviewSurface;
        private ImageReader mImageReader;
        private File mFile;

        /** How the picture for {@link #mFile} is being taken, described when it was asked for. */
        private ExifCaptureParameters mExifParameters = new ExifCaptureParameters();

        /** What {@link #mImageReader} was made for: JPEG, or JPEG_R for Ultra HDR. */
        private int mImageFormat = IMAGE_FORMAT_DEFAULT;

        PictureSurface(Camera2Module camera2Module, CameraSurface previewSurface) {
            super(camera2Module);
            mPreviewSurface = previewSurface;
        }

        @Override
        void initialize(StreamConfigurationMap map) {
            initialize(map, null /* maxSize */, true /* ultraHdrAllowed */);
        }

        void initialize(
                StreamConfigurationMap map, @Nullable Size maxSize, boolean ultraHdrAllowed) {
            if (DEBUG) Log.d(TAG, "Initializing PictureSession");
            mImageFormat = chooseImageFormat(ultraHdrAllowed);
            super.initialize(chooseSize(within(getSizes(map), maxSize), mPreviewSurface.mSize));
            if (mImageReader != null) {
                // Initialized again - a session can be, when the camera is reconfigured - and an
                // ImageReader holds native buffers the collector cannot reclaim, so the one being
                // replaced has to be handed back rather than dropped.
                mImageReader.close();
            }
            mImageReader = ImageReader.newInstance(
                    getWidth(), getHeight(), mImageFormat, 1 /* maxImages */);
            mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mCameraView.getHandler());
        }

        /**
         * JPEG_R where Ultra HDR is asked for and this camera writes it. Asked again each time,
         * because a lens or colour space that changed since the request may not support it.
         */
        private int chooseImageFormat(boolean ultraHdrAllowed) {
            if (ultraHdrAllowed && Build.VERSION.SDK_INT >= 34
                    && mCameraView.getPictureFormat() == CameraView.PictureFormat.ULTRA_HDR
                    && mCameraView.isPictureFormatSupported(CameraView.PictureFormat.ULTRA_HDR)) {
                return ImageFormat.JPEG_R;
            }
            return getImageFormat(getQuality());
        }

        void initializePicture(File file, ExifCaptureParameters exifParameters) {
            mFile = file;
            mExifParameters = exifParameters;
            if (mFile.exists()) {
                Log.w(TAG, "File already exists. Deleting.");
                if (!mFile.delete()) {
                    Log.w(TAG, "Failed to delete existing file.");
                }
            }
        }

        @Override
        Surface getSurface() {
            return mImageReader.getSurface();
        }

        @Override
        void close() {
            if (mImageReader != null) {
                mImageReader.close();
                mImageReader = null;
            }
            if (mFile != null) {
                onImageFailed();
                mFile = null;
            }
        }
    }

    private static int getImageFormat(CameraView.Quality quality) {
        switch (quality) {
            case LOW:
                return IMAGE_FORMAT_LOW;
            case MEDIUM:
                return IMAGE_FORMAT_MEDIUM;
            case HIGH:
                return IMAGE_FORMAT_HIGH;
            case MAX:
                return IMAGE_FORMAT_MAX;
            default:
                return IMAGE_FORMAT_DEFAULT;
        }
    }
}
