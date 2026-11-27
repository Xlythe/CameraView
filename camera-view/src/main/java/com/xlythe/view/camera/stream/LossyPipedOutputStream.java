package com.xlythe.view.camera.stream;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

/**
 * A {@link PipedOutputStream} that discards data instead of blocking when the reader falls
 * behind, trading completeness for latency on a live link.
 *
 * <p>There are two ways to write to it, and they have different guarantees.</p>
 *
 * <p>{@link #writeFrame(byte[], boolean)} is the one to use for anything whose records depend on
 * each other, such as encoded video. It discards a record as a unit, holds on to the ones others
 * are decoded against for longest, and once it has discarded one, discards the records predicted
 * from it as well.</p>
 *
 * <p>The inherited {@link #write} methods drop at the granularity of a single call, and the
 * caller is responsible for the consequences: a record split across several calls can be dropped
 * halfway, and because the format on the wire carries no sentinel to resynchronize on, that
 * desynchronizes the reader permanently. They are safe only for a producer that writes one whole
 * independent record per call and then flushes, which is what {@link AudioRecorder} does with PCM
 * chunks.</p>
 */
public class LossyPipedOutputStream extends PipedOutputStream {
  /**
   * How far behind the reader may fall, in bytes, before droppable frames start being discarded.
   * Large enough to absorb a key frame and the frames around it, small enough that the reader is
   * never more than a moment behind.
   */
  public static final int DEFAULT_MAX_BUFFERED_BYTES = 128 * 1024;

  /**
   * How much further behind the reader may fall before even frames that others depend on are
   * discarded. Past this point the backlog is too stale to be worth showing, and holding on to
   * reference frames only grows it, since those are the largest frames.
   */
  private static final int HARD_CEILING_MULTIPLIER = 4;

  /** The backlog past which droppable frames, and byte writes after a flush, are discarded. */
  private volatile int maxBufferedBytes;

  /** The backlog past which even frames others depend on are discarded. */
  private volatile int hardCeilingBytes;

  /** The reader's end, whose unread bytes are the backlog every decision is made on. */
  private PipedInputStream snk;

  /** Whether the last byte write was followed by a flush, so the next one starts a new record. */
  private boolean canDropNextPacket = false;

  /** Whether the producer should make a key frame, because the reader is missing the last one. */
  private boolean resyncRequested = false;

  /**
   * Whether a frame has been discarded since the last frame others depend on was written. While it
   * is set, every droppable frame would be predicted from something the reader never got.
   */
  private boolean chainBroken = false;

  public LossyPipedOutputStream() {
    this(DEFAULT_MAX_BUFFERED_BYTES);
  }

  public LossyPipedOutputStream(int maxBufferedBytes) {
    super();
    this.maxBufferedBytes = maxBufferedBytes;
    this.hardCeilingBytes = maxBufferedBytes * HARD_CEILING_MULTIPLIER;
  }

  public LossyPipedOutputStream(PipedInputStream snk) throws IOException {
    this(snk, DEFAULT_MAX_BUFFERED_BYTES);
  }

  public LossyPipedOutputStream(PipedInputStream snk, int maxBufferedBytes) throws IOException {
    super(snk);
    this.snk = snk;
    this.maxBufferedBytes = maxBufferedBytes;
    this.hardCeilingBytes = maxBufferedBytes * HARD_CEILING_MULTIPLIER;
  }

  @Override
  public synchronized void connect(PipedInputStream snk) throws IOException {
    super.connect(snk);
    this.snk = snk;
  }

