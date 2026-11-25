package com.xlythe.view.camera;

import android.graphics.PointF;
import android.opengl.Matrix;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Puts things in the world onto the viewfinder showing it.
 *
 * <p>Two ways of saying where something is, because there are two ways of knowing. A direction -
 * "the keys are 30 degrees to my right and a little below" - is all a compass and a location can
 * give, and it needs nothing but the camera's angle of view. A point in metres is what a tracked
 * session gives, and it needs that session's own view and projection matrices.</p>
 *
 * <p>Both come out as a position in the view, ready to draw at. Neither draws anything: what the
 * marker looks like is the app's business, and a library that decided would be in the way.</p>
 *
 * @see CameraView#getHorizontalFieldOfView()
 */
public final class WorldOverlay {
    private WorldOverlay() {}

    /**
     * Where a compass bearing lands across the viewfinder.
     *
     * <p>The bearing is relative to where the camera is pointed, positive to the right. Half the
     * angle of view away is the edge of the view, a quarter is halfway out - but not linearly,
     * which is the part worth getting from here rather than from arithmetic at the call site: the
     * view is a projection, so the position goes as the tangent of the angle.</p>
     *
     * @param bearingDegrees degrees clockwise from where the camera points, negative to the left
     * @param fieldOfViewDegrees the camera's horizontal angle of view, from
     *     {@link CameraView#getHorizontalFieldOfView()}
     * @param viewWidth the width of the view, in pixels
     * @return the x coordinate, which is outside [0, viewWidth] when the bearing is out of frame,
     *     or {@link Float#NaN} when there is no angle of view to work from or the bearing is at or
     *     past a right angle, where a projection has no answer
     */
    public static float screenXForBearing(
            float bearingDegrees, float fieldOfViewDegrees, int viewWidth) {
        return project(bearingDegrees, fieldOfViewDegrees, viewWidth, false /* invert */);
    }

    /**
     * Where an elevation lands down the viewfinder.
     *
     * <p>Positive is above where the camera points, which is up the world and so <em>down</em> the
     * y axis of a view. The flip is here so that a caller cannot forget it.</p>
     *
     * @param elevationDegrees degrees above where the camera points, negative below
     * @param fieldOfViewDegrees the camera's vertical angle of view, from
     *     {@link CameraView#getVerticalFieldOfView()}
     * @param viewHeight the height of the view, in pixels
     * @return the y coordinate, or {@link Float#NaN} on the same terms as
     *     {@link #screenXForBearing}
     */
    public static float screenYForElevation(
            float elevationDegrees, float fieldOfViewDegrees, int viewHeight) {
        return project(elevationDegrees, fieldOfViewDegrees, viewHeight, true /* invert */);
    }

    private static float project(
            float angleDegrees, float fieldOfViewDegrees, int extent, boolean invert) {
        if (fieldOfViewDegrees <= 0f || fieldOfViewDegrees >= 180f || extent <= 0) {
            return Float.NaN;
        }
        if (Math.abs(angleDegrees) >= 90f) {
            // At a right angle the projection plane is parallel to the direction, so there is no
            // position to report rather than an enormous one.
            return Float.NaN;
        }
        double halfExtent = Math.tan(Math.toRadians(fieldOfViewDegrees / 2));
        if (halfExtent <= 0) {
            return Float.NaN;
        }
        double offset = Math.tan(Math.toRadians(angleDegrees)) / halfExtent;
        if (invert) {
            offset = -offset;
        }
        return (float) (extent * (0.5 + offset / 2));
    }

    /**
     * Where a point in the world lands in the view.
     *
     * <p>For a session that tracks the device through the world and can say where things are in
     * metres. The matrices are the ones such a session publishes, column-major as OpenGL wants
     * them, and this applies them the way the renderer does - so a marker drawn at the result sits
     * where the renderer would have drawn it.</p>
     *
     * @param viewProjectionMatrix the projection matrix multiplied by the view matrix, 16 floats
     * @param out set to the position in the view, in pixels
     * @return whether the point is in front of the camera at all. A point behind it has no
     *     position in the view, and {@code out} is left alone - drawing a marker for something
     *     behind you is the mistake this return value exists to prevent
     */
    public static boolean project(
            @NonNull float[] viewProjectionMatrix,
            float worldX, float worldY, float worldZ,
            int viewWidth, int viewHeight,
            @NonNull PointF out) {
        if (viewProjectionMatrix.length < 16 || viewWidth <= 0 || viewHeight <= 0) {
            return false;
        }
        float[] clip = new float[4];
        Matrix.multiplyMV(clip, 0, viewProjectionMatrix, 0,
                new float[] {worldX, worldY, worldZ, 1f}, 0);
        if (clip[3] <= 0f) {
            // w is the distance along the view direction, so this is at or behind the camera.
            return false;
        }
        // Clip space to normalized device coordinates, which run -1 to 1 with y upwards, and then
        // to the view, whose y runs downwards from the top.
        float ndcX = clip[0] / clip[3];
        float ndcY = clip[1] / clip[3];
        out.set(viewWidth * (ndcX + 1f) / 2f, viewHeight * (1f - ndcY) / 2f);
        return true;
    }

