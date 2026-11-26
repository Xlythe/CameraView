package com.xlythe.view.camera.stream;

import android.media.AudioFormat;
import android.media.MediaCodec;

import androidx.annotation.Nullable;

import com.google.common.primitives.Ints;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * A live stream crossing a link, stepped one simulated millisecond at a time.
 *
 * <p>The sending half is the library's own: a producer writes through
 * {@link LossyPipedOutputStream} the way {@link AudioRecorder} and {@link VideoRecorder} do, into a
 * pipe sized the way {@code VideoStream} sizes it. Everything after the pipe is a model of what
 * the library cannot see - the app's pump, the transport, the link and the player - because that
 * is what decides whether the pipe's back-pressure ever arrives.</p>
 *
 * <p>One thread and a simulated clock, so a run is a function of its configuration alone and
 * reports the same numbers on every machine.</p>
 */
final class LinkSimulation {
    /** How long a run lasts. Long enough to tell a backlog that grows from one that settles. */
    static final int RUN_MILLIS = 20_000;

    /** What the app's pump reads at a time, as WearFragment does. */
    static final int PUMP_CHUNK_BYTES = 8 * 1024;

    /** One-way delay across the link by default. A Data Layer round trip is tens of ms. */
    static final int ONE_WAY_MILLIS = 30;

    /** Latency a stream has to be back under before it counts as recovered from an outage. */
    static final int RECOVERED_MILLIS = 400;

    /** The window "where it ended up" is averaged over. */
    private static final int TAIL_MILLIS = 1_000;

    // ---------- Audio, as AudioRecorder and VideoStream produce it ----------

    /** VideoStream's audio pipe. */
    static final int AUDIO_PIPE_BYTES = 128 * 1024;

    /** AudioBuffer's default rate. */
    static final int AUDIO_SAMPLE_RATE = 16_000;

    /** The drop threshold AudioRecorder sets at that rate. */
    static final int AUDIO_MAX_BUFFERED_BYTES = AudioRecorder.backlogBytesFor(
            AUDIO_SAMPLE_RATE, 1, AudioRecorder.TARGET_BACKLOG_MILLIS);

    /** Mono, 16 bits a sample. */
    static final int AUDIO_BYTES_PER_MS = AUDIO_SAMPLE_RATE * 2 / 1000;

    /** A typical AudioRecord minimum buffer at this rate, and so the length of each chunk. */
    static final int AUDIO_CHUNK_MILLIS = 20;

    /** Bytes in one chunk. */
    static final int AUDIO_CHUNK_BYTES = AUDIO_CHUNK_MILLIS * AUDIO_BYTES_PER_MS;

    /** A typical AudioTrack minimum buffer at this rate, which is what AudioPlayer asks for. */
    static final int AUDIO_TRACK_BYTES = 40 * AUDIO_BYTES_PER_MS;

    // ---------- Video, as VideoRecorder and the Wear sample produce it ----------

    /** VideoStream's video pipe. */
    static final int VIDEO_PIPE_BYTES = 1024 * 1024;

    /** The Wear sample's bit rate. */
    static final int VIDEO_BIT_RATE = 1_000_000;

    /** The Wear sample's frame rate. */
    static final int VIDEO_FRAME_RATE = 20;

    /** Frames between key frames: the Wear sample's one second. */
    static final int VIDEO_KEY_FRAME_INTERVAL = VIDEO_FRAME_RATE;

    /** How much larger a key frame is than a delta, which is typical of H.264 at this size. */
    static final int VIDEO_KEY_FRAME_RATIO = 8;

    /** Nominal bytes a millisecond. */
    static final int VIDEO_BYTES_PER_MS = VIDEO_BIT_RATE / 8 / 1000;

    /** What is being streamed. */
    enum Media {
        /** 16kHz mono PCM in 20ms chunks, through AudioRecorder's framing and VideoStream's pipe. */
        AUDIO,
        /** H.264 at 1Mbps and 20fps, the Wear sample's preview. */
        VIDEO
    }

    /** How the app moves bytes from the stream into the transport. */
    enum Pump {
        /** Reads whenever there is something to read and writes it straight on, as WearFragment does. */
        NAIVE,
        /** Reads only as much as the transport will take without queueing, then sends it at once. */
        PACED
    }

    /** What the transport does with a write it cannot send yet. */
    enum TransportKind {
        /** Accepts it into a queue with no limit, which is the failure case under test. */
        UNBOUNDED,
        /** Accepts up to a fixed number of bytes and blocks the writer beyond that. */
        BLOCKING,
        /** Unbounded underneath, but the receiver acknowledges what it has read. */
        ACKNOWLEDGED
    }

    /** A transport and its one limit, so a row of results can name it. */
    static final class Transport {
        /** Which kind of transport. */
        final TransportKind kind;

        /**
         * For {@link TransportKind#BLOCKING}, the bytes it holds; for
         * {@link TransportKind#ACKNOWLEDGED}, the milliseconds of stream allowed in flight.
         */
        final int limit;

        private Transport(TransportKind kind, int limit) {
            this.kind = kind;
            this.limit = limit;
        }

        static Transport unbounded() {
            return new Transport(TransportKind.UNBOUNDED, 0);
        }

