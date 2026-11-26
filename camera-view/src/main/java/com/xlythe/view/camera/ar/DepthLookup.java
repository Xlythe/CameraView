package com.xlythe.view.camera.ar;

import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;

import com.xlythe.view.camera.WorldOverlay;
import com.xlythe.view.camera.gl.Sampling;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Reads metres out of an ARCore depth image, for deciding what is in front of what.
 *
 * <p>This is the half of depth that can be tested. ARCore hands back a {@code Frame} that cannot
 * be constructed off a device, but what comes out of it is an ordinary little-endian buffer of
 * 16-bit millimetres, and every way of getting that wrong - the stride, the row order, the
 * scaling, the sentinel for "no reading" - lives here rather than in the part that needs a
 * camera.</p>
 *
 * <h2>What the buffer is</h2>
 *
 * <p>One plane, {@code HardwareBuffer.D_16}, little-endian, each sample the distance in
 * millimetres to the <em>camera plane</em> rather than along the ray. Typically 160x120 and up
 * to 640x480, which is far smaller than the viewfinder, so a lookup is a sample rather than a
 * pixel. Zero means no reading, and is returned as such rather than as "touching the lens" -
 * confusing the two is how an occlusion test hides everything.</p>
 *
 * <p>Where the camera image and the depth image have different aspect ratios the depth image is
 * a crop of the camera image, so some of the viewfinder has no depth at all. That is reported as
 * no reading too.</p>

 * <h2>Not part of the API</h2>
 *
 * <p>Reading metres out of a depth image. Restricted to the library: an app is handed it as a
 * WorldOverlay.SceneDepth, which is all an occlusion test takes.</p>
 *
 * @hide
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class DepthLookup implements WorldOverlay.SceneDepth {
    /** ARCore reports millimetres; the overlay works in metres. */
    private static final float MILLIMETRES_PER_METRE = 1000f;

    private final ByteBuffer mDepth;
    private final int mWidth;
    private final int mHeight;
    private final int mRowStride;
    private final int mViewWidth;
    private final int mViewHeight;

    /**
     * @param depth the depth plane, 16-bit little-endian millimetres
     * @param width the depth image's width in samples
     * @param height its height in samples
     * @param rowStride bytes per row, which is not always {@code width * 2}
     * @param viewWidth the width of the view the lookup is in terms of
     * @param viewHeight its height
     */
    public DepthLookup(@NonNull ByteBuffer depth, int width, int height, int rowStride,
            int viewWidth, int viewHeight) {
        mDepth = depth.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        mWidth = width;
        mHeight = height;
        mRowStride = rowStride;
        mViewWidth = viewWidth;
        mViewHeight = viewHeight;
    }

    @Override
    public float metersAt(float viewX, float viewY) {
        if (mViewWidth <= 0 || mViewHeight <= 0 || mWidth <= 0 || mHeight <= 0) {
            return 0;
        }
        int sampleX = sampleFor(viewX, mViewWidth, mWidth);
        int sampleY = sampleFor(viewY, mViewHeight, mHeight);
        if (sampleX < 0 || sampleY < 0) {
            return 0;
        }
        int at = sampleY * mRowStride + sampleX * 2;
        if (at < 0 || at + 2 > mDepth.capacity()) {
            return 0;
        }
        int millimetres = mDepth.getShort(at) & 0xFFFF;
        // Zero is ARCore's "nothing here", not "against the lens".
        return millimetres == 0 ? 0 : millimetres / MILLIMETRES_PER_METRE;
    }

    /**
     * Which sample a point in the view falls on, or -1 when it falls outside.
     *
     * <p>A segmentation mask needs the same mapping onto a grid coarser than the view, so the
     * arithmetic lives in one place and this defers to it.</p>
     */
    @VisibleForTesting
    static int sampleFor(float position, int viewExtent, int sampleExtent) {
        return Sampling.sampleFor(position, viewExtent, sampleExtent);
    }
}
