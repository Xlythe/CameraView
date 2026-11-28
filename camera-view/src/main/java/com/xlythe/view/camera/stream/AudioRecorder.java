package com.xlythe.view.camera.stream;

import android.Manifest;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;
import androidx.annotation.RestrictTo;
import androidx.annotation.WorkerThread;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.VideoStream;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static android.os.Process.THREAD_PRIORITY_AUDIO;
import static android.os.Process.setThreadPriority;

/**
 * When created, you must pass a {@link ParcelFileDescriptor}. Once {@link #start()} is called, the
 * file descriptor will be written to until {@link #stop()} is called.
 */
public class AudioRecorder {
  private static final String TAG = CameraView.class.getSimpleName();

  /**
   * How far behind the reader may fall, as a duration of audio, before chunks are dropped. The
   * same budget VideoRecorder gives video, so neither half of a stream runs further behind.
   */
  static final long TARGET_BACKLOG_MILLIS = 250;

  /** 16-bit PCM, which is the only encoding the microphone is read in. */
  private static final int BYTES_PER_SAMPLE = 2;

  /** The least backlog compressed audio is allowed, a handful of frames at the lowest rate. */
  static final int MIN_COMPRESSED_BACKLOG_BYTES = 512;

  /** The least time between two cuts to the audio bit rate. Each one restarts the encoder. */
  static final long DECREASE_INTERVAL_MILLIS = 500;

  /** The least time before the audio bit rate is raised again. */
  static final long INCREASE_INTERVAL_MILLIS = 2000;

  /** How far the controller's rate has to fall below the encoder's before it is restarted. */
  static final double DECREASE_THRESHOLD = 0.85;

  /** How far it has to rise above it. */
  static final double INCREASE_THRESHOLD = 1.2;

  /** The stream to write to. */
  private final OutputStream mOutputStream;

  /**
   * If true, the background thread will continue to loop and record audio. Once false, the thread
   * will shut down.
   */
  private volatile boolean mIsAlive;
  /** Ensures the output stream is closed exactly once. */
  private final AtomicBoolean mStopped = new AtomicBoolean();

  /** The background thread recording audio for us. */
  private volatile Thread mThread;

  /** The rate asked for, which the buffer may not be able to honour. */
  private final int mPreferredSampleRate;

  /** The channel count asked for, likewise. */
  private final int mPreferredChannelCount;

  /** What the stream was asked for beyond the shape of the audio. */
  private final Options mOptions;

  /** Opens the microphone, or what stands in for it under test. */
  private final PcmSource.Factory mSourceFactory;

  /** The format the stream settled on, once it has; PCM until then. */
  @AudioCodec.Codec private volatile int mCodec = AudioCodec.PCM;

  /** The bit rate the audio is being encoded at, or 0 before it starts. */
  private volatile int mBitRate;

  /**
   * A simple audio recorder.
   *
   * @param pfd The output stream of the recording.
   */
  public AudioRecorder(ParcelFileDescriptor pfd) {
    this(new ParcelFileDescriptor.AutoCloseOutputStream(pfd),
            AudioBuffer.DEFAULT_SAMPLE_RATE, AudioBuffer.DEFAULT_CHANNEL_COUNT);
  }

  /**
   * A simple audio recorder.
   *
   * @param outputStream The output stream of the recording.
   */
  public AudioRecorder(OutputStream outputStream) {
    this(outputStream, AudioBuffer.DEFAULT_SAMPLE_RATE, AudioBuffer.DEFAULT_CHANNEL_COUNT);
  }

  /**
   * A simple audio recorder, at a chosen rate.
   *
   * @param sampleRate the rate to record at, if the device can
   * @param channelCount 1 or 2
   */
  public AudioRecorder(OutputStream outputStream, @VideoStream.SampleRate int sampleRate,
      @VideoStream.ChannelCount int channelCount) {
    this(outputStream, sampleRate, channelCount, Options.DEFAULT);
  }

