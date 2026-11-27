package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.media.FakeImage;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.media.ImageReader;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Size;

import androidx.annotation.NonNull;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.CaptureDelivery;
import com.xlythe.view.camera.Exif;
import com.xlythe.view.camera.gl.FrameRenderThread;
import com.xlythe.view.camera.gl.FrameRenderer;
import com.xlythe.view.camera.gl.GlFrame;
import com.xlythe.view.camera.shadow.ShadowCallbackCameraCaptureSession;
import com.xlythe.view.camera.shadow.ShadowFrameSurfaceTexture;
import com.xlythe.view.camera.shadow.ShadowInjectableImageReader;
import com.xlythe.view.camera.shadow.ShadowRecordingGles20;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPausedAsyncTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A Camera2 camera with an effect running: the preview drawn through the renderer, and a
 * photograph taken through the renderer's own full-size input and written from what it drew.
 *
 * <p>What the renderer draws needs a GPU. What does not is everything around it: that the camera
 * writes into the renderer rather than the view, that the still is drawn the right way up at the
 * camera's resolution, that the picture read back from the renderer is cropped out of its padded
 * rows rather than sheared, and that the file is finished like any other photograph. Native
 * graphics, so the JPEG written can be decoded and looked at.</p>
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {29, 37}, qualifiers = "w360dp-h800dp-xxxhdpi", shadows = {
        ShadowCallbackCameraCaptureSession.class,
        ShadowInjectableImageReader.class,
        ShadowFrameSurfaceTexture.class,
        ShadowRecordingGles20.class})
public class FrameEffectSessionTest {
    /** What a camera's SurfaceTexture typically reports: the buffer flipped top to bottom. */
    private static final float[] CAMERA_FLIP = {
            1, 0, 0, 0,
            0, -1, 0, 0,
            0, 0, 1, 0,
            0, 1, 0, 1,
    };

    /**
     * The picture's colour, magenta so that it reads the same whichever way round the red and
     * blue channels are stored, and as far from the padding's green as a colour gets.
     */
    private static final byte[] MAGENTA = {(byte) 200, 40, (byte) 200, (byte) 0xFF};

    /** The largest JPEG size the fake camera lists, which is the still the camera would write. */
    private static final Size STILL = FakeCamera2.SIZE_1080P;

    private FakeCamera2.TestCameraView cameraView;
    private Camera2Module module;
    private File output;
    private RecordingRenderer renderer;
    private final AtomicReference<File> captured = new AtomicReference<>();
    private final AtomicBoolean failed = new AtomicBoolean();
    /** How many times the shutter was reported. */
    private final AtomicInteger shutters = new AtomicInteger();
    /** Where each picture taken without a file was filed, in the order the listener heard. */
    private final List<Uri> saved = new ArrayList<>();

