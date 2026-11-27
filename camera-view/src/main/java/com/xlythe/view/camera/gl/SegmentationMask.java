package com.xlythe.view.camera.gl;

import androidx.annotation.NonNull;
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

    private final FloatBuffer mConfidence;
    private final int mWidth;
    private final int mHeight;
    private final boolean mMirrored;

    /**
     * @param confidence one float per sample, row major, each between nought and one
     * @param width the mask's width in samples
     * @param height its height in samples
     * @param mirrored true when the mask was produced from a mirrored front-camera frame, so a
     *     lookup in view coordinates has to be flipped to find it
     */
    public SegmentationMask(@NonNull ByteBuffer confidence, int width, int height,
            boolean mirrored) {
        mConfidence = confidence.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer();
        mWidth = width;
        mHeight = height;
        mMirrored = mirrored;
    }

    /** The mask's width in samples, which is not the view's. */
    public int getWidth() {
        return mWidth;
    }

    /** The mask's height in samples. */
    public int getHeight() {
        return mHeight;
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
