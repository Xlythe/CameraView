package com.xlythe.view.camera.stream;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.WorkerThread;

import com.xlythe.view.camera.CameraView;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import static android.os.Process.THREAD_PRIORITY_AUDIO;
import static android.os.Process.setThreadPriority;

/**
 * A fire-once class. When created, you must pass a {@link InputStream}. Once {@link #start()} is
 * called, the input stream will be read from until either {@link #stop()} is called or the stream
 * ends.
 *
 * <p>The stream is live, so the player keeps up with it rather than playing everything it is
 * given: audio that waited unplayed through a whole second is discarded, down to a small cushion,
 * instead of being played late for the rest of the stream.</p>
 *
 * <p>The audio is PCM, or compressed in whatever format its header names: a recorder only
 * compresses for a receiver that said it can play the format, so a stream from any version of
 * the recorder plays here.</p>
 */
public class AudioPlayer {
  private static final String TAG = CameraView.class.getSimpleName();

  /** 16-bit PCM, which is what reaches the speaker whatever the stream was sent in. */
  private static final int BYTES_PER_SAMPLE = 2;

  /** The audio stream we're reading from. */
  private final InputStream mInputStream;

  /** Opens the speaker, or what stands in for it under test. */
  private final PcmOutput.Factory mOutputFactory;

  /** An optional listener that fires when the InputStream has ended. */
  @Nullable private volatile StreamEndListener mStreamEndListener;

  /**
   * If true, the background thread will continue to loop and play audio. Once false, the thread
   * will shut down.
   */
  private volatile boolean mIsAlive;
  /** Ensures the input stream is closed exactly once. */
  private final AtomicBoolean mStopped = new AtomicBoolean();

  /** The background thread playing audio for us. */
  private volatile Thread mThread;

  /** The format the stream's header named, once it has been read; PCM until then. */
  @AudioCodec.Codec private volatile int mCodec = AudioCodec.PCM;

  /**
   * A simple audio player.
   *
   * @param inputStream The input stream of the recording.
   */
  public AudioPlayer(InputStream inputStream) {
    this(inputStream, TrackOutput.FACTORY);
  }

  AudioPlayer(InputStream inputStream, @NonNull PcmOutput.Factory outputFactory) {
    this.mInputStream = inputStream;
    this.mOutputFactory = outputFactory;
  }

  public void setStreamEndListener(@Nullable StreamEndListener listener) {
    mStreamEndListener = listener;
  }

  /** @return True if currently playing. */
  public boolean isPlaying() {
    return mIsAlive;
  }

  /** The format the stream arrived in, PCM until its header has been read. */
  @AudioCodec.Codec
  int getCodec() {
    return mCodec;
  }

  /** Starts playing the stream. */
  public synchronized void start() {
    if (mThread != null || mStopped.get()) {
      Log.w(TAG, "AudioPlayer cannot be started more than once");
      return;
    }

    mIsAlive = true;
    mThread =
            new Thread() {
              @Override
              public void run() {
                setThreadPriority(THREAD_PRIORITY_AUDIO);

                InputStream audio = mInputStream;
                AudioStreamHeader header = null;
                try {
                  AudioStreamHeader.Read read = AudioStreamHeader.read(mInputStream);
                  audio = read.stream;
                  header = read.header;
                } catch (IOException error) {
                  Log.e(TAG, "Failed to read the audio stream's description", error);
                }

                try {
                  if (header != null && header.getCodec() != AudioCodec.PCM) {
                    mCodec = header.getCodec();
                    if (Build.VERSION.SDK_INT < AudioCodec.MEDIA_CODEC_SDK) {
                      throw new IOException("Cannot decode " + header + " on this release");
                    }
                    playCompressed(header, audio);
                  } else {
                    playPcm(header, audio);
                  }
                } catch (IOException e) {
                  Log.e(TAG, "Exception with playing audio stream", e);
                } finally {
                  stopInternal();

                  StreamEndListener listener = mStreamEndListener;
                  if (listener != null) {
                    listener.onStreamEnded();
                  }
                }
              }
            };
    mThread.start();
  }

