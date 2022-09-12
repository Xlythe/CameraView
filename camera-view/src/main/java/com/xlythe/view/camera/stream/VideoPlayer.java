package com.xlythe.view.camera.stream;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.IntRange;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;

import com.google.common.primitives.Ints;
import com.xlythe.view.camera.CameraView;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import static android.media.MediaCodec.INFO_TRY_AGAIN_LATER;
import static android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED;
import static android.media.MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED;
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

  private static final int INFO_SUCCESS = 0;
  private static final int TIMEOUT_USEC = 10000;
  private static final int NO_TIMEOUT = -1;

  /** The surface we're drawing to. */
  private final Surface mSurface;

  /** The video stream we're reading from. */
  private final InputStream mInputStream;

  /** A callback that fires once we know what the video parameters are. */
  @Nullable private OnMetadataAvailableListener mOnMetadataAvailableListener;

  /**
   * If true, the background thread will continue to loop and play video. Once false, the thread
   * will shut down.
   */
  private volatile boolean mIsAlive;

  /** The background thread playing video for us. */
  private Thread mThread;

  /**
   * A simple audio player.
   *
   * @param inputStream The input stream of the recording.
   */
  public VideoPlayer(Surface surface, InputStream inputStream) {
    this.mSurface = surface;
    this.mInputStream = inputStream;
  }

  public void setOnMetadataAvailableListener(OnMetadataAvailableListener l) {
    mOnMetadataAvailableListener = l;
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

                MediaCodec decoder = null;
                try {
                  VideoFrame header = readHeader();
                  if (mOnMetadataAvailableListener != null) {
                    mOnMetadataAvailableListener.onMetadataAvailable(header.getWidth(), header.getHeight(), header.getOrientation());
                  }

                  MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, header.getWidth(), header.getHeight());

                  // Failing to specify some of these can cause the MediaCodec configure() call to
                  // throw an unhelpful exception.
                  format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                  format.setInteger(MediaFormat.KEY_BIT_RATE, header.getBitRate());
                  format.setInteger(MediaFormat.KEY_FRAME_RATE, header.getFrameRate());
                  format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, header.getIFrameInterval());

                  // Create a MediaCodec for the decoder, just based on the MIME type.
                  // The various format details will be passed through the csd-0 meta-data later on.
                  decoder = MediaCodec.createDecoderByType(MIME_TYPE);
                  decoder.configure(format, mSurface, null, 0);
                  decoder.start();
                  Log.d(TAG, "Started playing video");

                  VideoFrame dataFrame = new VideoFrame.Builder().build();
                  while (isPlaying() && !isEndOfStream(dataFrame.getFlags())) {
                    // MediaCodec will consume data through #queueInputBuffer,
                    // while transforming/caching the data in the output buffer.
                    // MediaCodec only draws to the surface after calling
                    // #releaseOutputBuffer(..., true).
                    // Before we start writing more data into the input buffer,
                    // we must first make sure the output buffer is drained
                    // so that we have space to write.
                    drainOutputBuffer(decoder);

                    // Now that we have space, we can write the next few bytes.
                    int index = decoder.dequeueInputBuffer(NO_TIMEOUT);
                    if (index < 0) {
                      throw new IOException("No space left to decode");
                    }
                    ByteBuffer inputBuffer = decoder.getInputBuffers()[index];
                    inputBuffer.clear();

                    dataFrame = readFrame();

                    inputBuffer.put(dataFrame.getData());
                    decoder.queueInputBuffer(index, 0, dataFrame.getData().length, dataFrame.getPresentationTimeUs(), dataFrame.getFlags());
                  }
                } catch (IOException | IllegalArgumentException e) {
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

  private void drainOutputBuffer(MediaCodec decoder) throws IOException {
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    while (isPlaying()) {
      // This grabs the next frame off of the MediaCodec. If no frame is ready yet,
      // MediaCodec#INFO_TRY_AGAIN_LATER is returned. #TIMEOUT_USEC is purposefully
      // small, because this will otherwise block until data is ready.
      int statusOrIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_USEC);
      int status = getStatus(statusOrIndex);
      int index = getIndex(statusOrIndex);

      switch (status) {
        case INFO_SUCCESS:
          boolean doRender = info.size != 0;
          decoder.releaseOutputBuffer(index, doRender);
          break;
        case INFO_TRY_AGAIN_LATER:
          // Fully drained. We're done here.
          return;
        case INFO_OUTPUT_FORMAT_CHANGED:
          Log.d(TAG, "Video decoder output format changed: " + decoder.getOutputFormat());
          break;
        case INFO_OUTPUT_BUFFERS_CHANGED:
          Log.d(TAG, "Video decoder output buffers changed");
          break;
        default:
          throw new IOException("Unknown decoder status " + status);
      }
    }
  }

  private int getStatus(int statusOrIndex) {
    return Math.min(statusOrIndex, 0);
  }

  private int getIndex(int statusOrIndex) {
    return Math.max(statusOrIndex, 0);
  }

  private VideoFrame readHeader() throws IOException {
    VideoFrame frame = readFrame();
    if (frame.getType() != VideoFrame.Type.HEADER) {
      throw new IOException("Received frame of unexpected type " + frame.getType());
    }
    return frame;
  }

  private VideoFrame readFrame() throws IOException {
    int len = readInt();
    if (len < 0) {
      throw new IOException("Negative length");
    }

    byte[] buffer = new byte[len];
    readFully(buffer);
    return VideoFrame.fromBytes(buffer);
  }

  private int readInt() throws IOException {
    byte[] buffer = new byte[4];
    readFully(buffer);
    return Ints.fromByteArray(buffer);
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

  private boolean isEndOfStream(int flags) {
    return (flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
  }

  public interface OnMetadataAvailableListener {
    void onMetadataAvailable(int width, int height, int orientation);
  }
}
