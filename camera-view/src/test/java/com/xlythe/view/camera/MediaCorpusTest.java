package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.location.Location;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Arrays;
import java.util.TimeZone;

/**
 * Real files from phones, cameras and other libraries' test data, under test/resources/media
 * with their sources in its NOTICE.md: what each was chosen for is read as it should be.
 */
@RunWith(RobolectricTestRunner.class)
public class MediaCorpusTest {
    private TimeZone mZone;

    @Before
    public void setUp() {
        mZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @After
    public void tearDown() {
        TimeZone.setDefault(mZone);
    }

    private static byte[] bytes(String name) throws IOException {
        try (InputStream in = MediaCorpusTest.class.getResourceAsStream("/media/" + name)) {
            assertNotNull(name, in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    /** Opened from a file, the way an app opens one. */
    private static MediaTags open(String name) throws IOException {
        return MediaTags.open(MediaFixtures.file(bytes(name), "-" + name));
    }

    @Test
    public void motionPhotos() throws IOException {
        MediaTags.MotionPhoto pixel =
                open("androidx_pixel-motion-photo-without-exif-shortened.jpg").getMotionPhoto();
        assertEquals(MediaTags.MotionPhoto.FORMAT_MOTION_PHOTO, pixel.getFormat());
        assertEquals(130611, pixel.getVideoOffset());
        assertEquals(8730, pixel.getVideoLength());

        MediaTags samsung = open("androidx_ss-motion-photo-shortened.jpg");
        assertEquals(MediaTags.MotionPhoto.FORMAT_MICRO_VIDEO,
                samsung.getMotionPhoto().getFormat());
        assertEquals(20345, samsung.getMotionPhoto().getVideoOffset());
        assertEquals(90, samsung.getRotation());
        assertEquals("samsung", samsung.getMake());

        MediaTags heic = open("androidx_sample_MP.heic");
        assertEquals(MediaTags.FORMAT_HEIF, heic.getFormat());
        assertEquals(28869, heic.getMotionPhoto().getVideoOffset());
        assertEquals(28803, heic.getMotionPhoto().getVideoLength());
        assertNull(open("androidx_sample_still_photo.heic").getMotionPhoto());
    }

    @Test
    public void gainMaps() throws IOException {
        assertEquals(MediaTags.GAIN_MAP_ULTRA_HDR,
                open("libavif_paris_exif_xmp_gainmap_littleendian.jpg").getGainMap());
        assertEquals(MediaTags.GAIN_MAP_APPLE,
                open("libultrahdr_apple_gainmap_new.jpg").getGainMap());
        assertEquals(MediaTags.GAIN_MAP_ISO_21496_1,
                open("skia_gainmap_iso21496_1.jpg").getGainMap());
        assertEquals(MediaTags.GAIN_MAP_ISO_21496_1,
                open("libultrahdr_gainmap_hevc_16x16.heic").getGainMap());
        assertEquals(MediaTags.GAIN_MAP_NONE,
                open("libavif_paris_exif_orientation_5.jpg").getGainMap());
    }

    @Test
    public void orientations() throws IOException {
        MediaTags transposed = open("libavif_paris_exif_orientation_5.jpg");
        assertEquals(270, transposed.getRotation());
        assertTrue(transposed.isMirrored());
        assertEquals(90, open("skia_orientation_6.webp").getRotation());
        assertEquals(90, open("wpt_png_eXIf_orientation-bottom-right.png").getRotation());
        assertEquals(90, open("skia_F-exif-chunk-early.png").getRotation());
        // irot and imir, in the order they are listed.
        MediaTags avif = open("libavif_clap_irot_imir_non_essential.avif");
        assertEquals(MediaTags.FORMAT_AVIF, avif.getFormat());
        assertEquals(270, avif.getRotation());
        assertTrue(avif.isMirrored());
        // A DNG's orientation is its IFD0's, which the Exif reader passes over.
        MediaTags dng = open("skia_sample_1mp_rotated.dng");
        assertEquals(MediaTags.FORMAT_DNG, dng.getFormat());
        assertEquals(270, dng.getRotation());
        assertEquals("LG Mobile", dng.getMake());
    }

    @Test
    public void pngText() throws IOException {
        for (String name : new String[] {"pngsuite_ct1n0g04_tEXt.png",
                "pngsuite_ctzn0g04_zTXt.png", "pngsuite_cten0g04_iTXt.png"}) {
            MediaTags tags = open(name);
            assertEquals(name, "PngSuite", tags.getTitle());
            assertTrue(name, tags.getAuthor().startsWith("Willem"));
            assertTrue(name, tags.getDescription().startsWith("A compilation"));
        }
        assertEquals("2017 Willem van Schaik", open("pngsuite_exif2c08_eXIf.png").getCopyright());
    }

    @Test
    public void videos() throws IOException {
        MediaTags dolby = open("androidx_dolbyVision-hdr.mov");
        assertEquals(MediaTags.FORMAT_QUICKTIME, dolby.getFormat());
        assertEquals(MediaTags.TRANSFER_HLG, dolby.getVideoTransfer());
        assertEquals(90, dolby.getRotation());
        assertEquals("Apple", dolby.getMake());
        assertEquals("iPhone 14 Pro", dolby.getModel());
        assertEquals(51.5334, dolby.getLocation().getLatitude(), 1e-4);
        assertEquals(26.756, dolby.getLocation().getAltitude(), 1e-3);
        assertEquals(1683632506000L, dolby.getCaptureTime());

        // HDR10 said only by the stream's own parameter set.
        assertEquals(MediaTags.TRANSFER_PQ,
                open("chromium_bear-1280x720-hevc-10bit-hdr10.mp4").getVideoTransfer());

        MediaTags.SphericalVideo spherical = open("chromium_spherical.mp4").getSphericalVideo();
        assertEquals(MediaTags.SphericalVideo.PROJECTION_EQUIRECTANGULAR,
                spherical.getProjection());
        assertEquals(MediaTags.SphericalVideo.STEREO_TOP_BOTTOM, spherical.getStereoMode());

        assertEquals(90, open("chromium_bear_rotate_90.mp4").getRotation());
        MediaTags threeGpp = open("androidx_bbb_176x144_h263.3gp");
        assertEquals(MediaTags.FORMAT_3GP, threeGpp.getFormat());
        assertEquals("Big Buck Bunny, Sunflower version", threeGpp.getTitle());
        assertEquals(240f, open("androidx_sample_android_slow_motion.mp4")
                .getCaptureSettings().getCaptureFrameRate(), 0f);
    }

    @Test
    public void sound() throws IOException {
        MediaTags id3 = open("androidx_bear-id3.mp3");
        assertEquals(MediaTags.FORMAT_MP3, id3.getFormat());
        assertEquals("Test title", id3.getTitle());
        assertEquals("Test Artist", id3.getAuthor());
        assertEquals(38946, id3.getCoverArt().length);
        assertEquals("440Hz Test Tone", open("androidx_sine-wave-cbr-trailing-id3v1.mp3")
                .getTitle());
        // An ID3 tag in front of a FLAC, which a sniffer that stops at "ID3" takes for an MP3.
        MediaTags flac = open("androidx_bear_with_id3.flac");
        assertEquals(MediaTags.FORMAT_FLAC, flac.getFormat());
        assertEquals("那么爱你为什么", flac.getTitle());
        assertEquals(MediaTags.FORMAT_OPUS, open("androidx_bear.opus").getFormat());
        assertEquals(MediaTags.FORMAT_M4A, open("chromium_sfx.m4a").getFormat());
        assertEquals(MediaTags.KIND_AUDIO, open("chromium_sfx.m4a").getKind());
    }

    /** Every asset, as a file to open. */
    private static File[] assets() throws URISyntaxException {
        URL notice = MediaCorpusTest.class.getResource("/media/NOTICE.md");
        assertNotNull(notice);
        File[] files = new File(notice.toURI()).getParentFile().listFiles(
                (directory, name) -> !name.equals("NOTICE.md"));
        assertNotNull(files);
        Arrays.sort(files);
        return files;
    }

    /** Every asset reads whole, whatever it is, and says the same twice. */
    @Test
    public void everyAssetReads() throws IOException, URISyntaxException {
        for (File asset : assets()) {
            String name = asset.getName();
            MediaTags tags = MediaTags.open(MediaFixtures.file(MediaFixtures.read(asset),
                    "-" + name));
            String said = describe(tags);
            assertEquals(name, said, describe(MediaTags.read(MediaFixtures.read(asset))));
            assertNotNull(name, tags.toString());
        }
    }

    /** Everything a file says, as one line, to compare. */
    private static String describe(MediaTags tags) {
        StringBuilder line = new StringBuilder();
        line.append(tags.getFormat()).append('/').append(tags.getKind())
                .append('/').append(tags.getTitle()).append('/').append(tags.getDescription())
                .append('/').append(tags.getAuthor()).append('/').append(tags.getCopyright())
                .append('/').append(tags.getMake()).append('/').append(tags.getModel())
                .append('/').append(tags.getSoftware()).append('/').append(tags.getCaptureTime())
                .append('/').append(tags.getLocation() == null ? null
                        : tags.getLocation().getLatitude() + "," + tags.getLocation().getLongitude())
                .append('/').append(tags.getHeading()).append('/').append(tags.getRotation())
                .append('/').append(tags.isMirrored()).append('/').append(tags.getAmbientConditions())
                .append('/').append(tags.getPanorama()).append('/').append(tags.getMotionPhoto())
                .append('/').append(tags.getGainMap()).append('/').append(tags.getVideoTransfer())
                .append('/').append(tags.getSphericalVideo()).append('/')
                .append(tags.getCaptureSettings()).append('/').append(tags.getLivePhotoId())
                .append('/').append(tags.getCoverArt() == null ? -1 : tags.getCoverArt().length)
                .append('/').append(Arrays.toString(tags.getVideoSize()))
                .append('/').append(tags.isAnimated()).append('/').append(tags.getExifTag(0x010F))
                .append('/').append(tags.getVideoTag(VideoMetadata.Tag.TITLE))
                .append('/').append(tags.getXmp(Xmp.NS_DC, "title"));
        return line.toString();
    }

    /**
     * Every asset that can be written takes every field it says it can keep, and gives each
     * back; the media itself is untouched. One that cannot be written refuses each.
     */
    @Test
    public void everyWritableAssetTakesEveryField() throws IOException, URISyntaxException {
        Location location = new Location("test");
        location.setLatitude(47.6);
        location.setLongitude(-122.3);
        for (File asset : assets()) {
            String name = asset.getName();
            File file = MediaFixtures.file(MediaFixtures.read(asset), "-" + name);
            MediaTags before = MediaTags.open(file);
            boolean title = before.setTitle("Titre édité");
            boolean description = before.setDescription("Described");
            boolean author = before.setAuthor("Ana Ñúñez");
            boolean copyright = before.setCopyright("CC0");
            boolean software = before.setSoftware("CameraView");
            // On a whole minute: ID3v2.3 keeps no seconds.
            boolean time = before.setCaptureTime(1_599_999_960_000L);
            boolean place = before.setLocation(location);
            boolean heading = before.setHeading(123.5f);
            boolean ambient = before.setAmbientConditions(new AmbientConditions.Builder()
                    .setTemperature(21.5).build());
            boolean xmp = before.setXmp(Xmp.NS_DC, "source", "Corpus");
            if (before.isReadOnly()) {
                assertFalse(name, title || description || author || copyright || software
                        || time || place || heading || ambient || xmp);
                continue;
            }
            before.save();
            MediaTags after = MediaTags.open(file);
            if (title) {
                assertEquals(name, "Titre édité", after.getTitle());
            }
            if (description) {
                assertEquals(name, "Described", after.getDescription());
            }
            if (author) {
                assertEquals(name, "Ana Ñúñez", after.getAuthor());
            }
            if (copyright) {
                assertEquals(name, "CC0", after.getCopyright());
            }
            if (software) {
                assertEquals(name, "CameraView", after.getSoftware());
            }
            if (time) {
                // WAV's creation date has no time of day.
                assertEquals(name, name.endsWith(".wav") ? 1_599_955_200_000L
                        : 1_599_999_960_000L, after.getCaptureTime());
            }
            if (place) {
                assertEquals(name, 47.6, after.getLocation().getLatitude(), 1e-4);
            }
            if (heading) {
                assertEquals(name, 123.5f, after.getHeading(), 0.01f);
            }
            if (ambient) {
                assertEquals(name, 21.5, after.getAmbientConditions().getTemperature(), 0.01);
            }
            if (xmp) {
                assertEquals(name, "Corpus", after.getXmp(Xmp.NS_DC, "source"));
            }
            MediaTags original = MediaTags.open(MediaFixtures.file(MediaFixtures.read(asset),
                    "-" + name));
            assertEquals(name, original.getRotation(), after.getRotation());
            assertEquals(name, original.getGainMap(), after.getGainMap());
            assertEquals(name, original.getVideoTransfer(), after.getVideoTransfer());
            assertEquals(name, original.getMotionPhoto() == null, after.getMotionPhoto() == null);
            assertEquals(name, original.isAnimated(), after.isAnimated());
            assertEquals(name, Arrays.toString(original.getVideoSize()),
                    Arrays.toString(after.getVideoSize()));

            // And taking it all away again leaves none of it.
            assertTrue(name, !title || after.setTitle(null));
            assertTrue(name, !time || after.setCaptureTime(-1));
            assertTrue(name, !place || after.setLocation(null));
            after.save();
            MediaTags cleared = MediaTags.open(file);
            if (title) {
                assertEquals(name, original.getTitle() == null ? null : cleared.getTitle(),
                        cleared.getTitle());
            }
        }
    }

    @Test
    public void everyFileSurvivesAnEdit() throws IOException {
        String[] writable = {"androidx_ss-motion-photo-shortened.jpg",
                "androidx_sample_MP.heic", "libavif_paris_exif_xmp_gainmap_littleendian.jpg",
                "skia_orientation_6.webp", "pngsuite_exif2c08_eXIf.png",
                "androidx_dolbyVision-hdr.mov", "chromium_spherical.mp4", "androidx_bear-id3.mp3",
                "androidx_bear_with_id3.flac", "androidx_bear.opus", "chromium_sfx.m4a",
                "libultrahdr_gainmap_hevc_16x16.heic", "libavif_clap_irot_imir_non_essential.avif"};
        for (String name : writable) {
            File file = MediaFixtures.file(bytes(name), "-" + name);
            MediaTags before = MediaTags.open(file);
            assertTrue(name, before.setTitle("Edited"));
            before.save();
            MediaTags after = MediaTags.open(file);
            assertEquals(name, "Edited", after.getTitle());
            assertEquals(name, before.getRotation(), after.getRotation());
            assertEquals(name, before.isMirrored(), after.isMirrored());
            assertEquals(name, before.getGainMap(), after.getGainMap());
            assertEquals(name, before.getVideoTransfer(), after.getVideoTransfer());
            assertEquals(name, before.getMotionPhoto() == null, after.getMotionPhoto() == null);
            if (before.getMotionPhoto() != null) {
                assertEquals(name, before.getMotionPhoto().getVideoLength(),
                        after.getMotionPhoto().getVideoLength());
                // The clip still ends the file, where its description says it is.
                assertEquals(name, file.length(), after.getMotionPhoto().getVideoOffset()
                        + after.getMotionPhoto().getVideoLength());
            }
            assertEquals(name, before.getSphericalVideo() == null,
                    after.getSphericalVideo() == null);
        }
    }
}
