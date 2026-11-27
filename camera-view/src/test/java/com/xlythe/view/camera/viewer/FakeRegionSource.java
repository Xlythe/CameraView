package com.xlythe.view.camera.viewer;

import android.graphics.Bitmap;
import android.graphics.Rect;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A picture of any size that decodes into solid bitmaps, and counts every pixel it hands out
 * that has not been recycled. That count, not the viewer's own bookkeeping, is what a memory
 * test holds the viewer to.
 */
final class FakeRegionSource implements RegionSource {
    /** One decode, as asked for. */
    static final class Decode {
        final Rect region;
        final int sampleSize;

        Decode(Rect region, int sampleSize) {
            this.region = region;
            this.sampleSize = sampleSize;
        }
    }

    private final int mWidth;
    private final int mHeight;

    /** The colour each decode is filled with. */
    private final int mColor;

    /** Every bitmap handed out. */
    private final List<Bitmap> mHandedOut = new ArrayList<>();

    /** Every decode, in order. */
    private final List<Decode> mDecodes = new ArrayList<>();

    /** The most pixels handed out and not yet recycled at once. */
    private long mPeakLivePixels;

    /** Whether decodes fail from now on. */
    private boolean mFailing;

    private boolean mClosed;

    FakeRegionSource(int width, int height, int color) {
        mWidth = width;
        mHeight = height;
        mColor = color;
    }

    @Override
    public int getWidth() {
        return mWidth;
    }

    @Override
    public int getHeight() {
        return mHeight;
    }

    @Nullable
    @Override
    public synchronized Bitmap decode(@NonNull Rect region, int sampleSize) {
        mDecodes.add(new Decode(new Rect(region), sampleSize));
        if (mFailing || mClosed) {
            return null;
        }
        Bitmap bitmap = Bitmap.createBitmap(Tiles.ceilDivide(region.width(), sampleSize),
                Tiles.ceilDivide(region.height(), sampleSize), Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(mColor);
        mHandedOut.add(bitmap);
        mPeakLivePixels = Math.max(mPeakLivePixels, livePixels());
        return bitmap;
    }

    @Override
    public synchronized void close() {
        mClosed = true;
    }

    synchronized boolean isClosed() {
        return mClosed;
    }

    synchronized void setFailing(boolean failing) {
        mFailing = failing;
    }

    /** Pixels handed out and not recycled. */
    synchronized long livePixels() {
        long pixels = 0;
        for (Bitmap bitmap : mHandedOut) {
            if (!bitmap.isRecycled()) {
                pixels += (long) bitmap.getWidth() * bitmap.getHeight();
            }
        }
        return pixels;
    }

    synchronized long peakLivePixels() {
        // A recycle between decodes can only lower the count, so the peak is seen at a decode.
        return Math.max(mPeakLivePixels, livePixels());
    }

    synchronized int decodeCount() {
        return mDecodes.size();
    }

    synchronized List<Decode> decodes() {
        return new ArrayList<>(mDecodes);
    }
}
