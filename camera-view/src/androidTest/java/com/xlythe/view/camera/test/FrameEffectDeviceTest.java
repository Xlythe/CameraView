package com.xlythe.view.camera.test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.opengl.GLES20;
import android.os.SystemClock;
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
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
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
@RunWith(Parameterized.class)
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
    @Parameterized.Parameters(name = "{0}")
    public static Collection<CameraView.Backend> backends() {
        return Arrays.asList(
                CameraView.Backend.CAMERA2, CameraView.Backend.CAMERA_X);
    }

    @Parameterized.Parameter
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
    /** How far apart two aspect ratios may be and still count as one crop, after rounding. */
    private static final float ASPECT_TOLERANCE = 0.01f;
    private static final String FILTER_TAG = "FrameEffect";
    /** How long the preview is given to show what the renderer drew, once the camera is open. */
    private static final long PREVIEW_WAIT_MILLIS = 10_000;
    /** How often the screen is looked at while waiting for it. */
    private static final long PREVIEW_POLL_MILLIS = 250;
    /** Brighter than any channel of the dark window that shows before the first frame. */
    private static final int UNDRAWN_CEILING = 64;

    @Rule
    public final GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
    );

    /** Skips, rather than fails, a test that the emulator's camera HAL stalled under. */
    @Rule
    public final TestRule cameraHal = DeviceState.skipWhenTheEmulatorCameraHalStalls();

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

    /**
     * Paints each quarter of the camera's buffer a colour of its own, so every frame carries a mark
     * that turns and mirrors exactly as the scene does, whatever the camera is pointed at.
     *
     * <p>The colour is chosen by where the frame's matrix reads the camera texture, not by where
     * the pixel is in the output. Every output then shows the same four quarters, each turned and
     * mirrored by its own matrix - so an output that shows them differently from the preview is
     * one whose matrix is wrong. Red marks the right half of the buffer and green the half the
     * texture coordinate counts up to; blue is there so that no quarter is black, which is what a
     * frame that drew nothing looks like.</p>
     */
    private static final class Quarters implements FrameRenderer {
        private static final String VERTEX_SHADER =
                "uniform mat4 uTextureMatrix;\n"
                + "attribute vec4 aPosition;\n"
                + "attribute vec4 aTextureCoordinate;\n"
                + "varying vec2 vCameraCoordinate;\n"
                + "void main() {\n"
                + "  gl_Position = aPosition;\n"
                + "  vCameraCoordinate = (uTextureMatrix * aTextureCoordinate).xy;\n"
                + "}\n";

        private static final String FRAGMENT_SHADER =
                "precision mediump float;\n"
                + "varying vec2 vCameraCoordinate;\n"
                + "void main() {\n"
                + "  gl_FragColor = vec4(step(0.5, vCameraCoordinate.x),"
                + " step(0.5, vCameraCoordinate.y), 1.0, 1.0);\n"
                + "}\n";

        /** All of clip space, read across the whole texture, as x, y, s, t. */
        private static final float[] QUAD = {
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f,
        };

        private final FloatBuffer vertices = ByteBuffer.allocateDirect(QUAD.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        private int program;

        @Override public void onRendererStarted() {
            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER));
            GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER));
            GLES20.glLinkProgram(program);
            vertices.put(QUAD).position(0);
        }

        @Override public boolean onDrawFrame(@NonNull GlFrame frame) {
            GLES20.glUseProgram(program);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTextureMatrix"),
                    1, false, frame.getTextureMatrix(), 0);
            int position = GLES20.glGetAttribLocation(program, "aPosition");
            int coordinate = GLES20.glGetAttribLocation(program, "aTextureCoordinate");
            vertices.position(0);
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices);
            GLES20.glEnableVertexAttribArray(position);
            vertices.position(2);
            GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 16, vertices);
            GLES20.glEnableVertexAttribArray(coordinate);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(position);
            GLES20.glDisableVertexAttribArray(coordinate);
            GLES20.glUseProgram(0);
            return true;
        }

        @Override public void onRendererStopped() {
            GLES20.glDeleteProgram(program);
        }

        private static int compile(int type, @NonNull String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            return shader;
        }
    }

    /**
     * A recording drawn through an effect is shown the way its preview was, from either lens: the
     * same way up, and mirrored at the front.
     *
     * <p>The recording is stored in the sensor's orientation and tagged with the turn that shows
     * it upright, while the preview is drawn upright, so the two are drawn with different matrices
     * - and a recording drawn with the preview's matrix is turned twice. {@link Quarters} makes
     * that visible on any scene.</p>
     */
    @Test
    public void aRecordingThroughAnEffectIsShownAsItsPreviewWas() throws Exception {
        for (CameraView.LensFacing lensFacing : CameraView.LensFacing.values()) {
            File file = temporaryFile(".mp4");
            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<File> recorded = new AtomicReference<>();
            AtomicReference<Bitmap> preview = new AtomicReference<>();

            boolean opened = withOpenCamera(new Quarters(), lensFacing, camera -> {
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
                Thread.sleep(1_500);
                preview.set(drawnPreview(camera));
                Thread.sleep(1_500);
                camera.stopRecording();
                assertTrue("the recording never reported",
                        finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            });
            if (!opened) {
                continue;
            }

            Assume.assumeTrue("this device's encoder produced no video",
                    recorded.get() != null && recorded.get().length() > 0);
            Bitmap frame = ShownFrames.ofRecording(recorded.get(), 1_500_000);
            Assume.assumeTrue("no frame could be decoded from the recording", frame != null);
            assertSameQuarters(lensFacing + " recording", preview.get(), frame);
            Log.i(FILTER_TAG, backend + " " + lensFacing + " recording shows the preview's quarters");
        }
    }

    /**
     * Draws a mark in the coordinates of the picture as it is shown, and nothing of the camera: a
     * band across the top, blue on the left and yellow on the right, over grey.
     *
     * <p>The other half of what a renderer can draw. {@link Quarters} is pinned to the camera's
     * buffer and has to turn with the scene; this is pinned to the output and must not turn at
     * all. A caption, a watermark or a background drawn behind a subject is drawn this way, and
     * it has to sit where it was drawn on every output - the right way up, and not mirrored, on
     * the screen, in the recording and in the picture, whichever lens took them.</p>
     */
    private static final class TopBand implements FrameRenderer {
        /**
         * How much of the picture the band covers, from the top. Deep enough to be seen below a
         * status bar and an app bar drawn over the top of the preview, as they are edge to edge.
         */
        private static final float BAND = 0.4f;

        @Override public boolean onDrawFrame(@NonNull GlFrame frame) {
            int width = frame.getWidth();
            int height = frame.getHeight();
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
            GLES20.glScissor(0, 0, width, height);
            GLES20.glClearColor(0.5f, 0.5f, 0.5f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            // GL counts rows from the bottom, so the top of the picture is the end of the range.
            int bandHeight = Math.round(height * BAND);
            GLES20.glScissor(0, height - bandHeight, width / 2, bandHeight);
            GLES20.glClearColor(0f, 0f, 1f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glScissor(width / 2, height - bandHeight, width - width / 2, bandHeight);
            GLES20.glClearColor(1f, 1f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
            return true;
        }
    }

    /**
     * What a renderer draws relative to the picture lands there in every output: a band drawn
     * across the top of the preview is across the top of the recording and the picture, blue
     * end on the left, as each file is meant to be shown.
     *
     * <p>A renderer is never told the sensor's orientation, the display's rotation or whether
     * the lens mirrors, and does not need to be: each output it draws is the right way up as it
     * will be shown.</p>
     */
    @Test
    public void aMarkDrawnAtTheTopIsAtTheTopOfEveryOutput() throws Exception {
        for (CameraView.LensFacing lensFacing : CameraView.LensFacing.values()) {
            File picture = temporaryFile(".jpg");
            File video = temporaryFile(".mp4");
            CountDownLatch pictured = new CountDownLatch(1);
            CountDownLatch recorded = new CountDownLatch(1);
            AtomicReference<File> savedPicture = new AtomicReference<>();
            AtomicReference<File> savedVideo = new AtomicReference<>();
            AtomicReference<Bitmap> preview = new AtomicReference<>();

            boolean opened = withOpenCamera(new TopBand(), lensFacing, camera -> {
                camera.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
                    @Override public void onImageConfirmation() {}

                    @Override public void onImageCaptured(File saved) {
                        savedPicture.set(saved);
                        pictured.countDown();
                    }

                    @Override public void onFailure() {
                        pictured.countDown();
                    }
                });
                camera.setOnVideoCapturedListener(new CameraView.OnVideoCapturedListener() {
                    @Override public void onVideoConfirmation() {}

                    @Override public void onVideoCaptured(File saved) {
                        savedVideo.set(saved);
                        recorded.countDown();
                    }

                    @Override public void onFailure() {
                        recorded.countDown();
                    }
                });
                Thread.sleep(1_000);
                preview.set(drawnPreview(camera));
                camera.takePicture(picture);
                assertTrue("the capture never reported",
                        pictured.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                camera.startRecording(video);
                Thread.sleep(3_000);
                camera.stopRecording();
                assertTrue("the recording never reported",
                        recorded.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            });
            if (!opened) {
                continue;
            }

            assertBandAtTheTop(lensFacing + " preview", preview.get());
            assertNotNull("the " + lensFacing + " capture failed", savedPicture.get());
            Bitmap shownPicture = ShownFrames.ofPicture(savedPicture.get());
            try {
                assertBandAtTheTop(lensFacing + " picture", shownPicture);
            } finally {
                shownPicture.recycle();
            }
            Assume.assumeTrue("this device's encoder produced no video",
                    savedVideo.get() != null && savedVideo.get().length() > 0);
            Bitmap frame = ShownFrames.ofRecording(savedVideo.get(), 1_500_000);
            Assume.assumeTrue("no frame could be decoded from the recording", frame != null);
            assertBandAtTheTop(lensFacing + " recording", frame);
            Log.i(FILTER_TAG, backend + " " + lensFacing + " band is at the top everywhere");
        }
    }

    /**
     * Asserts a frame as shown has {@link TopBand}'s band across its top, blue end on the left,
     * and grey below it.
     *
     * <p>Read a quarter of the way in from each edge: below where system bars cover the top of
     * an edge-to-edge preview, and inside what a preview's crop leaves of the band.</p>
     */
    private static void assertBandAtTheTop(@NonNull String what, @NonNull Bitmap shown) {
        int topLeft = shown.getPixel(shown.getWidth() / 4, shown.getHeight() / 4);
        int topRight = shown.getPixel(shown.getWidth() * 3 / 4, shown.getHeight() / 4);
        int bottomLeft = shown.getPixel(shown.getWidth() / 4, shown.getHeight() * 3 / 4);
        int bottomRight = shown.getPixel(shown.getWidth() * 3 / 4, shown.getHeight() * 3 / 4);
        String colours = what + " showed " + String.format(Locale.US,
                "#%06X #%06X above #%06X #%06X", topLeft & 0xFFFFFF, topRight & 0xFFFFFF,
                bottomLeft & 0xFFFFFF, bottomRight & 0xFFFFFF);
        assertTrue("the band's blue end should be at the top left: " + colours,
                Color.blue(topLeft) > 180 && Color.red(topLeft) < 80 && Color.green(topLeft) < 80);
        assertTrue("the band's yellow end should be at the top right: " + colours,
                Color.red(topRight) > 180 && Color.green(topRight) > 180
                        && Color.blue(topRight) < 80);
        assertTrue("there should be no band at the bottom: " + colours,
                isGrey(bottomLeft) && isGrey(bottomRight));
    }

    private static boolean isGrey(int pixel) {
        return channelSpread(pixel) < 40 && Color.red(pixel) > 80 && Color.red(pixel) < 180;
    }

    /**
     * And a picture drawn through an effect is shown the way its preview was, from either lens.
     *
     * <p>A drawn picture is encoded from pixels the renderer read back rather than by the camera,
     * and then tagged with the turn and the mirror the camera's own JPEG would have carried - so
     * it has to be drawn the way the camera writes one, or the tag turns it a second time.</p>
     */
    @Test
    public void aPictureThroughAnEffectIsShownAsItsPreviewWas() throws Exception {
        for (CameraView.LensFacing lensFacing : CameraView.LensFacing.values()) {
            File file = temporaryFile(".jpg");
            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<File> captured = new AtomicReference<>();
            AtomicReference<Bitmap> preview = new AtomicReference<>();

            boolean opened = withOpenCamera(new Quarters(), lensFacing, camera -> {
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
                Thread.sleep(1_000);
                preview.set(drawnPreview(camera));
                camera.takePicture(file);
                assertTrue("the capture never reported",
                        finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            });
            if (!opened) {
                continue;
            }

            assertNotNull("the " + lensFacing + " capture failed", captured.get());
            Bitmap picture = ShownFrames.ofPicture(captured.get());
            try {
                assertSameQuarters(lensFacing + " picture", preview.get(), picture);
            } finally {
                picture.recycle();
            }
            Log.i(FILTER_TAG, backend + " " + lensFacing + " picture shows the preview's quarters");
        }
    }

    /**
     * A drawn picture is encoded from what the renderer produced and carries no gain map, so a
     * camera that writes Ultra HDR stops offering it while anything is drawing.
     */
    @Test
    public void ultraHdrIsNotOfferedWhileDrawing() throws Exception {
        AtomicReference<Boolean> offered = new AtomicReference<>();

        withOpenCamera(new FloodFill(), camera -> offered.set(
                camera.isPictureFormatSupported(CameraView.PictureFormat.ULTRA_HDR)));

        assertFalse("Ultra HDR offered while a renderer is drawing", offered.get());
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
     * A frame drawn straight through comes out the shape it would have without the effect.
     *
     * <p>The effect is built with CameraX's only unrestricted constructor, which describes the
     * transformation as arbitrary rather than as the camera-and-surface rotation the processor
     * actually applies. That is a claim about what CameraX may assume downstream, and the place
     * it would show is the geometry of the saved file - a frame rotated a quarter turn, or
     * cropped to the wrong rectangle, arrives here turned or at another aspect ratio.</p>
     *
     * <p>The pixel count is not compared. CameraX draws every output from one shared stream, so
     * its drawn still has the preview's resolution, which is smaller than its own still on a
     * camera that offers more.</p>
     */
    @Test
    public void drawingTheFrameThroughDoesNotChangeItsShape() throws Exception {
        Size withoutEffect = captureSize(null);
        Size withEffect = captureSize(frame -> {
            frame.drawCameraFrame();
            return true;
        });

        String sizes = withoutEffect + " without the effect, " + withEffect + " with it";
        assertEquals("an effect that draws the frame through should not turn the capture: "
                        + sizes,
                withoutEffect.getWidth() > withoutEffect.getHeight(),
                withEffect.getWidth() > withEffect.getHeight());
        assertEquals("an effect that draws the frame through should not recrop the capture: "
                        + sizes,
                aspectRatio(withoutEffect), aspectRatio(withEffect), ASPECT_TOLERANCE);
    }

    private static float aspectRatio(@NonNull Size size) {
        return (float) size.getWidth() / size.getHeight();
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

    /**
     * The preview on screen, once the renderer has drawn into it.
     *
     * <p>Waited for rather than taken after a fixed pause, because a camera that has just opened
     * with an effect reconfigures before its first frame, and on a slow device a screenshot taken
     * too early shows only the window behind the view. Both renderers here paint the middle of
     * the picture something lighter than that.</p>
     */
    @NonNull
    private static Bitmap drawnPreview(@NonNull CameraView camera) throws InterruptedException {
        long deadline = SystemClock.uptimeMillis() + PREVIEW_WAIT_MILLIS;
        Bitmap shown = ShownFrames.onScreen(camera);
        while (isUndrawn(shown) && SystemClock.uptimeMillis() < deadline) {
            shown.recycle();
            Thread.sleep(PREVIEW_POLL_MILLIS);
            shown = ShownFrames.onScreen(camera);
        }
        return shown;
    }

    private static boolean isUndrawn(@NonNull Bitmap shown) {
        int middle = shown.getPixel(shown.getWidth() / 2, shown.getHeight() / 2);
        return Math.max(Color.red(middle), Math.max(Color.green(middle), Color.blue(middle)))
                < UNDRAWN_CEILING;
    }

    /**
     * Asserts two frames as shown have the same {@link Quarters} colour in each quarter.
     *
     * <p>Read a quarter of the way in from each edge, which is inside the same quarter of the
     * camera's buffer however much of the frame a preview crops away: every crop here is centred,
     * and the quarters meet at the centre.</p>
     */
    private static void assertSameQuarters(@NonNull String what, @NonNull Bitmap preview,
            @NonNull Bitmap shown) {
        int[] expected = quarters(preview);
        Set<Integer> different = new HashSet<>();
        for (int quarter : expected) {
            different.add(quarter);
        }
        assertEquals("the preview should show four different quarters, and showed "
                + Arrays.toString(expected), 4, different.size());
        assertArrayEquals("the " + what + " should show the quarters where the preview did",
                expected, quarters(shown));
    }

    /** The {@link Quarters} colour in each quarter, top left first, as red + 2 * green. */
    @NonNull
    private static int[] quarters(@NonNull Bitmap bitmap) {
        float[][] points = {{0.25f, 0.25f}, {0.75f, 0.25f}, {0.25f, 0.75f}, {0.75f, 0.75f}};
        int[] quarters = new int[points.length];
        for (int i = 0; i < points.length; i++) {
            int pixel = bitmap.getPixel((int) (points[i][0] * bitmap.getWidth()),
                    (int) (points[i][1] * bitmap.getHeight()));
            assertTrue("a quarter should be lit blue, and was "
                    + String.format("#%06X", pixel & 0xFFFFFF), Color.blue(pixel) > 128);
            quarters[i] = (Color.red(pixel) > 128 ? 1 : 0) + (Color.green(pixel) > 128 ? 2 : 0);
        }
        return quarters;
    }

    private interface CameraAction {
        void run(CameraView camera) throws Exception;
    }

    private void withOpenCamera(@Nullable FrameRenderer renderer, @NonNull CameraAction action)
            throws Exception {
        withOpenCamera(renderer, null, action);
    }

    /**
     * Opens the camera with a renderer set, on a lens if one is named, and runs the action.
     *
     * @return false, having run nothing, when there is no camera facing that way
     */
    private boolean withOpenCamera(@Nullable FrameRenderer renderer,
            @Nullable CameraView.LensFacing lensFacing, @NonNull CameraAction action)
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
                if (lensFacing != null) {
                    camera.setLensFacing(lensFacing);
                }
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
            // Asked once the camera is open, because CameraX knows its lenses only then.
            AtomicReference<CameraView.LensFacing> opensOn = new AtomicReference<>();
            scenario.onActivity(activity -> opensOn.set(activity.getCameraView().getLensFacing()));
            if (lensFacing != null && opensOn.get() != lensFacing) {
                scenario.onActivity(activity -> {
                    activity.getCameraView().setFrameRenderer(null);
                    activity.getCameraView().close();
                });
                return false;
            }
            DeviceState.turnTheFlashOff(camera);
            try {
                action.run(camera);
            } finally {
                scenario.onActivity(activity -> {
                    activity.getCameraView().setFrameRenderer(null);
                    activity.getCameraView().close();
                });
            }
            return true;
        }
    }

    private static File temporaryFile(@NonNull String extension) throws Exception {
        File file = File.createTempFile("effect", extension,
                ApplicationProvider.getApplicationContext().getCacheDir());
        assertTrue(file.delete() || !file.exists());
        return file;
    }
}
