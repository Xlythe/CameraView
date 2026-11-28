package com.xlythe.view.camera.x;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.media.FakeImage;
import android.media.ImageReader;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageInfo;
import androidx.camera.core.ImageProxy;

import com.xlythe.view.camera.ExifCaptureParameters;
import com.xlythe.view.camera.gl.FrameRenderer;
import com.xlythe.view.camera.gl.GlFrame;
import com.xlythe.view.camera.gl.GlMath;
import com.xlythe.view.camera.shadow.ShadowFrameSurfaceTexture;
import com.xlythe.view.camera.shadow.ShadowInjectableImageReader;
import com.xlythe.view.camera.shadow.ShadowRecordingGles20;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSurface;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A CameraX picture taken while frames are drawn: taken from the camera at its own resolution,
 * then drawn through the renderer, read back and written.
 *
 * <p>CameraX drew an effect's still from the stream it shares with the preview, so the picture
 * came out at the preview's resolution - 576x1280 against 836x1856 undrawn on the API 37
 * emulator. What the GPU draws cannot be looked at here; what goes into the renderer, at what
 * size and which way round, and what is written from what comes back, can.</p>
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {29, 37}, shadows = {ShadowFrameSurfaceTexture.class,
        ShadowRecordingGles20.class, ShadowInjectableImageReader.class, ShadowSurface.class})
public class DrawnStillTest {
    /** How long a test waits on the worker or the render thread. */
    private static final long TIMEOUT_SECONDS = 10;

    /** What a camera's SurfaceTexture typically reports: the buffer flipped top to bottom. */
    private static final float[] CAMERA_FLIP = {
            1, 0, 0, 0,
            0, -1, 0, 0,
            0, 0, 1, 0,
            0, 1, 0, 1,
    };

    /** The drawing read back, magenta so it cannot be mistaken for the camera's frame. */
    private static final byte[] MAGENTA = {(byte) 255, 0, (byte) 255, (byte) 255};

    private final RecordingRenderer renderer = new RecordingRenderer();
    private FrameEffect effect;
    private File output;

    @Before
    public void setUp() {
        ShadowFrameSurfaceTexture.reset();
        ShadowRecordingGles20.reset();
        ShadowInjectableImageReader.reset();
        ShadowFrameSurfaceTexture.setFrameTransform(CAMERA_FLIP);
        effect = FrameEffect.create(FrameEffect.TARGETS, Runnable::run, renderer, null,
                error -> {});
        assertNotNull(effect);
        output = new File(RuntimeEnvironment.getApplication().getCacheDir(), "drawn-still.jpg");
    }

    @After
    public void tearDown() {
        effect.release();
        if (output.exists() && !output.delete()) {
            output.deleteOnExit();
        }
    }

    /**
     * The camera's frame goes into the renderer at its own size, cropped to what the preview
     * showed, and is drawn the right way up; what comes back is written at the size it is shown,
     * with the drawing in it.
     */
    @Test
    public void aStillIsDrawnAtItsOwnSizeAndWrittenFromTheDrawing() throws Exception {
        // A frame the camera wrote landscape, cropped at the edges, to be turned a quarter.
        ImageProxy image = jpegImage(64, 48, new Rect(4, 0, 60, 48), 90);
        AtomicReference<Boolean> written = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        DrawnStill still = new DrawnStill(context(), image, false, effect, output, 90,
                new ExifCaptureParameters(), null, result -> {
                    written.set(result);
                    finished.countDown();
                });
        verify(image).close();

        Thread worker = new Thread(still, "DrawnStill");
        worker.start();
        SurfaceTexture stillInput = awaitStillInput();
        assertEquals("the frame goes in at its cropped size, as the camera wrote it",
                new Size(56, 48), ShadowFrameSurfaceTexture.bufferSizeOf(stillInput));
        ImageReader reader = awaitReader(PixelFormat.RGBA_8888);
        assertEquals("the drawing comes back turned", 48, reader.getWidth());
        assertEquals(56, reader.getHeight());
        awaitWaiting(worker);

        ShadowInjectableImageReader.offerImage(reader, FakeImage.rgba(48, 56, 8, MAGENTA));
        ShadowFrameSurfaceTexture.deliverFrame(stillInput);

        assertTrue("the picture was never written", await(finished));
        assertTrue(written.get());
        assertEquals("the renderer drew the still the way it is shown",
                new Size(48, 56), renderer.lastFrameSize());
        assertArrayEquals("with the matrix that turns the camera's frame upright",
                GlMath.uprightMatrix(CAMERA_FLIP, 90, false), renderer.lastMatrix(), 1e-5f);
        Bitmap file = BitmapFactory.decodeFile(output.getPath());
        assertNotNull(file);
        assertEquals(48, file.getWidth());
        assertEquals(56, file.getHeight());
        int pixel = file.getPixel(24, 28);
        assertTrue("the file is the drawing: " + Integer.toHexString(pixel),
                Color.red(pixel) > 200 && Color.green(pixel) < 60 && Color.blue(pixel) > 200);
    }

