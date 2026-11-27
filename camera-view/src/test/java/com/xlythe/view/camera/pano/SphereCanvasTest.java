package com.xlythe.view.camera.pano;

import static com.xlythe.view.camera.pano.SphereMathTest.matrixOf;
import static com.xlythe.view.camera.pano.SphereMathTest.uprightFacing;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/**
 * Where a frame's pixels land on the sphere, and what happens where two frames meet.
 *
 * <p>Placement is checked with frames whose colour says where in the frame a pixel came from: red
 * counts across, green counts down. A canvas pixel's colour then names the frame pixel that was
 * painted there, and that has to be the one the canvas pixel's direction projects to.</p>
 */
public class SphereCanvasTest {
    /** Two pixels to a degree. */
    private static final int WIDTH = 720;

    /** A 256 by 192 frame whose long side spans 60 degrees. */
    private static final int FRAME_WIDTH = 256;
    private static final int FRAME_HEIGHT = 192;
    private static final float FOCAL = (float) (128 / Math.tan(Math.toRadians(30)));

    @Test
    public void eachPixel_landsWhereItLooked() {
        SphereCanvas canvas = new SphereCanvas(WIDTH, 0f);
        CameraPose pose = pose(25f, 10f, 0f);
        int painted = canvas.paint(coordinates(), pose);
        assertTrue(painted > 1000);
        assertPlacedWhereTheyLook(canvas, pose);
    }

    @Test
    public void rolledFrames_landWhereTheyLooked() {
        SphereCanvas canvas = new SphereCanvas(WIDTH, 0f);
        CameraPose pose = pose(-60f, -20f, 35f);
        canvas.paint(coordinates(), pose);
        assertPlacedWhereTheyLook(canvas, pose);
    }

