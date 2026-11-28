package com.xlythe.view.camera;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Stands in for the AudioTrack a burst's sound plays through, and records what it was asked to
 * play: "loop 100" for a loop of a 100 ms click, "once 100" for the click played once, "stop",
 * and "end 170" for the end tone.
 */
public final class BurstSoundRecorder {
    /** Everything played, in order. */
    public static final List<String> EVENTS = new ArrayList<>();

    /** Each player made, by whether its sound was required. */
    public static final List<Boolean> PLAYERS = new ArrayList<>();

    /** The end tone, as recorded. */
    public static final String END = "end " + BurstSound.END_TONE_MILLIS;

    /** A loop of the click for a burst at the given rate, as recorded. */
    @NonNull
    public static String loopAt(float picturesPerSecond) {
        return "loop " + BurstSound.framePeriodMillis(picturesPerSecond);
    }

    private BurstSoundRecorder() {}

    /** Records burst sounds from now on, forgetting any recorded before. */
    public static void install() {
        EVENTS.clear();
        PLAYERS.clear();
        BurstSound.setPlayerFactory(required -> {
            PLAYERS.add(required);
            return new BurstSound.Player() {
                @Override public void startLoop(@NonNull short[] clip) {
                    EVENTS.add("loop " + millis(clip));
                }

                @Override public void playOnce(@NonNull short[] clip) {
                    EVENTS.add("once " + millis(clip));
                }

                @Override public void stopLoop() {
                    EVENTS.add("stop");
                }

                @Override public void playEnd(@NonNull short[] tone) {
                    EVENTS.add("end " + millis(tone));
                }
            };
        });
    }

    /** Puts the AudioTrack back. */
    public static void uninstall() {
        BurstSound.setPlayerFactory(null);
    }

    private static int millis(@NonNull short[] pcm) {
        return pcm.length * 1000 / BurstSound.SAMPLE_RATE;
    }
}
