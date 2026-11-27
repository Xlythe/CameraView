package com.xlythe.view.camera;

import android.graphics.RectF;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.nio.ByteBuffer;

/**
 * Follows a patch of the picture from one frame to the next.
 *
 * <p>The patch is whatever was under the point the tracker started at, matched against each new
 * frame by normalized cross-correlation of brightness. Normalizing matters more here than it
 * would elsewhere: the tracker exists to move exposure onto the subject, so the subject's
 * brightness changes as a direct result of being tracked, and a plain difference of pixels would
 * read that as the subject leaving.</p>
 *
 * <p>Each frame is averaged down to a small working picture first, and searched coarse to fine:
 * a wide search on a reduced copy finds roughly where the patch went, and a narrow one at the
 * working size places it and tries it slightly larger and smaller, which is how a subject walking
 * towards the camera keeps its box. The template learns slowly from confident matches, so a
 * subject that turns or changes shape is still recognised, without a single bad frame being
 * learned as the subject.</p>
 *
 * <p>Not thread-safe: one thread feeds it frames.</p>
 */
final class SubjectTracker {
    /** The working picture's longer side, in pixels. Frames are averaged down to about this. */
    static final int WORKING_SIZE = 320;

    /** The template's side, in samples. The patch it covers grows and shrinks with the scale. */
    static final int TEMPLATE_SIZE = 32;

    /** The patch's side at the level the wide search runs on, which picks that level. */
    private static final int COARSE_PATCH_SIZE = 12;

    /** The reduced copies kept for the wide search: half and quarter of the working size. */
    private static final int LEVELS = 3;

    /** The least distance, in pixels of the level searched, the wide search looks either way. */
    private static final int MIN_SEARCH_RADIUS = 6;

    /** Below this correlation a frame's best match is not the subject. */
    static final float MIN_CORRELATION = 0.5f;

    /** Above this correlation a match is trusted enough to teach the template. */
    private static final float LEARNING_CORRELATION = 0.8f;

    /** How much of a confident match goes into the template. */
    private static final float LEARNING_RATE = 0.1f;

    /**
     * Frames in a row without a match before the subject counts as lost.
     *
     * <p>Enough to ride out a hand passing in front of it or a frame of motion blur, few enough
     * that the box does not sit on empty space for long after the subject has really gone.</p>
     */
    static final int MAX_MISSES = 10;

    /** Below this spread of grey levels a patch is flat, and there is nothing in it to follow. */
    static final float MIN_CONTRAST = 4f;

    /** How much larger and smaller the patch is tried on each frame. */
    private static final float SCALE_STEP = 1.05f;

    /** How small the patch may shrink, relative to where it started. */
    private static final float MIN_SCALE = 0.5f;

    /** How large the patch may grow, relative to where it started. */
    private static final float MAX_SCALE = 3f;

    /** How much of the last frame's movement is expected again in the next. */
    private static final float VELOCITY_RETENTION = 0.5f;

    /** The scales tried around the current one on each frame, reused so tracking allocates nothing. */
    private final float[] mScaleCandidates = new float[2];

    /** The frame size the working picture was made for, so a change of camera is noticed. */
    private int mFrameWidth;

    /** See {@link #mFrameWidth}. */
    private int mFrameHeight;

    /** How many frame pixels each working pixel stands for, along each axis. */
    private int mFactor = 1;

    /** The working picture and its reduced copies; level 0 is the working size. */
    private final int[][] mLevels = new int[LEVELS][];

    /** Each level's width, in its own pixels. */
    private final int[] mLevelWidths = new int[LEVELS];

    /** Each level's height, in its own pixels. */
    private final int[] mLevelHeights = new int[LEVELS];

    /** Running sums over the level the wide search uses, for a window's mean in four reads. */
    private long[] mSums = new long[0];

    /** Running sums of squares alongside {@link #mSums}, for a window's spread. */
    private long[] mSquares = new long[0];

    /** What the subject looks like, in grey levels, sampled on a TEMPLATE_SIZE grid. */
    private final float[] mTemplate = new float[TEMPLATE_SIZE * TEMPLATE_SIZE];

    /** The template with its mean removed and scaled to unit length, ready to correlate. */
    private final float[] mNormalized = new float[TEMPLATE_SIZE * TEMPLATE_SIZE];

    /** Samples of the frame under a candidate position, reused so tracking allocates nothing. */
    private final float[] mSamples = new float[TEMPLATE_SIZE * TEMPLATE_SIZE];

