package com.xlythe.view.camera;

import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Captions for a recording, written beside it after the fact.
 *
 * <p>Beside it rather than inside it, because the platform cannot put them inside. MediaMuxer
 * writes audio and video tracks, and from API 26 a metadata track whose type begins with
 * {@code application/} - it has no subtitle or timed-text track at all, and MediaRecorder, which
 * is what two of the three backends record with, has no caption API whatever. So a caption track
 * a player would render cannot be muxed into an MP4 with what Android provides.</p>
 *
 * <p>A sidecar is the other way, and it is the one that works everywhere: a file of the same name
 * with a different extension, which every desktop player and most mobile ones pick up
 * automatically. Nothing is re-encoded and the recording is not touched, so adding captions costs
 * no quality and cannot fail halfway through and leave a damaged video.</p>
 *
 * <p>Nothing here draws. Captions are not burned into the frames - that would mean decoding,
 * compositing and re-encoding every frame, which throws away quality to produce something that
 * cannot be turned off again.</p>
 */
public final class Captions {
    /** Both formats count from the start of the recording. */
    private static final long MILLIS_PER_HOUR = 3_600_000L;
    private static final long MILLIS_PER_MINUTE = 60_000L;
    private static final long MILLIS_PER_SECOND = 1_000L;

    /** What both formats are written as, and what players expect to read. */
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private final List<Cue> mCues;

    private Captions(@NonNull List<Cue> cues) {
        mCues = Collections.unmodifiableList(new ArrayList<>(cues));
    }

    /** The cues, in the order they are shown. */
    @NonNull
    public List<Cue> getCues() {
        return mCues;
    }

    /** One line of captioning, and when it is on screen. */
    public static final class Cue {
        private final long mStartMs;
        private final long mEndMs;
        private final String mText;

        Cue(long startMs, long endMs, @NonNull String text) {
            mStartMs = startMs;
            mEndMs = endMs;
            mText = text;
        }

        /** Milliseconds from the start of the recording. */
        public long getStartMs() {
            return mStartMs;
        }

        /** Milliseconds from the start of the recording, after {@link #getStartMs}. */
        public long getEndMs() {
            return mEndMs;
        }

        /** What to show. May contain line breaks; never a blank line. */
        @NonNull
        public String getText() {
            return mText;
        }
    }

    public static final class Builder {
        private final List<Cue> mCues = new ArrayList<>();

        /**
         * Adds a cue.
         *
         * <p>Cues may overlap, since two people can talk at once, but each has to end after it
         * starts and none can start before the recording does.</p>
         *
         * @param text shown while the cue is on screen; blank lines are removed, because a blank
         *     line is what ends a cue in a SubRip file and one inside the text would split it in
         *     two
         */
        @NonNull
        public Builder addCue(long startMs, long endMs, @NonNull String text) {
            if (startMs < 0) {
                throw new IllegalArgumentException("A cue cannot start before the recording: "
                        + startMs);
            }
            if (endMs <= startMs) {
                throw new IllegalArgumentException("A cue has to end after it starts: "
                        + startMs + " to " + endMs);
            }
            String cleaned = clean(text);
            if (cleaned.isEmpty()) {
                throw new IllegalArgumentException("A cue with nothing to show is not a cue");
            }
            mCues.add(new Cue(startMs, endMs, cleaned));
            return this;
        }

        /**
         * @return the captions, with the cues in time order
         */
        @NonNull
        public Captions build() {
            List<Cue> ordered = new ArrayList<>(mCues);
            // Sorted here rather than demanded of the caller: speech recognition reports what it
            // settles on, and a later correction can arrive out of order.
            Collections.sort(ordered, (left, right) ->
                    Long.compare(left.getStartMs(), right.getStartMs()));
            return new Captions(ordered);
        }
    }

    /**
     * Normalises a caption's text.
     *
     * <p>Line endings become newlines and blank lines go, since a blank line terminates a cue in
     * SubRip and one left inside the text would break the file into two malformed cues.</p>
     */
    @NonNull
    static String clean(@NonNull String text) {
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder cleaned = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (cleaned.length() > 0) {
                cleaned.append('\n');
            }
            cleaned.append(trimmed);
        }
        return cleaned.toString();
    }

    /**
     * Writes a SubRip file, which is the format the most players read.
     *
     * @param file overwritten if it exists
     */
    public void writeSubRip(@NonNull File file) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            writeSubRip(out);
        }
    }

    /** @see #writeSubRip(File) */
    public void writeSubRip(@NonNull OutputStream out) throws IOException {
        Writer writer = new OutputStreamWriter(out, UTF_8);
        int index = 1;
        for (Cue cue : mCues) {
            writer.write(Integer.toString(index++));
            writer.write("\n");
            writer.write(timestamp(cue.getStartMs(), ','));
            writer.write(" --> ");
            writer.write(timestamp(cue.getEndMs(), ','));
            writer.write("\n");
            writer.write(cue.getText());
            // A blank line ends a cue, so every cue is followed by one, including the last.
            writer.write("\n\n");
        }
        writer.flush();
    }

    /**
     * Writes a WebVTT file, which is what a browser reads.
     *
     * @param file overwritten if it exists
     */
    public void writeWebVtt(@NonNull File file) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            writeWebVtt(out);
        }
    }

    /** @see #writeWebVtt(File) */
    public void writeWebVtt(@NonNull OutputStream out) throws IOException {
        Writer writer = new OutputStreamWriter(out, UTF_8);
        // The signature line, without which nothing will read the file at all.
        writer.write("WEBVTT\n\n");
        for (Cue cue : mCues) {
            writer.write(timestamp(cue.getStartMs(), '.'));
            writer.write(" --> ");
            writer.write(timestamp(cue.getEndMs(), '.'));
            writer.write("\n");
            writer.write(cue.getText());
            writer.write("\n\n");
        }
        writer.flush();
    }

    /**
     * A time in the form both formats want, differing only in what separates the milliseconds.
     *
     * <p>SubRip uses a comma and WebVTT a full stop, which is the entire difference between their
     * timestamps - and getting it the wrong way round produces a file that loads and shows
     * nothing.</p>
     */
    @NonNull
    static String timestamp(long millis, char millisSeparator) {
        long remaining = Math.max(0, millis);
        long hours = remaining / MILLIS_PER_HOUR;
        remaining -= hours * MILLIS_PER_HOUR;
        long minutes = remaining / MILLIS_PER_MINUTE;
        remaining -= minutes * MILLIS_PER_MINUTE;
        long seconds = remaining / MILLIS_PER_SECOND;
        remaining -= seconds * MILLIS_PER_SECOND;
        return String.format(Locale.US, "%02d:%02d:%02d%c%03d",
                hours, minutes, seconds, millisSeparator, remaining);
    }

    /**
     * The sidecar beside a recording: its name, with this extension instead.
     *
     * <p>Same name is what makes a player find it without being told, so this replaces the
     * extension rather than appending one - {@code holiday.mp4} becomes {@code holiday.srt}, not
     * {@code holiday.mp4.srt}.</p>
     *
     * @param extension without a leading dot, such as {@code srt} or {@code vtt}
     */
    @NonNull
    public static File sidecarFor(@NonNull File video, @NonNull String extension) {
        String name = video.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return new File(video.getParentFile(), stem + "." + extension);
    }

    /** @hide */
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    public int size() {
        return mCues.size();
    }
}
