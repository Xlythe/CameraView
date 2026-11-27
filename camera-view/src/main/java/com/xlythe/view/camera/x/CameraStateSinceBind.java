package com.xlythe.view.camera.x;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraState;

/**
 * Tells what a camera's state says about the use cases bound now from what it still says about
 * the ones bound before them.
 *
 * <p>CameraX keeps one state per camera for the life of the process, so it outlives every bind. A
 * session that ended in a critical error, such as an output whose surface went away as its host
 * closed, leaves that error as the first state the next bind observes, and the old session's
 * teardown can post it again after the new use cases are bound. Neither is about them: CameraX
 * opens the camera for the new use cases regardless. So a state counts once the camera begins
 * opening for this bind, which comes before any genuine failure to open or configure it.</p>
 */
@MainThread
final class CameraStateSinceBind {
    /** The state the camera was in when the use cases were bound, which is about earlier ones. */
    @Nullable private final CameraState mStateAtBind;
    /** Whether the camera has been opening or open, without an error, since the bind. */
    private boolean mOpeningSinceBind;

    CameraStateSinceBind(@Nullable CameraState stateAtBind) {
        mStateAtBind = stateAtBind;
        // A camera that is already open and well needs no new open for these use cases, so what
        // happens to it from here on is about them.
        mOpeningSinceBind = stateAtBind != null && isOpeningWell(stateAtBind);
    }

    /** Whether a state the camera reports is about the use cases bound now. */
    boolean isAboutTheBind(@NonNull CameraState state) {
        if (!mOpeningSinceBind && state != mStateAtBind && isOpeningWell(state)) {
            mOpeningSinceBind = true;
        }
        return mOpeningSinceBind;
    }

    private static boolean isOpeningWell(@NonNull CameraState state) {
        return state.getError() == null
                && (state.getType() == CameraState.Type.OPENING
                || state.getType() == CameraState.Type.OPEN);
    }
}
