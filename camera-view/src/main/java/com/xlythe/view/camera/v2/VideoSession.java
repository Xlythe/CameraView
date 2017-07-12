package com.xlythe.view.camera.v2;

import android.annotation.TargetApi;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.location.Location;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static com.xlythe.view.camera.ICameraModule.DEBUG;
import static com.xlythe.view.camera.ICameraModule.TAG;

@TargetApi(21)
class VideoSession extends PreviewSession {
    /**
     * The VideoSurface must always check CamcorderProfile to see if the outputs supported by the
     * camera are also supported by MediaRecorder. Bizarre as it sounds,
     * getOutputSizes(MediaRecorder.class) doesn't actually care if MediaRecorder supports the
     * sizes it gives back. Because MediaRecorder is always (at most) limited to 4k recording, that
     * is our max video size.
     */
    private final VideoSurface mVideoSurface;

    VideoSession(Camera2Module camera2Module, File file) {
        super(camera2Module);
        mVideoSurface = new VideoSurface(camera2Module, file, getPreviewSurface());
    }

    @Override
    public void initialize(@NonNull StreamConfigurationMap map) throws CameraAccessException {
        super.initialize(map);
        mVideoSurface.initialize(map);
        if (!mVideoSurface.mIsInitialized) {
            onVideoFailed();
            throw new CameraAccessException(CameraAccessException.CAMERA_ERROR);
        }
    }

    private CaptureRequest createCaptureRequest(@NonNull CameraDevice device) throws CameraAccessException {
        CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        if (mMeteringRectangle != null) {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{mMeteringRectangle});
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{mMeteringRectangle});
        }
        if (mCropRegion != null) {
            builder.set(CaptureRequest.SCALER_CROP_REGION, mCropRegion);
        }
        builder.addTarget(getPreviewSurface().getSurface());
        builder.addTarget(mVideoSurface.getSurface());
        return builder.build();
    }

    @Override
    public void onAvailable(@NonNull CameraDevice device, @NonNull CameraCaptureSession session) throws CameraAccessException {
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
                    mVideoSurface.startRecording();
                }
            }
        }, getHandler());
    }

    @Override
    public void onInvalidate(@NonNull CameraDevice device, @NonNull CameraCaptureSession session) throws CameraAccessException {
        if (!mVideoSurface.mIsInitialized) {
            Log.w(TAG, "onInvalidate failed. Not initialize.");
            return;
        }

        if (mVideoSurface.mAwaitingRecording) {
            Log.w(TAG, "Ignoring invalidate. Still waiting on capture completed.");
            return;
        }

        session.setRepeatingRequest(createCaptureRequest(device), null /* callback */, getHandler());
    }

    @Override
    public void close() {
        super.close();
        mVideoSurface.stopRecording();
        mVideoSurface.close();
    }

    @NonNull
    @Override
    public List<Surface> getSurfaces() {
        List<Surface> surfaces = super.getSurfaces();
        surfaces.add(mVideoSurface.getSurface());
        return surfaces;
    }

    public File getFile() {
        return mVideoSurface.mFile;
    }

    boolean isRecording() {
        return mVideoSurface.isRecording();
    }

    private static final class VideoSurface extends CameraSurface {
        private List<Size> getSizes(StreamConfigurationMap map) {
            Size[] sizes = map.getOutputSizes(MediaRecorder.class);

            // StreamConfigurationMap.getOutputSizes(MediaRecorder.class) only tells us if the
            // camera supports these sizes. It does not tell us if MediaRecorder supports these
            // sizes, odd as that sounds. Therefore, we need to filter ourselves manually.
            List<Size> filtered;
            if (CamcorderProfile.hasProfile(getCameraId(), CamcorderProfile.QUALITY_2160P)) {
                filtered = filter(sizes, SIZE_4K);
                if (!filtered.isEmpty()) {
                    return filtered;
                }
            }
            if (CamcorderProfile.hasProfile(getCameraId(), CamcorderProfile.QUALITY_1080P)) {
                filtered = filter(sizes, SIZE_1080P);
                if (!filtered.isEmpty()) {
                    return filtered;
                }
            }
            if (CamcorderProfile.hasProfile(getCameraId(), CamcorderProfile.QUALITY_720P)) {
                filtered = filter(sizes, SIZE_720P);
                if (!filtered.isEmpty()) {
                    return filtered;
                }
            }
            if (CamcorderProfile.hasProfile(getCameraId(), CamcorderProfile.QUALITY_480P)) {
                filtered = filter(sizes, SIZE_480P);
                if (!filtered.isEmpty()) {
                    return filtered;
                }
            }
            return Arrays.asList(sizes);
        }

        private boolean mIsRecordingVideo;
        private boolean mIsInitialized;
        private boolean mAwaitingRecording;

        private MediaRecorder mMediaRecorder;

        @NonNull
        private final File mFile;
        private final CameraSurface mPreviewSurface;

        VideoSurface(Camera2Module cameraView, @NonNull File file, CameraSurface previewSurface) {
            super(cameraView);
            mFile = file;
            mPreviewSurface = previewSurface;
        }

        @Override
        void initialize(StreamConfigurationMap map) {
            if (DEBUG) Log.d(TAG, "Initializing VideoSession");
            super.initialize(chooseSize(getSizes(map), mPreviewSurface.mSize));

            try {
                mMediaRecorder = new MediaRecorder();
                mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                switch (getQuality()) {
                    case MAX:
                        if (CamcorderProfile.hasProfile(getCameraId(), CamcorderProfile.QUALITY_2160P)) {
                            mMediaRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_2160P));
                            break;
                        }
                        // Fall-through
                    case HIGH:
                        if (CamcorderProfile.hasProfile(getCameraId(), CamcorderProfile.QUALITY_1080P)) {
                            mMediaRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_1080P));
                            break;
                        }
                        // Fall-through
                    case MEDIUM:
                        if (CamcorderProfile.hasProfile(getCameraId(), CamcorderProfile.QUALITY_720P)) {
                            mMediaRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_720P));
                            break;
                        }
                        // Fall-through
                    case LOW:
                        if (CamcorderProfile.hasProfile(getCameraId(), CamcorderProfile.QUALITY_480P)) {
                            mMediaRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_480P));
                            break;
                        }
                        // Fall-through
                    default:
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
                mMediaRecorder.setOrientationHint(mCameraView.getRelativeCameraOrientation());

                Location location = getLocation(getContext());
                if (location != null) {
                    mMediaRecorder.setLocation((float) location.getLatitude(), (float) location.getLongitude());
                }

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

        void startRecording() {
            if (mMediaRecorder == null) {
                Log.w(TAG, "Cannot record. Failed to initialize.");
                return;
            }

            try {
                mMediaRecorder.start();
                mIsRecordingVideo = true;
            } catch (IllegalStateException e) {
                Log.e(TAG, "Unable to start recording", e);
                onVideoFailed();
            } catch (RuntimeException e) {
                // MediaRecorder can crash with 'start failed.'
                Log.e(TAG, "Let me guess. 'start failed.'?", e);
                onVideoFailed();
            }
        }

        void stopRecording() {
            if (mIsRecordingVideo) {
                mIsRecordingVideo = false;
                try {
                    mMediaRecorder.stop();
                    mMediaRecorder.reset();
                    showVideoConfirmation(mFile);
                } catch (RuntimeException e) {
                    // MediaRecorder can crash with 'stop failed.'
                    Log.e(TAG, "Let me guess. 'stop failed.'?", e);
                    onVideoFailed();
                }
            } else {
                onVideoFailed();
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
