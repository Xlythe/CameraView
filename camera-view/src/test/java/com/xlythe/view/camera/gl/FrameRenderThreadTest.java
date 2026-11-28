package com.xlythe.view.camera.gl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;

import com.xlythe.view.camera.shadow.ShadowFrameSurfaceTexture;
import com.xlythe.view.camera.shadow.ShadowRecordingEglExt;
import com.xlythe.view.camera.shadow.ShadowRecordingGles20;
import com.xlythe.view.camera.shadow.ShadowUnavailableEgl;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The render thread's plumbing: which outputs a frame is drawn into, at what size and through
 * which matrix, what becomes of a still, and what is given back when it stops.
 *
 * <p>Robolectric's EGL brings a context up and draws nothing, which is enough to run the thread
 * for real. {@link ShadowFrameSurfaceTexture} delivers the camera's frames, and
 * {@link ShadowRecordingGles20} records what each one was drawn with. Whether the pixels come
 * out right is for a device; whether every output is asked for the right picture is here.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, shadows = {
        ShadowFrameSurfaceTexture.class,
        ShadowRecordingGles20.class,
        ShadowRecordingEglExt.class})
public class FrameRenderThreadTest {
    private static final float TOLERANCE = 1e-5f;

    /** The name every thread here is given, so a test can tell its frames arrived on it. */
    private static final String THREAD_NAME = "FrameRenderThreadTest";

    /**
     * What a camera's SurfaceTexture typically reports: the buffer flipped top to bottom, because
     * GL counts rows up from the bottom and the camera writes them down from the top.
     */
    private static final float[] CAMERA_FLIP = {
            1, 0, 0, 0,
            0, -1, 0, 0,
            0, 0, 1, 0,
            0, 1, 0, 1,
    };

    /** A quarter turn clockwise as shown, which takes (s, t) to (t, 1 - s). */
    private static final float[] QUARTER_TURN = {
            0, -1, 0, 0,
            1, 0, 0, 0,
            0, 0, 1, 0,
            0, 1, 0, 1,
    };

    /** A mirror left to right, for a transform that is recognisably not the camera's own. */
    private static final float[] MIRROR = {
            -1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            1, 0, 0, 1,
    };

    private static final Size LANDSCAPE = new Size(1920, 1080);
    private static final Size PORTRAIT = new Size(1080, 1920);

    /** Every thread a test started, so none outlives it. */
    private final List<FrameRenderThread> threads = new ArrayList<>();

    @Before
    public void setUp() {
        ShadowFrameSurfaceTexture.reset();
        ShadowRecordingGles20.reset();
        ShadowRecordingEglExt.reset();
        ShadowFrameSurfaceTexture.setFrameTransform(CAMERA_FLIP);
    }

    @After
    public void tearDown() {
        for (FrameRenderThread thread : threads) {
            thread.stop();
        }
    }

    /**
     * Starting brings up an input for the camera and starts the renderer on the render thread;
     * stopping stops the renderer there and gives the input back.
     */
    @Test
    public void theRendererStartsAndStopsOnTheRenderThread() {
        RecordingRenderer renderer = new RecordingRenderer();
        FrameRenderThread thread = newThread(renderer);

        assertTrue(thread.start());

        SurfaceTexture input = thread.getInputSurfaceTexture();
        assertNotNull("the camera needs somewhere to write", input);
        assertEquals(1, renderer.started.get());
        assertEquals(THREAD_NAME, renderer.startedOn);

        thread.stop();

        assertEquals(1, renderer.stopped.get());
        assertNull("a stopped renderer has no input to hand out", thread.getInputSurfaceTexture());
        assertTrue(ShadowFrameSurfaceTexture.isReleased(input));
        assertTrue("both of the library's programs are given back",
                ShadowRecordingGles20.deletedProgramCount() >= 2);
    }

    /** Asking a running renderer to start again is not a second renderer. */
    @Test
    public void startingTwiceIsOneRenderer() {
        RecordingRenderer renderer = new RecordingRenderer();
        FrameRenderThread thread = newThread(renderer);
        SurfaceTexture input;

        assertTrue(thread.start());
        input = thread.getInputSurfaceTexture();
        assertTrue(thread.start());

        assertEquals(1, renderer.started.get());
        assertEquals(input, thread.getInputSurfaceTexture());
    }

    /**
     * Where GL will not run, start says so instead of throwing, the renderer is never started,
     * and nothing that needs a context will arm.
     */
    @Test
    @Config(shadows = {ShadowUnavailableEgl.class})
    public void whereGlWillNotRunNothingStarts() {
        RecordingRenderer renderer = new RecordingRenderer();
        FrameRenderThread thread = newThread(renderer);

        assertFalse(thread.start());

        assertEquals(0, renderer.started.get());
        assertNull(thread.getInputSurfaceTexture());
        assertNull(thread.createStillInput(LANDSCAPE));
        assertFalse(thread.drawStillInto(newSurface(), LANDSCAPE, GlMath.ScaleType.FILL_CENTER,
                copying(MIRROR), () -> {}));
    }

