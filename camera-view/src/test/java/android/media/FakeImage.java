package android.media;

import android.graphics.ImageFormat;

import java.nio.ByteBuffer;

/**
 * An {@link Image} a test can build, carrying bytes it chose.
 *
 * <p>Lives in {@code android.media} because {@link Image} and {@link Image.Plane} both have
 * package-private constructors, so there is no way to extend either from outside. Robolectric
 * shadows ImageReader's native buffer plumbing but gives no way to hand it a frame, and the
 * camera that would normally produce one draws nothing under test.</p>
 */
public final class FakeImage extends Image {
    private final int format;
    private final int width;
    private final int height;
    private final long timestamp;
    private final Plane[] planes;
    private boolean closed;

    /** A JPEG frame whose single plane holds exactly these bytes. */
    public static FakeImage jpeg(int width, int height, byte[] bytes) {
        return new FakeImage(ImageFormat.JPEG, width, height, bytes);
    }

    /** A frame in a format nothing in the library knows how to decode. */
    public static FakeImage unsupportedFormat(int width, int height) {
        return new FakeImage(ImageFormat.RAW10, width, height, new byte[] {1, 2, 3, 4});
    }

    /** A frame in the format a camera produces for analysis. */
    public static FakeImage yuv(int width, int height) {
        return new FakeImage(
                ImageFormat.YUV_420_888, width, height, new byte[width * height]);
    }

    /**
     * A three-plane YUV_420_888 frame, laid out the way a camera really lays one out.
     *
     * <p>{@link #yuv} carries a single plane, which is enough for a reader that only measures a
     * frame and not enough for anything that converts one. A real frame has a luma plane whose
     * rows are padded out to a hardware-friendly stride, and chroma planes that are either
     * separate (planar, pixel stride 1) or two views into one interleaved buffer (semi-planar,
     * pixel stride 2). Both layouts are in the wild and they are converted differently, so a
     * fake offering only one of them leaves the other untested.</p>
     *
     * @param rowPadding bytes of padding after each luma row, making the stride wider than the
     *     image
     * @param uvPixelStride 1 for separate chroma planes, 2 for a single interleaved buffer
     */
    public static FakeImage yuv420(int width, int height, int rowPadding, int uvPixelStride) {
        if (uvPixelStride != 1 && uvPixelStride != 2) {
            throw new IllegalArgumentException(
                    "a chroma pixel stride is 1 or 2, not " + uvPixelStride);
        }
        int lumaStride = width + rowPadding;
        byte[] luma = new byte[lumaStride * height];
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                luma[row * lumaStride + col] = expectedLuma(width, row, col);
            }
            for (int pad = width; pad < lumaStride; pad++) {
                // Padding that a correct conversion never reads, poisoned so that reading it
                // shows up in an assertion rather than passing as plausible image data.
                luma[row * lumaStride + pad] = POISON;
            }
        }

        int chromaWidth = width / 2;
        int chromaHeight = height / 2;
        Plane lumaPlane = new FakePlane(ByteBuffer.wrap(luma), 1, lumaStride);
        Plane uPlane;
        Plane vPlane;
        if (uvPixelStride == 2) {
            // Semi-planar: one buffer of V, U, V, U..., which is what NV21 hardware hands over,
            // seen through two planes that start one byte apart.
            byte[] chroma = new byte[chromaWidth * chromaHeight * 2];
            for (int i = 0; i < chromaWidth * chromaHeight; i++) {
                chroma[i * 2] = expectedV(i);
                chroma[i * 2 + 1] = expectedU(i);
            }
            vPlane = new FakePlane(from(chroma, 0), 2, chromaWidth * 2);
            uPlane = new FakePlane(from(chroma, 1), 2, chromaWidth * 2);
        } else {
            byte[] u = new byte[chromaWidth * chromaHeight];
            byte[] v = new byte[chromaWidth * chromaHeight];
            for (int i = 0; i < u.length; i++) {
                v[i] = expectedV(i);
                u[i] = expectedU(i);
            }
            uPlane = new FakePlane(ByteBuffer.wrap(u), 1, chromaWidth);
            vPlane = new FakePlane(ByteBuffer.wrap(v), 1, chromaWidth);
        }
        return new FakeImage(ImageFormat.YUV_420_888, width, height,
                new Plane[] {lumaPlane, uPlane, vPlane});
    }

    /** The byte that stands in for luma-row padding, which nothing should read. */
    public static final byte POISON = (byte) 0xEE;

    /** The luma byte at a pixel, whatever the rows are padded to. */
    public static byte expectedLuma(int width, int row, int col) {
        return (byte) (1 + row * width + col);
    }

    /** The chroma byte a correct conversion should read for V at this position. */
    public static byte expectedV(int index) {
        return (byte) (0x80 + index);
    }

    /** And for U. */
    public static byte expectedU(int index) {
        return (byte) (0xC0 + index);
    }

    /** A buffer over the array from an offset, positioned at zero as a plane buffer is. */
    private static ByteBuffer from(byte[] array, int offset) {
        ByteBuffer buffer = ByteBuffer.wrap(array);
        buffer.position(offset);
        return buffer.slice();
    }

    private FakeImage(int format, int width, int height, byte[] bytes) {
        this(format, width, height,
                new Plane[] {new FakePlane(ByteBuffer.wrap(bytes), 1, width)});
    }

    private FakeImage(int format, int width, int height, Plane[] planes) {
        this.format = format;
        this.width = width;
        this.height = height;
        this.timestamp = System.nanoTime();
        this.planes = planes;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public int getFormat() {
        return format;
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public Plane[] getPlanes() {
        return planes;
    }

    @Override
    public void close() {
        closed = true;
    }

    private static final class FakePlane extends Plane {
        private final ByteBuffer bytes;
        private final int pixelStride;
        private final int rowStride;

        FakePlane(ByteBuffer bytes, int pixelStride, int rowStride) {
            this.bytes = bytes;
            this.pixelStride = pixelStride;
            this.rowStride = rowStride;
        }

        @Override
        public ByteBuffer getBuffer() {
            // A fresh view each time, since a reader consumes it by position. Duplicated rather
            // than rewound, so that two readers cannot tread on each other.
            return bytes.duplicate();
        }

        @Override
        public int getPixelStride() {
            return pixelStride;
        }

        @Override
        public int getRowStride() {
            return rowStride;
        }
    }
}
