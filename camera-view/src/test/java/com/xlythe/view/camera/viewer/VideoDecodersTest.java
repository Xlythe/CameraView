package com.xlythe.view.camera.viewer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecInfo.CodecProfileLevel;
import android.media.MediaFormat;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.MediaCodecInfoBuilder;
import org.robolectric.shadows.ShadowMediaCodecList;
import org.robolectric.shadows.ShadowMediaExtractor;
import org.robolectric.shadows.util.DataSource;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;

/** Whether a video's format, profile and level find a decoder, before it plays. */
@RunWith(RobolectricTestRunner.class)
public class VideoDecodersTest {
    /** androidx_sample.mp4's parameter sets: H.264 High, level 3.1. */
    private static final String AVC_HIGH_31 = "000000016764001facd9404405be5f011000003e90000ea60"
            + "0f18319600000000168ebe3cb22c0";

    /** chromium_testsrc-no-durations-h264.mkv's: H.264 Constrained Baseline, level 2.1. */
    private static final String AVC_CONSTRAINED_BASELINE_21 =
            "000000016742c0158c68141f201e1108d4";

    /** chromium_bear-1280x720-hevc-10bit-hdr10.mp4's: HEVC Main 10, main tier, level 3.1. */
    private static final String HEVC_MAIN10_31 = "0000000140010c01ffff0220000003009000000300000300"
            + "5d9598090000000142010102200000030090000003000003005da00280802d1365959a4932bc05a84"
            + "8804f0800001f480003a98040000000014401c172b46240";

    /** chromium_hevc-rext-10bit-422.mp4's sequence parameter set: HEVC range extensions. */
    private static final String HEVC_REXT_SPS =
            "000000014201010408000003009d0800000300005db00280802d1365959a4932bc05a80868082";

    /** androidx_h265_bframes.mp4's: HEVC Main, level 3. */
    private static final String HEVC_MAIN_3 =
            "0000000142010101600000030090000003000003005aa006b201e1d796566924cae68080";

    /** What Robolectric's extractor keys the test's file on. */
    private static final String SOURCE = "video-decoders-test";

    @Before
    public void setUp() {
        ShadowMediaCodecList.reset();
        ShadowMediaExtractor.reset();
        DataSource.setFileDescriptorTransform((descriptor, offset) -> SOURCE);
    }

    @After
    public void tearDown() {
        ShadowMediaCodecList.reset();
        ShadowMediaExtractor.reset();
        DataSource.reset();
    }

    @Test
    public void readsAnAvcProfileAndLevel() {
        assertArrayEquals(new int[] {CodecProfileLevel.AVCProfileHigh,
                CodecProfileLevel.AVCLevel31}, VideoDecoders.avcProfileLevel(hex(AVC_HIGH_31)));
        assertArrayEquals(new int[] {CodecProfileLevel.AVCProfileConstrainedBaseline,
                CodecProfileLevel.AVCLevel21},
                VideoDecoders.avcProfileLevel(hex(AVC_CONSTRAINED_BASELINE_21)));
        // Main at level 1b: level_idc 11 with constraint_set3.
        assertArrayEquals(new int[] {CodecProfileLevel.AVCProfileMain,
                CodecProfileLevel.AVCLevel1b}, VideoDecoders.avcProfileLevel(hex("674d100b")));
        // Constrained High: constraint_set4 and 5.
        assertArrayEquals(new int[] {CodecProfileLevel.AVCProfileConstrainedHigh,
                CodecProfileLevel.AVCLevel4}, VideoDecoders.avcProfileLevel(hex("67640c28")));
        assertNull(VideoDecoders.avcProfileLevel(hex("0000000168ebe3cb22c0")));
    }

