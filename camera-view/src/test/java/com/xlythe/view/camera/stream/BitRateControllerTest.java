package com.xlythe.view.camera.stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The congestion controller, driven by a simulated link.
 *
 * <p>The simulation is the stream as VideoRecorder runs it: an encoder producing the controller's
 * bit rate at its frame rate into a pipe that drops frames past a quarter second of backlog, and a
 * reader taking bytes out at the link's rate. Its clock is simulated, so a minute of congestion
 * runs in milliseconds and comes out the same everywhere.</p>
 */
public class BitRateControllerTest {
    /** The Wear sample's bit rate, which is a typical ceiling. */
    private static final int CEILING = 1_000_000;

    /** The library's floor. */
    private static final int FLOOR = BitRateController.VIDEO_FLOOR_BIT_RATE;

    /** What the recorder offers from API 29: half the frames, then half the area twice. */
    private static final List<BitRateController.Level> LADDER = Arrays.asList(
            new BitRateController.Level(0.5f, 1),
            new BitRateController.Level(0.5f, 0.5f),
            new BitRateController.Level(0.5f, 0.25f));

    /** A stream crossing a link, a frame at a time. */
    private static final class Link {
        final BitRateController controller;

        /** What the link carries now. */
        int bitsPerSecond;

        /** Frames a second at full quality. */
        final int frameRate = 30;

        /** Bytes waiting in the pipe. */
        double queued;

        /** Bytes the reader has taken. */
        double delivered;

        /** Simulated time. */
        long now;

        /** The time of every level change, and the level it went to. */
        final List<long[]> levelChanges = new ArrayList<>();

        /** The lowest bit rate seen while at full quality. */
        int lowestAtFullQuality = Integer.MAX_VALUE;

        Link(BitRateController controller, int bitsPerSecond) {
            this.controller = controller;
            this.bitsPerSecond = bitsPerSecond;
        }

        /** Runs the stream for a while, and returns the lowest bit rate it reached. */
        int run(long millis) {
            long end = now + millis;
            int lowest = Integer.MAX_VALUE;
            while (now < end) {
                BitRateController.Level level = controller.getLevel();
                double fps = frameRate * level.getFrameRateScale();
                long interval = Math.round(1000 / fps);
                now += interval;

                double drain = bitsPerSecond / 8.0 * interval / 1000;
                double read = Math.min(queued, drain);
                queued -= read;
                delivered += read;
                controller.onDelivered(now, (long) delivered);

                // As the recorder does: the queue is looked at before the frame joins it.
                int bitRate = controller.getBitRate();
                int threshold = VideoRecorder.backlogBytesFor(bitRate, 250);
                controller.onQueue(now, (int) queued, threshold);
                if (queued > threshold) {
                    controller.onDropped();
                } else {
                    queued += bitRate / 8.0 / fps;
                }
                int before = controller.getLevelIndex();
                controller.update(now);
                if (controller.getLevelIndex() != before) {
                    levelChanges.add(new long[] {now, controller.getLevelIndex()});
                }
                lowest = Math.min(lowest, controller.getBitRate());
                if (controller.getLevelIndex() == 0) {
                    lowestAtFullQuality = Math.min(lowestAtFullQuality, controller.getBitRate());
                }
            }
            return lowest;
        }

        /** Runs until the bit rate is at most a value, and returns how long that took. */
        long runUntilAtMost(int bitRate, long limit) {
            long start = now;
            while (controller.getBitRate() > bitRate && now - start < limit) {
                run(10);
            }
            return now - start;
        }
    }

    private static BitRateController video() {
        return BitRateController.withLevels(CEILING, FLOOR, LADDER);
    }

    /** A link with room to spare is left alone. */
    @Test
    public void aLinkThatKeepsUpIsLeftAlone() {
        Link link = new Link(video(), 2_000_000);

        int lowest = link.run(20_000);

        assertEquals(CEILING, lowest);
        assertEquals(0, link.controller.getLevelIndex());
    }

    /**
     * When the link drops below the stream, the rate goes straight to just under what the reader
     * managed, in well under a second, rather than stepping down a little at a time.
     */
    @Test
    public void itCutsFastToUnderTheLinkRate() {
        Link link = new Link(video(), 2_000_000);
        link.run(5_000);

        link.bitsPerSecond = 400_000;
        long took = link.runUntilAtMost(400_000, 5_000);

        assertTrue("took " + took + "ms to get under the link", took <= 1_000);
        assertTrue("cut to " + link.controller.getBitRate(),
                link.controller.getBitRate() >= 400_000 * 0.6);
    }