        static Transport blocking(int bytes) {
            return new Transport(TransportKind.BLOCKING, bytes);
        }

        static Transport acknowledged(int windowMillis) {
            return new Transport(TransportKind.ACKNOWLEDGED, windowMillis);
        }

        @Override
        public String toString() {
            switch (kind) {
                case BLOCKING:
                    return "blocking " + limit / 1024 + "KB";
                case ACKNOWLEDGED:
                    return "acked " + limit + "ms";
                default:
                    return "unbounded";
            }
        }
    }

    /** The link's capacity over a run, as a multiple of the stream's nominal rate. */
    static final class Link {
        /** What a row of results calls it. */
        private final String mName;

        /** Capacity outside any outage, as a multiple of the nominal rate. */
        private final double mMultiple;

        /** Start of an outage, inclusive, or -1 for none. */
        private final int mOutageFrom;

        /** End of the outage, exclusive. */
        private final int mOutageTo;

        /** Period of an on/off pattern, or 0 for a steady link. */
        private final int mBurstPeriod;

        /** Milliseconds of each period the link is up. Its capacity is packed into them. */
        private final int mBurstUp;

        private Link(String name, double multiple, int outageFrom, int outageTo,
                int burstPeriod, int burstUp) {
            mName = name;
            mMultiple = multiple;
            mOutageFrom = outageFrom;
            mOutageTo = outageTo;
            mBurstPeriod = burstPeriod;
            mBurstUp = burstUp;
        }

        /** A link that carries a steady multiple of the stream for the whole run. */
        static Link steady(String name, double multiple) {
            return new Link(name, multiple, -1, -1, 0, 0);
        }

        /** A link with room to spare that goes down completely for a while. */
        static Link outage(String name, double multiple, int from, int to) {
            return new Link(name, multiple, from, to, 0, 0);
        }

        /** A link that delivers its average in bursts, as a radio sharing its time does. */
        static Link bursty(String name, double multiple, int period, int up) {
            return new Link(name, multiple, -1, -1, period, up);
        }

        double capacityAt(long t) {
            if (t >= mOutageFrom && t < mOutageTo) {
                return 0;
            }
            if (mBurstPeriod > 0) {
                return t % mBurstPeriod < mBurstUp ? mMultiple * mBurstPeriod / mBurstUp : 0;
            }
            return mMultiple;
        }

        /** The first millisecond at or after t that anything can cross. */
        long nextUp(long t) {
            while (capacityAt(t) <= 0) {
                t++;
            }
            return t;
        }

        /** When the outage ends, or -1 for a link without one. */
        int outageEnd() {
            return mOutageTo;
        }

        @Override
        public String toString() {
            return mName;
        }
    }

    /** What a run measured. */
    static final class Result {
        /** What was streamed. */
        Media media;
        /** The link it crossed. */
        Link link;
        /** The transport it crossed it on. */
        Transport transport;
        /** How the app fed the transport. */
        Pump pump;
        /** How long a reader could go without reading before the pipe dropped anyway, or 0. */
        int stallTimeoutMillis;
        /** The pipe's drop threshold, as milliseconds of the stream at its nominal rate. */
        int pipeBudgetMillis;
        /** Whether the audio player discarded what waited too long, as AudioPlayer does. */
        boolean catchUp;

        /** Bytes the producer offered the pipe. */
        long producedBytes;
        /** Bytes the pipe discarded. */
        long droppedBytes;
        /** Bytes of silence the audio player played for want of audio, once it had started. */
        long underrunBytes;
        /** Bytes the audio player discarded to catch up. */
        long skippedBytes;
        /** Whether the stream's header arrived first and intact. */
        boolean headerIntact;
        /** Records that arrived torn or out of order. Anything but zero is a desync. */
        int corruptRecords;
        /** Video frames that arrived but whose reference chain a drop had broken. */
        int undecodableFrames;
        /** Video frames that arrived and could be decoded. */
        int decodableFrames;
        /** When each sample was taken and its latency: per frame for video, per ms for audio. */
        final List<long[]> latencies = new ArrayList<>();

        void sample(long now, long latency) {
            latencies.add(new long[] {now, latency});
        }

        /** The latency below which the given fraction of samples fall, or -1 with no samples. */
        long percentile(double fraction) {
            if (latencies.isEmpty()) {
                return -1;
            }
            long[] sorted = new long[latencies.size()];
            for (int i = 0; i < sorted.length; i++) {
                sorted[i] = latencies.get(i)[1];
            }
            Arrays.sort(sorted);
            return sorted[(int) Math.min(sorted.length - 1, Math.floor(fraction * sorted.length))];
        }

        long maxLatency() {
            return percentile(1.0);
        }

        /** Mean latency over the second before the given moment, or -1 with no samples. */
        long latencyBefore(long t) {
            long sum = 0;
            int count = 0;
            for (long[] sample : latencies) {
                if (sample[0] >= t - TAIL_MILLIS && sample[0] < t) {
                    sum += sample[1];
                    count++;
                }
            }
            return count == 0 ? -1 : sum / count;
        }

        /** Mean latency over the last second, which is where a backlog that never drains shows. */
        long tailLatency() {
            return latencyBefore(RUN_MILLIS);
        }

