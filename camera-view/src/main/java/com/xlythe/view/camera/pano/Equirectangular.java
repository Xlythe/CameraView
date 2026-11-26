package com.xlythe.view.camera.pano;

import android.graphics.PointF;

import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;

/**
 * Where a camera frame lands on an equirectangular canvas, given where the camera was pointed.
 *
 * <p>Equirectangular because it is the one projection everything else understands: longitude
 * across, latitude down, two pixels wide for every one tall. It is what Google Photos, Street
 * View, every web viewer and the GPano metadata all assume, so a file in this shape is a
 * panorama to other software rather than a wide picture.</p>
 *
 * <h2>How frames are placed</h2>
 *
 * <p>By where the camera was pointed, not by matching what the frames contain. A phone knows its
 * own rotation - from the rotation vector, or far more precisely from a tracked AR session - and
 * that is enough to say where every pixel of a frame belongs on the sphere. It costs the thing
 * feature matching buys: parallax. Two frames taken from slightly different positions disagree
 * about anything close to the camera, and no rotation can reconcile them, so near objects show a
 * seam. Turning on the spot rather than walking is the whole technique, and it is why the sample
 * tells the person to.</p>
 *
 * <p>Everything here is arithmetic on angles, which is the half of panorama capture that can be
 * tested without a camera, a GPU or a room.</p>

 * <h2>Not part of the API</h2>
 *
 * <p>The projection a sweep is painted with. Restricted to the library: an app is handed
 * positions it can draw at - through WorldOverlay for a marker, or GPanoBounds for how much
 * of the sphere a file covers - and needs none of the arithmetic behind them.</p>
 *
 * @hide
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class Equirectangular {
    /** A full turn, which is the width of the canvas in degrees. */
    public static final float FULL_TURN_DEGREES = 360f;

    /** Pole to pole, which is its height in degrees. */
    public static final float HALF_TURN_DEGREES = 180f;

    private Equirectangular() {}

    /**
     * Where a direction lands on the canvas, in pixels.
     *
     * <p>Yaw runs east across the canvas from -180 at the left edge; pitch runs from +90 at the
     * top down to -90 at the bottom, because a canvas counts downwards and the sky is up.</p>
     *
     * @param yawDegrees east of the canvas centre, -180 to 180
     * @param pitchDegrees above the horizon, -90 to 90
     * @param out set to the position on the canvas
     */
    public static void project(float yawDegrees, float pitchDegrees,
            int canvasWidth, int canvasHeight, @NonNull PointF out) {
        out.set(
                canvasWidth * (wrapYaw(yawDegrees) + HALF_TURN_DEGREES) / FULL_TURN_DEGREES,
                canvasHeight * (90f - clampPitch(pitchDegrees)) / HALF_TURN_DEGREES);
    }

    /**
     * Where a direction lands, without bringing it back onto the canvas first.
     *
     * <p>For drawing a frame as a mesh, where every vertex has to stay next to its neighbours.
     * Wrapping each one independently is what tears a mesh that straddles the cut in the
     * cylinder: two vertices a few degrees apart end up at opposite ends of the canvas, and the
     * quad between them is stretched across the whole picture. Projecting unwrapped puts them a
     * few pixels apart with both outside the canvas, which is a shape that can be drawn and
     * then shifted into place.</p>
     *
     * @param yawDegrees east of the canvas centre, in any range
     */
    public static void projectUnwrapped(float yawDegrees, float pitchDegrees,
            int canvasWidth, int canvasHeight, @NonNull PointF out) {
        out.set(
                canvasWidth * (yawDegrees + HALF_TURN_DEGREES) / FULL_TURN_DEGREES,
                canvasHeight * (90f - clampPitch(pitchDegrees)) / HALF_TURN_DEGREES);
    }

    /**
     * The same direction as a yaw, expressed within half a turn of a reference.
     *
     * <p>What keeps a mesh together across the seam: every vertex of a frame is within half a
     * turn of its centre, so measuring them all from the centre leaves them in one piece
     * wherever the frame is pointed.</p>
     */
    public static float unwrapYawNear(float referenceDegrees, float yawDegrees) {
        return referenceDegrees + wrapYaw(yawDegrees - referenceDegrees);
    }

    /**
     * Which way a point in the frame is pointing, once the camera's rotation is applied.
     *
     * <p>The frame is treated as a flat window a unit away from the camera, which is what a
     * lens without heavy distortion is: a point moves across the window as the tangent of its
     * angle, not in proportion to it. Ignoring that - placing frames by scaling their angle -
     * is what stretches the edges of a sweep and leaves the horizon bowed.</p>
     *
     * @param frameU across the frame, 0 at the left edge and 1 at the right
     * @param frameV down the frame, 0 at the top and 1 at the bottom
     * @param rotationMatrix the camera's rotation, 16 floats column-major, as
     *     {@link android.hardware.SensorManager#getRotationMatrixFromVector} or an AR session
     *     gives it: it takes a direction in the camera's own frame to one in the world
     * @param horizontalFieldOfViewDegrees the camera's angle of view across
     * @param verticalFieldOfViewDegrees its angle of view down
     * @param out set to {yaw, pitch} in degrees
     * @return whether the direction could be worked out, which it cannot for an angle of view of
     *     zero or at or past a half turn
     */
    public static boolean directionFor(float frameU, float frameV,
            @NonNull float[] rotationMatrix,
            float horizontalFieldOfViewDegrees, float verticalFieldOfViewDegrees,
            @NonNull float[] out) {
        if (rotationMatrix.length < 16 || out.length < 2
                || !isUsableFieldOfView(horizontalFieldOfViewDegrees)
                || !isUsableFieldOfView(verticalFieldOfViewDegrees)) {
            return false;
        }
        // The point on the window, in the camera's own frame. The camera looks down its negative
        // z, x is to the right and y is up, which is the convention both the rotation vector and
        // every AR session use.
        double x = (2 * frameU - 1) * Math.tan(Math.toRadians(horizontalFieldOfViewDegrees / 2));
        double y = (1 - 2 * frameV) * Math.tan(Math.toRadians(verticalFieldOfViewDegrees / 2));
        double z = -1;

        double worldX = rotationMatrix[0] * x + rotationMatrix[4] * y + rotationMatrix[8] * z;
        double worldY = rotationMatrix[1] * x + rotationMatrix[5] * y + rotationMatrix[9] * z;
        double worldZ = rotationMatrix[2] * x + rotationMatrix[6] * y + rotationMatrix[10] * z;

        double length = Math.sqrt(worldX * worldX + worldY * worldY + worldZ * worldZ);
        if (length == 0) {
            return false;
        }
        // Yaw from the horizontal part of the direction, measured from straight ahead - which is
        // the world's negative z, the direction a device at rest with the screen up is pointed.
        out[0] = (float) Math.toDegrees(Math.atan2(worldX, -worldZ));
        out[1] = (float) Math.toDegrees(Math.asin(worldY / length));
        return true;
    }

    /**
     * Where a point in the frame lands on the canvas, which is the two steps above together.
     *
     * <p>The call a renderer makes per vertex of the mesh it warps a frame onto.</p>
     *
     * @return whether it landed anywhere, on the same terms as {@link #directionFor}
     */
    public static boolean projectFramepoint(float frameU, float frameV,
            @NonNull float[] rotationMatrix,
            float horizontalFieldOfViewDegrees, float verticalFieldOfViewDegrees,
            int canvasWidth, int canvasHeight, @NonNull PointF out) {
        float[] direction = new float[2];
        if (!directionFor(frameU, frameV, rotationMatrix,
                horizontalFieldOfViewDegrees, verticalFieldOfViewDegrees, direction)) {
            return false;
        }
        project(direction[0], direction[1], canvasWidth, canvasHeight, out);
        return true;
    }

    /**
     * The canvas height that matches a width, which is always half of it.
     *
     * <p>Here so that nothing has to remember it. An equirectangular image that is not two to
     * one is not equirectangular, and a viewer handed one stretches the result rather than
     * reporting it.</p>
     */
    public static int canvasHeightFor(int canvasWidth) {
        return canvasWidth / 2;
    }

    /**
     * A yaw brought into the half-open range the canvas counts in: -180 up to, but not, 180.
     *
     * <p>Needed because yaws are added and subtracted - the difference between two headings, a
     * sweep crossing north - and a yaw that has wandered outside the range lands off the canvas
     * rather than at the other edge of it.</p>
     *
     * <p>Half-open, and that is the part that matters. The two edges of the canvas are the same
     * meridian, so a yaw of 180 and one of -180 are the same direction; mapping either to +180
     * would put it at an x of exactly the canvas width, which is one pixel past the last
     * column. Everything here lands inside the canvas instead.</p>
     */
    public static float wrapYaw(float yawDegrees) {
        float wrapped = yawDegrees % FULL_TURN_DEGREES;
        if (wrapped >= HALF_TURN_DEGREES) {
            wrapped -= FULL_TURN_DEGREES;
        } else if (wrapped < -HALF_TURN_DEGREES) {
            wrapped += FULL_TURN_DEGREES;
        }
        return wrapped;
    }

    /**
     * How far apart two yaws are, the short way round.
     *
     * <p>Always at most half a turn, so that a sweep from 170 to -170 is twenty degrees rather
     * than three hundred and forty.</p>
     */
    public static float yawDistance(float fromDegrees, float toDegrees) {
        return Math.abs(wrapYaw(toDegrees - fromDegrees));
    }

    /** A pitch held to the poles, since there is no canvas beyond them. */
    public static float clampPitch(float pitchDegrees) {
        return Math.max(-90f, Math.min(90f, pitchDegrees));
    }

    private static boolean isUsableFieldOfView(float degrees) {
        return degrees > 0f && degrees < HALF_TURN_DEGREES;
    }
}
