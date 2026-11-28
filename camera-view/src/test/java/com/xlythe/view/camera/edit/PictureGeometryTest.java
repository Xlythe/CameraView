package com.xlythe.view.camera.edit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * Where pixels land. The picture is 400x200, wider than tall, so every quarter turn shows in
 * its size, and the corners are followed one at a time so a mirror shows as well.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 37)
public class PictureGeometryTest {
    private static final int WIDTH = 400;
    private static final int HEIGHT = 200;

    @Test
    public void eachOrientationStandsThePictureUpright() {
        // Where the stored top left corner lands, for each turn after an optional mirror.
        assertPoint(0, 0, PictureGeometry.orientationMatrix(WIDTH, HEIGHT, 0, false), 0, 0);
        assertPoint(WIDTH, 0, PictureGeometry.orientationMatrix(WIDTH, HEIGHT, 0, true), 0, 0);
        assertPoint(HEIGHT, 0, PictureGeometry.orientationMatrix(WIDTH, HEIGHT, 90, false), 0, 0);
        assertPoint(WIDTH, HEIGHT,
                PictureGeometry.orientationMatrix(WIDTH, HEIGHT, 180, false), 0, 0);
        assertPoint(0, WIDTH, PictureGeometry.orientationMatrix(WIDTH, HEIGHT, 270, false), 0, 0);
        // Mirrored and then turned a quarter, Exif's transverse: the top left goes to the
        // bottom right, and the top right to the top right.
        assertPoint(HEIGHT, WIDTH, PictureGeometry.orientationMatrix(WIDTH, HEIGHT, 90, true),
                0, 0);
        assertPoint(HEIGHT, 0, PictureGeometry.orientationMatrix(WIDTH, HEIGHT, 90, true),
                WIDTH, 0);
        // Mirrored and turned three quarters, Exif's transpose: the top left stays.
        assertPoint(0, 0, PictureGeometry.orientationMatrix(WIDTH, HEIGHT, 270, true), 0, 0);
        assertPoint(0, WIDTH, PictureGeometry.orientationMatrix(WIDTH, HEIGHT, 270, true),
                WIDTH, 0);
        assertPoint(0, HEIGHT, PictureGeometry.orientationMatrix(WIDTH, HEIGHT, 180, true), 0, 0);
        assertPoint(WIDTH, HEIGHT,
                PictureGeometry.orientationMatrix(WIDTH, HEIGHT, -180, false), 0, 0);
    }

    @Test
    public void aQuarterTurnSwapsTheSides() {
        assertTrue(PictureGeometry.isTransposed(90));
        assertTrue(PictureGeometry.isTransposed(-90));
        assertFalse(PictureGeometry.isTransposed(180));
        assertFalse(PictureGeometry.isTransposed(360));

        PictureGeometry turned = geometry(0, false, new PictureEdits.Builder()
                .setRotationDegrees(90).build());
        assertEquals(HEIGHT, turned.getOutputWidth());
        assertEquals(WIDTH, turned.getOutputHeight());
        assertEquals(WIDTH, turned.getUprightWidth());
        assertEquals(HEIGHT, turned.getUprightHeight());
    }

    @Test
    public void anOrientedPictureIsEditedAsItIsSeen() {
        // Stored on its side, stood up by a quarter turn: 200 wide and 400 tall as seen.
        PictureGeometry geometry = geometry(90, false, new PictureEdits.Builder().build());
        assertEquals(HEIGHT, geometry.getUprightWidth());
        assertEquals(WIDTH, geometry.getUprightHeight());
        assertEquals(HEIGHT, geometry.getOutputWidth());
        assertEquals(WIDTH, geometry.getOutputHeight());
        assertPoint(HEIGHT, 0, geometry.storedToOutput(), 0, 0);
    }

    @Test
    public void flipsMirrorThePictureAsTurned() {
        PictureEdits edits = new PictureEdits.Builder()
                .setRotationDegrees(90)
                .setFlippedHorizontally(true)
                .build();
        // Turned, the stored top left is at the top right; mirrored, it is back at the left.
        assertPoint(0, 0, geometry(0, false, edits).storedToOutput(), 0, 0);

        PictureEdits vertical = new PictureEdits.Builder().setFlippedVertically(true).build();
        assertPoint(0, HEIGHT, geometry(0, false, vertical).storedToOutput(), 0, 0);
    }

    @Test
    public void aCropIsAPartOfThePictureAsTurned() {
        PictureEdits edits = new PictureEdits.Builder()
                .setRotationDegrees(90)
                .setCrop(new RectF(0f, 0.5f, 1f, 1f))
                .build();
        PictureGeometry geometry = geometry(0, false, edits);
        assertEquals(HEIGHT, geometry.getOutputWidth());
        assertEquals(WIDTH / 2, geometry.getOutputHeight());
        // Turned clockwise, the bottom half as seen is the right half as stored.
        assertEquals(new Rect(WIDTH / 2 - 1, 0, WIDTH, HEIGHT), geometry.storedRegion());
        assertPoint(HEIGHT, 0, geometry.storedToOutput(), WIDTH / 2f, 0);
    }

