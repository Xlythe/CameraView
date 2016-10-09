package com.xlythe.view.camera.v2;

import android.annotation.TargetApi;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.Surface;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.ICameraModule;

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
public class Camera2PictureModule extends Camera2PreviewModule {
    static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private PhotoSurface mPhotoSurface = new PhotoSurface(this);
    private CaptureRequest.Builder mCaptureRequestBuilder;

    private CameraCaptureSession.CaptureCallback mCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        private void process(CaptureResult result) {
            Log.d(ICameraModule.TAG, "CaptureCallback: " + getState());
            switch (getState()) {
                case PREVIEW: {
                    // We have nothing to do when the camera preview is working normally.
                    break;
                }
                case WAITING_LOCK: {
                    Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                    if (afState == null) {
                        // This camera can't focus, so let's just take the picture
                        captureStillPicture();
                    } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState
                            || CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                        // CONTROL_AE_STATE can be null on some devices
                        Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                        if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            setState(State.PICTURE_TAKEN);
                            captureStillPicture();
                        } else {
                            runPrecaptureSequence();
                        }
                    }
                    break;
                }
                case WAITING_UNLOCK:
                    // After this, the camera will go back to the normal state of preview.
                    startPreview();
                    break;
                case WAITING_PRECAPTURE: {
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null
                            || aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE
                            || aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        setState(State.WAITING_NON_PRECAPTURE);
                    }
                    break;
                }
                case WAITING_NON_PRECAPTURE: {
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        setState(State.PICTURE_TAKEN);
                        captureStillPicture();
                    }
                    break;
                }
            }
        }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureResult partialResult) {
            process(partialResult);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            process(result);
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
            mCaptureRequestBuilder = getCameraDevice().createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            List<Surface> surfaces = new ArrayList<>();

            // We still want to draw the preview
            surfaces.add(getPreviewSurface().getSurface());
            mCaptureRequestBuilder.addTarget(getPreviewSurface().getSurface());

            // But we also want to save the picture
            surfaces.add(mPhotoSurface.getSurface());
            mCaptureRequestBuilder.addTarget(mPhotoSurface.getSurface());

            // Set camera focus to auto
            mCaptureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

            // And! Switch!
            createCaptureSession(surfaces, mCaptureRequestBuilder.build(), mCaptureCallback, new Callback() {
                @Override
                public void onComplete(CameraCaptureSession cameraCaptureSession) {
                    // Now that we draw to our ImageReader, lock the camera focus
                    lockFocus();
                }
            });
        } catch (CameraAccessException | IllegalStateException e) {
            // Crashes if the Camera is interacted with while still loading
            e.printStackTrace();
        }
    }

    /**
     * Run the precapture sequence for capturing a still image. This method should be called when
     * we get a response in {@link #mCaptureCallback} from {@link #lockFocus()}.
     */
    private void runPrecaptureSequence() {
        Log.d(ICameraModule.TAG, "runPrecaptureSequence");
        try {
            // This is how to tell the camera to trigger.
            mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            // Tell #mCaptureCallback to wait for the precapture sequence to be set.
            setState(State.WAITING_PRECAPTURE);
            getCaptureSession().capture(mCaptureRequestBuilder.build(), mCaptureCallback, getBackgroundHandler());
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Capture a still picture. This method should be called when we get a response in
     * {@link #mCaptureCallback} from both {@link #lockFocus()}.
     */
    private void captureStillPicture() {
        Log.d(ICameraModule.TAG, "captureStillPicture");
        try {
            // This is the CaptureRequest.Builder that we use to take a picture.
            final CaptureRequest.Builder captureBuilder = getCameraDevice().createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(mPhotoSurface.getSurface());

            // Use the same AE and AF modes as the preview.
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
//            setAutoFlash(captureBuilder); TODO

            // Orientation
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(getDisplayRotation()));

            CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    unlockFocus();
                }
            };

            getCaptureSession().stopRepeating();
            getCaptureSession().capture(captureBuilder.build(), captureCallback, getBackgroundHandler());
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Lock the focus as the first step for a still image capture.
     */
    private void lockFocus() {
        Log.d(ICameraModule.TAG, "lockFocus");
        try {
            // This is how to tell the camera to lock focus.
            mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,  CameraMetadata.CONTROL_AF_TRIGGER_START);
            // Tell #mCaptureCallback to wait for the lock.
            setState(State.WAITING_LOCK);
            getCaptureSession().capture(mCaptureRequestBuilder.build(), mCaptureCallback, getBackgroundHandler());
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Unlock the focus. This method should be called when still image capture sequence is
     * finished.
     */
    private void unlockFocus() {
        Log.d(ICameraModule.TAG, "unlockFocus");
        try {
            // Reset the auto-focus trigger
            mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
//            setAutoFlash(mPreviewRequestBuilder); TODO
            setState(State.WAITING_UNLOCK);
            getCaptureSession().capture(mCaptureRequestBuilder.build(), mCaptureCallback, getBackgroundHandler());
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private static class ImageSaver implements Runnable {
        private final Image mImage;
        private final File mFile;

        public ImageSaver(Image image, File file) {
            mImage = image;
            mFile = file;
        }

        @Override
        public void run() {
            ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            FileOutputStream output = null;
            try {
                output = new FileOutputStream(mFile);
                output.write(bytes);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                mImage.close();
                if (null != output) {
                    try {
                        output.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
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
                            new ImageSaver(reader.acquireNextImage(), mFile).run();
                            onPictureTaken();
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
            super.initialize(Collections.max(Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)), new CompareSizesByArea()));

            mImageReader = ImageReader.newInstance(getWidth(), getHeight(), ImageFormat.JPEG, 2 /*maxImages*/);
            mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mCameraView.getBackgroundHandler());
        }

        void initializePicture(File file, CameraView.OnImageCapturedListener listener) {
            mFile = file;
            mPhotoListener = listener;
        }

        void onPictureTaken() {
            if (mPhotoListener != null) {
                mPhotoListener.onImageCaptured(mFile);
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