  /**
   * A recorder for a stream: it compresses into a format the receiver said it plays, applies the
   * speech processing asked for, and lowers its bit rate when the reader falls behind.
   */
  @RestrictTo(RestrictTo.Scope.LIBRARY)
  public AudioRecorder(OutputStream outputStream, @VideoStream.SampleRate int sampleRate,
      @VideoStream.ChannelCount int channelCount, @NonNull Options options) {
    this(outputStream, sampleRate, channelCount, options, MicrophoneSource.FACTORY);
  }

  AudioRecorder(OutputStream outputStream, int sampleRate, int channelCount,
      @NonNull Options options, @NonNull PcmSource.Factory sourceFactory) {
    this.mOutputStream = outputStream;
    this.mPreferredSampleRate = sampleRate;
    this.mPreferredChannelCount = channelCount;
    this.mOptions = options;
    this.mSourceFactory = sourceFactory;
  }

  /** @return True if actively recording. False otherwise. */
  public boolean isRecording() {
    return mIsAlive;
  }

  /** The format the audio is sent in, PCM until the recorder has chosen. */
  @AudioCodec.Codec
  int getCodec() {
    return mCodec;
  }

  /** The bit rate the audio is sent at, or 0 before recording starts. */
  int getBitRate() {
    return mBitRate;
  }

  /** Starts recording audio. */
  @RequiresPermission(Manifest.permission.RECORD_AUDIO)
  public synchronized void start() {
    if (mThread != null || mStopped.get()) {
      Log.w(TAG, "AudioRecorder is already running");
      return;
    }

    mIsAlive = true;
    mThread =
            new Thread() {
              @RequiresPermission(Manifest.permission.RECORD_AUDIO)
              @Override
              public void run() {
                setThreadPriority(THREAD_PRIORITY_AUDIO);

                PcmSource source = mSourceFactory.open(
                        mPreferredSampleRate, mPreferredChannelCount, mOptions);
                if (source == null) {
                  Log.w(TAG, "Failed to start recording audio");
                  mIsAlive = false;
                  return;
                }
                Log.d(TAG, "Started recording audio");

                try {
                  record(source);
                } catch (IOException e) {
                  Log.e(TAG, "Exception with recording audio stream", e);
                } finally {
                  stopInternal();
                  source.close();
                }
              }
            };
    mThread.start();
  }

  /** Picks the format and records in it until stopped. */
  @WorkerThread
  private void record(@NonNull PcmSource source) throws IOException {
    int sampleRate = source.getSampleRate();
    int channelCount = source.getChannelCount();
    int codec = AudioCodec.choose(
            PlaybackCapabilities.parse(mOptions.getPlaybackCapabilities()),
            sampleRate, channelCount, Build.VERSION.SDK_INT, AudioCodec.DEVICE);
    AudioEncoder encoder = null;
    if (codec != AudioCodec.PCM && Build.VERSION.SDK_INT >= AudioCodec.MEDIA_CODEC_SDK) {
      encoder = startEncoder(codec, sampleRate, channelCount,
              AudioCodec.defaultBitRate(codec, sampleRate, channelCount));
    }
    if (encoder != null && Build.VERSION.SDK_INT >= AudioCodec.MEDIA_CODEC_SDK) {
      recordCompressed(source, encoder);
    } else {
      recordPcm(source);
    }
  }

  /**
   * Records PCM under a version 1 header, exactly as every earlier release does, so a receiver
   * that never said what it plays reads it the way it always has.
   */
  @WorkerThread
  private void recordPcm(@NonNull PcmSource source) throws IOException {
    int sampleRate = source.getSampleRate();
    int channelCount = source.getChannelCount();
    mCodec = AudioCodec.PCM;
    mBitRate = AudioCodec.defaultBitRate(AudioCodec.PCM, sampleRate, channelCount);
    applyBacklogBudget(backlogBytesFor(sampleRate, channelCount, TARGET_BACKLOG_MILLIS));

    // Tell the other end what it is about to receive, so it does not have to guess the sample
    // rate from its own hardware and play the audio at the wrong pitch.
    new AudioStreamHeader(sampleRate, channelCount, AudioFormat.ENCODING_PCM_16BIT)
            .writeTo(mOutputStream);

    // While we're running, we'll read the bytes from the AudioRecord and write them to our output
    // stream.
    byte[] buffer = new byte[source.getBufferSize()];
    while (isRecording()) {
      int len = source.read(buffer, 0, buffer.length);
      if (len >= 0 && len <= buffer.length) {
        mOutputStream.write(buffer, 0, len);
        mOutputStream.flush();
      } else {
        Log.w(TAG, "Unexpected length returned: " + len);
      }
    }
  }

