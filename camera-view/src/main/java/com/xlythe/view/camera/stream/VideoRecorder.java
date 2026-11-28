package com.xlythe.view.camera.stream;

import android.Manifest;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;
import androidx.annotation.RestrictTo;
import androidx.annotation.WorkerThread;

import com.xlythe.view.camera.CameraView;

import static com.xlythe.view.camera.ICameraModule.DEBUG;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
@RequiresApi(18)
public class VideoRecorder {
  private static final String TAG = CameraView.class.getSimpleName();

  private static final String MIME_TYPE = "video/avc";    // H.264 Advanced Video Coding
  private static final int DEFAULT_BIT_RATE = 6000000;    // 6M bit/s
  private static final int DEFAULT_FRAME_RATE = 15;       // 15fps
  private static final int DEFAULT_IFRAME_INTERVAL = 10;  // 10 seconds between I-frames

  /** How far behind the reader may fall, as a duration of video, before frames are dropped. */
  static final long TARGET_BACKLOG_MILLIS = 250;
  /** Floor for the backlog budget, so a very low bit rate still leaves room for a frame. */
  private static final int MIN_BACKLOG_BYTES = 8 * 1024;
  /** Ceiling for the backlog budget, so a very high bit rate does not buffer without limit. */
  private static final int MAX_BACKLOG_BYTES = 512 * 1024;

  private static final int INFO_SUCCESS = 0;
  private static final int NO_TIMEOUT = -1;
  /** Maximum time allowed for the camera/encoder surface handshake. */
  private static final long SURFACE_TIMEOUT_SECONDS = 10;

  /** The first release that can change a running encoder's bit rate. */
  static final int RUNTIME_BIT_RATE_SDK = 19;
  /** The first release whose encoders can be told to drop frames from their input surface. */
  static final int MAX_FPS_SDK = 29;
  /** The least a congested stream lowers its frame rate to. */
  static final int MIN_FRAME_RATE = 5;
  /** How long the first session counts frames for, to learn the camera's own rate. */
  private static final long FRAME_RATE_MEASURE_MILLIS = 2000;

  /** The stream to write to. */
  private final OutputStream mOutputStream;

  /** The encoder that writes bytes into the OutputStream. */
  private final AtomicReference<MediaCodec> mEncoder = new AtomicReference<>();

  /** Draws on our surface. */
  private final Canvas mCanvas;

  /** The bit rate, in bits per second. */
  private int mBitRate = DEFAULT_BIT_RATE;

  /** The frame rate, in frames per second. */
  private int mFrameRate = DEFAULT_FRAME_RATE;

  /** The iframe interval, in bits per second. */
  private int mIFrameInterval = DEFAULT_IFRAME_INTERVAL;

  /** The largest frame to encode, or null to follow whatever the preview is using. */
  @Nullable private volatile Size mMaxSize;

  /** Whether the background thread should continue recording video. */
  private final AtomicBoolean mIsAlive = new AtomicBoolean();
  /** Ensures the output stream is closed exactly once. */
  private final AtomicBoolean mStopped = new AtomicBoolean();
  /** The background thread recording video for us. */
  private final AtomicReference<Thread> mThread = new AtomicReference<>();

  /** Frames discarded because the reader fell behind. */
  private final AtomicLong mDroppedFrames = new AtomicLong();
  /** Surface provider currently attached to the camera backend. */
  private final AtomicReference<SurfaceProvider> mSurfaceProvider = new AtomicReference<>();

  /**
   * Lowers the bit rate, then frames, then pixels, when the reader falls behind. Null for a
   * stream that cannot adapt: a lossless one, or one on a release that cannot change a running
   * encoder.
   */
  @Nullable private volatile BitRateController mController;
  /** Measures the reader, and the far end when it acknowledges, for the controller. */
  @Nullable private volatile DeliveryMeter mDeliveryMeter;
  /** The bit rate the running encoder was last given. Recorder thread only. */
  private int mAppliedBitRate;
  /** The controller level the running session was configured for. Recorder thread only. */
  private int mSessionLevel;
  /** The dropped-frame count the controller was last told about. Recorder thread only. */
  private long mLastDroppedFrames;
  /** The first session's frame size, which later sessions scale from. */
  private volatile int mBaseWidth;
  /** Likewise. */
  private volatile int mBaseHeight;
  /** When the first session's frame count began, or -1. Recorder thread only. */
  private long mMeasureStartMillis = -1;
  /** Frames counted since then. Recorder thread only. */
  private long mMeasuredFrames;
  /** The rate the camera was measured drawing at, or 0 before it has been. */
  private volatile float mMeasuredFrameRate;

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

