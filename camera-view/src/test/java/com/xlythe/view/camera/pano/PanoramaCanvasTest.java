package com.xlythe.view.camera.pano;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.opengl.Matrix;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Whether frames end up on the sphere where the arithmetic says they should.
 *
 * <p>{@link EquirectangularTest} pins the arithmetic; this pins the painting, which is a
 * different set of mistakes: a frame drawn as a rectangle instead of a mesh, a frame at the
 * back of the sphere lost over the seam, and a crop that does not match what was asked for.
 * Each is checked by painting a frame of one flat colour and asking which canvas pixels
 * changed, because that is the only question with an answer that does not depend on a
 * camera.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PanoramaCanvasTest {
    /** Small, so that the whole canvas can be scanned pixel by pixel in a test. */
    private static final int CANVAS_WIDTH = 512;

    private static final float H_FOV = 60f;
    private static final float V_FOV = 45f;

    private File directory;
    private Bitmap frame;

    @Before
    public void setUp() throws IOException {
        directory = Files.createTempDirectory("panocanvas").toFile();
        frame = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888);
        frame.eraseColor(Color.RED);
    }

    @After
    public void tearDown() {
        if (frame != null && !frame.isRecycled()) {
            frame.recycle();
        }
    }

    private static float[] facing(float yawDegrees, float pitchDegrees) {
        float[] matrix = new float[16];
        Matrix.setIdentityM(matrix, 0);
        Matrix.rotateM(matrix, 0, yawDegrees, 0f, 1f, 0f);
        Matrix.rotateM(matrix, 0, pitchDegrees, 1f, 0f, 0f);
        return matrix;
    }

    /** Which columns of the canvas have anything painted in them. */
    private static boolean[] paintedColumns(Bitmap canvas) {
        boolean[] painted = new boolean[canvas.getWidth()];
        for (int x = 0; x < canvas.getWidth(); x++) {
            for (int y = 0; y < canvas.getHeight(); y++) {
                if (Color.alpha(canvas.getPixel(x, y)) != 0) {
                    painted[x] = true;
                    break;
                }
            }
        }
        return painted;
    }

    @Test
    public void theCanvasIsTwiceAsWideAsItIsTall() {
        PanoramaCanvas canvas = new PanoramaCanvas(CANVAS_WIDTH, PanoramaCoverage.forPanorama());
        try {
            assertEquals(CANVAS_WIDTH, canvas.getWidth());
            assertEquals(CANVAS_WIDTH / 2, canvas.getHeight());
        } finally {
            canvas.recycle();
        }
    }

    @Test
    public void aForwardFrameLandsInTheMiddle() {
        PanoramaCanvas canvas = new PanoramaCanvas(CANVAS_WIDTH, PanoramaCoverage.forPanorama());
        try {
            assertTrue(canvas.draw(frame, facing(0f, 0f), H_FOV, V_FOV));
            assertEquals(1, canvas.getFramesDrawn());

            assertEquals("the middle of the canvas should be painted",
                    Color.RED, canvas.getBitmap().getPixel(CANVAS_WIDTH / 2, CANVAS_WIDTH / 4));
            assertEquals("and the edges should not be",
                    0, Color.alpha(canvas.getBitmap().getPixel(2, CANVAS_WIDTH / 4)));
        } finally {
            canvas.recycle();
        }
    }

    /**
     * A sixty degree lens paints about a sixth of the way round, and not more.
     *
     * <p>The assertion that catches a frame drawn at the wrong scale, which is the mistake that
     * produces a panorama where everything is too big and the sweep never finishes.</p>
     */
    @Test
    public void aFrameCoversItsOwnAngleOfViewAndNoMore() {
        PanoramaCanvas canvas = new PanoramaCanvas(CANVAS_WIDTH, PanoramaCoverage.forPanorama());
        try {
            canvas.draw(frame, facing(0f, 0f), H_FOV, V_FOV);

            boolean[] painted = paintedColumns(canvas.getBitmap());
            int count = 0;
            for (boolean column : painted) {
                if (column) {
                    count++;
                }
            }
            int expected = Math.round(CANVAS_WIDTH * H_FOV / 360f);
            assertEquals("a " + H_FOV + " degree lens should cover " + expected + " columns",
                    expected, count, 4);
        } finally {
            canvas.recycle();
        }
    }

    @Test
    public void turningTheCameraMovesWhereTheFrameLands() {
        PanoramaCanvas canvas = new PanoramaCanvas(CANVAS_WIDTH, PanoramaCoverage.forPanorama());
        try {
            // A quarter turn to the right, which is three quarters across the canvas.
            canvas.draw(frame, facing(-90f, 0f), H_FOV, V_FOV);

            Bitmap bitmap = canvas.getBitmap();
            assertEquals(Color.RED,
                    bitmap.getPixel(CANVAS_WIDTH * 3 / 4, CANVAS_WIDTH / 4));
            assertEquals("the middle should be untouched",
                    0, Color.alpha(bitmap.getPixel(CANVAS_WIDTH / 2, CANVAS_WIDTH / 4)));
        } finally {
            canvas.recycle();
        }
    }

    /**
     * A frame at the back of the sphere arrives at both edges rather than being lost.
     *
     * <p>The canvas is a cylinder cut open, so a frame pointed at the cut is in two pieces. This
     * is the test that fails if it is drawn once: half of it lands off the canvas and the far
     * edge stays empty.</p>
     */
    @Test
    public void aFrameAtTheSeamArrivesAtBothEdges() {
        PanoramaCanvas canvas = new PanoramaCanvas(CANVAS_WIDTH, PanoramaCoverage.forPanorama());
        try {
            canvas.draw(frame, facing(180f, 0f), H_FOV, V_FOV);

            Bitmap bitmap = canvas.getBitmap();
            int middleRow = CANVAS_WIDTH / 4;
            assertEquals("the left edge should have the far half of the frame",
                    Color.RED, bitmap.getPixel(1, middleRow));
            assertEquals("and the right edge the near half",
                    Color.RED, bitmap.getPixel(CANVAS_WIDTH - 2, middleRow));
            assertEquals("the middle of the canvas is the opposite direction and must be empty",
                    0, Color.alpha(bitmap.getPixel(CANVAS_WIDTH / 2, middleRow)));
        } finally {
            canvas.recycle();
        }
    }

    @Test
    public void raisingTheCameraPaintsHigherUp() {
        PanoramaCanvas canvas = new PanoramaCanvas(CANVAS_WIDTH,
                PanoramaCoverage.forPhotosphere());
        try {
            canvas.draw(frame, facing(0f, 45f), H_FOV, V_FOV);

            Bitmap bitmap = canvas.getBitmap();
            int above = CANVAS_WIDTH / 4 - Math.round(CANVAS_WIDTH / 2f * 45f / 180f);
            assertEquals(Color.RED, bitmap.getPixel(CANVAS_WIDTH / 2, above));
            assertEquals("the horizon should be untouched",
                    0, Color.alpha(bitmap.getPixel(CANVAS_WIDTH / 2, CANVAS_WIDTH / 4)));
        } finally {
            canvas.recycle();
        }
    }

    /** An angle of view that cannot be is refused rather than painting a folded frame. */
    @Test
    public void anImpossibleLensPaintsNothing() {
        PanoramaCanvas canvas = new PanoramaCanvas(CANVAS_WIDTH, PanoramaCoverage.forPanorama());
        try {
            assertFalse(canvas.draw(frame, facing(0f, 0f), 0f, V_FOV));
            assertEquals(0, canvas.getFramesDrawn());
        } finally {
            canvas.recycle();
        }
    }

    /** A flat sweep is a band out of the sphere, not the whole thing. */
    @Test
    public void aFlatSweepIsCroppedToItsBand() {
        PanoramaCoverage coverage = PanoramaCoverage.forPanorama();
        coverage.cover(0f, 0f);
        PanoramaCanvas canvas = new PanoramaCanvas(CANVAS_WIDTH, coverage);
        try {
            Rect crop = canvas.cropToCoverage();

            assertEquals("a full turn is the whole width", 0, crop.left);
            assertEquals(CANVAS_WIDTH, crop.right);
            assertTrue("but only a band of the height, got " + crop.height(),
                    crop.height() > 0 && crop.height() < canvas.getHeight() / 4);
        } finally {
            canvas.recycle();
        }
    }

    /** A photosphere is the whole canvas, so there is nothing to crop. */
    @Test
    public void aPhotosphereKeepsTheWholeCanvas() {
        PanoramaCoverage coverage = PanoramaCoverage.forPhotosphere();
        coverage.cover(0f, 0f);
        PanoramaCanvas canvas = new PanoramaCanvas(CANVAS_WIDTH, coverage);
        try {
            Rect crop = canvas.cropToCoverage();

            assertEquals(0, crop.left);
            assertEquals(0, crop.top);
            assertEquals(CANVAS_WIDTH, crop.right);
            assertEquals(canvas.getHeight(), crop.bottom);
        } finally {
            canvas.recycle();
        }
    }

    @Test
    public void whatIsSavedIsAPanoramaAndNotJustAPicture() throws IOException {
        PanoramaCoverage coverage = PanoramaCoverage.forPhotosphere();
        coverage.cover(0f, 0f);
        PanoramaCanvas canvas = new PanoramaCanvas(CANVAS_WIDTH, coverage);
        File file = new File(directory, "sphere.jpg");
        try {
            canvas.draw(frame, facing(0f, 0f), H_FOV, V_FOV);

            assertTrue(canvas.save(file, 90, 12f));

            assertTrue("nothing was written", file.length() > 0);
            Bitmap decoded = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath());
            assertNotNull("the file is not a readable picture", decoded);
            assertEquals(CANVAS_WIDTH, decoded.getWidth());
            assertEquals(CANVAS_WIDTH / 2, decoded.getHeight());
            decoded.recycle();

            String xmp = GPanoMetadata.readXmp(file);
            assertNotNull("a panorama without GPano metadata is just a wide photograph", xmp);
            assertTrue(xmp, xmp.contains("GPano:ProjectionType=\"equirectangular\""));
            assertTrue(xmp, xmp.contains("GPano:FullPanoWidthPixels=\"" + CANVAS_WIDTH + "\""));
            assertTrue(xmp, xmp.contains("GPano:PoseHeadingDegrees=\"12.0\""));
        } finally {
            canvas.recycle();
        }
    }

    /** A cropped band says where it sits, which is what stops a viewer stretching it. */
    @Test
    public void aCroppedSaveRecordsWhereItSits() throws IOException {
        PanoramaCoverage coverage = PanoramaCoverage.forPanorama();
        coverage.cover(0f, 0f);
        PanoramaCanvas canvas = new PanoramaCanvas(CANVAS_WIDTH, coverage);
        File file = new File(directory, "band.jpg");
        try {
            canvas.draw(frame, facing(0f, 0f), H_FOV, V_FOV);
            Rect crop = canvas.cropToCoverage();

            assertTrue(canvas.save(file, 90, Float.NaN));

            String xmp = GPanoMetadata.readXmp(file);
            assertNotNull(xmp);
            assertTrue(xmp, xmp.contains(
                    "GPano:CroppedAreaImageHeightPixels=\"" + crop.height() + "\""));
            assertTrue(xmp, xmp.contains("GPano:CroppedAreaTopPixels=\"" + crop.top + "\""));
            assertTrue("the full canvas is still described",
                    xmp.contains("GPano:FullPanoHeightPixels=\"" + canvas.getHeight() + "\""));
        } finally {
            canvas.recycle();
        }
    }
}
