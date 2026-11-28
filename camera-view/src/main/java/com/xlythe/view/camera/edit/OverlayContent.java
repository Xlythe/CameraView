package com.xlythe.view.camera.edit;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.WorkerThread;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * What an overlay item shows: a still picture, an animation, or laid-out text, drawn into the
 * item's own units and read for how opaque it is where a touch lands.
 *
 * <p>An animation is either frames the library decoded, an animated PNG on any release, shown
 * by the moment asked for; or, for an animated GIF or WebP from Android 9, the platform's
 * {@link AnimatedImageDrawable}, which plays on its own clock and shows the frame it is on.</p>
 */
final class OverlayContent {
    /** The tag this logs under. */
    private static final String TAG = "OverlayContent";

    /** The most bytes of a sticker read to decode it. */
    private static final int MAX_BYTES = 20 * 1024 * 1024;

    /** A still picture, or null. */
    @Nullable private final Bitmap mStill;
    /** Frames the library decoded, or null. */
    @Nullable private final Animation mAnimation;
    /** The platform's animation, from Android 9, or null. */
    @Nullable private final Drawable mDrawable;
    /** Laid-out text, or null. */
    @Nullable private final StaticLayout mText;
    /** The text's size, in the picture's units. */
    private final float mTextSize;
    /** The width and height, in the item's units. */
    private final float mWidth;
    private final float mHeight;

    private OverlayContent(@Nullable Bitmap still, @Nullable Animation animation,
            @Nullable Drawable drawable, @Nullable StaticLayout text, float textSize, float width,
            float height) {
        mStill = still;
        mAnimation = animation;
        mDrawable = drawable;
        mText = text;
        mTextSize = textSize;
        mWidth = width;
        mHeight = height;
    }

    /** Lays out text at a size, in the picture's units. */
    @NonNull
    @SuppressWarnings("deprecation")
    static OverlayContent text(@NonNull CharSequence text, int color, float size) {
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setTextSize(Math.max(1f, size));
        int width = (int) Math.ceil(StaticLayout.getDesiredWidth(text, paint)) + 1;
        StaticLayout layout = Build.VERSION.SDK_INT >= 23
                ? StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                        .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
                : new StaticLayout(text, paint, width, Layout.Alignment.ALIGN_CENTER, 1f, 0f,
                        false);
        return new OverlayContent(null, null, null, layout, size, layout.getWidth(),
                layout.getHeight());
    }

