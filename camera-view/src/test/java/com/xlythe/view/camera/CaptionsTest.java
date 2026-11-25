package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;

/**
 * Writing captions beside a recording.
 *
 * <p>Caption files fail by loading and showing nothing, which is why every byte of the format is
 * asserted here rather than only the round trip. The comma against the full stop in a timestamp is
 * the entire difference between the two formats, and getting it the wrong way round produces a
 * file a player accepts and silently ignores.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class CaptionsTest {
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    /** SubRip: a 1-based index, a comma before the milliseconds, a blank line after each cue. */
    @Test
    public void subRipIsWrittenAsPlayersExpectIt() throws IOException {
        Captions captions = new Captions.Builder()
                .addCue(0, 2_500, "Hello.")
                .addCue(2_500, 4_000, "Is this thing on?")
                .build();

        assertEquals("1\n"
                        + "00:00:00,000 --> 00:00:02,500\n"
                        + "Hello.\n"
                        + "\n"
                        + "2\n"
                        + "00:00:02,500 --> 00:00:04,000\n"
                        + "Is this thing on?\n"
                        + "\n",
                subRip(captions));
    }

    /** WebVTT: the signature line, and a full stop before the milliseconds. */
    @Test
    public void webVttIsWrittenAsBrowsersExpectIt() throws IOException {
        Captions captions = new Captions.Builder()
                .addCue(0, 2_500, "Hello.")
                .build();

        assertEquals("WEBVTT\n"
                        + "\n"
                        + "00:00:00.000 --> 00:00:02.500\n"
                        + "Hello.\n"
                        + "\n",
                webVtt(captions));
    }

    /** The two formats differ in that one character and nothing else. */
    @Test
    public void theTwoFormatsDifferOnlyInTheMillisecondSeparator() {
        assertEquals("01:02:03,004", Captions.timestamp(3_723_004L, ','));
        assertEquals("01:02:03.004", Captions.timestamp(3_723_004L, '.'));
    }

    /** Hours do not wrap, since a recording can run past one. */
    @Test
    public void timestampsCarryHoursAndPadEveryField() {
        assertEquals("00:00:00,000", Captions.timestamp(0, ','));
        assertEquals("00:00:01,000", Captions.timestamp(1_000, ','));
        assertEquals("00:01:00,000", Captions.timestamp(60_000, ','));
        assertEquals("01:00:00,000", Captions.timestamp(3_600_000, ','));
        assertEquals("a long recording keeps counting hours",
                "10:00:00,000", Captions.timestamp(36_000_000L, ','));
        assertEquals("milliseconds are three digits",
                "00:00:00,007", Captions.timestamp(7, ','));
    }

    /** A negative time is clamped rather than written as nonsense. */
    @Test
    public void aNegativeTimeIsClampedToTheStart() {
        assertEquals("00:00:00,000", Captions.timestamp(-5, ','));
    }

    /**
     * A blank line inside a cue would split it into two malformed ones.
     *
     * <p>In SubRip a blank line is what ends a cue, so text arriving with one in it - which
     * happens the moment captions come from anything that formats its output - has to be
     * flattened rather than written through.</p>
     */
    @Test
    public void blankLinesInsideACueAreRemoved() throws IOException {
        Captions captions = new Captions.Builder()
                .addCue(0, 1_000, "First line\n\n\nSecond line")
                .build();

        assertEquals("1\n"
                        + "00:00:00,000 --> 00:00:01,000\n"
                        + "First line\n"
                        + "Second line\n"
                        + "\n",
                subRip(captions));
    }

    /** Windows and old Mac line endings become newlines, so the framing survives either. */
    @Test
    public void carriageReturnsAreNormalised() {
        assertEquals("one\ntwo", Captions.clean("one\r\ntwo"));
        assertEquals("one\ntwo", Captions.clean("one\rtwo"));
        assertEquals("one\ntwo", Captions.clean("  one  \n\n  two  "));
    }

    /** Cues come out in time order, because a later correction can arrive out of order. */
    @Test
    public void cuesAreSortedByTime() {
        Captions captions = new Captions.Builder()
                .addCue(5_000, 6_000, "third")
                .addCue(0, 1_000, "first")
                .addCue(2_000, 3_000, "second")
                .build();

        assertEquals(3, captions.getCues().size());
        assertEquals("first", captions.getCues().get(0).getText());
        assertEquals("second", captions.getCues().get(1).getText());
        assertEquals("third", captions.getCues().get(2).getText());
    }

    /** Overlapping cues are allowed, since two people can talk at once. */
    @Test
    public void cuesMayOverlap() throws IOException {
        Captions captions = new Captions.Builder()
                .addCue(0, 3_000, "Someone talking")
                .addCue(1_000, 4_000, "Someone else")
                .build();

        assertTrue(subRip(captions).contains("00:00:01,000 --> 00:00:04,000"));
        assertEquals(2, captions.size());
    }

    /** A cue that cannot be shown is refused when it is added, not when it is written. */
    @Test
    public void animpossibleCueIsRefused() {
        Captions.Builder builder = new Captions.Builder();

        assertThrows("a cue cannot start before the recording",
                IllegalArgumentException.class, () -> builder.addCue(-1, 1_000, "x"));
        assertThrows("a cue has to end after it starts",
                IllegalArgumentException.class, () -> builder.addCue(1_000, 1_000, "x"));
        assertThrows(IllegalArgumentException.class, () -> builder.addCue(2_000, 1_000, "x"));
        assertThrows("a cue with nothing in it is not a cue",
                IllegalArgumentException.class, () -> builder.addCue(0, 1_000, "   \n  "));
    }

    /** Nothing to say still writes a valid file, which is better than writing none. */
    @Test
    public void noCuesIsStillAValidFile() throws IOException {
        Captions empty = new Captions.Builder().build();

        assertEquals("", subRip(empty));
        assertEquals("the signature line is what makes it a WebVTT file at all",
                "WEBVTT\n\n", webVtt(empty));
    }

    /**
     * The sidecar replaces the extension rather than appending one.
     *
     * <p>Matching the recording's name is what lets a player find it unprompted, and
     * {@code holiday.mp4.srt} does not match.</p>
     */
    @Test
    public void theSidecarTakesTheRecordingsName() {
        File video = new File("/movies/holiday.mp4");

        assertEquals("holiday.srt", Captions.sidecarFor(video, "srt").getName());
        assertEquals("holiday.vtt", Captions.sidecarFor(video, "vtt").getName());
        assertEquals("and sits beside it",
                video.getParent(), Captions.sidecarFor(video, "srt").getParent());
    }

    /** A name with no extension, or with dots in it, still gets one sidecar. */
    @Test
    public void theSidecarCopesWithAwkwardNames() {
        assertEquals("recording.srt",
                Captions.sidecarFor(new File("/movies/recording"), "srt").getName());
        assertEquals("my.holiday.2026.srt",
                Captions.sidecarFor(new File("/movies/my.holiday.2026.mp4"), "srt").getName());
    }

    /** And it really does reach the disk. */
    @Test
    public void theFileIsWritten() throws IOException {
        File video = File.createTempFile("captioned", ".mp4");
        video.deleteOnExit();
        File sidecar = Captions.sidecarFor(video, "srt");
        sidecar.deleteOnExit();

        new Captions.Builder().addCue(0, 1_000, "On disk").build().writeSubRip(sidecar);

        assertTrue(sidecar.exists());
        assertTrue(sidecar.length() > 0);
    }

    private static String subRip(Captions captions) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        captions.writeSubRip(out);
        return new String(out.toByteArray(), UTF_8);
    }

    private static String webVtt(Captions captions) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        captions.writeWebVtt(out);
        return new String(out.toByteArray(), UTF_8);
    }
}
