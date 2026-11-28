package com.xlythe.view.camera;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.xlythe.view.camera.ICameraModule.TAG;

/**
 * The sound of a burst, as Samsung's camera plays one: a single click looped while the burst
 * runs, one frame long at the burst's rate, and a softer two-note tone once it ends. No picture
 * clicks on its own.
 *
 * <p>The loop starts as the first picture is exposed. It stops the moment the burst is stopped
 * short, or once its last picture is exposed, and the end tone plays. A burst stopped before its
 * first picture was exposed, by a quick release, plays the click once instead, and no end tone,
 * as does a burst of one picture.
 * Where the platform clicks for every picture itself, as the old camera API does where the click
 * cannot be silenced, its clicks stand in for the loop and only the end tone is added.</p>
 *
 * <p>Both sounds are synthesized here, so the library ships no audio files.</p>
 */
@MainThread
final class BurstSound {
    /** The sample rate both sounds are made at. */
    @VisibleForTesting
    static final int SAMPLE_RATE = 44_100;

    /**
     * The frame lengths a loop's click is cut to, in milliseconds: 20, 10, 5, 4 and 3 pictures a
     * second, the rates Samsung's camera has clips for.
     */
    @VisibleForTesting
    static final int[] FRAME_PERIODS_MILLIS = {50, 100, 200, 250, 320};

    /** The frame length when the burst's rate is unknown: 10 pictures a second. */
    @VisibleForTesting
    static final int DEFAULT_FRAME_PERIOD_MILLIS = 100;

    /** How long the end tone lasts. */
    @VisibleForTesting
    static final int END_TONE_MILLIS = 170;

    /** Plays the sounds. */
    interface Player {
        /** Plays the clip over and over until {@link #stopLoop}. */
        void startLoop(@NonNull short[] clip);

        /** Plays the clip once. */
        void playOnce(@NonNull short[] clip);

        /** Stops the loop at once. */
        void stopLoop();

        /** Plays the end tone once. */
        void playEnd(@NonNull short[] tone);
    }

    /** Makes a player. */
    interface PlayerFactory {
        /** @param required whether the sound must be heard whatever the device is set to */
        @NonNull
        Player create(boolean required);
    }

    /** Says whether the platform clicks for every picture of the burst itself. */
    interface PlatformClicks {
        boolean clicksEachPicture();
    }

    /** Hears the rate the burst ran at, for the next burst's loop. */
    interface RateListener {
        void onRateMeasured(float picturesPerSecond);
    }

    /** Makes every burst's player: an AudioTrack, or a test's stand-in. */
    @NonNull
    private static PlayerFactory sPlayerFactory = TrackPlayer::new;

    /** The burst this is the sound of. */
    @NonNull
    private final Burst mBurst;

    /** Plays the loop and the end tone. */
    @NonNull
    private final Player mPlayer;

    /** Asked at the first picture, once the backend knows whether its platform clicks. */
    @NonNull
    private final PlatformClicks mPlatform;

    /** The loop's click, one frame long. */
    @NonNull
    private final short[] mClip;

    /** Hears how fast the burst ran. */
    @NonNull
    private final RateListener mRateListener;

    /** How many of the burst's pictures have been exposed. */
    private int mExposed;

    /** When the first picture was exposed, on the elapsed-realtime clock. */
    private long mFirstExposedMillis;

    /** When the last picture so far was exposed. */
    private long mLastExposedMillis;

    /** Whether the platform clicks for each picture, as read at the first. */
    private boolean mPlatformClicks;

    /** Whether the loop is sounding, or the platform's clicks stand in for it. */
    private boolean mRunning;

    /** Whether nothing more will play. */
    private boolean mDone;

    /**
     * @param periodMillis the loop's frame length, from {@link #framePeriodMillis}
     * @param required whether the sound must be heard whatever the device is set to
     */
    BurstSound(@NonNull Burst burst, boolean required, @NonNull PlatformClicks platform,
            int periodMillis, @NonNull RateListener rateListener) {
        mBurst = burst;
        mPlayer = sPlayerFactory.create(required);
        mPlatform = platform;
        mClip = click(periodMillis);
        mRateListener = rateListener;
        burst.whenStopped(this::onStopped);
        burst.whenFinished(this::onFinished);
    }

    /** Called as each picture of the burst is exposed. */
    void onPictureExposed() {
        if (mDone) {
            return;
        }
        mExposed++;
        long now = SystemClock.elapsedRealtime();
        mLastExposedMillis = now;
        boolean last = mBurst.isEnded() && mExposed >= mBurst.getRequestedCount();
        if (mExposed == 1) {
            mFirstExposedMillis = now;
            mPlatformClicks = mPlatform.clicksEachPicture();
            if (mBurst.isStopped() || last) {
                // A quick release, stopped before its first picture, or a burst of one.
                if (!mPlatformClicks) {
                    mPlayer.playOnce(mClip);
                }
                mDone = true;
                return;
            }
            if (!mPlatformClicks) {
                mPlayer.startLoop(mClip);
            }
            mRunning = true;
        } else if (last) {
            // The last picture of a burst that reached its count.
            end();
        }
    }

