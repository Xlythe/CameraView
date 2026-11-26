package com.xlythe.view.camera.caption;

import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.speech.RecognitionListener;
import android.speech.RecognitionPart;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.xlythe.view.camera.Captions;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.xlythe.view.camera.ICameraModule.TAG;

/**
 * Captions for a recording, from what was said in it.
 *
 * <p>Nothing is added to the library to do it. Android 13 let the platform recogniser read from
 * a file descriptor rather than only the microphone, which is what makes transcribing a
 * finished recording possible at all; Android 14 added a time for each word, which is what
 * makes the captions land where the words do. Below 13 there is no file input and this reports
 * that rather than pretending - an app that needs captions on older devices has to bring its
 * own model, and {@link Captions} will still write whatever it produces.</p>
 *
 * <h2>Why it goes a window at a time</h2>
 *
 * <p>One pass over a whole recording gives one block of text, and on a device with no word
 * times there is then nothing to say when any of it was said. Recognising a window at a time
 * means every result has a known place in the recording whatever the device reports, and word
 * times refine it where they exist rather than being the only source of it. It also keeps each
 * recognition short, which matters because the platform gives no promise about how long a
 * recording it will accept.</p>
 *
 * <h2>Threads</h2>
 *
 * <p>{@link SpeechRecognizer} has to be used from the main thread, and decoding audio must not
 * be. So the work is split: decoding runs on the caller's background thread and each window is
 * handed to the main thread to recognise, which the background thread waits for. That is why
 * {@link #transcribe} blocks and must not itself be called from the main thread.</p>
 */
@RequiresApi(33)
public final class SpeechTranscriber {
    /**
     * How much of the recording is recognised at a time.
     *
     * <p>Six seconds is a sentence or two. Long enough that the recogniser has context to work
     * with - fed a word at a time it guesses worse - and short enough that a device reporting
     * no word times still places its captions within a few seconds of the truth.</p>
     */
    private static final long WINDOW_MILLIS = 6_000L;

    /** How long to wait for one window to come back before giving up on it. */
    private static final long RECOGNITION_TIMEOUT_SECONDS = 30;

    private final Context mContext;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean mCancelled = new AtomicBoolean();

    public SpeechTranscriber(@NonNull Context context) {
        mContext = context.getApplicationContext();
    }

    /**
     * Whether this device can transcribe a recording at all.
     *
     * <p>Two things, and both are needed: the platform has to accept a file as its audio source,
     * which arrived in Android 13, and there has to be a recogniser installed to accept it.</p>
     */
    public static boolean isSupported(@NonNull Context context) {
        return Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isRecognitionAvailable(context);
    }

    /**
     * Whether this device reports when each word was said.
     *
     * <p>It still transcribes without it - the captions are then as precise as the window they
     * were recognised in - so this is worth asking only to tell somebody what to expect.</p>
     */
    public static boolean hasWordTiming() {
        return Build.VERSION.SDK_INT >= 34;
    }

    /** Stops a transcription in progress at the next window boundary. */
    public void cancel() {
        mCancelled.set(true);
    }

