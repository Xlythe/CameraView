package com.xlythe.view.camera.x;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.SurfaceTexture;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraEffect;
import androidx.camera.core.ProcessingException;
import androidx.camera.core.SurfaceOutput;
import androidx.camera.core.SurfaceProcessor;
import androidx.camera.core.SurfaceRequest;
import androidx.core.util.Consumer;

import com.xlythe.view.camera.gl.CameraTextureProvider;
import com.xlythe.view.camera.gl.FrameRenderer;
import com.xlythe.view.camera.gl.GlFrame;
import com.xlythe.view.camera.gl.TimeLapse;
import com.xlythe.view.camera.shadow.ShadowFrameSurfaceTexture;
import com.xlythe.view.camera.shadow.ShadowRecordingGles20;
import com.xlythe.view.camera.shadow.ShadowUnavailableEgl;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The effect CameraX is handed, driven the way CameraX drives it: an input surface asked for,
 * outputs handed over and taken back, and the whole thing released.
 *
 * <p>Binding an effect to a camera cannot happen off a device, for the reasons recorded on
 * {@code CameraXHarnessTest}: CameraX's own GL machinery comes apart in a JVM before any of this
 * library's code runs. The processor underneath does not need any of that. CameraX's side of the
 * conversation is a {@link SurfaceRequest} and a {@link SurfaceOutput}, which are stood in for
 * here, and the renderer's side is Robolectric's EGL, which runs.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, shadows = {ShadowFrameSurfaceTexture.class, ShadowRecordingGles20.class})
public class FrameEffectTest {
    private static final float TOLERANCE = 1e-5f;

    /** What a camera's SurfaceTexture typically reports: the buffer flipped top to bottom. */
    private static final float[] CAMERA_FLIP = {
            1, 0, 0, 0,
            0, -1, 0, 0,
            0, 0, 1, 0,
            0, 1, 0, 1,
    };

    /** A mirror left to right, standing in for a matrix only CameraX could have worked out. */
    private static final float[] MIRROR = {
            -1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            1, 0, 0, 1,
    };

    private static final Size LANDSCAPE = new Size(1920, 1080);
    private static final Size PORTRAIT = new Size(1080, 1920);

    /** Runs CameraX's callbacks where they are made, as the main-thread executor would. */
    private final Executor directExecutor = Runnable::run;

    /** Every effect a test made, so none outlives it. */
    private final List<FrameEffect> effects = new ArrayList<>();

    @Before
    public void setUp() {
        ShadowFrameSurfaceTexture.reset();
        ShadowRecordingGles20.reset();
        ShadowFrameSurfaceTexture.setFrameTransform(CAMERA_FLIP);
    }

    @After
    public void tearDown() {
        for (FrameEffect effect : effects) {
            effect.release();
        }
    }

    /** One effect covers the preview, the recording and the still, so a filter is in all three. */
    @Test
    public void anEffectReachesEveryOutput() {
        FrameEffect effect = create(new RecordingRenderer(), null);

        assertEquals(CameraEffect.PREVIEW | CameraEffect.VIDEO_CAPTURE
                | CameraEffect.IMAGE_CAPTURE, effect.getTargets());
    }

    /** Where GL will not run there is no effect, and the caller binds without one. */
    @Test
    @Config(shadows = {ShadowUnavailableEgl.class})
    public void whereGlWillNotRunThereIsNoEffect() {
        RecordingRenderer renderer = new RecordingRenderer();

        assertNull(FrameEffect.create(FrameEffect.ALL_TARGETS, directExecutor, renderer, null,
                error -> {}));
        assertEquals(0, renderer.started.get());
    }

    /** CameraX is given a surface over the renderer's input, sized for what it will write. */
    @Test
    public void theCameraWritesIntoTheRenderersInput() throws ProcessingException {
        FrameEffect effect = create(new RecordingRenderer(), null);
        SurfaceRequest request = request(LANDSCAPE);

        processor(effect).onInputSurface(request);

        verify(request).provideSurface(any(Surface.class), any(Executor.class), any());
        verify(request, never()).willNotProvideSurface();
        assertEquals(LANDSCAPE, ShadowFrameSurfaceTexture.bufferSizeOf(input()));
    }

