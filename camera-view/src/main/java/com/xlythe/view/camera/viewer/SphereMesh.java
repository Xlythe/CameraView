package com.xlythe.view.camera.viewer;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * A patch of the sphere, the part one texture covers, as triangles: a grid of points on the unit
 * sphere, each with the place in the texture that belongs there.
 *
 * <p>The grid is fine enough, a few degrees a cell, that a flat triangle between three points
 * stands for the curved patch between them without the picture visibly bending at its edges.</p>
 */
final class SphereMesh {
    /** The most degrees one cell of the grid spans. */
    static final double CELL_DEGREES = 4.0;

    /** x, y, z, then s, t. */
    static final int FLOATS_PER_VERTEX = 5;

    /** Each cell is two triangles. */
    private static final int VERTICES_PER_CELL = 6;

    /** The vertices, ready for GL: interleaved and in native order. */
    @NonNull
    final FloatBuffer vertices;

    /** How many vertices there are. */
    final int vertexCount;

    private SphereMesh(@NonNull FloatBuffer vertices, int vertexCount) {
        this.vertices = vertices;
        this.vertexCount = vertexCount;
    }

    /**
     * The patch that shows part of a picture.
     *
     * @param gpano where the picture sits on the sphere
     * @param left the part's left edge, as a fraction of the picture's width
     * @param top its top edge, as a fraction of the picture's height
     * @param right its right edge
     * @param bottom its bottom edge
     * @param s0 the texture's s at the left edge
     * @param t0 its t at the top edge
     * @param s1 its s at the right edge
     * @param t1 its t at the bottom edge
     */
    @AnyThread
    @NonNull
    static SphereMesh patch(@NonNull GPano gpano, double left, double top, double right,
            double bottom, float s0, float t0, float s1, float t1) {
        double headingSpan = (right - left) * gpano.width * 360.0;
        double pitchSpan = (bottom - top) * gpano.height * 180.0;
        int columns = Math.max(1, (int) Math.ceil(headingSpan / CELL_DEGREES - 1e-9));
        int rows = Math.max(1, (int) Math.ceil(pitchSpan / CELL_DEGREES - 1e-9));
        float[] values = new float[columns * rows * VERTICES_PER_CELL * FLOATS_PER_VERTEX];
        int offset = 0;
        for (int row = 0; row < rows; row++) {
            double v0 = (double) row / rows;
            double v1 = (double) (row + 1) / rows;
            for (int column = 0; column < columns; column++) {
                double u0 = (double) column / columns;
                double u1 = (double) (column + 1) / columns;
                // Two triangles, top-left, bottom-left, top-right, then top-right, bottom-left,
                // bottom-right.
                offset = vertex(values, offset, gpano, left, top, right, bottom, u0, v0,
                        s0, t0, s1, t1);
                offset = vertex(values, offset, gpano, left, top, right, bottom, u0, v1,
                        s0, t0, s1, t1);
                offset = vertex(values, offset, gpano, left, top, right, bottom, u1, v0,
                        s0, t0, s1, t1);
                offset = vertex(values, offset, gpano, left, top, right, bottom, u1, v0,
                        s0, t0, s1, t1);
                offset = vertex(values, offset, gpano, left, top, right, bottom, u0, v1,
                        s0, t0, s1, t1);
                offset = vertex(values, offset, gpano, left, top, right, bottom, u1, v1,
                        s0, t0, s1, t1);
            }
        }
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(values).position(0);
        return new SphereMesh(buffer, values.length / FLOATS_PER_VERTEX);
    }

    /** Writes one vertex, at a fraction u across and v down the patch. */
    private static int vertex(@NonNull float[] values, int offset, @NonNull GPano gpano,
            double left, double top, double right, double bottom, double u, double v, float s0,
            float t0, float s1, float t1) {
        double x = left + (right - left) * u;
        double y = top + (bottom - top) * v;
        SphereMath.direction(gpano.headingAt(x), gpano.pitchAt(y), values, offset);
        values[offset + 3] = (float) (s0 + (s1 - s0) * u);
        values[offset + 4] = (float) (t0 + (t1 - t0) * v);
        return offset + FLOATS_PER_VERTEX;
    }
}
