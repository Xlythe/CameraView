package com.xlythe.view.camera.stream;

import androidx.annotation.NonNull;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Locale;

/**
 * A known signal to stream, and how closely what came out matches it.
 *
 * <p>Speech-like rather than a pure tone: five partials across the speech band under a syllable
 * rate envelope, so a codec that only kept the loudest partial, or smeared the envelope, would
 * score visibly lower. Deterministic, so two devices generate the same one.</p>
 */
final class TestSignal {
    /** The partials, in hertz: all under 4kHz, so every rate the tests use carries them. */
    private static final double[] PARTIALS = {300, 650, 1100, 2300, 3700};

    /** The envelope's rate, about that of syllables. */
    private static final double ENVELOPE_HZ = 3.7;

    /** A slow vibrato on every partial, so the signal never repeats and a delay is unambiguous. */
    private static final double VIBRATO_HZ = 5.3;

    /** How far the vibrato swings, as a share of each partial's frequency. */
    private static final double VIBRATO_DEPTH = 0.03;

    /** Peak level as a share of full scale. */
    private static final double LEVEL = 0.6;

    private TestSignal() {}

    /**
     * Keeps a file of evidence in the test app's own storage, where {@code run-as} can fetch it
     * for the plots that go with the report.
     */
    static void save(@NonNull String name, @NonNull byte[] bytes) {
        File directory = new File(InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getFilesDir(), "streamaudio");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            return;
        }
        try (FileOutputStream out = new FileOutputStream(new File(directory, name))) {
            out.write(bytes);
        } catch (IOException e) {
            // Evidence is a convenience; the assertions are the test.
        }
    }

    /** Mono 16-bit little-endian PCM. */
    @NonNull
    static byte[] generate(int sampleRate, double seconds) {
        int samples = (int) (sampleRate * seconds);
        ByteBuffer pcm = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < samples; i++) {
            double t = (double) i / sampleRate;
            double envelope = 0.35 + 0.65 * (0.5 + 0.5 * Math.sin(2 * Math.PI * ENVELOPE_HZ * t));
            // The phase of a partial whose frequency swings sinusoidally, integrated in closed form.
            double swing = VIBRATO_DEPTH / (2 * Math.PI * VIBRATO_HZ)
                    * (1 - Math.cos(2 * Math.PI * VIBRATO_HZ * t));
            double value = 0;
            for (int p = 0; p < PARTIALS.length; p++) {
                double phase = 2 * Math.PI * PARTIALS[p] * (t + swing);
                value += Math.sin(phase + p) / PARTIALS.length;
            }
            pcm.putShort((short) Math.round(value * envelope * LEVEL * Short.MAX_VALUE));
        }
        return pcm.array();
    }

    /** 16-bit little-endian PCM as samples, the first channel only. */
    @NonNull
    static double[] samples(@NonNull byte[] pcm, int channelCount) {
        ByteBuffer buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        double[] samples = new double[pcm.length / 2 / channelCount];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = buffer.getShort(i * 2 * channelCount);
        }
        return samples;
    }

    /** What a comparison found. */
    static final class Match {
        /** The peak normalised cross-correlation, 1 for identical. */
        final double correlation;

        /** Where it peaked: how many input samples late the output was. */
        final int lagSamples;

        /** Which of the output's samples lined up, when it runs at a multiple of the rate. */
        final int phase;

        Match(double correlation, int lagSamples, int phase) {
            this.correlation = correlation;
            this.lagSamples = lagSamples;
            this.phase = phase;
        }

        @NonNull
        @Override
        public String toString() {
            return String.format(Locale.US, "r=%.4f at lag %d", correlation, lagSamples);
        }
    }

    /**
     * How closely the output follows the input, allowing for the codec's delay and for output at
     * a whole multiple of the input's rate, which is how Opus decodes.
     *
     * @param window input samples compared, taken from a second into the input so a codec's
     *     start-up is not counted
     */
    @NonNull
    static Match compare(@NonNull double[] input, int inputRate, @NonNull double[] output,
            int outputRate, int maxLagSamples, int window) {
        int factor = Math.max(1, Math.round((float) outputRate / inputRate));
        int start = Math.min(inputRate, Math.max(0, input.length - window - maxLagSamples));
        Match best = new Match(-1, 0, 0);
        for (int phase = 0; phase < factor; phase++) {
            int length = (output.length - phase) / factor;
            double[] decimated = new double[Math.max(0, length)];
            for (int i = 0; i < decimated.length; i++) {
                decimated[i] = output[phase + i * factor];
            }
            // From before zero too: audio lost at the start makes the output early, not late.
            for (int lag = -maxLagSamples / 2; lag <= maxLagSamples; lag++) {
                double cross = 0;
                double inEnergy = 0;
                double outEnergy = 0;
                int end = Math.min(start + window, Math.min(input.length, decimated.length - lag));
                for (int i = Math.max(start, -lag); i < end; i++) {
                    double x = input[i];
                    double y = decimated[i + lag];
                    cross += x * y;
                    inEnergy += x * x;
                    outEnergy += y * y;
                }
                if (inEnergy == 0 || outEnergy == 0) {
                    continue;
                }
                double r = cross / Math.sqrt(inEnergy * outEnergy);
                if (r > best.correlation) {
                    best = new Match(r, lag, phase);
                }
            }
        }
        return best;
    }

    /**
     * How closely the output's spectrogram follows the input's, as the correlation of their
     * short-time magnitude spectra, aligned at a match from {@link #compare}. Linear magnitudes,
     * so the comparison weighs what is loud, as a listener does, rather than the noise floor.
     *
     * <p>A perceptual codec keeps each band's energy and not its exact phase, so a waveform
     * correlation understates it: Opus can sound right and still score well under 1. The
     * spectrogram is what a listener hears.</p>
     */
    static double spectralCorrelation(@NonNull double[] input, int inputRate,
            @NonNull double[] output, int outputRate, @NonNull Match match, int window) {
        int factor = Math.max(1, Math.round((float) outputRate / inputRate));
        int start = Math.min(inputRate, Math.max(0, input.length - window));
        int size = 512;
        int hop = 256;
        double sumX = 0;
        double sumY = 0;
        double sumXX = 0;
        double sumYY = 0;
        double sumXY = 0;
        int count = 0;
        double[] a = new double[size];
        double[] b = new double[size];
        for (int frame = start; frame + size <= start + window; frame += hop) {
            boolean complete = true;
            for (int i = 0; i < size; i++) {
                double hann = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (size - 1));
                int o = (frame + i + match.lagSamples) * factor + match.phase;
                if (frame + i >= input.length || o < 0 || o >= output.length) {
                    complete = false;
                    break;
                }
                a[i] = input[frame + i] * hann;
                b[i] = output[o] * hann;
            }
            if (!complete) {
                break;
            }
            double[] ma = magnitudes(a);
            double[] mb = magnitudes(b);
            for (int k = 0; k < ma.length; k++) {
                double x = ma[k];
                double y = mb[k];
                sumX += x;
                sumY += y;
                sumXX += x * x;
                sumYY += y * y;
                sumXY += x * y;
                count++;
            }
        }
        double cov = sumXY - sumX * sumY / count;
        double varX = sumXX - sumX * sumX / count;
        double varY = sumYY - sumY * sumY / count;
        return cov / Math.sqrt(varX * varY);
    }

    /** The magnitude spectrum of a power-of-two block, up to Nyquist, by a radix-2 FFT. */
    @NonNull
    static double[] magnitudes(@NonNull double[] block) {
        int n = block.length;
        double[] re = block.clone();
        double[] im = new double[n];
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
            }
        }
        for (int length = 2; length <= n; length <<= 1) {
            double angle = -2 * Math.PI / length;
            for (int i = 0; i < n; i += length) {
                for (int k = 0; k < length / 2; k++) {
                    double wr = Math.cos(angle * k);
                    double wi = Math.sin(angle * k);
                    int p = i + k;
                    int q = i + k + length / 2;
                    double xr = re[q] * wr - im[q] * wi;
                    double xi = re[q] * wi + im[q] * wr;
                    re[q] = re[p] - xr;
                    im[q] = im[p] - xi;
                    re[p] += xr;
                    im[p] += xi;
                }
            }
        }
        double[] magnitude = new double[n / 2];
        for (int k = 0; k < magnitude.length; k++) {
            magnitude[k] = Math.hypot(re[k], im[k]);
        }
        return magnitude;
    }

    /** Counts what passes through, to measure what crossed the link. */
    static final class CountingInputStream extends FilterInputStream {
        /** Bytes read so far. */
        long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                count++;
            }
            return value;
        }

        @Override
        public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                count += read;
            }
            return read;
        }
    }

    /** A microphone that says the signal in real time, then silence. */
    static final class Source implements AudioRecorder.PcmSource {
        /** What it says. */
        private final byte[] mSignal;

        /** Its rate. */
        private final int mSampleRate;

        /** The size of each read. */
        private final int mChunkBytes;

        /** How far through it is. */
        private int mOffset;

        /** When the first read happened, which paces the rest. */
        private long mStartNanos = -1;

        /** Whether all of it has been said. */
        volatile boolean finished;

        Source(byte[] signal, int sampleRate) {
            mSignal = signal;
            mSampleRate = sampleRate;
            mChunkBytes = sampleRate / 50 * 2;
        }

        @Override
        public int getSampleRate() {
            return mSampleRate;
        }

        @Override
        public int getChannelCount() {
            return 1;
        }

        @Override
        public int getBufferSize() {
            return mChunkBytes;
        }

        @Override
        public int read(@NonNull byte[] buffer, int offset, int length) {
            if (mStartNanos < 0) {
                mStartNanos = System.nanoTime();
            }
            long due = mStartNanos + (long) mOffset / 2 * 1_000_000_000L / mSampleRate;
            long wait = due - System.nanoTime();
            if (wait > 0) {
                try {
                    Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            int count = Math.min(Math.min(length, mChunkBytes), mSignal.length - mOffset);
            if (count <= 0) {
                finished = true;
                // Silence after the signal, at the rate a microphone would deliver it.
                count = Math.min(length, mChunkBytes);
                Arrays.fill(buffer, offset, offset + count, (byte) 0);
                mOffset += count;
                return count;
            }
            System.arraycopy(mSignal, mOffset, buffer, offset, count);
            mOffset += count;
            return count;
        }

        @Override
        public void close() {}
    }

    /** A speaker that keeps everything it is given. */
    static final class Capture implements AudioPlayer.PcmOutput.Factory {
        /** What was played. */
        final ByteArrayOutputStream pcm = new ByteArrayOutputStream();

        /** The rate the player opened the speaker at. */
        volatile int sampleRate;

        /** The channels. */
        volatile int channelCount;

        @NonNull
        @Override
        public AudioPlayer.PcmOutput open(int sampleRate, int channelCount, int fallbackBytes) {
            this.sampleRate = sampleRate;
            this.channelCount = channelCount;
            return new AudioPlayer.PcmOutput() {
                @Override
                public void write(@NonNull byte[] data, int offset, int length) {
                    synchronized (pcm) {
                        pcm.write(data, offset, length);
                    }
                }

                @Override
                public void release() {}
            };
        }

        byte[] bytes() {
            synchronized (pcm) {
                return pcm.toByteArray();
            }
        }
    }
}
