package com.xlythe.view.camera.stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.xlythe.view.camera.stream.LinkSimulation.Link;
import com.xlythe.view.camera.stream.LinkSimulation.Media;
import com.xlythe.view.camera.stream.LinkSimulation.Pump;
import com.xlythe.view.camera.stream.LinkSimulation.Result;
import com.xlythe.view.camera.stream.LinkSimulation.Transport;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Whether the lossy pipe's back-pressure survives the trip through an app's pump and a transport.
 *
 * <p>The pipe can only drop what is still in it. It sees a reader falling behind only when the
 * pump stops reading, and a pump stops reading only when the transport stops taking writes. A
 * transport that queues whatever it is given never does, so the backlog moves out of the pipe,
 * where it could have been dropped, into the transport, where it cannot.</p>
 *
 * <p>Plain JUnit: nothing here touches the platform, so there is no SDK to run it against.</p>
 */
public class LinkBackpressureTest {
    /** Persistently too slow: the link carries four fifths of the stream. */
    private static final Link SLOW = Link.steady("slow", 0.8);

    /** Twice the stream, gone completely for three seconds. */
    private static final Link OUTAGE = Link.outage("outage", 2, 6_000, 9_000);

    /** Twice the stream on average, delivered in bursts of 150ms out of every 250ms. */
    private static final Link BURSTY = Link.bursty("bursty", 2, 250, 150);

    /** The fixed audio threshold VideoStream starts with: half a second at 16kHz mono. */
    private static final int FIXED_AUDIO_THRESHOLD = 16 * 1024;

    /** A transport and the pump feeding it. */
    private static final class Strategy {
        /** The transport. */
        final Transport transport;
        /** The pump. */
        final Pump pump;

        Strategy(Transport transport, Pump pump) {
            this.transport = transport;
            this.pump = pump;
        }
    }

    /** Every pairing worth comparing. A paced pump on an unbounded transport is a naive one. */
    private static final Strategy[] STRATEGIES = {
            new Strategy(Transport.unbounded(), Pump.NAIVE),
            new Strategy(Transport.blocking(64 * 1024), Pump.NAIVE),
            new Strategy(Transport.blocking(8 * 1024), Pump.NAIVE),
            new Strategy(Transport.blocking(8 * 1024), Pump.PACED),
            new Strategy(Transport.acknowledged(200), Pump.PACED),
    };

    private static Result run(Media media, Link link, Transport transport, Pump pump)
            throws IOException {
        return new LinkSimulation(media).link(link).transport(transport).pump(pump).run();
    }

    /**
     * The whole matrix, printed for the record, and the one property every run keeps: the reader
     * can always parse what arrives. Dropping may cost frames, never framing.
     */
    @Test
    public void everyStrategyKeepsTheStreamParseable() throws IOException {
        List<String> rows = new ArrayList<>();
        rows.add(Result.header());
        for (Media media : Media.values()) {
            for (Link link : new Link[] {SLOW, OUTAGE, BURSTY}) {
                for (Strategy strategy : STRATEGIES) {
                    Result result = run(media, link, strategy.transport, strategy.pump);
                    rows.add(result.row());
                    assertTrue("header lost: " + result.row(), result.headerIntact);
                    assertEquals("torn records: " + result.row(), 0, result.corruptRecords);
                }
            }
        }
        System.out.println(String.join("\n", rows));
    }

    /**
     * A pump that reads as fast as it can into a transport that queues without limit: the pipe
     * never sees a backlog, so it never drops, and the delay grows for as long as the link is
     * slower than the stream - a fifth of a second for every second here, without end.
     */
    @Test
    public void anUnboundedTransportKeepsThePipeFromEverDropping() throws IOException {
        for (Media media : Media.values()) {
            Result result = run(media, SLOW, Transport.unbounded(), Pump.NAIVE);
            assertEquals(result.row(), 0, result.droppedBytes);
            assertTrue(result.row(), result.tailLatency() > result.latencyBefore(10_000) + 1_500);
            assertTrue(result.row(), result.tailLatency() > 3_500);
        }
    }

