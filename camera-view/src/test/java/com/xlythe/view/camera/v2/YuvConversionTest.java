package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.media.FakeImage;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Turning a camera's three planes into the one NV21 buffer the JPEG encoder wants.
 *
 * <p>This arithmetic fails quietly. Reading a luma row at its stride instead of its width shears
 * the picture; taking the chroma planes in the wrong order swaps the colours; ignoring the chroma
 * pixel stride tints it. None of that throws, and the capture still produces a file, so no
 * end-to-end test notices - which is why the planes are built here with known values and padding
 * poisoned, and the result is checked byte for byte.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class YuvConversionTest {
    /** Small enough to assert every byte, big enough to have more than one chroma row. */
    private static final int WIDTH = 4;
    private static final int HEIGHT = 4;

    /** Semi-planar is what most hardware produces: one buffer of V, U, V, U. */
    @Test
    public void semiPlanarChromaIsInterleavedIntoNv21() {
        byte[] nv21 = PictureSession.ImageSaver.YUV_420_888toNV21(
                FakeImage.yuv420(WIDTH, HEIGHT, 0 /* rowPadding */, 2 /* uvPixelStride */));

        assertLumaIsIntact(nv21);
        assertChromaIsInterleaved(nv21);
    }

    /** Planar is the other layout, with the chroma in two separate planes. */
    @Test
    public void planarChromaIsInterleavedIntoNv21() {
        byte[] nv21 = PictureSession.ImageSaver.YUV_420_888toNV21(
                FakeImage.yuv420(WIDTH, HEIGHT, 0 /* rowPadding */, 1 /* uvPixelStride */));

        assertLumaIsIntact(nv21);
        assertChromaIsInterleaved(nv21);
    }

    /**
     * A padded luma plane is read by width, not by stride.
     *
     * <p>Cameras align each row to a hardware-friendly stride, so a 4-pixel row can occupy 6
     * bytes. Copying the stride instead of the width shifts every row a little further along than
     * the last, which is the shear you see on a picture that came out looking like a deck of
     * cards.</p>
     */
    @Test
    public void paddingBetweenLumaRowsIsSkipped() {
        byte[] nv21 = PictureSession.ImageSaver.YUV_420_888toNV21(
                FakeImage.yuv420(WIDTH, HEIGHT, 2 /* rowPadding */, 2 /* uvPixelStride */));

        assertLumaIsIntact(nv21);
        assertChromaIsInterleaved(nv21);
        for (int i = 0; i < WIDTH * HEIGHT + WIDTH * HEIGHT / 2; i++) {
            assertNotEquals("row padding was copied into the image at " + i,
                    FakeImage.POISON, nv21[i]);
        }
    }

    /** Both layouts have to agree, since the difference is the camera's and not the picture's. */
    @Test
    public void bothChromaLayoutsProduceTheSamePicture() {
        byte[] semiPlanar = PictureSession.ImageSaver.YUV_420_888toNV21(
                FakeImage.yuv420(WIDTH, HEIGHT, 0, 2));
        byte[] planar = PictureSession.ImageSaver.YUV_420_888toNV21(
                FakeImage.yuv420(WIDTH, HEIGHT, 0, 1));

        int meaningful = WIDTH * HEIGHT + WIDTH * HEIGHT / 2;
        for (int i = 0; i < meaningful; i++) {
            assertEquals("the two chroma layouts disagree at byte " + i, planar[i], semiPlanar[i]);
        }
    }

    /** The luma comes first, one byte per pixel, in row order and without its padding. */
    private static void assertLumaIsIntact(byte[] nv21) {
        for (int row = 0; row < HEIGHT; row++) {
            for (int col = 0; col < WIDTH; col++) {
                assertEquals("luma at " + row + "," + col,
                        FakeImage.expectedLuma(WIDTH, row, col), nv21[row * WIDTH + col]);
            }
        }
    }

    /** Then the chroma, V before U, which is what the "21" in NV21 means. */
    private static void assertChromaIsInterleaved(byte[] nv21) {
        int at = WIDTH * HEIGHT;
        for (int i = 0; i < (WIDTH / 2) * (HEIGHT / 2); i++) {
            assertEquals("V at chroma pixel " + i, FakeImage.expectedV(i), nv21[at + i * 2]);
            assertEquals("U at chroma pixel " + i, FakeImage.expectedU(i), nv21[at + i * 2 + 1]);
        }
    }
}
