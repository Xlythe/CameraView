package com.xlythe.view.camera.ar;

import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;

import com.xlythe.view.camera.gl.GlMath;

/**
 * The arithmetic that turns what ARCore reports about a frame into what the renderer draws with,
 * with no ARCore in it.
 *
 * <p>ARCore describes its camera texture by transforming points between coordinate systems rather
 * than as a matrix, its pose as camera-to-world where a renderer wants world-to-camera, and its
 * intrinsics for the image it tracks with rather than the texture it is drawn from. Each of those
 * conversions is a place for a frame to come out mirrored or a marker to drift off its anchor,
 * and none of them needs a device to check, so they are pure functions here.</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class ArMath {
    /** Floats in a texture's extent: left, top, right and bottom. */
    public static final int BOUNDS_SIZE = 4;

    /** Floats in a ray: an origin and a direction, three each. */
    public static final int RAY_SIZE = 6;

    /** How small an extent may be before it is taken to describe no texture at all. */
    private static final float EPSILON = 1e-6f;

    private ArMath() {}

    /**
     * The part of the camera image a texture shows, from where three of its corners land.
     *
     * @param corners where the texture's (0, 0), (1, 0) and (0, 1) land in the image, in image
     *     coordinates normalized to its size and counted down from its top
     * @return {left, top, right, bottom}, in the same coordinates
     * @throws IllegalArgumentException if the corners are not six floats, or show no area
     */
    @NonNull
    public static float[] textureBounds(@NonNull float[] corners) {
        checkCorners(corners);
        float originX = corners[0];
        float originY = corners[1];
        float farX = corners[2] + corners[4] - originX;
        float farY = corners[3] + corners[5] - originY;
        float[] bounds = {
                Math.min(Math.min(originX, corners[2]), Math.min(corners[4], farX)),
                Math.min(Math.min(originY, corners[3]), Math.min(corners[5], farY)),
                Math.max(Math.max(originX, corners[2]), Math.max(corners[4], farX)),
                Math.max(Math.max(originY, corners[3]), Math.max(corners[5], farY)),
        };
        if (!(bounds[2] - bounds[0] > EPSILON) || !(bounds[3] - bounds[1] > EPSILON)) {
            throw new IllegalArgumentException("A texture that covers no area shows nothing");
        }
        return bounds;
    }

    /**
     * Where the renderer's buffer coordinates read the texture.
     *
     * <p>The buffer is the part of the image the texture shows, held the way the image is read
     * out - which is the way ARCore's camera pose is held - and counted up from the bottom as GL
     * counts texture coordinates. The texture may hold it flipped, and this is what says so, so a
     * frame drawn through it is never mirrored by mistake.</p>
     *
     * @param corners as {@link #textureBounds} takes them
     */
    @NonNull
    public static float[] bufferToTexture(@NonNull float[] corners) {
        float[] bounds = textureBounds(corners);
        float[] textureToImage = GlMath.identity();
        textureToImage[0] = corners[2] - corners[0];
        textureToImage[1] = corners[3] - corners[1];
        textureToImage[4] = corners[4] - corners[0];
        textureToImage[5] = corners[5] - corners[1];
        textureToImage[12] = corners[0];
        textureToImage[13] = corners[1];
        float[] bufferToImage = GlMath.identity();
        bufferToImage[0] = bounds[2] - bounds[0];
        // Up the buffer is up the image, which is towards its first row.
        bufferToImage[5] = -(bounds[3] - bounds[1]);
        bufferToImage[12] = bounds[0];
        bufferToImage[13] = bounds[3];
        return GlMath.multiply(GlMath.invertAffine(textureToImage), bufferToImage);
    }

    /**
     * The intrinsics of the buffer, as {@link GlMath#projection} takes them, from those of the
     * camera image and the part of it the texture shows.
     *
     * @param focalX the focal length across the image, in its pixels
     * @param focalY the focal length down the image, in its pixels
     * @param principalX where the optical axis meets the image, in pixels from its left
     * @param principalY where the optical axis meets the image, in pixels from its top
     * @param bounds from {@link #textureBounds}
     * @return {fx/w, fy/h, cx/w, cy/h, skew/w} of the buffer
     * @throws IllegalArgumentException for an image with no size or a lens with no focal length
     */
    @NonNull
    public static float[] bufferIntrinsics(float focalX, float focalY, float principalX,
            float principalY, int imageWidth, int imageHeight, @NonNull float[] bounds) {
        if (imageWidth <= 0 || imageHeight <= 0 || !(focalX > 0f) || !(focalY > 0f)) {
            throw new IllegalArgumentException("A camera image has a size and a focal length");
        }
        if (bounds.length != BOUNDS_SIZE) {
            throw new IllegalArgumentException(
                    "An extent is " + BOUNDS_SIZE + " floats, not " + bounds.length);
        }
        float width = bounds[2] - bounds[0];
        float height = bounds[3] - bounds[1];
        return new float[] {
                focalX / imageWidth / width,
                focalY / imageHeight / height,
                (principalX / imageWidth - bounds[0]) / width,
                (principalY / imageHeight - bounds[1]) / height,
                0f,
        };
    }

    /**
     * The inverse of a matrix that only turns and moves, which takes a camera's pose in the world
     * to the view matrix that brings the world into the camera's space.
     *
     * @throws IllegalArgumentException if the matrix is not sixteen floats
     */
    @NonNull
    public static float[] invertRigid(@NonNull float[] pose) {
        if (pose.length != GlMath.MATRIX_SIZE) {
            throw new IllegalArgumentException(
                    "A matrix is " + GlMath.MATRIX_SIZE + " floats, not " + pose.length);
        }
        float[] inverse = GlMath.identity();
        for (int row = 0; row < 3; row++) {
            float offset = 0f;
            for (int column = 0; column < 3; column++) {
                // The turn's inverse is its transpose.
                float value = pose[row * 4 + column];
                inverse[column * 4 + row] = value;
                offset -= value * pose[12 + column];
            }
            inverse[12 + row] = offset;
        }
        return inverse;
    }

    /**
     * The ray in the world through a point of the buffer, as a hit test casts it.
     *
     * @param intrinsics the buffer's, as {@link GlMath#projection} takes them
     * @param pose the camera's pose in the world, camera-to-world, column-major
     * @param s across the buffer from its left, from 0 to 1
     * @param t up the buffer from its bottom, from 0 to 1
     * @return {origin x, y, z, direction x, y, z}, with a direction of no particular length
     */
    @NonNull
    public static float[] worldRay(@NonNull float[] intrinsics, @NonNull float[] pose, float s,
            float t) {
        if (pose.length != GlMath.MATRIX_SIZE) {
            throw new IllegalArgumentException(
                    "A matrix is " + GlMath.MATRIX_SIZE + " floats, not " + pose.length);
        }
        float[] direction = GlMath.bufferRay(intrinsics, s, t);
        float[] ray = new float[RAY_SIZE];
        for (int row = 0; row < 3; row++) {
            ray[row] = pose[12 + row];
            ray[3 + row] = pose[row] * direction[0] + pose[4 + row] * direction[1]
                    + pose[8 + row] * direction[2];
        }
        return ray;
    }

    /**
     * Where a point on the preview reads the buffer.
     *
     * <p>The preview shows the frame stretched over the whole view and then moved by the view's
     * transform, and the frame is the buffer as the camera asks for it to be shown. So the point
     * is taken back through the transform, out of the view's pixels, and through the request.</p>
     *
     * @param viewToContent the inverse of the view's transform, as the nine values of an
     *     {@link android.graphics.Matrix}
     * @param request from {@link GlMath#cameraRequest}, which reads the buffer from the frame
     * @param x in view pixels from the left
     * @param y in view pixels from the top
     * @return {s, t} in the buffer, counted up from the bottom, which is outside 0 to 1 for a
     *     point the buffer does not reach
     * @throws IllegalArgumentException for a view with no size
     */
    @NonNull
    public static float[] viewToBuffer(@NonNull float[] viewToContent, int viewWidth,
            int viewHeight, @NonNull float[] request, float x, float y) {
        if (viewWidth <= 0 || viewHeight <= 0 || viewToContent.length != 9) {
            throw new IllegalArgumentException("A view has a size and a transform");
        }
        float w = viewToContent[6] * x + viewToContent[7] * y + viewToContent[8];
        float contentX = (viewToContent[0] * x + viewToContent[1] * y + viewToContent[2]) / w;
        float contentY = (viewToContent[3] * x + viewToContent[4] * y + viewToContent[5]) / w;
        float u = contentX / viewWidth;
        float v = 1f - contentY / viewHeight;
        return new float[] {
                request[0] * u + request[4] * v + request[12],
                request[1] * u + request[5] * v + request[13],
        };
    }

    private static void checkCorners(@NonNull float[] corners) {
        if (corners.length != 6) {
            throw new IllegalArgumentException(
                    "Three corners are six floats, not " + corners.length);
        }
    }
}
