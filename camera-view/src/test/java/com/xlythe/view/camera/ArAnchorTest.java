package com.xlythe.view.camera;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.xlythe.view.camera.gl.GlMath;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** An anchor says where it is only while AR knows, and never again once let go of. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37)
public final class ArAnchorTest {
    /** Somewhere in the room: two metres ahead and a little to the right. */
    private static final float[] PLACE = {
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            0.4f, 0, -2, 1,
    };

    @Test
    public void anAnchorIsNowhereUntilItIsTracked() {
        ArAnchor anchor = new ArAnchor();
        float[] matrix = GlMath.identity();
        assertFalse(anchor.isTracking());
        assertFalse(anchor.getModelMatrix(matrix));
        assertArrayEquals("an untracked anchor leaves the matrix alone",
                GlMath.identity(), matrix, 0f);
    }

    @Test
    public void aTrackedAnchorSaysWhereItIs() {
        ArAnchor anchor = new ArAnchor();
        anchor.onPose(PLACE);
        float[] matrix = new float[16];
        assertTrue(anchor.isTracking());
        assertTrue(anchor.getModelMatrix(matrix));
        assertArrayEquals(PLACE, matrix, 0f);

        anchor.onPose(null);
        assertFalse("lost from view is not tracked", anchor.isTracking());
        assertFalse(anchor.getModelMatrix(matrix));
    }

    /** The anchor keeps its own copy of where it is, not the tracker's scratch array. */
    @Test
    public void anAnchorKeepsItsOwnCopy() {
        ArAnchor anchor = new ArAnchor();
        float[] scratch = PLACE.clone();
        anchor.onPose(scratch);
        scratch[12] = 9f;
        float[] matrix = new float[16];
        assertTrue(anchor.getModelMatrix(matrix));
        assertArrayEquals(PLACE, matrix, 0f);
    }

    @Test
    public void aDetachedAnchorIsNeverTrackedAgain() {
        ArAnchor anchor = new ArAnchor();
        anchor.onPose(PLACE);
        anchor.detach();
        assertTrue(anchor.isDetached());
        assertFalse(anchor.isTracking());
        anchor.onPose(PLACE);
        assertFalse(anchor.isTracking());
        assertFalse(anchor.getModelMatrix(new float[16]));
    }

    @Test
    public void aMatrixIsSixteenFloats() {
        ArAnchor anchor = new ArAnchor();
        assertThrows(IllegalArgumentException.class, () -> anchor.getModelMatrix(new float[9]));
    }
}