  /**
   * Records compressed frames under a version 2 header.
   *
   * <p>On a lossy stream the bit rate follows the reader: a controller watches the pipe and the
   * rate the reader takes it at, and the encoder is restarted at its rate when the two differ by
   * enough to be worth the hiccup.</p>
   */
  @RequiresApi(16)
  @WorkerThread
  private void recordCompressed(@NonNull PcmSource source, @NonNull AudioEncoder first)
          throws IOException {
    int sampleRate = source.getSampleRate();
    int channelCount = source.getChannelCount();
    int codec = first.getCodec();
    mCodec = codec;
    mBitRate = first.getBitRate();
    applyBacklogBudget(compressedBacklogBytesFor(mBitRate));

    BitRateController controller = BitRateController.forBitRate(first.getBitRate(),
            AudioCodec.minimumBitRate(codec, sampleRate, channelCount));
    DeliveryMeter meter = mOptions.getDeliveryMeter();
    if (meter != null) {
      meter.setListener(controller);
    }
    FrameWriter writer = new FrameWriter(sampleRate, channelCount, codec, controller);

    AudioEncoder encoder = first;
    long lastChangeMillis = SystemClock.elapsedRealtime();
    byte[] buffer = new byte[source.getBufferSize()];
    try {
      while (isRecording()) {
        int len = source.read(buffer, 0, buffer.length);
        if (len < 0 || len > buffer.length) {
          Log.w(TAG, "Unexpected length returned: " + len);
          continue;
        }
        if (!(mOutputStream instanceof LossyPipedOutputStream)) {
          encoder.encode(buffer, len, writer);
          continue;
        }
        // The queue is read before this chunk's frames join it: what is left of earlier ones is
        // what the reader did not take in time.
        LossyPipedOutputStream lossy = (LossyPipedOutputStream) mOutputStream;
        controller.onQueue(SystemClock.elapsedRealtime(), lossy.bufferedBytes(),
                lossy.getMaxBufferedBytes());
        encoder.encode(buffer, len, writer);
        long now = SystemClock.elapsedRealtime();
        controller.update(now);
        int target = controller.getBitRate();
        if (shouldChangeBitRate(encoder.getBitRate(), target, now - lastChangeMillis)) {
          AudioEncoder replacement = startEncoder(codec, sampleRate, channelCount, target);
          if (replacement != null) {
            encoder.release();
            encoder = replacement;
            mBitRate = target;
            applyBacklogBudget(compressedBacklogBytesFor(target));
          }
          lastChangeMillis = now;
        }
      }
    } finally {
      encoder.release();
      if (meter != null) {
        meter.setListener(null);
      }
    }
  }

  /**
   * Whether the encoder should be restarted at the controller's rate.
   *
   * <p>A restart costs a few milliseconds of audio, so small moves are not worth one. Cuts are
   * taken sooner than raises, as the controller itself takes them.</p>
   */
  static boolean shouldChangeBitRate(int current, int target, long sinceLastChangeMillis) {
    if (target < current * DECREASE_THRESHOLD) {
      return sinceLastChangeMillis >= DECREASE_INTERVAL_MILLIS;
    }
    if (target > current * INCREASE_THRESHOLD) {
      return sinceLastChangeMillis >= INCREASE_INTERVAL_MILLIS;
    }
    return false;
  }

  /** An encoder for the format, or null when the device will not give one. */
  @Nullable
  @RequiresApi(16)
  @WorkerThread
  private static AudioEncoder startEncoder(@AudioCodec.Codec int codec, int sampleRate,
      int channelCount, int bitRate) {
    try {
      return new AudioEncoder(codec, sampleRate, channelCount, bitRate);
    } catch (IOException | RuntimeException e) {
      Log.w(TAG, "Unable to start an audio encoder at " + bitRate + "bps", e);
      return null;
    }
  }

