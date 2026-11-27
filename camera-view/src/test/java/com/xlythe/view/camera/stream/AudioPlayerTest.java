package com.xlythe.view.camera.stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.AudioFormat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAudioTrack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The player's background thread, which is where all of its work happens.
 *
 * <p>Everything the class does - reading the header the recorder sent, sizing a buffer from it,
 * opening an AudioTrack, draining the stream, and reporting the end - lives in one anonymous
 * Thread. Nothing drove it before, so none of it ran under test: a stream that ended without
 * telling anyone, or one left open, would have looked exactly like a passing suite.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class AudioPlayerTest {
    /** Long enough for a thread to drain a handful of bytes, short enough to fail fast. */
    private static final long TIMEOUT_SECONDS = 10;

    /** A stream that reports when it was closed, which is the player's other obligation. */
    private static final class ClosingStream extends ByteArrayInputStream {
        final AtomicBoolean closed = new AtomicBoolean();

        ClosingStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed.set(true);
            super.close();
        }
    }

    /** A stream that fails part way through, as a socket does when the other side goes away. */
    private static final class BrokenStream extends InputStream {
        @Override
        public int read() throws IOException {
            throw new IOException("the other side went away");
        }

        @Override
        public int read(byte[] into, int offset, int length) throws IOException {
            throw new IOException("the other side went away");
        }
    }

    @Test
    public void aStreamIsPlayedToItsEndAndReported() throws Exception {
        ClosingStream stream = new ClosingStream(streamOf(44100, 2, audioOf(4096)));
        AudioPlayer player = new AudioPlayer(stream);
        CountDownLatch ended = new CountDownLatch(1);
        player.setStreamEndListener(ended::countDown);

        player.start();

        assertTrue("the player never reported the end of the stream",
                ended.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertFalse("a finished player should not still call itself playing", player.isPlaying());
        assertTrue("the player left the stream open", stream.closed.get());
    }

    /**
     * A stream that breaks is still reported, and still closed.
     *
     * <p>The listener is how a caller learns to tear down the other half of a call, so losing it
     * on failure is worse than losing it on success.</p>
     */
    @Test
    public void aBrokenStreamIsStillReported() throws Exception {
        AudioPlayer player = new AudioPlayer(new BrokenStream());
        CountDownLatch ended = new CountDownLatch(1);
        player.setStreamEndListener(ended::countDown);

        player.start();

        assertTrue("a failing stream has to be reported too",
                ended.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertFalse(player.isPlaying());
    }

    /** A stream with no header at all still plays, on this device's own defaults. */
    @Test
    public void aStreamWithoutAHeaderStillPlays() throws Exception {
        ClosingStream stream = new ClosingStream(audioOf(2048));
        AudioPlayer player = new AudioPlayer(stream);
        CountDownLatch ended = new CountDownLatch(1);
        player.setStreamEndListener(ended::countDown);

        player.start();

        assertTrue(ended.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(stream.closed.get());
    }

    /** Fire-once: a second start is refused rather than playing the stream twice over. */
    @Test
    public void aPlayerCannotBeStartedTwice() throws Exception {
        ClosingStream stream = new ClosingStream(streamOf(16000, 1, audioOf(1024)));
        AudioPlayer player = new AudioPlayer(stream);
        CountDownLatch ended = new CountDownLatch(1);
        player.setStreamEndListener(ended::countDown);

        player.start();
        player.start();

        assertTrue(ended.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        // One end report, not two: a second thread would have reported again as it failed to
        // read from the stream the first one closed.
        assertFalse(player.isPlaying());
        assertTrue(stream.closed.get());
    }

    /** Stopping a player that never started closes the stream and stays stopped. */
    @Test
    public void stoppingBeforeStartingIsSafe() {
        ClosingStream stream = new ClosingStream(audioOf(16));
        AudioPlayer player = new AudioPlayer(stream);

        player.stop();

        assertFalse(player.isPlaying());
        assertTrue("stopping should hand the stream back", stream.closed.get());

        // And a player that has been stopped does not then start.
        player.start();
        assertFalse(player.isPlaying());
    }

    /** Stopping mid-stream ends the playback rather than draining the rest of it. */
    @Test
    public void stoppingEndsPlayback() throws Exception {
        ClosingStream stream = new ClosingStream(streamOf(44100, 1, audioOf(1024 * 1024)));
        AudioPlayer player = new AudioPlayer(stream);
        CountDownLatch ended = new CountDownLatch(1);
        player.setStreamEndListener(ended::countDown);

        player.start();
        player.stop();

        assertTrue("stopping should still report the end",
                ended.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertFalse(player.isPlaying());
        assertTrue(stream.closed.get());
    }

    /** The header the recorder wrote is what the player reads back out of the stream. */
    @Test
    public void theHeaderIsReadOffTheFrontOfTheStream() throws Exception {
        byte[] audio = audioOf(64);
        byte[] whole = streamOf(8000, 2, audio);

        AudioStreamHeader.Read read = AudioStreamHeader.read(new ByteArrayInputStream(whole));

        assertTrue("the header should have been recognised", read.header != null);
        assertArrayEquals("the audio behind the header has to come through untouched",
                audio, drain(read.stream));
    }

    /**
     * A socket may end a read anywhere, and AudioTrack drops a partial frame, so the player holds
     * the tail of a read back until the rest of its frame arrives.
     */
    @Test
    public void readsThatEndMidFrameStayOnFrameBoundaries() throws Exception {
        byte[] audio = audioOf(4096);
        InputStream trickle = new FilterInputStream(new ByteArrayInputStream(
                streamOf(44100, 2, audio))) {
            @Override
            public int read(byte[] into, int offset, int length) throws IOException {
                return super.read(into, offset, Math.min(length, 3));
            }
        };
        ByteArrayOutputStream played = new ByteArrayOutputStream();
        List<Integer> writes = new ArrayList<>();
        ShadowAudioTrack.OnAudioDataWrittenListener listener = (track, data, format) -> {
            writes.add(data.length);
            played.write(data, 0, data.length);
        };
        ShadowAudioTrack.addAudioDataListener(listener);
        try {
            playToTheEnd(trickle);
        } finally {
            ShadowAudioTrack.removeAudioDataListener(listener);
        }

        for (int length : writes) {
            assertEquals("a write split a stereo frame", 0, length % 4);
        }
        assertArrayEquals(audio, played.toByteArray());
    }

    /**
     * Audio that waited unplayed through a whole second is discarded, down to a small cushion, so
     * the player moves on to the newest audio rather than playing the backlog late.
     */
    @Test
    public void aBacklogThatNeverDrainsIsDiscarded() throws Exception {
        // Three seconds of 16kHz mono that all arrived at once.
        byte[] audio = audioOf(3 * 32_000);
        ByteArrayOutputStream played = new ByteArrayOutputStream();
        ShadowAudioTrack.OnAudioDataWrittenListener listener =
                (track, data, format) -> played.write(data, 0, data.length);
        ShadowAudioTrack.addAudioDataListener(listener);
        try {
            playToTheEnd(new ByteArrayInputStream(streamOf(16000, 1, audio)));
        } finally {
            ShadowAudioTrack.removeAudioDataListener(listener);
        }

        byte[] heard = played.toByteArray();
        assertTrue("only " + heard.length + " bytes should have played",
                heard.length < audio.length / 2);
        assertEquals(0, heard.length % 2);
        byte[] tail = Arrays.copyOfRange(audio, audio.length - 1024, audio.length);
        assertArrayEquals("the newest audio should still have played",
                tail, Arrays.copyOfRange(heard, heard.length - 1024, heard.length));
    }

    private static void playToTheEnd(InputStream stream) throws InterruptedException {
        AudioPlayer player = new AudioPlayer(stream);
        CountDownLatch ended = new CountDownLatch(1);
        player.setStreamEndListener(ended::countDown);
        player.start();
        assertTrue("the player never reported the end of the stream",
                ended.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    /** A header, then the audio, as the recorder sends it. */
    private static byte[] streamOf(int sampleRate, int channels, byte[] audio) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new AudioStreamHeader(sampleRate, channels, AudioFormat.ENCODING_PCM_16BIT).writeTo(out);
        out.write(audio);
        return out.toByteArray();
    }

    /** Something that is not silence, so a buffer dropped on the floor would be visible. */
    private static byte[] audioOf(int length) {
        byte[] audio = new byte[length];
        for (int i = 0; i < length; i++) {
            audio[i] = (byte) (i % 251);
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
