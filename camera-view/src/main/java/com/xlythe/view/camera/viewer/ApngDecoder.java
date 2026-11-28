package com.xlythe.view.camera.viewer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Decodes an animated PNG into its frames, composed as a browser shows them, for every Android
 * release: no platform decoder animates one. Each frame is rebuilt as a PNG of its own and
 * decoded by {@link BitmapFactory}, then drawn over what the frames before it left, as their
 * dispose and blend operations say. Frames are kept at a fraction of their size when all of them
 * at full size would hold more than a budget.
 */
final class ApngDecoder {
    /** The charset of box and chunk names, by name, which every release has. */
    private static final Charset ASCII = Charset.forName("US-ASCII");

    /** The eight bytes every PNG starts with. */
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    /** A frame's dispose operation: leave what it drew. */
    private static final int DISPOSE_NONE = 0;

    /** A frame's dispose operation: clear its region afterwards. */
    private static final int DISPOSE_BACKGROUND = 1;

    /** A frame's dispose operation: put back what was there before it. */
    private static final int DISPOSE_PREVIOUS = 2;

    /** A frame's blend operation: replace its region. */
    private static final int BLEND_SOURCE = 0;

    /** The most decoded bytes all frames may hold together: 48 MB. */
    @VisibleForTesting
    static final long MAX_BYTES = 48L * 1024 * 1024;

    /** The shortest a frame is shown; browsers treat anything quicker as this. */
    private static final int MIN_DELAY_MILLIS = 20;

    /** What a frame shows for that is said to show for no time at all. */
    private static final int DEFAULT_DELAY_MILLIS = 100;

    /** A decoded animation. */
    static final class Animation {
        /** Each frame, composed, all the same size. */
        @NonNull
        final Bitmap[] frames;

        /** How long each frame shows, in milliseconds. */
        @NonNull
        final int[] delays;

        /** How many times it plays through, or 0 for forever. */
        final int plays;

        Animation(@NonNull Bitmap[] frames, @NonNull int[] delays, int plays) {
            this.frames = frames;
            this.delays = delays;
            this.plays = plays;
        }
    }

    /** One frame's control chunk, and the image data that follows it. */
    private static final class Frame {
        /** Its width. */
        int width;

        /** Its height. */
        int height;

        /** Where it goes across. */
        int x;

        /** Where it goes down. */
        int y;

        /** How long it shows, in milliseconds. */
        int delay;

        /** What happens to its region before the next frame. */
        int dispose;

        /** How it is drawn over what is there. */
        int blend;

        /** Its compressed image data, from IDAT or fdAT chunks. */
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
    }

    private ApngDecoder() {}

    /**
     * Decodes an animated PNG.
     *
     * @return the animation, or null if the file is a PNG without animation control
     * @throws IOException if it is not a PNG, or a frame cannot be decoded
     */
    @WorkerThread
    @Nullable
    static Animation decode(@NonNull byte[] png) throws IOException {
        if (png.length < SIGNATURE.length + 25 || !startsWith(png, 0, SIGNATURE)) {
            throw new IOException("Not a PNG");
        }
        byte[] header = null;
        ByteArrayOutputStream shared = new ByteArrayOutputStream();
        List<Frame> frames = new ArrayList<>();
        Frame current = null;
        boolean animated = false;
        boolean seenData = false;
        int plays = 0;
        int at = SIGNATURE.length;
        while (at + 12 <= png.length) {
            int length = u32(png, at);
            String type = new String(png, at + 4, 4, ASCII);
            int data = at + 8;
            if (length < 0 || data + length + 4 > png.length) {
                break;
            }
            switch (type) {
                case "IHDR":
                    header = new byte[length];
                    System.arraycopy(png, data, header, 0, length);
                    break;
                case "acTL":
                    animated = length >= 8;
                    plays = length >= 8 ? u32(png, data + 4) : 0;
                    break;
                case "fcTL":
                    if (length >= 26) {
                        current = control(png, data);
                        frames.add(current);
                    }
                    break;
                case "IDAT":
                    seenData = true;
                    // The default image is the first frame only when a control chunk came first.
                    if (current != null) {
                        current.data.write(png, data, length);
                    }
                    break;
                case "fdAT":
                    if (current != null && length > 4) {
                        current.data.write(png, data + 4, length - 4);
                    }
                    break;
                case "IEND":
                    at = png.length;
                    continue;
                default:
                    // Palettes, transparency and colour information every frame needs.
                    if (!seenData && isShared(type)) {
                        shared.write(png, at, length + 12);
                    }
                    break;
            }
            at = data + length + 4;
        }
        if (!animated || header == null || header.length < 13 || frames.isEmpty()) {
            return null;
        }
        return compose(header, shared.toByteArray(), frames, plays);
    }

    /** A frame control chunk's contents. */
    @NonNull
    private static Frame control(@NonNull byte[] png, int data) {
        Frame frame = new Frame();
        frame.width = u32(png, data + 4);
        frame.height = u32(png, data + 8);
        frame.x = u32(png, data + 12);
        frame.y = u32(png, data + 16);
        int numerator = u16(png, data + 20);
        int denominator = u16(png, data + 22);
        int millis = numerator * 1000 / (denominator == 0 ? 100 : denominator);
        frame.delay = millis == 0 ? DEFAULT_DELAY_MILLIS : Math.max(MIN_DELAY_MILLIS, millis);
        frame.dispose = png[data + 24] & 0xFF;
        frame.blend = png[data + 25] & 0xFF;
        return frame;
    }