  /**
   * Writes the header, then each frame, to the stream.
   *
   * <p>The header waits for the encoder's own configuration, which it produces before its first
   * frame; an encoder that produces none gets the configuration the format's shape implies. A
   * restarted encoder's configuration is the same as the first one's and is not sent again.</p>
   */
  @RequiresApi(16)
  private final class FrameWriter implements AudioEncoder.Sink {
    /** Samples a second of the audio described. */
    private final int mSampleRate;

    /** Channels of the audio described. */
    private final int mChannelCount;

    /** The format described. */
    @AudioCodec.Codec private final int mFormat;

    /** Told about every frame the pipe drops. */
    private final BitRateController mController;

    /** Whether the header has gone out. */
    private boolean mHeaderWritten;

    FrameWriter(int sampleRate, int channelCount, @AudioCodec.Codec int format,
        @NonNull BitRateController controller) {
      mSampleRate = sampleRate;
      mChannelCount = channelCount;
      mFormat = format;
      mController = controller;
    }

    @Override
    public void onConfig(@NonNull byte[] config) throws IOException {
      if (!mHeaderWritten) {
        writeHeader(AudioCodec.normalizeConfig(mFormat, config));
      }
    }

    @Override
    public void onFrame(@NonNull byte[] frame) throws IOException {
      if (!mHeaderWritten) {
        writeHeader(null);
      }
      if (frame.length > AudioCodec.MAX_FRAME_BYTES) {
        Log.w(TAG, "Dropping an audio frame too long to send: " + frame.length);
        return;
      }
      if (!write(AudioCodec.frame(frame), true)) {
        mController.onDropped();
      }
    }

    private void writeHeader(@Nullable byte[] config) throws IOException {
      byte[] described = config != null
              ? config : AudioCodec.defaultConfig(mFormat, mSampleRate, mChannelCount);
      write(new AudioStreamHeader(mSampleRate, mChannelCount, mFormat, described).toByteArray(),
              false);
      mHeaderWritten = true;
    }
  }

  /**
   * Writes one record whole, or drops it whole on a lossy stream when it may be dropped.
   *
   * @return whether it was written
   */
  @WorkerThread
  private boolean write(@NonNull byte[] record, boolean droppable) throws IOException {
    if (mOutputStream instanceof LossyPipedOutputStream) {
      LossyPipedOutputStream lossy = (LossyPipedOutputStream) mOutputStream;
      // An audio frame stands alone, so losing one costs only itself.
      return droppable ? lossy.writeIndependentFrame(record) : lossy.writeFrame(record, false);
    }
    mOutputStream.write(record);
    mOutputStream.flush();
    return true;
  }

  /**
   * Converts a latency budget into a backlog of bytes at the given rate.
   *
   * <p>A fixed byte count means a different delay at every rate the recorder can settle on: the
   * 16KB VideoStream starts with is half a second at 16kHz mono, a second at 8kHz and a sixth of
   * one at 48kHz stereo. On a link slower than the stream the backlog sits at the threshold, so
   * the threshold is the delay.</p>
   */
  static int backlogBytesFor(int sampleRate, int channelCount, long latencyMillis) {
    return (int) ((long) sampleRate * channelCount * BYTES_PER_SAMPLE * latencyMillis / 1000);
  }

  /** The same latency budget for compressed audio, from its bit rate. */
  static int compressedBacklogBytesFor(int bitRate) {
    return (int) Math.max(MIN_COMPRESSED_BACKLOG_BYTES,
            (long) bitRate / 8 * TARGET_BACKLOG_MILLIS / 1000);
  }

  /** Applies the latency budget to a lossy sink, which cannot know the rate itself. */
  @WorkerThread
  private void applyBacklogBudget(int bytes) {
    if (mOutputStream instanceof LossyPipedOutputStream) {
      ((LossyPipedOutputStream) mOutputStream).setMaxBufferedBytes(bytes);
    }
  }

  private void stopInternal() {
    mIsAlive = false;
    if (mStopped.compareAndSet(false, true)) {
      try {
        mOutputStream.close();
      } catch (IOException e) {
        Log.e(TAG, "Failed to close audio output stream", e);
      }
    }
  }

