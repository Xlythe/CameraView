package com.xlythe.view.camera.edit;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;

import com.xlythe.view.camera.TransformMath;

import java.util.Arrays;

/**
 * Where each pixel of a stored picture lands once its orientation and a {@link PictureEdits}
 * have been applied, and what part of the stored picture an edit needs.
 *
 * <p>An orientation is a clockwise quarter turn after an optional mirror, as Exif and
 * {@link com.xlythe.view.camera.MediaTags} describe one. The eight of them form a group, so any
 * run of turns and flips comes back to one of them; that is what lets turns and flips be saved
 * without touching a pixel.</p>
 */
final class PictureGeometry {
    /** The stored picture's width. */
    private final int mStoredWidth;
    /** The stored picture's height. */
    private final int mStoredHeight;
    /** The upright picture's width. */
    private final int mUprightWidth;
    /** The upright picture's height. */
    private final int mUprightHeight;
    /** The edited picture's width, at full resolution. */
    private final int mOutputWidth;
    /** The edited picture's height, at full resolution. */
    private final int mOutputHeight;
    /** Maps stored pixels to upright ones. */
    private final Matrix mStoredToUpright;
    /** Maps upright pixels to edited ones, at full resolution. */
    private final Matrix mUprightToOutput;

    /**
     * @param storedWidth the stored picture's width
     * @param storedHeight the stored picture's height
     * @param rotation how far the stored picture turns clockwise to stand upright
     * @param mirrored whether it is mirrored left to right before it turns
     * @param edits the changes to make to the upright picture
     */
    PictureGeometry(int storedWidth, int storedHeight, int rotation, boolean mirrored,
            @NonNull PictureEdits edits) {
        mStoredWidth = storedWidth;
        mStoredHeight = storedHeight;
        mStoredToUpright = orientationMatrix(storedWidth, storedHeight, rotation, mirrored);
        boolean transposed = isTransposed(rotation);
        mUprightWidth = transposed ? storedHeight : storedWidth;
        mUprightHeight = transposed ? storedWidth : storedHeight;

        // The quarter turn and the flips, which move the picture whole.
        int turn = edits.getRotationDegrees();
        Matrix toOutput = orientationMatrix(mUprightWidth, mUprightHeight, turn, false);
        boolean turned = isTransposed(turn);
        float width = turned ? mUprightHeight : mUprightWidth;
        float height = turned ? mUprightWidth : mUprightHeight;
        if (edits.isFlippedHorizontally()) {
            toOutput.postScale(-1, 1);
            toOutput.postTranslate(width, 0);
        }
        if (edits.isFlippedVertically()) {
            toOutput.postScale(1, -1);
            toOutput.postTranslate(0, height);
        }
        // The straightening, about the middle, inside the same frame.
        if (edits.getStraightenDegrees() != 0f) {
            toOutput.postRotate(edits.getStraightenDegrees(), width / 2f, height / 2f);
        }
        // The crop.
        RectF crop = edits.getCrop();
        if (crop == null) {
            mOutputWidth = Math.round(width);
            mOutputHeight = Math.round(height);
        } else {
            float left = crop.left * width;
            float top = crop.top * height;
            toOutput.postTranslate(-left, -top);
            mOutputWidth = Math.max(1, Math.round(crop.right * width) - Math.round(left));
            mOutputHeight = Math.max(1, Math.round(crop.bottom * height) - Math.round(top));
        }
        mUprightToOutput = toOutput;
    }

    /** The upright picture's width. */
    @AnyThread
    int getUprightWidth() {
        return mUprightWidth;
    }

    /** The upright picture's height. */
    @AnyThread
    int getUprightHeight() {
        return mUprightHeight;
    }

    /** The edited picture's width, at full resolution. */
    @AnyThread
    int getOutputWidth() {
        return mOutputWidth;
    }

    /** The edited picture's height, at full resolution. */
    @AnyThread
    int getOutputHeight() {
        return mOutputHeight;
    }

    /** Maps upright pixels to edited ones, at full resolution. */
    @AnyThread
    @NonNull
    Matrix uprightToOutput() {
        return new Matrix(mUprightToOutput);
    }

    /** Maps stored pixels to edited ones, at full resolution. */
    @AnyThread
    @NonNull
    Matrix storedToOutput() {
        Matrix matrix = new Matrix(mStoredToUpright);
        matrix.postConcat(mUprightToOutput);
        return matrix;
    }