        double droppedPercent() {
            return producedBytes == 0 ? 0 : 100.0 * droppedBytes / producedBytes;
        }

        long underrunMillis() {
            return underrunBytes / AUDIO_BYTES_PER_MS;
        }

        long skippedMillis() {
            return skippedBytes / AUDIO_BYTES_PER_MS;
        }

        /** Frames a second the viewer could actually have seen. */
        double decodableFramesPerSecond() {
            return decodableFrames * 1000.0 / RUN_MILLIS;
        }

        /**
         * How long after the link's outage the latency took to come back under
         * {@link #RECOVERED_MILLIS} and stay there, or -1 if it was still over it in the last
         * second of the run.
         */
        long recovery() {
            long lastOver = -1;
            for (long[] sample : latencies) {
                if (sample[1] > RECOVERED_MILLIS) {
                    lastOver = sample[0];
                }
            }
            if (lastOver >= RUN_MILLIS - TAIL_MILLIS) {
                return -1;
            }
            return Math.max(0, lastOver + 1 - link.outageEnd());
        }

        static String header() {
            return String.format(Locale.US, "%-5s | %-6s | %-13s | %-5s | %5s | %5s | %5s | %5s"
                            + " | %5s | %5s | %6s | %6s | %7s | %7s | %-21s | %s",
                    "media", "link", "transport", "pump", "pipe", "stall", "p50", "p95", "max",
                    "end", "drop", "gaps", "skipped", "recover", "decodable", "intact");
        }

        String row() {
            long recovery = recovery();
            return String.format(Locale.US, "%-5s | %-6s | %-13s | %-5s | %5s | %5s | %5d | %5d"
                            + " | %5d | %5d | %5.1f%% | %4dms | %7s | %7s | %-21s | %s",
                    media.name().toLowerCase(Locale.US), link, transport,
                    pump.name().toLowerCase(Locale.US),
                    pipeBudgetMillis + "ms",
                    stallTimeoutMillis == 0 ? "-" : stallTimeoutMillis + "ms",
                    percentile(0.5), percentile(0.95), maxLatency(), tailLatency(),
                    droppedPercent(), underrunMillis(),
                    media == Media.VIDEO ? "-" : catchUp ? skippedMillis() + "ms" : "off",
                    link.outageEnd() < 0 ? "-" : recovery < 0 ? "never" : recovery + "ms",
                    media == Media.AUDIO ? "-" : String.format(Locale.US, "%.1f/s, %d broken",
                            decodableFramesPerSecond(), undecodableFrames),
                    headerIntact && corruptRecords == 0 ? "yes" : "NO");
        }
    }

    // ---------- Configuration ----------

    /** What is streamed. */
    private final Media mMedia;
    /** The link it crosses. */
    private Link mLink = Link.steady("2x", 2);
    /** The transport it crosses it on. */
    private Transport mTransport = Transport.unbounded();
    /** How the app feeds the transport. */
    private Pump mPump = Pump.NAIVE;
    /** One-way delay across the link, which acknowledgements pay on the way back too. */
    private int mOneWayMillis = ONE_WAY_MILLIS;
    /** The audio pipe's drop threshold. */
    private int mAudioMaxBufferedBytes = AUDIO_MAX_BUFFERED_BYTES;
    /** Fraction of its nominal bit rate the video encoder actually produces. */
    private double mVideoFill = 1.0;
    /** Frames between scheduled key frames. */
    private int mKeyFrameInterval = VIDEO_KEY_FRAME_INTERVAL;
    /** How long the pipe lets its reader go without reading before it drops anyway, or 0. */
    private int mStallTimeoutMillis;
    /** Whether the audio player discards what waited too long, as AudioPlayer does. */
    private boolean mCatchUp = true;

    LinkSimulation(Media media) {
        mMedia = media;
    }

    LinkSimulation link(Link link) {
        mLink = link;
        return this;
    }

    LinkSimulation transport(Transport transport) {
        mTransport = transport;
        return this;
    }

    LinkSimulation pump(Pump pump) {
        mPump = pump;
        return this;
    }

    LinkSimulation oneWayMillis(int millis) {
        mOneWayMillis = millis;
        return this;
    }

    LinkSimulation audioMaxBufferedBytes(int bytes) {
        mAudioMaxBufferedBytes = bytes;
        return this;
    }

    /** An encoder under its target, as one is on a scene that hardly changes. */
    LinkSimulation videoFill(double fraction) {
        mVideoFill = fraction;
        return this;
    }

    LinkSimulation keyFrameInterval(int frames) {
        mKeyFrameInterval = frames;
        return this;
    }

    /** Plays every byte of audio however late, as AudioPlayer did before it caught up. */
    LinkSimulation catchUp(boolean catchUp) {
        mCatchUp = catchUp;
        return this;
    }

    /** Runs the pipe as {@link StallAwarePipe}, which is the policy under question. */
    LinkSimulation stallTimeoutMillis(int millis) {
        mStallTimeoutMillis = millis;
        return this;
    }

    // ---------- The run ----------