  /** Stops recording audio. */
  public void stop() {
    stopInternal();
    Thread thread = mThread;
    if (thread == null || thread == Thread.currentThread()) {
      return;
    }
    try {
      thread.join(300);
    } catch (InterruptedException e) {
      Log.e(TAG, "Interrupted while joining AudioRecorder thread", e);
      Thread.currentThread().interrupt();
    }
  }

  /** What a stream asks of its audio beyond its rate and channels. */
  @RestrictTo(RestrictTo.Scope.LIBRARY)
  public static final class Options {
    /** Uncompressed, unprocessed, and not adapted: what the public constructors record. */
    static final Options DEFAULT = new Options(null, false, false, false, null);

    /** What the receiver said it plays, or null when it said nothing. */
    @Nullable private final byte[] mPlaybackCapabilities;

    /** Whether to suppress background noise, where the device can. */
    private final boolean mNoiseSuppression;

    /** Whether to cancel the device's own playback from the microphone, where it can. */
    private final boolean mEchoCancellation;

    /** Whether to even out the microphone's level, where the device can. */
    private final boolean mAutomaticGainControl;

    /** Measures the reader, for the bit rate to follow; null when nothing is measured. */
    @Nullable private final DeliveryMeter mDeliveryMeter;

    public Options(@Nullable byte[] playbackCapabilities, boolean noiseSuppression,
        boolean echoCancellation, boolean automaticGainControl,
        @Nullable DeliveryMeter deliveryMeter) {
      mPlaybackCapabilities = playbackCapabilities == null ? null : playbackCapabilities.clone();
      mNoiseSuppression = noiseSuppression;
      mEchoCancellation = echoCancellation;
      mAutomaticGainControl = automaticGainControl;
      mDeliveryMeter = deliveryMeter;
    }

    @Nullable
    byte[] getPlaybackCapabilities() {
      return mPlaybackCapabilities;
    }

    boolean isNoiseSuppressionEnabled() {
      return mNoiseSuppression;
    }

    boolean isEchoCancellationEnabled() {
      return mEchoCancellation;
    }

    boolean isAutomaticGainControlEnabled() {
      return mAutomaticGainControl;
    }

    @Nullable
    DeliveryMeter getDeliveryMeter() {
      return mDeliveryMeter;
    }
  }

  /** Where PCM comes from: the microphone, or a signal a test supplies. */
  interface PcmSource {
    int getSampleRate();

    int getChannelCount();

    /** The size to read in. */
    int getBufferSize();

    /** Reads PCM, blocking until some is available. */
    @WorkerThread
    int read(@NonNull byte[] buffer, int offset, int length);

    /** Stops reading and releases whatever was held. */
    @WorkerThread
    void close();

    /** Opens a source. */
    interface Factory {
      /** A started source, or null when there is none to be had. */
      @Nullable
      @RequiresPermission(Manifest.permission.RECORD_AUDIO)
      @WorkerThread
      PcmSource open(int preferredSampleRate, int preferredChannelCount,
          @NonNull Options options);
    }
  }

  /** The microphone, with whatever speech processing the stream asked for. */
  static final class MicrophoneSource implements PcmSource {
    /** Opens the microphone. */
    static final PcmSource.Factory FACTORY = new PcmSource.Factory() {
      @Nullable
      @RequiresPermission(Manifest.permission.RECORD_AUDIO)
      @Override
      public PcmSource open(int preferredSampleRate, int preferredChannelCount,
          @NonNull Options options) {
        return MicrophoneSource.open(preferredSampleRate, preferredChannelCount, options);
      }
    };

    /** The platform recorder. */
    private final AudioRecord mRecord;

    /** The rate and size it was opened with. */
    private final Buffer mBuffer;

    /** Processing attached to the recorder's session, released with it. */
    private final List<AudioEffect> mEffects;

    private MicrophoneSource(AudioRecord record, Buffer buffer, List<AudioEffect> effects) {
      mRecord = record;
      mBuffer = buffer;
      mEffects = effects;
    }

