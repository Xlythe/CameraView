package com.xlythe.view.camera.stream;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.AnyThread;
import androidx.annotation.GuardedBy;
import androidx.annotation.IntDef;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import com.xlythe.view.camera.CameraView;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Encodes the camera's frames continuously and keeps the last few seconds, so that a picture can
 * carry the moments before it was taken as well as the ones after.
 *
 * <p>Frames reach it one of two ways. A backend that can point the camera at a surface asks
 * {@link #getInputSurface} for one, of the size it chose; one that only sees preview frames as
 * NV21 bytes hands each to {@link #queueFrame}. Either way they go into a hardware encoder - HEVC
 * where the phone has one, H.264 where it does not - and the encoded frames into an
 * {@link EncodedVideoBuffer} that holds a little more than {@link #PRE_ROLL_US} of them, starting
 * at a key frame. Encoding rather than holding raw frames is what makes this affordable: a second
 * and a half of 1080p is a few megabytes encoded and several hundred raw.</p>
 *
 * <p>{@link #capture} marks the shutter. The encoder keeps going for {@link #POST_ROLL_US} more,
 * then the frames from the key frame before the pre-roll through the end of the post-roll become a
 * {@link Clip}, which {@link Clip#writeTo} muxes into an MP4.</p>
 *
 * <p>The shutter is placed in the frames' own clock. Cameras stamp frames with either the
 * monotonic clock or the one that counts sleep, and which one is a property of the device, so the
 * clock is recognised from the frames themselves and the moment of the call read from it. That
 * makes "before the shutter" mean captured before the picture was asked for, not merely encoded
 * before it.</p>
 *
 * <p>Whoever feeds it says how the frames are turned to be shown, and has already mirrored a front
 * camera's frames the way the still is mirrored. The encoder only runs while a backend is feeding
 * it: the owner releases this when motion photos are turned off or the camera closes.</p>
 */
@RequiresApi(21)
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class MotionPhotoRecorder {
    private static final String TAG = CameraView.class.getSimpleName();

    /** How much video before the shutter a clip has, at least. */
    public static final long PRE_ROLL_US = 1_500_000L;
    /** How much video after the shutter a clip has. */
    public static final long POST_ROLL_US = 1_000_000L;

    /**
     * Seconds between key frames. A clip has to start on one, so this is also how much more than
     * {@link #PRE_ROLL_US} a clip can have: one second costs a little bit rate and keeps the
     * pre-roll between 1.5 and 2.5 seconds.
     */
    @VisibleForTesting
    static final int KEY_FRAME_INTERVAL_SECONDS = 1;
    /** The frame rate the encoder budgets for. The camera decides the real one. */
    @VisibleForTesting
    static final int FRAME_RATE = 30;
    /** How far apart frames are at that rate, which is what a stall is closed up to. */
    @VisibleForTesting
    static final long FRAME_INTERVAL_US = 1_000_000L / FRAME_RATE;
    /** A gap between frames from a surface longer than this is the camera having stopped. */
    @VisibleForTesting
    static final long MAX_GAP_US = 500_000L;
    /**
     * The same for frames handed over as bytes. The old camera API stops its preview while it
     * takes a picture, so a gap of a second or two is the shutter rather than a new video. A clip
     * waits that stall out, and is closed up across it: see {@link Clip}.
     */
    @VisibleForTesting
    static final long MAX_BYTE_FRAME_GAP_US = 3_000_000L;
    /**
     * What the buffer may hold however the timestamps behave. Four seconds of 1080p at the highest
     * rate chosen here is ten megabytes.
     */
    @VisibleForTesting
    static final long MAX_BUFFER_BYTES = 24L * 1024 * 1024;
    /** How long a capture waits beyond its post-roll for frames that are late or not coming. */
    @VisibleForTesting
    static final long CAPTURE_SLACK_MS = 2_500L;
    /** No frame for this long means the camera is not feeding the encoder, so nothing is live. */
    @VisibleForTesting
    static final long STALE_NANOS = 1_000_000_000L;
    /**
     * How far apart a frame's timestamp and a clock may be for the frame to be on that clock. The
     * two clocks Android cameras use differ by the time the phone has spent asleep, which is far
     * more than this on any phone that has been in a pocket, and a frame reaches the encoder well
     * inside it.
     */
    @VisibleForTesting
    static final long CLOCK_MATCH_US = 1_000_000L;
    /**
     * How long a replaced encoder outlives its replacement. The camera may still be finishing a
     * frame into the old surface while the session that used it closes.
     */
    private static final long RETIRE_DELAY_MS = 1_000L;
    /** How long the drain loop waits for an encoded frame before checking whether to stop. */
    private static final long DEQUEUE_TIMEOUT_US = 10_000L;
    /** How long a byte frame waits for the encoder to take it before it is dropped. */
    private static final long INPUT_TIMEOUT_US = 20_000L;

    /** The largest frame encoded on a hardware encoder: 1080p, which is what a phone records. */
    @VisibleForTesting
    static final Size MAX_HARDWARE_SIZE = new Size(1920, 1080);
    /** The largest frame encoded in software, where every pixel costs the CPU. */
    @VisibleForTesting
    static final Size MAX_SOFTWARE_SIZE = new Size(1280, 720);

    /** Which clock a camera stamps its frames with. */
    @IntDef({CLOCK_UNKNOWN, CLOCK_MONOTONIC, CLOCK_BOOTTIME})
    @Retention(RetentionPolicy.SOURCE)
    @interface FrameClock {}
    /** Neither: the shutter is placed relative to the newest frame instead. */
    static final int CLOCK_UNKNOWN = 0;
    /** {@code CLOCK_MONOTONIC}, which is {@link System#nanoTime}. */
    static final int CLOCK_MONOTONIC = 1;
    /** {@code CLOCK_BOOTTIME}, which is {@link SystemClock#elapsedRealtimeNanos}. */
    static final int CLOCK_BOOTTIME = 2;

    /** How frames reach an encoder. */
    @IntDef({INPUT_CAMERA, INPUT_RENDERED, INPUT_BYTES})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Input {}
    /** The camera writes into the encoder's surface itself. */
    public static final int INPUT_CAMERA = 0;
    /** A renderer draws into the encoder's surface. */
    public static final int INPUT_RENDERED = 1;
    /** NV21 frames are copied into the encoder's input buffers. */
    public static final int INPUT_BYTES = 2;

    /** Reads the two clocks, so a test can say what time it is. */
    @VisibleForTesting
    interface Clock {
        /** {@link System#nanoTime}. */
        long monotonicNanos();

        /** {@link SystemClock#elapsedRealtimeNanos}. */
        long bootNanos();
    }

    /** The clocks as the device keeps them. */
    private static final Clock SYSTEM_CLOCK = new Clock() {
        @Override
        public long monotonicNanos() {
            return System.nanoTime();
        }

        @Override
        public long bootNanos() {
            return SystemClock.elapsedRealtimeNanos();
        }
    };

    /** Tears down replaced encoders and does nothing else, so a slow release blocks no camera. */
    private static final ExecutorService RETIRER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MotionPhotoRecorder");
        thread.setDaemon(true);
        return thread;
    });

    /** Hears the clip a {@link Capture} produced. */
    public interface ClipListener {
        /**
         * Called on the main thread once the post-roll is in.
         *
         * @param clip the clip, or null when there were no frames to make one from
         */
        @MainThread
        void onClip(@Nullable Clip clip);
    }

    /** Reads the time, for the shutter and for staleness. */
    private final Clock mClock;
    /** Delivers clips and times captures out. */
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    /** The encoded frames. */
    private final EncodedVideoBuffer mBuffer =
            new EncodedVideoBuffer(PRE_ROLL_US, MAX_GAP_US, MAX_BUFFER_BYTES);
    /** Guards everything below. */
    private final Object mLock = new Object();

    /** The encoder frames are going into, or null before a backend has fed this. */
    @GuardedBy("mLock")
    @Nullable
    private Encoder mEncoder;
    /** The encoder's output format, which carries the parameter sets the muxer needs. */
    @GuardedBy("mLock")
    @Nullable
    private MediaFormat mFormat;
    /** How the current encoder's frames are turned clockwise to be shown. */
    @GuardedBy("mLock")
    private int mRotationDegrees;
    /** Captures waiting on their post-roll. */
    @GuardedBy("mLock")
    private final List<Capture> mPending = new ArrayList<>();
    /** Which clock the frames are stamped with, recognised from the newest frame. */
    @GuardedBy("mLock")
    @FrameClock
    private int mFrameClock = CLOCK_UNKNOWN;
    /** For an unknown clock, the newest frame's timestamp less the monotonic time it arrived. */
    @GuardedBy("mLock")
    private long mUnknownClockOffsetUs;
    /** When, by the monotonic clock, the newest frame arrived, or 0 before any has. */
    @GuardedBy("mLock")
    private long mLastFrameNanos;
    /** The last frame size no encoder would take, logged once rather than every frame. */
    private int mUnencodableWidth;
    /** The last frame size no encoder would take. */
    private int mUnencodableHeight;
    /** Set by {@link #release}, after which no encoder starts and no capture begins. */
    @GuardedBy("mLock")
    private boolean mReleased;

    public MotionPhotoRecorder() {
        this(SYSTEM_CLOCK);
    }

    @VisibleForTesting
    MotionPhotoRecorder(@NonNull Clock clock) {
        mClock = clock;
    }

    /**
     * Whether this device has an encoder a camera or a renderer can write into. The platform keeps
     * the codec list once it has read it, so asking again is cheap.
     */
    @AnyThread
    public static boolean isSupported() {
        return chooseCodec(null, INPUT_CAMERA) != null;
    }

    /** Whether this device has an encoder that takes frames copied in as bytes. */
    @AnyThread
    public static boolean isByteInputSupported() {
        return chooseCodec(null, INPUT_BYTES) != null;
    }

    /**
     * The surface frames go into, encoded at the size given.
     *
     * <p>Called again whenever a backend rebuilds its session, which is often: the same surface
     * comes back if nothing changed, and an encoder for the new size replaces the old one if
     * something did. So does one for a different producer, because a surface the camera has just
     * let go of may not yet take a renderer's frames.</p>
     *
     * @param rotationDegrees how the frames are turned clockwise to be shown as the still is
     * @param input who writes into the surface, {@link #INPUT_CAMERA} or {@link #INPUT_RENDERED}
     * @throws IllegalStateException if no encoder can be started, which the backend answers by
     *     leaving the output out
     */
    @MainThread
    @NonNull
    public Surface getInputSurface(int width, int height, int rotationDegrees, @Input int input) {
        Encoder encoder = obtainEncoder(width, height, rotationDegrees, input);
        Surface surface = encoder.getInputSurface();
        if (surface == null) {
            throw new IllegalStateException("The motion photo encoder takes no surface");
        }
        return surface;
    }

    /**
     * Encodes one preview frame, for a backend that only sees its frames as bytes. The frame is
     * copied on the encoder's own thread, and dropped if the one before it has not been taken
     * yet.
     *
     * @param nv21 the frame, read after this returns, so one the caller no longer writes into
     * @param rotationDegrees how the frame is turned clockwise to be shown as the still is
     * @param mirrored whether to mirror it as it is shown, as a front camera's still is
     */
    @AnyThread
    public void queueFrame(@NonNull byte[] nv21, int width, int height, int rotationDegrees,
            boolean mirrored) {
        int step = byteFrameStep(width, height, maxSizeFor(INPUT_BYTES));
        int encodedWidth = (width / step) & ~1;
        int encodedHeight = (height / step) & ~1;
        Encoder encoder;
        try {
            encoder = obtainEncoder(encodedWidth, encodedHeight, rotationDegrees, INPUT_BYTES);
        } catch (IllegalStateException error) {
            if (mUnencodableWidth != width || mUnencodableHeight != height) {
                mUnencodableWidth = width;
                mUnencodableHeight = height;
                Log.w(TAG, "Unable to encode motion photo frames of " + width + "x" + height,
                        error);
            }
            return;
        }
        encoder.queue(nv21, width, height, step, mClock.monotonicNanos() / 1000, mirrored,
                rotationDegrees);
    }

    /**
     * How many frame pixels each encoded pixel takes, each way: 1, or the smallest power of two
     * that brings a frame within the encoder's largest. A frame copied in by hand is shrunk by
     * skipping pixels, which is all a copy can afford on the CPU.
     */
    @VisibleForTesting
    static int byteFrameStep(int width, int height, @NonNull Size max) {
        int maxLong = Math.max(max.getWidth(), max.getHeight());
        int maxShort = Math.min(max.getWidth(), max.getHeight());
        int step = 1;
        while (Math.max(width, height) / step > maxLong
                || Math.min(width, height) / step > maxShort) {
            step *= 2;
        }
        return step;
    }

    @NonNull
    private Encoder obtainEncoder(int width, int height, int rotationDegrees, @Input int input) {
        Encoder retired = null;
        try {
            synchronized (mLock) {
                if (mReleased) {
                    throw new IllegalStateException("This motion photo recorder was released");
                }
                Encoder current = mEncoder;
                if (current != null && current.matches(width, height, input)) {
                    mRotationDegrees = rotationDegrees;
                    return current;
                }
                if (current != null) {
                    // The frames from before belong to a stream a new encoder cannot continue.
                    completeAll();
                    mBuffer.clear();
                    mFormat = null;
                    mEncoder = null;
                    retired = current;
                }
                Encoder encoder = Encoder.start(this, width, height, input);
                mBuffer.setMaxGapUs(input == INPUT_BYTES ? MAX_BYTE_FRAME_GAP_US : MAX_GAP_US);
                mEncoder = encoder;
                mRotationDegrees = rotationDegrees;
                return encoder;
            }
        } finally {
            if (retired != null) {
                retireLater(retired);
            }
        }
    }

    /**
     * The largest frame worth encoding. A backend picks the closest size it supports within this,
     * matching the preview's shape, so the clip shows what the still does.
     */
    @AnyThread
    @NonNull
    public Size getMaxSize() {
        return maxSizeFor(INPUT_CAMERA);
    }

    /** The largest frame worth encoding from an input, by whether its encoder is hardware. */
    @NonNull
    private static Size maxSizeFor(@Input int input) {
        CodecChoice codec = chooseCodec(null, input);
        return codec != null && codec.hardware ? MAX_HARDWARE_SIZE : MAX_SOFTWARE_SIZE;
    }

    /** Whether frames are arriving now, so that a picture taken now would have a clip. */
    @AnyThread
    public boolean isLive() {
        long monotonicNanos = mClock.monotonicNanos();
        synchronized (mLock) {
            return isLiveLocked(monotonicNanos);
        }
    }

    @GuardedBy("mLock")
    private boolean isLiveLocked(long monotonicNanos) {
        return !mReleased && mFormat != null && mLastFrameNanos != 0
                && monotonicNanos - mLastFrameNanos <= STALE_NANOS;
    }

    /**
     * Marks the shutter and starts the post-roll.
     *
     * @return the capture, or null when no frames are arriving to make a clip from - before the
     *     camera has fed the encoder, or while it is feeding something else
     */
    @AnyThread
    @Nullable
    public Capture capture(@NonNull ClipListener listener) {
        long monotonicNanos = mClock.monotonicNanos();
        long bootNanos = mClock.bootNanos();
        synchronized (mLock) {
            if (!isLiveLocked(monotonicNanos)) {
                return null;
            }
            long shutterUs = shutterUs(mFrameClock, monotonicNanos, bootNanos,
                    mUnknownClockOffsetUs);
            Capture capture = new Capture(shutterUs, mRotationDegrees, listener);
            mBuffer.hold(capture.mStartUs);
            mPending.add(capture);
            mMainHandler.postDelayed(capture.mTimeout, POST_ROLL_US / 1000 + CAPTURE_SLACK_MS);
            return capture;
        }
    }

    /** Where the shutter falls in the frames' clock, given the time on both clocks now. */
    @VisibleForTesting
    static long shutterUs(@FrameClock int frameClock, long monotonicNanos, long bootNanos,
            long unknownClockOffsetUs) {
        switch (frameClock) {
            case CLOCK_MONOTONIC:
                return monotonicNanos / 1000;
            case CLOCK_BOOTTIME:
                return bootNanos / 1000;
            case CLOCK_UNKNOWN:
            default:
                return monotonicNanos / 1000 + unknownClockOffsetUs;
        }
    }

    /**
     * Stops encoding. Captures still waiting are finished with the frames already in, so closing
     * the camera straight after the shutter costs the end of the clip rather than all of it.
     */
    @AnyThread
    public void release() {
        Encoder encoder;
        synchronized (mLock) {
            if (mReleased) {
                return;
            }
            mReleased = true;
            completeAll();
            mBuffer.clear();
            encoder = mEncoder;
            mEncoder = null;
            mFormat = null;
        }
        if (encoder != null) {
            retireLater(encoder);
        }
    }

    /** Lets a test feed frames as bytes would arrive, stalls and all, without an encoder. */
    @VisibleForTesting
    void setMaxGapUs(long maxGapUs) {
        mBuffer.setMaxGapUs(maxGapUs);
    }

    /** Called by the encoder when its output format is known, before its first frame. */
    @VisibleForTesting
    @AnyThread
    void onFormat(@NonNull MediaFormat format) {
        synchronized (mLock) {
            mFormat = format;
        }
    }

    /**
     * Called by the encoder for every frame it produces.
     *
     * @param encoder the encoder that produced it, to ask for a key frame when one is needed;
     *     null in a test
     */
    @VisibleForTesting
    @WorkerThread
    void onFrame(@Nullable Encoder encoder, @NonNull byte[] data, long presentationTimeUs,
            int flags) {
        long monotonicNanos = mClock.monotonicNanos();
        long bootNanos = mClock.bootNanos();
        synchronized (mLock) {
            if (encoder != null && encoder != mEncoder) {
                return;
            }
            mFrameClock = frameClockOf(presentationTimeUs, monotonicNanos / 1000, bootNanos / 1000);
            mUnknownClockOffsetUs = presentationTimeUs - monotonicNanos / 1000;
            mLastFrameNanos = monotonicNanos;
        }
        long previous = mBuffer.newestPresentationTimeUs();
        if (previous != Long.MIN_VALUE && presentationTimeUs - previous > MAX_GAP_US) {
            // A stall the buffer keeps as one video, as the old camera API's preview stops for
            // a picture. The post-roll is counted in frames that show something, so a capture
            // waiting through it waits that much longer.
            synchronized (mLock) {
                for (Capture capture : mPending) {
                    capture.mStallUs += presentationTimeUs - previous - FRAME_INTERVAL_US;
                }
            }
        }
        if (!mBuffer.add(data, presentationTimeUs, flags) && encoder != null) {
            // The camera stopped and started again, or this is the first frame, and nothing can
            // be kept until the encoder sends a frame that decodes on its own.
            encoder.requestKeyFrame();
        }
        long newest = mBuffer.newestPresentationTimeUs();
        List<Capture> ready = new ArrayList<>();
        synchronized (mLock) {
            for (Capture capture : mPending) {
                if (newest >= capture.mEndUs + capture.mStallUs) {
                    ready.add(capture);
                }
            }
        }
        for (Capture capture : ready) {
            complete(capture, true);
        }
    }

    /**
     * Which clock a frame's timestamp is on, judged by which one it is close to when it arrives.
     */
    @VisibleForTesting
    @FrameClock
    static int frameClockOf(long presentationTimeUs, long monotonicUs, long bootUs) {
        long fromMonotonic = Math.abs(presentationTimeUs - monotonicUs);
        long fromBoot = Math.abs(presentationTimeUs - bootUs);
        if (fromMonotonic <= CLOCK_MATCH_US && fromMonotonic <= fromBoot) {
            return CLOCK_MONOTONIC;
        }
        if (fromBoot <= CLOCK_MATCH_US) {
            return CLOCK_BOOTTIME;
        }
        return CLOCK_UNKNOWN;
    }

    /** Finishes every capture in progress with what is in the buffer now. */
    @GuardedBy("mLock")
    private void completeAll() {
        for (Capture capture : new ArrayList<>(mPending)) {
            completeLocked(capture, true);
        }
    }

    private void complete(@NonNull Capture capture, boolean deliver) {
        synchronized (mLock) {
            completeLocked(capture, deliver);
        }
    }

    @GuardedBy("mLock")
    private void completeLocked(@NonNull Capture capture, boolean deliver) {
        if (!mPending.remove(capture)) {
            return;
        }
        mMainHandler.removeCallbacks(capture.mTimeout);
        List<EncodedVideoBuffer.Sample> samples =
                mBuffer.slice(capture.mStartUs, capture.mEndUs + capture.mStallUs);
        mBuffer.release(capture.mStartUs);
        if (!deliver) {
            return;
        }
        Clip clip = samples.isEmpty() || mFormat == null
                ? null
                : new Clip(mFormat, samples, capture.mShutterUs, capture.mRotationDegrees);
        if (clip != null) {
            Log.d(TAG, String.format(Locale.US, "Motion photo clip: %d frames over %d ms, "
                            + "first frame %d us before the shutter at %d us (clock %d)",
                    clip.getFrameCount(), clip.getDurationUs() / 1000,
                    capture.mShutterUs - clip.getStartTimeUs(), capture.mShutterUs, mFrameClock));
        } else {
            Log.w(TAG, "No frames to make a motion photo clip from");
        }
        ClipListener listener = capture.mListener;
        mMainHandler.post(() -> listener.onClip(clip));
    }

    private static void retireLater(@NonNull Encoder encoder) {
        encoder.stopDraining();
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> RETIRER.execute(encoder::release), RETIRE_DELAY_MS);
    }

    /** A picture waiting for the rest of its clip. */
    public final class Capture {
        /** Where the shutter falls, in the frames' clock. */
        private final long mShutterUs;
        /** The earliest frame the clip wants; it starts at the key frame at or before this. */
        private final long mStartUs;
        /** The last frame the clip wants. */
        private final long mEndUs;
        /** How the clip is turned to match the still. */
        private final int mRotationDegrees;
        /** Hears the clip. */
        private final ClipListener mListener;
        /** How long the frames have stalled since the capture began, which the post-roll skips. */
        @GuardedBy("mLock")
        private long mStallUs;
        /** Finishes the capture when its post-roll never fully arrives. */
        private final Runnable mTimeout;

        Capture(long shutterUs, int rotationDegrees, @NonNull ClipListener listener) {
            mShutterUs = shutterUs;
            mStartUs = shutterUs - PRE_ROLL_US;
            mEndUs = shutterUs + POST_ROLL_US;
            mRotationDegrees = rotationDegrees;
            mListener = listener;
            mTimeout = () -> complete(this, true);
        }

        /** Abandons the clip, as when the picture it was for failed. The listener is not called. */
        @AnyThread
        public void cancel() {
            complete(this, false);
        }
    }

    /**
     * The frames cut for one picture, with what the motion photo needs to know about them.
     *
     * <p>A stall in the frames - the old camera API's preview stopping while it takes the picture
     * - is closed up to a single frame's interval, so the clip plays straight through the shutter
     * rather than freezing on its last frame before it. The times here are the closed-up ones.</p>
     */
    public static final class Clip {
        /** The encoder's output format, with the parameter sets the frames need. */
        private final MediaFormat mFormat;
        /** The frames, starting at a key frame. */
        private final List<EncodedVideoBuffer.Sample> mSamples;
        /** When each frame is shown, in the frames' clock with any stall closed up. */
        private final long[] mTimesUs;
        /** Where the shutter falls, in the frames' clock with any stall before it closed up. */
        private final long mShutterUs;
        /** How a player turns the frames to show them as the still is shown. */
        private final int mRotationDegrees;

        Clip(@NonNull MediaFormat format, @NonNull List<EncodedVideoBuffer.Sample> samples,
                long shutterUs, int rotationDegrees) {
            mFormat = format;
            mSamples = Collections.unmodifiableList(new ArrayList<>(samples));
            mTimesUs = new long[samples.size()];
            long closedUp = 0;
            long shutterClosedUp = 0;
            for (int i = 0; i < mTimesUs.length; i++) {
                long time = samples.get(i).presentationTimeUs;
                if (i > 0) {
                    long previous = samples.get(i - 1).presentationTimeUs;
                    if (time - previous > MAX_GAP_US) {
                        closedUp += time - previous - FRAME_INTERVAL_US;
                    }
                }
                if (time <= shutterUs) {
                    shutterClosedUp = closedUp;
                }
                mTimesUs[i] = time - closedUp;
            }
            mShutterUs = shutterUs - shutterClosedUp;
            mRotationDegrees = rotationDegrees;
        }

        /**
         * The frame the still corresponds to, in microseconds from the start of the clip. This is
         * the motion photo's presentation timestamp.
         */
        public long getPresentationTimestampUs() {
            return Math.max(0, Math.min(mShutterUs - getStartTimeUs(), getDurationUs()));
        }

        /** From the first frame to the last. */
        public long getDurationUs() {
            return mTimesUs[mTimesUs.length - 1] - getStartTimeUs();
        }

        /** When the first frame was captured, in the camera's clock. */
        public long getStartTimeUs() {
            return mTimesUs[0];
        }

        /** Where the shutter falls, in the camera's clock. */
        public long getShutterTimeUs() {
            return mShutterUs;
        }

        /** How many frames the clip has. */
        public int getFrameCount() {
            return mSamples.size();
        }

        /** How a player turns the frames clockwise to show them as the still is shown. */
        public int getRotationDegrees() {
            return mRotationDegrees;
        }

        /** The encoder's MIME type, which says whether the clip is HEVC or H.264. */
        @Nullable
        public String getMimeType() {
            return mFormat.getString(MediaFormat.KEY_MIME);
        }

        /**
         * Muxes the clip into an MP4, its timestamps counted from its first frame.
         *
         * @param metadataMime the MIME type of a metadata track to add, or null for none. A muxer
         *     that will not take one writes the video alone.
         * @param metadataSample that track's one sample
         */
        @WorkerThread
        public void writeTo(@NonNull File mp4, @Nullable String metadataMime,
                @Nullable byte[] metadataSample) throws IOException {
            MediaMuxer muxer = new MediaMuxer(mp4.getPath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            try {
                muxer.setOrientationHint(mRotationDegrees);
                int video = muxer.addTrack(mFormat);
                int metadata = -1;
                // A metadata track is an application/ MIME type, which the muxer writes as a
                // text metadata sample entry from API 26.
                if (metadataMime != null && metadataSample != null && Build.VERSION.SDK_INT >= 26) {
                    MediaFormat format = new MediaFormat();
                    format.setString(MediaFormat.KEY_MIME, metadataMime);
                    try {
                        metadata = muxer.addTrack(format);
                    } catch (IllegalArgumentException | IllegalStateException refused) {
                        Log.w(TAG, "Writing the motion photo's clip without its metadata track",
                                refused);
                    }
                }
                muxer.start();
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                long start = getStartTimeUs();
                for (int i = 0; i < mSamples.size(); i++) {
                    EncodedVideoBuffer.Sample sample = mSamples.get(i);
                    info.set(0, sample.data.length, mTimesUs[i] - start,
                            sample.isKeyFrame() ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                    muxer.writeSampleData(video, ByteBuffer.wrap(sample.data), info);
                }
                if (metadata >= 0) {
                    info.set(0, metadataSample.length, 0, 0);
                    muxer.writeSampleData(metadata, ByteBuffer.wrap(metadataSample), info);
                }
                muxer.stop();
            } catch (IllegalStateException | IllegalArgumentException error) {
                throw new IOException("Could not write the motion photo's video", error);
            } finally {
                try {
                    muxer.release();
                } catch (IllegalStateException error) {
                    Log.w(TAG, "Could not release the muxer", error);
                }
            }
        }
    }

    /** Which encoder to use, and what it encodes. */
    @VisibleForTesting
    static final class CodecChoice {
        /** The codec's name, for MediaCodec.createByCodecName. */
        final String name;
        /** What it encodes: HEVC or H.264. */
        final String mime;
        /** Whether it runs on dedicated hardware rather than the CPU. */
        final boolean hardware;

        CodecChoice(@NonNull String name, @NonNull String mime, boolean hardware) {
            this.name = name;
            this.mime = mime;
            this.hardware = hardware;
        }
    }

    /**
     * The encoder to use for frames of a size, or for any size when given none.
     *
     * <p>Hardware first, because the encoder runs the whole time the camera is open and a software
     * one would run the CPU just as long. HEVC before H.264 on hardware, because it is around a
     * third smaller at the same quality and every gallery that reads motion photos plays it. In
     * software it is the other way round: a software HEVC encoder rarely keeps up.</p>
     */
    @VisibleForTesting
    @Nullable
    static CodecChoice chooseCodec(@Nullable Size size, @Input int input) {
        CodecChoice hardwareAvc = null;
        CodecChoice softwareAvc = null;
        CodecChoice softwareHevc = null;
        MediaCodecInfo[] codecs;
        try {
            codecs = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to list the codecs", error);
            return null;
        }
        for (MediaCodecInfo codec : codecs) {
            if (!codec.isEncoder()) {
                continue;
            }
            for (String type : codec.getSupportedTypes()) {
                boolean hevc = MediaFormat.MIMETYPE_VIDEO_HEVC.equalsIgnoreCase(type);
                boolean avc = MediaFormat.MIMETYPE_VIDEO_AVC.equalsIgnoreCase(type);
                if ((!hevc && !avc) || !supportsInput(codec, type, size, colorFormatFor(input))) {
                    continue;
                }
                boolean hardware = isHardware(codec);
                CodecChoice choice = new CodecChoice(codec.getName(),
                        hevc ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC,
                        hardware);
                if (hevc && hardware) {
                    return choice;
                } else if (avc && hardware && hardwareAvc == null) {
                    hardwareAvc = choice;
                } else if (avc && !hardware && softwareAvc == null) {
                    softwareAvc = choice;
                } else if (hevc && !hardware && softwareHevc == null) {
                    softwareHevc = choice;
                }
            }
        }
        if (hardwareAvc != null) {
            return hardwareAvc;
        }
        return softwareAvc != null ? softwareAvc : softwareHevc;
    }

    /** The colour format an encoder is configured with for an input. */
    private static int colorFormatFor(@Input int input) {
        return input == INPUT_BYTES
                ? MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                : MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface;
    }

    /** Whether a codec takes a colour format and, if a size is given, frames of that size. */
    private static boolean supportsInput(@NonNull MediaCodecInfo codec,
            @NonNull String type, @Nullable Size size, int colorFormat) {
        try {
            MediaCodecInfo.CodecCapabilities capabilities = codec.getCapabilitiesForType(type);
            boolean takesFormat = false;
            for (int format : capabilities.colorFormats) {
                if (format == colorFormat) {
                    takesFormat = true;
                    break;
                }
            }
            if (!takesFormat) {
                return false;
            }
            MediaCodecInfo.VideoCapabilities video = capabilities.getVideoCapabilities();
            return size == null || video == null
                    || video.isSizeSupported(size.getWidth(), size.getHeight());
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to read the capabilities of " + codec.getName(), error);
            return false;
        }
    }

    /**
     * Whether a codec is hardware. API 29 says; before it, the platform's own software codecs are
     * recognisable by name.
     */
    @VisibleForTesting
    static boolean isHardware(@NonNull MediaCodecInfo codec) {
        if (Build.VERSION.SDK_INT >= 29) {
            return codec.isHardwareAccelerated();
        }
        return isHardwareName(codec.getName());
    }

    /** Whether a codec's name is not one of the platform's software codecs. */
    @VisibleForTesting
    static boolean isHardwareName(@NonNull String codecName) {
        String name = codecName.toLowerCase(Locale.US);
        return !name.startsWith("omx.google.") && !name.startsWith("c2.android.")
                && !name.startsWith("c2.google.") && !name.contains(".sw.");
    }

    /**
     * The bit rate for a clip: about a tenth of a bit per pixel per frame for H.264, which looks
     * like a phone's own recording at 1080p, and less for HEVC, which needs less for the same
     * picture.
     */
    @VisibleForTesting
    static int bitRateFor(@NonNull String mime, int width, int height) {
        double bitsPerPixel = MediaFormat.MIMETYPE_VIDEO_HEVC.equals(mime) ? 0.07 : 0.1;
        long rate = Math.round((double) width * height * FRAME_RATE * bitsPerPixel);
        return (int) Math.max(1_000_000L, Math.min(rate, 20_000_000L));
    }

    /**
     * Copies an NV21 frame into the three planes of an encoder's input image, shrunk and mirrored
     * as asked.
     *
     * <p>The planes are described by their strides rather than read off an {@link Image}, so the
     * copy can be checked without a codec. NV21 is a full-size luma plane followed by one plane of
     * interleaved V and U at half size each way.</p>
     *
     * @param width the frame's width
     * @param height the frame's height
     * @param step how many frame pixels each copied pixel stands for, each way
     * @param outWidth the width copied, at most {@code width / step}
     * @param outHeight the height copied, at most {@code height / step}
     * @param flipRows whether the last row is written first
     * @param flipColumns whether each row is written back to front
     */
    @VisibleForTesting
    static void copyNv21(@NonNull byte[] nv21, int width, int height, int step, int outWidth,
            int outHeight, boolean flipRows, boolean flipColumns, @NonNull Plane y,
            @NonNull Plane u, @NonNull Plane v) {
        byte[] row = new byte[outWidth];
        for (int r = 0; r < outHeight; r++) {
            int source = (flipRows ? outHeight - 1 - r : r) * step * width;
            if (step == 1 && !flipColumns) {
                System.arraycopy(nv21, source, row, 0, outWidth);
            } else {
                for (int c = 0; c < outWidth; c++) {
                    row[c] = nv21[source + (flipColumns ? outWidth - 1 - c : c) * step];
                }
            }
            y.putRow(r, row, outWidth);
        }
        int chromaWidth = outWidth / 2;
        int chromaHeight = outHeight / 2;
        byte[] uRow = new byte[chromaWidth];
        byte[] vRow = new byte[chromaWidth];
        int chroma = width * height;
        for (int r = 0; r < chromaHeight; r++) {
            int source = chroma + (flipRows ? chromaHeight - 1 - r : r) * step * width;
            for (int c = 0; c < chromaWidth; c++) {
                int at = source + 2 * (flipColumns ? chromaWidth - 1 - c : c) * step;
                vRow[c] = nv21[at];
                uRow[c] = nv21[at + 1];
            }
            u.putRow(r, uRow, chromaWidth);
            v.putRow(r, vRow, chromaWidth);
        }
    }

    /** One plane of an image being written, with its strides. */
    @VisibleForTesting
    static final class Plane {
        /** Where the plane's bytes go. */
        private final ByteBuffer mBuffer;
        /** Bytes from the start of one row to the next. */
        private final int mRowStride;
        /** Bytes from one sample to the next within a row. */
        private final int mPixelStride;

        Plane(@NonNull ByteBuffer buffer, int rowStride, int pixelStride) {
            mBuffer = buffer;
            mRowStride = rowStride;
            mPixelStride = pixelStride;
        }

        /** Writes one row of samples. */
        void putRow(int row, @NonNull byte[] samples, int count) {
            int start = row * mRowStride;
            if (mPixelStride == 1) {
                ByteBuffer target = mBuffer.duplicate();
                target.position(start);
                target.put(samples, 0, count);
                return;
            }
            for (int i = 0; i < count; i++) {
                mBuffer.put(start + i * mPixelStride, samples[i]);
            }
        }
    }

    /**
     * Whether mirroring a frame as it is shown flips its rows rather than its columns. A frame
     * shown a quarter turn round has its rows shown as columns.
     */
    @VisibleForTesting
    static boolean mirrorFlipsRows(int rotationDegrees) {
        return ((rotationDegrees % 360 + 360) % 360 / 90) % 2 != 0;
    }

    /** One encoder, its input and the thread draining it. */
    @VisibleForTesting
    static final class Encoder {
        /** Where frames and the output format are reported. */
        private final MotionPhotoRecorder mRecorder;
        /** The size it encodes. */
        private final int mWidth;
        /** The size it encodes. */
        private final int mHeight;
        /** How frames reach it. */
        @Input private final int mInput;
        /** The codec. */
        private final MediaCodec mCodec;
        /** The codec's own input surface, or null for one fed bytes. */
        @Nullable
        private final Surface mCodecSurface;
        /** Drains encoded frames. */
        private final Thread mDrainThread;
        /** Copies byte frames in, one at a time, for an encoder fed bytes. */
        @Nullable
        private final ExecutorService mFeeder;
        /** The byte frame waiting for {@link #mFeeder}, replaced by a newer one if it waits. */
        private final AtomicReference<ByteFrame> mWaiting = new AtomicReference<>();
        /** Whether the drain thread should keep going. */
        private volatile boolean mDraining = true;

        private Encoder(@NonNull MotionPhotoRecorder recorder, int width, int height,
                @Input int input, @NonNull MediaCodec codec, @Nullable Surface codecSurface) {
            mRecorder = recorder;
            mWidth = width;
            mHeight = height;
            mInput = input;
            mCodec = codec;
            mCodecSurface = codecSurface;
            mDrainThread = new Thread(this::drain, "MotionPhotoEncoder");
            mFeeder = input == INPUT_BYTES ? Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "MotionPhotoFeeder");
                thread.setDaemon(true);
                return thread;
            }) : null;
        }

        /** Creates, configures and starts an encoder, and starts draining it. */
        @NonNull
        static Encoder start(@NonNull MotionPhotoRecorder recorder, int width, int height,
                @Input int input) {
            CodecChoice choice = chooseCodec(new Size(width, height), input);
            if (choice == null) {
                throw new IllegalStateException("No encoder for " + width + "x" + height);
            }
            MediaFormat format = MediaFormat.createVideoFormat(choice.mime, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormatFor(input));
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRateFor(choice.mime, width, height));
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_INTERVAL_SECONDS);
            if (Build.VERSION.SDK_INT >= 29) {
                // B-frames would put frames out of presentation order, and a clip is cut by
                // presentation time.
                format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
                // A 60fps preview would otherwise be encoded at 60fps, twice the work for a clip
                // nobody watches frame by frame.
                format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, FRAME_RATE);
            }

            MediaCodec codec = null;
            Surface surface = null;
            try {
                codec = MediaCodec.createByCodecName(choice.name);
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                if (input != INPUT_BYTES) {
                    surface = codec.createInputSurface();
                }
                codec.start();
            } catch (IOException | RuntimeException error) {
                if (codec != null) {
                    codec.release();
                }
                if (surface != null) {
                    surface.release();
                }
                throw new IllegalStateException("Could not start " + choice.name, error);
            }
            Log.d(TAG, "Encoding motion photo frames with " + choice.name + " at " + width + "x"
                    + height + " from input " + input);
            Encoder encoder = new Encoder(recorder, width, height, input, codec, surface);
            encoder.mDrainThread.start();
            return encoder;
        }

        boolean matches(int width, int height, @Input int input) {
            return mDraining && mWidth == width && mHeight == height && mInput == input;
        }

        @Nullable
        Surface getInputSurface() {
            return mCodecSurface;
        }

        /** Hands a byte frame to the feeder, replacing one still waiting. */
        void queue(@NonNull byte[] nv21, int width, int height, int step,
                long presentationTimeUs, boolean mirrored, int rotationDegrees) {
            ExecutorService feeder = mFeeder;
            if (feeder == null || !mDraining || nv21.length < width * height * 3 / 2
                    || width / step < mWidth || height / step < mHeight) {
                return;
            }
            boolean flipRows = mirrored && mirrorFlipsRows(rotationDegrees);
            boolean flipColumns = mirrored && !flipRows;
            ByteFrame frame = new ByteFrame(nv21, width, height, step, presentationTimeUs,
                    flipRows, flipColumns);
            if (mWaiting.getAndSet(frame) == null) {
                try {
                    feeder.execute(this::feed);
                } catch (RuntimeException shutDown) {
                    mWaiting.set(null);
                }
            }
        }

        /** Copies the waiting byte frame into the codec, or drops it if the codec has no room. */
        @WorkerThread
        private void feed() {
            ByteFrame frame = mWaiting.getAndSet(null);
            if (frame == null || !mDraining) {
                return;
            }
            try {
                int index = mCodec.dequeueInputBuffer(INPUT_TIMEOUT_US);
                if (index < 0) {
                    return;
                }
                Image image = mCodec.getInputImage(index);
                if (image == null) {
                    mCodec.queueInputBuffer(index, 0, 0, frame.presentationTimeUs, 0);
                    return;
                }
                Image.Plane[] planes = image.getPlanes();
                copyNv21(frame.nv21, frame.width, frame.height, frame.step, mWidth, mHeight,
                        frame.flipRows, frame.flipColumns,
                        new Plane(planes[0].getBuffer(), planes[0].getRowStride(),
                                planes[0].getPixelStride()),
                        new Plane(planes[1].getBuffer(), planes[1].getRowStride(),
                                planes[1].getPixelStride()),
                        new Plane(planes[2].getBuffer(), planes[2].getRowStride(),
                                planes[2].getPixelStride()));
                mCodec.queueInputBuffer(index, 0, mWidth * mHeight * 3 / 2,
                        frame.presentationTimeUs, 0);
            } catch (IllegalStateException | IndexOutOfBoundsException error) {
                Log.w(TAG, "Dropped a motion photo frame", error);
            }
        }

        /** Asks the codec for a key frame now rather than at the next interval. */
        void requestKeyFrame() {
            Bundle parameters = new Bundle();
            parameters.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            try {
                mCodec.setParameters(parameters);
            } catch (IllegalStateException error) {
                Log.w(TAG, "Unable to request a key frame", error);
            }
        }

        void stopDraining() {
            mDraining = false;
        }

        /** Stops and frees the codec, its surface and the threads. Blocks briefly. */
        @WorkerThread
        void release() {
            mDraining = false;
            if (mFeeder != null) {
                mFeeder.shutdownNow();
            }
            try {
                mDrainThread.join(500);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            try {
                mCodec.stop();
            } catch (IllegalStateException error) {
                Log.w(TAG, "Unable to stop the motion photo encoder", error);
            }
            mCodec.release();
            if (mCodecSurface != null) {
                mCodecSurface.release();
            }
        }

        @WorkerThread
        private void drain() {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (mDraining) {
                int index;
                try {
                    index = mCodec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
                } catch (IllegalStateException error) {
                    Log.w(TAG, "The motion photo encoder stopped", error);
                    return;
                }
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    mRecorder.onFormat(mCodec.getOutputFormat());
                    continue;
                }
                if (index < 0) {
                    continue;
                }
                try {
                    ByteBuffer buffer = mCodec.getOutputBuffer(index);
                    if (buffer != null && info.size > 0
                            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        byte[] data = new byte[info.size];
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        buffer.get(data);
                        mRecorder.onFrame(this, data, info.presentationTimeUs, info.flags);
                    }
                    mCodec.releaseOutputBuffer(index, false);
                } catch (IllegalStateException error) {
                    Log.w(TAG, "The motion photo encoder stopped", error);
                    return;
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    return;
                }
            }
        }
    }

    /** A preview frame waiting to be copied into an encoder. */
    private static final class ByteFrame {
        /** The frame, in NV21. */
        final byte[] nv21;
        /** The frame's width. */
        final int width;
        /** The frame's height. */
        final int height;
        /** How many frame pixels each encoded pixel takes, each way. */
        final int step;
        /** When it arrived, in microseconds of the monotonic clock. */
        final long presentationTimeUs;
        /** Whether it is copied bottom row first. */
        final boolean flipRows;
        /** Whether each row is copied back to front. */
        final boolean flipColumns;

        ByteFrame(@NonNull byte[] nv21, int width, int height, int step, long presentationTimeUs,
                boolean flipRows, boolean flipColumns) {
            this.nv21 = nv21;
            this.width = width;
            this.height = height;
            this.step = step;
            this.presentationTimeUs = presentationTimeUs;
            this.flipRows = flipRows;
            this.flipColumns = flipColumns;
        }
    }
}
