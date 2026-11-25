package com.xlythe.view.camera.stream;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import androidx.annotation.Nullable;

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
 */
public class AudioPlayer {
  private static final String TAG = CameraView.class.getSimpleName();

  /** The audio stream we're reading from. */
  private final InputStream mInputStream;

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

  /**
   * A simple audio player.
   *
   * @param inputStream The input stream of the recording.
   */
  public AudioPlayer(InputStream inputStream) {
    this.mInputStream = inputStream;
  }

  public void setStreamEndListener(@Nullable StreamEndListener listener) {
    mStreamEndListener = listener;
  }

  /** @return True if currently playing. */
  public boolean isPlaying() {
    return mIsAlive;
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

                Buffer buffer = new Buffer();
                InputStream audio = mInputStream;
                int sampleRate = buffer.getSampleRate();
                int channels = AudioFormat.CHANNEL_OUT_MONO;
                try {
                  AudioStreamHeader.Read read = AudioStreamHeader.read(mInputStream);
                  audio = read.stream;
                  if (read.header != null) {
                    // The recorder said what it is sending, which beats guessing from this
                    // device's hardware: the two are not the same device.
                    sampleRate = read.header.getSampleRate();
                    channels = read.header.getChannelCount() > 1
                            ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
                  }
                } catch (IOException error) {
                  Log.e(TAG, "Failed to read the audio stream's description", error);
                }

                int size = AudioTrack.getMinBufferSize(
                        sampleRate, channels, AudioFormat.ENCODING_PCM_16BIT);
                if (size <= 0) {
                  size = buffer.getSize();
                }
                AudioTrack audioTrack =
                        new AudioTrack(
                                AudioManager.STREAM_VOICE_CALL,
                                sampleRate,
                                channels,
                                AudioFormat.ENCODING_PCM_16BIT,
                                size,
                                AudioTrack.MODE_STREAM);
                audioTrack.play();
                Log.d(TAG, "Started playing audio at " + sampleRate + "Hz");

                int len;
                try {
                  while (isPlaying() && (len = audio.read(buffer.data())) > 0) {
                    audioTrack.write(buffer.data(), 0, len);
                  }
                } catch (IOException e) {
                  Log.e(TAG, "Exception with playing audio stream", e);
                } finally {
                  stopInternal();
                  audioTrack.release();

                  StreamEndListener listener = mStreamEndListener;
                  if (listener != null) {
                    listener.onStreamEnded();
                  }
                }
              }
            };
    mThread.start();
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
