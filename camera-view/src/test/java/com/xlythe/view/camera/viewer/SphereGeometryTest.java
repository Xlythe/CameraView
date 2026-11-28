package com.xlythe.view.camera.viewer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;
import android.hardware.SensorManager;
import android.opengl.Matrix;
import android.view.Surface;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** The sphere's geometry: rotations, projection, how it is tiled and how a patch is meshed. */
@RunWith(RobolectricTestRunner.class)
public class SphereGeometryTest {
    private static final float EPSILON = 1e-4f;

    /** A phone pointed at a heading and pitch says it looks at that heading and pitch. */
    @Test
    public void lookRotationsRoundTrip() {
        for (double heading : new double[] {0, 45, 90, 179, -90, -135}) {
            for (double pitch : new double[] {-80, -30, 0, 30, 80}) {
                float[] rotation = SphereMath.lookRotation(heading, pitch);
                assertEquals(heading, SphereMath.headingOf(rotation), 1e-3);
                assertEquals(pitch, SphereMath.pitchOf(rotation), 1e-3);
            }
        }
    }

    /** Upright and facing north: the screen's top is up, its right is east. */
    @Test
    public void facingNorthTheScreenIsUpright() {
        float[] rotation = SphereMath.lookRotation(0, 0);
        // Columns are where the screen's x, y and z point in the world.
        assertVector(1, 0, 0, rotation[0], rotation[3], rotation[6]);
        assertVector(0, 0, 1, rotation[1], rotation[4], rotation[7]);
        assertVector(0, -1, 0, rotation[2], rotation[5], rotation[8]);
    }

    /** The view matrix brings the direction looked along to straight ahead, -z. */
    @Test
    public void theViewLooksAlongMinusZ() {
        float[] view = new float[16];
        SphereMath.view(SphereMath.lookRotation(120, 25), view);
        float[] direction = new float[4];
        SphereMath.direction(120, 25, direction, 0);
        direction[3] = 1;
        float[] eye = new float[4];
        Matrix.multiplyMV(eye, 0, view, 0, direction, 0);
        assertVector(0, 0, -1, eye[0], eye[1], eye[2]);
    }

    /** Straight ahead lands in the middle of the screen, and the edge of the view at its edge. */
    @Test
    public void theProjectionSpansTheFieldOfView() {
        float[] projection = new float[16];
        // Portrait: 60 degrees across the width.
        SphereMath.projection(60, 1000, 2000, projection);
        float[] ahead = project(projection, 0, 0, -1);
        assertEquals(0, ahead[0], EPSILON);
        assertEquals(0, ahead[1], EPSILON);
        float[] edge = project(projection, (float) Math.tan(Math.toRadians(30)), 0, -1);
        assertEquals(1, edge[0], EPSILON);

        // Landscape: 60 degrees across the height.
        SphereMath.projection(60, 2000, 1000, projection);
        edge = project(projection, 0, (float) Math.tan(Math.toRadians(30)), -1);
        assertEquals(1, edge[1], EPSILON);
    }

    @Test
    public void fieldsOfViewConvert() {
        assertEquals(60, SphereMath.horizontalFov(60, 1000, 2000), 1e-9);
        assertEquals(60, SphereMath.verticalFov(60, 2000, 1000), 1e-9);
        double vertical = SphereMath.verticalFov(60, 1000, 2000);
        assertTrue(vertical > 90 && vertical < 100);
        double horizontal = SphereMath.horizontalFov(60, 2000, 1000);
        assertEquals(vertical, horizontal, 1e-9);
        assertEquals(60, SphereMath.shortSideFov(horizontal, 2000, 1000), 1e-9);
        assertEquals(70, SphereMath.shortSideFov(70, 1000, 2000), 1e-9);
    }

    /** The pose turns the middle of the panorama to its heading, tips and rolls it. */
    @Test
    public void thePoseTurnsThePanorama() {
        float[] middle = new float[3];
        SphereMath.direction(0, 0, middle, 0);

        float[] east = transform(SphereMath.pose(pose(90, 0, 0)), middle);
        assertVector(1, 0, 0, east[0], east[1], east[2]);

        float[] tipped = transform(SphereMath.pose(pose(0, 10, 0)), middle);
        assertVector(0, (float) Math.cos(Math.toRadians(10)),
                (float) Math.sin(Math.toRadians(10)), tipped[0], tipped[1], tipped[2]);

        // Rolled 10 degrees: the panorama's own up leans east.
        float[] rolledUp = transform(SphereMath.pose(pose(0, 0, 10)), new float[] {0, 0, 1});
        assertVector((float) Math.sin(Math.toRadians(10)), 0,
                (float) Math.cos(Math.toRadians(10)), rolledUp[0], rolledUp[1], rolledUp[2]);
    }