    @Test
    public void readsAnHevcProfileAndLevel() {
        assertArrayEquals(new int[] {CodecProfileLevel.HEVCProfileMain10,
                CodecProfileLevel.HEVCMainTierLevel31},
                VideoDecoders.hevcProfileLevel(hex(HEVC_MAIN10_31)));
        assertArrayEquals(new int[] {CodecProfileLevel.HEVCProfileMain,
                CodecProfileLevel.HEVCMainTierLevel3},
                VideoDecoders.hevcProfileLevel(hex(HEVC_MAIN_3)));
        assertArrayEquals(new int[] {VideoDecoders.UNLISTED_PROFILE,
                CodecProfileLevel.HEVCMainTierLevel31},
                VideoDecoders.hevcProfileLevel(hex(HEVC_REXT_SPS)));
        // The same sequence parameter set in the high tier.
        byte[] high = hex(HEVC_MAIN_3);
        high[7] |= 0x20;
        assertArrayEquals(new int[] {CodecProfileLevel.HEVCProfileMain,
                CodecProfileLevel.HEVCHighTierLevel3}, VideoDecoders.hevcProfileLevel(high));
        // A unit without a start code.
        assertArrayEquals(new int[] {CodecProfileLevel.HEVCProfileMain,
                CodecProfileLevel.HEVCMainTierLevel3},
                VideoDecoders.hevcProfileLevel(slice(hex(HEVC_MAIN_3), 4)));
        assertNull(VideoDecoders.hevcProfileLevel(hex("000000014401c172b46240")));
    }

    @Test
    public void readsAProfileFromTheTrackItselfFirst() {
        MediaFormat avc = MediaFormat.createVideoFormat("video/avc", 1080, 720);
        avc.setByteBuffer("csd-0", ByteBuffer.wrap(hex(AVC_HIGH_31)));
        assertArrayEquals(new int[] {CodecProfileLevel.AVCProfileHigh,
                CodecProfileLevel.AVCLevel31}, VideoDecoders.profileLevel("video/avc", avc));
        avc.setInteger("profile", CodecProfileLevel.AVCProfileMain);
        assertArrayEquals(new int[] {CodecProfileLevel.AVCProfileMain, 0},
                VideoDecoders.profileLevel("video/avc", avc));

        MediaFormat hevc = MediaFormat.createVideoFormat("video/hevc", 1280, 720);
        hevc.setByteBuffer("csd-0", ByteBuffer.wrap(hex(HEVC_MAIN10_31)));
        assertArrayEquals(new int[] {CodecProfileLevel.HEVCProfileMain10,
                CodecProfileLevel.HEVCMainTierLevel31},
                VideoDecoders.profileLevel("video/hevc", hevc));

        // Other formats' decoders are taken at their word.
        MediaFormat mpeg4 = MediaFormat.createVideoFormat("video/mp4v-es", 176, 144);
        mpeg4.setInteger("profile", CodecProfileLevel.MPEG4ProfileSimple);
        assertNull(VideoDecoders.profileLevel("video/mp4v-es", mpeg4));
        assertNull(VideoDecoders.profileLevel("video/avc",
                MediaFormat.createVideoFormat("video/avc", 16, 16)));
    }

    /**
     * Android 4 and 5's software decoders list H.264 Baseline only, so High is turned away.
     * Android 5's plays Main, so Main is taken on its word.
     */
    @Test
    public void aBaselineDecoderTakesBaselineAndMainAndNotHigh() {
        CodecProfileLevel[] baseline = {level(CodecProfileLevel.AVCProfileBaseline,
                CodecProfileLevel.AVCLevel51)};
        assertFalse(VideoDecoders.supports("video/avc", CodecProfileLevel.AVCProfileHigh,
                CodecProfileLevel.AVCLevel31, baseline));
        assertTrue(VideoDecoders.supports("video/avc", CodecProfileLevel.AVCProfileMain,
                CodecProfileLevel.AVCLevel31, baseline));
        assertTrue(VideoDecoders.supports("video/avc",
                CodecProfileLevel.AVCProfileConstrainedBaseline, CodecProfileLevel.AVCLevel21,
                baseline));
    }