    /**
     * An output is drawn at its own size with the matrix CameraX publishes for it, which is where
     * the rotation, the mirror and the crop CameraX worked out come from.
     */
    @Test
    public void anOutputIsDrawnWithTheMatrixCameraXPublishes() throws ProcessingException {
        RecordingRenderer renderer = new RecordingRenderer();
        FrameEffect effect = create(renderer, null);
        FakeSurfaceOutput output = new FakeSurfaceOutput(PORTRAIT, MIRROR);

        processor(effect).onOutputSurface(output);
        ShadowFrameSurfaceTexture.deliverFrame(input());

        assertEquals(1, renderer.frames().size());
        assertEquals(PORTRAIT, renderer.frames().get(0).size);
        assertArrayEquals(MIRROR, renderer.frames().get(0).textureMatrix, TOLERANCE);
    }

    /**
     * How the frames are shown turns the renderer's drawing to match, for the one shared output
     * CameraX turns again for each use case afterwards.
     */
    @Test
    public void theDrawingIsTurnedToHowTheFramesAreShown() throws ProcessingException {
        RecordingRenderer renderer = new RecordingRenderer();
        FrameEffect effect = create(renderer, null);
        processor(effect).onInputSurface(request(LANDSCAPE));
        processor(effect).onOutputSurface(new FakeSurfaceOutput(LANDSCAPE, CAMERA_FLIP));

        effect.setShownOrientation(90, false);
        ShadowFrameSurfaceTexture.deliverFrame(input());

        assertEquals("drawn tall, as it is shown", PORTRAIT, renderer.frames().get(0).size);
    }

    /** An output CameraX asks for back stops being drawn and is closed. */
    @Test
    public void anOutputCameraXTakesBackIsClosed() throws ProcessingException {
        RecordingRenderer renderer = new RecordingRenderer();
        FrameEffect effect = create(renderer, null);
        FakeSurfaceOutput output = new FakeSurfaceOutput(PORTRAIT, MIRROR);
        processor(effect).onOutputSurface(output);

        output.requestClose();
        ShadowFrameSurfaceTexture.deliverFrame(input());

        assertTrue(output.closed);
        assertTrue("nothing left to draw into", renderer.frames().isEmpty());
    }

    /**
     * Releasing closes every output and stops the renderer, and an effect that has been released
     * refuses what CameraX hands it afterwards - answering the request rather than leaving
     * CameraX waiting on it.
     */
    @Test
    public void aReleasedEffectClosesEverythingAndRefusesMore() {
        RecordingRenderer renderer = new RecordingRenderer();
        FrameEffect effect = create(renderer, null);
        SurfaceProcessor processor = processor(effect);
        FakeSurfaceOutput preview = new FakeSurfaceOutput(PORTRAIT, MIRROR);
        FakeSurfaceOutput recording = new FakeSurfaceOutput(LANDSCAPE, MIRROR);
        try {
            processor.onOutputSurface(preview);
            processor.onOutputSurface(recording);
        } catch (ProcessingException error) {
            throw new AssertionError(error);
        }

        effect.release();
        effect.release();

        assertTrue(preview.closed);
        assertTrue(recording.closed);
        assertEquals("stopped once, however often it is released", 1, renderer.stopped.get());

        FakeSurfaceOutput late = new FakeSurfaceOutput(PORTRAIT, MIRROR);
        assertThrows(ProcessingException.class, () -> processor.onOutputSurface(late));
        assertTrue(late.closed);
        SurfaceRequest request = request(LANDSCAPE);
        assertThrows(ProcessingException.class, () -> processor.onInputSurface(request));
        verify(request).willNotProvideSurface();
    }

    /**
     * What draws can change without a new effect; whose texture the camera writes into cannot,
     * since the texture is made once.
     */
    @Test
    public void theRendererChangesInPlaceAndTheTextureDoesNot() throws ProcessingException {
        RecordingRenderer first = new RecordingRenderer();
        RecordingRenderer second = new RecordingRenderer();
        CameraTextureProvider provider = new FixedTextureProvider();
        FrameEffect effect = create(first, provider);
        processor(effect).onOutputSurface(new FakeSurfaceOutput(PORTRAIT, MIRROR));

        effect.setFrameRenderer(second);
        ShadowFrameSurfaceTexture.deliverFrame(input());

        assertTrue(first.frames().isEmpty());
        assertEquals(1, second.frames().size());
        assertTrue(effect.usesTextureFrom(provider));
        assertFalse(effect.usesTextureFrom(new FixedTextureProvider()));
        assertFalse(effect.usesTextureFrom(null));
    }