    /**
     * A renderer that throws while starting brings the pipeline down cleanly and is reported as
     * GL being unavailable, so the camera carries on without it.
     */
    @Test
    public void aRendererThatThrowsWhileStartingIsReportedAsNoGl() {
        RecordingRenderer renderer = new RecordingRenderer();
        renderer.failOnStart = true;
        FrameRenderThread thread = newThread(renderer);

        assertFalse(thread.start());

        assertNull(thread.getInputSurfaceTexture());
        assertEquals("what it did manage to make is given back", 1, renderer.stopped.get());
    }

    /**
     * Every output is drawn once per frame, at its own size, with its own matrix and the
     * camera's timestamp, from the one texture the camera wrote.
     */
    @Test
    public void eachOutputIsDrawnOncePerFrameAtItsOwnSize() {
        RecordingRenderer renderer = new RecordingRenderer();
        FrameRenderThread thread = started(renderer);
        thread.addOutput("preview", newSurface(), PORTRAIT, PORTRAIT,
                GlMath.ScaleType.FILL_CENTER, copying(MIRROR));
        thread.addOutput("small", newSurface(), new Size(640, 480), new Size(640, 480),
                GlMath.ScaleType.FILL_CENTER, copying(QUARTER_TURN));
        ShadowFrameSurfaceTexture.setFrameTimestamp(123_456L);

        ShadowFrameSurfaceTexture.deliverFrame(thread.getInputSurfaceTexture());

        List<DrawnFrame> frames = renderer.frames();
        assertEquals(2, frames.size());
        assertEquals(PORTRAIT, frames.get(0).size);
        assertEquals(new Size(640, 480), frames.get(1).size);
        assertArrayEquals(MIRROR, frames.get(0).textureMatrix, TOLERANCE);
        assertArrayEquals(QUARTER_TURN, frames.get(1).textureMatrix, TOLERANCE);
        for (DrawnFrame frame : frames) {
            assertEquals(123_456L, frame.timestampNanos);
            assertEquals(THREAD_NAME, frame.thread);
            assertEquals(frames.get(0).textureName, frame.textureName);
        }
        assertEquals("the frame is consumed once, not once per output",
                1, ShadowFrameSurfaceTexture.updatesOf(thread.getInputSurfaceTexture()));
        assertEquals("and each output is stamped with when it was captured",
                Arrays.asList(123_456L, 123_456L), ShadowRecordingEglExt.presentationTimes());
    }

    /**
     * With no renderer the library draws the camera frame itself, through the output's matrix,
     * placed by the output's scale type: a square frame fitted into a tall output is centred.
     */
    @Test
    public void withNoRendererTheCameraFrameIsPlacedByTheScaleType() {
        FrameRenderThread thread = started(null);
        thread.addOutput("preview", newSurface(), PORTRAIT, new Size(1080, 1080),
                GlMath.ScaleType.FIT_CENTER, copying(MIRROR));

        ShadowFrameSurfaceTexture.deliverFrame(thread.getInputSurfaceTexture());

        List<ShadowRecordingGles20.Draw> draws = ShadowRecordingGles20.draws();
        assertEquals(1, draws.size());
        assertArrayEquals(MIRROR, draws.get(0).textureMatrix, TOLERANCE);
        assertArrayEquals(new int[] {0, 420, 1080, 1080}, draws.get(0).viewport);
        assertEquals("straight into the output", 0, draws.get(0).framebuffer);
    }

    /**
     * A renderer that declines a frame has the library draw the camera frame; one that takes it
     * gets nothing drawn underneath it.
     */
    @Test
    public void aRendererThatDeclinesAFrameGetsTheCameraFrame() {
        RecordingRenderer renderer = new RecordingRenderer();
        FrameRenderThread thread = started(renderer);
        thread.addOutput("preview", newSurface(), PORTRAIT, PORTRAIT,
                GlMath.ScaleType.FILL_CENTER, copying(MIRROR));

        ShadowFrameSurfaceTexture.deliverFrame(thread.getInputSurfaceTexture());
        assertEquals("declined, so the library drew it", 1, ShadowRecordingGles20.draws().size());

        renderer.drawsItself = true;
        ShadowFrameSurfaceTexture.deliverFrame(thread.getInputSurfaceTexture());
        assertEquals("taken, and this renderer draws nothing", 1,
                ShadowRecordingGles20.draws().size());
    }

    /** A renderer swapped while running draws the next frame; clearing it restores the camera. */
    @Test
    public void aRendererSwappedWhileRunningDrawsTheNextFrame() {
        RecordingRenderer first = new RecordingRenderer();
        RecordingRenderer second = new RecordingRenderer();
        FrameRenderThread thread = started(first);
        thread.addOutput("preview", newSurface(), PORTRAIT, PORTRAIT,
                GlMath.ScaleType.FILL_CENTER, copying(MIRROR));

        thread.setFrameRenderer(second);
        ShadowFrameSurfaceTexture.deliverFrame(thread.getInputSurfaceTexture());
        thread.setFrameRenderer(null);
        ShadowFrameSurfaceTexture.deliverFrame(thread.getInputSurfaceTexture());

        assertTrue(first.frames().isEmpty());
        assertEquals(1, second.frames().size());
        assertEquals("the camera frame, drawn by the library both times",
                2, ShadowRecordingGles20.draws().size());
    }

