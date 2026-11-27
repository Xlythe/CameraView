package com.xlythe.view.camera;

import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;
import androidx.annotation.WorkerThread;

import java.nio.ByteBuffer;

/**
 * One preview frame's brightness plane, as a backend hands it to the subject tracker.
 *
 * <p>Brightness alone, because that is all the tracker reads, and because it is the one plane
 * every source has in the same layout: the Y plane of a YUV image and the first part of the old
 * camera API's NV21 buffer are both a byte per pixel, row after row.</p>
 *
 * <p>Valid only for the duration of the {@link Listener#onFrame} call that received it.</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class LumaFrame {
    /** Receives frames on a background thread. */
    public interface Listener {
        @WorkerThread
        void onFrame(@NonNull LumaFrame frame);
    }

    /** One byte per pixel, starting at index zero. */
    @NonNull
    final ByteBuffer luma;

    /** The frame's size, in the sensor's orientation. */
    final int width;

    /** See {@link #width}. */
    final int height;

    /** Bytes from the start of one row to the start of the next, which is at least the width. */
    final int rowStride;

    /** The clockwise rotation, in degrees, of the sensor relative to the device's natural orientation. */
    final int sensorOrientation;

    /** Whether the frame came from a user-facing lens, whose preview is shown mirrored. */
    final boolean mirrored;

    public LumaFrame(@NonNull ByteBuffer luma, int width, int height, int rowStride,
            int sensorOrientation, boolean mirrored) {
        this.luma = luma;
        this.width = width;
        this.height = height;
        this.rowStride = rowStride;
        this.sensorOrientation = sensorOrientation;
        this.mirrored = mirrored;
    }
}
