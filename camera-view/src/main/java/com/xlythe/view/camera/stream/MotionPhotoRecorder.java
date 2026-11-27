package com.xlythe.view.camera.stream;

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

/**
 * Encodes the camera's frames continuously and keeps the last few seconds, so that a picture can
 * carry the moments before it was taken as well as the ones after.
 *
 * <p>A backend treats this as one more output surface, through the same
 * {@link VideoRecorder.SurfaceProvider} handshake a stream uses: it asks for a surface of the size
 * it chose, and frames start arriving. They go straight into a hardware encoder - HEVC where the
 * phone has one, H.264 where it does not - and the encoded frames into an
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
 * <p>The encoder only runs while a backend is feeding it: the owner releases this when motion
 * photos are turned off or the camera closes. A front camera's frames are mirrored on the way in
 * through a {@link MirroringSurface}, so the clip matches the still, which is mirrored like the
 * preview.</p>
 */
@RequiresApi(21)
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class MotionPhotoRecorder implements VideoRecorder.SurfaceProvider {
    private static final String TAG = CameraView.class.getSimpleName();

    /** How much video before the shutter a clip has, at least. */
    public static final long PRE_ROLL_US = 1_500_000L;
    /** How much video after the shutter a clip has. */
    public static final long POST_ROLL_US = 1_500_000L;

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
    /** A gap between frames longer than this is the camera having stopped, not one video. */
    @VisibleForTesting
    static final long MAX_GAP_US = 500_000L;
    /**
     * What the buffer may hold however the timestamps behave. Four seconds of 1080p at the highest
     * rate chosen here is ten megabytes.
     */
    @VisibleForTesting
    static final long MAX_BUFFER_BYTES = 24L * 1024 * 1024;
    /** How long a capture waits beyond its post-roll for frames that are late or not coming. */
    @VisibleForTesting
    static final long CAPTURE_SLACK_MS = 1_500L;
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

    /** The encoder frames are going into, or null before a backend has asked for a surface. */
    @GuardedBy("mLock")
    @Nullable
    private Encoder mEncoder;
    /** The encoder's output format, which carries the parameter sets the muxer needs. */
    @GuardedBy("mLock")
    @Nullable
    private MediaFormat mFormat;
    /** Captures waiting on their post-roll. */
    @GuardedBy("mLock")
    private final List<Capture> mPending = new ArrayList<>();
    /** Which clock the frames are stamped with, recognised from the newest frame. */
    @GuardedBy("mLock")
    @FrameClock
    private int mFrameClock = CLOCK_UNKNOWN;
    /** For an unrecognised clock, the newest frame's timestamp less the monotonic time it arrived. */
    @GuardedBy("mLock")
    private long mUnknownClockOffsetUs;
    /** When, by the monotonic clock, the newest frame arrived, or 0 before any has. */
    @GuardedBy("mLock")
    private long mLastFrameNanos;
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

    /** Whether this device has an encoder that can make motion photos at all. */
    @AnyThread
    public static boolean isSupported() {
        return chooseCodec(null) != null;
    }

    /**
     * The surface frames go into, encoded at the size given.
     *
     * <p>Called again whenever a backend rebuilds its session, which is often: the same surface
     * comes back if nothing changed, and an encoder for the new size replaces the old one if
     * something did. A backend calls this on the main thread, and it returns once the encoder is
     * running.</p>
     *
     * @param orientation unused: the clip's rotation is decided when the picture is taken
     * @param flipped whether the frames come from a front camera and need mirroring
     * @throws IllegalStateException if no encoder can be started, which the backend answers by
     *     leaving the output out
     */
    @Override
    public Surface getSurface(int width, int height, int orientation, boolean flipped) {
        Encoder retired = null;
        try {
            synchronized (mLock) {
                if (mReleased) {
                    throw new IllegalStateException("This motion photo recorder was released");
                }
                Encoder current = mEncoder;
                if (current != null && current.matches(width, height, flipped)) {
                    return current.getInputSurface();
                }
                if (current != null) {
                    // The frames from before belong to a stream a new encoder cannot continue.
                    completeAll();
                    mBuffer.clear();
                    mFormat = null;
                    mEncoder = null;
                    retired = current;
                }
                Encoder encoder = Encoder.start(this, width, height, flipped);
                mEncoder = encoder;
                return encoder.getInputSurface();
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
    @Nullable
    @Override
    public Size getMaxSize() {
        CodecChoice codec = chooseCodec(null);
        return codec != null && codec.hardware ? MAX_HARDWARE_SIZE : MAX_SOFTWARE_SIZE;
    }

    /**
     * Marks the shutter and starts the post-roll.
     *
     * @param rotationDegrees how a player turns the frames to show them as the still is shown
     * @return the capture, or null when no frames are arriving to make a clip from - before the
     *     camera has fed the encoder, or while it is feeding something else
     */
    @MainThread
    @Nullable
    public Capture capture(int rotationDegrees, @NonNull ClipListener listener) {
        long monotonicNanos = mClock.monotonicNanos();
        long bootNanos = mClock.bootNanos();
        synchronized (mLock) {
            if (mReleased || mFormat == null || mLastFrameNanos == 0
                    || monotonicNanos - mLastFrameNanos > STALE_NANOS) {
                return null;
            }
            long shutterUs;
            switch (mFrameClock) {
                case CLOCK_MONOTONIC:
                    shutterUs = monotonicNanos / 1000;
                    break;
                case CLOCK_BOOTTIME:
                    shutterUs = bootNanos / 1000;
                    break;
                case CLOCK_UNKNOWN:
                default:
                    shutterUs = monotonicNanos / 1000 + mUnknownClockOffsetUs;
                    break;
            }
            Capture capture = new Capture(shutterUs, rotationDegrees, listener);
            mBuffer.hold(capture.mStartUs);
            mPending.add(capture);
            mMainHandler.postDelayed(capture.mTimeout, POST_ROLL_US / 1000 + CAPTURE_SLACK_MS);
            return capture;
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
        if (!mBuffer.add(data, presentationTimeUs, flags) && encoder != null) {
            // The camera stopped and started again, or this is the first frame, and nothing can
            // be kept until the encoder sends a frame that decodes on its own.
            encoder.requestKeyFrame();
        }
        long newest = mBuffer.newestPresentationTimeUs();
        List<Capture> ready = new ArrayList<>();
        synchronized (mLock) {
            for (Capture capture : mPending) {
                if (newest >= capture.mEndUs) {
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
        List<EncodedVideoBuffer.Sample> samples = mBuffer.slice(capture.mStartUs, capture.mEndUs);
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
     */
    public static final class Clip {
        /** The encoder's output format, with the parameter sets the frames need. */
        private final MediaFormat mFormat;
        /** The frames, starting at a key frame. */
        private final List<EncodedVideoBuffer.Sample> mSamples;
        /** Where the shutter falls, in the frames' clock. */
        private final long mShutterUs;
        /** How a player turns the frames to show them as the still is shown. */
        private final int mRotationDegrees;

        Clip(@NonNull MediaFormat format, @NonNull List<EncodedVideoBuffer.Sample> samples,
                long shutterUs, int rotationDegrees) {
            mFormat = format;
            mSamples = Collections.unmodifiableList(new ArrayList<>(samples));
            mShutterUs = shutterUs;
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
            return mSamples.get(mSamples.size() - 1).presentationTimeUs - getStartTimeUs();
        }

        /** When the first frame was captured, in the camera's clock. */
        public long getStartTimeUs() {
            return mSamples.get(0).presentationTimeUs;
        }

        /** Where the shutter falls, in the camera's clock. */
        public long getShutterTimeUs() {
            return mShutterUs;
        }

        public int getFrameCount() {
            return mSamples.size();
        }

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
         * @param metadataMime the MIME type of a metadata track to add, or null for none
         * @param metadataSample that track's one sample
         */
        @WorkerThread
        public void writeTo(@NonNull File mp4, @Nullable String metadataMime,
                @Nullable byte[] metadataSample) throws IOException {
            MediaMuxer muxer = new MediaMuxer(mp4.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            try {
                muxer.setOrientationHint(mRotationDegrees);
                int video = muxer.addTrack(mFormat);
                int metadata = -1;
                // A metadata track is an application/ MIME type, which the muxer writes as a
                // text metadata sample entry from API 26.
                if (metadataMime != null && metadataSample != null && Build.VERSION.SDK_INT >= 26) {
                    MediaFormat format = new MediaFormat();
                    format.setString(MediaFormat.KEY_MIME, metadataMime);
                    metadata = muxer.addTrack(format);
                }
                muxer.start();
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                long start = getStartTimeUs();
                for (EncodedVideoBuffer.Sample sample : mSamples) {
                    info.set(0, sample.data.length, sample.presentationTimeUs - start,
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
    static CodecChoice chooseCodec(@Nullable Size size) {
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
                if ((!hevc && !avc) || !supportsSurfaceInput(codec, type, size)) {
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

    /** Whether a codec takes a surface and, if a size is given, encodes frames of that size. */
    private static boolean supportsSurfaceInput(@NonNull MediaCodecInfo codec,
            @NonNull String type, @Nullable Size size) {
        try {
            MediaCodecInfo.CodecCapabilities capabilities = codec.getCapabilitiesForType(type);
            boolean surface = false;
            for (int format : capabilities.colorFormats) {
                if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) {
                    surface = true;
                    break;
                }
            }
            if (!surface) {
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
        String name = codec.getName().toLowerCase(Locale.US);
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

    /** One encoder, its input surface and the thread draining it. */
    @VisibleForTesting
    static final class Encoder {
        /** Where frames and the output format are reported. */
        private final MotionPhotoRecorder mRecorder;
        /** The size it encodes. */
        private final int mWidth;
        /** The size it encodes. */
        private final int mHeight;
        /** Whether frames are mirrored on the way in. */
        private final boolean mMirrored;
        /** The codec. */
        private final MediaCodec mCodec;
        /** The codec's own input surface. */
        private final Surface mCodecSurface;
        /** Mirrors frames into {@link #mCodecSurface}, for a front camera. */
        @Nullable
        private final MirroringSurface mMirror;
        /** Drains encoded frames. */
        private final Thread mDrainThread;
        /** Whether the drain thread should keep going. */
        private volatile boolean mDraining = true;

        private Encoder(@NonNull MotionPhotoRecorder recorder, int width, int height,
                boolean mirrored, @NonNull MediaCodec codec, @NonNull Surface codecSurface,
                @Nullable MirroringSurface mirror) {
            mRecorder = recorder;
            mWidth = width;
            mHeight = height;
            mMirrored = mirrored;
            mCodec = codec;
            mCodecSurface = codecSurface;
            mMirror = mirror;
            mDrainThread = new Thread(this::drain, "MotionPhotoEncoder");
        }

        /** Creates, configures and starts an encoder, and starts draining it. */
        @NonNull
        static Encoder start(@NonNull MotionPhotoRecorder recorder, int width, int height,
                boolean mirrored) {
            CodecChoice choice = chooseCodec(new Size(width, height));
            if (choice == null) {
                throw new IllegalStateException("No encoder for " + width + "x" + height);
            }
            MediaFormat format = MediaFormat.createVideoFormat(choice.mime, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
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
            MirroringSurface mirror = null;
            try {
                codec = MediaCodec.createByCodecName(choice.name);
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                surface = codec.createInputSurface();
                codec.start();
                if (mirrored) {
                    mirror = new MirroringSurface(surface, width, height);
                }
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
                    + height + (mirrored ? ", mirrored" : ""));
            Encoder encoder = new Encoder(recorder, width, height, mirrored, codec, surface, mirror);
            encoder.mDrainThread.start();
            return encoder;
        }

        boolean matches(int width, int height, boolean mirrored) {
            return mDraining && mWidth == width && mHeight == height && mMirrored == mirrored;
        }

        @NonNull
        Surface getInputSurface() {
            return mMirror != null ? mMirror.getInputSurface() : mCodecSurface;
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

        /** Stops and frees the codec, its surfaces and the thread. Blocks briefly. */
        @WorkerThread
        void release() {
            mDraining = false;
            try {
                mDrainThread.join(500);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            if (mMirror != null) {
                mMirror.release();
            }
            try {
                mCodec.stop();
            } catch (IllegalStateException error) {
                Log.w(TAG, "Unable to stop the motion photo encoder", error);
            }
            mCodec.release();
            mCodecSurface.release();
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
}
