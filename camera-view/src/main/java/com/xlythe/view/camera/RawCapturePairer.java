package com.xlythe.view.camera;

import android.hardware.camera2.DngCreator;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

import androidx.annotation.AnyThread;
import androidx.annotation.GuardedBy;
import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;

/**
 * Pairs each RAW frame with the capture result that describes it.
 *
 * <p>A DNG needs both. The frame carries the sensor's readings and the result carries what is
 * needed to interpret them - the colour matrices, the black levels, the noise profile - and they
 * arrive on different callbacks with no ordering between them, so whichever comes first waits
 * here for the other.</p>
 *
 * <p>They are matched on the sensor timestamp, exactly. A frame's timestamp and the
 * {@code SENSOR_TIMESTAMP} of its result are the same clock reading, and a nearest match would
 * pair the frames of a burst with each other's results: a plausible and wrong photograph rather
 * than an obviously broken one.</p>
 *
 * <p>Both sides are bounded, because a half may never get its partner: a failed capture delivers
 * neither, and a session torn down mid-capture can deliver one without the other. Past the bound
 * the oldest goes, and a dropped frame is closed, since a reader whose buffers are all held hands
 * out nothing further.</p>
 *
 * @param <M> the result half: a {@code TotalCaptureResult} on both backends, and anything a test
 *     can tell apart. Its timestamp is passed beside it, because a result without one cannot be
 *     paired and that is for the caller to notice.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class RawCapturePairer<M> {
    /** A RAW frame waiting to be written, holding a buffer until it is closed. */
    public interface Frame {
        /** When the exposure started, on the clock {@code SENSOR_TIMESTAMP} reads. */
        long getTimestamp();

        /** Hands the pixels to DngCreator to write. Only valid until {@link #close}. */
        void writeTo(@NonNull DngCreator creator, @NonNull OutputStream output)
                throws IOException;

        /** Gives the buffer back. Called once by whoever ends up holding the frame. */
        @AnyThread
        void close();
    }

    /** A frame and the result that describes it, ready to be written as a DNG. */
    public static final class Pairing<M> {
        /** Owned by whoever received the pairing, who has to close it. */
        @NonNull
        public final Frame frame;

        /** The capture result with the same sensor timestamp as {@link #frame}. */
        @NonNull
        public final M metadata;

        Pairing(@NonNull Frame frame, @NonNull M metadata) {
            this.frame = frame;
            this.metadata = metadata;
        }
    }

    /** A result waiting for its frame, with the timestamp it will be matched on. */
    private static final class PendingMetadata<M> {
        /** The result's {@code SENSOR_TIMESTAMP}. */
        private final long timestamp;

        /** The result itself. */
        @NonNull
        private final M metadata;

        PendingMetadata(long timestamp, @NonNull M metadata) {
            this.timestamp = timestamp;
            this.metadata = metadata;
        }
    }

    /** How many frames, and separately how many results, may wait for a partner. */
    private final int mMaxPending;

    /** Frames waiting for their results, oldest first. */
    @GuardedBy("this")
    private final Deque<Frame> mFrames = new ArrayDeque<>();

    /** Results waiting for their frames, oldest first. */
    @GuardedBy("this")
    private final Deque<PendingMetadata<M>> mMetadata = new ArrayDeque<>();

    /** How many frames were dropped, and closed, for want of a result. */
    @GuardedBy("this")
    private int mDroppedFrames;

    /** How many results were dropped for want of a frame. */
    @GuardedBy("this")
    private int mDroppedMetadata;

    public RawCapturePairer(@IntRange(from = 1) int maxPending) {
        if (maxPending < 1) {
            throw new IllegalArgumentException("A pairer that holds nothing can pair nothing");
        }
        mMaxPending = maxPending;
    }

    /**
     * Offers a frame.
     *
     * @return the pairing when the frame's result was already waiting, or null when the frame is
     *     now the one waiting. A frame that is neither paired nor held has been closed.
     */
    @AnyThread
    @Nullable
    public synchronized Pairing<M> offerFrame(@NonNull Frame frame) {
        M metadata = takeMetadata(frame.getTimestamp());
        if (metadata != null) {
            return new Pairing<>(frame, metadata);
        }
        while (mFrames.size() >= mMaxPending) {
            mFrames.removeFirst().close();
            mDroppedFrames++;
        }
        mFrames.addLast(frame);
        return null;
    }

    /**
     * Offers the result of a capture.
     *
     * @param timestamp the result's {@code SENSOR_TIMESTAMP}
     * @return the pairing when the frame it describes was already waiting, else null
     */
    @AnyThread
    @Nullable
    public synchronized Pairing<M> offerMetadata(@NonNull M metadata, long timestamp) {
        Frame frame = takeFrame(timestamp);
        if (frame != null) {
            return new Pairing<>(frame, metadata);
        }
        while (mMetadata.size() >= mMaxPending) {
            mMetadata.removeFirst();
            mDroppedMetadata++;
        }
        mMetadata.addLast(new PendingMetadata<>(timestamp, metadata));
        return null;
    }

    /** Closes every waiting frame and forgets every waiting result. */
    @AnyThread
    public synchronized void clear() {
        for (Frame frame : mFrames) {
            frame.close();
        }
        mFrames.clear();
        mMetadata.clear();
    }

    @VisibleForTesting
    synchronized int pendingFrameCount() {
        return mFrames.size();
    }

    @VisibleForTesting
    synchronized int pendingMetadataCount() {
        return mMetadata.size();
    }

    @VisibleForTesting
    synchronized int droppedFrameCount() {
        return mDroppedFrames;
    }

    @VisibleForTesting
    synchronized int droppedMetadataCount() {
        return mDroppedMetadata;
    }

    @GuardedBy("this")
    @Nullable
    private M takeMetadata(long timestamp) {
        for (Iterator<PendingMetadata<M>> pending = mMetadata.iterator(); pending.hasNext(); ) {
            PendingMetadata<M> candidate = pending.next();
            if (candidate.timestamp == timestamp) {
                pending.remove();
                return candidate.metadata;
            }
        }
        return null;
    }

    @GuardedBy("this")
    @Nullable
    private Frame takeFrame(long timestamp) {
        for (Iterator<Frame> frames = mFrames.iterator(); frames.hasNext(); ) {
            Frame candidate = frames.next();
            if (candidate.getTimestamp() == timestamp) {
                frames.remove();
                return candidate;
            }
        }
        return null;
    }
}
