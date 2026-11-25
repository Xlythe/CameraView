package com.xlythe.view.camera.v2;

import android.annotation.TargetApi;
import android.media.Image;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Pairs each RAW frame with the capture metadata that describes it.
 *
 * <p>A DNG needs both halves. The frame carries the sensor's own readings and the capture result
 * carries everything needed to interpret them - the colour matrices, the black levels, the noise
 * profile - and the two arrive on different callbacks, a frame on the reader's listener and a
 * result on the session's, with no ordering between them. So whichever half arrives first waits
 * here for the other.</p>
 *
 * <p>The sensor timestamp is what ties them together: an {@link Image#getTimestamp} and the
 * {@code SENSOR_TIMESTAMP} of the result for the same exposure are the same clock reading, so the
 * match is exact rather than nearest. A tolerance would pair the frames of a burst to each
 * other's results, which is worse than not pairing them at all - the metadata decides how the
 * pixels are read, so the wrong result produces a plausible-looking and wrong photograph.</p>
 *
 * <p>Both sides are bounded, because a half may never get its partner: a capture that fails
 * delivers neither, an aborted one can deliver a result with no frame, and a session torn down
 * mid-capture can deliver a frame with no result. Past the bound the oldest is dropped, and a
 * dropped frame is closed - an ImageReader whose buffers are all held hands out nothing further,
 * so a frame kept forever stops every later capture rather than only its own.</p>
 *
 * @param <M> the metadata half: a {@code TotalCaptureResult} in production, and something a test
 *     can tell apart at a glance under test. The timestamp is passed alongside it rather than
 *     read from it, because a result that carries no sensor timestamp cannot be paired at all and
 *     that is the caller's problem to notice.
 */
@TargetApi(21)
final class RawCapturePairer<M> {
    /** A frame and the metadata that describes it, ready to be written as a DNG. */
    static final class Pairing<M> {
        @NonNull final Image image;
        @NonNull final M metadata;

        Pairing(@NonNull Image image, @NonNull M metadata) {
            this.image = image;
            this.metadata = metadata;
        }
    }

    /** Metadata waiting for its frame, with the timestamp it will be matched on. */
    private static final class Pending<M> {
        private final long timestamp;
        @NonNull private final M metadata;

        Pending(long timestamp, @NonNull M metadata) {
            this.timestamp = timestamp;
            this.metadata = metadata;
        }
    }

    private final int mMaxPending;
    private final Deque<Image> mImages = new ArrayDeque<>();
    private final Deque<Pending<M>> mMetadata = new ArrayDeque<>();
    private int mDroppedImages;
    private int mDroppedMetadata;

    /** @param maxPending how many frames, and how many results, may wait for a partner */
    RawCapturePairer(int maxPending) {
        if (maxPending < 1) {
            throw new IllegalArgumentException("A pairer that holds nothing can pair nothing");
        }
        mMaxPending = maxPending;
    }

    /**
     * Offers a frame.
     *
     * @return the pairing, when the result for this frame was already waiting, and null when the
     *     frame is now the one waiting. A frame that is neither paired nor held has been closed.
     */
    @Nullable
    synchronized Pairing<M> offerImage(@NonNull Image image) {
        M metadata = takeMetadata(image.getTimestamp());
        if (metadata != null) {
            return new Pairing<>(image, metadata);
        }
        while (mImages.size() >= mMaxPending) {
            mImages.removeFirst().close();
            mDroppedImages++;
        }
        mImages.addLast(image);
        return null;
    }

    /**
     * Offers the metadata for a capture.
     *
     * @param timestamp the result's {@code SENSOR_TIMESTAMP}
     * @return the pairing, when the frame it describes was already waiting, else null
     */
    @Nullable
    synchronized Pairing<M> offerMetadata(@NonNull M metadata, long timestamp) {
        Image image = takeImage(timestamp);
        if (image != null) {
            return new Pairing<>(image, metadata);
        }
        while (mMetadata.size() >= mMaxPending) {
            mMetadata.removeFirst();
            mDroppedMetadata++;
        }
        mMetadata.addLast(new Pending<>(timestamp, metadata));
        return null;
    }

    /** Releases everything still waiting, for a session that is going away. */
    synchronized void clear() {
        for (Image image : mImages) {
            image.close();
        }
        mImages.clear();
        mMetadata.clear();
    }

    /** How many frames are waiting for a result. */
    synchronized int pendingImageCount() {
        return mImages.size();
    }

    /** How many results are waiting for a frame. */
    synchronized int pendingMetadataCount() {
        return mMetadata.size();
    }

    /** How many frames have been dropped, and so closed, for want of a result. */
    synchronized int droppedImageCount() {
        return mDroppedImages;
    }

    /** How many results have been dropped for want of a frame. */
    synchronized int droppedMetadataCount() {
        return mDroppedMetadata;
    }

    @Nullable
    private M takeMetadata(long timestamp) {
        for (Iterator<Pending<M>> pending = mMetadata.iterator(); pending.hasNext(); ) {
            Pending<M> candidate = pending.next();
            if (candidate.timestamp == timestamp) {
                pending.remove();
                return candidate.metadata;
            }
        }
        return null;
    }

    @Nullable
    private Image takeImage(long timestamp) {
        for (Iterator<Image> images = mImages.iterator(); images.hasNext(); ) {
            Image candidate = images.next();
            if (candidate.getTimestamp() == timestamp) {
                images.remove();
                return candidate;
            }
        }
        return null;
    }
}
