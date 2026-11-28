package com.xlythe.view.camera.stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;

import androidx.annotation.NonNull;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAudioEffect;
import org.robolectric.shadows.ShadowSystemClock;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A sender and a receiver that may not be the same version, in every combination.
 *
 * <p>Two phones streaming to each other are updated separately, so a stream has to work whichever
 * end is newer. The old end here is the format as the last release before compression wrote and
 * read it ({@link ReleasedAudioFormat}); the new end is the recorder and player as they are now,
 * through stand-in codecs ({@link FakeAudioCodecs}) that scramble reversibly, so audio that comes
 * out intact went through the encoder and the decoder rather than around them.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class AudioPairingTest {
    private static final long TIMEOUT_SECONDS = 10;

    /** Wideband speech, the stream default. */
    private static final int SAMPLE_RATE = 16000;

    /** 20ms of 16kHz mono, a typical AudioRecord chunk. */
    private static final int CHUNK_BYTES = 640;

    /** Half a second of something that is not silence, so a dropped chunk would show. */
    private static final byte[] SIGNAL = signal(25 * CHUNK_BYTES);

    @Before
    public void setUp() {
        FakeAudioCodecs.reset();
        ShadowAudioEffect.reset();
    }

    @After
    public void tearDown() {
        FakeAudioCodecs.reset();
        ShadowAudioEffect.reset();
    }

    // ---------- New sender ----------

    /**
     * A receiver that has not updated never sends capabilities, and is sent what the last release
     * sent: the version 1 header and raw PCM, which it plays exactly as it always has.
     */
    @Test
    public void newSenderToOldReceiverIsPcmAsBefore() throws Exception {
        FakeAudioCodecs.addAll();

        Recorded recorded = record(null);

        assertEquals(AudioCodec.PCM, recorded.codec);
        assertArrayEquals("byte for byte what the last release sent",
                ReleasedAudioFormat.send(SAMPLE_RATE, 1, SIGNAL), recorded.bytes);
        ReleasedAudioFormat.Received received = ReleasedAudioFormat.receive(recorded.bytes);
        assertEquals(SAMPLE_RATE, received.sampleRate);
        assertEquals(1, received.channelCount);
        assertArrayEquals(SIGNAL, received.played);
    }

    /** Two current devices share Opus, and the audio arrives intact through both codecs. */
    @Test
    public void newSenderToNewReceiverSharesOpus() throws Exception {
        FakeAudioCodecs.addAll();

        Recorded recorded = record(PlaybackCapabilities.forThisDevice());

        assertEquals(AudioCodec.OPUS, recorded.codec);
        AudioStreamHeader.Read read =
                AudioStreamHeader.read(new ByteArrayInputStream(recorded.bytes));
        assertNotNull(read.header);
        assertEquals(AudioCodec.OPUS, read.header.getCodec());
        assertEquals(SAMPLE_RATE, read.header.getSampleRate());
        assertArrayEquals("the encoder made no config, so the default goes out",
                AudioCodec.defaultConfig(AudioCodec.OPUS, SAMPLE_RATE, 1),
                read.header.getConfig());
        assertArrayEquals(SIGNAL, FakeAudioCodecs.payloadOf(drain(read.stream)));

        Played played = play(recorded.bytes);
        assertEquals(AudioCodec.OPUS, played.codec);
        assertArrayEquals(SIGNAL, played.pcm());
        assertEquals(SAMPLE_RATE, played.sampleRates.get(0).intValue());
    }

    /** A receiver without an Opus decoder does not list it, and gets AAC. */
    @Test
    public void aReceiverWithoutOpusGetsAac() throws Exception {
        FakeAudioCodecs.addEncoder(MediaFormat.MIMETYPE_AUDIO_OPUS);
        FakeAudioCodecs.addEncoder(MediaFormat.MIMETYPE_AUDIO_AAC);
        FakeAudioCodecs.addDecoder(MediaFormat.MIMETYPE_AUDIO_AAC);

        Recorded recorded = record(PlaybackCapabilities.forThisDevice());

        assertEquals(AudioCodec.AAC, recorded.codec);
        Played played = play(recorded.bytes);
        assertEquals(AudioCodec.AAC, played.codec);
        assertArrayEquals(SIGNAL, played.pcm());
    }

    /**
     * A sender below API 29 cannot encode Opus even for a receiver that plays it, so it sends
     * AAC: an older phone streaming to a newer one.
     */
    @Test
    @Config(sdk = 28)
    public void aSenderBelowApi29SendsAac() throws Exception {
        FakeAudioCodecs.addAll();
        byte[] newerReceiver = PlaybackCapabilities.forDevice(37, AudioCodec.DEVICE);

        Recorded recorded = record(newerReceiver);

        assertEquals(AudioCodec.AAC, recorded.codec);
        assertArrayEquals(SIGNAL, play(recorded.bytes).pcm());
    }

    /** A receiver that lists only PCM still reads the old header, and that is what it gets. */
    @Test
    public void aReceiverThatOnlyPlaysPcmGetsTheReleasedLayout() throws Exception {
        FakeAudioCodecs.addAll();

        Recorded recorded = record(PlaybackCapabilities.forDevice(14, AudioCodec.DEVICE));

        assertEquals(AudioCodec.PCM, recorded.codec);
        assertArrayEquals(ReleasedAudioFormat.send(SAMPLE_RATE, 1, SIGNAL), recorded.bytes);
    }

    /** A device with no encoder to give sends PCM rather than nothing. */
    @Test
    public void aSenderWithoutEncodersSendsPcm() throws Exception {
        FakeAudioCodecs.addDecoder(MediaFormat.MIMETYPE_AUDIO_OPUS);
        FakeAudioCodecs.addDecoder(MediaFormat.MIMETYPE_AUDIO_AAC);

        Recorded recorded = record(PlaybackCapabilities.forThisDevice());

        assertEquals(AudioCodec.PCM, recorded.codec);
        assertArrayEquals(SIGNAL, play(recorded.bytes).pcm());
    }

    // ---------- Old sender ----------

    /** A stream from a sender that has not updated plays here exactly as it did there. */
    @Test
    public void oldSenderToNewReceiverPlaysUnchanged() throws Exception {
        Played played = play(ReleasedAudioFormat.send(SAMPLE_RATE, 1, SIGNAL));

        assertEquals(AudioCodec.PCM, played.codec);
        assertEquals(SAMPLE_RATE, played.sampleRates.get(0).intValue());
        assertArrayEquals(SIGNAL, played.pcm());
    }

    /** And so does one from before there was a header at all. */
    @Test
    public void aSenderFromBeforeTheHeaderStillPlays() throws Exception {
        Played played = play(SIGNAL);

        assertEquals(AudioCodec.PCM, played.codec);
        assertArrayEquals(SIGNAL, played.pcm());
    }

    /** The baseline the other three are held to. */
    @Test
    public void oldSenderToOldReceiverIsTheBaseline() throws Exception {
        ReleasedAudioFormat.Received received =
                ReleasedAudioFormat.receive(ReleasedAudioFormat.send(SAMPLE_RATE, 1, SIGNAL));

        assertEquals(SAMPLE_RATE, received.sampleRate);
        assertArrayEquals(SIGNAL, received.played);
    }

    // ---------- Adaptation and processing ----------

    /**
     * A reader that stops reading makes the recorder lower its audio bit rate, by restarting the
     * encoder, down to the format's floor and no further.
     */
    @Test
    public void aStalledReaderLowersTheAudioBitRate() throws Exception {
        FakeAudioCodecs.addAll();
        PipedInputStream pipe = new PipedInputStream(128 * 1024);
        LossyPipedOutputStream lossy = new LossyPipedOutputStream(pipe, 16 * 1024);
        SignalSource source = new SignalSource(signal(400 * CHUNK_BYTES), true);
        AudioRecorder recorder = new AudioRecorder(lossy, SAMPLE_RATE, 1,
                options(PlaybackCapabilities.forThisDevice()), (rate, channels, options) -> source);
        int start = AudioCodec.defaultBitRate(AudioCodec.OPUS, SAMPLE_RATE, 1);
        int floor = AudioCodec.minimumBitRate(AudioCodec.OPUS, SAMPLE_RATE, 1);

        recorder.start();
        try {
            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS);
            // Zero until the recorder has started encoding.
            while ((recorder.getBitRate() == 0
                    || recorder.getBitRate() > floor / AudioRecorder.DECREASE_THRESHOLD)
                    && System.currentTimeMillis() < deadline) {
                // The recorder keeps time on the system clock, which only moves when told to here.
                Thread.sleep(20);
                ShadowSystemClock.advanceBy(Duration.ofMillis(100));
            }
        } finally {
            recorder.stop();
        }

        assertEquals(AudioCodec.OPUS, recorder.getCodec());
        int bitRate = recorder.getBitRate();
        assertTrue("never cut from " + start, bitRate < start);
        assertTrue("cut to " + bitRate + ", which is within a restart's worth of the floor",
                bitRate >= floor && bitRate <= floor / AudioRecorder.DECREASE_THRESHOLD);
        assertEquals("the backlog allowance follows the rate",
                AudioRecorder.compressedBacklogBytesFor(bitRate), lossy.getMaxBufferedBytes());
    }

    /**
     * Compressed audio keeps arriving after the reader falls behind and catches up. Each frame
     * stands alone, so a dropped one costs only itself and the next one is sent.
     */
    @Test
    public void compressedAudioResumesAfterAStall() throws Exception {
        FakeAudioCodecs.addAll();
        PipedInputStream pipe = new PipedInputStream(128 * 1024);
        LossyPipedOutputStream lossy = new LossyPipedOutputStream(pipe, 16 * 1024);
        SignalSource source = new SignalSource(signal(400 * CHUNK_BYTES), true);
        AudioRecorder recorder = new AudioRecorder(lossy, SAMPLE_RATE, 1,
                options(PlaybackCapabilities.forThisDevice()), (rate, channels, options) -> source);

        recorder.start();
        int afterStall = 0;
        try {
            AudioStreamHeader header = AudioStreamHeader.read(pipe).header;
            assertNotNull(header);
            assertEquals(AudioCodec.OPUS, header.getCodec());
            // Long enough behind that frames are dropped.
            Thread.sleep(300);
            long until = System.currentTimeMillis() + 500;
            while (System.currentTimeMillis() < until && AudioCodec.readFrame(pipe) != null) {
                afterStall++;
            }
        } finally {
            recorder.stop();
        }

        assertTrue("only " + afterStall + " frames after the stall", afterStall > 20);
    }

    /** Cuts are taken sooner than raises, and small moves not at all. */
    @Test
    public void encoderRestartsAreRateLimited() {
        assertTrue(AudioRecorder.shouldChangeBitRate(24_000, 16_000, 500));
        assertFalse(AudioRecorder.shouldChangeBitRate(24_000, 16_000, 499));
        assertFalse("too small a cut", AudioRecorder.shouldChangeBitRate(24_000, 22_000, 5000));
        assertTrue(AudioRecorder.shouldChangeBitRate(16_000, 24_000, 2000));
        assertFalse(AudioRecorder.shouldChangeBitRate(16_000, 24_000, 1999));
        assertFalse("too small a raise", AudioRecorder.shouldChangeBitRate(20_000, 22_000, 5000));
        assertEquals(AudioRecorder.MIN_COMPRESSED_BACKLOG_BYTES,
                AudioRecorder.compressedBacklogBytesFor(1_000));
        assertEquals(750, AudioRecorder.compressedBacklogBytesFor(24_000));
    }

    /**
     * Speech processing attaches the platform's effects to the microphone's session, and echo
     * cancellation records through the voice call input its canceller needs.
     */
    @Test
    // One release only: Robolectric's effect shadow does not intercept the setup newer releases
    // use, so there the platform's own create() fails and returns null, as on a device without
    // the effect - which missingEffectsAreSkipped covers on every release.
    @Config(sdk = 29)
    public void speechProcessingAttachesTheEffectsAskedFor() throws Exception {
        ShadowAudioEffect.addEffect(descriptor(AudioEffect.EFFECT_TYPE_NS));
        ShadowAudioEffect.addEffect(descriptor(AudioEffect.EFFECT_TYPE_AGC));
        AudioRecorder.Options options = new AudioRecorder.Options(null, true, false, true, null);

        AudioRecorder.PcmSource source =
                AudioRecorder.MicrophoneSource.open(SAMPLE_RATE, 1, options);
        assertNotNull(source);
        try {
            List<Class<?>> attached = new ArrayList<>();
            for (AudioEffect effect : ShadowAudioEffect.getAudioEffects()) {
                attached.add(effect.getClass());
                assertTrue("an attached effect is switched on", effect.getEnabled());
            }
            assertTrue(attached.contains(NoiseSuppressor.class));
            assertTrue(attached.contains(AutomaticGainControl.class));
            assertEquals("echo cancellation was not asked for", 2, attached.size());
        } finally {
            source.close();
        }

        assertEquals(MediaRecorder.AudioSource.CAMCORDER,
                AudioRecorder.MicrophoneSource.audioSourceFor(options));
        assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                AudioRecorder.MicrophoneSource.audioSourceFor(
                        new AudioRecorder.Options(null, false, true, false, null)));
    }

    /** Effects the device does not have are skipped, and the microphone still opens. */
    @Test
    public void missingEffectsAreSkipped() {
        AudioRecorder.Options options = new AudioRecorder.Options(null, true, true, true, null);

        AudioRecorder.PcmSource source =
                AudioRecorder.MicrophoneSource.open(SAMPLE_RATE, 1, options);

        assertNotNull(source);
        assertTrue(ShadowAudioEffect.getAudioEffects().isEmpty());
        assertTrue(source.getBufferSize() > 0);
        assertEquals(1, source.getChannelCount());
        source.close();
    }

    // ---------- Harness ----------

    private static AudioRecorder.Options options(byte[] capabilities) {
        return new AudioRecorder.Options(capabilities, false, false, false, null);
    }

    /** What a recorder sent, and the format it chose. */
    private static final class Recorded {
        final byte[] bytes;
        final int codec;

        Recorded(byte[] bytes, int codec) {
            this.bytes = bytes;
            this.codec = codec;
        }
    }

    /** Records {@link #SIGNAL} for a receiver with these capabilities, to a lossless sink. */
    private static Recorded record(byte[] capabilities) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SignalSource source = new SignalSource(SIGNAL, false);
        AudioRecorder recorder = new AudioRecorder(out, SAMPLE_RATE, 1, options(capabilities),
                (rate, channels, options) -> source);
        recorder.start();
        assertTrue("the source was never drained",
                source.drained.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        // One more empty read collects what the encoder finished last.
        Thread.sleep(50);
        recorder.stop();
        assertTrue("the microphone has to be released", source.closed.await(
                TIMEOUT_SECONDS, TimeUnit.SECONDS));
        return new Recorded(out.toByteArray(), recorder.getCodec());
    }

    /** What a player played. */
    private static final class Played {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final List<Integer> sampleRates = new ArrayList<>();
        int codec;

        byte[] pcm() {
            return bytes.toByteArray();
        }
    }

    /** Plays a stream to its end into a recording instead of a speaker. */
    private static Played play(byte[] stream) throws Exception {
        Played played = new Played();
        AudioPlayer player = new AudioPlayer(new ByteArrayInputStream(stream),
                (sampleRate, channelCount, fallbackBytes) -> {
                    played.sampleRates.add(sampleRate);
                    return new AudioPlayer.PcmOutput() {
                        @Override
                        public void write(@NonNull byte[] data, int offset, int length) {
                            played.bytes.write(data, offset, length);
                        }

                        @Override
                        public void release() {}
                    };
                });
        CountDownLatch ended = new CountDownLatch(1);
        player.setStreamEndListener(ended::countDown);
        player.start();
        assertTrue("the player never finished",
                ended.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        played.codec = player.getCodec();
        return played;
    }

    /** A microphone that says a fixed signal, then nothing. */
    private static final class SignalSource implements AudioRecorder.PcmSource {
        final byte[] signal;
        final boolean repeat;
        final CountDownLatch drained = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        int offset;

        SignalSource(byte[] signal, boolean repeat) {
            this.signal = signal;
            this.repeat = repeat;
        }

        @Override
        public int getSampleRate() {
            return SAMPLE_RATE;
        }

        @Override
        public int getChannelCount() {
            return 1;
        }

        @Override
        public int getBufferSize() {
            return CHUNK_BYTES;
        }

        @Override
        public int read(@NonNull byte[] buffer, int off, int length) {
            if (offset >= signal.length) {
                if (repeat) {
                    offset = 0;
                } else {
                    drained.countDown();
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return 0;
                }
            }
            int count = Math.min(Math.min(length, CHUNK_BYTES), signal.length - offset);
            System.arraycopy(signal, offset, buffer, off, count);
            offset += count;
            return count;
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private static AudioEffect.Descriptor descriptor(UUID type) {
        return new AudioEffect.Descriptor(type.toString(), UUID.randomUUID().toString(),
                AudioEffect.EFFECT_PRE_PROCESSING, "fake", "test");
    }

    private static byte[] signal(int length) {
        byte[] audio = new byte[length];
        for (int i = 0; i < length; i++) {
            audio[i] = (byte) (i * 7 % 251);
        }
        return audio;
    }

    private static byte[] drain(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        int read;
        while ((read = stream.read(chunk)) > 0) {
            out.write(chunk, 0, read);
        }
        return out.toByteArray();
    }
}
