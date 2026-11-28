package com.xlythe.view.camera.edit;

import android.graphics.Bitmap;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Writes a picture as a HEIF (HEVC) or AVIF (AV1) still with the device's own video encoder and
 * the platform's HEIF muxer, so it needs no library of its own.
 *
 * <p>A picture larger than a tile is cut into a grid of tiles, each encoded as a frame, which
 * the muxer joins into one image, as cameras write them. A small picture whose width or height
 * is odd is cut in two the same way, since the encoders take even sizes only and the grid gives
 * the picture its exact size.</p>
 */
@RequiresApi(28)
final class HeifEncoder {
    /** The tag this logs under. */
    private static final String TAG = "HeifEncoder";

    /** The largest tile, as cameras and Android's own HEIF writer use. */
    @VisibleForTesting
    static final int TILE = 512;

    /** The image MIME type AV1 stills are muxed as. */
    private static final String MIMETYPE_IMAGE_AVIF = "image/avif";

    /** The frame rate given to the encoder, which only shapes its bit budget. */
    private static final int FRAME_RATE = 30;

    /** How long to wait for a codec buffer, in microseconds. */
    private static final long TIMEOUT_US = 10_000;

    /** How many waits in a row with no progress before giving up: about ten seconds. */
    private static final int MAX_IDLE_WAITS = 1000;

    /** How the picture is cut up. */
    @VisibleForTesting
    static final class Grid {
        /** The width of each tile. */
        final int tileWidth;
        /** The height of each tile. */
        final int tileHeight;
        /** The columns of tiles. */
        final int columns;
        /** The rows of tiles. */
        final int rows;

        Grid(int tileWidth, int tileHeight, int columns, int rows) {
            this.tileWidth = tileWidth;
            this.tileHeight = tileHeight;
            this.columns = columns;
            this.rows = rows;
        }

        /** Whether there is more than one tile, so the muxer joins them as a grid. */
        boolean isGrid() {
            return columns * rows > 1;
        }
    }

    private HeifEncoder() {}

    /** Whether this device can write the format: HEIF from Android 9, AVIF from Android 14. */
    @WorkerThread
    static boolean isSupported(boolean avif) {
        if (avif && Build.VERSION.SDK_INT < 34) {
            return false;
        }
        return findEncoder(videoMime(avif)) != null;
    }

    /**
     * How a picture is cut into tiles: one tile when it fits in one and its sides are even, or
     * else a grid of tiles no larger than {@link #TILE} whose sides are even.
     */
    @VisibleForTesting
    @NonNull
    static Grid gridFor(int width, int height) {
        if (width <= TILE && height <= TILE && width % 2 == 0 && height % 2 == 0) {
            return new Grid(width, height, 1, 1);
        }
        int columns = Math.max(width > TILE ? divideRoundingUp(width, TILE) : 1,
                width % 2 == 0 || width > TILE ? 1 : 2);
        int rows = height > TILE ? divideRoundingUp(height, TILE) : 1;
        if (columns * rows == 1) {
            // The width is even, so the height is odd: cut across instead.
            rows = 2;
        }
        int tileWidth = Math.max(2, roundUpToEven(divideRoundingUp(width, columns)));
        int tileHeight = Math.max(2, roundUpToEven(divideRoundingUp(height, rows)));
        return new Grid(tileWidth, tileHeight, columns, rows);
    }

