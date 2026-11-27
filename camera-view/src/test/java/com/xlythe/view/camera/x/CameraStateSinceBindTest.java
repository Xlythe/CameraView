package com.xlythe.view.camera.x;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.camera.core.CameraState;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Which of a camera's states are about the use cases bound now.
 *
 * <p>The sequence these tests replay is the one a device run recorded: a test closed its camera
 * while a resize was rebinding it, the rebind failed to configure an output whose surface had
 * gone, and the next test's bind observed that stream-configuration error first and reported its
 * own camera as failed, although CameraX went on to open it for the new use cases.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class CameraStateSinceBindTest {
    /** A critical error left by an earlier session's configuration failure. */
    private static final CameraState STREAM_CONFIG_FAILED = CameraState.create(
            CameraState.Type.CLOSING,
            CameraState.StateError.create(CameraState.ERROR_STREAM_CONFIG));

    /** The error the camera is first seen in is about whatever was bound before. */
    @Test
    public void theErrorTheCameraIsInAtBindIsNotAboutTheBind() {
        CameraStateSinceBind sinceBind = new CameraStateSinceBind(STREAM_CONFIG_FAILED);

        assertFalse(sinceBind.isAboutTheBind(STREAM_CONFIG_FAILED));
    }

    /**
     * Nor is one the old session's teardown posts after the bind, before the camera has begun
     * opening for the new use cases.
     */
    @Test
    public void anErrorBeforeTheCameraOpensForTheBindIsNotAboutIt() {
        CameraStateSinceBind sinceBind = new CameraStateSinceBind(STREAM_CONFIG_FAILED);

        assertFalse(sinceBind.isAboutTheBind(CameraState.create(
                CameraState.Type.PENDING_OPEN,
                CameraState.StateError.create(CameraState.ERROR_STREAM_CONFIG))));
    }

    /** Once the camera is opening for the bind, everything it reports is about the bind. */
    @Test
    public void onceTheCameraOpensForTheBindItsErrorsCount() {
        CameraStateSinceBind sinceBind = new CameraStateSinceBind(STREAM_CONFIG_FAILED);

        assertTrue(sinceBind.isAboutTheBind(CameraState.create(CameraState.Type.OPENING)));
        assertTrue(sinceBind.isAboutTheBind(CameraState.create(CameraState.Type.OPEN)));
        assertTrue("a configuration that fails for these use cases is theirs to report",
                sinceBind.isAboutTheBind(CameraState.create(
                        CameraState.Type.CLOSING,
                        CameraState.StateError.create(CameraState.ERROR_STREAM_CONFIG))));
    }

    /** An open that fails is still reported, because the camera begins opening before it fails. */
    @Test
    public void aCameraThatFailsToOpenForTheBindReportsIt() {
        CameraStateSinceBind sinceBind =
                new CameraStateSinceBind(CameraState.create(CameraState.Type.CLOSED));

        assertFalse(sinceBind.isAboutTheBind(CameraState.create(CameraState.Type.PENDING_OPEN)));
        assertTrue(sinceBind.isAboutTheBind(CameraState.create(CameraState.Type.OPENING)));
        assertTrue(sinceBind.isAboutTheBind(CameraState.create(
                CameraState.Type.CLOSED,
                CameraState.StateError.create(CameraState.ERROR_CAMERA_DISABLED))));
    }

    /**
     * An opening that carries an error is not the camera opening well, so it does not make an
     * earlier session's errors count.
     */
    @Test
    public void anOpeningWithAnErrorIsNotAnOpeningForTheBind() {
        CameraStateSinceBind sinceBind = new CameraStateSinceBind(null);

        assertFalse(sinceBind.isAboutTheBind(CameraState.create(
                CameraState.Type.OPENING,
                CameraState.StateError.create(CameraState.ERROR_OTHER_RECOVERABLE_ERROR))));
    }

    /** A camera already open and well at bind needs no new open, so what follows is about it. */
    @Test
    public void aCameraAlreadyOpenAtBindReportsFromTheStart() {
        CameraState open = CameraState.create(CameraState.Type.OPEN);
        CameraStateSinceBind sinceBind = new CameraStateSinceBind(open);

        assertTrue(sinceBind.isAboutTheBind(open));
        assertTrue(sinceBind.isAboutTheBind(CameraState.create(
                CameraState.Type.CLOSING,
                CameraState.StateError.create(CameraState.ERROR_CAMERA_FATAL_ERROR))));
    }
}