    /**
     * For a time-lapse the pipeline splits in two: the preview through the processor it always
     * had, and the recording through a processor of its own on the same thread.
     */
    @Test
    public void aTimeLapseSplitsThePreviewFromTheRecording() {
        FrameEffect effect = create(new RecordingRenderer(), null);

        CameraEffect preview = effect.forPreview();
        CameraEffect recording = effect.startTimeLapse(new TimeLapse(10, 30));

        assertEquals(CameraEffect.PREVIEW, preview.getTargets());
        assertSame("the preview keeps the pipeline it had",
                effect.getSurfaceProcessor(), preview.getSurfaceProcessor());
        assertEquals(CameraEffect.VIDEO_CAPTURE, recording.getTargets());
        assertNotSame(effect.getSurfaceProcessor(), recording.getSurfaceProcessor());
    }

    /**
     * The recording's camera stream is a second input, and once the recording has started its
     * frames are drawn into the recording alone, as the time-lapse keeps them, through the same
     * renderer. The preview's frames do not reach it.
     */
    @Test
    public void aTimeLapseRecordingIsDrawnFromAStreamOfItsOwn() throws ProcessingException {
        RecordingRenderer renderer = new RecordingRenderer();
        FrameEffect effect = create(renderer, null);
        TimeLapse timeLapse = new TimeLapse(10, 30);
        SurfaceProcessor recording = effect.startTimeLapse(timeLapse).getSurfaceProcessor();
        assertNotNull(recording);
        SurfaceRequest request = request(LANDSCAPE);

        recording.onInputSurface(request);
        recording.onOutputSurface(new FakeSurfaceOutput(LANDSCAPE, MIRROR));
        SurfaceTexture recordingInput = secondInput();
        timeLapse.start();
        ShadowFrameSurfaceTexture.setFrameTimestamp(System.nanoTime());
        ShadowFrameSurfaceTexture.deliverFrame(firstInput());
        ShadowFrameSurfaceTexture.deliverFrame(recordingInput);

        verify(request).provideSurface(any(Surface.class), any(Executor.class), any());
        assertEquals(LANDSCAPE, ShadowFrameSurfaceTexture.bufferSizeOf(recordingInput));
        assertEquals("only the recording's own frame is drawn", 1, renderer.frames().size());
        assertEquals(LANDSCAPE, renderer.frames().get(0).size);
    }

    /**
     * Stopping the time-lapse closes the recording and gives its stream back, and leaves the
     * pipeline running; releasing the effect does both.
     */
    @Test
    public void aStoppedTimeLapseGivesItsStreamBack() throws ProcessingException {
        RecordingRenderer renderer = new RecordingRenderer();
        FrameEffect effect = create(renderer, null);
        SurfaceProcessor first = effect.startTimeLapse(new TimeLapse(10, 30))
                .getSurfaceProcessor();
        assertNotNull(first);
        FakeSurfaceOutput output = new FakeSurfaceOutput(LANDSCAPE, MIRROR);
        first.onInputSurface(request(LANDSCAPE));
        first.onOutputSurface(output);
        SurfaceTexture firstInput = secondInput();

        effect.stopTimeLapse();
        ShadowFrameSurfaceTexture.flush(ShadowFrameSurfaceTexture.handlerOf(firstInput()));

        assertTrue(output.closed);
        assertTrue(ShadowFrameSurfaceTexture.isReleased(firstInput));
        assertEquals("the pipeline is still running", 0, renderer.stopped.get());
        SurfaceRequest late = request(LANDSCAPE);
        assertThrows(ProcessingException.class, () -> first.onInputSurface(late));
        verify(late).willNotProvideSurface();

        SurfaceProcessor second = effect.startTimeLapse(new TimeLapse(10, 30))
                .getSurfaceProcessor();
        assertNotNull(second);
        FakeSurfaceOutput secondOutput = new FakeSurfaceOutput(LANDSCAPE, MIRROR);
        second.onOutputSurface(secondOutput);
        effect.release();

        assertTrue("released with the effect", secondOutput.closed);
        assertEquals(1, renderer.stopped.get());
    }

