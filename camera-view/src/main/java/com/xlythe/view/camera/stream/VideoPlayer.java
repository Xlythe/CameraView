package com.xlythe.view.camera.stream;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.IntRange;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;

import com.xlythe.view.camera.CameraView;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import static android.os.Process.THREAD_PRIORITY_DISPLAY;
import static android.os.Process.THREAD_PRIORITY_VIDEO;
import static android.os.Process.setThreadPriority;

/**
 * A fire-once class. When created, you must pass a {@link InputStream}. Once {@link #start()} is
 * called, the input stream will be read from until either {@link #stop()} is called or the stream
 * ends.
 */
@RequiresApi(21)
@RestrictTo(RestrictTo.Scope.LIBRARY)
public class VideoPlayer {
  private static final String TAG = CameraView.class.getSimpleName();

  private static final String MIME_TYPE = "video/avc";    // H.264 Advanced Video Coding
  private static final int DEFAULT_BIT_RATE = 6000000;    // 6M bit/s
  private static final int DEFAULT_FRAME_RATE = 15;       // 15fps
  private static final int DEFAULT_IFRAME_INTERVAL = 10;  // 10 seconds between I-frames

  /** The surface we're drawing to. */
  private final Surface mSurface;

  /** The video stream we're reading from. */
  private final InputStream mInputStream;

  /**
   * If true, the background thread will continue to loop and play video. Once false, the thread
   * will shut down.
   */
  private volatile boolean mIsAlive;

  /** The background thread playing video for us. */
  private Thread mThread;

  /** The size of a frame, in pixels. */
  private Size mSize = new Size(0, 0);

  /** The bit rate, in bits per second. */
  private int mBitRate = DEFAULT_BIT_RATE;

  /** The frame rate, in frames per second. */
  private int mFrameRate = DEFAULT_FRAME_RATE;

  /** The iframe interval, in bits per second. */
  private int mIFrameInterval = DEFAULT_IFRAME_INTERVAL;

  /**
   * A simple audio player.
   *
   * @param inputStream The input stream of the recording.
   */
  public VideoPlayer(Surface surface, InputStream inputStream) {
    this.mSurface = surface;
    this.mInputStream = inputStream;
  }

  /** Sets the desired frame size and bit rate. */
  public void setSize(@IntRange(from = 0) int width, @IntRange(from = 0) int height) {
    setSize(new Size(width, height));
  }

  /** Sets the desired frame size and bit rate. */
  public void setSize(Size size) {
    if ((size.getWidth() % 16) != 0 || (size.getHeight() % 16) != 0) {
      Log.w(TAG, "WARNING: width or height not multiple of 16");
    }

    mSize = size;
  }

  public int getWidth() {
    return mSize.getWidth();
  }

  public int getHeight() {
    return mSize.getHeight();
  }

  /** Sets the desired bit rate. */
  public void setBitRate(@IntRange(from = 0) int bitRate) {
    mBitRate = bitRate;
  }

  /** Returns the desired bit rate. */
  public int getBitRate() {
    return mBitRate;
  }

  /** Sets the frame rate. */
  public void setFrameRate(int frameRate) {
    mFrameRate = frameRate;
  }

  /** Returns the frame rate. */
  public int getFrameRate() {
    return mFrameRate;
  }

  /** Sets the iframe interval. */
  public void setIFrameInterval(int iframeInterval) {
    mIFrameInterval = iframeInterval;
  }

  /** Returns the iframe interval. */
  public int getIFrameInterval() {
    return mIFrameInterval;
  }

  /** @return True if currently playing. */
  public boolean isPlaying() {
    return mIsAlive;
  }

  /** Starts playing the stream. */
  public void start() {
    mIsAlive = true;
    mThread =
            new Thread() {
              @Override
              public void run() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                  setThreadPriority(THREAD_PRIORITY_VIDEO);
                } else {
                  setThreadPriority(THREAD_PRIORITY_DISPLAY);
                }

                MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, getWidth(), getHeight());

                // Failing to specify some of these can cause the MediaCodec configure() call to
                // throw an unhelpful exception.
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                format.setInteger(MediaFormat.KEY_BIT_RATE, getBitRate());
                format.setInteger(MediaFormat.KEY_FRAME_RATE, getFrameRate());
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, getIFrameInterval());
                Log.d(TAG, "VideoPlayer with bit rate " + getBitRate() + ", frame rate " + getFrameRate() + ", and iframe interval " + getIFrameInterval() + " started");

                MediaCodec decoder = null;
                try {
                  // Create a MediaCodec for the decoder, just based on the MIME type.
                  // The various format details will be passed through the csd-0 meta-data later on.
                  decoder = MediaCodec.createDecoderByType(MIME_TYPE);
                  decoder.configure(format, mSurface, null, 0);
                  decoder.start();
                  Log.d(TAG, "Started playing video");

                  MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                  while (isPlaying() && !isEndOfStream(info)) {
                    Log.d(TAG, "De-queuing next buffer");
                    int statusOrIndex = decoder.dequeueOutputBuffer(info, -1);
                    Log.d(TAG, "VideoPlayer got status " + statusOrIndex);
                    if (statusOrIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                      Thread.sleep(100);
                      continue;
                    } else if (statusOrIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                      Log.d(TAG, "Video decoder output format changed: " + decoder.getOutputFormat());
                    } else if (statusOrIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                      Log.d(TAG, "Video decoder output buffers changed");
                    } else if (statusOrIndex < 0) {
                      throw new IOException("Unknown decoder status " + statusOrIndex);
                    } else {  // index >= 0
                      int index = decoder.dequeueInputBuffer(-1);
                      ByteBuffer inputBuffer = decoder.getInputBuffers()[index];
                      inputBuffer.clear();
                      byte[] data = new byte[info.size];
                      readFully(data);
                      inputBuffer.put(data);
                      decoder.queueInputBuffer(index, 0, info.size, info.presentationTimeUs, info.flags);

                      boolean doRender = info.size != 0;
                      decoder.releaseOutputBuffer(statusOrIndex, doRender);
                    }
                  }
                } catch (IOException | IllegalArgumentException | InterruptedException e) {
                  Log.e(TAG, "Exception with playing video stream", e);
                } finally {
                  stopInternal();
                  if (decoder != null) {
                    decoder.stop();
                    decoder.release();
                  }
                  onFinish();
                }
              }
            };
    mThread.start();
  }

  private void readFully(byte[] buffer) throws IOException {
    int bytesRead = 0;
    while (bytesRead != buffer.length) {
      bytesRead += mInputStream.read(buffer, bytesRead, buffer.length - bytesRead);
    }
  }

  private void stopInternal() {
    mIsAlive = false;
    try {
      mInputStream.close();
    } catch (IOException e) {
      Log.e(TAG, "Failed to close video input stream", e);
    }
    mSurface.release();
    Log.d(TAG, "Stopped playing video");
  }

  /** Stops playing the stream. */
  public void stop() {
    stopInternal();
    try {
      mThread.join();
    } catch (InterruptedException e) {
      Log.e(TAG, "Interrupted while joining VideoPlayer thread", e);
      Thread.currentThread().interrupt();
    }
  }

  /** The stream has now ended. */
  protected void onFinish() {}

  private boolean isEndOfStream(MediaCodec.BufferInfo info) {
    return (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
  }
}
