package com.xlythe.view.camera;

import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The zoom arithmetic every backend shares, so that a level, a pinch and a preset mean the same
 * thing whichever camera API is underneath.
 *
 * <p>Zoom is a ratio in the camera's own terms: 1 is the main lens, below 1 is an ultra-wide the
 * camera can switch to, and above 1 is a longer lens or a crop.</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class ZoomMath {
    /** The far end of the zoom level scale. See {@link CameraView#getMaxZoomLevel}. */
    public static final int MAX_ZOOM_LEVEL = 100;

    /**
     * How far apart two stops have to be to each deserve a button, as a ratio between them.
     *
     * <p>Closer than any two lenses on one phone, and further apart than the rounding in the focal
     * lengths and sensor sizes cameras publish.</p>
     */
    private static final float DISTINCT_STOP_RATIO = 1.1f;

    /** The crop {@link #canCropTo2x} asks the main sensor about. */
    private static final float CROP_STOP = 2f;

    /** The most decimal places {@link #labels} gives, enough to part any two distinct stops. */
    public static final int MAX_LABEL_DECIMALS = 2;

    /** From here up, a label is a whole number: 2.74 reads 3, as phone camera apps show it. */
    private static final float WHOLE_LABEL_FROM = 2f;

    private ZoomMath() {}

    /**
     * Maps a zoom level onto a ratio, on a log scale.
     *
     * <p>Equal travel along a slider then multiplies the zoom by the same amount wherever it is. A
     * linear mapping over a 0.6x to 10x range would spend only the first 4% of the slider below
     * 1x.</p>
     */
    public static float ratioForLevel(int level, float minRatio, float maxRatio) {
        if (!(maxRatio > minRatio) || minRatio <= 0f) {
            return minRatio;
        }
        int clamped = Math.max(0, Math.min(level, MAX_ZOOM_LEVEL));
        if (clamped == MAX_ZOOM_LEVEL) {
            // Exactly the top, which the power below can miss by a rounding error.
            return maxRatio;
        }
        return (float) (minRatio
                * Math.pow(maxRatio / minRatio, (double) clamped / MAX_ZOOM_LEVEL));
    }

    /** The level {@link #ratioForLevel} maps onto a ratio, rounded to the nearest one. */
    public static int levelForRatio(float ratio, float minRatio, float maxRatio) {
        if (!(maxRatio > minRatio) || minRatio <= 0f || !(ratio > minRatio)) {
            return 0;
        }
        if (ratio >= maxRatio) {
            return MAX_ZOOM_LEVEL;
        }
        double fraction = Math.log(ratio / minRatio) / Math.log(maxRatio / minRatio);
        return (int) Math.round(fraction * MAX_ZOOM_LEVEL);
    }

    /** Holds a ratio inside a camera's range. */
    public static float clamp(float ratio, float minRatio, float maxRatio) {
        return Math.max(minRatio, Math.min(ratio, maxRatio));
    }

    /**
     * Where a lens sits on the zoom scale of the camera it belongs to.
     *
     * <p>Zoom is a ratio of fields of view, and a field of view follows from how long the lens is
     * relative to how wide the sensor behind it is. Focal length alone is not enough, because an
     * ultra-wide or a telephoto usually sits in front of a smaller sensor than the main lens.</p>
     *
     * @param referenceFocalLength the focal length that a ratio of 1 means
     * @param referenceSensorWidth the width of the sensor behind that focal length
     * @return the ratio, or 0 when either camera does not publish its optics
     */
    public static float lensStop(float focalLength, float sensorWidth,
            float referenceFocalLength, float referenceSensorWidth) {
        if (focalLength <= 0f || sensorWidth <= 0f
                || referenceFocalLength <= 0f || referenceSensorWidth <= 0f) {
            return 0f;
        }
        return (focalLength / sensorWidth) / (referenceFocalLength / referenceSensorWidth);
    }

    /**
     * Whether a sensor can crop to 2x and still fill the largest picture the camera takes.
     *
     * <p>A phone with a high-resolution main sensor bins it down for ordinary pictures, so a 2x
     * crop of the full sensor is as sharp as the uncropped picture. That makes 2x a stop worth
     * offering where no lens sits.</p>
     *
     * @param sensorWidth the sensor's width in pixels, at its highest resolution
     * @param largestPictureWidth the width of the largest JPEG the camera offers
     */
    public static boolean canCropTo2x(int sensorWidth, int largestPictureWidth) {
        return largestPictureWidth > 0 && sensorWidth >= CROP_STOP * largestPictureWidth;
    }

    /**
     * The stops worth a button, in increasing order.
     *
     * <p>Android publishes the lenses a camera can switch between, but not the stops its own camera
     * app offers, so this assembles them: 1x always, the wide end when it is below 1x, a stop per
     * lens, and 2x where the main sensor can crop to it and no lens is already there. Stops within
     * ten percent of one another are one stop. 1x and the wide end win over a lens that lands near
     * them, because the camera reaches those two exactly.</p>
     *
     * @param stops the other ratios the camera has a reason to stop at, usually each lens from
     *     {@link #lensStop}, where a lens that did not publish its optics gives 0 and is left out
     * @param cropStop whether the main sensor can crop to 2x, from {@link #canCropTo2x}
     */
    @NonNull
    public static float[] presets(float minRatio, float maxRatio, @NonNull float[] stops,
            boolean cropStop) {
        List<Float> presetList = new ArrayList<>();
        presetList.add(clamp(1f, minRatio, maxRatio));
        if (minRatio < 1f) {
            addDistinct(presetList, minRatio);
        }

        float[] sorted = stops.clone();
        Arrays.sort(sorted);
        boolean stopAtCrop = false;
        for (float stop : sorted) {
            if (stop <= 0f) {
                continue;
            }
            stopAtCrop |= isSameStop(stop, CROP_STOP);
            addDistinct(presetList, clamp(stop, minRatio, maxRatio));
        }
        if (cropStop && !stopAtCrop) {
            addDistinct(presetList, clamp(CROP_STOP, minRatio, maxRatio));
        }

        float[] presets = new float[presetList.size()];
        for (int i = 0; i < presets.length; i++) {
            presets[i] = presetList.get(i);
        }
        Arrays.sort(presets);
        return presets;
    }

    /**
     * Rounds each ratio for a button's label: to a whole number from 2 up, so that a 2.74x lens
     * reads 3, and to one decimal place below that, so that an ultra-wide reads 0.6.
     *
     * <p>Where two ratios would round to the same label, both get another decimal place, up to
     * {@link #MAX_LABEL_DECIMALS}; a 2x crop beside a 2.3x lens reads 2 and 2.3 rather than 2 and
     * 2.</p>
     */
    @NonNull
    public static float[] labels(@NonNull float[] ratios) {
        int[] decimals = new int[ratios.length];
        for (int i = 0; i < ratios.length; i++) {
            decimals[i] = ratios[i] >= WHOLE_LABEL_FROM ? 0 : 1;
        }
        float[] labels = new float[ratios.length];
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < ratios.length; i++) {
                labels[i] = round(ratios[i], decimals[i]);
            }
            for (int i = 0; i < ratios.length; i++) {
                for (int j = i + 1; j < ratios.length; j++) {
                    if (labels[i] != labels[j]) {
                        continue;
                    }
                    if (decimals[i] < MAX_LABEL_DECIMALS) {
                        decimals[i]++;
                        changed = true;
                    }
                    if (decimals[j] < MAX_LABEL_DECIMALS) {
                        decimals[j]++;
                        changed = true;
                    }
                }
            }
        }
        return labels;
    }

    /** Rounds half up to the given number of decimal places. */
    private static float round(float value, int decimals) {
        double scale = Math.pow(10, decimals);
        return (float) (Math.floor(value * scale + 0.5) / scale);
    }

    /** Adds a stop unless one already there is close enough to stand for it. */
    private static void addDistinct(@NonNull List<Float> stops, float stop) {
        for (float existing : stops) {
            if (isSameStop(existing, stop)) {
                return;
            }
        }
        stops.add(stop);
    }

    private static boolean isSameStop(float a, float b) {
        return Math.max(a, b) < DISTINCT_STOP_RATIO * Math.min(a, b);
    }
}
