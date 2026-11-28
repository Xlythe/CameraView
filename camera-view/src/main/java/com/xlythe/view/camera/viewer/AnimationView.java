package com.xlythe.view.camera.viewer;

import static com.xlythe.view.camera.ICameraModule.TAG;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Executor;

/**
 * Plays an animated picture, fitted inside the view and looping: an animated PNG on every
 * release, through {@link ApngDecoder}; an animated GIF, WebP, AVIF or HEIF sequence through the
 * platform's own decoder from Android 9; and an animated GIF before that, or one the platform
 * cannot read, through {@link GifDecoder}, a frame at a time. A picture that does not animate on
 * this release shows still.
 */
final class AnimationView extends View {
    /** The most bytes of a file read into memory to decode it: 64 MB. */
    private static final int MAX_FILE_BYTES = 64 * 1024 * 1024;

    /**
     * How long a decode may take before the picture is reported as failed. A platform decoder can
     * stall on a malformed file, as Android 5's does on one animated PNG; its thread is left to
     * it, and whatever it finishes with is ignored.
     */
    @VisibleForTesting
    static final long DECODE_TIMEOUT_MILLIS = 20_000;

    /** The longest side a still is decoded at. */
    private static final int MAX_STILL_SIDE = 2048;

    /** Posts what was decoded back. */
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /** Draws bitmaps scaled smoothly. */
    private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    /** Where the picture is drawn, fitted inside the view. */
    private final RectF mBounds = new RectF();

    /** Decodes the file off the main thread. */
    @NonNull
    private final Executor mExecutor;

    /** Hears whether the picture showed. */
    @Nullable
    private ImageLoadListener mListener;

    /** Counts loads, so an old one that finishes late is ignored. */
    private int mGeneration;

    /** The platform's drawable, from Android 9, or null. */
    @Nullable
    private Drawable mDrawable;

    /** An animated GIF played through the library's own decoder, or null. */
    @Nullable
    private GifFrames mGif;

    /** An animated PNG's frames, or null. */
    @Nullable
    private ApngDecoder.Animation mFrames;

    /** A still, for a picture that does not animate here, or null. */
    @Nullable
    private Bitmap mStill;

    /** Whether it is playing. */
    private boolean mPlaying;

    /** When the current play began, less how far it had already played. */
    private long mStartedAt;

    /** How far it had played when it was paused, in milliseconds. */
    private long mPausedAt;

    /** Whether it was playing when it was taken off the screen, to play again when put back. */
    private boolean mResumeOnAttach;

    /** Gives up on a decode that has taken too long. */
    private final Runnable mTimeout = new Runnable() {
        @Override
        public void run() {
            Log.w(TAG, "Gave up decoding an animation after " + DECODE_TIMEOUT_MILLIS + " ms");
            mGeneration++;
            if (mListener != null) {
                mListener.onImageFailed();
            }
        }
    };

