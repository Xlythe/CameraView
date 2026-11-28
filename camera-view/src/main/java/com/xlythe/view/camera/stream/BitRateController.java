package com.xlythe.view.camera.stream;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Decides how much a live stream should send, from how well the link is keeping up.
 *
 * <p>The approach is WebRTC's: estimate what the link carries, cut fast when it falls behind and
 * raise slowly when it does not. Three signals say it has fallen behind - a queue that stays in
 * the stream's pipe instead of draining, frames the pipe had to drop, and acknowledgements that
 * arrive later than they used to - and a cut goes straight to just under what the reader actually
 * took, so one cut is usually enough. Raising is a few percent a second, and waits for a second
 * of calm after a cut, so the stream creeps back towards the link rather than overshooting it
 * again.</p>
 *
 * <p>Below a floor, more compression stops buying anything a viewer would want: the picture turns
 * to blocks. So the bit rate stops there and the stream gives up frames, then pixels, instead.
 * Each {@link Level} keeps the bits a frame gets per pixel at what the floor allows the first, so
 * halving the frame rate halves the floor. Moving between levels restarts the encoder, which is
 * a visible hitch, so it takes seconds of evidence each way, and a level that failed when it was
 * restored waits twice as long before it is tried again.</p>
 *
 * <p>The methods are synchronized: signals arrive from the reader's thread and the app's, and the
 * encoder's thread asks for decisions.</p>
 */
final class BitRateController implements DeliveryMeter.Listener {
    /** The video floor: below this, 15-30fps of a phone-sized picture is mostly blocking. */
    static final int VIDEO_FLOOR_BIT_RATE = 150_000;

    /** What a cut leaves of the rate the reader managed: under it, so the queue drains. */
    static final double DECREASE_FACTOR = 0.85;

    /** How much the estimate grows a second while the link keeps up. */
    static final double INCREASE_PER_SECOND = 1.08;

    /** The least time between cuts, so one congestion event is not cut for twice. */
    static final long DECREASE_INTERVAL_MILLIS = 400;

    /** How long after a cut the estimate holds before it grows again. */
    static final long HOLD_AFTER_DECREASE_MILLIS = 1000;

    /**
     * The window a standing queue is measured over. A key frame fills the pipe for a moment on any
     * link; what matters is whether the queue was ever empty in the window, as CoDel asks. Long
     * enough to hold two frames at the lowest frame rate.
     */
    static final long QUEUE_WINDOW_MILLIS = 400;

    /** A queue that never fell below this share of the drop threshold means the link is behind. */
    static final double OVERUSE_QUEUE_SHARE = 0.3;

    /** A queue that fell below this share means the link is keeping up. */
    static final double CLEAR_QUEUE_SHARE = 0.05;

    /** Acknowledgements this much later than the quickest recent one mean a queue is growing. */
    static final long OVERUSE_DELAY_MILLIS = 200;

    /** And this little later means none is. */
    static final long CLEAR_DELAY_MILLIS = 60;

    /** How long the quickest acknowledgement is remembered as the link's own delay. */
    static final long MIN_DELAY_WINDOW_MILLIS = 10_000;

    /**
     * The span the delivery rate is averaged over. Short, because a cut goes to what the reader
     * managed since the link slowed, and a long average would still be counting before.
     */
    static final long THROUGHPUT_WINDOW_MILLIS = 500;

    /** Delivery history needed before its rate is trusted. */
    static final long MIN_THROUGHPUT_SPAN_MILLIS = 250;

    /** How long the link has to fail to carry a level's floor before the next level is taken. */
    static final long DEGRADE_AFTER_MILLIS = 2000;

    /** How long the link has to carry the level above, with room, before it is restored. */
    static final long RESTORE_AFTER_MILLIS = 10_000;

    /** The longest a failed restore can push the next attempt back. */
    static final long MAX_RESTORE_AFTER_MILLIS = 80_000;

    /** A restore that is undone within this long failed. */
    static final long RESTORE_TRIAL_MILLIS = 15_000;

    /** A restore that lasts this long resets the wait to its starting value. */
    static final long RESTORE_SETTLED_MILLIS = 60_000;

    /** How far above the level's floor the estimate has to be before a level is restored. */
    static final double RESTORE_HEADROOM = 1.2;

    /** One step of what the stream gives up below the floor. */
    static final class Level {
        /** The share of the full frame rate sent. */
        final float frameRateScale;