    /** The best candidate's samples, kept to teach the template once the frame is decided. */
    private final float[] mBestSamples = new float[TEMPLATE_SIZE * TEMPLATE_SIZE];

    /** Where the subject is, in working pixels. */
    private float mCenterX;

    /** See {@link #mCenterX}. */
    private float mCenterY;

    /** How far it moved last frame, in working pixels, for predicting the next. */
    private float mVelocityX;

    /** See {@link #mVelocityX}. */
    private float mVelocityY;

    /** The patch's size relative to TEMPLATE_SIZE working pixels. */
    private float mScale = 1f;

    /** Frames in a row the subject was not found in. */
    private int mMisses;

    /** The last frame's best correlation, for tests and diagnostics. */
    private float mCorrelation;

    /** Whether there is a subject to follow. */
    private boolean mTracking;

    /**
     * Picks the patch under a point as the subject.
     *
     * @param luma the frame's brightness plane, one byte per pixel
     * @param rowStride bytes from the start of one row to the start of the next
     * @param x the point, in frame pixels
     * @param y the point, in frame pixels
     * @return whether there is anything there to follow. A flat patch - a blank wall, the sky -
     *     matches everywhere equally well, so it is refused rather than followed at random.
     */
    @WorkerThread
    boolean start(@NonNull ByteBuffer luma, int width, int height, int rowStride,
            float x, float y) {
        mTracking = false;
        if (width <= 0 || height <= 0) {
            return false;
        }
        load(luma, width, height, rowStride);
        if (mLevelWidths[0] < TEMPLATE_SIZE || mLevelHeights[0] < TEMPLATE_SIZE) {
            return false;
        }

        mScale = 1f;
        float half = TEMPLATE_SIZE / 2f;
        mCenterX = clamp(x / mFactor, half, mLevelWidths[0] - half);
        mCenterY = clamp(y / mFactor, half, mLevelHeights[0] - half);
        sample(mCenterX, mCenterY, mScale, mTemplate);
        if (spread(mTemplate) < MIN_CONTRAST) {
            return false;
        }
        normalize(mTemplate, mNormalized);
        mVelocityX = 0f;
        mVelocityY = 0f;
        mMisses = 0;
        mCorrelation = 1f;
        mTracking = true;
        return true;
    }

