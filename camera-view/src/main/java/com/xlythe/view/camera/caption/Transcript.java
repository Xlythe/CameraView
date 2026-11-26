package com.xlythe.view.camera.caption;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xlythe.view.camera.Captions;

import java.util.ArrayList;
import java.util.List;

/**
 * What was said, as it is collected, and the captions it turns into.
 *
 * <p>Speech recognition gives text and, on a new enough device, a time for each word. Captions
 * need something else: short lines, each with a start and an end, that a reader can keep up
 * with. Turning one into the other is the whole of this class, and it is arithmetic on
 * timestamps - which means it can be tested without a microphone, a recogniser or a recording,
 * and that is the point of it being separate.</p>
 *
 * <h2>Where the times come from</h2>
 *
 * <p>Two sources, because the platform has two. Recognition is run over the recording a window
 * at a time, so every result has a window to sit in even on a device that reports no word
 * times at all - the cue is then as long as the window, which is coarse but never wrong. Where
 * word times are reported, they are offsets from the start of that window and are used to split
 * it into lines that land when the words do.</p>
 *
 * <p>A reported time of zero is treated as no time at all. The platform returns zero both for a
 * word that genuinely begins at the start of a window and for a recogniser that was not asked
 * for timings or cannot produce them, and there is nothing in the result to tell those apart -
 * so a window whose words all say zero is treated as untimed, which is the reading that cannot
 * put a caption in the wrong place.</p>
 */
public final class Transcript {
    /**
     * How many characters a caption line may run to before it is split.
     *
     * <p>Around forty is what subtitle guidelines settle on for one line: enough for a clause,
     * short enough to read in the couple of seconds it is up for.</p>
     */
    private static final int MAX_LINE_CHARACTERS = 42;

    /** How long a cue may last, so a long silence does not leave one line up for a minute. */
    private static final long MAX_CUE_MILLIS = 6_000L;

    /** How short a cue may be, so a single quick word is still readable. */
    private static final long MIN_CUE_MILLIS = 900L;

    private final List<Window> mWindows = new ArrayList<>();

    /** One stretch of the recording that was recognised, and what came back for it. */
    private static final class Window {
        private final long mStartMs;
        private final long mEndMs;
        private final String mText;
        @Nullable private final List<Word> mWords;

        private Window(long startMs, long endMs, @NonNull String text,
                @Nullable List<Word> words) {
            mStartMs = startMs;
            mEndMs = endMs;
            mText = text;
            mWords = words;
        }
    }

    /** One word, and when it began, measured from the start of its window. */
    public static final class Word {
        private final String mText;
        private final long mOffsetMs;

        /**
         * @param offsetMs from the start of the window this word was recognised in, as
         *     {@code RecognitionPart.getTimestampMillis} reports it
         */
        public Word(@NonNull String text, long offsetMs) {
            mText = text;
            mOffsetMs = offsetMs;
        }

        @NonNull
        public String getText() {
            return mText;
        }

        public long getOffsetMs() {
            return mOffsetMs;
        }
    }

