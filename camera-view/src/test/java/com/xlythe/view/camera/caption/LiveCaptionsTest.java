package com.xlythe.view.camera.caption;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.annotation.NonNull;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Whether live captions behave the way a conversation needs.
 *
 * <p>The recogniser and the translator are the platform's and cannot be stood up here, so what
 * is checked is everything around them: that a line is a value a caller can rely on, that a
 * device with no recogniser is told so rather than left waiting, and that starting and stopping
 * leaves nothing running. The recognising itself is checked on a device, where there is a
 * microphone.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class LiveCaptionsTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
    }

    /** Collects what arrives, so a test can say what the caller would have seen. */
    private static final class Captions implements LiveCaptionListener {
        private final List<LiveCaption> lines = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();
        private final List<Boolean> translation = new ArrayList<>();

        @Override
        public void onLiveCaption(@NonNull LiveCaption caption) {
            lines.add(caption);
        }

        @Override
        public void onLiveCaptionFailed(@NonNull String reason) {
            failures.add(reason);
        }

        @Override
        public void onTranslationAvailable(boolean available) {
            translation.add(available);
        }
    }

    /**
     * A device with no recogniser is told so, rather than left waiting for captions.
     *
     * <p>The case that matters most for a library: an app shows a captions button, somebody
     * presses it, and nothing happens for ever. Reported instead, with a reason worth putting
     * on screen.</p>
     */
    @Test
    public void aDeviceWithNoRecogniserSaysSo() {
        Captions captions = new Captions();
        LiveCaptions live = new LiveCaptions(context);

        live.start(Locale.ENGLISH, null, captions);
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        // Robolectric has no recognition service, which is the same as a device without one.
        assertFalse("captioning should not be running", live.isCaptioning());
        assertEquals(1, captions.failures.size());
        assertTrue(captions.failures.get(0),
                captions.failures.get(0).contains("no speech recogniser"));
        assertTrue("and no captions should have arrived", captions.lines.isEmpty());
    }

    @Test
    public void supportIsReportedRatherThanAssumed() {
        // False here, and the point is that it is asked rather than that it is false: an app
        // offering captions has a way to find out before offering them.
        assertFalse(LiveCaptions.isSupported(context));
    }

    /** Captioning a stream needs Android 13, and says so on anything older. */
    @Test
    @Config(sdk = 29)
    public void captioningAStreamNeedsAndroid13() {
        Captions captions = new Captions();
        LiveCaptions live = new LiveCaptions(context);

        assertNull(live.startListeningToAudio(16_000, 1, Locale.ENGLISH, null, captions));
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        assertEquals(1, captions.failures.size());
        assertTrue(captions.failures.get(0), captions.failures.get(0).contains("Android 13"));
    }

    /** Stopping without starting is what a lifecycle does, and must not throw. */
    @Test
    public void stoppingWithoutStartingIsHarmless() {
        LiveCaptions live = new LiveCaptions(context);

        live.stop();
        live.stop();

        assertFalse(live.isCaptioning());
    }

    /** A recogniser may only be driven from the main thread, and this says so rather than racing. */
    @Test
    public void startingOffTheMainThreadIsRefused() throws Exception {
        LiveCaptions live = new LiveCaptions(context);
        Throwable[] thrown = new Throwable[1];
        Thread worker = new Thread(() -> {
            try {
                live.start(Locale.ENGLISH, null, new Captions());
            } catch (Throwable error) {
                thrown[0] = error;
            }
        });

        worker.start();
        worker.join();

        assertNotNull("starting off the main thread should be refused", thrown[0]);
        assertTrue("expected an IllegalStateException, got " + thrown[0],
                thrown[0] instanceof IllegalStateException);
    }

    /**
     * A line keeps what it was given, including a translation arriving later.
     *
     * <p>Worth pinning because the same line is reported several times and the later reports are
     * built from the earlier ones: a caption that lost its timing when its translation arrived
     * would jump around the transcript.</p>
     */
    @Test
    public void aLineKeepsItsTimingWhenItsTranslationArrives() {
        LiveCaption spoken = new LiveCaption("bonjour", null, false, 1200, 1800);

        assertEquals("bonjour", spoken.getText());
        assertNull(spoken.getTranslation());
        assertFalse(spoken.isFinal());
        assertEquals(1200, spoken.getStartMs());
        assertEquals(1800, spoken.getEndMs());

        LiveCaption translated = spoken.withTranslation("hello");

        assertEquals("hello", translated.getTranslation());
        assertEquals("the words are unchanged", "bonjour", translated.getText());
        assertEquals("and so is the timing", 1200, translated.getStartMs());
        assertEquals(1800, translated.getEndMs());
        assertFalse("and whether it has settled", translated.isFinal());
    }

    /** A settled line stays settled when its translation turns up. */
    @Test
    public void aSettledLineStaysSettled() {
        LiveCaption settled = new LiveCaption("merci", null, true, 0, 500);

        assertTrue(settled.withTranslation("thank you").isFinal());
    }

    /** Translation support is asked of the device rather than assumed from the API level. */
    @Test
    public void translationSupportIsAskedOfTheDevice() {
        // No translation service in Robolectric, which is also a real configuration: an OEM may
        // ship a device without one, and then captions arrive untranslated.
        assertFalse(LiveCaptions.isTranslationSupported(context, Locale.FRENCH, Locale.ENGLISH));
    }

    /** Starting twice stops the first, rather than leaving two recognisers on the microphone. */
    @Test
    public void startingTwiceDoesNotLeaveTwoRunning() {
        LiveCaptions live = new LiveCaptions(context);

        live.start(Locale.ENGLISH, null, new Captions());
        live.start(Locale.ENGLISH, null, new Captions());
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        assertFalse(live.isCaptioning());
        live.stop();
    }

    /** The listener's default says nothing happened, so an app need only implement what it uses. */
    @Test
    public void theListenerHasADefaultForTranslationAvailability() {
        LiveCaptionListener minimal = new LiveCaptionListener() {
            @Override public void onLiveCaption(@NonNull LiveCaption caption) {}
            @Override public void onLiveCaptionFailed(@NonNull String reason) {}
        };

        try {
            minimal.onTranslationAvailable(false);
        } catch (RuntimeException error) {
            fail("the default should do nothing, not throw: " + error);
        }
    }
}
