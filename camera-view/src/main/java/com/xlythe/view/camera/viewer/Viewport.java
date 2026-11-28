package com.xlythe.view.camera.viewer;

import androidx.annotation.NonNull;

/**
 * Where an upright picture sits in a view: how far it is zoomed and how far it is panned, kept
 * within what makes sense for the picture.
 *
 * <p>A picture smaller than the view on one axis is centred on it; a larger one cannot be panned
 * past its edges. A picture that wraps, a panorama that goes all the way round, has no edges
 * across: it repeats, and the offset is kept within one repeat so it never runs away.</p>
 */
final class Viewport {
    /**
     * The most a picture is zoomed beyond its smallest scale, unless that is still less than
     * {@link #MAX_PIXEL_SCALE}.
     */
    static final float MAX_ZOOM = 4f;

    /** How many screen pixels a picture's pixel may be stretched over at most. */
    static final float MAX_PIXEL_SCALE = 2f;

    /** The view's width, in pixels. */
    private int mViewWidth;

    /** The view's height. */
    private int mViewHeight;

    /** The upright picture's width, in its pixels. */
    private int mImageWidth;

    /** The upright picture's height. */
    private int mImageHeight;

    /** Whether the picture repeats across, its right edge meeting its left. */
    private boolean mWraps;

    /** The smallest scale, at which the picture fits the view the way the viewer wants. */
    private float mMinScale = 1f;

    /** The largest scale. */
    private float mMaxScale = MAX_PIXEL_SCALE;

    /** View pixels per picture pixel. */
    private float mScale = 1f;

    /** Where the picture's left edge is in the view, for the repeat at or left of the origin. */
    private float mX;

    /** Where its top edge is. */
    private float mY;

    void setViewSize(int width, int height) {
        mViewWidth = width;
        mViewHeight = height;
    }

    void setImage(int width, int height, boolean wraps) {
        mImageWidth = width;
        mImageHeight = height;
        mWraps = wraps;
    }

    /** Whether both the view and the picture have a size, without which nothing is placed. */
    boolean isReady() {
        return mViewWidth > 0 && mViewHeight > 0 && mImageWidth > 0 && mImageHeight > 0;
    }

    /** The scale at which the whole picture fits inside the view. */
    float fitScale() {
        return Math.min((float) mViewWidth / mImageWidth, (float) mViewHeight / mImageHeight);
    }

    /** The scale at which the picture fills the view from top to bottom. */
    float fillHeightScale() {
        return (float) mViewHeight / mImageHeight;
    }

    /**
     * Zooms all the way out and centres on a point.
     *
     * @param minScale the smallest scale the picture may have from now on
     * @param centerX the picture's pixel to put in the middle of the view, across
     * @param centerY and down
     */
    void reset(float minScale, float centerX, float centerY) {
        mMinScale = minScale;
        mMaxScale = Math.max(minScale * MAX_ZOOM, MAX_PIXEL_SCALE);
        mScale = minScale;
        mX = mViewWidth / 2f - centerX * mScale;
        mY = mViewHeight / 2f - centerY * mScale;
        clamp();
    }

    float getScale() {
        return mScale;
    }

    float getMinScale() {
        return mMinScale;
    }

    float getMaxScale() {
        return mMaxScale;
    }

    float getX() {
        return mX;
    }

    float getY() {
        return mY;
    }

    boolean wraps() {
        return mWraps;
    }

    /** Zooms by a factor, keeping the picture's pixel under a point of the view where it is. */
    void scaleBy(float factor, float focusX, float focusY) {
        scaleTo(mScale * factor, focusX, focusY);
    }

    /** Zooms to a scale, within the limits, keeping the pixel under a point where it is. */
    void scaleTo(float scale, float focusX, float focusY) {
        float clamped = Math.max(mMinScale, Math.min(mMaxScale, scale));
        float ratio = clamped / mScale;
        mX = focusX - (focusX - mX) * ratio;
        mY = focusY - (focusY - mY) * ratio;
        mScale = clamped;
        clamp();
    }

    /** Moves the picture by some view pixels. */
    void panBy(float dx, float dy) {
        mX += dx;
        mY += dy;
        clamp();
    }

    /** Puts the picture's left and top edges at a place in the view, within the limits. */
    void moveTo(float x, float y) {
        mX = x;
        mY = y;
        clamp();
    }

    /** The picture's width at the current scale, which is how far apart its repeats are. */
    float scaledWidth() {
        return mImageWidth * mScale;
    }

    float scaledHeight() {
        return mImageHeight * mScale;
    }

    /**
     * The last repeat that shows, counting the one at the origin as 0. The origin's is always the
     * first, since the offset is kept within one repeat of the left edge.
     */
    int lastCopy() {
        if (!mWraps) {
            return 0;
        }
        return (int) Math.floor((mViewWidth - mX) / scaledWidth() - 1e-6);
    }

    /**
     * The part of the upright picture a repeat shows, cut to the picture.
     *
     * @return {left, top, right, bottom} in the picture's pixels, empty if none of it shows
     */
    @NonNull
    double[] visible(int copy) {
        double originX = mX + copy * (double) scaledWidth();
        double left = Math.max(0, -originX / mScale);
        double top = Math.max(0, -mY / mScale);
        double right = Math.min(mImageWidth, (mViewWidth - originX) / mScale);
        double bottom = Math.min(mImageHeight, (mViewHeight - mY) / mScale);
        return new double[] {left, top, Math.max(left, right), Math.max(top, bottom)};
    }

    /** Whether there is anywhere to pan, which is when a drag belongs to this view. */
    boolean canPan() {
        return mWraps || scaledWidth() > mViewWidth + 0.5f || scaledHeight() > mViewHeight + 0.5f;
    }

    /** How far the picture can be panned across, from its left limit. 0 if it cannot. */
    float panRangeX() {
        return Math.max(0, scaledWidth() - mViewWidth);
    }

    /** How far it can be panned down. */
    float panRangeY() {
        return Math.max(0, scaledHeight() - mViewHeight);
    }

    private void clamp() {
        if (!isReady()) {
            return;
        }
        float width = scaledWidth();
        if (mWraps) {
            // Kept within one repeat: whichever repeat is under the left edge is the origin.
            mX = (float) (mX - Math.floor(mX / width) * width);
            if (mX > 0) {
                mX -= width;
            }
        } else {
            mX = clampAxis(mX, width, mViewWidth);
        }
        mY = clampAxis(mY, scaledHeight(), mViewHeight);
    }

    /** Centres a picture shorter than the view, and keeps a longer one covering it. */
    private static float clampAxis(float position, float size, int viewSize) {
        if (size <= viewSize) {
            return (viewSize - size) / 2f;
        }
        return Math.max(viewSize - size, Math.min(0, position));
    }
}
