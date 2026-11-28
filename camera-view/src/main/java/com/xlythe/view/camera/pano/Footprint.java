package com.xlythe.view.camera.pano;

import androidx.annotation.NonNull;

/**
 * The patch of sphere a frame covers, as ranges of yaw and pitch.
 *
 * <p>Found by walking the frame's border, since a straight edge of the frame is a great circle on
 * the sphere and its extremes lie on the border rather than inside. Two cases need care. A frame
 * that contains a pole covers every yaw, however narrow it is, and its border alone would say
 * otherwise. And yaw wraps: a frame aimed just past south has edges at 170 and -170 degrees, which
 * is twenty degrees wide and not three hundred and forty, so yaws are measured from the frame's
 * own centre rather than from north.</p>
 */
final class Footprint {
    /** Border points sampled per edge, enough that the bulge of a long edge is not missed. */
    private static final int SAMPLES_PER_EDGE = 16;

    /**
     * Added to every side, to cover the sliver a border sample can miss between two points and
     * the pixel the sampling reaches into.
     */
    private static final float SLACK_DEGREES = 1f;

    /** How far either side of the centre a border's yaw may reach before it may have wrapped. */
    private static final float WRAP_RISK_DEGREES = 135f;

    /** The lowest pitch covered. */
    final float minPitch;

    /** The highest pitch covered. */
    final float maxPitch;

    /** Whether every yaw is covered, which is true of any frame containing a pole. */
    final boolean allYaws;

    /** The yaw of the frame's centre, which {@link #minYaw} and {@link #maxYaw} surround. */
    final float centreYaw;

    /**
     * The lowest yaw covered, unwrapped around {@link #centreYaw} so it may be below -180.
     * Meaningless when {@link #allYaws}.
     */
    final float minYaw;

    /** The highest yaw covered, unwrapped like {@link #minYaw}, within 360 of it. */
    final float maxYaw;

    private Footprint(float minPitch, float maxPitch, boolean allYaws, float centreYaw,
            float minYaw, float maxYaw) {
        this.minPitch = minPitch;
        this.maxPitch = maxPitch;
        this.allYaws = allYaws;
        this.centreYaw = centreYaw;
        this.minYaw = minYaw;
        this.maxYaw = maxYaw;
    }

    @NonNull
    static Footprint of(@NonNull CameraPose pose) {
        float[] direction = new float[3];
        float[] point = new float[2];
        float centreYaw = pose.yaw();
        float minPitch = 90f;
        float maxPitch = -90f;
        float minYaw = 0f;
        float maxYaw = 0f;
        for (int edge = 0; edge < 4; edge++) {
            for (int i = 0; i < SAMPLES_PER_EDGE; i++) {
                float t = (float) i / SAMPLES_PER_EDGE;
                float x;
                float y;
                switch (edge) {
                    case 0:
                        x = t * pose.width;
                        y = 0;
                        break;
                    case 1:
                        x = pose.width;
                        y = t * pose.height;
                        break;
                    case 2:
                        x = (1 - t) * pose.width;
                        y = pose.height;
                        break;
                    default:
                        x = 0;
                        y = (1 - t) * pose.height;
                        break;
                }
                pose.unproject(x, y, direction);
                float pitch = SphereMath.pitch(direction[0], direction[1], direction[2]);
                minPitch = Math.min(minPitch, pitch);
                maxPitch = Math.max(maxPitch, pitch);
                float yaw = SphereMath.wrap(SphereMath.yaw(direction[0], direction[1]) - centreYaw);
                minYaw = Math.min(minYaw, yaw);
                maxYaw = Math.max(maxYaw, yaw);
            }
        }
        boolean north = pose.project(0f, 0f, 1f, point) && pose.contains(point, 0f);
        boolean south = pose.project(0f, 0f, -1f, point) && pose.contains(point, 0f);
        if (north) {
            maxPitch = 90f;
        }
        if (south) {
            minPitch = -90f;
        }
        // Near a pole the border's yaws spread round most of the circle, and once they reach
        // halfway round, a border point wrapped to the far side could be one that went past it.
        // Every yaw is taken instead, which costs only the rows of a polar cap.
        boolean allYaws = north || south
                || minYaw < -WRAP_RISK_DEGREES || maxYaw > WRAP_RISK_DEGREES;
        return new Footprint(
                Math.max(-90f, minPitch - SLACK_DEGREES),
                Math.min(90f, maxPitch + SLACK_DEGREES),
                allYaws,
                centreYaw,
                centreYaw + minYaw - SLACK_DEGREES,
                centreYaw + maxYaw + SLACK_DEGREES);
    }
}
