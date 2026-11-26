package com.xlythe.view.camera.test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.Captions;
import com.xlythe.view.camera.caption.CaptionFormat;
import com.xlythe.view.camera.caption.SpeechTranscriber;
import com.xlythe.view.camera.caption.TranscriptionListener;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Whether a recording can be turned into captions on this device.
 *
 * <p>{@link com.xlythe.view.camera.caption.Transcript} is tested off the device, where the
 * timing arithmetic lives. What cannot be tested there is everything about the platform: that a
 * recording this library made can be decoded back to samples, that the recogniser accepts those
 * samples through a file descriptor, and that what comes back is written out as subtitles.</p>
 *
 * <p>Nobody is speaking during the test, so the expected outcome is a recording with no
 * intelligible speech in it - which is a reported failure, not a crash, and the assertion is
 * that it is reported correctly. The quiet case is the one worth pinning: it is what every
 * silent recording does, and treating it as a fault is how an app ends up showing an error for
 * a video of a sunset.</p>
 */
@RunWith(AndroidJUnit4.class)
public final class TranscriptionDeviceTest {
    private static final String TAG = "TranscriptionDeviceTest";
    private static final long TIMEOUT_SECONDS = 60;

    /** Long enough that the transcriber reads more than one window out of it. */
    private static final long RECORD_MILLIS = 8_000;