    Result run() throws IOException {
        Result result = new Result();
        result.media = mMedia;
        result.link = mLink;
        result.transport = mTransport;
        result.pump = mPump;
        result.stallTimeoutMillis = mStallTimeoutMillis;
        result.catchUp = mCatchUp;

        boolean audio = mMedia == Media.AUDIO;
        int bytesPerMs = audio ? AUDIO_BYTES_PER_MS : VIDEO_BYTES_PER_MS;
        int maxBufferedBytes = audio ? mAudioMaxBufferedBytes : VideoRecorder.backlogBytesFor(
                VIDEO_BIT_RATE, VideoRecorder.TARGET_BACKLOG_MILLIS);
        result.pipeBudgetMillis = maxBufferedBytes / bytesPerMs;

        long[] clock = new long[1];
        PipedInputStream pipe = new PipedInputStream(audio ? AUDIO_PIPE_BYTES : VIDEO_PIPE_BYTES);
        LossyPipedOutputStream lossy = mStallTimeoutMillis > 0
                ? new StallAwarePipe(pipe, maxBufferedBytes, mStallTimeoutMillis, clock)
                : new LossyPipedOutputStream(pipe, maxBufferedBytes);

        Producer producer;
        Receiver receiver;
        if (audio) {
            AudioProducer audioProducer = new AudioProducer(result);
            producer = audioProducer;
            receiver = new AudioReceiver(result, audioProducer,
                    mCatchUp ? new AudioCatchUp(AUDIO_SAMPLE_RATE, 1) : null);
        } else {
            producer = new VideoProducer(result, mVideoFill, mKeyFrameInterval);
            receiver = new VideoReceiver(result);
        }
        Channel channel = new Channel(mTransport, mLink, bytesPerMs, mOneWayMillis);

        byte[] held = new byte[PUMP_CHUNK_BYTES];
        int heldOffset = 0;
        int heldLength = 0;
        for (long now = 0; now < RUN_MILLIS; now++) {
            clock[0] = now;
            producer.step(now, lossy);

            channel.receiveAcks(now);
            while (true) {
                if (heldLength > 0) {
                    // A blocking write that has not finished keeps the pump from reading.
                    int accepted = channel.offer(held, heldOffset, heldLength);
                    heldOffset += accepted;
                    heldLength -= accepted;
                    if (heldLength > 0) {
                        break;
                    }
                    continue;
                }
                int want = Math.min(pipe.available(), PUMP_CHUNK_BYTES);
                if (mPump == Pump.PACED) {
                    want = Math.min(want, channel.writable());
                }
                if (want <= 0) {
                    break;
                }
                heldOffset = 0;
                heldLength = pipe.read(held, 0, want);
            }

            channel.step(now, receiver);
            receiver.step(now, channel);
        }
        lossy.close();
        return result;
    }

    // ---------- The policy under question ----------

    /**
     * The pipe with a stall timeout added: a reader that has read nothing for a while is treated
     * as behind, however small its backlog, and droppable writes are discarded until it reads.
     *
     * <p>The pipe cannot see reads directly, but only the writer ever adds to the backlog, so a
     * backlog smaller than the one the last write left means the reader took some of it.</p>
     */
    static final class StallAwarePipe extends LossyPipedOutputStream {
        /** How long the reader may go without reading while there is something to read. */
        private final int mTimeoutMillis;
        /** The simulation's clock. */
        private final long[] mClock;
        /** The backlog as the last write left it. */
        private int mBacklogAfterWrite;
        /** The last time the reader was seen to read, or had nothing to read. */
        private long mLastProgress;
        /** Whether a flush has ended the last record, after which a byte write may be dropped. */
        private boolean mFlushed;

        StallAwarePipe(PipedInputStream sink, int maxBufferedBytes, int timeoutMillis,
                long[] clock) throws IOException {
            super(sink, maxBufferedBytes);
            mTimeoutMillis = timeoutMillis;
            mClock = clock;
        }

        private boolean stalled() throws IOException {
            int backlog = bufferedBytes();
            if (backlog == 0 || backlog < mBacklogAfterWrite) {
                mLastProgress = mClock[0];
            }
            mBacklogAfterWrite = backlog;
            return backlog > 0 && mClock[0] - mLastProgress >= mTimeoutMillis;
        }

        @Override
        public synchronized boolean writeFrame(byte[] frame, boolean droppable)
                throws IOException {
            if (stalled() && droppable) {
                return false;
            }
            boolean written = super.writeFrame(frame, droppable);
            mBacklogAfterWrite = bufferedBytes();
            return written;
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
            if (stalled() && mFlushed) {
                return;
            }
            mFlushed = false;
            super.write(bytes, offset, length);
            mBacklogAfterWrite = bufferedBytes();
        }

        @Override
        public synchronized void flush() throws IOException {
            super.flush();
            mFlushed = true;
        }
    }

    // ---------- Producers ----------

    /** Writes into the pipe the way the library's recorders do. */
    private abstract static class Producer {
        /** Where the run's numbers go. */
        final Result mResult;

        Producer(Result result) {
            mResult = result;
        }

        abstract void step(long now, LossyPipedOutputStream lossy) throws IOException;
    }

