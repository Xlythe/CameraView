package com.xlythe.view.camera.v2;

import android.annotation.SuppressLint;
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
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import com.xlythe.view.camera.CameraView;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import static com.xlythe.view.camera.ICameraModule.DEBUG;
import static com.xlythe.view.camera.ICameraModule.TAG;

@TargetApi(21)
class VideoSession extends PictureSession {
    /**
     * The VideoSurface must always check CamcorderProfile to see if the outputs supported by the
     * camera are also supported by MediaRecorder. Bizarre as it sounds,
     * getOutputSizes(MediaRecorder.class) doesn't actually care if MediaRecorder supports the
     * sizes it gives back. Because MediaRecorder is always (at most) limited to 4k recording, that
     * is our max video size.
     */
    /** Floor for a rescaled bit rate, below which even a small frame looks broken. */
    private static final int MIN_VIDEO_BIT_RATE = 64_000;
    /** Ceiling for a rescaled bit rate, so a 4K profile cannot run away on an odd device. */
    private static final int MAX_VIDEO_BIT_RATE = 100_000_000;

    private final VideoSurface mVideoSurface;

    /** Whether this session carries a still output alongside the recording. */
    private final boolean mSnapshotSupported;

    VideoSession(Camera2Module camera2Module, File file) {
        super(camera2Module);
        mVideoSurface = new VideoSurface(camera2Module, file, getPreviewSurface());
        mSnapshotSupported = camera2Module.isRecordingSnapshotSupported();
    }

    /**
     * Whether a picture can be taken while this session is recording.
     *
     * <p>Camera2 guarantees a preview, a recording and a still together from LIMITED hardware
     * upwards - it is the combination every camera app's video-snapshot button uses - but only
     * with the still no larger than the recording, which is what {@link #maxPictureSize} says.
     * A LEGACY camera has no such guarantee, so it records without one rather than risking a
     * session it cannot configure.</p>
     */
    @Override
    protected boolean hasStillCapture() {
        return mSnapshotSupported;
    }

    @Nullable
    @Override
    protected Size maxPictureSize() {
        return mVideoSurface.mIsInitialized
                ? new Size(mVideoSurface.getWidth(), mVideoSurface.getHeight())
                : null;
    }

    @Override
    protected boolean allowsUltraHdr() {
        return false;
    }

