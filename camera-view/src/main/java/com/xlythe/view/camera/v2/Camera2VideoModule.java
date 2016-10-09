package com.xlythe.view.camera.v2;

import android.annotation.TargetApi;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import com.xlythe.view.camera.CameraView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * In order to take videos, we create a MediaRecorder instance. When we start recording, we
 * tell the camera to draw to the MediaRecorder surface and then have the MediaRecorder save
 * itself to the given file.
 */
@TargetApi(21)
public class Camera2VideoModule extends Camera2PictureModule {
    private VideoSurface mVideoSurface = new VideoSurface(this);

    Camera2VideoModule(CameraView view) {
        super(view);
    }

    @Override
    protected List<Camera2PreviewModule.CameraSurface> getCameraSurfaces() {
        List<Camera2PreviewModule.CameraSurface> list = super.getCameraSurfaces();
        list.add(mVideoSurface);
        return list;
    }

    @Override
    public void startRecording(File file) {
        mVideoSurface.setFile(file);
        startRecording();
    }

    @Override
    public void stopRecording() {
        mVideoSurface.stopRecording();
        startPreview();
    }

    @Override
    public boolean isRecording() {
        return mVideoSurface.mIsRecordingVideo;
    }

    private void startRecording() {
        try {
            SurfaceTexture texture = getSurfaceTexture();
            texture.setDefaultBufferSize(mVideoSurface.getWidth(), mVideoSurface.getHeight());

            final CaptureRequest.Builder builder = getCameraDevice().createCaptureRequest(CameraDevice.TEMPLATE_RECORD);

            List<Surface> surfaces = new ArrayList<>();

            // We still want to draw the preview
            surfaces.add(getPreviewSurface().getSurface());
            builder.addTarget(getPreviewSurface().getSurface());

            // But we also want to record
            surfaces.add(mVideoSurface.getSurface());
            builder.addTarget(mVideoSurface.getSurface());

            // Set camera focus to auto
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

            // And! Switch!
            createCaptureSession(surfaces, builder.build(), new Callback() {
                @Override
                public void onComplete(CameraCaptureSession cameraCaptureSession) {
                    // Alright, our session is active. Start recording the Video surface.
                    mVideoSurface.startRecording(getOnVideoCapturedListener());
                }
            });
        } catch (CameraAccessException | IllegalStateException e) {
            // Crashes if the Camera is interacted with while still loading
            e.printStackTrace();
        }
    }

    private static final class VideoSurface extends CameraSurface {
        private static Size chooseVideoSize(Size[] choices) {
            for (Size size : choices) {
                if (size.getWidth() == size.getHeight() * 4 / 3 && size.getWidth() <= 1080) {
                    return size;
                }
            }
            Log.e(TAG, "Couldn't find any suitable video size");
            return choices[0];
        }

        private boolean mIsRecordingVideo;
        private MediaRecorder mMediaRecorder;
        private CameraView.OnVideoCapturedListener mVideoListener;
        private File mFile;

        VideoSurface(Camera2VideoModule cameraView) {
            super(cameraView);
        }

        @Override
        void initialize(StreamConfigurationMap map) {
            super.initialize(chooseVideoSize(map.getOutputSizes(MediaRecorder.class)));
        }

        void setFile(File file) {
            mFile = file;

            mMediaRecorder = new MediaRecorder();
            mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mMediaRecorder.setOutputFile(mFile.getAbsolutePath());
            mMediaRecorder.setVideoEncodingBitRate(10000000);
            mMediaRecorder.setVideoFrameRate(30);
            mMediaRecorder.setVideoSize(getWidth(), getHeight());
            mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            int rotation = mCameraView.getDisplayRotation();
            int orientation = ORIENTATIONS.get(rotation);
            mMediaRecorder.setOrientationHint(orientation);
            try {
                mMediaRecorder.prepare();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        @Override
        Surface getSurface() {
            return mMediaRecorder.getSurface();
        }

        void startRecording(CameraView.OnVideoCapturedListener listener) {
            mVideoListener = listener;
            try {
                mMediaRecorder.start();
                mIsRecordingVideo = true;
            } catch (IllegalStateException e) {
                e.printStackTrace();
            }
        }

        void stopRecording() {
            mIsRecordingVideo = false;
            mMediaRecorder.stop();
            mMediaRecorder.reset();
            if (mVideoListener != null) {
                mVideoListener.onVideoCaptured(mFile);
            }
        }

        @Override
        void close() {
            if (mMediaRecorder != null) {
                mMediaRecorder.release();
                mMediaRecorder = null;
            }
        }
    }
}
