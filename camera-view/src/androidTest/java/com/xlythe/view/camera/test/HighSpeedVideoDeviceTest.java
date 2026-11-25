package com.xlythe.view.camera.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.rule.GrantPermissionRule;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.HighSpeedProfile;

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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Slow motion, on a phone that can actually do it.
 *
 * <p>A high-speed recording is the one thing in the library that cannot be checked off a device at
 * all: Robolectric's camera2 shadows publish no high-speed configurations, no emulator reports the
 * capability, and the arrangement that makes the file play back slowly - a capture rate above the
 * frame rate - only exists inside a real MediaRecorder. So the unit tests cover the arithmetic and
 * these cover the claim: that the file is longer than the thing that was filmed.</p>
 *
 * <p>Every test here skips, loudly, on hardware that does not publish the capability, which is
 * most hardware and all emulators. A skipped run says nothing about the library; it says the
 * phone cannot do slow motion.</p>
 */
@RunWith(Parameterized.class)
public final class HighSpeedVideoDeviceTest {
    private static final String TAG = "HighSpeedVideoDeviceTest";

    /** Long enough for a camera to open, configure a high-speed session and deliver. */
    private static final long TIMEOUT_SECONDS = 30;

    /** How long to film for. The file should come back several times longer than this. */
    private static final long RECORD_MILLIS = 2_000;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<CameraView.Backend> backends() {
        return Arrays.asList(CameraView.Backend.CAMERA2, CameraView.Backend.CAMERA_X);
    }

