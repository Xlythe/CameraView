package com.xlythe.view.camera.pano;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;

/**
 * Whether a panorama can be read back as the panorama it was written as.
 *
 * <p>Half of these go through a real file, because the round trip is the claim worth making:
 * what {@link GPanoMetadata} writes is what {@link GPanoBounds} reads, and a disagreement
 * between the two would show as a panorama displayed wrongly with nothing in either half
 * looking obviously broken.</p>
 *
 * <p>The other half is the awkward input - a packet with pieces missing, a projection this
 * cannot show, an ordinary photograph - because a viewer is handed those and has to say so
 * rather than wrapping a snapshot round a sphere.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class GPanoBoundsTest {
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final float TOLERANCE = 0.01f;

    private File directory;

    @Before
    public void setUp() throws IOException {
        directory = Files.createTempDirectory("gpanobounds").toFile();
    }

    private File jpeg(String name) throws IOException {
        File file = new File(directory, name);
        Bitmap bitmap = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888);
        try (OutputStream out = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out));
        } finally {
            bitmap.recycle();
        }
        return file;
    }

    /** The round trip: what was written is what is read. */
    @Test
    public void aFullSphereComesBackAsOne() throws IOException {
        File file = jpeg("sphere.jpg");
        assertTrue(GPanoMetadata.forFullSphere(4000, 2000, 37.5f).writeTo(file));

        GPanoBounds bounds = GPanoBounds.from(file);

        assertNotNull("what was written as a panorama did not read back as one", bounds);
        assertEquals(4000, bounds.getImageWidth());
        assertEquals(2000, bounds.getImageHeight());
        assertEquals(4000, bounds.getFullWidth());
        assertEquals(2000, bounds.getFullHeight());
        assertEquals(0, bounds.getLeft());
        assertEquals(0, bounds.getTop());
        assertEquals(37.5f, bounds.getHeadingDegrees(), TOLERANCE);
        assertTrue(bounds.isFullSphere());
    }

    @Test
    public void aCroppedBandComesBackWhereItWas() throws IOException {
        File file = jpeg("band.jpg");
        assertTrue(new GPanoMetadata(1000, 400, 4000, 2000, 1500, 800, Float.NaN).writeTo(file));

        GPanoBounds bounds = GPanoBounds.from(file);

        assertNotNull(bounds);
        assertEquals(1000, bounds.getImageWidth());
        assertEquals(400, bounds.getImageHeight());
        assertEquals(1500, bounds.getLeft());
        assertEquals(800, bounds.getTop());
        assertFalse(bounds.isFullSphere());
        assertTrue("an unknown heading stays unknown",
                Float.isNaN(bounds.getHeadingDegrees()));
    }

    /** A full sphere covers everything, which is the easy case to get the arithmetic wrong on. */
    @Test
    public void aFullSphereCoversTheWholeSphere() {
        GPanoBounds bounds = GPanoBounds.forFullSphere(4000, 2000);

        assertEquals(-180f, bounds.getLeftYaw(), TOLERANCE);
        assertEquals(360f, bounds.getYawSpan(), TOLERANCE);
        assertEquals(90f, bounds.getTopPitch(), TOLERANCE);
        assertEquals(180f, bounds.getPitchSpan(), TOLERANCE);
    }

    /** A band says which slice of the sphere it is, in degrees. */
    @Test
    public void aBandSaysWhichSliceItIs() {
        // A quarter of the width, starting a quarter in, and a tenth of the height centred.
        GPanoBounds bounds = GPanoBounds.fromXmp(xmp(1000, 200, 4000, 2000, 1000, 900));

        assertNotNull(bounds);
        assertEquals("a quarter in is a quarter turn west of the back",
                -90f, bounds.getLeftYaw(), TOLERANCE);
        assertEquals(90f, bounds.getYawSpan(), TOLERANCE);
        assertEquals("nine tenths of the way down from the pole is nine degrees above the horizon",
                9f, bounds.getTopPitch(), TOLERANCE);
        assertEquals(18f, bounds.getPitchSpan(), TOLERANCE);
    }

    /**
     * A direction in the middle of the picture samples the middle of the texture.
     *
     * <p>This is what a renderer does per pixel, so it is the assertion that a crop is handled
     * rather than ignored: the middle of a band that sits off to one side is not the middle of
     * the sphere.</p>
     */
    @Test
    public void theMiddleOfABandSamplesTheMiddleOfTheTexture() {
        GPanoBounds bounds = GPanoBounds.fromXmp(xmp(1000, 200, 4000, 2000, 1000, 900));
        assertNotNull(bounds);
        float[] at = new float[2];

        // The middle of that band is a quarter turn west plus half of ninety degrees.
        bounds.textureCoordinatesFor(-45f, 0f, at);

        assertEquals(0.5f, at[0], TOLERANCE);
        assertEquals(0.5f, at[1], TOLERANCE);
    }

    /** A direction outside the picture reports as outside, which is the cue to draw nothing. */
    @Test
    public void aDirectionOutsideTheBandIsOutsideTheTexture() {
        GPanoBounds bounds = GPanoBounds.fromXmp(xmp(1000, 200, 4000, 2000, 1000, 900));
        assertNotNull(bounds);
        float[] at = new float[2];

        bounds.textureCoordinatesFor(90f, 0f, at);
        assertTrue("a direction behind the band should fall outside it, got " + at[0],
                at[0] < 0f || at[0] > 1f);

        bounds.textureCoordinatesFor(-45f, 80f, at);
        assertTrue("the sky is not in a band at eye level, got " + at[1],
                at[1] < 0f || at[1] > 1f);
    }

    @Test
    public void theCornersOfAFullSphereAreTheCornersOfTheTexture() {
        GPanoBounds bounds = GPanoBounds.forFullSphere(4000, 2000);
        float[] at = new float[2];

        bounds.textureCoordinatesFor(-180f, 90f, at);
        assertEquals(0f, at[0], TOLERANCE);
        assertEquals(0f, at[1], TOLERANCE);

        bounds.textureCoordinatesFor(0f, 0f, at);
        assertEquals(0.5f, at[0], TOLERANCE);
        assertEquals(0.5f, at[1], TOLERANCE);
    }

    /** An ordinary photograph is not a panorama, and says so. */
    @Test
    public void aPictureWithNoPacketIsNotAPanorama() throws IOException {
        assertNull(GPanoBounds.from(jpeg("ordinary.jpg")));
        assertNull(GPanoBounds.fromXmp(null));
    }

    /** A packet from some other tool, with no dimensions, cannot say where the picture sits. */
    @Test
    public void aPacketWithNoDimensionsIsRefused() {
        assertNull(GPanoBounds.fromXmp(
                "<rdf:Description GPano:ProjectionType=\"equirectangular\" />"));
    }

    /** A projection this cannot show is refused rather than shown as if it were a sphere. */
    @Test
    public void aProjectionThatIsNotEquirectangularIsRefused() {
        assertNull(GPanoBounds.fromXmp(
                "<rdf:Description GPano:ProjectionType=\"cylindrical\""
                        + " GPano:FullPanoWidthPixels=\"4000\""
                        + " GPano:FullPanoHeightPixels=\"2000\" />"));
    }

    /** A packet that names no projection but gives dimensions is taken at its word. */
    @Test
    public void aPacketWithNoProjectionIsAssumedEquirectangular() {
        GPanoBounds bounds = GPanoBounds.fromXmp(
                "<rdf:Description GPano:FullPanoWidthPixels=\"4000\""
                        + " GPano:FullPanoHeightPixels=\"2000\" />");

        assertNotNull(bounds);
        assertEquals("with no crop given, the picture is the whole canvas",
                4000, bounds.getImageWidth());
        assertTrue(bounds.isFullSphere());
    }

    /** A number that is not a number falls back rather than throwing out of a viewer. */
    @Test
    public void aPropertyThatIsNotANumberIsIgnored() {
        GPanoBounds bounds = GPanoBounds.fromXmp(
                "<rdf:Description GPano:ProjectionType=\"equirectangular\""
                        + " GPano:FullPanoWidthPixels=\"4000\""
                        + " GPano:FullPanoHeightPixels=\"2000\""
                        + " GPano:CroppedAreaLeftPixels=\"halfway\""
                        + " GPano:PoseHeadingDegrees=\"north\" />");

        assertNotNull(bounds);
        assertEquals("an unreadable offset is no offset", 0, bounds.getLeft());
        assertTrue(Float.isNaN(bounds.getHeadingDegrees()));
    }

    /** Whitespace around a value is ordinary in XML and must not break the parse. */
    @Test
    public void whitespaceAroundAValueIsAllowed() {
        GPanoBounds bounds = GPanoBounds.fromXmp(
                "<rdf:Description GPano:ProjectionType = \"equirectangular\""
                        + " GPano:FullPanoWidthPixels = \" 4000 \""
                        + " GPano:FullPanoHeightPixels=\"2000\" />");

        assertNotNull(bounds);
        assertEquals(4000, bounds.getFullWidth());
    }

    /** A packet as the writer would produce it, so these tests read what it really writes. */
    private static String xmp(int imageWidth, int imageHeight, int fullWidth, int fullHeight,
            int left, int top) {
        return new String(new GPanoMetadata(imageWidth, imageHeight, fullWidth, fullHeight,
                left, top, Float.NaN).toXmpPacket(), UTF_8);
    }
}