    @Test
    public void toGlTransposesIntoColumns() {
        float[] rotation = SphereMath.rotationZ(30);
        float[] gl = new float[16];
        SphereMath.toGl(rotation, gl);
        float[] in = {1, 0, 0, 1};
        float[] out = new float[4];
        Matrix.multiplyMV(out, 0, gl, 0, in, 0);
        float[] expected = transform(rotation, new float[] {1, 0, 0});
        assertVector(expected[0], expected[1], expected[2], out[0], out[1], out[2]);
        assertEquals(1, out[3], 0);
    }

    @Test
    public void wrapsDegrees() {
        assertEquals(0, SphereMath.wrapDegrees(360), 1e-9);
        assertEquals(-170, SphereMath.wrapDegrees(190), 1e-9);
        assertEquals(170, SphereMath.wrapDegrees(-190), 1e-9);
        assertEquals(180, SphereMath.wrapDegrees(-180), 1e-9);
        assertEquals(45, SphereMath.wrapDegrees(45 + 720), 1e-9);
    }

    /** The screen's axes for each rotation, as the sensor framework spells them. */
    @Test
    public void screenAxesFollowTheDisplay() {
        assertEquals(SensorManager.AXIS_X, PhotosphereView.screenAxes(Surface.ROTATION_0)[0]);
        assertEquals(SensorManager.AXIS_MINUS_X,
                PhotosphereView.screenAxes(Surface.ROTATION_90)[1]);
        assertEquals(SensorManager.AXIS_MINUS_Y,
                PhotosphereView.screenAxes(Surface.ROTATION_180)[1]);
        assertEquals(SensorManager.AXIS_MINUS_Y,
                PhotosphereView.screenAxes(Surface.ROTATION_270)[0]);
    }

    /** A sphere within the budget keeps every pixel, cut into bordered tiles. */
    @Test
    public void aSphereWithinTheBudgetKeepsEveryPixel() {
        SphereTiling plan = SphereTiling.plan(4096, 2048, 8192, true);

        assertEquals(1.0, plan.scaleX, 0);
        assertEquals(1.0, plan.scaleY, 0);
        assertEquals(4096, plan.textureWidth);
        assertEquals(8, plan.tiles.size());
        assertEquals(4, plan.baseSampleSize);
        assertEquals(1, plan.sampleSize());
        SphereTiling.Tile first = plan.tiles.get(0);
        assertEquals(new Rect(0, 0, 1024, 1024), first.inside);
        // Its right and bottom neighbours lend a border, and the far end of the picture its left.
        assertEquals(new Rect(0, 0, 1025, 1025), first.outside);
        assertTrue(first.wrapsLeft);
        assertFalse(first.wrapsRight);
        assertEquals(1026, first.textureWidth());
        assertEquals(1, first.insideLeft());
        assertEquals(0, first.insideTop());
        SphereTiling.Tile inner = plan.tiles.get(1);
        assertEquals(new Rect(1023, 0, 2049, 1025), inner.outside);
        assertEquals(1, inner.insideLeft());
        SphereTiling.Tile last = plan.tiles.get(7);
        assertTrue(last.wrapsRight);
        assertEquals(1, last.insideTop());
    }

    /** A picture too large for the budget is shrunk to it, borders and all. */
    @Test
    public void aHugeSphereIsShrunkToTheBudget() {
        SphereTiling plan = SphereTiling.plan(20000, 10000, 4096, true);

        assertTrue(plan.scaleX < 1);
        assertEquals(plan.scaleX, plan.scaleY, 1e-3);
        long texels = 0;
        for (SphereTiling.Tile tile : plan.tiles) {
            texels += tile.texels();
            assertTrue(tile.textureWidth() <= 1026);
            assertTrue(tile.textureHeight() <= 1026);
        }
        assertTrue(texels <= SphereTiling.TEXEL_BUDGET);
        assertTrue(texels > SphereTiling.TEXEL_BUDGET * 0.95);
        assertEquals(2, plan.sampleSize());
        assertEquals(32, plan.baseSampleSize);
        // The texels for the picture's far corner come from its far corner.
        Rect source = plan.source(plan.textureWidth - 1, plan.textureHeight - 1,
                plan.textureWidth, plan.textureHeight);
        assertEquals(20000, source.right);
        assertEquals(10000, source.bottom);
        assertTrue(source.width() >= 3 && source.width() <= 5);
    }

