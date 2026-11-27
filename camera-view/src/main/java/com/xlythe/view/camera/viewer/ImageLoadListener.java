package com.xlythe.view.camera.viewer;

import androidx.annotation.MainThread;

/**
 * Hears whether a viewer could show the picture it was given. Both are called on the main
 * thread, once for each picture.
 */
public interface ImageLoadListener {
    /** The picture is showing. Sharper detail may still be on its way. */
    @MainThread
    default void onImageLoaded() {}

    /**
     * The picture could not be shown: the file is missing or unreadable, or it is in a format
     * this device cannot decode.
     */
    @MainThread
    default void onImageFailed() {}
}
