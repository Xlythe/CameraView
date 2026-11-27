package com.xlythe.view.camera.viewer;

import android.graphics.Bitmap;
import android.graphics.Rect;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

/**
 * A picture that can be decoded a piece at a time, so that only the pieces on screen, at the
 * detail the screen can show, are ever in memory.
 */
interface RegionSource {
    /** The picture's width as stored, before any Exif rotation. */
    @AnyThread
    int getWidth();

    /** The picture's height as stored. */
    @AnyThread
    int getHeight();

    /**
     * Decodes part of the picture.
     *
     * @param region the part, in stored pixels, inside the picture
     * @param sampleSize a power of two: how many stored pixels across each decoded one stands for
     * @return the part, about {@code region / sampleSize} in size, or null if it cannot be read.
     *     Never larger than that.
     */
    @WorkerThread
    @Nullable
    Bitmap decode(@NonNull Rect region, int sampleSize);

    /** Lets go of the file. Decoding after this returns null. */
    @AnyThread
    void close();
}
