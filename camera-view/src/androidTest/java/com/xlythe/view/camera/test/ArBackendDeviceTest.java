package com.xlythe.view.camera.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.graphics.PointF;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.WorldOverlay;
import com.xlythe.view.camera.ar.ArFrame;
import com.xlythe.view.camera.ar.ArFrameListener;
import com.xlythe.view.camera.ar.ArTrackingFailure;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Whether the AR backend opens the camera and reports a pose that means something.
 *
 * <p>The part of AR that cannot be reasoned about. A shared-camera session either configures or
 * it does not, and when it does not it fails by tracking nothing rather than by throwing - the
 * surfaces are all present, the capture session reports configured, and every frame comes back
 * untracked. So the assertion that matters is not that the camera opened but that a pose
 * arrived, and that it is a pose of something: a session that is wired up wrongly reports the
 * identity matrix forever.</p>
 *
 * <p>Needs a device certified for AR with ARCore installed, and a room with enough texture to
 * track in. The whole class skips where AR did not start, because a device that cannot do AR is
 * a real configuration and the fallback to Camera2 is tested in the backend matrix.</p>
 */
@RunWith(AndroidJUnit4.class)
public final class ArBackendDeviceTest {
    private static final String TAG = "ArBackendDeviceTest";

    private static final long OPEN_TIMEOUT_SECONDS = 30;

    /**
     * How long to wait for tracking, which is not the same as waiting for a frame.
     *
     * <p>ARCore needs a few seconds and some movement before it believes it knows where it is,
     * and a phone lying still on a desk can take most of this. Shorter than this and the test
     * reports a wiring fault on a device that was only being patient.</p>
     */
    private static final long TRACKING_TIMEOUT_SECONDS = 25;

