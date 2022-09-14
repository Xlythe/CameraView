package com.xlythe.view.camera.stream;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import androidx.annotation.Nullable;

import com.xlythe.view.camera.CameraView;

import java.io.IOException;
import java.io.InputStream;

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

  /** The background thread playing audio for us. */
  private Thread mThread;

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
  public void start() {
    if (mThread != null) {
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
                AudioTrack audioTrack =
                        new AudioTrack(
                                AudioManager.STREAM_MUSIC,
                                buffer.mSampleRate,
                                AudioFormat.CHANNEL_OUT_MONO,
                                AudioFormat.ENCODING_PCM_16BIT,
                                buffer.mSize,
                                AudioTrack.MODE_STREAM);
                audioTrack.play();
                Log.d(TAG, "Started playing audio");

                int len;
                try {
                  while (isPlaying() && (len = mInputStream.read(buffer.mData)) > 0) {
                    audioTrack.write(buffer.mData, 0, len);
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
    try {
      mInputStream.close();
    } catch (IOException e) {
      Log.e(TAG, "Failed to close audio input stream", e);
    }
    Log.d(TAG, "Stopped playing audio");
  }

  /** Stops playing the stream. */
  public void stop() {
    if (mThread == null) {
      Log.w(TAG, "AudioPlayer not started");
      return;
    }

    stopInternal();
    try {
      mThread.join(300);
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
    protected int getMinBufferSize(int sampleRate) {
      return AudioTrack.getMinBufferSize(
              sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
    }
  }

  public interface StreamEndListener {
    void onStreamEnded();
  }
}