    /**
     * Reading only what the transport can send at once moves the backlog back into the pipe,
     * which drops past its threshold, so the delay settles instead of growing.
     */
    @Test
    public void pacingTheReadsBoundsTheDelay() throws IOException {
        for (Media media : Media.values()) {
            for (Transport transport : new Transport[] {
                    Transport.blocking(8 * 1024), Transport.acknowledged(200)}) {
                Result result = run(media, SLOW, transport, Pump.PACED);
                assertTrue(result.row(), result.droppedPercent() > 10);
                assertTrue(result.row(),
                        Math.abs(result.tailLatency() - result.latencyBefore(10_000)) < 100);
                assertTrue(result.row(), result.tailLatency() < 1_100);
            }
        }
    }

    /**
     * A transport that blocks is only as good as its buffer is small. A kernel pipe's 64KB in
     * front of the link is seconds of 16kHz audio, so the amount in flight has to be bounded in
     * time rather than left to whatever the transport happens to hold.
     */
    @Test
    public void aBlockingTransportIsOnlyAsGoodAsItsBufferIsSmall() throws IOException {
        Result large = run(Media.AUDIO, SLOW, Transport.blocking(64 * 1024), Pump.NAIVE);
        Result small = run(Media.AUDIO, SLOW, Transport.blocking(8 * 1024), Pump.NAIVE);
        assertTrue(large.row(), large.tailLatency() > 2_500);
        assertTrue(small.row(), small.tailLatency() < large.tailLatency() - 1_500);
    }

    /**
     * An acknowledgement window caps throughput at one window per round trip, so a window shorter
     * than the round trip starves a link that has room to spare.
     */
    @Test
    public void theAckWindowHasToCoverTheRoundTrip() throws IOException {
        Link roomy = Link.steady("2x", 2);
        Result covered = new LinkSimulation(Media.VIDEO).link(roomy)
                .transport(Transport.acknowledged(200)).pump(Pump.PACED).oneWayMillis(30).run();
        Result starved = new LinkSimulation(Media.VIDEO).link(roomy)
                .transport(Transport.acknowledged(200)).pump(Pump.PACED).oneWayMillis(150).run();
        assertEquals(covered.row(), 0, covered.droppedBytes);
        assertTrue(starved.row(), starved.droppedPercent() > 25);
    }

