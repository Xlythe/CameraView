package com.xlythe.view.camera.gl;

import androidx.annotation.RestrictTo;

/**
 * Mapping a point in the view onto a grid that is coarser than the view.
 *
 * <p>Depth images and segmentation masks are both far smaller than the viewfinder they describe
 * - a depth image is typically 160x120 and a mask a few hundred pixels across - so looking
 * something up in either is a sample rather than a pixel. The arithmetic is the same for both
 * and is easy to get subtly wrong at the edges, so it lives in one place.</p>
 *
 * @hide
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class Sampling {
    private Sampling() {}

    /**
     * Which sample a point in the view falls on, or -1 when it falls outside.
     *
     * <p>Rounded down rather than to nearest, so the sample covering a point is the one returned
     * at every point it covers; nearest would make the two halves of a sample disagree about
     * which sample they are in. A point exactly on the far edge would round into a sample that
     * is not there, and is pulled back to the last one.</p>
     */
    public static int sampleFor(float position, int viewExtent, int sampleExtent) {
        if (viewExtent <= 0 || sampleExtent <= 0 || position < 0 || position >= viewExtent) {
            return -1;
        }
        int sample = (int) (position / viewExtent * sampleExtent);
        return sample >= sampleExtent ? sampleExtent - 1 : sample;
    }
}
