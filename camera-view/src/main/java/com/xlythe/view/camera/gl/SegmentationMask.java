package com.xlythe.view.camera.gl;

import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Which parts of a frame are the subject, as a grid of confidences from 0, the background, to 1,
 * the subject. Build one from a segmentation model of your own and hand it to
 * {@link BackgroundReplacer#setMask}; a model with the opposite convention has to be inverted
 * first.
 */
public final class SegmentationMask {
    /** Below this a sample is treated as background, for the callers who want a hard edge. */
    private static final float SUBJECT_THRESHOLD = 0.5f;

    /** One confidence per sample, row major, from 0 to 1. */
    private final FloatBuffer mConfidence;
    /** The mask's width in samples. */
    private final int mWidth;
    /** The mask's height in samples. */
    private final int mHeight;
    /** Whether the mask came from a mirrored frame, so a view lookup is flipped to find it. */
    private final boolean mMirrored;
    /**
     * When the frame the mask was made from was taken, on the camera's clock in nanoseconds, or
     * 0 when that is not known.
     */
    private final long mTimestampNanos;

    /**
     * @param confidence one float per sample, row major, each between nought and one
     * @param width the mask's width in samples
     * @param height its height in samples
     * @param mirrored true when the mask was produced from a mirrored front-camera frame, so a
     *     lookup in view coordinates has to be flipped to find it
     */
    public SegmentationMask(@NonNull ByteBuffer confidence, int width, int height,
            boolean mirrored) {
        this(confidence, width, height, mirrored, 0);
    }

    /**
     * A mask that says when its frame was taken, so a picture is drawn with the mask made nearest
     * to it rather than whichever arrived last.
     *
     * @param timestampNanos when the frame was taken, on the camera's clock in nanoseconds
     * @hide
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    public SegmentationMask(@NonNull ByteBuffer confidence, int width, int height,
            boolean mirrored, long timestampNanos) {
        mConfidence = confidence.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer();
        mWidth = width;
        mHeight = height;
        mMirrored = mirrored;
        mTimestampNanos = timestampNanos;
    }

    /** The mask's width in samples, which is not the view's. */
    public int getWidth() {
        return mWidth;
    }

    /** The mask's height in samples. */
    public int getHeight() {
        return mHeight;
    }

    /** Whether the mask was made from a mirrored frame, so it is read flipped left to right. */
    boolean isMirrored() {
        return mMirrored;
    }

    /** When the frame the mask was made from was taken, in nanoseconds, or 0 if not known. */
    long getTimestampNanos() {
        return mTimestampNanos;
    }

    /**
     * How confident the model is that this point is the subject.
     *
     * @return nought to one, and nought for a point the mask does not cover
     */
    public float confidenceAt(float viewX, float viewY, int viewWidth, int viewHeight) {
        int x = Sampling.sampleFor(viewX, viewWidth, mWidth);
        int y = Sampling.sampleFor(viewY, viewHeight, mHeight);
        if (x < 0 || y < 0) {
            return 0;
        }
        if (mMirrored) {
            x = mWidth - 1 - x;
        }
        int at = y * mWidth + x;
        if (at < 0 || at >= mConfidence.capacity()) {
            return 0;
        }
        float confidence = mConfidence.get(at);
        // A model is entitled to hand back something slightly outside the range it promised.
        return Math.max(0, Math.min(1, confidence));
    }

    /** Whether a point is the subject rather than the background. */
    public boolean isSubjectAt(float viewX, float viewY, int viewWidth, int viewHeight) {
        return confidenceAt(viewX, viewY, viewWidth, viewHeight) >= SUBJECT_THRESHOLD;
    }

    /** The mask as one byte per sample, from 0 to 255, for uploading to a texture. */
    @NonNull
    @VisibleForTesting
    public byte[] toAlpha() {
        byte[] alpha = new byte[mWidth * mHeight];
        for (int i = 0; i < alpha.length && i < mConfidence.capacity(); i++) {
            float confidence = Math.max(0, Math.min(1, mConfidence.get(i)));
            alpha[i] = (byte) Math.round(confidence * 255);
        }
        return alpha;
    }
}
