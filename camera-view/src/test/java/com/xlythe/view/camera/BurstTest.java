package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.annotation.NonNull;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * What a burst hands out and when it is over, apart from any camera.
 *
 * <p>Every backend keeps a different number of pictures in flight, so the property that matters
 * is the accounting: every file handed out is reported once, the end waits for the last of them,
 * and the app hears it exactly once whatever the camera does.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class BurstTest {
    private File directory;
    private final Heard heard = new Heard();

    /** Everything the callback heard, in order. */
    private static final class Heard implements Burst.Callback {
        final List<File> pictures = new ArrayList<>();
        int finishes;

        @Override
        public void onPictureTaken(@NonNull File file) {
            pictures.add(file);
        }

        @Override
        public void onBurstFinished() {
            finishes++;
        }
    }

    @Before
    public void setUp() {
        directory = new File(RuntimeEnvironment.getApplication().getCacheDir(), "burst-test");
        assertTrue(directory.isDirectory() || directory.mkdirs());
    }

    @Test
    public void filesAreNamedForTheBurstAndTheirPlaceInIt() {
        Burst burst = new Burst(directory, "2026-11-26-10-00-00-000", 3, heard);

        assertEquals(new File(directory, "2026-11-26-10-00-00-000_001.jpg"), burst.nextFile());
        assertEquals(new File(directory, "2026-11-26-10-00-00-000_002.jpg"), burst.nextFile());
    }

    @Test
    public void noMoreFilesAreHandedOutPastTheCount() {
        Burst burst = new Burst(directory, "count", 2, heard);

        assertNotNull(burst.nextFile());
        assertFalse(burst.isEnded());
        assertNotNull(burst.nextFile());

        assertTrue("the last file ends the burst", burst.isEnded());
        assertNull(burst.nextFile());
    }

    @Test
    public void picturesAskedForBeforeTheEndStillLand() throws IOException {
        Burst burst = new Burst(directory, "end", 10, heard);
        File first = write(burst.nextFile());
        File second = write(burst.nextFile());

        burst.end();
        assertNull("an ended burst hands out nothing more", burst.nextFile());
        assertFalse("but it is not over while two pictures are owed", burst.isFinished());

        burst.onPictureTaken(first);
        assertFalse(burst.isFinished());
        burst.onPictureTaken(second);

        assertTrue(burst.isFinished());
        assertEquals(List.of(first, second), heard.pictures);
        assertEquals("the end is reported once", 1, heard.finishes);
    }

    @Test
    public void endingWithNothingOwedFinishesAtOnce() {
        Burst burst = new Burst(directory, "idle", 3, heard);

        burst.end();

        assertTrue(burst.isFinished());
        assertEquals(1, heard.finishes);
        burst.end();
        assertEquals("ending twice reports once", 1, heard.finishes);
    }

    @Test
    public void aFailureEndsTheBurstAndLeavesNoFile() throws IOException {
        Burst burst = new Burst(directory, "fail", 10, heard);
        File failed = write(burst.nextFile());
        File landing = write(burst.nextFile());

        burst.onPictureFailed(failed);

        assertFalse("a failed picture leaves nothing behind", failed.exists());
        assertTrue("and ends the burst", burst.isEnded());
        assertNull(burst.nextFile());
        assertFalse("the picture still in flight is waited for", burst.isFinished());

        burst.onPictureTaken(landing);
        assertTrue(burst.isFinished());
        assertEquals(List.of(landing), heard.pictures);
    }

    @Test
    public void whatTheBackendUndoesRunsOnceTheBurstFinishes() throws IOException {
        Burst burst = new Burst(directory, "cleanup", 1, heard);
        int[] runs = {0};
        burst.whenFinished(() -> runs[0]++);
        File only = write(burst.nextFile());

        assertEquals("not while a picture is owed", 0, runs[0]);
        burst.onPictureTaken(only);
        assertEquals(1, runs[0]);

        burst.whenFinished(() -> runs[0]++);
        assertEquals("and at once if it already has", 2, runs[0]);
    }

    @Test
    public void aCameraThatStopsAnsweringFinishesTheBurst() throws IOException {
        Burst burst = new Burst(directory, "stall", 10, heard);
        File late = burst.nextFile();
        assertNotNull(late);

        ShadowLooper.idleMainLooper(Burst.STALL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

        assertTrue("a burst does not wait forever", burst.isFinished());
        assertEquals(1, heard.finishes);

        write(late);
        burst.onPictureTaken(late);
        assertTrue("a picture landing after the app heard the count is not kept",
                heard.pictures.isEmpty());
        assertFalse(late.exists());
    }

    @Test
    public void eachPictureRestartsTheWait() throws IOException {
        Burst burst = new Burst(directory, "slow", 10, heard);
        File first = write(burst.nextFile());
        burst.nextFile();

        ShadowLooper.idleMainLooper(Burst.STALL_TIMEOUT_MILLIS - 1, TimeUnit.MILLISECONDS);
        burst.onPictureTaken(first);
        ShadowLooper.idleMainLooper(Burst.STALL_TIMEOUT_MILLIS - 1, TimeUnit.MILLISECONDS);

        assertFalse("a slow camera that is still answering is waited for", burst.isFinished());
    }

    @Test
    public void aBurstTakesAtLeastOnePicture() {
        try {
            new Burst(directory, "none", 0, heard);
            fail("expected a count of zero to be refused");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    @Test
    public void picturesAreSavedInTheOrderTheyWereTaken() throws Exception {
        Burst burst = new Burst(directory, "order", 3, heard);
        List<Integer> order = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(3);
        for (int i = 0; i < 3; i++) {
            int index = i;
            burst.getExecutor().execute(() -> {
                synchronized (order) {
                    order.add(index);
                }
                done.countDown();
            });
        }

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(List.of(0, 1, 2), order);
    }

    private static File write(File file) throws IOException {
        assertNotNull(file);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(new byte[] {1, 2, 3});
        }
        return file;
    }
}
