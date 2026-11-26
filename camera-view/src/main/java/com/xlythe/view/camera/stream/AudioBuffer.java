package com.xlythe.view.camera.stream;

import androidx.annotation.RestrictTo;

import com.xlythe.view.camera.VideoStream;

/**
 * A buffer sized for the sample rate the caller asked for.
 *
 * <p>The rate is a request rather than an instruction: not every device supports every rate, so a
 * rate that cannot be sized falls back through the others, highest first.</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public abstract class AudioBuffer {
  /**
   * Highest first, because a higher rate costs bandwidth rather than delay.
   *
   * <p>This used to be the other way round, taking the first rate that worked on the grounds that
   * a shorter buffer streams sooner - which confused bytes with time. The minimum buffer scales
   * with the rate, so a buffer at 48kHz holds roughly the same number of milliseconds as one at
   * 8kHz and arrives just as quickly; it is simply six times the bytes. The old order therefore
   * bought nothing and always landed on 8kHz, since every device can size that - telephone
   * quality, with nothing above 4kHz in it.</p>
   */
  private static final int[] POSSIBLE_SAMPLE_RATES =
          new int[] {48000, 44100, 22050, 16000, 11025, 8000};

  /**
   * Wideband speech, which is what this is mostly used for.
   *
   * <p>Twice the bandwidth of the telephone rate it replaces and a different thing to listen to.
   * A caller recording music rather than a conversation should ask for more.</p>
   */
  @VideoStream.SampleRate
  public static final int DEFAULT_SAMPLE_RATE = 16000;

  /** One channel. Stereo needs two microphones and a caller who asked for it. */
  @VideoStream.ChannelCount
  public static final int DEFAULT_CHANNEL_COUNT = 1;

  private final int mSize;
  private final int mSampleRate;
  private final int mChannelCount;
  private final byte[] mData;

  protected AudioBuffer() {
    this(DEFAULT_SAMPLE_RATE, DEFAULT_CHANNEL_COUNT);
  }

  /**
   * @param preferredSampleRate tried first, and used if the device can size a buffer for it
   * @param channelCount 1 or 2; a device that cannot do two falls back to one
   */
  protected AudioBuffer(@VideoStream.SampleRate int preferredSampleRate,
      @VideoStream.ChannelCount int channelCount) {
    int channels = channelCount >= 2 ? 2 : 1;
    int size = getMinBufferSize(preferredSampleRate, channels);
    int sampleRate = preferredSampleRate;

    if (!validSize(size) && channels > 1) {
      // Stereo is the likelier of the two to be refused, so give up the second channel before
      // giving up the rate the caller asked for.
      channels = 1;
      size = getMinBufferSize(sampleRate, channels);
    }

    for (int i = 0; !validSize(size) && i < POSSIBLE_SAMPLE_RATES.length; i++) {
      sampleRate = POSSIBLE_SAMPLE_RATES[i];
      size = getMinBufferSize(sampleRate, channels);
    }

    // If none of them were good, then just pick 1kb
    if (!validSize(size)) {
      size = 1024;
    }

    this.mSize = size;
    this.mSampleRate = sampleRate;
    this.mChannelCount = channels;
    mData = new byte[size];
  }

  public int getSize() {
    return mSize;
  }

  public int getSampleRate() {
    return mSampleRate;
  }

  /** How many channels the buffer was sized for, which is 1 unless two were asked for and sized. */
  public int getChannelCount() {
    return mChannelCount;
  }

  public byte[] data() {
    return mData;
  }

  protected abstract boolean validSize(int size);

  protected abstract int getMinBufferSize(int sampleRate, int channelCount);
}
