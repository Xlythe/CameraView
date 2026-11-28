package com.xlythe.view.camera.stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Looper;
import android.util.Size;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.MediaCodecInfoBuilder;
import org.robolectric.shadows.ShadowMediaCodecList;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The recorder behind motion photos, fed frames by hand on a clock the test keeps, so that where a
 * clip starts and ends is decided by the frames' timestamps and nothing else.
 *
 * <p>Frames come 100 ms apart with a key frame on every whole second, stamped on whichever clock
 * a test says the camera uses.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class MotionPhotoRecorderTest {
    private static final long SECOND_US = 1_000_000L;
    private static final long FRAME_US = 100_000L;

    /** The time on both clocks, which a test moves forward. */
    private static final class FakeClock implements MotionPhotoRecorder.Clock {
        long monotonicNanos = 50L * 1_000_000_000L;
        long bootNanos = 80L * 1_000_000_000L;

        @Override
        public long monotonicNanos() {
            return monotonicNanos;
        }

        @Override
        public long bootNanos() {
            return bootNanos;
        }

        void advanceUs(long us) {
            monotonicNanos += us * 1000;
            bootNanos += us * 1000;
        }
    }

    private FakeClock mClock;
    private MotionPhotoRecorder mRecorder;
    /** Every clip delivered, in order; a null entry is a capture that came to nothing. */
    private final List<MotionPhotoRecorder.Clip> mClips = new ArrayList<>();
    private int mDeliveries;

    @Before
    public void setUp() {
        ShadowMediaCodecList.reset();
        mClock = new FakeClock();
        mRecorder = new MotionPhotoRecorder(mClock);
        mRecorder.onFormat(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 640, 480));
    }

    @After
    public void tearDown() {
        mRecorder.release();
        ShadowMediaCodecList.reset();
    }

    private final MotionPhotoRecorder.ClipListener mListener = clip -> {
        mDeliveries++;
        mClips.add(clip);
    };

    /** Feeds frames for a while, on the monotonic clock, starting now. */
    private void feedMonotonic(long durationUs) {
        feed(durationUs, 0);
    }

    /**
     * Feeds frames for a while, each stamped at the monotonic time it arrives plus an offset, with
     * a key frame whenever the stamp is a whole second.
     */
    private void feed(long durationUs, long offsetUs) {
        for (long elapsed = 0; elapsed < durationUs; elapsed += FRAME_US) {
            mClock.advanceUs(FRAME_US);
            long stamp = mClock.monotonicNanos / 1000 + offsetUs;
            mRecorder.onFrame(null, new byte[] {1, 2, 3}, stamp,
                    stamp % SECOND_US == 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
        }
    }

    private void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void noClipBeforeAnyFrame() {
        assertFalse(mRecorder.isLive());
        assertNull(mRecorder.capture(mListener));
    }

    @Test
    public void aClipRunsFromTheKeyFrameBeforeThePreRollToTheEndOfThePostRoll() {
        feedMonotonic(3 * SECOND_US);
        long shutterUs = mClock.monotonicNanos / 1000;
        assertTrue(mRecorder.isLive());

        assertNotNull(mRecorder.capture(mListener));
        feedMonotonic(MotionPhotoRecorder.POST_ROLL_US - FRAME_US);
        idle();
        assertEquals("not before the post-roll is in", 0, mDeliveries);
        feedMonotonic(FRAME_US);
        idle();

        MotionPhotoRecorder.Clip clip = mClips.get(0);
        long preRollStart = shutterUs - MotionPhotoRecorder.PRE_ROLL_US;
        long keyFrame = preRollStart - Math.floorMod(preRollStart, SECOND_US);
        assertEquals(keyFrame, clip.getStartTimeUs());
        assertEquals(shutterUs, clip.getShutterTimeUs());
        assertEquals(shutterUs - keyFrame, clip.getPresentationTimestampUs());
        assertEquals(shutterUs + MotionPhotoRecorder.POST_ROLL_US - keyFrame,
                clip.getDurationUs());
        assertEquals(clip.getDurationUs() / FRAME_US + 1, clip.getFrameCount());
        assertTrue(clip.getPresentationTimestampUs() >= MotionPhotoRecorder.PRE_ROLL_US);
        assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, clip.getMimeType());
        assertEquals(0, clip.getRotationDegrees());
    }

    /**
     * The old camera API stops its preview while it takes a picture. The post-roll waits that out,
     * and the clip is closed up across it so it plays straight through.
     */
    @Test
    public void aStallAfterTheShutterIsWaitedOutAndClosedUp() {
        mRecorder.setMaxGapUs(MotionPhotoRecorder.MAX_BYTE_FRAME_GAP_US);
        feedMonotonic(3 * SECOND_US);
        long shutterUs = mClock.monotonicNanos / 1000;
        mRecorder.capture(mListener);

        mClock.advanceUs(1_200_000);
        feedMonotonic(MotionPhotoRecorder.POST_ROLL_US);
        idle();
        assertEquals("the stall does not count towards the post-roll", 0, mDeliveries);
        feedMonotonic(2 * FRAME_US);
        idle();

        MotionPhotoRecorder.Clip clip = mClips.get(0);
        assertEquals("the shutter is before the stall, so it is where it was",
                shutterUs, clip.getShutterTimeUs());
        long afterUs = clip.getDurationUs() - clip.getPresentationTimestampUs();
        assertTrue("about the post-roll after the still, not the stall: " + afterUs,
                afterUs >= MotionPhotoRecorder.POST_ROLL_US - 2 * FRAME_US
                        && afterUs <= MotionPhotoRecorder.POST_ROLL_US);
        assertTrue(clip.getPresentationTimestampUs() >= MotionPhotoRecorder.PRE_ROLL_US);
    }

    @Test
    public void theShutterIsPlacedOnTheClockTheFramesAreStampedWith() {
        long bootAheadUs = (mClock.bootNanos - mClock.monotonicNanos) / 1000;
        feed(3 * SECOND_US, bootAheadUs);
        long shutterUs = mClock.bootNanos / 1000;

        mRecorder.capture(mListener);
        feed(MotionPhotoRecorder.POST_ROLL_US, bootAheadUs);
        idle();

        assertEquals(shutterUs, mClips.get(0).getShutterTimeUs());
    }

    @Test
    public void anUnknownClockIsReadRelativeToTheNewestFrame() {
        long offsetUs = 500L * SECOND_US;
        feed(3 * SECOND_US, offsetUs);
        long shutterUs = mClock.monotonicNanos / 1000 + offsetUs;

        mRecorder.capture(mListener);
        feed(MotionPhotoRecorder.POST_ROLL_US, offsetUs);
        idle();

        assertEquals(shutterUs, mClips.get(0).getShutterTimeUs());
    }

    @Test
    public void framesThatStoppedMakeNoClip() {
        feedMonotonic(2 * SECOND_US);
        mClock.advanceUs(2 * SECOND_US);

        assertFalse(mRecorder.isLive());
        assertNull(mRecorder.capture(mListener));
    }

    @Test
    public void aPostRollThatNeverArrivesEndsWithTheFramesThereAre() {
        feedMonotonic(3 * SECOND_US);
        long lastFrameUs = mClock.monotonicNanos / 1000;

        mRecorder.capture(mListener);
        shadowOf(Looper.getMainLooper()).idleFor(
                MotionPhotoRecorder.POST_ROLL_US / 1000 + MotionPhotoRecorder.CAPTURE_SLACK_MS,
                TimeUnit.MILLISECONDS);
        idle();

        assertEquals(1, mDeliveries);
        MotionPhotoRecorder.Clip clip = mClips.get(0);
        assertEquals(lastFrameUs, clip.getStartTimeUs() + clip.getDurationUs());
        assertEquals("the still is at the end of a clip that stops there",
                clip.getDurationUs(), clip.getPresentationTimestampUs());
    }

    @Test
    public void aCancelledCaptureIsNeverDelivered() {
        feedMonotonic(3 * SECOND_US);

        mRecorder.capture(mListener).cancel();
        feedMonotonic(2 * SECOND_US);
        shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.SECONDS);

        assertEquals(0, mDeliveries);
    }

    @Test
    public void releasingFinishesWaitingCapturesAndRefusesMore() {
        feedMonotonic(3 * SECOND_US);
        mRecorder.capture(mListener);

        mRecorder.release();
        idle();

        assertEquals(1, mDeliveries);
        assertNotNull("the frames already in make the clip", mClips.get(0));
        assertNull(mRecorder.capture(mListener));
        try {
            mRecorder.getInputSurface(640, 480, 90, MotionPhotoRecorder.INPUT_CAMERA);
            fail("a released recorder starts no encoder");
        } catch (IllegalStateException expected) {
            // As it should.
        }
        // Frames handed over as bytes are dropped rather than thrown back at the camera.
        mRecorder.queueFrame(new byte[640 * 480 * 3 / 2], 640, 480, 90, false);
    }

    @Test
    public void aCaptureWithNoFramesLeftIsDeliveredAsNothing() {
        feedMonotonic(3 * SECOND_US);
        mRecorder.capture(mListener);
        // A frame from far later is a new video, which drops everything held.
        mClock.advanceUs(5 * SECOND_US);
        mRecorder.onFrame(null, new byte[1], mClock.monotonicNanos / 1000 + 1, 0);

        mRecorder.release();
        idle();

        assertEquals(1, mDeliveries);
        assertNull(mClips.get(0));
    }

    @Test
    public void tellsTheClocksApart() {
        assertEquals(MotionPhotoRecorder.CLOCK_MONOTONIC,
                MotionPhotoRecorder.frameClockOf(10_000_100, 10_000_000, 90_000_000));
        assertEquals(MotionPhotoRecorder.CLOCK_BOOTTIME,
                MotionPhotoRecorder.frameClockOf(90_000_100, 10_000_000, 90_000_000));
        assertEquals(MotionPhotoRecorder.CLOCK_UNKNOWN,
                MotionPhotoRecorder.frameClockOf(500_000_000, 10_000_000, 90_000_000));
        assertEquals("nearer the monotonic clock when both are close",
                MotionPhotoRecorder.CLOCK_MONOTONIC,
                MotionPhotoRecorder.frameClockOf(1_000, 1_100, 1_500));
    }

    @Test
    public void placesTheShutterOnEachClock() {
        assertEquals(5, MotionPhotoRecorder.shutterUs(
                MotionPhotoRecorder.CLOCK_MONOTONIC, 5_000, 9_000, 0));
        assertEquals(9, MotionPhotoRecorder.shutterUs(
                MotionPhotoRecorder.CLOCK_BOOTTIME, 5_000, 9_000, 0));
        assertEquals(105, MotionPhotoRecorder.shutterUs(
                MotionPhotoRecorder.CLOCK_UNKNOWN, 5_000, 9_000, 100));
    }

    @Test
    public void mirroringAQuarterTurnedFrameFlipsItsRows() {
        assertFalse(MotionPhotoRecorder.mirrorFlipsRows(0));
        assertTrue(MotionPhotoRecorder.mirrorFlipsRows(90));
        assertFalse(MotionPhotoRecorder.mirrorFlipsRows(180));
        assertTrue(MotionPhotoRecorder.mirrorFlipsRows(270));
        assertTrue(MotionPhotoRecorder.mirrorFlipsRows(-90));
    }

    /** A 4x2 NV21 frame: luma 0-7 row by row, then one row of V,U pairs: V 20, U 30, V 21, U 31. */
    private static byte[] nv21() {
        return new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 20, 30, 21, 31};
    }

    @Test
    public void copiesAnNv21FrameIntoPlanarPlanes() {
        ByteBuffer y = ByteBuffer.allocate(8);
        ByteBuffer u = ByteBuffer.allocate(2);
        ByteBuffer v = ByteBuffer.allocate(2);

        MotionPhotoRecorder.copyNv21(nv21(), 4, 2, 1, 4, 2, false, false,
                new MotionPhotoRecorder.Plane(y, 4, 1),
                new MotionPhotoRecorder.Plane(u, 2, 1),
                new MotionPhotoRecorder.Plane(v, 2, 1));

        assertArrayEquals(new byte[] {0, 1, 2, 3, 4, 5, 6, 7}, y.array());
        assertArrayEquals(new byte[] {30, 31}, u.array());
        assertArrayEquals(new byte[] {20, 21}, v.array());
    }

    @Test
    public void copiesIntoInterleavedPlanesWithPaddedRows() {
        ByteBuffer y = ByteBuffer.allocate(12);
        ByteBuffer uv = ByteBuffer.allocate(4);

        MotionPhotoRecorder.copyNv21(nv21(), 4, 2, 1, 4, 2, false, false,
                new MotionPhotoRecorder.Plane(y, 6, 1),
                new MotionPhotoRecorder.Plane(uv, 4, 2),
                new MotionPhotoRecorder.Plane(sliceFrom(uv, 1), 4, 2));

        assertArrayEquals(new byte[] {0, 1, 2, 3, 0, 0, 4, 5, 6, 7, 0, 0}, y.array());
        assertArrayEquals("NV12, U first", new byte[] {30, 20, 31, 21}, uv.array());
    }

    @Test
    public void mirrorsColumnsOrRows() {
        ByteBuffer y = ByteBuffer.allocate(8);
        ByteBuffer u = ByteBuffer.allocate(2);
        ByteBuffer v = ByteBuffer.allocate(2);
        MotionPhotoRecorder.copyNv21(nv21(), 4, 2, 1, 4, 2, false, true,
                new MotionPhotoRecorder.Plane(y, 4, 1),
                new MotionPhotoRecorder.Plane(u, 2, 1),
                new MotionPhotoRecorder.Plane(v, 2, 1));
        assertArrayEquals(new byte[] {3, 2, 1, 0, 7, 6, 5, 4}, y.array());
        assertArrayEquals(new byte[] {31, 30}, u.array());
        assertArrayEquals(new byte[] {21, 20}, v.array());

        ByteBuffer flipped = ByteBuffer.allocate(8);
        MotionPhotoRecorder.copyNv21(nv21(), 4, 2, 1, 4, 2, true, false,
                new MotionPhotoRecorder.Plane(flipped, 4, 1),
                new MotionPhotoRecorder.Plane(ByteBuffer.allocate(2), 2, 1),
                new MotionPhotoRecorder.Plane(ByteBuffer.allocate(2), 2, 1));
        assertArrayEquals(new byte[] {4, 5, 6, 7, 0, 1, 2, 3}, flipped.array());
    }

    @Test
    public void shrinksAFrameBySkippingPixels() {
        // 8x4: luma is its index, and the chroma rows hold V,U pairs 100+i, 200+i.
        byte[] frame = new byte[8 * 4 * 3 / 2];
        for (int i = 0; i < 32; i++) {
            frame[i] = (byte) i;
        }
        for (int i = 0; i < 8; i++) {
            frame[32 + 2 * i] = (byte) (100 + i);
            frame[33 + 2 * i] = (byte) (200 + i);
        }
        ByteBuffer y = ByteBuffer.allocate(8);
        ByteBuffer u = ByteBuffer.allocate(2);
        ByteBuffer v = ByteBuffer.allocate(2);

        MotionPhotoRecorder.copyNv21(frame, 8, 4, 2, 4, 2, false, true,
                new MotionPhotoRecorder.Plane(y, 4, 1),
                new MotionPhotoRecorder.Plane(u, 2, 1),
                new MotionPhotoRecorder.Plane(v, 2, 1));

        assertArrayEquals("every other pixel of every other row, back to front",
                new byte[] {6, 4, 2, 0, 22, 20, 18, 16}, y.array());
        assertArrayEquals(new byte[] {(byte) 202, (byte) 200}, u.array());
        assertArrayEquals(new byte[] {102, 100}, v.array());
    }

    @Test
    public void shrinksOnlyAFrameTooLargeToEncode() {
        assertEquals(1, MotionPhotoRecorder.byteFrameStep(1280, 720, new Size(1280, 720)));
        assertEquals("either way round", 1,
                MotionPhotoRecorder.byteFrameStep(720, 1280, new Size(1280, 720)));
        assertEquals(2, MotionPhotoRecorder.byteFrameStep(1920, 1080, new Size(1280, 720)));
        assertEquals(8, MotionPhotoRecorder.byteFrameStep(4000, 3000, new Size(1280, 720)));
    }

    /** A view of a buffer starting a byte in, as an encoder's V plane overlaps its U plane. */
    private static ByteBuffer sliceFrom(ByteBuffer buffer, int offset) {
        ByteBuffer view = buffer.duplicate();
        view.position(offset);
        return view.slice();
    }

    @Test
    public void budgetsTheBitRateByCodecAndSize() {
        assertEquals(4_354_560,
                MotionPhotoRecorder.bitRateFor(MediaFormat.MIMETYPE_VIDEO_HEVC, 1920, 1080));
        assertEquals(6_220_800,
                MotionPhotoRecorder.bitRateFor(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080));
        assertEquals("a floor for a tiny frame", 1_000_000,
                MotionPhotoRecorder.bitRateFor(MediaFormat.MIMETYPE_VIDEO_AVC, 160, 120));
        assertEquals("a ceiling for a huge one", 20_000_000,
                MotionPhotoRecorder.bitRateFor(MediaFormat.MIMETYPE_VIDEO_AVC, 7680, 4320));
    }

    @Test
    public void recognisesThePlatformsSoftwareCodecsByName() {
        assertFalse(MotionPhotoRecorder.isHardwareName("OMX.google.h264.encoder"));
        assertFalse(MotionPhotoRecorder.isHardwareName("c2.android.avc.encoder"));
        assertFalse(MotionPhotoRecorder.isHardwareName("c2.google.hevc.encoder"));
        assertFalse(MotionPhotoRecorder.isHardwareName("c2.vendor.sw.avc.encoder"));
        assertTrue(MotionPhotoRecorder.isHardwareName("c2.exynos.hevc.encoder"));
        assertTrue(MotionPhotoRecorder.isHardwareName("OMX.qcom.video.encoder.avc"));
    }

    private static MediaCodecInfo encoder(String name, String mime, boolean hardware,
            int... colorFormats) {
        return MediaCodecInfoBuilder.newBuilder()
                .setName(name)
                .setIsEncoder(true)
                .setIsHardwareAccelerated(hardware)
                .setIsSoftwareOnly(!hardware)
                .setCapabilities(MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                        .setMediaFormat(MediaFormat.createVideoFormat(mime, 1920, 1080))
                        .setIsEncoder(true)
                        .setColorFormats(colorFormats)
                        .build())
                .build();
    }

    /**
     * Lists encoders, from scratch. The platform reads its codec list once and keeps it, as the
     * shadow does, so each list a test wants is set up before anything asks.
     */
    private static void listEncoders(MediaCodecInfo... encoders) {
        ShadowMediaCodecList.reset();
        for (MediaCodecInfo encoder : encoders) {
            ShadowMediaCodecList.addCodec(encoder);
        }
    }

    @Test
    public void prefersHardwareHevcThenHardwareAvcThenSoftwareAvc() {
        int surface = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface;
        MediaCodecInfo softwareHevc = encoder("c2.android.hevc.encoder",
                MediaFormat.MIMETYPE_VIDEO_HEVC, false, surface);
        MediaCodecInfo softwareAvc = encoder("c2.android.avc.encoder",
                MediaFormat.MIMETYPE_VIDEO_AVC, false, surface);
        MediaCodecInfo hardwareAvc = encoder("c2.vendor.avc.encoder",
                MediaFormat.MIMETYPE_VIDEO_AVC, true, surface);
        MediaCodecInfo hardwareHevc = encoder("c2.vendor.hevc.encoder",
                MediaFormat.MIMETYPE_VIDEO_HEVC, true, surface);

        listEncoders(softwareHevc, softwareAvc);
        assertEquals("c2.android.avc.encoder", MotionPhotoRecorder.chooseCodec(
                null, MotionPhotoRecorder.INPUT_CAMERA).name);
        assertEquals("no hardware, so the smaller budget",
                MotionPhotoRecorder.MAX_SOFTWARE_SIZE, new MotionPhotoRecorder().getMaxSize());
        assertTrue(MotionPhotoRecorder.isSupported());

        listEncoders(softwareHevc, softwareAvc, hardwareAvc);
        assertEquals("c2.vendor.avc.encoder", MotionPhotoRecorder.chooseCodec(
                null, MotionPhotoRecorder.INPUT_CAMERA).name);

        listEncoders(softwareHevc, softwareAvc, hardwareAvc, hardwareHevc);
        MotionPhotoRecorder.CodecChoice choice = MotionPhotoRecorder.chooseCodec(
                null, MotionPhotoRecorder.INPUT_CAMERA);
        assertEquals("c2.vendor.hevc.encoder", choice.name);
        assertEquals(MediaFormat.MIMETYPE_VIDEO_HEVC, choice.mime);
        assertTrue(choice.hardware);
        assertEquals(MotionPhotoRecorder.MAX_HARDWARE_SIZE,
                new MotionPhotoRecorder().getMaxSize());

        listEncoders(softwareHevc);
        assertEquals("software HEVC only when there is nothing else", "c2.android.hevc.encoder",
                MotionPhotoRecorder.chooseCodec(null, MotionPhotoRecorder.INPUT_CAMERA).name);
    }

    @Test
    public void bytesNeedAnEncoderThatTakesFlexibleYuv() {
        MediaCodecInfo surfaceOnly = encoder("c2.vendor.hevc.encoder",
                MediaFormat.MIMETYPE_VIDEO_HEVC, true,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        listEncoders(surfaceOnly);
        assertNull(MotionPhotoRecorder.chooseCodec(null, MotionPhotoRecorder.INPUT_BYTES));
        assertFalse(MotionPhotoRecorder.isByteInputSupported());

        listEncoders(surfaceOnly, encoder("c2.android.avc.encoder",
                MediaFormat.MIMETYPE_VIDEO_AVC, false,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible));
        assertEquals("c2.android.avc.encoder", MotionPhotoRecorder.chooseCodec(
                null, MotionPhotoRecorder.INPUT_BYTES).name);
        assertTrue(MotionPhotoRecorder.isByteInputSupported());
    }

    @Test
    public void ignoresDecodersAndOtherFormats() {
        listEncoders(MediaCodecInfoBuilder.newBuilder()
                        .setName("c2.vendor.avc.decoder")
                        .setIsEncoder(false)
                        .setCapabilities(MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                                .setMediaFormat(MediaFormat.createVideoFormat(
                                        MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080))
                                .setIsEncoder(false)
                                .setColorFormats(new int[] {
                                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface})
                                .build())
                        .build(),
                encoder("c2.vendor.vp9.encoder", MediaFormat.MIMETYPE_VIDEO_VP9, true,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface));

        assertNull(MotionPhotoRecorder.chooseCodec(null, MotionPhotoRecorder.INPUT_CAMERA));
        assertFalse(MotionPhotoRecorder.isSupported());
    }

    @Test
    public void noEncoderAtAllIsNoMotionPhoto() {
        assertNull(MotionPhotoRecorder.chooseCodec(null, MotionPhotoRecorder.INPUT_CAMERA));
        try {
            new MotionPhotoRecorder(mClock).getInputSurface(
                    640, 480, 0, MotionPhotoRecorder.INPUT_CAMERA);
            fail("there is no encoder to start");
        } catch (IllegalStateException expected) {
            // As it should.
        }
    }
}
