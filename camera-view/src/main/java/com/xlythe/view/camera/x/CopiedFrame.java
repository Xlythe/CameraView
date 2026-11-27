package com.xlythe.view.camera.x;

import android.hardware.camera2.DngCreator;
import android.util.Size;

import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.camera.core.ImageProxy;

import com.xlythe.view.camera.RawCapturePairer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * A RAW frame copied out of the image CameraX handed over.
 *
 * <p>CameraX frees an image's buffer when the camera closes or rebinds, whether or not the image
 * has been closed, so a frame still being written at that moment leaves DngCreator reading freed
 * memory. The pixels are copied out as the image arrives, on the thread that would close the
 * camera, and the image goes straight back.</p>
 */
final class CopiedFrame implements RawCapturePairer.Frame {
    /** RAW_SENSOR is sixteen bits a pixel, which is also how DngCreator wants it packed. */
    private static final int BYTES_PER_PIXEL = 2;

    /** When the exposure started, which pairs the frame with its capture result. */
    private final long mTimestamp;

    /** The frame's size in pixels. */
    @NonNull
    private final Size mSize;

    /** The pixels with no padding between rows, which DngCreator requires of a buffer. */
    @Nullable
    private volatile ByteBuffer mPixels;

    private CopiedFrame(long timestamp, @NonNull Size size, @NonNull ByteBuffer pixels) {
        mTimestamp = timestamp;
        mSize = size;
        mPixels = pixels;
    }

    /** Copies a RAW_SENSOR image. The image stays open; the caller closes it. */
    @MainThread
    @NonNull
    static CopiedFrame copyOf(@NonNull ImageProxy image) {
        ImageProxy.PlaneProxy plane = image.getPlanes()[0];
        return new CopiedFrame(image.getImageInfo().getTimestamp(),
                new Size(image.getWidth(), image.getHeight()),
                pack(plane.getBuffer(), plane.getRowStride(), image.getWidth(),
                        image.getHeight()));
    }

    /**
     * Copies rows of sixteen-bit pixels into a buffer with no padding between them.
     *
     * <p>A camera pads its rows out to whatever stride its hardware prefers, and the last row
     * may stop short of a full stride.</p>
     */
    @VisibleForTesting
    @NonNull
    static ByteBuffer pack(@NonNull ByteBuffer source, int rowStride, int width, int height) {
        int rowBytes = width * BYTES_PER_PIXEL;
        ByteBuffer packed = ByteBuffer.allocateDirect(rowBytes * height);
        ByteBuffer rows = source.duplicate();
        for (int row = 0; row < height; row++) {
            rows.clear();
            rows.position(row * rowStride);
            rows.limit(row * rowStride + rowBytes);
            packed.put(rows);
        }
        packed.flip();
        return packed;
    }

    @Override
    public long getTimestamp() {
        return mTimestamp;
    }

    @Override
    public void writeTo(@NonNull DngCreator creator, @NonNull OutputStream output)
            throws IOException {
        ByteBuffer pixels = mPixels;
        if (pixels == null) {
            throw new IOException("The frame was closed before it was written");
        }
        creator.writeByteBuffer(output, mSize, pixels.duplicate(), 0);
    }

    @AnyThread
    @Override
    public void close() {
        mPixels = null;
    }
}