    /**
     * Finds the subject in the next frame.
     *
     * @return where it is, in frame pixels, or null once it is lost. A frame it is missing from
     *     reports where it was last seen, until {@link #MAX_MISSES} in a row give it up.
     */
    @WorkerThread
    @Nullable
    RectF track(@NonNull ByteBuffer luma, int width, int height, int rowStride) {
        if (!mTracking) {
            return null;
        }
        if (width != mFrameWidth || height != mFrameHeight) {
            // A different camera, or the same one reconfigured: the subject's position means
            // nothing in the new frame.
            stop();
            return null;
        }
        load(luma, width, height, rowStride);

        float predictedX = mCenterX + mVelocityX;
        float predictedY = mCenterY + mVelocityY;

        // The wide search, on whichever reduced copy shows the patch at about COARSE_PATCH_SIZE.
        int level = coarseLevel(mScale);
        int step = 1 << level;
        int windowSize = Math.max(4, Math.round(TEMPLATE_SIZE * mScale / step));
        float[] coarseTemplate = resampledTemplate(windowSize);
        integrate(level);
        int radius = Math.max(MIN_SEARCH_RADIUS, windowSize);
        int levelWidth = mLevelWidths[level];
        int levelHeight = mLevelHeights[level];
        int originX = Math.round(predictedX / step - windowSize / 2f);
        int originY = Math.round(predictedY / step - windowSize / 2f);
        float bestCoarse = -2f;
        int bestLeft = clamp(originX, 0, levelWidth - windowSize);
        int bestTop = clamp(originY, 0, levelHeight - windowSize);
        for (int top = Math.max(0, originY - radius);
                top <= Math.min(levelHeight - windowSize, originY + radius); top++) {
            for (int left = Math.max(0, originX - radius);
                    left <= Math.min(levelWidth - windowSize, originX + radius); left++) {
                float correlation = correlateWindow(level, left, top, windowSize, coarseTemplate);
                if (correlation > bestCoarse) {
                    bestCoarse = correlation;
                    bestLeft = left;
                    bestTop = top;
                }
            }
        }

        // The narrow search, at the working size, around what the wide one found.
        float coarseX = (bestLeft + windowSize / 2f) * step;
        float coarseY = (bestTop + windowSize / 2f) * step;
        float best = -2f;
        float bestX = mCenterX;
        float bestY = mCenterY;
        for (int dy = -step; dy <= step; dy++) {
            for (int dx = -step; dx <= step; dx++) {
                float candidateX = coarseX + dx;
                float candidateY = coarseY + dy;
                if (!fits(candidateX, candidateY, mScale)) {
                    continue;
                }
                float correlation = correlateSamples(candidateX, candidateY, mScale);
                if (correlation > best) {
                    best = correlation;
                    bestX = candidateX;
                    bestY = candidateY;
                    System.arraycopy(mSamples, 0, mBestSamples, 0, mSamples.length);
                }
            }
        }

        // And slightly larger and smaller, which is how an approaching subject keeps its box.
        float bestScale = mScale;
        mScaleCandidates[0] = mScale * SCALE_STEP;
        mScaleCandidates[1] = mScale / SCALE_STEP;
        for (float scale : mScaleCandidates) {
            if (best < MIN_CORRELATION || scale < MIN_SCALE || scale > MAX_SCALE
                    || !fits(bestX, bestY, scale)) {
                continue;
            }
            float correlation = correlateSamples(bestX, bestY, scale);
            if (correlation > best) {
                best = correlation;
                bestScale = scale;
                System.arraycopy(mSamples, 0, mBestSamples, 0, mSamples.length);
            }
        }
        mCorrelation = best;

        if (best < MIN_CORRELATION) {
            // Not here. Hold where it was last seen rather than chase noise, and give up after
            // long enough that it is not just a frame of blur.
            mVelocityX *= VELOCITY_RETENTION;
            mVelocityY *= VELOCITY_RETENTION;
            if (++mMisses >= MAX_MISSES) {
                stop();
                return null;
            }
            return bounds();
        }

        mMisses = 0;
        mVelocityX = VELOCITY_RETENTION * mVelocityX
                + (1 - VELOCITY_RETENTION) * (bestX - mCenterX);
        mVelocityY = VELOCITY_RETENTION * mVelocityY
                + (1 - VELOCITY_RETENTION) * (bestY - mCenterY);
        mCenterX = bestX;
        mCenterY = bestY;
        mScale = bestScale;
        if (best > LEARNING_CORRELATION) {
            for (int i = 0; i < mTemplate.length; i++) {
                mTemplate[i] += LEARNING_RATE * (mBestSamples[i] - mTemplate[i]);
            }
            normalize(mTemplate, mNormalized);
        }
        return bounds();
    }

    /** Forgets the subject. */
    void stop() {
        mTracking = false;
    }

    /** Whether there is a subject being followed. */
    boolean isTracking() {
        return mTracking;
    }

    /** How well the last frame matched, from -1 to 1. */
    float getCorrelation() {
        return mCorrelation;
    }

    /** Where the subject is, in frame pixels. Meaningful only while {@link #isTracking}. */
    @NonNull
    RectF getBounds() {
        return bounds();
    }

    /** The patch, in frame pixels. */
    @NonNull
    private RectF bounds() {
        float half = TEMPLATE_SIZE * mScale / 2f;
        return new RectF(
                (mCenterX - half) * mFactor,
                (mCenterY - half) * mFactor,
                (mCenterX + half) * mFactor,
                (mCenterY + half) * mFactor);
    }

    /** The reduced copy on which a patch at this scale spans about COARSE_PATCH_SIZE pixels. */
    private static int coarseLevel(float scale) {
        float size = TEMPLATE_SIZE * scale;
        int level = 0;
        while (level < LEVELS - 1 && size / (1 << (level + 1)) >= COARSE_PATCH_SIZE) {
            level++;
        }
        return level;
    }

    /** Whether a patch centred here at this scale lies inside the working picture. */
    private boolean fits(float centerX, float centerY, float scale) {
        float half = TEMPLATE_SIZE * scale / 2f;
        return centerX - half >= 0 && centerY - half >= 0
                && centerX + half <= mLevelWidths[0] && centerY + half <= mLevelHeights[0];
    }

