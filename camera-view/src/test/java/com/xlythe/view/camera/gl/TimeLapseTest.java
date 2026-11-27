package com.xlythe.view.camera.gl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import android.media.CamcorderProfile;
import android.media.MediaRecorder;

import com.xlythe.view.camera.shadow.ShadowGeotaggingMediaRecorder;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowCamcorderProfile;
import org.robolectric.shadows.ShadowMediaRecorder;

import java.util.ArrayList;
import java.util.List;

/**
 * Which frames a time-lapse keeps and when it plays them, against made-up clocks, and how it sets
 * up a recorder that keeps its own.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, shadows = {ShadowGeotaggingMediaRecorder.class})
public class TimeLapseTest {
    /** A camera frame interval at 30 frames a second, in nanoseconds. */
    private static final long FRAME = 33_333_333L;

    /** A second, in nanoseconds. */
    private static final long SECOND = 1_000_000_000L;

    /** When the made-up encoder starts, on the monotonic clock. */
    private static final long STARTED = 100 * SECOND;

    /** How far the made-up clock that counts sleep runs ahead of the monotonic one. */
    private static final long ASLEEP = 3_600 * SECOND;

    @Before
    public void setUp() {
        ShadowGeotaggingMediaRecorder.forgetEverything();
    }

    /** At ten times, one frame in ten is kept, and each plays one frame interval after the last. */
    @Test
    public void oneFrameInSpeedIsKeptAndPlayedAFrameApart() {
        TimeLapse timeLapse = new TimeLapse(10, 30);
        timeLapse.start(STARTED, STARTED + ASLEEP);

        List<Long> kept = new ArrayList<>();
        List<Long> stamps = new ArrayList<>();
        for (int frame = 0; frame < 100; frame++) {
            long taken = STARTED + frame * FRAME;
            long stamp = stamp(timeLapse, taken, false);
            if (stamp != TimeLapse.SKIP) {
                kept.add(taken);
                stamps.add(stamp);
            }
        }

        assertEquals("ten seconds of frames, ten times as fast", 10, kept.size());
        for (int i = 1; i < kept.size(); i++) {
            assertEquals("kept a third of a second apart",
                    10 * FRAME, kept.get(i) - kept.get(i - 1), 10);
            assertEquals("played a frame apart", FRAME, stamps.get(i) - stamps.get(i - 1), 10);
        }
        assertEquals("the first frame plays when it was taken", STARTED, (long) stamps.get(0));
    }

    /** Nothing is kept before the encoder starts, which would otherwise drop it unseen. */
    @Test
    public void nothingIsKeptBeforeTheEncoderStarts() {
        TimeLapse timeLapse = new TimeLapse(10, 30);

        assertEquals(TimeLapse.SKIP, stamp(timeLapse, STARTED, false));

        timeLapse.start(STARTED, STARTED + ASLEEP);

        assertEquals("taken before the start", TimeLapse.SKIP,
                stamp(timeLapse, STARTED - FRAME, false));
        assertEquals(STARTED + FRAME, stamp(timeLapse, STARTED + FRAME, false));
    }

    /**
     * A camera stamping its frames on the clock that counts sleep is compared with the start on
     * that clock, and played on it.
     */
    @Test
    public void framesOnTheClockThatCountsSleepStayOnIt() {
        TimeLapse timeLapse = new TimeLapse(4, 30);
        timeLapse.start(STARTED, STARTED + ASLEEP);

        assertEquals("a monotonic start, read as realtime, is before the start",
                TimeLapse.SKIP, stamp(timeLapse, STARTED + FRAME, true));
        long first = STARTED + ASLEEP + FRAME;
        assertEquals(first, stamp(timeLapse, first, true));
        assertEquals(first + FRAME, stamp(timeLapse, first + 4 * FRAME, true));
    }

    /**
     * A camera that stalls leaves a gap where its frames would have been, rather than slowing the
     * time-lapse down to catch up.
     */
    @Test
    public void aStalledCameraLeavesAGap() {
        TimeLapse timeLapse = new TimeLapse(10, 30);
        timeLapse.start(STARTED, STARTED + ASLEEP);
        stamp(timeLapse, STARTED, false);

        long afterStall = STARTED + 5 * SECOND;

        assertEquals("five seconds is fifteen places in the schedule",
                STARTED + 15 * FRAME, stamp(timeLapse, afterStall, false));
        assertEquals("and the next place is one on", STARTED + 16 * FRAME,
                stamp(timeLapse, afterStall + 10 * FRAME, false));
    }

    /**
     * A frame a little early for its place takes it, so a camera running slightly fast still fills
     * each place, and the frame after it does not take the same place again.
     */
    @Test
    public void aPlaceIsFilledByTheFirstFrameToReachIt() {
        TimeLapse timeLapse = new TimeLapse(10, 30);
        timeLapse.start(STARTED, STARTED + ASLEEP);
        stamp(timeLapse, STARTED, false);
        long early = 5_000_000L;

        assertEquals(TimeLapse.SKIP, stamp(timeLapse, STARTED + 9 * FRAME, false));
        assertEquals(STARTED + FRAME, stamp(timeLapse, STARTED + 10 * FRAME - early, false));
        assertEquals("that place is taken", TimeLapse.SKIP,
                stamp(timeLapse, STARTED + 10 * FRAME, false));
    }

    /**
     * Once stopping, a frame plays at the moment it is drawn - after the encoder's stop - and
     * never before the last frame kept.
     */
    @Test
    public void aStoppingTimeLapseStampsTheNextFrameNow() {
        TimeLapse timeLapse = new TimeLapse(10, 30);
        timeLapse.start(STARTED, STARTED + ASLEEP);
        long last = stamp(timeLapse, STARTED, false);
        long now = STARTED + 60 * SECOND;

        timeLapse.finish();

        assertEquals(now, timeLapse.presentationTime(STARTED + 59 * SECOND, false, now,
                now + ASLEEP));
        assertEquals("on the camera's own clock", now + ASLEEP,
                timeLapse.presentationTime(STARTED + 59 * SECOND, true, now, now + ASLEEP));
        assertEquals("never going backwards", now + ASLEEP + FRAME,
                timeLapse.presentationTime(STARTED, false, last, last));
    }

    /** A speed of one is a normal recording, and a frame rate has to be a rate. */
    @Test
    public void onlyASpeedUpIsATimeLapse() {
        assertThrows(IllegalArgumentException.class, () -> new TimeLapse(1, 30));
        assertThrows(IllegalArgumentException.class, () -> new TimeLapse(10, 0));
    }

    /**
     * A recorder is set up from the profile's video alone, and slows down what it is given only
     * when it is told to keep a time-lapse's frames itself.
     */
    @Test
    public void aRecorderIsSetUpFromTheProfilesVideo() {
        CamcorderProfile profile = ShadowCamcorderProfile.createProfile(30,
                CamcorderProfile.QUALITY_1080P, MediaRecorder.OutputFormat.MPEG_4,
                MediaRecorder.VideoEncoder.HEVC, 12_000_000, 24, 1920, 1080,
                MediaRecorder.AudioEncoder.AAC, 96_000, 44_100, 1);
        MediaRecorder recorder = new MediaRecorder();
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);

        TimeLapse.setUpRecorder(recorder, profile, 1);

        ShadowMediaRecorder shadow = Shadow.extract(recorder);
        assertEquals(MediaRecorder.OutputFormat.MPEG_4, shadow.getOutputFormat());
        assertEquals(MediaRecorder.VideoEncoder.HEVC, shadow.getVideoEncoder());
        assertEquals(24, shadow.getVideoFrameRate());
        assertEquals(1920, shadow.getVideoWidth());
        assertEquals(1080, shadow.getVideoHeight());
        assertEquals(12_000_000, shadow.getVideoEncodingBitRate());
        assertNull("no microphone", ShadowGeotaggingMediaRecorder.getLastAudioSource());
        assertNull("a renderer keeps the frames", ShadowGeotaggingMediaRecorder.getLastCaptureRate());

        MediaRecorder slowed = new MediaRecorder();
        slowed.setVideoSource(MediaRecorder.VideoSource.CAMERA);
        TimeLapse.setUpRecorder(slowed, profile, 12);

        assertEquals("two frames a second, played at twenty-four",
                2.0, ShadowGeotaggingMediaRecorder.getLastCaptureRate(), 1e-9);
        recorder.release();
        slowed.release();
    }

    private static long stamp(TimeLapse timeLapse, long taken, boolean realtimeClock) {
        return timeLapse.presentationTime(taken, realtimeClock, taken, taken + ASLEEP);
    }
}