    /**
     * Once the link recovers, the rate climbs back by a few percent a second, so it reaches the
     * ceiling again over tens of seconds rather than overshooting the link straight away.
     */
    @Test
    public void itRaisesSlowlyOnceTheLinkRecovers() {
        Link link = new Link(video(), 2_000_000);
        link.run(5_000);
        link.bitsPerSecond = 400_000;
        link.run(5_000);
        int congested = link.controller.getBitRate();

        link.bitsPerSecond = 2_000_000;
        link.run(1_000);
        assertTrue("held after the cut", link.controller.getBitRate() <= congested * 1.1);
        link.run(4_000);
        int afterFive = link.controller.getBitRate();
        assertTrue("five seconds should be at most about 1.08^5: " + congested + " -> " + afterFive,
                afterFive <= congested * Math.pow(BitRateController.INCREASE_PER_SECOND, 5) * 1.05);
        assertTrue("but it is climbing: " + congested + " -> " + afterFive,
                afterFive > congested * 1.2);

        link.run(40_000);
        assertEquals(CEILING, link.controller.getBitRate());
    }

    /** A link that settles between the floor and the ceiling is tracked from just under it. */
    @Test
    public void itSettlesJustUnderASteadyLink() {
        Link link = new Link(video(), 600_000);

        link.run(60_000);
        long[] rates = new long[100];
        for (int i = 0; i < rates.length; i++) {
            link.run(100);
            rates[i] = link.controller.getBitRate();
        }

        long sum = 0;
        for (long rate : rates) {
            sum += rate;
        }
        double average = sum / (double) rates.length;
        assertTrue("averaged " + average, average > 600_000 * 0.6 && average < 600_000 * 1.1);
        assertEquals(0, link.controller.getLevelIndex());
    }

    /**
     * Below the floor the bit rate stops, and the stream gives up frames before pixels: the first
     * step halves the frame rate and keeps the size.
     */
    @Test
    public void framesGoBeforePixels() {
        Link link = new Link(video(), 2_000_000);
        link.run(5_000);

        link.bitsPerSecond = 30_000;
        int lowest = link.run(30_000);

        assertTrue("the bit rate never goes under the lowest level's floor: " + lowest,
                lowest >= FLOOR * 0.5 * 0.25 - 1);
        List<Integer> order = new ArrayList<>();
        for (long[] change : link.levelChanges) {
            order.add((int) change[1]);
        }
        assertEquals(Arrays.asList(1, 2, 3), order);
        BitRateController.Level first = LADDER.get(0);
        assertEquals(0.5f, first.getFrameRateScale(), 0);
        assertEquals("the first step keeps every pixel", 1f, first.getAreaScale(), 0);
        for (int i = 1; i < link.levelChanges.size(); i++) {
            assertTrue("steps are seconds apart",
                    link.levelChanges.get(i)[0] - link.levelChanges.get(i - 1)[0]
                            >= BitRateController.DEGRADE_AFTER_MILLIS);
        }
    }

    /** At full quality the rate never goes below the floor, however bad the link. */
    @Test
    public void theFloorHoldsAtFullQuality() {
        Link link = new Link(video(), 2_000_000);
        link.run(5_000);

        link.bitsPerSecond = 50_000;
        link.run(30_000);

        assertEquals(FLOOR, link.lowestAtFullQuality);
        assertTrue("and then gave up frames", link.controller.getLevelIndex() > 0);
        assertTrue("the estimate went below it", link.controller.getEstimate() < FLOOR);
    }

    /** A link that carries the floor after all is not given up on, even after a deep cut. */
    @Test
    public void aLinkThatCarriesTheFloorKeepsFullQuality() {
        Link link = new Link(video(), 2_000_000);
        link.run(5_000);

        link.bitsPerSecond = 180_000;
        link.run(60_000);

        assertEquals(0, link.controller.getLevelIndex());
        assertTrue(link.levelChanges.isEmpty());
    }