    @Test
    public void straighteningTurnsAboutTheMiddleWithinTheFrame() {
        PictureEdits edits = new PictureEdits.Builder().setStraightenDegrees(10).build();
        PictureGeometry geometry = geometry(0, false, edits);
        assertEquals(WIDTH, geometry.getOutputWidth());
        assertEquals(HEIGHT, geometry.getOutputHeight());
        assertPoint(WIDTH / 2f, HEIGHT / 2f, geometry.storedToOutput(), WIDTH / 2f, HEIGHT / 2f);
        // The whole picture is needed: its turned corners reach every edge.
        assertEquals(new Rect(0, 0, WIDTH, HEIGHT), geometry.storedRegion());
        float[] corner = {0, 0};
        geometry.storedToOutput().mapPoints(corner);
        assertTrue("the top left moves right as it turns clockwise", corner[0] > 0);
    }

    @Test
    public void aSmallCropNeedsOnlyItsPartOfThePicture() {
        PictureEdits edits = new PictureEdits.Builder()
                .setCrop(new RectF(0.25f, 0.25f, 0.5f, 0.5f))
                .build();
        PictureGeometry geometry = geometry(0, false, edits);
        assertEquals(new Rect(99, 49, 201, 101), geometry.storedRegion());
        assertEquals(100, geometry.getOutputWidth());
        assertEquals(50, geometry.getOutputHeight());
    }

    @Test
    public void aCropOfOnlyEmptyCornersStillNeedsAPixel() {
        PictureEdits edits = new PictureEdits.Builder()
                .setStraightenDegrees(45)
                .setCrop(new RectF(0f, 0f, 0.01f, 0.01f))
                .build();
        PictureGeometry geometry = new PictureGeometry(1000, 10, 0, false, edits);
        Rect region = geometry.storedRegion();
        assertFalse(region.isEmpty());
    }

    @Test
    public void turnsAndFlipsComeBackToAnOrientation() {
        // Four quarter turns are none.
        int[] orientation = {0, 0};
        for (int i = 0; i < 4; i++) {
            orientation = PictureGeometry.combine(orientation[0], orientation[1] == 1, 90, false,
                    false);
        }
        assertArrayEquals(new int[] {0, 0}, orientation);
        // Two flips the same way are none.
        assertArrayEquals(new int[] {0, 0}, PictureGeometry.combine(0, true, 0, true, false));
        // Both flips are a half turn.
        assertArrayEquals(new int[] {180, 0}, PictureGeometry.combine(0, false, 0, true, true));
        // A vertical flip is a mirror and a half turn.
        assertArrayEquals(new int[] {180, 1}, PictureGeometry.combine(0, false, 0, false, true));
        // Stored on its side (Exif 6), turned another quarter: upside down (Exif 3).
        assertArrayEquals(new int[] {180, 0}, PictureGeometry.combine(90, false, 90, false,
                false));
        // Stored on its side and mirrored as seen: Exif 5, transpose.
        assertArrayEquals(new int[] {270, 1}, PictureGeometry.combine(90, false, 0, true,
                false));
        // A mirror then a turn, mirrored again: just the turn the other way.
        assertArrayEquals(new int[] {270, 0}, PictureGeometry.combine(90, true, 0, true, false));
    }

    @Test
    public void combiningAgreesWithTheMatrices() {
        // For every orientation and every edit of turns and flips, the matrix of the combined
        // orientation moves the corners where the two matrices one after the other do.
        for (int rotation = 0; rotation < 360; rotation += 90) {
            for (int mirror = 0; mirror < 2; mirror++) {
                for (int turn = 0; turn < 360; turn += 90) {
                    for (int flips = 0; flips < 4; flips++) {
                        PictureEdits edits = new PictureEdits.Builder()
                                .setRotationDegrees(turn)
                                .setFlippedHorizontally((flips & 1) != 0)
                                .setFlippedVertically((flips & 2) != 0)
                                .build();
                        Matrix applied = geometry(rotation, mirror == 1, edits).storedToOutput();
                        int[] combined = PictureGeometry.combine(rotation, mirror == 1, turn,
                                (flips & 1) != 0, (flips & 2) != 0);
                        Matrix expected = PictureGeometry.orientationMatrix(WIDTH, HEIGHT,
                                combined[0], combined[1] == 1);
                        float[] a = {0, 0, WIDTH, 0, 0, HEIGHT};
                        float[] b = a.clone();
                        applied.mapPoints(a);
                        expected.mapPoints(b);
                        assertArrayEquals(rotation + " " + mirror + " " + turn + " " + flips,
                                b, a, 0.01f);
                    }
                }
            }
        }
    }

    private static PictureGeometry geometry(int rotation, boolean mirrored, PictureEdits edits) {
        return new PictureGeometry(WIDTH, HEIGHT, rotation, mirrored, edits);
    }

    private static void assertPoint(float expectedX, float expectedY, Matrix matrix, float x,
            float y) {
        float[] point = {x, y};
        matrix.mapPoints(point);
        assertEquals("x of (" + x + ", " + y + ")", expectedX, point[0], 0.01f);
        assertEquals("y of (" + x + ", " + y + ")", expectedY, point[1], 0.01f);
    }
}
