package com.xlythe.view.camera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.util.Log;
import android.util.Size;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;

import com.xlythe.view.camera.stream.AudioRecorder;
import com.xlythe.view.camera.stream.LossyPipedOutputStream;
import com.xlythe.view.camera.stream.VideoRecorder;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@RequiresApi(18)
public class VideoStream implements Closeable {
  private static final String TAG = VideoStream.class.getSimpleName();

  // A PipedInputStream defaults to a 1024 byte buffer, which is smaller than a single encoded
  // key frame. The writer then blocks mid-frame waiting on the reader for every frame, which
  // stalls the encoder drain loop instead of letting the lossy path shed frames. Size the pipes
  // to hold several frames, and let the lossy stream start dropping well before they fill.
  /** Buffer for the encoded video pipe. */
  private static final int VIDEO_PIPE_BYTES = 1024 * 1024;
  /** Backlog tolerated on the video pipe before droppable frames are discarded. */
  private static final int VIDEO_MAX_BUFFERED_BYTES = 256 * 1024;
  /** Buffer for the PCM audio pipe, whose chunks are far smaller than a video frame. */
  private static final int AUDIO_PIPE_BYTES = 64 * 1024;
  /** Backlog tolerated on the audio pipe before chunks are discarded. */
  private static final int AUDIO_MAX_BUFFERED_BYTES = 16 * 1024;

  /** Ensures recorder and input resources are closed exactly once. */
  private final AtomicBoolean mClosed = new AtomicBoolean();

  /** Determines if this is encoding or decoding a video stream. */
  private final InputType mInputType;

  // ---------- From CameraModule ----------
  /** Encodes an audio stream. Non-null for audio streams of type {@link InputType.CAMERA_MODULE}. */
  @Nullable private final AudioRecorder mAudioRecorder;
  /** Encodes a video stream. Non-null for video streams of type {@link InputType.CAMERA_MODULE}. */
  @Nullable private final VideoRecorder mVideoRecorder;

  // ---------- Output ----------
  /** Exposes a handle to read the encoded audio bytes from the stream. Non-null for audio streams. */
  @Nullable private final InputStream mAudioInputStream;
  /** Exposes a handle to read the encoded video bytes from the stream. Non-null for video streams. */
  @Nullable private final InputStream mVideoInputStream;

  @RequiresPermission(allOf = {
          Manifest.permission.CAMERA,
          Manifest.permission.RECORD_AUDIO
  })
  private VideoStream(ICameraModule cameraModule, Params params) {
    mInputType = InputType.CAMERA_MODULE;

    AudioRecorder audioRecorder = null;
    PipedInputStream audioInputStream = null;
    VideoRecorder videoRecorder = null;
    PipedInputStream videoInputStream = null;
    try {
      if (params.isAudioEnabled()) {
        audioInputStream = new PipedInputStream(AUDIO_PIPE_BYTES);
        audioRecorder = new AudioRecorder(params.isLossy()
                ? new LossyPipedOutputStream(audioInputStream, AUDIO_MAX_BUFFERED_BYTES)
                : new PipedOutputStream(audioInputStream));
        audioRecorder.start();
      }

      if (params.isVideoEnabled()) {
        videoInputStream = new PipedInputStream(VIDEO_PIPE_BYTES);
        videoRecorder = new VideoRecorder(
                cameraModule.getCanvas(),
                params.isLossy()
                        ? new LossyPipedOutputStream(videoInputStream, VIDEO_MAX_BUFFERED_BYTES)
                        : new PipedOutputStream(videoInputStream));
        if (params.getBitRate() != 0) {
          videoRecorder.setBitRate(params.getBitRate());
        }
        if (params.getFrameRate() != 0) {
          videoRecorder.setFrameRate(params.getFrameRate());
        }
        if (params.getIFrameInterval() != 0) {
          videoRecorder.setIFrameInterval(params.getIFrameInterval());
        }
        videoRecorder.setMaxSize(params.getMaxSize());
        videoRecorder.start();
      }
    } catch (IOException | RuntimeException e) {
      if (audioRecorder != null) {
        audioRecorder.stop();
      }
      if (videoRecorder != null) {
        videoRecorder.stop();
      }
      closeQuietly(audioInputStream);
      closeQuietly(videoInputStream);
      throw new IllegalStateException("Failed to create camera stream", e);
    }

    mAudioRecorder = audioRecorder;
    mAudioInputStream = audioInputStream;
    mVideoRecorder = videoRecorder;
    mVideoInputStream = videoInputStream;
  }

