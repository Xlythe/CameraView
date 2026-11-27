package com.xlythe.view.camera.gl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.xlythe.view.camera.shadow.ShadowRecordingGles20;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * What a frame says about the camera that wrote it: the projection for the output it is drawn
 * into, where the camera was, and the depth, each only where something knew it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37, shadows = {ShadowRecordingGles20.class})
public final class GlFrameTest {
    private static final float TOLERANCE = 1e-5f;

    /** A pinhole off the middle of a buffer. */
    private static final float[] LENS = {0.8f, 1.4f, 0.45f, 0.52f, 0f};

    /** What a camera's SurfaceTexture reports: its buffer, flipped for GL. */
    private static final float[] FLIP = {
            1, 0, 0, 0,
            0, -1, 0, 0,
            0, 0, 1, 0,
            0, 1, 0, 1,
    };

    private GlFrame frame;
    private FrameGeometry geometry;

    @Before
    public void setUp() {
        ShadowRecordingGles20.reset();
        frame = new GlFrame(new OesTextureProgram());
        geometry = new FrameGeometry();
    }

    private void set(float[] textureMatrix, float[] surfaceTextureMatrix) {
        frame.set(7, textureMatrix, surfaceTextureMatrix, geometry, 1920, 1080, 5L,
                new int[] {0, 0, 1920, 1080});
    }

    /** A frame nobody described has no projection, no view and no depth, and says so. */
    @Test
    public void aFrameNobodyDescribedKnowsNothing() {
        frame.set(7, FLIP, FLIP, null, 1920, 1080, 5L, new int[] {0, 0, 1920, 1080});
        float[] matrix = {3f, 3f, 3f, 3f, 3f, 3f, 3f, 3f, 3f, 3f, 3f, 3f, 3f, 3f, 3f, 3f};
        assertFalse(frame.getProjectionMatrix(matrix, 0.1f, 10f));
        assertFalse(frame.getViewMatrix(matrix));
        assertEquals(0, frame.getDepthTextureName());
        assertEquals("a matrix that is not filled is left alone", 3f, matrix[0], 0f);

        set(FLIP, FLIP);
        assertFalse(frame.getProjectionMatrix(matrix, 0.1f, 10f));
        assertFalse(frame.getViewMatrix(matrix));
    }

    /**
     * The projection is the lens's, through how this output reads the buffer: drawn straight, as
     * the camera's own transform, it is the lens's own projection.
     */
    @Test
    public void theProjectionIsTheLensThroughThisOutput() {
        geometry.setNormalizedIntrinsics(LENS);
        set(FLIP, FLIP);
        float[] matrix = new float[16];
        assertTrue(frame.getProjectionMatrix(matrix, 0.1f, 10f));
        assertArrayEquals(GlMath.projection(LENS, GlMath.identity(), 0.1f, 10f), matrix,
                TOLERANCE);

        float[] turned = GlMath.multiply(FLIP, GlMath.cameraRequest(90, false));
        set(turned, turned);
        assertTrue(frame.getProjectionMatrix(matrix, 0.1f, 10f));
        assertArrayEquals(GlMath.projection(LENS, GlMath.cameraRequest(90, false), 0.1f, 10f),
                matrix, TOLERANCE);
    }

    @Test
    public void aProjectionNeedsPlanesInFrontAndInOrder() {
        geometry.setNormalizedIntrinsics(LENS);
        set(FLIP, FLIP);
        assertThrows(IllegalArgumentException.class,
                () -> frame.getProjectionMatrix(new float[16], 0f, 10f));
        assertThrows(IllegalArgumentException.class,
                () -> frame.getProjectionMatrix(new float[16], 5f, 1f));
        assertThrows(IllegalArgumentException.class,
                () -> frame.getProjectionMatrix(new float[15], 0.1f, 1f));
        assertThrows(IllegalArgumentException.class, () -> frame.getViewMatrix(new float[3]));
    }

    /** An output whose matrix flattens the frame has no projection to give. */
    @Test
    public void aFlattenedOutputHasNoProjection() {
        geometry.setNormalizedIntrinsics(LENS);
        set(new float[16], FLIP);
        assertFalse(frame.getProjectionMatrix(new float[16], 0.1f, 10f));
    }

    @Test
    public void theViewAndTheDepthAreWhatTheGeometrySaid() {
        float[] view = GlMath.identity();
        view[12] = 4f;
        geometry.setViewMatrix(view);
        geometry.setDepthTextureName(42);
        set(FLIP, FLIP);
        float[] matrix = new float[16];
        assertTrue(frame.getViewMatrix(matrix));
        assertArrayEquals(view, matrix, 0f);
        assertEquals(42, frame.getDepthTextureName());

        geometry.clear();
        assertFalse(frame.getViewMatrix(matrix));
        assertEquals(0, frame.getDepthTextureName());
        assertFalse(geometry.hasIntrinsics());
    }

    /** The geometry keeps its own copies, so a caller reusing an array changes nothing. */
    @Test
    public void theGeometryKeepsItsOwnCopies() {
        float[] lens = LENS.clone();
        float[] view = GlMath.identity();
        geometry.setNormalizedIntrinsics(lens);
        geometry.setViewMatrix(view);
        lens[0] = 99f;
        view[0] = 99f;
        assertEquals(LENS[0], geometry.getIntrinsics()[0], 0f);
        assertEquals(1f, geometry.getViewMatrix()[0], 0f);
    }
}