    /** Restoring goes back up the ladder in reverse, pixels first, seconds apart. */
    @Test
    public void itRestoresInReverse() {
        Link link = new Link(video(), 2_000_000);
        link.run(5_000);
        link.bitsPerSecond = 30_000;
        link.run(30_000);
        assertEquals(3, link.controller.getLevelIndex());
        link.levelChanges.clear();

        link.bitsPerSecond = 3_000_000;
        link.run(120_000);

        List<Integer> order = new ArrayList<>();
        for (long[] change : link.levelChanges) {
            order.add((int) change[1]);
        }
        assertEquals(Arrays.asList(2, 1, 0), order);
        for (int i = 1; i < link.levelChanges.size(); i++) {
            assertTrue(link.levelChanges.get(i)[0] - link.levelChanges.get(i - 1)[0]
                    >= BitRateController.RESTORE_AFTER_MILLIS);
        }
        assertEquals(CEILING, link.controller.getBitRate());
    }

    /**
     * A level restored onto a link that cannot carry it is taken away again, and the next try
     * waits twice as long, so a marginal link does not hitch every few seconds.
     */
    @Test
    public void aFailedRestoreWaitsLonger() {
        Link link = new Link(video(), 2_000_000);
        link.run(5_000);
        link.bitsPerSecond = 100_000;
        link.run(10_000);
        assertEquals(1, link.controller.getLevelIndex());

        // Long enough at a rate that looks like room for full quality, then back down.
        link.bitsPerSecond = 400_000;
        long restoredAt = waitForLevel(link, 0, 60_000);
        link.bitsPerSecond = 100_000;
        long degradedAt = waitForLevel(link, 1, 30_000);
        assertTrue("taken away within the trial",
                degradedAt - restoredAt < BitRateController.RESTORE_TRIAL_MILLIS);

        link.bitsPerSecond = 400_000;
        long retriedAt = waitForLevel(link, 0, 120_000);
        assertTrue("the second try waited " + (retriedAt - degradedAt) + "ms",
                retriedAt - degradedAt >= 2 * BitRateController.RESTORE_AFTER_MILLIS);
    }

    private static long waitForLevel(Link link, int level, long limit) {
        long start = link.now;
        while (link.controller.getLevelIndex() != level && link.now - start < limit) {
            link.run(10);
        }
        assertEquals("never reached level " + level, level, link.controller.getLevelIndex());
        return link.now;
    }

    /**
     * Acknowledgements that come back later and later mean a queue is growing past the pipe, in
     * the transport, and that alone is a reason to cut.
     */
    @Test
    public void growingAcknowledgementDelayIsCongestion() {
        BitRateController controller = BitRateController.forBitRate(CEILING, FLOOR);
        long now = 0;
        for (int i = 0; i < 50; i++) {
            now += 100;
            controller.onQueue(now, 0, 32_000);
            controller.onDelay(now, 50);
            controller.update(now);
        }
        assertEquals(CEILING, controller.getBitRate());

        now += 100;
        controller.onQueue(now, 0, 32_000);
        controller.onDelay(now, 50 + BitRateController.OVERUSE_DELAY_MILLIS + 1);
        controller.update(now);

        assertTrue(controller.getBitRate() < CEILING);
    }

    /** A delay that is high but not growing is the link's own, and does not hold the rate back. */
    @Test
    public void aSteadyDelayIsNotCongestion() {
        BitRateController controller = BitRateController.forBitRate(CEILING, FLOOR);
        long now = 0;
        controller.onQueue(now, 0, 32_000);
        controller.update(now);
        controller.onDropped();
        controller.update(now += 100);
        int cut = controller.getBitRate();

        for (int i = 0; i < 100; i++) {
            now += 100;
            controller.onQueue(now, 0, 32_000);
            controller.onDelay(now, 400);
            controller.update(now);
        }

        assertTrue(controller.getBitRate() > cut);
    }

    /** The quickest acknowledgement is forgotten after a while, so the baseline can rise. */
    @Test
    public void theDelayBaselineMovesOn() {
        BitRateController controller = BitRateController.forBitRate(CEILING, FLOOR);
        controller.onDelay(0, 10);
        controller.onDelay(BitRateController.MIN_DELAY_WINDOW_MILLIS + 1, 500);
        controller.onQueue(0, 0, 32_000);
        controller.onQueue(BitRateController.QUEUE_WINDOW_MILLIS, 0, 32_000);

        controller.update(BitRateController.MIN_DELAY_WINDOW_MILLIS + 1);

        assertEquals("500ms is now the baseline, so nothing is queued", CEILING,
                controller.getBitRate());
    }

