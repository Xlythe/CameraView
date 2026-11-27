package com.xlythe.view.camera;

import static com.xlythe.view.camera.MediaFixtures.box;
import static com.xlythe.view.camera.MediaFixtures.fullBox;
import static com.xlythe.view.camera.MediaFixtures.u32;
import static com.xlythe.view.camera.MediaFixtures.xmp;

/** Media files for tests outside this package, such as the viewers'. */
public final class MediaTestFiles {
    private MediaTestFiles() {}

    /** A movie with one video track. */
    public static byte[] movie() {
        return MediaTagsBoxTest.movie(new byte[0]);
    }

    /** A movie with version 2 spherical metadata: equirectangular, top and bottom. */
    public static byte[] sphericalMovie() {
        byte[] prhd = fullBox("prhd", 0, 0, u32(0), u32(0), u32(0));
        byte[] equi = fullBox("equi", 0, 0, u32(0), u32(0), u32(0), u32(0));
        byte[] sv3d = box("sv3d", box("proj", prhd, equi));
        byte[] st3d = fullBox("st3d", 0, 0, new byte[] {1});
        return MediaTagsBoxTest.movie(new byte[0], sv3d, st3d);
    }

    /** An MP3 with a title, and a cover picture unless null. */
    public static byte[] mp3WithCover(byte[] cover) {
        Id3Container.Frame title = MediaFixtures.textFrame("TIT2", "Song");
        byte[] tag = cover == null ? MediaFixtures.id3(3, 0, title)
                : MediaFixtures.id3(3, 0, title, new Id3Container.Frame("APIC", 0,
                MediaFixtures.concat(new byte[] {0}, MediaFixtures.ascii("image/jpeg"),
                        new byte[] {0, 3, 0}, cover)));
        return MediaFixtures.concat(tag, MediaFixtures.mp3Frames());
    }

    /** An MP4 of a size that is only an ftyp and padding. */
    public static byte[] mp4Clip(int size) {
        return MediaTagsFixtures.mp4Clip(size);
    }

    /** A motion photo's XMP for a clip of a length at the end of the file. */
    public static String motionPhotoXmp(long videoLength) {
        return MediaTagsFixtures.motionPhotoXmp(videoLength, 0, false);
    }

    /** A picture whose Ultra HDR gain map is there, located by MPF. */
    public static byte[] gainMapJpeg() {
        return MediaTagsFixtures.gainMapJpeg(xmp("xmlns:hdrgm=\"" + Xmp.NS_HDR_GAIN_MAP + "\"",
                "hdrgm:Version=\"1.0\""), null, false);
    }

    public static byte[] concat(byte[]... parts) {
        return Bytes.concat(parts);
    }
}
