package com.xlythe.view.camera.stream;

import androidx.annotation.RestrictTo;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

public class LossyPipedOutputStream extends PipedOutputStream {
  private static final int MAX_BUFFER_SIZE = 0;

  private PipedInputStream snk;
  private boolean canDropNextPacket = false;

  public LossyPipedOutputStream() {
    super();
  }

  public LossyPipedOutputStream(PipedInputStream snk) throws IOException {
    super(snk);
    this.snk = snk;
  }

  @Override
  public synchronized void connect(PipedInputStream snk) throws IOException {
    super.connect(snk);
    this.snk = snk;
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
    PipedInputStream pipedInputStream = snk;
    if (snk == null) {
      throw new IOException("Missing sink");
    }

    int bytesWritten = pipedInputStream.available();
    if (canDropNextPacket && bytesWritten > MAX_BUFFER_SIZE) {
      return true;
    }

    canDropNextPacket = false;
    return false;
  }
}