    /** A GPU that takes only small textures gets small tiles. */
    @Test
    public void tilesFitTheGpu() {
        SphereTiling small = SphereTiling.plan(2000, 1000, 512, false);
        for (SphereTiling.Tile tile : small.tiles) {
            assertTrue(tile.textureWidth() <= 512);
            assertTrue(tile.textureHeight() <= 512);
            assertFalse(tile.wrapsLeft || tile.wrapsRight);
        }
        assertEquals(4, small.baseSampleSize);

        SphereTiling unknown = SphereTiling.plan(2000, 1000, 0, false);
        assertEquals(2, unknown.baseSampleSize);
        assertEquals(2, unknown.tiles.size());
    }

    /** A patch's corners are the corners of its part of the picture, on the sphere. */
    @Test
    public void aPatchCoversItsPartOfTheSphere() {
        GPano band = new GPano(0.25, 0.25, 0.5, 0.5, 0, 0, 0, Float.NaN, Float.NaN,
                Float.NaN);
        SphereMesh mesh = SphereMesh.patch(band, 0, 0, 1, 1, 0.1f, 0.2f, 0.9f, 0.8f);

        // 180 by 90 degrees in cells of at most 4.
        assertEquals(45 * 23 * 6, mesh.vertexCount);
        float[] vertices = new float[mesh.vertexCount * SphereMesh.FLOATS_PER_VERTEX];
        mesh.vertices.position(0);
        mesh.vertices.get(vertices);
        // The first vertex is the top-left corner: heading -90, pitch 45.
        float[] corner = new float[3];
        SphereMath.direction(-90, 45, corner, 0);
        assertVector(corner[0], corner[1], corner[2], vertices[0], vertices[1], vertices[2]);
        assertEquals(0.1f, vertices[3], EPSILON);
        assertEquals(0.2f, vertices[4], EPSILON);
        // The last is the bottom-right: heading 90, pitch -45.
        int last = (mesh.vertexCount - 1) * SphereMesh.FLOATS_PER_VERTEX;
        SphereMath.direction(90, -45, corner, 0);
        assertVector(corner[0], corner[1], corner[2], vertices[last], vertices[last + 1],
                vertices[last + 2]);
        assertEquals(0.9f, vertices[last + 3], EPSILON);
        assertEquals(0.8f, vertices[last + 4], EPSILON);
        // Every point is on the unit sphere.
        for (int i = 0; i < mesh.vertexCount; i++) {
            int o = i * SphereMesh.FLOATS_PER_VERTEX;
            double length = Math.sqrt(vertices[o] * vertices[o] + vertices[o + 1] * vertices[o + 1]
                    + vertices[o + 2] * vertices[o + 2]);
            assertEquals(1, length, EPSILON);
        }
    }

    /** A sliver of a patch is still at least one cell. */
    @Test
    public void aSliverIsOneCell() {
        GPano sphere = GPano.assumed(100, 50);
        SphereMesh mesh = SphereMesh.patch(sphere, 0.5, 0.5, 0.5001, 0.5001, 0, 0, 1, 1);
        assertEquals(6, mesh.vertexCount);
    }

    private static GPano pose(float heading, float pitch, float roll) {
        return new GPano(0, 0, 1, 1, heading, pitch, roll, Float.NaN, Float.NaN, Float.NaN);
    }

    private static float[] project(float[] projection, float x, float y, float z) {
        float[] clip = new float[4];
        Matrix.multiplyMV(clip, 0, projection, 0, new float[] {x, y, z, 1}, 0);
        return new float[] {clip[0] / clip[3], clip[1] / clip[3], clip[2] / clip[3]};
    }

    private static float[] transform(float[] rotation, float[] vector) {
        float[] result = new float[3];
        for (int row = 0; row < 3; row++) {
            result[row] = rotation[row * 3] * vector[0] + rotation[row * 3 + 1] * vector[1]
                    + rotation[row * 3 + 2] * vector[2];
        }
        return result;
    }

    private static void assertVector(float x, float y, float z, float actualX, float actualY,
            float actualZ) {
        assertEquals(x, actualX, EPSILON);
        assertEquals(y, actualY, EPSILON);
        assertEquals(z, actualZ, EPSILON);
    }
}
