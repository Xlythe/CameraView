package com.xlythe.view.camera.viewer;

import android.graphics.Bitmap;
import android.graphics.Rect;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

/**
 * A smaller copy of a picture that answers in the original's pixels.
 *
 * <p>A copy made because the original could not be decoded in pieces may have had to be made at
 * half its size or less. Viewing it through this keeps the original's dimensions, so the
 * picture is laid out at its real size, and a piece asked for at more detail than the copy has
 * comes back at the detail it does have.</p>
 */
final class ScaledRegionSource implements RegionSource {
    /** The copy. */
    @NonNull
    private final RegionSource mCopy;

    /** The original's width. */
    private final int mWidth;

    /** The original's height. */
    private final int mHeight;

    ScaledRegionSource(@NonNull RegionSource copy, int width, int height) {
        mCopy = copy;
        mWidth = width;
        mHeight = height;
    }

    @AnyThread
    @Override
    public int getWidth() {
        return mWidth;
    }

    @AnyThread
    @Override
    public int getHeight() {
        return mHeight;
    }

    @WorkerThread
    @Nullable
    @Override
    public Bitmap decode(@NonNull Rect region, int sampleSize) {
        double scaleX = (double) mCopy.getWidth() / mWidth;
        double scaleY = (double) mCopy.getHeight() / mHeight;
        Rect copyRegion = new Rect(
                (int) Math.floor(region.left * scaleX),
                (int) Math.floor(region.top * scaleY),
                (int) Math.ceil(region.right * scaleX),
                (int) Math.ceil(region.bottom * scaleY));
        if (!copyRegion.intersect(0, 0, mCopy.getWidth(), mCopy.getHeight())) {
            return null;
        }
        return mCopy.decode(copyRegion,
                Tiles.floorPowerOfTwo(sampleSize * Math.min(scaleX, scaleY)));
    }

    @AnyThread
    @Override
    public void close() {
        mCopy.close();
    }
}
