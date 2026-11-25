package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.util.Range;
import android.util.Size;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Choosing what to record slow motion at, from what the camera published.
 *
 * <p>The whole point of the type is that a frame rate is not a choice on its own. A session asked
 * for a size and a frame rate the camera never published fails to configure, taking the recording
 * with it, so every decision here is about staying inside the published set.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class HighSpeedProfileTest {
    private static final Size SIZE_1080P = new Size(1920, 1080);
    private static final Size SIZE_720P = new Size(1280, 720);

    @SafeVarargs
    private static Range<Integer>[] ranges(Range<Integer>... ranges) {
        return ranges;
    }

    private static Range<Integer> fixed(int frameRate) {
        return new Range<>(frameRate, frameRate);
    }

    @Test
    public void aFrameRateIsTheTopOfTheRangeTheCameraPublished() {
        HighSpeedProfile profile = new HighSpeedProfile(SIZE_720P, new Range<>(30, 240));

        assertEquals(240, profile.getFrameRate());
        assertEquals(SIZE_720P, profile.getSize());
        assertEquals(new Range<>(30, 240), profile.getFrameRateRange());
        assertFalse("a range the camera may drop inside is not a fixed rate",
                profile.isFixedFrameRate());
        assertTrue(new HighSpeedProfile(SIZE_720P, fixed(240)).isFixedFrameRate());
    }

    /**
     * One choice per frame rate, and the fixed range wins.
     *
     * <p>A camera publishes both [30, 240] and [240, 240] for the same size, and they are not two
     * choices: they are the same recording, one of which is allowed to slow down when the light
     * goes. A recording whose rate sagged is written as though every frame arrived on time, so it
     * plays back faster than it was shot.</p>
     */
    @Test
    public void onePublishedSizeBecomesOneChoicePerFrameRate() {
        List<HighSpeedProfile> profiles = HighSpeedProfile.forSize(SIZE_720P, ranges(
                new Range<>(30, 120), fixed(120), new Range<>(30, 240), fixed(240)));

        assertEquals(2, profiles.size());
        assertEquals(Arrays.asList(120, 240), frameRatesOf(profiles));
        for (HighSpeedProfile profile : profiles) {
            assertTrue(profile + " should be the fixed form", profile.isFixedFrameRate());
        }
    }

    /** A rate with no fixed form is still a rate this camera records at. */
    @Test
    public void aRatePublishedOnlyAsAVariableRangeIsStillOffered() {
        List<HighSpeedProfile> profiles =
                HighSpeedProfile.forSize(SIZE_720P, ranges(new Range<>(30, 120)));

        assertEquals(1, profiles.size());
        assertEquals(120, profiles.get(0).getFrameRate());
        assertFalse(profiles.get(0).isFixedFrameRate());
    }

    /** A camera that publishes a size and then nothing for it contributes nothing. */
    @Test
    public void nonsensePublishedRangesAreIgnored() {
        assertTrue(HighSpeedProfile.forSize(SIZE_720P, null).isEmpty());
        assertTrue(HighSpeedProfile.forSize(SIZE_720P, ranges()).isEmpty());
        assertTrue(HighSpeedProfile.forSize(SIZE_720P, ranges(fixed(0))).isEmpty());
        assertTrue(HighSpeedProfile.forSize(SIZE_720P, ranges((Range<Integer>) null)).isEmpty());
    }

    /** Slowest first, smallest first at each rate, whatever order the camera listed them in. */
    @Test
    public void theChoicesComeOutInAStableOrder() {
        List<HighSpeedProfile> published = new ArrayList<>(Arrays.asList(
                new HighSpeedProfile(SIZE_720P, fixed(240)),
                new HighSpeedProfile(SIZE_1080P, fixed(120)),
                new HighSpeedProfile(SIZE_720P, fixed(120))));

        List<HighSpeedProfile> ordered = HighSpeedProfile.ordered(published);

        assertEquals(Arrays.asList(120, 120, 240), frameRatesOf(ordered));
        assertEquals(SIZE_720P, ordered.get(0).getSize());
        assertEquals(SIZE_1080P, ordered.get(1).getSize());
        assertEquals("the caller's list is left alone", SIZE_720P, published.get(0).getSize());
    }

    @Test
    public void aPublishedPairIsMatchedExactly() {
        List<HighSpeedProfile> published = published();

        HighSpeedProfile matched = HighSpeedProfile.match(published, SIZE_1080P, 120);

        assertSame(published.get(1), matched);
    }

    /**
     * An unpublished pair is refused rather than approximated.
     *
     * <p>1080p at 240 is the case this exists for: the camera records 240 and it records 1080p,
     * but not both at once, and a session asked for the combination fails.</p>
     */
    @Test
    public void anUnpublishedPairIsRefused() {
        List<HighSpeedProfile> published = published();

        assertNull("1080p240 is not on this camera's list",
                HighSpeedProfile.match(published, SIZE_1080P, 240));
        assertNull("nor is a frame rate it never mentioned",
                HighSpeedProfile.match(published, SIZE_720P, 480));
        assertNull("nor is a size it never mentioned",
                HighSpeedProfile.match(published, new Size(3840, 2160), 120));
        assertNull(HighSpeedProfile.match(Collections.emptyList(), SIZE_720P, 240));
    }

    /** No size named means the largest the camera reaches at that rate. */
    @Test
    public void aRateWithoutASizeTakesTheLargestThatReachesIt() {
        List<HighSpeedProfile> published = published();

        assertEquals(SIZE_1080P, HighSpeedProfile.match(published, null, 120).getSize());
        assertEquals(SIZE_720P, HighSpeedProfile.match(published, null, 240).getSize());
        assertNull(HighSpeedProfile.match(published, null, 480));
    }

    @Test
    public void theFastestChoiceIsTheFastestAtItsLargestSize() {
        List<HighSpeedProfile> published = new ArrayList<>(published());
        published.add(new HighSpeedProfile(new Size(960, 720), fixed(240)));

        HighSpeedProfile fastest = HighSpeedProfile.fastest(published);

        assertEquals(240, fastest.getFrameRate());
        assertEquals(SIZE_720P, fastest.getSize());
        assertNull(HighSpeedProfile.fastest(Collections.emptyList()));
    }

    /** How slow the result plays, which is the number an app puts on a button. */
    @Test
    public void theSlowdownIsTheRatioOfTheTwoRates() {
        HighSpeedProfile profile = new HighSpeedProfile(SIZE_720P, fixed(240));

        assertEquals(8, profile.getSlowdown(30));
        assertEquals(4, profile.getSlowdown(60));
        assertEquals("a capture rate below playback is not slow motion",
                1, new HighSpeedProfile(SIZE_720P, fixed(30)).getSlowdown(60));
        assertEquals("and a playback rate of nothing is not a ratio",
                1, profile.getSlowdown(0));
    }

    @Test
    public void twoChoicesAreTheSameWhenTheCameraPublishedTheSameThing() {
        HighSpeedProfile profile = new HighSpeedProfile(SIZE_720P, fixed(240));

        assertEquals(profile, new HighSpeedProfile(SIZE_720P, fixed(240)));
        assertEquals(profile.hashCode(), new HighSpeedProfile(SIZE_720P, fixed(240)).hashCode());
        assertEquals(profile, profile);
        assertNotEquals(profile, new HighSpeedProfile(SIZE_1080P, fixed(240)));
        assertNotEquals("a fixed rate is not the variable range that ends at it",
                profile, new HighSpeedProfile(SIZE_720P, new Range<>(30, 240)));
        assertNotEquals(profile, SIZE_720P);
        assertTrue(profile.toString().contains("240"));
    }

    /** A plausible phone: 240fps only at 720p, 120fps up to 1080p. */
    private static List<HighSpeedProfile> published() {
        return Arrays.asList(
                new HighSpeedProfile(SIZE_720P, fixed(120)),
                new HighSpeedProfile(SIZE_1080P, fixed(120)),
                new HighSpeedProfile(SIZE_720P, fixed(240)));
    }

    private static List<Integer> frameRatesOf(List<HighSpeedProfile> profiles) {
        List<Integer> frameRates = new ArrayList<>(profiles.size());
        for (HighSpeedProfile profile : profiles) {
            frameRates.add(profile.getFrameRate());
        }
        return frameRates;
    }
}