    /** An output added under a key already in use replaces it; one removed is drawn no more. */
    @Test
    public void outputsAreReplacedAndRemovedByKey() {
        RecordingRenderer renderer = new RecordingRenderer();
        FrameRenderThread thread = started(renderer);
        thread.addOutput("preview", newSurface(), new Size(640, 480), new Size(640, 480),
                GlMath.ScaleType.FILL_CENTER, copying(MIRROR));
        thread.addOutput("preview", newSurface(), new Size(1280, 720), new Size(1280, 720),
                GlMath.ScaleType.FILL_CENTER, copying(MIRROR));

        ShadowFrameSurfaceTexture.deliverFrame(thread.getInputSurfaceTexture());
        assertEquals(1, renderer.frames().size());
        assertEquals(new Size(1280, 720), renderer.frames().get(0).size);

        thread.removeOutput("preview");
        ShadowFrameSurfaceTexture.deliverFrame(thread.getInputSurfaceTexture());
        assertEquals("nothing left to draw into", 1, renderer.frames().size());
    }

    /**
     * An output that fails is given up on and not retried every frame, and the others carry on;
     * this is what happens when a use case is unbound a frame before the renderer hears of it.
     */
    @Test
    public void anOutputThatFailsIsGivenUpWhileTheOthersCarryOn() {
        RecordingRenderer renderer = new RecordingRenderer();
        renderer.failForWidth = 640;
        FrameRenderThread thread = started(renderer);
        thread.addOutput("preview", newSurface(), PORTRAIT, PORTRAIT,
                GlMath.ScaleType.FILL_CENTER, copying(MIRROR));
        thread.addOutput("gone", newSurface(), new Size(640, 480), new Size(640, 480),
                GlMath.ScaleType.FILL_CENTER, copying(MIRROR));

        ShadowFrameSurfaceTexture.deliverFrame(thread.getInputSurfaceTexture());
        ShadowFrameSurfaceTexture.deliverFrame(thread.getInputSurfaceTexture());

        assertEquals("the broken output was tried once", 1, renderer.attemptsAt(640));
        assertEquals("the other was drawn every frame", 2, renderer.framesAt(PORTRAIT));
    }

    /**
     * An output that holds the picture turned - a preview in the camera buffer's wide shape,
     * shown tall - is drawn by the renderer the right way up, in the shape it is shown, and that
     * picture is then turned onto the output.
     */
    @Test
    public void anOutputHeldTurnedIsDrawnInTheShapeItIsShown() {
        RecordingRenderer renderer = new RecordingRenderer();
        FrameRenderThread thread = started(renderer);
        thread.setInputBufferSize(LANDSCAPE);
        thread.setShownOrientation(90, false);
        thread.addOutput("preview", newSurface(), LANDSCAPE, LANDSCAPE,
                GlMath.ScaleType.FILL_CENTER, copying(CAMERA_FLIP));

        ShadowFrameSurfaceTexture.deliverFrame(thread.getInputSurfaceTexture());

        assertEquals(LANDSCAPE, ShadowFrameSurfaceTexture.bufferSizeOf(
                thread.getInputSurfaceTexture()));
        assertEquals("drawn tall, as it is shown", PORTRAIT, renderer.frames().get(0).size);
        List<ShadowRecordingGles20.Draw> draws = ShadowRecordingGles20.draws();
        assertEquals("the camera frame into the canvas, then the canvas onto the output",
                2, draws.size());
        ShadowRecordingGles20.Draw intoCanvas = draws.get(0);
        ShadowRecordingGles20.Draw ontoOutput = draws.get(1);
        assertNotEquals("the picture is drawn offscreen", 0, intoCanvas.framebuffer);
        assertArrayEquals(new int[] {0, 0, 1080, 1920}, intoCanvas.viewport);
        assertEquals("and turned onto the output itself", 0, ontoOutput.framebuffer);
        assertArrayEquals(new int[] {0, 0, 1920, 1080}, ontoOutput.viewport);
        assertArrayEquals(QUARTER_TURN, ontoOutput.textureMatrix, TOLERANCE);
    }

    /** An output already the right way up is drawn straight into, with no canvas in between. */
    @Test
    public void anOutputAlreadyUprightIsDrawnStraightInto() {
        RecordingRenderer renderer = new RecordingRenderer();
        FrameRenderThread thread = started(renderer);
        thread.setInputBufferSize(LANDSCAPE);
        thread.setShownOrientation(0, false);
        thread.addOutput("preview", newSurface(), LANDSCAPE, LANDSCAPE,
                GlMath.ScaleType.FILL_CENTER, copying(CAMERA_FLIP));

        ShadowFrameSurfaceTexture.deliverFrame(thread.getInputSurfaceTexture());

        assertEquals(LANDSCAPE, renderer.frames().get(0).size);
        assertEquals(0, ShadowRecordingGles20.framebuffersCreated());
        assertEquals(1, ShadowRecordingGles20.draws().size());
        assertEquals(0, ShadowRecordingGles20.draws().get(0).framebuffer);
    }

