package com.xlythe.view.camera;

import static com.xlythe.view.camera.MediaFixtures.png;
import static com.xlythe.view.camera.MediaFixtures.webp;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.location.Location;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The metadata writers' temporary copies where java.io.tmpdir cannot be written, as Android 4.2
 * and older leave it. The JVM reads java.io.tmpdir once, unlike Android, which reads it on every
 * File.createTempFile, so these check the folder each write is given; the API 15 emulator checks
 * that ExifInterface then saves.
 */
@RunWith(RobolectricTestRunner.class)
public class TempFilesTest {
    @Rule
    public final TemporaryFolder mFolder = new TemporaryFolder();

    /** java.io.tmpdir as the test found it. */
    private String mTmpdir;

    /** The folder of the files being written. */
    private File mFiles;

    @Before
    public void setUp() throws IOException {
        mTmpdir = System.getProperty("java.io.tmpdir");
        mFiles = mFolder.newFolder("files");
    }

    @After
    public void restoreTheTmpdir() {
        System.setProperty("java.io.tmpdir", mTmpdir);
    }

    /** Points java.io.tmpdir where an app cannot write, as /sdcard is without permission. */
    private String unwritableTmpdir() {
        String missing = new File(mFolder.getRoot(), "sdcard").getPath();
        System.setProperty("java.io.tmpdir", missing);
        return missing;
    }

    @Test
    public void aWritableTmpdirIsUsedAsItIs() throws IOException {
        String tmpdir = mFolder.newFolder("cache").getPath();
        System.setProperty("java.io.tmpdir", tmpdir);
        AtomicReference<String> during = new AtomicReference<>();

        assertNull(TempFiles.directory(mFiles));
        TempFiles.run(mFiles, () -> during.set(System.getProperty("java.io.tmpdir")));

        assertEquals(tmpdir, during.get());
        assertEquals(tmpdir, System.getProperty("java.io.tmpdir"));
    }

    @Test
    public void anUnwritableTmpdirGivesWayToTheFilesFolder() throws IOException {
        String tmpdir = unwritableTmpdir();
        AtomicReference<String> during = new AtomicReference<>();
        AtomicReference<File> copy = new AtomicReference<>();

        assertEquals(mFiles, TempFiles.directory(mFiles));
        TempFiles.run(mFiles, () -> {
            during.set(System.getProperty("java.io.tmpdir"));
            copy.set(File.createTempFile("copy", ".tmp", new File(during.get())));
        });

        assertEquals(mFiles.getPath(), during.get());
        assertEquals(mFiles, copy.get().getParentFile());
        assertEquals(tmpdir, System.getProperty("java.io.tmpdir"));
    }

    @Test
    public void theTmpdirIsPutBackWhenTheWriteFails() {
        String tmpdir = unwritableTmpdir();

        try {
            TempFiles.run(mFiles, () -> {
                throw new IOException("disk full");
            });
            fail("the write's failure was lost");
        } catch (IOException expected) {
            assertEquals("disk full", expected.getMessage());
        }

        assertEquals(tmpdir, System.getProperty("java.io.tmpdir"));
    }

    @Test
    public void anUnsetTmpdirIsLeftUnset() throws IOException {
        System.clearProperty("java.io.tmpdir");
        AtomicReference<String> during = new AtomicReference<>();

        TempFiles.run(mFiles, () -> during.set(System.getProperty("java.io.tmpdir")));

        assertEquals(mFiles.getPath(), during.get());
        assertNull(System.getProperty("java.io.tmpdir"));
    }

    @Test
    public void withNoFolderTheWriteRunsAsBefore() throws IOException {
        String tmpdir = unwritableTmpdir();
        AtomicReference<String> during = new AtomicReference<>();

        assertNull(TempFiles.directory(null));
        TempFiles.run(null, () -> during.set(System.getProperty("java.io.tmpdir")));

        assertEquals(tmpdir, during.get());
    }

    @Test
    public void aJpegSavesWhereTheTmpdirCannotBeWritten() throws IOException {
        File file = new File(mFiles, "picture.jpg");
        Files.write(file.toPath(), ExifFixtures.exifJpeg(false));
        String tmpdir = unwritableTmpdir();

        Exif exif = new Exif(file);
        exif.setTitle("Harbour at dawn");
        exif.save();

        assertEquals("Harbour at dawn", new Exif(file).getTitle());
        assertArrayEquals(new String[] {"picture.jpg"}, mFiles.list());
        assertEquals(tmpdir, System.getProperty("java.io.tmpdir"));
    }

    @Test
    public void aPngIsEditedWhereTheTmpdirCannotBeWritten() throws IOException {
        assertEditedWhereTheTmpdirCannotBeWritten("picture.png", png());
    }

    @Test
    public void aWebpIsEditedWhereTheTmpdirCannotBeWritten() throws IOException {
        assertEditedWhereTheTmpdirCannotBeWritten("picture.webp", webp(4, 3));
    }

    /**
     * Edits the Exif of a format that keeps it as a bare block, which is wrapped in a JPEG of its
     * own to be changed: the wrapping goes beside the file and is gone once the edit is saved.
     */
    private void assertEditedWhereTheTmpdirCannotBeWritten(String name, byte[] bytes)
            throws IOException {
        File file = new File(mFiles, name);
        Files.write(file.toPath(), bytes);
        String tmpdir = unwritableTmpdir();
        Location location = new Location("test");
        location.setLatitude(37.4219);
        location.setLongitude(-122.0840);

        MediaTags tags = MediaTags.open(file);
        assertTrue(tags.setLocation(location));
        String[] during = mFiles.list();
        tags.save();

        Arrays.sort(during);
        assertEquals(2, during.length);
        assertTrue(Arrays.toString(during), during[0].startsWith("mediatags"));
        assertArrayEquals(new String[] {name}, mFiles.list());
        Location read = MediaTags.open(file).getLocation();
        assertEquals(37.4219, read.getLatitude(), 1e-4);
        assertEquals(-122.0840, read.getLongitude(), 1e-4);
        assertEquals(tmpdir, System.getProperty("java.io.tmpdir"));
    }
}