  /**
   * Writes one whole frame, or discards the whole frame.
   *
   * <p>A frame is only ever written in full, so the reader never sees a partial record.</p>
   *
   * @param frame the complete frame, including whatever length prefix the format uses
   * @param droppable whether the stream survives losing this frame. Pass true for a delta frame,
   *     which costs at most the rest of its group of pictures, and false for a key frame, a
   *     codec-configuration frame and a stream header: those are written even when the reader is
   *     behind, because nothing after them decodes without them.
   * @return true when the frame was written, false when it was dropped
   */
  public synchronized boolean writeFrame(byte[] frame, boolean droppable) throws IOException {
    PipedInputStream sink = requireSink();
    int buffered = sink.available();
    if (droppable) {
      if (chainBroken || buffered > maxBufferedBytes) {
        // H.264 predicts each delta from the frame before it, so once one is gone every delta
        // until the next key frame decodes to garbage. Sending them would spend the link on
        // frames the reader cannot show, so drop them too and ask for that key frame early.
        if (!chainBroken) {
          chainBroken = true;
          resyncRequested = true;
        }
        return false;
      }
    } else if (buffered > hardCeilingBytes) {
      // Reference frames are piling up too. Only the newest one is ever needed to resume, and
      // the queued ones describe a moment that has already passed, so stop feeding the backlog
      // and let the reader drain. Ask for a fresh one via consumeResyncRequest once it has.
      chainBroken = true;
      resyncRequested = true;
      return false;
    }
    super.write(frame, 0, frame.length);
    super.flush();
    if (!droppable) {
      // Everything after this frame is decoded against it, so nothing lost before it matters.
      chainBroken = false;
      resyncRequested = false;
    }
    // The frame API owns its own decision, so leave the byte-level path's state alone.
    canDropNextPacket = false;
    return true;
  }

  /**
   * Whether the producer should now make a new frame that nothing else depends on, because the
   * ones it had were discarded.
   *
   * <p>Returns true once, and only after the reader has read everything it was given. A key frame
   * is the largest frame there is, so one asked for any sooner lands on a backlog that is still
   * too deep, pushes it back over the threshold and costs the next group of pictures too. Until
   * then the reader holds its last picture, which is the cost of having fallen behind.</p>
   */
  public synchronized boolean consumeResyncRequest() throws IOException {
    if (!resyncRequested || requireSink().available() > 0) {
      return false;
    }
    resyncRequested = false;
    return true;
  }

  /** Returns how far behind the reader currently is, in bytes. */
  public synchronized int bufferedBytes() throws IOException {
    return requireSink().available();
  }

  /**
   * Adjusts how far behind the reader may fall before frames are dropped.
   *
   * <p>A byte count only means something next to a bit rate: the same backlog that is a fraction
   * of a second of video on a fast link is several seconds of it on a slow one, and on a live
   * stream that latency is the failure the dropping exists to prevent. A producer that knows its
   * bit rate should set this from it rather than leave the default.</p>
   */
  public synchronized void setMaxBufferedBytes(int maxBufferedBytes) {
    if (maxBufferedBytes <= 0) {
      throw new IllegalArgumentException("Buffer limit must be positive: " + maxBufferedBytes);
    }
    this.maxBufferedBytes = maxBufferedBytes;
    this.hardCeilingBytes = maxBufferedBytes * HARD_CEILING_MULTIPLIER;
  }

  /** The backlog, in bytes, at which droppable frames start being discarded. */
  public int getMaxBufferedBytes() {
    return maxBufferedBytes;
  }

  @Override
  public void write(int b) throws IOException {
    if (shouldDropPacket()) {
      return;
    }
    super.write(b);
  }

  @Override
  public void write(byte[] b) throws IOException {
    if (shouldDropPacket()) {
      return;
    }
    super.write(b);
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    if (shouldDropPacket()) {
      return;
    }
    super.write(b, off, len);
  }

  @Override
  public synchronized void flush() throws IOException {
    super.flush();
    canDropNextPacket = true;
  }

  private boolean shouldDropPacket() throws IOException {
    PipedInputStream pipedInputStream = requireSink();

    int bytesWritten = pipedInputStream.available();
    if (canDropNextPacket && bytesWritten > maxBufferedBytes) {
      // Deliberately leaves the flag set, so a producer that splits one record over several
      // calls drops all of them together rather than writing the tail of a dropped record.
      return true;
    }

    canDropNextPacket = false;
    return false;
  }

  private PipedInputStream requireSink() throws IOException {
    PipedInputStream sink = snk;
    if (sink == null) {
      throw new IOException("Missing sink");
    }
    return sink;
  }
}
