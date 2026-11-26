package com.xlythe.view.camera.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.opengl.GLES20;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.exifinterface.media.ExifInterface;
import androidx.test.rule.GrantPermissionRule;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.gl.BackgroundReplacer;
import com.xlythe.view.camera.gl.ColorFilter;
import com.xlythe.view.camera.gl.ColorMatrix;
import com.xlythe.view.camera.gl.FrameRenderer;
import com.xlythe.view.camera.gl.SegmentationMask;
import com.xlythe.view.camera.gl.GlFrame;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Whether a frame renderer reaches the files the camera writes.
 *
 * <p>Everything else about the GL pipeline can be reasoned about or shadowed; this cannot. There is
 * no GPU off a device, so a shader that does not compile, an EGL config a driver refuses, or a
 * transform that leaves the frame somewhere other than the output all look exactly like working
 * code until a photograph comes back. The only honest test is to draw a colour no camera produces
 * and then read the saved file back.</p>
 *
 * <p>Magenta is the colour because nothing in a room is that: a frame that arrives unaltered, or
 * half-drawn, or from the wrong texture, fails this rather than passing by luck.</p>
 */
@RunWith(org.junit.runners.Parameterized.class)
public final class FrameEffectDeviceTest {
    /**
     * Both backends that put frames through GL.
     *
     * <p>CameraX has a seam for it and always did. Camera2 did not: its frames went to the
     * surfaces in its session, and the session is now built so that those surfaces are the
     * renderer's. Running the same assertions against both is the point - a filter that
     * reaches the preview and not the photograph is a bug, and only an end-to-end check on
     * each backend says which one it reached.</p>
     */
    @org.junit.runners.Parameterized.Parameters(name = "{0}")
    public static java.util.Collection<CameraView.Backend> backends() {
        return java.util.Arrays.asList(
                CameraView.Backend.CAMERA2, CameraView.Backend.CAMERA_X);
    }

    @org.junit.runners.Parameterized.Parameter
    public CameraView.Backend backend;

    private static final long TIMEOUT_SECONDS = 30;
    /** How far a channel may stray, which is a JPEG quantising a flat field and nothing more. */
    private static final int TOLERANCE = 24;
    /** How far the three channels of a grey pixel may differ after JPEG. */
    private static final int GREY_TOLERANCE = 12;
    /** How far apart they must be before a scene counts as having colour. */
    private static final int COLOUR_SPREAD = 30;
    /** How near to 255 a clamped channel has to land, allowing for JPEG. */
    private static final int WHITE_FLOOR = 243;
    private static final String FILTER_TAG = "FrameEffect";

