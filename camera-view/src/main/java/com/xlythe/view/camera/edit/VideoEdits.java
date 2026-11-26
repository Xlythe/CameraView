package com.xlythe.view.camera.edit;

import android.graphics.RectF;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xlythe.view.camera.TransformMath;

import java.io.File;

/**
 * The changes to make to a video that has already been recorded. {@link VideoEditor} applies one.
 */
public final class VideoEdits {
    /** The value of {@link #getEndMs()} when the clip is to run to the end of the recording. */
    public static final long UNSET_TIME_MS = -1;

    /** The value of {@link #getOutputHeight()} when the recording's own height is to be kept. */
    public static final int UNSET_HEIGHT = -1;

    private final long mStartMs;
    private final long mEndMs;
    @Nullable private final RectF mCrop;
    private final float mScaleX;
    private final float mScaleY;
    private final int mOutputHeight;
    private final int mRotationDegrees;
    private final boolean mAudioRemoved;
    @Nullable private final File mAudioSource;

    private VideoEdits(long startMs,
                       long endMs,
                       @Nullable RectF crop,
                       float scaleX,
                       float scaleY,
                       int outputHeight,
                       int rotationDegrees,
                       boolean audioRemoved,
                       @Nullable File audioSource) {
        this.mStartMs = startMs;
        this.mEndMs = endMs;
        this.mCrop = crop == null ? null : new RectF(crop);
        this.mScaleX = scaleX;
        this.mScaleY = scaleY;
        this.mOutputHeight = outputHeight;
        this.mRotationDegrees = rotationDegrees;
        this.mAudioRemoved = audioRemoved;
        this.mAudioSource = audioSource;
    }

    /** Where the clip starts, in milliseconds from the start of the recording. */
    public long getStartMs() {
        return mStartMs;
    }

    /** Where the clip ends, or {@link #UNSET_TIME_MS} to run to the end of the recording. */
    public long getEndMs() {
        return mEndMs;
    }

    /**
     * The part of each frame to keep, as a fraction of the recorded frame with (0, 0) at its top
     * left corner, or null to keep all of it.
     */
    @Nullable
    public RectF getCrop() {
        return mCrop == null ? null : new RectF(mCrop);
    }

    /** The horizontal scale to apply, where 1 leaves the width alone. */
    public float getScaleX() {
        return mScaleX;
    }

    /** The vertical scale to apply, where 1 leaves the height alone. */
    public float getScaleY() {
        return mScaleY;
    }

    /** The height to scale the output to, or {@link #UNSET_HEIGHT} to keep the recorded one. */
    public int getOutputHeight() {
        return mOutputHeight;
    }

    /** The clockwise rotation to apply, in degrees within [0, 360). */
    public int getRotationDegrees() {
        return mRotationDegrees;
    }

    /** True if the output is to have no audio track. */
    public boolean isAudioRemoved() {
        return mAudioRemoved;
    }

    /** The file whose audio replaces the recorded audio, or null to keep what was recorded. */
    @Nullable
    public File getAudioSource() {
        return mAudioSource;
    }

    public static class Builder {
        private long mStartMs;
        private long mEndMs = UNSET_TIME_MS;
        @Nullable private RectF mCrop;
        private float mScaleX = 1f;
        private float mScaleY = 1f;
        private int mOutputHeight = UNSET_HEIGHT;
        private int mRotationDegrees;
        private boolean mAudioRemoved;
        @Nullable private File mAudioSource;

        /**
         * Keeps only the part of the recording between two timestamps.
         *
         * @param startMs where the clip starts, in milliseconds from the start of the recording
         * @param endMs where the clip ends, or {@link VideoEdits#UNSET_TIME_MS} to run to the end
         */
        public Builder setTimeRangeMs(@IntRange(from = 0) long startMs, long endMs) {
            mStartMs = startMs;
            mEndMs = endMs;
            return this;
        }

        /** Keeps only part of each frame, given as a fraction of it with (0, 0) at the top left. */
        public Builder setCrop(@Nullable RectF crop) {
            mCrop = crop == null ? null : new RectF(crop);
            return this;
        }

        /** Scales each frame, where 1, 1 leaves it alone. */
        public Builder setScale(float scaleX, float scaleY) {
            mScaleX = scaleX;
            mScaleY = scaleY;
            return this;
        }

        /** Scales each frame to a height, keeping its aspect ratio. */
        public Builder setOutputHeight(int outputHeight) {
            mOutputHeight = outputHeight;
            return this;
        }

        /**
         * Rotates each frame clockwise, counted the way the camera and Exif count rotation, so
         * the value that came off {@code CameraView} can be passed straight in.
         */
        public Builder setRotationDegrees(int rotationDegrees) {
            mRotationDegrees = TransformMath.within360(rotationDegrees);
            return this;
        }

        /** Drops the recorded audio, leaving a video with no audio track. */
        public Builder setAudioRemoved(boolean audioRemoved) {
            mAudioRemoved = audioRemoved;
            return this;
        }

        /**
         * Replaces the recorded audio with the audio from another file.
         *
         * <p>The replacement is looped to the length of the video, so a shorter file repeats and
         * a longer one is cut off; either way the output lasts as long as the video.</p>
         */
        public Builder setAudioSource(@Nullable File audioSource) {
            mAudioSource = audioSource;
            return this;
        }

        public VideoEdits build() {
            if (mAudioRemoved && mAudioSource != null) {
                throw new IllegalStateException("Cannot both remove the audio track and replace it");
            }
            if (mStartMs < 0) {
                throw new IllegalArgumentException("Clip cannot start before the recording does: " + mStartMs);
            }
            if (mEndMs != UNSET_TIME_MS && mEndMs <= mStartMs) {
                throw new IllegalArgumentException("Clip must end after it starts: " + mStartMs + " to " + mEndMs);
            }
            if (mCrop != null && !isWithinFrame(mCrop)) {
                throw new IllegalArgumentException("Crop must be a non-empty fraction of the frame: " + mCrop);
            }
            if (!isPositive(mScaleX) || !isPositive(mScaleY)) {
                throw new IllegalArgumentException("Scale must be positive: " + mScaleX + ", " + mScaleY);
            }
            if (mOutputHeight != UNSET_HEIGHT && mOutputHeight <= 0) {
                throw new IllegalArgumentException("Output height must be positive: " + mOutputHeight);
            }

            return new VideoEdits(mStartMs, mEndMs, mCrop, mScaleX, mScaleY, mOutputHeight,
                    mRotationDegrees, mAudioRemoved, mAudioSource);
        }

        private static boolean isWithinFrame(@NonNull RectF crop) {
            return crop.left >= 0f && crop.top >= 0f && crop.right <= 1f && crop.bottom <= 1f
                    && crop.left < crop.right && crop.top < crop.bottom;
        }

        private static boolean isPositive(float scale) {
            return scale > 0f && !Float.isInfinite(scale);
        }
    }
}
