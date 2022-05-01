package com.xlythe.view.camera.v2;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
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

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.Exif;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import androidx.annotation.NonNull;
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

    private final PictureSurface mPictureSurface;

    PictureSession(Camera2Module camera2Module) {
        super(camera2Module);
        mPictureSurface = new PictureSurface(camera2Module, getPreviewSurface());
    }

    @Override
    public void initialize(@NonNull StreamConfigurationMap map) throws CameraAccessException {
        super.initialize(map);
        mPictureSurface.initialize(map);
    }

    @NonNull
    @Override
    public List<Surface> getSurfaces() {
        List<Surface> surfaces = super.getSurfaces();
        surfaces.add(mPictureSurface.getSurface());
        return surfaces;
    }

    @Override
    public void close() {
        super.close();
        mPictureSurface.close();
    }

    void takePicture(@NonNull File file, @NonNull CameraDevice device, @NonNull CameraCaptureSession session) {
        mPictureSurface.initializePicture(file);
        try {
            CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            if (hasFlash()) {
                switch (getFlash()) {
                    case AUTO:
                        // TODO: This doesn't work :(
                        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                        break;
                    case ON:
                        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE);
                        break;
                    case OFF:
                        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
                        break;
                }
            }
            builder.addTarget(mPictureSurface.getSurface());
            if (mMeteringRectangle != null) {
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{mMeteringRectangle});
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{mMeteringRectangle});
            }
            if (mCropRegion != null) {
                builder.set(CaptureRequest.SCALER_CROP_REGION, mCropRegion);
            }
            session.capture(builder.build(), null /* callback */, getHandler());
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            // Crashes if the Camera is interacted with while still loading
            Log.e(TAG, "Failed to create capture request", e);
            onImageFailed();
        }
    }

    private static class ImageSaver extends AsyncTask<Void, Void, Void> {
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

        @WorkerThread
        @Override
        protected Void doInBackground(Void... params) {
            // Finally, we save the file to disk
            byte[] bytes = getBytes();
            FileOutputStream output = null;
            try {
                output = new FileOutputStream(mFile);
                output.write(bytes);

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
                Log.e(TAG, "Failed to write the file", e);
            } finally {
                mImage.close();
                if (output != null) {
                    try {
                        output.close();
                    } catch (IOException e) {
                        Log.e(TAG, "Failed to close the output stream", e);
                    }
                }
            }
            return null;
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

        private static byte[] YUV_420_888toNV21(Image image) {
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
                mCameraView.getHandler().post(() -> {
                    try {
                        if (mFile != null) {
                            final File file = mFile;
                            new ImageSaver(
                                    mCameraView.getContext(),
                                    reader.acquireLatestImage(),
                                    mCameraView.getRelativeCameraOrientation(),
                                    isUsingFrontFacingCamera(),
                                    mFile) {
                                @UiThread
                                @Override
                                protected void onPostExecute(Void aVoid) {
                                    showImageConfirmation(file);
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
                });
            }
        };

        private final CameraSurface mPreviewSurface;
        private ImageReader mImageReader;
        private File mFile;
        private boolean mIsTakingPhoto = false;

        PictureSurface(Camera2Module camera2Module, CameraSurface previewSurface) {
            super(camera2Module);
            mPreviewSurface = previewSurface;
        }

        @Override
        void initialize(StreamConfigurationMap map) {
            if (DEBUG) Log.d(TAG, "Initializing PictureSession");
            super.initialize(chooseSize(getSizes(map), mPreviewSurface.mSize));
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

    private static boolean isRaw(int imageFormat) {
        switch (imageFormat) {
            case ImageFormat.YUV_420_888:
                return true;
            default:
                return false;
        }
    }
}
