package com.xlythe.view.camera.ar;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.xlythe.view.camera.WorldOverlay;

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
 */
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
     * <p>Rounded down rather than nearest, so that the sample covering a point is the one
     * returned at every point it covers; nearest would make the two edges of a sample disagree
     * about which sample they are in.</p>
     */
    @VisibleForTesting
    static int sampleFor(float position, int viewExtent, int sampleExtent) {
        if (position < 0 || position >= viewExtent) {
            return -1;
        }
        int sample = (int) (position / viewExtent * sampleExtent);
        // A point exactly on the far edge rounds into a sample that is not there.
        return sample >= sampleExtent ? sampleExtent - 1 : sample;
    }
}
