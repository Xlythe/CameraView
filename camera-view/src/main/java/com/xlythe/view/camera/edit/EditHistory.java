package com.xlythe.view.camera.edit;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One undo and redo across the editing views, so undo takes back whatever was done last, a
 * stroke or a sticker's move, in whichever view it was done.
 *
 * <p>Each view keeps its own steps; this keeps the order they were taken in. Attach each view
 * once, then undo and redo here. Undoing on a view itself, or showing it something new with
 * nothing to undo, keeps this in step.</p>
 */
public final class EditHistory {
    /** A view whose steps are kept in order. */
    private static final class Layer {
        /** Takes back the view's last step, through the view so it redraws. */
        @NonNull final Runnable undo;
        /** Takes the view's last undone step again. */
        @NonNull final Runnable redo;

        Layer(@NonNull Runnable undo, @NonNull Runnable redo) {
            this.undo = undo;
            this.redo = redo;
        }
    }

    /** Which view took each step that can be undone, oldest first. */
    private final List<Layer> mUndo = new ArrayList<>();
    /** Which view each undone step belongs to, the next to redo last. */
    private final List<Layer> mRedo = new ArrayList<>();
    /** Told when what can be undone or redone changes. */
    @Nullable private Runnable mListener;

    /** Starts with nothing to undo. */
    public EditHistory() {}

    /** Keeps a drawing's strokes and erases in order with the other views' steps. */
    @MainThread
    public void attach(@NonNull DrawingView view) {
        attach(view.history(), view::undo, view::redo);
    }

    /** Keeps the stickers' and text's changes in order with the other views' steps. */
    @MainThread
    public void attach(@NonNull OverlayView view) {
        attach(view.history(), view::undo, view::redo);
    }

    private void attach(@NonNull History<?> history, @NonNull Runnable undo,
            @NonNull Runnable redo) {
        Layer layer = new Layer(undo, redo);
        history.setListener(event -> onEvent(layer, event));
    }

    /** Takes back the last step, in whichever view it was taken. */
    @MainThread
    public void undo() {
        if (!mUndo.isEmpty()) {
            mUndo.get(mUndo.size() - 1).undo.run();
        }
    }

    /** Takes the last step taken back again. */
    @MainThread
    public void redo() {
        if (!mRedo.isEmpty()) {
            mRedo.get(mRedo.size() - 1).redo.run();
        }
    }

    @MainThread
    public boolean canUndo() {
        return !mUndo.isEmpty();
    }

    @MainThread
    public boolean canRedo() {
        return !mRedo.isEmpty();
    }

    /** Hears what can be undone or redone change, such as to enable undo and redo buttons. */
    @MainThread
    public void setOnHistoryChangedListener(@Nullable Runnable listener) {
        mListener = listener;
    }

    /** Follows a view's own steps. */
    private void onEvent(@NonNull Layer layer, @History.Event int event) {
        switch (event) {
            case History.EVENT_COMMIT:
                mUndo.add(layer);
                mRedo.clear();
                break;
            case History.EVENT_AMEND:
                // The view can no longer redo.
                removeAll(mRedo, layer);
                break;
            case History.EVENT_ROLLBACK:
                removeLast(mUndo, layer);
                break;
            case History.EVENT_UNDO:
                removeLast(mUndo, layer);
                mRedo.add(layer);
                break;
            case History.EVENT_REDO:
                removeLast(mRedo, layer);
                mUndo.add(layer);
                break;
            case History.EVENT_RESET:
                removeAll(mUndo, layer);
                removeAll(mRedo, layer);
                break;
            case History.EVENT_DROP_OLDEST:
                mUndo.remove(layer);
                break;
            default:
                return;
        }
        if (mListener != null) {
            mListener.run();
        }
    }

    private static void removeLast(@NonNull List<Layer> layers, @NonNull Layer layer) {
        int index = layers.lastIndexOf(layer);
        if (index >= 0) {
            layers.remove(index);
        }
    }

    private static void removeAll(@NonNull List<Layer> layers, @NonNull Layer layer) {
        layers.removeAll(Collections.singleton(layer));
    }
}
