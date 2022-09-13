package com.xlythe.view.camera.stream;

import android.Manifest;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.IntRange;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;
import androidx.annotation.RestrictTo;

import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.SettableFuture;
import com.xlythe.view.camera.CameraView;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

import static android.media.MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED;
import static android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED;
import static android.media.MediaCodec.INFO_TRY_AGAIN_LATER;
import static android.os.Process.THREAD_PRIORITY_DISPLAY;
import static android.os.Process.THREAD_PRIORITY_VIDEO;
import static android.os.Process.setThreadPriority;

/**
 * When created, you must pass a {@link ParcelFileDescriptor}. Once {@link #start()} is called, the
 * file descriptor will be written to until {@link #stop()} is called.
 */
@RequiresApi(21)
@RestrictTo(RestrictTo.Scope.LIBRARY)
public class VideoRecorder {
  private static final String TAG = CameraView.class.getSimpleName();

  private static final String MIME_TYPE = "video/avc";    // H.264 Advanced Video Coding
  private static final int DEFAULT_BIT_RATE = 6000000;    // 6M bit/s
  private static final int DEFAULT_FRAME_RATE = 15;       // 15fps
  private static final int DEFAULT_IFRAME_INTERVAL = 10;  // 10 seconds between I-frames

  private static final int INFO_SUCCESS = 0;
  private static final int NO_TIMEOUT = -1;

  /** The stream to write to. */
  private final OutputStream mOutputStream;

  /** Draws on our surface. */
  private final Canvas mCanvas;

  /** The bit rate, in bits per second. */
  private int mBitRate = DEFAULT_BIT_RATE;

  /** The frame rate, in frames per second. */
  private int mFrameRate = DEFAULT_FRAME_RATE;

  /** The iframe interval, in bits per second. */
  private int mIFrameInterval = DEFAULT_IFRAME_INTERVAL;

  /**
   * If true, the background thread will continue to loop and record video. Once false, the thread
   * will shut down.
   */
  private volatile boolean mIsAlive;

  /** The background thread recording video for us. */
  private Thread mThread;

  /**
   * A simple video recorder.
   *
   * @param pfd The output stream of the recording.
   */
  public VideoRecorder(Canvas canvas, ParcelFileDescriptor pfd) {
    this.mCanvas = canvas;
    this.mOutputStream = new ParcelFileDescriptor.AutoCloseOutputStream(pfd);
  }

  /**
   * A simple video recorder.
   *
   * @param outputStream The output stream of the recording.
   */
  public VideoRecorder(Canvas canvas, OutputStream outputStream) {
    this.mCanvas = canvas;
    this.mOutputStream = outputStream;
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

  /** @return True if actively recording. False otherwise. */
  public boolean isRecording() {
    return mIsAlive;
  }

  /** Starts recording video. */
  @RequiresPermission(Manifest.permission.CAMERA)
  public void start() {
    if (isRecording()) {
      Log.w(TAG, "VideoRecorder is already running");
      return;
    }

    mIsAlive = true;
    mThread =
            new Thread() {
              @RequiresPermission(Manifest.permission.CAMERA)
              @Override
              public void run() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                  setThreadPriority(THREAD_PRIORITY_VIDEO);
                } else {
                  setThreadPriority(THREAD_PRIORITY_DISPLAY);
                }

                MediaCodecInfo codecInfo = selectCodec(MIME_TYPE);
                if (codecInfo == null) {
                  Log.e(TAG, "Unable to find an appropriate codec for " + MIME_TYPE);
                  return;
                }

                SettableFuture<CameraMetadata> requestedSizeFuture = SettableFuture.create();
                SettableFuture<Surface> providedSurface = SettableFuture.create();
                SurfaceProvider surfaceProvider = (width, height, orientation) -> {
                  requestedSizeFuture.set(new CameraMetadata(width, height, orientation));
                  try {
                    return providedSurface.get();
                  } catch (ExecutionException | InterruptedException e) {
                    throw new RuntimeException(e);
                  }
                };
                mCanvas.attachSurface(surfaceProvider);

                MediaCodec encoder = null;
                Surface surface = null;
                try {
                  CameraMetadata size = Objects.requireNonNull(requestedSizeFuture.get());
                  MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, size.getWidth(), size.getHeight());

                  // Failing to specify some of these can cause the MediaCodec configure() call to
                  // throw an unhelpful exception.
                  format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                  format.setInteger(MediaFormat.KEY_BIT_RATE, getBitRate());
                  format.setInteger(MediaFormat.KEY_FRAME_RATE, getFrameRate());
                  format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, getIFrameInterval());
                  if (Build.VERSION.SDK_INT >= 31) {
                    format.setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, /*true=*/1);
                  }

                  // Pass this info to the remote device.
                  write(size.getWidth(), size.getHeight(), size.getOrientation(), getBitRate(), getFrameRate(), getIFrameInterval());