    /**
     * AudioRecorder's loop: a header, then one whole chunk per write, each followed by a flush.
     *
     * <p>Each chunk starts with its sequence number and capture time and is filled with a pattern
     * derived from them, so the receiver can tell a torn chunk from a whole one.</p>
     */
    private static final class AudioProducer extends Producer {
        /** Stream offset of each chunk the pipe accepted, and when its first byte was captured. */
        private final List<long[]> mAccepted = new ArrayList<>();
        /** Bytes the pipe has accepted, which is the stream offset of the next one. */
        private long mOffset;
        /** The next chunk's sequence number. */
        private int mSequence;

        AudioProducer(Result result) {
            super(result);
        }

        @Override
        void step(long now, LossyPipedOutputStream lossy) throws IOException {
            if (now == 0) {
                int before = lossy.bufferedBytes();
                new AudioStreamHeader(AUDIO_SAMPLE_RATE, 1, AudioFormat.ENCODING_PCM_16BIT)
                        .writeTo(lossy);
                mOffset += lossy.bufferedBytes() - before;
                return;
            }
            // AudioRecord hands a chunk over once it has filled it.
            if (now % AUDIO_CHUNK_MILLIS != 0) {
                return;
            }
            long capturedAt = now - AUDIO_CHUNK_MILLIS;
            byte[] chunk = audioChunk(mSequence++, (int) capturedAt);
            mResult.producedBytes += chunk.length;

            int before = lossy.bufferedBytes();
            lossy.write(chunk, 0, chunk.length);
            lossy.flush();
            if (lossy.bufferedBytes() - before == chunk.length) {
                mAccepted.add(new long[] {mOffset, capturedAt});
                mOffset += chunk.length;
            } else {
                mResult.droppedBytes += chunk.length;
            }
        }

        /** When the byte at a stream offset was captured. */
        long capturedAt(long offset) {
            int low = 0;
            int high = mAccepted.size() - 1;
            while (low < high) {
                int mid = (low + high + 1) >>> 1;
                if (mAccepted.get(mid)[0] <= offset) {
                    low = mid;
                } else {
                    high = mid - 1;
                }
            }
            long[] chunk = mAccepted.get(low);
            return chunk[1] + (offset - chunk[0]) / AUDIO_BYTES_PER_MS;
        }
    }

    static byte[] audioChunk(int sequence, int capturedAt) {
        byte[] chunk = new byte[AUDIO_CHUNK_BYTES];
        System.arraycopy(Ints.toByteArray(sequence), 0, chunk, 0, 4);
        System.arraycopy(Ints.toByteArray(capturedAt), 0, chunk, 4, 4);
        for (int i = 8; i < chunk.length; i++) {
            chunk[i] = (byte) (sequence * 31 + i);
        }
        return chunk;
    }

    /**
     * VideoRecorder's loop: a header and the codec configuration, then length-prefixed frames
     * through {@link LossyPipedOutputStream#writeFrame}, with a key frame made early whenever the
     * pipe asks for one.
     */
    private static final class VideoProducer extends Producer {
        /** Milliseconds between frames. */
        private static final int FRAME_MILLIS = 1000 / VIDEO_FRAME_RATE;

        /** Frames between scheduled key frames. */
        private final int mKeyFrameInterval;
        /** Bytes in a delta frame, chosen so a group of pictures averages the bit rate. */
        private final int mDeltaBytes;
        /** Bytes in a key frame. */
        private final int mKeyBytes;
        /** The next frame's index. */
        private int mIndex;
        /** Frames since the last key frame. */
        private int mSinceKeyFrame;
        /** Whether the pipe asked for the next frame to be a key frame. */
        private boolean mKeyFrameRequested;

        VideoProducer(Result result, double fill, int keyFrameInterval) {
            super(result);
            mKeyFrameInterval = keyFrameInterval;
            double groupBytes = fill * VIDEO_BIT_RATE / 8.0 * keyFrameInterval / VIDEO_FRAME_RATE;
            mDeltaBytes = (int) (groupBytes / (keyFrameInterval - 1 + VIDEO_KEY_FRAME_RATIO));
            mKeyBytes = mDeltaBytes * VIDEO_KEY_FRAME_RATIO;
        }

        @Override
        void step(long now, LossyPipedOutputStream lossy) throws IOException {
            if (now == 0) {
                write(lossy, new VideoFrame.Builder(VideoFrame.Type.HEADER)
                        .width(640).height(480).bitRate(VIDEO_BIT_RATE)
                        .frameRate(VIDEO_FRAME_RATE).iframeInterval(1)
                        .build().asBytes(), false, false);
                write(lossy, videoFrame(-1, 32, now, MediaCodec.BUFFER_FLAG_CODEC_CONFIG),
                        false, false);
            }
            if (now % FRAME_MILLIS != 0) {
                return;
            }
            boolean key = mSinceKeyFrame % mKeyFrameInterval == 0 || mKeyFrameRequested;
            mKeyFrameRequested = false;
            mSinceKeyFrame = key ? 1 : mSinceKeyFrame + 1;
            int flags = key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            byte[] frame = videoFrame(mIndex++, key ? mKeyBytes : mDeltaBytes, now, flags);
            write(lossy, frame, VideoRecorder.isDroppable(flags), true);
            if (lossy.consumeResyncRequest()) {
                mKeyFrameRequested = true;
            }
        }

