package com.xlythe.view.camera.v2;

import android.annotation.TargetApi;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import com.xlythe.view.camera.CameraView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.xlythe.view.camera.ICameraModule.DEBUG;
import static com.xlythe.view.camera.ICameraModule.TAG;

@TargetApi(21)
class VideoSession extends PreviewSession {
    private final VideoSurface mVideoSurface;

    VideoSession(Camera2Module camera2Module, File file) {
        super(camera2Module);
        mVideoSurface = new VideoSurface(camera2Module, file);
    }

    @Override
    public void initialize(StreamConfigurationMap map) throws CameraAccessException {
        super.initialize(map);
        mVideoSurface.initialize(map);
    }

    private CaptureRequest createCaptureRequest(CameraDevice device) throws CameraAccessException {
        CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        if (mMeteringRectangle != null) {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{mMeteringRectangle});
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{mMeteringRectangle});
        }
        builder.addTarget(getPreviewSurface().getSurface());
        builder.addTarget(mVideoSurface.getSurface());
        return builder.build();
    }

    @Override
    public void onAvailable(CameraDevice device, CameraCaptureSession session) throws CameraAccessException {
        if (!mVideoSurface.mIsInitialized) {
            Log.w(TAG, "onAvailable failed. Not initialize.");
            return;
        }

        mVideoSurface.mAwaitingRecording = true;
        session.setRepeatingRequest(createCaptureRequest(device), new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                if (mVideoSurface.mAwaitingRecording) {
                    mVideoSurface.mAwaitingRecording = false;
                    mVideoSurface.startRecording(getOnVideoCapturedListener());
                }
            }
        }, getBackgroundHandler());
    }

    @Override
    public void onInvalidate(CameraDevice device, CameraCaptureSession session) throws CameraAccessException {
        if (!mVideoSurface.mIsInitialized) {
            Log.w(TAG, "onInvalidate failed. Not initialize.");
            return;
        }

        if (mVideoSurface.mAwaitingRecording) {
            Log.w(TAG, "Ignoring invalidate. Still waiting on capture completed.");
            return;
        }

        session.setRepeatingRequest(createCaptureRequest(device), null /* callback */, getBackgroundHandler());
    }

    @Override
    public void close() {
        super.close();
        mVideoSurface.stopRecording();
        mVideoSurface.close();
    }

    @Override
    public List<Surface> getSurfaces() {
        List<Surface> surfaces = super.getSurfaces();
        surfaces.add(mVideoSurface.getSurface());
        return surfaces;
    }

    private static final class VideoSurface extends CameraSurface {
        private Size chooseVideoSize(StreamConfigurationMap map) {
            Size[] choices = map.getOutputSizes(MediaRecorder.class);
            List<Size> availableSizes = new ArrayList<>(choices.length);
            for (Size size : choices) {
                if (getQuality() == CameraView.Quality.HIGH && size.getWidth() > 1080) {
                    // TODO Figure out why camera crashes when we use a size higher than 1080
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
                Log.e(TAG, "Couldn't find a suitable video size");
                availableSizes.add(choices[0]);
            }

            if (DEBUG) {
                Log.d(TAG, "Found available video sizes: " + availableSizes);
            }

            return Collections.max(availableSizes, new CompareSizesByArea());
        }

        private boolean mIsRecordingVideo;
        private boolean mIsInitialized;
        private boolean mAwaitingRecording;

        private MediaRecorder mMediaRecorder;

        @Nullable
        private CameraView.OnVideoCapturedListener mVideoListener;

        @NonNull
        private final File mFile;

        VideoSurface(Camera2Module cameraView, @NonNull File file) {
            super(cameraView);
            mFile = file;
        }

        @Override
        void initialize(StreamConfigurationMap map) {
            super.initialize(chooseVideoSize(map));

            SurfaceTexture texture = mCameraView.getSurfaceTexture();
            texture.setDefaultBufferSize(getWidth(), getHeight());

            try {
                mMediaRecorder = new MediaRecorder();
                mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                switch (mCameraView.getQuality()) {
                    case HIGH:
                        mMediaRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH));
                        break;
                    case MEDIUM:
                        mMediaRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_720P));
                        break;
                    case LOW:
                        mMediaRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_LOW));
                        break;
                }
                mMediaRecorder.setOutputFile(mFile.getAbsolutePath());
                mMediaRecorder.setMaxDuration((int) mCameraView.getMaxVideoDuration());
                mMediaRecorder.setMaxFileSize(mCameraView.getMaxVideoSize());
                mMediaRecorder.setOnInfoListener(new MediaRecorder.OnInfoListener() {
                    @Override
                    public void onInfo(MediaRecorder mr, int what, int extra) {
                        switch (what) {
                            case MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED:
                                Log.w(TAG, "Max duration for recording reached");
                                break;
                            case MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED:
                                Log.w(TAG, "Max filesize for recording reached");
                                break;
                        }
                    }
                });
                mMediaRecorder.setVideoSize(getWidth(), getHeight());
                mMediaRecorder.setOrientationHint(ORIENTATIONS.get(mCameraView.getDisplayRotation()));
                mMediaRecorder.prepare();
                mIsInitialized = true;
            } catch (IOException e) {
                Log.e(TAG, "Failed to initialize", e);
            }
        }

        @Override
        Surface getSurface() {
            return mMediaRecorder.getSurface();
        }

        boolean isRecording() {
            return mIsRecordingVideo;
        }

        void startRecording(CameraView.OnVideoCapturedListener listener) {
            if (mMediaRecorder == null) {
                Log.w(TAG, "Cannot record. Failed to initialize.");
                return;
            }

            mVideoListener = listener;
            try {
                mMediaRecorder.start();
                mIsRecordingVideo = true;
            } catch (IllegalStateException e) {
                Log.e(TAG, "Unable to start recording", e);
            } catch (RuntimeException e) {
                // MediaRecorder can crash with 'start failed.'
                Log.e(TAG, "Let me guess. 'start failed.'?", e);
                mIsInitialized = false;
            }
        }

        void stopRecording() {
            if (mIsRecordingVideo) {
                mIsRecordingVideo = false;
                try {
                    mMediaRecorder.stop();
                    mMediaRecorder.reset();
                } catch (RuntimeException e) {
                    // MediaRecorder can crash with 'stop failed.'
                    Log.e(TAG, "Let me guess. 'stop failed.'?", e);
                }
                if (mVideoListener != null) {
                    mVideoListener.onVideoCaptured(mFile);
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
