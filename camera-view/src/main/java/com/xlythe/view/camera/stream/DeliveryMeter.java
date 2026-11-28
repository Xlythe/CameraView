package com.xlythe.view.camera.stream;

import android.os.SystemClock;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;

/**
 * Measures how fast whoever reads a stream is taking it, and how late it arrives at the far end.
 *
 * <p>The reader is the app's transport, so the rate it reads at is the link's rate once the link
 * is the bottleneck. An app whose transport acknowledges what arrived can say so too, and then the
 * rate comes from the acknowledgements, and the time from reading a byte to hearing it arrived
 * says whether a queue is growing somewhere the stream cannot see.</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class DeliveryMeter {
    /** How many reads are remembered for matching acknowledgements against. */
    private static final int MAX_READS = 1024;

    /** Told what was delivered. */
    interface Listener {
        /** The running total of bytes delivered, read or acknowledged. */
        @AnyThread
        void onDelivered(long nowMillis, long totalBytes);

        /** How long the latest acknowledged byte took from being read to being acknowledged. */
        @AnyThread
        void onDelay(long nowMillis, long delayMillis);
    }

    /** A source of time, which tests set. */
    interface Clock {
        long nowMillis();
    }

    /** The time the rest of the stream runs on. */
    private final Clock mClock;

    /** Reads not yet acknowledged: pairs of the running total after the read, and its time. */
    private final ArrayDeque<long[]> mReads = new ArrayDeque<>();

    /** Bytes read so far. */
    private long mTotalRead;

    /** Whether the app acknowledges, so delivery is counted from acknowledgements, not reads. */
    private boolean mAcknowledging;

    /** Who is told, once the recorder has a controller to tell. */
    @Nullable private Listener mListener;

    public DeliveryMeter() {
        this(SystemClock::elapsedRealtime);
    }

    DeliveryMeter(@NonNull Clock clock) {
        mClock = clock;
    }

    /** The stream to hand the reader, which counts what it takes. */
    @NonNull
    public InputStream wrap(@NonNull InputStream inputStream) {
        return new FilterInputStream(inputStream) {
            @Override
            public int read() throws IOException {
                int value = super.read();
                if (value >= 0) {
                    onRead(1);
                }
                return value;
            }

            @Override
            public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
                int count = super.read(buffer, offset, length);
                if (count > 0) {
                    onRead(count);
                }
                return count;
            }

            @Override
            public long skip(long count) throws IOException {
                long skipped = super.skip(count);
                if (skipped > 0) {
                    onRead(skipped);
                }
                return skipped;
            }
        };
    }

    /** Starts telling a listener, or stops with null. */
    @AnyThread
    synchronized void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    @AnyThread
    private synchronized void onRead(long count) {
        long now = mClock.nowMillis();
        mTotalRead += count;
        mReads.addLast(new long[] {mTotalRead, now});
        if (mReads.size() > MAX_READS) {
            mReads.removeFirst();
        }
        if (!mAcknowledging && mListener != null) {
            mListener.onDelivered(now, mTotalRead);
        }
    }

    /**
     * Reports the running total of bytes the far end has received.
     *
     * <p>Acknowledgements replace reads as the measure of delivery from the first one on: a reader
     * that buffers what it reads would otherwise look faster than the link.</p>
     */
    @AnyThread
    public synchronized void onAcknowledged(long totalBytes) {
        long now = mClock.nowMillis();
        mAcknowledging = true;
        long[] containing = null;
        while (!mReads.isEmpty()) {
            long[] read = mReads.peekFirst();
            if (read[0] >= totalBytes) {
                containing = read;
                break;
            }
            mReads.removeFirst();
        }
        if (mListener == null) {
            return;
        }
        mListener.onDelivered(now, totalBytes);
        if (containing != null) {
            mListener.onDelay(now, Math.max(0, now - containing[1]));
        }
    }
}
