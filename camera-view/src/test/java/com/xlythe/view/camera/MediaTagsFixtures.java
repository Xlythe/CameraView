package com.xlythe.view.camera;

import static com.xlythe.view.camera.MediaFixtures.ascii;
import static com.xlythe.view.camera.MediaFixtures.box;
import static com.xlythe.view.camera.MediaFixtures.concat;
import static com.xlythe.view.camera.MediaFixtures.ftyp;
import static com.xlythe.view.camera.MediaFixtures.jpeg;
import static com.xlythe.view.camera.MediaFixtures.segment;
import static com.xlythe.view.camera.MediaFixtures.utf8;
import static com.xlythe.view.camera.MediaFixtures.xmpElements;
import static com.xlythe.view.camera.MediaFixtures.xmpSegment;

import androidx.annotation.Nullable;

/** Values and files that more than one metadata test needs. */
final class MediaTagsFixtures {
    private MediaTagsFixtures() {}

    /** Photo sphere metadata with every property set, as CameraView's sweep writes it. */
    static MediaTags.Panorama everyGPanoField() {
        return new MediaTags.Panorama.Builder()
                .setUsePanoramaViewer(true)
                .setCaptureSoftware("Camera")
                .setStitchingSoftware("CameraView")
                .setProjectionType(MediaTags.Panorama.PROJECTION_EQUIRECTANGULAR)
                .setPoseHeadingDegrees(1.599f)
                .setPosePitchDegrees(0f)
                .setPoseRollDegrees(-0.5f)
                .setInitialViewHeadingDegrees(2)
                .setInitialViewPitchDegrees(-3)
                .setInitialViewRollDegrees(0)
                .setInitialHorizontalFovDegrees(52.877f)
                .setInitialVerticalFovDegrees(67.088f)
                .setFirstPhotoDate(1795700482729L)
                .setLastPhotoDate(1795700549501L)
                .setSourcePhotosCount(23)
                .setExposureLockUsed(false)
                .setCroppedAreaImageWidthPixels(3032)
                .setCroppedAreaImageHeightPixels(532)
                .setFullPanoWidthPixels(3032)
                .setFullPanoHeightPixels(1516)
                .setCroppedAreaLeftPixels(0)
                .setCroppedAreaTopPixels(472)
                .setInitialCameraDolly(0f)
                .setLargestValidInteriorRectLeft(1)
                .setLargestValidInteriorRectTop(2)
                .setLargestValidInteriorRectWidth(3000)
                .setLargestValidInteriorRectHeight(500)
                .build();
    }

    /** An MP4 of a given size that is only an ftyp and padding: enough to be found. */
    static byte[] mp4Clip(int size) {
        byte[] ftyp = ftyp("isom", "isom", "mp42");
        return concat(ftyp, box("free", new byte[size - ftyp.length - 8]));
    }

    /** A motion photo's XMP: a container directory of the primary picture and the clip. */
    static String motionPhotoXmp(long videoLength, long padding, boolean presentation) {
        return xmpElements("xmlns:Camera=\"" + Xmp.NS_CAMERA + "\" xmlns:Container=\""
                + Xmp.NS_CONTAINER + "\" xmlns:Item=\"" + Xmp.NS_ITEM + "\" Camera:MotionPhoto=\"1\" "
                + "Camera:MotionPhotoVersion=\"1\""
                + (presentation ? " Camera:MotionPhotoPresentationTimestampUs=\"1234\"" : ""),
                "<Container:Directory><rdf:Seq>"
                + "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"image/jpeg\" "
                + "Item:Semantic=\"Primary\" Item:Length=\"0\" Item:Padding=\"" + padding
                + "\"/></rdf:li>"
                + "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"video/mp4\" "
                + "Item:Semantic=\"MotionPhoto\" Item:Length=\"" + videoLength
                + "\"/></rdf:li></rdf:Seq></Container:Directory>");
    }