    /**
     * Averages the frame down into the working picture, and that into its reduced copies.
     *
     * <p>Each working pixel averages a two-by-two block at the reduction's stride rather than the
     * whole block it stands for: a wide frame would otherwise cost dozens of reads per pixel, and
     * two by two is already enough to keep fine texture from aliasing into false matches.</p>
     */
    private void load(@NonNull ByteBuffer luma, int width, int height, int rowStride) {
        if (width != mFrameWidth || height != mFrameHeight || mLevels[0] == null) {
            mFrameWidth = width;
            mFrameHeight = height;
            mFactor = Math.max(1,
                    (int) Math.ceil(Math.max(width, height) / (double) WORKING_SIZE));
            int levelWidth = width / mFactor;
            int levelHeight = height / mFactor;
            for (int level = 0; level < LEVELS; level++) {
                mLevelWidths[level] = levelWidth;
                mLevelHeights[level] = levelHeight;
                mLevels[level] = new int[levelWidth * levelHeight];
                levelWidth = Math.max(1, levelWidth / 2);
                levelHeight = Math.max(1, levelHeight / 2);
            }
        }

        int[] working = mLevels[0];
        int workingWidth = mLevelWidths[0];
        int workingHeight = mLevelHeights[0];
        // The neighbour averaged with each sample: the next pixel and row along, except at a
        // factor of one, where the working picture is the frame itself.
        int across = mFactor > 1 ? 1 : 0;
        int down = mFactor > 1 ? rowStride : 0;
        for (int y = 0; y < workingHeight; y++) {
            int row = y * mFactor * rowStride;
            for (int x = 0; x < workingWidth; x++) {
                int a = row + x * mFactor;
                int total = (luma.get(a) & 0xFF) + (luma.get(a + across) & 0xFF)
                        + (luma.get(a + down) & 0xFF) + (luma.get(a + down + across) & 0xFF);
                working[y * workingWidth + x] = total >> 2;
            }
        }

        for (int level = 1; level < LEVELS; level++) {
            int[] source = mLevels[level - 1];
            int sourceWidth = mLevelWidths[level - 1];
            int[] target = mLevels[level];
            int targetWidth = mLevelWidths[level];
            int targetHeight = mLevelHeights[level];
            for (int y = 0; y < targetHeight; y++) {
                int top = 2 * y * sourceWidth;
                int bottom = top + sourceWidth;
                for (int x = 0; x < targetWidth; x++) {
                    int i = 2 * x;
                    target[y * targetWidth + x] = (source[top + i] + source[top + i + 1]
                            + source[bottom + i] + source[bottom + i + 1]) >> 2;
                }
            }
        }
    }

    /** Fills the running sums for a level, so any window's mean and spread cost four reads. */
    private void integrate(int level) {
        int width = mLevelWidths[level];
        int height = mLevelHeights[level];
        int stride = width + 1;
        int size = stride * (height + 1);
        if (mSums.length < size) {
            mSums = new long[size];
            mSquares = new long[size];
        }
        int[] pixels = mLevels[level];
        for (int x = 0; x < stride; x++) {
            mSums[x] = 0;
            mSquares[x] = 0;
        }
        for (int y = 1; y <= height; y++) {
            long rowSum = 0;
            long rowSquares = 0;
            mSums[y * stride] = 0;
            mSquares[y * stride] = 0;
            for (int x = 1; x <= width; x++) {
                int value = pixels[(y - 1) * width + (x - 1)];
                rowSum += value;
                rowSquares += (long) value * value;
                mSums[y * stride + x] = mSums[(y - 1) * stride + x] + rowSum;
                mSquares[y * stride + x] = mSquares[(y - 1) * stride + x] + rowSquares;
            }
        }
    }

    /** Correlates a normalized template with the window at (left, top) on a level. */
    private float correlateWindow(
            int level, int left, int top, int size, @NonNull float[] template) {
        int width = mLevelWidths[level];
        int stride = width + 1;
        int a = top * stride + left;
        int b = a + size;
        int c = (top + size) * stride + left;
        int d = c + size;
        double sum = mSums[d] - mSums[b] - mSums[c] + mSums[a];
        double squares = mSquares[d] - mSquares[b] - mSquares[c] + mSquares[a];
        double variance = squares - sum * sum / (size * size);
        if (variance < 1e-6) {
            return 0f;
        }
        int[] pixels = mLevels[level];
        double dot = 0;
        for (int y = 0; y < size; y++) {
            int row = (top + y) * width + left;
            int templateRow = y * size;
            for (int x = 0; x < size; x++) {
                dot += pixels[row + x] * template[templateRow + x];
            }
        }
        return (float) (dot / Math.sqrt(variance));
    }