  /**
   * Plays PCM, at the rate the header gave or this device's own guess when there was none.
   */
  @WorkerThread
  private void playPcm(@Nullable AudioStreamHeader header, @NonNull InputStream audio)
          throws IOException {
    Buffer buffer = new Buffer();
    int sampleRate = buffer.getSampleRate();
    int channelCount = 1;
    if (header != null) {
      // The recorder said what it is sending, which beats guessing from this device's hardware:
      // the two are not the same device.
      sampleRate = header.getSampleRate();
      channelCount = header.getChannelCount() > 1 ? 2 : 1;
    }

    PcmOutput output = mOutputFactory.open(sampleRate, channelCount, buffer.getSize());
    Log.d(TAG, "Started playing audio at " + sampleRate + "Hz");
    try {
      AudioCatchUp catchUp = new AudioCatchUp(sampleRate, channelCount);
      int frameBytes = catchUp.getFrameBytes();
      byte[] data = buffer.data();
      // AudioTrack writes whole frames and silently drops the rest, so a read that ends mid-frame
      // - which a socket is free to return - would shift every sample after it. The tail of such
      // a read waits at the front of the buffer for the rest.
      int carried = 0;
      while (isPlaying()) {
        int skip = catchUp.bytesToSkip(audio.available());
        if (skip > 0) {
          // A partial frame from before the skip is part of what is discarded.
          discard(audio, data, skip - carried);
          carried = 0;
        }
        int len = audio.read(data, carried, data.length - carried);
        if (len <= 0) {
          break;
        }
        int total = carried + len;
        int whole = total - total % frameBytes;
        output.write(data, 0, whole);
        catchUp.onPlayed(whole);
        carried = total - whole;
        System.arraycopy(data, whole, data, 0, carried);
      }
    } finally {
      output.release();
    }
  }

  /** Decodes and plays a compressed stream until it ends or the player is stopped. */
  @RequiresApi(16)
  @WorkerThread
  private void playCompressed(@NonNull AudioStreamHeader header, @NonNull InputStream audio)
          throws IOException {
    AudioDecoder decoder = new AudioDecoder(header.getCodec(), header.getSampleRate(),
            header.getChannelCount(), header.getConfig());
    DecodedOutput output = new DecodedOutput();
    Log.d(TAG, "Started playing " + header);
    try {
      while (isPlaying()) {
        long skip = output.compressedBytesToSkip(audio.available());
        while (skip > 0) {
          byte[] dropped = AudioCodec.readFrame(audio);
          if (dropped == null) {
            return;
          }
          skip -= dropped.length + 2;
        }
        byte[] frame = AudioCodec.readFrame(audio);
        if (frame == null) {
          break;
        }
        output.onCompressed(frame.length + 2);
        decoder.decode(frame, output);
      }
    } finally {
      decoder.release();
      output.release();
    }
  }

  /**
   * Plays what the decoder produces, and keeps compressed playback near live.
   *
   * <p>The backlog is measured in compressed bytes, which mean nothing to {@link AudioCatchUp}, so
   * it is scaled by how much PCM each compressed byte has decoded to so far. What it says to skip
   * is skipped as whole compressed frames, before they are decoded.</p>
   */
  @RequiresApi(16)
  private final class DecodedOutput implements AudioDecoder.Sink {
    /** The speaker, once the decoder has said what it produces. */
    @Nullable private PcmOutput mOutput;

    /** Watches the backlog, at the decoded rate. */
    @Nullable private AudioCatchUp mCatchUp;

    /** The shape the speaker was opened for. */
    private int mSampleRate;

    /** Likewise. */
    private int mChannelCount;

    /** Compressed bytes read off the wire, frame lengths included. */
    private long mCompressedBytes;

    /** PCM bytes they decoded to. */
    private long mPcmBytes;

    /** PCM bytes of a partial frame, waiting at the front of {@link #mCarry}. */
    private int mCarried;

    /** Holds the tail of a decoded buffer that ended mid-frame. */
    private byte[] mCarry = new byte[0];

    void onCompressed(int bytes) {
      mCompressedBytes += bytes;
    }

    /** How many compressed bytes to discard before the next frame. */
    long compressedBytesToSkip(int backlogBytes) {
      AudioCatchUp catchUp = mCatchUp;
      if (catchUp == null || mCompressedBytes == 0 || mPcmBytes == 0) {
        return 0;
      }
      double pcmPerCompressed = (double) mPcmBytes / mCompressedBytes;
      int skip = catchUp.bytesToSkip((int) Math.min(Integer.MAX_VALUE,
              backlogBytes * pcmPerCompressed));
      return (long) (skip / pcmPerCompressed);
    }

    @Override
    public void onFormat(int sampleRate, int channelCount) {
      int channels = channelCount > 1 ? 2 : 1;
      if (mOutput != null && sampleRate == mSampleRate && channels == mChannelCount) {
        return;
      }
      release();
      mSampleRate = sampleRate;
      mChannelCount = channels;
      mOutput = mOutputFactory.open(sampleRate, channels, 0);
      mCatchUp = new AudioCatchUp(sampleRate, channels);
      mCarried = 0;
    }