    /** A front camera's still is drawn mirrored, as its preview is. */
    @Test
    public void aFrontStillIsDrawnMirrored() throws Exception {
        ImageProxy image = jpegImage(32, 24, new Rect(0, 0, 32, 24), 270);
        CountDownLatch finished = new CountDownLatch(1);
        DrawnStill still = new DrawnStill(context(), image, true, effect, output, 90,
                new ExifCaptureParameters(), null, result -> finished.countDown());

        Thread worker = new Thread(still, "DrawnStill");
        worker.start();
        SurfaceTexture stillInput = awaitStillInput();
        ImageReader reader = awaitReader(PixelFormat.RGBA_8888);
        awaitWaiting(worker);
        ShadowInjectableImageReader.offerImage(reader, FakeImage.rgba(24, 32, 0, MAGENTA));
        ShadowFrameSurfaceTexture.deliverFrame(stillInput);

        assertTrue(await(finished));
        assertArrayEquals(GlMath.uprightMatrix(CAMERA_FLIP, 270, true), renderer.lastMatrix(),
                1e-5f);
    }

    /** A renderer that has stopped takes no still, and the picture is reported failed. */
    @Test
    public void aStillForAStoppedRendererFails() throws Exception {
        effect.release();
        ImageProxy image = jpegImage(32, 24, new Rect(0, 0, 32, 24), 0);
        AtomicReference<Boolean> written = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        new DrawnStill(context(), image, false, effect, output, 90, new ExifCaptureParameters(),
                null, result -> {
                    written.set(result);
                    finished.countDown();
                }).run();

        assertTrue(finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertFalse(written.get());
        assertFalse("nothing is left behind", output.exists());
    }

    @Test
    public void rotationsAreHeldToQuarterTurns() {
        assertEquals(0, DrawnStill.toRotation(0));
        assertEquals(90, DrawnStill.toRotation(90));
        assertEquals(270, DrawnStill.toRotation(-90));
        assertEquals(180, DrawnStill.toRotation(540));
    }

    /**
     * Waits for the worker, running the main looper meanwhile: the reader tells the worker the
     * drawing has arrived from there.
     */
    private static boolean await(CountDownLatch latch) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS);
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper();
            if (latch.await(10, TimeUnit.MILLISECONDS)) {
                return true;
            }
        }
        return false;
    }

    private static Context context() {
        return RuntimeEnvironment.getApplication();
    }

    /** An in-memory JPEG capture, as CameraX hands one over. */
    private static ImageProxy jpegImage(int width, int height, Rect crop, int rotationDegrees) {
        Bitmap frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        frame.eraseColor(Color.DKGRAY);
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        frame.compress(Bitmap.CompressFormat.JPEG, 90, jpeg);
        frame.recycle();
        ImageProxy.PlaneProxy plane = mock(ImageProxy.PlaneProxy.class);
        when(plane.getBuffer()).thenReturn(ByteBuffer.wrap(jpeg.toByteArray()));
        ImageInfo info = mock(ImageInfo.class);
        when(info.getRotationDegrees()).thenReturn(rotationDegrees);
        ImageProxy image = mock(ImageProxy.class);
        when(image.getPlanes()).thenReturn(new ImageProxy.PlaneProxy[] {plane});
        when(image.getCropRect()).thenReturn(crop);
        when(image.getWidth()).thenReturn(width);
        when(image.getHeight()).thenReturn(height);
        when(image.getImageInfo()).thenReturn(info);
        return image;
    }

    /** The renderer's still input, once the worker has made it. */
    private static SurfaceTexture awaitStillInput() throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS);
        while (System.currentTimeMillis() < deadline) {
            List<SurfaceTexture> listening = ShadowFrameSurfaceTexture.listeningTextures();
            // The first is the camera's input; a second is made for the still.
            if (listening.size() > 1) {
                return listening.get(listening.size() - 1);
            }
            Thread.sleep(5);
        }
        throw new AssertionError("the still never went into the renderer");
    }

    /** The newest reader of a format, once the worker has made it. */
    private static ImageReader awaitReader(int format) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS);
        while (System.currentTimeMillis() < deadline) {
            ImageReader reader = ShadowInjectableImageReader.latestReader(format);
            if (reader != null) {
                return reader;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("nothing was made to read the drawing back into");
    }

    /**
     * Waits until the worker is waiting on the renderer to draw, having handed the frame over,
     * rather than still waiting on the renderer to make its input.
     */
    private static void awaitWaiting(Thread worker) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS);
        while (System.currentTimeMillis() < deadline) {
            if (worker.getState() == Thread.State.TIMED_WAITING && !isMakingTheInput(worker)) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("the worker never handed the frame over");
    }

    private static boolean isMakingTheInput(Thread worker) {
        for (StackTraceElement element : worker.getStackTrace()) {
            if (element.getMethodName().equals("createStillInput")) {
                return true;
            }
        }
        return false;
    }

    /** Remembers the size and matrix of every frame it was handed, and draws nothing. */
    private static final class RecordingRenderer implements FrameRenderer {
        /** The size of each frame, guarded by itself. */
        private final List<Size> mSizes = new ArrayList<>();
        /** The matrix of each frame, guarded by {@link #mSizes}. */
        private final List<float[]> mMatrices = new ArrayList<>();

        @Override
        public boolean onDrawFrame(@NonNull GlFrame frame) {
            synchronized (mSizes) {
                mSizes.add(new Size(frame.getWidth(), frame.getHeight()));
                mMatrices.add(frame.getTextureMatrix().clone());
            }
            return false;
        }

        Size lastFrameSize() {
            synchronized (mSizes) {
                return mSizes.get(mSizes.size() - 1);
            }
        }

        float[] lastMatrix() {
            synchronized (mSizes) {
                return mMatrices.get(mMatrices.size() - 1);
            }
        }
    }
}
