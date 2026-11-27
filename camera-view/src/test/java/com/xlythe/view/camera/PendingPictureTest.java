package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

/**
 * Reporting a shutter press once, whatever it wrote.
 *
 * <p>RAW_JPEG writes two files on two writers, and the caller hears once: after both, with the
 * file it named, or as a failure that leaves neither behind.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class PendingPictureTest {
    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private File jpeg;
    private File dng;
    private int saved;
    private int failed;
    private File reported;

    private final PendingPicture.Callback callback = new PendingPicture.Callback() {
        @Override
        public void onSaved(@NonNull File file) {
            saved++;
            reported = file;
        }

        @Override
        public void onFailed() {
            failed++;
        }
    };

    @Before
    public void setUp() throws IOException {
        jpeg = folder.newFile("IMG_1.jpg");
        dng = folder.newFile("IMG_1.dng");
    }

    @Test
    public void aSingleFileIsReportedWhenItIsWritten() {
        PendingPicture picture = new PendingPicture(jpeg, callback);

        picture.onWritten(jpeg, true);

        assertEquals(1, saved);
        assertEquals(jpeg, reported);
        assertTrue(picture.isReported());
    }

    /** The caller hears nothing until the second file lands, so both are there when it does. */
    @Test
    public void twoFilesAreReportedAfterTheLastOfThem() {
        PendingPicture picture = new PendingPicture(jpeg, Arrays.asList(jpeg, dng), callback);

        picture.onWritten(dng, true);
        assertEquals("one file is not the picture", 0, saved);
        assertFalse(picture.isReported());

        picture.onWritten(jpeg, true);
        assertEquals(1, saved);
        assertEquals("the file the caller named", jpeg, reported);
        assertEquals(0, failed);
    }

    /** Losing either file fails the press, and the one that did land goes too. */
    @Test
    public void losingOneFileFailsThePressAndLeavesNeither() {
        PendingPicture picture = new PendingPicture(jpeg, Arrays.asList(jpeg, dng), callback);

        picture.onWritten(jpeg, true);
        picture.onWritten(dng, false);

        assertEquals(0, saved);
        assertEquals(1, failed);
        assertFalse(jpeg.exists());
        assertFalse(dng.exists());
    }

    /** A writer and a teardown can both report a file, and the press is still reported once. */
    @Test
    public void aFileReportedTwiceCountsOnce() {
        PendingPicture picture = new PendingPicture(jpeg, Arrays.asList(jpeg, dng), callback);

        picture.onWritten(jpeg, true);
        picture.onWritten(jpeg, false);
        picture.onWritten(dng, true);
        picture.onWritten(dng, false);

        assertEquals(1, saved);
        assertEquals(0, failed);
    }

    /** A file the press does not write is not one it waits for. */
    @Test
    public void aStrangerFileIsIgnored() throws IOException {
        PendingPicture picture = new PendingPicture(jpeg, callback);

        picture.onWritten(folder.newFile("other.jpg"), false);

        assertFalse(picture.isReported());
        assertEquals(0, failed);
        assertNull(reported);
    }

    /** Failing the press fails what is outstanding, once. */
    @Test
    public void failingThePressReportsOnce() {
        PendingPicture picture = new PendingPicture(jpeg, Arrays.asList(jpeg, dng), callback);
        picture.onWritten(jpeg, true);

        picture.fail();
        picture.fail();

        assertEquals(1, failed);
        assertEquals(0, saved);
        assertFalse(jpeg.exists());
    }
}