    @Override
    public void onPcm(@NonNull byte[] pcm, int length) {
      PcmOutput output = mOutput;
      AudioCatchUp catchUp = mCatchUp;
      if (output == null || catchUp == null) {
        return;
      }
      mPcmBytes += length;
      int frameBytes = catchUp.getFrameBytes();
      if (mCarry.length < mCarried + length) {
        byte[] grown = new byte[mCarried + length];
        System.arraycopy(mCarry, 0, grown, 0, mCarried);
        mCarry = grown;
      }
      System.arraycopy(pcm, 0, mCarry, mCarried, length);
      int total = mCarried + length;
      int whole = total - total % frameBytes;
      output.write(mCarry, 0, whole);
      catchUp.onPlayed(whole);
      mCarried = total - whole;
      System.arraycopy(mCarry, whole, mCarry, 0, mCarried);
    }

    void release() {
      if (mOutput != null) {
        mOutput.release();
        mOutput = null;
      }
    }
  }

  /**
   * Reads and throws away bytes. InputStream#skip would seek, which a pipe or a socket refuses.
   */
  private static void discard(InputStream inputStream, byte[] scratch, int bytes)
          throws IOException {
    while (bytes > 0) {
      int read = inputStream.read(scratch, 0, Math.min(scratch.length, bytes));
      if (read < 0) {
        return;
      }
      bytes -= read;
    }
  }

  private void stopInternal() {
    mIsAlive = false;
    if (mStopped.compareAndSet(false, true)) {
      try {
        mInputStream.close();
      } catch (IOException e) {
        Log.e(TAG, "Failed to close audio input stream", e);
      }
      Log.d(TAG, "Stopped playing audio");
    }
  }

  /** Stops playing the stream. */
  public void stop() {
    stopInternal();
    Thread thread = mThread;
    if (thread == null || thread == Thread.currentThread()) {
      return;
    }
    try {
      thread.join(300);
    } catch (InterruptedException e) {
      Log.e(TAG, "Interrupted while joining AudioPlayer thread", e);
      Thread.currentThread().interrupt();
    }
  }

  /** Where PCM goes: the speaker, or a recording a test inspects. */
  interface PcmOutput {
    /** Writes whole frames of 16-bit PCM. */
    @WorkerThread
    void write(@NonNull byte[] data, int offset, int length);

    /** Stops and releases whatever was held. */
    @WorkerThread
    void release();

    /** Opens an output. */
    interface Factory {
      /**
       * An output ready to be written to.
       *
       * @param fallbackBytes the buffer to use if the device cannot say what it needs; 0 for
       *     the device's own choice
       */
      @NonNull
      @WorkerThread
      PcmOutput open(int sampleRate, int channelCount, int fallbackBytes);
    }
  }

  /** The speaker, through an AudioTrack on the voice call stream. */
  static final class TrackOutput implements PcmOutput {
    /** Opens the speaker. */
    static final PcmOutput.Factory FACTORY = TrackOutput::new;

    /** The platform track. */
    private final AudioTrack mTrack;

    TrackOutput(int sampleRate, int channelCount, int fallbackBytes) {
      int channels = channelCount > 1
              ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
      int size = AudioTrack.getMinBufferSize(
              sampleRate, channels, AudioFormat.ENCODING_PCM_16BIT);
      if (size <= 0) {
        size = fallbackBytes > 0 ? fallbackBytes : sampleRate * channelCount * BYTES_PER_SAMPLE / 10;
      }
      mTrack = new AudioTrack(
              AudioManager.STREAM_VOICE_CALL,
              sampleRate,
              channels,
              AudioFormat.ENCODING_PCM_16BIT,
              size,
              AudioTrack.MODE_STREAM);
      mTrack.play();
    }

    @Override
    public void write(@NonNull byte[] data, int offset, int length) {
      mTrack.write(data, offset, length);
    }

    @Override
    public void release() {
      mTrack.release();
    }
  }

  private static class Buffer extends AudioBuffer {
    @Override
    protected boolean validSize(int size) {
      return size != AudioTrack.ERROR && size != AudioTrack.ERROR_BAD_VALUE;
    }

    @Override
    protected int getMinBufferSize(int sampleRate, int channelCount) {
      return AudioTrack.getMinBufferSize(
              sampleRate,
              channelCount >= 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO,
              AudioFormat.ENCODING_PCM_16BIT);
    }
  }

  public interface StreamEndListener {
    void onStreamEnded();
  }
}
