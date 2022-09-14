package com.xlythe.view.camera.stream;

import androidx.annotation.IntDef;
import androidx.annotation.RestrictTo;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;

/**
 * A frame transforms data into a byte[] to be sent to another device.
 * All frames have a {@link VideoFrame.Type}, along with type-specific data that go along with it.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
class VideoFrame {
  private static final byte FIELD_TYPE = 1;
  private static final byte FIELD_WIDTH = 2;
  private static final byte FIELD_HEIGHT = 3;
  private static final byte FIELD_ORIENTATION = 4;
  private static final byte FIELD_BIT_RATE = 5;
  private static final byte FIELD_FRAME_RATE = 6;
  private static final byte FIELD_I_FRAME_INTERVAL = 7;
  private static final byte FIELD_DATA = 8;
  private static final byte FIELD_PRESENTATION_TIME_US = 9;
  private static final byte FIELD_FLAGS = 10;
  private static final byte FIELD_FLIPPED = 11;

  // The frame's type.
  @Type private final int type;

  // ------------ HEADER ------------

  // The width of the video.
  private final int width;
  // The height of the video.
  private final int height;
  // The orientation of the video.
  private final int orientation;
  // If the video feed is flipped horizontally.
  private final boolean flipped;
  // The bit rate of the video.
  private final int bitRate;
  // The frame rate of the video.
  private final int frameRate;
  // The iframe interval of the video.
  private final int iframeInterval;

  // ------------ DATA ------------

  // The data for the next frame of the video.
  private final byte[] data;
  // The presentation timestamp in microseconds for this buffer.
  private final long presentationTimeUs;
  // Optional flags (such as end of stream).
  private final int flags;

  private VideoFrame(
          @Type int type,
          int width,
          int height,
          int orientation,
          boolean flipped,
          int bitRate,
          int frameRate,
          int iframeInterval,
          byte[] data,
          long presentationTimeUs,
          int flags) {
    this.type = type;
    this.width = width;
    this.height = height;
    this.orientation = orientation;
    this.flipped = flipped;
    this.bitRate = bitRate;
    this.frameRate = frameRate;
    this.iframeInterval = iframeInterval;
    this.data = data;
    this.presentationTimeUs = presentationTimeUs;
    this.flags = flags;
  }

  /** Parses a VideoFrame from a byte[]. */
  public static VideoFrame fromBytes(byte[] bytes) {
    VideoFrame.Builder builder = new VideoFrame.Builder();

    ByteBuffer buffer = ByteBuffer.wrap(bytes);
    while (buffer.hasRemaining()) {
      int len = buffer.getInt();
      if (len < 1) continue;

      byte frameType = buffer.get();
      byte[] data = new byte[len - 1];
      buffer.get(data);

      try {
        switch (frameType) {
          case FIELD_TYPE:
            builder.type(Ints.fromByteArray(data));
            break;
          case FIELD_WIDTH:
            builder.width(Ints.fromByteArray(data));
            break;
          case FIELD_HEIGHT:
            builder.height(Ints.fromByteArray(data));
            break;
          case FIELD_ORIENTATION:
            builder.orientation(Ints.fromByteArray(data));
            break;
          case FIELD_FLIPPED:
            builder.flipped(data[0] == 1);
            break;
          case FIELD_BIT_RATE:
            builder.bitRate(Ints.fromByteArray(data));
            break;
          case FIELD_FRAME_RATE:
            builder.frameRate(Ints.fromByteArray(data));
            break;
          case FIELD_I_FRAME_INTERVAL:
            builder.iframeInterval(Ints.fromByteArray(data));
            break;
          case FIELD_DATA:
            builder.data(data);
            break;
          case FIELD_PRESENTATION_TIME_US:
            builder.presentationTimeUs(Longs.fromByteArray(data));
            break;
          case FIELD_FLAGS:
            builder.flags(Ints.fromByteArray(data));
            break;
        }
      } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
        // skip
      }
    }
    return builder.build();
  }

  public byte[] asBytes() {
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    try {
      switch (type) {
        case Type.HEADER:
          write(os, FIELD_TYPE, Ints.toByteArray(type));
          write(os, FIELD_WIDTH, Ints.toByteArray(width));
          write(os, FIELD_HEIGHT, Ints.toByteArray(height));
          write(os, FIELD_ORIENTATION, Ints.toByteArray(orientation));
          write(os, FIELD_FLIPPED, new byte[] { (byte) (flipped ? 1 : 0) });
          write(os, FIELD_BIT_RATE, Ints.toByteArray(bitRate));
          write(os, FIELD_FRAME_RATE, Ints.toByteArray(frameRate));
          write(os, FIELD_I_FRAME_INTERVAL, Ints.toByteArray(iframeInterval));
          break;
        case Type.DATA:
          write(os, FIELD_TYPE, Ints.toByteArray(type));
          write(os, FIELD_DATA, data);
          write(os, FIELD_PRESENTATION_TIME_US, Longs.toByteArray(presentationTimeUs));
          write(os, FIELD_FLAGS, Ints.toByteArray(flags));
          break;
      }
    } catch (IOException e) {
      // ignored
    }
    return os.toByteArray();
  }

  private void write(OutputStream outputStream, byte field, byte[] data) throws IOException {
    // LENGTH
    outputStream.write(Ints.toByteArray(data.length + 1));
    // FIELD
    outputStream.write(field);
    // DATA
    outputStream.write(data);
  }

  @Type
  public int getType() {
    return type;
  }

  public int getWidth() {
    return width;
  }

  public int getHeight() {
    return height;
  }

  public int getOrientation() {
    return orientation;
  }

  public boolean isFlipped() {
    return flipped;
  }

  public int getBitRate() {
    return bitRate;
  }

  public int getFrameRate() {
    return frameRate;
  }

  public int getIFrameInterval() {
    return iframeInterval;
  }

  public byte[] getData() {
    return data;
  }

  public long getPresentationTimeUs() {
    return presentationTimeUs;
  }

  public int getFlags() {
    return flags;
  }

  /** The type of data within this frame. */
  @Retention(RetentionPolicy.SOURCE)
  @IntDef({
          Type.HEADER,
          Type.DATA,
  })
  public @interface Type {
    int HEADER = 0;
    int DATA = 1;
  }

  static class Builder {
    @Type private int type;
    private int width;
    private int height;
    private int orientation;
    private boolean flipped;
    private int bitRate;
    private int frameRate;
    private int iframeInterval;
    private byte[] data;
    private long presentationTimeUs;
    private int flags;

    Builder() {}

    Builder(@Type int type) {
      this.type = type;
    }

    Builder type(@Type int type) {
      this.type = type;
      return this;
    }

    Builder width(int width) {
      this.width = width;
      return this;
    }

    Builder height(int height) {
      this.height = height;
      return this;
    }

    Builder orientation(int orientation) {
      this.orientation = orientation;
      return this;
    }

    Builder flipped(boolean flipped) {
      this.flipped = flipped;
      return this;
    }

    Builder bitRate(int bitRate) {
      this.bitRate = bitRate;
      return this;
    }

    Builder frameRate(int frameRate) {
      this.frameRate = frameRate;
      return this;
    }

    Builder iframeInterval(int iframeInterval) {
      this.iframeInterval = iframeInterval;
      return this;
    }

    Builder data(byte[] data) {
      this.data = data;
      return this;
    }

    Builder presentationTimeUs(long presentationTimeUs) {
      this.presentationTimeUs = presentationTimeUs;
      return this;
    }

    Builder flags(int flags) {
      this.flags = flags;
      return this;
    }

    VideoFrame build() {
      return new VideoFrame(type, width, height, orientation, flipped, bitRate, frameRate, iframeInterval, data, presentationTimeUs, flags);
    }
  }
}