    @Rule
    public final GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO);

    @Before
    public void needsARecordingAndARecogniser() {
        DeviceState.assumeATestableCamera();
        Assume.assumeTrue("transcription needs Android 13, where the recogniser first took a"
                        + " file as its audio source",
                Build.VERSION.SDK_INT >= 33);
        Context context = ApplicationProvider.getApplicationContext();
        Assume.assumeTrue("this device has no speech recogniser installed",
                SpeechTranscriber.isSupported(context));
        CameraView.setDefaultBackend(CameraView.Backend.CAMERA2);
    }

    @After
    public void restoreTheDefault() {
        CameraView.setDefaultBackend(CameraView.Backend.AUTO);
    }

    private static final class Transcription implements TranscriptionListener {
        private final CountDownLatch mFinished = new CountDownLatch(1);
        private final AtomicReference<Captions> mCaptions = new AtomicReference<>();
        private final AtomicReference<File> mSidecar = new AtomicReference<>();
        private final AtomicReference<String> mFailure = new AtomicReference<>();
        private final AtomicBoolean mProgressed = new AtomicBoolean();

        @Override
        public void onTranscriptionProgress(float progress) {
            mProgressed.set(true);
        }

        @Override
        public void onTranscribed(@NonNull Captions captions, @NonNull File sidecar) {
            mCaptions.set(captions);
            mSidecar.set(sidecar);
            mFinished.countDown();
        }

        @Override
        public void onTranscriptionFailed(@NonNull String reason) {
            mFailure.set(reason);
            mFinished.countDown();
        }
    }

    /**
     * The claim: a recording goes all the way through the transcriber and reports an outcome.
     *
     * <p>Either outcome is a pass. What is being checked is that the audio decodes, that the
     * recogniser takes it, and that the answer comes back - a silent room has nothing to
     * transcribe and says so, and a room with somebody in it writes a subtitle file. What would
     * fail is a hang, a crash, or a report that the recording has no audio at all, which would
     * mean the decoding never worked.</p>
     */
    @Test
    public void aRecordingIsTranscribedOrSaysWhyNot() throws Exception {
        File video = recordSomething();
        assertNotNull("nothing was recorded, so there is nothing to transcribe", video);
        assertTrue("the recording is empty", video.length() > 0);
        Transcription transcription = new Transcription();

        try (ActivityScenario<CameraTestActivity> scenario =
                     ActivityScenario.launch(CameraTestActivity.class)) {
            scenario.onActivity(activity -> {
                CameraView camera = activity.getCameraView();
                Assume.assumeTrue("this build reports no transcription support",
                        camera.isTranscriptionSupported());
                camera.transcribe(video, CaptionFormat.SUB_RIP, transcription);
            });

            assertTrue("the transcriber never reported an outcome, which is a hang rather than"
                            + " a quiet recording",
                    transcription.mFinished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }

        String failure = transcription.mFailure.get();
        Log.i(TAG, "transcription: captions=" + transcription.mCaptions.get()
                + " failure=" + failure);
        assertTrue("progress should be reported for a recording with audio in it",
                transcription.mProgressed.get() || failure != null);

        if (failure != null) {
            // The expected outcome in a quiet room. What is asserted is that it failed for that
            // reason and not because the audio could not be read, which would be a real fault.
            assertFalse("the recording has an audio track, so this is a decoding failure"
                            + " dressed up as a quiet room: " + failure,
                    failure.contains("no audio"));
            return;
        }
        Captions captions = transcription.mCaptions.get();
        assertNotNull(captions);
        assertTrue("captions were reported with no cues in them", captions.size() > 0);
        File sidecar = transcription.mSidecar.get();
        assertNotNull(sidecar);
        assertTrue("the subtitle file was not written", sidecar.length() > 0);
        assertTrue("a sidecar sits beside the recording",
                sidecar.getName().endsWith(".srt"));
        for (Captions.Cue cue : captions.getCues()) {
            assertTrue("a cue cannot end before it starts", cue.getEndMs() > cue.getStartMs());
            assertTrue("a cue cannot start before the recording", cue.getStartMs() >= 0);
        }
    }

    /** The audio of a real recording decodes, which is the half the platform could refuse. */
    @Test
    public void theAudioOfARecordingCanBeRead() throws Exception {
        File video = recordSomething();
        assertNotNull(video);
        Transcription transcription = new Transcription();
        SpeechTranscriber transcriber =
                new SpeechTranscriber(ApplicationProvider.getApplicationContext());

        // On a background thread, which the transcriber insists on, and the point here is only
        // that it gets as far as reading the audio rather than what it hears.
        Thread worker = new Thread(() -> transcriber.transcribe(
                video, CaptionFormat.WEB_VTT, transcription));
        worker.start();
        assertTrue(transcription.mFinished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        worker.join(TimeUnit.SECONDS.toMillis(5));

        String failure = transcription.mFailure.get();
        assertFalse("the audio track of this recording could not be decoded: " + failure,
                failure != null && failure.contains("no audio"));
    }

    /** Transcribing on the main thread is refused rather than freezing the app. */
    @Test
    public void transcribingOnTheMainThreadIsRefused() {
        SpeechTranscriber transcriber =
                new SpeechTranscriber(ApplicationProvider.getApplicationContext());
        Throwable[] thrown = new Throwable[1];

        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> {
                    try {
                        transcriber.transcribe(new File("/does/not/exist.mp4"),
                                CaptionFormat.SUB_RIP, new Transcription());
                    } catch (Throwable error) {
                        thrown[0] = error;
                    }
                });

        assertNotNull("transcribe should refuse the main thread", thrown[0]);
        assertTrue("expected an IllegalStateException, got " + thrown[0],
                thrown[0] instanceof IllegalStateException);
    }

    /** Records a few seconds with audio, which is what there is to transcribe. */
    private File recordSomething() throws Exception {
        File video = new File(
                ApplicationProvider.getApplicationContext().getCacheDir(),
                "to-transcribe-" + System.currentTimeMillis() + ".mp4");
        CountDownLatch recorded = new CountDownLatch(1);
        AtomicReference<File> saved = new AtomicReference<>();

        try (ActivityScenario<CameraTestActivity> scenario =
                     ActivityScenario.launch(CameraTestActivity.class)) {
            CountDownLatch opened = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                CameraView camera = activity.getCameraView();
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
                    opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            scenario.onActivity(activity -> {
                CameraView camera = activity.getCameraView();
                DeviceState.turnTheFlashOff(camera);
                camera.setOnVideoCapturedListener(new CameraView.OnVideoCapturedListener() {
                    @Override public void onVideoConfirmation() {}

                    @Override public void onVideoCaptured(File file) {
                        saved.set(file);
                        recorded.countDown();
                    }

                    @Override public void onFailure() {
                        recorded.countDown();
                    }
                });
                camera.startRecording(video);
            });
            Thread.sleep(RECORD_MILLIS);
            scenario.onActivity(activity -> activity.getCameraView().stopRecording());
            assertTrue("the recording never reported",
                    recorded.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            scenario.onActivity(activity -> activity.getCameraView().close());
        }
        return saved.get();
    }
}