    @Test
    public void aProfileContainsTheProfilesWithinIt() {
        CodecProfileLevel[] high = {level(CodecProfileLevel.AVCProfileHigh,
                CodecProfileLevel.AVCLevel41)};
        assertTrue(VideoDecoders.supports("video/avc", CodecProfileLevel.AVCProfileMain,
                CodecProfileLevel.AVCLevel4, high));
        assertTrue(VideoDecoders.supports("video/avc", CodecProfileLevel.AVCProfileBaseline,
                CodecProfileLevel.AVCLevel3, high));
        assertTrue(VideoDecoders.supports("video/avc",
                CodecProfileLevel.AVCProfileConstrainedHigh, CodecProfileLevel.AVCLevel3, high));
        assertFalse(VideoDecoders.supports("video/avc", CodecProfileLevel.AVCProfileHigh10,
                CodecProfileLevel.AVCLevel3, high));
        assertFalse(VideoDecoders.supports("video/avc", CodecProfileLevel.AVCProfileExtended,
                CodecProfileLevel.AVCLevel3, high));
        assertTrue(VideoDecoders.contains("video/avc", CodecProfileLevel.AVCProfileHigh444,
                CodecProfileLevel.AVCProfileHigh10));
        assertTrue(VideoDecoders.contains("video/avc", CodecProfileLevel.AVCProfileHigh444,
                CodecProfileLevel.AVCProfileHigh422));

        CodecProfileLevel[] main10 = {level(CodecProfileLevel.HEVCProfileMain10HDR10,
                CodecProfileLevel.HEVCMainTierLevel51)};
        assertTrue(VideoDecoders.supports("video/hevc", CodecProfileLevel.HEVCProfileMain,
                CodecProfileLevel.HEVCMainTierLevel4, main10));
        assertTrue(VideoDecoders.supports("video/hevc", CodecProfileLevel.HEVCProfileMain10,
                CodecProfileLevel.HEVCMainTierLevel4, main10));
        CodecProfileLevel[] main = {level(CodecProfileLevel.HEVCProfileMain,
                CodecProfileLevel.HEVCMainTierLevel51)};
        assertFalse(VideoDecoders.supports("video/hevc", CodecProfileLevel.HEVCProfileMain10,
                CodecProfileLevel.HEVCMainTierLevel4, main));
        assertFalse(VideoDecoders.supports("video/hevc", VideoDecoders.UNLISTED_PROFILE,
                CodecProfileLevel.HEVCMainTierLevel31, main10));

        CodecProfileLevel[] vp9 = {level(CodecProfileLevel.VP9Profile0,
                CodecProfileLevel.VP9Level5)};
        assertFalse(VideoDecoders.supports("video/x-vnd.on2.vp9", CodecProfileLevel.VP9Profile2,
                CodecProfileLevel.VP9Level31, vp9));
        assertTrue(VideoDecoders.contains("video/x-vnd.on2.vp9",
                CodecProfileLevel.VP9Profile2HDR, CodecProfileLevel.VP9Profile2));
        assertTrue(VideoDecoders.contains("video/x-vnd.on2.vp9",
                CodecProfileLevel.VP9Profile3HDR10Plus, CodecProfileLevel.VP9Profile3));
        CodecProfileLevel[] av1 = {level(CodecProfileLevel.AV1ProfileMain8,
                CodecProfileLevel.AV1Level51)};
        assertFalse(VideoDecoders.supports("video/av01", CodecProfileLevel.AV1ProfileMain10,
                CodecProfileLevel.AV1Level31, av1));
        assertTrue(VideoDecoders.contains("video/av01", CodecProfileLevel.AV1ProfileMain10HDR10,
                CodecProfileLevel.AV1ProfileMain10));
        assertFalse(VideoDecoders.contains("video/mp4v-es", 1, 2));
    }

    @Test
    public void aLevelAboveTheDecodersIsTurnedAway() {
        CodecProfileLevel[] high3 = {level(CodecProfileLevel.AVCProfileHigh,
                CodecProfileLevel.AVCLevel3)};
        assertFalse(VideoDecoders.supports("video/avc", CodecProfileLevel.AVCProfileHigh,
                CodecProfileLevel.AVCLevel31, high3));
        assertTrue(VideoDecoders.supports("video/avc", CodecProfileLevel.AVCProfileHigh, 0,
                high3));
        // A decoder that lists nothing is taken at its word.
        assertTrue(VideoDecoders.supports("video/avc", CodecProfileLevel.AVCProfileHigh,
                CodecProfileLevel.AVCLevel31, new CodecProfileLevel[0]));
        assertTrue(VideoDecoders.supports("video/avc", CodecProfileLevel.AVCProfileHigh,
                CodecProfileLevel.AVCLevel31, null));
    }

