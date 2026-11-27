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

import java.util.ArrayList;
import java.util.List;

/**
 * What a colour filter hands the GPU: the matrix it was given, the frame's own transform, and
 * nothing at all when the driver will not build its program.
 *
 * <p>The colours that matrix produces are asserted in {@link ColorMatrixTest}; this is the part
 * between a caller setting a matrix and a shader receiving one, which is where a filter that
 * looks right in a unit test and does nothing on screen would go wrong.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37, shadows = {ShadowRecordingGles20.class})
public class ColorFilterTest {
    private static final float TOLERANCE = 1e-6f;

    /** The external texture the camera frame is in, for a frame built by hand. */
    private static final int CAMERA_TEXTURE = 900;

    private GlFrame frame;

    /** A texture matrix with a quarter turn in it, so a frame's own matrix is recognisable. */
    private final float[] quarterTurn = {
            0, -1, 0, 0,
            1, 0, 0, 0,
            0, 0, 1, 0,
            0, 1, 0, 1,
    };

    @Before
    public void setUp() {
        ShadowRecordingGles20.reset();
        frame = new GlFrame(new OesTextureProgram());
        frame.set(CAMERA_TEXTURE, quarterTurn, 640, 480, 1_000L, new int[] {0, 0, 640, 480});
    }

    /** A filter given a matrix is drawn with exactly that matrix. */
    @Test
    public void aFilterDrawsWithTheMatrixItWasGiven() {
        ColorFilter filter = new ColorFilter(ColorMatrix.sepia());
        filter.onRendererStarted();

        assertTrue("a filter draws the frame itself", filter.onDrawFrame(frame));

        assertArrayEquals(ColorMatrix.sepia(), ShadowRecordingGles20.uniform("uColorMatrix"),
                TOLERANCE);
    }

    /** A filter nobody has configured is the identity, so turning one on changes nothing. */
    @Test
    public void aNewFilterChangesNothing() {
        ColorFilter filter = new ColorFilter();
        filter.onRendererStarted();
        filter.onDrawFrame(frame);

        assertArrayEquals(ColorMatrix.identity(), ShadowRecordingGles20.uniform("uColorMatrix"),
                TOLERANCE);
    }

    /** A matrix changed between frames is the one the next frame is drawn with, uncompiled. */
    @Test
    public void aMatrixChangedBetweenFramesReachesTheNextFrame() {
        ColorFilter filter = new ColorFilter(ColorMatrix.sepia());
        filter.onRendererStarted();
        filter.onDrawFrame(frame);
        int programsBefore = drawnPrograms().size();

        filter.setMatrix(ColorMatrix.grayscale());
        filter.onDrawFrame(frame);

        assertArrayEquals(ColorMatrix.grayscale(), ShadowRecordingGles20.uniform("uColorMatrix"),
                TOLERANCE);
        assertEquals("a new matrix is a uniform, not a new program",
                drawnPrograms().get(0), drawnPrograms().get(programsBefore));
    }

    /** The caller's array is copied, so changing it afterwards cannot reach a frame mid-draw. */
    @Test
    public void theCallersArrayIsCopiedRatherThanKept() {
        float[] matrix = ColorMatrix.invert();
        ColorFilter filter = new ColorFilter(matrix);
        filter.onRendererStarted();

        matrix[0] = 42f;
        filter.onDrawFrame(frame);

        assertArrayEquals(ColorMatrix.invert(), ShadowRecordingGles20.uniform("uColorMatrix"),
                TOLERANCE);
    }

    /** Anything but sixteen floats is refused up front rather than read past its end. */
    @Test
    public void aMatrixOfTheWrongSizeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ColorFilter(new float[9]));
        ColorFilter filter = new ColorFilter();
        assertThrows(IllegalArgumentException.class, () -> filter.setMatrix(new float[20]));
    }

    /**
     * The frame is sampled through its own matrix, from the camera's texture, across the whole
     * picture - which is what keeps a filtered preview the right way up.
     */
    @Test
    public void theFrameIsSampledThroughItsOwnMatrixAcrossThePicture() {
        ColorFilter filter = new ColorFilter(ColorMatrix.contrast(1.5f));
        filter.onRendererStarted();
        filter.onDrawFrame(frame);

        List<ShadowRecordingGles20.Draw> draws = ShadowRecordingGles20.draws();
        assertEquals("one quad per frame", 1, draws.size());
        ShadowRecordingGles20.Draw draw = draws.get(0);
        assertArrayEquals(quarterTurn, draw.textureMatrix, TOLERANCE);
        assertEquals(CAMERA_TEXTURE, draw.externalTexture);
        assertArrayEquals(new int[] {0, 0, 640, 480}, draw.viewport);
    }

    /**
     * A driver that will not compile the filter leaves the camera working: the filter declines
     * every frame, which has the library draw it unfiltered.
     */
    @Test
    public void aShaderTheDriverRefusesLeavesFramesUnfiltered() {
        ShadowRecordingGles20.setShadersCompile(false);
        ColorFilter filter = new ColorFilter(ColorMatrix.sepia());

        filter.onRendererStarted();

        assertFalse("the frame goes back to the library", filter.onDrawFrame(frame));
        assertTrue("and the filter draws nothing itself", ShadowRecordingGles20.draws().isEmpty());
    }

    /** Stopping gives the program back, and a stopped filter draws nothing. */
    @Test
    public void stoppingGivesTheProgramBack() {
        ColorFilter filter = new ColorFilter(ColorMatrix.sepia());
        filter.onRendererStarted();
        filter.onDrawFrame(frame);
        int program = ShadowRecordingGles20.draws().get(0).program;

        filter.onRendererStopped();

        assertTrue(ShadowRecordingGles20.isProgramDeleted(program));
        assertFalse("there is no program left to draw with", filter.onDrawFrame(frame));
    }

    /** The program behind each draw so far, in order. */
    private static List<Integer> drawnPrograms() {
        List<Integer> programs = new ArrayList<>();
        for (ShadowRecordingGles20.Draw draw : ShadowRecordingGles20.draws()) {
            programs.add(draw.program);
        }
        return programs;
    }
}
