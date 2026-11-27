package com.xlythe.view.camera.stream;

import android.media.MediaCodec;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The last few seconds of an encoded video, kept so that a moment can be cut out of it after the
 * moment has passed.
 *
 * <p>It always starts at a key frame. Every frame of H.264 and HEVC after a key frame is coded
 * against the frames before it, back to that key frame, so anything older than the newest key
 * frame worth keeping is dropped a whole group at a time rather than frame by frame: half a group
 * cannot be decoded.</p>
 *
 * <p>How much it keeps is a duration behind the newest frame, measured in the camera's own
 * timestamps. A capture that is still waiting for frames after its moment can hold the frames
 * before it, so that a long post-roll cannot trim away the pre-roll it was going to use.</p>
 */
final class EncodedVideoBuffer {
    /** One encoded frame. */
    static final class Sample {
        /** The frame's bytes, exactly as the encoder produced them. */
        @NonNull final byte[] data;
        /** When the camera captured the frame, in microseconds of the camera's clock. */
        final long presentationTimeUs;
        /** MediaCodec's flags for the frame, of which only the key frame flag matters here. */
        final int flags;

        Sample(@NonNull byte[] data, long presentationTimeUs, int flags) {
            this.data = data;
            this.presentationTimeUs = presentationTimeUs;
            this.flags = flags;
        }

        /** Whether the frame decodes on its own, which is where a clip can start. */
        boolean isKeyFrame() {
            return (flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        }
    }

    /** How much video to keep behind the newest frame. */
    private final long mHistoryUs;
    /**
     * The longest gap between two frames that is still one video. Longer means the camera stopped
     * and started again - a pause, a session rebuilt, a picture on the old camera API - and a clip
     * spanning the gap would jump from before it to after.
     */
    private final long mMaxGapUs;
    /** A ceiling on the bytes held, whatever the timestamps say, so memory stays bounded. */
    private final long mMaxBytes;

    /** The frames, oldest first. The oldest is always a key frame. */
    private final ArrayDeque<Sample> mSamples = new ArrayDeque<>();
    /** The total size of {@link #mSamples}. */
    private long mBytes;
    /** The earliest timestamp each capture in progress still needs, one entry per capture. */
    private final List<Long> mHolds = new ArrayList<>();

    EncodedVideoBuffer(long historyUs, long maxGapUs, long maxBytes) {
        mHistoryUs = historyUs;
        mMaxGapUs = maxGapUs;
        mMaxBytes = maxBytes;
    }

    /**
     * Adds a frame the encoder produced.
     *
     * @return false if the frame could not be kept because it is not a key frame and there is no
     *     key frame before it to decode it against. The caller asks the encoder for one.
     */
    @AnyThread
    synchronized boolean add(@NonNull byte[] data, long presentationTimeUs, int flags) {
        if ((flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            // The parameter sets travel in the output format, which the muxer is given.
            return true;
        }
        Sample newest = mSamples.peekLast();
        if (newest != null && Math.abs(presentationTimeUs - newest.presentationTimeUs) > mMaxGapUs) {
            dropAll();
        }
        Sample sample = new Sample(data, presentationTimeUs, flags);
        if (mSamples.isEmpty() && !sample.isKeyFrame()) {
            return false;
        }
        mSamples.addLast(sample);
        mBytes += data.length;
        trim();
        return true;
    }

    /** The newest frame's timestamp, or {@link Long#MIN_VALUE} when there are none. */
    @AnyThread
    synchronized long newestPresentationTimeUs() {
        Sample newest = mSamples.peekLast();
        return newest == null ? Long.MIN_VALUE : newest.presentationTimeUs;
    }

    /** Keeps the frames a clip starting at {@code fromUs} needs until {@link #release} says so. */
    @AnyThread
    synchronized void hold(long fromUs) {
        mHolds.add(fromUs);
    }

    /** Lets go of a {@link #hold}. */
    @AnyThread
    synchronized void release(long fromUs) {
        mHolds.remove(Long.valueOf(fromUs));
        trim();
    }

    /**
     * The frames from the last key frame at or before {@code fromUs} through the last frame at or
     * before {@code toUs}. Starts at the oldest frame when everything kept is younger than
     * {@code fromUs}, which is a shorter clip rather than none.
     */
    @AnyThread
    @NonNull
    synchronized List<Sample> slice(long fromUs, long toUs) {
        List<Sample> slice = new ArrayList<>();
        Sample start = null;
        for (Sample sample : mSamples) {
            if (sample.presentationTimeUs > fromUs) {
                break;
            }
            if (sample.isKeyFrame()) {
                start = sample;
            }
        }
        boolean started = false;
        for (Sample sample : mSamples) {
            if (!started) {
                started = start == null || sample == start;
                if (!started) {
                    continue;
                }
            }
            if (sample.presentationTimeUs > toUs) {
                break;
            }
            slice.add(sample);
        }
        return slice;
    }

    /** Drops every frame, as when the encoder is replaced. */
    @AnyThread
    synchronized void clear() {
        dropAll();
    }

    /** How many frames are held. */
    @AnyThread
    synchronized int size() {
        return mSamples.size();
    }

    /** How many bytes are held. */
    @AnyThread
    synchronized long bytes() {
        return mBytes;
    }

    /** The oldest frame's timestamp, or {@link Long#MIN_VALUE} when there are none. */
    @AnyThread
    synchronized long oldestPresentationTimeUs() {
        Sample oldest = mSamples.peekFirst();
        return oldest == null ? Long.MIN_VALUE : oldest.presentationTimeUs;
    }

    private void dropAll() {
        mSamples.clear();
        mBytes = 0;
    }

    /**
     * Drops whole groups of frames from the front: everything before the newest key frame that is
     * at least the history old, and no later than any capture's hold. Then, whatever the holds,
     * groups until the bytes are back under the ceiling.
     */
    private void trim() {
        Sample newest = mSamples.peekLast();
        if (newest == null) {
            return;
        }
        long keepFrom = newest.presentationTimeUs - mHistoryUs;
        for (long hold : mHolds) {
            keepFrom = Math.min(keepFrom, hold);
        }
        Sample firstKept = null;
        for (Sample sample : mSamples) {
            if (sample.presentationTimeUs > keepFrom) {
                break;
            }
            if (sample.isKeyFrame()) {
                firstKept = sample;
            }
        }
        if (firstKept != null) {
            dropBefore(firstKept);
        }
        while (mBytes > mMaxBytes && !mSamples.isEmpty()) {
            dropOldestGroup();
        }
    }

    /** Drops every frame ahead of the given one. */
    private void dropBefore(@NonNull Sample sample) {
        Iterator<Sample> iterator = mSamples.iterator();
        while (iterator.hasNext()) {
            Sample next = iterator.next();
            if (next == sample) {
                return;
            }
            mBytes -= next.data.length;
            iterator.remove();
        }
    }

    /** Drops the oldest key frame and the frames that depend on it. */
    private void dropOldestGroup() {
        Sample oldest = mSamples.pollFirst();
        if (oldest == null) {
            return;
        }
        mBytes -= oldest.data.length;
        while (!mSamples.isEmpty() && !mSamples.peekFirst().isKeyFrame()) {
            mBytes -= mSamples.pollFirst().data.length;
        }
    }
}
