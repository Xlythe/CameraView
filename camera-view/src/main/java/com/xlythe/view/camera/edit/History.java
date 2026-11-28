package com.xlythe.view.camera.edit;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Undo and redo over whole snapshots of an immutable value, as Muse's ink history keeps them:
 * snapshots share what did not change, and can never drift from the value the way hand-written
 * inverse edits can.
 *
 * <p>A gesture is one step however many samples it took: its first change commits and the rest
 * amend. A gesture that turns out to be a palm is rolled back, leaving nothing to redo.</p>
 *
 * @param <T> the immutable value
 */
final class History<T> {
    /** The steps kept before the oldest falls off. */
    static final int DEFAULT_CAPACITY = 100;

    /** What happened to the steps, as a {@link Listener} hears it. */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({EVENT_COMMIT, EVENT_AMEND, EVENT_ROLLBACK, EVENT_UNDO, EVENT_REDO, EVENT_RESET,
            EVENT_DROP_OLDEST})
    @interface Event {}

    /** A new step was taken, and nothing can be redone. */
    static final int EVENT_COMMIT = 0;
    /** The last step changed, and nothing can be redone. */
    static final int EVENT_AMEND = 1;
    /** The last step was taken back and forgotten. */
    static final int EVENT_ROLLBACK = 2;
    /** The last step was taken back, to be redone. */
    static final int EVENT_UNDO = 3;
    /** The last step taken back was taken again. */
    static final int EVENT_REDO = 4;
    /** Every step was forgotten. */
    static final int EVENT_RESET = 5;
    /** The oldest step was forgotten, to keep within the capacity. */
    static final int EVENT_DROP_OLDEST = 6;

    /** Hears each change to the steps, such as to keep one order across several histories. */
    interface Listener {
        void onHistoryEvent(@Event int event);
    }

    /** The most undo steps kept. */
    private final int mCapacity;
    /** Earlier values, newest last. */
    private final Deque<T> mUndo = new ArrayDeque<>();
    /** Undone values, newest last. */
    private final Deque<T> mRedo = new ArrayDeque<>();
    /** The value as it stands. */
    @NonNull private T mCurrent;
    /** Told about each change to the steps, or null. */
    @Nullable private Listener mListener;

    History(@NonNull T initial, int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Keep at least one step: " + capacity);
        }
        mCurrent = initial;
        mCapacity = capacity;
    }

    @NonNull
    T current() {
        return mCurrent;
    }

    boolean canUndo() {
        return !mUndo.isEmpty();
    }

    boolean canRedo() {
        return !mRedo.isEmpty();
    }

    /**
     * Makes a value current as a new step, abandoning what could be redone. The same value
     * again is no step at all.
     */
    void commit(@NonNull T value) {
        if (value == mCurrent) {
            return;
        }
        mUndo.addLast(mCurrent);
        boolean dropped = mUndo.size() > mCapacity;
        if (dropped) {
            mUndo.removeFirst();
        }
        mRedo.clear();
        mCurrent = value;
        if (dropped) {
            tell(EVENT_DROP_OLDEST);
        }
        tell(EVENT_COMMIT);
    }

    /** Replaces the current value without a new step, for the rest of a gesture. */
    void amend(@NonNull T value) {
        if (value == mCurrent) {
            return;
        }
        mRedo.clear();
        mCurrent = value;
        tell(EVENT_AMEND);
    }

    /** Steps back and forgets the step, for a gesture that was never meant. */
    @NonNull
    T rollback() {
        if (!mUndo.isEmpty()) {
            mCurrent = mUndo.removeLast();
            tell(EVENT_ROLLBACK);
        }
        return mCurrent;
    }

    @NonNull
    T undo() {
        if (!mUndo.isEmpty()) {
            mRedo.addLast(mCurrent);
            mCurrent = mUndo.removeLast();
            tell(EVENT_UNDO);
        }
        return mCurrent;
    }

    @NonNull
    T redo() {
        if (!mRedo.isEmpty()) {
            mUndo.addLast(mCurrent);
            mCurrent = mRedo.removeLast();
            tell(EVENT_REDO);
        }
        return mCurrent;
    }

    /** Starts again from a value, with nothing to undo or redo. */
    void reset(@NonNull T value) {
        mUndo.clear();
        mRedo.clear();
        mCurrent = value;
        tell(EVENT_RESET);
    }

    /** Hears each change to the steps from now on, or none for null. */
    void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    private void tell(@Event int event) {
        if (mListener != null) {
            mListener.onHistoryEvent(event);
        }
    }
}
