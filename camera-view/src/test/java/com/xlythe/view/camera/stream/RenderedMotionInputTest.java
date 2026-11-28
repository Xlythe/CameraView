package com.xlythe.view.camera.stream;

import static org.junit.Assert.assertEquals;

import android.graphics.Rect;
import android.util.Size;

import com.xlythe.view.camera.gl.GlMath;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The sizing and cropping a renderer feeding a motion photo applies to each frame. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class RenderedMotionInputTest {
    private static final float TOLERANCE = 1e-6f;

    @Test
    public void aFrameWithinTheEncodersLimitKeepsItsSize() {
        assertEquals(new Size(1440, 1080),
                RenderedMotionInput.encodedSize(1440, 1080, new Size(1920, 1080)));
        assertEquals("either way round", new Size(1080, 1440),
                RenderedMotionInput.encodedSize(1080, 1440, new Size(1920, 1080)));
    }

    @Test
    public void aLargerFrameIsScaledDownToFitKeepingItsShape() {
        assertEquals(new Size(1920, 1080),
                RenderedMotionInput.encodedSize(3840, 2160, new Size(1920, 1080)));
        assertEquals("the short side limits a squarer frame", new Size(1440, 1080),
                RenderedMotionInput.encodedSize(4000, 3000, new Size(1920, 1080)));
        assertEquals("a tall crop of a view port", new Size(886, 1920),
                RenderedMotionInput.encodedSize(1000, 2167, new Size(1920, 1080)));
    }

    @Test
    public void sidesAreEven() {
        assertEquals(new Size(638, 478),
                RenderedMotionInput.encodedSize(639, 479, new Size(1920, 1080)));
        assertEquals("never empty", new Size(2, 2),
                RenderedMotionInput.encodedSize(1, 1, new Size(1920, 1080)));
    }

    @Test
    public void theWholeBufferIsTheIdentity() {
        float[] matrix = RenderedMotionInput.cropMatrix(new Rect(0, 0, 640, 480),
                new Size(640, 480));

        assertEquals(true, GlMath.isIdentity(matrix));
    }

    @Test
    public void aCropMapsTheOutputOntoItsPartOfTheBuffer() {
        // The middle half across, and the top half down: rows 0 to 240 of 480, which in texture
        // coordinates counted up from the bottom is 0.5 to 1.
        float[] matrix = RenderedMotionInput.cropMatrix(new Rect(160, 0, 480, 240),
                new Size(640, 480));

        assertPoint(matrix, 0f, 0f, 0.25f, 0.5f);
        assertPoint(matrix, 1f, 1f, 0.75f, 1f);
        assertPoint(matrix, 0.5f, 0.5f, 0.5f, 0.75f);
    }

    private static void assertPoint(float[] matrix, float s, float t, float expectedS,
            float expectedT) {
        assertEquals(expectedS, matrix[0] * s + matrix[4] * t + matrix[12], TOLERANCE);
        assertEquals(expectedT, matrix[1] * s + matrix[5] * t + matrix[13], TOLERANCE);
    }
}