    /**
     * Decodes a sticker: an animated PNG's frames, an animated GIF or WebP through the platform
     * from Android 9, or a still at no more than a longest side.
     *
     * @return the content, or null if it cannot be read or decoded
     */
    @WorkerThread
    @Nullable
    static OverlayContent decode(@NonNull Context context, @NonNull Uri uri,
            @Nullable String mimeType, int maxDimension, long maxAnimationPixels) {
        byte[] data;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) {
                return null;
            }
            data = readAll(input);
        } catch (IOException | SecurityException e) {
            Log.w(TAG, "Unable to read " + uri, e);
            return null;
        }
        try {
            Animation animation = null;
            if (Animations.isPng(data)) {
                animation = Animations.decodePng(data, maxAnimationPixels);
            } else if (Animations.isGif(data) || Animations.isAnimatedWebp(data)) {
                if (Build.VERSION.SDK_INT >= 28) {
                    Drawable drawable = Platform.decodeOrNull(data, maxDimension);
                    if (drawable != null) {
                        return new OverlayContent(null, null, drawable, null, 0,
                                drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
                    }
                } else {
                    animation = Animations.decodeLegacy(data, maxAnimationPixels);
                }
            }
            if (animation != null && animation.frames.length > 1) {
                return new OverlayContent(null, animation, null, null, 0, animation.width(),
                        animation.height());
            }
            if (animation != null) {
                animation.recycle();
            }
        } catch (IOException e) {
            Log.d(TAG, uri + " does not animate here, so it shows still", e);
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (options.inSampleSize * 2)
                >= maxDimension) {
            options.inSampleSize *= 2;
        }
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (bitmap == null) {
            return null;
        }
        return new OverlayContent(bitmap, null, null, null, 0, bitmap.getWidth(),
                bitmap.getHeight());
    }

    @NonNull
    private static byte[] readAll(@NonNull InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            out.write(buffer, 0, count);
            if (out.size() > MAX_BYTES) {
                throw new IOException("A sticker larger than " + MAX_BYTES + " bytes");
            }
        }
        return out.toByteArray();
    }

    /**
     * Lets the platform's animation ask its view to redraw and to time its next frame, and
     * starts it playing; or, for null, stops it. Nothing for any other content.
     */
    @MainThread
    void setCallback(@Nullable Drawable.Callback callback) {
        if (mDrawable == null || Build.VERSION.SDK_INT < 28) {
            return;
        }
        mDrawable.setCallback(callback);
        if (callback == null) {
            Platform.stop(mDrawable);
        } else {
            Platform.start(mDrawable);
        }
    }

    @AnyThread
    boolean isAnimated() {
        return mAnimation != null || mDrawable != null;
    }

    /**
     * How long after a moment of its animation the frame changes, or -1 if it never does or the
     * platform times it itself.
     */
    @AnyThread
    long untilNextFrame(long timeMillis) {
        return mAnimation == null ? -1 : mAnimation.untilNextFrame(timeMillis);
    }

    @AnyThread
    float width() {
        return mWidth;
    }

    @AnyThread
    float height() {
        return mHeight;
    }

    @AnyThread
    float textSize() {
        return mTextSize;
    }

    /**
     * Draws the content over an item's own units: frames the library decoded at a moment of
     * their animation, and the platform's animation at the frame it is on.
     */
    @MainThread
    void draw(@NonNull Canvas canvas, @NonNull Overlay overlay, long timeMillis,
            @NonNull Paint paint) {
        Bitmap bitmap = mAnimation != null ? mAnimation.bitmapAt(timeMillis) : mStill;
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, null, new RectF(0, 0, overlay.getWidth(),
                    overlay.getHeight()), paint);
            return;
        }
        if (mDrawable != null) {
            int saved = canvas.save();
            canvas.scale(overlay.getWidth() / Math.max(1f, mWidth),
                    overlay.getHeight() / Math.max(1f, mHeight));
            mDrawable.setBounds(0, 0, Math.round(mWidth), Math.round(mHeight));
            mDrawable.setAlpha(paint.getAlpha());
            mDrawable.draw(canvas);
            canvas.restoreToCount(saved);
            return;
        }
        StaticLayout text = mText;
        if (text == null) {
            return;
        }
        int saved = canvas.save();
        canvas.scale(overlay.getWidth() / Math.max(1f, mWidth),
                overlay.getHeight() / Math.max(1f, mHeight));
        if (overlay.getBackground() == Overlay.BACKGROUND_SOLID) {
            Paint block = new Paint(Paint.ANTI_ALIAS_FLAG);
            block.setColor(overlay.getBackgroundColor());
            float pad = mTextSize * 0.25f;
            canvas.drawRoundRect(new RectF(-pad, -pad / 2, mWidth + pad, mHeight + pad / 2),
                    pad, pad, block);
        } else if (overlay.getBackground() == Overlay.BACKGROUND_OUTLINE) {
            TextPaint textPaint = text.getPaint();
            int color = textPaint.getColor();
            Paint.Style style = textPaint.getStyle();
            textPaint.setStyle(Paint.Style.STROKE);
            textPaint.setStrokeWidth(mTextSize * 0.12f);
            textPaint.setColor(overlay.getBackgroundColor());
            text.draw(canvas);
            textPaint.setStyle(style);
            textPaint.setColor(color);
        }
        TextPaint textPaint = text.getPaint();
        int alpha = textPaint.getAlpha();
        textPaint.setAlpha(alpha * paint.getAlpha() / 255);
        text.draw(canvas);
        textPaint.setAlpha(alpha);
        canvas.restoreToCount(saved);
    }

    /**
     * How opaque the content is at a point, given as fractions of its width and height: a
     * picture's own alpha there, at a moment of frames the library decoded or the frame the
     * platform's animation is on, or opaque anywhere over text.
     */
    @MainThread
    int alphaAt(float fractionX, float fractionY, long timeMillis) {
        Bitmap bitmap = mAnimation != null ? mAnimation.bitmapAt(timeMillis) : mStill;
        if (bitmap != null) {
            int x = Math.max(0, Math.min(bitmap.getWidth() - 1,
                    (int) (fractionX * bitmap.getWidth())));
            int y = Math.max(0, Math.min(bitmap.getHeight() - 1,
                    (int) (fractionY * bitmap.getHeight())));
            return Color.alpha(bitmap.getPixel(x, y));
        }
        if (mDrawable != null) {
            Bitmap pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(pixel);
            canvas.translate(-fractionX * mWidth, -fractionY * mHeight);
            mDrawable.setBounds(0, 0, Math.round(mWidth), Math.round(mHeight));
            mDrawable.setAlpha(255);
            mDrawable.draw(canvas);
            int alpha = Color.alpha(pixel.getPixel(0, 0));
            pixel.recycle();
            return alpha;
        }
        return 255;
    }

    /** The platform's animated GIF and WebP decoder, kept apart so nothing older loads it. */
    @RequiresApi(28)
    private static final class Platform {
        private Platform() {}

        /**
         * Decodes an animation, no larger than a longest side.
         *
         * @return it, or null if the platform does not animate it or cannot decode it, so the
         *     sticker shows still
         */
        @WorkerThread
        @Nullable
        static Drawable decodeOrNull(@NonNull byte[] data, int maxDimension) {
            try {
                return decode(data, maxDimension);
            } catch (IOException | RuntimeException e) {
                Log.d(TAG, "The platform does not animate this sticker", e);
                return null;
            }
        }

        @WorkerThread
        @Nullable
        private static Drawable decode(@NonNull byte[] data, int maxDimension)
                throws IOException {
            Drawable drawable = ImageDecoder.decodeDrawable(
                    ImageDecoder.createSource(ByteBuffer.wrap(data)),
                    (decoder, info, source) -> {
                        int width = info.getSize().getWidth();
                        int height = info.getSize().getHeight();
                        int longest = Math.max(width, height);
                        if (longest > maxDimension) {
                            float scale = maxDimension / (float) longest;
                            decoder.setTargetSize(Math.max(1, Math.round(width * scale)),
                                    Math.max(1, Math.round(height * scale)));
                        }
                    });
            return drawable instanceof AnimatedImageDrawable ? drawable : null;
        }

        static void start(@NonNull Drawable drawable) {
            ((AnimatedImageDrawable) drawable).start();
        }

        static void stop(@NonNull Drawable drawable) {
            ((AnimatedImageDrawable) drawable).stop();
        }
    }
}