    /** The burst was stopped short or finished. Only a running loop ends here. */
    private void onStopped() {
        if (mRunning && !mDone) {
            end();
        }
    }

    /** Nothing plays after the burst is over, even for a picture never exposed. */
    private void onFinished() {
        mDone = true;
        if (mExposed > 1 && mLastExposedMillis > mFirstExposedMillis) {
            mRateListener.onRateMeasured(
                    (mExposed - 1) * 1000f / (mLastExposedMillis - mFirstExposedMillis));
        }
    }

    private void end() {
        mDone = true;
        mRunning = false;
        if (!mPlatformClicks) {
            mPlayer.stopLoop();
        }
        mPlayer.playEnd(endTone());
    }

    /**
     * The loop's frame length for a burst taking the given number of pictures a second: the
     * nearest of {@link #FRAME_PERIODS_MILLIS}, or {@link #DEFAULT_FRAME_PERIOD_MILLIS} for a rate
     * that is not known.
     */
    @AnyThread
    static int framePeriodMillis(float picturesPerSecond) {
        if (!(picturesPerSecond > 0) || Float.isInfinite(picturesPerSecond)) {
            return DEFAULT_FRAME_PERIOD_MILLIS;
        }
        float period = 1000f / picturesPerSecond;
        int nearest = FRAME_PERIODS_MILLIS[0];
        for (int candidate : FRAME_PERIODS_MILLIS) {
            if (Math.abs(candidate - period) < Math.abs(nearest - period)) {
                nearest = candidate;
            }
        }
        return nearest;
    }

    /**
     * One frame of the loop: a shutter's click, two quick transients like a blade opening and
     * closing, then silence to the end of the frame.
     */
    @AnyThread
    @NonNull
    static short[] click(int periodMillis) {
        float[] samples = new float[SAMPLE_RATE * periodMillis / 1000];
        // Seeded, so the click is the same every time.
        Random noise = new Random(0x5EED);
        addTransient(samples, 0, 1f, noise);
        addTransient(samples, millisToSamples(9), 0.55f, noise);
        return toPcm(samples, 0.8f);
    }

    /**
     * Adds one transient: a burst of noise that dies away within a couple of milliseconds, over a
     * ring at 1.8 kHz that lasts a little longer.
     */
    @AnyThread
    private static void addTransient(@NonNull float[] samples, int start, float amplitude,
            @NonNull Random noise) {
        int length = Math.min(millisToSamples(12), samples.length - start);
        float previous = 0f;
        for (int i = 0; i < length; i++) {
            float t = (float) i / SAMPLE_RATE;
            float white = noise.nextFloat() * 2f - 1f;
            // Differenced, which takes out the low end and leaves the noise crisp.
            float crisp = white - previous;
            previous = white;
            float tick = crisp * (float) Math.exp(-t / 0.0009f);
            float ring = 0.4f * (float) (Math.sin(2 * Math.PI * 1800 * t) * Math.exp(-t / 0.002f));
            samples[start + i] += amplitude * (tick + ring);
        }
    }

    /**
     * The end tone: two soft notes falling a fourth, E6 then B5, each faded in and out so neither
     * clicks.
     */
    @AnyThread
    @NonNull
    static short[] endTone() {
        float[] samples = new float[millisToSamples(END_TONE_MILLIS)];
        addNote(samples, 0, millisToSamples(75), 1318.5f);
        addNote(samples, millisToSamples(85), millisToSamples(85), 987.8f);
        return toPcm(samples, 0.3f);
    }

    /** Adds a note with a little of its octave, faded in over 5 ms and away over its length. */
    @AnyThread
    private static void addNote(@NonNull float[] samples, int start, int length, float frequency) {
        int attack = millisToSamples(5);
        for (int i = 0; i < length && start + i < samples.length; i++) {
            float t = (float) i / SAMPLE_RATE;
            float envelope = Math.min(1f, (float) i / attack)
                    * (float) Math.exp(-3.0 * i / length)
                    * (float) Math.sin(Math.PI / 2 * Math.min(1f, (float) (length - i) / attack));
            float tone = (float) (Math.sin(2 * Math.PI * frequency * t)
                    + 0.2 * Math.sin(4 * Math.PI * frequency * t));
            samples[start + i] += envelope * tone;
        }
    }

    @AnyThread
    private static int millisToSamples(int millis) {
        return SAMPLE_RATE * millis / 1000;
    }

