package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertEquals;

import android.media.CamcorderProfile;
import android.media.MediaRecorder;

import com.xlythe.view.camera.shadow.ShadowListingCamcorderProfile;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowCamcorderProfile;

/**
 * The audio rate a recording is written at, when its size and its profile disagree.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, shadows = {ShadowListingCamcorderProfile.class})
public class AudioBitRateTest {

    /** The case this exists for: recording bigger than the profile that set the audio. */
    @Test
    public void aRecordingLargerThanItsProfileGetsTheBetterAudio() {
        CamcorderProfile lowProfile = profile(640, 480, 96_000);
        CamcorderProfile best = profile(3840, 2160, 192_000);

        assertEquals("a 4K recording should not carry 480p's audio",
                192_000,
                VideoSession.VideoSurface.audioBitRateFor(lowProfile, best, 3840, 2160));
    }

    /** A recording no bigger than its profile keeps the profile's audio, which suits it. */
    @Test
    public void aRecordingWithinItsProfileIsLeftAlone() {
        CamcorderProfile profile = profile(1920, 1080, 128_000);
        CamcorderProfile best = profile(3840, 2160, 192_000);

        assertEquals("nothing to correct", 0,
                VideoSession.VideoSurface.audioBitRateFor(profile, best, 1920, 1080));
        assertEquals("nor for a smaller one", 0,
                VideoSession.VideoSurface.audioBitRateFor(profile, best, 1280, 720));
    }

    /** The rate is only ever raised, never lowered. */
    @Test
    public void theRateIsNeverLowered() {
        CamcorderProfile generous = profile(640, 480, 256_000);
        CamcorderProfile best = profile(3840, 2160, 192_000);

        assertEquals("a profile already richer than the best is left as it is",
                0, VideoSession.VideoSurface.audioBitRateFor(generous, best, 3840, 2160));
    }

    /** It never asks for a rate no profile on the device uses. */
    @Test
    public void theRateComesFromTheDeviceAndNowhereElse() {
        CamcorderProfile lowProfile = profile(640, 480, 96_000);
        CamcorderProfile best = profile(1920, 1080, 128_000);

        assertEquals("the best the device publishes, not an invented number",
                128_000,
                VideoSession.VideoSurface.audioBitRateFor(lowProfile, best, 3840, 2160));
    }

    /** A camera publishing no profile to compare against leaves the audio alone. */
    @Test
    public void nothingToCompareAgainstLeavesItAlone() {
        CamcorderProfile profile = profile(640, 480, 96_000);

        assertEquals(0, VideoSession.VideoSurface.audioBitRateFor(profile, null, 3840, 2160));
    }

    /** Equal rates need no change, which is the common case on a well-behaved device. */
    @Test
    public void equalRatesNeedNoChange() {
        CamcorderProfile profile = profile(640, 480, 128_000);
        CamcorderProfile best = profile(3840, 2160, 128_000);

        assertEquals(0, VideoSession.VideoSurface.audioBitRateFor(profile, best, 3840, 2160));
    }

    /** A profile built through Robolectric, since CamcorderProfile has no public constructor. */
    private static CamcorderProfile profile(int width, int height, int audioBitRate) {
        return ShadowCamcorderProfile.createProfile(
                /* duration= */ 30,
                CamcorderProfile.QUALITY_HIGH,
                MediaRecorder.OutputFormat.MPEG_4,
                MediaRecorder.VideoEncoder.H264,
                /* videoBitRate= */ 8_000_000,
                /* videoFrameRate= */ 30,
                width,
                height,
                MediaRecorder.AudioEncoder.AAC,
                audioBitRate,
                /* audioSampleRate= */ 44_100,
                /* audioChannels= */ 1);
    }
}
