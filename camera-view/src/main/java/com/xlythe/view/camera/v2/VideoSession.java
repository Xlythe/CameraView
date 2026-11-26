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
import android.os.Build;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.gl.FrameRenderThread;
import com.xlythe.view.camera.gl.GlMath;
import com.xlythe.view.camera.HighSpeedProfile;

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

    /** What a slow-motion file plays back at where the device publishes no profile to copy. */
    private static final int DEFAULT_PLAYBACK_FRAME_RATE = 30;

    private final VideoSurface mVideoSurface;

    /** Whether this session carries a still output alongside the recording. */
    private final boolean mSnapshotSupported;

    /** The high-speed choice this records at, or null for an ordinary recording. */
    @Nullable
    private final HighSpeedProfile mHighSpeedProfile;

    VideoSession(Camera2Module camera2Module, File file) {
        this(camera2Module, file, null);
    }

    VideoSession(Camera2Module camera2Module, File file,
            @Nullable HighSpeedProfile highSpeedProfile) {
        // No RAW output while recording, whatever format was asked for: Camera2's guaranteed
        // combinations carry a preview, a recording and a still, and a full-size RAW stream
        // alongside all three is not among them. A snapshot asked for in a RAW format is refused
        // rather than written as a JPEG the caller did not ask for.
        super(camera2Module, false /* capturesRaw */);
        mHighSpeedProfile = highSpeedProfile;
        mVideoSurface = new VideoSurface(camera2Module, file, getPreviewSurface(),
                highSpeedProfile);
        // A constrained high-speed session takes the preview and the recording and nothing else,
        // so there is no still output to decide about.
        mSnapshotSupported =
                highSpeedProfile == null && camera2Module.isRecordingSnapshotSupported();
        if (highSpeedProfile != null) {
            // Every surface in a high-speed session has to be the published high-speed size,
            // the preview included.
            requirePreviewSize(highSpeedProfile.getSize());
        }
    }

    @Override
    public boolean isHighSpeed() {
        return mHighSpeedProfile != null;
    }

    /**
     * Whether a picture can be taken while this session is recording.
     *
     * <p>Camera2 guarantees a preview, a recording and a still together from LIMITED hardware
     * upwards - it is the combination every camera app's video-snapshot button uses - but only
     * with the still no larger than the recording, which is what {@link #maxPictureSize} says.
     * A LEGACY camera has no such guarantee, so it records without one rather than risking a
     * session it cannot configure.</p>
     *
     * <p>Always false for a high-speed recording, whatever the hardware level: that session
     * accepts no third stream at all. {@link PictureSession#takePicture} then reports a failure
     * and the recording carries on, which is the only choice that neither crashes nor hangs -
     * tearing the session down to take the picture would end the recording the caller asked for,
     * and queueing the picture until the recording stops would leave a caller waiting on a
     * callback for as long as they keep recording.</p>
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
    @SuppressLint({"WrongThread", "ThreadConstraint"})
    protected void onPreviewInitialized(@NonNull StreamConfigurationMap map)
            throws CameraAccessException {
        mVideoSurface.initialize(map);
        if (!mVideoSurface.mIsInitialized) {
            onVideoFailed();
            throw new CameraAccessException(CameraAccessException.CAMERA_ERROR);
        }
        // After the recorder has a size, which is what the renderer has to be told.
        attachRecorderToRenderer();
    }

    private CaptureRequest createCaptureRequest(@NonNull CameraDevice device) throws CameraAccessException {
        CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        if (mHighSpeedProfile != null) {
            // The frame rate range is what tells the camera to run fast, and it has to be one of
            // the ranges published for this size - createHighSpeedRequestList refuses anything
            // else. Metering, face detection and zoom are left off: a high-speed request is
            // checked against a short list of controls the camera accepts in this mode, and the
            // whole request is refused over one it does not, which would cost the recording.
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    mHighSpeedProfile.getFrameRateRange());
        } else {
            if (mMeteringRectangle != null) {
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{mMeteringRectangle});
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{mMeteringRectangle});
            }
            applyZoom(builder);
            applyFaceDetection(builder);
        }
        builder.addTarget(getPreviewSurface().getSurface());
        if (!mThroughGl) {
            // While an effect is running the recorder is drawn into rather than captured into,
            // so it is not a target: the camera writes one stream, into the renderer.
            builder.addTarget(mVideoSurface.getSurface());
        }
        return builder.build();
    }

    /**
     * Starts the repeating work this session records from.
     *
     * <p>Which call that is depends on the kind of session: a high-speed one is driven by a burst
     * the camera itself builds. See {@link HighSpeedCapture#setRepeatingBurst}.</p>
     */
    private void submitRepeating(@NonNull CameraCaptureSession session,
            @NonNull CaptureRequest request,
            @Nullable CameraCaptureSession.CaptureCallback callback) throws CameraAccessException {
        if (mHighSpeedProfile != null && Build.VERSION.SDK_INT >= 23) {
            HighSpeedCapture.setRepeatingBurst(session, request, callback, getHandler());
            return;
        }
        session.setRepeatingRequest(request, callback, getHandler());
    }

    @Override
    public void onAvailable(@NonNull CameraDevice device, @NonNull CameraCaptureSession session) throws CameraAccessException {
        if (!mVideoSurface.mIsInitialized) {
            Log.w(TAG, "onAvailable failed. Not initialize.");
            return;
        }

        mVideoSurface.mAwaitingRecording = true;
        submitRepeating(session, createCaptureRequest(device), new CameraCaptureSession.CaptureCallback() {
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
        });
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

        submitRepeating(session, createCaptureRequest(device), repeatingCallback());
    }

    @Override
    public void close() {
        if (mThroughGl) {
            FrameRenderThread effect = getModule().getEffect();
            if (effect != null) {
                // Before the recorder stops, because the renderer is drawing into its surface
                // and removing an output waits until it has stopped.
                effect.removeOutput(mVideoSurface);
            }
            mThroughGl = false;
        }
        super.close();
        mVideoSurface.stopRecording();
        mVideoSurface.close();
    }

    /**
     * Whether the recording is being drawn by the renderer rather than filled by the camera.
     *
     * <p>Decided once the sizes are known, in {@link #initialize}, because that is when there
     * is a recorder surface to hand the renderer.</p>
     */
    private boolean mThroughGl;

    @NonNull
    @Override
    public List<Surface> getSurfaces() {
        List<Surface> surfaces = super.getSurfaces();
        if (!mThroughGl) {
            surfaces.add(mVideoSurface.getSurface());
        }
        return surfaces;
    }

    /**
     * Gives the recorder to the renderer, so that what is recorded is what is on screen.
     *
     * <p>Without this a filter would reach the preview and the photograph and not the video,
     * which is the one output somebody would notice last and mind most. The recorder takes a
     * surface and does not care who fills it, so the renderer fills it with the same frame it
     * is drawing everywhere else.</p>
     *
     * <p>Not for a high-speed recording. Those are driven by a burst the camera builds at a
     * frame rate no GL pass is going to keep up with, and a renderer that dropped half of a
     * two hundred and forty frame second would turn slow motion into something worse than
     * unfiltered.</p>
     */
    private void attachRecorderToRenderer() {
        FrameRenderThread effect = getModule().getEffect();
        if (effect == null || mHighSpeedProfile != null) {
            mThroughGl = false;
            return;
        }
        Size size = new Size(mVideoSurface.getWidth(), mVideoSurface.getHeight());
        effect.addOutput(mVideoSurface, mVideoSurface.getSurface(), size, size,
                GlMath.ScaleType.FILL_CENTER,
                (surfaceTextureMatrix, into) ->
                        System.arraycopy(surfaceTextureMatrix, 0, into, 0, into.length));
        mThroughGl = true;
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
        /**
         * The audio rate to record at, given the profile and the size actually being recorded.
         *
         * <p>The profile is chosen by quality and the size separately, from what the camera
         * supports at that quality and at the preview's aspect ratio, so the two routinely
         * disagree. Where the recording is bigger than its profile, the profile's audio is too low
         * for it: the video rate is scaled up to suit, and the audio was left behind.</p>
         *
         * <p>Only ever raised, and only as far as the best the device itself publishes - so this
         * cannot ask an encoder for a rate no profile on the device uses. A recording smaller than
         * its profile keeps the profile's audio, since there is nothing wrong with it.</p>
         *
         * @return the rate to set, or 0 to leave the profile's alone
         */
        static int audioBitRateFor(@NonNull CamcorderProfile profile,
                @Nullable CamcorderProfile best, int width, int height) {
            if (best == null || best.audioBitRate <= profile.audioBitRate) {
                return 0;
            }
            long recorded = (long) width * height;
            long inProfile = (long) profile.videoFrameWidth * profile.videoFrameHeight;
            if (recorded <= inProfile) {
                return 0;
            }
            return best.audioBitRate;
        }

        /**
         * The frame rate a slow-motion recording claims, which is what makes it slow.
         *
         * <p>MediaRecorder writes the file at this rate while the camera fills the surface at the
         * capture rate, so a 240fps stream tagged 30fps is eight seconds of file for every second
         * in front of the lens. Tagging it 240 instead would give a correctly timed file nothing
         * plays smoothly, which is the mistake this exists to avoid.</p>
         *
         * <p>Fixed at 30 rather than taken from the device's ordinary profile. Following the
         * profile looks more respectful of the device and is worse: a Pixel 7 Pro records
         * ordinarily at 60, so the same 240fps capture came out four times slower there and
         * eight times slower on the CameraX backend, which encodes slow motion at 30 and says so
         * in its own SLOW_MOTION_ENCODE_FRAME_RATE. Two backends disagreeing about how slow slow
         * motion is defeats the point of having both. Measured on that device: 240fps tagged 60
         * wrote 5187ms from 2000ms of filming where the caller had every reason to expect 16s.
         *
         * <p>A capture rate no faster than playback is left alone rather than written as slower
         * than life.</p>
         */
        static int playbackFrameRate(int captureRate) {
            if (captureRate > 0 && captureRate < DEFAULT_PLAYBACK_FRAME_RATE) {
                return captureRate;
            }
            return DEFAULT_PLAYBACK_FRAME_RATE;
        }

        /**
         * Rescales a bit rate from the frame rate its profile was written for.
         *
         * <p>The size rescaling in {@link #scaleBitRate} is not enough on its own here: a
         * high-speed recording puts eight times as many 1080p frames through the encoder per
         * second as a 30fps one, and a profile's rate pays for thirty of them. Left alone, every
         * frame gets a thirtieth of the bits it was budgeted and the result is mush exactly when
         * the detail matters.</p>
         *
         * <p>Linear in the frame rate, which over-asks slightly - consecutive frames 4ms apart
         * are more alike than frames 33ms apart, so each needs fewer bits - and the ceiling in
         * {@link #scaleBitRate} catches the extreme. Over-asking is the safe direction: an
         * encoder given more than it needs writes a larger file, while one given less throws
         * detail away permanently.</p>
         *
         * @param profileFrameRate the rate the bit rate was published for, or 0 when unknown
         */
        static int scaleBitRateForFrameRate(int bitRate, int profileFrameRate, int captureRate) {
            if (bitRate <= 0 || profileFrameRate <= 0 || captureRate <= profileFrameRate) {
                return clampBitRate(bitRate);
            }
            return clampBitRate((long) bitRate * captureRate / profileFrameRate);
        }

        private static int clampBitRate(long bitRate) {
            return (int) Math.max(MIN_VIDEO_BIT_RATE, Math.min(bitRate, MAX_VIDEO_BIT_RATE));
        }

        /**
         * The high-speed profile this device publishes for a frame size, or null for none.
         *
         * <p>Preferred over the ordinary profile as the basis for a high-speed recording because
         * it is the device's own answer to this question: its bit rate and frame rate are the
         * ones the manufacturer records slow motion at. Not every device publishes them, and a
         * published size does not promise a matching profile, so the caller still needs a
         * fallback.</p>
         */
        @Nullable
        static CamcorderProfile highSpeedProfile(int cameraId, @NonNull Size size) {
            int quality;
            if (size.getHeight() >= SIZE_4K.getHeight()) {
                quality = CamcorderProfile.QUALITY_HIGH_SPEED_2160P;
            } else if (size.getHeight() >= SIZE_1080P.getHeight()) {
                quality = CamcorderProfile.QUALITY_HIGH_SPEED_1080P;
            } else if (size.getHeight() >= SIZE_720P.getHeight()) {
                quality = CamcorderProfile.QUALITY_HIGH_SPEED_720P;
            } else {
                quality = CamcorderProfile.QUALITY_HIGH_SPEED_480P;
            }
            try {
                if (CamcorderProfile.hasProfile(cameraId, quality)) {
                    return CamcorderProfile.get(cameraId, quality);
                }
                if (CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_HIGH_SPEED_LOW)) {
                    return CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_HIGH_SPEED_LOW);
                }
            } catch (RuntimeException error) {
                if (DEBUG) Log.d(TAG, "No high-speed profile for quality " + quality, error);
            }
            return null;
        }

        /**
         * The richest profile this camera publishes, for the settings that are not about size.
         *
         * @return the profile, or null for a camera that publishes none
         */
        @Nullable
        private static CamcorderProfile highestProfile(int cameraId) {
            int[] qualities = new int[] {
                    CamcorderProfile.QUALITY_2160P,
                    CamcorderProfile.QUALITY_1080P,
                    CamcorderProfile.QUALITY_720P,
                    CamcorderProfile.QUALITY_480P,
            };
            for (int quality : qualities) {
                try {
                    if (CamcorderProfile.hasProfile(cameraId, quality)) {
                        return CamcorderProfile.get(cameraId, quality);
                    }
                } catch (RuntimeException error) {
                    if (DEBUG) Log.d(TAG, "No profile for quality " + quality, error);
                }
            }
            return null;
        }

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

        /** The high-speed choice this records at, or null for an ordinary recording. */
        @Nullable
        private final HighSpeedProfile mHighSpeedProfile;

        VideoSurface(Camera2Module cameraView, @NonNull File file, CameraSurface previewSurface) {
            this(cameraView, file, previewSurface, null);
        }

        VideoSurface(Camera2Module cameraView, @NonNull File file, CameraSurface previewSurface,
                @Nullable HighSpeedProfile highSpeedProfile) {
            super(cameraView);
            mFile = file;
            mPreviewSurface = previewSurface;
            mHighSpeedProfile = highSpeedProfile;
        }

        @Override
        void initialize(StreamConfigurationMap map) {
            if (DEBUG) Log.d(TAG, "Initializing VideoSession");
            // A high-speed session is only configured at a size the camera published for it, so
            // there is nothing left to choose; an ordinary one picks from everything the camera
            // and MediaRecorder agree on.
            super.initialize(mHighSpeedProfile != null ? mHighSpeedProfile.getSize()
                    : chooseSize(getSizes(map), mPreviewSurface.mSize));

            try {
                mMediaRecorder = new MediaRecorder();
                // A high-speed recording has no audio at all, neither a source here nor an
                // encoder below. setCaptureRate puts MediaRecorder into time lapse mode, which
                // records video only, so an audio encoder configured here would be one with
                // nothing to feed it and prepare() would fail. No great loss: a second of audio
                // stretched over eight seconds of video is not audio anyone wants.
                if (mHighSpeedProfile == null) {
                    // CAMCORDER rather than MIC: on a phone with several microphones it selects the
                    // one facing the same way as the camera, and it applies the camcorder tuning
                    // instead of the voice-call tuning. The voice tuning is automatic gain control and
                    // noise suppression built for speech, which audibly pumps on music and on room
                    // ambience - exactly the material a camera records.
                    mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
                }
                mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                int intCameraId = getIntCameraId();
                CamcorderProfile profile = chooseProfile(intCameraId, getQuality());
                if (mHighSpeedProfile != null) {
                    applyHighSpeed(intCameraId, profile);
                } else {
                    mMediaRecorder.setProfile(profile);
                }
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
                mMediaRecorder.setVideoEncodingBitRate(videoBitRate(intCameraId, profile));
                if (mHighSpeedProfile == null) {
                    // setProfile fixed the audio rate the same way, and unlike the video above it
                    // was never corrected - so a recording larger than its profile carried that
                    // profile's audio. A 480p profile is 96kbps, which is not what someone
                    // recording 4K expects, and audio is a rounding error beside the video rate,
                    // so there is nothing to save by skimping on it.
                    int audioBitRate = audioBitRateFor(
                            profile, highestProfile(intCameraId), getWidth(), getHeight());
                    if (audioBitRate > 0) {
                        mMediaRecorder.setAudioEncodingBitRate(audioBitRate);
                    }
                }
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

        /**
         * Configures the recorder for slow motion, in place of {@link MediaRecorder#setProfile}.
         *
         * <p>setProfile is not used on this path because it also configures an audio encoder, and
         * a time lapse recording has no audio source to feed one. The container and the video
         * codec are the parts of a profile worth having here, so they are taken by hand.</p>
         *
         * @param fallback the camera's ordinary profile, for a device that publishes no
         *     high-speed one and for the rate this recording should play back at
         */
        private void applyHighSpeed(int cameraId, @NonNull CamcorderProfile fallback) {
            CamcorderProfile highSpeed = highSpeedProfile(cameraId, mHighSpeedProfile.getSize());
            CamcorderProfile container = highSpeed != null ? highSpeed : fallback;
            mMediaRecorder.setOutputFormat(container.fileFormat);
            mMediaRecorder.setVideoEncoder(container.videoCodec);
            // These two lines are the whole trick. The camera fills the surface at the capture
            // rate while the file declares the slower rate, so a player shows the frames further
            // apart in time than they were taken. Setting the frame rate to the capture rate
            // instead would give a correctly timed file at a rate nothing plays smoothly.
            mMediaRecorder.setVideoFrameRate(playbackFrameRate(mHighSpeedProfile.getFrameRate()));
            mMediaRecorder.setCaptureRate(mHighSpeedProfile.getFrameRate());
        }

        /** The rate to write the video at, for the size and frame rate actually being recorded. */
        private int videoBitRate(int cameraId, @NonNull CamcorderProfile profile) {
            if (mHighSpeedProfile == null) {
                return scaleBitRate(profile.videoBitRate, profile.videoFrameWidth,
                        profile.videoFrameHeight, getWidth(), getHeight());
            }
            // The device's own slow-motion profile where there is one: its bit rate is the
            // manufacturer's answer for this resolution at this sort of frame rate, which beats
            // extrapolating from a profile written for thirty frames a second.
            CamcorderProfile basis = highSpeedProfile(cameraId, mHighSpeedProfile.getSize());
            if (basis == null) {
                basis = profile;
            }
            return scaleBitRateForFrameRate(
                    scaleBitRate(basis.videoBitRate, basis.videoFrameWidth,
                            basis.videoFrameHeight, getWidth(), getHeight()),
                    basis.videoFrameRate, mHighSpeedProfile.getFrameRate());
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