  /**
   * Caps the resolution this stream encodes, independent of what the preview shows.
   *
   * <p>Set this when the stream leaves the device over a link that cannot carry the preview's
   * resolution. The backend picks the closest size its camera supports within the bound, so the
   * stream is usually smaller than asked and never larger. Null follows the preview.</p>
   */
  @RequiresApi(21)
  public void setMaxSize(@Nullable Size maxSize) {
    if (maxSize != null && (maxSize.getWidth() <= 0 || maxSize.getHeight() <= 0)) {
      throw new IllegalArgumentException("Stream dimensions must be positive: " + maxSize);
    }
    mMaxSize = maxSize;
  }

  /** The resolution cap for this stream, or null when it follows the preview. */
  @Nullable
  public Size getMaxSize() {
    return mMaxSize;
  }

  /**
   * Measures whoever reads this stream, so the bit rate can follow the link. Set before
   * {@link #start()}.
   */
  @RestrictTo(RestrictTo.Scope.LIBRARY)
  public void setDeliveryMeter(@Nullable DeliveryMeter deliveryMeter) {
    mDeliveryMeter = deliveryMeter;
  }

  /** The controller adapting this stream, or null when it does not adapt. */
  @Nullable
  BitRateController getController() {
    return mController;
  }

  /** @return True if actively recording. False otherwise. */
  public boolean isRecording() {
    return mIsAlive.get();
  }

  /** Starts recording video. */
  @RequiresPermission(Manifest.permission.CAMERA)
  public synchronized void start() {
    if (mThread.get() != null || mStopped.get()) {
      Log.w(TAG, "VideoRecorder is already running");
      return;
    }

    mIsAlive.set(true);
    applyBacklogBudget();
    mController = createController(getBitRate(), Build.VERSION.SDK_INT, mOutputStream);
    DeliveryMeter meter = mDeliveryMeter;
    if (meter != null && mController != null) {
      meter.setListener(mController);
    }
    Thread thread =
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
                  stopInternal();
                  return;
                }

