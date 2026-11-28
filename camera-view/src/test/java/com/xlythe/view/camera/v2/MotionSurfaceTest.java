package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.util.Size;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Which size a camera feeds a motion photo's encoder at. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class MotionSurfaceTest {
    private static final Size MAX = new Size(1920, 1080);

    private static final Size[] SIZES = {
            new Size(3840, 2160), new Size(1920, 1440), new Size(1920, 1080),
            new Size(1440, 1080), new Size(1280, 960), new Size(1280, 720),
            new Size(640, 480), new Size(320, 240)
    };

    @Test
    public void takesThePreviewsOwnSizeWhenItFits() {
        assertEquals(new Size(1440, 1080),
                MotionSurface.chooseSize(SIZES, new Size(1440, 1080), MAX));
        assertEquals(new Size(1280, 720),
                MotionSurface.chooseSize(SIZES, new Size(1280, 720), MAX));
    }

    @Test
    public void otherwiseTheLargestOfThePreviewsShapeThatFits() {
        assertEquals("a 4:3 preview too large to encode", new Size(1440, 1080),
                MotionSurface.chooseSize(SIZES, new Size(1920, 1440), MAX));
        assertEquals("a 16:9 one", new Size(1920, 1080),
                MotionSurface.chooseSize(SIZES, new Size(3840, 2160), MAX));
        assertEquals("a software encoder's smaller limit", new Size(1280, 720),
                MotionSurface.chooseSize(SIZES, new Size(1920, 1080), new Size(1280, 720)));
    }

    @Test
    public void otherwiseTheLargestThatFits() {
        assertEquals(new Size(1920, 1080),
                MotionSurface.chooseSize(SIZES, new Size(1000, 1000), MAX));
        assertEquals(new Size(1920, 1080), MotionSurface.chooseSize(SIZES, null, MAX));
    }

    @Test
    public void nothingWhenNothingFits() {
        assertNull(MotionSurface.chooseSize(new Size[] {new Size(4000, 3000)},
                new Size(4000, 3000), MAX));
        assertNull(MotionSurface.chooseSize(new Size[0], new Size(640, 480), MAX));
        assertNull(MotionSurface.chooseSize(null, new Size(640, 480), MAX));
    }
}