        /** The share of the full picture area sent. */
        final float areaScale;

        Level(float frameRateScale, float areaScale) {
            this.frameRateScale = frameRateScale;
            this.areaScale = areaScale;
        }

        float getFrameRateScale() {
            return frameRateScale;
        }

        float getAreaScale() {
            return areaScale;
        }

        @NonNull
        @Override
        public String toString() {
            return "Level{fps x" + frameRateScale + ", area x" + areaScale + "}";
        }
    }

    /** The most the stream sends, which is what the caller asked for. */
    private final int mCeiling;

    /** The least it sends at full frame rate and size. */
    private final int mFloor;

    /** The ladder, full quality first. */
    private final List<Level> mLevels;

    /** The lowest the estimate goes, half the floor of the last level. */
    private final double mMinimumEstimate;

    /** What the link is thought to carry, which may be below the floor. */
    private double mEstimate;

    /** The current level. */
    private int mLevel;

    /** When the estimate was last updated, or -1 before the first update. */
    private long mLastUpdateMillis = -1;

    /** When the estimate was last cut. */
    private long mLastDecreaseMillis = Long.MIN_VALUE / 2;

    /** When the level last changed. */
    private long mLastLevelChangeMillis = Long.MIN_VALUE / 2;

    /** Whether the last level change was a restore. */
    private boolean mLastChangeWasRestore;

    /** How long a restore waits, doubled by each failed one. */
    private long mRestoreAfterMillis = RESTORE_AFTER_MILLIS;

    /** Since when the link has failed to carry the level's floor, or -1. */
    private long mBelowFloorSinceMillis = -1;

    /** Since when the link has carried the level above, or -1. */
    private long mAboveRestoreSinceMillis = -1;

    // ---------- Queue ----------

    /** When the current queue window began, or -1. */
    private long mQueueWindowStartMillis = -1;

    /** The smallest queue seen in the current window, as a share of the drop threshold. */
    private double mQueueWindowMin = Double.MAX_VALUE;

    /** The standing queue from the last complete window, or -1 before there was one. */
    private double mStandingQueue = -1;

    /** Whether a frame was dropped since the last update. */
    private boolean mDropped;

    // ---------- Delivery ----------

    /** Recent delivery totals: pairs of time and bytes, oldest first. */
    private final ArrayDeque<long[]> mDelivered = new ArrayDeque<>();

    // ---------- Delay ----------

    /** Recent delays, increasing from the front: the front is the window's minimum. */
    private final ArrayDeque<long[]> mDelays = new ArrayDeque<>();

    /** The latest delay reported, or -1 before any. */
    private long mLatestDelay = -1;

    private BitRateController(int ceiling, int floor, List<Level> levels) {
        mCeiling = ceiling;
        mFloor = Math.min(floor, ceiling);
        mLevels = levels;
        mMinimumEstimate = floorAt(levels.size() - 1) / 2.0;
        mEstimate = ceiling;
    }

    /**
     * A controller that only moves the bit rate, between a floor and the caller's ceiling.
     */
    @NonNull
    static BitRateController forBitRate(int ceiling, int floor) {
        return new BitRateController(ceiling, floor, Collections.singletonList(new Level(1, 1)));
    }

    /**
     * A controller that also gives up frames, then pixels, below the floor.
     *
     * @param levels the steps below full quality, in the order to take them
     */
    @NonNull
    static BitRateController withLevels(int ceiling, int floor, @NonNull List<Level> levels) {
        List<Level> ladder = new ArrayList<>(levels.size() + 1);
        ladder.add(new Level(1, 1));
        ladder.addAll(levels);
        return new BitRateController(ceiling, floor, Collections.unmodifiableList(ladder));
    }

    /**
     * Reports how much is waiting in the stream's pipe, just before the next frame is written.
     *
     * <p>Before rather than after, so the frame being written does not count: what is left from
     * earlier frames is what the reader did not take in time.</p>
     *
     * @param queuedBytes bytes written and not yet read
     * @param thresholdBytes the backlog past which the pipe drops frames
     */
    @AnyThread
    synchronized void onQueue(long nowMillis, int queuedBytes, int thresholdBytes) {
        double share = thresholdBytes > 0 ? (double) queuedBytes / thresholdBytes : 0;
        if (mQueueWindowStartMillis < 0) {
            mQueueWindowStartMillis = nowMillis;
        }
        mQueueWindowMin = Math.min(mQueueWindowMin, share);
        if (nowMillis - mQueueWindowStartMillis >= QUEUE_WINDOW_MILLIS) {
            mStandingQueue = mQueueWindowMin;
            mQueueWindowMin = Double.MAX_VALUE;
            mQueueWindowStartMillis = nowMillis;
        }
    }

