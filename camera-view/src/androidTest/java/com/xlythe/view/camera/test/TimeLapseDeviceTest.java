package com.xlythe.view.camera.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.rule.GrantPermissionRule;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.gl.FrameRenderer;
import com.xlythe.view.camera.gl.GlFrame;

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
import java.util.concurrent.atomic.AtomicReference;

/**
 * A time-lapse plays back as many times faster than it was recorded as it was asked to, without
 * sound, on every backend - read back out of the file rather than taken on trust.
 *
 * <p>The recording runs for a fixed time on the wall clock and the file's own duration is set
 * against it. Startup is not instant on any backend, so the file is allowed to come out somewhat
 * short of the wall time divided by the speed; what it may not do is come out anywhere near the
 * wall time, which is what a recording that ignored the speed would do.</p>
 */
@RunWith(Parameterized.class)
public final class TimeLapseDeviceTest {
    private static final String TAG = "TimeLapseDeviceTest";

    /** Long enough for a camera to open, converge and deliver. */
    private static final long TIMEOUT_SECONDS = 30;

    /** How long each time-lapse is recorded for, on the wall clock. */
    private static final long RECORD_MILLIS = 12_000;

    /** How many times faster than it happened each time-lapse plays. */
    private static final int SPEED = 10;

    /**
     * The least of the expected length a file may have. Generous, because each backend takes a
     * moment to start writing after it is asked to.
     */
    private static final double SHORTEST = 0.6;

    /** The most of the expected length a file may have, over a frame for rounding. */
    private static final double LONGEST = 1.25;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<CameraView.Backend> backends() {
        return Arrays.asList(CameraView.Backend.CAMERA2, CameraView.Backend.CAMERA_X,
                CameraView.Backend.LEGACY);
    }

    @Rule
    public final GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
    );

    private final CameraView.Backend backend;

    public TimeLapseDeviceTest(CameraView.Backend backend) {
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

    /** A time-lapse from the back camera plays the speed faster than it took, without sound. */
    @Test
    public void aTimeLapsePlaysFasterThanItWasRecorded() throws Exception {
        assertPlaysAtItsSpeed(record(null));
    }

    /**
     * And so does one drawn through a renderer, which every recording is drawn through once one
     * is set, and which the time-lapse then shares with the preview.
     */
    @Test
    public void aTimeLapseThroughARendererPlaysFasterToo() throws Exception {
        Assume.assumeTrue("the old API draws no frames", backend != CameraView.Backend.LEGACY);
        assertPlaysAtItsSpeed(record(frame -> false));
    }

    private void assertPlaysAtItsSpeed(@NonNull Recording recording) throws Exception {
        long fileMillis = durationMillis(recording.file);
        int frames = videoFrames(recording.file);
        double expectedMillis = (double) recording.wallMillis / SPEED;
        Log.i(TAG, String.format(Locale.US,
                "%s%s: recorded for %d ms of wall clock, the file plays for %d ms in %d frames"
                        + " (expected about %.0f ms at %dx)",
                backend, recording.throughRenderer ? " through a renderer" : "",
                recording.wallMillis, fileMillis, frames, expectedMillis, SPEED));

        assertEquals("a time-lapse has no sound", 0, audioTracks(recording.file));
        assertTrue(backend + " played for " + fileMillis + " ms, more than "
                        + recording.wallMillis + " / " + SPEED + " allows",
                fileMillis <= expectedMillis * LONGEST + 34);
        assertTrue(backend + " played for only " + fileMillis + " ms of an expected "
                        + Math.round(expectedMillis),
                fileMillis >= expectedMillis * SHORTEST);
    }

    /** What one recording left behind, and how long it took. */
    private static final class Recording {
        File file;
        long wallMillis;
        boolean throughRenderer;
    }

    @NonNull
    private Recording record(@Nullable FrameRenderer renderer) throws Exception {
        File file = File.createTempFile("timelapse", ".mp4",
                ApplicationProvider.getApplicationContext().getCacheDir());
        file.deleteOnExit();
        Recording recording = new Recording();
        recording.throughRenderer = renderer != null;
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<File> recorded = new AtomicReference<>();

        try (ActivityScenario<CameraTestActivity> scenario =
                     ActivityScenario.launch(CameraTestActivity.class)) {
            CountDownLatch opened = new CountDownLatch(1);
            AtomicReference<CameraView> held = new AtomicReference<>();
            scenario.onActivity(activity -> {
                CameraView camera = activity.getCameraView();
                held.set(camera);
                camera.setFlash(CameraView.Flash.OFF);
                camera.setTimeLapseSpeed(SPEED);
                if (renderer != null) {
                    camera.setFrameRenderer(renderer);
                }
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
            DeviceState.turnTheFlashOff(camera);
            if (renderer != null) {
                Assume.assumeTrue("frames do not go through GL here",
                        camera.isFrameEffectSupported());
            }
            try {
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
                long started = SystemClock.elapsedRealtime();
                scenario.onActivity(activity -> activity.getCameraView().startRecording(file));
                Thread.sleep(RECORD_MILLIS);
                scenario.onActivity(activity -> activity.getCameraView().stopRecording());
                recording.wallMillis = SystemClock.elapsedRealtime() - started;
                assertTrue("the time-lapse never reported on " + backend,
                        finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            } finally {
                scenario.onActivity(activity -> activity.getCameraView().close());
            }
        }

        assertNotNull(backend + " failed to record a time-lapse", recorded.get());
        assertTrue(backend + " wrote an empty time-lapse", recorded.get().length() > 0);
        recording.file = recorded.get();
        return recording;
    }

    /** How long the file says it plays for. */
    private static long durationMillis(@NonNull File file) throws Exception {
        MediaMetadataRetriever reader = new MediaMetadataRetriever();
        try {
            reader.setDataSource(file.getAbsolutePath());
            String duration = reader.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            assertNotNull("the file has no duration", duration);
            return Long.parseLong(duration);
        } finally {
            reader.release();
        }
    }

    /** How many frames the video track holds, counted sample by sample. */
    private static int videoFrames(@NonNull File file) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    extractor.selectTrack(i);
                    int frames = 0;
                    while (extractor.getSampleTime() >= 0) {
                        frames++;
                        extractor.advance();
                    }
                    return frames;
                }
            }
        } finally {
            extractor.release();
        }
        throw new AssertionError("the time-lapse has no video track");
    }

    private static int audioTracks(@NonNull File file) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int audio = 0;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audio++;
                }
            }
            return audio;
        } finally {
            extractor.release();
        }
    }
}
