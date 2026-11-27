package com.xlythe.view.camera.pano;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Measures the lens's real focal length from the first frames of a sweep.
 *
 * <p>A frame is placed with the focal length the camera publishes, and a camera can publish one
 * that does not match what it delivers: a digital crop it does not mention, a logical camera
 * describing a different physical lens, or an emulator rendering at one angle of view and
 * reporting another. Placed with the wrong one, every frame is stretched or squeezed about its own
 * centre, and a sweep comes out with each wall repeated or missing a strip.</p>
 *
 * <p>Two frames taken a few degrees apart settle it. The rotation between them is known from the
 * sensor, so for any focal length there is exactly one way the first frame's pixels should land in
 * the second; the focal length that makes the two pictures agree is the lens's own. Each pair of
 * frames gives one estimate, as a scale on the published focal length, and the scale is settled
 * once a few of them agree.</p>
 */
@WorkerThread
final class LensCalibration {
    /** Frames are compared this small along their long side, which is plenty to align. */
    static final int THUMBNAIL_SIDE = 160;

    /**
     * Below this turn between two frames the focal length changes where pixels land by too little
     * to measure it to within {@link #AGREEMENT}.
     */
    static final float MIN_TURN_DEGREES = 8f;

    /** The scales tried, from this far below the published focal length... */
    static final float MIN_SCALE = 0.4f;

    /** ...to this far above it. */
    static final float MAX_SCALE = 2.5f;

    /** How many estimates have to agree before the scale is settled. */
    static final int AGREEING_ESTIMATES = 3;

    /** How close to their median estimates have to be to count as agreeing. */
    static final float AGREEMENT = 0.05f;

    /**
     * After comparing this many pairs without agreement, the published focal length is kept: the
     * frames are kept until the calibration settles, so it cannot go on for ever.
     */
    static final int MAX_PAIRS = 10;

    /** Below this correlation the two pictures have too little in common to say anything. */
    static final float MIN_CORRELATION = 0.5f;

    /** How many scales the coarse search tries. */
    private static final int COARSE_STEPS = 40;

    /** How many the fine search tries, around the best coarse one. */
    private static final int FINE_STEPS = 20;

    /** The fewest overlapping samples a comparison is worth making on. */
    private static final int MIN_OVERLAP = 300;

    /** Every estimate so far. */
    private final List<Float> mEstimates = new ArrayList<>();

    /** How many pairs of frames have been compared, whether or not they gave an estimate. */
    private int mPairs;

    /** The previous frame, small and grey, or null before the first. */
    private Thumbnail mPrevious;

    /** The settled scale, or NaN while still measuring. */
    private float mScale = Float.NaN;

    /** Whether measuring has stopped, settled or not. */
    private boolean mDone;

    /**
     * Takes a frame with where it was looking, compares it with the one before, and keeps it
     * for the next.
     *
     * @param rotation the device-to-world matrix when the frame was taken
     * @param pose where the frame looked, with the published focal length scaled to its size
     */
    void offer(@NonNull ArgbImage image, @NonNull float[] rotation, @NonNull CameraPose pose) {
        if (mDone) {
            return;
        }
        Thumbnail current = new Thumbnail(image, rotation, pose);
        Thumbnail previous = mPrevious;
        if (previous != null) {
            if (SphereMath.angleBetween(previous.forward, current.forward) < MIN_TURN_DEGREES) {
                // Too close to say anything; keep the older one, which is further from the next.
                return;
            }
            mPairs++;
            float estimate = estimate(previous, current);
            if (!Float.isNaN(estimate)) {
                mEstimates.add(estimate);
            }
            settle();
        }
        mPrevious = current;
    }

    /** Whether measuring has stopped, whether or not it settled on a scale. */
    boolean isDone() {
        return mDone;
    }

    /**
     * What to multiply the published focal length by to get the lens's own.
     *
     * @return the scale, 1 when measuring gave up, or NaN while it is still going
     */
    float getScale() {
        return mScale;
    }

    private void settle() {
        if (mEstimates.isEmpty()) {
            giveUpAfterTooManyPairs();
            return;
        }
        List<Float> sorted = new ArrayList<>(mEstimates);
        Collections.sort(sorted);
        float median = sorted.get(sorted.size() / 2);
        int agreeing = 0;
        float sum = 0f;
        for (float estimate : sorted) {
            if (Math.abs(estimate / median - 1f) <= AGREEMENT) {
                agreeing++;
                sum += estimate;
            }
        }
        if (agreeing >= AGREEING_ESTIMATES) {
            mScale = sum / agreeing;
            mDone = true;
        } else {
            giveUpAfterTooManyPairs();
        }
    }

    private void giveUpAfterTooManyPairs() {
        if (mPairs >= MAX_PAIRS) {
            mScale = 1f;
            mDone = true;
        }
    }

