package com.xlythe.view.camera.viewer;

import androidx.annotation.MainThread;

/**
 * Hears whether a {@link MediaView} could show what it was given. Both are called on the main
 * thread, once for each file.
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
     * it.
     */
    @MainThread
    default void onMediaFailed() {}
}
