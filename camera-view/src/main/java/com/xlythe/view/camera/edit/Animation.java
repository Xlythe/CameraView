package com.xlythe.view.camera.edit;

import android.graphics.Bitmap;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;

/**
 * An animated picture's frames, each composed as it is shown, and how long each shows, so any
 * moment of it can be drawn exactly and the same moment is the same frame everywhere.
 */
final class Animation {
    /** Each frame, composed, all the same size. */
    @NonNull final Bitmap[] frames;
    /** How long each frame shows, in milliseconds. */
    @NonNull final int[] delays;
    /** How many times it plays through, or 0 for forever. */
    final int loops;
    /** How long one play through lasts, in milliseconds. */
    final long duration;

    Animation(@NonNull Bitmap[] frames, @NonNull int[] delays, int loops) {
        if (frames.length == 0 || frames.length != delays.length) {
            throw new IllegalArgumentException("An animation has a delay for each frame");
        }
        this.frames = frames;
        this.delays = delays;
        this.loops = loops;
        long total = 0;
        for (int delay : delays) {
            total += Math.max(1, delay);
        }
        this.duration = total;
    }

    /**
     * The frame showing at a moment, playing through as many times as it says and holding the
     * last.
     */
    @AnyThread
    int frameAt(long timeMillis) {
        if (frames.length == 1 || timeMillis <= 0) {
            return 0;
        }
        if (loops > 0 && timeMillis >= duration * loops) {
            return frames.length - 1;
        }
        long at = timeMillis % duration;
        for (int i = 0; i < delays.length; i++) {
            at -= Math.max(1, delays[i]);
            if (at < 0) {
                return i;
            }
        }
        return frames.length - 1;
    }

    /** How long after a moment the frame changes, in milliseconds, or -1 if it never does. */
    @AnyThread
    long untilNextFrame(long timeMillis) {
        if (frames.length == 1 || (loops > 0 && timeMillis >= duration * loops)) {
            return -1;
        }
        long at = Math.max(0, timeMillis) % duration;
        long end = 0;
        for (int delay : delays) {
            end += Math.max(1, delay);
            if (at < end) {
                return end - at;
            }
        }
        return 1;
    }

    /** The frame bitmap showing at a moment. */
    @AnyThread
    @NonNull
    Bitmap bitmapAt(long timeMillis) {
        return frames[frameAt(timeMillis)];
    }

    int width() {
        return frames[0].getWidth();
    }

    int height() {
        return frames[0].getHeight();
    }

    /** Lets go of every frame. */
    void recycle() {
        for (Bitmap frame : frames) {
            frame.recycle();
        }
    }
}