    @Test
    public void aFrameOfThePole_coversEveryYawWithoutTearing() {
        SphereCanvas canvas = new SphereCanvas(WIDTH, 0f);
        CameraPose pose = pose(40f, 90f, 0f);
        canvas.paint(coordinates(), pose);
        // The top rows are a cap round the pole: every column of them is painted, and from
        // the middle of the frame rather than smeared from its edges.
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < WIDTH; x++) {
                assertNotEquals(0, canvas.pixelAt(x, y));
            }
        }
        assertPlacedWhereTheyLook(canvas, pose);
    }

    @Test
    public void aFrameAcrossTheEdge_isPaintedOnBothSides() {
        SphereCanvas canvas = new SphereCanvas(WIDTH, 0f);
        canvas.paint(solid(0xFF808080), pose(180f, 0f, 0f));
        int middle = WIDTH / 4;
        assertNotEquals(0, canvas.pixelAt(0, middle));
        assertNotEquals(0, canvas.pixelAt(WIDTH - 1, middle));
        assertEquals(0, canvas.pixelAt(WIDTH / 2, middle));
        int[] region = canvas.getLastRegion();
        assertTrue(region[0] + region[1] > WIDTH);
    }

    @Test
    public void whereTwoFramesMeet_theyAreCrossFadedHalfway() {
        SphereCanvas canvas = new SphereCanvas(WIDTH, 0f);
        canvas.paint(solid(0xFFC80000), pose(0f, 0f, 0f));
        canvas.paint(solid(0xFF0000C8), pose(20f, 0f, 0f));
        int row = (int) canvas.yOf(0f);
        // Nearer the first frame's centre, the first frame alone.
        assertEquals(0xFFC80000, canvas.pixelAt((int) canvas.xOf(-5f), row));
        // Nearer the second's, the second alone.
        assertEquals(0xFF0000C8, canvas.pixelAt((int) canvas.xOf(25f), row));
        // Halfway, both.
        int seam = canvas.pixelAt((int) canvas.xOf(10f), row);
        assertTrue(ArgbImage.red(seam) > 50 && ArgbImage.blue(seam) > 50);
        // And the change across the seam is gradual: no step bigger than a fraction of it.
        int previous = canvas.pixelAt((int) canvas.xOf(2f), row);
        for (float yaw = 2.5f; yaw <= 18f; yaw += 0.5f) {
            int colour = canvas.pixelAt((int) canvas.xOf(yaw), row);
            assertTrue(Math.abs(ArgbImage.red(colour) - ArgbImage.red(previous)) < 60);
            previous = colour;
        }
    }

    @Test
    public void aLessCentralFrame_doesNotPaintOverAMoreCentralOne() {
        SphereCanvas canvas = new SphereCanvas(WIDTH, 0f);
        canvas.paint(solid(0xFFC80000), pose(0f, 0f, 0f));
        // Its edge reaches the first frame's centre, where the first frame is far more central.
        canvas.paint(solid(0xFF0000C8), pose(22f, 0f, 0f));
        assertEquals(0xFFC80000, canvas.pixelAt((int) canvas.xOf(0f), (int) canvas.yOf(0f)));
    }

    @Test
    public void aDarkerFrame_isBroughtUpToMatch() {
        SphereCanvas canvas = new SphereCanvas(WIDTH, 0f);
        canvas.paint(solid(0xFF646464), pose(0f, 0f, 0f));
        canvas.paint(solid(0xFF505050), pose(18f, 0f, 0f));
        // Where only the second frame reaches, it has been scaled from 80 towards 100.
        int colour = canvas.pixelAt((int) canvas.xOf(36f), (int) canvas.yOf(0f));
        assertEquals(100, ArgbImage.red(colour), 2);
        assertEquals(100, ArgbImage.green(colour), 2);
        assertEquals(100, ArgbImage.blue(colour), 2);
    }

    @Test
    public void aWildlyBrighterFrame_isOnlyScaledSoFar() {
        SphereCanvas canvas = new SphereCanvas(WIDTH, 0f);
        canvas.paint(solid(0xFF202020), pose(0f, 0f, 0f));
        canvas.paint(solid(0xFFC0C0C0), pose(18f, 0f, 0f));
        int colour = canvas.pixelAt((int) canvas.xOf(36f), (int) canvas.yOf(0f));
        assertEquals(128, ArgbImage.red(colour), 2);
    }

    @Test
    public void clippedPixels_sayNothingAboutExposure() {
        SphereCanvas canvas = new SphereCanvas(WIDTH, 0f);
        canvas.paint(solid(0xFFFFFFFF), pose(0f, 0f, 0f));
        canvas.paint(solid(0xFF505050), pose(18f, 0f, 0f));
        int colour = canvas.pixelAt((int) canvas.xOf(36f), (int) canvas.yOf(0f));
        assertEquals(0x50, ArgbImage.red(colour));
    }

    @Test
    public void coveredRegion_ofNothing_isNull() {
        assertNull(new SphereCanvas(WIDTH, 0f).coveredRegion(-1));
    }

    @Test
    public void coveredRegion_ofOneFrame_isItsFootprint() {
        SphereCanvas canvas = new SphereCanvas(WIDTH, 0f);
        canvas.paint(solid(0xFF808080), pose(0f, 0f, 0f));
        int[] region = canvas.coveredRegion(-1);
        // Upright, the frame is 46 degrees across and 60 down, at two pixels a degree.
        assertEquals(92, region[2], 4);
        assertEquals(120, region[3], 4);
        assertEquals(canvas.xOf(-23f), region[0], 3);
        assertEquals(canvas.yOf(30f), region[1], 3);
    }

    @Test
    public void coveredRegion_acrossTheEdge_wraps() {
        SphereCanvas canvas = new SphereCanvas(WIDTH, 0f);
        canvas.paint(solid(0xFF808080), pose(170f, 0f, 0f));
        canvas.paint(solid(0xFF808080), pose(-170f, 0f, 0f));
        int[] region = canvas.coveredRegion(-1);
        assertEquals(canvas.xOf(147f), region[0], 3);
        assertEquals(132, region[2], 6);
    }

    @Test
    public void coveredRegion_allTheWayRound_isTheWholeWidth() {
        SphereCanvas canvas = sweptBand();
        int[] region = canvas.coveredRegion(-1);
        assertEquals(0, region[0]);
        assertEquals(WIDTH, region[2]);
    }

    @Test
    public void coveredRegion_ofAPanorama_isTheBandPaintedAllTheWayAcross() {
        SphereCanvas canvas = sweptBand();
        // One frame higher up, which reaches higher than the rest but only in one place.
        canvas.paint(solid(0xFF808080), pose(0f, 25f, 0f));
        int[] sphere = canvas.coveredRegion(-1);
        int[] band = canvas.coveredRegion((int) canvas.yOf(0f));
        assertTrue(band[1] > sphere[1]);
        assertEquals(WIDTH, band[2]);
        // Every row kept is complete.
        int[] row = new int[WIDTH];
        for (int y = band[1]; y < band[1] + band[3]; y++) {
            canvas.readRow(y, band[0], band[2], row, 0, 0);
            for (int colour : row) {
                assertNotEquals(0, colour);
            }
        }
    }

    @Test
    public void coveredRegion_withNoCompleteRow_keepsEverything() {
        SphereCanvas canvas = new SphereCanvas(WIDTH, 0f);
        canvas.paint(solid(0xFF808080), pose(0f, 0f, 0f));
        canvas.paint(solid(0xFF808080), pose(90f, 0f, 0f));
        int[] sphere = canvas.coveredRegion(-1);
        assertArrayEquals(sphere, canvas.coveredRegion((int) canvas.yOf(0f)));
    }

    @Test
    public void coveredRegion_findsTheBandAwayFromTheSweptRow() {
        SphereCanvas canvas = sweptBand();
        int[] band = canvas.coveredRegion(0);
        assertTrue(band[1] > 0);
        assertEquals(WIDTH, band[2]);
    }

    @Test
    public void readRow_fillsWhatWasNotPainted() {
        SphereCanvas canvas = new SphereCanvas(WIDTH, 0f);
        canvas.paint(solid(0xFF808080), pose(0f, 0f, 0f));
        int[] row = new int[WIDTH];
        canvas.readRow((int) canvas.yOf(0f), 0, WIDTH, row, 0, 0xFF000000);
        assertEquals(0xFF000000, row[0]);
        assertEquals(0xFF808080, row[WIDTH / 2]);
    }

    @Test
    public void thumbnail_followsThePaint() {
        SphereCanvas canvas = new SphereCanvas(WIDTH, 0f);
        int[] thumbnail = new int[72 * 36];
        canvas.paint(solid(0xFF808080), pose(90f, 0f, 0f));
        canvas.updateThumbnail(thumbnail, 72, 36);
        assertEquals(0xFF808080, thumbnail[18 * 72 + 54]);
        assertEquals(0, thumbnail[18 * 72 + 10]);
        assertEquals(0, thumbnail[2 * 72 + 54]);
    }

    @Test
    public void framesPainted_areCounted() {
        SphereCanvas canvas = new SphereCanvas(WIDTH, 0f);
        canvas.paint(solid(0xFF808080), pose(0f, 0f, 0f));
        canvas.paint(solid(0xFF808080), pose(10f, 0f, 0f));
        assertEquals(2, canvas.getFramesPainted());
        canvas.release();
        assertEquals(0, canvas.pixelAt(WIDTH / 2, WIDTH / 4));
    }

    @Test
    public void columnsAndRows_mapToYawAndPitch() {
        SphereCanvas canvas = new SphereCanvas(WIDTH, 30f);
        assertEquals(30.25f, canvas.yawAt(WIDTH / 2), 0.001f);
        assertEquals(-149.75f, canvas.yawAt(0), 0.001f);
        assertEquals(89.75f, canvas.pitchAt(0), 0.001f);
        assertEquals(WIDTH / 2f, canvas.xOf(30f), 0.001f);
        assertEquals(WIDTH / 4f, canvas.yOf(0f), 0.001f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void oddWidth_isRefused() {
        new SphereCanvas(721, 0f);
    }

    @Test
    public void blendAndScale_workChannelByChannel() {
        assertEquals(0xFF7F7F7F, SphereCanvas.blend(0xFF000000, 0xFFFFFFFF, 128));
        assertEquals(0xFF000000, SphereCanvas.blend(0xFF000000, 0xFFFFFFFF, 0));
        assertEquals(0xFF20FF40, SphereCanvas.scale(0xFF10A020, 512, 512, 512));
    }

    @Test(expected = IllegalArgumentException.class)
    public void anImageWithTooFewPixels_isRefused() {
        new ArgbImage(new int[10], 4, 4);
    }

    /** A canvas swept all the way round at the horizon, a frame every twenty degrees. */
    private static SphereCanvas sweptBand() {
        SphereCanvas canvas = new SphereCanvas(WIDTH, 0f);
        for (int yaw = -180; yaw < 180; yaw += 20) {
            canvas.paint(solid(0xFF808080), pose(yaw, 0f, 0f));
        }
        return canvas;
    }

    /**
     * Checks every painted canvas pixel against the frame pixel its direction projects to, whose
     * coordinates the frame's colour spells out.
     */
    private static void assertPlacedWhereTheyLook(SphereCanvas canvas, CameraPose pose) {
        float[] direction = new float[3];
        float[] point = new float[2];
        int checked = 0;
        for (int y = 0; y < canvas.height; y++) {
            for (int x = 0; x < canvas.width; x++) {
                int colour = canvas.pixelAt(x, y);
                if (colour == 0) {
                    continue;
                }
                SphereMath.direction(canvas.yawAt(x), canvas.pitchAt(y), direction);
                assertTrue(pose.project(direction, point));
                assertEquals(point[0] - 0.5f, ArgbImage.red(colour), 1.5f);
                assertEquals(point[1] - 0.5f, ArgbImage.green(colour), 1.5f);
                checked++;
            }
        }
        assertTrue(checked > 1000);
    }

    /** A frame whose red is its column and green its row, so a colour names a position. */
    private static ArgbImage coordinates() {
        int[] pixels = new int[FRAME_WIDTH * FRAME_HEIGHT];
        for (int y = 0; y < FRAME_HEIGHT; y++) {
            for (int x = 0; x < FRAME_WIDTH; x++) {
                pixels[y * FRAME_WIDTH + x] = 0xFF000000 | (x << 16) | (y << 8);
            }
        }
        return new ArgbImage(pixels, FRAME_WIDTH, FRAME_HEIGHT);
    }

    private static ArgbImage solid(int colour) {
        int[] pixels = new int[FRAME_WIDTH * FRAME_HEIGHT];
        Arrays.fill(pixels, colour);
        return new ArgbImage(pixels, FRAME_WIDTH, FRAME_HEIGHT);
    }

    /** A back camera, held upright, aimed at a yaw and pitch and rolled about its lens. */
    static CameraPose pose(float yaw, float pitch, float roll) {
        return new CameraPose(
                matrixOf(FootprintTest.rolled(uprightFacing(yaw, pitch), roll)), 90, false,
                FRAME_WIDTH, FRAME_HEIGHT, FOCAL);
    }
}