        private void write(LossyPipedOutputStream lossy, byte[] frame, boolean droppable,
                boolean counted) throws IOException {
            byte[] prefixed = new byte[Integer.BYTES + frame.length];
            System.arraycopy(Ints.toByteArray(frame.length), 0, prefixed, 0, Integer.BYTES);
            System.arraycopy(frame, 0, prefixed, Integer.BYTES, frame.length);
            if (counted) {
                mResult.producedBytes += prefixed.length;
            }
            if (!lossy.writeFrame(prefixed, droppable) && counted) {
                mResult.droppedBytes += prefixed.length;
            }
        }
    }

    static byte[] videoFrame(int index, int length, long capturedAt, int flags) {
        byte[] data = new byte[Math.max(4, length)];
        System.arraycopy(Ints.toByteArray(index), 0, data, 0, 4);
        for (int i = 4; i < data.length; i++) {
            data[i] = (byte) (index * 7 + i);
        }
        return new VideoFrame.Builder(VideoFrame.Type.DATA)
                .data(data)
                .presentationTimeUs(capturedAt * 1000)
                .flags(flags)
                .build()
                .asBytes();
    }

    // ---------- The transport and the link ----------

    /** Bytes one way and acknowledgements the other. */
    private static final class Channel {
        /** What kind of transport this is, and its limit. */
        private final Transport mTransport;
        /** The link underneath it. */
        private final Link mLink;
        /** The stream's nominal rate, which the link's capacity is a multiple of. */
        private final int mBytesPerMs;
        /** One-way delay across the link, in either direction. */
        private final int mOneWayMillis;
        /** Bytes accepted from the pump that the link has not carried yet. */
        private final ByteQueue mQueue = new ByteQueue();
        /** Bytes on the link, each batch with the millisecond it lands. */
        private final ArrayDeque<Object[]> mInFlight = new ArrayDeque<>();
        /** Acknowledgements on their way back, each with the millisecond it lands. */
        private final ArrayDeque<long[]> mAcks = new ArrayDeque<>();
        /** Fractional capacity carried from one millisecond to the next. */
        private double mCredit;
        /** Bytes accepted from the pump. */
        private long mAccepted;
        /** Bytes the receiver has said it read. */
        private long mAcknowledged;

        Channel(Transport transport, Link link, int bytesPerMs, int oneWayMillis) {
            mTransport = transport;
            mLink = link;
            mBytesPerMs = bytesPerMs;
            mOneWayMillis = oneWayMillis;
        }

        /** Bytes the transport will take now without queueing them past its limit. */
        int writable() {
            switch (mTransport.kind) {
                case BLOCKING:
                    return Math.max(0, mTransport.limit - mQueue.size());
                case ACKNOWLEDGED:
                    long window = (long) mTransport.limit * mBytesPerMs;
                    return (int) Math.max(0, window - (mAccepted - mAcknowledged));
                default:
                    return Integer.MAX_VALUE;
            }
        }

        /** Takes what it can and returns how much. Only a blocking transport ever takes less. */
        int offer(byte[] bytes, int offset, int length) {
            int accepted = mTransport.kind == TransportKind.BLOCKING
                    ? Math.min(length, writable()) : length;
            mQueue.write(bytes, offset, accepted);
            mAccepted += accepted;
            return accepted;
        }

        void step(long now, Receiver receiver) {
            double capacity = mLink.capacityAt(now) * mBytesPerMs;
            if (capacity <= 0) {
                mCredit = 0;
            } else {
                mCredit += capacity;
                int carried = Math.min((int) mCredit, mQueue.size());
                if (carried > 0) {
                    byte[] bytes = new byte[carried];
                    mQueue.read(bytes, 0, carried);
                    mInFlight.add(new Object[] {now + mOneWayMillis, bytes});
                    mCredit -= carried;
                }
                if (mQueue.size() == 0) {
                    // A link cannot save up capacity it had nothing to spend on.
                    mCredit = Math.min(mCredit, capacity);
                }
            }
            while (!mInFlight.isEmpty() && (long) mInFlight.peek()[0] <= now) {
                receiver.arrive(now, (byte[]) mInFlight.poll()[1]);
            }
        }

        /** The receiver has read this many bytes in all; the sender hears so a trip later. */
        void acknowledge(long now, long read) {
            mAcks.add(new long[] {mLink.nextUp(now + mOneWayMillis), read});
        }

        void receiveAcks(long now) {
            while (!mAcks.isEmpty() && mAcks.peek()[0] <= now) {
                mAcknowledged = mAcks.poll()[1];
            }
        }
    }

    // ---------- Receivers ----------

    /** The app on the far side, and what it does with the bytes as they land. */
    private abstract static class Receiver {
        /** Where the run's numbers go. */
        final Result mResult;
        /** Bytes that have landed and the app has not read. */
        final ByteQueue mInput = new ByteQueue();
        /** Everything that landed, kept apart for the integrity check. */
        final ByteQueue mArrived = new ByteQueue();
        /** Bytes the app has read, which is also the stream offset of the next one. */
        long mRead;

        Receiver(Result result) {
            mResult = result;
        }