    /**
     * The part of the stored picture the edited one is made from: every stored pixel that lands
     * inside it, and one more all round so filtering at its edges has neighbours to read.
     *
     * @return a rectangle within the stored picture, never empty
     */
    @AnyThread
    @NonNull
    Rect storedRegion() {
        Matrix toStored = new Matrix();
        storedToOutput().invert(toStored);
        RectF region = new RectF(0, 0, mOutputWidth, mOutputHeight);
        toStored.mapRect(region);
        Rect bounds = new Rect((int) Math.floor(region.left) - 1,
                (int) Math.floor(region.top) - 1, (int) Math.ceil(region.right) + 1,
                (int) Math.ceil(region.bottom) + 1);
        if (!bounds.intersect(0, 0, mStoredWidth, mStoredHeight)) {
            // The crop sees none of the picture, only the corners straightening leaves empty.
            return new Rect(0, 0, Math.min(1, mStoredWidth), Math.min(1, mStoredHeight));
        }
        return bounds;
    }

    /**
     * Maps a stored picture's pixels to its upright ones.
     *
     * @param rotation a clockwise quarter turn
     * @param mirrored whether the picture is mirrored left to right before it turns
     */
    @AnyThread
    @NonNull
    static Matrix orientationMatrix(int width, int height, int rotation, boolean mirrored) {
        Matrix matrix = new Matrix();
        if (mirrored) {
            matrix.setScale(-1, 1);
            matrix.postTranslate(width, 0);
        }
        switch (TransformMath.within360(rotation)) {
            case 90:
                matrix.postRotate(90);
                matrix.postTranslate(height, 0);
                break;
            case 180:
                matrix.postRotate(180);
                matrix.postTranslate(width, height);
                break;
            case 270:
                matrix.postRotate(270);
                matrix.postTranslate(0, width);
                break;
            default:
                break;
        }
        return matrix;
    }

    /** Whether a quarter turn swaps the width and the height. */
    @AnyThread
    static boolean isTransposed(int rotation) {
        int turn = TransformMath.within360(rotation);
        return turn == 90 || turn == 270;
    }

    /**
     * The orientation a stored picture has once a quarter turn and flips are applied to it
     * upright, as {clockwise turn, 1 if mirrored before it or else 0}.
     *
     * @param rotation how the stored picture turns upright now
     * @param mirrored whether it is mirrored before turning now
     */
    @AnyThread
    @NonNull
    static int[] combine(int rotation, boolean mirrored, int turn, boolean flipHorizontally,
            boolean flipVertically) {
        // Each orientation as the 2x2 part of its matrix, which is all that tells them apart.
        int[] linear = linear(rotation, mirrored);
        linear = multiply(linear(turn, false), linear);
        if (flipHorizontally) {
            linear = multiply(new int[] {-1, 0, 0, 1}, linear);
        }
        if (flipVertically) {
            linear = multiply(new int[] {1, 0, 0, -1}, linear);
        }
        for (int quarter = 0; quarter < 4; quarter++) {
            for (int mirror = 0; mirror < 2; mirror++) {
                if (Arrays.equals(linear(quarter * 90, mirror == 1), linear)) {
                    return new int[] {quarter * 90, mirror};
                }
            }
        }
        throw new IllegalStateException("Turns and flips always make an orientation");
    }

    /** The 2x2 matrix {a, b, c, d}, mapping (x, y) to (ax + by, cx + dy), of an orientation. */
    @NonNull
    private static int[] linear(int rotation, boolean mirrored) {
        int[] matrix = mirrored ? new int[] {-1, 0, 0, 1} : new int[] {1, 0, 0, 1};
        // A clockwise quarter turn on a screen, where y points down.
        int[] quarter = {0, -1, 1, 0};
        for (int i = 0; i < TransformMath.within360(rotation) / 90; i++) {
            matrix = multiply(quarter, matrix);
        }
        return matrix;
    }

    /** The product {@code first * second}, which applies second and then first. */
    @NonNull
    private static int[] multiply(@NonNull int[] first, @NonNull int[] second) {
        return new int[] {
                first[0] * second[0] + first[1] * second[2],
                first[0] * second[1] + first[1] * second[3],
                first[2] * second[0] + first[3] * second[2],
                first[2] * second[1] + first[3] * second[3],
        };
    }
}