    /**
     * Android 4's software decoder lists H.264 Baseline only, so a High file finds no decoder; an
     * encoder of High is no help. Robolectric builds its codec list from Android 10, and reads it
     * once a test, so each list is a test of its own.
     */
    @Test
    @Config(minSdk = 29)
    public void aHighFileFindsNoDecoderInABaselineOne() throws IOException {
        addDecoder("video/avc", CodecProfileLevel.AVCProfileBaseline, false);
        addDecoder("video/avc", CodecProfileLevel.AVCProfileHigh, true);
        assertFalse(VideoDecoders.canDecode(ApplicationProvider.getApplicationContext(),
                highFile(), -1, -1));
    }

    @Test
    @Config(minSdk = 29)
    public void aHighFileFindsAHighDecoder() throws IOException {
        addDecoder("video/avc", CodecProfileLevel.AVCProfileBaseline, false);
        addDecoder("video/avc", CodecProfileLevel.AVCProfileHigh, false);
        assertTrue(VideoDecoders.canDecode(ApplicationProvider.getApplicationContext(),
                highFile(), 0, 4));
    }

    @Test
    @Config(minSdk = 29)
    public void aFileFindsNoDecoderWhereThereIsNoneForItsFormat() throws IOException {
        assertFalse(VideoDecoders.canDecode(ApplicationProvider.getApplicationContext(),
                highFile(), -1, -1));
    }

    /** A file Robolectric's extractor reads as sound and H.264 High at level 3.1. */
    private static ImageLocation highFile() throws IOException {
        File file = File.createTempFile("video-decoders", ".mp4");
        file.deleteOnExit();
        Files.write(file.toPath(), new byte[] {0, 0, 0, 8});
        MediaFormat audio = MediaFormat.createAudioFormat("audio/mp4a-latm", 44100, 2);
        MediaFormat video = MediaFormat.createVideoFormat("video/avc", 1080, 720);
        video.setByteBuffer("csd-0", ByteBuffer.wrap(hex(AVC_HIGH_31)));
        ShadowMediaExtractor.addTrack(DataSource.toDataSource(SOURCE), audio, new byte[0]);
        ShadowMediaExtractor.addTrack(DataSource.toDataSource(SOURCE), video, new byte[0]);
        return ImageLocation.of(file);
    }

    @Test
    @Config(minSdk = 29)
    public void aFileThatCannotBeReadIsLeftToThePlayer() {
        Context context = ApplicationProvider.getApplicationContext();
        assertTrue(VideoDecoders.canDecode(context,
                ImageLocation.of(new File("/nowhere/at/all.mp4")), -1, -1));
    }

    /** A video the platform reads no video track from, as Android 9 an AV1 MP4, fails. */
    @Test
    @Config(minSdk = 29)
    public void aVideoThePlatformFindsNoVideoInFails() throws IOException {
        File file = File.createTempFile("video-decoders", ".mp4");
        file.deleteOnExit();
        Files.write(file.toPath(), new byte[] {0, 0, 0, 8});
        ShadowMediaExtractor.addTrack(DataSource.toDataSource(SOURCE),
                MediaFormat.createAudioFormat("audio/mp4a-latm", 44100, 2), new byte[0]);
        addDecoder("video/avc", CodecProfileLevel.AVCProfileHigh, false);
        assertFalse(VideoDecoders.canDecode(ApplicationProvider.getApplicationContext(),
                ImageLocation.of(file), -1, -1));
    }

    private static void addDecoder(String mime, int profile, boolean encoder) {
        MediaFormat format = new MediaFormat();
        format.setString(MediaFormat.KEY_MIME, mime);
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder()
                .setName("test." + profile + (encoder ? ".encoder" : ".decoder"))
                .setIsEncoder(encoder)
                .setCapabilities(MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                        .setMediaFormat(format)
                        .setIsEncoder(encoder)
                        .setProfileLevels(new CodecProfileLevel[] {
                                level(profile, CodecProfileLevel.AVCLevel51)})
                        .setColorFormats(new int[] {
                                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible})
                        .build())
                .build());
    }

    private static CodecProfileLevel level(int profile, int level) {
        CodecProfileLevel entry = new CodecProfileLevel();
        entry.profile = profile;
        entry.level = level;
        return entry;
    }

    private static byte[] slice(byte[] bytes, int from) {
        byte[] out = new byte[bytes.length - from];
        System.arraycopy(bytes, from, out, 0, out.length);
        return out;
    }

    private static byte[] hex(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return bytes;
    }
}
