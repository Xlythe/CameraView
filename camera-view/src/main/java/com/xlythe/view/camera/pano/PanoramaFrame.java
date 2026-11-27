package com.xlythe.view.camera.pano;

import android.graphics.ImageFormat;
import android.media.Image;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;
import androidx.annotation.WorkerThread;

import java.nio.ByteBuffer;

/**
 * One camera frame on its way into a sweep, from whichever backend produced it.
 *
 * <p>Two shapes of frame arrive. The backends built on camera2 hand over an {@link Image} with
 * three planes, each with its own strides. The original camera API hands over a byte array in
 * NV21, which is the same three planes packed into one buffer with V before U. Both are read
 * through the same plane description, so nothing downstream knows which it was given.</p>
 *
 * <p>A frame wrapping an {@link Image} reads the image's own buffers, so it is only valid until
 * the image is closed; the sweep converts it before then.</p>
 */
@RequiresApi(19)
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class PanoramaFrame {
    /** The frame's width, as the sensor wrote it. */
    final int width;

    /** The frame's height, as the sensor wrote it. */
    final int height;

    /** The clockwise turn that stands the frame upright. */
    final int rotationDegrees;

    /** Whether the frame came from a lens facing the user. */
    final boolean frontFacing;

    /** When the frame was exposed, on the {@code SystemClock.elapsedRealtimeNanos} clock. */
    final long timestampNanos;

    /** The brightness plane, full resolution. */
    private final Plane mY;

    /** The blue-difference plane, half resolution both ways. */
    private final Plane mU;

    /** The red-difference plane, half resolution both ways. */
    private final Plane mV;

    private PanoramaFrame(int width, int height, int rotationDegrees, boolean frontFacing,
            long timestampNanos, @NonNull Plane y, @NonNull Plane u, @NonNull Plane v) {
        this.width = width;
        this.height = height;
        this.rotationDegrees = rotationDegrees;
        this.frontFacing = frontFacing;
        this.timestampNanos = timestampNanos;
        mY = y;
        mU = u;
        mV = v;
    }

    /**
     * Wraps a YUV_420_888 image, which must stay open until the frame has been converted.
     *
     * @param elapsedNow {@code SystemClock.elapsedRealtimeNanos()} as the image arrived
     * @param monotonicNow {@code System.nanoTime()} as the image arrived
     * @return the frame, or null for an image in any other format
     */
    @Nullable
    public static PanoramaFrame fromImage(@NonNull Image image, int rotationDegrees,
            boolean frontFacing, long elapsedNow, long monotonicNow) {
        if (image.getFormat() != ImageFormat.YUV_420_888) {
            return null;
        }
        Image.Plane[] planes = image.getPlanes();
        return new PanoramaFrame(image.getWidth(), image.getHeight(), rotationDegrees,
                frontFacing,
                TimeBase.toElapsedRealtime(image.getTimestamp(), elapsedNow, monotonicNow),
                new Plane(planes[0].getBuffer(), planes[0].getRowStride(),
                        planes[0].getPixelStride()),
                new Plane(planes[1].getBuffer(), planes[1].getRowStride(),
                        planes[1].getPixelStride()),
                new Plane(planes[2].getBuffer(), planes[2].getRowStride(),
                        planes[2].getPixelStride()));
    }

    /**
     * Wraps an NV21 preview frame, which has no capture time of its own.
     *
     * @param arrivedNanos {@code SystemClock.elapsedRealtimeNanos()} as the frame arrived, which
     *     stands in for when it was captured
     */
    @NonNull
    public static PanoramaFrame fromNv21(@NonNull byte[] data, int width, int height,
            int rotationDegrees, boolean frontFacing, long arrivedNanos) {
        int luma = width * height;
        int chroma = data.length - luma;
        // V comes first in NV21, interleaved with U: VUVU..., one pair per two-by-two block.
        return new PanoramaFrame(width, height, rotationDegrees, frontFacing, arrivedNanos,
                new Plane(ByteBuffer.wrap(data, 0, luma).slice(), width, 1),
                new Plane(ByteBuffer.wrap(data, luma + 1, chroma - 1).slice(), width, 2),
                new Plane(ByteBuffer.wrap(data, luma, chroma).slice(), width, 2));
    }

    /**
     * The frame as ARGB, shrunk by a whole factor if it is longer than {@code maxSide}.
     *
     * <p>Shrunk by averaging each block rather than taking every nth pixel, which would alias
     * fine detail into moire that no later blending can take out.</p>
     *
     * @param scratch buffers reused between frames; any size, grown as needed
     */
    @NonNull
    @WorkerThread
    ArgbImage toArgb(int maxSide, @NonNull Scratch scratch) {
        int step = shrinkFactor(maxSide);
        int outWidth = width / step;
        int outHeight = height / step;
        byte[] y = scratch.y = mY.copy(scratch.y);
        byte[] u = scratch.u = mU.copy(scratch.u);
        byte[] v = scratch.v = mV.copy(scratch.v);
        int[] pixels = new int[outWidth * outHeight];
        int blockArea = step * step;
        for (int row = 0; row < outHeight; row++) {
            int top = row * step;
            int chromaRow = (top + step / 2) / 2;
            for (int column = 0; column < outWidth; column++) {
                int left = column * step;
                int sum = 0;
                for (int dy = 0; dy < step; dy++) {
                    int start = (top + dy) * mY.rowStride + left * mY.pixelStride;
                    for (int dx = 0; dx < step; dx++) {
                        sum += y[start + dx * mY.pixelStride] & 0xFF;
                    }
                }
                int chromaColumn = (left + step / 2) / 2;
                int luma = sum / blockArea;
                int blue = (u[chromaRow * mU.rowStride + chromaColumn * mU.pixelStride] & 0xFF)
                        - 128;
                int red = (v[chromaRow * mV.rowStride + chromaColumn * mV.pixelStride] & 0xFF)
                        - 128;
                pixels[row * outWidth + column] = toArgb(luma, blue, red);
            }
        }
        return new ArgbImage(pixels, outWidth, outHeight);
    }

    /** What {@link #toArgb} divides each side by to fit {@code maxSide}. */
    int shrinkFactor(int maxSide) {
        return Math.max(1, (Math.max(width, height) + maxSide - 1) / maxSide);
    }

    /** Full-range BT.601, which is what camera YUV is, in fixed point with ten fractional bits. */
    static int toArgb(int luma, int blue, int red) {
        int r = clamp(luma + ((1436 * red) >> 10));
        int g = clamp(luma - ((352 * blue + 731 * red) >> 10));
        int b = clamp(luma + ((1815 * blue) >> 10));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }

    /** Byte arrays a sweep keeps between frames, so converting one does not allocate them. */
    static final class Scratch {
        /** The brightness plane's bytes. */
        @Nullable byte[] y;

        /** The blue-difference plane's bytes. */
        @Nullable byte[] u;

        /** The red-difference plane's bytes. */
        @Nullable byte[] v;
    }

    /** One plane of a frame: its bytes, and how far apart its rows and its pixels are. */
    private static final class Plane {
        /** The plane's bytes, from its first pixel. */
        final ByteBuffer buffer;

        /** Bytes from the start of one row to the start of the next. */
        final int rowStride;

        /** Bytes from one pixel to the next along a row. */
        final int pixelStride;

        Plane(@NonNull ByteBuffer buffer, int rowStride, int pixelStride) {
            this.buffer = buffer;
            this.rowStride = rowStride;
            this.pixelStride = pixelStride;
        }

        /** Copies the plane out in one bulk read, which is far cheaper than a read per pixel. */
        @NonNull
        byte[] copy(@Nullable byte[] reuse) {
            ByteBuffer source = buffer.duplicate();
            source.rewind();
            int size = source.remaining();
            byte[] bytes = reuse != null && reuse.length >= size ? reuse : new byte[size];
            source.get(bytes, 0, size);
            return bytes;
        }
    }
}