    /**
     * A recording keeps the turn it started with, because the file is tagged once; the display
     * turning afterwards changes how the preview is shown and not how the recording is.
     */
    @Test
    public void aRecordingKeepsTheTurnItStartedWith() {
        RecordingRenderer renderer = new RecordingRenderer();
        FrameRenderThread thread = started(renderer);
        thread.setInputBufferSize(LANDSCAPE);
        thread.setShownOrientation(0, false);
        thread.addEncoderOutput("recording", newSurface(), LANDSCAPE, copying(CAMERA_FLIP),
                90, false);

        ShadowFrameSurfaceTexture.deliverFrame(thread.getInputSurfaceTexture());
        thread.setShownOrientation(270, true);
        ShadowFrameSurfaceTexture.deliverFrame(thread.getInputSurfaceTexture());

        assertEquals(PORTRAIT, renderer.frames().get(0).size);
        assertEquals("still drawn as it was tagged", PORTRAIT, renderer.frames().get(1).size);
        List<ShadowRecordingGles20.Draw> draws = ShadowRecordingGles20.draws();
        assertArrayEquals("and still turned the same way onto the file",
                QUARTER_TURN, draws.get(draws.size() - 1).textureMatrix, TOLERANCE);
    }

    /**
     * Where the driver will not draw offscreen, the renderer draws straight into the output -
     * turned, but drawn - and the canvas is not tried again on every frame.
     */
    @Test
    public void whereTheDriverCannotDrawOffscreenTheRendererDrawsStraightIn() {
        ShadowRecordingGles20.setFramebufferStatus(GLES20.GL_FRAMEBUFFER_UNSUPPORTED);
        RecordingRenderer renderer = new RecordingRenderer();
        FrameRenderThread thread = started(renderer);
        thread.setInputBufferSize(LANDSCAPE);
        thread.setShownOrientation(90, false);
        thread.addOutput("preview", newSurface(), LANDSCAPE, LANDSCAPE,
                GlMath.ScaleType.FILL_CENTER, copying(CAMERA_FLIP));

        ShadowFrameSurfaceTexture.deliverFrame(thread.getInputSurfaceTexture());
        ShadowFrameSurfaceTexture.deliverFrame(thread.getInputSurfaceTexture());

        assertEquals(LANDSCAPE, renderer.frames().get(0).size);
        assertEquals(LANDSCAPE, renderer.frames().get(1).size);
        assertEquals("the canvas was tried once", 1, ShadowRecordingGles20.framebuffersCreated());
    }

    /**
     * A camera timestamp on the clock that counts sleep is moved onto the monotonic clock for an
     * encoder, which lines video up with audio on that clock; the preview is stamped as captured.
     */
    @Test
    public void anEncoderIsStampedOnTheMonotonicClock() {
        long realtimeNanos = SystemClock.elapsedRealtimeNanos();
        long monotonicNanos = System.nanoTime();
        assumeTrue("the two clocks have to be told apart",
                Math.abs(monotonicNanos - realtimeNanos) > TimeUnit.SECONDS.toNanos(10));
        FrameRenderThread thread = started(new RecordingRenderer());
        thread.addOutput("preview", newSurface(), LANDSCAPE, LANDSCAPE,
                GlMath.ScaleType.FILL_CENTER, copying(CAMERA_FLIP));
        thread.addEncoderOutput("recording", newSurface(), LANDSCAPE, copying(CAMERA_FLIP),
                0, false);
        ShadowFrameSurfaceTexture.setFrameTimestamp(realtimeNanos);

        ShadowFrameSurfaceTexture.deliverFrame(thread.getInputSurfaceTexture());

        List<Long> stamps = ShadowRecordingEglExt.presentationTimes();
        assertEquals(2, stamps.size());
        assertEquals("the preview is told when the camera captured it",
                realtimeNanos, (long) stamps.get(0));
        assertTrue("the encoder is told the same moment on its own clock, not " + stamps.get(1),
                Math.abs(stamps.get(1) - System.nanoTime()) < TimeUnit.SECONDS.toNanos(5));
    }

    /** A camera timestamp already on the monotonic clock reaches the encoder unchanged. */
    @Test
    public void aMonotonicTimestampReachesTheEncoderUnchanged() {
        long monotonicNanos = System.nanoTime();
        assumeTrue("the two clocks have to be told apart",
                Math.abs(monotonicNanos - SystemClock.elapsedRealtimeNanos())
                        > TimeUnit.SECONDS.toNanos(10));
        FrameRenderThread thread = started(new RecordingRenderer());
        thread.addEncoderOutput("recording", newSurface(), LANDSCAPE, copying(CAMERA_FLIP),
                0, false);
        ShadowFrameSurfaceTexture.setFrameTimestamp(monotonicNanos);

        ShadowFrameSurfaceTexture.deliverFrame(thread.getInputSurfaceTexture());

        assertEquals(Collections.singletonList(monotonicNanos),
                ShadowRecordingEglExt.presentationTimes());
    }

