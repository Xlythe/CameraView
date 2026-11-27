package com.xlythe.view.camera;

import static com.xlythe.view.camera.ExifFixtures.TAG_UNDEFINED_BY_ANY_STANDARD;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import androidx.exifinterface.media.ExifInterface;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/**
 * The tags Exif saves beyond those ExifInterface names: set by number or through the methods
 * built on them, a file's own kept across saves, and what happens when there is no room.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class ExifTagsSaveTest {
    private static final int TAG_F_NUMBER = 0x829D;
    private static final int TAG_DATETIME_ORIGINAL = 0x9003;
    private static final int TAG_TEMPERATURE = 0x9400;
    private static final int TAG_HUMIDITY = 0x9401;
    private static final int TAG_IMAGE_TITLE = 0xA436;
    private static final int TAG_PHOTOGRAPHER = 0xA437;
    private static final int TAG_COMPOSITE_IMAGE = 0xA460;
    private static final int TAG_SOURCE_EXPOSURE_TIMES = 0xA462;

    private File file;

    @Before
    public void setUp() throws IOException {
        file = File.createTempFile("tags", ".jpg");
        file.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(ExifMetadataTest.minimalJpeg());
        }
    }

    /**
     * Tags set before saving, beside the attributes set with them, are written by the save, into
     * an Exif block the file did not have until then.
     */
    @Test
    public void tagsSetBeforeSavingAreWritten() throws IOException {
        Exif exif = new Exif(file);
        assertTrue(exif.setTag(TAG_UNDEFINED_BY_ANY_STANDARD, Exif.TYPE_ASCII, "From the future"));
        assertTrue(exif.setTag(TAG_HUMIDITY, Exif.TYPE_RATIONAL, "44.5"));
        exif.setDescription("A description");
        exif.save();

        Exif saved = new Exif(file);
        assertEquals("From the future", saved.getTag(TAG_UNDEFINED_BY_ANY_STANDARD));
        assertEquals("44500/1000", saved.getTag(TAG_HUMIDITY));
        assertEquals("A description", saved.getDescription());
    }

    /** Before the save, a tag reads as it was set, in the shape the file will store it in. */
    @Test
    public void aTagReadsAsSetBeforeItIsSaved() throws IOException {
        Exif exif = new Exif(file);
        exif.setTag(TAG_HUMIDITY, Exif.TYPE_RATIONAL, "44.5");

        assertEquals("44500/1000", exif.getTag(TAG_HUMIDITY));
        assertNull("nothing is written until the save", new Exif(file).getTag(TAG_HUMIDITY));
    }

    /** A tag nobody wrote reads as absent. */
    @Test
    public void anUnwrittenTagIsNull() throws IOException {
        new Exif(file).save();

        Exif saved = new Exif(file);
        assertNull(saved.getTag(TAG_UNDEFINED_BY_ANY_STANDARD));
        assertNull(saved.getTagBytes(TAG_UNDEFINED_BY_ANY_STANDARD));
        assertNull(saved.getTitle());
    }

    /**
     * The tags a file arrives with survive a save that never mentions them, a tag no edition of
     * the standard defines among them.
     */
    @Test
    public void aFilesOwnTagsSurviveAPlainSave() throws IOException {
        withPrivateTags(file,
                TAG_UNDEFINED_BY_ANY_STANDARD, Exif.TYPE_ASCII, "Unheard of",
                TAG_PHOTOGRAPHER, Exif.TYPE_ASCII, "A. Photographer",
                TAG_COMPOSITE_IMAGE, Exif.TYPE_SHORT, "2");

        Exif exif = new Exif(file);
        exif.setDescription("Saved again");
        exif.save();

        Exif saved = new Exif(file);
        assertEquals("Unheard of", saved.getTag(TAG_UNDEFINED_BY_ANY_STANDARD));
        assertEquals("A. Photographer", saved.getTag(TAG_PHOTOGRAPHER));
        assertEquals("2", saved.getTag(TAG_COMPOSITE_IMAGE));
        assertEquals("Saved again", saved.getDescription());
    }

    /** A tag no standard defines is set, saved, and then removed again by a later save. */
    @Test
    public void aTagNoStandardDefinesIsSetAndRemoved() throws IOException {
        Exif exif = new Exif(file);
        exif.setTag(TAG_UNDEFINED_BY_ANY_STANDARD, Exif.TYPE_SLONG, "-7,8");
        exif.save();
        assertEquals("-7,8", new Exif(file).getTag(TAG_UNDEFINED_BY_ANY_STANDARD));

        Exif again = new Exif(file);
        assertTrue(again.setTag(TAG_UNDEFINED_BY_ANY_STANDARD, Exif.TYPE_SLONG, null));
        assertNull("gone as soon as it is removed", again.getTag(TAG_UNDEFINED_BY_ANY_STANDARD));
        again.save();

        assertNull(new Exif(file).getTag(TAG_UNDEFINED_BY_ANY_STANDARD));
        assertArrayEquals(new int[0], tagsOf(file, TAG_UNDEFINED_BY_ANY_STANDARD));
    }

    /** A tag the file has and is set again takes the new value, and only once. */
    @Test
    public void aReplacedTagTakesTheNewValue() throws IOException {
        withPrivateTags(file,
                TAG_IMAGE_TITLE, Exif.TYPE_ASCII, "Old title",
                TAG_PHOTOGRAPHER, Exif.TYPE_ASCII, "Kept");

        Exif exif = new Exif(file);
        exif.setTag(TAG_IMAGE_TITLE, Exif.TYPE_ASCII, "New title");
        exif.save();

        Exif saved = new Exif(file);
        assertEquals("New title", saved.getTag(TAG_IMAGE_TITLE));
        assertEquals("Kept", saved.getTag(TAG_PHOTOGRAPHER));
        assertEquals("copies of the new title", 1, occurrences(file, "New title"));
        assertEquals("copies of the old one", 0, occurrences(file, "Old title"));
    }

    /**
     * Saving twice from one Exif, then again from another, leaves one copy of each tag, and the
     * block no bigger than the first save made it.
     */
    @Test
    public void savingTwiceKeepsOneCopyOfEachTag() throws IOException {
        Exif exif = new Exif(file);
        exif.setTitle("Only once");
        exif.setTag(TAG_TEMPERATURE, Exif.TYPE_SRATIONAL, "21.5");
        exif.save();
        int first = ExifSegment.find(readAll(file)).payloadLength;

        exif.save();
        assertTrue("the same Exif saving again",
                ExifSegment.find(readAll(file)).payloadLength <= first);
        new Exif(file).save();

        assertTrue("another Exif saving again",
                ExifSegment.find(readAll(file)).payloadLength <= first);
        assertEquals(1, occurrences(file, "Only once"));
        Exif saved = new Exif(file);
        assertEquals("Only once", saved.getTitle());
        assertEquals("21500/1000", saved.getTag(TAG_TEMPERATURE));
    }

    /** Tags written beside the ones saved as bytes leave those as bytes. */
    @Test
    public void tagsLeaveTheBytesAsBytes() throws IOException {
        Exif exif = new Exif(file);
        exif.attachCaptureDevice(RuntimeEnvironment.getApplication());
        exif.setTitle("A title");
        exif.save();

        Map<Integer, ExifTags.Value> directory = ExifTags.readAll(readAll(file));
        assertEquals(Exif.TYPE_UNDEFINED, directory.get(0x9101).type);
        assertEquals(Exif.TYPE_UNDEFINED, directory.get(0xA300).type);
        assertEquals(Exif.TYPE_UNDEFINED, directory.get(0xA301).type);
        assertEquals("A title", new Exif(file).getTitle());
    }

    /**
     * A tag ExifInterface writes is set and removed by number as any other is, and reads by
     * number as the file stores it.
     */
    @Test
    public void aTagExifInterfaceNamesIsSetAndRemovedByNumber() throws IOException {
        Exif exif = new Exif(file);
        exif.setTag(TAG_F_NUMBER, Exif.TYPE_RATIONAL, "28/10");
        exif.save();

        Exif saved = new Exif(file);
        assertEquals("28/10", saved.getTag(TAG_F_NUMBER));
        assertEquals(2.8, Double.parseDouble(saved.getAttribute(ExifInterface.TAG_F_NUMBER)), 1e-9);
        assertArrayEquals("one entry", new int[] {TAG_F_NUMBER}, tagsOf(file, TAG_F_NUMBER));

        saved.setTag(TAG_F_NUMBER, Exif.TYPE_RATIONAL, null);
        saved.save();

        assertNull(new Exif(file).getAttribute(ExifInterface.TAG_F_NUMBER));
        assertNull(new Exif(file).getTag(TAG_F_NUMBER));
    }

    /**
     * Whichever of a tag's name and number was set last is the one saved, so removing the
     * timestamp after setting one by number leaves no timestamp.
     */
    @Test
    public void theLaterOfANameAndANumberWins() throws IOException {
        Exif exif = new Exif(file);
        exif.setTag(TAG_DATETIME_ORIGINAL, Exif.TYPE_ASCII, "2001:02:03 04:05:06");
        exif.removeTimestamp();
        exif.setTag(TAG_F_NUMBER, Exif.TYPE_RATIONAL, "28/10");
        exif.setAttribute(ExifInterface.TAG_F_NUMBER, "4");
        exif.save();

        Exif saved = new Exif(file);
        assertNull(saved.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL));
        assertNull(saved.getTag(TAG_DATETIME_ORIGINAL));
        assertEquals(4.0, Double.parseDouble(saved.getAttribute(ExifInterface.TAG_F_NUMBER)), 0);
    }

    /** A value that does not suit its type is refused when it is set, and changes nothing. */
    @Test
    public void anUnsuitableValueIsRefusedWhenSet() throws IOException {
        Exif exif = new Exif(file);
        exif.setTag(TAG_COMPOSITE_IMAGE, Exif.TYPE_SHORT, "1");

        assertFalse(exif.setTag(TAG_COMPOSITE_IMAGE, Exif.TYPE_SHORT, "quite a lot"));
        assertFalse("nor a type the standard doesn't define",
                exif.setTag(TAG_COMPOSITE_IMAGE, 99, "1"));
        assertFalse("nor no bytes at all", exif.setTag(TAG_SOURCE_EXPOSURE_TIMES, new byte[0]));
        assertFalse("nor the entry that leads to another directory",
                exif.setTag(0xA005, Exif.TYPE_LONG, "8"));
        assertFalse(exif.setTag(0xA005, new byte[] {1}));
        assertFalse("nor a number no entry can carry",
                exif.setTag(0x10000, Exif.TYPE_SHORT, "1"));
        assertFalse(exif.setTag(-1, Exif.TYPE_SHORT, "1"));

        assertEquals("1", exif.getTag(TAG_COMPOSITE_IMAGE));
    }

    /** Bytes that are not text go in as bytes and come back as the same bytes. */
    @Test
    public void bytesAreSetAndReadAsBytes() throws IOException {
        byte[] exposures = {0, 0, 0, 1, (byte) 0xFF, (byte) 0xFE, (byte) 0x80, 0x7F, 0, 1};
        Exif exif = new Exif(file);
        assertTrue(exif.setTag(TAG_SOURCE_EXPOSURE_TIMES, exposures));
        exif.setTag(TAG_HUMIDITY, Exif.TYPE_RATIONAL, "44.5");
        exif.save();

        Exif saved = new Exif(file);
        assertArrayEquals(exposures, saved.getTagBytes(TAG_SOURCE_EXPOSURE_TIMES));
        assertArrayEquals("numbers come most significant byte first",
                new byte[] {0, 0, (byte) 0xAD, (byte) 0xD4, 0, 0, 3, (byte) 0xE8},
                saved.getTagBytes(TAG_HUMIDITY));

        saved.setTag(TAG_SOURCE_EXPOSURE_TIMES, (byte[]) null);
        saved.save();
        assertNull(new Exif(file).getTagBytes(TAG_SOURCE_EXPOSURE_TIMES));
    }

    /**
     * A tag's standard name reads and writes it whether or not ExifInterface has a name for it,
     * the latter with the type the standard gives it.
     */
    @Test
    public void everyStandardNameReachesItsTag() throws IOException {
        Exif exif = new Exif(file);
        exif.setAttribute("ImageTitle", "By name");
        exif.setAttribute("Temperature", "-3.5");
        exif.setAttribute(ExifInterface.TAG_LENS_SERIAL_NUMBER, "SN-1");
        exif.setAttribute("CompositeImage", "not a number");
        exif.save();

        Exif saved = new Exif(file);
        assertEquals("By name", saved.getTitle());
        assertEquals("By name", saved.getAttribute("ImageTitle"));
        assertEquals("-3500/1000", saved.getAttribute("Temperature"));
        assertEquals(-3.5, saved.getAmbientConditions().getTemperature(), 0);
        assertEquals("SN-1", saved.getAttribute(ExifInterface.TAG_LENS_SERIAL_NUMBER));
        assertNull("a value the type can't hold is ignored, as any name's is",
                saved.getAttribute("CompositeImage"));

        saved.setAttribute("ImageTitle", null);
        saved.save();
        assertNull(new Exif(file).getTitle());
    }

    /** A copy carries the other picture's tags as well as its named attributes. */
    @Test
    public void copyFromCarriesTheTags() throws IOException {
        Exif source = new Exif(file);
        source.setTitle("Copied");
        source.setTag(TAG_UNDEFINED_BY_ANY_STANDARD, Exif.TYPE_SHORT, "9");
        source.setAttribute(ExifInterface.TAG_ARTIST, "Someone");
        File other = File.createTempFile("tags", ".jpg");
        other.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(other)) {
            out.write(ExifMetadataTest.minimalJpeg());
        }

        Exif copy = new Exif(other);
        copy.copyFrom(source);
        copy.save();

        Exif saved = new Exif(other);
        assertEquals("Copied", saved.getTitle());
        assertEquals("9", saved.getTag(TAG_UNDEFINED_BY_ANY_STANDARD));
        assertEquals("Someone", saved.getAttribute(ExifInterface.TAG_ARTIST));
    }

    /** A title round trips, and replaces the one a file already has. */
    @Test
    public void theTitleIsSetAndReplaced() throws IOException {
        Exif exif = new Exif(file);
        exif.setTitle("Harbour at dawn");
        exif.save();
        assertEquals("Harbour at dawn", new Exif(file).getTitle());

        withPrivateTags(file, TAG_IMAGE_TITLE, Exif.TYPE_ASCII, "The camera's title");
        Exif existing = new Exif(file);
        assertEquals("The camera's title", existing.getTitle());
        existing.setTitle("Mine");
        existing.save();

        assertEquals("Mine", new Exif(file).getTitle());
        assertEquals(0, occurrences(file, "The camera's title"));

        Exif removing = new Exif(file);
        removing.setTitle(null);
        removing.save();
        assertNull(new Exif(file).getTitle());
    }

    /** Text beyond ASCII is kept whole, stored as the UTF-8 the standard defines for it. */
    @Test
    public void aTitleBeyondAsciiIsKeptWhole() throws IOException {
        Exif exif = new Exif(file);
        exif.setTitle("Café at noon");
        exif.save();

        assertEquals("Café at noon", new Exif(file).getTitle());
        assertEquals(Exif.TYPE_UTF8,
                ExifTags.readAll(readAll(file)).get(TAG_IMAGE_TITLE).type);
    }

    /** The photographer is written as Photographer and as Artist, which older readers know. */
    @Test
    public void thePhotographerIsWrittenTwice() throws IOException {
        Exif exif = new Exif(file);
        exif.setPhotographer("A. Photographer");
        exif.save();

        Exif saved = new Exif(file);
        assertEquals("A. Photographer", saved.getPhotographer());
        assertEquals("A. Photographer", saved.getTag(TAG_PHOTOGRAPHER));
        assertEquals("A. Photographer", saved.getAttribute(ExifInterface.TAG_ARTIST));
    }

    /** Reading prefers Photographer, and falls back to Artist when that is all there is. */
    @Test
    public void thePhotographerFallsBackToTheArtist() throws IOException {
        ExifInterface older = new ExifInterface(file.getPath());
        older.setAttribute(ExifInterface.TAG_ARTIST, "Only the artist");
        older.saveAttributes();
        assertEquals("Only the artist", new Exif(file).getPhotographer());

        withPrivateTags(file, TAG_PHOTOGRAPHER, Exif.TYPE_ASCII, "The photographer");
        assertEquals("The photographer", new Exif(file).getPhotographer());
    }

    /** A photographer the file has is replaced in both places, and removed from both. */
    @Test
    public void thePhotographerIsReplacedAndRemoved() throws IOException {
        withPrivateTags(file, TAG_PHOTOGRAPHER, Exif.TYPE_ASCII, "Old");
        Exif exif = new Exif(file);
        exif.setPhotographer("New");
        exif.save();
        Exif saved = new Exif(file);
        assertEquals("New", saved.getPhotographer());
        assertEquals("New", saved.getAttribute(ExifInterface.TAG_ARTIST));

        saved.setPhotographer(null);
        saved.save();
        Exif removed = new Exif(file);
        assertNull(removed.getPhotographer());
        assertNull(removed.getAttribute(ExifInterface.TAG_ARTIST));
        assertNull(removed.getTag(TAG_PHOTOGRAPHER));
    }

    /** The copyright round trips, replaces the one a file has, and can be removed. */
    @Test
    public void theCopyrightIsSetReplacedAndRemoved() throws IOException {
        ExifInterface camera = new ExifInterface(file.getPath());
        camera.setAttribute(ExifInterface.TAG_COPYRIGHT, "The camera's notice");
        camera.saveAttributes();
        assertEquals("The camera's notice", new Exif(file).getCopyright());

        Exif exif = new Exif(file);
        exif.setCopyright("2026 Someone");
        exif.save();
        assertEquals("2026 Someone", new Exif(file).getCopyright());
        assertEquals("2026 Someone", new Exif(file).getAttribute(ExifInterface.TAG_COPYRIGHT));

        Exif removing = new Exif(file);
        removing.setCopyright(null);
        removing.save();
        assertNull(new Exif(file).getCopyright());
    }

    /** Every reading of the conditions round trips in the units the methods name. */
    @Test
    public void theConditionsRoundTrip() throws IOException {
        AmbientConditions conditions = new AmbientConditions.Builder()
                .setTemperature(-4.25)
                .setHumidity(61.5)
                .setPressure(1013.25)
                .setWaterDepth(12.5)
                .setAcceleration(980665)
                .setCameraElevationAngle(-30)
                .build();
        Exif exif = new Exif(file);
        assertNull("none recorded yet", exif.getAmbientConditions());
        exif.attachAmbientConditions(conditions);
        exif.save();

        assertEquals(conditions, new Exif(file).getAmbientConditions());
        assertEquals("stored as thousandths", "-4250/1000",
                new Exif(file).getTag(TAG_TEMPERATURE));
    }

    /** Conditions with only some readings record only those, and the rest read as NaN. */
    @Test
    public void partialConditionsRecordOnlyWhatTheyHave() throws IOException {
        Exif exif = new Exif(file);
        exif.attachAmbientConditions(new AmbientConditions.Builder()
                .setTemperature(21.5)
                .setPressure(1009)
                .build());
        exif.save();

        AmbientConditions saved = new Exif(file).getAmbientConditions();
        assertNotNull(saved);
        assertEquals(21.5, saved.getTemperature(), 0);
        assertEquals(1009, saved.getPressure(), 0);
        assertTrue(Double.isNaN(saved.getHumidity()));
        assertTrue(Double.isNaN(saved.getWaterDepth()));
        assertTrue(Double.isNaN(saved.getAcceleration()));
        assertTrue(Double.isNaN(saved.getCameraElevationAngle()));
        assertNull(new Exif(file).getTag(TAG_HUMIDITY));
    }

    /**
     * Attaching conditions replaces those a file already has, the readings left out included,
     * and a reading the file cannot hold is left out rather than kept stale.
     */
    @Test
    public void attachedConditionsReplaceAFilesOwn() throws IOException {
        withPrivateTags(file,
                TAG_TEMPERATURE, Exif.TYPE_SRATIONAL, "30",
                TAG_HUMIDITY, Exif.TYPE_RATIONAL, "80");
        assertEquals(80, new Exif(file).getAmbientConditions().getHumidity(), 0);

        Exif exif = new Exif(file);
        exif.attachAmbientConditions(new AmbientConditions.Builder()
                .setTemperature(18)
                .setPressure(-5)
                .build());
        exif.save();

        AmbientConditions saved = new Exif(file).getAmbientConditions();
        assertEquals(new AmbientConditions.Builder().setTemperature(18).build(), saved);
    }

    /** Removing the conditions removes every reading. */
    @Test
    public void conditionsAreRemoved() throws IOException {
        withPrivateTags(file,
                TAG_TEMPERATURE, Exif.TYPE_SRATIONAL, "30",
                TAG_HUMIDITY, Exif.TYPE_RATIONAL, "80",
                0x9405, Exif.TYPE_SRATIONAL, "-10");

        Exif exif = new Exif(file);
        exif.removeAmbientConditions();
        assertNull(exif.getAmbientConditions());
        exif.save();

        assertNull(new Exif(file).getAmbientConditions());
    }

    /** The summary a log line prints includes the title and the conditions. */
    @Test
    public void theSummaryIncludesTheNewFields() throws IOException {
        Exif exif = new Exif(file);
        exif.setTitle("Summarised");
        exif.setPhotographer("Someone");
        exif.setCopyright("2026 Someone");
        exif.attachAmbientConditions(new AmbientConditions.Builder().setHumidity(40).build());
        exif.save();

        String summary = Exif.describe(file);
        assertTrue(summary, summary.contains("title=Summarised"));
        assertTrue(summary, summary.contains("photographer=Someone"));
        assertTrue(summary, summary.contains("copyright=2026 Someone"));
        assertTrue(summary, summary.contains("AmbientConditions{humidity=40.0}"));
    }

    /**
     * An Exif read from a stream reads the tags a file holds, as the sample's photo info does,
     * though it has no file to save them into.
     */
    @Test
    public void aStreamReadsTheTags() throws IOException {
        Exif exif = new Exif(file);
        exif.setTitle("Through a stream");
        exif.setTag(TAG_UNDEFINED_BY_ANY_STANDARD, Exif.TYPE_SHORT, "3");
        exif.attachAmbientConditions(new AmbientConditions.Builder().setTemperature(5).build());
        exif.save();

        try (FileInputStream in = new FileInputStream(file)) {
            Exif streamed = new Exif(in);
            assertEquals("Through a stream", streamed.getTitle());
            assertEquals("3", streamed.getTag(TAG_UNDEFINED_BY_ANY_STANDARD));
            assertEquals(5, streamed.getAmbientConditions().getTemperature(), 0);
            assertNotNull("and ExifInterface still reads the rest",
                    streamed.getAttribute(ExifInterface.TAG_EXIF_VERSION));
            streamed.setTitle("Nowhere to go");
            assertThrows(IOException.class, streamed::save);
        }
        try (InputStream in = new ByteArrayInputStream(readAll(file))) {
            Exif marked = new Exif(in);
            assertEquals("a stream that can mark is reset", "Through a stream", marked.getTitle());
            assertNotNull(marked.getAttribute(ExifInterface.TAG_EXIF_VERSION));
        }
        try (InputStream in = new OneWayInputStream(readAll(file))) {
            Exif replayed = new Exif(in);
            assertEquals("a stream that can't go back is replayed",
                    "Through a stream", replayed.getTitle());
            assertNotNull(replayed.getAttribute(ExifInterface.TAG_EXIF_VERSION));
        }
    }

    /** A stream that can only go forward, as a network's does. */
    private static final class OneWayInputStream extends InputStream {
        /** What it holds. */
        private final byte[] mBytes;
        /** How much of it has been read. */
        private int mRead;

        OneWayInputStream(byte[] bytes) {
            mBytes = bytes;
        }

        @Override
        public int read() {
            return mRead < mBytes.length ? mBytes[mRead++] & 0xFF : -1;
        }
    }

    /**
     * A PNG keeps none of these tags, and its save says so after saving everything else, rather
     * than leaving the caller to find them missing.
     */
    @Test
    public void aPngSaysItKeptNoTags() throws IOException {
        File png = File.createTempFile("tags", ".png");
        png.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(png)) {
            out.write(ExifMetadataTest.minimalPng());
        }

        Exif exif = new Exif(png);
        assertTrue(exif.setTag(TAG_UNDEFINED_BY_ANY_STANDARD, Exif.TYPE_ASCII, "nowhere"));
        exif.setTitle("nowhere either");
        exif.setDescription("Kept");
        ExifNoRoomException error = assertThrows(ExifNoRoomException.class, exif::save);

        assertArrayEquals(new int[] {TAG_IMAGE_TITLE, TAG_UNDEFINED_BY_ANY_STANDARD},
                error.getTags());
        assertEquals("Kept", new Exif(png).getDescription());
    }

    /**
     * A block with no room left for a tag leaves it out, keeps everything else the save wrote,
     * and says which tag it left out.
     */
    @Test
    public void aBlockWithNoRoomNamesWhatItLeftOut() throws IOException {
        String makerNote = fillBlock(file, 400);
        char[] title = new char[1000];
        Arrays.fill(title, 't');

        Exif exif = new Exif(file);
        exif.setAttribute(ExifInterface.TAG_MAKER_NOTE, makerNote);
        exif.setTitle(new String(title));
        exif.setDescription("Kept");
        ExifNoRoomException error = assertThrows(ExifNoRoomException.class, exif::save);

        assertArrayEquals(new int[] {TAG_IMAGE_TITLE}, error.getTags());
        assertTrue(error.getMessage(), error.getMessage().contains("ImageTitle (0xA436)"));
        Exif saved = new Exif(file);
        assertEquals(makerNote, saved.getAttribute(ExifInterface.TAG_MAKER_NOTE));
        assertEquals("Kept", saved.getDescription());
        assertNull(saved.getTitle());
        assertTrue(ExifSegment.find(readAll(file)).payloadLength <= ExifSegment.MAX_APP1_PAYLOAD);
        assertEquals("it still reads as set, for a later save with room",
                new String(title), exif.getTitle());
    }

    /** Of several tags that do not all fit, the largest are left out and the rest written. */
    @Test
    public void theLargestTagsAreLeftOutFirst() throws IOException {
        String makerNote = fillBlock(file, 700);
        char[] title = new char[1000];
        Arrays.fill(title, 't');

        Exif exif = new Exif(file);
        exif.setAttribute(ExifInterface.TAG_MAKER_NOTE, makerNote);
        exif.setTitle(new String(title));
        exif.setTag(TAG_COMPOSITE_IMAGE, Exif.TYPE_SHORT, "2");
        exif.setPhotographer("Short");
        ExifNoRoomException error = assertThrows(ExifNoRoomException.class, exif::save);

        assertArrayEquals(new int[] {TAG_IMAGE_TITLE}, error.getTags());
        Exif saved = new Exif(file);
        assertEquals("2", saved.getTag(TAG_COMPOSITE_IMAGE));
        assertEquals("Short", saved.getTag(TAG_PHOTOGRAPHER));
        assertNull(saved.getTitle());
    }

    /**
     * A camera's own tag that fits the block as the camera wrote it, but not beside what a
     * capture adds, is named by the save, which keeps everything else. This is the file the
     * capture tests hand each backend.
     */
    @Test
    public void aCamerasOwnTagThatNoLongerFitsIsNamed() throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(ExifFixtures.overfullCapture());
        }
        Exif exif = new Exif(file);
        assertNotNull("the camera's tag reads", exif.getTag(TAG_UNDEFINED_BY_ANY_STANDARD));
        exif.attachTimestamp();
        exif.attachCaptureDevice(RuntimeEnvironment.getApplication());
        exif.attachThumbnail();

        ExifNoRoomException error = assertThrows(ExifNoRoomException.class, exif::save);

        assertArrayEquals(new int[] {TAG_UNDEFINED_BY_ANY_STANDARD}, error.getTags());
        Exif saved = new Exif(file);
        assertNotNull(saved.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL));
        assertNotNull(saved.getAttribute(ExifInterface.TAG_MAKE));
        assertNull(saved.getTag(TAG_UNDEFINED_BY_ANY_STANDARD));
    }

    /** Removing a tag needs no room, so it works in a block that is full. */
    @Test
    public void removingNeedsNoRoom() throws IOException {
        ExifInterface camera = new ExifInterface(file.getPath());
        camera.setAttribute(ExifInterface.TAG_F_NUMBER, "2.8");
        camera.saveAttributes();
        String makerNote = fillBlock(file, 40);

        Exif exif = new Exif(file);
        exif.setAttribute(ExifInterface.TAG_MAKER_NOTE, makerNote);
        exif.setTag(TAG_F_NUMBER, Exif.TYPE_RATIONAL, null);
        exif.save();

        Exif saved = new Exif(file);
        assertNull(saved.getAttribute(ExifInterface.TAG_F_NUMBER));
        assertEquals(makerNote, saved.getAttribute(ExifInterface.TAG_MAKER_NOTE));
    }

    /**
     * A maker note sized to leave the block about {@code left} bytes short of its limit once a
     * save has written it, as a camera's can.
     */
    static String fillBlock(File file, int left) throws IOException {
        new Exif(file).save();
        int used = ExifSegment.find(readAll(file)).payloadLength;
        char[] filler = new char[ExifSegment.MAX_APP1_PAYLOAD - used - left];
        Arrays.fill(filler, 'x');
        return new String(filler);
    }

    /**
     * Writes tags into the file's private directory directly, as a camera that knew them would
     * have, given as {tag, type, text} triples.
     */
    static void withPrivateTags(File file, Object... triples) throws IOException {
        ExifInterface camera = new ExifInterface(file.getPath());
        camera.setAttribute(ExifInterface.TAG_EXIF_VERSION, "0232");
        camera.saveAttributes();
        byte[] jpeg = readAll(file);
        Map<Integer, ExifTags.Value> values = new TreeMap<>();
        for (int i = 0; i < triples.length; i += 3) {
            ExifTags.Value value =
                    ExifTags.encode((Integer) triples[i + 1], (String) triples[i + 2]);
            assertNotNull("the fixture's value should encode", value);
            values.put((Integer) triples[i], value);
        }
        byte[] updated = ExifTags.write(jpeg, values);
        assertNotNull("the fixture should take the tags", updated);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(updated);
        }
    }

    /** The entries of the file's private directory for the given tag, one per copy. */
    private static int[] tagsOf(File file, int tag) throws IOException {
        int count = 0;
        for (int each : ExifFixtures.privateTags(readAll(file))) {
            if (each == tag) count++;
        }
        int[] tags = new int[count];
        Arrays.fill(tags, tag);
        return tags;
    }

    /** How many times some text appears in the file's Exif block. */
    private static int occurrences(File file, String text) throws IOException {
        byte[] jpeg = readAll(file);
        byte[] wanted = text.getBytes(StandardCharsets.UTF_8);
        ExifSegment.Segment segment = ExifSegment.find(jpeg);
        assertNotNull(segment);
        int count = 0;
        for (int at = segment.tiffOffset; at + wanted.length <= segment.end(); at++) {
            if (Arrays.equals(wanted, Arrays.copyOfRange(jpeg, at, at + wanted.length))) {
                count++;
            }
        }
        return count;
    }

    private static byte[] readAll(File file) throws IOException {
        return Files.readAllBytes(file.toPath());
    }
}
