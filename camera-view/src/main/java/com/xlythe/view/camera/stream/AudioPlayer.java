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
 *
 * <p>The stream is live, so the player keeps up with it rather than playing everything it is
 * given: audio that waited unplayed through a whole second is discarded, down to a small cushion,
 * instead of being played late for the rest of the stream.</p>
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
                int channelCount = 1;
                try {
                  AudioStreamHeader.Read read = AudioStreamHeader.read(mInputStream);
                  audio = read.stream;
                  if (read.header != null) {
                    // The recorder said what it is sending, which beats guessing from this
                    // device's hardware: the two are not the same device.
                    sampleRate = read.header.getSampleRate();
                    channelCount = read.header.getChannelCount() > 1 ? 2 : 1;
                  }
                } catch (IOException error) {
                  Log.e(TAG, "Failed to read the audio stream's description", error);
                }

                int channels = channelCount > 1
                        ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
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

                AudioCatchUp catchUp = new AudioCatchUp(sampleRate, channelCount);
                int frameBytes = catchUp.getFrameBytes();
                byte[] data = buffer.data();
                // AudioTrack writes whole frames and silently drops the rest, so a read that ends
                // mid-frame - which a socket is free to return - would shift every sample after
                // it. The tail of such a read waits at the front of the buffer for the rest.
                int carried = 0;
                try {
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
                    audioTrack.write(data, 0, whole);
                    catchUp.onPlayed(whole);
                    carried = total - whole;
                    System.arraycopy(data, whole, data, 0, carried);
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