    @Rule
    public final GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.CAMERA);

    @Before
    public void useTheArBackend() {
        DeviceState.assumeATestableCamera();
        CameraView.setDefaultBackend(CameraView.Backend.AR);
    }

    @After
    public void restoreTheDefault() {
        CameraView.setDefaultBackend(CameraView.Backend.AUTO);
    }

    /** Collects what arrives, on the render thread, without holding a frame past its callback. */
    private static final class Poses implements ArFrameListener {
        private final CountDownLatch mTracked = new CountDownLatch(1);
        private final AtomicInteger mFrames = new AtomicInteger();
        private final AtomicReference<ArTrackingFailure> mFailure = new AtomicReference<>();
        private final AtomicBoolean mHadDepth = new AtomicBoolean();
        private final AtomicReference<float[]> mViewMatrix = new AtomicReference<>();
        private final AtomicReference<float[]> mViewProjection = new AtomicReference<>();

        @Override
        public void onArFrame(@NonNull ArFrame frame) {
            mFrames.incrementAndGet();
            if (frame.getDepth() != null) {
                mHadDepth.set(true);
            }
            // Copied, because the arrays belong to the frame and are overwritten next time.
            mViewMatrix.set(frame.getViewMatrix().clone());
            mViewProjection.set(frame.getViewProjectionMatrix().clone());
            mTracked.countDown();
        }

        @Override
        public void onArNotTracking(@NonNull ArTrackingFailure reason) {
            mFailure.set(reason);
        }
    }

    /**
     * The claim: asking for AR gets AR, and it tracks.
     *
     * <p>Four things at once because they need one opened camera between them and opening it is
     * most of the runtime: that the backend reports itself, that poses arrive, that they are
     * poses rather than identity matrices, and that a point in front of the camera projects into
     * the view.</p>
     */
    @Test
    public void theArBackendTracksTheWorld() throws Exception {
        Poses poses = new Poses();

        try (ActivityScenario<CameraTestActivity> scenario =
                     ActivityScenario.launch(CameraTestActivity.class)) {
            CountDownLatch opened = new CountDownLatch(1);
            AtomicReference<CameraView> held = new AtomicReference<>();
            AtomicReference<CameraView.Backend> backend = new AtomicReference<>();
            scenario.onActivity(activity -> {
                CameraView camera = activity.getCameraView();
                held.set(camera);
                camera.setArFrameListener(poses);
                camera.setOnCameraStateChangedListener(
                        new CameraView.OnCameraStateChangedListener() {
                            @Override public void onCameraOpened() {
                                opened.countDown();
                            }

                            @Override public void onCameraClosed() {}
                        });
                if (camera.isOpen()) opened.countDown();
                else camera.open();
            });

            assertTrue("the camera did not open " + DeviceState.whyTheCameraMightNotOpen(),
                    opened.await(OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            CameraView camera = held.get();
            assertNotNull(camera);
            DeviceState.turnTheFlashOff(camera);
            // Read after opening, because whether a session started is only known by then.
            scenario.onActivity(activity -> backend.set(activity.getCameraView().getBackend()));

            try {
                Assume.assumeTrue("AR did not start on this device, so there is nothing to test",
                        backend.get() == CameraView.Backend.AR);

                boolean tracked = poses.mTracked.await(
                        TRACKING_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                ArTrackingFailure failure = poses.mFailure.get();
                Log.i(TAG, "frames " + poses.mFrames.get()
                        + ", failure " + failure
                        + ", depth " + poses.mHadDepth.get());

                // The session reporting a reason is the session working: the pipeline reached
                // ARCore, it looked at the room, and the room is the problem. A dark desk at
                // night is the usual one and is not something the library can fix, so it is a
                // skip - but only for the reasons that are genuinely about the room. BAD_STATE
                // and CAMERA_UNAVAILABLE are about the wiring and still fail.
                if (!tracked) {
                    Assume.assumeFalse("ARCore cannot track here: " + failure
                                    + ". Point the camera at a lit, textured scene and move it"
                                    + " a little, then run again",
                            failure == ArTrackingFailure.INSUFFICIENT_LIGHT
                                    || failure == ArTrackingFailure.INSUFFICIENT_FEATURES
                                    || failure == ArTrackingFailure.EXCESSIVE_MOTION);
                }
                assertTrue("no tracked frame arrived in " + TRACKING_TIMEOUT_SECONDS + "s and"
                                + " the session gave no reason to blame the room (" + failure
                                + "). A shared-camera session that is wired up wrongly"
                                + " configures cleanly and then tracks nothing, so this is the"
                                + " symptom to look at the surface list for",
                        tracked);

                float[] viewMatrix = poses.mViewMatrix.get();
                assertNotNull(viewMatrix);
                assertEquals("a view matrix is 16 floats", 16, viewMatrix.length);
                assertTrue("the view matrix is the identity, which is what a session that never"
                                + " located itself reports",
                        !isIdentity(viewMatrix));

                assertPointsInFrontProjectIntoTheView(poses.mViewProjection.get(),
                        camera.getWidth(), camera.getHeight());
            } finally {
                scenario.onActivity(activity -> {
                    activity.getCameraView().setArFrameListener(null);
                    activity.getCameraView().close();
                });
            }
        }
    }

    /**
     * A point a metre in front of the camera lands on screen, and one behind it does not.
     *
     * <p>What makes the matrix usable rather than merely present. The overlay is the only thing
     * an app sees of the pose, so the thing worth asserting is that it answers sensibly - and
     * the point behind the camera is half of it: a projection applied without the check lands
     * things that are behind you in the middle of the view.</p>
     */
    private static void assertPointsInFrontProjectIntoTheView(
            @NonNull float[] viewProjection, int viewWidth, int viewHeight) {
        assertNotNull(viewProjection);
        Assume.assumeTrue("the view was never laid out", viewWidth > 0 && viewHeight > 0);

        // The world origin is where the session started, which is where the device was. A metre
        // along the session's negative z from there is in front of where it was pointed.
        PointF at = new PointF();
        boolean inFront = WorldOverlay.project(
                viewProjection, 0f, 0f, -1f, viewWidth, viewHeight, at);
        boolean behind = WorldOverlay.project(
                viewProjection, 0f, 0f, 1f, viewWidth, viewHeight, new PointF());

        // Not asserted as on-screen: the device has been moved by the time this runs, so the
        // point may genuinely be out of frame. What is asserted is that exactly one of the two
        // directions projects, which no amount of moving changes.
        assertTrue("neither direction projected, so the matrix is not a projection",
                inFront || behind);
        assertTrue("both directions projected, so the w check is not being applied",
                inFront != behind);
        Log.i(TAG, "a point in front projects to " + at.x + ", " + at.y);
    }

    private static boolean isIdentity(@NonNull float[] matrix) {
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                float expected = column == row ? 1f : 0f;
                if (Math.abs(matrix[column * 4 + row] - expected) > 1e-6f) {
                    return false;
                }
            }
        }
        return true;
    }
}
