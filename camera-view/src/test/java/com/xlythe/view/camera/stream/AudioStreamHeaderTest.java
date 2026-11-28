package com.xlythe.view.camera.stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.media.AudioFormat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * What the recorder tells the player about the audio that follows.
 *
 * <p>The property that matters is that nothing is lost either way: a described stream is read back
 * exactly as written, and a stream with no description is still played rather than having its
 * first bytes eaten by a header that was not there.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class AudioStreamHeaderTest {
    @Test
    public void aDescribedStreamReadsBackWhatWasWritten() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new AudioStreamHeader(44100, 2, AudioFormat.ENCODING_PCM_16BIT).writeTo(out);
        byte[] audio = {1, 2, 3, 4, 5, 6, 7, 8};
        out.write(audio);

        AudioStreamHeader.Read read =
                AudioStreamHeader.read(new ByteArrayInputStream(out.toByteArray()));

        assertNotNull(read.header);
        assertEquals(44100, read.header.getSampleRate());
        assertEquals(2, read.header.getChannelCount());
        assertEquals(AudioFormat.ENCODING_PCM_16BIT, read.header.getEncoding());
        assertArrayEquals("the audio after the header is untouched", audio, drain(read.stream));
        assertEquals("the header describes itself for logs",
                true, read.header.toString().contains("44100"));
    }

    /** A recorder from before the header existed sends PCM straight away. */
    @Test
    public void anUndescribedStreamKeepsAllOfItsAudio() throws IOException {
        byte[] audio = new byte[64];
        for (int i = 0; i < audio.length; i++) {
            audio[i] = (byte) i;
        }

        AudioStreamHeader.Read read = AudioStreamHeader.read(new ByteArrayInputStream(audio));

        assertNull("there was no header to find", read.header);
        assertArrayEquals("and not one byte of audio was eaten", audio, drain(read.stream));
    }

    /** A stream shorter than a header is still a stream. */
    @Test
    public void aStreamTooShortToHoldAHeaderIsStillRead() throws IOException {
        byte[] audio = {9, 8, 7};

        AudioStreamHeader.Read read = AudioStreamHeader.read(new ByteArrayInputStream(audio));

        assertNull(read.header);
        assertArrayEquals(audio, drain(read.stream));
    }

    @Test
    public void anEmptyStreamIsNotAHeader() throws IOException {
        AudioStreamHeader.Read read = AudioStreamHeader.read(new ByteArrayInputStream(new byte[0]));

        assertNull(read.header);
        assertArrayEquals(new byte[0], drain(read.stream));
    }

    /** Audio that happens to be header-length is not mistaken for one. */
    @Test
    public void audioTheLengthOfAHeaderIsNotMistakenForOne() throws IOException {
        byte[] audio = new byte[AudioStreamHeader.LENGTH];

        AudioStreamHeader.Read read = AudioStreamHeader.read(new ByteArrayInputStream(audio));

        assertNull(read.header);
        assertArrayEquals(audio, drain(read.stream));
    }

    /** A header claiming a nonsense rate is no better than no header. */
    @Test
    public void anImpossibleRateIsRejected() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new AudioStreamHeader(0, 1, AudioFormat.ENCODING_PCM_16BIT).writeTo(out);

        AudioStreamHeader.Read read =
                AudioStreamHeader.read(new ByteArrayInputStream(out.toByteArray()));

        assertNull(read.header);
    }

    /**
     * PCM goes out byte for byte as the last release wrote it, so a receiver that has not updated
     * reads it exactly as before.
     */
    @Test
    public void pcmIsWrittenInTheReleasedLayout() throws IOException {
        byte[] audio = {1, 2, 3, 4};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new AudioStreamHeader(16000, 1, AudioCodec.PCM, new byte[0]).writeTo(out);
        out.write(audio);

        assertArrayEquals(ReleasedAudioFormat.send(16000, 1, audio), out.toByteArray());
        ReleasedAudioFormat.Received received = ReleasedAudioFormat.receive(out.toByteArray());
        assertEquals(16000, received.sampleRate);
        assertEquals(1, received.channelCount);
        assertArrayEquals(audio, received.played);
    }

    /** A stream from the last release reads the same here. */
    @Test
    public void theReleasedLayoutStillReads() throws IOException {
        byte[] audio = {9, 9, 9, 9};

        AudioStreamHeader.Read read = AudioStreamHeader.read(
                new ByteArrayInputStream(ReleasedAudioFormat.send(48000, 2, audio)));

        assertNotNull(read.header);
        assertEquals(AudioCodec.PCM, read.header.getCodec());
        assertEquals(48000, read.header.getSampleRate());
        assertEquals(2, read.header.getChannelCount());
        assertEquals(0, read.header.getConfig().length);
        assertArrayEquals(audio, drain(read.stream));
    }

    /** A compressed header reads back with its format and decoder configuration. */
    @Test
    public void aCompressedHeaderRoundTrips() throws IOException {
        for (int codec : new int[] {AudioCodec.AAC, AudioCodec.OPUS}) {
            byte[] config = AudioCodec.defaultConfig(codec, 24000, 2);
            byte[] frames = AudioCodec.frame(new byte[] {5, 6, 7});
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(new AudioStreamHeader(24000, 2, codec, config).toByteArray());
            out.write(frames);

            AudioStreamHeader.Read read =
                    AudioStreamHeader.read(new ByteArrayInputStream(out.toByteArray()));

            assertNotNull(read.header);
            assertEquals(codec, read.header.getCodec());
            assertEquals(24000, read.header.getSampleRate());
            assertEquals(2, read.header.getChannelCount());
            assertArrayEquals(config, read.header.getConfig());
            assertArrayEquals("the frames after it are untouched", frames, drain(read.stream));
            assertTrue(read.header.toString().contains("codec=" + codec));
        }
        assertEquals(AudioFormat.ENCODING_AAC_LC,
                new AudioStreamHeader(8000, 1, AudioCodec.AAC, new byte[0]).getEncoding());
        assertEquals(AudioFormat.ENCODING_OPUS,
                new AudioStreamHeader(8000, 1, AudioCodec.OPUS, new byte[0]).getEncoding());
    }

    /**
     * The released reader turns a compressed header down rather than reading a rate out of the
     * wrong bytes. It would still play what follows as PCM, which is why a compressed header is
     * only ever sent to a receiver that listed the format; see {@link AudioPairingTest}.
     */
    @Test
    public void theReleasedReaderDoesNotMistakeACompressedHeaderForItsOwn() throws IOException {
        byte[] header = new AudioStreamHeader(16000, 1, AudioCodec.OPUS,
                AudioCodec.defaultConfig(AudioCodec.OPUS, 16000, 1)).toByteArray();

        ReleasedAudioFormat.Received received = ReleasedAudioFormat.receive(header);

        assertEquals("no rate was read out of it", -1, received.sampleRate);
        assertEquals(-1, received.channelCount);
    }

    /** A later version appends fields; this one reads what it knows and skips the rest. */
    @Test
    public void aLaterVersionsAdditionsAreSkipped() throws IOException {
        byte[] config = {1, 2};
        byte[] appended = {42, 42, 42};
        byte[] audio = AudioCodec.frame(new byte[] {8});
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(out);
        data.writeInt(0x58415544);
        data.writeByte(3);
        data.writeShort(7 + config.length + appended.length);
        data.writeInt(16000);
        data.writeByte(1);
        data.writeByte(AudioCodec.AAC);
        data.writeByte(config.length);
        data.write(config);
        data.write(appended);
        data.write(audio);

        AudioStreamHeader.Read read =
                AudioStreamHeader.read(new ByteArrayInputStream(out.toByteArray()));

        assertNotNull(read.header);
        assertEquals(AudioCodec.AAC, read.header.getCodec());
        assertArrayEquals(config, read.header.getConfig());
        assertArrayEquals("the reader resumes after the fields it skipped",
                audio, drain(read.stream));
    }

    /** A compressed header that does not hold together is no header, and loses no bytes. */
    @Test
    public void aBrokenCompressedHeaderIsNotOne() throws IOException {
        byte[] good = new AudioStreamHeader(16000, 1, AudioCodec.AAC,
                new byte[] {0x14, 0x08}).toByteArray();
        assertBroken("cut short", Arrays.copyOf(good, good.length - 1));

        byte[] tooShortALength = good.clone();
        tooShortALength[6] = 3;
        assertBroken("a length shorter than its own fields", tooShortALength);

        byte[] tooLongALength = good.clone();
        tooLongALength[5] = 0x10;
        assertBroken("a length past what any header needs", tooLongALength);

        byte[] unknownFormat = good.clone();
        unknownFormat[12] = 77;
        assertBroken("a format this version cannot decode", unknownFormat);

        byte[] longConfig = good.clone();
        longConfig[13] = 9;
        assertBroken("a config longer than the header", longConfig);

        byte[] noRate = good.clone();
        noRate[7] = 0;
        noRate[8] = 0;
        noRate[9] = 0;
        noRate[10] = 0;
        assertBroken("no rate", noRate);

        byte[] noChannels = good.clone();
        noChannels[11] = 0;
        assertBroken("no channels", noChannels);

        byte[] versionZero = good.clone();
        versionZero[4] = 0;
        assertBroken("a version never written", versionZero);
    }

    @Test(expected = IllegalArgumentException.class)
    public void aConfigTooLongToDescribeIsRefused() {
        new AudioStreamHeader(16000, 1, AudioCodec.AAC, new byte[256]);
    }

    private static void assertBroken(String why, byte[] bytes) throws IOException {
        AudioStreamHeader.Read read = AudioStreamHeader.read(new ByteArrayInputStream(bytes));
        assertNull(why, read.header);
        assertArrayEquals(why + ": every byte is given back", bytes, drain(read.stream));
    }

    private static byte[] drain(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[32];
        int read;
        while ((read = stream.read(chunk)) > 0) {
            out.write(chunk, 0, read);
        }
        return out.toByteArray();
    }
}
