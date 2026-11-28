package com.xlythe.view.camera.viewer;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.WorkerThread;

import java.io.IOException;

/** The animated PNG decoder, for the editor's stickers as well as the viewers. */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class AnimatedPng {
    /** Each frame, composed, all the same size. */
    @NonNull public final Bitmap[] frames;
    /** How long each frame shows, in milliseconds. */
    @NonNull public final int[] delays;
    /** How many times it plays through, or 0 for forever. */
    public final int plays;

    private AnimatedPng(@NonNull Bitmap[] frames, @NonNull int[] delays, int plays) {
        this.frames = frames;
        this.delays = delays;
        this.plays = plays;
    }

    /**
     * Decodes an animated PNG, within the viewers' memory budget.
     *
     * @return its frames, or null for a PNG that does not animate
     * @throws IOException if it is not a PNG, or a frame will not decode
     */
    @WorkerThread
    @Nullable
    public static AnimatedPng decode(@NonNull byte[] png) throws IOException {
        ApngDecoder.Animation animation = ApngDecoder.decode(png);
        return animation == null ? null
                : new AnimatedPng(animation.frames, animation.delays, animation.plays);
    }
}