    @Nullable
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @WorkerThread
    static PcmSource open(int preferredSampleRate, int preferredChannelCount,
        @NonNull Options options) {
      Buffer buffer = new Buffer(preferredSampleRate, preferredChannelCount);
      AudioRecord record =
              new AudioRecord(
                      audioSourceFor(options),
                      buffer.getSampleRate(),
                      buffer.getChannelCount() >= 2
                              ? AudioFormat.CHANNEL_IN_STEREO
                              : AudioFormat.CHANNEL_IN_MONO,
                      AudioFormat.ENCODING_PCM_16BIT,
                      buffer.getSize());

      if (record.getState() != AudioRecord.STATE_INITIALIZED) {
        record.release();
        return null;
      }

      List<AudioEffect> effects = new ArrayList<>();
      if (Build.VERSION.SDK_INT >= 16) {
        attachEffects(record.getAudioSessionId(), options, effects);
      }
      record.startRecording();
      return new MicrophoneSource(record, buffer, effects);
    }

    /**
     * The microphone to open.
     *
     * <p>By default the one facing the camera, with camcorder tuning rather than the speech
     * processing that pumps on anything but a voice - the same reasoning as the recording paths.
     * A stream that asked for echo cancellation is a conversation, and the platform's canceller
     * only works on the voice call input: elsewhere it has no reference of what the speaker
     * played.</p>
     */
    static int audioSourceFor(@NonNull Options options) {
      return options.isEchoCancellationEnabled()
              ? MediaRecorder.AudioSource.VOICE_COMMUNICATION
              : MediaRecorder.AudioSource.CAMCORDER;
    }

    /** Attaches each effect asked for that the device has, and enables it. */
    @RequiresApi(16)
    private static void attachEffects(int sessionId, @NonNull Options options,
        @NonNull List<AudioEffect> effects) {
      try {
        if (options.isNoiseSuppressionEnabled() && NoiseSuppressor.isAvailable()) {
          enable(NoiseSuppressor.create(sessionId), effects);
        }
        if (options.isEchoCancellationEnabled() && AcousticEchoCanceler.isAvailable()) {
          enable(AcousticEchoCanceler.create(sessionId), effects);
        }
        if (options.isAutomaticGainControlEnabled() && AutomaticGainControl.isAvailable()) {
          enable(AutomaticGainControl.create(sessionId), effects);
        }
      } catch (RuntimeException e) {
        // An effect the device lists and then fails to create is the device's problem, and the
        // audio is still worth sending without it.
        Log.w(TAG, "Unable to apply speech processing", e);
      }
    }

    @RequiresApi(16)
    private static void enable(@Nullable AudioEffect effect, @NonNull List<AudioEffect> effects) {
      if (effect == null) {
        return;
      }
      effects.add(effect);
      effect.setEnabled(true);
    }

    @Override
    public int getSampleRate() {
      return mBuffer.getSampleRate();
    }

    @Override
    public int getChannelCount() {
      return mBuffer.getChannelCount();
    }

    @Override
    public int getBufferSize() {
      return mBuffer.getSize();
    }

    @Override
    public int read(@NonNull byte[] buffer, int offset, int length) {
      return mRecord.read(buffer, offset, length);
    }

    @Override
    public void close() {
      try {
        mRecord.stop();
      } catch (IllegalStateException e) {
        Log.e(TAG, "Failed to stop AudioRecord", e);
      }
      if (Build.VERSION.SDK_INT >= 16) {
        releaseEffects(mEffects);
      }
      mRecord.release();
    }

    @RequiresApi(16)
    private static void releaseEffects(@NonNull List<AudioEffect> effects) {
      for (AudioEffect effect : effects) {
        effect.release();
      }
      effects.clear();
    }
  }

  private static class Buffer extends AudioBuffer {
    Buffer(@VideoStream.SampleRate int sampleRate,
        @VideoStream.ChannelCount int channelCount) {
      super(sampleRate, channelCount);
    }

    @Override
    protected boolean validSize(int size) {
      return size != AudioRecord.ERROR && size != AudioRecord.ERROR_BAD_VALUE;
    }

    @Override
    protected int getMinBufferSize(int sampleRate, int channelCount) {
      return AudioRecord.getMinBufferSize(
              sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
    }
  }
}
