package com.xlythe.view.camera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.os.Build;
import android.util.Log;
import android.util.Size;

import androidx.annotation.AnyThread;
import androidx.annotation.IntDef;
import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;

import com.xlythe.view.camera.stream.AudioBuffer;
import com.xlythe.view.camera.stream.AudioRecorder;
import com.xlythe.view.camera.stream.DeliveryMeter;
import com.xlythe.view.camera.stream.LossyPipedOutputStream;
import com.xlythe.view.camera.stream.PlaybackCapabilities;
import com.xlythe.view.camera.stream.VideoRecorder;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
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
  /**
   * Buffer for the PCM audio pipe. Room for the recorder's latency budget at the highest rate it
   * can settle on, 48kHz stereo, with a chunk on top, so a write never blocks the recorder.
   */
  private static final int AUDIO_PIPE_BYTES = 128 * 1024;
  /** Backlog tolerated on the audio pipe until the recorder sets it from its sample rate. */
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

  /**
   * Measures how fast the video is read, and how late it arrives when the app says so. Non-null
   * for video streams from a camera.
   */
  @Nullable private final DeliveryMeter mVideoDeliveryMeter;

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
    DeliveryMeter audioMeter = new DeliveryMeter();
    DeliveryMeter videoMeter = new DeliveryMeter();
    try {
      if (params.isAudioEnabled()) {
        audioInputStream = new PipedInputStream(AUDIO_PIPE_BYTES);
        audioRecorder = new AudioRecorder(
                params.isLossy()
                        ? new LossyPipedOutputStream(audioInputStream, AUDIO_MAX_BUFFERED_BYTES)
                        : new PipedOutputStream(audioInputStream),
                params.getAudioSampleRate(),
                params.getAudioChannelCount(),
                new AudioRecorder.Options(
                        params.getPlaybackCapabilities(),
                        params.isNoiseSuppressionEnabled(),
                        params.isEchoCancellationEnabled(),
                        params.isAutomaticGainControlEnabled(),
                        audioMeter));
        audioRecorder.start();
      }

      if (params.isVideoEnabled()) {
        videoInputStream = new PipedInputStream(VIDEO_PIPE_BYTES);
        videoRecorder = new VideoRecorder(
                cameraModule.getCanvas(),
                params.isLossy()
                        ? new LossyPipedOutputStream(videoInputStream, VIDEO_MAX_BUFFERED_BYTES)
                        : new PipedOutputStream(videoInputStream));
        videoRecorder.setDeliveryMeter(videoMeter);
        if (params.getBitRate() != 0) {
          videoRecorder.setBitRate(params.getBitRate());
        }
        if (params.getFrameRate() != 0) {
          videoRecorder.setFrameRate(params.getFrameRate());
        }
        if (params.getIFrameInterval() != 0) {
          videoRecorder.setIFrameInterval(params.getIFrameInterval());
        }
        if (Build.VERSION.SDK_INT >= 21) {
          videoRecorder.setMaxSize(params.getMaxSize());
        }
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
    mAudioInputStream = audioInputStream == null ? null : audioMeter.wrap(audioInputStream);
    mVideoRecorder = videoRecorder;
    mVideoInputStream = videoInputStream == null ? null : videoMeter.wrap(videoInputStream);
    mVideoDeliveryMeter = videoInputStream == null ? null : videoMeter;
  }

  private VideoStream(@Nullable InputStream audioStream,
                      @Nullable InputStream videoStream) {
    mInputType = InputType.INPUT_STREAM;

    mAudioRecorder = null;
    mVideoRecorder = null;

    mAudioInputStream = audioStream;
    mVideoInputStream = videoStream;
    mVideoDeliveryMeter = null;
  }

  /**
   * Describes what this device can play, for the device that will stream to it.
   *
   * <p>Send these bytes to the other device however the app already asks it to start streaming,
   * and pass them to {@link Params.Builder#setPlaybackCapabilities} there. The stream then
   * compresses its audio into the best format both devices share, which is a fraction of the
   * bandwidth. A device that is never sent them streams uncompressed audio, which every version
   * of this library plays, so two devices on different versions still understand each other.</p>
   */
  @NonNull
  @AnyThread
  public static byte[] getPlaybackCapabilities() {
    return PlaybackCapabilities.forThisDevice();
  }

  /**
   * Reports how much of this stream's video the receiving device has received so far, as a
   * running total of bytes.
   *
   * <p>Optional, for an app whose link already confirms delivery. The stream lowers its quality
   * when whoever reads it falls behind, and it can see that on its own once a backlog builds up;
   * confirmations show a link slowing down before then, from how much longer each takes to come
   * back. Has no effect on a stream that is not being sent from this device's camera.</p>
   */
  @AnyThread
  public void reportVideoReceived(long totalBytes) {
    DeliveryMeter meter = mVideoDeliveryMeter;
    if (meter != null) {
      meter.onAcknowledged(totalBytes);
    }
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

  /** A rate audio can be recorded at, in hertz: the ones the recorder can fall back through. */
  @Retention(RetentionPolicy.SOURCE)
  @IntDef({8000, 11025, 16000, 22050, 44100, 48000})
  public @interface SampleRate {}

  /** One channel or two. */
  @Retention(RetentionPolicy.SOURCE)
  @IntDef({1, 2})
  public @interface ChannelCount {}

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
    /** The rate to record audio at, which the device may not be able to honour. */
    private final int mAudioSampleRate;
    /** The channels to record, likewise. */
    private final int mAudioChannelCount;
    /** What the receiving device said it plays, or null when it said nothing. */
    @Nullable private final byte[] mPlaybackCapabilities;
    /** Whether to suppress background noise in the audio, where the device can. */
    private final boolean mNoiseSuppressionEnabled;
    /** Whether to cancel this device's own playback from the audio, where it can. */
    private final boolean mEchoCancellationEnabled;
    /** Whether to even out the microphone's level, where the device can. */
    private final boolean mAutomaticGainControlEnabled;

    private Params(boolean audioEnabled,
                   boolean videoEnabled,
                   @IntRange(from = 1) int bitRate,
                   @IntRange(from = 1) int frameRate,
                   @IntRange(from = 0) int iframeInterval,
                   boolean isLossy,
                   @Nullable Size maxSize,
                   int audioSampleRate,
                   int audioChannelCount,
                   @Nullable byte[] playbackCapabilities,
                   boolean noiseSuppressionEnabled,
                   boolean echoCancellationEnabled,
                   boolean automaticGainControlEnabled) {
      this.mPlaybackCapabilities = playbackCapabilities;
      this.mNoiseSuppressionEnabled = noiseSuppressionEnabled;
      this.mEchoCancellationEnabled = echoCancellationEnabled;
      this.mAutomaticGainControlEnabled = automaticGainControlEnabled;
      this.mAudioSampleRate = audioSampleRate;
      this.mAudioChannelCount = audioChannelCount;
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

    /** The sample rate audio should be recorded at, in hertz. */
    @SampleRate
    public int getAudioSampleRate() {
      return mAudioSampleRate;
    }

    /** How many audio channels to record. */
    @ChannelCount
    public int getAudioChannelCount() {
      return mAudioChannelCount;
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

    @Nullable
    byte[] getPlaybackCapabilities() {
      return mPlaybackCapabilities == null ? null : mPlaybackCapabilities.clone();
    }

    boolean isNoiseSuppressionEnabled() {
      return mNoiseSuppressionEnabled;
    }

    boolean isEchoCancellationEnabled() {
      return mEchoCancellationEnabled;
    }

    boolean isAutomaticGainControlEnabled() {
      return mAutomaticGainControlEnabled;
    }

    public static class Builder {
      private int mAudioSampleRate = AudioBuffer.DEFAULT_SAMPLE_RATE;
      private int mAudioChannelCount = AudioBuffer.DEFAULT_CHANNEL_COUNT;
      private boolean mAudioEnabled = true;
      private boolean mVideoEnabled = true;
      private int mBitRate;
      private int mFrameRate;
      private int mIFrameInterval;
      private boolean mIsLossy = true;
      @Nullable private Size mMaxSize;
      /** What the receiving device said it plays; null sends uncompressed audio. */
      @Nullable private byte[] mPlaybackCapabilities;
      /** Whether to ask for noise suppression. */
      private boolean mNoiseSuppressionEnabled;
      /** Whether to ask for echo cancellation. */
      private boolean mEchoCancellationEnabled;
      /** Whether to ask for automatic gain control. */
      private boolean mAutomaticGainControlEnabled;

      public Builder setAudioEnabled(boolean audioEnabled) {
        this.mAudioEnabled = audioEnabled;
        return this;
      }

      public Builder setVideoEnabled(boolean videoEnabled) {
        this.mVideoEnabled = videoEnabled;
        return this;
      }

      /**
       * The video bit rate to aim for, in bits per second.
       *
       * <p>On a lossy stream this is the most it sends. When whoever reads the stream falls behind,
       * the stream lowers its bit rate to what they are keeping up with, and raises it back
       * gradually once they catch up. Below a floor, around 150kbps, it sends fewer frames and
       * then smaller ones instead of blurrier ones, and restores them in the same way.</p>
       */
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
      @RequiresApi(21)
      public Builder setMaxSize(@Nullable Size maxSize) {
        if (maxSize != null && (maxSize.getWidth() <= 0 || maxSize.getHeight() <= 0)) {
          throw new IllegalArgumentException("Stream dimensions must be positive: " + maxSize);
        }
        mMaxSize = maxSize;
        return this;
      }

      /**
       * The sample rate to record audio at, in hertz.
       *
       * <p>Defaults to {@link AudioBuffer#DEFAULT_SAMPLE_RATE}, which is wideband speech. Ask for
       * more when the stream carries something worth more - 44100 or 48000 for music - and less
       * when it has to fit down a narrow link. A rate the device cannot record at falls back to
       * the highest it can, and the stream says which it settled on in its header, so the other
       * end does not have to be told separately.</p>
       */
      public Builder setAudioSampleRate(@SampleRate int audioSampleRate) {
        mAudioSampleRate = audioSampleRate;
        return this;
      }

      /**
       * How many audio channels to record, 1 or 2.
       *
       * <p>Defaults to one. Two needs a device with two microphones and roughly doubles the
       * bandwidth; a device that cannot manage it records one and says so in the header. The
       * second channel is given up before the sample rate is.</p>
       */
      public Builder setAudioChannelCount(@ChannelCount int audioChannelCount) {
        mAudioChannelCount = audioChannelCount;
        return this;
      }

      /**
       * What the device that plays this stream can play, as
       * {@link VideoStream#getPlaybackCapabilities()} returned it there.
       *
       * <p>With it, the audio is compressed into the best format both devices share, at a bit rate
       * that falls when the reader falls behind. Without it, or when the bytes came from somewhere
       * else, the audio is sent uncompressed, which is what a device running an older version of
       * this library expects.</p>
       */
      public Builder setPlaybackCapabilities(@Nullable byte[] playbackCapabilities) {
        mPlaybackCapabilities = playbackCapabilities == null ? null : playbackCapabilities.clone();
        return this;
      }

      /**
       * Suppresses steady background noise in the audio, such as fans or traffic, on devices that
       * can. Off by default, because it treats anything that is not a voice as noise.
       */
      public Builder setNoiseSuppressionEnabled(boolean enabled) {
        mNoiseSuppressionEnabled = enabled;
        return this;
      }

      /**
       * Removes this device's own speaker from what its microphone records, on devices that can,
       * so a conversation does not echo back to the person who spoke.
       *
       * <p>Turn it on when this device also plays audio from the other end. It records through the
       * device's voice call tuning, which the canceller needs and which favours a nearby voice over
       * the scene in front of the camera, so it is off by default.</p>
       */
      public Builder setEchoCancellationEnabled(boolean enabled) {
        mEchoCancellationEnabled = enabled;
        return this;
      }

      /**
       * Evens out the microphone's level, on devices that can, so a quiet speaker and a loud one
       * arrive at a similar volume. Off by default, because it also lifts the background between
       * words.
       */
      public Builder setAutomaticGainControlEnabled(boolean enabled) {
        mAutomaticGainControlEnabled = enabled;
        return this;
      }

      public Params build() {
        if (!mAudioEnabled && !mVideoEnabled) {
          throw new IllegalStateException("Cannot create a stream with both audio and video disabled");
        }
        if (mBitRate < 0 || mFrameRate < 0 || mIFrameInterval < 0) {
          throw new IllegalArgumentException("Stream encoding values cannot be negative");
        }
        if (mAudioSampleRate <= 0) {
          throw new IllegalArgumentException(
                  "Audio sample rate must be positive: " + mAudioSampleRate);
        }
        if (mAudioChannelCount < 1 || mAudioChannelCount > 2) {
          throw new IllegalArgumentException(
                  "Audio is mono or stereo, not " + mAudioChannelCount + " channels");
        }
        return new Params(mAudioEnabled, mVideoEnabled, mBitRate, mFrameRate, mIFrameInterval,
                mIsLossy, mMaxSize, mAudioSampleRate, mAudioChannelCount, mPlaybackCapabilities,
                mNoiseSuppressionEnabled, mEchoCancellationEnabled, mAutomaticGainControlEnabled);
      }
    }
  }
}
