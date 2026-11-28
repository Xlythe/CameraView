package com.xlythe.view.camera.edit;

import android.graphics.RectF;

import androidx.annotation.NonNull;

/**
 * Whether an eraser's path touches a stroke: whether any of its samples comes within the
 * eraser's half-width and the stroke's own of the stroke's line, as Muse's eraser measures it.
 */
final class Eraser {
    private Eraser() {}

    /** Whether an eraser stroke touches another stroke anywhere. */
    static boolean touches(@NonNull Stroke eraser, @NonNull Stroke stroke) {
        RectF bounds = stroke.inkedBounds();
        float reach = eraser.getBrush().getWidth() / 2f;
        bounds.inset(-reach, -reach);
        float strokeRadius = stroke.getBrush().maxRadius();
        float within = reach + strokeRadius;
        // Segment against segment, so an eraser moved quickly between samples still takes
        // what it passed over.
        int eraserSegments = Math.max(1, eraser.size() - 1);
        int strokeSegments = Math.max(1, stroke.size() - 1);
        for (int i = 0; i < eraserSegments; i++) {
            float ax0 = eraser.getX(i);
            float ay0 = eraser.getY(i);
            float ax1 = eraser.getX(Math.min(i + 1, eraser.size() - 1));
            float ay1 = eraser.getY(Math.min(i + 1, eraser.size() - 1));
            if (!RectF.intersects(bounds, new RectF(Math.min(ax0, ax1), Math.min(ay0, ay1),
                    Math.max(ax0, ax1), Math.max(ay0, ay1)))) {
                continue;
            }
            for (int j = 0; j < strokeSegments; j++) {
                float bx0 = stroke.getX(j);
                float by0 = stroke.getY(j);
                float bx1 = stroke.getX(Math.min(j + 1, stroke.size() - 1));
                float by1 = stroke.getY(Math.min(j + 1, stroke.size() - 1));
                if (distanceBetweenSegments(ax0, ay0, ax1, ay1, bx0, by0, bx1, by1) <= within) {
                    return true;
                }
            }
        }
        return false;
    }

    /** How close two segments come: 0 where they cross. */
    static float distanceBetweenSegments(float ax0, float ay0, float ax1, float ay1, float bx0,
            float by0, float bx1, float by1) {
        if (cross(ax0, ay0, ax1, ay1, bx0, by0) * cross(ax0, ay0, ax1, ay1, bx1, by1) < 0
                && cross(bx0, by0, bx1, by1, ax0, ay0) * cross(bx0, by0, bx1, by1, ax1, ay1) < 0) {
            return 0f;
        }
        return Math.min(Math.min(distanceToSegment(ax0, ay0, bx0, by0, bx1, by1),
                distanceToSegment(ax1, ay1, bx0, by0, bx1, by1)),
                Math.min(distanceToSegment(bx0, by0, ax0, ay0, ax1, ay1),
                        distanceToSegment(bx1, by1, ax0, ay0, ax1, ay1)));
    }

    /** Which side of the line through a segment a point is on. */
    private static float cross(float x0, float y0, float x1, float y1, float x, float y) {
        return (x1 - x0) * (y - y0) - (y1 - y0) * (x - x0);
    }

    /** How far a point is from a segment. */
    static float distanceToSegment(float x, float y, float x0, float y0, float x1, float y1) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float lengthSquared = dx * dx + dy * dy;
        if (lengthSquared == 0f) {
            return distance(x, y, x0, y0);
        }
        float t = Brush.clamp(((x - x0) * dx + (y - y0) * dy) / lengthSquared, 0f, 1f);
        return distance(x, y, x0 + t * dx, y0 + t * dy);
    }

    private static float distance(float x0, float y0, float x1, float y1) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
