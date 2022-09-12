package com.xlythe.view.camera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;

import com.xlythe.view.camera.stream.AudioRecorder;
import com.xlythe.view.camera.stream.LossyPipedOutputStream;
import com.xlythe.view.camera.stream.VideoRecorder;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;

@RequiresApi(21)
public class VideoStream implements Closeable {
  /** Determines if this is encoding or decoding a video stream. */
  private final InputType mInputType;

  // ---------- From CameraModule ----------
  /** Encodes an audio stream. Non-null for audio streams of type {@link InputType.CAMERA_MODULE}. */
  @Nullable private final AudioRecorder mAudioRecorder;
  /** Encodes a video stream. Non-null for video streams of type {@link InputType.CAMERA_MODULE}. */
  @Nullable private final VideoRecorder mVideoRecorder;

  // ---------- From byte stream ----------
  private final Size mSize;
  private final int mBitRate;
  private final int mFrameRate;
  private final int mIFrameInterval;

  // ---------- Output ----------
  /** Exposes a handle to read the encoded audio bytes from the stream. Non-null for audio streams. */
  @Nullable private final InputStream mAudioInputStream;
  /** Exposes a handle to read the encoded video bytes from the stream. Non-null for video streams. */
  @Nullable private final InputStream mVideoInputStream;

  @RequiresPermission(allOf = {
          Manifest.permission.CAMERA,
          Manifest.permission.RECORD_AUDIO
  })
  private VideoStream(ICameraModule cameraModule, Params params) {
    mInputType = InputType.CAMERA_MODULE;

    if (params.isAudioEnabled()) {
      AudioRecorder audioRecorder;
      PipedInputStream audioInputStream;
      try {
        audioInputStream = new PipedInputStream();
        audioRecorder = new AudioRecorder(new LossyPipedOutputStream(audioInputStream));
        audioRecorder.start();
      } catch (IOException e) {
        audioRecorder = null;
        audioInputStream = null;
      }

      mAudioRecorder = audioRecorder;
      mAudioInputStream = audioInputStream;
    } else {
      mAudioRecorder = null;
      mAudioInputStream = null;
    }

    if (params.isVideoEnabled()) {
      VideoRecorder videoRecorder;
      PipedInputStream videoInputStream;
      try {
        videoInputStream = new PipedInputStream();
        videoRecorder = new VideoRecorder(cameraModule.getCanvas(), new LossyPipedOutputStream(videoInputStream));
        videoRecorder.start();
      } catch (IOException e) {
        videoRecorder = null;
        videoInputStream = null;
      }

      mVideoRecorder = videoRecorder;
      mVideoInputStream = videoInputStream;
    } else {
      mVideoRecorder = null;
      mVideoInputStream = null;
    }

    if (mVideoRecorder != null) {
      mSize = new Size(mVideoRecorder.getWidth(), mVideoRecorder.getHeight());
      mBitRate = mVideoRecorder.getBitRate();
      mFrameRate = mVideoRecorder.getFrameRate();
      mIFrameInterval = mVideoRecorder.getIFrameInterval();
    } else {
      mSize = new Size(0, 0);
      mBitRate = 0;
      mFrameRate = 0;
      mIFrameInterval = 0;
    }
  }

  private VideoStream(@Nullable InputStream audioStream,
                      @Nullable InputStream videoStream,
                      Size size,
                      int bitRate,
                      int frameRate,
                      int iFrameInterval) {
    mInputType = InputType.INPUT_STREAM;

    mAudioRecorder = null;
    mVideoRecorder = null;

    mAudioInputStream = audioStream;
    mVideoInputStream = videoStream;

    mSize = size;
    mBitRate = bitRate;
    mFrameRate = frameRate;
    mIFrameInterval = iFrameInterval;
  }

  @Override
  public void close() {
    if (mAudioRecorder != null) {
      mAudioRecorder.stop();
    }
    if (mVideoRecorder != null) {
      mVideoRecorder.stop();
    }
    if (mAudioInputStream != null) {
      try {
        mAudioInputStream.close();
      } catch (IOException e) {
        // ignored
      }
    }
    if (mVideoInputStream != null) {
      try {
        mVideoInputStream.close();
      } catch (IOException e) {
        // ignored
      }
    }
  }

  public boolean hasAudio() {
    return mAudioInputStream != null;
  }