                  encoder = MediaCodec.createByCodecName(codecInfo.getName());
                  encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                  surface = encoder.createInputSurface();
                  providedSurface.set(surface);
                  encoder.start();
                  Log.d(TAG, "Started recording video with dimensions " + size);

                  MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                  while (isRecording()) {
                    int statusOrIndex = encoder.dequeueOutputBuffer(info, NO_TIMEOUT);
                    int status = getStatus(statusOrIndex);
                    int index = getIndex(statusOrIndex);

                    switch (status) {
                      case INFO_SUCCESS:
                        ByteBuffer encodedData = encoder.getOutputBuffers()[index];
                        if (encodedData == null) {
                          throw new IOException("ByteBuffer for " + index + " was null");
                        }

                        // It's usually necessary to adjust the ByteBuffer values to match BufferInfo.
                        encodedData.position(info.offset);
                        encodedData.limit(info.offset + info.size);
                        byte[] data = new byte[info.size];
                        encodedData.get(data);
                        encodedData.position(info.offset);
                        write(data, info.presentationTimeUs, info.flags);
                        encoder.releaseOutputBuffer(index, false);
                        break;
                      case INFO_TRY_AGAIN_LATER:
                        Log.d(TAG, "Video not ready yet. Trying again later.");
                        Thread.sleep(100);
                        continue;
                      case INFO_OUTPUT_FORMAT_CHANGED:
                        Log.d(TAG, "Video encoder output format changed: " + encoder.getOutputFormat());
                        break;
                      case INFO_OUTPUT_BUFFERS_CHANGED:
                        Log.d(TAG, "Video encoder output buffers changed");
                        break;
                    }
                  }

                  encoder.signalEndOfInputStream();
                } catch (IOException | IllegalArgumentException | InterruptedException | ExecutionException e) {
                  Log.e(TAG, "Exception with recording video stream", e);
                } finally {
                  stopInternal();
                  if (encoder != null) {
                    encoder.stop();
                    encoder.release();
                  }
                  if (surface != null) {
                    mCanvas.detachSurface(surfaceProvider);
                    surface.release();
                  }
                }
              }
            };
    mThread.start();
  }

  private int getStatus(int statusOrIndex) {
    return Math.min(statusOrIndex, 0);
  }

  private int getIndex(int statusOrIndex) {
    return Math.max(statusOrIndex, 0);
  }

  private void write(int width, int height, int orientation, int bitRate, int frameRate, int iframeInterval) throws IOException {
    byte[] frame = new VideoFrame.Builder(VideoFrame.Type.HEADER)
            .width(width)
            .height(height)
            .orientation(orientation)
            .bitRate(bitRate)
            .frameRate(frameRate)
            .iframeInterval(iframeInterval)
            .build()
            .asBytes();

    mOutputStream.write(Ints.toByteArray(frame.length));
    mOutputStream.write(frame);
    mOutputStream.flush();
  }

  private void write(byte[] data, long presentationTimeUs, int flags) throws IOException {
    byte[] frame = new VideoFrame.Builder(VideoFrame.Type.DATA)
            .data(data)
            .presentationTimeUs(presentationTimeUs)
            .flags(flags)
            .build()
            .asBytes();

    mOutputStream.write(Ints.toByteArray(frame.length));
    mOutputStream.write(frame);
    mOutputStream.flush();
  }

  private void stopInternal() {
    mIsAlive = false;
    try {
      mOutputStream.close();
    } catch (IOException e) {
      Log.e(TAG, "Failed to close video output stream", e);
    }
  }

  /** Stops recording video. */
  public void stop() {
    stopInternal();
    try {
      mThread.join();
    } catch (InterruptedException e) {
      Log.e(TAG, "Interrupted while joining VideoRecorder thread", e);
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Returns the first codec capable of encoding the specified MIME type, or null if no
   * match was found.
   */
  @Nullable
  private static MediaCodecInfo selectCodec(String mimeType) {
    int numCodecs = MediaCodecList.getCodecCount();
    for (int i = 0; i < numCodecs; i++) {
      MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
      if (!codecInfo.isEncoder()) {
        continue;
      }

      String[] types = codecInfo.getSupportedTypes();
      for (String type : types) {
        if (type.equalsIgnoreCase(mimeType)) {
          return codecInfo;
        }
      }
    }
    return null;
  }

  public interface Canvas {
    void attachSurface(SurfaceProvider surfaceProvider);
    void detachSurface(SurfaceProvider surfaceProvider);
  }

  public interface SurfaceProvider {
    Surface getSurface(int width, int height, int orientation);
  }

  private static class CameraMetadata {
    final int width;
    final int height;
    final int orientation;

    CameraMetadata(int width, int height, int orientation) {
      this.width = width;
      this.height = height;
      this.orientation = orientation;
    }

    int getWidth() {
      return width;
    }

    int getHeight() {
      return height;
    }

    int getOrientation() {
      return orientation;
    }

    @Override
    public String toString() {
      return "CameraMetadata{" +
              "width=" + width +
              ", height=" + height +
              ", orientation=" + orientation +
              '}';
    }
  }
}
