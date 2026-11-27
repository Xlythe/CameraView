package com.xlythe.view.camera.pano;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import androidx.exifinterface.media.ExifInterface;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Every GPano property, written into a real JPEG and read back. */
@RunWith(RobolectricTestRunner.class)
public class GPanoMetadataTest {
    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void everyProperty_roundTrips() {
        GPanoMetadata written = everything();
        String xmp = new String(written.toXmpPacket(), StandardCharsets.UTF_8);
        GPanoMetadata read = GPanoMetadata.parse(xmp);
        assertNotNull(read);
        assertEverything(read);
    }

    @Test
    public void everyProperty_isNamedAsTheSpecificationNamesIt() {
        String xmp = new String(everything().toXmpPacket(), StandardCharsets.UTF_8);
        String[] names = {
                "UsePanoramaViewer", "CaptureSoftware", "StitchingSoftware", "ProjectionType",
                "PoseHeadingDegrees", "PosePitchDegrees", "PoseRollDegrees",
                "InitialViewHeadingDegrees", "InitialViewPitchDegrees", "InitialViewRollDegrees",
                "InitialHorizontalFOVDegrees", "InitialVerticalFOVDegrees", "FirstPhotoDate",
                "LastPhotoDate", "SourcePhotosCount", "ExposureLockUsed",
                "CroppedAreaImageWidthPixels", "CroppedAreaImageHeightPixels",
                "FullPanoWidthPixels", "FullPanoHeightPixels", "CroppedAreaLeftPixels",
                "CroppedAreaTopPixels", "InitialCameraDolly",
        };
        assertEquals(23, names.length);
        for (String name : names) {
            assertTrue(name, xmp.contains("GPano:" + name + "=\""));
        }
        assertTrue(xmp.contains("xmlns:GPano=\"" + GPanoMetadata.NAMESPACE + "\""));
        assertTrue(xmp.contains("GPano:UsePanoramaViewer=\"True\""));
        assertTrue(xmp.contains("GPano:ExposureLockUsed=\"False\""));
        assertTrue(xmp.contains("GPano:FirstPhotoDate=\"2026-11-26T14:03:12.345Z\""));
        assertTrue(xmp.contains("GPano:PoseHeadingDegrees=\"271.25\""));
    }

    @Test
    public void unsetOptionalProperties_areLeftOut() {
        GPanoMetadata required = new GPanoMetadata();
        required.croppedAreaImageWidthPixels = 10;
        String xmp = new String(required.toXmpPacket(), StandardCharsets.UTF_8);
        assertFalse(xmp.contains("PoseHeadingDegrees"));
        assertFalse(xmp.contains("CaptureSoftware"));
        assertTrue(xmp.contains("GPano:ProjectionType=\"equirectangular\""));
        assertTrue(xmp.contains("GPano:CroppedAreaLeftPixels=\"0\""));
    }

    @Test
    public void realNumbers_neverUseAnExponent() {
        GPanoMetadata metadata = new GPanoMetadata();
        metadata.poseHeadingDegrees = 0.0001f;
        metadata.posePitchDegrees = 0f;
        metadata.poseRollDegrees = Float.NaN;
        String xmp = new String(metadata.toXmpPacket(), StandardCharsets.UTF_8);
        assertTrue(xmp.contains("GPano:PoseHeadingDegrees=\"0\""));
        assertTrue(xmp.contains("GPano:PosePitchDegrees=\"0\""));
        assertFalse(xmp.contains("PoseRollDegrees"));
    }

    @Test
    public void text_isEscaped() {
        GPanoMetadata metadata = new GPanoMetadata();
        metadata.captureSoftware = "Tom & Jerry's \"<Camera>\"";
        String xmp = new String(metadata.toXmpPacket(), StandardCharsets.UTF_8);
        assertTrue(xmp.contains("Tom &amp; Jerry's &quot;&lt;Camera&gt;&quot;"));
        assertEquals(metadata.captureSoftware, GPanoMetadata.parse(xmp).captureSoftware);
    }

