package com.xlythe.view.camera.viewer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import com.xlythe.view.camera.MediaTags;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Working out where a picture sits on the sphere from its photo sphere XMP. */
@RunWith(RobolectricTestRunner.class)
public class GPanoTest {
    private static final double EPSILON = 1e-9;

    /** The namespace every property is in. */
    static final String NAMESPACE = "http://ns.google.com/photos/1.0/panorama/";

    /**
     * Word for word what CameraView's panorama sweep writes for a full-circle strip: the XMP of a
     * panorama saved on the emulator, 3032 x 532 out of a 3032 x 1516 sphere.
     */
    static final String SWEEP_PANORAMA_XMP =
            "<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n"
            + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n"
            + " <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
            + "  <rdf:Description rdf:about=\"\"\n"
            + "    xmlns:GPano=\"http://ns.google.com/photos/1.0/panorama/\"\n"
            + "    GPano:UsePanoramaViewer=\"True\"\n"
            + "    GPano:CaptureSoftware=\"Camera\"\n"
            + "    GPano:StitchingSoftware=\"CameraView\"\n"
            + "    GPano:ProjectionType=\"equirectangular\"\n"
            + "    GPano:PoseHeadingDegrees=\"1.599\"\n"
            + "    GPano:PosePitchDegrees=\"0\"\n"
            + "    GPano:PoseRollDegrees=\"0\"\n"
            + "    GPano:InitialViewHeadingDegrees=\"2\"\n"
            + "    GPano:InitialViewPitchDegrees=\"2\"\n"
            + "    GPano:InitialViewRollDegrees=\"0\"\n"
            + "    GPano:InitialHorizontalFOVDegrees=\"52.877\"\n"
            + "    GPano:InitialVerticalFOVDegrees=\"67.088\"\n"
            + "    GPano:FirstPhotoDate=\"2026-11-27T11:41:22.729Z\"\n"
            + "    GPano:LastPhotoDate=\"2026-11-27T11:42:29.501Z\"\n"
            + "    GPano:SourcePhotosCount=\"23\"\n"
            + "    GPano:ExposureLockUsed=\"False\"\n"
            + "    GPano:CroppedAreaImageWidthPixels=\"3032\"\n"
            + "    GPano:CroppedAreaImageHeightPixels=\"532\"\n"
            + "    GPano:FullPanoWidthPixels=\"3032\"\n"
            + "    GPano:FullPanoHeightPixels=\"1516\"\n"
            + "    GPano:CroppedAreaLeftPixels=\"0\"\n"
            + "    GPano:CroppedAreaTopPixels=\"472\"\n"
            + "    GPano:InitialCameraDolly=\"0\"/>\n"
            + " </rdf:RDF>\n"
            + "</x:xmpmeta>\n"
            + "<?xpacket end=\"w\"?>";

    /** The sweep writer's strip: all the way round, a band from 33.96 to -29.21 degrees. */
    @Test
    public void readsWhatTheSweepWrites() {
        GPano gpano = parse(SWEEP_PANORAMA_XMP, 3032, 532);

        assertNotNull(gpano);
        assertTrue(gpano.isFullCircle());
        assertEquals(0, gpano.left, EPSILON);
        assertEquals(1, gpano.width, EPSILON);
        assertEquals(472.0 / 1516, gpano.top, EPSILON);
        assertEquals(532.0 / 1516, gpano.height, EPSILON);
        assertEquals(1.599f, gpano.poseHeadingDegrees, 1e-6f);
        assertEquals(0f, gpano.posePitchDegrees, 0f);
        assertEquals(2f, gpano.initialHeadingDegrees, 0f);
        assertEquals(2f, gpano.initialPitchDegrees, 0f);
        assertEquals(52.877f, gpano.initialHorizontalFovDegrees, 1e-4f);
        assertEquals(90 - 472 * 180.0 / 1516, gpano.pitchAt(0), 1e-9);
        assertEquals(90 - 1004 * 180.0 / 1516, gpano.pitchAt(1), 1e-9);
        assertEquals(-180, gpano.headingAt(0), 1e-9);
        assertEquals(0, gpano.headingAt(0.5), 1e-9);
        assertEquals(180, gpano.headingAt(1), 1e-9);
    }

    /** A partial sweep sits in the middle of the full panorama, as the writer centres it. */
    @Test
    public void aPartialSphereKeepsItsPlace() {
        GPano gpano = parse(xmp("CroppedAreaImageWidthPixels=\"1000\" "
                + "CroppedAreaImageHeightPixels=\"500\" FullPanoWidthPixels=\"4000\" "
                + "FullPanoHeightPixels=\"2000\" CroppedAreaLeftPixels=\"1500\" "
                + "CroppedAreaTopPixels=\"750\""), 1000, 500);

        assertNotNull(gpano);
        assertFalse(gpano.isFullCircle());
        assertEquals(-45, gpano.headingAt(0), 1e-9);
        assertEquals(45, gpano.headingAt(1), 1e-9);
        assertEquals(22.5, gpano.pitchAt(0), 1e-9);
        assertEquals(-22.5, gpano.pitchAt(1), 1e-9);
        assertTrue(Float.isNaN(gpano.initialHeadingDegrees));
        assertTrue(Float.isNaN(gpano.initialHorizontalFovDegrees));
        assertEquals(0f, gpano.poseHeadingDegrees, 0f);
    }

