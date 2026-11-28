package com.xlythe.view.camera.edit;

import android.graphics.Bitmap;
import android.graphics.RectF;

import androidx.annotation.AnyThread;
import androidx.annotation.FloatRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xlythe.view.camera.TransformMath;
import com.xlythe.view.camera.gl.ColorMatrix;

import java.util.Arrays;

/**
 * The changes to make to a picture. {@link PictureEditor} applies them.
 *
 * <p>They apply to the picture as it is meant to be seen, already turned the way its metadata
 * says, in this order: the quarter turn, then the flips, then the straightening, then the crop.
 * Each is described as it looks after the ones before it, the way an editing screen shows them:
 * a horizontal flip mirrors the turned picture left to right, and the crop is a part of the
 * turned, flipped and straightened picture.</p>
 *
 * <p>The colour matrix applies to the picture alone unless {@link #isOverlayFiltered()}, and
 * the overlay is drawn over the picture before any of the geometry, so a drawing on it stays
 * where it was drawn when the picture is turned or cropped.</p>
 */
public final class PictureEdits {
    /** The clockwise quarter turn, in degrees: 0, 90, 180 or 270. */
    private final int mRotationDegrees;
    /** Whether the turned picture is mirrored left to right. */
    private final boolean mFlippedHorizontally;
    /** Whether the turned picture is mirrored top to bottom. */
    private final boolean mFlippedVertically;
    /** How far the picture is turned clockwise beneath the crop, in degrees. */
    private final float mStraightenDegrees;
    /** The part to keep, as fractions of the turned picture, or null for all of it. */
    @Nullable private final RectF mCrop;
    /** The colour matrix, as {@link ColorMatrix} defines one, or null for none. */
    @Nullable private final float[] mColorMatrix;
    /** What is drawn over the picture, stretched to cover it, or null for nothing. */
    @Nullable private final Bitmap mOverlay;
    /** Whether the colour matrix applies to the overlay as well. */
    private final boolean mOverlayFiltered;

    private PictureEdits(@NonNull Builder builder) {
        mRotationDegrees = builder.mRotationDegrees;
        mFlippedHorizontally = builder.mFlippedHorizontally;
        mFlippedVertically = builder.mFlippedVertically;
        mStraightenDegrees = builder.mStraightenDegrees;
        mCrop = builder.mCrop == null ? null : new RectF(builder.mCrop);
        mColorMatrix = builder.mColorMatrix == null ? null : builder.mColorMatrix.clone();
        mOverlay = builder.mOverlay;
        mOverlayFiltered = builder.mOverlayFiltered;
    }

    /** How far the picture is turned clockwise: 0, 90, 180 or 270 degrees. */
    @AnyThread
    public int getRotationDegrees() {
        return mRotationDegrees;
    }

    /** Whether the turned picture is mirrored left to right. */
    @AnyThread
    public boolean isFlippedHorizontally() {
        return mFlippedHorizontally;
    }

    /** Whether the turned picture is mirrored top to bottom. */
    @AnyThread
    public boolean isFlippedVertically() {
        return mFlippedVertically;
    }

    /**
     * How far the picture is turned clockwise beneath the crop, in degrees between -45 and 45,
     * to level a horizon.
     */
    @AnyThread
    public float getStraightenDegrees() {
        return mStraightenDegrees;
    }

    /**
     * The part of the picture to keep, as fractions of the turned and flipped picture with
     * (0, 0) at its top left, or null to keep all of it.
     */
    @AnyThread
    @Nullable
    public RectF getCrop() {
        return mCrop == null ? null : new RectF(mCrop);
    }

    /** The colour matrix, as {@link ColorMatrix} defines one, or null for none. */
    @AnyThread
    @Nullable
    public float[] getColorMatrix() {
        return mColorMatrix == null ? null : mColorMatrix.clone();
    }

    /** What is drawn over the picture, stretched to cover it, or null for nothing. */
    @AnyThread
    @Nullable
    public Bitmap getOverlay() {
        return mOverlay;
    }

    /** Whether the colour matrix applies to the overlay as well as the picture. */
    @AnyThread
    public boolean isOverlayFiltered() {
        return mOverlayFiltered;
    }

    /** Whether nothing changes at all. */
    @AnyThread
    boolean isEmpty() {
        return changesOnlyOrientation() && mRotationDegrees == 0 && !mFlippedHorizontally
                && !mFlippedVertically;
    }

    /**
     * Whether the only changes are quarter turns and flips, which a file can take by saying it
     * turns differently, leaving its pixels as they are.
     */
    @AnyThread
    boolean changesOnlyOrientation() {
        return keepsFraming() && mColorMatrix == null && mOverlay == null;
    }

    /** Whether every pixel of the picture stays in the result: no crop and no straightening. */
    @AnyThread
    boolean keepsFraming() {
        return mCrop == null && mStraightenDegrees == 0f;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (!(other instanceof PictureEdits)) {
            return false;
        }
        PictureEdits edits = (PictureEdits) other;
        return mRotationDegrees == edits.mRotationDegrees
                && mFlippedHorizontally == edits.mFlippedHorizontally
                && mFlippedVertically == edits.mFlippedVertically
                && Float.compare(mStraightenDegrees, edits.mStraightenDegrees) == 0
                && (mCrop == null ? edits.mCrop == null : mCrop.equals(edits.mCrop))
                && Arrays.equals(mColorMatrix, edits.mColorMatrix)
                && mOverlay == edits.mOverlay
                && mOverlayFiltered == edits.mOverlayFiltered;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(new Object[] {mRotationDegrees, mFlippedHorizontally,
                mFlippedVertically, mStraightenDegrees, mCrop, Arrays.hashCode(mColorMatrix),
                mOverlay, mOverlayFiltered});
    }

