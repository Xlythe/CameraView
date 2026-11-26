package com.xlythe.view.camera.caption;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.xlythe.view.camera.ICameraModule.TAG;

/**
 * Captions for a conversation, as it happens, in another language if asked.
 *
 * <p>What this is for is a call: two people who do not share a language, each seeing what the
 * other is saying underneath them as they say it. That shapes everything about it. The words
 * have to appear while the sentence is still being spoken rather than after it, because a
 * caption that arrives when the speaker has already moved on is not a caption of a
 * conversation; a silence is an ordinary part of talking rather than the end of the session; and
 * the translation, which takes a moment longer than the hearing, arrives after the words it
 * translates rather than holding them up.</p>
 *
 * <p>Nothing is added to the library to do either half. The platform recogniser gives partial
 * results as somebody talks, and Android 12 added an on-device translator - meant for
 * translating what is on screen, and it translates text for anyone who asks. So there is no
 * model to ship and no dependency for an app to match a version of.</p>
 *
 * <h2>Two sides of a call</h2>
 *
 * <p>This listens to the microphone, which is the near side: what the person holding the phone
 * says, captioned so it can be sent with the stream. The far side is the same class listening to
 * audio that arrives over the network - see {@link #startListeningToAudio} - because a call needs
 * both and a captioner tied to the camera could only ever do one.</p>
 *
 * <h2>What it costs</h2>
 *
 * <p>A recogniser and, where one is asked for, a translator. Both are held for as long as
 * captioning runs and released by {@link #stop}. It is not free: continuous recognition keeps
 * the microphone open and a model running, which a call is already paying for and an idle screen
 * is not.</p>
 */
public final class LiveCaptions {
    /**
     * How long to wait before listening again after the recogniser gave up on a phrase.
     *
     * <p>Short, because this is the gap between sentences and a person does not pause for long.
     * Not zero, because a recogniser that fails immediately and is restarted immediately is a
     * loop that holds the main thread.</p>
     */
    private static final long RESTART_DELAY_MS = 150;

    /**
     * How many times to restart after failures with no speech in between before giving up.
     *
     * <p>A recogniser that refuses twenty times in a row is not waiting for somebody to speak,
     * it is broken - and a loop that never reports anything is worse than one that says so.</p>
     */
    private static final int MAX_CONSECUTIVE_FAILURES = 20;

    private final Context mContext;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    @Nullable
    private SpeechRecognizer mRecognizer;

    @Nullable
    private LiveTranslation mTranslation;

    @Nullable
    private LiveCaptionListener mListener;

    /** The intent the recogniser is started with, kept so a restart is the same request. */
    @Nullable
    private Intent mRequest;

    private boolean mStarted;
    private long mSessionStartMs;
    private long mPhraseStartMs;
    private int mConsecutiveFailures;

    /** The newest partial text, so a repeat of the same words is not reported twice. */
    @Nullable
    private String mLastPartial;

    public LiveCaptions(@NonNull Context context) {
        mContext = context.getApplicationContext();
    }

    /** Whether this device can caption a conversation at all. */
    public static boolean isSupported(@NonNull Context context) {
        return SpeechRecognizer.isRecognitionAvailable(context);
    }

    /**
     * Whether this device can translate between two languages right now.
     *
     * <p>Blocks for as long as the platform takes to answer, which it warns can be seconds, so
     * it belongs on a worker thread. Captioning works without it - the words arrive with no
     * translation attached - so this is worth asking in order to say so rather than to decide
     * whether to caption at all.</p>
     */
    public static boolean isTranslationSupported(@NonNull Context context, @NonNull Locale from,
            @NonNull Locale to) {
        return Build.VERSION.SDK_INT >= 31
                && LiveTranslation.availabilityOf(context, from, to)
                        == LiveTranslation.Availability.READY;
    }

    /**
     * Where the user manages translation languages, or null if there is nowhere to send them.
     *
     * <p>The answer to {@link #isTranslationSupported} being false. The service downloads
     * languages on demand and will not fetch one because an app asked, so without this a
     * caller can tell somebody that translation is unavailable and nothing more. This is the
     * screen they fix it on.</p>
     */
    @Nullable
    public static android.app.PendingIntent getTranslationSettingsIntent(
            @NonNull Context context) {
        return Build.VERSION.SDK_INT >= 31 ? LiveTranslation.settingsIntent(context) : null;
    }

    /**
     * Starts captioning what the microphone hears.
     *
     * <p>Runs until {@link #stop}, through the silences: a pause is part of talking, so the
     * recogniser is started again rather than treated as the end. The listener is called on the
     * main thread, and so must this be.</p>
     *
     * @param spoken the language being spoken, or null for the device's own
     * @param translateTo the language to translate into, or null to caption without translating
     */
    @MainThread
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    public void start(@Nullable Locale spoken, @Nullable Locale translateTo,
            @NonNull LiveCaptionListener listener) {
        startWith(buildRequest(spoken, null /* audio */), spoken, translateTo, listener);
    }

