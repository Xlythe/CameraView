package com.xlythe.view.camera.stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.media.MediaFormat;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Which format two devices share, and what each format needs on the wire.
 *
 * <p>The choice is a function of four things - what the receiver said it plays, the rate the
 * microphone settled on, the sender's release and the sender's codecs - so it is checked here as
 * that function, across releases a Robolectric run cannot be all of at once.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class AudioCodecTest {
    /** A device with every codec. */
    private static final AudioCodec.Query EVERYTHING = (mimeType, encoder) -> true;

    /** A device with none. */
    private static final AudioCodec.Query NOTHING = (mimeType, encoder) -> false;

    /** Everything a current receiver lists. */
    private static final int[] ALL = {AudioCodec.OPUS, AudioCodec.AAC, AudioCodec.PCM};

    @Before
    public void setUp() {
        FakeAudioCodecs.reset();
    }

    @After
    public void tearDown() {
        FakeAudioCodecs.reset();
    }

    /** A device with only the listed codecs. */
    private static AudioCodec.Query only(String... entries) {
        Set<String> set = new HashSet<>(Arrays.asList(entries));
        return (mimeType, encoder) -> set.contains((encoder ? "enc:" : "dec:") + mimeType);
    }

    @Test
    public void aReceiverThatSaidNothingGetsPcm() {
        assertEquals(AudioCodec.PCM, AudioCodec.choose(null, 16000, 1, 37, EVERYTHING));
    }

    @Test
    public void opusIsPreferredWhereBothEndsHaveIt() {
        assertEquals(AudioCodec.OPUS, AudioCodec.choose(ALL, 16000, 1, 37, EVERYTHING));
        assertEquals(AudioCodec.OPUS, AudioCodec.choose(ALL, 48000, 2, 29, EVERYTHING));
    }

    /** The platform only encodes Opus from API 29, so an older sender falls back to AAC. */
    @Test
    public void aSenderBelowApi29SendsAac() {
        assertEquals(AudioCodec.AAC, AudioCodec.choose(ALL, 16000, 1, 28, EVERYTHING));
        assertEquals(AudioCodec.AAC, AudioCodec.choose(ALL, 16000, 1, 16, EVERYTHING));
    }

    /** Below MediaCodec there is nothing to compress with at all. */
    @Test
    public void aSenderWithoutMediaCodecSendsPcm() {
        assertEquals(AudioCodec.PCM, AudioCodec.choose(ALL, 16000, 1, 15, EVERYTHING));
        assertEquals(AudioCodec.PCM, AudioCodec.choose(ALL, 16000, 1, 14, EVERYTHING));
    }

    /** What the receiver did not list is never sent, however good. */
    @Test
    public void onlyWhatTheReceiverListedIsSent() {
        assertEquals(AudioCodec.AAC, AudioCodec.choose(
                new int[] {AudioCodec.AAC, AudioCodec.PCM}, 16000, 1, 37, EVERYTHING));
        assertEquals(AudioCodec.PCM, AudioCodec.choose(
                new int[] {AudioCodec.PCM}, 16000, 1, 37, EVERYTHING));
        assertEquals("a receiver that lists nothing still reads the header, and gets PCM",
                AudioCodec.PCM, AudioCodec.choose(new int[0], 16000, 1, 37, EVERYTHING));
    }

    /** A sender missing the encoder for the best shared format moves down the list. */
    @Test
    public void aMissingEncoderFallsThrough() {
        assertEquals(AudioCodec.AAC, AudioCodec.choose(ALL, 16000, 1, 37,
                only("enc:" + MediaFormat.MIMETYPE_AUDIO_AAC)));
        assertEquals(AudioCodec.PCM, AudioCodec.choose(ALL, 16000, 1, 37, NOTHING));
    }

    /** Opus is defined at five rates; a recorder that settled on another sends AAC. */
    @Test
    public void opusOnlyAtItsOwnRates() {
        for (int rate : new int[] {8000, 12000, 16000, 24000, 48000}) {
            assertEquals("at " + rate, AudioCodec.OPUS,
                    AudioCodec.choose(ALL, rate, 1, 37, EVERYTHING));
        }
        for (int rate : new int[] {11025, 22050, 44100}) {
            assertEquals("at " + rate, AudioCodec.AAC,
                    AudioCodec.choose(ALL, rate, 1, 37, EVERYTHING));
        }
    }

    @Test
    public void moreThanTwoChannelsIsOnlyPcm() {
        assertEquals(AudioCodec.PCM, AudioCodec.choose(ALL, 16000, 6, 37, EVERYTHING));
        assertFalse(AudioCodec.canEncode(AudioCodec.AAC, 16000, 0, 37, EVERYTHING));
        assertTrue(AudioCodec.canEncode(AudioCodec.PCM, 16000, 6, 37, NOTHING));
        assertFalse(AudioCodec.canEncode(99, 16000, 1, 37, EVERYTHING));
    }

    /** A rate outside AAC's table cannot be described to its decoder, so it is not encoded. */
    @Test
    public void aacOnlyAtRatesItsConfigCanName() {
        assertFalse(AudioCodec.canEncode(AudioCodec.AAC, 9000, 1, 37, EVERYTHING));
        assertTrue(AudioCodec.canEncode(AudioCodec.AAC, 11025, 1, 37, EVERYTHING));
    }

    /** Decoding Opus goes back further than encoding it. */
    @Test
    public void decodingFollowsItsOwnReleases() {
        assertTrue(AudioCodec.canDecode(AudioCodec.PCM, 14, NOTHING));
        assertFalse(AudioCodec.canDecode(AudioCodec.AAC, 15, EVERYTHING));
        assertTrue(AudioCodec.canDecode(AudioCodec.AAC, 16, EVERYTHING));
        assertFalse(AudioCodec.canDecode(AudioCodec.OPUS, 20, EVERYTHING));
        assertTrue(AudioCodec.canDecode(AudioCodec.OPUS, 21, EVERYTHING));
        assertFalse(AudioCodec.canDecode(AudioCodec.OPUS, 37, NOTHING));
        assertFalse(AudioCodec.canDecode(99, 37, EVERYTHING));
    }

    @Test
    public void onlyTheThreeFormatsAreKnown() {
        assertTrue(AudioCodec.isKnown(AudioCodec.PCM));
        assertTrue(AudioCodec.isKnown(AudioCodec.AAC));
        assertTrue(AudioCodec.isKnown(AudioCodec.OPUS));
        assertFalse(AudioCodec.isKnown(3));
        assertFalse(AudioCodec.isKnown(-1));
        assertNull(AudioCodec.mimeTypeOf(AudioCodec.PCM));
        assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, AudioCodec.mimeTypeOf(AudioCodec.AAC));
        assertEquals(MediaFormat.MIMETYPE_AUDIO_OPUS, AudioCodec.mimeTypeOf(AudioCodec.OPUS));
    }

    /**
     * The device query reads the platform's codec list. The list is read once per process, as
     * the platform caches it, so the codecs go in before the first question.
     */
    @Test
    public void theDeviceQueryReadsTheCodecList() {
        FakeAudioCodecs.addEncoder(MediaFormat.MIMETYPE_AUDIO_OPUS);
        FakeAudioCodecs.addDecoder(MediaFormat.MIMETYPE_AUDIO_AAC);

        assertTrue(AudioCodec.DEVICE.has(MediaFormat.MIMETYPE_AUDIO_OPUS, true));
        assertFalse("an encoder is not a decoder",
                AudioCodec.DEVICE.has(MediaFormat.MIMETYPE_AUDIO_OPUS, false));
        assertTrue(AudioCodec.DEVICE.has(MediaFormat.MIMETYPE_AUDIO_AAC, false));
        assertFalse(AudioCodec.DEVICE.has(MediaFormat.MIMETYPE_AUDIO_AAC, true));
    }

    /**
     * Compression is the point: the compressed rates are a small fraction of the PCM they
     * replace, and the floors sit below the starting rates.
     */
    @Test
    public void bitRatesAreAFractionOfPcm() {
        int pcm = AudioCodec.defaultBitRate(AudioCodec.PCM, 16000, 1);
        assertEquals(256_000, pcm);
        assertEquals(24_000, AudioCodec.defaultBitRate(AudioCodec.OPUS, 16000, 1));
        assertEquals(32_000, AudioCodec.defaultBitRate(AudioCodec.AAC, 16000, 1));
        assertEquals(64_000, AudioCodec.defaultBitRate(AudioCodec.OPUS, 24000, 2));
        assertEquals(128_000, AudioCodec.defaultBitRate(AudioCodec.OPUS, 48000, 2));
        assertEquals(96_000, AudioCodec.defaultBitRate(AudioCodec.AAC, 22050, 2));
        assertEquals(128_000, AudioCodec.defaultBitRate(AudioCodec.AAC, 48000, 2));
        for (int codec : new int[] {AudioCodec.OPUS, AudioCodec.AAC}) {
            for (int rate : new int[] {8000, 16000, 24000, 48000}) {
                assertTrue(AudioCodec.minimumBitRate(codec, rate, 1)
                        < AudioCodec.defaultBitRate(codec, rate, 1));
            }
        }
        assertEquals(pcm, AudioCodec.minimumBitRate(AudioCodec.PCM, 16000, 1));
    }

    /** ISO 14496-3's two-byte AudioSpecificConfig, checked against known encoder output. */
    @Test
    public void aacConfigMatchesWhatEncodersProduce() {
        assertArrayEquals(new byte[] {0x14, 0x08}, AudioCodec.aacConfig(16000, 1));
        assertArrayEquals(new byte[] {0x12, 0x10}, AudioCodec.aacConfig(44100, 2));
        assertArrayEquals(new byte[] {0x11, (byte) 0x90}, AudioCodec.aacConfig(48000, 2));
        assertArrayEquals("an unlisted rate is described as the nearest listed one",
                AudioCodec.aacConfig(16000, 1), AudioCodec.aacConfig(16100, 1));
        assertArrayEquals(AudioCodec.aacConfig(8000, 1),
                AudioCodec.defaultConfig(AudioCodec.AAC, 8000, 1));
        assertEquals(0, AudioCodec.defaultConfig(AudioCodec.PCM, 8000, 1).length);
    }

    /** RFC 7845's identification header. */
    @Test
    public void opusHeadIsTheRfcLayout() {
        byte[] head = AudioCodec.opusHead(16000, 2, 312);
        ByteBuffer read = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);

        assertEquals(19, head.length);
        assertEquals("OpusHead", new String(head, 0, 8));
        assertEquals(1, head[8]);
        assertEquals(2, head[9]);
        assertEquals(312, read.getShort(10));
        assertEquals(16000, read.getInt(12));
        assertEquals(0, read.getShort(16));
        assertEquals(0, head[18]);
        assertArrayEquals(head, AudioCodec.defaultConfig(AudioCodec.OPUS, 16000, 2));
    }

    /**
     * The platform's Opus encoder wraps its header in tagged sections only API 29 decoders
     * unpack; the bare header is what a receiver on any release can use.
     */
    @Test
    public void theEncodersWrappedOpusHeaderIsUnwrapped() {
        byte[] head = AudioCodec.opusHead(48000, 1, 120);
        ByteBuffer wrapped = ByteBuffer.allocate(16 + head.length + 32)
                .order(ByteOrder.LITTLE_ENDIAN);
        wrapped.put("AOPUSHDR".getBytes()).putLong(head.length).put(head);
        wrapped.put("AOPUSDLY".getBytes()).putLong(8).putLong(2_500_000L);
        byte[] csd = wrapped.array();

        assertArrayEquals(head, AudioCodec.normalizeConfig(AudioCodec.OPUS, csd));
        assertArrayEquals("a bare header is already right",
                head, AudioCodec.normalizeConfig(AudioCodec.OPUS, head));
        assertNull("nothing recognisable is replaced by the default",
                AudioCodec.normalizeConfig(AudioCodec.OPUS, new byte[] {1, 2, 3}));
        byte[] lying = csd.clone();
        lying[8] = 100;
        assertNull("a length past the end is not trusted",
                AudioCodec.normalizeConfig(AudioCodec.OPUS, lying));
        byte[] tooShort = Arrays.copyOf("AOPUSHDR".getBytes(), 12);
        assertNull(AudioCodec.normalizeConfig(AudioCodec.OPUS, tooShort));
        byte[] notAHeader = csd.clone();
        notAHeader[16] = 'X';
        assertNull(AudioCodec.normalizeConfig(AudioCodec.OPUS, notAHeader));
        assertNull(AudioCodec.normalizeConfig(AudioCodec.OPUS, new byte[0]));
        assertNull(AudioCodec.normalizeConfig(AudioCodec.AAC, null));
        byte[] aac = {0x14, 0x08};
        assertArrayEquals("AAC's is sent as the encoder made it",
                aac, AudioCodec.normalizeConfig(AudioCodec.AAC, aac));
    }

    /** Opus decoders take three buffers, the last two in nanoseconds; AAC's takes one. */
    @Test
    public void decoderConfigIsWhatMediaFormatExpects() {
        byte[] head = AudioCodec.opusHead(16000, 1, 480);
        ByteBuffer[] opus = AudioCodec.decoderConfig(AudioCodec.OPUS, head);

        assertEquals(3, opus.length);
        assertEquals(ByteBuffer.wrap(head), opus[0]);
        assertEquals("480 samples at 48kHz", 10_000_000L,
                opus[1].order(ByteOrder.nativeOrder()).getLong(0));
        assertEquals(80_000_000L, opus[2].order(ByteOrder.nativeOrder()).getLong(0));

        ByteBuffer[] shortHead = AudioCodec.decoderConfig(AudioCodec.OPUS, new byte[4]);
        assertEquals("a header too short to read falls back to the usual pre-skip",
                AudioCodec.OPUS_PRE_SKIP_SAMPLES * 1_000_000_000L / 48000,
                shortHead[1].order(ByteOrder.nativeOrder()).getLong(0));

        ByteBuffer[] aac = AudioCodec.decoderConfig(AudioCodec.AAC, new byte[] {0x14, 0x08});
        assertEquals(1, aac.length);
    }

    /** A frame on the wire is its length and itself, and reads back whole. */
    @Test
    public void framesRoundTrip() throws IOException {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        byte[] small = {1, 2, 3};
        byte[] large = new byte[1500];
        Arrays.fill(large, (byte) 7);
        wire.write(AudioCodec.frame(small));
        wire.write(AudioCodec.frame(new byte[0]));
        wire.write(AudioCodec.frame(large));

        ByteArrayInputStream in = new ByteArrayInputStream(wire.toByteArray());
        assertArrayEquals(small, AudioCodec.readFrame(in));
        assertArrayEquals(new byte[0], AudioCodec.readFrame(in));
        assertArrayEquals(large, AudioCodec.readFrame(in));
        assertNull("the end of the stream", AudioCodec.readFrame(in));
    }

    /** A stream cut off mid-frame ends there, rather than handing the decoder half a frame. */
    @Test
    public void aTruncatedFrameEndsTheStream() throws IOException {
        byte[] framed = AudioCodec.frame(new byte[] {1, 2, 3, 4});

        assertNull(AudioCodec.readFrame(new ByteArrayInputStream(framed, 0, 4)));
        assertNull(AudioCodec.readFrame(new ByteArrayInputStream(framed, 0, 1)));
        assertNull(AudioCodec.readFrame(new ByteArrayInputStream(new byte[0])));
    }

    @Test(expected = IllegalArgumentException.class)
    public void aFrameTooLongForItsLengthIsRefused() {
        AudioCodec.frame(new byte[AudioCodec.MAX_FRAME_BYTES + 1]);
    }
}
