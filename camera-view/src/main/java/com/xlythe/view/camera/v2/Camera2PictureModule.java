package com.xlythe.view.camera.v2;

import android.annotation.TargetApi;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.support.annotation.UiThread;
import android.support.annotation.WorkerThread;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.Surface;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.ICameraModule;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * In order to take photos, we create an ImageReader instance. We first lock the camera focus,
 * then await precapture, take the picture, and then unlock the focus and resume the preview.
 */
@TargetApi(21)
class Camera2PictureModule extends Camera2PreviewModule {
    static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    private static final long PICTURE_TIMEOUT = 3000; // 3 seconds

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private PhotoSurface mPhotoSurface = new PhotoSurface(this);

    private CameraCaptureSession.CaptureCallback mCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        private void process(CaptureResult result, boolean complete) {
            Log.d(ICameraModule.TAG, String.format("CaptureCallback: state=%s, complete=%s", getState(), complete));
            switch (getState()) {
                case PREVIEW: {
                    // We have nothing to do when the camera preview is working normally.
                    break;
                }
                case PICTURE_TAKEN: {
                    if (complete) {
                        startPreview();
                    }
                    break;
                }
                default: {
                    Log.w(TAG, "Received unexpected state: " + getState());
                }
            }
        }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureResult partialResult) {
            process(partialResult, false /* complete */);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            process(result, true /* complete */);
        }
    };

    Camera2PictureModule(CameraView view) {
        super(view);
    }

    @Override
    protected List<Camera2PreviewModule.CameraSurface> getCameraSurfaces() {
        List<Camera2PreviewModule.CameraSurface> list = super.getCameraSurfaces();
        list.add(mPhotoSurface);
        return list;
    }

    @Override
    public void takePicture(File file) {
        mPhotoSurface.initializePicture(file, getOnImageCapturedListener());
        try {
            // Right now, the preview ONLY draws to the preview surface. We need to create a session
            // that also draws to our ImageReader.
            CaptureRequest.Builder captureRequest = getCameraDevice().createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureRequest.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            captureRequest.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(getDisplayRotation()));
            captureRequest.addTarget(mPhotoSurface.getSurface());

            getCaptureSession().capture(captureRequest.build(), mCaptureCallback, getBackgroundHandler());
        } catch (CameraAccessException | IllegalStateException e) {
            // Crashes if the Camera is interacted with while still loading
            e.printStackTrace();

            CameraView.OnImageCapturedListener l = getOnImageCapturedListener();
            if (l != null) {
                l.onFailure();
            }
        }
    }

    private static class ImageSaver extends AsyncTask<Void, Void, Void> {
        private final Image mImage;
        private final int mOrientation;
        private final File mFile;

        ImageSaver(Image image, int orientation, File file) {
            mImage = image;
            mOrientation = orientation;
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
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                mImage.close();
                if (output != null) {
                    try {
                        output.close();
                    } catch (IOException e) {
                        e.printStackTrace();
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
    }

    private static final class PhotoSurface extends CameraSurface {
        private final ImageReader.OnImageAvailableListener mOnImageAvailableListener = new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(final ImageReader reader) {
                mCameraView.getBackgroundHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        if (mFile != null) {
                            final CameraView.OnImageCapturedListener listener = mPhotoListener;
                            final File file = mFile;
                            new ImageSaver(reader.acquireLatestImage(), mCameraView.getRelativeCameraOrientation(), mFile) {
                                @UiThread
                                @Override
                                protected void onPostExecute(Void aVoid) {
                                    if (listener != null) {
                                        listener.onImageCaptured(file);
                                    }
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
        private CameraView.OnImageCapturedListener mPhotoListener;
        private File mFile;

        PhotoSurface(Camera2PictureModule cameraView) {
            super(cameraView);
        }

        void initialize(StreamConfigurationMap map) {
            super.initialize(Collections.max(Arrays.asList(map.getOutputSizes(ImageFormat.YUV_420_888)), new CompareSizesByArea()));

            mImageReader = ImageReader.newInstance(getWidth(), getHeight(), ImageFormat.YUV_420_888, 1 /* maxImages */);
            mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mCameraView.getBackgroundHandler());
        }

        void initializePicture(File file, CameraView.OnImageCapturedListener listener) {
            mFile = file;
            if (mFile.exists()) {
                Log.w(TAG, "File already exists. Deleting.");
                mFile.delete();
            }
            mPhotoListener = listener;
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
