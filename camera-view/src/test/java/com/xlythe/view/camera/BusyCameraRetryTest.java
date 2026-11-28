package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** When a busy camera is tried again, and when the trying stops. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class BusyCameraRetryTest {
    /** The retry under test, posting to the main looper the test drives. */
    private final BusyCameraRetry retry = new BusyCameraRetry(new Handler(Looper.getMainLooper()));

    /** Each wait doubles up to its cap, and the last is cut short to land on the window's end. */
    @Test
    public void triesBackOffAndEndWithTheWindow() {
        long start = SystemClock.uptimeMillis();
        List<Long> triedAt = new ArrayList<>();
        Runnable[] open = new Runnable[1];
        open[0] = () -> {
            triedAt.add(SystemClock.uptimeMillis() - start);
            // Refused again, as a camera that is never let go would be.
            retry.retry(open[0]);
        };

        assertTrue(retry.retry(open[0]));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5 * BusyCameraRetry.WINDOW_MILLIS));

        assertEquals(Arrays.asList(100L, 300L, 700L, 1_200L, 1_700L, 2_000L), triedAt);
        assertFalse("nothing is left waiting once the window is out", retry.isPending());
    }

    /** A camera that opens, closes or is swapped starts the next refusal with a fresh window. */
    @Test
    public void cancellingDropsTheTryAndStartsAfresh() {
        int[] tries = new int[1];
        Runnable open = () -> tries[0]++;

        assertTrue(retry.retry(open));
        assertTrue(retry.isPending());
        retry.cancel();
        assertFalse(retry.isPending());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(BusyCameraRetry.WINDOW_MILLIS));
        assertEquals("a cancelled try never runs", 0, tries[0]);

        assertTrue("the window starts again", retry.retry(open));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(BusyCameraRetry.FIRST_DELAY_MILLIS));
        assertEquals(1, tries[0]);
    }
}
