package com.xlythe.view.camera.edit;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;

import com.xlythe.view.camera.gl.ColorMatrix;

/**
 * Turns the colour matrices a live filter takes into the ones {@link android.graphics.Canvas}
 * draws with, so a still is filtered exactly as the preview was.
 */
final class Filters {
    /** The floats in one of Android's colour matrices: four rows of five. */
    static final int ANDROID_SIZE = 20;

    private Filters() {}

    /**
     * The {@link android.graphics.ColorMatrix} values for a {@link ColorMatrix}.
     *
     * <p>A {@link ColorMatrix} is 4x4 and column-major, on straight RGB from 0 to 1 with a
     * constant 1 as the fourth input, and gives every pixel full alpha. Android's is 4x5 and
     * row-major, on RGBA from 0 to 255 with a fifth column of offsets. Alpha is carried through
     * unchanged here, so a transparent overlay stays transparent under a filter.</p>
     */
    @AnyThread
    @NonNull
    static float[] toAndroid(@NonNull float[] matrix) {
        float[] values = new float[ANDROID_SIZE];
        for (int row = 0; row < 3; row++) {
            values[row * 5] = matrix[row];
            values[row * 5 + 1] = matrix[4 + row];
            values[row * 5 + 2] = matrix[8 + row];
            values[row * 5 + 4] = matrix[12 + row] * 255f;
        }
        values[18] = 1f;
        return values;
    }
}
