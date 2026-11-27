package com.xlythe.view.camera.shadow;

import android.media.MediaCodec;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowMediaCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A codec whose start() takes as long as a test wants, and which remembers the order of its
 * lifecycle calls.
 *
 * <p>Robolectric's codec starts instantly and keeps no record of stop() or release(), so without
 * this nothing can stand in the window where a real encoder is still starting on one thread while
 * another tries to stop it.</p>
 */
@Implements(MediaCodec.class)
public class ShadowSlowStartingMediaCodec extends ShadowMediaCodec {
    /** Counted down when a start() begins, so a test knows the codec is mid-start. */
    private static CountDownLatch sStarting = new CountDownLatch(1);
    /** Holds every start() until the test counts it down. */
    private static CountDownLatch sMayFinishStarting = new CountDownLatch(1);
    /** "started", "stopped" and "released", in the order the codec saw them. */
    private static final List<String> sEvents = new ArrayList<>();

    @Implementation
    protected void native_start() {
        sStarting.countDown();
        try {
            sMayFinishStarting.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        super.native_start();
        record("started");
    }

    @Implementation
    protected void native_stop() {
        record("stopped");
    }

    @Implementation
    protected void native_release() {
        record("released");
    }

    /** Waits for a start() to begin, returning false if none did in time. */
    public static boolean awaitStarting(long timeout, TimeUnit unit) throws InterruptedException {
        return sStarting.await(timeout, unit);
    }

    /** Lets every start(), current and future, finish. */
    public static void finishStarting() {
        sMayFinishStarting.countDown();
    }

    /** The lifecycle calls so far, in order. */
    public static List<String> getEvents() {
        synchronized (sEvents) {
            return new ArrayList<>(sEvents);
        }
    }

    /**
     * Holds starts again and forgets the calls. Statics outlive the test that set them, so a test
     * that uses this shadow calls it first.
     */
    public static void reset() {
        sStarting = new CountDownLatch(1);
        sMayFinishStarting = new CountDownLatch(1);
        synchronized (sEvents) {
            sEvents.clear();
        }
    }

    private static void record(String event) {
        synchronized (sEvents) {
            sEvents.add(event);
        }
    }
}
