package com.xlythe.view.camera.v2;

import android.annotation.TargetApi;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureFailure;
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

import com.xlythe.view.camera.Burst;
import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.Exif;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
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

    /**
     * How many burst pictures the camera is asked for at once: enough that the next is always
     * queued while the last is encoded, and few enough that little is still to come once the
     * burst is stopped.
     */
    static final int BURST_PICTURES_IN_FLIGHT = 3;

    private final PictureSurface mPictureSurface;

    /** The burst being taken, or null when none is. */
    @Nullable
    private Burst mBurst;

    /** What every request of the burst is built from, tagged with its picture's file. */
    @Nullable
    private CaptureRequest.Builder mBurstRequest;

    /** The capture session the burst runs on, which each further request goes to. */
    @Nullable
    private CameraCaptureSession mBurstSession;

    /** Hears of burst pictures the camera could not take, which no frame will arrive for. */
    private final CameraCaptureSession.CaptureCallback mBurstCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureFailed(@NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
                    if (!failure.wasImageCaptured()) {
                        onBurstPictureLost(request);
                    }
                }

                @Override
                public void onCaptureBufferLost(@NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request, @NonNull Surface target,
                        long frameNumber) {
                    if (mPictureSurface.isSurface(target)) {
                        onBurstPictureLost(request);
                    }
                }
            };

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
        mPictureSurface.close();
    }

    void takePicture(@NonNull File file, @NonNull CameraDevice device, @NonNull CameraCaptureSession session) {
        if (!hasStillCapture() || mBurst != null) {
            Log.w(TAG, "This camera cannot take a picture in its current configuration");
            onImageFailed();
            return;
        }
        mPictureSurface.initializePicture(file);
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
            session.capture(builder.build(), null /* callback */, getHandler());
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            // Crashes if the Camera is interacted with while still loading
            Log.e(TAG, "Failed to create capture request", e);
            onImageFailed();
        }
    }

    /**
     * Takes a burst as a run of still requests, a few at a time, each asked for as an earlier one
     * arrives. A repeating request would be simpler to start, but its frames cannot be told apart
     * from the ones still in flight once it stops, while each of these carries its own file.
     *
     * <p>The preview is a target of every request, because the camera serves queued requests ahead
     * of the repeating one and the preview would otherwise freeze for the whole burst.</p>
     */
    void startBurst(@NonNull Burst burst, @NonNull CameraDevice device,
            @NonNull CameraCaptureSession session) {
        if (!hasStillCapture() || mBurst != null) {
            Log.w(TAG, "This camera cannot take a burst in its current configuration");
            burst.end();
            return;
        }
        CaptureRequest.Builder builder;
        try {
            builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            Log.e(TAG, "Failed to create a burst request", e);
            burst.end();
            return;
        }
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        // The flash cannot recharge between pictures, and metering for it would hold up each one.
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
        if (mMeteringRectangle != null) {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{mMeteringRectangle});
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{mMeteringRectangle});
        }
        applyZoom(builder);
        applyFaceDetection(builder);
        builder.set(CaptureRequest.JPEG_QUALITY, (byte) getPictureQuality());
        builder.addTarget(getPreviewSurface().getSurface());
        builder.addTarget(mPictureSurface.getSurface());

        mBurst = burst;
        mBurstRequest = builder;
        mBurstSession = session;
        burst.whenFinished(() -> {
            if (mBurst == burst) {
                mBurst = null;
                mBurstRequest = null;
                mBurstSession = null;
            }
        });
        mPictureSurface.startBurst(burst, this::requestBurstPicture);
        for (int i = 0; i < BURST_PICTURES_IN_FLIGHT; i++) {
            requestBurstPicture();
        }
    }

    /** Asks the camera for the burst's next picture, unless it has all it is going to take. */
    private void requestBurstPicture() {
        Burst burst = mBurst;
        CaptureRequest.Builder builder = mBurstRequest;
        CameraCaptureSession session = mBurstSession;
        if (burst == null || builder == null || session == null) {
            return;
        }
        File file = burst.nextFile();
        if (file == null) {
            return;
        }
        builder.setTag(file);
        mPictureSurface.expectBurstPicture(file);
        try {
            session.capture(builder.build(), mBurstCallback, getHandler());
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            Log.e(TAG, "Failed to request a burst picture", e);
            if (mPictureSurface.forgetBurstPicture(file)) {
                burst.onPictureFailed(file);
            }
        }
    }

    /** A burst picture the camera dropped, which no frame will arrive for. */
    private void onBurstPictureLost(@NonNull CaptureRequest request) {
        Burst burst = mBurst;
        Object tag = request.getTag();
        if (burst == null || !(tag instanceof File)) {
            return;
        }
        File file = (File) tag;
        if (mPictureSurface.forgetBurstPicture(file)) {
            Log.w(TAG, "The camera dropped a burst picture");
            burst.onPictureFailed(file);
        }
    }

    /**
     * Package-private, and so is the conversion below it, so that the planes-to-NV21 arithmetic
     * can be asserted directly. It is the kind of code that fails by tinting or striping an
     * image rather than by throwing, which no end-to-end assertion notices.
     */
    static class ImageSaver extends AsyncTask<Void, Void, Boolean> {
        // Where the picture was taken, or null to leave it untagged
        @Nullable
        private final Location mLocation;

        // The image that was captured, or null when it was copied into mBytes already
        @Nullable
        private final Image mImage;

        // The encoded picture, when it was copied out of its frame up front, or null
        @Nullable
        private final byte[] mBytes;

        // The orientation of the image
        private final int mOrientation;

        // If true, the picture taken is reversed and needs to be flipped.
        // Typical with front facing cameras.
        private final boolean mIsReversed;

        // The file to save the image to
        private final File mFile;

        ImageSaver(@Nullable Location location, Image image, int orientation, boolean reversed,
                File file) {
            mLocation = location;
            mImage = image;
            mBytes = null;
            mOrientation = orientation;
            mIsReversed = reversed;
            mFile = file;
        }

        /**
         * Saves a picture already copied out of its frame. A burst copies each one out as it
         * arrives, so the reader never runs out of frames to hand the camera while saves queue.
         */
        ImageSaver(@Nullable Location location, @NonNull byte[] bytes, int orientation,
                boolean reversed, File file) {
            mLocation = location;
            mImage = null;
            mBytes = bytes;
            mOrientation = orientation;
            mIsReversed = reversed;
            mFile = file;
        }

        /** @return true only when the picture reached disk, so a failure is never reported as one. */
        @WorkerThread
        @Override
        protected Boolean doInBackground(Void... params) {
            // Finally, we save the file to disk
            byte[] bytes = mBytes;
            if (mImage != null) {
                try {
                    bytes = toByteArray(mImage);
                } finally {
                    mImage.close();
                }
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

        @Nullable
        static byte[] toByteArray(Image image) {
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
        /**
         * Frames the reader holds at once. A burst gives each back as soon as it is copied, so a
         * second is only there to cover a moment where the main thread is late to collect one.
         */
        private static final int MAX_IMAGES = 2;

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
                if (mBurst != null) {
                    onBurstImageAvailable(reader, mBurst);
                    return;
                }
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
                                getLocation(),
                                image,
                                mCameraView.getRelativeCameraOrientation(),
                                isUsingFrontFacingCamera(),
                                mFile) {
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

        /** The burst whose frames this reader collects, or null when none is running. */
        @Nullable
        private Burst mBurst;

        /** Where each burst frame the camera still owes goes, in the order they were asked for. */
        private final Deque<File> mBurstFiles = new ArrayDeque<>();

        /** Asks the camera for another burst picture, run as each one arrives. */
        @Nullable
        private Runnable mOnBurstFrame;

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
            // A frame left the camera for every one replaced, so a burst running on the old reader
            // will never see the rest of its pictures.
            abandonBurstPictures();
            mImageReader = ImageReader.newInstance(
                    getWidth(), getHeight(), mImageFormat, MAX_IMAGES);
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

        /** Collects the frames of a burst until it finishes, asking for more as each arrives. */
        void startBurst(@NonNull Burst burst, @NonNull Runnable onFrame) {
            mBurst = burst;
            mOnBurstFrame = onFrame;
            burst.whenFinished(() -> {
                if (mBurst == burst) {
                    mBurst = null;
                    mOnBurstFrame = null;
                }
            });
        }

        /** Notes where the next frame of the burst goes. Frames arrive in the order asked for. */
        void expectBurstPicture(@NonNull File file) {
            mBurstFiles.add(file);
        }

        /** Forgets a burst picture no frame will arrive for, if it was still owed. */
        boolean forgetBurstPicture(@NonNull File file) {
            return mBurstFiles.remove(file);
        }

        /** Whether a surface is this reader's, for the camera's word on a lost frame. */
        boolean isSurface(@NonNull Surface surface) {
            return mImageReader != null && mImageReader.getSurface() == surface;
        }

        /**
         * Copies a burst frame out and gives it back to the reader at once, then saves it in the
         * order it was taken. Asking for the next picture before saving keeps the camera busy.
         */
        private void onBurstImageAvailable(@NonNull ImageReader reader, @NonNull Burst burst) {
            Image image;
            try {
                image = reader.acquireNextImage();
            } catch (IllegalStateException e) {
                Log.e(TAG, "Failed to read a burst frame", e);
                return;
            }
            if (image == null) {
                return;
            }
            byte[] bytes;
            try {
                bytes = ImageSaver.toByteArray(image);
            } finally {
                image.close();
            }
            final File file = mBurstFiles.poll();
            if (file == null) {
                Log.w(TAG, "Dropped a frame the burst did not ask for");
                return;
            }
            if (bytes == null) {
                burst.onPictureFailed(file);
                return;
            }
            if (mOnBurstFrame != null) {
                mOnBurstFrame.run();
            }
            new ImageSaver(
                    getLocation(),
                    bytes,
                    mCameraView.getRelativeCameraOrientation(),
                    isUsingFrontFacingCamera(),
                    file) {
                @UiThread
                @Override
                protected void onPostExecute(Boolean saved) {
                    if (Boolean.TRUE.equals(saved)) {
                        burst.onPictureTaken(file);
                    } else {
                        burst.onPictureFailed(file);
                    }
                }
            }.executeOnExecutor(burst.getExecutor());
        }

        /** Fails the burst pictures still owed by a reader that is going away. */
        private void abandonBurstPictures() {
            Burst burst = mBurst;
            if (burst == null) {
                return;
            }
            File file;
            while ((file = mBurstFiles.poll()) != null) {
                burst.onPictureFailed(file);
            }
            burst.end();
        }

        void initializePicture(File file) {
            mFile = file;
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
            abandonBurstPictures();
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
