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

  /** The background thread recording audio for us. */
  private Thread mThread;

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
  public void start() {
    if (isRecording()) {
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
                                MediaRecorder.AudioSource.DEFAULT,
                                buffer.mSampleRate,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT,
                                buffer.mSize);

                if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                  Log.w(TAG, "Failed to start recording audio");
                  mIsAlive = false;
                  return;
                }

                record.startRecording();
                Log.d(TAG, "Started recording audio");

                // While we're running, we'll read the bytes from the AudioRecord and write them
                // to our output stream.
                try {
                  while (isRecording()) {
                    int len = record.read(buffer.mData, 0, buffer.mSize);
                    if (len >= 0 && len <= buffer.mSize) {
                      mOutputStream.write(buffer.mData, 0, len);
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
    try {
      mOutputStream.close();
    } catch (IOException e) {
      Log.e(TAG, "Failed to close audio output stream", e);
    }
  }

  /** Stops recording audio. */
  public void stop() {
    stopInternal();
    try {
      mThread.join(300);
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
