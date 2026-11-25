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

import androidx.annotation.NonNull;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.gl.FrameRenderer;
import com.xlythe.view.camera.gl.GlFrame;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
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
@RunWith(AndroidJUnit4.class)
public final class FrameEffectDeviceTest {
    private static final long TIMEOUT_SECONDS = 30;
    /** How far a channel may stray, which is a JPEG quantising a flat field and nothing more. */
    private static final int TOLERANCE = 24;

    @Rule
    public final GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
    );

    @Before
    public void useTheBackendThatHasEffects() {
        DeviceState.assumeATestableCamera();
        CameraView.setDefaultBackend(CameraView.Backend.CAMERA_X);
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

    private void withOpenCamera(@NonNull FrameRenderer renderer, @NonNull CameraAction action)
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