    /** A still input is made at the size the camera will write the photograph at. */
    @Test
    public void aStillInputIsMadeAtTheSizeOfThePhotograph() {
        FrameRenderThread thread = started(new RecordingRenderer());

        assertNotNull(thread.createStillInput(new Size(4000, 3000)));

        SurfaceTexture still = stillTexture(thread);
        assertEquals(new Size(4000, 3000), ShadowFrameSurfaceTexture.bufferSizeOf(still));
    }

    /** Asking for a still input again replaces the first, which is given back. */
    @Test
    public void aNewStillInputReplacesTheOld() {
        FrameRenderThread thread = started(new RecordingRenderer());
        thread.createStillInput(new Size(4000, 3000));
        SurfaceTexture first = stillTexture(thread);

        thread.createStillInput(new Size(3000, 4000));

        assertTrue(ShadowFrameSurfaceTexture.isReleased(first));
        assertEquals(new Size(3000, 4000),
                ShadowFrameSurfaceTexture.bufferSizeOf(stillTexture(thread)));
    }

    /**
     * An armed still is drawn once, through the renderer, from its own full-size texture and at
     * its own size; the preview is not drawn for it, and whoever is waiting is told.
     */
    @Test
    public void anArmedStillIsDrawnOnceThroughTheRenderer() {
        RecordingRenderer renderer = new RecordingRenderer();
        FrameRenderThread thread = started(renderer);
        thread.addOutput("preview", newSurface(), PORTRAIT, PORTRAIT,
                GlMath.ScaleType.FILL_CENTER, copying(MIRROR));
        ShadowFrameSurfaceTexture.deliverFrame(thread.getInputSurfaceTexture());
        int previewTexture = renderer.frames().get(0).textureName;
        thread.createStillInput(new Size(4000, 3000));
        SurfaceTexture still = stillTexture(thread);
        AtomicInteger drawn = new AtomicInteger();
        ShadowFrameSurfaceTexture.setFrameTimestamp(777L);

        assertTrue(thread.drawStillInto(newSurface(), new Size(3000, 4000),
                GlMath.ScaleType.FILL_CENTER, copying(QUARTER_TURN), drawn::incrementAndGet));
        ShadowFrameSurfaceTexture.deliverFrame(still);

        List<DrawnFrame> frames = renderer.frames();
        assertEquals("one preview frame and one still", 2, frames.size());
        DrawnFrame stillFrame = frames.get(1);
        assertEquals(new Size(3000, 4000), stillFrame.size);
        assertNotEquals("a still is read from its own texture", previewTexture,
                stillFrame.textureName);
        assertArrayEquals(QUARTER_TURN, stillFrame.textureMatrix, TOLERANCE);
        assertEquals(777L, stillFrame.timestampNanos);
        assertEquals(1, drawn.get());

        ShadowFrameSurfaceTexture.deliverFrame(still);
        assertEquals("a second frame was not asked for", 2, renderer.frames().size());
        assertEquals(1, drawn.get());
        assertEquals("but it is consumed, or the next one never arrives",
                2, ShadowFrameSurfaceTexture.updatesOf(still));
    }

    /**
     * A still waits, while the camera segments for the replacer, until a mask made at its moment
     * or after has arrived; drawn on landing, it took whichever mask came last.
     */
    @Test
    public void aStillWaitsForTheMaskMadeAtItsOwnMoment() {
        BackgroundReplacer replacer = new BackgroundReplacer();
        replacer.setSegmentedByCamera(true);
        replacer.setMask(timedMask(500L));
        FrameRenderThread thread = started(replacer);
        thread.createStillInput(new Size(4000, 3000));
        AtomicInteger drawn = armTimedStill(thread, 1_000L);

        ShadowFrameSurfaceTexture.deliverFrame(stillTexture(thread));
        assertEquals("only a mask from before it", 0, drawn.get());

        replacer.setMask(timedMask(1_100L));
        idleRenderThread(thread, 20);
        assertEquals(1, drawn.get());
    }

    /**
     * A still whose mask has come in is drawn ahead of the next preview frame, rather than when
     * its next look for the mask comes round behind the frames queued meanwhile.
     */
    @Test
    public void aStillIsDrawnAheadOfThePreviewOnceItsMaskIsIn() {
        BackgroundReplacer replacer = new BackgroundReplacer();
        replacer.setSegmentedByCamera(true);
        FrameRenderThread thread = started(replacer);
        thread.addOutput("preview", newSurface(), PORTRAIT, PORTRAIT,
                GlMath.ScaleType.FILL_CENTER, copying(MIRROR));
        thread.createStillInput(new Size(4000, 3000));
        AtomicInteger drawn = armTimedStill(thread, 1_000L);
        ShadowFrameSurfaceTexture.deliverFrame(stillTexture(thread));
        assertEquals(0, drawn.get());

        replacer.setMask(timedMask(1_000L));
        ShadowFrameSurfaceTexture.deliverFrame(thread.getInputSurfaceTexture());

        assertEquals(1, drawn.get());
    }

