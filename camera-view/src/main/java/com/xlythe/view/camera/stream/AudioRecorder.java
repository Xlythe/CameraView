package com.xlythe.view.camera.stream;

import android.Manifest;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.RequiresPermission;

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
  public AudioRecorder(ParcelFileDescriptor pfd) {
    mOutputStream = new ParcelFileDescriptor.AutoCloseOutputStream(pfd);
  }

  /**
   * A simple audio recorder.
   *
   * @param outputStream The output stream of the recording.
   */
  public AudioRecorder(OutputStream outputStream) {
    this.mOutputStream = outputStream;
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

                Buffer buffer = new Buffer();
                AudioRecord record =
                        new AudioRecord(
                                // The same reasoning as the recording paths: the microphone
                                // facing the camera, with camcorder tuning rather than the
                                // speech processing that pumps on anything but a voice.
                                MediaRecorder.AudioSource.CAMCORDER,
                                buffer.getSampleRate(),
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT,
                                buffer.getSize());

                if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                  Log.w(TAG, "Failed to start recording audio");
                  mIsAlive = false;
                  return;
                }

                record.startRecording();
                Log.d(TAG, "Started recording audio");

                // Tell the other end what it is about to receive, so it does not have to guess
                // the sample rate from its own hardware and play the audio at the wrong pitch.
                try {
                  new AudioStreamHeader(buffer.getSampleRate(), 1 /* mono */,
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
    @Override
    protected boolean validSize(int size) {
      return size != AudioRecord.ERROR && size != AudioRecord.ERROR_BAD_VALUE;
    }

    @Override
    protected int getMinBufferSize(int sampleRate) {
      return AudioRecord.getMinBufferSize(
              sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
    }
  }
}
