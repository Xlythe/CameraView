package com.xlythe.view.camera.stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Stream audio through this device's real codecs and check what comes out is what went in.
 *
 * <p>The microphone is replaced by a known signal said in real time, and the speaker by a
 * recording, so the comparison is between the signal and what the player would have played,
 * across the recorder, the wire format and the player. Everything else is the library's own.</p>
 *
 * <p>The link tests run one end on each of two devices. Start the receiver with
 * {@code -e streamRole receiver}, forward its port to the host, reverse the sender's to the same
 * host port, then start the sender with {@code -e streamRole sender}. The receiver sends its
 * capabilities over the socket before anything else, which is the negotiation as an app would
 * carry it, and the two devices can be on different releases.</p>
 */
@RunWith(AndroidJUnit4.class)
public class StreamAudioDeviceTest {
    private static final String TAG = "StreamAudioDeviceTest";

    /** Wideband speech, the stream default. */
    private static final int SAMPLE_RATE = 16000;

    /** How much signal is streamed. */
    private static final double SECONDS = 4;

    /** The longest codec delay looked for, plus slack for the player's start. */
    private static final int MAX_LAG_SAMPLES = SAMPLE_RATE / 2;

    /** Two seconds of signal are compared. */
    private static final int WINDOW = 2 * SAMPLE_RATE;

    /** The port the receiver listens on. */
    private static final int RECEIVER_PORT = 7000;

    /** The port the sender connects to, reversed through the host to the receiver's. */
    private static final int SENDER_PORT = 7001;

    /** This release negotiates what the brief expects: Opus where it can encode it, else AAC. */
    @Test
    public void negotiatesTheBestFormatThisReleaseShares() {
        byte[] capabilities = PlaybackCapabilities.forThisDevice();
        int[] decodable = PlaybackCapabilities.parse(capabilities);
        int chosen = AudioCodec.choose(decodable, SAMPLE_RATE, 1, Build.VERSION.SDK_INT,
                AudioCodec.DEVICE);

        report("negotiated", "sdk=" + Build.VERSION.SDK_INT + " decodes="
                + Arrays.toString(decodable) + " sends=" + name(chosen));
        assertNotNull(decodable);
        assertEquals(Build.VERSION.SDK_INT >= 29 ? AudioCodec.OPUS : AudioCodec.AAC, chosen);
    }

    @Test
    public void pcmArrivesIntact() throws Exception {
        Result result = loopback(AudioCodec.PCM);
        assertEquals(AudioCodec.PCM, result.codec);
        // Not exactly 1: a player that starts behind discards what it is late by, as a live
        // player should, and a discard inside the compared window costs a little.
        assertTrue(result.toString(), result.match.correlation > 0.99);
        assertTrue(result.toString(), result.spectral > 0.99);
    }

    @Test
    public void aacArrivesIntact() throws Exception {
        Result result = loopback(AudioCodec.AAC);
        assertEquals(AudioCodec.AAC, result.codec);
        assertTrue(result.toString(), result.match.correlation > 0.9);
        assertTrue(result.toString(), result.spectral > 0.95);
        assertTrue(result.toString(), result.ratio > 5);
    }

    @Test
    public void opusArrivesIntact() throws Exception {
        assumeTrue("Opus is encoded from API 29", Build.VERSION.SDK_INT >= 29);
        Result result = loopback(AudioCodec.OPUS);
        assertEquals(AudioCodec.OPUS, result.codec);
        // Opus at 24kbps codes each band's energy and shape rather than its exact waveform, so
        // it matches less closely than AAC at 32kbps: 0.89 and 0.90 on the API 37 emulator.
        assertTrue(result.toString(), result.match.correlation > 0.8);
        assertTrue(result.toString(), result.spectral > 0.85);
        assertTrue(result.toString(), result.ratio > 5);
    }

