package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.android.gms.tasks.TaskCompletionSource;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The frame thread does not give a frame back while ML Kit is still reading it.
 *
 * <p>The frame is closed as soon as the scanner returns from it, so these hold the scanner's wait
 * to that: it returns when the scan completes, and not before, whether it is interrupted or the
 * scan is slow.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class BarcodeScanWaitTest {
    /** Long enough that a frame thread still waiting is plainly waiting, not slow to return. */
    private static final long STILL_WAITING_MILLIS = 300;

    /** Longer than any wait here should take once the scan completes. */
    private static final long JOIN_MILLIS = 5_000;

    /** A slow scan's threshold far beyond the test, so only an interrupt can end the wait early. */
    private static final long NEVER_SLOW_MILLIS = 60_000;

    /** A slow scan's threshold the test passes many times over. */
    private static final long SOON_SLOW_MILLIS = 20;

    @Test
    public void anInterruptedWaitHoldsTheFrameUntilTheScanCompletes() throws Exception {
        TaskCompletionSource<String> scan = new TaskCompletionSource<>();
        FrameThread thread = new FrameThread(scan, NEVER_SLOW_MILLIS);
        thread.start();
        awaitWaiting(thread);

        thread.interrupt();
        thread.join(STILL_WAITING_MILLIS);
        assertTrue("the frame is held while the scan runs", thread.isAlive());

        scan.setResult("scanned");
        thread.join(JOIN_MILLIS);
        assertFalse(thread.isAlive());
        assertEquals("scanned", thread.result.get());
        assertTrue("the interrupt is passed on once the scan is done", thread.interruptedAfter.get());
    }

    @Test
    public void aSlowScanIsWaitedOut() throws Exception {
        TaskCompletionSource<String> scan = new TaskCompletionSource<>();
        FrameThread thread = new FrameThread(scan, SOON_SLOW_MILLIS);
        thread.start();

        thread.join(STILL_WAITING_MILLIS);
        assertTrue("the frame is held past the slow-scan threshold", thread.isAlive());

        scan.setResult("scanned");
        thread.join(JOIN_MILLIS);
        assertFalse(thread.isAlive());
        assertEquals("scanned", thread.result.get());
        assertFalse(thread.interruptedAfter.get());
    }

    @Test
    public void aFailedScanReturnsTheFrameWithItsError() throws Exception {
        TaskCompletionSource<String> scan = new TaskCompletionSource<>();
        FrameThread thread = new FrameThread(scan, NEVER_SLOW_MILLIS);
        thread.start();
        awaitWaiting(thread);
        thread.interrupt();

        scan.setException(new IllegalStateException("detector closed"));
        thread.join(JOIN_MILLIS);
        assertFalse(thread.isAlive());
        assertNull(thread.result.get());
        assertTrue(thread.error.get() instanceof ExecutionException);
        assertTrue(thread.interruptedAfter.get());
    }

    /** Waits until the thread is blocked on the scan, so an interrupt lands inside the wait. */
    private static void awaitWaiting(Thread thread) throws InterruptedException {
        long deadline = System.currentTimeMillis() + JOIN_MILLIS;
        while (thread.getState() != Thread.State.TIMED_WAITING
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
    }

    /** Stands in for the frame thread, which closes the frame as soon as the wait returns. */
    private static final class FrameThread extends Thread {
        /** The scan the frame is handed to. */
        private final TaskCompletionSource<String> mScan;
        /** Passed to the wait as its slow-scan threshold. */
        private final long mSlowMillis;
        /** What the scan found, once the wait returns with it. */
        final AtomicReference<String> result = new AtomicReference<>();
        /** What the wait threw, if it threw. */
        final AtomicReference<Exception> error = new AtomicReference<>();
        /** Whether the thread was left interrupted when the wait returned. */
        final AtomicBoolean interruptedAfter = new AtomicBoolean();

        FrameThread(TaskCompletionSource<String> scan, long slowMillis) {
            mScan = scan;
            mSlowMillis = slowMillis;
        }

        @Override
        public void run() {
            try {
                result.set(BarcodeScanner.awaitScan(mScan.getTask(), mSlowMillis));
            } catch (ExecutionException e) {
                error.set(e);
            } finally {
                interruptedAfter.set(Thread.currentThread().isInterrupted());
            }
        }
    }
}