    /**
     * The scale on the focal length that best lines one frame up with the next.
     *
     * @return the scale, or NaN when the best alignment is still a poor one
     */
    static float estimate(@NonNull Thumbnail from, @NonNull Thumbnail to) {
        float bestScale = Float.NaN;
        float bestScore = -2f;
        double logMin = Math.log(MIN_SCALE);
        double logStep = (Math.log(MAX_SCALE) - logMin) / (COARSE_STEPS - 1);
        for (int i = 0; i < COARSE_STEPS; i++) {
            float scale = (float) Math.exp(logMin + i * logStep);
            float score = correlation(from, to, scale);
            if (score > bestScore) {
                bestScore = score;
                bestScale = scale;
            }
        }
        if (Float.isNaN(bestScale)) {
            return Float.NaN;
        }
        double centre = Math.log(bestScale);
        for (int i = 0; i <= FINE_STEPS; i++) {
            float scale = (float) Math.exp(centre - logStep + 2 * logStep * i / FINE_STEPS);
            float score = correlation(from, to, scale);
            if (score > bestScore) {
                bestScore = score;
                bestScale = scale;
            }
        }
        return bestScore >= MIN_CORRELATION ? bestScale : Float.NaN;
    }

    /**
     * How alike the two frames are where they overlap, if the focal length is {@code scale}
     * times the published one: normalised cross-correlation, from -1 to 1, or -2 when they
     * barely overlap.
     */
    static float correlation(@NonNull Thumbnail from, @NonNull Thumbnail to, float scale) {
        CameraPose source = from.pose(scale);
        CameraPose target = to.pose(scale);
        float[] direction = new float[3];
        float[] point = new float[2];
        double sumA = 0;
        double sumB = 0;
        double sumAA = 0;
        double sumBB = 0;
        double sumAB = 0;
        int count = 0;
        for (int y = 1; y < from.height - 1; y += 2) {
            for (int x = 1; x < from.width - 1; x += 2) {
                source.unproject(x + 0.5f, y + 0.5f, direction);
                if (!target.project(direction, point)) {
                    continue;
                }
                float u = point[0] - 0.5f;
                float v = point[1] - 0.5f;
                if (u < 0f || v < 0f || u > to.width - 1 || v > to.height - 1) {
                    continue;
                }
                double a = from.luma[y * from.width + x];
                double b = to.sample(u, v);
                sumA += a;
                sumB += b;
                sumAA += a * a;
                sumBB += b * b;
                sumAB += a * b;
                count++;
            }
        }
        if (count < MIN_OVERLAP) {
            return -2f;
        }
        double covariance = sumAB / count - (sumA / count) * (sumB / count);
        double varianceA = sumAA / count - (sumA / count) * (sumA / count);
        double varianceB = sumBB / count - (sumB / count) * (sumB / count);
        if (varianceA <= 1e-6 || varianceB <= 1e-6) {
            return -2f;
        }
        return (float) (covariance / Math.sqrt(varianceA * varianceB));
    }

    /** A frame shrunk to a grey thumbnail, with where it looked. */
    static final class Thumbnail {
        /** Brightness, row by row. */
        final float[] luma;

        /** How many pixels across. */
        final int width;

        /** How many rows. */
        final int height;

        /** The device's rotation when the frame was taken. */
        final float[] rotation;

        /** The clockwise turn that stands the frame upright. */
        final int sensorRotation;

        /** Whether the frame came from a lens facing the user. */
        final boolean frontFacing;

        /** The published focal length, in this thumbnail's pixels. */
        final float focal;

        /** Where the frame's centre looked. */
        final float[] forward;

        Thumbnail(@NonNull ArgbImage image, @NonNull float[] rotation, @NonNull CameraPose pose) {
            int step = Math.max(1,
                    (Math.max(image.width, image.height) + THUMBNAIL_SIDE - 1) / THUMBNAIL_SIDE);
            width = image.width / step;
            height = image.height / step;
            luma = new float[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int sum = 0;
                    for (int dy = 0; dy < step; dy++) {
                        for (int dx = 0; dx < step; dx++) {
                            int argb = image.pixels[(y * step + dy) * image.width + x * step + dx];
                            sum += 2 * ArgbImage.red(argb) + 5 * ArgbImage.green(argb)
                                    + ArgbImage.blue(argb);
                        }
                    }
                    luma[y * width + x] = sum / (8f * step * step);
                }
            }
            this.rotation = rotation.clone();
            sensorRotation = pose.sensorRotation;
            frontFacing = pose.frontFacing;
            focal = pose.focal * width / image.width;
            forward = pose.forward.clone();
        }

        /** Where this thumbnail looked, for a focal length {@code scale} times the published. */
        @NonNull
        CameraPose pose(float scale) {
            return new CameraPose(rotation, sensorRotation, frontFacing, width, height,
                    focal * scale);
        }

        /** The brightness between pixel centres. */
        float sample(float x, float y) {
            int x0 = (int) x;
            int y0 = (int) y;
            int x1 = Math.min(x0 + 1, width - 1);
            int y1 = Math.min(y0 + 1, height - 1);
            float fx = x - x0;
            float fy = y - y0;
            float top = luma[y0 * width + x0] * (1 - fx) + luma[y0 * width + x1] * fx;
            float bottom = luma[y1 * width + x0] * (1 - fx) + luma[y1 * width + x1] * fx;
            return top * (1 - fy) + bottom * fy;
        }
    }
}