    /** A still whose mask never comes is drawn once the wait is up, with the nearest there is. */
    @Test
    public void aStillIsDrawnOnceItsWaitIsUp() {
        BackgroundReplacer replacer = new BackgroundReplacer();
        replacer.setSegmentedByCamera(true);
        FrameRenderThread thread = started(replacer);
        thread.createStillInput(new Size(4000, 3000));
        AtomicInteger drawn = armTimedStill(thread, 1_000L);
        ShadowFrameSurfaceTexture.deliverFrame(stillTexture(thread));

        idleRenderThread(thread, 1_000);
        assertEquals("still within its wait", 0, drawn.get());
        idleRenderThread(thread, 600);
        assertEquals(1, drawn.get());
    }

    /** With nothing segmenting for the replacer, a still is drawn as it lands. */
    @Test
    public void aStillIsDrawnAtOnceWhenNothingSegments() {
        FrameRenderThread thread = started(new BackgroundReplacer());
        thread.createStillInput(new Size(4000, 3000));
        AtomicInteger drawn = armTimedStill(thread, 1_000L);

        ShadowFrameSurfaceTexture.deliverFrame(stillTexture(thread));

        assertEquals(1, drawn.get());
    }

    /** A still waiting on its mask is drawn when the renderer stops, not lost with it. */
    @Test
    public void aStillWaitingOnItsMaskIsDrawnWhenTheRendererStops() {
        BackgroundReplacer replacer = new BackgroundReplacer();
        replacer.setSegmentedByCamera(true);
        FrameRenderThread thread = started(replacer);
        thread.createStillInput(new Size(4000, 3000));
        AtomicInteger drawn = armTimedStill(thread, 1_000L);
        ShadowFrameSurfaceTexture.deliverFrame(stillTexture(thread));
        assertEquals(0, drawn.get());

        thread.stop();

        assertEquals(1, drawn.get());
    }

    /** The next still to land draws the one still waiting first, from the frame it waits on. */
    @Test
    public void aWaitingStillIsDrawnBeforeTheNextOneLands() {
        BackgroundReplacer replacer = new BackgroundReplacer();
        replacer.setSegmentedByCamera(true);
        FrameRenderThread thread = started(replacer);
        thread.createStillInput(new Size(4000, 3000));
        AtomicInteger first = armTimedStill(thread, 1_000L);
        SurfaceTexture still = stillTexture(thread);
        ShadowFrameSurfaceTexture.deliverFrame(still);

        AtomicInteger second = armTimedStill(thread, 2_000L);
        ShadowFrameSurfaceTexture.deliverFrame(still);

        assertEquals(1, first.get());
        assertEquals("the second waits on its own mask", 0, second.get());
        assertEquals(2, ShadowFrameSurfaceTexture.updatesOf(still));
    }

    /** With no renderer a still is the camera frame, filling the picture. */
    @Test
    public void aStillWithNoRendererIsTheCameraFrame() {
        FrameRenderThread thread = started(null);
        thread.createStillInput(new Size(4000, 3000));
        AtomicInteger drawn = new AtomicInteger();

        thread.drawStillInto(newSurface(), new Size(3000, 4000), GlMath.ScaleType.FILL_CENTER,
                copying(QUARTER_TURN), drawn::incrementAndGet);
        ShadowFrameSurfaceTexture.deliverFrame(stillTexture(thread));

        List<ShadowRecordingGles20.Draw> draws = ShadowRecordingGles20.draws();
        assertEquals(1, draws.size());
        assertArrayEquals(QUARTER_TURN, draws.get(0).textureMatrix, TOLERANCE);
        assertArrayEquals(new int[] {0, 0, 3000, 4000}, draws.get(0).viewport);
        assertEquals(1, drawn.get());
    }

    /** A still cancelled before the camera wrote it is consumed when it lands, and not drawn. */
    @Test
    public void aCancelledStillIsConsumedButNotDrawn() {
        RecordingRenderer renderer = new RecordingRenderer();
        FrameRenderThread thread = started(renderer);
        thread.createStillInput(new Size(4000, 3000));
        SurfaceTexture still = stillTexture(thread);
        AtomicInteger drawn = new AtomicInteger();

        thread.drawStillInto(newSurface(), new Size(3000, 4000), GlMath.ScaleType.FILL_CENTER,
                copying(QUARTER_TURN), drawn::incrementAndGet);
        thread.cancelStill();
        ShadowFrameSurfaceTexture.deliverFrame(still);

        assertTrue(renderer.frames().isEmpty());
        assertEquals(0, drawn.get());
        assertEquals(1, ShadowFrameSurfaceTexture.updatesOf(still));
    }

