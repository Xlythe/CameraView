package com.xlythe.view.camera.test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import com.xlythe.view.camera.caption.LiveCaption;
import com.xlythe.view.camera.caption.LiveCaptionListener;
import com.xlythe.view.camera.caption.LiveCaptions;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Whether a conversation really gets captioned on this device.
 *
 * <p>The awkward thing about testing live captions is that nobody is talking. So the device
 * talks to itself: the speech synthesiser writes a sentence to a file, and that audio is fed
 * into the captioner through the stream it offers for exactly this - audio that arrived from
 * somewhere other than the microphone, which in a call is the far side. If a caption comes back
 * then the pipe, the descriptor the recogniser reads it through, the partial results and the
 * final result all work.</p>
 *
 * <p>What is asserted is that something was heard, not what. A recogniser fed synthesised
 * speech does not promise the words back verbatim, and a test that demanded them would fail on
 * a device whose voice is simply harder to read than another's. The words it did hear are logged,
 * which is what makes a wrong answer visible to somebody reading the run.</p>
 */
@RunWith(AndroidJUnit4.class)
public final class LiveCaptionDeviceTest {
    private static final String TAG = "LiveCaptionDeviceTest";
    private static final long TIMEOUT_SECONDS = 45;

    /** Short, clear, and unlikely to be misheard as nothing at all. */
    private static final String SPOKEN = "hello, this is a test of live captions";

