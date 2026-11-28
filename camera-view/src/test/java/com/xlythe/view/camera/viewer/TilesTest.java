package com.xlythe.view.camera.viewer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import androidx.exifinterface.media.ExifInterface;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.LinkedHashSet;
import java.util.Set;

/** Cutting a picture into tiles, and turning it upright. */
@RunWith(RobolectricTestRunner.class)
public class TilesTest {
    @Test
    public void floorPowerOfTwo() {
        assertEquals(1, Tiles.floorPowerOfTwo(0.3));
        assertEquals(1, Tiles.floorPowerOfTwo(1));
        assertEquals(1, Tiles.floorPowerOfTwo(1.99));
        assertEquals(2, Tiles.floorPowerOfTwo(2));
        assertEquals(4, Tiles.floorPowerOfTwo(7.9));
        assertEquals(1 << 30, Tiles.floorPowerOfTwo(1e12));
    }

    /** The coarsest sample size whose pixels are no larger than the screen's. */
    @Test
    public void sampleSizeKeepsThePictureSharp() {
        assertEquals(1, Tiles.sampleSize(2));
        assertEquals(1, Tiles.sampleSize(1));
        assertEquals(1, Tiles.sampleSize(0.6));
        assertEquals(2, Tiles.sampleSize(0.5));
        assertEquals(8, Tiles.sampleSize(0.07));
        assertEquals(1, Tiles.sampleSize(0));
    }

    @Test
    public void sampleSizeWithinABudget() {
        assertEquals(1, Tiles.sampleSizeWithin(1000, 1000, 1_000_000));
        assertEquals(2, Tiles.sampleSizeWithin(1001, 1000, 1_000_000));
        // A 200 MP picture into a 2.6 MP view.
        assertEquals(16, Tiles.sampleSizeWithin(16384, 12288, 1080L * 2400));
    }

    @Test
    public void decodedPixelsRoundUp() {
        assertEquals(1, Tiles.decodedPixels(1, 1, 4));
        assertEquals(3 * 2, Tiles.decodedPixels(9, 5, 4));
    }

    @Test
    public void keysRoundTrip() {
        long key = Tiles.key(64, 12345, 678);
        assertEquals(64, Tiles.sampleSizeOf(key));
        assertEquals(12345, Tiles.columnOf(key));
        assertEquals(678, Tiles.rowOf(key));
        assertTrue(Tiles.key(1, 0, 1) != Tiles.key(1, 1, 0));
        assertTrue(Tiles.key(2, 0, 0) != Tiles.key(1, 0, 0));
    }

    /** A tile covers 512 decoded pixels a side, cut short at the picture's edge. */
    @Test
    public void regionsAreCutAtTheEdge() {
        assertEquals(new Rect(1024, 0, 1536, 512), Tiles.region(Tiles.key(1, 2, 0), 5000, 3000));
        assertEquals(new Rect(4096, 2048, 5000, 3000),
                Tiles.region(Tiles.key(4, 2, 1), 5000, 3000));
        assertEquals(Tiles.ceilDivide(904, 4) * Tiles.ceilDivide(952, 4),
                Tiles.decodedPixels(Tiles.key(4, 2, 1), 5000, 3000));
    }

    @Test
    public void coveringFindsEveryTileARegionTouches() {
        Set<Long> keys = new LinkedHashSet<>();
        long pixels = Tiles.covering(500, 10, 1030, 520, 1, 2000, 2000, keys);

        // Columns 0 to 2 and rows 0 to 1.
        assertEquals(6, keys.size());
        assertTrue(keys.contains(Tiles.key(1, 2, 1)));
        assertEquals(6L * 512 * 512, pixels);

        // Asked again, nothing new is counted.
        assertEquals(0, Tiles.covering(500, 10, 1030, 520, 1, 2000, 2000, keys));
    }

    /** A region that runs off the picture, or lies outside it, covers only what is inside. */
    @Test
    public void coveringStaysInsideThePicture() {
        Set<Long> keys = new LinkedHashSet<>();
        Tiles.covering(-100, -100, 99999, 99999, 2, 3000, 1500, keys);
        // 3000 / 1024 is 3 columns, 1500 / 1024 is 2 rows.
        assertEquals(6, keys.size());

        keys.clear();
        assertEquals(0, Tiles.covering(4000, 0, 5000, 100, 1, 3000, 1500, keys));
        assertTrue(keys.isEmpty());
    }

