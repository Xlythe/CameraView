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

    private FakeImage(int format, int width, int height, byte[] bytes) {
        this.format = format;
        this.width = width;
        this.height = height;
        this.timestamp = System.nanoTime();
        this.planes = new Plane[] {new FakePlane(bytes, width)};
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
        private final byte[] bytes;
        private final int rowStride;

        FakePlane(byte[] bytes, int rowStride) {
            this.bytes = bytes;
            this.rowStride = rowStride;
        }

        @Override
        public ByteBuffer getBuffer() {
            // A fresh buffer each time, since the reader consumes it by position.
            return ByteBuffer.wrap(bytes);
        }

        @Override
        public int getPixelStride() {
            return 1;
        }

        @Override
        public int getRowStride() {
            return rowStride;
        }
    }
}