    /**
     * How far a point in the world is from the camera, in metres.
     *
     * <p>This is the number to compare against a depth reading to decide whether something in the
     * scene is in front of it.</p>
     *
     * @param viewMatrix the session's view matrix, 16 floats, column-major
     * @return the distance in metres, or {@link Float#NaN} if the matrix is not a matrix
     */
    public static float distanceFromCamera(
            @NonNull float[] viewMatrix, float worldX, float worldY, float worldZ) {
        if (viewMatrix.length < 16) {
            return Float.NaN;
        }
        float[] inCamera = new float[4];
        Matrix.multiplyMV(inCamera, 0, viewMatrix, 0,
                new float[] {worldX, worldY, worldZ, 1f}, 0);
        // The camera looks down its own negative z, so what is in front has negative z.
        return -inCamera[2];
    }

    /**
     * Whether something in the scene stands in front of the point being marked.
     *
     * <p>This is what makes a marker look like it is in the room rather than painted on the glass:
     * a pillar placed across the hall should disappear behind the sofa in front of it.</p>
     *
     * <p>The tolerance matters. A depth reading is noisy and is measured at a slightly different
     * moment from the pose, so comparing them exactly makes a marker flicker along every edge it
     * is near. Treating anything within the tolerance as "not occluded" keeps the marker steady
     * and errs towards showing it, which is the kinder way to be wrong.</p>
     *
     * @param markerMeters how far away the thing being marked is
     * @param sceneMeters how far away the scene is at that point on screen, 0 or negative where
     *     the session has no reading
     * @param toleranceMeters how much closer the scene has to be before it counts as in the way
     * @return whether to hide the marker
     */
    public static boolean isOccluded(
            float markerMeters, float sceneMeters, float toleranceMeters) {
        if (sceneMeters <= 0f || Float.isNaN(sceneMeters) || Float.isNaN(markerMeters)) {
            // No reading is not the same as nothing in the way, and guessing either way is worse
            // than showing the marker.
            return false;
        }
        return sceneMeters < markerMeters - Math.max(0f, toleranceMeters);
    }

    /**
     * The visible part of an upright pillar standing at a point in the world.
     *
     * <p>A pillar of light is the one marker that cannot be a dot: it is tall, which is what makes
     * it findable from across a room and over furniture. The bottom may be behind the sofa while
     * the top is not, so this walks it from the ground up and returns the stretch that is in
     * front - the top being visible is the whole point, and a marker that vanished because its
     * base was hidden would be useless.</p>
     *
     * @param samples how many points up the pillar to test; more is smoother and slower
     * @param scene asked how far away the scene is at a position in the view
     * @return the lowest and highest visible points in the view, or null when no part of it is
     */
    @Nullable
    public static VisibleRun visiblePillar(
            @NonNull float[] viewMatrix, @NonNull float[] viewProjectionMatrix,
            float worldX, float groundY, float worldZ, float heightMeters,
            int viewWidth, int viewHeight, int samples, float toleranceMeters,
            @NonNull SceneDepth scene) {
        if (samples < 2 || heightMeters <= 0f) {
            return null;
        }
        PointF at = new PointF();
        PointF lowest = null;
        PointF highest = null;
        for (int i = 0; i < samples; i++) {
            float y = groundY + heightMeters * i / (samples - 1);
            if (!project(viewProjectionMatrix, worldX, y, worldZ, viewWidth, viewHeight, at)) {
                continue;
            }
            float distance = distanceFromCamera(viewMatrix, worldX, y, worldZ);
            if (isOccluded(distance, scene.metersAt(at.x, at.y), toleranceMeters)) {
                continue;
            }
            if (lowest == null) {
                lowest = new PointF(at.x, at.y);
            }
            highest = new PointF(at.x, at.y);
        }
        return lowest == null ? null : new VisibleRun(lowest, highest);
    }

    /** How far away the scene is at a point in the view. */
    public interface SceneDepth {
        /**
         * @return metres, or 0 or less where there is no reading
         */
        float metersAt(float viewX, float viewY);
    }

    /** The stretch of a pillar that is not hidden, from its lowest visible point to its highest. */
    public static final class VisibleRun {
        private final PointF mFrom;
        private final PointF mTo;

        VisibleRun(@NonNull PointF from, @NonNull PointF to) {
            mFrom = from;
            mTo = to;
        }

        /** The lowest visible point, which is nearest the ground. */
        @NonNull
        public PointF getFrom() {
            return mFrom;
        }

        /** The highest visible point. */
        @NonNull
        public PointF getTo() {
            return mTo;
        }
    }
}
