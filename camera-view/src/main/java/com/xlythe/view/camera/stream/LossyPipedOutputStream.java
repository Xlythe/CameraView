package com.xlythe.view.camera.stream;

import androidx.annotation.RestrictTo;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

@RestrictTo(RestrictTo.Scope.LIBRARY)
public class LossyPipedOutputStream extends PipedOutputStream {
  private static final int MAX_BUFFER_SIZE = 0;

  private PipedInputStream snk;

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

  private boolean shouldDropPacket() throws IOException {
    PipedInputStream pipedInputStream = snk;
    if (snk == null) {
      throw new IOException("Missing sink");
    }

    int bytesWritten = pipedInputStream.available();
    return false;//bytesWritten > MAX_BUFFER_SIZE;
  }
}