    @Rule
    public final GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.CAMERA,
            // Granted even though a high-speed recording has no audio: startRecording is annotated
            // as needing it, and a recording that is refused for want of a permission would skip
            // these tests for the wrong reason.
            Manifest.permission.RECORD_AUDIO
    );

    private final CameraView.Backend backend;

    public HighSpeedVideoDeviceTest(CameraView.Backend backend) {
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
     * Everything the camera published can be asked for.
     *
     * <p>The probe and the setter read the same list, so this is the one thing that can be wrong
     * without a camera noticing: a published choice the library then refuses would leave an app
     * showing a frame rate that cannot be selected.</p>
     */
    @Test
    public void everyPublishedChoiceCanBeSelected() throws Exception {
        withAnOpenCamera(camera -> {
            List<HighSpeedProfile> published = camera.getSupportedHighSpeedProfiles();
            assumeHighSpeed(camera, published);
            Log.i(TAG, backend + " publishes " + published);

            for (HighSpeedProfile profile : published) {
                assertTrue(backend + " published " + profile + " and then refused it",
                        camera.setHighSpeedProfile(profile));
                assertEquals(profile, camera.getHighSpeedProfile());
            }
            assertTrue("ordinary speed is always available", camera.setHighSpeedProfile(null));
        });
    }

    /** Where the capability is reported, there is something to select; where it is not, nothing. */
    @Test
    public void theProbeAgreesWithTheList() throws Exception {
        withAnOpenCamera(camera -> {
            boolean supported = camera.isHighSpeedRecordingSupported();
            boolean hasChoices = !camera.getSupportedHighSpeedProfiles().isEmpty();
            Log.i(TAG, backend + " high speed supported=" + supported
                    + ", emulator=" + DeviceState.isEmulator());

            assertEquals(backend + " disagrees with itself about high speed",
                    supported, hasChoices);
        });
    }

    /**
     * The file is longer than the thing that was filmed.
     *
     * <p>Which is the whole claim. A recording whose capture rate and frame rate were left equal
     * comes back the same length as the time spent filming, however fast the sensor was run, and
     * nothing else in the suite would tell the difference.</p>
     */
    @Test
    public void aSlowMotionRecordingIsLongerThanItTookToFilm() throws Exception {
        Recording recording = recordAtTheFastestRate();
        Assume.assumeTrue("no high-speed recording to measure", recording != null);

        long duration = durationMillis(recording.file);
        int slowdown = recording.profile.getSlowdown(30);
        Log.i(TAG, backend + " filmed " + RECORD_MILLIS + "ms at " + recording.profile
                + " and wrote " + duration + "ms, expecting about " + slowdown + "x");

        // Half the expected stretch, not all of it: the camera takes a moment to reach its rate
        // and the first frames are written at whatever it managed, so the file is always a little
        // short of the arithmetic. Anything at or near 1x is a recording that is not slow motion.
        assertTrue(backend + " wrote " + duration + "ms from " + RECORD_MILLIS
                        + "ms of filming at " + recording.profile + ", which is not slow motion",
                duration > RECORD_MILLIS * slowdown / 2);
    }

    /**
     * And it declares a frame rate a player can show.
     *
     * <p>The other half of the arrangement: a file tagged at the capture rate would be correctly
     * timed and unplayable, so the track has to claim the playback rate.</p>
     */
    @Test
    public void aSlowMotionRecordingDeclaresThePlaybackRate() throws Exception {
        Recording recording = recordAtTheFastestRate();
        Assume.assumeTrue("no high-speed recording to measure", recording != null);

        MediaFormat format = videoTrack(recording.file);
        Assume.assumeTrue("this file declares no frame rate to check",
                format.containsKey(MediaFormat.KEY_FRAME_RATE));
        int declared = format.getInteger(MediaFormat.KEY_FRAME_RATE);
        Log.i(TAG, backend + " declared " + declared + "fps for a "
                + recording.profile.getFrameRate() + "fps capture");

        assertTrue(backend + " declared " + declared + "fps, which is the rate it captured at"
                        + " rather than a rate anything plays",
                declared < recording.profile.getFrameRate());
    }

    /**
     * A picture asked for during a high-speed recording fails, and the recording survives.
     *
     * <p>The session has room for the preview and the recording and nothing else, so there is no
     * still to capture. The library reports a failure rather than tearing the session down, which
     * would end the recording the caller asked for, or queueing the picture until the recording
     * stops, which would leave a caller waiting on a callback indefinitely.</p>
     */
    @Test
    public void aPictureDuringASlowMotionRecordingFailsWithoutLosingTheRecording() throws Exception {
        File video = newFile("highspeed", ".mp4");
        File picture = newFile("highspeed", ".jpg");
        CountDownLatch pictureSettled = new CountDownLatch(1);
        AtomicBoolean pictureSucceeded = new AtomicBoolean();
        AtomicReference<File> recorded = new AtomicReference<>();
        CountDownLatch recordingFinished = new CountDownLatch(1);

        withAnOpenCamera(camera -> {
            assumeHighSpeed(camera, camera.getSupportedHighSpeedProfiles());
            HighSpeedProfile fastest =
                    HighSpeedProfile.fastest(camera.getSupportedHighSpeedProfiles());
            Assume.assumeTrue(backend + " would not select " + fastest,
                    camera.setHighSpeedProfile(fastest));

            camera.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
                @Override public void onImageConfirmation() {}

                @Override public void onImageCaptured(File saved) {
                    pictureSucceeded.set(true);
                    pictureSettled.countDown();
                }

                @Override public void onFailure() {
                    pictureSettled.countDown();
                }
            });
            camera.setOnVideoCapturedListener(new CameraView.OnVideoCapturedListener() {
                @Override public void onVideoConfirmation() {}

                @Override public void onVideoCaptured(File saved) {
                    recorded.set(saved);
                    recordingFinished.countDown();
                }

                @Override public void onFailure() {
                    recordingFinished.countDown();
                }
            });

            camera.startRecording(video);
            Thread.sleep(RECORD_MILLIS / 2);
            camera.takePicture(picture);
            assertTrue(backend + " never reported what became of the picture, which is a caller"
                            + " left waiting rather than told no",
                    pictureSettled.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            Thread.sleep(RECORD_MILLIS / 2);
            camera.stopRecording();
            assertTrue(backend + " never reported the recording",
                    recordingFinished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            camera.setHighSpeedProfile(null);
        });

        assertFalse(backend + " took a picture out of a session with no still output in it,"
                        + " which means the session was not the high-speed one",
                pictureSucceeded.get());
        assertNotNull(backend + " lost the recording over a picture it could not take",
                recorded.get());
        assertTrue("the recording is empty", recorded.get().length() > 0);
    }

    /** What a finished high-speed recording came out as, or null when the phone cannot do one. */
    private static final class Recording {
        final File file;
        final HighSpeedProfile profile;

        Recording(File file, HighSpeedProfile profile) {
            this.file = file;
            this.profile = profile;
        }
    }

    /** Records a couple of seconds at the fastest rate this camera publishes. */
    private Recording recordAtTheFastestRate() throws Exception {
        File file = newFile("highspeed", ".mp4");
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<File> recorded = new AtomicReference<>();
        AtomicReference<HighSpeedProfile> selected = new AtomicReference<>();

        withAnOpenCamera(camera -> {
            List<HighSpeedProfile> published = camera.getSupportedHighSpeedProfiles();
            assumeHighSpeed(camera, published);
            HighSpeedProfile fastest = HighSpeedProfile.fastest(published);
            Assume.assumeTrue(backend + " would not select " + fastest,
                    camera.setHighSpeedProfile(fastest));
            selected.set(fastest);

            camera.setOnVideoCapturedListener(new CameraView.OnVideoCapturedListener() {
                @Override public void onVideoConfirmation() {}

                @Override public void onVideoCaptured(File saved) {
                    recorded.set(saved);
                    finished.countDown();
                }

                @Override public void onFailure() {
                    finished.countDown();
                }
            });
            camera.startRecording(file);
            Thread.sleep(RECORD_MILLIS);
            camera.stopRecording();
            assertTrue(backend + " never reported the recording",
                    finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            camera.setHighSpeedProfile(null);
        });

        if (recorded.get() == null || recorded.get().length() == 0) {
            return null;
        }
        return new Recording(recorded.get(), selected.get());
    }

    /** Skips with something worth reading when the camera has no high speed to offer. */
    private void assumeHighSpeed(
            @NonNull CameraView camera, @NonNull List<HighSpeedProfile> published) {
        Assume.assumeTrue("this camera publishes no high-speed configurations on " + backend
                        + (DeviceState.isEmulator()
                        ? ", which no emulator does - run this on a phone"
                        : ", so it cannot record slow motion"),
                camera.isHighSpeedRecordingSupported() && !published.isEmpty());
    }

    /** Something to do with a camera that is open, on the thread the test is running on. */
    private interface WithCamera {
        void run(CameraView camera) throws Exception;
    }

    /**
     * Opens the camera, runs the body, and closes it again.
     *
     * <p>The body runs on the test thread rather than on the activity's, because it waits: a
     * recording takes seconds and the main thread is where the camera's own callbacks arrive.</p>
     */
    private void withAnOpenCamera(WithCamera body) throws Exception {
        try (ActivityScenario<CameraTestActivity> scenario =
                     ActivityScenario.launch(CameraTestActivity.class)) {
            CountDownLatch opened = new CountDownLatch(1);
            AtomicReference<CameraView> held = new AtomicReference<>();
            scenario.onActivity(activity -> {
                CameraView camera = activity.getCameraView();
                held.set(camera);
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
            assertTrue("the camera never opened on " + backend + " "
                            + DeviceState.whyTheCameraMightNotOpen(),
                    opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            CameraView camera = held.get();
            assertNotNull(camera);
            // Nothing here is about exposure, and the library's default is AUTO: left alone, a
            // run in a dim room fires the lamp at whoever is holding the phone.
            DeviceState.turnTheFlashOff(camera);
            try {
                body.run(camera);
            } finally {
                scenario.onActivity(activity -> activity.getCameraView().close());
            }
        }
    }

    private static File newFile(String prefix, String suffix) throws Exception {
        File file = File.createTempFile(prefix, suffix,
                ApplicationProvider.getApplicationContext().getCacheDir());
        file.deleteOnExit();
        return file;
    }

    /** How long the file says it is, as the platform's own parser reads it. */
    private static long durationMillis(File file) throws Exception {
        MediaMetadataRetriever reader = new MediaMetadataRetriever();
        try {
            reader.setDataSource(file.getAbsolutePath());
            String duration =
                    reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            assertNotNull("the recording reports no duration at all", duration);
            return Long.parseLong(duration);
        } finally {
            reader.release();
        }
    }

    /** The video track's format, as the platform's own demuxer reads it back. */
    private static MediaFormat videoTrack(File file) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    return format;
                }
            }
        } finally {
            extractor.release();
        }
        throw new AssertionError("the recording has no video track");
    }
}
