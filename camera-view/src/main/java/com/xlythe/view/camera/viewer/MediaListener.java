package com.xlythe.view.camera.viewer;

import androidx.annotation.MainThread;

/**
 * Hears whether a {@link MediaView} could show what it was given. Each is called on the main
 * thread, at most once for each file. A video or recording that showed and then cannot play,
 * because the device's decoder gives up on it, is reported as loaded and then as failed.
 */
public interface MediaListener {
    /**
     * The file is showing, as the kind of media it was found to be or was set to show as.
     *
     * @param type one of {@link MediaView}'s media types
     */
    @MainThread
    default void onMediaLoaded(@MediaView.MediaType int type) {}

    /**
     * The file could not be shown: it is missing or unreadable, or this device cannot decode
     * it. MediaView shows its error placeholder in its place.
     */
    @MainThread
    default void onMediaFailed() {}
}