                try {
                  boolean first = true;
                  while (isRecording()) {
                    if (!runSession(codecInfo, first)) {
                      break;
                    }
                    first = false;
                  }
                } finally {
                  DeliveryMeter meter = mDeliveryMeter;
                  if (meter != null) {
                    meter.setListener(null);
                  }
                  stopInternal();
                }
              }
            };
    mThread.set(thread);
    thread.start();
  }

  /**
   * Attaches an encoder to the camera and drains it, until the recording stops or the controller
   * moves to another level.
   *
   * <p>A level is a frame rate and a size, and neither can be changed on a running encoder fed
   * from a surface, so a new level is a new session: the encoder is released, the camera is
   * asked for a surface again, at the new size, and a new encoder starts at the new frame rate.
   * The stream itself carries on. The new encoder opens with its own configuration and a key
   * frame, which is all a decoder needs to follow a change of size, so nothing else is sent: a
   * second header would reach a player from before this as a frame of video.</p>
   *
   * @param first whether this is the stream's first session, which writes its header
   * @return whether to run another session
   */
  @RequiresPermission(Manifest.permission.CAMERA)
  @WorkerThread
  private boolean runSession(MediaCodecInfo codecInfo, boolean first) {
    BitRateController controller = mController;
    int level = controller == null ? 0 : controller.getLevelIndex();
    BitRateController.Level step = controller == null
            ? new BitRateController.Level(1, 1) : controller.getLevel();
    mSessionLevel = level;
    int frameRate = sessionFrameRate(step.getFrameRateScale());
    Size sessionMaxSize = Build.VERSION.SDK_INT >= 21 ? sessionMaxSize(step.getAreaScale()) : null;

    Handoff<CameraMetadata> requestedSizeFuture = new Handoff<>();
    Handoff<Surface> providedSurface = new Handoff<>();
    SurfaceProvider surfaceProvider = new SurfaceProvider() {
      @Override
      public Surface getSurface(int width, int height, int orientation, boolean flipped) {
        requestedSizeFuture.set(new CameraMetadata(width, height, orientation, flipped));
        try {
          return providedSurface.get(SURFACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException | TimeoutException e) {
          throw new RuntimeException(e);
        }
      }

      @Nullable
      @Override
      public Size getMaxSize() {
        return sessionMaxSize != null ? sessionMaxSize : VideoRecorder.this.getMaxSize();
      }
    };
    synchronized (VideoRecorder.this) {
      if (!isRecording()) {
        return false;
      }
      mSurfaceProvider.set(surfaceProvider);
      mCanvas.attachSurface(surfaceProvider);
    }

    Surface surface = null;
    boolean restart = false;
    try {
      CameraMetadata metadata = requestedSizeFuture.get(SURFACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      int bitRate = controller == null ? getBitRate() : controller.getBitRate();
      MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, metadata.getWidth(), metadata.getHeight());

      // Failing to specify some of these can cause the MediaCodec configure() call to
      // throw an unhelpful exception.
      format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
      format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
      format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
      format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, getIFrameInterval());
      if (Build.VERSION.SDK_INT >= MAX_FPS_SDK && step.getFrameRateScale() < 1) {
        // The frame rate above only informs rate control: an encoder fed from a surface takes
        // every frame the camera draws into it unless it is told to drop down to a rate.
        format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, frameRate);
      }
      if (Build.VERSION.SDK_INT >= 31) {
        format.setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, /*true=*/1);
      }

      if (first) {
        mBaseWidth = metadata.getWidth();
        mBaseHeight = metadata.getHeight();
        // Pass this info to the remote device.
        write(metadata.getWidth(),
                metadata.getHeight(),
                metadata.getOrientation(),
                metadata.isFlipped(),
                getBitRate(),
                getFrameRate(),
                getIFrameInterval());
      }

      MediaCodec encoder;
      // Held until start() returns, because stop() takes the same lock before it
      // closes the encoder. Before API 29, MediaCodec aborts the whole process when a
      // release() overtakes a start() that is still completing on another thread.
      synchronized (VideoRecorder.this) {
        if (!isRecording()) {
          return false;
        }
        encoder = MediaCodec.createByCodecName(codecInfo.getName());
        mEncoder.set(encoder);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        surface = encoder.createInputSurface();
        providedSurface.set(surface);
        encoder.start();
      }
      mAppliedBitRate = bitRate;
      if (controller != null) {
        applyBacklogBudget(bitRate);
      }
      Log.d(TAG, "Started recording video with dimensions " + metadata
              + " at " + bitRate + "bps and " + frameRate + "fps, level " + level);

      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      while (isRecording() && !restart) {
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
            observeQueue();
            write(data, info.presentationTimeUs, info.flags);
            encoder.releaseOutputBuffer(index, false);
            requestSyncFrameIfNeeded(encoder);
            if (first) {
              measureFrameRate(info.flags);
            }
            restart = adapt(encoder);
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

      if (!restart) {
        encoder.signalEndOfInputStream();
      }
      return restart && isRecording();
    } catch (IOException | IllegalArgumentException | IllegalStateException | InterruptedException | TimeoutException e) {
      Log.e(TAG, "Exception with recording video stream", e);
      return false;
    } finally {
      detachSurfaceProvider();
      if (restart) {
        closeEncoder();
      }
      if (surface != null) {
        surface.release();
      }
    }
  }

  /**
   * The controller for a stream, or null when it has nothing to act on.
   *
   * <p>Only a lossy stream has a backlog to watch: a lossless one blocks the encoder instead. And
   * the bit rate can only be changed on a running encoder from API 19.</p>
   */
  @Nullable
  static BitRateController createController(int bitRate, int sdk, OutputStream outputStream) {
    if (sdk < RUNTIME_BIT_RATE_SDK || !(outputStream instanceof LossyPipedOutputStream)) {
      return null;
    }
    return BitRateController.withLevels(bitRate, BitRateController.VIDEO_FLOOR_BIT_RATE,
            levelsFor(sdk));
  }

  /**
   * What the stream gives up below the bit rate floor, in order: half its frames, then half its
   * area, then half again.
   *
   * <p>Frames go first because a viewer forgives a lower frame rate sooner than a softer picture,
   * and because it is the cheaper restart: the camera is asked for the same size again. An
   * encoder can only be told to drop frames from API 29, and below that the frame rate step is
   * left out rather than taken with no effect. A size needs API 21 to be asked for at all.</p>
   */
  @NonNull
  static List<BitRateController.Level> levelsFor(int sdk) {
    List<BitRateController.Level> levels = new ArrayList<>();
    float frameRateScale = 1;
    if (sdk >= MAX_FPS_SDK) {
      frameRateScale = 0.5f;
      levels.add(new BitRateController.Level(frameRateScale, 1));
    }
    if (sdk >= 21) {
      levels.add(new BitRateController.Level(frameRateScale, 0.5f));
      levels.add(new BitRateController.Level(frameRateScale, 0.25f));
    }
    return levels;
  }

  /** Tells the controller what the reader has left unread, before the next frame joins it. */
  @WorkerThread
  private void observeQueue() {
    BitRateController controller = mController;
    if (controller == null || !(mOutputStream instanceof LossyPipedOutputStream)) {
      return;
    }
    LossyPipedOutputStream lossy = (LossyPipedOutputStream) mOutputStream;
    try {
      controller.onQueue(SystemClock.elapsedRealtime(), lossy.bufferedBytes(),
              lossy.getMaxBufferedBytes());
    } catch (IOException e) {
      // The reader has gone, which ends the stream on the next write.
    }
  }

  /**
   * Feeds the controller what the last frame showed about the reader, and acts on its answer.
   *
   * @return whether the controller moved to another level, which needs a new session
   */
  @WorkerThread
  private boolean adapt(MediaCodec encoder) {
    BitRateController controller = mController;
    if (controller == null) {
      return false;
    }
    long now = SystemClock.elapsedRealtime();
    long dropped = mDroppedFrames.get();
    if (dropped != mLastDroppedFrames) {
      mLastDroppedFrames = dropped;
      controller.onDropped();
    }
    if (!controller.update(now)) {
      return false;
    }
    int bitRate = controller.getBitRate();
    if (bitRate != mAppliedBitRate) {
      applyBitRate(encoder, bitRate);
    }
    return controller.getLevelIndex() != mSessionLevel;
  }

  /** Changes the running encoder's bit rate, and the backlog that is a quarter second of it. */
  @WorkerThread
  private void applyBitRate(MediaCodec encoder, int bitRate) {
    if (Build.VERSION.SDK_INT < RUNTIME_BIT_RATE_SDK) {
      return;
    }
    Bundle parameters = new Bundle();
    parameters.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitRate);
    try {
      encoder.setParameters(parameters);
      mAppliedBitRate = bitRate;
      applyBacklogBudget(bitRate);
      if (DEBUG) {
        Log.d(TAG, "Video bit rate now " + bitRate + "bps");
      }
    } catch (IllegalStateException error) {
      Log.w(TAG, "Unable to change the video bit rate", error);
    }
  }

  /** Counts the first session's frames, to learn the rate the camera actually draws at. */
  @WorkerThread
  private void measureFrameRate(int flags) {
    if ((flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
      return;
    }
    long now = SystemClock.elapsedRealtime();
    if (mMeasureStartMillis < 0) {
      mMeasureStartMillis = now;
      return;
    }
    mMeasuredFrames++;
    long elapsed = now - mMeasureStartMillis;
    if (elapsed >= FRAME_RATE_MEASURE_MILLIS) {
      mMeasuredFrameRate = mMeasuredFrames * 1000f / elapsed;
    }
  }

  /**
   * The frame rate for a session at a share of the full rate.
   *
   * <p>The full rate is what the camera was measured drawing at, rather than what was asked for:
   * an encoder fed from a surface takes whatever it is given, so the rate asked for is often not
   * the rate being sent, and halving that would be no reduction at all.</p>
   */
  int sessionFrameRate(float scale) {
    if (scale >= 1) {
      return getFrameRate();
    }
    float full = mMeasuredFrameRate > 0 ? mMeasuredFrameRate : getFrameRate();
    return Math.max(MIN_FRAME_RATE, Math.round(full * scale));
  }

  /**
   * The size cap for a session at a share of the first session's area, within the caller's own.
   */
  @Nullable
  @RequiresApi(21)
  Size sessionMaxSize(float areaScale) {
    Size cap = getMaxSize();
    if (areaScale >= 1 || mBaseWidth <= 0 || mBaseHeight <= 0) {
      return cap;
    }
    double side = Math.sqrt(areaScale);
    int width = Math.max(2, (int) (mBaseWidth * side) & ~1);
    int height = Math.max(2, (int) (mBaseHeight * side) & ~1);
    if (cap != null) {
      width = Math.min(width, cap.getWidth());
      height = Math.min(height, cap.getHeight());
    }
    return new Size(width, height);
  }

  private void detachSurfaceProvider() {
    SurfaceProvider surfaceProvider = mSurfaceProvider.getAndSet(null);
    if (surfaceProvider == null) return;
    try {
      mCanvas.detachSurface(surfaceProvider);
    } catch (RuntimeException error) {
      Log.w(TAG, "Unable to detach video stream surface", error);
    }
  }

  private synchronized void closeEncoder() {
    MediaCodec encoder = mEncoder.getAndSet(null);
    if (encoder != null) {
      try {
        encoder.stop();
      } catch (IllegalStateException e) {
        Log.e(TAG, "Exception with recording video stream", e);
      }
      try {
        encoder.release();
      } catch (IllegalStateException e) {
        Log.e(TAG, "Failed to release video encoder", e);
      }
    }
  }

  private int getStatus(int statusOrIndex) {
    return Math.min(statusOrIndex, 0);
  }

  private int getIndex(int statusOrIndex) {
    return Math.max(statusOrIndex, 0);
  }

  private void write(int width,
                     int height,
                     int orientation,
                     boolean flipped,
                     int bitRate,
                     int frameRate,
                     int iframeInterval) throws IOException {
    byte[] frame = new VideoFrame.Builder(VideoFrame.Type.HEADER)
            .width(width)
            .height(height)
            .orientation(orientation)
            .flipped(flipped)
            .bitRate(bitRate)
            .frameRate(frameRate)
            .iframeInterval(iframeInterval)
            .build()
            .asBytes();

    // The header describes every frame that follows, so it is never droppable.
    writeFrame(frame, false);
  }

  private void write(byte[] data, long presentationTimeUs, int flags) throws IOException {
    byte[] frame = new VideoFrame.Builder(VideoFrame.Type.DATA)
            .data(data)
            .presentationTimeUs(presentationTimeUs)
            .flags(flags)
            .build()
            .asBytes();

    writeFrame(frame, isDroppable(flags));
  }

  /**
   * Hands one frame to the output stream.
   *
   * <p>On a lossy stream the length prefix and the payload have to be discarded together, so they
   * go out as a single frame rather than as two writes: dropping only one of them would leave the
   * reader looking for a length in the middle of a payload, and the format carries no sentinel it
   * could resynchronize on.</p>
   */
  private void writeFrame(byte[] frame, boolean droppable) throws IOException {
    byte[] prefixed = ByteBuffer.allocate(Integer.BYTES + frame.length)
            .putInt(frame.length)
            .put(frame)
            .array();

    if (mOutputStream instanceof LossyPipedOutputStream) {
      LossyPipedOutputStream lossyOutputStream = (LossyPipedOutputStream) mOutputStream;
      if (!lossyOutputStream.writeFrame(prefixed, droppable)) {
        long dropped = mDroppedFrames.incrementAndGet();
        if (DEBUG) {
          Log.d(TAG, "Dropped a video frame to keep up with the reader. Total: " + dropped);
        }
      }
      return;
    }

    mOutputStream.write(prefixed);
    mOutputStream.flush();
  }

  /**
   * Whether the stream survives losing a frame.
   *
   * <p>H.264 sends a key frame and then deltas, each predicted from the frame before it, so every
   * frame until the next key frame depends on that one. Dropping a key frame therefore breaks the
   * picture for up to a whole I-frame interval, and dropping the codec-configuration frame that
   * carries the SPS/PPS leaves the decoder unable to start at all. Losing a delta costs the rest
   * of its group, which the lossy stream drops along with it.</p>
   */
  static boolean isDroppable(int flags) {
    return (flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) == 0
            && (flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
            && (flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0;
  }

  /** Number of frames dropped so far because the reader could not keep up. */
  public long getDroppedFrameCount() {
    return mDroppedFrames.get();
  }

  /**
   * Converts a latency budget into a backlog of bytes at the given bit rate.
   *
   * <p>A fixed byte count is the wrong unit for a live stream, because the latency it represents
   * depends entirely on the bit rate: 128 KiB is under a fifth of a second at 6 Mbps but four
   * seconds at 256 kbps, and four seconds behind is exactly what dropping frames is meant to
   * avoid. Clamped at both ends so a very low bit rate still leaves room for a frame and a very
   * high one does not buffer without limit.</p>
   */
  static int backlogBytesFor(int bitRate, long latencyMillis) {
    long bytes = (long) bitRate / 8 * latencyMillis / 1000;
    return (int) Math.max(MIN_BACKLOG_BYTES, Math.min(bytes, MAX_BACKLOG_BYTES));
  }

  /** Applies the latency budget to a lossy sink, which cannot know the bit rate itself. */
  private void applyBacklogBudget() {
    applyBacklogBudget(getBitRate());
  }

  /** Applies the latency budget at a bit rate, which changes as the stream adapts. */
  private void applyBacklogBudget(int bitRate) {
    if (!(mOutputStream instanceof LossyPipedOutputStream)) {
      return;
    }
    int bytes = backlogBytesFor(bitRate, TARGET_BACKLOG_MILLIS);
    ((LossyPipedOutputStream) mOutputStream).setMaxBufferedBytes(bytes);
    if (DEBUG) {
      Log.d(TAG, "Dropping frames past " + bytes + " bytes of backlog at " + bitRate + "bps");
    }
  }

  /**
   * Mints a new key frame once the lossy stream has had to discard a frame the reader needed.
   *
   * <p>Once the stream drops a delta it drops the rest of that group of pictures too, and when it
   * is hopelessly behind it throws reference frames away rather than queueing them, since only the
   * newest matters to a reader resuming playback. Either way the reader has nothing decodable
   * until the next key frame, up to a whole I-frame interval away and ten seconds by default.
   * Asking the encoder for one closes that gap, and means a long I-frame interval stays affordable
   * for the bandwidth it saves.</p>
   */
  private void requestSyncFrameIfNeeded(MediaCodec encoder) {
    if (Build.VERSION.SDK_INT < 19 || !(mOutputStream instanceof LossyPipedOutputStream)) {
      return;
    }
    try {
      if (!((LossyPipedOutputStream) mOutputStream).consumeResyncRequest()) {
        return;
      }
    } catch (IOException error) {
      return;
    }

    Bundle parameters = new Bundle();
    parameters.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
    try {
      encoder.setParameters(parameters);
      if (DEBUG) {
        Log.d(TAG, "Requested a key frame to resume a stream that fell too far behind");
      }
    } catch (IllegalStateException error) {
      Log.w(TAG, "Unable to request a key frame", error);
    }
  }

  private void stopInternal() {
    mIsAlive.set(false);
    if (mStopped.compareAndSet(false, true)) {
      try {
        mOutputStream.close();
      } catch (IOException e) {
        Log.e(TAG, "Failed to close video output stream", e);
      }
    }
    closeEncoder();
  }

  /** Stops recording video. */
  public synchronized void stop() {
    detachSurfaceProvider();
    stopInternal();
    Thread thread = mThread.get();
    if (thread == null || thread == Thread.currentThread()) {
      return;
    }
    try {
      thread.interrupt();
      thread.join(300);
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
    Surface getSurface(int width, int height, int orientation, boolean flipped);

    /**
     * The largest frame this stream wants, or null to follow the preview.
     *
     * <p>A backend asks before it picks the resolution it will render into, so a caller sending
     * the stream over a link that cannot carry the preview can ask for less. The backend chooses
     * the closest size its camera supports within this bound, so the result is usually smaller
     * than the value given and never larger.</p>
     */
    @Nullable
    default Size getMaxSize() {
      return null;
    }
  }

  private static class CameraMetadata {
    final int width;
    final int height;
    final int orientation;
    final boolean flipped;

    CameraMetadata(int width, int height, int orientation, boolean flipped) {
      this.width = width;
      this.height = height;
      this.orientation = orientation;
      this.flipped = flipped;
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

    boolean isFlipped() {
      return flipped;
    }

    @NonNull
    @Override
    public String toString() {
      return "CameraMetadata{" +
              "width=" + width +
              ", height=" + height +
              ", orientation=" + orientation +
              ", flipped=" + flipped +
              '}';
    }
  }
}
