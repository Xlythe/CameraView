package com.xlythe.view.camera.stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.VideoStream;
import com.xlythe.view.camera.test.CameraTestActivity;
import com.xlythe.view.camera.test.DeviceState;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A stream from the real camera and microphone, through the real encoders and decoders, while
 * the things a stream has to live through happen to it.
 *
 * <p>The receiving end plays the audio through the same player VideoView uses, with the speaker
 * replaced by a recording, and reads the video as a transport would, so what arrived can be
 * counted: decoded milliseconds of audio and encoded video frames. The camera is switched, the app
 * is sent to the background and brought back, the receiver goes away and a new one connects, and a
 * receiver from before compression asks for nothing.</p>
 */
@RunWith(AndroidJUnit4.class)
public final class StreamLifecycleDeviceTest {
    private static final String TAG = "StreamLifecycleDeviceTest";

    private static final long TIMEOUT_SECONDS = 30;

    @Rule
    public final GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
    );

    /** Skips, rather than fails, a test that the emulator's camera HAL stalled under. */
    @Rule
    public final TestRule cameraHal = DeviceState.skipWhenTheEmulatorCameraHalStalls();

    @Before
    public void requireACamera() {
        Assume.assumeTrue("no camera on this device",
                ApplicationProvider.getApplicationContext().getPackageManager()
                        .hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY));
    }

    /** What a current receiver on this device would ask for. */
    private static VideoStream.Params params(byte[] capabilities) {
        VideoStream.Params.Builder builder = new VideoStream.Params.Builder()
                .setAudioEnabled(true)
                .setVideoEnabled(true)
                .setMaxSize(new Size(640, 480))
                .setBitRate(1_000_000)
                .setIFrameInterval(1)
                .setIsLossy(true)
                .setEchoCancellationEnabled(true)
                .setNoiseSuppressionEnabled(true)
                .setAutomaticGainControlEnabled(true);
        if (capabilities != null) {
            builder.setPlaybackCapabilities(capabilities);
        }
        return builder.build();
    }

    /** The format a current receiver on this release negotiates. */
    private static int expectedCodec() {
        return Build.VERSION.SDK_INT >= 29 ? AudioCodec.OPUS : AudioCodec.AAC;
    }

    /**
     * Camera and microphone to decoded frames and audio, compressed in the format this device
     * negotiates with itself.
     */
    @Test
    public void theCameraAndMicrophoneArriveThroughTheNegotiatedCodecs() throws Exception {
        AtomicReference<Receiver> received = new AtomicReference<>();
        withOpenCamera((scenario, camera) -> {
            VideoStream stream = camera.stream(params(VideoStream.getPlaybackCapabilities()));
            Receiver receiver = new Receiver(stream, "negotiated");
            received.set(receiver);
            SystemClock.sleep(6_000);
            receiver.snapshot("end");
            receiver.saveEvidence("sdk" + Build.VERSION.SDK_INT + "-camera");
            stream.close();
            receiver.awaitEnd();
        });

        Receiver receiver = received.get();
        report("negotiated", receiver.toString());
        assertEquals(expectedCodec(), receiver.codec());
        assertTrue(receiver.toString(), receiver.encodedFrames.get() >= 30);

        assertTrue(receiver.toString(), receiver.audioMillis() >= 4_000);
    }

    /**
     * Switching lenses mid-stream: the audio carries on, and the video, which belongs to the
     * session the switch replaces, stops - as it always has, which is why the sample app starts
     * a new stream when it switches. The new stream is negotiated and arrives.
     */
    @Test
    public void aCameraSwitchKeepsTheAudioAndANewStreamCarriesTheVideo() throws Exception {
        AtomicReference<Receiver> received = new AtomicReference<>();
        AtomicReference<Receiver> restarted = new AtomicReference<>();
        AtomicLong framesBefore = new AtomicLong();
        AtomicLong audioBefore = new AtomicLong();
        AtomicReference<Boolean> hasFront = new AtomicReference<>(false);
        withOpenCamera((scenario, camera) -> {
            hasFront.set(camera.hasFrontFacingCamera());
            if (!hasFront.get()) {
                return;
            }
            VideoStream stream = camera.stream(params(VideoStream.getPlaybackCapabilities()));
            Receiver receiver = new Receiver(stream, "switch");
            received.set(receiver);
            SystemClock.sleep(4_000);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(camera::toggleCamera);
            framesBefore.set(receiver.encodedFrames.get());
            audioBefore.set(receiver.audioMillis());
            SystemClock.sleep(4_000);
            receiver.snapshot("end");
            stream.close();
            receiver.awaitEnd();

            // What the sample app does on a switch.
            VideoStream again = camera.stream(params(VideoStream.getPlaybackCapabilities()));
            Receiver fresh = new Receiver(again, "after-switch");
            restarted.set(fresh);
            SystemClock.sleep(5_000);
            fresh.snapshot("end");
            again.close();
            fresh.awaitEnd();
        });

        Assume.assumeTrue("this device has only one camera", hasFront.get());
        Receiver receiver = received.get();
        report("camera-switch", receiver + String.format(Locale.US,
                ", %d frames and %dms of audio in the 4s after the switch",
                receiver.encodedFrames.get() - framesBefore.get(),
                receiver.audioMillis() - audioBefore.get()));
        report("camera-switch-new-stream", restarted.get().toString());
        assertTrue("audio stopped after the switch",
                receiver.audioMillis() - audioBefore.get() >= 2_500);
        assertEquals(expectedCodec(), restarted.get().codec());
        assertTrue(restarted.get().toString(), restarted.get().encodedFrames.get() >= 10);
        assertTrue(restarted.get().toString(), restarted.get().audioMillis() >= 2_500);
    }

    /**
     * Sending the app to the background closes the camera, so the video stops, but the audio is
     * the caller's and carries on. A stream's video does not come back by itself when the camera
     * reopens - the README says the video stops - so the app starts a new stream, which works.
     */
    @Test
    public void theAudioCarriesOnThroughPauseAndResume() throws Exception {
        AtomicReference<Receiver> received = new AtomicReference<>();
        AtomicReference<Receiver> restarted = new AtomicReference<>();
        long[] marks = new long[4];
        withOpenCamera((scenario, camera) -> {
            VideoStream stream = camera.stream(params(VideoStream.getPlaybackCapabilities()));
            Receiver receiver = new Receiver(stream, "pause");
            received.set(receiver);
            SystemClock.sleep(3_000);
            marks[0] = receiver.audioMillis();
            scenario.moveToState(Lifecycle.State.CREATED);
            SystemClock.sleep(4_000);
            marks[1] = receiver.audioMillis();
            marks[2] = receiver.encodedFrames.get();
            scenario.moveToState(Lifecycle.State.RESUMED);
            CountDownLatch reopened = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                activity.getCameraView().setOnCameraStateChangedListener(
                        new CameraView.OnCameraStateChangedListener() {
                            @Override public void onCameraOpened() {
                                reopened.countDown();
                            }

                            @Override public void onCameraClosed() {}
                        });
                activity.getCameraView().open();
            });
            assertTrue("the camera did not reopen",
                    reopened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            SystemClock.sleep(3_000);
            marks[3] = receiver.encodedFrames.get();
            receiver.snapshot("end");
            stream.close();
            receiver.awaitEnd();

            VideoStream again = camera.stream(params(VideoStream.getPlaybackCapabilities()));
            Receiver fresh = new Receiver(again, "after-resume");
            restarted.set(fresh);
            SystemClock.sleep(5_000);
            fresh.snapshot("end");
            again.close();
            fresh.awaitEnd();
        });

        Receiver receiver = received.get();
        long pausedAudio = marks[1] - marks[0];
        long resumedFrames = marks[3] - marks[2];
        report("pause-resume", receiver + String.format(Locale.US,
                ", %dms of audio during the 4s paused, %d frames of the old stream in the 3s after"
                        + " the camera reopened", pausedAudio, resumedFrames));
        report("pause-resume-new-stream", restarted.get().toString());
        assertTrue("the audio stopped with the camera: " + pausedAudio, pausedAudio >= 2_500);
        assertEquals(expectedCodec(), restarted.get().codec());
        assertTrue(restarted.get().toString(), restarted.get().encodedFrames.get() >= 20);
        assertTrue(restarted.get().toString(), restarted.get().audioMillis() >= 2_500);
    }

    /**
     * A receiver that goes away ends the stream it was reading; a new receiver gets a new stream,
     * negotiated afresh with whatever it says it plays.
     */
    @Test
    public void aReconnectingPeerGetsANewlyNegotiatedStream() throws Exception {
        AtomicReference<Receiver> first = new AtomicReference<>();
        AtomicReference<Receiver> second = new AtomicReference<>();
        withOpenCamera((scenario, camera) -> {
            VideoStream stream = camera.stream(params(VideoStream.getPlaybackCapabilities()));
            Receiver receiver = new Receiver(stream, "first");
            first.set(receiver);
            SystemClock.sleep(3_000);
            // The far end drops: the transport stops reading and closes.
            receiver.disconnect();
            SystemClock.sleep(1_000);
            stream.close();

            // It comes back as a peer that only plays AAC, say an older release.
            byte[] aacOnly = {'X', 'C', 'A', 'P', 1, 2, AudioCodec.AAC, AudioCodec.PCM};
            VideoStream again = camera.stream(params(aacOnly));
            Receiver reconnected = new Receiver(again, "second");
            second.set(reconnected);
            SystemClock.sleep(5_000);
            reconnected.snapshot("end");
            again.close();
            reconnected.awaitEnd();
        });

        report("reconnect-first", first.get().toString());
        report("reconnect-second", second.get().toString());
        assertEquals(expectedCodec(), first.get().codec());
        assertEquals("negotiated again for the new peer", AudioCodec.AAC, second.get().codec());
        assertTrue(second.get().toString(), second.get().encodedFrames.get() >= 20);
        assertTrue(second.get().toString(), second.get().audioMillis() >= 2_500);
    }

    /**
     * A receiver from before compression sends no capabilities, and is sent the released layout:
     * the version 1 header, then PCM.
     */
    @Test
    public void anOldVersionPeerGetsTheReleasedLayout() throws Exception {
        AtomicReference<Receiver> received = new AtomicReference<>();
        withOpenCamera((scenario, camera) -> {
            VideoStream stream = camera.stream(params(null));
            Receiver receiver = new Receiver(stream, "old-peer");
            received.set(receiver);
            SystemClock.sleep(5_000);
            receiver.snapshot("end");
            stream.close();
            receiver.awaitEnd();
        });

        Receiver receiver = received.get();
        report("old-peer", receiver.toString());
        byte[] head = receiver.audioHead();
        assertTrue("no audio arrived", head.length >= AudioStreamHeader.LENGTH);
        ByteBuffer header = ByteBuffer.wrap(head);
        assertEquals("the released magic", 0x58415544, header.getInt());
        assertEquals("version 1, which every release reads", 1, header.get());
        assertEquals(AudioCodec.PCM, receiver.codec());
        assertTrue(receiver.toString(), receiver.audioMillis() >= 3_000);
        assertTrue(receiver.toString(), receiver.encodedFrames.get() >= 20);
    }

    /** What a test does with an open camera. */
    private interface CameraAction {
        void run(ActivityScenario<CameraTestActivity> scenario, CameraView camera)
                throws Exception;
    }

    /** Opens the camera in a hosted activity, waits for it, runs the action, and closes it. */
    private void withOpenCamera(CameraAction action) throws Exception {
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
            Assume.assumeTrue("the camera did not open on this device",
                    opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            CameraView camera = held.get();
            assertNotNull(camera);
            camera.setFlash(CameraView.Flash.OFF);
            try {
                action.run(scenario, camera);
            } finally {
                scenario.onActivity(activity -> activity.getCameraView().close());
            }
        }
    }

    /**
     * The far end of a stream: plays the audio into a recording and reads the video, counting what
     * arrives of both.
     */
    private static final class Receiver {
        /** Names the receiver in logs. */
        private final String mName;

        /** Where the audio is played. */
        final TestSignal.Capture audio = new TestSignal.Capture();

        /** Plays the audio. */
        private final AudioPlayer mAudioPlayer;

        /** The video as it arrives, which the view plays. */
        private final FrameCountingStream mVideo;

        /** Encoded video frames that arrived, the header and configuration included. */
        final AtomicLong encodedFrames = new AtomicLong();

        /** The first bytes of the audio stream, kept to inspect its header. */
        private final ByteArrayOutputStream mAudioHead = new ByteArrayOutputStream();

        /** The audio player has finished. */
        private final CountDownLatch mAudioEnded = new CountDownLatch(1);

        /** The video has ended. */
        private final CountDownLatch mVideoEnded = new CountDownLatch(1);

        /** Counts at the last snapshot. */
        private String mSnapshot = "";

        Receiver(VideoStream stream, String name) {
            mName = name;
            InputStream audioIn = new HeadKeepingStream(stream.getAudioInputStream());
            mAudioPlayer = new AudioPlayer(audioIn, audio);
            mAudioPlayer.setStreamEndListener(mAudioEnded::countDown);
            mAudioPlayer.start();
            mVideo = new FrameCountingStream(stream.getVideoInputStream());
            // Read as a transport would, so what is measured is the sending end: this branch
            // changes how video is encoded and sent, not how it is decoded.
            Thread reader = new Thread(() -> {
                byte[] chunk = new byte[8192];
                try {
                    while (mVideo.read(chunk, 0, chunk.length) >= 0) {
                        // Counted on the way through.
                    }
                } catch (IOException e) {
                    // The stream closing ends the read.
                } finally {
                    closeQuietly(mVideo);
                }
            }, "VideoReader");
            reader.setDaemon(true);
            reader.start();
        }

        int codec() {
            return mAudioPlayer.getCodec();
        }

        long audioMillis() {
            int rate = audio.sampleRate;
            int channels = Math.max(1, audio.channelCount);
            return rate == 0 ? 0 : audio.bytes().length * 1000L / (rate * channels * 2);
        }

        byte[] audioHead() {
            synchronized (mAudioHead) {
                return mAudioHead.toByteArray();
            }
        }

        void snapshot(String label) {
            mSnapshot = label + ": " + encodedFrames.get() + " frames arrived, "
                    + audioMillis() + "ms";
            Log.i(TAG, mName + " " + mSnapshot);
        }

        /** The transport going away: the reader stops and closes what it was reading. */
        void disconnect() {
            mAudioPlayer.stop();
            closeQuietly(mVideo);
        }

        /** Lets the stream go and waits for both players to finish. */
        void awaitEnd() throws InterruptedException {
            mAudioEnded.await(10, TimeUnit.SECONDS);
            mVideoEnded.await(10, TimeUnit.SECONDS);
        }

        /** Saves what was played, as evidence. */
        void saveEvidence(String prefix) {
            TestSignal.save(prefix + "-audio-" + audio.sampleRate + "hz-"
                    + Math.max(1, audio.channelCount) + "ch.pcm", audio.bytes());
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "%s: codec %d, %d frames arrived, %dms of audio at "
                            + "%dHz, %s", mName, codec(), encodedFrames.get(), audioMillis(),
                    audio.sampleRate, mSnapshot);
        }

        private static void closeQuietly(InputStream in) {
            try {
                in.close();
            } catch (IOException e) {
                // Already closed, which is all that was wanted.
            }
        }

        /** Counts the encoded frames that pass on their way to the decoder. */
        private final class FrameCountingStream extends java.io.FilterInputStream {
            /** Bytes left of the frame being passed, or -1 while its length is being read. */
            private int mRemaining = -1;

            /** The length prefix, as it arrives. */
            private int mLength;

            /** How many bytes of the prefix have arrived. */
            private int mLengthBytes;

            FrameCountingStream(InputStream in) {
                super(in);
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int read = super.read(buffer, offset, length);
                for (int i = 0; i < read; i++) {
                    if (mRemaining > 0) {
                        int skip = Math.min(mRemaining, read - i);
                        mRemaining -= skip;
                        i += skip - 1;
                        continue;
                    }
                    mLength = (mLength << 8) | (buffer[offset + i] & 0xFF);
                    if (++mLengthBytes == 4) {
                        mRemaining = mLength;
                        mLength = 0;
                        mLengthBytes = 0;
                        encodedFrames.incrementAndGet();
                    }
                }
                return read;
            }

            @Override
            public void close() throws IOException {
                super.close();
                mVideoEnded.countDown();
            }
        }

        /** Keeps the first bytes that pass, for the header. */
        private final class HeadKeepingStream extends java.io.FilterInputStream {
            HeadKeepingStream(InputStream in) {
                super(in);
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int read = super.read(buffer, offset, length);
                synchronized (mAudioHead) {
                    if (read > 0 && mAudioHead.size() < 64) {
                        mAudioHead.write(buffer, offset, Math.min(read, 64 - mAudioHead.size()));
                    }
                }
                return read;
            }
        }
    }

    /** Logs a measurement and puts it in the instrumentation output, where a run keeps it. */
    private static void report(String key, String value) {
        Log.i(TAG, key + ": " + value);
        Bundle bundle = new Bundle();
        bundle.putString(TAG + "." + key, value);
        InstrumentationRegistry.getInstrumentation().sendStatus(0, bundle);
    }
}
