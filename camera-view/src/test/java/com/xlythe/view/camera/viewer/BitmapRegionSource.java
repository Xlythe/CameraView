package com.xlythe.view.camera.viewer;

import android.graphics.Bitmap;
import android.graphics.Rect;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** A picture held in memory, decoded a piece at a time by cropping it and shrinking the crop. */
final class BitmapRegionSource implements RegionSource {
    private final Bitmap mBitmap;

    BitmapRegionSource(@NonNull Bitmap bitmap) {
        mBitmap = bitmap;
    }

    @Override
    public int getWidth() {
        return mBitmap.getWidth();
    }

    @Override
    public int getHeight() {
        return mBitmap.getHeight();
    }

    @Nullable
    @Override
    public Bitmap decode(@NonNull Rect region, int sampleSize) {
        Bitmap crop = Bitmap.createBitmap(mBitmap, region.left, region.top, region.width(),
                region.height());
        if (sampleSize == 1) {
            return crop.copy(Bitmap.Config.ARGB_8888, true);
        }
        Bitmap scaled = Bitmap.createScaledBitmap(crop,
                Tiles.ceilDivide(region.width(), sampleSize),
                Tiles.ceilDivide(region.height(), sampleSize), true);
        if (crop != mBitmap && crop != scaled) {
            crop.recycle();
        }
        return scaled;
    }

    @Override
    public void close() {}

    /**
     * An equirectangular picture with a colour for each quarter of the compass and each pole, and
     * a narrow cyan stripe from heading 28 to 32, in the panorama's own headings.
     *
     * @param fullWidth the full panorama's width; the picture may be a crop of it
     * @param left where the crop starts in the full panorama
     * @param top where it starts down
     */
    @NonNull
    static Bitmap compass(int width, int height, int fullWidth, int fullHeight, int left,
            int top) {
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            double pitch = 90 - (top + y + 0.5) / fullHeight * 180;
            for (int x = 0; x < width; x++) {
                double heading = (left + x + 0.5) / fullWidth * 360 - 180;
                pixels[y * width + x] = compassColor(heading, pitch);
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
                .copy(Bitmap.Config.ARGB_8888, true);
    }

    /** The colour the compass picture has looking along a heading and pitch. */
    static int compassColor(double heading, double pitch) {
        if (pitch > 60) {
            return 0xFFFFFFFF;
        }
        if (pitch < -60) {
            return 0xFFFF00FF;
        }
        if (heading >= 28 && heading <= 32) {
            return 0xFF00FFFF;
        }
        if (heading >= -45 && heading < 45) {
            return 0xFFFF0000;
        }
        if (heading >= 45 && heading < 135) {
            return 0xFF00FF00;
        }
        if (heading >= -135 && heading < -45) {
            return 0xFFFFFF00;
        }
        return 0xFF0000FF;
    }
}
