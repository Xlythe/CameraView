package com.xlythe.view.camera.stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.media.AudioFormat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

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
