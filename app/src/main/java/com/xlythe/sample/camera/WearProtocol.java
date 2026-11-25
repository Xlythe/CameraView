package com.xlythe.sample.camera;

import android.util.Size;

/**
 * What the phone and the watch say to each other.
 *
 * <p>Mirrored in the watch module on purpose: they are separate applications that happen to live
 * in one repository, and a shared Gradle module for a handful of constants would be more
 * machinery than it saves. A change has to be made in both copies.</p>
 *
 * <p>The split between messages and channels is the Data Layer's own: a message is a small
 * one-shot notification, and a channel is a socket, which is what a preview or a photo needs.
 * What a channel is not is fast. The Data Layer prefers Bluetooth and only uses Wi-Fi when there
 * is no Bluetooth at all, so a realistic budget is a couple of megabits - which is why the
 * preview is sent at a size and bit rate a watch link can carry rather than whatever the phone's
 * camera would like to send.</p>
 */
final class WearProtocol {
    /** The watch asking the phone to start sending a preview. */
    static final String PATH_START_PREVIEW = "/camera-view/start-preview";

    /** The watch asking the phone to stop. */
    static final String PATH_STOP_PREVIEW = "/camera-view/stop-preview";

    /** The watch asking the phone to take a picture and send it back. */
    static final String PATH_TAKE_PICTURE = "/camera-view/take-picture";

    /** The channel the phone streams the preview over. */
    static final String PATH_PREVIEW = "/camera-view/preview";

    /** The channel the phone sends a captured picture over. */
    static final String PATH_PICTURE = "/camera-view/picture";

    /**
     * The preview the phone sends.
     *
     * <p>480p at a megabit is about what a Bluetooth link will carry without the stream falling
     * behind, and a watch screen cannot show more than that anyway. The key frame interval is the
     * part that is easy to get wrong: a stream can only be joined at a key frame, so a long
     * interval means the watch stares at black for that long after it connects.</p>
     */
    static final Size PREVIEW_SIZE = new Size(640, 480);

    static final int PREVIEW_BIT_RATE = 1_000_000;

    static final int PREVIEW_FRAME_RATE = 20;

    static final int PREVIEW_KEY_FRAME_INTERVAL_SECONDS = 1;

    private WearProtocol() {}
}