    @Before
    public void setUp() {
        ShadowCallbackCameraCaptureSession.reset();
        ShadowInjectableImageReader.reset();
        ShadowFrameSurfaceTexture.reset();
        ShadowRecordingGles20.reset();
        ShadowFrameSurfaceTexture.setFrameTransform(CAMERA_FLIP);

        Context context = RuntimeEnvironment.getApplication();
        // Cameras that write RAW, so that what a renderer withdraws is there to withdraw.
        FakeCamera2.addBackAndFrontCameras(context, FakeCamera2.RawSupport.FULL);
        cameraView = FakeCamera2.laidOutCameraView(context, 1080, 1920);
        cameraView.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
            @Override public void onShutter() { shutters.incrementAndGet(); }
            @Override public void onImageConfirmation() {}
            @Override public void onImageCaptured(File file) { captured.set(file); }
            @Override public void onImageSaved(@NonNull Uri uri) { saved.add(uri); }
            @Override public void onFailure() { failed.set(true); }
        });
        // The drawn picture is saved on AsyncTask's pool. Run it inline, so a capture finishes
        // within the test rather than on a thread nothing waits for.
        ShadowPausedAsyncTask.overrideExecutor(Runnable::run);
        module = new Camera2Module(cameraView);
        renderer = new RecordingRenderer();
        output = new File(context.getCacheDir(), "drawn-picture.jpg");
    }

    @After
    public void tearDown() {
        module.close();
        FakeCamera2.idleMainLooper();
        cameraView.release();
        if (output.exists() && !output.delete()) {
            output.deleteOnExit();
        }
    }

    /** With a renderer set, opening the camera brings the renderer up before the camera. */
    @Test
    public void openingWithARendererStartsIt() {
        module.setFrameRenderer(renderer);
        module.open();
        FakeCamera2.idleMainLooper();

        assertNotNull("the camera's frames go through GL", module.getEffect());
        assertEquals(1, renderer.started.get());
        assertTrue(module.isFrameEffectSupported());
    }

    /**
     * The preview reaches the screen through the renderer, which draws it the right way up and
     * in the shape it is shown - tall, on a phone held upright, although the camera writes it
     * wide.
     */
    @Test
    public void thePreviewIsDrawnThroughTheRendererAsItIsShown() {
        module.setFrameRenderer(renderer);
        module.open();
        FakeCamera2.idleMainLooper();
        FrameRenderThread effect = module.getEffect();
        assertNotNull(effect);

        ShadowFrameSurfaceTexture.deliverFrame(effect.getInputSurfaceTexture());

        List<Size> frames = renderer.frameSizes();
        assertEquals("one output, the preview", 1, frames.size());
        Size buffer = ShadowFrameSurfaceTexture.bufferSizeOf(effect.getInputSurfaceTexture());
        assertNotNull("the renderer's input is told the size the camera writes", buffer);
        assertTrue("the camera writes the preview wide", buffer.getWidth() > buffer.getHeight());
        assertEquals("and the renderer draws it tall, as it is shown",
                new Size(buffer.getHeight(), buffer.getWidth()), frames.get(0));
    }

    /**
     * Closing the camera takes the renderer down, and opening it again brings the same renderer
     * back - an app that sets a renderer once and then rotates expects it to still be there.
     */
    @Test
    public void aRendererOutlivesTheCameraBeingClosed() {
        module.setFrameRenderer(renderer);
        module.open();
        FakeCamera2.idleMainLooper();

        module.close();
        FakeCamera2.idleMainLooper();
        assertNull(module.getEffect());
        assertEquals(1, renderer.stopped.get());

        module.open();
        FakeCamera2.idleMainLooper();
        assertNotNull(module.getEffect());
        assertEquals(2, renderer.started.get());
    }

    /** Clearing the renderer takes the camera back off GL, straight to the preview. */
    @Test
    public void clearingTheRendererTakesTheCameraOffGl() {
        module.setFrameRenderer(renderer);
        module.open();
        FakeCamera2.idleMainLooper();

        module.setFrameRenderer(null);
        FakeCamera2.idleMainLooper();

        assertNull(module.getEffect());
        assertEquals(1, renderer.stopped.get());
    }

    /**
     * A photograph is drawn by the renderer at the camera's own resolution, the right way up as
     * it is shown, and written from what the renderer drew: the padded rows the reader hands
     * back are cropped rather than read as picture, and the file is stamped like any other.
     */
    @Test
    public void aPictureIsDrawnAtTheCamerasResolutionAndWrittenFromTheDrawing()
            throws IOException {
        module.setFrameRenderer(renderer);
        module.open();
        FakeCamera2.idleMainLooper();
        FrameRenderThread effect = module.getEffect();
        assertNotNull(effect);
        SurfaceTexture still = stillInput(effect);
        assertEquals("the camera writes the still at its full size",
                STILL, ShadowFrameSurfaceTexture.bufferSizeOf(still));

        module.takePicture(output);
        FakeCamera2.idleMainLooper();
        ShadowFrameSurfaceTexture.deliverFrame(still);

        Size upright = new Size(STILL.getHeight(), STILL.getWidth());
        assertEquals("the still is drawn tall, as the phone was held",
                upright, renderer.frameSizes().get(renderer.frameSizes().size() - 1));
        ImageReader drawn = ShadowInjectableImageReader.latestReader();
        assertNotNull("the drawing is read back from a reader of its own", drawn);
        assertEquals(PixelFormat.RGBA_8888, drawn.getImageFormat());
        assertEquals(upright.getWidth(), drawn.getWidth());
        assertEquals(upright.getHeight(), drawn.getHeight());

        ShadowInjectableImageReader.offerImage(drawn,
                FakeImage.rgba(upright.getWidth(), upright.getHeight(), 16, MAGENTA));
        FakeCamera2.idleMainLooper();
        FakeCamera2.idleMainLooper();

        assertFalse("should not have reported a failure", failed.get());
        assertEquals(output, captured.get());
        Bitmap written = BitmapFactory.decodeFile(output.getPath());
        assertNotNull("a JPEG was written", written);
        assertEquals(upright.getWidth(), written.getWidth());
        assertEquals(upright.getHeight(), written.getHeight());
        assertNoPadding(written);
        assertNotEquals("the file carries when it was taken",
                -1L, new Exif(output).getTimestamp());
        assertNotSame("the reader is given back once its one frame is read",
                drawn, ShadowInjectableImageReader.latestReader());
    }

    /**
     * RAW is withdrawn while a renderer draws, as Ultra HDR is: a drawn picture is a JPEG. A RAW
     * format chosen before the renderer was set leaves the session without a sensor reader, and a
     * picture asked for in it is refused rather than quietly written as a JPEG.
     */
    @Test
    public void aRendererWithdrawsRawAndRefusesAPictureAskedForInIt() {
        module.setPictureFormat(CameraView.PictureFormat.RAW_JPEG);
        assertEquals("this camera writes RAW",
                CameraView.PictureFormat.RAW_JPEG, module.getPictureFormat());
        module.setFrameRenderer(renderer);
        module.open();
        FakeCamera2.idleMainLooper();

        assertFalse(module.isPictureFormatSupported(CameraView.PictureFormat.RAW));
        assertFalse(module.isPictureFormatSupported(CameraView.PictureFormat.RAW_JPEG));
        assertTrue(module.isPictureFormatSupported(CameraView.PictureFormat.JPEG));
        assertNull("no sensor reader while frames are drawn",
                ShadowInjectableImageReader.latestReader(ImageFormat.RAW_SENSOR));

        module.takePicture(output);
        FakeCamera2.idleMainLooper();

        assertTrue("the picture is refused", failed.get());
        assertNull(captured.get());
        assertEquals("nothing is asked of the camera", 0, stills().size());

        module.setFrameRenderer(null);
        FakeCamera2.idleMainLooper();
        assertTrue("and RAW is offered again once nothing draws",
                module.isPictureFormatSupported(CameraView.PictureFormat.RAW));
    }

    /**
     * A drawn picture with the flash on waits for auto-exposure to meter with a pre-flash, as a
     * camera frame does, and reports its shutter once the frame is exposed.
     */
    @Test
    public void aDrawnFlashPictureIsMeteredAndReportsItsShutter() {
        ShadowCallbackCameraCaptureSession.setAeState(CaptureResult.CONTROL_AE_STATE_PRECAPTURE);
        module.setFlash(CameraView.Flash.ON);
        module.setFrameRenderer(renderer);
        module.open();
        FakeCamera2.idleMainLooper();
        FrameRenderThread effect = module.getEffect();
        assertNotNull(effect);
        SurfaceTexture still = stillInput(effect);

        module.takePicture(output);
        FakeCamera2.idleMainLooper();

        assertEquals("metering starts first", 1, precaptureTriggers().size());
        assertEquals("and the picture waits for it", 0, stills().size());
        assertEquals(0, shutters.get());

        ShadowCallbackCameraCaptureSession.setAeState(
                CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED);
        ShadowCallbackCameraCaptureSession.deliverRepeatingFrame();
        FakeCamera2.idleMainLooper();

        assertEquals(1, stills().size());
        assertEquals("the shutter is reported as the frame is exposed", 1, shutters.get());
        deliverDrawnPicture(still);
        assertFalse("should not have reported a failure", failed.get());
        assertEquals(output, captured.get());
    }

    /**
     * A drawn picture taken without a file of the caller's is filed in the gallery as a JPEG, and
     * leaves nothing behind in the cache it was written to first.
     */
    @Test
    public void aDrawnPictureWithoutAFileIsFiled() throws InterruptedException {
        module.setFrameRenderer(renderer);
        module.open();
        FakeCamera2.idleMainLooper();
        FrameRenderThread effect = module.getEffect();
        assertNotNull(effect);
        SurfaceTexture still = stillInput(effect);
        File own = CaptureDelivery.ownPictureFile(cameraView, CameraView.PictureFormat.JPEG);

        module.takePicture(own);
        FakeCamera2.idleMainLooper();
        deliverDrawnPicture(still);
        awaitFiling();

        assertFalse("should not have reported a failure", failed.get());
        assertEquals(1, saved.size());
        List<ShadowContentResolver.InsertStatement> inserts =
                Shadows.shadowOf(cameraView.getContext().getContentResolver())
                        .getInsertStatements();
        assertEquals(1, inserts.size());
        ContentValues row = inserts.get(0).getContentValues();
        assertEquals(own.getName(), row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        assertEquals("image/jpeg", row.getAsString(MediaStore.MediaColumns.MIME_TYPE));
        File[] left = CaptureDelivery.captureDirectory(cameraView).listFiles();
        assertTrue("left in the cache: " + Arrays.toString(left),
                left == null || left.length == 0);
    }

    /** Closing the camera while a picture is being drawn reports it lost, and leaves no file. */
    @Test
    public void closingWhileAPictureIsDrawnReportsItLost() {
        module.setFrameRenderer(renderer);
        module.open();
        FakeCamera2.idleMainLooper();

        module.takePicture(output);
        FakeCamera2.idleMainLooper();
        module.close();
        FakeCamera2.idleMainLooper();

        assertTrue("the picture is reported lost", failed.get());
        assertNull(captured.get());
        assertFalse(output.exists());
    }

    /**
     * A second press while a picture is still being drawn takes its place, since the renderer
     * draws one still at a time, and the first is reported lost rather than never reported.
     */
    @Test
    public void aSecondPressTakesThePlaceOfAPictureStillBeingDrawn() {
        module.setFrameRenderer(renderer);
        module.open();
        FakeCamera2.idleMainLooper();
        FrameRenderThread effect = module.getEffect();
        assertNotNull(effect);
        SurfaceTexture still = stillInput(effect);
        File first = new File(output.getParentFile(), "first-drawn-picture.jpg");

        module.takePicture(first);
        FakeCamera2.idleMainLooper();
        module.takePicture(output);
        FakeCamera2.idleMainLooper();
        assertTrue("the first press is reported lost", failed.get());

        deliverDrawnPicture(still);
        assertEquals("the second is written", output, captured.get());
        assertFalse(first.exists());
    }

    /** Lands the still's frame on the renderer's input, and hands back what it drew. */
    private void deliverDrawnPicture(SurfaceTexture still) {
        ShadowFrameSurfaceTexture.deliverFrame(still);
        Size upright = new Size(STILL.getHeight(), STILL.getWidth());
        ImageReader drawn = ShadowInjectableImageReader.latestReader(PixelFormat.RGBA_8888);
        assertNotNull("the drawing is read back from a reader of its own", drawn);
        ShadowInjectableImageReader.offerImage(drawn,
                FakeImage.rgba(upright.getWidth(), upright.getHeight(), 16, MAGENTA));
        FakeCamera2.idleMainLooper();
        FakeCamera2.idleMainLooper();
    }

    /** Runs the main looper until filing, which has a thread of its own, has been heard. */
    private void awaitFiling() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (saved.isEmpty() && !failed.get() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper();
            Thread.sleep(10);
        }
        assertTrue("the picture was never filed", !saved.isEmpty() || failed.get());
    }

    /** One-off requests that start a precapture sequence. */
    private static List<CaptureRequest> precaptureTriggers() {
        List<CaptureRequest> triggers = new ArrayList<>();
        for (CaptureRequest request : ShadowCallbackCameraCaptureSession.getCaptures()) {
            Integer trigger = request.get(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER);
            if (trigger != null && trigger == CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START) {
                triggers.add(request);
            }
        }
        return triggers;
    }

    /** One-off requests carrying a picture, which are the only ones that set a JPEG quality. */
    private static List<CaptureRequest> stills() {
        List<CaptureRequest> stills = new ArrayList<>();
        for (CaptureRequest request : ShadowCallbackCameraCaptureSession.getCaptures()) {
            if (request.get(CaptureRequest.JPEG_QUALITY) != null) {
                stills.add(request);
            }
        }
        return stills;
    }

    /** The texture the camera writes stills into: the one listening that is not the preview's. */
    private static SurfaceTexture stillInput(FrameRenderThread effect) {
        SurfaceTexture still = null;
        for (SurfaceTexture texture : ShadowFrameSurfaceTexture.listeningTextures()) {
            if (texture != effect.getInputSurfaceTexture()) {
                still = texture;
            }
        }
        assertNotNull("the renderer should have an input for stills", still);
        return still;
    }

    /**
     * Fails if any pixel along rows spread down the picture is the padding's green. Reading a
     * padded row as if it were packed shears the padding across the picture in thin diagonal
     * bands, which a whole row crosses where scattered points could miss it.
     */
    private static void assertNoPadding(Bitmap picture) {
        int rows = 9;
        for (int sample = 0; sample < rows; sample++) {
            int y = (picture.getHeight() - 1) * sample / (rows - 1);
            for (int x = 0; x < picture.getWidth(); x++) {
                int pixel = picture.getPixel(x, y);
                assertTrue("padding read as picture at " + x + "," + y + ": "
                                + Integer.toHexString(pixel),
                        Color.green(pixel) < 128 && Color.red(pixel) > 128
                                && Color.blue(pixel) > 128);
            }
        }
    }

    /** A renderer that remembers the size of every frame it was handed and draws nothing. */
    private static final class RecordingRenderer implements FrameRenderer {
        /** The size of every frame handed over, in order, guarded by itself. */
        private final List<Size> mFrameSizes = new ArrayList<>();

        final AtomicInteger started = new AtomicInteger();
        final AtomicInteger stopped = new AtomicInteger();

        @Override
        public void onRendererStarted() {
            started.incrementAndGet();
        }

        @Override
        public boolean onDrawFrame(@NonNull GlFrame frame) {
            synchronized (mFrameSizes) {
                mFrameSizes.add(new Size(frame.getWidth(), frame.getHeight()));
            }
            return false;
        }

        @Override
        public void onRendererStopped() {
            stopped.incrementAndGet();
        }

        List<Size> frameSizes() {
            synchronized (mFrameSizes) {
                return new ArrayList<>(mFrameSizes);
            }
        }
    }
}