    /** A picture resized after it was written keeps the same place: only ratios matter. */
    @Test
    public void onlyRatiosMatter() {
        GPano gpano = parse(xmp("CroppedAreaImageWidthPixels=\"8000\" "
                + "CroppedAreaImageHeightPixels=\"2000\" FullPanoWidthPixels=\"8000\" "
                + "FullPanoHeightPixels=\"4000\" CroppedAreaLeftPixels=\"0\" "
                + "CroppedAreaTopPixels=\"1000\""), 2000, 500);

        assertNotNull(gpano);
        assertEquals(0.25, gpano.top, EPSILON);
        assertEquals(0.5, gpano.height, EPSILON);
    }

    /** The element form, and a namespace bound to a prefix other than GPano. */
    @Test
    public void readsElementsUnderAnyPrefix() {
        String packet = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF><rdf:Description "
                + "xmlns:pano='" + NAMESPACE + "'>"
                + "<pano:ProjectionType>equirectangular</pano:ProjectionType>"
                + "<pano:CroppedAreaImageWidthPixels>2048</pano:CroppedAreaImageWidthPixels>"
                + "<pano:CroppedAreaImageHeightPixels>512</pano:CroppedAreaImageHeightPixels>"
                + "<pano:FullPanoWidthPixels>4096</pano:FullPanoWidthPixels>"
                + "<pano:FullPanoHeightPixels>2048</pano:FullPanoHeightPixels>"
                + "<pano:CroppedAreaLeftPixels>1024.0</pano:CroppedAreaLeftPixels>"
                + "<pano:CroppedAreaTopPixels>768</pano:CroppedAreaTopPixels>"
                + "<pano:PoseHeadingDegrees>90</pano:PoseHeadingDegrees>"
                + "</rdf:Description></rdf:RDF></x:xmpmeta>";

        GPano gpano = parse(packet, 2048, 512);

        assertNotNull(gpano);
        assertEquals(0.25, gpano.left, EPSILON);
        assertEquals(0.5, gpano.width, EPSILON);
        assertEquals(0.375, gpano.top, EPSILON);
        assertEquals(90f, gpano.poseHeadingDegrees, 0f);
    }

    /** Attributes in single quotes, with escaped characters, read like double-quoted ones. */
    @Test
    public void readsSingleQuotedAttributes() {
        GPano gpano = parse(xmp("CroppedAreaImageWidthPixels='100' "
                + "FullPanoWidthPixels='&#32;400' PoseRollDegrees='&quot;5&quot;'"), 100, 50);

        assertNotNull(gpano);
        // The width is unreadable, so it falls back to the cropped width: a full circle.
        assertTrue(gpano.isFullCircle());
        assertEquals(0f, gpano.poseRollDegrees, 0f);
    }

    /** What a missing property is taken to be, from the rest. */
    @Test
    public void fillsInWhatIsMissing() {
        GPano gpano = parse(xmp("UsePanoramaViewer=\"True\""), 4000, 1000);

        assertNotNull(gpano);
        // The cropped size is the picture's own, the full width the cropped one, the full
        // height half that, and the crop centred.
        assertTrue(gpano.isFullCircle());
        assertEquals(0.5, gpano.height, EPSILON);
        assertEquals(0.25, gpano.top, EPSILON);
    }

    /** A crop that claims more than the whole is held to the whole. */
    @Test
    public void clampsACropLargerThanTheWhole() {
        GPano gpano = parse(xmp("CroppedAreaImageWidthPixels=\"4100\" "
                + "CroppedAreaImageHeightPixels=\"2100\" FullPanoWidthPixels=\"4096\" "
                + "FullPanoHeightPixels=\"2048\" CroppedAreaLeftPixels=\"-3\" "
                + "CroppedAreaTopPixels=\"9\""), 4100, 2100);

        assertNotNull(gpano);
        assertEquals(1, gpano.width, EPSILON);
        assertEquals(1, gpano.height, EPSILON);
        assertEquals(0, gpano.left, EPSILON);
        assertEquals(0, gpano.top, EPSILON);
    }

    @Test
    public void refusesAnotherProjection() {
        assertNull(parse(xmp("ProjectionType=\"cylindrical\""), 100, 50));
    }

    @Test
    public void refusesAPacketWithoutTheNamespace() {
        assertNull(parse("<x:xmpmeta><dc:title>Hello</dc:title></x:xmpmeta>", 100, 50));
    }