    /**
     * A renderer that throws on a still does not leave its caller waiting: whoever armed it is
     * told, and the next frame is not mistaken for the still that failed.
     */
    @Test
    public void aStillTheRendererFailsOnStillTellsItsCaller() {
        RecordingRenderer renderer = new RecordingRenderer();
        renderer.failForWidth = 3000;
        FrameRenderThread thread = started(renderer);
        thread.createStillInput(new Size(4000, 3000));
        SurfaceTexture still = stillTexture(thread);
        AtomicInteger drawn = new AtomicInteger();

        thread.drawStillInto(newSurface(), new Size(3000, 4000), GlMath.ScaleType.FILL_CENTER,
                copying(QUARTER_TURN), drawn::incrementAndGet);
        ShadowFrameSurfaceTexture.deliverFrame(still);
        assertEquals(1, drawn.get());

        ShadowFrameSurfaceTexture.deliverFrame(still);
        assertEquals("the failed still is not drawn again", 1, renderer.attemptsAt(3000));
    }

    /**
     * A texture provider makes the camera's texture, is told of each frame before it is drawn,
     * and is handed it back at the end rather than having it deleted from under it.
     */
    @Test
    public void aTextureProviderOwnsTheCameraTexture() {
        RecordingTextureProvider provider = new RecordingTextureProvider(4242);
        RecordingRenderer renderer = new RecordingRenderer();
        FrameRenderThread thread = newThread(renderer);
        thread.setCameraTextureProvider(provider);
        assertTrue(thread.start());
        SurfaceTexture input = thread.getInputSurfaceTexture();
        thread.addOutput("preview", newSurface(), PORTRAIT, PORTRAIT,
                GlMath.ScaleType.FILL_CENTER, copying(MIRROR));

        ShadowFrameSurfaceTexture.deliverFrame(input);

        assertEquals(THREAD_NAME, provider.createdOn);
        assertEquals(1, provider.framesSeen.get());
        assertEquals("frames are drawn from the provider's texture",
                4242, renderer.frames().get(0).textureName);
        assertEquals("a provider that did not consume the frame has it consumed for it",
                1, ShadowFrameSurfaceTexture.updatesOf(input));

        thread.stop();

        assertEquals(4242, provider.released.get());
        assertFalse("a texture the renderer did not make is not the renderer's to delete",
                ShadowRecordingGles20.isTextureDeleted(4242));
    }

    /** A provider that consumed the frame through its own API does not have it consumed twice. */
    @Test
    public void aFrameTheProviderConsumedIsNotConsumedAgain() {
        RecordingTextureProvider provider = new RecordingTextureProvider(4242);
        provider.consumes = true;
        RecordingRenderer renderer = new RecordingRenderer();
        FrameRenderThread thread = newThread(renderer);
        thread.setCameraTextureProvider(provider);
        thread.start();
        thread.addOutput("preview", newSurface(), PORTRAIT, PORTRAIT,
                GlMath.ScaleType.FILL_CENTER, copying(MIRROR));

        ShadowFrameSurfaceTexture.deliverFrame(thread.getInputSurfaceTexture());

        assertEquals(0, ShadowFrameSurfaceTexture.updatesOf(thread.getInputSurfaceTexture()));
        assertEquals("and the frame is still drawn", 1, renderer.frames().size());
    }

    /** The texture is made when the renderer starts, so a provider cannot be changed after. */
    @Test
    public void theTextureProviderIsFixedOnceRunning() {
        FrameRenderThread thread = started(new RecordingRenderer());

        assertThrows(IllegalStateException.class,
                () -> thread.setCameraTextureProvider(new RecordingTextureProvider(1)));
    }

    /** A thread that has not been started, or has stopped, takes no outputs and no stills. */
    @Test
    public void aRendererThatIsNotRunningTakesNothing() {
        FrameRenderThread thread = newThread(new RecordingRenderer());

        thread.addOutput("preview", newSurface(), PORTRAIT, PORTRAIT,
                GlMath.ScaleType.FILL_CENTER, copying(MIRROR));
        thread.cancelStill();

        assertNull(thread.createStillInput(LANDSCAPE));
        assertFalse(thread.drawStillInto(newSurface(), LANDSCAPE, GlMath.ScaleType.FILL_CENTER,
                copying(MIRROR), () -> {}));
        assertTrue("nothing was drawn into anything", ShadowRecordingGles20.draws().isEmpty());
    }

    /** Arms a still the camera took at a time, counting its draws. */
    private static AtomicInteger armTimedStill(FrameRenderThread thread, long timestampNanos) {
        AtomicInteger drawn = new AtomicInteger();
        assertTrue(thread.drawStillInto(newSurface(), new Size(3000, 4000),
                GlMath.ScaleType.FILL_CENTER, copying(QUARTER_TURN), drawn::incrementAndGet,
                timestampNanos));
        return drawn;
    }

    /** Lets the render thread's clock run on, running what falls due. */
    private static void idleRenderThread(FrameRenderThread thread, long millis) {
        HandlerThread render = ReflectionHelpers.getField(thread, "mThread");
        Shadows.shadowOf(render.getLooper()).idleFor(Duration.ofMillis(millis));
    }