    /** The receiving end of a stream between two devices. */
    @Test
    public void linkReceiver() throws Exception {
        assumeTrue("set -e streamRole receiver", "receiver".equals(role()));
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(RECEIVER_PORT));
            server.setSoTimeout(180_000);
            try (Socket socket = server.accept()) {
                byte[] capabilities = PlaybackCapabilities.forThisDevice();
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                out.writeInt(capabilities.length);
                out.write(capabilities);
                out.flush();

                TestSignal.CountingInputStream wire =
                        new TestSignal.CountingInputStream(socket.getInputStream());
                TestSignal.Capture capture = new TestSignal.Capture();
                AudioPlayer player = new AudioPlayer(wire, capture);
                CountDownLatch ended = new CountDownLatch(1);
                player.setStreamEndListener(ended::countDown);
                player.start();
                assertTrue("the stream never ended", ended.await(60, TimeUnit.SECONDS));

                Result result = compare(player.getCodec(), capture, wire.count);
                report("link-receiver", "sdk=" + Build.VERSION.SDK_INT + " " + result);
                assertTrue(result.toString(), result.spectral > 0.85);
                TestSignal.save("link-sdk" + Build.VERSION.SDK_INT + "-" + name(result.codec)
                        + "-out-" + capture.sampleRate + "hz-" + capture.channelCount + "ch.pcm",
                        capture.bytes());
                assertTrue(result.toString(), result.match.correlation > 0.8);
            }
        }
    }

    /** The sending end of a stream between two devices. */
    @Test
    public void linkSender() throws Exception {
        assumeTrue("set -e streamRole sender", "sender".equals(role()));
        Socket socket = connect();
        try {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            byte[] capabilities = new byte[in.readInt()];
            in.readFully(capabilities);

            byte[] signal = TestSignal.generate(SAMPLE_RATE, SECONDS);
            TestSignal.Source source = new TestSignal.Source(signal, SAMPLE_RATE);
            AudioRecorder recorder = new AudioRecorder(socket.getOutputStream(), SAMPLE_RATE, 1,
                    new AudioRecorder.Options(capabilities, false, false, false, null),
                    (rate, channels, options) -> source);
            recorder.start();
            awaitFinished(source);
            report("link-sender", "sdk=" + Build.VERSION.SDK_INT + " receiver decodes="
                    + Arrays.toString(PlaybackCapabilities.parse(capabilities))
                    + " sends=" + name(recorder.getCodec())
                    + " bitRate=" + recorder.getBitRate());
            recorder.stop();
        } finally {
            socket.close();
        }
    }

    /** What a stream through one format came out as. */
    private static final class Result {
        final int codec;
        final TestSignal.Match match;
        final double spectral;
        final long wireBytes;
        final double ratio;
        final int outputRate;

        Result(int codec, TestSignal.Match match, double spectral, long wireBytes, double ratio,
                int outputRate) {
            this.codec = codec;
            this.match = match;
            this.spectral = spectral;
            this.wireBytes = wireBytes;
            this.ratio = ratio;
            this.outputRate = outputRate;
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "%s %s, spectrogram r=%.4f, %d bytes on the wire,"
                    + " %.1fx smaller than PCM, played at %dHz", name(codec), match, spectral,
                    wireBytes, ratio, outputRate);
        }
    }

    /**
     * Streams the signal through a pipe, as VideoStream does, to a player on this device, for a
     * receiver that only lists one format.
     */
    private Result loopback(int codec) throws Exception {
        byte[] capabilities = {'X', 'C', 'A', 'P', 1, 1, (byte) codec};
        byte[] signal = TestSignal.generate(SAMPLE_RATE, SECONDS);
        TestSignal.Source source = new TestSignal.Source(signal, SAMPLE_RATE);
        PipedInputStream pipe = new PipedInputStream(128 * 1024);
        OutputStream out = new PipedOutputStream(pipe);
        AudioRecorder recorder = new AudioRecorder(out, SAMPLE_RATE, 1,
                new AudioRecorder.Options(capabilities, false, false, false, null),
                (rate, channels, options) -> source);

        TestSignal.CountingInputStream wire = new TestSignal.CountingInputStream(pipe);
        TestSignal.Capture capture = new TestSignal.Capture();
        AudioPlayer player = new AudioPlayer(wire, capture);
        CountDownLatch ended = new CountDownLatch(1);
        player.setStreamEndListener(ended::countDown);

        // The player first, as a receiver is listening before a sender starts.
        player.start();
        recorder.start();
        awaitFinished(source);
        recorder.stop();
        assertTrue("the player never finished", ended.await(10, TimeUnit.SECONDS));

        Result result = compare(player.getCodec(), capture, wire.count);
        report("loopback-" + name(codec), "sdk=" + Build.VERSION.SDK_INT + " " + result);
        String prefix = "sdk" + Build.VERSION.SDK_INT + "-" + name(codec);
        TestSignal.save(prefix + "-in-" + SAMPLE_RATE + "hz.pcm", signal);
        TestSignal.save(prefix + "-out-" + capture.sampleRate + "hz-" + capture.channelCount
                + "ch.pcm", capture.bytes());
        return result;
    }

    private static Result compare(int codec, TestSignal.Capture capture, long wireBytes) {
        double[] input = TestSignal.samples(TestSignal.generate(SAMPLE_RATE, SECONDS), 1);
        byte[] played = capture.bytes();
        assertTrue("nothing was played", played.length > 0 && capture.sampleRate > 0);
        double[] output = TestSignal.samples(played, Math.max(1, capture.channelCount));
        TestSignal.Match match = TestSignal.compare(input, SAMPLE_RATE, output,
                capture.sampleRate, MAX_LAG_SAMPLES, WINDOW);
        double spectral = TestSignal.spectralCorrelation(input, SAMPLE_RATE, output,
                capture.sampleRate, match, WINDOW);
        double pcmBytes = SECONDS * SAMPLE_RATE * 2;
        return new Result(codec, match, spectral, wireBytes, pcmBytes / Math.max(1, wireBytes),
                capture.sampleRate);
    }

    private static void awaitFinished(TestSignal.Source source) throws InterruptedException {
        long deadline = System.currentTimeMillis() + (long) (SECONDS * 1000) + 10_000;
        while (!source.finished && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue("the signal was never said", source.finished);
        // The codec's own delay, so the end of the signal is through before the stream closes.
        Thread.sleep(500);
    }

    private static Socket connect() throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + 120_000;
        while (true) {
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress("127.0.0.1", SENDER_PORT), 5_000);
                return socket;
            } catch (IOException e) {
                if (System.currentTimeMillis() > deadline) {
                    throw e;
                }
                Thread.sleep(500);
            }
        }
    }

    private static String role() {
        return InstrumentationRegistry.getArguments().getString("streamRole");
    }

    private static String name(int codec) {
        switch (codec) {
            case AudioCodec.AAC:
                return "AAC";
            case AudioCodec.OPUS:
                return "Opus";
            default:
                return "PCM";
        }
    }

    /** Logs a measurement and puts it in the instrumentation output, where a run keeps it. */
    private static void report(String key, String value) {
        Log.i(TAG, key + ": " + value);
        Bundle bundle = new Bundle();
        bundle.putString(TAG + "." + key, value);
        InstrumentationRegistry.getInstrumentation().sendStatus(0, bundle);
    }
}
