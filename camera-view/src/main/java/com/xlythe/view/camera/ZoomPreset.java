package com.xlythe.view.camera;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A zoom stop worth a button: the exact ratio to zoom to, and a rounded label to show for it.
 *
 * <p>A lens rarely lands on a round number. A telephoto at 2.74x is labelled "3", the way phone
 * camera apps show it, while {@link #getRatio} keeps 2.74 so that the zoom lands on the lens
 * itself rather than on a crop near it.</p>
 */
public final class ZoomPreset {
    /** The ratio to hand to {@link CameraView#setZoomRatio}. */
    private final float mRatio;
    /** {@link #mRatio} rounded and formatted for showing, with no unit. */
    @NonNull private final String mLabel;

    ZoomPreset(float ratio, @NonNull String label) {
        mRatio = ratio;
        mLabel = label;
    }

    /** Labels each ratio, in the order given, formatted for the locale. */
    @NonNull
    static List<ZoomPreset> of(@NonNull float[] ratios, @NonNull Locale locale) {
        float[] labels = ZoomMath.labels(ratios);
        NumberFormat format = NumberFormat.getNumberInstance(locale);
        format.setMinimumFractionDigits(0);
        format.setMaximumFractionDigits(ZoomMath.MAX_LABEL_DECIMALS);
        List<ZoomPreset> presets = new ArrayList<>(ratios.length);
        for (int i = 0; i < ratios.length; i++) {
            presets.add(new ZoomPreset(ratios[i], format.format(labels[i])));
        }
        return Collections.unmodifiableList(presets);
    }

    /** The exact ratio to zoom to, such as 2.74 for a telephoto lens. */
    public float getRatio() {
        return mRatio;
    }

    /**
     * The ratio rounded for showing, such as "3" for a 2.74x lens or "0.6" for an ultra-wide.
     *
     * <p>Ratios of 2 and above round to a whole number and those below to one decimal place, with
     * more decimals only where two presets would otherwise share a label. It is formatted for the
     * default locale and carries no unit, so add the "x" or "×" the design calls for.</p>
     */
    @NonNull
    public String getLabel() {
        return mLabel;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ZoomPreset)) {
            return false;
        }
        ZoomPreset other = (ZoomPreset) o;
        return Float.compare(mRatio, other.mRatio) == 0 && mLabel.equals(other.mLabel);
    }

    @Override
    public int hashCode() {
        return 31 * Float.floatToIntBits(mRatio) + mLabel.hashCode();
    }

    @NonNull
    @Override
    public String toString() {
        return "ZoomPreset{ratio=" + mRatio + ", label=" + mLabel + "}";
    }
}
