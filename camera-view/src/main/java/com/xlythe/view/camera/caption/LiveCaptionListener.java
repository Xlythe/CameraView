package com.xlythe.view.camera.caption;

import androidx.annotation.NonNull;

/**
 * Told what is being said, as it is said. Called on the main thread.
 *
 * <p>The same line arrives several times: once as soon as there are words, again as more of the
 * sentence is heard, again when its translation turns up, and a last time once the recogniser
 * has settled on it. So a caller replaces the line it is showing rather than adding to it, and
 * keeps it only when {@link LiveCaption#isFinal} says it has stopped changing.</p>
 */
public interface LiveCaptionListener {
    /**
     * A line, which may be the same line as last time with more of it heard.
     *
     * <p>Replace whatever is on screen with this. Appending instead is the mistake the repeated
     * delivery invites, and it shows up as the first few words of every sentence doubling.</p>
     */
    void onLiveCaption(@NonNull LiveCaption caption);

    /**
     * Captioning has stopped and will not start again on its own, and this is why.
     *
     * <p>Not called for the ordinary pauses: a silence, a phrase the recogniser could not make
     * out, a timeout. Those are what a conversation is made of and captioning carries on
     * through them. This is for the things it cannot carry on through - no permission, no
     * recogniser, a language it does not have.</p>
     */
    void onLiveCaptionFailed(@NonNull String reason);

    /**
     * Whether the translation this asked for can be made on this device.
     *
     * <p>Called once, before any captions, when a translation was asked for. False means the
     * captions will arrive with no translation attached - the words are still worth showing,
     * and saying so lets a caller explain the gap rather than leaving somebody waiting for a
     * second line that is not coming.</p>
     */
    default void onTranslationAvailable(boolean available) {}
}
