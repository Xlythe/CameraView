package com.xlythe.view.camera.test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.graphics.Bitmap;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.rule.GrantPermissionRule;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.VideoView;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A recording comes out the way its preview showed it: mirrored from the front camera, as it is
 * from the back.
 *
 * <p>Nothing is drawn into the frame, so the scene is whatever the camera sees, and the test
 * compares rather than looks for a mark. A frame of the recording, shown the way its file says to
 * show it, is set against the preview as it was on screen during the recording, and against that
 * preview mirrored. Each is cut to the same shape, reduced to a coarse grid of brightness and
 * correlated. A recording that matches its preview correlates with it far better than with its
 * mirror image, and one that is backwards does the opposite. A scene that looks much the same
 * either way round - a blank ceiling, a lens against a desk - decides nothing, and the test is
 * skipped rather than passed. The emulator's cameras show a fixed, lopsided scene, which decides
 * it every time.</p>
 *
 * <p>The preview is the reference, not the stored frames, because the rule is about what the
 * person saw. The camera's own frames are unmirrored either way, so the comparison also says the
 * file is mirrored relative to them.</p>
 *
 * <p>The old API is left out. It hands its buffers straight to the encoder with nowhere to draw
 * them, so its front recordings are unmirrored, and its confirmation flips them for display.</p>
 */
@RunWith(Parameterized.class)
public final class FrontRecordingMirrorDeviceTest {
    private static final String TAG = "RecordingMirror";

    private static final long TIMEOUT_SECONDS = 30;

    /** Long enough for exposure and white balance to settle before anything is compared. */
    private static final long SETTLE_MILLIS = 1_500;

    /** How far into the recording the preview is captured, and the frame read back. */
    private static final long COMPARED_AT_MILLIS = 1_500;

    /** The grid the frames are compared at, coarse enough that two captures of a scene agree. */
    private static final int GRID = 16;

    /** How much better the closer of the two must correlate before the scene decides anything. */
    private static final double DECISIVE_MARGIN = 0.15;

    /** How well the closer of the two must correlate before they count as the same scene. */
    private static final double SAME_SCENE = 0.5;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<CameraView.Backend> backends() {
        return Arrays.asList(CameraView.Backend.CAMERA2, CameraView.Backend.CAMERA_X);
    }