    /** Reports that the pipe dropped a frame because the reader was behind. */
    @AnyThread
    synchronized void onDropped() {
        mDropped = true;
    }

    /** Reports the running total of bytes the far end has taken, read or acknowledged. */
    @AnyThread
    @Override
    public synchronized void onDelivered(long nowMillis, long totalBytes) {
        if (!mDelivered.isEmpty() && totalBytes < mDelivered.peekLast()[1]) {
            // A total that went backwards is a different counter; start again from it.
            mDelivered.clear();
        }
        mDelivered.addLast(new long[] {nowMillis, totalBytes});
        while (mDelivered.size() > 2
                && nowMillis - mDelivered.peekFirst()[0] > 2 * THROUGHPUT_WINDOW_MILLIS) {
            mDelivered.removeFirst();
        }
    }

    /** Reports how long the latest acknowledged byte took to be acknowledged after it was sent. */
    @AnyThread
    @Override
    public synchronized void onDelay(long nowMillis, long delayMillis) {
        mLatestDelay = delayMillis;
        while (!mDelays.isEmpty() && mDelays.peekLast()[1] >= delayMillis) {
            mDelays.removeLast();
        }
        mDelays.addLast(new long[] {nowMillis, delayMillis});
        while (nowMillis - mDelays.peekFirst()[0] > MIN_DELAY_WINDOW_MILLIS) {
            mDelays.removeFirst();
        }
    }

    /**
     * Updates the estimate and the level from the signals since the last call.
     *
     * @return whether the bit rate or the level changed
     */
    @AnyThread
    synchronized boolean update(long nowMillis) {
        int bitRateBefore = getBitRate();
        int levelBefore = mLevel;
        long elapsed = mLastUpdateMillis < 0 ? 0 : Math.max(0, nowMillis - mLastUpdateMillis);
        mLastUpdateMillis = nowMillis;

        boolean overused = isOverused();
        boolean clear = !overused && isClear();
        mDropped = false;

        if (overused) {
            if (nowMillis - mLastDecreaseMillis >= DECREASE_INTERVAL_MILLIS) {
                // Just under what the reader took, and never up. With a rate to go by, a queue
                // that takes a few cuts' worth of time to drain is cut to the same place each
                // time instead of compounding below the link.
                double throughput = throughput(nowMillis);
                double cut = (throughput > 0 ? throughput : mEstimate) * DECREASE_FACTOR;
                if (cut < mEstimate) {
                    mEstimate = Math.max(mMinimumEstimate, cut);
                }
                mLastDecreaseMillis = nowMillis;
            }
        } else if (clear && nowMillis - mLastDecreaseMillis >= HOLD_AFTER_DECREASE_MILLIS) {
            mEstimate = Math.min(mCeiling,
                    mEstimate * Math.pow(INCREASE_PER_SECOND, elapsed / 1000.0));
        }

        updateLevel(nowMillis);
        return getBitRate() != bitRateBefore || mLevel != levelBefore;
    }