    /** Without a delivery rate to go by, a cut takes the estimate itself down a step. */
    @Test
    public void withoutAMeasuredRateACutIsAStep() {
        BitRateController controller = BitRateController.forBitRate(CEILING, FLOOR);
        controller.onDropped();

        assertTrue(controller.update(0));

        assertEquals((int) (CEILING * BitRateController.DECREASE_FACTOR),
                controller.getBitRate());
        controller.onDropped();
        assertFalse("one congestion event is not cut twice",
                controller.update(BitRateController.DECREASE_INTERVAL_MILLIS - 1));
    }

    /** A counter that went backwards is a new one, and the rate is measured from it afresh. */
    @Test
    public void aDeliveryTotalThatGoesBackwardsStartsAgain() {
        BitRateController controller = BitRateController.forBitRate(CEILING, FLOOR);
        controller.onDelivered(0, 1_000_000);
        controller.onDelivered(1_000, 2_000_000);
        controller.onDelivered(1_100, 10);
        controller.onDelivered(1_700, 10 + 7_500);
        controller.onDropped();

        controller.update(1_700);

        assertEquals("cut to 0.85 of 100kbps measured since the reset",
                FLOOR, controller.getBitRate());
        assertTrue(controller.getEstimate() < 100_000);
    }

    /** A ceiling under the floor is respected, and so is the floor everywhere else. */
    @Test
    public void theCeilingWinsOverTheFloor() {
        BitRateController controller = BitRateController.forBitRate(100_000, FLOOR);

        assertEquals(100_000, controller.getFloor());
        assertEquals(100_000, controller.getCeiling());
        assertEquals(1, controller.getLevelCount());
        Link link = new Link(controller, 20_000);
        assertTrue(link.run(10_000) >= 100_000);
        assertTrue(controller.getBitRate() <= 100_000);
    }

    /**
     * A caller who asked for exactly the floor still gets full quality back: the headroom a
     * restore wants is capped at the ceiling, which is all the estimate can reach.
     */
    @Test
    public void aCeilingAtTheFloorStillRestores() {
        Link link = new Link(BitRateController.withLevels(FLOOR, FLOOR, LADDER), 2_000_000);
        link.bitsPerSecond = 40_000;
        link.run(20_000);
        assertTrue(link.controller.getLevelIndex() > 0);

        link.bitsPerSecond = 2_000_000;
        link.run(120_000);

        assertEquals(0, link.controller.getLevelIndex());
        assertEquals(FLOOR, link.controller.getBitRate());
    }

    /**
     * A restore that lasts resets the wait for the next one: a third restore, after a lasting
     * second one, takes as long as the first did rather than as long as the doubled second.
     */
    @Test
    public void aLastingRestoreResetsTheWait() {
        Link link = new Link(video(), 2_000_000);
        link.bitsPerSecond = 100_000;
        long firstDegraded = waitForLevel(link, 1, 30_000);
        link.bitsPerSecond = 400_000;
        long first = waitForLevel(link, 0, 120_000) - firstDegraded;

        link.bitsPerSecond = 100_000;
        long secondDegraded = waitForLevel(link, 1, 30_000);
        link.bitsPerSecond = 400_000;
        long second = waitForLevel(link, 0, 120_000) - secondDegraded;
        assertTrue("the failed restore doubled the wait: " + first + " then " + second,
                second >= first + BitRateController.RESTORE_AFTER_MILLIS - 1_000);

        link.run(BitRateController.RESTORE_SETTLED_MILLIS + 1_000);
        link.bitsPerSecond = 100_000;
        long thirdDegraded = waitForLevel(link, 1, 30_000);
        link.bitsPerSecond = 400_000;
        long third = waitForLevel(link, 0, 120_000) - thirdDegraded;

        assertTrue("back to the first wait: " + first + ", " + second + ", " + third,
                Math.abs(third - first) < 2_000);
    }

    @Test
    public void levelsDescribeThemselves() {
        assertTrue(LADDER.get(1).toString().contains("0.5"));
        assertEquals(4, video().getLevelCount());
        assertEquals(1f, video().getLevel().getAreaScale(), 0);
    }
}
