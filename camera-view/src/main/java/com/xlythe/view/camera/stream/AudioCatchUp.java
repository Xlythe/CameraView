package com.xlythe.view.camera.stream;

import androidx.annotation.IntRange;
import androidx.annotation.WorkerThread;

import com.xlythe.view.camera.VideoStream;

/**
 * Decides how much of a live audio stream's backlog to throw away, so playback stays near live.
 *
 * <p>An AudioTrack plays at the rate the audio was recorded, so delay that builds up in front of
 * it - after an outage, or behind a transport that delivered a burst - is never worked off. Some
 * backlog is the cushion that rides out jitter, though, and discarding that only turns jitter into
 * gaps. The two are told apart over time: whatever was still waiting at the backlog's lowest point
 * over the last second was never needed as a cushion, so that much is delay.</p>
 *
 * <p>Used from {@link AudioPlayer}'s thread alone.</p>
 */
final class AudioCatchUp {
  /**
   * How much playback the backlog is watched over before any of it counts as delay. Longer than
   * the gaps of a link that drops out briefly and often, whose cushion is not delay.
   */
  static final int WINDOW_MILLIS = 1000;

  /** Backlog kept at the window's lowest point, as the margin for the next one. */
  static final int TARGET_MILLIS = 80;

  /** 16-bit PCM, which is what AudioRecorder writes. */
  private static final int BYTES_PER_SAMPLE = 2;

  /**
   * Bytes in one frame, a sample for each channel. Anything discarded is a whole number of these,
   * or every sample after it would be read from the middle of one.
   */
  private final int mFrameBytes;

  /** Bytes of playback a window spans. */
  private final long mWindowBytes;

  /** Bytes of backlog kept. */
  private final int mTargetBytes;

  /** Bytes played since the window began. */
  private long mPlayedBytes;

  /** The smallest backlog seen since the window began. */
  private int mLowestBacklog = Integer.MAX_VALUE;

  AudioCatchUp(@IntRange(from = 1) int sampleRate, @VideoStream.ChannelCount int channelCount) {
    mFrameBytes = BYTES_PER_SAMPLE * channelCount;
    long bytesPerSecond = (long) sampleRate * mFrameBytes;
    mWindowBytes = bytesPerSecond * WINDOW_MILLIS / 1000;
    mTargetBytes = (int) (bytesPerSecond * TARGET_MILLIS / 1000);
  }

  /** Bytes in one frame, which is the unit the player has to write and discard in. */
  int getFrameBytes() {
    return mFrameBytes;
  }

  /**
   * How much of the backlog to discard before the next read.
   *
   * @param backlog bytes that have arrived and not been read, as InputStream#available reports
   *     them. A stream that cannot tell reports none, and is never skipped.
   * @return a whole number of frames, and never more than the backlog
   */
  @WorkerThread
  int bytesToSkip(int backlog) {
    mLowestBacklog = Math.min(mLowestBacklog, backlog);
    if (mPlayedBytes < mWindowBytes) {
      return 0;
    }
    int excess = mLowestBacklog - mTargetBytes;
    mPlayedBytes = 0;
    mLowestBacklog = Integer.MAX_VALUE;
    return excess > 0 ? excess - excess % mFrameBytes : 0;
  }

  /** Counts bytes handed to the track, which is the clock the window runs on. */
  @WorkerThread
  void onPlayed(int bytes) {
    mPlayedBytes += bytes;
  }
}