    private void updateLevel(long nowMillis) {
        if (mLastChangeWasRestore
                && nowMillis - mLastLevelChangeMillis >= RESTORE_SETTLED_MILLIS) {
            mRestoreAfterMillis = RESTORE_AFTER_MILLIS;
            mLastChangeWasRestore = false;
        }

        // The estimate only falls below the floor when the reader took less than the floor
        // allows for: a cut goes to just under what it took, never below what it took. So two
        // seconds of it is a link that cannot carry this level, even on a bursty link whose queue
        // empties now and then.
        if (mEstimate < floorAt(mLevel)) {
            if (mBelowFloorSinceMillis < 0) {
                mBelowFloorSinceMillis = nowMillis;
            }
            if (mLevel < mLevels.size() - 1
                    && nowMillis - mBelowFloorSinceMillis >= DEGRADE_AFTER_MILLIS
                    && nowMillis - mLastLevelChangeMillis >= DEGRADE_AFTER_MILLIS) {
                if (mLastChangeWasRestore
                        && nowMillis - mLastLevelChangeMillis < RESTORE_TRIAL_MILLIS) {
                    mRestoreAfterMillis = Math.min(MAX_RESTORE_AFTER_MILLIS,
                            mRestoreAfterMillis * 2);
                }
                mLevel++;
                mLastLevelChangeMillis = nowMillis;
                mLastChangeWasRestore = false;
                mBelowFloorSinceMillis = -1;
                mAboveRestoreSinceMillis = -1;
            }
        } else {
            mBelowFloorSinceMillis = -1;
        }

        // The headroom is capped at the ceiling, or a caller who asked for exactly the floor
        // would never be given full quality back.
        if (mLevel > 0
                && mEstimate >= Math.min(mCeiling, floorAt(mLevel - 1) * RESTORE_HEADROOM)) {
            if (mAboveRestoreSinceMillis < 0) {
                mAboveRestoreSinceMillis = nowMillis;
            }
            if (nowMillis - mAboveRestoreSinceMillis >= mRestoreAfterMillis
                    && nowMillis - mLastLevelChangeMillis >= mRestoreAfterMillis) {
                mLevel--;
                mLastLevelChangeMillis = nowMillis;
                mLastChangeWasRestore = true;
                mAboveRestoreSinceMillis = -1;
            }
        } else {
            mAboveRestoreSinceMillis = -1;
        }
    }

    private boolean isOverused() {
        if (mDropped) {
            return true;
        }
        if (mStandingQueue > OVERUSE_QUEUE_SHARE) {
            return true;
        }
        return queueingDelay() > OVERUSE_DELAY_MILLIS;
    }

    private boolean isClear() {
        if (mStandingQueue < 0 || mStandingQueue > CLEAR_QUEUE_SHARE) {
            return false;
        }
        long delay = queueingDelay();
        return delay < 0 || delay < CLEAR_DELAY_MILLIS;
    }

    /** How much later the latest acknowledgement was than the quickest recent one, or -1. */
    private long queueingDelay() {
        if (mLatestDelay < 0 || mDelays.isEmpty()) {
            return -1;
        }
        return mLatestDelay - mDelays.peekFirst()[1];
    }

    /** Bits a second delivered over the last window, or 0 when there is too little history. */
    private double throughput(long nowMillis) {
        if (mDelivered.size() < 2) {
            return 0;
        }
        long[] newest = mDelivered.peekLast();
        long[] oldest = null;
        for (long[] sample : mDelivered) {
            if (newest[0] - sample[0] <= THROUGHPUT_WINDOW_MILLIS) {
                oldest = sample;
                break;
            }
        }
        if (oldest == null || oldest == newest) {
            oldest = mDelivered.peekFirst();
        }
        // The newest sample may be stale if the reader stopped altogether, and a reader that
        // stopped is delivering nothing, so the span runs to now.
        long span = nowMillis - oldest[0];
        if (span < MIN_THROUGHPUT_SPAN_MILLIS) {
            return 0;
        }
        return (newest[1] - oldest[1]) * 8.0 * 1000 / span;
    }

    /** The floor at a level: the bits a frame gets per pixel at full quality, kept. */
    private double floorAt(int level) {
        Level step = mLevels.get(level);
        return mFloor * step.frameRateScale * step.areaScale;
    }

    /** The bit rate to encode at now. */
    @AnyThread
    synchronized int getBitRate() {
        return (int) Math.round(Math.min(mCeiling, Math.max(mEstimate, floorAt(mLevel))));
    }

    /** What the link is thought to carry, in bits a second; below the floor when it is. */
    @AnyThread
    synchronized int getEstimate() {
        return (int) Math.round(mEstimate);
    }

    /** Which step of the ladder the stream is on; 0 is full quality. */
    @AnyThread
    synchronized int getLevelIndex() {
        return mLevel;
    }

    /** The step of the ladder the stream is on. */
    @NonNull
    @AnyThread
    synchronized Level getLevel() {
        return mLevels.get(mLevel);
    }

    /** How many steps the ladder has, full quality included. */
    @AnyThread
    int getLevelCount() {
        return mLevels.size();
    }

    /** The most the stream sends. */
    @AnyThread
    int getCeiling() {
        return mCeiling;
    }

    /** The least the stream sends at full quality. */
    @AnyThread
    int getFloor() {
        return mFloor;
    }
}
