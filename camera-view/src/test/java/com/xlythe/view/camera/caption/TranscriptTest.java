package com.xlythe.view.camera.caption;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.xlythe.view.camera.Captions;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Whether what was heard becomes captions somebody can read.
 *
 * <p>The half of transcription that does not need a recogniser: given text, windows and word
 * times, do the cues land in the right place and are the lines short enough. Every case here is
 * one somebody will hit - a device that reports no word times, one that reports them, a long
 * sentence, a silent stretch - and the awkward one in the middle: a recogniser that reports
 * zero for every word because it was never asked for times, which is indistinguishable from a
 * window where the speech began immediately.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class TranscriptTest {
    private static List<Transcript.Word> words(String... pairs) {
        List<Transcript.Word> words = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            words.add(new Transcript.Word(pairs[i], Long.parseLong(pairs[i + 1])));
        }
        return words;
    }

    @Test
    public void nothingHeardIsNoCaptions() {
        Transcript transcript = new Transcript();

        assertTrue(transcript.isEmpty());
        assertEquals(0, transcript.toCaptions().size());
    }

    /** A window of silence leaves a gap rather than an empty line. */
    @Test
    public void silenceIsNotACue() {
        Transcript transcript = new Transcript();

        transcript.add(0, 3000, "", null);
        transcript.add(3000, 6000, "   ", null);

        assertTrue(transcript.isEmpty());
        assertEquals(0, transcript.toCaptions().size());
    }

    @Test
    public void aShortLineIsOneCueSpanningItsWindow() {
        Transcript transcript = new Transcript();

        transcript.add(1000, 4000, "hello there", null);

        Captions captions = transcript.toCaptions();
        assertEquals(1, captions.size());
        Captions.Cue cue = captions.getCues().get(0);
        assertEquals("hello there", cue.getText());
        assertEquals(1000, cue.getStartMs());
        assertEquals(4000, cue.getEndMs());
    }

    /** A long sentence is broken into readable lines rather than shown all at once. */
    @Test
    public void aLongLineIsSplit() {
        Transcript transcript = new Transcript();
        String sentence = "this is a much longer sentence that nobody could possibly read in a "
                + "single glance so it has to be broken into several lines";

        transcript.add(0, 6000, sentence, null);

        Captions captions = transcript.toCaptions();
        assertTrue("it should be more than one cue, got " + captions.size(),
                captions.size() > 1);
        for (Captions.Cue cue : captions.getCues()) {
            assertTrue("a line of " + cue.getText().length() + " characters is too long: "
                            + cue.getText(),
                    cue.getText().length() <= 42);
        }
    }

    /** The pieces of a split window tile it exactly, with no gap left at the end. */
    @Test
    public void aSplitWindowIsTiledExactly() {
        Transcript transcript = new Transcript();
        String sentence = "one two three four five six seven eight nine ten eleven twelve "
                + "thirteen fourteen fifteen sixteen";

        transcript.add(2000, 8000, sentence, null);

        Captions captions = transcript.toCaptions();
        List<Captions.Cue> cues = captions.getCues();
        assertEquals("the first cue starts with the window", 2000, cues.get(0).getStartMs());
        assertEquals("the last cue ends with it",
                8000, cues.get(cues.size() - 1).getEndMs());
        for (int i = 1; i < cues.size(); i++) {
            assertEquals("cue " + i + " should start where the one before ended",
                    cues.get(i - 1).getEndMs(), cues.get(i).getStartMs());
        }
    }

    /** No cue is left on screen for less time than it takes to read. */
    @Test
    public void noCueIsTooShortToRead() {
        Transcript transcript = new Transcript();

        transcript.add(0, 100, "yes", null);

        Captions.Cue cue = transcript.toCaptions().getCues().get(0);
        assertTrue("a cue of " + (cue.getEndMs() - cue.getStartMs()) + "ms is unreadable",
                cue.getEndMs() - cue.getStartMs() >= 900);
    }

    /**
     * Word times put a cue where the words were said, not where the window began.
     *
     * <p>The reason word timings are worth asking for: a sentence spoken at the end of a six
     * second window should appear then, not six seconds early.</p>
     */
    @Test
    public void wordTimesDecideWhenACueStarts() {
        Transcript transcript = new Transcript();

        transcript.add(10_000, 16_000, "hello there friend",
                words("hello", "4000", "there", "4400", "friend", "4900"));

        Captions.Cue cue = transcript.toCaptions().getCues().get(0);
        assertEquals("the cue should start when the first word was said",
                14_000, cue.getStartMs());
    }

    @Test
    public void timedLinesRunOnFromOneToTheNext() {
        Transcript transcript = new Transcript();
        // Enough words that the line has to be split, with a time for each.
        List<Transcript.Word> timed = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            String word = "word" + i;
            timed.add(new Transcript.Word(word, 100L * (i + 1)));
            text.append(word).append(' ');
        }

        transcript.add(0, 6000, text.toString(), timed);

        List<Captions.Cue> cues = transcript.toCaptions().getCues();
        assertTrue("it should be more than one cue", cues.size() > 1);
        for (int i = 1; i < cues.size(); i++) {
            assertEquals("cue " + i + " should start where the one before ended",
                    cues.get(i - 1).getEndMs(), cues.get(i).getStartMs());
        }
        assertEquals("the first cue starts at the first word", 100, cues.get(0).getStartMs());
    }

    /**
     * Every word reporting zero means the times were never asked for, not that it all began at
     * once.
     *
     * <p>The platform returns zero both ways and gives nothing to tell them apart, so the
     * reading that cannot put a caption in the wrong place is the one taken: fall back to
     * spreading the text over the window.</p>
     */
    @Test
    public void wordTimesThatAreAllZeroAreIgnored() {
        Transcript transcript = new Transcript();

        transcript.add(5000, 11_000, "one two three four",
                words("one", "0", "two", "0", "three", "0", "four", "0"));

        List<Captions.Cue> cues = transcript.toCaptions().getCues();
        assertEquals(1, cues.size());
        assertEquals("the cue should span the window, not sit at its start",
                5000, cues.get(0).getStartMs());
        assertEquals(11_000, cues.get(0).getEndMs());
    }

    /** One word at zero is ordinary: speech that began as the window did. */
    @Test
    public void aSingleWordAtZeroIsStillBelieved() {
        Transcript transcript = new Transcript();

        transcript.add(5000, 11_000, "one two",
                words("one", "0", "two", "500"));

        List<Captions.Cue> cues = transcript.toCaptions().getCues();
        assertEquals(1, cues.size());
        assertEquals(5000, cues.get(0).getStartMs());
    }

    /** A line is not left up for ever because nothing was said after it. */
    @Test
    public void aCueIsNotLeftUpThroughALongSilence() {
        Transcript transcript = new Transcript();

        transcript.add(0, 60_000, "a few words",
                words("a", "10", "few", "200", "words", "400"));

        Captions.Cue cue = transcript.toCaptions().getCues().get(0);
        assertTrue("a cue of " + (cue.getEndMs() - cue.getStartMs()) + "ms is a stuck caption",
                cue.getEndMs() - cue.getStartMs() <= 6000);
    }

    /** Words that are only punctuation fall back to the text rather than producing nothing. */
    @Test
    public void wordsThatAreAllPunctuationFallBackToTheText() {
        Transcript transcript = new Transcript();

        transcript.add(0, 3000, "hello", words(" ", "100", "", "200"));

        List<Captions.Cue> cues = transcript.toCaptions().getCues();
        assertEquals(1, cues.size());
        assertEquals("hello", cues.get(0).getText());
    }

    @Test
    public void windowsComeOutInTheOrderTheyHappened() {
        Transcript transcript = new Transcript();

        transcript.add(0, 3000, "first", null);
        transcript.add(3000, 6000, "second", null);
        transcript.add(6000, 9000, "third", null);

        List<Captions.Cue> cues = transcript.toCaptions().getCues();
        assertEquals(3, cues.size());
        assertEquals("first", cues.get(0).getText());
        assertEquals("second", cues.get(1).getText());
        assertEquals("third", cues.get(2).getText());
        assertEquals(3, transcript.size());
        assertFalse(transcript.isEmpty());
    }

    @Test
    public void anEmptyWordListIsTreatedAsNoTimes() {
        Transcript transcript = new Transcript();

        transcript.add(1000, 4000, "hello", Collections.<Transcript.Word>emptyList());

        Captions.Cue cue = transcript.toCaptions().getCues().get(0);
        assertEquals(1000, cue.getStartMs());
        assertEquals(4000, cue.getEndMs());
    }

    /** The captions it produces are the ones that can actually be written out. */
    @Test
    public void whatComesOutCanBeWrittenAsSubtitles() throws Exception {
        Transcript transcript = new Transcript();
        transcript.add(1000, 4000, "hello there", null);
        transcript.add(5000, 8000, "and goodbye", null);

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        transcript.toCaptions().writeSubRip(out);

        String subRip = out.toString("UTF-8");
        assertTrue(subRip, subRip.contains("hello there"));
        assertTrue(subRip, subRip.contains("and goodbye"));
        assertTrue("SubRip counts from the start of the recording",
                subRip.contains("00:00:01,000"));
    }

    /** Cues never overlap, which a player would show as two lines at once. */
    @Test
    public void cuesNeverOverlap() {
        Transcript transcript = new Transcript();
        transcript.add(0, 6000, "the first window has quite a lot of words in it so that it "
                + "has to be split into several lines", null);
        transcript.add(6000, 12_000, "and the second window does too with more words again "
                + "than will fit on one line", null);

        List<Captions.Cue> cues = transcript.toCaptions().getCues();
        assertTrue(cues.size() > 2);
        for (int i = 1; i < cues.size(); i++) {
            assertTrue("cue " + i + " starts at " + cues.get(i).getStartMs()
                            + " before the one before ended at " + cues.get(i - 1).getEndMs(),
                    cues.get(i).getStartMs() >= cues.get(i - 1).getEndMs());
        }
    }
}
