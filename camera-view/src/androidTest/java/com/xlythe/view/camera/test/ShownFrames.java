package com.xlythe.view.camera.test;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.MediaMetadataRetriever;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.io.IOException;

/**
 * Frames as a person sees them: the preview on screen, a recording as a player shows it, and a
 * picture as a gallery shows it.
 *
 * <p>Comparing these rather than the stored pixels is the only way to ask whether a capture
 * matches its preview. A recording is stored in the sensor's orientation and tagged with how to
 * turn it, a picture may be stored mirrored or tagged to be, and the preview is drawn with a
 * transform of its own - so two files that look identical can store nothing alike.</p>
 */
final class ShownFrames {
    /** Pixels sampled across each cell of a brightness grid, each way. */
    private static final int SAMPLES_PER_CELL = 6;

    private ShownFrames() {}

    /**
     * What the view is showing, cut out of a screenshot.
     *
     * <p>A screenshot rather than {@code TextureView#getBitmap}, which reads the texture through
     * only part of the view's transform and comes back turned when the display is.</p>
     */
    @NonNull
    static Bitmap onScreen(@NonNull View view) {
        int[] location = new int[2];
        int[] size = new int[2];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            view.getLocationOnScreen(location);
            size[0] = view.getWidth();
            size[1] = view.getHeight();
        });
        Bitmap screen = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .takeScreenshot();
        if (screen == null) {
            throw new AssertionError("the screen could not be captured");
        }
        int left = Math.max(location[0], 0);
        int top = Math.max(location[1], 0);
        int width = Math.min(size[0], screen.getWidth() - left);
        int height = Math.min(size[1], screen.getHeight() - top);
        Bitmap shown = Bitmap.createBitmap(screen, left, top, width, height);
        if (shown != screen) {
            screen.recycle();
        }
        return shown;
    }

    /**
     * One frame of a recording as a player shows it, or null if none could be decoded.
     *
     * <p>{@link MediaMetadataRetriever#getFrameAtTime} turns the frame by the file's rotation tag
     * before returning it, so nothing more is needed here.</p>
     */
    @Nullable
    static Bitmap ofRecording(@NonNull File file, long timeUs) throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            return retriever.getFrameAtTime(timeUs);
        } finally {
            retriever.release();
        }
    }

    /** A picture as a gallery shows it, with its Exif turn and mirror applied. */
    @NonNull
    static Bitmap ofPicture(@NonNull File file) throws IOException {
        Bitmap stored = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (stored == null) {
            throw new AssertionError(file + " is not an image");
        }
        ExifInterface exif = new ExifInterface(file.getAbsolutePath());
        Matrix shown = new Matrix();
        // Mirrored before it is turned, which is how Exif describes the four mirrored cases.
        if (exif.isFlipped()) {
            shown.postScale(-1f, 1f);
        }
        shown.postRotate(exif.getRotationDegrees());
        if (shown.isIdentity()) {
            return stored;
        }
        Bitmap turned = Bitmap.createBitmap(
                stored, 0, 0, stored.getWidth(), stored.getHeight(), shown, false);
        stored.recycle();
        return turned;
    }

    /** The middle of a frame, cut to the shape of another, as a preview crops its stream. */
    @NonNull
    static Bitmap centredTo(@NonNull Bitmap bitmap, int shapeWidth, int shapeHeight) {
        float shape = (float) shapeWidth / shapeHeight;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if ((float) width / height > shape) {
            width = Math.round(height * shape);
        } else {
            height = Math.round(width / shape);
        }
        return Bitmap.createBitmap(bitmap, (bitmap.getWidth() - width) / 2,
                (bitmap.getHeight() - height) / 2, width, height);
    }

    /**
     * The frame as a coarse grid of brightness, row by row from the top.
     *
     * <p>Averaged over several pixels a cell, because two captures of one scene agree about its
     * shapes long before they agree about any one pixel - they differ in exposure, in sharpening
     * and in where exactly their edges fall.</p>
     */
    @NonNull
    static float[] brightness(@NonNull Bitmap bitmap, int columns, int rows) {
        float[] grid = new float[columns * rows];
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                float sum = 0f;
                for (int y = 0; y < SAMPLES_PER_CELL; y++) {
                    for (int x = 0; x < SAMPLES_PER_CELL; x++) {
                        int pixel = bitmap.getPixel(
                                (int) ((column + (x + 0.5f) / SAMPLES_PER_CELL)
                                        * bitmap.getWidth() / columns),
                                (int) ((row + (y + 0.5f) / SAMPLES_PER_CELL)
                                        * bitmap.getHeight() / rows));
                        sum += 0.299f * Color.red(pixel) + 0.587f * Color.green(pixel)
                                + 0.114f * Color.blue(pixel);
                    }
                }
                grid[row * columns + column] = sum / (SAMPLES_PER_CELL * SAMPLES_PER_CELL);
            }
        }
        return grid;
    }

    /** The grid mirrored left to right. */
    @NonNull
    static float[] mirrored(@NonNull float[] grid, int columns, int rows) {
        float[] mirrored = new float[grid.length];
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                mirrored[row * columns + column] = grid[row * columns + (columns - 1 - column)];
            }
        }
        return mirrored;
    }

    /** Pearson's correlation, which ignores the exposure two captures disagree about. */
    static double correlation(@NonNull float[] a, @NonNull float[] b) {
        double meanA = 0;
        double meanB = 0;
        for (int i = 0; i < a.length; i++) {
            meanA += a[i];
            meanB += b[i];
        }
        meanA /= a.length;
        meanB /= b.length;
        double covariance = 0;
        double varianceA = 0;
        double varianceB = 0;
        for (int i = 0; i < a.length; i++) {
            covariance += (a[i] - meanA) * (b[i] - meanB);
            varianceA += (a[i] - meanA) * (a[i] - meanA);
            varianceB += (b[i] - meanB) * (b[i] - meanB);
        }
        if (varianceA == 0 || varianceB == 0) {
            return 0;
        }
        return covariance / Math.sqrt(varianceA * varianceB);
    }
}
