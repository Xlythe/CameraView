package com.xlythe.view.camera.edit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.xlythe.view.camera.viewer.AnimatedPng;

import java.io.IOException;

/**
 * Tells an animated sticker from its first bytes, and decodes the ones whose frames the library
 * reads itself.
 *
 * <ul>
 *   <li>An animated PNG, on every release, through the viewers' own decoder.</li>
 *   <li>An animated GIF or WebP from Android 9 plays through the platform's
 *   {@code AnimatedImageDrawable}; see {@link OverlayContent}.</li>
 *   <li>Before Android 9 the platform hands over no frames of a GIF or WebP.
 *   {@link #decodeLegacy} is where the library's own frame decoder, shared with the viewers,
 *   plugs in; without it such a sticker shows its first frame, still.</li>
 * </ul>
 */
final class Animations {
    /** The VP8X flag that says a WebP animates. */
    private static final int WEBP_FLAG_ANIMATION = 0x02;

    private Animations() {}

    /** Whether the bytes are a GIF, animated or not. */
    static boolean isGif(@NonNull byte[] data) {
        return data.length >= 6 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F'
                && data[3] == '8';
    }

    /** Whether the bytes are a WebP that says it animates. */
    static boolean isAnimatedWebp(@NonNull byte[] data) {
        return data.length >= 30 && ascii(data, 0, "RIFF") && ascii(data, 8, "WEBP")
                && ascii(data, 12, "VP8X") && (data[20] & WEBP_FLAG_ANIMATION) != 0;
    }

    /** Whether the bytes are a PNG, animated or not. */
    static boolean isPng(@NonNull byte[] data) {
        return data.length >= 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N'
                && data[3] == 'G';
    }

    /**
     * Decodes an animated PNG into its composed frames.
     *
     * @return its frames, or null for a PNG that does not animate
     * @throws IOException if it cannot be decoded, or its frames together take more pixels
     *     than allowed
     */
    @WorkerThread
    @Nullable
    static Animation decodePng(@NonNull byte[] data, long maxPixels) throws IOException {
        AnimatedPng png = AnimatedPng.decode(data);
        if (png == null) {
            return null;
        }
        Animation animation = new Animation(png.frames, png.delays, png.plays);
        if ((long) animation.width() * animation.height() * animation.frames.length > maxPixels) {
            animation.recycle();
            throw new IOException("An animation of more than " + maxPixels + " pixels");
        }
        return animation;
    }

    /**
     * The frames of an animated GIF or WebP on a release before Android 9, from the library's
     * own frame decoder shared with the viewers, which plugs in here. Until it does, there are
     * none and the sticker shows still.
     *
     * @return its frames, or null if they cannot be read here
     */
    @WorkerThread
    @Nullable
    static Animation decodeLegacy(@NonNull byte[] data, long maxPixels) {
        return null;
    }

    private static boolean ascii(@NonNull byte[] data, int offset, @NonNull String text) {
        for (int i = 0; i < text.length(); i++) {
            if (data[offset + i] != text.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