    @NonNull
    @Override
    public String toString() {
        return "PictureEdits{rotation=" + mRotationDegrees + ", flipH=" + mFlippedHorizontally
                + ", flipV=" + mFlippedVertically + ", straighten=" + mStraightenDegrees
                + ", crop=" + mCrop + ", filtered=" + (mColorMatrix != null) + ", overlay="
                + (mOverlay != null) + "}";
    }

    /** Builds the changes. Nothing changes until something is set. */
    public static final class Builder {
        /** The quarter turn. */
        private int mRotationDegrees;
        /** The horizontal flip. */
        private boolean mFlippedHorizontally;
        /** The vertical flip. */
        private boolean mFlippedVertically;
        /** The straightening. */
        private float mStraightenDegrees;
        /** The crop, or null. */
        @Nullable private RectF mCrop;
        /** The colour matrix, or null. */
        @Nullable private float[] mColorMatrix;
        /** The overlay, or null. */
        @Nullable private Bitmap mOverlay;
        /** Whether the colour matrix applies to the overlay. */
        private boolean mOverlayFiltered;

        /** Starts with nothing changed. */
        public Builder() {}

        /** Starts from another set of changes. */
        public Builder(@NonNull PictureEdits edits) {
            mRotationDegrees = edits.mRotationDegrees;
            mFlippedHorizontally = edits.mFlippedHorizontally;
            mFlippedVertically = edits.mFlippedVertically;
            mStraightenDegrees = edits.mStraightenDegrees;
            mCrop = edits.mCrop == null ? null : new RectF(edits.mCrop);
            mColorMatrix = edits.mColorMatrix == null ? null : edits.mColorMatrix.clone();
            mOverlay = edits.mOverlay;
            mOverlayFiltered = edits.mOverlayFiltered;
        }

        /**
         * Turns the picture clockwise by a right angle, counted the way Exif and the camera
         * count rotation; a negative angle turns it the other way.
         *
         * @throws IllegalArgumentException if the angle is not a multiple of 90
         */
        @NonNull
        public Builder setRotationDegrees(int degrees) {
            if (degrees % 90 != 0) {
                throw new IllegalArgumentException("Only quarter turns are allowed: " + degrees);
            }
            mRotationDegrees = TransformMath.within360(degrees);
            return this;
        }

        /** Mirrors the turned picture left to right. */
        @NonNull
        public Builder setFlippedHorizontally(boolean flipped) {
            mFlippedHorizontally = flipped;
            return this;
        }

        /** Mirrors the turned picture top to bottom. */
        @NonNull
        public Builder setFlippedVertically(boolean flipped) {
            mFlippedVertically = flipped;
            return this;
        }

        /**
         * Turns the picture clockwise beneath the crop by a small angle, to level a horizon.
         * Where the turned picture leaves the crop's corners empty, a format with transparency
         * leaves them transparent and any other fills them as it fills transparency.
         */
        @NonNull
        public Builder setStraightenDegrees(
                @FloatRange(from = -45, to = 45) float degrees) {
            if (!(degrees >= -45f && degrees <= 45f)) {
                throw new IllegalArgumentException("Straighten by -45 to 45 degrees: " + degrees);
            }
            mStraightenDegrees = degrees == 0f ? 0f : degrees;
            return this;
        }

        /**
         * Keeps only part of the turned, flipped and straightened picture, as fractions of it
         * with (0, 0) at the top left, or all of it for null.
         *
         * @throws IllegalArgumentException if the crop is empty or reaches outside the picture
         */
        @NonNull
        public Builder setCrop(@Nullable RectF crop) {
            if (crop != null && !(crop.left >= 0f && crop.top >= 0f && crop.right <= 1f
                    && crop.bottom <= 1f && crop.left < crop.right && crop.top < crop.bottom)) {
                throw new IllegalArgumentException(
                        "Crop must be a non-empty fraction of the picture: " + crop);
            }
            mCrop = crop == null || (crop.left == 0f && crop.top == 0f && crop.right == 1f
                    && crop.bottom == 1f) ? null : new RectF(crop);
            return this;
        }

        /**
         * Puts every pixel of the picture through a colour matrix, as {@link ColorMatrix} makes
         * them, the same ones a live filter takes, or none for null.
         *
         * @throws IllegalArgumentException if the matrix is not one {@link ColorMatrix} makes
         */
        @NonNull
        public Builder setColorMatrix(@Nullable float[] matrix) {
            if (matrix != null && matrix.length != ColorMatrix.SIZE) {
                throw new IllegalArgumentException("A colour matrix is " + ColorMatrix.SIZE
                        + " floats, not " + matrix.length);
            }
            mColorMatrix = matrix == null || Arrays.equals(matrix, ColorMatrix.identity())
                    ? null : matrix.clone();
            return this;
        }

        /**
         * Draws a picture over this one before it is turned or cropped, such as a drawing or
         * stickers, stretched to cover it: give it the picture's own proportions. It is read
         * while the edit runs, so leave it unchanged until the editor reports.
         */
        @NonNull
        public Builder setOverlay(@Nullable Bitmap overlay) {
            mOverlay = overlay;
            return this;
        }

        /**
         * Whether the colour matrix applies to the overlay too. By default it applies to the
         * picture alone, so a drawing keeps its colours under a black and white filter.
         */
        @NonNull
        public Builder setOverlayFiltered(boolean filtered) {
            mOverlayFiltered = filtered;
            return this;
        }

        @NonNull
        public PictureEdits build() {
            return new PictureEdits(this);
        }
    }
}
