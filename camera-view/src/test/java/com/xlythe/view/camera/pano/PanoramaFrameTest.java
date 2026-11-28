package com.xlythe.view.camera.pano;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.media.FakeImage;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;

/** Frames from every backend, read the same way whatever their layout. */
@RunWith(RobolectricTestRunner.class)
public class PanoramaFrameTest {
    @Test
    public void colours_areFullRangeBt601() {
        assertEquals(0xFF808080, PanoramaFrame.toArgb(128, 0, 0));
        assertEquals(0xFF000000, PanoramaFrame.toArgb(0, 0, 0));
        assertEquals(0xFFFFFFFF, PanoramaFrame.toArgb(255, 0, 0));
        // Pure red, written as YUV, comes back red.
        int red = PanoramaFrame.toArgb(76, 85 - 128, 255 - 128);
        assertTrue(ArgbImage.red(red) > 250);
        assertTrue(ArgbImage.green(red) < 5);
        assertTrue(ArgbImage.blue(red) < 5);
    }

    @Test
    public void nv21_readsVBeforeU() {
        // A 4x2 frame, one chroma pair per 2x2 block: the left block reddish, the right bluish.
        byte[] nv21 = {
                100, 100, 100, 100,
                100, 100, 100, 100,
                (byte) 200, (byte) 60, (byte) 60, (byte) 200,
        };
        PanoramaFrame frame = PanoramaFrame.fromNv21(nv21, 4, 2, 90, false, 1234L);
        ArgbImage image = frame.toArgb(1024, new PanoramaFrame.Scratch());
        assertEquals(4, image.width);
        assertEquals(2, image.height);
        assertEquals(PanoramaFrame.toArgb(100, 60 - 128, 200 - 128), image.pixels[0]);
        assertEquals(PanoramaFrame.toArgb(100, 200 - 128, 60 - 128), image.pixels[3]);
        assertEquals(90, frame.rotationDegrees);
        assertEquals(1234L, frame.timestampNanos);
    }

    @Test
    public void aLargeFrame_isShrunkByAveraging() {
        byte[] nv21 = new byte[8 * 8 * 3 / 2];
        Arrays.fill(nv21, (byte) 128);
        // Alternating columns of 0 and 200: averaged in pairs, every pixel is 100.
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                nv21[y * 8 + x] = (byte) (x % 2 == 0 ? 0 : 200);
            }
        }
        ArgbImage image = PanoramaFrame.fromNv21(nv21, 8, 8, 0, false, 0L)
                .toArgb(4, new PanoramaFrame.Scratch());
        assertEquals(4, image.width);
        assertEquals(4, image.height);
        for (int pixel : image.pixels) {
            assertEquals(0xFF646464, pixel);
        }
    }

    @Test
    public void paddedPlanarImage_skipsThePadding() {
        assertMatchesTheImage(FakeImage.yuv420(8, 4, 3, 1));
    }

    @Test
    public void semiPlanarImage_readsInterleavedChroma() {
        assertMatchesTheImage(FakeImage.yuv420(8, 4, 0, 2));
    }

    @Test
    public void anImageInAnotherFormat_isRefused() {
        assertNull(PanoramaFrame.fromImage(FakeImage.jpeg(4, 4, new byte[16]), 0, false, 0L, 0L));
    }

    @Test
    public void anImage_isStampedOnTheElapsedClock() {
        FakeImage image = FakeImage.yuv420(8, 4, 0, 2);
        long monotonicNow = image.getTimestamp() + 30_000_000L;
        long elapsedNow = monotonicNow + 3_600_000_000_000L;
        PanoramaFrame frame = PanoramaFrame.fromImage(image, 270, true, elapsedNow,
                monotonicNow);
        assertNotNull(frame);
        assertEquals(elapsedNow - 30_000_000L, frame.timestampNanos);
        assertTrue(frame.frontFacing);
        assertEquals(270, frame.rotationDegrees);
    }

    @Test
    public void scratch_isReusedBetweenFrames() {
        PanoramaFrame.Scratch scratch = new PanoramaFrame.Scratch();
        FakeImage image = FakeImage.yuv420(8, 4, 0, 1);
        PanoramaFrame.fromImage(image, 0, false, 0L, 0L).toArgb(1024, scratch);
        byte[] luma = scratch.y;
        PanoramaFrame.fromImage(image, 0, false, 0L, 0L).toArgb(1024, scratch);
        assertSame(luma, scratch.y);
    }

    private static void assertMatchesTheImage(FakeImage image) {
        PanoramaFrame frame = PanoramaFrame.fromImage(image, 0, false, 0L, 0L);
        assertNotNull(frame);
        ArgbImage argb = frame.toArgb(1024, new PanoramaFrame.Scratch());
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 8; column++) {
                int chroma = (row / 2) * 4 + column / 2;
                int expected = PanoramaFrame.toArgb(
                        FakeImage.expectedLuma(8, row, column) & 0xFF,
                        (FakeImage.expectedU(chroma) & 0xFF) - 128,
                        (FakeImage.expectedV(chroma) & 0xFF) - 128);
                assertEquals(expected, argb.pixels[row * 8 + column]);
            }
        }
    }
}