    private FrameEffect create(FrameRenderer renderer, CameraTextureProvider provider) {
        Consumer<Throwable> errors = error -> {
            throw new AssertionError("CameraX reported an error", error);
        };
        FrameEffect effect = FrameEffect.create(FrameEffect.ALL_TARGETS, directExecutor, renderer,
                provider, errors);
        assertNotNull("Robolectric's EGL should give the effect a context", effect);
        effects.add(effect);
        return effect;
    }

    private static SurfaceProcessor processor(FrameEffect effect) {
        SurfaceProcessor processor = effect.getSurfaceProcessor();
        assertNotNull(processor);
        return processor;
    }

    /** The renderer's input, which is the only texture listening for camera frames. */
    private static SurfaceTexture input() {
        List<SurfaceTexture> listening = ShadowFrameSurfaceTexture.listeningTextures();
        assertEquals("one camera input", 1, listening.size());
        return listening.get(0);
    }

    /** The camera's main input, which is the first texture listening for frames. */
    private static SurfaceTexture firstInput() {
        return ShadowFrameSurfaceTexture.listeningTextures().get(0);
    }

    /** A second camera stream's input, which is the second texture listening for frames. */
    private static SurfaceTexture secondInput() {
        List<SurfaceTexture> listening = ShadowFrameSurfaceTexture.listeningTextures();
        assertEquals("the main input and the recording's", 2, listening.size());
        return listening.get(1);
    }

    /** CameraX asking for somewhere to write frames of this size. */
    private static SurfaceRequest request(Size resolution) {
        SurfaceRequest request = mock(SurfaceRequest.class);
        when(request.getResolution()).thenReturn(resolution);
        return request;
    }

    /** What a renderer was handed for one frame, copied, since the frame is reused. */
    private static final class DrawnFrame {
        final Size size;
        final float[] textureMatrix;

        DrawnFrame(GlFrame frame) {
            size = new Size(frame.getWidth(), frame.getHeight());
            textureMatrix = frame.getTextureMatrix().clone();
        }
    }

    /** A renderer that remembers every frame it was handed, and declines to draw any of them. */
    private static final class RecordingRenderer implements FrameRenderer {
        /** Every frame handed over, in order, guarded by itself. */
        private final List<DrawnFrame> mFrames = new ArrayList<>();

        final AtomicInteger started = new AtomicInteger();
        final AtomicInteger stopped = new AtomicInteger();

        @Override
        public void onRendererStarted() {
            started.incrementAndGet();
        }

        @Override
        public boolean onDrawFrame(@NonNull GlFrame frame) {
            synchronized (mFrames) {
                mFrames.add(new DrawnFrame(frame));
            }
            return false;
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
    }

    /** A provider whose texture is a fixed name and whose frames the renderer consumes. */
    private static final class FixedTextureProvider implements CameraTextureProvider {
        @Override
        public int onCreateCameraTexture() {
            return 4242;
        }

        @Override
        public boolean onCameraFrameAvailable(@NonNull SurfaceTexture texture) {
            return false;
        }

        @Override
        public void onReleaseCameraTexture(int textureName) {}
    }

    /** One of CameraX's outputs, with a size and the matrix CameraX would publish for it. */
    private static final class FakeSurfaceOutput implements SurfaceOutput {
        private final Size mSize;
        private final float[] mMatrix;
        private final Surface mSurface = new Surface(new SurfaceTexture(0));

        /** Where CameraX's request for the output back is delivered. */
        private Consumer<Event> mCloseRequests;

        /** Whether the effect has closed this output. */
        boolean closed;

        FakeSurfaceOutput(Size size, float[] matrix) {
            mSize = size;
            mMatrix = matrix;
        }

        /** Asks for the output back, as CameraX does when its use case is unbound. */
        void requestClose() {
            mCloseRequests.accept(Event.of(Event.EVENT_REQUEST_CLOSE, this));
        }

        @NonNull
        @Override
        public Surface getSurface(@NonNull Executor executor,
                @NonNull Consumer<Event> listener) {
            mCloseRequests = listener;
            return mSurface;
        }

        @Override
        public int getTargets() {
            return CameraEffect.PREVIEW;
        }

        @NonNull
        @Override
        public Size getSize() {
            return mSize;
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public void updateTransformMatrix(@NonNull float[] updated, @NonNull float[] original) {
            System.arraycopy(mMatrix, 0, updated, 0, mMatrix.length);
        }
    }
}