    @Rule
    public final GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
    );

    private final CameraView.Backend backend;

    public FrontRecordingMirrorDeviceTest(CameraView.Backend backend) {
        this.backend = backend;
    }

    @Before
    public void chooseTheBackend() {
        DeviceState.assumeATestableCamera();
        CameraView.setDefaultBackend(backend);
    }

    @After
    public void restoreTheDefault() {
        CameraView.setDefaultBackend(CameraView.Backend.AUTO);
    }

    /**
     * The claim: a front recording is mirrored as its preview was, and the backend says so.
     *
     * <p>What the backend says is read off the confirmation, which flips a front file for display
     * exactly when the backend reports it unmirrored - so a confirmation that plays it as it is
     * means {@code isFrontVideoMirrored()} answered true.</p>
     */
    @Test
    public void aFrontRecordingIsMirroredLikeItsPreview() throws Exception {
        Recording recording = record(CameraView.LensFacing.FRONT);

        // A file reported unmirrored is right only where GL will not run, which leaves the camera
        // writing the recording itself as it always did.
        assertFalse("the front recording was reported unmirrored although GL runs here",
                recording.confirmationMirrored && recording.glRuns);
        Assume.assumeFalse("GL will not run here, so the recording is unmirrored and its"
                + " confirmation flips it", recording.confirmationMirrored);
        assertLooksLikeItsPreview(recording);
    }

    /** And a back recording is still not mirrored, which is also what its preview showed. */
    @Test
    public void aBackRecordingIsNotMirrored() throws Exception {
        Recording recording = record(CameraView.LensFacing.BACK);

        assertFalse("a back recording should never be flipped for its confirmation",
                recording.confirmationMirrored);
        assertLooksLikeItsPreview(recording);
    }

    private void assertLooksLikeItsPreview(@NonNull Recording recording) {
        Bitmap preview = recording.preview;
        Bitmap frame = ShownFrames.centredTo(
                recording.frame, preview.getWidth(), preview.getHeight());
        float[] shown = ShownFrames.brightness(preview, GRID, GRID);
        float[] recorded = ShownFrames.brightness(frame, GRID, GRID);
        double same = ShownFrames.correlation(recorded, shown);
        double mirrored = ShownFrames.correlation(
                recorded, ShownFrames.mirrored(shown, GRID, GRID));
        String scores = String.format(Locale.US,
                "(correlation with the preview %.2f, with its mirror image %.2f)", same, mirrored);
        Log.i(TAG, backend + " " + recording.lensFacing + " " + scores);

        Assume.assumeTrue("the scene looks the same either way round, so it cannot say which way"
                        + " the recording is " + scores,
                Math.abs(same - mirrored) > DECISIVE_MARGIN
                        && Math.max(same, mirrored) > SAME_SCENE);
        assertTrue("the " + recording.lensFacing + " recording is the mirror image of its"
                + " preview " + scores, same > mirrored);
    }

    /** What one recording left behind, and what was on screen while it was made. */
    private static final class Recording {
        CameraView.LensFacing lensFacing;
        Bitmap preview;
        Bitmap frame;
        /** Whether the confirmation flipped the file for display. */
        boolean confirmationMirrored;
        /** Whether the backend could still draw frames afterwards, which is whether GL runs. */
        boolean glRuns;
    }

    @NonNull
    private Recording record(@NonNull CameraView.LensFacing lensFacing) throws Exception {
        File file = File.createTempFile("mirror", ".mp4",
                ApplicationProvider.getApplicationContext().getCacheDir());
        Recording recording = new Recording();
        recording.lensFacing = lensFacing;
        try (ActivityScenario<CameraTestActivity> scenario =
                     ActivityScenario.launch(CameraTestActivity.class)) {
            CountDownLatch opened = new CountDownLatch(1);
            CountDownLatch confirmed = new CountDownLatch(1);
            AtomicReference<CameraView> held = new AtomicReference<>();
            scenario.onActivity(activity -> {
                CameraView camera = activity.getCameraView();
                held.set(camera);
                camera.setLensFacing(lensFacing);
                camera.setFlash(CameraView.Flash.OFF);
                // The confirmation is where the library acts on whether the file is mirrored,
                // so it is on, and the test reads what it did.
                camera.setVideoConfirmationEnabled(true);
                camera.setOnVideoCapturedListener(new CameraView.OnVideoCapturedListener() {
                    @Override public void onVideoConfirmation() {
                        confirmed.countDown();
                    }

                    @Override public void onVideoCaptured(File saved) {}

                    @Override public void onFailure() {
                        confirmed.countDown();
                    }
                });
                camera.setOnCameraStateChangedListener(
                        new CameraView.OnCameraStateChangedListener() {
                            @Override public void onCameraOpened() {
                                opened.countDown();
                            }

                            @Override public void onCameraClosed() {}
                        });
                if (camera.isOpen()) {
                    opened.countDown();
                } else {
                    camera.open();
                }
            });
            assertTrue("the camera did not open " + DeviceState.whyTheCameraMightNotOpen(),
                    opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            CameraView camera = held.get();
            assertNotNull(camera);
            // Asked once the camera is open, because CameraX knows its lenses only then.
            AtomicBoolean hasLens = new AtomicBoolean();
            scenario.onActivity(activity ->
                    hasLens.set(activity.getCameraView().getLensFacing() == lensFacing));
            Assume.assumeTrue("this device has no " + lensFacing + " camera", hasLens.get());
            Thread.sleep(SETTLE_MILLIS);

            scenario.onActivity(activity -> activity.getCameraView().startRecording(file));
            Thread.sleep(COMPARED_AT_MILLIS);
            recording.preview = ShownFrames.onScreen(camera);
            Thread.sleep(COMPARED_AT_MILLIS);
            scenario.onActivity(activity -> activity.getCameraView().stopRecording());
            assertTrue("the recording never reported",
                    confirmed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            scenario.onActivity(activity -> {
                CameraView view = activity.getCameraView();
                VideoView confirmation = videoConfirmation(view);
                recording.confirmationMirrored = confirmation.isMirrored();
                recording.glRuns = view.isFrameEffectSupported();
                view.setVideoConfirmationEnabled(false);
                view.close();
            });
        }
        Assume.assumeTrue("this device's encoder produced no video", file.length() > 0);
        recording.frame = ShownFrames.ofRecording(file, COMPARED_AT_MILLIS * 1_000);
        Assume.assumeTrue("no frame could be decoded from the recording", recording.frame != null);
        return recording;
    }

    /** The player CameraView shows a recording in while it waits to be confirmed. */
    @NonNull
    private static VideoView videoConfirmation(@NonNull ViewGroup camera) {
        for (int i = 0; i < camera.getChildCount(); i++) {
            View child = camera.getChildAt(i);
            if (child instanceof VideoView && child.getVisibility() == View.VISIBLE) {
                return (VideoView) child;
            }
        }
        throw new AssertionError("the recording was never offered for confirmation");
    }
}
