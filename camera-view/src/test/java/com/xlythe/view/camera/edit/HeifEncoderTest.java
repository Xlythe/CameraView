package com.xlythe.view.camera.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.Image;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.nio.ByteBuffer;

/**
 * How a picture is cut into tiles and turned into YUV for the encoder. The encoding itself
 * needs a device's codec, and the device suite covers it.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 37)
public class HeifEncoderTest {
    @Test
    public void aSmallEvenPictureIsOneTile() {
        assertGrid(HeifEncoder.gridFor(512, 384), 512, 384, 1, 1);
        assertFalse(HeifEncoder.gridFor(512, 384).isGrid());
    }

    @Test
    public void aLargePictureIsCutIntoEvenTilesThatCoverIt() {
        HeifEncoder.Grid grid = HeifEncoder.gridFor(4000, 3000);
        assertTrue(grid.isGrid());
        assertEquals(8, grid.columns);
        assertEquals(6, grid.rows);
        assertCovers(grid, 4000, 3000);
        // Just over a tile is two tiles of about half, not a tile and a sliver.
        assertGrid(HeifEncoder.gridFor(513, 200), 258, 200, 2, 1);
        assertCovers(HeifEncoder.gridFor(4001, 2999), 4001, 2999);
    }

    @Test
    public void anOddSideIsCutInTwoSoTheTilesAreEven() {
        assertGrid(HeifEncoder.gridFor(301, 200), 152, 200, 2, 1);
        assertGrid(HeifEncoder.gridFor(300, 201), 300, 102, 1, 2);
        assertGrid(HeifEncoder.gridFor(1, 1), 2, 2, 2, 1);
        assertCovers(HeifEncoder.gridFor(511, 511), 511, 511);
    }

    @Test
    public void aTileRepeatsTheEdgeWhereItReachesPastIt() {
        Bitmap bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888);
        bitmap.setPixel(0, 0, Color.RED);
        bitmap.setPixel(1, 0, Color.GREEN);
        bitmap.setPixel(2, 0, Color.BLUE);
        bitmap.setPixel(0, 1, Color.WHITE);
        bitmap.setPixel(1, 1, Color.BLACK);
        bitmap.setPixel(2, 1, Color.YELLOW);
        int[] pixels = new int[4 * 3];
        HeifEncoder.readTile(bitmap, 0, 0, 4, 3, pixels);
        assertEquals(Color.RED, pixels[0]);
        assertEquals(Color.BLUE, pixels[2]);
        assertEquals("the last column repeats", Color.BLUE, pixels[3]);
        assertEquals(Color.YELLOW, pixels[4 + 3]);
        assertEquals("the last row repeats", Color.WHITE, pixels[8]);
        assertEquals(Color.YELLOW, pixels[11]);

        // A tile wholly to the right of a one pixel wide picture repeats its column.
        Bitmap narrow = Bitmap.createBitmap(1, 2, Bitmap.Config.ARGB_8888);
        narrow.setPixel(0, 0, Color.CYAN);
        narrow.setPixel(0, 1, Color.MAGENTA);
        int[] past = new int[2 * 2];
        HeifEncoder.readTile(narrow, 2, 0, 2, 2, past);
        assertEquals(Color.CYAN, past[0]);
        assertEquals(Color.CYAN, past[1]);
        assertEquals(Color.MAGENTA, past[3]);
    }

    @Test
    public void pixelsBecomeFullRangeYuvWithAveragedColour() {
        // Left half red, right half blue, in a 4x2 tile: two chroma samples.
        int[] argb = {Color.RED, Color.RED, Color.BLUE, Color.BLUE,
                Color.RED, Color.RED, Color.BLUE, Color.BLUE};
        ByteBuffer y = ByteBuffer.allocate(8 * 2);
        ByteBuffer u = ByteBuffer.allocate(4 * 1);
        ByteBuffer v = ByteBuffer.allocate(4 * 1);
        // Luma packed with a wider row; chroma interleaved, as semi-planar layouts are.
        Image image = image(plane(y, 8, 1), plane(u, 4, 2), plane(v, 4, 2));
        HeifEncoder.writeYuv(argb, 4, 2, image);

        assertEquals(76, y.get(0) & 0xFF);
        assertEquals(29, y.get(2) & 0xFF);
        assertEquals("the second row starts a stride on", 76, y.get(8) & 0xFF);
        // Red pulls U down and V up; blue the other way.
        assertEquals(85, u.get(0) & 0xFF);
        assertEquals(255, v.get(0) & 0xFF);
        assertEquals(255, u.get(2) & 0xFF);
        assertEquals(107, v.get(2) & 0xFF);
    }

    private static Image image(Image.Plane... planes) {
        Image image = mock(Image.class);
        when(image.getPlanes()).thenReturn(planes);
        return image;
    }

    private static Image.Plane plane(ByteBuffer buffer, int rowStride, int pixelStride) {
        Image.Plane plane = mock(Image.Plane.class);
        when(plane.getBuffer()).thenReturn(buffer);
        when(plane.getRowStride()).thenReturn(rowStride);
        when(plane.getPixelStride()).thenReturn(pixelStride);
        return plane;
    }

    private static void assertGrid(HeifEncoder.Grid grid, int tileWidth, int tileHeight,
            int columns, int rows) {
        assertEquals("tile width", tileWidth, grid.tileWidth);
        assertEquals("tile height", tileHeight, grid.tileHeight);
        assertEquals("columns", columns, grid.columns);
        assertEquals("rows", rows, grid.rows);
    }

    private static void assertCovers(HeifEncoder.Grid grid, int width, int height) {
        assertTrue(grid.tileWidth * grid.columns >= width);
        assertTrue(grid.tileHeight * grid.rows >= height);
        assertTrue("no column wholly past the edge", grid.tileWidth * (grid.columns - 1) < width);
        assertTrue("no row wholly past the edge", grid.tileHeight * (grid.rows - 1) < height);
        assertEquals(0, grid.tileWidth % 2);
        assertEquals(0, grid.tileHeight % 2);
        assertTrue(grid.tileWidth <= HeifEncoder.TILE);
        assertTrue(grid.tileHeight <= HeifEncoder.TILE);
    }
}