    /** Samsung's trailer around a clip: its record, then the directory and "SEFT". */
    static byte[] samsungTrailer(byte[] video) {
        byte[] name = ascii("MotionPhoto_Data");
        byte[] record = new byte[8 + name.length];
        Bytes.put16le(record, 2, 0x0A30);
        Bytes.put32le(record, 4, name.length);
        System.arraycopy(name, 0, record, 8, name.length);
        byte[] data = concat(record, video);
        byte[] directory = new byte[12 + 12];
        System.arraycopy(ascii("SEFH"), 0, directory, 0, 4);
        Bytes.put32le(directory, 4, 106);
        Bytes.put32le(directory, 8, 1);
        Bytes.put16le(directory, 14, 0x0A30);
        Bytes.put32le(directory, 16, data.length);
        Bytes.put32le(directory, 20, data.length);
        byte[] tail = new byte[8];
        Bytes.put32le(tail, 0, directory.length);
        System.arraycopy(ascii("SEFT"), 0, tail, 4, 4);
        return concat(data, directory, tail);
    }

    /** An MPF segment's TIFF block listing a primary picture and one at an offset. */
    static byte[] mpf(long offset, boolean little) {
        byte[] tiff = new byte[8 + 2 + 12 + 4 + 32];
        if (little) {
            tiff[0] = 'I';
            tiff[1] = 'I';
            tiff[2] = 42;
            Bytes.put32le(tiff, 4, 8);
            Bytes.put16le(tiff, 8, 1);
            Bytes.put16le(tiff, 10, 0xB002);
            Bytes.put16le(tiff, 12, 7);
            Bytes.put32le(tiff, 14, 32);
            Bytes.put32le(tiff, 18, 26);
            Bytes.put32le(tiff, 26 + 16 + 8, offset);
        } else {
            tiff[0] = 'M';
            tiff[1] = 'M';
            tiff[3] = 42;
            Bytes.put32(tiff, 4, 8);
            Bytes.put16(tiff, 8, 1);
            Bytes.put16(tiff, 10, 0xB002);
            Bytes.put16(tiff, 12, 7);
            Bytes.put32(tiff, 14, 32);
            Bytes.put32(tiff, 18, 26);
            Bytes.put32(tiff, 26 + 16 + 8, offset);
        }
        return tiff;
    }

    static byte[] gainMapJpeg(@Nullable String primaryXmp, @Nullable String gainMapXmp,
            boolean stripped) {
        return gainMapJpeg(primaryXmp, gainMapXmp, stripped, false);
    }

    /**
     * A picture with a gain map after it, which MPF locates, and metadata on either: XMP, or
     * ISO 21496-1's segment on both.
     *
     * @param stripped whether the gain map was taken off, leaving MPF pointing past the end
     */
    static byte[] gainMapJpeg(@Nullable String primaryXmp, @Nullable String gainMapXmp,
            boolean stripped, boolean iso) {
        byte[] isoSegment = segment(JpegContainer.APP2, JpegContainer.ISO_GAIN_MAP_IDENTIFIER,
                new byte[] {0, 0, 0, 0});
        byte[] gainMap = jpeg(gainMapXmp == null ? new byte[0] : xmpSegment(gainMapXmp),
                iso ? isoSegment : new byte[0]);
        byte[] before = concat(primaryXmp == null ? new byte[0] : xmpSegment(primaryXmp),
                iso ? isoSegment : new byte[0]);
        // The MPF segment's own size is fixed, so the primary picture's length is known.
        byte[] placeholder = segment(JpegContainer.APP2, JpegContainer.MPF_IDENTIFIER,
                mpf(0, false));
        byte[] primary = jpeg(before, placeholder);
        int mpfTiff = 2 + before.length + 4 + JpegContainer.MPF_IDENTIFIER.length;
        long offset = primary.length - mpfTiff;
        byte[] mpf = segment(JpegContainer.APP2, JpegContainer.MPF_IDENTIFIER,
                mpf(offset, false));
        primary = jpeg(before, mpf);
        return stripped ? primary : concat(primary, gainMap);
    }

    /** An empty XMP declaration list, for tests that need none. */
    static byte[] noXmp() {
        return utf8("");
    }
}