    /** An all-subject mask of one sample, made from a frame taken at this time. */
    private static SegmentationMask timedMask(long timestampNanos) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(Float.BYTES).order(ByteOrder.nativeOrder());
        buffer.putFloat(1f).rewind();
        return new SegmentationMask(buffer, 1, 1, false, timestampNanos);
    }

    private FrameRenderThread newThread(FrameRenderer renderer) {
        FrameRenderThread thread = new FrameRenderThread(THREAD_NAME);
        thread.setFrameRenderer(renderer);
        threads.add(thread);
        return thread;
    }

    private FrameRenderThread started(FrameRenderer renderer) {
        FrameRenderThread thread = newThread(renderer);
        assertTrue("Robolectric's EGL should give the renderer a context", thread.start());
        return thread;
    }

    /** The still input, which is the texture listening for frames that is not the camera's. */
    private static SurfaceTexture stillTexture(FrameRenderThread thread) {
        SurfaceTexture still = null;
        for (SurfaceTexture texture : ShadowFrameSurfaceTexture.listeningTextures()) {
            if (texture != thread.getInputSurfaceTexture()) {
                still = texture;
            }
        }
        assertNotNull("expected a still input to be listening for its frame", still);
        return still;
    }

    private static Surface newSurface() {
        return new Surface(new SurfaceTexture(0));
    }

    /** A transform that draws with this matrix whatever the camera reported. */
    private static FrameRenderThread.TextureTransform copying(float[] matrix) {
        return (surfaceTextureMatrix, into) -> System.arraycopy(matrix, 0, into, 0, matrix.length);
    }

    /** What a renderer was handed for one frame, copied, since the frame is reused. */
    private static final class DrawnFrame {
        final Size size;
        final long timestampNanos;
        final int textureName;
        final float[] textureMatrix;
        final String thread;

        DrawnFrame(GlFrame frame) {
            size = new Size(frame.getWidth(), frame.getHeight());
            timestampNanos = frame.getTimestampNanos();
            textureName = frame.getTextureName();
            textureMatrix = frame.getTextureMatrix().clone();
            thread = Thread.currentThread().getName();
        }
    }

    /** A renderer that remembers every frame it was handed, and draws nothing of its own. */
    private static final class RecordingRenderer implements FrameRenderer {
        /** Every frame handed over, in order, guarded by itself. */
        private final List<DrawnFrame> mFrames = new ArrayList<>();

        /** The widths of every frame handed over, including those it threw on. */
        private final List<Integer> mAttemptedWidths = new ArrayList<>();

        final AtomicInteger started = new AtomicInteger();
        final AtomicInteger stopped = new AtomicInteger();
        volatile String startedOn;

        /** What {@link #onDrawFrame} answers: true to take the frame, false to decline it. */
        volatile boolean drawsItself;

        /** Throws for a frame this wide, standing in for an output that has gone. */
        volatile int failForWidth = -1;

        /** Throws while starting, standing in for a renderer whose own setup fails. */
        volatile boolean failOnStart;

        @Override
        public void onRendererStarted() {
            started.incrementAndGet();
            startedOn = Thread.currentThread().getName();
            if (failOnStart) {
                throw new IllegalStateException("This renderer will not start");
            }
        }

        @Override
        public boolean onDrawFrame(@NonNull GlFrame frame) {
            synchronized (mFrames) {
                mAttemptedWidths.add(frame.getWidth());
                if (frame.getWidth() == failForWidth) {
                    throw new IllegalStateException("This output has gone");
                }
                mFrames.add(new DrawnFrame(frame));
            }
            return drawsItself;
        }

        @Override
        public void onRendererStopped() {
            stopped.incrementAndGet();
        }

        List<DrawnFrame> frames() {
            synchronized (mFrames) {
                return new ArrayList<>(mFrames);
            }
        }

        int attemptsAt(int width) {
            synchronized (mFrames) {
                int attempts = 0;
                for (int attempted : mAttemptedWidths) {
                    if (attempted == width) {
                        attempts++;
                    }
                }
                return attempts;
            }
        }

        int framesAt(Size size) {
            int count = 0;
            for (DrawnFrame frame : frames()) {
                if (frame.size.equals(size)) {
                    count++;
                }
            }
            return count;
        }
    }

    /** A provider that hands over a texture name of its choosing and records what it is told. */
    private static final class RecordingTextureProvider implements CameraTextureProvider {
        private final int mTextureName;
        volatile String createdOn;
        final AtomicInteger framesSeen = new AtomicInteger();
        final AtomicInteger released = new AtomicInteger();

        /** Whether this provider consumes each frame itself, as one with its own API would. */
        volatile boolean consumes;

        RecordingTextureProvider(int textureName) {
            mTextureName = textureName;
        }

        @Override
        public int onCreateCameraTexture() {
            createdOn = Thread.currentThread().getName();
            return mTextureName;
        }

        @Override
        public boolean onCameraFrameAvailable(@NonNull SurfaceTexture texture) {
            framesSeen.incrementAndGet();
            return consumes;
        }

        @Override
        public void onReleaseCameraTexture(int textureName) {
            released.set(textureName);
        }
    }
}
