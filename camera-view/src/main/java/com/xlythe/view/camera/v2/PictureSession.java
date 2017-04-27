package com.xlythe.view.camera.v2;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.os.AsyncTask;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.UiThread;
import android.support.annotation.WorkerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.ICameraModule;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.xlythe.view.camera.ICameraModule.DEBUG;
import static com.xlythe.view.camera.ICameraModule.TAG;
import static com.xlythe.view.camera.ICameraModule.getRelativeImageOrientation;

@TargetApi(21)
class PictureSession extends PreviewSession {
    /**
     * Supports {@link ImageFormat#JPEG} and {@link ImageFormat#YUV_420_888}. You can support
     * larger sizes with YUV_420_888, at the cost of speed.
     */
    private static final int IMAGE_FORMAT = ImageFormat.JPEG;

    private final PictureSurface mPictureSurface;

    PictureSession(Camera2Module camera2Module) {
        super(camera2Module);
        mPictureSurface = new PictureSurface(camera2Module);
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

    void takePicture(@NonNull File file, @NonNull CameraDevice device, @NonNull CameraCaptureSession session) {
        mPictureSurface.initializePicture(file);
        try {
            CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            builder.set(CaptureRequest.JPEG_ORIENTATION, getRelativeCameraOrientation());
            builder.addTarget(mPictureSurface.getSurface());
            if (mMeteringRectangle != null) {
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{mMeteringRectangle});
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{mMeteringRectangle});
            }
            if (mCropRegion != null) {
                builder.set(CaptureRequest.SCALER_CROP_REGION, mCropRegion);
            }
            session.capture(builder.build(), null /* callback */, getBackgroundHandler());
        } catch (CameraAccessException | IllegalStateException | NullPointerException e) {
            // Crashes if the Camera is interacted with while still loading
            Log.e(TAG, "Failed to create capture request", e);

            CameraView.OnImageCapturedListener l = getOnImageCapturedListener();
            if (l != null) {
                l.onFailure();
            }
        }
    }

    private static class ImageSaver extends AsyncTask<Void, Void, Void> {
        private final Image mImage;
        private final int mOrientation;

        // If true, the picture taken is reversed and needs to be flipped.
        // Typical with front facing cameras.
        private final boolean mIsReversed;
        private final File mFile;

        ImageSaver(Image image, int orientation, boolean reversed, File file) {
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
                // For JPEG files, we flip the bytes
                if (IMAGE_FORMAT == ImageFormat.JPEG) {
                    if (mIsReversed) {
                        Matrix matrix = new Matrix();
                        matrix.postScale(-1, 1);
                        bytes = transform(bytes, matrix);
                    }
                }

                output = new FileOutputStream(mFile);
                output.write(bytes);

                // For all other files, we add Exif metadata so that others know we're flipped
                if (IMAGE_FORMAT != ImageFormat.JPEG) {
                    int orientation = 1;
                    switch (mOrientation) {
                        case 0:
                            orientation = ExifInterface.ORIENTATION_NORMAL;
                            break;
                        case 90:
                            orientation = ExifInterface.ORIENTATION_ROTATE_90;
                            break;
                        case 180:
                            orientation = ExifInterface.ORIENTATION_ROTATE_180;
                            break;
                        case 270:
                            orientation = ExifInterface.ORIENTATION_ROTATE_270;
                            break;
                    }

                    ExifInterface exifInterface = new ExifInterface(mFile.toString());
                    exifInterface.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(orientation));
                    exifInterface.saveAttributes();
                }
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
            }
            return data;
        }

        private static byte[] YUV_420_888toNV21(Image image) {
            byte[] nv21;
            ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
            ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
            ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();

            int ySize = yBuffer.remaining();
            int uSize = uBuffer.remaining();
            int vSize = vBuffer.remaining();

            nv21 = new byte[ySize + uSize + vSize];

            // U and V are swapped
            yBuffer.get(nv21, 0, ySize);
            vBuffer.get(nv21, ySize, vSize);
            uBuffer.get(nv21, ySize + vSize, uSize);

            return nv21;
        }

        private static byte[] NV21toJPEG(byte[] nv21, int width, int height) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
            yuv.compressToJpeg(new Rect(0, 0, width, height), 100, out);
            return out.toByteArray();
        }

        public static byte[] transform(byte[] image, Matrix matrix) {
            Bitmap original = BitmapFactory.decodeByteArray(image, 0, image.length);
            Bitmap flipped = Bitmap.createBitmap(original, 0, 0, original.getWidth(), original.getHeight(), matrix, true);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            flipped.compress(Bitmap.CompressFormat.JPEG, 100, bos);
            try {
                return bos.toByteArray();
            } finally {
                try {
                    bos.close();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to close ByteArrayOutputStream when flipping image", e);
                }
            }
        }
    }

    private static final class PictureSurface extends CameraSurface {
        private Size choosePictureSize(StreamConfigurationMap map) {
            Size[] choices = getSizes(map);
            List<Size> availableSizes = new ArrayList<>(choices.length);
            for (Size size : choices) {
                if (getQuality() == CameraView.Quality.HIGH
                        && size.getWidth() > 1080
                        && IMAGE_FORMAT == ImageFormat.JPEG) {
                    // Camera crashes when you use too high a resolution with JPEG.
                    continue;
                }
                if (getQuality() == CameraView.Quality.MEDIUM && size.getWidth() > 720) {
                    continue;
                }
                if (getQuality() == CameraView.Quality.LOW && size.getWidth() > 420) {
                    continue;
                }
                availableSizes.add(size);
            }

            if (availableSizes.isEmpty()) {
                Log.e(TAG, "Couldn't find a suitable picture size");
                availableSizes.add(choices[0]);
            }

            if (DEBUG) {
                Log.d(TAG, "Found available picture sizes: " + availableSizes);
            }

            return Collections.max(availableSizes, new CompareSizesByArea());
        }

        private Size[] getSizes(StreamConfigurationMap map) {
            // Special case for high resolution images (assuming, of course, quality was set to high)
            if (getQuality() == CameraView.Quality.HIGH && Build.VERSION.SDK_INT >= 23) {
                Size[] sizes = map.getHighResolutionOutputSizes(IMAGE_FORMAT);
                if (sizes != null && sizes.length > 0) {
                    return sizes;
                }
            }

            // Otherwise, just return the default sizes
            return map.getOutputSizes(IMAGE_FORMAT);
        }

        private final ImageReader.OnImageAvailableListener mOnImageAvailableListener = new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(final ImageReader reader) {
                mCameraView.getBackgroundHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        if (mFile != null) {
                            final File file = mFile;
                            new ImageSaver(
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
                            Log.w(ICameraModule.TAG, "OnImageAvailable called but no file to write to");
                        }
                    }
                });
            }
        };

        private ImageReader mImageReader;
        private File mFile;

        PictureSurface(Camera2Module camera2Module) {
            super(camera2Module);
        }

        @Override
        void initialize(StreamConfigurationMap map) {
            super.initialize(choosePictureSize(map));
            mImageReader = ImageReader.newInstance(getWidth(), getHeight(), IMAGE_FORMAT, 1 /* maxImages */);
            mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mCameraView.getBackgroundHandler());
        }

        void initializePicture(File file) {
            mFile = file;
            if (mFile.exists()) {
                Log.w(TAG, "File already exists. Deleting.");
                mFile.delete();
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
        }
    }
}
