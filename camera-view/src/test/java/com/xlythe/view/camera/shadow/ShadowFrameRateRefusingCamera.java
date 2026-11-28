package com.xlythe.view.camera.shadow;

import android.hardware.Camera;

import androidx.annotation.Nullable;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowCamera;

import java.util.Objects;

/**
 * A camera that will not change its preview frame rate range once it has taken one, though it
 * lists others, as some old cameras will not. Parameters that also change the list, as a test
 * sets up a camera, are taken.
 */
@Implements(Camera.class)
public class ShadowFrameRateRefusingCamera extends ShadowCamera {
    private static final String RANGE = "preview-fps-range";
    private static final String RANGES = "preview-fps-range-values";

    /** The range this camera took, or null before it took any parameters. */
    @Nullable
    private String mRange;

    /** The ranges it listed when it took them. */
    @Nullable
    private String mRanges;

    @Implementation
    @Override
    protected void setParameters(Camera.Parameters parameters) {
        String range = parameters.get(RANGE);
        String ranges = parameters.get(RANGES);
        if (mRange != null && Objects.equals(ranges, mRanges) && !mRange.equals(range)) {
            // The camera hands out its own parameters rather than a copy, so the refused change
            // is taken back out of them.
            parameters.set(RANGE, mRange);
            throw new RuntimeException("setParameters failed");
        }
        mRange = range;
        mRanges = ranges;
        super.setParameters(parameters);
    }
}