    @Override
    @SuppressLint({"WrongThread", "ThreadConstraint"})
    protected void onPreviewInitialized(@NonNull StreamConfigurationMap map)
            throws CameraAccessException {
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
        applyZoom(builder);
        applyFaceDetection(builder);
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
                // Recording does not stop whoever is watching for faces.
                CameraCaptureSession.CaptureCallback faces = repeatingCallback();
                if (faces != null) {
                    faces.onCaptureCompleted(session, request, result);
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

        session.setRepeatingRequest(createCaptureRequest(device), repeatingCallback(), getHandler());
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

    static final class VideoSurface extends CameraSurface {
        /**
         * The best profile the camera actually has for a requested quality, stepping down until
         * one exists. QUALITY_LOW is guaranteed on every camera, so it is the floor.
         */
        private static CamcorderProfile chooseProfile(int cameraId, CameraView.Quality quality) {
            int[] preferences;
            switch (quality) {
                case MAX:
                    preferences = new int[] {CamcorderProfile.QUALITY_2160P,
                            CamcorderProfile.QUALITY_1080P, CamcorderProfile.QUALITY_720P,
                            CamcorderProfile.QUALITY_480P};
                    break;
                case HIGH:
                    preferences = new int[] {CamcorderProfile.QUALITY_1080P,
                            CamcorderProfile.QUALITY_720P, CamcorderProfile.QUALITY_480P};
                    break;
                case MEDIUM:
                    preferences = new int[] {CamcorderProfile.QUALITY_720P,
                            CamcorderProfile.QUALITY_480P};
                    break;
                case LOW:
                default:
                    preferences = new int[] {CamcorderProfile.QUALITY_480P};
                    break;
            }
            for (int preference : preferences) {
                if (CamcorderProfile.hasProfile(cameraId, preference)) {
                    return CamcorderProfile.get(cameraId, preference);
                }
            }
            return CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_LOW);
        }

        /**
         * Rescales a profile's bit rate for the frame size actually being recorded.
         *
         * <p>Bit rate requirements track pixel count closely enough for this, and the result is
         * clamped so an odd profile cannot produce an unusable rate.</p>
         */
        static int scaleBitRate(int profileBitRate, int profileWidth, int profileHeight,
                int width, int height) {
            long profilePixels = (long) profileWidth * profileHeight;
            long pixels = (long) width * height;
            if (profileBitRate <= 0 || profilePixels <= 0 || pixels <= 0) {
                return Math.max(MIN_VIDEO_BIT_RATE, profileBitRate);
            }
            long scaled = (long) profileBitRate * pixels / profilePixels;
            return (int) Math.max(MIN_VIDEO_BIT_RATE, Math.min(scaled, MAX_VIDEO_BIT_RATE));
        }

        private int getIntCameraId() {
            try {
                return Integer.parseInt(getCameraId());
            } catch (NumberFormatException e) {
                Log.w(TAG, "Camera ID is not an integer, defaulting to 0 for CamcorderProfile");
                return 0;
            }
        }

        private List<Size> getSizes(StreamConfigurationMap map) {
            Size[] sizes = map.getOutputSizes(MediaRecorder.class);

            // StreamConfigurationMap.getOutputSizes(MediaRecorder.class) only tells us if the
            // camera supports these sizes. It does not tell us if MediaRecorder supports these
            // sizes, odd as that sounds. Therefore, we need to filter ourselves manually.
            List<Size> filtered;
            int intCameraId = getIntCameraId();
            if (CamcorderProfile.hasProfile(intCameraId, CamcorderProfile.QUALITY_2160P)) {
                filtered = filter(sizes, SIZE_4K);
                if (!filtered.isEmpty()) {
                    return filtered;
                }
            }
            if (CamcorderProfile.hasProfile(intCameraId, CamcorderProfile.QUALITY_1080P)) {
                filtered = filter(sizes, SIZE_1080P);
                if (!filtered.isEmpty()) {
                    return filtered;
                }
            }
            if (CamcorderProfile.hasProfile(intCameraId, CamcorderProfile.QUALITY_720P)) {
                filtered = filter(sizes, SIZE_720P);
                if (!filtered.isEmpty()) {
                    return filtered;
                }
            }
            if (CamcorderProfile.hasProfile(intCameraId, CamcorderProfile.QUALITY_480P)) {
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
                // CAMCORDER rather than MIC: on a phone with several microphones it selects the
                // one facing the same way as the camera, and it applies the camcorder tuning
                // instead of the voice-call tuning. The voice tuning is automatic gain control and
                // noise suppression built for speech, which audibly pumps on music and on room
                // ambience - exactly the material a camera records.
                mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
                mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                int intCameraId = getIntCameraId();
                CamcorderProfile profile = chooseProfile(intCameraId, getQuality());
                mMediaRecorder.setProfile(profile);
                mMediaRecorder.setOutputFile(mFile.getAbsolutePath());
                mMediaRecorder.setMaxDuration((int) mCameraView.getMaxVideoDuration());
                mMediaRecorder.setMaxFileSize(mCameraView.getMaxVideoSize());
                mMediaRecorder.setOnInfoListener((mr, what, extra) -> {
                    switch (what) {
                        case MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED:
                            Log.w(TAG, "Max duration for recording reached");
                            finishLimitedRecording();
                            break;
                        case MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED:
                            Log.w(TAG, "Max filesize for recording reached");
                            finishLimitedRecording();
                            break;
                    }
                });
                mMediaRecorder.setVideoSize(getWidth(), getHeight());
                // setProfile fixed the bit rate to the profile's own resolution, but the size
                // above is chosen separately, from what the camera supports at this quality and
                // at the preview's aspect ratio, so the two routinely disagree. Left alone, a
                // smaller frame is written at a wastefully high rate and a larger one is starved.
                mMediaRecorder.setVideoEncodingBitRate(scaleBitRate(
                        profile.videoBitRate, profile.videoFrameWidth, profile.videoFrameHeight,
                        getWidth(), getHeight()));
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

        /**
         * Delivers a recording that MediaRecorder ended on its own because the duration or file
         * size limit was reached. MediaRecorder has stopped capturing by this point but the file
         * is only finalized by stop(), so hand the session back to the module as an explicit
         * stopRecording() would and let it tear the VideoSession down.
         */
        private void finishLimitedRecording() {
            mCameraView.getHandler().post(() -> {
                if (mIsRecordingVideo && mCameraView.isRecording()) {
                    mCameraView.stopRecording();
                }
            });
        }

        void stopRecording() {
            if (mAwaitingRecording) {
                mAwaitingRecording = false;
                if (DEBUG) Log.d(TAG, "Cancelled recording before it started");
                onVideoFailed();
                return;
            }
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