    /** Draws each frame over the last, as its operations say. */
    @NonNull
    private static Animation compose(@NonNull byte[] header, @NonNull byte[] shared,
            @NonNull List<Frame> frames, int plays) throws IOException {
        int width = u32(header, 0);
        int height = u32(header, 4);
        if (width <= 0 || height <= 0) {
            throw new IOException("An animated PNG with no size");
        }
        int sample = sampleSize(width, height, frames.size());
        Bitmap canvasBitmap = Bitmap.createBitmap(Math.max(1, width / sample),
                Math.max(1, height / sample), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(canvasBitmap);
        Paint over = new Paint(Paint.FILTER_BITMAP_FLAG);
        Paint clear = new Paint();
        clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        Bitmap[] composed = new Bitmap[frames.size()];
        int[] delays = new int[frames.size()];
        Frame previous = null;
        Bitmap restore = null;
        for (int i = 0; i < frames.size(); i++) {
            Frame frame = frames.get(i);
            if (previous != null) {
                if (previous.dispose == DISPOSE_BACKGROUND) {
                    canvas.drawRect(region(previous, sample), clear);
                } else if (previous.dispose == DISPOSE_PREVIOUS && restore != null) {
                    canvas.drawRect(region(previous, sample), clear);
                    canvas.drawBitmap(restore, 0, 0, null);
                }
            }
            restore = frame.dispose == DISPOSE_PREVIOUS && i > 0
                    ? canvasBitmap.copy(Bitmap.Config.ARGB_8888, false) : null;
            Bitmap pixels = decodeFrame(header, shared, frame, sample);
            Rect target = region(frame, sample);
            if (frame.blend == BLEND_SOURCE) {
                canvas.drawRect(target, clear);
            }
            if (pixels != null) {
                canvas.drawBitmap(pixels, null, target, over);
                pixels.recycle();
            }
            composed[i] = canvasBitmap.copy(Bitmap.Config.ARGB_8888, false);
            delays[i] = frame.delay;
            // The first frame cannot put back what was before it: there was nothing.
            previous = frame;
            if (i == 0 && frame.dispose == DISPOSE_PREVIOUS) {
                frame.dispose = DISPOSE_BACKGROUND;
            }
        }
        canvasBitmap.recycle();
        return new Animation(composed, delays, plays);
    }

    /** Where a frame goes, at the sampled size. */
    @NonNull
    private static Rect region(@NonNull Frame frame, int sample) {
        return new Rect(frame.x / sample, frame.y / sample, (frame.x + frame.width) / sample,
                (frame.y + frame.height) / sample);
    }

    /** The smallest power of two to divide the size by so every frame fits the budget. */
    @VisibleForTesting
    static int sampleSize(int width, int height, int frames) {
        int sample = 1;
        while ((long) (width / sample) * (height / sample) * 4 * (frames + 1) > MAX_BYTES
                && sample < 64) {
            sample *= 2;
        }
        return sample;
    }

    /** One frame decoded on its own, as a PNG rebuilt from the file's header and its data. */
    @Nullable
    private static Bitmap decodeFrame(@NonNull byte[] header, @NonNull byte[] shared,
            @NonNull Frame frame, int sample) {
        byte[] ownHeader = header.clone();
        putU32(ownHeader, 0, frame.width);
        putU32(ownHeader, 4, frame.height);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.write(SIGNATURE, 0, SIGNATURE.length);
        chunk(png, "IHDR", ownHeader);
        png.write(shared, 0, shared.length);
        chunk(png, "IDAT", frame.data.toByteArray());
        chunk(png, "IEND", new byte[0]);
        byte[] bytes = png.toByteArray();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private static void chunk(@NonNull ByteArrayOutputStream out, @NonNull String type,
            @NonNull byte[] data) {
        byte[] name = type.getBytes(ASCII);
        byte[] length = new byte[4];
        putU32(length, 0, data.length);
        out.write(length, 0, 4);
        out.write(name, 0, 4);
        out.write(data, 0, data.length);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        byte[] check = new byte[4];
        putU32(check, 0, (int) crc.getValue());
        out.write(check, 0, 4);
    }

    /** Whether a chunk before the image data describes pixels every frame shares. */
    private static boolean isShared(@NonNull String type) {
        switch (type) {
            case "PLTE":
            case "tRNS":
            case "gAMA":
            case "cHRM":
            case "sRGB":
            case "iCCP":
            case "sBIT":
                return true;
            default:
                return false;
        }
    }

    private static boolean startsWith(@NonNull byte[] data, int at, @NonNull byte[] prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if (data[at + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static int u32(@NonNull byte[] data, int at) {
        return ((data[at] & 0xFF) << 24) | ((data[at + 1] & 0xFF) << 16)
                | ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
    }

    private static int u16(@NonNull byte[] data, int at) {
        return ((data[at] & 0xFF) << 8) | (data[at + 1] & 0xFF);
    }

    private static void putU32(@NonNull byte[] data, int at, int value) {
        data[at] = (byte) (value >>> 24);
        data[at + 1] = (byte) (value >>> 16);
        data[at + 2] = (byte) (value >>> 8);
        data[at + 3] = (byte) value;
    }
}