        void arrive(long now, byte[] bytes) {
            mInput.write(bytes, 0, bytes.length);
            mArrived.write(bytes, 0, bytes.length);
            check(now);
        }

        /** Parses everything that has landed, as the real reader would. */
        abstract void check(long now);

        /** Reads, and for audio plays, one millisecond's worth. */
        abstract void step(long now, Channel channel);
    }

    /**
     * VideoPlayer: reads frames as they land and renders each as soon as it is decoded, so it never
     * builds a backlog of its own. A frame's latency is when it lands less when it was captured.
     */
    private static final class VideoReceiver extends Receiver {
        /** Whether the header has arrived. */
        private boolean mHeaderRead;
        /** Whether the codec configuration has arrived, without which nothing decodes. */
        private boolean mConfigured;
        /** Whether every frame since the last key frame arrived. */
        private boolean mChainIntact;
        /** The index of the last frame to arrive. */
        private int mLastIndex = -1;

        VideoReceiver(Result result) {
            super(result);
        }

        @Override
        void check(long now) {
            byte[] length = new byte[Integer.BYTES];
            while (mArrived.size() >= Integer.BYTES) {
                mArrived.peek(length);
                int frameLength = Ints.fromByteArray(length);
                if (mArrived.size() < Integer.BYTES + frameLength) {
                    return;
                }
                mArrived.skip(Integer.BYTES);
                byte[] bytes = new byte[frameLength];
                mArrived.read(bytes, 0, frameLength);
                VideoFrame frame = VideoFrame.fromBytes(bytes);

                if (!mHeaderRead) {
                    mHeaderRead = true;
                    mResult.headerIntact = frame.getType() == VideoFrame.Type.HEADER
                            && frame.getBitRate() == VIDEO_BIT_RATE;
                    continue;
                }
                byte[] data = frame.getData();
                if (frame.getType() != VideoFrame.Type.DATA || data == null || data.length < 4) {
                    mResult.corruptRecords++;
                    continue;
                }
                int index = Ints.fromByteArray(Arrays.copyOf(data, 4));
                if (!Arrays.equals(data, videoData(index, data.length))) {
                    mResult.corruptRecords++;
                }
                if ((frame.getFlags() & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    mConfigured = true;
                    continue;
                }

                // H.264 predicts each delta from the frame before it, so one missing frame breaks
                // every frame after it until the next key frame.
                boolean key = (frame.getFlags() & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                mChainIntact = key ? mConfigured : mChainIntact && index == mLastIndex + 1;
                if (index <= mLastIndex) {
                    mResult.corruptRecords++;
                }
                mLastIndex = index;
                if (mChainIntact) {
                    mResult.decodableFrames++;
                } else {
                    mResult.undecodableFrames++;
                }
                mResult.sample(now, now - frame.getPresentationTimeUs() / 1000);
            }
        }

        private static byte[] videoData(int index, int length) {
            byte[] data = new byte[length];
            System.arraycopy(Ints.toByteArray(index), 0, data, 0, 4);
            for (int i = 4; i < length; i++) {
                data[i] = (byte) (index * 7 + i);
            }
            return data;
        }

        @Override
        void step(long now, Channel channel) {
            int available = mInput.size();
            if (available > 0) {
                mInput.skip(available);
                mRead += available;
                channel.acknowledge(now, mRead);
            }
        }
    }

    /**
     * AudioPlayer: reads what has landed, then writes it to an AudioTrack, which plays it at the
     * rate it was recorded and blocks the writer while it is full.
     *
     * <p>That fixed rate is the difference from video. Audio that lands late is played late, and
     * nothing plays the rest of it any faster, so whatever delay builds up stays unless the
     * player discards some of it, which is what {@link AudioCatchUp} decides.</p>
     */
    private static final class AudioReceiver extends Receiver {
        /** What AudioPlayer reads at a time: its AudioTrack's minimum buffer. */
        private static final int READ_BYTES = AUDIO_TRACK_BYTES;

        /** Where the capture time of each byte is looked up. */
        private final AudioProducer mProducer;
        /** Decides what to discard before each read, or null to play everything. */
        @Nullable private final AudioCatchUp mCatchUp;
        /** Stream offsets of the bytes in the AudioTrack's buffer, as contiguous runs. */
        private final ArrayDeque<long[]> mTrack = new ArrayDeque<>();
        /** Bytes in the AudioTrack's buffer. */
        private int mTrackBytes;
        /** Stream offset of what the player has read and not yet written to the track. */
        private long mHeldOffset;
        /** Bytes the player has read and not yet written to the track. */
        private int mHeldBytes;
        /** Whether the player has read the header off the front of the stream. */
        private boolean mHeaderRead;
        /** Whether anything has played yet, before which silence is not a gap. */
        private boolean mStarted;
        /** Whether the integrity check has read the header. */
        private boolean mHeaderChecked;
        /** The sequence number of the last chunk to arrive. */
        private int mLastSequence = -1;

        AudioReceiver(Result result, AudioProducer producer, @Nullable AudioCatchUp catchUp) {
            super(result);
            mProducer = producer;
            mCatchUp = catchUp;
        }

        @Override
        void check(long now) {
            if (!mHeaderChecked) {
                if (mArrived.size() < AudioStreamHeader.LENGTH) {
                    return;
                }
                byte[] header = new byte[AudioStreamHeader.LENGTH];
                mArrived.read(header, 0, header.length);
                try {
                    AudioStreamHeader.Read read =
                            AudioStreamHeader.read(new ByteArrayInputStream(header));
                    mResult.headerIntact = read.header != null
                            && read.header.getSampleRate() == AUDIO_SAMPLE_RATE;
                } catch (IOException e) {
                    mResult.headerIntact = false;
                }
                mHeaderChecked = true;
            }
            byte[] chunk = new byte[AUDIO_CHUNK_BYTES];
            while (mArrived.size() >= AUDIO_CHUNK_BYTES) {
                mArrived.read(chunk, 0, chunk.length);
                int sequence = Ints.fromByteArray(Arrays.copyOf(chunk, 4));
                int capturedAt = Ints.fromByteArray(Arrays.copyOfRange(chunk, 4, 8));
                if (sequence <= mLastSequence
                        || !Arrays.equals(chunk, audioChunk(sequence, capturedAt))) {
                    mResult.corruptRecords++;
                }
                mLastSequence = sequence;
            }
        }

        @Override
        void step(long now, Channel channel) {
            // The track plays a millisecond.
            int played = Math.min(AUDIO_BYTES_PER_MS, mTrackBytes);
            if (played > 0) {
                mStarted = true;
                mResult.sample(now, now - mProducer.capturedAt(mTrack.peek()[0]));
                mTrackBytes -= played;
                for (int left = played; left > 0; ) {
                    long[] run = mTrack.peek();
                    int taken = (int) Math.min(left, run[1]);
                    run[0] += taken;
                    run[1] -= taken;
                    left -= taken;
                    if (run[1] == 0) {
                        mTrack.poll();
                    }
                }
            }
            if (mStarted) {
                mResult.underrunBytes += AUDIO_BYTES_PER_MS - played;
            }

            // The player thread fills it, blocking whenever it is full.
            while (true) {
                if (mHeldBytes > 0) {
                    int moved = Math.min(mHeldBytes, AUDIO_TRACK_BYTES - mTrackBytes);
                    if (moved == 0) {
                        return;
                    }
                    mTrack.add(new long[] {mHeldOffset, moved});
                    mHeldOffset += moved;
                    mHeldBytes -= moved;
                    mTrackBytes += moved;
                    if (mCatchUp != null) {
                        mCatchUp.onPlayed(moved);
                    }
                    continue;
                }
                if (!mHeaderRead) {
                    if (mInput.size() < AudioStreamHeader.LENGTH) {
                        return;
                    }
                    mInput.skip(AudioStreamHeader.LENGTH);
                    mRead += AudioStreamHeader.LENGTH;
                    mHeaderRead = true;
                    channel.acknowledge(now, mRead);
                    continue;
                }
                int available = mInput.size();
                int skip = mCatchUp == null ? 0 : mCatchUp.bytesToSkip(available);
                if (skip > 0) {
                    mInput.skip(skip);
                    mRead += skip;
                    mResult.skippedBytes += skip;
                    available -= skip;
                    channel.acknowledge(now, mRead);
                }
                if (available == 0) {
                    return;
                }
                int read = Math.min(available, READ_BYTES);
                mInput.skip(read);
                mHeldOffset = mRead;
                mHeldBytes = read;
                mRead += read;
                channel.acknowledge(now, mRead);
            }
        }
    }

    // ---------- Plumbing ----------

    /** A growable first-in first-out buffer of bytes. */
    static final class ByteQueue {
        /** The ring the bytes live in. */
        private byte[] mData = new byte[4096];
        /** Index of the first byte. */
        private int mHead;
        /** Bytes held. */
        private int mSize;

        int size() {
            return mSize;
        }

        void write(byte[] bytes, int offset, int length) {
            if (mSize + length > mData.length) {
                byte[] larger = new byte[Math.max(mSize + length, mData.length * 2)];
                int size = mSize;
                read(larger, 0, size);
                mData = larger;
                mHead = 0;
                mSize = size;
            }
            int tail = (mHead + mSize) % mData.length;
            int first = Math.min(length, mData.length - tail);
            System.arraycopy(bytes, offset, mData, tail, first);
            System.arraycopy(bytes, offset + first, mData, 0, length - first);
            mSize += length;
        }

        int read(byte[] into, int offset, int length) {
            int count = Math.min(length, mSize);
            int first = Math.min(count, mData.length - mHead);
            System.arraycopy(mData, mHead, into, offset, first);
            System.arraycopy(mData, 0, into, offset + first, count - first);
            mHead = (mHead + count) % mData.length;
            mSize -= count;
            return count;
        }

        void peek(byte[] into) {
            int count = Math.min(into.length, mSize);
            int first = Math.min(count, mData.length - mHead);
            System.arraycopy(mData, mHead, into, 0, first);
            System.arraycopy(mData, 0, into, first, count - first);
        }

        void skip(int count) {
            count = Math.min(count, mSize);
            mHead = (mHead + count) % mData.length;
            mSize -= count;
        }
    }
}