    /** Shows the next frame of an animated PNG or GIF when it is due. */
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            invalidate();
            scheduleTick();
        }
    };

    AnimationView(@NonNull Context context, @NonNull Executor executor) {
        super(context);
        mExecutor = executor;
    }

    /** Hears whether the picture showed. */
    @MainThread
    void setImageLoadListener(@Nullable ImageLoadListener listener) {
        mListener = listener;
    }

    /** Decodes and starts playing a picture. */
    @MainThread
    void setLocation(@NonNull ImageLocation location) {
        release();
        final int generation = ++mGeneration;
        final Context context = getContext().getApplicationContext();
        mHandler.postDelayed(mTimeout, DECODE_TIMEOUT_MILLIS);
        mExecutor.execute(() -> {
            Object decoded;
            try {
                decoded = decode(context, location);
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                Log.w(TAG, "Unable to decode " + location, e);
                decoded = null;
            }
            final Object result = decoded;
            mHandler.post(() -> onDecoded(generation, result));
        });
    }

    /**
     * Whatever plays the picture on this release: frames, a drawable, a movie or a still.
     *
     * @throws IOException if the file cannot be read or decoded at all
     */
    @WorkerThread
    @NonNull
    private static Object decode(@NonNull Context context, @NonNull ImageLocation location)
            throws IOException {
        byte[] bytes = read(context, location);
        if (isPng(bytes)) {
            ApngDecoder.Animation animation = ApngDecoder.decode(bytes);
            if (animation != null) {
                return animation;
            }
        }
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                return decodeDrawable(context, location);
            } catch (IOException | RuntimeException e) {
                Log.d(TAG, location + " is not something the platform animates", e);
            }
        }
        if (isGif(bytes)) {
            GifFrames gif = decodeGif(bytes);
            if (gif != null) {
                return gif;
            }
        }
        Bitmap still = decodeStill(bytes);
        if (still == null) {
            throw new IOException(location + " is not a picture this device can decode");
        }
        return still;
    }

    @RequiresApi(28)
    @WorkerThread
    @NonNull
    private static Drawable decodeDrawable(@NonNull Context context,
            @NonNull ImageLocation location) throws IOException {
        // From the file or Uri rather than from memory: Android 9's animation thread aborts the
        // app when it has to read frames from a buffer.
        ImageDecoder.Source source = location.isFile()
                ? ImageDecoder.createSource(location.file())
                : ImageDecoder.createSource(context.getContentResolver(), location.uri());
        return ImageDecoder.decodeDrawable(source);
    }

    /**
     * An animated GIF with its first frame composed, or null if it has one frame, cannot be
     * read, or is too large to hold a frame of in memory.
     */
    @WorkerThread
    @Nullable
    private static GifFrames decodeGif(@NonNull byte[] bytes) {
        GifDecoder decoder;
        try {
            decoder = GifDecoder.parse(bytes);
        } catch (IOException e) {
            Log.d(TAG, "Unable to read a GIF's frames", e);
            return null;
        }
        if (decoder.getFrameCount() < 2) {
            return null;
        }
        int width = decoder.getWidth();
        int height = decoder.getHeight();
        // The decoder's screen and what it puts back, and the frame showing.
        long needs = (long) width * height * 4 * 3;
        if (needs > Runtime.getRuntime().maxMemory() / 4) {
            Log.w(TAG, "A " + width + "x" + height + " GIF is too large to animate here");
            return null;
        }
        Bitmap frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        frame.setPixels(decoder.advance(), 0, width, 0, 0, width, height);
        return new GifFrames(decoder, frame);
    }

    /** The picture still, at no more than a bounded size. */
    @WorkerThread
    @Nullable
    private static Bitmap decodeStill(@NonNull byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize
                > MAX_STILL_SIDE) {
            options.inSampleSize *= 2;
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    @WorkerThread
    @NonNull
    private static byte[] read(@NonNull Context context, @NonNull ImageLocation location)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream input = location.open(context)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_FILE_BYTES) {
                    throw new IOException(location + " is too large to animate");
                }
            }
        }
        return out.toByteArray();
    }

    private static boolean isPng(@NonNull byte[] bytes) {
        return bytes.length > 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P'
                && bytes[2] == 'N' && bytes[3] == 'G';
    }

    private static boolean isGif(@NonNull byte[] bytes) {
        return bytes.length > 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F';
    }

    @MainThread
    private void onDecoded(int generation, @Nullable Object decoded) {
        if (generation == mGeneration) {
            mHandler.removeCallbacks(mTimeout);
        }
        if (generation != mGeneration) {
            if (decoded instanceof Bitmap) {
                ((Bitmap) decoded).recycle();
            }
            return;
        }
        if (decoded == null) {
            if (mListener != null) {
                mListener.onImageFailed();
            }
            return;
        }
        if (decoded instanceof ApngDecoder.Animation) {
            mFrames = (ApngDecoder.Animation) decoded;
        } else if (decoded instanceof GifFrames) {
            mGif = (GifFrames) decoded;
        } else if (decoded instanceof Drawable) {
            mDrawable = (Drawable) decoded;
            mDrawable.setCallback(this);
        } else {
            mStill = (Bitmap) decoded;
        }
        requestLayout();
        invalidate();
        play();
        if (mListener != null) {
            mListener.onImageLoaded();
        }
    }

    /** Whether the picture animates here at all. */
    @MainThread
    boolean isAnimated() {
        return mFrames != null || mGif != null || mDrawable instanceof Animatable;
    }

    /** Whether it is playing. */
    @MainThread
    boolean isPlaying() {
        return mPlaying;
    }

    /**
     * Plays from where it was paused.
     *
     * @return false if it does not animate, or is not decoded yet
     */
    @MainThread
    boolean play() {
        if (!isAnimated() || mPlaying) {
            return false;
        }
        mPlaying = true;
        mStartedAt = SystemClock.uptimeMillis() - mPausedAt;
        if (mDrawable instanceof Animatable) {
            if (Build.VERSION.SDK_INT >= 28) {
                startDrawable();
            }
        } else {
            scheduleTick();
        }
        return true;
    }

    /** Redraws when the next frame is due, until it is paused or has played as often as asked. */
    @MainThread
    private void scheduleTick() {
        removeCallbacks(mTick);
        if (!mPlaying) {
            return;
        }
        long played = SystemClock.uptimeMillis() - mStartedAt;
        long delay = mFrames != null ? untilNextFrame(mFrames, played)
                : mGif != null ? untilNextFrame(mGif.delays, 0, played) : 0;
        if (delay > 0) {
            postDelayed(mTick, delay);
        }
    }

    @RequiresApi(28)
    @MainThread
    private void startDrawable() {
        if (mDrawable instanceof AnimatedImageDrawable) {
            ((AnimatedImageDrawable) mDrawable).setRepeatCount(
                    AnimatedImageDrawable.REPEAT_INFINITE);
        }
        ((Animatable) mDrawable).start();
    }

    /**
     * Pauses on the frame it shows.
     *
     * @return false if it was not playing
     */
    @MainThread
    boolean pause() {
        if (!mPlaying) {
            return false;
        }
        mPlaying = false;
        mPausedAt = SystemClock.uptimeMillis() - mStartedAt;
        removeCallbacks(mTick);
        if (mDrawable instanceof Animatable) {
            ((Animatable) mDrawable).stop();
        }
        return true;
    }

    /** Lets go of everything decoded. */
    @MainThread
    void release() {
        mGeneration++;
        mHandler.removeCallbacks(mTimeout);
        pause();
        mPausedAt = 0;
        if (mDrawable != null) {
            mDrawable.setCallback(null);
            mDrawable = null;
        }
        if (mGif != null) {
            mGif.frame.recycle();
            mGif = null;
        }
        if (mFrames != null) {
            for (Bitmap frame : mFrames.frames) {
                frame.recycle();
            }
            mFrames = null;
        }
        if (mStill != null) {
            mStill.recycle();
            mStill = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mResumeOnAttach) {
            mResumeOnAttach = false;
            play();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        // Kept, paused, so a view shown again plays on from where it was; release() frees it.
        mResumeOnAttach = pause();
        super.onDetachedFromWindow();
    }

    @Override
    protected boolean verifyDrawable(@NonNull Drawable who) {
        return who == mDrawable || super.verifyDrawable(who);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        long played = mPlaying ? SystemClock.uptimeMillis() - mStartedAt : mPausedAt;
        if (mFrames != null) {
            Bitmap frame = mFrames.frames[frameAt(mFrames, played)];
            if (fit(frame.getWidth(), frame.getHeight())) {
                canvas.drawBitmap(frame, null, mBounds, mPaint);
            }
        } else if (mGif != null) {
            showGifFrame(mGif, frameAt(mGif.delays, 0, played));
            if (fit(mGif.frame.getWidth(), mGif.frame.getHeight())) {
                canvas.drawBitmap(mGif.frame, null, mBounds, mPaint);
            }
        } else if (mDrawable != null) {
            if (fit(mDrawable.getIntrinsicWidth(), mDrawable.getIntrinsicHeight())) {
                drawFitted(canvas, mDrawable, mBounds);
            } else {
                mDrawable.setBounds(0, 0, getWidth(), getHeight());
                mDrawable.draw(canvas);
            }
        } else if (mStill != null && fit(mStill.getWidth(), mStill.getHeight())) {
            canvas.drawBitmap(mStill, null, mBounds, mPaint);
        }
    }

    /**
     * Draws a drawable of a known size into the bounds it is fitted to. It is drawn at its own
     * size, scaled by the canvas, rather than given those bounds: Android 9's
     * AnimatedImageDrawable draws at its own size in the corner whatever its bounds say.
     */
    @VisibleForTesting
    @MainThread
    static void drawFitted(@NonNull Canvas canvas, @NonNull Drawable drawable,
            @NonNull RectF bounds) {
        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();
        drawable.setBounds(0, 0, width, height);
        int saved = canvas.save();
        canvas.translate(bounds.left, bounds.top);
        canvas.scale(bounds.width() / width, bounds.height() / height);
        drawable.draw(canvas);
        canvas.restoreToCount(saved);
    }

    /**
     * Has a GIF's frame composed, off the main thread, unless it shows already or one is being
     * composed. The view redraws once it is ready.
     */
    @MainThread
    private void showGifFrame(@NonNull GifFrames gif, int index) {
        if (index == gif.shown || gif.composing || gif.broken) {
            return;
        }
        gif.composing = true;
        final int generation = mGeneration;
        mExecutor.execute(() -> {
            int[] pixels;
            try {
                pixels = gif.decoder.seek(index);
            } catch (RuntimeException | OutOfMemoryError e) {
                Log.w(TAG, "Unable to compose frame " + index + " of a GIF", e);
                pixels = null;
            }
            final int[] composed = pixels;
            mHandler.post(() -> onGifFrame(gif, generation, index, composed));
        });
    }

    /** Shows a GIF's frame once it is composed, unless the GIF has since been let go of. */
    @MainThread
    private void onGifFrame(@NonNull GifFrames gif, int generation, int index,
            @Nullable int[] pixels) {
        gif.composing = false;
        if (gif != mGif || generation != mGeneration) {
            return;
        }
        if (pixels == null) {
            // It stays on the frame it shows rather than trying again and again.
            gif.broken = true;
            return;
        }
        int width = gif.frame.getWidth();
        gif.frame.setPixels(pixels, 0, width, 0, 0, width, gif.frame.getHeight());
        gif.shown = index;
        invalidate();
    }

    /**
     * How long until the frame showing after playing for a time gives way to the next, or 0 once
     * it has played as many times as the file says.
     */
    @VisibleForTesting
    static long untilNextFrame(@NonNull ApngDecoder.Animation animation, long played) {
        return untilNextFrame(animation.delays, animation.plays, played);
    }

    /**
     * How long until the frame showing gives way to the next, for frames of these delays that
     * play a number of times, or 0 for forever; 0 once they have played that many times.
     */
    @VisibleForTesting
    static long untilNextFrame(@NonNull int[] delays, int plays, long played) {
        long total = 0;
        for (int delay : delays) {
            total += delay;
        }
        if (total <= 0 || (plays > 0 && played >= total * plays)) {
            return 0;
        }
        long into = played % total;
        long end = 0;
        for (int delay : delays) {
            end += delay;
            if (into < end) {
                return end - into;
            }
        }
        return 0;
    }

    /** Which frame shows after playing for a time, looping as many times as the file says. */
    @VisibleForTesting
    static int frameAt(@NonNull ApngDecoder.Animation animation, long played) {
        return frameAt(animation.delays, animation.plays, played);
    }

    /** Which of the frames of these delays shows after playing for a time, as above. */
    @VisibleForTesting
    static int frameAt(@NonNull int[] delays, int plays, long played) {
        long total = 0;
        for (int delay : delays) {
            total += delay;
        }
        if (total <= 0) {
            return 0;
        }
        if (plays > 0 && played >= total * plays) {
            return delays.length - 1;
        }
        long into = played % total;
        for (int i = 0; i < delays.length; i++) {
            into -= delays[i];
            if (into < 0) {
                return i;
            }
        }
        return delays.length - 1;
    }

    /** Fits a picture of a size inside the view, keeping its shape; false if either is empty. */
    @MainThread
    private boolean fit(int width, int height) {
        if (width <= 0 || height <= 0 || getWidth() <= 0 || getHeight() <= 0) {
            return false;
        }
        float scale = Math.min((float) getWidth() / width, (float) getHeight() / height);
        float left = (getWidth() - width * scale) / 2;
        float top = (getHeight() - height * scale) / 2;
        mBounds.set(left, top, left + width * scale, top + height * scale);
        return true;
    }

    /**
     * An animated GIF played a frame at a time: its decoder, used off the main thread by one
     * frame's composition at a time, and the frame showing, used on the main thread.
     */
    private static final class GifFrames {
        /** Composes each frame; touched only while {@link #composing}, off the main thread. */
        @NonNull
        final GifDecoder decoder;

        /** The frame showing. */
        @NonNull
        final Bitmap frame;

        /** How long each frame shows, in milliseconds. A GIF loops forever, as from Android 9. */
        @NonNull
        final int[] delays;

        /** Which frame {@link #frame} holds. */
        int shown;

        /** Whether a frame is being composed. */
        boolean composing;

        /** Whether a frame failed to compose, so no more are tried. */
        boolean broken;

        GifFrames(@NonNull GifDecoder decoder, @NonNull Bitmap frame) {
            this.decoder = decoder;
            this.frame = frame;
            this.delays = decoder.getDelays();
        }
    }
}
