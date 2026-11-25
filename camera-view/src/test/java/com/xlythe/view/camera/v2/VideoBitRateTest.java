package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * A CamcorderProfile fixes a bit rate for its own resolution, and the recorded frame size is
 * chosen separately, so the rate has to be rescaled to the frame actually being written.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class VideoBitRateTest {
    /** A typical 1080p profile: 17 Mbps at 1920x1080. */
    private static final int PROFILE_BIT_RATE = 17_000_000;
    private static final int PROFILE_WIDTH = 1920;
    private static final int PROFILE_HEIGHT = 1080;

    private static int scale(int width, int height) {
        return VideoSession.VideoSurface.scaleBitRate(
                PROFILE_BIT_RATE, PROFILE_WIDTH, PROFILE_HEIGHT, width, height);
    }

    @Test
    public void aMatchingFrameKeepsTheProfileRate() {
        assertEquals(PROFILE_BIT_RATE, scale(PROFILE_WIDTH, PROFILE_HEIGHT));
    }

    @Test
    public void aSmallerFrameIsNotWrittenAtTheLargerRate() {
        // 720p is 44% of 1080p's pixels, so the rate comes down with it rather than wasting
        // roughly twice the bytes the frame needs.
        int scaled = scale(1280, 720);
        assertEquals(7_555_555, scaled, 100_000);
        assertTrue(scaled < PROFILE_BIT_RATE);
    }

    @Test
    public void aLargerFrameIsNotStarved() {
        // A 4K frame against a 1080p profile needs roughly four times the rate.
        int scaled = scale(3840, 2160);
        assertEquals(68_000_000, scaled, 1_000_000);
        assertTrue(scaled > PROFILE_BIT_RATE);
    }

    @Test
    public void degenerateInputFallsBackRatherThanDividingByZero() {
        assertEquals(PROFILE_BIT_RATE,
                VideoSession.VideoSurface.scaleBitRate(PROFILE_BIT_RATE, 0, 0, 1280, 720));
        assertEquals(PROFILE_BIT_RATE,
                VideoSession.VideoSurface.scaleBitRate(PROFILE_BIT_RATE, PROFILE_WIDTH,
                        PROFILE_HEIGHT, 0, 0));
    }

    @Test
    public void theRateIsClampedAtBothEnds() {
        // A pathologically small frame still gets a usable rate.
        assertTrue(scale(2, 2) >= 64_000);
        // And a pathological profile cannot run away.
        assertTrue(VideoSession.VideoSurface.scaleBitRate(
                Integer.MAX_VALUE, 16, 16, 3840, 2160) <= 100_000_000);
    }
}