    /** Each of Exif's eight orientations, by where the stored corners end up. */
    @Test
    public void orientationsTurnThePictureUpright() {
        int w = 400;
        int h = 300;
        // Stored top-left and bottom-right corners, as an upright rectangle and back.
        assertUpright(ExifInterface.ORIENTATION_NORMAL, w, h, 400, 300);
        assertUpright(ExifInterface.ORIENTATION_UNDEFINED, w, h, 400, 300);
        assertUpright(ExifInterface.ORIENTATION_FLIP_HORIZONTAL, w, h, 400, 300);
        assertUpright(ExifInterface.ORIENTATION_ROTATE_180, w, h, 400, 300);
        assertUpright(ExifInterface.ORIENTATION_FLIP_VERTICAL, w, h, 400, 300);
        assertUpright(ExifInterface.ORIENTATION_TRANSPOSE, w, h, 300, 400);
        assertUpright(ExifInterface.ORIENTATION_ROTATE_90, w, h, 300, 400);
        assertUpright(ExifInterface.ORIENTATION_TRANSVERSE, w, h, 300, 400);
        assertUpright(ExifInterface.ORIENTATION_ROTATE_270, w, h, 300, 400);
    }

    /** A quarter turn clockwise puts the stored top-left at the upright top-right. */
    @Test
    public void aQuarterTurnMovesTheCorners() {
        Orientation rotate90 = Orientation.of(ExifInterface.ORIENTATION_ROTATE_90, 400, 300);
        float[] m = rotate90.toMatrixValues();
        // Stored (0, 0) lands at upright (300, 0).
        assertEquals(300, m[2], 0);
        assertEquals(0, m[5], 0);
        // Upright top-right corner, a 10 pixel square, comes from the stored top-left.
        double[] stored = rotate90.toStored(290, 0, 300, 10);
        assertEquals(0, stored[0], 1e-9);
        assertEquals(0, stored[1], 1e-9);
        assertEquals(10, stored[2], 1e-9);
        assertEquals(10, stored[3], 1e-9);

        Orientation rotate270 = Orientation.of(ExifInterface.ORIENTATION_ROTATE_270, 400, 300);
        // Upright bottom-left comes from the stored top-left.
        stored = rotate270.toStored(0, 390, 10, 400);
        assertEquals(0, stored[0], 1e-9);
        assertEquals(0, stored[1], 1e-9);
    }

    /**
     * Checks that a stored point lands where the matrix says, and that toStored brings an
     * upright rectangle back to the stored one it came from.
     */
    private static void assertUpright(int exif, int width, int height, int uprightWidth,
            int uprightHeight) {
        Orientation orientation = Orientation.of(exif, width, height);
        assertEquals(uprightWidth, orientation.getWidth());
        assertEquals(uprightHeight, orientation.getHeight());
        float[] m = orientation.toMatrixValues();
        // A stored rectangle, mapped upright by the matrix.
        double sx0 = 10;
        double sy0 = 20;
        double sx1 = 50;
        double sy1 = 70;
        double ux0 = m[0] * sx0 + m[1] * sy0 + m[2];
        double uy0 = m[3] * sx0 + m[4] * sy0 + m[5];
        double ux1 = m[0] * sx1 + m[1] * sy1 + m[2];
        double uy1 = m[3] * sx1 + m[4] * sy1 + m[5];
        assertTrue(Math.min(ux0, ux1) >= 0 && Math.max(ux0, ux1) <= uprightWidth);
        assertTrue(Math.min(uy0, uy1) >= 0 && Math.max(uy0, uy1) <= uprightHeight);
        double[] stored = orientation.toStored(Math.min(ux0, ux1), Math.min(uy0, uy1),
                Math.max(ux0, ux1), Math.max(uy0, uy1));
        assertEquals(sx0, stored[0], 1e-6);
        assertEquals(sy0, stored[1], 1e-6);
        assertEquals(sx1, stored[2], 1e-6);
        assertEquals(sy1, stored[3], 1e-6);
    }
}
