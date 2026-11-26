package com.xlythe.view.camera.ar;

import androidx.annotation.NonNull;

/**
 * Told where the camera is, every frame it is tracked.
 *
 * <p>The whole of what the library offers an AR app, and it draws nothing: where a marker goes
 * and what it looks like is the decision of the app, and a camera library that decided would be
 * in the way. {@link com.xlythe.view.camera.WorldOverlay} turns what arrives here into
 * positions in the view, including which parts of a marker the room is standing in front of.</p>
 *
 * <p>Called on the GL render thread, which is the thread the camera frame was drawn on and the
 * only one the pose belongs to. Work done here delays the preview, so an overlay drawn from a
 * View rather than in GL should post the numbers to the main thread and return.</p>
 */
public interface ArFrameListener {
    /**
     * A frame the session is tracking.
     *
     * <p>Only tracked frames arrive. A pose from an untracked frame is not inaccurate so much
     * as meaningless, and a marker drawn from one slides around the room, so an untracked frame
     * is reported through {@link #onArNotTracking} instead.</p>
     */
    void onArFrame(@NonNull ArFrame frame);

    /**
     * The world is not being tracked, and this is why.
     *
     * <p>Called when the reason changes rather than per frame, so it covers both losing
     * tracking and never having started - and most of the reasons are things only the person
     * holding the phone can fix, which is why they are reported at all. The next
     * {@link #onArFrame} is how recovery is reported.</p>
     */
    default void onArNotTracking(@NonNull ArTrackingFailure reason) {}
}