    /** Samples the working picture under a candidate and correlates that with the template. */
    private float correlateSamples(float centerX, float centerY, float scale) {
        sample(centerX, centerY, scale, mSamples);
        double sum = 0;
        double squares = 0;
        double dot = 0;
        for (int i = 0; i < mSamples.length; i++) {
            float value = mSamples[i];
            sum += value;
            squares += value * value;
            dot += value * mNormalized[i];
        }
        double variance = squares - sum * sum / mSamples.length;
        if (variance < 1e-6) {
            return 0f;
        }
        return (float) (dot / Math.sqrt(variance));
    }

    /**
     * Reads the working picture on a TEMPLATE_SIZE grid spread over a patch of the given scale.
     *
     * <p>Bilinear, because a scaled patch's grid falls between pixels, and nearest-pixel sampling
     * there makes the correlation jump as the scale changes.</p>
     */
    private void sample(float centerX, float centerY, float scale, @NonNull float[] out) {
        int[] pixels = mLevels[0];
        int width = mLevelWidths[0];
        int height = mLevelHeights[0];
        float left = centerX - TEMPLATE_SIZE * scale / 2f;
        float top = centerY - TEMPLATE_SIZE * scale / 2f;
        for (int j = 0; j < TEMPLATE_SIZE; j++) {
            float y = clamp(top + (j + 0.5f) * scale - 0.5f, 0, height - 1);
            int y0 = (int) y;
            int y1 = Math.min(y0 + 1, height - 1);
            float fy = y - y0;
            for (int i = 0; i < TEMPLATE_SIZE; i++) {
                float x = clamp(left + (i + 0.5f) * scale - 0.5f, 0, width - 1);
                int x0 = (int) x;
                int x1 = Math.min(x0 + 1, width - 1);
                float fx = x - x0;
                float upper = pixels[y0 * width + x0] * (1 - fx) + pixels[y0 * width + x1] * fx;
                float lower = pixels[y1 * width + x0] * (1 - fx) + pixels[y1 * width + x1] * fx;
                out[j * TEMPLATE_SIZE + i] = upper * (1 - fy) + lower * fy;
            }
        }
    }

    /** The template averaged down to a square of the given side, normalized to correlate. */
    @NonNull
    private float[] resampledTemplate(int size) {
        float[] resampled = new float[size * size];
        float ratio = TEMPLATE_SIZE / (float) size;
        for (int y = 0; y < size; y++) {
            int fromY = Math.min((int) (y * ratio), TEMPLATE_SIZE - 1);
            int toY = Math.min(Math.max(fromY + 1, (int) ((y + 1) * ratio)), TEMPLATE_SIZE);
            for (int x = 0; x < size; x++) {
                int fromX = Math.min((int) (x * ratio), TEMPLATE_SIZE - 1);
                int toX = Math.min(Math.max(fromX + 1, (int) ((x + 1) * ratio)), TEMPLATE_SIZE);
                float total = 0;
                for (int ty = fromY; ty < toY; ty++) {
                    for (int tx = fromX; tx < toX; tx++) {
                        total += mTemplate[ty * TEMPLATE_SIZE + tx];
                    }
                }
                resampled[y * size + x] = total / ((toY - fromY) * (toX - fromX));
            }
        }
        normalize(resampled, resampled);
        return resampled;
    }

    /** Removes the mean and scales to unit length, which turns a dot product into a correlation. */
    private static void normalize(@NonNull float[] values, @NonNull float[] out) {
        double sum = 0;
        for (float value : values) {
            sum += value;
        }
        float mean = (float) (sum / values.length);
        double squares = 0;
        for (int i = 0; i < values.length; i++) {
            float centered = values[i] - mean;
            out[i] = centered;
            squares += centered * centered;
        }
        float length = (float) Math.sqrt(squares);
        if (length < 1e-6f) {
            return;
        }
        for (int i = 0; i < out.length; i++) {
            out[i] /= length;
        }
    }

    /** The standard deviation of a patch's grey levels. */
    private static float spread(@NonNull float[] values) {
        double sum = 0;
        double squares = 0;
        for (float value : values) {
            sum += value;
            squares += value * value;
        }
        double mean = sum / values.length;
        return (float) Math.sqrt(Math.max(0, squares / values.length - mean * mean));
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }
}
