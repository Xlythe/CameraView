package com.xlythe.view.camera.viewer;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.exifinterface.media.ExifInterface;

/**
 * Turns a picture as stored into the picture as it is meant to be seen, for each of Exif's eight
 * orientations.
 *
 * <p>Every one of them is a quarter turn, a mirror or both, so a rectangle of the stored picture
 * is a rectangle of the upright one, and the turn is applied when drawing rather than by making
 * a turned copy of each tile.</p>
 */
final class Orientation {
    /** How much of the stored x goes into the upright x: x' = a x + b y + c. */
    private final float mA;

    /** How much of the stored y goes into the upright x. */
    private final float mB;

    /** How far the upright x is moved: the picture's width or height when it is mirrored. */
    private final float mC;

    /** How much of the stored x goes into the upright y: y' = d x + e y + f. */
    private final float mD;

    /** How much of the stored y goes into the upright y. */
    private final float mE;

    /** How far the upright y is moved. */
    private final float mF;

    /** The stored picture's width. */
    private final int mStoredWidth;

    /** The stored picture's height. */
    private final int mStoredHeight;

    /** Whether a quarter turn swaps the width and height. */
    private final boolean mTransposed;

    private Orientation(float a, float b, float c, float d, float e, float f, int storedWidth,
            int storedHeight) {
        mA = a;
        mB = b;
        mC = c;
        mD = d;
        mE = e;
        mF = f;
        mStoredWidth = storedWidth;
        mStoredHeight = storedHeight;
        mTransposed = b != 0;
    }

    /**
     * The orientation of a stored picture.
     *
     * @param exifOrientation one of ExifInterface's ORIENTATION_ constants; anything else is
     *     taken as upright
     */
    @AnyThread
    @NonNull
    static Orientation of(int exifOrientation, int width, int height) {
        switch (exifOrientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                return new Orientation(-1, 0, width, 0, 1, 0, width, height);
            case ExifInterface.ORIENTATION_ROTATE_180:
                return new Orientation(-1, 0, width, 0, -1, height, width, height);
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                return new Orientation(1, 0, 0, 0, -1, height, width, height);
            case ExifInterface.ORIENTATION_TRANSPOSE:
                return new Orientation(0, 1, 0, 1, 0, 0, width, height);
            case ExifInterface.ORIENTATION_ROTATE_90:
                return new Orientation(0, -1, height, 1, 0, 0, width, height);
            case ExifInterface.ORIENTATION_TRANSVERSE:
                return new Orientation(0, -1, height, -1, 0, width, width, height);
            case ExifInterface.ORIENTATION_ROTATE_270:
                return new Orientation(0, 1, 0, -1, 0, width, width, height);
            default:
                return new Orientation(1, 0, 0, 0, 1, 0, width, height);
        }
    }

    /**
     * The Exif orientation for a clockwise turn, after mirroring left to right if it says, as
     * {@link com.xlythe.view.camera.MediaTags} describes a picture.
     */
    @AnyThread
    static int exifOrientation(int rotation, boolean mirrored) {
        switch (rotation) {
            case 90:
                return mirrored ? ExifInterface.ORIENTATION_TRANSVERSE
                        : ExifInterface.ORIENTATION_ROTATE_90;
            case 180:
                return mirrored ? ExifInterface.ORIENTATION_FLIP_VERTICAL
                        : ExifInterface.ORIENTATION_ROTATE_180;
            case 270:
                return mirrored ? ExifInterface.ORIENTATION_TRANSPOSE
                        : ExifInterface.ORIENTATION_ROTATE_270;
            default:
                return mirrored ? ExifInterface.ORIENTATION_FLIP_HORIZONTAL
                        : ExifInterface.ORIENTATION_NORMAL;
        }
    }

    /** The upright picture's width. */
    @AnyThread
    int getWidth() {
        return mTransposed ? mStoredHeight : mStoredWidth;
    }

    /** The upright picture's height. */
    @AnyThread
    int getHeight() {
        return mTransposed ? mStoredWidth : mStoredHeight;
    }

    /**
     * The transform from stored to upright pixels, as the nine values an
     * {@link android.graphics.Matrix} takes.
     */
    @AnyThread
    @NonNull
    float[] toMatrixValues() {
        return new float[] {mA, mB, mC, mD, mE, mF, 0, 0, 1};
    }

    /**
     * The stored rectangle an upright one comes from.
     *
     * @return {left, top, right, bottom} in stored pixels
     */
    @AnyThread
    @NonNull
    double[] toStored(double left, double top, double right, double bottom) {
        // A rectangle's opposite corners stay opposite corners under a turn or a mirror.
        double x0 = storedX(left, top);
        double y0 = storedY(left, top);
        double x1 = storedX(right, bottom);
        double y1 = storedY(right, bottom);
        return new double[] {Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1),
                Math.max(y0, y1)};
    }

    private double storedX(double x, double y) {
        // The matrix is orthogonal with entries of 0 and 1 in magnitude, so its inverse is its
        // transpose: x = a (x' - c) + d (y' - f).
        return mA * (x - mC) + mD * (y - mF);
    }

    private double storedY(double x, double y) {
        return mB * (x - mC) + mE * (y - mF);
    }
}