  private VideoStream(@Nullable InputStream audioStream,
                      @Nullable InputStream videoStream) {
    mInputType = InputType.INPUT_STREAM;

    mAudioRecorder = null;
    mVideoRecorder = null;

    mAudioInputStream = audioStream;
    mVideoInputStream = videoStream;
  }

  @Override
  public void close() {
    if (!mClosed.compareAndSet(false, true)) {
      return;
    }
    try {
      if (mAudioRecorder != null) {
        mAudioRecorder.stop();
      }
    } catch (RuntimeException e) {
      Log.e(TAG, "Failed to stop audio stream", e);
    }
    try {
      if (mVideoRecorder != null) {
        mVideoRecorder.stop();
      }
    } catch (RuntimeException e) {
      Log.e(TAG, "Failed to stop video stream", e);
    }
    closeQuietly(mAudioInputStream);
    closeQuietly(mVideoInputStream);
    Log.d(TAG, "VideoStream closed");
  }

  private static void closeQuietly(@Nullable InputStream inputStream) {
    if (inputStream == null) {
      return;
    }
    try {
      inputStream.close();
    } catch (IOException e) {
      Log.w(TAG, "Failed to close stream input", e);
    }
  }

  public boolean hasAudio() {
    return mAudioInputStream != null;
  }

  public InputStream getAudioInputStream() {
    if (mAudioInputStream == null) {
      throw new IllegalStateException("Cannot get an input stream from this source");
    }
    return mAudioInputStream;
  }

  public boolean hasVideo() {
    return mVideoInputStream != null;
  }

  public InputStream getVideoInputStream() {
    if (mVideoInputStream == null) {
      throw new IllegalStateException("Cannot get an input stream from this source");
    }
    return mVideoInputStream;
  }

  @NonNull
  @Override
  public String toString() {
    return "VideoStream{" +
            "InputType=" + mInputType +
            ", HasAudio=" + hasAudio() +
            ", HasVideo=" + hasVideo() +
            '}';
  }

  private enum InputType {
    UNKNOWN, INPUT_STREAM, CAMERA_MODULE;
  }

  public static class Builder {
    private InputType mInputType = InputType.UNKNOWN;
    private Params mParams = new Params.Builder().build();
    @Nullable private ICameraModule mCameraModule;
    @Nullable private InputStream mAudioStream;
    @Nullable private InputStream mVideoStream;

    Builder setParams(Params params) {
      mParams = Objects.requireNonNull(params, "Stream params cannot be null");
      return this;
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    Builder attach(ICameraModule cameraModule) {
      setInputType(InputType.CAMERA_MODULE);
      mCameraModule = Objects.requireNonNull(cameraModule, "Camera module cannot be null");
      return this;
    }

    public Builder withAudioStream(InputStream audioStream) {
      setInputType(InputType.INPUT_STREAM);
      mAudioStream = Objects.requireNonNull(audioStream, "Audio stream cannot be null");
      return this;
    }

    public Builder withVideoStream(InputStream videoStream) {
      setInputType(InputType.INPUT_STREAM);
      mVideoStream = Objects.requireNonNull(videoStream, "Video stream cannot be null");
      return this;
    }

    private void setInputType(InputType inputType) {
      if (mInputType.equals(inputType)) {
        return;
      }

      if (!mInputType.equals(InputType.UNKNOWN)) {
        throw new IllegalArgumentException("Cannot change VideoStream source");
      }

      mInputType = inputType;
    }

    @SuppressLint("MissingPermission")
    public VideoStream build() {
      switch (mInputType) {
        case CAMERA_MODULE:
          return new VideoStream(mCameraModule, mParams);
        case INPUT_STREAM:
          return new VideoStream(mAudioStream, mVideoStream);
        case UNKNOWN:
        default:
          throw new IllegalStateException("Cannot create a VideoStream without a source");
      }
    }
  }