    /**
     * Treating "nothing read for a while" as back-pressure, the policy {@link
     * LinkSimulation.StallAwarePipe} adds, buys nothing a drop threshold set from a latency budget
     * does not already give.
     *
     * <p>It cannot see an unbounded transport at all, since that pump never stops reading. Behind
     * a paced pump it only fires during an outage, and there the threshold has already filled:
     * a fixed 16KB audio threshold plus a quarter-second stall timeout ends up exactly where
     * AudioRecorder's quarter-second budget alone does. The one place it differs is an encoder
     * well under its bit rate, where the byte threshold holds more time than the budget; there it
     * saves a few stale frames, which arrive in a burst after the outage and cost a few tens of
     * milliseconds of recovery.</p>
     */
    @Test
    public void aStallTimeoutAddsNothingToATimeBudget() throws IOException {
        Result unbounded = new LinkSimulation(Media.AUDIO).link(SLOW)
                .transport(Transport.unbounded()).stallTimeoutMillis(100).run();
        assertEquals(unbounded.row(), 0, unbounded.droppedBytes);

        Result bytesAndStall = new LinkSimulation(Media.AUDIO).link(OUTAGE)
                .transport(Transport.acknowledged(200)).pump(Pump.PACED)
                .audioMaxBufferedBytes(FIXED_AUDIO_THRESHOLD).stallTimeoutMillis(250).run();
        Result budget = run(Media.AUDIO, OUTAGE, Transport.acknowledged(200), Pump.PACED);
        Result budgetAndStall = new LinkSimulation(Media.AUDIO).link(OUTAGE)
                .transport(Transport.acknowledged(200)).pump(Pump.PACED)
                .stallTimeoutMillis(250).run();
        assertEquals(bytesAndStall.row(), budget.tailLatency(), bytesAndStall.tailLatency());
        assertEquals(budgetAndStall.row(), budget.tailLatency(), budgetAndStall.tailLatency());

        for (Media media : Media.values()) {
            Result paced = run(media, SLOW, Transport.acknowledged(200), Pump.PACED);
            Result pacedAndStall = new LinkSimulation(media).link(SLOW)
                    .transport(Transport.acknowledged(200)).pump(Pump.PACED)
                    .stallTimeoutMillis(250).run();
            assertEquals(pacedAndStall.row(), paced.tailLatency(), pacedAndStall.tailLatency());
            assertEquals(pacedAndStall.row(), paced.droppedBytes, pacedAndStall.droppedBytes);
        }

        Result quiet = new LinkSimulation(Media.VIDEO).link(OUTAGE).videoFill(0.3)
                .transport(Transport.acknowledged(200)).pump(Pump.PACED).run();
        Result quietAndStall = new LinkSimulation(Media.VIDEO).link(OUTAGE).videoFill(0.3)
                .transport(Transport.acknowledged(200)).pump(Pump.PACED)
                .stallTimeoutMillis(250).run();
        assertTrue(quiet.row() + "\n" + quietAndStall.row(),
                quiet.recovery() - quietAndStall.recovery() < 100);
    }

    /**
     * On a link slower than the stream the pipe sits at its threshold, so the threshold is the
     * delay. AudioRecorder sets it from a quarter-second budget; the fixed 16KB it replaces is
     * twice that at the default 16kHz mono, and four times at 8kHz.
     */
    @Test
    public void theAudioThresholdIsTheDelayOnASlowLink() throws IOException {
        Result fixed = new LinkSimulation(Media.AUDIO).link(SLOW)
                .transport(Transport.acknowledged(200)).pump(Pump.PACED)
                .audioMaxBufferedBytes(FIXED_AUDIO_THRESHOLD).run();
        Result budget = run(Media.AUDIO, SLOW, Transport.acknowledged(200), Pump.PACED);
        assertTrue(fixed.row() + "\n" + budget.row(),
                budget.tailLatency() < fixed.tailLatency() - 250);
        assertEquals(250, budget.pipeBudgetMillis);
    }

    /**
     * Video catches up on its own, because the player renders each frame as it lands. Audio
     * cannot: the track plays at the rate the audio was recorded, so the delay an outage leaves
     * behind is still there at the end of the run.
     */
    @Test
    public void audioNeverWorksOffTheDelayAnOutageLeaves() throws IOException {
        Result audio = run(Media.AUDIO, OUTAGE, Transport.acknowledged(200), Pump.PACED);
        Result video = run(Media.VIDEO, OUTAGE, Transport.acknowledged(200), Pump.PACED);
        assertEquals(audio.row(), -1, audio.recovery());
        assertTrue(audio.row(), audio.tailLatency() > 400);
        assertTrue(video.row(), video.recovery() >= 0);
        assertTrue(video.row(), video.tailLatency() < 100);
    }

    /**
     * Each H.264 delta is predicted from the frame before it, so dropping one breaks every frame
     * after it until the next key frame. Dropping deltas one at a time leaves a link that is
     * persistently a little too slow delivering frames that mostly cannot be decoded.
     */
    @Test
    public void droppingSingleDeltasBreaksMostOfWhatArrives() throws IOException {
        Result result = run(Media.VIDEO, SLOW, Transport.acknowledged(200), Pump.PACED);
        int arrived = result.decodableFrames + result.undecodableFrames;
        assertTrue(result.row(), result.undecodableFrames > arrived * 0.8);
    }
}