    /**
     * Starts captioning audio the caller supplies rather than the microphone.
     *
     * <p>The far side of a call: audio that arrived over the network, written into the stream
     * this returns. Needs Android 13, where the recogniser first accepted a descriptor instead
     * of the microphone.</p>
     *
     * <p>The samples have to be signed sixteen-bit, which is what every voice stream is, and
     * the caller says what rate and how many channels. Writing stops being useful once
     * {@link #stop} has been called.</p>
     *
     * @return where to write the audio, or null if this device cannot caption a stream
     */
    @MainThread
    @Nullable
    public java.io.OutputStream startListeningToAudio(int sampleRate, int channelCount,
            @Nullable Locale spoken, @Nullable Locale translateTo,
            @NonNull LiveCaptionListener listener) {
        if (Build.VERSION.SDK_INT < 33) {
            fail(listener, "Captioning audio from a stream needs Android 13");
            return null;
        }
        android.os.ParcelFileDescriptor[] pipe;
        try {
            pipe = android.os.ParcelFileDescriptor.createPipe();
        } catch (java.io.IOException error) {
            Log.w(TAG, "Unable to open a pipe for the recogniser", error);
            fail(listener, "This device would not open an audio pipe");
            return null;
        }
        Intent request = buildRequest(spoken, pipe[0]);
        request.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,
                android.media.AudioFormat.ENCODING_PCM_16BIT);
        request.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sampleRate);
        request.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, channelCount);
        startWith(request, spoken, translateTo, listener);
        return new java.io.FileOutputStream(pipe[1].getFileDescriptor());
    }

    @MainThread
    private void startWith(@NonNull Intent request, @Nullable Locale spoken,
            @Nullable Locale translateTo, @NonNull LiveCaptionListener listener) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException(
                    "A recogniser can only be used from the main thread");
        }
        if (mStarted) {
            stop();
        }
        if (!isSupported(mContext)) {
            fail(listener, "This device has no speech recogniser");
            return;
        }
        mListener = listener;
        mRequest = request;
        mStarted = true;
        mSessionStartMs = SystemClock.elapsedRealtime();
        mConsecutiveFailures = 0;
        mLastPartial = null;

        if (translateTo != null) {
            prepareTranslation(spoken == null ? Locale.getDefault() : spoken, translateTo);
        }
        listen();
    }

    /**
     * Asks for a translator, and says whether there will be one.
     *
     * <p>Asked for alongside the listening rather than before it, because the words are worth
     * showing whether or not they can be translated - and loading a language takes long enough
     * that waiting for it would lose the start of the conversation.</p>
     */
    private void prepareTranslation(@NonNull Locale from, @NonNull Locale to) {
        if (Build.VERSION.SDK_INT < 31) {
            report(listener -> listener.onTranslationAvailable(false));
            return;
        }
        LiveTranslation.create(mContext, from, to, mMainHandler::post, translation -> {
            if (!mStarted) {
                // Stopped while the service was loading a language, so it is handed back
                // rather than kept for a session that is over.
                if (translation != null) {
                    translation.close();
                }
                return;
            }
            mTranslation = translation;
            boolean available = translation != null;
            report(listener -> listener.onTranslationAvailable(available));
        });
    }

    /** The request the recogniser is started with, which a restart repeats exactly. */
    @NonNull
    private Intent buildRequest(@Nullable Locale spoken,
            @Nullable android.os.ParcelFileDescriptor audio) {
        Intent request = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                // The whole point: words as they are said rather than when the sentence ends.
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        if (spoken != null) {
            request.putExtra(RecognizerIntent.EXTRA_LANGUAGE, spoken.toLanguageTag());
        }
        if (audio != null) {
            request.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audio);
        }
        return request;
    }

    /** Creates a recogniser if there is not one, and starts it listening. */
    @MainThread
    private void listen() {
        Intent request = mRequest;
        if (!mStarted || request == null) {
            return;
        }
        if (mRecognizer == null) {
            try {
                // On device where it can be. A conversation is the last thing to send to a
                // server without being asked, and a library should not make that choice
                // quietly.
                mRecognizer = Build.VERSION.SDK_INT >= 31
                        && SpeechRecognizer.isOnDeviceRecognitionAvailable(mContext)
                        ? SpeechRecognizer.createOnDeviceSpeechRecognizer(mContext)
                        : SpeechRecognizer.createSpeechRecognizer(mContext);
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to create a speech recogniser", error);
                failAndStop("This device would not create a speech recogniser");
                return;
            }
            mRecognizer.setRecognitionListener(new Captioner());
        }
        try {
            mPhraseStartMs = SystemClock.elapsedRealtime() - mSessionStartMs;
            mRecognizer.startListening(request);
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to start listening", error);
            failAndStop("This device would not start listening");
        }
    }

    /** Stops captioning and gives the recogniser and the translator back. */
    @MainThread
    public void stop() {
        mStarted = false;
        mRequest = null;
        mLastPartial = null;
        SpeechRecognizer recognizer = mRecognizer;
        mRecognizer = null;
        if (recognizer != null) {
            try {
                recognizer.cancel();
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to cancel the recogniser", error);
            }
            try {
                recognizer.destroy();
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to release the recogniser", error);
            }
        }
        LiveTranslation translation = mTranslation;
        mTranslation = null;
        // The version check is for the reader as much as for lint: this field is only ever set
        // on 31 and above, where the platform has a translator at all.
        if (translation != null && Build.VERSION.SDK_INT >= 31) {
            translation.close();
        }
        mListener = null;
    }

    /** Whether captioning is running. */
    public boolean isCaptioning() {
        return mStarted;
    }

    /** What the recogniser reports, turned into lines. */
    private final class Captioner implements RecognitionListener {
        @Override
        public void onPartialResults(Bundle partialResults) {
            String text = firstResult(partialResults);
            if (text == null || text.equals(mLastPartial)) {
                // The same words again, which a recogniser reports while it waits for more.
                return;
            }
            mLastPartial = text;
            mConsecutiveFailures = 0;
            emit(text, false /* final */);
        }

        @Override
        public void onResults(Bundle results) {
            String text = firstResult(results);
            mLastPartial = null;
            if (text != null) {
                mConsecutiveFailures = 0;
                emit(text, true /* final */);
            }
            // A finished phrase is not a finished conversation, so listen again.
            restart();
        }

        @Override
        public void onError(int error) {
            mLastPartial = null;
            if (isFatal(error)) {
                failAndStop(describe(error));
                return;
            }
            if (++mConsecutiveFailures > MAX_CONSECUTIVE_FAILURES) {
                failAndStop("The recogniser reported " + describe(error) + " "
                        + mConsecutiveFailures + " times without hearing anything");
                return;
            }
            // A silence, or a phrase it could not make out, which is what a conversation is
            // full of.
            restart();
        }

        @Override public void onReadyForSpeech(Bundle params) {}

        @Override
        public void onBeginningOfSpeech() {
            // Timed from here rather than from when listening started, so a line is stamped
            // with when it was said and not with when the last silence began.
            mPhraseStartMs = SystemClock.elapsedRealtime() - mSessionStartMs;
        }

        @Override public void onRmsChanged(float rmsdB) {}
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() {}
        @Override public void onEvent(int eventType, Bundle params) {}
    }

    /** Starts listening again, after a moment, if captioning is still wanted. */
    @MainThread
    private void restart() {
        if (!mStarted) {
            return;
        }
        mMainHandler.postDelayed(this::listen, RESTART_DELAY_MS);
    }

    /**
     * Reports a line, and asks for its translation.
     *
     * <p>The line goes out first and the translation follows, which is why the same line is
     * reported more than once: the words are useful immediately and the translation is a
     * moment behind, so holding the words back to arrive together would make both late.</p>
     */
    private void emit(@NonNull String text, boolean settled) {
        long startMs = mPhraseStartMs;
        long endMs = SystemClock.elapsedRealtime() - mSessionStartMs;
        LiveCaption caption = new LiveCaption(text, null, settled, startMs, endMs);
        report(listener -> listener.onLiveCaption(caption));

        LiveTranslation translation = mTranslation;
        if (translation == null || Build.VERSION.SDK_INT < 31) {
            return;
        }
        // A line still being spoken drops the translation in flight for its previous version:
        // those words have already changed, so that answer is out of date before it arrives.
        translation.translate(text, !settled, translated ->
                report(listener -> listener.onLiveCaption(caption.withTranslation(translated))));
    }

    /** Whether an error means captioning cannot carry on, rather than just this phrase. */
    private static boolean isFatal(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
            case SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT:
                return true;
            default:
                // Everything else - a silence, a phrase it could not place, a busy recogniser,
                // a network hiccup - is something a conversation carries on through.
                return false;
        }
    }

    @NonNull
    private static String describe(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "the microphone permission has not been granted";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
                return "this recogniser does not have that language";
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
                return "that language is not downloaded";
            case SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT:
                return "the recogniser cannot say what it supports";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "nothing it could make out";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "silence";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "the recogniser is busy";
            default:
                return "recogniser error " + error;
        }
    }

    @Nullable
    private static String firstResult(@Nullable Bundle results) {
        if (results == null) {
            return null;
        }
        List<String> texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (texts == null || texts.isEmpty()) {
            return null;
        }
        String text = texts.get(0).trim();
        return text.isEmpty() ? null : text;
    }

    private void failAndStop(@NonNull String reason) {
        LiveCaptionListener listener = mListener;
        stop();
        if (listener != null) {
            Log.w(TAG, reason);
            mMainHandler.post(() -> listener.onLiveCaptionFailed(reason));
        }
    }

    private void fail(@NonNull LiveCaptionListener listener, @NonNull String reason) {
        Log.w(TAG, reason);
        mMainHandler.post(() -> listener.onLiveCaptionFailed(reason));
    }

    /** Calls the listener on the main thread, if there still is one. */
    private void report(@NonNull Report report) {
        LiveCaptionListener listener = mListener;
        if (listener == null) {
            return;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            report.to(listener);
            return;
        }
        mMainHandler.post(() -> {
            LiveCaptionListener still = mListener;
            if (still != null) {
                report.to(still);
            }
        });
    }

    private interface Report {
        void to(@NonNull LiveCaptionListener listener);
    }
}