  public static class Params {
    /** Whether the stream carries an audio channel, which is what needs the microphone. */
    private final boolean mAudioEnabled;
    /** Whether the stream carries a video channel. */
    private final boolean mVideoEnabled;
    /** Bits per second the encoder aims for. */
    @IntRange(from = 1) private final int mBitRate;
    /** Frames per second the encoder aims for. */
    @IntRange(from = 1) private final int mFrameRate;
    /** Seconds between key frames. A stream can only be joined at one, so this bounds how long
     * a reader waits before the first picture appears; zero makes every frame a key frame. */
    @IntRange(from = 0) private final int mIFrameInterval;
    /** Whether a reader that falls behind loses frames rather than blocking the encoder. */
    private final boolean mIsLossy;
    /** The largest frame to encode, or null to let the camera choose. */
    @Nullable private final Size mMaxSize;

    private Params(boolean audioEnabled,
                   boolean videoEnabled,
                   @IntRange(from = 1) int bitRate,
                   @IntRange(from = 1) int frameRate,
                   @IntRange(from = 0) int iframeInterval,
                   boolean isLossy,
                   @Nullable Size maxSize) {
      this.mAudioEnabled = audioEnabled;
      this.mVideoEnabled = videoEnabled;
      this.mBitRate = bitRate;
      this.mFrameRate = frameRate;
      this.mIFrameInterval = iframeInterval;
      this.mIsLossy = isLossy;
      this.mMaxSize = maxSize;
    }

    public boolean isAudioEnabled() {
      return mAudioEnabled;
    }

    public boolean isVideoEnabled() {
      return mVideoEnabled;
    }

    @IntRange(from = 1)
    public int getBitRate() {
      return mBitRate;
    }

    @IntRange(from = 1)
    public int getFrameRate() {
      return mFrameRate;
    }

    @IntRange(from = 0)
    public int getIFrameInterval() {
      return mIFrameInterval;
    }

    public boolean isLossy() {
      return mIsLossy;
    }

    /** The resolution cap for the video stream, or null to follow the preview. */
    @Nullable
    public Size getMaxSize() {
      return mMaxSize;
    }

    public static class Builder {
      private boolean mAudioEnabled = true;
      private boolean mVideoEnabled = true;
      private int mBitRate;
      private int mFrameRate;
      private int mIFrameInterval;
      private boolean mIsLossy = true;
      @Nullable private Size mMaxSize;

      public Builder setAudioEnabled(boolean audioEnabled) {
        this.mAudioEnabled = audioEnabled;
        return this;
      }

      public Builder setVideoEnabled(boolean videoEnabled) {
        this.mVideoEnabled = videoEnabled;
        return this;
      }

      public Builder setBitRate(@IntRange(from = 1) int bitRate) {
        mBitRate = bitRate;
        return this;
      }

      public Builder setFrameRate(@IntRange(from = 1) int frameRate) {
        mFrameRate = frameRate;
        return this;
      }

      /** Seconds between key frames; zero makes every frame one. */
      public Builder setIFrameInterval(@IntRange(from = 0) int iFrameInterval) {
        mIFrameInterval = iFrameInterval;
        return this;
      }

      public Builder setIsLossy(boolean isLossy) {
        mIsLossy = isLossy;
        return this;
      }

      /**
       * Caps the resolution of the video stream, independent of the preview on screen.
       *
       * <p>Set this when the stream leaves the device over a link that cannot carry the
       * preview's resolution, such as a watch reached over Bluetooth. The active camera picks
       * the closest size it supports within the bound, so the stream is usually smaller than
       * asked for and never larger. Leave it unset to follow the preview.</p>
       */
      public Builder setMaxSize(@Nullable Size maxSize) {
        mMaxSize = maxSize;
        return this;
      }

      public Params build() {
        if (!mAudioEnabled && !mVideoEnabled) {
          throw new IllegalStateException("Cannot create a stream with both audio and video disabled");
        }
        if (mBitRate < 0 || mFrameRate < 0 || mIFrameInterval < 0) {
          throw new IllegalArgumentException("Stream encoding values cannot be negative");
        }
        if (mMaxSize != null && (mMaxSize.getWidth() <= 0 || mMaxSize.getHeight() <= 0)) {
          throw new IllegalArgumentException("Stream dimensions must be positive: " + mMaxSize);
        }

        return new Params(mAudioEnabled, mVideoEnabled, mBitRate, mFrameRate, mIFrameInterval,
                mIsLossy, mMaxSize);
      }
    }
  }
}
