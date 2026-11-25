package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.util.Range;
import android.util.Size;

import com.xlythe.view.camera.HighSpeedProfile;
import com.xlythe.view.camera.shadow.ShadowInjectableImageReader;
import com.xlythe.view.camera.shadow.ShadowListingCamcorderProfile;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowCamcorderProfile;

/**
 * The arithmetic that turns a fast camera into a file that plays back slowly.
 *
 * <p>Two numbers decide whether a 240fps recording is slow motion or a curiosity: the rate the
 * file declares, which has to be the playback rate rather than the capture rate, and the bit rate,
 * which has to pay for eight times as many frames a second as the profile it came from was written
 * for. Neither needs a camera to check, which is why they live in static methods.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37},
        shadows = {ShadowInjectableImageReader.class, ShadowListingCamcorderProfile.class})
public class HighSpeedRecordingTest {
    /** A 720p profile: 10 Mbps of 30fps video, which has room to be scaled up eightfold. */
    private static final int PROFILE_BIT_RATE = 10_000_000;
    private static final int PROFILE_FRAME_RATE = 30;

    private Context context;
    private FakeCamera2.TestCameraView cameraView;
    private Camera2Module module;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
    }

    @After
    public void tearDown() {
        if (module == null) return;
        module.close();
        FakeCamera2.idleMainLooper();
        cameraView.release();
    }

    /**
     * The file declares the playback rate while the camera delivers the capture rate.
     *
     * <p>Tagging the file at the capture rate instead is the mistake that produces a correctly
     * timed recording at a frame rate nothing plays smoothly, rather than slow motion.</p>
     */
    @Test
    public void theFileDeclaresThePlaybackRateRatherThanTheCaptureRate() {
        assertEquals(30, VideoSession.VideoSurface.playbackFrameRate(240, 30));
        assertEquals("a phone whose ordinary recordings are 60fps plays back at 60",
                60, VideoSession.VideoSurface.playbackFrameRate(240, 60));
    }

    /** A device that publishes no frame rate still gets the rate everything plays. */
    @Test
    public void aDeviceWithNoProfileToCopyPlaysBackAtThirty() {
        assertEquals(30, VideoSession.VideoSurface.playbackFrameRate(120, 0));
    }

    /** Nothing is written as slower than life when it was not captured faster than life. */
    @Test
    public void aCaptureRateBelowPlaybackIsNotStretched() {
        assertEquals(30, VideoSession.VideoSurface.playbackFrameRate(30, 30));
        assertEquals(24, VideoSession.VideoSurface.playbackFrameRate(24, 30));
    }

    /**
     * Eight times the frames a second costs eight times the bit rate.
     *
     * <p>A profile's rate pays for thirty 1080p frames. Left at that, each of the 240 frames in a
     * second of high-speed video gets an eighth of the bits it was budgeted, and the detail that
     * slow motion exists to show is the first thing to go.</p>
     */
    @Test
    public void aFasterRecordingIsPaidForFrameByFrame() {
        assertEquals(80_000_000, VideoSession.VideoSurface.scaleBitRateForFrameRate(
                PROFILE_BIT_RATE, PROFILE_FRAME_RATE, 240));
    }

    /** And four times for 120, which is where most phones stop. */
    @Test
    public void aModestlyFasterRecordingCostsProportionallyLess() {
        assertEquals(40_000_000, VideoSession.VideoSurface.scaleBitRateForFrameRate(
                PROFILE_BIT_RATE, PROFILE_FRAME_RATE, 120));
    }

    /** A profile already written for the capture rate needs no help. */
    @Test
    public void aProfileAtTheCaptureRateIsLeftAlone() {
        assertEquals(PROFILE_BIT_RATE, VideoSession.VideoSurface.scaleBitRateForFrameRate(
                PROFILE_BIT_RATE, 240, 240));
        assertEquals(PROFILE_BIT_RATE, VideoSession.VideoSurface.scaleBitRateForFrameRate(
                PROFILE_BIT_RATE, 240, 120));
    }

    /** A device that reports nothing useful cannot divide the rate by zero. */
    @Test
    public void degenerateInputFallsBackRatherThanDividingByZero() {
        assertEquals(PROFILE_BIT_RATE, VideoSession.VideoSurface.scaleBitRateForFrameRate(
                PROFILE_BIT_RATE, 0, 240));
        assertTrue(VideoSession.VideoSurface.scaleBitRateForFrameRate(0, 30, 240) >= 64_000);
    }

    /** The ceiling still holds, so a 4K profile at 240fps cannot ask for the impossible. */
    @Test
    public void theRateIsStillClamped() {
        assertEquals(100_000_000, VideoSession.VideoSurface.scaleBitRateForFrameRate(
                80_000_000, 30, 240));
    }

    /**
     * The device's own slow-motion profile is preferred, by size.
     *
     * <p>Its bit rate and frame rate are the manufacturer's answer for recording this resolution
     * fast, which beats extrapolating from a profile written for thirty frames a second.</p>
     */
    @Test
    public void theDevicesOwnHighSpeedProfileIsFoundBySize() {
        addHighSpeedProfile(CamcorderProfile.QUALITY_HIGH_SPEED_720P,
                new Size(1280, 720), 240, 48_000_000);
        addHighSpeedProfile(CamcorderProfile.QUALITY_HIGH_SPEED_1080P,
                new Size(1920, 1080), 120, 60_000_000);

        CamcorderProfile at720 =
                VideoSession.VideoSurface.highSpeedProfile(0, new Size(1280, 720));
        CamcorderProfile at1080 =
                VideoSession.VideoSurface.highSpeedProfile(0, new Size(1920, 1080));

        assertNotNull(at720);
        assertEquals(240, at720.videoFrameRate);
        assertNotNull(at1080);
        assertEquals(120, at1080.videoFrameRate);
    }

    /** A published size does not promise a profile at that size, so there is a floor to fall to. */
    @Test
    public void aSizeWithNoProfileFallsBackToTheLowestTheDeviceHas() {
        addHighSpeedProfile(CamcorderProfile.QUALITY_HIGH_SPEED_LOW,
                new Size(1280, 720), 240, 48_000_000);

        CamcorderProfile profile =
                VideoSession.VideoSurface.highSpeedProfile(0, new Size(3840, 2160));

        assertNotNull(profile);
        assertEquals(240, profile.videoFrameRate);
    }

    /** And a device with none at all says so, rather than handing back something wrong. */
    @Test
    public void aDeviceWithNoHighSpeedProfilesHasNone() {
        assertNull(VideoSession.VideoSurface.highSpeedProfile(0, new Size(1280, 720)));
    }

    /**
     * A camera that publishes no high-speed configurations reports none.
     *
     * <p>Which is every camera the test fakes, and the important half of the contract: a backend
     * that guessed yes would hand an app a frame rate that fails the moment it is recorded at.
     * </p>
     */
    @Test
    public void aCameraWithNoHighSpeedConfigurationsReportsUnsupported() {
        Camera2Module module = givenACamera();

        assertFalse(module.isHighSpeedRecordingSupported());
        assertTrue(module.getSupportedHighSpeedProfiles().isEmpty());
    }

    /** An unpublished choice is declined, and the previous one stands. */
    @Test
    public void anUnpublishedChoiceIsDeclined() {
        Camera2Module module = givenACamera();

        boolean accepted = module.setHighSpeedProfile(
                new HighSpeedProfile(new Size(1280, 720), new Range<>(240, 240)));

        assertFalse(accepted);
        assertNull(module.getHighSpeedProfile());
    }

    /** Asking for ordinary speed always works: there is nothing to publish. */
    @Test
    public void ordinarySpeedIsAlwaysAvailable() {
        Camera2Module module = givenACamera();

        assertTrue(module.setHighSpeedProfile(null));
        assertNull(module.getHighSpeedProfile());
    }

    /** A recording with no high-speed choice cannot be slow motion, so a still may ride along. */
    @Test
    public void anOrdinaryRecordingStillCarriesItsSnapshot() {
        Camera2Module module = givenACamera();

        assertTrue(module.isSnapshotWhileRecordingSupported());
    }

    private Camera2Module givenACamera() {
        FakeCamera2.addBackAndFrontCameras(context);
        cameraView = FakeCamera2.laidOutCameraView(context, 1080, 1920);
        module = new Camera2Module(cameraView);
        return module;
    }

    private static void addHighSpeedProfile(int quality, Size size, int frameRate, int bitRate) {
        ShadowCamcorderProfile.addProfile(0, quality, ShadowCamcorderProfile.createProfile(
                /* duration= */ 30,
                quality,
                /* fileFormat= */ MediaRecorder.OutputFormat.MPEG_4,
                /* videoCodec= */ MediaRecorder.VideoEncoder.H264,
                bitRate,
                frameRate,
                size.getWidth(),
                size.getHeight(),
                /* audioCodec= */ MediaRecorder.AudioEncoder.AAC,
                /* audioBitRate= */ 0,
                /* audioSampleRate= */ 0,
                /* audioChannels= */ 0));
    }
}