    @Test
    public void refusesSizesThatAreNotPositive() {
        assertNull(parse(xmp("FullPanoWidthPixels=\"0\""), 100, 50));
        assertNull(parse(xmp("CroppedAreaImageHeightPixels=\"-1\""), 100, 50));
        assertNull(parse(xmp("FullPanoHeightPixels=\"NaN\" FullPanoWidthPixels=\"0\""),
                100, 50));
    }

    /** Numbers that are not finite are ignored rather than trusted. */
    @Test
    public void ignoresNumbersThatAreNotFinite() {
        GPano gpano = parse(xmp("PoseHeadingDegrees=\"Infinity\""), 200, 100);

        assertNotNull(gpano);
        assertEquals(0f, gpano.poseHeadingDegrees, 0f);
    }

    /** A picture with no metadata is taken to go all the way round at its own aspect. */
    @Test
    public void assumesAFullCircle() {
        GPano sphere = GPano.assumed(4096, 2048);
        assertTrue(sphere.isFullCircle());
        assertEquals(90, sphere.pitchAt(0), 1e-9);
        assertEquals(-90, sphere.pitchAt(1), 1e-9);

        GPano strip = GPano.assumed(3600, 450);
        assertEquals(22.5, strip.pitchAt(0), 1e-9);
        assertEquals(-22.5, strip.pitchAt(1), 1e-9);

        GPano tall = GPano.assumed(1000, 1000);
        assertEquals(1, tall.height, EPSILON);
    }

    /** The packet is found after the JFIF and Exif segments, past fill bytes. */
    @Test
    public void findsThePacketInAJpeg() throws IOException {
        GPano gpano = parse(SWEEP_PANORAMA_XMP, 3032, 532);

        assertNotNull(gpano);
        assertTrue(gpano.isFullCircle());
    }

    @Test
    public void findsNothingWithoutAPacket() throws IOException {
        assertNull(GPano.from(open(jpeg(false, null)).getPanorama(), 10, 10));
    }

    /** An APP1 segment too short to hold the XMP header is not mistaken for one. */
    @Test
    public void skipsShortSegments() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {(byte) 0xFF, (byte) 0xD8});
        segment(out, 0xE1, "Exif".getBytes(StandardCharsets.ISO_8859_1));
        segment(out, 0xE1, ("http://ns.adobe.com/xap/1.0/ " + xmp("PoseRollDegrees=\"3\""))
                .getBytes(StandardCharsets.UTF_8));
        out.write(new byte[] {(byte) 0xFF, (byte) 0xDA});

        GPano gpano = GPano.from(open(out.toByteArray()).getPanorama(), 200, 100);

        assertNotNull(gpano);
        assertEquals(3f, gpano.poseRollDegrees, 0f);
    }

    /** Where a picture with this packet sits, read the way the viewers read it. */
    static GPano parse(String xmp, int width, int height) {
        try {
            return GPano.from(open(jpeg(true, xmp)).getPanorama(), width, height);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /** The metadata of a file with these bytes. */
    static MediaTags open(byte[] bytes) throws IOException {
        File file = File.createTempFile("gpano", ".jpg");
        try {
            Files.write(file.toPath(), bytes);
            return MediaTags.open(file);
        } finally {
            file.deleteOnExit();
        }
    }

    /** A packet with the given GPano attributes. */
    static String xmp(String attributes) {
        String prefixed = attributes.replaceAll("(\\w+)=", "GPano:$1=");
        return "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF><rdf:Description "
                + "xmlns:GPano=\"" + NAMESPACE + "\" " + prefixed
                + "/></rdf:RDF></x:xmpmeta>";
    }

    /** A JPEG's header: JFIF, Exif, fill bytes, optionally XMP, then the start of the scan. */
    static byte[] jpeg(boolean withXmp, String xmp) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {(byte) 0xFF, (byte) 0xD8});
        segment(out, 0xE0, "JFIF\u0000\u0001\u0001\u0000\u0000\u0001\u0000\u0001\u0000\u0000"
                .getBytes(StandardCharsets.ISO_8859_1));
        segment(out, 0xE1, "Exif\u0000\u0000MM\u0000*".getBytes(StandardCharsets.ISO_8859_1));
        // Fill bytes before the next marker.
        out.write(0xFF);
        out.write(0xFF);
        if (withXmp) {
            segment(out, 0xE1, ("http://ns.adobe.com/xap/1.0/\u0000" + xmp)
                    .getBytes(StandardCharsets.UTF_8));
        }
        out.write(new byte[] {(byte) 0xFF, (byte) 0xDA, 0, 2, 0x11, 0x22});
        return out.toByteArray();
    }

    private static void segment(ByteArrayOutputStream out, int marker, byte[] payload) {
        // The leading 0xFF; a fill byte may already have been written before it.
        out.write(0xFF);
        out.write(marker);
        int length = payload.length + 2;
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.write(payload, 0, payload.length);
    }
}