  public InputStream getAudioInputStream() {
    if (mAudioInputStream == null) {
      throw new IllegalStateException("Cannot get an input stream from this source");
    }
    return mAudioInputStream;
  }

  public boolean hasVideo() {
    return mVideoInputStream != null;
  }

  public InputStream getVideoInputStream() {
    if (mVideoInputStream == null) {
      throw new IllegalStateException("Cannot get an input stream from this source");
    }
    return mVideoInputStream;
  }

  public int getBitRate() {
    return mBitRate;
  }

  public int getFrameRate() {
    return mFrameRate;
  }

  public int getIFrameInterval() {
    return mIFrameInterval;
  }

  public int getWidth() {
    return mSize.getWidth();
  }

  public int getHeight() {
    return mSize.getHeight();
  }

  @NonNull
  @Override
  public String toString() {
    return "VideoStream{" +
            "InputType=" + mInputType +
            ", HasAudio=" + hasAudio() +
            ", HasVideo=" + hasVideo() +
            '}';
  }

  private enum InputType {
    UNKNOWN, INPUT_STREAM, CAMERA_MODULE;
  }

  public static class Builder {
    private InputType mInputType = InputType.UNKNOWN;
    private Params mParams = new Params.Builder().build();
    @Nullable private ICameraModule mCameraModule;
    @Nullable private InputStream mAudioStream;
    @Nullable private InputStream mVideoStream;
    private Size mSize;
    private int mBitRate;
    private int mFrameRate;
    private int mIFrameInterval;

    Builder setParams(Params params) {
      mParams = params;
      return this;
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    Builder attach(ICameraModule cameraModule) {
      setInputType(InputType.CAMERA_MODULE);
      mCameraModule = cameraModule;
      setSize(mCameraModule.getPreviewWidth(), mCameraModule.getPreviewHeight());
      return this;
    }

    public Builder withAudioStream(InputStream audioStream) {
      setInputType(InputType.INPUT_STREAM);
      mAudioStream = audioStream;
      return this;
    }

    public Builder withVideoStream(InputStream videoStream) {
      setInputType(InputType.INPUT_STREAM);
      mVideoStream = videoStream;
      return this;
    }

    public Builder setSize(int width, int height) {
      return setSize(new Size(width, height));
    }

    public Builder setSize(Size size) {
      mSize = size;
      return this;
    }

    public Builder setBitRate(int bitRate) {
      mBitRate = bitRate;
      return this;
    }

    public Builder setFrameRate(int frameRate) {
      mFrameRate = frameRate;
      return this;
    }

    public Builder setIFrameInterval(int iFrameInterval) {
      mIFrameInterval = iFrameInterval;
      return this;
    }

    private void setInputType(InputType inputType) {
      if (mInputType.equals(inputType)) {
        return;
      }

      if (!mInputType.equals(InputType.UNKNOWN)) {
        throw new IllegalArgumentException("Cannot change VideoStream source");
      }

      mInputType = inputType;
    }

    @SuppressLint("MissingPermission")
    public VideoStream build() {
      switch (mInputType) {
        case CAMERA_MODULE:
          return new VideoStream(mCameraModule, mParams);
        case INPUT_STREAM:
          return new VideoStream(mAudioStream, mVideoStream, mSize, mBitRate, mFrameRate, mIFrameInterval);
        case UNKNOWN:
        default:
          throw new IllegalStateException("Cannot create a VideoStream without a source");
      }
    }
  }

  public static class Params {
    private final boolean audioEnabled;
    private final boolean videoEnabled;

    private Params(boolean audioEnabled, boolean videoEnabled) {
      this.audioEnabled = audioEnabled;
      this.videoEnabled = videoEnabled;
    }

    public boolean isAudioEnabled() {
      return audioEnabled;
    }

    public boolean isVideoEnabled() {
      return videoEnabled;
    }

    public static class Builder {
      private boolean audioEnabled = true;
      private boolean videoEnabled = true;

      public Builder setAudioEnabled(boolean audioEnabled) {
        this.audioEnabled = audioEnabled;
        return this;
      }

      public Builder setVideoEnabled(boolean videoEnabled) {
        this.videoEnabled = videoEnabled;
        return this;
      }

      public Params build() {
        if (!audioEnabled && !videoEnabled) {
          throw new IllegalStateException("Cannot create a stream with both audio and video disabled");
        }

        return new Params(audioEnabled, videoEnabled);
      }
    }
  }
}
