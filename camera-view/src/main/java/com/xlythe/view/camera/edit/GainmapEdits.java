package com.xlythe.view.camera.edit;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Gainmap;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

/**
 * Carries an HDR picture's gain map through an edit: cropped, turned, flipped and straightened
 * exactly as the picture's own pixels are, and neutral wherever the edited picture shows
 * something the original did not have, so what is drawn over it and the corners straightening
 * leaves are shown at standard brightness rather than lit by the gain map beneath.
 */
@RequiresApi(34)
final class GainmapEdits {
    private GainmapEdits() {}

    /**
     * Gives the edited picture the gain map of the picture it was drawn from.
     *
     * @param decoded the picture as decoded, which carries the gain map
     * @param output the edited picture, which is given one to match
     * @param decodedToOutput where each pixel of {@code decoded} landed in {@code output}
     * @param overlay what was drawn over the picture, or null
     * @param overlayToOutput where each pixel of the overlay landed, or null
     */
    @WorkerThread
    static void carry(@NonNull Bitmap decoded, @NonNull Bitmap output,
            @NonNull Matrix decodedToOutput, @Nullable Bitmap overlay,
            @Nullable Matrix overlayToOutput) {
        Gainmap source = decoded.getGainmap();
        if (source == null) {
            return;
        }
        Bitmap contents = source.getGainmapContents();
        float scaleX = contents.getWidth() / (float) decoded.getWidth();
        float scaleY = contents.getHeight() / (float) decoded.getHeight();
        int width = Math.max(1, Math.round(output.getWidth() * scaleX));
        int height = Math.max(1, Math.round(output.getHeight() * scaleY));
        float toGainX = width / (float) output.getWidth();
        float toGainY = height / (float) output.getHeight();

        Bitmap.Config config = contents.getConfig() == Bitmap.Config.ALPHA_8
                ? Bitmap.Config.ALPHA_8 : Bitmap.Config.ARGB_8888;
        Bitmap moved = Bitmap.createBitmap(width, height, config);
        int[] neutral = neutral(source);
        Canvas canvas = new Canvas(moved);
        canvas.drawColor(config == Bitmap.Config.ALPHA_8
                ? Color.argb(neutral[0], 0, 0, 0)
                : Color.argb(255, neutral[0], neutral[1], neutral[2]), PorterDuff.Mode.SRC);
        Matrix matrix = new Matrix();
        matrix.setScale(1 / scaleX, 1 / scaleY);
        matrix.postConcat(decodedToOutput);
        matrix.postScale(toGainX, toGainY);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        // Replaced rather than blended: the gain map holds ratios, not colours over a ground.
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
        canvas.drawBitmap(contents, matrix, paint);

        if (overlay != null && overlayToOutput != null) {
            Bitmap coverage = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8);
            Matrix toGain = new Matrix(overlayToOutput);
            toGain.postScale(toGainX, toGainY);
            new Canvas(coverage).drawBitmap(overlay, toGain, new Paint(Paint.FILTER_BITMAP_FLAG));
            neutralize(moved, coverage, neutral);
            coverage.recycle();
        }
        output.setGainmap(withContents(source, moved));
    }

    /**
     * The value, for each channel, that leaves a pixel's brightness alone: a gain of one,
     * placed between the map's least and greatest gains on its log scale and raised to its
     * gamma, as a byte.
     */
    @VisibleForTesting
    @NonNull
    static int[] neutral(@NonNull Gainmap gainmap) {
        float[] min = gainmap.getRatioMin();
        float[] max = gainmap.getRatioMax();
        float[] gamma = gainmap.getGamma();
        int[] neutral = new int[3];
        for (int channel = 0; channel < 3; channel++) {
            neutral[channel] = neutral(min[channel], max[channel], gamma[channel]);
        }
        return neutral;
    }

    /** {@link #neutral(Gainmap)} for one channel. */
    @VisibleForTesting
    static int neutral(float ratioMin, float ratioMax, float gamma) {
        double low = Math.log(ratioMin) / Math.log(2);
        double high = Math.log(ratioMax) / Math.log(2);
        if (!(high > low)) {
            return 0;
        }
        double position = Math.max(0, Math.min(1, -low / (high - low)));
        return (int) Math.round(Math.pow(position, gamma) * 255);
    }

    /** Pulls a gain map towards neutral wherever something covers the picture, by how much. */
    @VisibleForTesting
    static void neutralize(@NonNull Bitmap gainmap, @NonNull Bitmap coverage,
            @NonNull int[] neutral) {
        int width = gainmap.getWidth();
        int height = gainmap.getHeight();
        int[] gains = new int[width];
        int[] covered = new int[width];
        boolean single = gainmap.getConfig() == Bitmap.Config.ALPHA_8;
        for (int y = 0; y < height; y++) {
            gainmap.getPixels(gains, 0, width, 0, y, width, 1);
            coverage.getPixels(covered, 0, width, 0, y, width, 1);
            boolean changed = false;
            for (int x = 0; x < width; x++) {
                int amount = covered[x] >>> 24;
                if (amount == 0) {
                    continue;
                }
                changed = true;
                int gain = gains[x];
                if (single) {
                    gains[x] = Color.argb(mix(gain >>> 24, neutral[0], amount), 0, 0, 0);
                } else {
                    gains[x] = Color.argb(gain >>> 24,
                            mix((gain >> 16) & 0xFF, neutral[0], amount),
                            mix((gain >> 8) & 0xFF, neutral[1], amount),
                            mix(gain & 0xFF, neutral[2], amount));
                }
            }
            if (changed) {
                gainmap.setPixels(gains, 0, width, 0, y, width, 1);
            }
        }
    }

    private static int mix(int from, int to, int amount) {
        return (from * (255 - amount) + to * amount + 127) / 255;
    }

    /** A gain map with another's metadata and new contents. */
    @NonNull
    private static Gainmap withContents(@NonNull Gainmap source, @NonNull Bitmap contents) {
        if (Build.VERSION.SDK_INT >= 35) {
            return new Gainmap(source, contents);
        }
        Gainmap gainmap = new Gainmap(contents);
        float[] min = source.getRatioMin();
        float[] max = source.getRatioMax();
        float[] gamma = source.getGamma();
        float[] epsilonSdr = source.getEpsilonSdr();
        float[] epsilonHdr = source.getEpsilonHdr();
        gainmap.setRatioMin(min[0], min[1], min[2]);
        gainmap.setRatioMax(max[0], max[1], max[2]);
        gainmap.setGamma(gamma[0], gamma[1], gamma[2]);
        gainmap.setEpsilonSdr(epsilonSdr[0], epsilonSdr[1], epsilonSdr[2]);
        gainmap.setEpsilonHdr(epsilonHdr[0], epsilonHdr[1], epsilonHdr[2]);
        gainmap.setDisplayRatioForFullHdr(source.getDisplayRatioForFullHdr());
        gainmap.setMinDisplayRatioForHdrTransition(source.getMinDisplayRatioForHdrTransition());
        return gainmap;
    }
}
