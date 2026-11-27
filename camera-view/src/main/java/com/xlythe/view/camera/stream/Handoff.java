package com.xlythe.view.camera.stream;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A value that one thread sets once and another waits for.
 *
 * <p>The surface handshakes in {@link VideoRecorder} and FrameStream pass the frame size from the
 * camera's thread to the one consuming frames, and the surface back. A backend may ask for the
 * surface again after reconfiguring, so the first value is kept and every later wait returns it.
 * </p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class Handoff<V> {
  /** Released once {@link #mValue} holds the value. */
  private final CountDownLatch mSet = new CountDownLatch(1);

  /** The first value given to {@link #set}. Later ones are dropped. */
  private final AtomicReference<V> mValue = new AtomicReference<>();

  /** Publishes the value, unless one was already published. */
  @AnyThread
  public void set(@NonNull V value) {
    if (mValue.compareAndSet(null, value)) {
      mSet.countDown();
    }
  }

  /** Waits up to the timeout for the value. */
  @AnyThread
  @NonNull
  public V get(long timeout, @NonNull TimeUnit unit)
          throws InterruptedException, TimeoutException {
    if (!mSet.await(timeout, unit)) {
      throw new TimeoutException("Nothing was handed off within " + timeout + " " + unit);
    }
    return mValue.get();
  }
}
