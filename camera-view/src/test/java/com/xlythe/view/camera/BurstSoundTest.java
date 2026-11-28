package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.media.AudioAttributes;

import androidx.annotation.NonNull;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A burst's sound: one click looped at the burst's rate from its first picture, stopped with a
 * tone however the burst ends, the click once for a quick release, and nothing more.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class BurstSoundTest {
    /** The end tone, as the recorder names it. */
    private static final String END = "end " + BurstSound.END_TONE_MILLIS;

    private File directory;
    private final List<Float> rates = new ArrayList<>();
    private boolean platformClicks;

    @Before
    public void setUp() {
        BurstSoundRecorder.install();
        directory = new File(RuntimeEnvironment.getApplication().getCacheDir(), "burst-sound");
        assertTrue(directory.isDirectory() || directory.mkdirs());
    }

    @After
    public void tearDown() {
        BurstSoundRecorder.uninstall();
    }

    /** The loop's click is one frame long at the nearest of Samsung's five rates. */
    @Test
    public void theClipIsOneFrameAtTheNearestRate() {
        assertEquals(50, BurstSound.framePeriodMillis(20f));
        assertEquals(50, BurstSound.framePeriodMillis(30f));
        assertEquals(100, BurstSound.framePeriodMillis(10f));
        assertEquals(100, BurstSound.framePeriodMillis(7.4f));
        assertEquals(200, BurstSound.framePeriodMillis(5f));
        assertEquals(250, BurstSound.framePeriodMillis(4f));
        assertEquals(320, BurstSound.framePeriodMillis(3f));
        assertEquals(320, BurstSound.framePeriodMillis(0.8f));
        assertEquals("an unknown rate is 10 a second", 100, BurstSound.framePeriodMillis(0f));
        assertEquals(100, BurstSound.framePeriodMillis(Float.NaN));
        assertEquals(100, BurstSound.framePeriodMillis(Float.POSITIVE_INFINITY));

        for (int period : BurstSound.FRAME_PERIODS_MILLIS) {
            assertEquals(BurstSound.SAMPLE_RATE * period / 1000, BurstSound.click(period).length);
        }
    }

    /** The click is a short transient at the start of the frame, and silence after it. */
    @Test
    public void theClickIsATransientThenSilence() {
        short[] click = BurstSound.click(100);

        assertTrue("it is loud at the start", peak(click, 0, 3) > 0.5f * Short.MAX_VALUE);
        assertTrue("and dies away", peak(click, 25, 100) == 0);
        assertTrue("never clips", peak(click, 0, 100) <= 0.81f * Short.MAX_VALUE);
        assertTrue("the same every time", Arrays.equals(click, BurstSound.click(100)));
    }

    /** The end tone is about 170 ms, two notes, and softer than the click. */
    @Test
    public void theEndToneIsTwoSoftNotes() {
        short[] tone = BurstSound.endTone();

        assertEquals(BurstSound.SAMPLE_RATE * BurstSound.END_TONE_MILLIS / 1000, tone.length);
        assertTrue("the first note sounds", peak(tone, 5, 70) > 0.1f * Short.MAX_VALUE);
        assertTrue("there is a gap between them", peak(tone, 76, 84) < peak(tone, 5, 70) / 4);
        assertTrue("the second note sounds", peak(tone, 90, 160) > 0.05f * Short.MAX_VALUE);
        assertTrue("softer than the click",
                peak(tone, 0, BurstSound.END_TONE_MILLIS) <= 0.31f * Short.MAX_VALUE);
    }

    /** One loop starts with the first picture, and no picture adds a click. */
    @Test
    public void theLoopStartsOnce() {
        Burst burst = burst(10);
        BurstSound sound = sound(burst, 100);

        assertEquals("nothing before the first picture", List.of(), BurstSoundRecorder.EVENTS);
        expose(burst, sound, 4);

        assertEquals(List.of("loop 100"), BurstSoundRecorder.EVENTS);
    }

    /** The clip follows the rate it was made for. */
    @Test
    public void theLoopIsMadeAtTheBurstsRate() {
        for (int period : BurstSound.FRAME_PERIODS_MILLIS) {
            BurstSoundRecorder.EVENTS.clear();
            Burst burst = burst(10);
            expose(burst, sound(burst, period), 1);
            assertEquals(List.of("loop " + period), BurstSoundRecorder.EVENTS);
        }
    }

    /** Stopping the burst stops the loop at once, with pictures still on their way. */
    @Test
    public void stoppingEndsTheLoopAtOnce() {
        Burst burst = burst(10);
        BurstSound sound = sound(burst, 100);
        expose(burst, sound, 2);

        burst.end();

        assertEquals(List.of("loop 100", "stop", END), BurstSoundRecorder.EVENTS);
        sound.onPictureExposed();
        assertEquals("a picture already on its way plays nothing",
                List.of("loop 100", "stop", END), BurstSoundRecorder.EVENTS);
    }

    /** A burst that reaches its count ends as its last picture is exposed. */
    @Test
    public void reachingTheCountEndsTheLoopAtTheLastPicture() {
        Burst burst = burst(3);
        BurstSound sound = sound(burst, 100);
        List<File> files = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            files.add(burst.nextFile());
            sound.onPictureExposed();
            if (i < 2) {
                assertEquals(List.of("loop 100"), BurstSoundRecorder.EVENTS);
            }
        }

        assertEquals(List.of("loop 100", "stop", END), BurstSoundRecorder.EVENTS);
        for (File file : files) {
            burst.onPictureTaken(file);
        }
        assertTrue(burst.isFinished());
        assertEquals("and not again when it finishes",
                List.of("loop 100", "stop", END), BurstSoundRecorder.EVENTS);
    }

    /** A picture that fails ends the burst, and its sound. */
    @Test
    public void aFailedPictureEndsTheLoop() {
        Burst burst = burst(10);
        BurstSound sound = sound(burst, 100);
        File file = burst.nextFile();
        sound.onPictureExposed();

        burst.onPictureFailed(file);

        assertEquals(List.of("loop 100", "stop", END), BurstSoundRecorder.EVENTS);
    }

    /** A camera that stops answering ends the burst, and its sound. */
    @Test
    public void aStalledCameraEndsTheLoop() {
        Burst burst = burst(10);
        BurstSound sound = sound(burst, 100);
        burst.nextFile();
        sound.onPictureExposed();

        ShadowLooper.idleMainLooper(Burst.STALL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

        assertTrue(burst.isFinished());
        assertEquals(List.of("loop 100", "stop", END), BurstSoundRecorder.EVENTS);
    }

    /** A burst that finishes with fewer pictures exposed than asked for still ends its sound. */
    @Test
    public void aBurstThatFinishesShortEndsTheLoop() {
        Burst burst = burst(3);
        BurstSound sound = sound(burst, 100);
        File first = burst.nextFile();
        File second = burst.nextFile();
        File third = burst.nextFile();
        sound.onPictureExposed();
        burst.onPictureTaken(first);
        burst.onPictureTaken(second);
        assertEquals(List.of("loop 100"), BurstSoundRecorder.EVENTS);

        burst.onPictureTaken(third);

        assertEquals(List.of("loop 100", "stop", END), BurstSoundRecorder.EVENTS);
    }

    /** A burst stopped before its first picture plays the click once, and no end tone. */
    @Test
    public void aQuickReleasePlaysTheClickOnce() {
        Burst burst = burst(10);
        BurstSound sound = sound(burst, 100);
        File file = burst.nextFile();

        burst.end();
        assertEquals(List.of(), BurstSoundRecorder.EVENTS);
        sound.onPictureExposed();
        burst.onPictureTaken(file);

        assertEquals(List.of("once 100"), BurstSoundRecorder.EVENTS);
    }

    /** A burst of one picture is a single click. */
    @Test
    public void aBurstOfOnePlaysTheClickOnce() {
        Burst burst = burst(1);
        BurstSound sound = sound(burst, 100);
        File file = burst.nextFile();

        sound.onPictureExposed();
        burst.onPictureTaken(file);

        assertEquals(List.of("once 100"), BurstSoundRecorder.EVENTS);
    }

    /** A burst that ends before any picture is exposed, such as one refused, plays nothing. */
    @Test
    public void aBurstWithNoPicturePlaysNothing() {
        Burst burst = burst(10);
        BurstSound sound = sound(burst, 100);

        burst.end();
        sound.onPictureExposed();

        assertEquals(List.of(), BurstSoundRecorder.EVENTS);
    }

    /** Where the platform clicks for every picture, its clicks stand in for the loop. */
    @Test
    public void aPlatformClickGetsOnlyTheEndTone() {
        platformClicks = true;
        Burst burst = burst(10);
        BurstSound sound = sound(burst, 100);
        expose(burst, sound, 3);

        burst.end();

        assertEquals(List.of(END), BurstSoundRecorder.EVENTS);
    }

    /** And a quick release there is the platform's click alone. */
    @Test
    public void aPlatformClickOnAQuickReleaseIsAlone() {
        platformClicks = true;
        Burst burst = burst(10);
        BurstSound sound = sound(burst, 100);
        burst.nextFile();
        burst.end();

        sound.onPictureExposed();

        assertEquals(List.of(), BurstSoundRecorder.EVENTS);
    }

    /** The rate the burst ran at is measured from its pictures, for the next one. */
    @Test
    public void theRateIsMeasured() {
        Burst burst = burst(3);
        BurstSound sound = sound(burst, 100);
        List<File> files = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            files.add(burst.nextFile());
            sound.onPictureExposed();
            ShadowLooper.idleMainLooper(250, TimeUnit.MILLISECONDS);
        }
        for (File file : files) {
            burst.onPictureTaken(file);
        }

        assertEquals(1, rates.size());
        assertEquals(4f, rates.get(0), 0.01f);
    }

    /** The sound is a sonification, enforced only where it is required. */
    @Test
    public void onlyARequiredSoundIsEnforced() {
        AudioAttributes wanted = BurstSound.TrackPlayer.attributes(false);
        AudioAttributes required = BurstSound.TrackPlayer.attributes(true);

        assertEquals(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, wanted.getUsage());
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, required.getUsage());
        assertFalse((wanted.getFlags() & AudioAttributes.FLAG_AUDIBILITY_ENFORCED) != 0);
        assertTrue((required.getFlags() & AudioAttributes.FLAG_AUDIBILITY_ENFORCED) != 0);
    }

    /** The player is made with whether the sound is required. */
    @Test
    public void thePlayerKnowsWhetherTheSoundIsRequired() {
        sound(burst(2), 100);
        new BurstSound(burst(2), true, () -> false, 100, rate -> {});

        assertEquals(List.of(false, true), BurstSoundRecorder.PLAYERS);
    }

    /**
     * The real player takes every request, in order, without throwing, and a stopped loop is
     * gone. Robolectric has no audio stack to make a track with, so that the loop is heard is
     * shown on the emulator.
     */
    @Test
    public void theTrackPlayerTakesEveryRequest() throws Exception {
        BurstSound.TrackPlayer player = new BurstSound.TrackPlayer(true);
        short[] clip = BurstSound.click(50);

        player.startLoop(clip);
        player.playOnce(clip);
        player.stopLoop();
        assertFalse(player.isLooping());
        player.playEnd(BurstSound.endTone());
        player.stopLoop();

        assertFalse("stopping twice is harmless", player.isLooping());
    }

    private Burst burst(int count) {
        return new Burst(directory, "sound", count, new Burst.Callback() {
            @Override public void onPictureTaken(@NonNull File file) {}
            @Override public void onBurstFinished() {}
        });
    }

    private BurstSound sound(Burst burst, int periodMillis) {
        return new BurstSound(burst, false, () -> platformClicks, periodMillis, rates::add);
    }

    /** Hands out a file and exposes its picture, the given number of times. */
    private static void expose(Burst burst, BurstSound sound, int pictures) {
        for (int i = 0; i < pictures; i++) {
            assertNotNull(burst.nextFile());
            sound.onPictureExposed();
        }
    }

    /** The loudest sample between two times, in milliseconds. */
    private static float peak(short[] pcm, int fromMillis, int toMillis) {
        int from = BurstSound.SAMPLE_RATE * fromMillis / 1000;
        int to = Math.min(pcm.length, BurstSound.SAMPLE_RATE * toMillis / 1000);
        int loudest = 0;
        for (int i = from; i < to; i++) {
            loudest = Math.max(loudest, Math.abs(pcm[i]));
        }
        return loudest;
    }
}