    /** Scales the samples so the loudest reaches the given fraction of full scale. */
    @NonNull
    @AnyThread
    private static short[] toPcm(@NonNull float[] samples, float peak) {
        float loudest = 0f;
        for (float sample : samples) {
            loudest = Math.max(loudest, Math.abs(sample));
        }
        float scale = loudest == 0f ? 0f : peak * Short.MAX_VALUE / loudest;
        short[] pcm = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            pcm[i] = (short) Math.round(samples[i] * scale);
        }
        return pcm;
    }

    /** Lets a test hear what is played; null puts the AudioTrack back. */
    @VisibleForTesting
    static void setPlayerFactory(@Nullable PlayerFactory factory) {
        sPlayerFactory = factory == null ? TrackPlayer::new : factory;
    }

    /**
     * Plays through static AudioTracks, one for the loop and one for each sound played once, on
     * one thread so that a stop never overtakes the start it stops.
     */
    @AnyThread
    static final class TrackPlayer implements Player {
        /** Makes, plays, stops and releases every track, in the order asked. */
        private static final ScheduledExecutorService EXECUTOR =
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "CameraView-BurstSound");
                    thread.setDaemon(true);
                    return thread;
                });

        /** How long after a sound played once its track is released. */
        private static final long RELEASE_MARGIN_MILLIS = 100;

        /** Whether the sound must be heard whatever the device is set to. */
        private final boolean mRequired;

        /** The looping track, or null. Touched only on {@link #EXECUTOR}. */
        @Nullable
        private AudioTrack mLoop;

        TrackPlayer(boolean required) {
            mRequired = required;
        }

        @Override
        public void startLoop(@NonNull short[] clip) {
            EXECUTOR.execute(() -> {
                stopLoopNow();
                AudioTrack track = newTrack(clip);
                if (track == null) {
                    return;
                }
                try {
                    track.setLoopPoints(0, clip.length, -1 /* forever */);
                    track.play();
                    mLoop = track;
                } catch (IllegalStateException error) {
                    Log.w(TAG, "Could not loop the burst's click", error);
                    track.release();
                }
            });
        }

        @Override
        public void playOnce(@NonNull short[] clip) {
            EXECUTOR.execute(() -> playAndRelease(clip));
        }

        @Override
        public void stopLoop() {
            EXECUTOR.execute(this::stopLoopNow);
        }

        @Override
        public void playEnd(@NonNull short[] tone) {
            EXECUTOR.execute(() -> playAndRelease(tone));
        }

        /** Whether the loop is sounding, once every request so far has been carried out. */
        @VisibleForTesting
        boolean isLooping() throws InterruptedException, ExecutionException {
            return EXECUTOR.submit(() -> mLoop != null).get();
        }

        @WorkerThread
        private void stopLoopNow() {
            AudioTrack loop = mLoop;
            mLoop = null;
            if (loop == null) {
                return;
            }
            try {
                // A static track stops at once.
                loop.stop();
            } catch (IllegalStateException error) {
                Log.w(TAG, "Could not stop the burst's click", error);
            }
            loop.release();
        }

        @WorkerThread
        private void playAndRelease(@NonNull short[] pcm) {
            AudioTrack track = newTrack(pcm);
            if (track == null) {
                return;
            }
            try {
                track.play();
            } catch (IllegalStateException error) {
                Log.w(TAG, "Could not play a burst sound", error);
                track.release();
                return;
            }
            EXECUTOR.schedule(track::release,
                    pcm.length * 1000L / SAMPLE_RATE + RELEASE_MARGIN_MILLIS,
                    TimeUnit.MILLISECONDS);
        }

        /** A static track holding the sound, ready to play, or null if one cannot be made. */
        @WorkerThread
        @Nullable
        @SuppressWarnings("deprecation")
        private AudioTrack newTrack(@NonNull short[] pcm) {
            int bytes = pcm.length * 2;
            AudioTrack track;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    track = new AudioTrack(attributes(mRequired), new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(), bytes, AudioTrack.MODE_STATIC,
                            AudioManager.AUDIO_SESSION_ID_GENERATE);
                } else {
                    // No audio attributes yet; the system stream is the platform click's.
                    track = new AudioTrack(AudioManager.STREAM_SYSTEM, SAMPLE_RATE,
                            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, bytes,
                            AudioTrack.MODE_STATIC);
                }
            } catch (IllegalArgumentException | UnsupportedOperationException error) {
                Log.w(TAG, "Could not make a track for a burst sound", error);
                return null;
            }
            if (track.getState() == AudioTrack.STATE_UNINITIALIZED
                    || track.write(pcm, 0, pcm.length) != pcm.length) {
                Log.w(TAG, "Could not load a burst sound");
                track.release();
                return null;
            }
            return track;
        }

        /**
         * A sonification sound, as a camera's click is, which silent mode mutes. Where the sound
         * is required it is enforced, and plays whatever the device is set to.
         */
        @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
        @NonNull
        static AudioAttributes attributes(boolean required) {
            AudioAttributes.Builder builder = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION);
            if (required) {
                builder.setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED);
            }
            return builder.build();
        }
    }
}