    @Test
    public void elementForm_isReadToo() {
        String xmp = "<x:xmpmeta><rdf:RDF><rdf:Description>"
                + "<GPano:ProjectionType>equirectangular</GPano:ProjectionType>"
                + "<GPano:FullPanoWidthPixels>8000</GPano:FullPanoWidthPixels>"
                + "<GPano:InitialViewHeadingDegrees>12.6</GPano:InitialViewHeadingDegrees>"
                + "</rdf:Description></rdf:RDF></x:xmpmeta>";
        GPanoMetadata metadata = GPanoMetadata.parse(xmp);
        assertEquals(8000, metadata.fullPanoWidthPixels);
        assertEquals(Integer.valueOf(13), metadata.initialViewHeadingDegrees);
    }

    @Test
    public void malformedAndUnknownProperties_areSkipped() {
        String xmp = "GPano:FullPanoWidthPixels=\"wide\" GPano:Unheard=\"1\" "
                + "GPano:FirstPhotoDate=\"yesterday\"";
        assertNull(GPanoMetadata.parse(xmp));
        assertNull(GPanoMetadata.parse("<x:xmpmeta/>"));
    }

    @Test
    public void writtenIntoAJpeg_isReadBack_andTheJpegStillDecodes() throws IOException {
        File file = jpeg();
        everything().writeTo(file);
        GPanoMetadata read = GPanoMetadata.readFrom(file);
        assertNotNull(read);
        assertEverything(read);
        Bitmap decoded = BitmapFactory.decodeFile(file.getPath());
        assertNotNull(decoded);
        assertEquals(64, decoded.getWidth());
    }

    @Test
    public void writingTwice_leavesOnePacket() throws IOException {
        File file = jpeg();
        GPanoMetadata first = everything();
        first.sourcePhotosCount = 1;
        first.writeTo(file);
        GPanoMetadata second = everything();
        second.sourcePhotosCount = 2;
        second.writeTo(file);
        String bytes = new String(Files.readAllBytes(file.toPath()), StandardCharsets.ISO_8859_1);
        assertEquals(bytes.indexOf(GPanoMetadata.XMP_HEADER),
                bytes.lastIndexOf(GPanoMetadata.XMP_HEADER));
        assertEquals(Integer.valueOf(2), GPanoMetadata.readFrom(file).sourcePhotosCount);
    }

    @Test
    public void exif_staysFirst() throws IOException {
        File file = jpeg();
        ExifInterface exif = new ExifInterface(file.getPath());
        exif.setAttribute(ExifInterface.TAG_MAKE, "Xlythe");
        exif.saveAttributes();
        everything().writeTo(file);
        byte[] bytes = Files.readAllBytes(file.toPath());
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        assertTrue(text.indexOf("Exif\u0000\u0000") < text.indexOf(GPanoMetadata.XMP_HEADER));
        assertEquals("Xlythe", new ExifInterface(file.getPath())
                .getAttribute(ExifInterface.TAG_MAKE));
        assertNotNull(GPanoMetadata.readFrom(file));
    }

    @Test
    public void aJpegWithNoXmp_hasNoMetadata() throws IOException {
        assertNull(GPanoMetadata.readFrom(jpeg()));
    }

    @Test(expected = IOException.class)
    public void notAJpeg_isRefused() throws IOException {
        GPanoMetadata.insert("GIF89a".getBytes(StandardCharsets.US_ASCII), new byte[1]);
    }

