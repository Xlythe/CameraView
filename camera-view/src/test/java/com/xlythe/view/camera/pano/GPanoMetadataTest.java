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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;

/**
 * Whether a stitched picture comes out as a panorama rather than a wide photograph.
 *
 * <p>All of it is about a file nobody looks at by hand. The pixels can be perfect and the file
 * still opens as a snapshot, because what makes a viewer pan around it is an XMP packet most
 * image tools will not show you - so the assertions here are that the packet says what the
 * specification requires, that it is placed where a reader will find it, and that putting it
 * there leaves the picture itself readable.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class GPanoMetadataTest {
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private File directory;

    @Before
    public void setUp() throws IOException {
        directory = Files.createTempDirectory("gpano").toFile();
    }

    /** A real JPEG, so that the segment walking has something true to walk. */
    private File jpeg(int width, int height) throws IOException {
        File file = new File(directory, "pano-" + width + "x" + height + ".jpg");
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try (OutputStream out = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out));
        } finally {
            bitmap.recycle();
        }
        return file;
    }

    @Test
    public void theRequiredPropertiesAreAllThere() {
        String xmp = new String(GPanoMetadata.forFullSphere(4000, 2000, 0f).toXmpPacket(), UTF_8);

        // The seven the specification requires. A viewer that finds the namespace and not these
        // shows the picture flat, which is the failure this list exists to prevent.
        assertTrue(xmp, xmp.contains("GPano:ProjectionType=\"equirectangular\""));
        assertTrue(xmp, xmp.contains("GPano:CroppedAreaImageWidthPixels=\"4000\""));
        assertTrue(xmp, xmp.contains("GPano:CroppedAreaImageHeightPixels=\"2000\""));
        assertTrue(xmp, xmp.contains("GPano:FullPanoWidthPixels=\"4000\""));
        assertTrue(xmp, xmp.contains("GPano:FullPanoHeightPixels=\"2000\""));
        assertTrue(xmp, xmp.contains("GPano:CroppedAreaLeftPixels=\"0\""));
        assertTrue(xmp, xmp.contains("GPano:CroppedAreaTopPixels=\"0\""));
    }

    @Test
    public void thePacketNamesTheNamespaceAndWrapsItself() {
        String xmp = new String(GPanoMetadata.forFullSphere(4000, 2000, 0f).toXmpPacket(), UTF_8);

        assertTrue(xmp, xmp.contains("xmlns:GPano=\"http://ns.google.com/photos/1.0/panorama/\""));
        assertTrue("an XMP packet is wrapped in xpacket markers", xmp.startsWith("<?xpacket begin="));
        assertTrue(xmp.endsWith("<?xpacket end=\"w\"?>"));
        assertTrue(xmp, xmp.contains("<rdf:Description rdf:about=\"\""));
    }

    /**
     * A partial sweep says where in the sphere it sits, which is what stops it being stretched.
     *
     * <p>The whole reason the Cropped and Full pairs exist. A sweep that went a quarter of the
     * way round is a quarter-width crop of a full canvas; a viewer told only its own dimensions
     * wraps that quarter all the way round the sphere.</p>
     */
    @Test
    public void aPartialSweepSaysWhereItSitsInTheSphere() {
        String xmp = new String(
                new GPanoMetadata(1000, 400, 4000, 2000, 1500, 800, Float.NaN).toXmpPacket(),
                UTF_8);

        assertTrue(xmp, xmp.contains("GPano:CroppedAreaImageWidthPixels=\"1000\""));
        assertTrue(xmp, xmp.contains("GPano:CroppedAreaImageHeightPixels=\"400\""));
        assertTrue(xmp, xmp.contains("GPano:FullPanoWidthPixels=\"4000\""));
        assertTrue(xmp, xmp.contains("GPano:FullPanoHeightPixels=\"2000\""));
        assertTrue(xmp, xmp.contains("GPano:CroppedAreaLeftPixels=\"1500\""));
        assertTrue(xmp, xmp.contains("GPano:CroppedAreaTopPixels=\"800\""));
    }

    /** A heading is written when known and left out when not, rather than written as zero. */
    @Test
    public void theHeadingIsOptional() {
        String withHeading =
                new String(GPanoMetadata.forFullSphere(4000, 2000, 123.4f).toXmpPacket(), UTF_8);
        String without =
                new String(GPanoMetadata.forFullSphere(4000, 2000, Float.NaN).toXmpPacket(), UTF_8);

        assertTrue(withHeading, withHeading.contains("GPano:PoseHeadingDegrees=\"123.4\""));
        assertFalse("an unknown heading is not a heading of zero",
                without.contains("PoseHeadingDegrees"));
    }

    @Test
    public void thePacketReachesTheFile() throws IOException {
        File file = jpeg(64, 32);

        assertTrue(GPanoMetadata.forFullSphere(4000, 2000, 90f).writeTo(file));

        String xmp = GPanoMetadata.readXmp(file);
        assertNotNull("the segment was not found after writing it", xmp);
        assertTrue(xmp, xmp.contains("GPano:ProjectionType=\"equirectangular\""));
        assertTrue(xmp, xmp.contains("GPano:PoseHeadingDegrees=\"90.0\""));
    }

    /** The picture still decodes, which is the thing inserting a segment could easily break. */
    @Test
    public void thePictureSurvivesBeingWrittenInto() throws IOException {
        File file = jpeg(64, 32);
        long before = file.length();

        assertTrue(GPanoMetadata.forFullSphere(64, 32, 0f).writeTo(file));

        assertTrue("the file should have grown by the segment", file.length() > before);
        Bitmap decoded = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath());
        assertNotNull("the picture no longer decodes", decoded);
        assertEquals(64, decoded.getWidth());
        assertEquals(32, decoded.getHeight());
        decoded.recycle();
    }

    /**
     * The segment goes after the application segments already there, not in front of them.
     *
     * <p>Exif has to be the first segment after the start of the image. Writing this one first
     * would move it, and a reader that finds something else there stops looking - so a
     * panorama with Exif would have lost its date, its orientation and its location.</p>
     */
    @Test
    public void itgoesAfterTheSegmentsThatHaveToComeFirst() throws IOException {
        File file = jpeg(64, 32);
        // A JPEG from an encoder begins with APP0 (JFIF), which stands in here for the Exif
        // segment: both have to keep their place.
        assertEquals("this fixture should start with an application segment",
                0xE0, markerAt(file, 2));

        assertTrue(GPanoMetadata.forFullSphere(64, 32, 0f).writeTo(file));

        assertEquals("the first segment must still be the one that was there",
                0xE0, markerAt(file, 2));
        assertNotNull(GPanoMetadata.readXmp(file));
    }

    @Test
    public void aFileThatIsNotAJpegIsRefusedRatherThanCorrupted() throws IOException {
        File file = new File(directory, "notes.txt");
        byte[] content = "this is not a picture".getBytes(UTF_8);
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(content);
        }

        assertFalse(GPanoMetadata.forFullSphere(4000, 2000, 0f).writeTo(file));

        assertEquals("the file must be left exactly as it was", content.length, file.length());
        assertNull(GPanoMetadata.readXmp(file));
    }

    @Test
    public void aJpegWithNoXmpReportsNone() throws IOException {
        assertNull(GPanoMetadata.readXmp(jpeg(64, 32)));
    }

    @Test
    public void aMissingFileIsNotACrash() {
        File missing = new File(directory, "nothing.jpg");

        assertFalse(GPanoMetadata.forFullSphere(4000, 2000, 0f).writeTo(missing));
        assertNull(GPanoMetadata.readXmp(missing));
    }

    /** Written twice, as a retry would, and still readable rather than doubled into nonsense. */
    @Test
    public void writingTwiceLeavesAReadableFile() throws IOException {
        File file = jpeg(64, 32);

        assertTrue(GPanoMetadata.forFullSphere(4000, 2000, 10f).writeTo(file));
        assertTrue(GPanoMetadata.forFullSphere(4000, 2000, 20f).writeTo(file));

        Bitmap decoded = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath());
        assertNotNull("the picture no longer decodes", decoded);
        decoded.recycle();
        String xmp = GPanoMetadata.readXmp(file);
        assertNotNull(xmp);
        assertTrue("the newest packet is the one a reader finds first",
                xmp.contains("GPano:PoseHeadingDegrees=\"20.0\""));
        assertEquals("the second write should replace the first, not sit beside it",
                1, countXmpSegments(file));
    }

    /** How many XMP segments a file has, since two of them is a file that contradicts itself. */
    private static int countXmpSegments(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        byte[] header = GPanoMetadata.XMP_HEADER.getBytes(UTF_8);
        int found = 0;
        for (int i = 0; i + header.length <= bytes.length; i++) {
            boolean matches = true;
            for (int j = 0; j < header.length; j++) {
                if (bytes[i + j] != header[j]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                found++;
            }
        }
        return found;
    }

    private static int markerAt(File file, long offset) throws IOException {
        byte[] bytes = new byte[(int) offset + 2];
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            ByteArrayOutputStream read = new ByteArrayOutputStream();
            int value;
            while (read.size() < bytes.length && (value = in.read()) >= 0) {
                read.write(value);
            }
            bytes = read.toByteArray();
        }
        return bytes[(int) offset + 1] & 0xFF;
    }
}