    /**
     * Encodes a picture into a file, which is overwritten.
     *
     * @param quality 0 to 100, as {@link Bitmap#compress} takes it
     * @throws IOException if the device has no encoder for it, or the encoder fails
     */
    @WorkerThread
    static void encode(@NonNull Bitmap bitmap, int quality, boolean avif, @NonNull File output)
            throws IOException {
        String mime = videoMime(avif);
        MediaCodecInfo info = findEncoder(mime);
        if (info == null) {
            throw new IOException("This device has no " + mime + " encoder");
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        Grid grid = gridFor(width, height);
        MediaCodecInfo.CodecCapabilities capabilities = info.getCapabilitiesForType(mime);
        MediaCodecInfo.VideoCapabilities video = capabilities.getVideoCapabilities();
        if (video != null && !video.isSizeSupported(grid.tileWidth, grid.tileHeight)) {
            throw new IOException(info.getName() + " cannot encode " + grid.tileWidth + "x"
                    + grid.tileHeight + " tiles");
        }

        MediaFormat format = MediaFormat.createVideoFormat(mime, grid.tileWidth, grid.tileHeight);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0);
        format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_PAL);
        format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL);
        format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
        MediaCodecInfo.EncoderCapabilities encoder = capabilities.getEncoderCapabilities();
        if (encoder != null && encoder.isBitrateModeSupported(
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)) {
            format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ);
            int low = encoder.getQualityRange().getLower();
            int high = encoder.getQualityRange().getUpper();
            format.setInteger(MediaFormat.KEY_QUALITY,
                    low + Math.round((high - low) * quality / 100f));
        } else {
            // About a tenth of a bit a pixel at the bottom of the range, four at the top.
            float bitsPerPixel = 0.1f + 3.9f * quality / 100f;
            format.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(100_000,
                    Math.round(grid.tileWidth * grid.tileHeight * bitsPerPixel * FRAME_RATE)));
        }

        MediaCodec codec = MediaCodec.createByCodecName(info.getName());
        MediaMuxer muxer = null;
        boolean muxing = false;
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            muxer = new MediaMuxer(output.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_HEIF);
            int track = -1;
            int tiles = grid.columns * grid.rows;
            int queued = 0;
            int written = 0;
            int idle = 0;
            int[] pixels = new int[grid.tileWidth * grid.tileHeight];
            MediaCodec.BufferInfo buffer = new MediaCodec.BufferInfo();
            boolean ended = false;
            while (!ended) {
                boolean progressed = false;
                if (queued <= tiles) {
                    int index = codec.dequeueInputBuffer(TIMEOUT_US);
                    if (index >= 0) {
                        progressed = true;
                        long time = queued * 1_000_000L / FRAME_RATE;
                        if (queued == tiles) {
                            codec.queueInputBuffer(index, 0, 0, time,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        } else {
                            Image image = codec.getInputImage(index);
                            if (image == null) {
                                throw new IOException(info.getName()
                                        + " does not take its input as an image");
                            }
                            int column = queued % grid.columns;
                            int row = queued / grid.columns;
                            readTile(bitmap, column * grid.tileWidth, row * grid.tileHeight,
                                    grid.tileWidth, grid.tileHeight, pixels);
                            writeYuv(pixels, grid.tileWidth, grid.tileHeight, image);
                            codec.queueInputBuffer(index, 0,
                                    grid.tileWidth * grid.tileHeight * 3 / 2, time, 0);
                        }
                        queued++;
                    }
                }
                int index = codec.dequeueOutputBuffer(buffer, TIMEOUT_US);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    progressed = true;
                    MediaFormat produced = codec.getOutputFormat();
                    produced.setString(MediaFormat.KEY_MIME,
                            avif ? MIMETYPE_IMAGE_AVIF : MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC);
                    produced.setInteger(MediaFormat.KEY_WIDTH, width);
                    produced.setInteger(MediaFormat.KEY_HEIGHT, height);
                    if (grid.isGrid()) {
                        produced.setInteger(MediaFormat.KEY_TILE_WIDTH, grid.tileWidth);
                        produced.setInteger(MediaFormat.KEY_TILE_HEIGHT, grid.tileHeight);
                        produced.setInteger(MediaFormat.KEY_GRID_COLUMNS, grid.columns);
                        produced.setInteger(MediaFormat.KEY_GRID_ROWS, grid.rows);
                    }
                    track = muxer.addTrack(produced);
                    muxer.start();
                    muxing = true;
                } else if (index >= 0) {
                    progressed = true;
                    ByteBuffer data = codec.getOutputBuffer(index);
                    boolean config = (buffer.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (!config && buffer.size > 0 && data != null) {
                        if (!muxing) {
                            throw new IOException(info.getName() + " gave a frame before its "
                                    + "format");
                        }
                        data.position(buffer.offset);
                        data.limit(buffer.offset + buffer.size);
                        muxer.writeSampleData(track, data, buffer);
                        written++;
                    }
                    codec.releaseOutputBuffer(index, false);
                    ended = (buffer.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                }
                idle = progressed ? 0 : idle + 1;
                if (idle > MAX_IDLE_WAITS) {
                    throw new IOException(info.getName() + " stopped after " + written + " of "
                            + tiles + " tiles");
                }
            }
            if (written != tiles) {
                throw new IOException(info.getName() + " gave " + written + " of " + tiles
                        + " tiles");
            }
            muxer.stop();
            muxing = false;
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new IOException("Unable to encode a " + width + "x" + height + " "
                    + (avif ? "AVIF" : "HEIF"), e);
        } finally {
            try {
                codec.stop();
            } catch (IllegalStateException alreadyStopped) {
                Log.d(TAG, "The encoder had already stopped", alreadyStopped);
            }
            codec.release();
            if (muxer != null) {
                if (muxing) {
                    try {
                        muxer.stop();
                    } catch (IllegalStateException failed) {
                        Log.d(TAG, "The muxer could not finish an abandoned file", failed);
                    }
                }
                muxer.release();
            }
        }
    }

    /** The video MIME type whose encoder makes the format. */
    @NonNull
    private static String videoMime(boolean avif) {
        return avif ? MediaFormat.MIMETYPE_VIDEO_AV1 : MediaFormat.MIMETYPE_VIDEO_HEVC;
    }

    /** An encoder for the MIME type that takes flexible YUV input, or null. */
    @Nullable
    private static MediaCodecInfo findEncoder(@NonNull String mime) {
        MediaCodecInfo found = null;
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS)
                .getCodecInfos()) {
            if (!info.isEncoder() || !supports(info, mime)) {
                continue;
            }
            MediaCodecInfo.CodecCapabilities capabilities = info.getCapabilitiesForType(mime);
            boolean flexible = false;
            for (int color : capabilities.colorFormats) {
                flexible |= color
                        == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;
            }
            if (!flexible) {
                continue;
            }
            // A hardware encoder first, for speed; a software one otherwise.
            if (Build.VERSION.SDK_INT >= 29 ? info.isHardwareAccelerated()
                    : !info.getName().startsWith("OMX.google.")
                            && !info.getName().startsWith("c2.android.")) {
                return info;
            }
            if (found == null) {
                found = info;
            }
        }
        return found;
    }

    private static boolean supports(@NonNull MediaCodecInfo info, @NonNull String mime) {
        for (String type : info.getSupportedTypes()) {
            if (type.equalsIgnoreCase(mime)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads a tile's pixels, repeating the picture's last row and column where the tile reaches
     * past its edge, so the encoder sees no false edge there.
     */
    @VisibleForTesting
    static void readTile(@NonNull Bitmap bitmap, int left, int top, int width, int height,
            @NonNull int[] pixels) {
        // A tile wholly past the edge, as the second of a one pixel wide picture is, repeats
        // the edge itself.
        int fromLeft = Math.min(left, bitmap.getWidth() - 1);
        int fromTop = Math.min(top, bitmap.getHeight() - 1);
        int readWidth = Math.min(left + width, bitmap.getWidth()) - fromLeft;
        int readHeight = Math.min(top + height, bitmap.getHeight()) - fromTop;
        bitmap.getPixels(pixels, 0, width, fromLeft, fromTop, readWidth, readHeight);
        for (int y = 0; y < readHeight; y++) {
            int edge = pixels[y * width + readWidth - 1];
            for (int x = readWidth; x < width; x++) {
                pixels[y * width + x] = edge;
            }
        }
        for (int y = readHeight; y < height; y++) {
            System.arraycopy(pixels, (readHeight - 1) * width, pixels, y * width, width);
        }
    }

    /**
     * Writes ARGB pixels into a YUV 4:2:0 image as full-range BT.601, the encoding JPEG uses,
     * averaging each 2x2 block for its colour. Transparency is dropped: the pixels are taken as
     * opaque.
     */
    @VisibleForTesting
    static void writeYuv(@NonNull int[] argb, int width, int height, @NonNull Image image) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yPlane = planes[0].getBuffer();
        int yRow = planes[0].getRowStride();
        int yPixel = planes[0].getPixelStride();
        byte[] row = new byte[width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int color = argb[y * width + x];
                row[x] = (byte) clamp(Math.round(0.299f * red(color) + 0.587f * green(color)
                        + 0.114f * blue(color)));
            }
            put(yPlane, y * yRow, yPixel, row, width);
        }
        ByteBuffer uPlane = planes[1].getBuffer();
        ByteBuffer vPlane = planes[2].getBuffer();
        int uRow = planes[1].getRowStride();
        int uPixel = planes[1].getPixelStride();
        int vRow = planes[2].getRowStride();
        int vPixel = planes[2].getPixelStride();
        int chromaWidth = width / 2;
        byte[] us = new byte[chromaWidth];
        byte[] vs = new byte[chromaWidth];
        for (int y = 0; y < height / 2; y++) {
            for (int x = 0; x < chromaWidth; x++) {
                int r = 0;
                int g = 0;
                int b = 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int color = argb[(y * 2 + dy) * width + x * 2 + dx];
                        r += red(color);
                        g += green(color);
                        b += blue(color);
                    }
                }
                float red = r / 4f;
                float green = g / 4f;
                float blue = b / 4f;
                us[x] = (byte) clamp(Math.round(128 - 0.168736f * red - 0.331264f * green
                        + 0.5f * blue));
                vs[x] = (byte) clamp(Math.round(128 + 0.5f * red - 0.418688f * green
                        - 0.081312f * blue));
            }
            put(uPlane, y * uRow, uPixel, us, chromaWidth);
            put(vPlane, y * vRow, vPixel, vs, chromaWidth);
        }
    }

    /** Writes a row of samples into a plane, a sample every {@code stride} bytes. */
    private static void put(@NonNull ByteBuffer plane, int start, int stride,
            @NonNull byte[] samples, int count) {
        if (stride == 1) {
            plane.position(start);
            plane.put(samples, 0, count);
            return;
        }
        for (int i = 0; i < count; i++) {
            plane.put(start + i * stride, samples[i]);
        }
    }

    private static int red(int color) {
        return (color >> 16) & 0xFF;
    }

    private static int green(int color) {
        return (color >> 8) & 0xFF;
    }

    private static int blue(int color) {
        return color & 0xFF;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static int divideRoundingUp(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static int roundUpToEven(int value) {
        return (value + 1) & ~1;
    }
}