    @Test(expected = IOException.class)
    public void aPacketTooLargeForASegment_isRefused() throws IOException {
        GPanoMetadata.insert(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9},
                new byte[70_000]);
    }

    @Test(expected = IOException.class)
    public void aTruncatedSegment_isRefused() throws IOException {
        GPanoMetadata.insert(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
                0x10, 0x00, 0x00}, new byte[1]);
    }

    @Test
    public void aJpegOfOnlyMarkers_getsThePacketBeforeTheEnd() throws IOException {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x02,
                (byte) 0xFF, (byte) 0xD9};
        byte[] packet = "<x/>".getBytes(StandardCharsets.UTF_8);
        byte[] updated = GPanoMetadata.insert(jpeg, packet);
        assertEquals("<x/>", GPanoMetadata.findXmp(updated));
        byte[] tail = {(byte) 0xFF, (byte) 0xD9};
        byte[] end = {updated[updated.length - 2], updated[updated.length - 1]};
        assertArrayEquals(tail, end);
        assertNull(GPanoMetadata.findXmp(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF,
                (byte) 0xE0, 0x40, 0x00}));
    }

    private File jpeg() throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.rgb(40, 120, 200));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, bytes);
        File file = folder.newFile("pano.jpg");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes.toByteArray());
        }
        return file;
    }

    private static GPanoMetadata everything() {
        GPanoMetadata metadata = new GPanoMetadata();
        metadata.usePanoramaViewer = true;
        metadata.captureSoftware = "Sample";
        metadata.stitchingSoftware = "CameraView";
        metadata.projectionType = GPanoMetadata.EQUIRECTANGULAR;
        metadata.poseHeadingDegrees = 271.25f;
        metadata.posePitchDegrees = 0.5f;
        metadata.poseRollDegrees = -1.5f;
        metadata.initialViewHeadingDegrees = 280;
        metadata.initialViewPitchDegrees = 5;
        metadata.initialViewRollDegrees = 0;
        metadata.initialHorizontalFovDegrees = 60f;
        metadata.initialVerticalFovDegrees = 80f;
        metadata.firstPhotoDate = 1795701792345L;
        metadata.lastPhotoDate = 1795701852345L;
        metadata.sourcePhotosCount = 42;
        metadata.exposureLockUsed = false;
        metadata.croppedAreaImageWidthPixels = 4000;
        metadata.croppedAreaImageHeightPixels = 1000;
        metadata.fullPanoWidthPixels = 4000;
        metadata.fullPanoHeightPixels = 2000;
        metadata.croppedAreaLeftPixels = 0;
        metadata.croppedAreaTopPixels = 500;
        metadata.initialCameraDolly = 0f;
        return metadata;
    }

    private static void assertEverything(GPanoMetadata read) {
        assertEquals(Boolean.TRUE, read.usePanoramaViewer);
        assertEquals("Sample", read.captureSoftware);
        assertEquals("CameraView", read.stitchingSoftware);
        assertEquals("equirectangular", read.projectionType);
        assertEquals(271.25f, read.poseHeadingDegrees, 0f);
        assertEquals(0.5f, read.posePitchDegrees, 0f);
        assertEquals(-1.5f, read.poseRollDegrees, 0f);
        assertEquals(Integer.valueOf(280), read.initialViewHeadingDegrees);
        assertEquals(Integer.valueOf(5), read.initialViewPitchDegrees);
        assertEquals(Integer.valueOf(0), read.initialViewRollDegrees);
        assertEquals(60f, read.initialHorizontalFovDegrees, 0f);
        assertEquals(80f, read.initialVerticalFovDegrees, 0f);
        assertEquals(Long.valueOf(1795701792345L), read.firstPhotoDate);
        assertEquals(Long.valueOf(1795701852345L), read.lastPhotoDate);
        assertEquals(Integer.valueOf(42), read.sourcePhotosCount);
        assertEquals(Boolean.FALSE, read.exposureLockUsed);
        assertEquals(4000, read.croppedAreaImageWidthPixels);
        assertEquals(1000, read.croppedAreaImageHeightPixels);
        assertEquals(4000, read.fullPanoWidthPixels);
        assertEquals(2000, read.fullPanoHeightPixels);
        assertEquals(0, read.croppedAreaLeftPixels);
        assertEquals(500, read.croppedAreaTopPixels);
        assertEquals(0f, read.initialCameraDolly, 0f);
    }
}
