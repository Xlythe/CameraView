package com.xlythe.view.camera.edit;

import androidx.annotation.NonNull;

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

    /** The most undo steps kept. */
    private final int mCapacity;
    /** Earlier values, newest last. */
    private final Deque<T> mUndo = new ArrayDeque<>();
    /** Undone values, newest last. */
    private final Deque<T> mRedo = new ArrayDeque<>();
    /** The value as it stands. */
    @NonNull private T mCurrent;

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
        if (mUndo.size() > mCapacity) {
            mUndo.removeFirst();
        }
        mRedo.clear();
        mCurrent = value;
    }

    /** Replaces the current value without a new step, for the rest of a gesture. */
    void amend(@NonNull T value) {
        if (value == mCurrent) {
            return;
        }
        mRedo.clear();
        mCurrent = value;
    }

    /** Steps back and forgets the step, for a gesture that was never meant. */
    @NonNull
    T rollback() {
        if (!mUndo.isEmpty()) {
            mCurrent = mUndo.removeLast();
        }
        return mCurrent;
    }

    @NonNull
    T undo() {
        if (!mUndo.isEmpty()) {
            mRedo.addLast(mCurrent);
            mCurrent = mUndo.removeLast();
        }
        return mCurrent;
    }

    @NonNull
    T redo() {
        if (!mRedo.isEmpty()) {
            mUndo.addLast(mCurrent);
            mCurrent = mRedo.removeLast();
        }
        return mCurrent;
    }

    /** Starts again from a value, with nothing to undo or redo. */
    void reset(@NonNull T value) {
        mUndo.clear();
        mRedo.clear();
        mCurrent = value;
    }
}
