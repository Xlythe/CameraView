package com.xlythe.view.camera.stream;

import android.Manifest;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.RequiresPermission;
import androidx.annotation.WorkerThread;

import com.xlythe.view.camera.VideoStream;

import com.xlythe.view.camera.CameraView;

import java.io.IOException;
import java.io.OutputStream;
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

  /** 16-bit PCM, which is the only encoding the recorder writes. */
  private static final int BYTES_PER_SAMPLE = 2;

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

  /**
   * A simple audio recorder.
   *
   * @param pfd The output stream of the recording.
   */
  /** The rate asked for, which the buffer may not be able to honour. */
  private final int mPreferredSampleRate;

  /** The channel count asked for, likewise. */
  private final int mPreferredChannelCount;

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
    this.mOutputStream = outputStream;
    this.mPreferredSampleRate = sampleRate;
    this.mPreferredChannelCount = channelCount;
  }

  /** @return True if actively recording. False otherwise. */
  public boolean isRecording() {
    return mIsAlive;
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

                Buffer buffer = new Buffer(mPreferredSampleRate, mPreferredChannelCount);
                AudioRecord record =
                        new AudioRecord(
                                // The same reasoning as the recording paths: the microphone
                                // facing the camera, with camcorder tuning rather than the
                                // speech processing that pumps on anything but a voice.
                                MediaRecorder.AudioSource.CAMCORDER,
                                buffer.getSampleRate(),
                                buffer.getChannelCount() >= 2
                                        ? AudioFormat.CHANNEL_IN_STEREO
                                        : AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT,
                                buffer.getSize());

                if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                  Log.w(TAG, "Failed to start recording audio");
                  mIsAlive = false;
                  return;
                }

                record.startRecording();
                Log.d(TAG, "Started recording audio");
                applyBacklogBudget(buffer.getSampleRate(), buffer.getChannelCount());

                // Tell the other end what it is about to receive, so it does not have to guess
                // the sample rate from its own hardware and play the audio at the wrong pitch.
                try {
                  new AudioStreamHeader(buffer.getSampleRate(), buffer.getChannelCount(),
                          AudioFormat.ENCODING_PCM_16BIT).writeTo(mOutputStream);
                } catch (java.io.IOException error) {
                  Log.e(TAG, "Failed to describe the audio stream", error);
                  mIsAlive = false;
                  record.stop();
                  record.release();
                  return;
                }

                // While we're running, we'll read the bytes from the AudioRecord and write them
                // to our output stream.
                try {
                  while (isRecording()) {
                    int len = record.read(buffer.data(), 0, buffer.getSize());
                    if (len >= 0 && len <= buffer.getSize()) {
                      mOutputStream.write(buffer.data(), 0, len);
                      mOutputStream.flush();
                    } else {
                      Log.w(TAG, "Unexpected length returned: " + len);
                    }
                  }
                } catch (IOException e) {
                  Log.e(TAG, "Exception with recording audio stream", e);
                } finally {
                  stopInternal();
                  try {
                    record.stop();
                  } catch (IllegalStateException e) {
                    Log.e(TAG, "Failed to stop AudioRecord", e);
                  }
                  record.release();
                }
              }
            };
    mThread.start();
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

  /** Applies the latency budget to a lossy sink, which cannot know the rate itself. */
  @WorkerThread
  private void applyBacklogBudget(int sampleRate, int channelCount) {
    if (mOutputStream instanceof LossyPipedOutputStream) {
      ((LossyPipedOutputStream) mOutputStream).setMaxBufferedBytes(
              backlogBytesFor(sampleRate, channelCount, TARGET_BACKLOG_MILLIS));
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
