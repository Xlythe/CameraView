package com.xlythe.view.camera.v2;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
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
import com.xlythe.view.camera.Captions;
import com.xlythe.view.camera.Exif;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;
import androidx.exifinterface.media.ExifInterface;

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

    private final PictureSurface mPictureSurface;

    /** The RAW output, or null for a session that writes no DNG. */
    @Nullable
    private final RawPictureSurface mRawSurface;

    PictureSession(Camera2Module camera2Module) {
        // The format is read once, here, rather than per capture: the RAW output is a surface of
        // the session's, so a session either carries one or it does not, and asking for a
        // different format rebuilds the session. The camera is asked again as well as the
        // format, because the lens can change under a format that was accepted for the other
        // one - a front-facing camera often has no RAW capability where the back one does.
        this(camera2Module, writesRaw(camera2Module.getPictureFormat())
                && camera2Module.isPictureFormatSupported(camera2Module.getPictureFormat()));
    }

    PictureSession(Camera2Module camera2Module, boolean capturesRaw) {
        super(camera2Module);
        mPictureSurface = new PictureSurface(camera2Module, getPreviewSurface());
        mRawSurface = capturesRaw ? new RawPictureSurface(camera2Module) : null;
    }

    /** Whether a format writes a DNG, with or without a JPEG beside it. */
    static boolean writesRaw(@NonNull CameraView.PictureFormat format) {
        return format == CameraView.PictureFormat.RAW
                || format == CameraView.PictureFormat.RAW_JPEG;
    }

    @Override
    public void initialize(@NonNull StreamConfigurationMap map) throws CameraAccessException {
        super.initialize(map);
        // Both the still size and anything a subclass adds are chosen from the preview's, so the
        // hook goes between the two: a recording session sizes its video here and the still is
        // then capped to it.
        onPreviewInitialized(map);
        if (hasStillCapture()) {
            mPictureSurface.initialize(map, maxPictureSize());
            if (mRawSurface != null) {
                mRawSurface.initialize(map);
            }
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

    @NonNull
    @Override
    public List<Surface> getSurfaces() {
        List<Surface> surfaces = super.getSurfaces();
        if (hasStillCapture()) {
            surfaces.add(mPictureSurface.getSurface());
            RawPictureSurface rawSurface = rawSurface();
            if (rawSurface != null) {
                surfaces.add(rawSurface.getSurface());
            }
        }
        return surfaces;
    }

    /** The RAW output when this session has one configured, else null. */
    @Nullable
    private RawPictureSurface rawSurface() {
        return mRawSurface != null && mRawSurface.isInitialized() ? mRawSurface : null;
    }

    @Override
    public void close() {
        super.close();
        mPictureSurface.close();
        if (mRawSurface != null) {
            mRawSurface.close();
        }
    }

    void takePicture(@NonNull File file, @NonNull CameraDevice device, @NonNull CameraCaptureSession session) {
        if (!hasStillCapture()) {
            Log.w(TAG, "This camera cannot take a picture in its current configuration");
            onImageFailed();
            return;
        }
        CameraView.PictureFormat format = getPictureFormat();
        RawPictureSurface rawSurface = writesRaw(format) ? rawSurface() : null;
        if (writesRaw(format) && rawSurface == null) {
            // Reached when the format changed while this session was already configured, and
            // while recording, where the preview, the recorder and a RAW stream together are
            // outside what Camera2 guarantees. Refusing is the honest answer: a caller who asked
            // for a DNG and silently got a JPEG has no way to tell.
            Log.w(TAG, "This session cannot capture " + format + "; refusing rather than writing"
                    + " a JPEG the caller did not ask for");
            onImageFailed();
            return;
        }
        boolean jpeg = rawSurface == null || format == CameraView.PictureFormat.RAW_JPEG;
        if (jpeg) {
            mPictureSurface.initializePicture(file);
        }
        if (rawSurface != null) {
            // RAW_JPEG writes both files from one shutter press, so the DNG goes beside the JPEG
            // under the same name; RAW writes only the DNG, so it takes the file it was given,
            // whatever it is called, which is what the CameraX backend does with it too.
            rawSurface.initializePicture(
                    jpeg ? Captions.sidecarFor(file, "dng") : file, !jpeg /* announces */);
        }
        try {
            CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            applyFlash(builder);
            if (jpeg) {
                builder.addTarget(mPictureSurface.getSurface());
            }
            if (rawSurface != null) {
                builder.addTarget(rawSurface.getSurface());
            }
            if (mMeteringRectangle != null) {
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{mMeteringRectangle});
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{mMeteringRectangle});
            }
            applyZoom(builder);
            builder.set(CaptureRequest.JPEG_QUALITY, (byte) getPictureQuality());
            // A DNG is written from the capture result as much as from the frame, so a RAW
            // capture is the one case that needs the callback at all.
            session.capture(builder.build(),
                    rawSurface == null ? null : rawSurface.captureCallback(), getHandler());
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            // Crashes if the Camera is interacted with while still loading
            Log.e(TAG, "Failed to create capture request", e);
            onImageFailed();
        }
    }

    /**
     * Package-private, and so is the conversion below it, so that the planes-to-NV21 arithmetic
     * can be asserted directly. It is the kind of code that fails by tinting or striping an
     * image rather than by throwing, which no end-to-end assertion notices.
     */
    static class ImageSaver extends AsyncTask<Void, Void, Boolean> {
        private final Context mContext;

        // The image that was captured
        private final Image mImage;

        // The orientation of the image
        private final int mOrientation;

        // If true, the picture taken is reversed and needs to be flipped.
        // Typical with front facing cameras.
        private final boolean mIsReversed;

        // The file to save the image to
        private final File mFile;

        ImageSaver(Context context, Image image, int orientation, boolean reversed, File file) {
            mContext = context.getApplicationContext();
            mImage = image;
            mOrientation = orientation;
            mIsReversed = reversed;
            mFile = file;
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
                exif.rotate(mOrientation);
                if (mIsReversed) {
                    exif.flipHorizontally();
                }
                Location location = SessionImpl.CameraSurface.getLocation(mContext);
                if (location != null) {
                    exif.attachLocation(location);
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
            if (image.getFormat() == ImageFormat.JPEG) {
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

    /**
     * Writes a DNG from a frame and the capture result that describes it.
     *
     * <p>Package-private, like {@link ImageSaver}, so what can be asserted off a device is
     * reachable from a test.</p>
     */
    static class DngSaver extends AsyncTask<Void, Void, Boolean> {
        private final Context mContext;
        private final CameraCharacteristics mCharacteristics;
        private final RawCapturePairer.Pairing<TotalCaptureResult> mPairing;
        private final File mFile;

        /** The EXIF orientation the DNG carries, which is how a reader knows which way is up. */
        private final int mOrientation;

        DngSaver(Context context, CameraCharacteristics characteristics,
                RawCapturePairer.Pairing<TotalCaptureResult> pairing, int orientation, File file) {
            mContext = context.getApplicationContext();
            mCharacteristics = characteristics;
            mPairing = pairing;
            mOrientation = orientation;
            mFile = file;
        }

        /** @return true only when the DNG reached disk, so a failure is never reported as one. */
        @WorkerThread
        @Override
        protected Boolean doInBackground(Void... params) {
            DngCreator creator;
            try {
                creator = new DngCreator(mCharacteristics, mPairing.metadata);
            } catch (RuntimeException e) {
                // Thrown when the result does not describe the sensor well enough to write a DNG
                // at all, which no amount of retrying changes.
                Log.e(TAG, "Failed to describe the raw frame", e);
                mPairing.image.close();
                return false;
            }
            try {
                creator.setOrientation(mOrientation);
                attachLocation(creator);
                try (FileOutputStream output = new FileOutputStream(mFile)) {
                    creator.writeImage(output, mPairing.image);
                }
                return true;
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "Failed to write the DNG", e);
                // Leave no truncated file behind for a caller that is told the capture failed.
                if (mFile.exists() && !mFile.delete()) {
                    Log.w(TAG, "Failed to clean up a partially written DNG");
                }
                return false;
            } finally {
                creator.close();
                mPairing.image.close();
            }
        }

        /**
         * Records where the picture was taken, when that is known.
         *
         * <p>A DNG cannot be reopened and amended the way {@link Exif} amends a JPEG, so the
         * location has to be in hand before the file is written. Its own failure is caught here
         * rather than with the write: a location DngCreator will not accept is a reason to write
         * a DNG without one, not a reason to lose the picture.</p>
         */
        private void attachLocation(@NonNull DngCreator creator) {
            Location location = SessionImpl.CameraSurface.getLocation(mContext);
            if (location == null) {
                return;
            }
            try {
                creator.setLocation(location);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Failed to record where the picture was taken", e);
            }
        }
    }

    /**
     * The EXIF orientation for a picture taken at this rotation, mirrored or not.
     *
     * <p>The same answer {@link Exif#rotate} followed by {@link Exif#flipHorizontally} reaches on
     * the JPEG path, which is what keeps the two files of a RAW_JPEG capture agreeing about which
     * way is up. It is computed rather than applied because DngCreator wants the orientation
     * before it writes, and a written DNG cannot be amended.</p>
     */
    static int exifOrientation(int degrees, boolean mirrored) {
        switch (((degrees % 360) + 360) % 360) {
            case 90:
                return mirrored
                        ? ExifInterface.ORIENTATION_TRANSPOSE
                        : ExifInterface.ORIENTATION_ROTATE_90;
            case 180:
                return mirrored
                        ? ExifInterface.ORIENTATION_FLIP_VERTICAL
                        : ExifInterface.ORIENTATION_ROTATE_180;
            case 270:
                return mirrored
                        ? ExifInterface.ORIENTATION_TRANSVERSE
                        : ExifInterface.ORIENTATION_ROTATE_270;
            case 0:
                // Fall-through: anything that is not a right angle cannot be expressed as an
                // orientation, and the JPEG path treats it as undefined rather than guessing.
            default:
                return mirrored
                        ? ExifInterface.ORIENTATION_FLIP_HORIZONTAL
                        : ExifInterface.ORIENTATION_NORMAL;
        }
    }

    /**
     * The RAW_SENSOR output a DNG is written from.
     *
     * <p>Beside the JPEG reader rather than instead of it: a camera that reports the RAW
     * capability guarantees a preview, a full-size JPEG and a full-size RAW together, so
     * configuring both costs nothing the device has not promised, and RAW_JPEG needs both at
     * once. Only the capture request decides which of them a given shutter press writes.</p>
     */
    private static final class RawPictureSurface extends CameraSurface {
        /**
         * How many frames, and how many capture results, may wait for their partner.
         *
         * <p>Matched to the reader's buffer count, because a frame waiting to be paired is a
         * buffer the reader has handed out: a larger bound would promise to hold frames the
         * reader cannot produce, and a smaller one would drop a frame the next callback is about
         * to claim.</p>
         */
        private static final int MAX_PENDING_CAPTURES = 2;

        private final RawCapturePairer<TotalCaptureResult> mPairer =
                new RawCapturePairer<>(MAX_PENDING_CAPTURES);

        @Nullable
        private ImageReader mImageReader;

        /** Where the next DNG goes, or null when no capture is outstanding. */
        @Nullable
        private File mFile;

        /**
         * Whether this surface reports the capture to the caller.
         *
         * <p>False for RAW_JPEG, where the JPEG beside it is the file the caller named and the
         * one it is told about: one shutter press is one notification, whatever it wrote.</p>
         */
        private boolean mAnnounces;

        private final ImageReader.OnImageAvailableListener mOnImageAvailableListener = reader -> {
            Image image;
            try {
                // acquireNextImage, not acquireLatestImage: a frame is claimed by the result with
                // its timestamp, so discarding the older frames would discard the one a result
                // already waiting here is about to pair with.
                image = reader.acquireNextImage();
            } catch (IllegalStateException e) {
                Log.e(TAG, "Failed to acquire a raw frame", e);
                return;
            }
            if (image == null) {
                Log.w(TAG, "OnImageAvailable called but no raw frame available");
                return;
            }
            RawCapturePairer.Pairing<TotalCaptureResult> pairing = mPairer.offerImage(image);
            if (pairing != null) {
                write(pairing);
            }
        };

        RawPictureSurface(Camera2Module camera2Module) {
            super(camera2Module);
        }

        @Override
        void initialize(StreamConfigurationMap map) {
            if (DEBUG) Log.d(TAG, "Initializing the RAW surface");
            Size[] sizes = map.getOutputSizes(ImageFormat.RAW_SENSOR);
            if (sizes == null || sizes.length == 0) {
                // Only reachable if the camera's capabilities and its configuration map disagree,
                // which is a camera to write JPEGs for rather than a reason to fail the session.
                Log.w(TAG, "Camera claims the RAW capability but offers no RAW_SENSOR size");
                return;
            }
            // Nothing to choose between: RAW is the sensor's own grid of readings, so the largest
            // size is the whole sensor and anything smaller is a crop of it.
            super.initialize(Collections.max(Arrays.asList(sizes), new CompareSizesByArea()));
            // Frames first, then the reader they came from: closing a reader invalidates the
            // frames it handed out, and a frame is only given back by being closed.
            mPairer.clear();
            if (mImageReader != null) {
                // Initialized again, as a session is when the camera is reconfigured. An
                // ImageReader holds native buffers the collector cannot reclaim, so the one being
                // replaced has to be handed back rather than dropped.
                mImageReader.close();
            }
            mImageReader = ImageReader.newInstance(getWidth(), getHeight(),
                    ImageFormat.RAW_SENSOR, MAX_PENDING_CAPTURES);
            mImageReader.setOnImageAvailableListener(
                    mOnImageAvailableListener, mCameraView.getHandler());
        }

        boolean isInitialized() {
            return mImageReader != null;
        }

        void initializePicture(@NonNull File file, boolean announces) {
            mFile = file;
            mAnnounces = announces;
            if (mFile.exists()) {
                Log.w(TAG, "File already exists. Deleting.");
                if (!mFile.delete()) {
                    Log.w(TAG, "Failed to delete existing file.");
                }
            }
        }

        /** Feeds the capture results to the pairer, which is the other half of every DNG. */
        @NonNull
        CameraCaptureSession.CaptureCallback captureCallback() {
            return new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
                    if (timestamp == null) {
                        // There is no other way to tell which frame this result describes, and
                        // DngCreator reads the frame through the result it is given.
                        Log.w(TAG, "A capture result arrived with no sensor timestamp");
                        return;
                    }
                    RawCapturePairer.Pairing<TotalCaptureResult> pairing =
                            mPairer.offerMetadata(result, timestamp);
                    if (pairing != null) {
                        write(pairing);
                    }
                }

                @Override
                public void onCaptureFailed(@NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
                    Log.e(TAG, "The camera dropped a RAW capture, reason " + failure.getReason());
                    // Nothing is coming for this capture, so release what is waiting rather than
                    // leaving it to be paired with the next one.
                    mPairer.clear();
                    boolean announces = mAnnounces;
                    mFile = null;
                    if (announces) {
                        onImageFailed();
                    }
                }
            };
        }

        /** Hands a completed pair to the writer, if there is still somewhere to write it. */
        private void write(@NonNull RawCapturePairer.Pairing<TotalCaptureResult> pairing) {
            File file = mFile;
            boolean announces = mAnnounces;
            CameraCharacteristics characteristics = mCameraView.getCameraCharacteristics();
            if (file == null || characteristics == null) {
                Log.w(TAG, "A raw frame arrived with nowhere to write it");
                pairing.image.close();
                if (announces) {
                    onImageFailed();
                }
                return;
            }
            mFile = null;
            new DngSaver(
                    getContext(),
                    characteristics,
                    pairing,
                    exifOrientation(mCameraView.getRelativeCameraOrientation(),
                            isMirroringThisPicture()),
                    file) {
                @UiThread
                @Override
                protected void onPostExecute(Boolean saved) {
                    if (Boolean.TRUE.equals(saved)) {
                        if (announces) {
                            showImageConfirmation(file);
                        }
                    } else if (announces) {
                        onImageFailed();
                    } else {
                        // The JPEG beside it has already told the caller the capture succeeded,
                        // and one shutter press gets one answer, so a lost DNG is logged rather
                        // than contradicting it.
                        Log.e(TAG, "Wrote the JPEG but not the DNG for " + file);
                    }
                }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }

        @Override
        Surface getSurface() {
            return mImageReader.getSurface();
        }

        @Override
        void close() {
            mPairer.clear();
            if (mImageReader != null) {
                mImageReader.close();
                mImageReader = null;
            }
            if (mFile != null) {
                boolean announces = mAnnounces;
                mFile = null;
                if (announces) {
                    onImageFailed();
                }
            }
        }
    }

    private static final class PictureSurface extends CameraSurface {
        private List<Size> getSizes(StreamConfigurationMap map) {
            Size[] sizes = map.getOutputSizes(getImageFormat(getQuality()));

            // Special case for high resolution images (assuming, of course, quality was set to high)
            if (Build.VERSION.SDK_INT >= 23) {
                Size[] highResSizes = map.getHighResolutionOutputSizes(getImageFormat(getQuality()));
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
                                image,
                                mCameraView.getRelativeCameraOrientation(),
                                isMirroringThisPicture(),
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

        PictureSurface(Camera2Module camera2Module, CameraSurface previewSurface) {
            super(camera2Module);
            mPreviewSurface = previewSurface;
        }

        @Override
        void initialize(StreamConfigurationMap map) {
            initialize(map, null /* maxSize */);
        }

        void initialize(StreamConfigurationMap map, @Nullable Size maxSize) {
            if (DEBUG) Log.d(TAG, "Initializing PictureSession");
            super.initialize(chooseSize(within(getSizes(map), maxSize), mPreviewSurface.mSize));
            if (mImageReader != null) {
                // Initialized again - a session can be, when the camera is reconfigured - and an
                // ImageReader holds native buffers the collector cannot reclaim, so the one being
                // replaced has to be handed back rather than dropped.
                mImageReader.close();
            }
            mImageReader = ImageReader.newInstance(getWidth(), getHeight(), getImageFormat(getQuality()), 1 /* maxImages */);
            mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mCameraView.getHandler());
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
