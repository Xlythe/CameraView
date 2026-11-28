package com.xlythe.view.camera.stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Size;
import android.view.Surface;

import com.xlythe.view.camera.shadow.ShadowSlowStartingMediaCodec;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.MediaCodecInfoBuilder;
import org.robolectric.shadows.ShadowMediaCodec;
import org.robolectric.shadows.ShadowMediaCodecList;
import org.robolectric.shadows.ShadowSystemClock;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The encoding half of a stream.
 *
 * <p>This is what turns the camera's frames into the bytes the receiving end decodes, and it is
 * where every latency decision lives: how large a backlog is allowed before frames start being
 * dropped, which frames may be dropped, and how much the stream is asked to produce in the first
 * place. Those are checked here as the arithmetic they are, and the handshake with the camera -
 * which asks for a surface and is told what size to produce - as the exchange it is.</p>
 *
 * <p>Robolectric's MediaCodec shadow stands in for the encoder, so what is covered is the
 * library's own framing and bookkeeping rather than H.264.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class VideoRecorderTest {
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;

    private static final long TIMEOUT_SECONDS = 5;

    @Before
    public void setUp() {
        ShadowSlowStartingMediaCodec.reset();
        ShadowMediaCodecList.reset();
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder()
                .setName("fake.avc.encoder")
                .setIsEncoder(true)
                .setCapabilities(MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                        .setMediaFormat(videoFormat())
                        .setIsEncoder(true)
                        .setColorFormats(new int[] {
                                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                        })
                        .build())
                .build());
        // A passthrough encoder: what goes in comes out, which is all the framing needs of it.
        ShadowMediaCodec.addEncoder(MIME_TYPE, new ShadowMediaCodec.CodecConfig(
                4096 /* inputBufferSize */, 4096 /* outputBufferSize */,
                (in, out) -> out.put(in)));
    }

    @After
    public void tearDown() {
        // A test that failed while an encoder was held mid-start would otherwise leave its
        // thread waiting forever.
        ShadowSlowStartingMediaCodec.finishStarting();
        ShadowMediaCodec.clearCodecs();
        ShadowMediaCodecList.reset();
    }

    /**
     * A backlog is a latency budget, and it has to mean the same delay at any bit rate.
     *
     * <p>Bytes are the wrong unit for the question a caller is really asking - how far behind am I
     * willing to be - so the budget is a quarter of a second of whatever the stream is producing.
     * </p>
     */
    @Test
    public void theBacklogBudgetIsTheSameDelayAtEveryBitRate() {
        int low = VideoRecorder.backlogBytesFor(1_000_000, 250);
        int high = VideoRecorder.backlogBytesFor(8_000_000, 250);

        assertEquals("250ms of a megabit", 1_000_000 / 8 / 4, low);
        assertEquals("and 250ms of eight", 8_000_000 / 8 / 4, high);
        assertTrue(high > low);
    }

    /** A trickle still needs enough buffered to be worth decoding. */
    @Test
    public void aVeryLowBitRateGetsAFloor() {
        int tiny = VideoRecorder.backlogBytesFor(1_000, 250);

        assertTrue("a few bytes of backlog is useless", tiny >= 8_000);
    }

    /** A longer allowance is more bytes, because that is the whole point of the knob. */
    @Test
    public void aLongerAllowanceIsMoreBytes() {
        int quarterSecond = VideoRecorder.backlogBytesFor(4_000_000, 250);
        int wholeSecond = VideoRecorder.backlogBytesFor(4_000_000, 1_000);

        assertTrue(wholeSecond > quarterSecond);
        assertEquals(4_000_000 / 8, wholeSecond);
    }

    /**
     * Only a delta frame may be dropped.
     *
     * <p>Everything after a key frame is described relative to it, so dropping one takes the rest
     * of the group with it and the picture breaks until the next one arrives. Codec configuration
     * is not droppable either: without it the decoder cannot start at all.</p>
     */
    @Test
    public void onlyDeltaFramesMayBeDropped() {
        assertTrue("a plain frame is a delta", VideoRecorder.isDroppable(0));
        assertFalse("a key frame is not",
                VideoRecorder.isDroppable(MediaCodec.BUFFER_FLAG_KEY_FRAME));
        assertFalse("and neither is the codec's own configuration",
                VideoRecorder.isDroppable(MediaCodec.BUFFER_FLAG_CODEC_CONFIG));
        assertFalse(VideoRecorder.isDroppable(
                MediaCodec.BUFFER_FLAG_KEY_FRAME | MediaCodec.BUFFER_FLAG_CODEC_CONFIG));
    }

    @Test
    public void theStreamParametersAreRememberedAsGiven() {
        VideoRecorder recorder = new VideoRecorder(new NoCanvas(), new ByteArrayOutputStream());

        recorder.setBitRate(2_000_000);
        recorder.setFrameRate(24);
        recorder.setIFrameInterval(2);
        recorder.setMaxSize(new Size(640, 480));

        assertEquals(2_000_000, recorder.getBitRate());
        assertEquals(24, recorder.getFrameRate());
        assertEquals(2, recorder.getIFrameInterval());
        assertEquals(new Size(640, 480), recorder.getMaxSize());
    }

    /** No cap means follow the preview, which is what a local stream wants. */
    @Test
    public void noMaxSizeMeansFollowThePreview() {
        VideoRecorder recorder = new VideoRecorder(new NoCanvas(), new ByteArrayOutputStream());

        assertEquals(null, recorder.getMaxSize());
        recorder.setMaxSize(new Size(320, 240));
        recorder.setMaxSize(null);
        assertEquals(null, recorder.getMaxSize());
    }

    /**
     * The cap reaches the camera, because the camera is what chooses the size.
     *
     * <p>A stream going somewhere that cannot carry the preview - a watch, say - has to ask for
     * less before the frames exist, not re-encode them afterwards.</p>
     */
    @Test
    public void theCapIsWhatTheCameraIsAskedFor() throws Exception {
        RecordingCanvas canvas = new RecordingCanvas();
        VideoRecorder recorder = new VideoRecorder(canvas, new ByteArrayOutputStream());
        recorder.setMaxSize(new Size(640, 480));

        recorder.start();

        assertTrue("the recorder has to attach to the camera",
                canvas.attached.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(new Size(640, 480), canvas.requestedMaxSize.get());
        recorder.stop();
    }

    /**
     * Starting attaches to the camera and stopping hands the surface back.
     *
     * <p>The surface itself is null here and that is Robolectric, not the library: its MediaCodec
     * shadow has no input surface to give. What the handshake is for - the camera asking, being
     * told what size to produce, and being released afterwards - happens either way.</p>
     */
    @Test
    public void startingAttachesAndStoppingDetaches() throws Exception {
        RecordingCanvas canvas = new RecordingCanvas();
        VideoRecorder recorder = new VideoRecorder(canvas, new ByteArrayOutputStream());

        recorder.start();
        assertTrue("the camera has to be asked for a surface",
                canvas.attached.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        recorder.stop();

        assertTrue("and told when it can have it back",
                canvas.detached.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertFalse(recorder.isRecording());
    }

    /** Starting twice is one recording, because a caller cannot always tell. */
    @Test
    public void startingTwiceIsOneRecording() throws Exception {
        RecordingCanvas canvas = new RecordingCanvas();
        VideoRecorder recorder = new VideoRecorder(canvas, new ByteArrayOutputStream());

        recorder.start();
        assertTrue(canvas.attached.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        recorder.start();

        assertEquals("one attachment, not two", 1, canvas.attachments);
        recorder.stop();
    }

    /**
     * A recorder is fire-once: stopped, it stays stopped.
     *
     * <p>Which includes being stopped before it ever started. The encoder and the stream it
     * writes to are both consumed by a recording, so a second one would be writing into a closed
     * pipe - a new recorder is the only honest way to record again.</p>
     */
    @Test
    public void aStoppedRecorderStaysStopped() throws Exception {
        RecordingCanvas canvas = new RecordingCanvas();
        VideoRecorder recorder = new VideoRecorder(canvas, new ByteArrayOutputStream());

        recorder.stop();
        recorder.start();

        assertFalse(recorder.isRecording());
        assertEquals("it never attached to the camera", 0, canvas.attachments);
        recorder.stop();
        assertFalse("and stopping twice is the same as stopping once", recorder.isRecording());
    }

    /**
     * Stopping while the encoder is still starting waits for the start to finish.
     *
     * <p>A caller stops a stream from its own thread, whenever it likes, and the first bytes reach
     * it before the encoder has finished starting. Before API 29, a MediaCodec released while a
     * start is completing on another thread aborts the process, so the release has to come after.
     * </p>
     */
    @Test
    @Config(shadows = ShadowSlowStartingMediaCodec.class)
    public void stoppingWhileTheEncoderStartsWaitsForTheStart() throws Exception {
        VideoRecorder recorder =
                new VideoRecorder(new RecordingCanvas(), new ByteArrayOutputStream());
        recorder.start();
        assertTrue("the encoder has to begin starting",
                ShadowSlowStartingMediaCodec.awaitStarting(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        Thread stopper = new Thread(recorder::stop, "Stopper");
        stopper.start();
        awaitBlockedOrFinished(stopper);
        ShadowSlowStartingMediaCodec.finishStarting();
        stopper.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));

        assertFalse("stop() has to return once the encoder has started", stopper.isAlive());
        assertEquals("the encoder is stopped and released only after it has started",
                Arrays.asList("started", "stopped", "released"),
                ShadowSlowStartingMediaCodec.getEvents());
        assertFalse(recorder.isRecording());
    }

    /**
     * What a stream can give up below the floor depends on the release: frames only from API
     * 29, where an encoder can be told to drop them, and pixels from 21, where a size can be
     * asked for. Frames always come first.
     */
    @Test
    public void theLadderFollowsTheRelease() {
        List<BitRateController.Level> current = VideoRecorder.levelsFor(37);
        assertEquals(3, current.size());
        assertEquals(0.5f, current.get(0).getFrameRateScale(), 0);
        assertEquals(1f, current.get(0).getAreaScale(), 0);
        assertEquals(0.5f, current.get(1).getAreaScale(), 0);
        assertEquals(0.25f, current.get(2).getAreaScale(), 0);
        assertEquals(0.5f, current.get(2).getFrameRateScale(), 0);

        List<BitRateController.Level> api28 = VideoRecorder.levelsFor(28);
        assertEquals("no frame step without an encoder that drops frames", 2, api28.size());
        assertEquals(1f, api28.get(0).getFrameRateScale(), 0);
        assertEquals(0.5f, api28.get(0).getAreaScale(), 0);

        assertTrue(VideoRecorder.levelsFor(20).isEmpty());
    }

    /** Only a lossy stream, on a release that can change a running encoder, adapts. */
    @Test
    public void onlyALossyStreamAdapts() throws Exception {
        LossyPipedOutputStream lossy = new LossyPipedOutputStream(new PipedInputStream());

        BitRateController controller = VideoRecorder.createController(1_000_000, 37, lossy);
        assertNotNull(controller);
        assertEquals(1_000_000, controller.getCeiling());
        assertEquals(BitRateController.VIDEO_FLOOR_BIT_RATE, controller.getFloor());
        assertEquals(4, controller.getLevelCount());

        assertNull(VideoRecorder.createController(1_000_000, 37, new ByteArrayOutputStream()));
        assertNull(VideoRecorder.createController(1_000_000, 18, lossy));
    }

    /**
     * A lower frame rate is a share of what the camera was measured drawing at; with nothing
     * measured yet, of what was asked for, and never below a floor.
     */
    @Test
    public void aSessionsFrameRateIsAShareOfTheFullRate() {
        VideoRecorder recorder = new VideoRecorder(new NoCanvas(), new ByteArrayOutputStream());
        recorder.setFrameRate(20);

        assertEquals(20, recorder.sessionFrameRate(1));
        assertEquals(10, recorder.sessionFrameRate(0.5f));
        recorder.setFrameRate(6);
        assertEquals(VideoRecorder.MIN_FRAME_RATE, recorder.sessionFrameRate(0.5f));
    }

    /** A smaller size is a share of the first session's area, within the caller's own cap. */
    @Test
    public void aSessionsSizeIsAShareOfTheFirst() throws Exception {
        RecordingCanvas canvas = new RecordingCanvas();
        VideoRecorder recorder = new VideoRecorder(canvas, new ByteArrayOutputStream());
        assertNull("no cap and nothing to scale from", recorder.sessionMaxSize(0.5f));

        recorder.start();
        assertTrue(canvas.attached.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS);
        while (recorder.sessionMaxSize(0.25f) == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        recorder.stop();

        assertEquals("the camera gave 1280x720", new Size(640, 360), recorder.sessionMaxSize(0.25f));
        assertEquals(new Size(904, 508), recorder.sessionMaxSize(0.5f));
        assertNull("full size follows the caller's cap, which is none",
                recorder.sessionMaxSize(1));
        recorder.setMaxSize(new Size(320, 480));
        assertEquals("never past the caller's cap", new Size(320, 360),
                recorder.sessionMaxSize(0.25f));
    }

    /** A lossy recorder starts with a controller, and the meter reports to it. */
    @Test
    public void aLossyRecorderListensToItsMeter() throws Exception {
        PipedInputStream pipe = new PipedInputStream(1024 * 1024);
        LossyPipedOutputStream lossy = new LossyPipedOutputStream(pipe);
        RecordingCanvas canvas = new RecordingCanvas();
        VideoRecorder recorder = new VideoRecorder(canvas, lossy);
        DeliveryMeter meter = new DeliveryMeter();
        recorder.setDeliveryMeter(meter);
        assertNull("nothing adapts before the stream starts", recorder.getController());

        recorder.start();
        try {
            assertTrue(canvas.attached.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            BitRateController controller = recorder.getController();
            assertNotNull(controller);
            int before = controller.getBitRate();
            meter.onAcknowledged(0);
            assertEquals(before, controller.getBitRate());
        } finally {
            recorder.stop();
        }
    }

    /**
     * A reader that stops reading makes the running encoder lower its bit rate, down to the
     * floor, and then the recorder restarts the encoder for the next level down: a second
     * session, asking the camera for a surface again.
     *
     * <p>The stand-in encoder passes through whatever is queued into it, so the test plays the
     * camera by queueing frames itself, and moves the system clock the recorder keeps time on.</p>
     */
    @Test
    public void aStalledReaderCutsTheBitRateAndThenMovesDownALevel() throws Exception {
        PipedInputStream pipe = new PipedInputStream(1024 * 1024);
        LossyPipedOutputStream lossy = new LossyPipedOutputStream(pipe);
        RecordingCanvas canvas = new RecordingCanvas();
        VideoRecorder recorder = new VideoRecorder(canvas, lossy);
        recorder.setBitRate(1_000_000);
        recorder.setFrameRate(30);
        recorder.start();
        try {
            assertTrue(canvas.attached.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            BitRateController controller = recorder.getController();
            assertNotNull(controller);
            int lowestAtFullQuality = Integer.MAX_VALUE;
            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
            for (int frame = 0; controller.getLevelIndex() == 0
                    && System.currentTimeMillis() < deadline; frame++) {
                queueFrame(recorder, 20_000, frame % 30 == 0);
                ShadowSystemClock.advanceBy(Duration.ofMillis(33));
                // The controller's own lock, so the level and the rate are read together.
                synchronized (controller) {
                    if (controller.getLevelIndex() == 0) {
                        lowestAtFullQuality =
                                Math.min(lowestAtFullQuality, controller.getBitRate());
                    }
                }
            }

            assertEquals("the bit rate stops at the floor", BitRateController.VIDEO_FLOOR_BIT_RATE,
                    lowestAtFullQuality);
            assertEquals("and then the stream gives something up", 1,
                    controller.getLevelIndex());
            long restartDeadline = System.currentTimeMillis()
                    + TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS);
            while (canvas.attachments < 2 && System.currentTimeMillis() < restartDeadline) {
                // The old encoder drains its last frame before the recorder notices the level.
                queueFrame(recorder, 1_000, false);
                Thread.sleep(10);
            }
            assertEquals("a new session asks the camera again", 2, canvas.attachments);
            assertTrue("the backlog allowance follows the bit rate down",
                    lossy.getMaxBufferedBytes() < VideoRecorder.backlogBytesFor(1_000_000, 250));
        } finally {
            recorder.stop();
        }
    }

    /** Queues one frame into whichever encoder the recorder is running, if it has one ready. */
    private static void queueFrame(VideoRecorder recorder, int size, boolean keyFrame)
            throws Exception {
        Field field = VideoRecorder.class.getDeclaredField("mEncoder");
        field.setAccessible(true);
        MediaCodec encoder = (MediaCodec) ((AtomicReference<?>) field.get(recorder)).get();
        if (encoder == null) {
            Thread.sleep(5);
            return;
        }
        try {
            int index = encoder.dequeueInputBuffer(10_000);
            if (index < 0) {
                return;
            }
            ByteBuffer input = encoder.getInputBuffer(index);
            input.clear();
            input.put(new byte[Math.min(size, input.remaining())]);
            encoder.queueInputBuffer(index, 0, input.position(), 0,
                    keyFrame ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
            // Gives the recorder's thread the frame before the clock moves again.
            Thread.sleep(2);
        } catch (IllegalStateException e) {
            // Released between sessions; the next frame goes to the new encoder.
        }
    }

    /** Waits until a thread is either waiting for a lock or finished, whichever comes first. */
    static void awaitBlockedOrFinished(Thread thread) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS);
        while (thread.getState() != Thread.State.BLOCKED
                && thread.getState() != Thread.State.TERMINATED
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }

    /** A canvas that never answers, for the cases that do not get as far as an encoder. */
    private static final class NoCanvas implements VideoRecorder.Canvas {
        @Override public void attachSurface(VideoRecorder.SurfaceProvider surfaceProvider) {}

        @Override public void detachSurface(VideoRecorder.SurfaceProvider surfaceProvider) {}
    }

    /** A canvas that asks for a surface the way a backend does, from its own thread. */
    private static final class RecordingCanvas implements VideoRecorder.Canvas {
        final CountDownLatch attached = new CountDownLatch(1);
        final CountDownLatch detached = new CountDownLatch(1);
        final AtomicReference<Size> requestedMaxSize = new AtomicReference<>();
        final AtomicReference<Surface> surface = new AtomicReference<>();
        volatile int attachments;

        @Override
        public void attachSurface(VideoRecorder.SurfaceProvider surfaceProvider) {
            attachments++;
            requestedMaxSize.set(surfaceProvider.getMaxSize());
            Thread camera = new Thread(() -> {
                surface.set(surfaceProvider.getSurface(1280, 720, 90, false));
                attached.countDown();
            }, "RecordingCanvas");
            camera.setDaemon(true);
            camera.start();
        }

        @Override
        public void detachSurface(VideoRecorder.SurfaceProvider surfaceProvider) {
            detached.countDown();
        }
    }

    private static MediaFormat videoFormat() {
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, 1920, 1080);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000);
        return format;
    }
}
