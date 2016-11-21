package com.xlythe.view.camera.v2;

import android.Manifest;
import android.annotation.TargetApi;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresPermission;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import com.xlythe.view.camera.CameraView;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * In order to take videos, we create a MediaRecorder instance. When we start recording, we
 * tell the camera to draw to the MediaRecorder surface and then have the MediaRecorder save
 * itself to the given file.
 */
@TargetApi(21)
class Camera2VideoModule extends Camera2PictureModule {
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

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @Override
    public void startRecording(File file) {
        //mVideoSurface.setFile(file);
        mVideoSurface.mWantsToRecord = true;
        try {
            SurfaceTexture texture = getSurfaceTexture();
            texture.setDefaultBufferSize(mVideoSurface.getWidth(), mVideoSurface.getHeight());

            CaptureRequest.Builder request = getCameraDevice().createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            request.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            request.addTarget(getPreviewSurface().getSurface());
            request.addTarget(mVideoSurface.getSurface());

            setState(State.RECORDING);
            getCaptureSession().setRepeatingRequest(request.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    // Alright, our session is active. Start recording the Video surface.
                    if (!mVideoSurface.mWantsToRecord) {
                        mVideoSurface.startRecording(getOnVideoCapturedListener());
                    }
                }
            }, getBackgroundHandler());
        } catch (CameraAccessException | IllegalStateException e) {
            // Crashes if the Camera is interacted with while still loading
            e.printStackTrace();
        }
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

        private boolean mWantsToRecord;
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
            setFile(new File(getContext().getCacheDir() + "/" + "temp.mp4"));
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
                mWantsToRecord = false;
            } catch (IllegalStateException e) {
                e.printStackTrace();
            }
        }

        void stopRecording() {
            if (mIsRecordingVideo) {
                mIsRecordingVideo = false;
                try {
                    mMediaRecorder.stop();
                    mMediaRecorder.reset();
                    if (mVideoListener != null) {
                        mVideoListener.onVideoCaptured(mFile);
                    }
                } catch (RuntimeException e) {
                    // MediaRecorder can crash with 'stop failed.'
                    e.printStackTrace();
                }
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
