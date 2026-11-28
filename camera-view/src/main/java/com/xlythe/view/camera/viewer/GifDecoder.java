package com.xlythe.view.camera.viewer;

import androidx.annotation.AnyThread;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Decodes an animated GIF a frame at a time, each composed over what the frames before it left,
 * the way browsers and Android 9's own decoder show it. It is plain Java, so it behaves the same
 * on every release, and it holds one frame's pixels at a time rather than all of them.
 *
 * <p>What browsers do where the format leaves it open is followed: a frame that says to restore
 * the background clears to transparent; a first frame larger than the screen it says it fits
 * makes the screen larger; a frame with no delay, or one of 10 ms or less, shows for 100 ms; a
 * colour index past the end of the colour table is opaque black; and a file cut short shows what
 * it has.</p>
 *
 * <p>It is not thread safe: use it from one thread at a time.</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class GifDecoder {
    /** What happens to a frame's area once it has been shown. */
    @IntDef({DISPOSE_KEEP, DISPOSE_BACKGROUND, DISPOSE_PREVIOUS})
    @Retention(RetentionPolicy.SOURCE)
    @interface Disposal {}

    /** The frame stays, and the next is drawn over it. */
    @VisibleForTesting
    static final int DISPOSE_KEEP = 0;

    /** The frame's area is cleared to transparent. */
    @VisibleForTesting
    static final int DISPOSE_BACKGROUND = 1;

    /** The frame's area goes back to what it was before the frame was drawn. */
    @VisibleForTesting
    static final int DISPOSE_PREVIOUS = 2;

    /** The most pixels a screen may hold, so a malformed size cannot overflow an array. */
    @VisibleForTesting
    static final long MAX_PIXELS = 1L << 26;

    /** The most codes an LZW table holds: 12 bits' worth. */
    private static final int MAX_CODES = 4096;

    /** What a colour index past the end of its table shows as. */
    private static final int OPAQUE_BLACK = 0xFF000000;

    /** What a frame shows for when it says no time, or a time too short to see. */
    private static final int DEFAULT_DELAY_MILLIS = 100;

    /** The longest delay that counts as too short to see. */
    private static final int SHORTEST_DELAY_MILLIS = 10;

    /** Where each pass of an interlaced frame starts, and how many rows it steps. */
    private static final int[] PASS_START = {0, 4, 2, 1};
    private static final int[] PASS_STEP = {8, 8, 4, 2};

    /** One frame's image descriptor and graphic control, and where its pixel data starts. */
    private static final class Frame {
        /** Where it goes across the screen. */
        int x;

        /** Where it goes down the screen. */
        int y;

        /** Its width. */
        int width;

        /** Its height. */
        int height;

        /** Whether its rows are stored in four interlaced passes. */
        boolean interlaced;

        /** Its colours, as opaque ARGB, from its own table or the file's; null if it has none. */
        @Nullable
        int[] colors;

        /** The colour index drawn as nothing, or -1. */
        int transparent = -1;

        /** How long it shows, in milliseconds; as long as a frame given no delay, without one. */
        int delay = DEFAULT_DELAY_MILLIS;

        /** What happens to its area once shown. */
        @Disposal
        int disposal = DISPOSE_KEEP;

        /** The LZW minimum code size. */
        int codeSize;

        /** Where its first data sub-block starts. */
        int data;
    }

    /** The whole file. */
    @NonNull
    private final byte[] mGif;

    /** The screen's width. */
    private final int mWidth;

    /** The screen's height. */
    private final int mHeight;

    /** Every frame found, in order. */
    @NonNull
    private final Frame[] mFrames;

    /** The screen as last composed, as non-premultiplied ARGB, or null before the first frame. */
    @Nullable
    private int[] mCanvas;

    /** What a frame that is to be put back covered before it was drawn, or null. */
    @Nullable
    private int[] mSaved;

    /** The frame last composed, or -1 for none. */
    private int mIndex = -1;

    /** The LZW table: each code's prefix code. */
    private final short[] mPrefix = new short[MAX_CODES];

    /** The LZW table: each code's last index. */
    private final byte[] mSuffix = new byte[MAX_CODES];

    /** A code's indices, last first, as it is unwound. */
    private final byte[] mStack = new byte[MAX_CODES + 1];

    private GifDecoder(@NonNull byte[] gif, int width, int height, @NonNull Frame[] frames) {
        mGif = gif;
        mWidth = width;
        mHeight = height;
        mFrames = frames;
    }

    /**
     * Reads a GIF's screen and frames, decoding none of them yet.
     *
     * @throws IOException if it is not a GIF, has no frame, or is too large to hold
     */
    @AnyThread
    @NonNull
    public static GifDecoder parse(@NonNull byte[] gif) throws IOException {
        if (gif.length < 13 || gif[0] != 'G' || gif[1] != 'I' || gif[2] != 'F') {
            throw new IOException("Not a GIF");
        }
        int width = u16(gif, 6);
        int height = u16(gif, 8);
        int packed = gif[10] & 0xFF;
        int at = 13;
        int[] global = null;
        if ((packed & 0x80) != 0) {
            int size = 2 << (packed & 7);
            global = colors(gif, at, size);
            at += 3 * size;
        }
        List<Frame> frames = new ArrayList<>();
        Frame next = new Frame();
        while (at < gif.length) {
            int block = gif[at++] & 0xFF;
            if (block == 0x21) {
                if (at >= gif.length) {
                    break;
                }
                int label = gif[at++] & 0xFF;
                if (label == 0xF9 && at + 5 <= gif.length && (gif[at] & 0xFF) >= 4) {
                    control(next, gif, at + 1);
                }
                at = skipBlocks(gif, at);
            } else if (block == 0x2C) {
                if (at + 9 > gif.length) {
                    break;
                }
                next.x = u16(gif, at);
                next.y = u16(gif, at + 2);
                next.width = u16(gif, at + 4);
                next.height = u16(gif, at + 6);
                int flags = gif[at + 8] & 0xFF;
                next.interlaced = (flags & 0x40) != 0;
                at += 9;
                next.colors = global;
                if ((flags & 0x80) != 0) {
                    int size = 2 << (flags & 7);
                    next.colors = colors(gif, at, size);
                    at += 3 * size;
                }
                if (at >= gif.length) {
                    break;
                }
                next.codeSize = gif[at++] & 0xFF;
                next.data = at;
                at = skipBlocks(gif, at);
                if (frames.isEmpty()) {
                    // A first frame larger than the screen makes the screen larger, as browsers
                    // do; later frames are cut to it.
                    width = Math.max(width, next.x + next.width);
                    height = Math.max(height, next.y + next.height);
                }
                frames.add(next);
                next = new Frame();
            } else {
                // The trailer, or something no GIF holds: either way, nothing more to read.
                break;
            }
        }
        if (frames.isEmpty() || width <= 0 || height <= 0) {
            throw new IOException("A GIF with no frame to show");
        }
        if ((long) width * height > MAX_PIXELS) {
            throw new IOException("A GIF too large to hold: " + width + "x" + height);
        }
        for (Frame frame : frames) {
            if (frame.width == 0 || frame.height == 0) {
                // A frame of no size covers the screen, as browsers take it.
                frame.x = 0;
                frame.y = 0;
                frame.width = width;
                frame.height = height;
            }
        }
        return new GifDecoder(gif, width, height, frames.toArray(new Frame[0]));
    }

    /** Reads a graphic control extension's fields into the frame it describes. */
    private static void control(@NonNull Frame frame, @NonNull byte[] gif, int at) {
        int flags = gif[at] & 0xFF;
        switch ((flags >> 2) & 7) {
            case 2:
                frame.disposal = DISPOSE_BACKGROUND;
                break;
            case 3:
            case 4:
                // Some encoders write 4, setting the field's third bit, for "restore previous".
                frame.disposal = DISPOSE_PREVIOUS;
                break;
            default:
                frame.disposal = DISPOSE_KEEP;
                break;
        }
        int delay = u16(gif, at + 1) * 10;
        frame.delay = delay <= SHORTEST_DELAY_MILLIS ? DEFAULT_DELAY_MILLIS : delay;
        frame.transparent = (flags & 1) != 0 ? gif[at + 3] & 0xFF : -1;
    }

    /** A colour table, as opaque ARGB, padded with black where the file ends early. */
    @NonNull
    private static int[] colors(@NonNull byte[] gif, int at, int size) {
        int[] colors = new int[size];
        for (int i = 0; i < size; i++) {
            int offset = at + 3 * i;
            if (offset + 2 >= gif.length) {
                colors[i] = OPAQUE_BLACK;
                continue;
            }
            colors[i] = OPAQUE_BLACK | (gif[offset] & 0xFF) << 16 | (gif[offset + 1] & 0xFF) << 8
                    | (gif[offset + 2] & 0xFF);
        }
        return colors;
    }

    /** Where the data sub-blocks starting at an offset end, past their terminator. */
    private static int skipBlocks(@NonNull byte[] gif, int at) {
        while (at < gif.length) {
            int length = gif[at] & 0xFF;
            at += 1 + length;
            if (length == 0) {
                return at;
            }
        }
        return gif.length;
    }

    private static int u16(@NonNull byte[] data, int at) {
        return (data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8;
    }

    /** The screen's width, which every frame is composed at. */
    @AnyThread
    public int getWidth() {
        return mWidth;
    }

    /** The screen's height, which every frame is composed at. */
    @AnyThread
    public int getHeight() {
        return mHeight;
    }

    /** How many frames the file holds. */
    @AnyThread
    public int getFrameCount() {
        return mFrames.length;
    }

    /** Each frame's delay, in order, in milliseconds. */
    @AnyThread
    @NonNull
    public int[] getDelays() {
        int[] delays = new int[mFrames.length];
        for (int i = 0; i < delays.length; i++) {
            delays[i] = mFrames[i].delay;
        }
        return delays;
    }

    /**
     * Composes the next frame, going back to the first after the last.
     *
     * @return the screen, {@link #getWidth()} pixels a row, as non-premultiplied ARGB. It is the
     *     decoder's own array, which the next frame overwrites.
     */
    @WorkerThread
    @NonNull
    public int[] advance() {
        int[] canvas = mCanvas;
        if (canvas == null) {
            canvas = new int[mWidth * mHeight];
            mCanvas = canvas;
        }
        int next = mIndex + 1;
        if (next >= mFrames.length || mIndex < 0) {
            // Each time through starts from a clear screen.
            next = 0;
            Arrays.fill(canvas, 0);
        } else {
            dispose(canvas, mFrames[mIndex]);
        }
        Frame frame = mFrames[next];
        if (frame.disposal == DISPOSE_PREVIOUS) {
            save(canvas, frame);
        }
        draw(canvas, frame);
        mIndex = next;
        return canvas;
    }

    /**
     * Composes a frame, going on from the frame last composed or, for an earlier one, starting
     * again from the first.
     *
     * @return the screen, as {@link #advance()} returns it
     */
    @WorkerThread
    @NonNull
    public int[] seek(int frame) {
        if (frame < 0 || frame >= mFrames.length) {
            throw new IndexOutOfBoundsException("No frame " + frame + " of " + mFrames.length);
        }
        if (mCanvas != null && frame == mIndex) {
            return mCanvas;
        }
        if (frame < mIndex) {
            mIndex = -1;
        }
        int[] canvas;
        do {
            canvas = advance();
        } while (mIndex != frame);
        return canvas;
    }

    /** Clears, or puts back, what the frame last shown covered, as it asked. */
    private void dispose(@NonNull int[] canvas, @NonNull Frame frame) {
        if (frame.disposal == DISPOSE_KEEP) {
            return;
        }
        int left = Math.min(frame.x, mWidth);
        int top = Math.min(frame.y, mHeight);
        int right = Math.min(frame.x + frame.width, mWidth);
        int bottom = Math.min(frame.y + frame.height, mHeight);
        int span = right - left;
        for (int y = top; y < bottom; y++) {
            if (frame.disposal == DISPOSE_BACKGROUND || mSaved == null) {
                Arrays.fill(canvas, y * mWidth + left, y * mWidth + right, 0);
            } else {
                System.arraycopy(mSaved, (y - top) * span, canvas, y * mWidth + left, span);
            }
        }
    }

    /** Keeps what a frame is about to cover, to put back once it has shown. */
    private void save(@NonNull int[] canvas, @NonNull Frame frame) {
        int left = Math.min(frame.x, mWidth);
        int top = Math.min(frame.y, mHeight);
        int right = Math.min(frame.x + frame.width, mWidth);
        int bottom = Math.min(frame.y + frame.height, mHeight);
        int span = right - left;
        int size = span * (bottom - top);
        if (mSaved == null || mSaved.length < size) {
            mSaved = new int[size];
        }
        for (int y = top; y < bottom; y++) {
            System.arraycopy(canvas, y * mWidth + left, mSaved, (y - top) * span, span);
        }
    }

    /**
     * Decodes a frame's LZW data onto the screen, leaving its transparent pixels, and anything
     * the data runs out before, as they were.
     */
    private void draw(@NonNull int[] canvas, @NonNull Frame frame) {
        int[] colors = frame.colors;
        int minimum = frame.codeSize;
        if (colors == null || minimum < 1 || minimum > 11) {
            return;
        }
        byte[] gif = mGif;
        short[] prefix = mPrefix;
        byte[] suffix = mSuffix;
        byte[] stack = mStack;
        int clear = 1 << minimum;
        int end = clear + 1;
        for (int code = 0; code < clear; code++) {
            prefix[code] = 0;
            suffix[code] = (byte) code;
        }
        int codeSize = minimum + 1;
        int codeMask = (1 << codeSize) - 1;
        int available = clear + 2;
        int previous = -1;
        int first = 0;

        // Where the next byte of data is, and how many are left in its sub-block.
        int at = frame.data;
        int left = 0;
        // Bits read but not yet used, lowest first.
        int bits = 0;
        int count = 0;

        // Where the next pixel goes: its column, its row within the frame, and the pass.
        int column = 0;
        int row = 0;
        int pass = 0;
        int written = 0;
        int total = frame.width * frame.height;
        int transparent = frame.transparent;

        decoding:
        while (written < total) {
            while (count < codeSize) {
                if (left == 0) {
                    if (at >= gif.length) {
                        break decoding;
                    }
                    left = gif[at++] & 0xFF;
                    if (left == 0) {
                        break decoding;
                    }
                }
                if (at >= gif.length) {
                    break decoding;
                }
                bits |= (gif[at++] & 0xFF) << count;
                count += 8;
                left--;
            }
            int code = bits & codeMask;
            bits >>>= codeSize;
            count -= codeSize;

            if (code == clear) {
                codeSize = minimum + 1;
                codeMask = (1 << codeSize) - 1;
                available = clear + 2;
                previous = -1;
                continue;
            }
            if (code == end) {
                break;
            }
            int top = 0;
            if (previous == -1) {
                if (code >= clear) {
                    break;
                }
                first = code;
                stack[top++] = (byte) code;
            } else {
                if (code > available) {
                    break;
                }
                int current = code;
                if (code == available) {
                    // The code being defined: the previous string and its own first index.
                    stack[top++] = (byte) first;
                    current = previous;
                }
                while (current >= clear) {
                    stack[top++] = suffix[current];
                    current = prefix[current];
                }
                first = suffix[current] & 0xFF;
                stack[top++] = (byte) first;
                if (available < MAX_CODES) {
                    prefix[available] = (short) previous;
                    suffix[available] = (byte) first;
                    available++;
                    if (available > codeMask && codeSize < 12) {
                        codeSize++;
                        codeMask = (1 << codeSize) - 1;
                    }
                }
            }
            previous = code;

            while (top > 0 && written < total) {
                int index = stack[--top] & 0xFF;
                int x = frame.x + column;
                int y = frame.y + row;
                if (index != transparent && x < mWidth && y < mHeight) {
                    canvas[y * mWidth + x] = index < colors.length ? colors[index] : OPAQUE_BLACK;
                }
                written++;
                if (++column == frame.width) {
                    column = 0;
                    if (frame.interlaced) {
                        row += PASS_STEP[pass];
                        while (row >= frame.height && pass < 3) {
                            pass++;
                            row = PASS_START[pass];
                        }
                    } else {
                        row++;
                    }
                }
            }
        }
    }
}