    /**
     * Transcribes a recording and writes the captions beside it.
     *
     * <p>Blocks until it is done, so it belongs on a background thread. The listener is called
     * on the main thread.</p>
     *
     * @param video the recording, which must have an audio track
     * @param format what to write, as {@link Captions} understands it
     */
    public void transcribe(@NonNull File video, @NonNull CaptionFormat format,
            @NonNull TranscriptionListener listener) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException(
                    "transcribe decodes audio and blocks; it cannot run on the main thread");
        }
        if (!isSupported(mContext)) {
            fail(listener, "This device cannot transcribe a recording: it needs Android 13 and a"
                    + " speech recogniser");
            return;
        }
        AudioPcm audio = AudioPcm.open(video);
        if (audio == null) {
            fail(listener, "This recording has no audio to transcribe");
            return;
        }
        Transcript transcript = new Transcript();
        try {
            long durationMs = Math.max(1, durationOf(audio, video));
            byte[] window;
            while (!mCancelled.get() && (window = audio.readWindow(WINDOW_MILLIS)) != null) {
                long startMs = audio.getWindowStartMs();
                long endMs = startMs + audio.millisFor(window.length);
                Result result = recognise(window, audio.getSampleRate(),
                        audio.getChannelCount());
                if (result != null) {
                    transcript.add(startMs, endMs, result.mText, result.mWords);
                }
                float progress = Math.min(1f, (float) endMs / durationMs);
                mMainHandler.post(() -> listener.onTranscriptionProgress(progress));
            }
        } finally {
            audio.close();
        }
        if (transcript.isEmpty()) {
            fail(listener, mCancelled.get()
                    ? "The transcription was cancelled"
                    : "Nothing was said in this recording, or none of it could be made out");
            return;
        }
        Captions captions = transcript.toCaptions();
        File sidecar = Captions.sidecarFor(video, format.getExtension());
        try {
            if (format == CaptionFormat.WEB_VTT) {
                captions.writeWebVtt(sidecar);
            } else {
                captions.writeSubRip(sidecar);
            }
        } catch (IOException error) {
            Log.w(TAG, "Unable to write captions to " + sidecar, error);
            fail(listener, "The captions could not be written: " + error.getMessage());
            return;
        }
        mMainHandler.post(() -> listener.onTranscribed(captions, sidecar));
    }

    /** What came back for one window. */
    private static final class Result {
        private final String mText;
        @Nullable private final List<Transcript.Word> mWords;

        private Result(@NonNull String text, @Nullable List<Transcript.Word> words) {
            mText = text;
            mWords = words;
        }
    }

    /**
     * Recognises one window, waiting for the answer.
     *
     * <p>The samples go through a pipe rather than a temporary file: the recogniser wants a file
     * descriptor, and a pipe is one without leaving anything on disk. The writing happens on its
     * own thread because a pipe holds only so much before it blocks, and the thread that is
     * waiting for the result cannot be the one that has stopped filling it.</p>
     */
    @Nullable
    private Result recognise(@NonNull byte[] samples, int sampleRate, int channelCount) {
        ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (IOException error) {
            Log.w(TAG, "Unable to open a pipe for the recogniser", error);
            return null;
        }
        ParcelFileDescriptor read = pipe[0];
        ParcelFileDescriptor write = pipe[1];
        Thread writer = new Thread(() -> {
            try (OutputStream out = new FileOutputStream(write.getFileDescriptor())) {
                out.write(samples);
            } catch (IOException error) {
                // Ordinary when the recogniser stops reading early, which it does once it has
                // decided it has heard everything.
                Log.d(TAG, "The recogniser stopped reading before the window was finished");
            } finally {
                try {
                    write.close();
                } catch (IOException ignored) {
                    // Nothing can be done about a descriptor that will not close.
                }
            }
        }, "SpeechTranscriber");
        writer.setDaemon(true);
        writer.start();

        CountDownLatch finished = new CountDownLatch(1);
        List<Result> result = new ArrayList<>(1);
        mMainHandler.post(() -> start(read, sampleRate, channelCount, result, finished));
        try {
            if (!finished.await(RECOGNITION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(TAG, "The recogniser did not answer for a window of audio");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                read.close();
            } catch (IOException ignored) {
                // Already closed by the recogniser, which is the usual case.
            }
        }
        return result.isEmpty() ? null : result.get(0);
    }

    /** Runs on the main thread, which is the only thread a SpeechRecognizer may be used from. */
    private void start(@NonNull ParcelFileDescriptor audio, int sampleRate, int channelCount,
            @NonNull List<Result> into, @NonNull CountDownLatch finished) {
        SpeechRecognizer recognizer;
        try {
            // On device where it can be: a recording is private, and sending its audio to a
            // server to be transcribed is not a decision a camera library should make quietly.
            recognizer = SpeechRecognizer.isOnDeviceRecognitionAvailable(mContext)
                    ? SpeechRecognizer.createOnDeviceSpeechRecognizer(mContext)
                    : SpeechRecognizer.createSpeechRecognizer(mContext);
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to create a speech recogniser", error);
            finished.countDown();
            return;
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audio)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,
                        AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sampleRate)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, channelCount);
        if (hasWordTiming()) {
            intent.putExtra(RecognizerIntent.EXTRA_REQUEST_WORD_TIMING, true);
        }
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onResults(android.os.Bundle results) {
                into.add(read(results));
                done();
            }

            @Override
            public void onError(int error) {
                // A window of silence reports an error rather than an empty result, which is
                // why this is not treated as a failure of the whole transcription.
                Log.d(TAG, "A window of audio was not recognised, error " + error);
                done();
            }

            @Override public void onReadyForSpeech(android.os.Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onPartialResults(android.os.Bundle partialResults) {}
            @Override public void onEvent(int eventType, android.os.Bundle params) {}

            private void done() {
                try {
                    recognizer.destroy();
                } catch (RuntimeException error) {
                    Log.w(TAG, "Unable to release the speech recogniser", error);
                }
                finished.countDown();
            }
        });
        try {
            recognizer.startListening(intent);
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to start recognition for a window of audio", error);
            recognizer.destroy();
            finished.countDown();
        }
    }

    /** Pulls the text and, where it is there, the word times out of a result. */
    @NonNull
    private static Result read(@Nullable android.os.Bundle results) {
        List<String> texts = results == null ? null
                : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        String text = texts == null || texts.isEmpty() ? "" : texts.get(0);
        if (Build.VERSION.SDK_INT < 34 || results == null) {
            return new Result(text, null);
        }
        List<RecognitionPart> parts =
                results.getParcelableArrayList(SpeechRecognizer.RECOGNITION_PARTS,
                        RecognitionPart.class);
        if (parts == null || parts.isEmpty()) {
            return new Result(text, null);
        }
        List<Transcript.Word> words = new ArrayList<>(parts.size());
        for (RecognitionPart part : parts) {
            String raw = part.getFormattedText() != null ? part.getFormattedText()
                    : part.getRawText();
            words.add(new Transcript.Word(raw == null ? "" : raw, part.getTimestampMillis()));
        }
        return new Result(text, words);
    }

    /**
     * How long the recording is, for reporting progress.
     *
     * <p>Read from the track where it says, and guessed from the file otherwise - progress that
     * is a little wrong is better than no progress at all for something this slow.</p>
     */
    private static long durationOf(@NonNull AudioPcm audio, @NonNull File video) {
        android.media.MediaMetadataRetriever retriever =
                new android.media.MediaMetadataRetriever();
        try {
            retriever.setDataSource(video.getAbsolutePath());
            String duration = retriever.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            return duration == null ? 0 : Long.parseLong(duration);
        } catch (RuntimeException error) {
            Log.d(TAG, "This recording does not say how long it is");
            return 0;
        } finally {
            try {
                retriever.release();
            } catch (IOException ignored) {
                // Nothing can be done about a retriever that will not release.
            }
        }
    }

    private void fail(@NonNull TranscriptionListener listener, @NonNull String reason) {
        Log.w(TAG, reason);
        mMainHandler.post(() -> listener.onTranscriptionFailed(reason));
    }
}