    @Rule
    public final GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
    );

    @Before
    public void useTheBackendUnderTest() {
        DeviceState.assumeATestableCamera();
        CameraView.setDefaultBackend(backend);
    }

    @After
    public void restoreTheDefault() {
        CameraView.setDefaultBackend(CameraView.Backend.AUTO);
    }

    /** A renderer that paints the whole frame, so the output cannot be mistaken for the camera's. */
    private static final class FloodFill implements FrameRenderer {
        private final AtomicInteger frames = new AtomicInteger();

        @Override public boolean onDrawFrame(@NonNull GlFrame frame) {
            GLES20.glClearColor(1f, 0f, 1f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            frames.incrementAndGet();
            return true;
        }
    }

    /** The claim: what the renderer draws is what the capture writes. */
    @Test
    public void aRendererReachesTheSavedPicture() throws Exception {
        File file = temporaryFile(".jpg");
        FloodFill renderer = new FloodFill();
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<File> captured = new AtomicReference<>();

        withOpenCamera(renderer, camera -> {
            camera.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
                @Override public void onImageConfirmation() {}

                @Override public void onImageCaptured(File saved) {
                    captured.set(saved);
                    finished.countDown();
                }

                @Override public void onFailure() {
                    finished.countDown();
                }
            });
            camera.takePicture(file);
            assertTrue("the capture never reported", finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        });

        assertNotNull("the capture failed with an effect bound", captured.get());
        Bitmap picture = BitmapFactory.decodeFile(captured.get().getAbsolutePath());
        assertNotNull("the capture wrote something that is not an image", picture);
        try {
            assertTrue("the renderer was never called", renderer.frames.get() > 0);
            assertMagenta("the saved picture", picture.getPixel(
                    picture.getWidth() / 2, picture.getHeight() / 2));
            // The corners too: a transform that lands the frame somewhere other than the output
            // leaves the edges of the buffer as the encoder found them.
            assertMagenta("the top-left of the saved picture", picture.getPixel(1, 1));
            assertMagenta("the bottom-right of the saved picture",
                    picture.getPixel(picture.getWidth() - 2, picture.getHeight() - 2));
        } finally {
            picture.recycle();
        }
    }

    /** And the same for a recording, which goes through a different output of the same effect. */
    @Test
    public void aRendererReachesTheRecording() throws Exception {
        File file = temporaryFile(".mp4");
        FloodFill renderer = new FloodFill();
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<File> recorded = new AtomicReference<>();

        withOpenCamera(renderer, camera -> {
            camera.setOnVideoCapturedListener(new CameraView.OnVideoCapturedListener() {
                @Override public void onVideoConfirmation() {}

                @Override public void onVideoCaptured(File saved) {
                    recorded.set(saved);
                    finished.countDown();
                }

                @Override public void onFailure() {
                    finished.countDown();
                }
            });
            camera.startRecording(file);
            Thread.sleep(5_000);
            camera.stopRecording();
            assertTrue("the recording never reported",
                    finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        });

        // An encoder that produced nothing is the encoder's business, and the suite already
        // tolerates that elsewhere; what must not happen is a recording of the wrong pixels.
        Assume.assumeTrue("this device's encoder produced no video",
                recorded.get() != null && recorded.get().length() > 0);
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(recorded.get().getAbsolutePath());
            Bitmap frame = retriever.getFrameAtTime(1_000_000);
            Assume.assumeTrue("no frame could be decoded from the recording", frame != null);
            try {
                assertMagenta("a frame of the recording", frame.getPixel(
                        frame.getWidth() / 2, frame.getHeight() / 2));
            } finally {
                frame.recycle();
            }
        } finally {
            retriever.release();
        }
    }

    /**
     * A frame drawn straight through comes out the size it would have without the effect.
     *
     * <p>The effect is built with CameraX's only unrestricted constructor, which describes the
     * transformation as arbitrary rather than as the camera-and-surface rotation the processor
     * actually applies. That is a claim about what CameraX may assume downstream, and the place
     * it would show is the geometry of the saved file - a frame rotated a quarter turn, or
     * cropped to the wrong rectangle, arrives here as different dimensions.</p>
     */
    @Test
    public void drawingTheFrameThroughDoesNotChangeItsShape() throws Exception {
        Size withoutEffect = captureSize(null);
        Size withEffect = captureSize(frame -> {
            frame.drawCameraFrame();
            return true;
        });

        assertEquals("an effect that draws the frame through should not reshape the capture",
                withoutEffect, withEffect);
    }

    /**
     * A colour filter reaches the photograph, and does to it what it claims.
     *
     * <p>Asserted with a matrix whose output does not depend on the scene. Grayscale would be
     * the natural choice and is the wrong one here: proving it worked needs the scene to have
     * had colour in it, and a phone face down on a desk photographs something already grey, so
     * the test would pass by skipping and say nothing. Lifting every channel past one clamps to
     * white whatever the camera is pointed at, which either happens or does not.</p>
     *
     * <p>The grayscale case is then checked as well, but only where the first capture shows
     * there was colour to remove.</p>
     */
    @Test
    public void aColourFilterReachesTheSavedPicture() throws Exception {
        Bitmap white = captureThrough(
                new ColorFilter(ColorMatrix.brightness(1f)), temporaryFile(".jpg"));
        try {
            for (int[] point : samplePoints(white)) {
                int pixel = white.getPixel(point[0], point[1]);
                assertTrue("a filter that lifts every channel past one should leave white, and"
                                + " left " + String.format("#%06X", pixel & 0xFFFFFF),
                        Color.red(pixel) >= WHITE_FLOOR && Color.green(pixel) >= WHITE_FLOOR
                                && Color.blue(pixel) >= WHITE_FLOOR);
            }
        } finally {
            white.recycle();
        }

        Bitmap unfiltered = captureThrough(null, temporaryFile(".jpg"));
        Bitmap grey = captureThrough(
                new ColorFilter(ColorMatrix.grayscale()), temporaryFile(".jpg"));
        try {
            if (isColourful(unfiltered)) {
                assertTrue("every pixel should be grey once the colour is taken out",
                        isGreyscale(grey));
            } else {
                Log.i(FILTER_TAG, "the scene had no colour to remove, so grayscale proves little");
            }
        } finally {
            unfiltered.recycle();
            grey.recycle();
        }
    }

    /**
     * A mask decides what is kept and what is replaced, in the saved photograph.
     *
     * <p>Asserted at the two ends, where the answer does not depend on what the camera is
     * pointed at. A mask that is entirely subject must leave the frame alone; a mask that is
     * entirely background must leave the background colour and nothing else. A compositor that
     * ignored the mask, inverted it, or never reached the still would fail one of the two.</p>
     *
     * <p>The middle - a real segmentation, softly edged - cannot be asserted without knowing
     * what is in front of the lens, and is not pretended at here.</p>
     */
    @Test
    public void aMaskDecidesWhatIsReplacedInTheSavedPicture() throws Exception {
        BackgroundReplacer allBackground = new BackgroundReplacer();
        allBackground.setBackgroundColor(0xFF00FF00);
        allBackground.setMask(maskOf(0f));

        Bitmap replaced = captureThrough(allBackground, temporaryFile(".jpg"));
        try {
            for (int[] point : samplePoints(replaced)) {
                int pixel = replaced.getPixel(point[0], point[1]);
                assertTrue("a mask that is all background should leave only the background"
                                + " colour, and left " + String.format("#%06X", pixel & 0xFFFFFF),
                        Color.green(pixel) > 200 && Color.red(pixel) < 60
                                && Color.blue(pixel) < 60);
            }
        } finally {
            replaced.recycle();
        }

        BackgroundReplacer allSubject = new BackgroundReplacer();
        allSubject.setBackgroundColor(0xFF00FF00);
        allSubject.setMask(maskOf(1f));

        Bitmap kept = captureThrough(allSubject, temporaryFile(".jpg"));
        try {
            boolean anyGreenWash = false;
            for (int[] point : samplePoints(kept)) {
                int pixel = kept.getPixel(point[0], point[1]);
                if (Color.green(pixel) > 200 && Color.red(pixel) < 60 && Color.blue(pixel) < 60) {
                    anyGreenWash = true;
                }
            }
            assertTrue("a mask that is all subject should leave the camera frame, not the"
                    + " background", !anyGreenWash);
        } finally {
            kept.recycle();
        }
    }

    /** A mask of one confidence everywhere, which is how the two ends are asked for. */
    @NonNull
    private static SegmentationMask maskOf(float confidence) {
        int width = 16;
        int height = 16;
        ByteBuffer buffer = ByteBuffer.allocate(width * height * 4)
                .order(ByteOrder.nativeOrder());
        for (int i = 0; i < width * height; i++) {
            buffer.putFloat(confidence);
        }
        buffer.rewind();
        return new SegmentationMask(buffer, width, height, false);
    }

    /** Captures with the given renderer and decodes the result. */
    @NonNull
    private Bitmap captureThrough(@Nullable FrameRenderer renderer, @NonNull File file)
            throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<File> captured = new AtomicReference<>();
        withOpenCamera(renderer, camera -> {
            camera.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
                @Override public void onImageConfirmation() {}

                @Override public void onImageCaptured(File saved) {
                    captured.set(saved);
                    finished.countDown();
                }

                @Override public void onFailure() {
                    finished.countDown();
                }
            });
            camera.takePicture(file);
            assertTrue("the capture never reported",
                    finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        });
        assertNotNull("the capture failed", captured.get());
        Bitmap decoded = BitmapFactory.decodeFile(captured.get().getAbsolutePath());
        assertNotNull("the capture wrote something that is not an image", decoded);
        return decoded;
    }

    /** Whether a scene has enough colour in it for removing colour to be visible. */
    private static boolean isColourful(@NonNull Bitmap bitmap) {
        for (int[] point : samplePoints(bitmap)) {
            if (channelSpread(bitmap.getPixel(point[0], point[1])) > COLOUR_SPREAD) {
                return true;
            }
        }
        return false;
    }

    /** Whether every sampled pixel has its three channels equal, within JPEG's rounding. */
    private static boolean isGreyscale(@NonNull Bitmap bitmap) {
        for (int[] point : samplePoints(bitmap)) {
            int spread = channelSpread(bitmap.getPixel(point[0], point[1]));
            if (spread > GREY_TOLERANCE) {
                Log.i(FILTER_TAG, "not grey at " + point[0] + "," + point[1]
                        + ", channels differ by " + spread);
                return false;
            }
        }
        return true;
    }

    private static int channelSpread(int pixel) {
        int high = Math.max(Math.max(Color.red(pixel), Color.green(pixel)), Color.blue(pixel));
        int low = Math.min(Math.min(Color.red(pixel), Color.green(pixel)), Color.blue(pixel));
        return high - low;
    }

    /** A spread of points across the frame, so one flat corner cannot carry the verdict. */
    private static int[][] samplePoints(@NonNull Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        return new int[][] {
                {width / 4, height / 4}, {width / 2, height / 4}, {width * 3 / 4, height / 4},
                {width / 4, height / 2}, {width / 2, height / 2}, {width * 3 / 4, height / 2},
                {width / 4, height * 3 / 4}, {width / 2, height * 3 / 4},
                {width * 3 / 4, height * 3 / 4},
        };
    }

    /**
     * The dimensions of one capture as a viewer sees them, with the given renderer or with none.
     *
     * <p>Orientation is applied rather than ignored, because the two paths record it differently
     * and both are correct: a JPEG straight from the camera is stored in sensor orientation with
     * an Exif tag saying how to turn it, while one drawn through the processor arrives already
     * turned. Comparing the stored buffers would call that a difference; comparing what is
     * displayed is the question actually worth asking.</p>
     */
    @NonNull
    private Size captureSize(@Nullable FrameRenderer renderer) throws Exception {
        File file = temporaryFile(".jpg");
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<File> captured = new AtomicReference<>();

        withOpenCamera(renderer, camera -> {
            camera.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
                @Override public void onImageConfirmation() {}

                @Override public void onImageCaptured(File saved) {
                    captured.set(saved);
                    finished.countDown();
                }

                @Override public void onFailure() {
                    finished.countDown();
                }
            });
            camera.takePicture(file);
            assertTrue("the capture never reported",
                    finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        });

        assertNotNull("the capture failed", captured.get());
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(captured.get().getAbsolutePath(), bounds);
        assertTrue("the capture wrote something that is not an image", bounds.outWidth > 0);
        int orientation = new ExifInterface(captured.get().getAbsolutePath()).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        boolean quarterTurned = orientation == ExifInterface.ORIENTATION_ROTATE_90
                || orientation == ExifInterface.ORIENTATION_ROTATE_270
                || orientation == ExifInterface.ORIENTATION_TRANSPOSE
                || orientation == ExifInterface.ORIENTATION_TRANSVERSE;
        return quarterTurned
                ? new Size(bounds.outHeight, bounds.outWidth)
                : new Size(bounds.outWidth, bounds.outHeight);
    }

    private static void assertMagenta(@NonNull String what, int pixel) {
        assertEquals(what + " should be opaque", 255, Color.alpha(pixel));
        assertTrue(what + " should be magenta and was "
                        + String.format("#%06X", pixel & 0xFFFFFF),
                Math.abs(Color.red(pixel) - 255) <= TOLERANCE
                        && Color.green(pixel) <= TOLERANCE
                        && Math.abs(Color.blue(pixel) - 255) <= TOLERANCE);
    }

    private interface CameraAction {
        void run(CameraView camera) throws Exception;
    }

    private void withOpenCamera(@Nullable FrameRenderer renderer, @NonNull CameraAction action)
            throws Exception {
        try (ActivityScenario<CameraTestActivity> scenario =
                     ActivityScenario.launch(CameraTestActivity.class)) {
            CountDownLatch opened = new CountDownLatch(1);
            AtomicReference<CameraView> held = new AtomicReference<>();
            AtomicReference<Boolean> effects = new AtomicReference<>();
            scenario.onActivity(activity -> {
                CameraView camera = activity.getCameraView();
                held.set(camera);
                effects.set(camera.isFrameEffectSupported());
                // Set before opening: the renderer is installed with the use cases, and this test
                // is about the pipeline the camera is built with, not about swapping one later.
                camera.setFrameRenderer(renderer);
                camera.setOnCameraStateChangedListener(
                        new CameraView.OnCameraStateChangedListener() {
                            @Override public void onCameraOpened() {
                                opened.countDown();
                            }

                            @Override public void onCameraClosed() {}
                        });
                if (camera.isOpen()) opened.countDown();
                else camera.open();
            });

            // A backend or a device without effects is a real configuration, not a failure. This
            // is the only skip here: once effects are reported, they have to work.
            Assume.assumeTrue("this build reports no frame effects",
                    Boolean.TRUE.equals(effects.get()));
            assertTrue("the camera did not open " + DeviceState.whyTheCameraMightNotOpen(),
                    opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            CameraView camera = held.get();
            assertNotNull(camera);
            DeviceState.turnTheFlashOff(camera);
            try {
                action.run(camera);
            } finally {
                scenario.onActivity(activity -> {
                    activity.getCameraView().setFrameRenderer(null);
                    activity.getCameraView().close();
                });
            }
        }
    }

    private static File temporaryFile(@NonNull String extension) throws Exception {
        File file = File.createTempFile("effect", extension,
                ApplicationProvider.getApplicationContext().getCacheDir());
        assertTrue(file.delete() || !file.exists());
        return file;
    }
}