    /**
     * Adds what was recognised in one window of the recording.
     *
     * @param startMs where the window begins in the recording
     * @param endMs where it ends
     * @param text what was heard, or empty for a window with no speech in it
     * @param words the words and their offsets, or null where the recogniser reported none
     */
    public void add(long startMs, long endMs, @NonNull String text,
            @Nullable List<Word> words) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            // A window of silence is not a cue. Kept out rather than added empty, so that the
            // captions have gaps where the recording does.
            return;
        }
        // Stored as given. A window stretched to meet the minimum readable length here would
        // reach into the window after it, and the minimum is applied per cue at the end
        // instead, where there is something to measure the room against.
        mWindows.add(new Window(startMs, Math.max(endMs, startMs), trimmed,
                usableWords(words)));
    }

    /**
     * The words, or null if their times cannot be believed.
     *
     * <p>Every offset being zero is what a recogniser that was not asked for timings reports,
     * and it is indistinguishable from a window where every word really did begin at zero -
     * which cannot happen for more than one word. So more than one word at zero means the times
     * are absent rather than remarkable.</p>
     */
    @Nullable
    private static List<Word> usableWords(@Nullable List<Word> words) {
        if (words == null || words.isEmpty()) {
            return null;
        }
        int atZero = 0;
        for (Word word : words) {
            if (word.getOffsetMs() <= 0) {
                atZero++;
            }
        }
        return atZero > 1 ? null : words;
    }

    /** Whether anything was heard at all. */
    public boolean isEmpty() {
        return mWindows.isEmpty();
    }

    /** How many windows had speech in them. */
    public int size() {
        return mWindows.size();
    }

    /**
     * The captions, as lines a reader can keep up with.
     *
     * <p>Built in the order the windows were added, which is the order they were recognised in,
     * which is the order they happen in the recording.</p>
     */
    @NonNull
    public Captions toCaptions() {
        List<Cue> cues = new ArrayList<>();
        for (Window window : mWindows) {
            if (window.mWords == null) {
                addUntimed(cues, window);
            } else {
                addTimed(cues, window);
            }
        }
        settle(cues);
        Captions.Builder captions = new Captions.Builder();
        for (Cue cue : cues) {
            captions.addCue(cue.mStartMs, cue.mEndMs, cue.mText);
        }
        return captions.build();
    }

    /** One line and when it is up, before the timing has been settled against its neighbours. */
    private static final class Cue {
        private final long mStartMs;
        private long mEndMs;
        private final String mText;

        private Cue(long startMs, long endMs, @NonNull String text) {
            mStartMs = startMs;
            mEndMs = endMs;
            mText = text;
        }
    }

    /**
     * Settles when each line comes off screen, which is the one rule they all have to obey.
     *
     * <p>Two things are wanted and they conflict: a line should be up long enough to read, and
     * two lines should never be up at once. Overlapping wins, because a player shows an overlap
     * as both lines together, which is worse than a line that went by quickly - so a cue is
     * extended towards the readable minimum only as far as the next one lets it. The last cue
     * has nothing after it and can always have its full time.</p>
     *
     * <p>Done in a pass at the end rather than as each cue is built, because whether there is
     * room depends on the cue that comes next, and that is not known yet when a window is being
     * split.</p>
     */
    private static void settle(@NonNull List<Cue> cues) {
        for (int i = 0; i < cues.size(); i++) {
            Cue cue = cues.get(i);
            long limit = i + 1 < cues.size() ? cues.get(i + 1).mStartMs : Long.MAX_VALUE;
            // Never past the next line, whatever the window said.
            cue.mEndMs = Math.min(cue.mEndMs, limit);
            // And up to the readable minimum, where there is room for it.
            cue.mEndMs = Math.max(cue.mEndMs, Math.min(cue.mStartMs + MIN_CUE_MILLIS, limit));
            // A cue that still has no length at all would be a caption nobody sees; given the
            // clamp above that means the next line starts at the same moment, so it goes.
            if (cue.mEndMs <= cue.mStartMs) {
                cues.remove(i--);
            }
        }
    }

    /**
     * A window with no word times becomes as few cues as will hold its text.
     *
     * <p>Split by length only, with the window's own span shared out between the pieces in
     * proportion to how much text each holds. That is a guess, and it is a better one than
     * putting forty words on screen at once for six seconds.</p>
     */
    private static void addUntimed(@NonNull List<Cue> cues, @NonNull Window window) {
        List<String> lines = split(window.mText);
        long span = window.mEndMs - window.mStartMs;
        int characters = 0;
        for (String line : lines) {
            characters += line.length();
        }
        long at = window.mStartMs;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            // The last line ends where the window does, however the rounding went, so the cues
            // tile the window exactly rather than leaving a few milliseconds at the end.
            long end = i == lines.size() - 1
                    ? window.mEndMs
                    : at + span * line.length() / Math.max(1, characters);
            cues.add(new Cue(at, end, line));
            at = end;
        }
    }

    /**
     * A window with word times becomes cues that start when their first word was said.
     *
     * <p>A cue ends when the next one starts, so the lines run on without gaps, and the last
     * one ends with the window. A cue is also cut short of {@link #MAX_CUE_MILLIS} even if
     * nothing follows it, because a line left up through a long silence reads as a stuck
     * caption.</p>
     */
    private static void addTimed(@NonNull List<Cue> cues, @NonNull Window window) {
        List<Word> words = window.mWords;
        List<Integer> starts = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i).getText().trim();
            if (word.isEmpty()) {
                continue;
            }
            if (line.length() > 0 && line.length() + 1 + word.length() > MAX_LINE_CHARACTERS) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() == 0) {
                starts.add(i);
            } else {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        if (lines.isEmpty()) {
            // The words were all punctuation or whitespace, so the text is all there is to go on.
            addUntimed(cues, window);
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            long start = window.mStartMs + words.get(starts.get(i)).getOffsetMs();
            long end = i + 1 < lines.size()
                    ? window.mStartMs + words.get(starts.get(i + 1)).getOffsetMs()
                    : window.mEndMs;
            // Cut short of the maximum even where nothing follows, because a line left up
            // through a long silence reads as a caption that has stuck.
            cues.add(new Cue(start, Math.min(end, start + MAX_CUE_MILLIS), lines.get(i)));
        }
    }

    /** Text broken into lines short enough to read, on word boundaries. */
    @NonNull
    private static List<String> split(@NonNull String text) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (line.length() > 0 && line.length() + 1 + word.length() > MAX_LINE_CHARACTERS) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        if (lines.isEmpty()) {
            lines.add(text);
        }
        return lines;
    }
}