    @Rule
    public final GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.RECORD_AUDIO);

    @Before
    public void needsARecogniser() {
        Context context = ApplicationProvider.getApplicationContext();
        Assume.assumeTrue("this device has no speech recogniser",
                LiveCaptions.isSupported(context));
    }

    /** Collects what arrives, on the main thread, as a caller would. */
    private static final class Captions implements LiveCaptionListener {
        private final CountDownLatch mHeard = new CountDownLatch(1);
        private final CountDownLatch mSettled = new CountDownLatch(1);
        private final List<LiveCaption> mLines = new ArrayList<>();
        private final AtomicReference<String> mFailure = new AtomicReference<>();
        private final AtomicReference<Boolean> mTranslation = new AtomicReference<>();

        @Override
        public void onLiveCaption(@NonNull LiveCaption caption) {
            mLines.add(caption);
            Log.i(TAG, "caption: " + caption);
            mHeard.countDown();
            if (caption.isFinal()) {
                mSettled.countDown();
            }
        }

        @Override
        public void onLiveCaptionFailed(@NonNull String reason) {
            mFailure.set(reason);
            mHeard.countDown();
            mSettled.countDown();
        }

        @Override
        public void onTranslationAvailable(boolean available) {
            mTranslation.set(available);
        }
    }

    /**
     * The claim: audio in, captions out, while it is still being spoken.
     *
     * <p>Through the stream rather than the microphone, because that is the half a call needs
     * and the half that can be fed a known sentence. It is also the stricter path: it goes
     * through a pipe and a file descriptor as well as the recogniser.</p>
     */
    @Test
    public void audioFromAStreamIsCaptioned() throws Exception {
        Assume.assumeTrue("captioning a stream needs Android 13", Build.VERSION.SDK_INT >= 33);
        File spoken = synthesise(SPOKEN);
        Assume.assumeNotNull("this device would not synthesise any speech to caption", spoken);
        Wav wav = Wav.read(spoken);
        assertNotNull("the synthesiser wrote something that is not a WAV", wav);
        Log.i(TAG, "speaking " + wav.mSamples.length + " bytes at " + wav.mSampleRate
                + "Hz, " + wav.mChannels + " channel(s)");
        Captions captions = new Captions();
        AtomicReference<OutputStream> audio = new AtomicReference<>();

        LiveCaptions live = new LiveCaptions(
                ApplicationProvider.getApplicationContext());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                audio.set(live.startListeningToAudio(wav.mSampleRate, wav.mChannels,
                        Locale.US, null /* no translation */, captions)));
        assertNotNull("the captioner would not take a stream: " + captions.mFailure.get(),
                audio.get());

        // Written on this thread, which is not the main one: the pipe holds only so much and
        // the recogniser is reading it from the other end.
        try (OutputStream out = audio.get()) {
            out.write(wav.mSamples);
            out.flush();
        } catch (IOException stopped) {
            // The recogniser can stop reading once it has decided it has heard enough.
            Log.d(TAG, "the recogniser stopped reading early");
        }

        boolean heard = captions.mHeard.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(live::stop);

        String failure = captions.mFailure.get();
        Assume.assumeTrue("this device would not caption the stream: " + failure,
                failure == null);
        assertTrue("nothing was captioned in " + TIMEOUT_SECONDS + "s, so either the audio"
                        + " never reached the recogniser or it made nothing of it", heard);
        assertFalse("a caption arrived with no words in it", captions.mLines.isEmpty());
        for (LiveCaption caption : captions.mLines) {
            assertFalse("a caption should have text", caption.getText().trim().isEmpty());
            assertTrue("a caption cannot end before it starts",
                    caption.getEndMs() >= caption.getStartMs());
            assertTrue("a caption is timed from when captioning began",
                    caption.getStartMs() >= 0);
        }
    }

    /**
     * What the device will translate, asked rather than assumed.
     *
     * <p>Reported rather than required: the service downloads languages on demand, so a device
     * that has never translated anything has none of them, and that is a real configuration
     * rather than a fault. The point is that the question can be asked at all - an app needs to
     * know before it offers a translation it cannot make.</p>
     */
    @Test
    public void whatCanBeTranslatedIsReported() {
        Assume.assumeTrue("the platform translator arrived in Android 12",
                Build.VERSION.SDK_INT >= 31);
        Context context = ApplicationProvider.getApplicationContext();

        boolean frenchToEnglish =
                LiveCaptions.isTranslationSupported(context, Locale.FRENCH, Locale.ENGLISH);
        boolean englishToEnglish =
                LiveCaptions.isTranslationSupported(context, Locale.ENGLISH, Locale.ENGLISH);

        Log.i(TAG, "translation French to English: " + frenchToEnglish
                + ", English to English: " + englishToEnglish);
        // Neither is asserted either way. What is asserted is that asking does not throw and
        // does not hang, which is the part this library is responsible for.
    }

    /** Starting and stopping leaves nothing listening, which is what a call hanging up does. */
    @Test
    public void captioningStopsWhenItIsStopped() {
        LiveCaptions live = new LiveCaptions(ApplicationProvider.getApplicationContext());
        Captions captions = new Captions();

        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> live.start(Locale.US, null, captions));
        boolean started = live.isCaptioning();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(live::stop);

        assertTrue("the microphone should have been picked up", started);
        assertFalse("and put down again", live.isCaptioning());
    }

    /**
     * Speaks a sentence into a file, so there is something to caption.
     *
     * @return the WAV, or null if this device will not synthesise speech
     */
    @Nullable
    private static File synthesise(@NonNull String text) throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File file = new File(context.getCacheDir(), "spoken-" + System.currentTimeMillis()
                + ".wav");
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<Integer> status = new AtomicReference<>();
        AtomicReference<TextToSpeech> held = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> held.set(
                new TextToSpeech(context, result -> {
                    status.set(result);
                    ready.countDown();
                })));
        TextToSpeech speech = held.get();
        if (!ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                || !Integer.valueOf(TextToSpeech.SUCCESS).equals(status.get())) {
            shutdown(speech);
            return null;
        }
        if (speech.setLanguage(Locale.US) < TextToSpeech.LANG_AVAILABLE) {
            shutdown(speech);
            return null;
        }
        CountDownLatch written = new CountDownLatch(1);
        speech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) {}

            @Override
            public void onDone(String utteranceId) {
                written.countDown();
            }

            @Override
            public void onError(String utteranceId) {
                written.countDown();
            }
        });
        int queued = speech.synthesizeToFile(text, new Bundle(), file, "caption-test");
        if (queued != TextToSpeech.SUCCESS
                || !written.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                || file.length() == 0) {
            shutdown(speech);
            return null;
        }
        shutdown(speech);
        return file;
    }

    private static void shutdown(@Nullable TextToSpeech speech) {
        if (speech != null) {
            speech.shutdown();
        }
    }

    /**
     * The samples out of a WAV, and what they are.
     *
     * <p>Parsed rather than assumed to start at byte forty-four: a synthesiser is free to write
     * chunks before the data, and reading the header as a fixed size turns the first moment of
     * speech into noise - or the whole file into it.</p>
     */
    private static final class Wav {
        private final byte[] mSamples;
        private final int mSampleRate;
        private final int mChannels;

        private Wav(byte[] samples, int sampleRate, int channels) {
            mSamples = samples;
            mSampleRate = sampleRate;
            mChannels = channels;
        }

        @Nullable
        static Wav read(@NonNull File file) throws IOException {
            try (RandomAccessFile wav = new RandomAccessFile(file, "r")) {
                byte[] riff = new byte[12];
                wav.readFully(riff);
                if (riff[0] != 'R' || riff[1] != 'I' || riff[2] != 'F' || riff[3] != 'F') {
                    return null;
                }
                int sampleRate = 0;
                int channels = 1;
                while (wav.getFilePointer() + 8 <= wav.length()) {
                    byte[] id = new byte[4];
                    wav.readFully(id);
                    int size = Integer.reverseBytes(wav.readInt());
                    long next = wav.getFilePointer() + size + (size % 2);
                    String chunk = new String(id, "US-ASCII");
                    if ("fmt ".equals(chunk)) {
                        byte[] format = new byte[Math.min(size, 16)];
                        wav.readFully(format);
                        ByteBuffer buffer = ByteBuffer.wrap(format)
                                .order(ByteOrder.LITTLE_ENDIAN);
                        buffer.getShort();
                        channels = buffer.getShort();
                        sampleRate = buffer.getInt();
                    } else if ("data".equals(chunk)) {
                        byte[] samples = new byte[size];
                        wav.readFully(samples);
                        return sampleRate == 0 ? null
                                : new Wav(samples, sampleRate, Math.max(1, channels));
                    }
                    wav.seek(next);
                }
                return null;
            }
        }
    }
}
