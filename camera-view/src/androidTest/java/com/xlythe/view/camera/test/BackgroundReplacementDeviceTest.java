package com.xlythe.view.camera.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.rule.GrantPermissionRule;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.gl.BackgroundReplacer;
import com.xlythe.view.camera.gl.SegmentationMask;

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
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Background replacement on a real camera: pictures taken while it runs, the camera closing and
 * opening under it, and masks landing where they describe.
 *
 * <p>The background is magenta, which nothing in a room is, so a sample of it says the mask put
 * background there and any other colour says the camera's frame came through.</p>
 */
@RunWith(Parameterized.class)
public final class BackgroundReplacementDeviceTest {
    /** Both backends that draw frames, and so can replace a background. */
    @Parameterized.Parameters(name = "{0}")
    public static Collection<CameraView.Backend> backends() {
        return Arrays.asList(CameraView.Backend.CAMERA2, CameraView.Backend.CAMERA_X);
    }

    /** The backend under test. */
    @Parameterized.Parameter
    public CameraView.Backend backend;

    /** How long a camera is given to open or a capture to report. */
    private static final long TIMEOUT_SECONDS = 30;
    /** How long the segmenter is given to replace most of the preview. */
    private static final long SEGMENTATION_WAIT_MILLIS = 10_000;
    /** How often the screen is looked at while waiting for it. */
    private static final long POLL_MILLIS = 250;
    /** How long a drawn frame is given to reach the screen after a mask changes. */
    private static final long SETTLE_MILLIS = 1_500;
    /** The background colour, opaque magenta. */
    private static final int MAGENTA = 0xFFFF00FF;
    /** How far a channel may stray from magenta, for JPEG and the display's colour handling. */
    private static final int TOLERANCE = 48;
    /** The share of the sampled points that has to be background for a frame to count as replaced. */
    private static final float MOSTLY = 0.6f;
    /** The log tag, for the shares this measures. */
    private static final String TAG = "BackgroundReplacement";

    @Rule
    public final GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO);

    /** Skips, rather than fails, a test that the emulator's camera HAL stalled under. */
    @Rule
    public final TestRule cameraHal = DeviceState.skipWhenTheEmulatorCameraHalStalls();

    @Before
    public void useTheBackendUnderTest() {
        DeviceState.assumeATestableCamera();
        DeviceState.assumeYuvBuffersWork();
        CameraView.setDefaultBackend(backend);
    }

    @After
    public void restoreTheDefault() {
        CameraView.setDefaultBackend(CameraView.Backend.AUTO);
    }

    /**
     * A picture taken while the background is replaced is written, at the size the same replacer
     * draws one without the segmenter running: the frames the segmenter reads cost the picture
     * nothing.
     */
    @Test
    public void aPictureIsTakenWhileTheBackgroundIsReplaced() throws Exception {
        BackgroundReplacer replacer = magentaReplacer();
        AtomicReference<File> drawn = new AtomicReference<>();
        AtomicReference<File> captured = new AtomicReference<>();
        withOpenCamera(null, (scenario, camera) -> {
            // A mask that replaces everything, so the preview says when the replacer draws.
            replacer.setMask(uniformMask(0f, false));
            scenario.onActivity(activity -> activity.getCameraView().setFrameRenderer(replacer));
            awaitBackgroundAt(camera, samples());
            drawn.set(takePicture(camera));
            assumeReplacing(scenario, replacer);
            // Waited out either way: a scene the segmenter takes for a person still gets a picture.
            awaitSegmenting(replacer, camera);
            captured.set(takePicture(camera));
        });
        assertNotNull("the picture failed without the segmenter", drawn.get());
        assertNotNull("the picture failed while the background was replaced", captured.get());
        Bitmap without = ShownFrames.ofPicture(drawn.get());
        Bitmap shown = ShownFrames.ofPicture(captured.get());
        try {
            Log.i(TAG, backend + " picture during replacement is " + shown.getWidth() + "x"
                    + shown.getHeight() + ", " + percent(backgroundShare(shown, samples()))
                    + " background; without the segmenter " + without.getWidth() + "x"
                    + without.getHeight());
            assertEquals("the segmenter's frames should cost the picture nothing",
                    without.getWidth() + "x" + without.getHeight(),
                    shown.getWidth() + "x" + shown.getHeight());
        } finally {
            without.recycle();
            shown.recycle();
        }
    }

    /**
     * The background is still replaced once the camera has been closed and opened again, as the
     * view says it is.
     *
     * <p>Whether the segmenter is running is read off the preview. The replacer is handed a mask
     * that keeps the whole frame, and the segmenter, finding no subject in most of a scene,
     * replaces it with one that turns most of the preview magenta. A stale mask cannot pass for
     * a running segmenter that way. A camera pointed at something the segmenter takes for a
     * person cannot show it either, and the test is skipped.</p>
     */
    @Test
    public void theBackgroundIsReplacedAgainOnceTheCameraReopens() throws Exception {
        BackgroundReplacer replacer = magentaReplacer();
        withOpenCamera(null, (scenario, camera) -> {
            assumeReplacing(scenario, replacer);
            Assume.assumeTrue("the segmenter never replaced most of this scene",
                    awaitSegmenting(replacer, camera));

            reopen(scenario);

            AtomicBoolean replacing = new AtomicBoolean();
            scenario.onActivity(activity ->
                    replacing.set(activity.getCameraView().isReplacingBackground()));
            assertTrue("the view should still say it replaces the background", replacing.get());
            assertTrue("the segmenter should be running again after the camera reopened",
                    awaitSegmenting(replacer, camera));
        });
    }

    /**
     * The background is still replaced, by masks the segmenter goes on making, once the camera
     * has switched lens and back.
     */
    @Test
    public void theBackgroundIsReplacedAcrossALensSwitch() throws Exception {
        BackgroundReplacer replacer = magentaReplacer();
        withOpenCamera(null, (scenario, camera) -> {
            assumeReplacing(scenario, replacer);
            Assume.assumeTrue("the segmenter never replaced most of this scene",
                    awaitSegmenting(replacer, camera));
            AtomicBoolean twoLenses = new AtomicBoolean();
            scenario.onActivity(activity ->
                    twoLenses.set(activity.getCameraView().hasFrontFacingCamera()));
            Assume.assumeTrue("this device has one lens", twoLenses.get());

            scenario.onActivity(activity -> activity.getCameraView().toggleCamera());
            Thread.sleep(SETTLE_MILLIS);
            scenario.onActivity(activity -> activity.getCameraView().toggleCamera());

            assertTrue("the segmenter should be running after switching lens and back",
                    awaitSegmenting(replacer, camera));
        });
    }

    /**
     * Hands the replacer a mask that keeps the whole frame and waits for the segmenter to replace
     * most of the preview, which only a running segmenter does.
     */
    private boolean awaitSegmenting(@NonNull BackgroundReplacer replacer,
            @NonNull CameraView camera) throws InterruptedException {
        replacer.setMask(uniformMask(1f, false));
        // Long enough for a frame drawn with that mask to replace what the screen was showing.
        Thread.sleep(2 * POLL_MILLIS);
        return awaitMostlyBackground(camera);
    }

    /**
     * A mask is drawn the way up it is given, in the preview and in the picture, from either
     * lens: a mask whose top half is subject keeps the top of the frame and replaces the bottom.
     */
    @Test
    public void aMaskIsDrawnTheWayUpItIsGiven() throws Exception {
        for (CameraView.LensFacing lensFacing : CameraView.LensFacing.values()) {
            BackgroundReplacer replacer = magentaReplacer();
            replacer.setMask(halfMask(true /* top */, false /* mirrored */));
            AtomicReference<Bitmap> preview = new AtomicReference<>();
            AtomicReference<File> captured = new AtomicReference<>();
            boolean opened = withOpenCamera(lensFacing, (scenario, camera) -> {
                scenario.onActivity(activity ->
                        activity.getCameraView().setFrameRenderer(replacer));
                preview.set(awaitBackgroundAt(camera, half(false /* bottom */)));
                captured.set(takePicture(camera));
            });
            if (!opened) {
                continue;
            }
            assertHalves(lensFacing + " preview", preview.get(), true /* top */);
            assertNotNull("the " + lensFacing + " picture failed", captured.get());
            Bitmap picture = ShownFrames.ofPicture(captured.get());
            try {
                assertHalves(lensFacing + " picture", picture, true /* top */);
            } finally {
                picture.recycle();
            }
        }
    }

    /**
     * A mask made from a mirrored frame is drawn mirrored: its subject half lands on the other
     * side of the preview, as the subject it was made from does.
     */
    @Test
    public void aMirroredMaskIsDrawnMirrored() throws Exception {
        for (boolean mirrored : new boolean[] {false, true}) {
            BackgroundReplacer replacer = magentaReplacer();
            replacer.setMask(halfMask(false /* left */, mirrored));
            AtomicReference<Bitmap> preview = new AtomicReference<>();
            withOpenCamera(null, (scenario, camera) -> {
                scenario.onActivity(activity ->
                        activity.getCameraView().setFrameRenderer(replacer));
                // The background lands on the right, or on the left once mirrored.
                preview.set(awaitBackgroundAt(camera, side(mirrored /* left */)));
            });
            // A left-half mask keeps the left of the frame, unless it was made mirrored.
            assertSides((mirrored ? "mirrored" : "unmirrored") + " preview", preview.get(),
                    !mirrored /* subject on the left */);
        }
    }

    /**
     * A background image is shown the way up it was given, from either lens, and is not mirrored
     * along with a mirrored mask: an image magenta only in its top left quarter is magenta only
     * in the top left of the preview and the picture.
     */
    @Test
    public void aBackgroundImageIsShownTheWayUpItIsGiven() throws Exception {
        for (CameraView.LensFacing lensFacing : CameraView.LensFacing.values()) {
            Bitmap image = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
            image.eraseColor(Color.CYAN);
            for (int y = 0; y < 32; y++) {
                for (int x = 0; x < 32; x++) {
                    image.setPixel(x, y, MAGENTA);
                }
            }
            BackgroundReplacer replacer = new BackgroundReplacer();
            replacer.setBackgroundImage(image);
            replacer.setMask(uniformMask(0f, true /* mirrored */));
            AtomicReference<Bitmap> preview = new AtomicReference<>();
            AtomicReference<File> captured = new AtomicReference<>();
            boolean opened = withOpenCamera(lensFacing, (scenario, camera) -> {
                scenario.onActivity(activity ->
                        activity.getCameraView().setFrameRenderer(replacer));
                preview.set(awaitBackgroundAt(camera, topLeftQuarter()));
                captured.set(takePicture(camera));
            });
            if (!opened) {
                continue;
            }
            assertTopLeftQuarter(lensFacing + " preview", preview.get());
            assertNotNull("the " + lensFacing + " picture failed", captured.get());
            Bitmap picture = ShownFrames.ofPicture(captured.get());
            try {
                assertTopLeftQuarter(lensFacing + " picture", picture);
            } finally {
                picture.recycle();
            }
        }
    }

    /**
     * Asserts the top left quarter is magenta and the other three are not. Read from below the
     * system bars that cover the top of an edge-to-edge preview.
     */
    private static void assertTopLeftQuarter(@NonNull String what, @NonNull Bitmap shown) {
        float topLeft = backgroundShare(shown, topLeftQuarter());
        float topRight = backgroundShare(shown, grid(0.6f, 0.9f, 0.15f, 0.4f));
        float bottomLeft = backgroundShare(shown, grid(0.1f, 0.4f, 0.6f, 0.9f));
        float bottomRight = backgroundShare(shown, grid(0.6f, 0.9f, 0.6f, 0.9f));
        String measured = String.format(Locale.US, "%s: magenta %s %s above %s %s", what,
                percent(topLeft), percent(topRight), percent(bottomLeft), percent(bottomRight));
        Log.i(TAG, measured);
        assertTrue("only the top left should be magenta; " + measured, topLeft > 0.8f
                && topRight < 0.2f && bottomLeft < 0.2f && bottomRight < 0.2f);
    }

    /** Enters replacement, skipping where this device cannot segment. */
    private static void assumeReplacing(@NonNull ActivityScenario<CameraTestActivity> scenario,
            @NonNull BackgroundReplacer replacer) {
        AtomicBoolean started = new AtomicBoolean();
        scenario.onActivity(activity ->
                started.set(activity.getCameraView().enterBackgroundReplacement(replacer)));
        Assume.assumeTrue("this device cannot segment", started.get());
    }

    /** Closes the camera and opens it again, waiting until it reports open. */
    private static void reopen(@NonNull ActivityScenario<CameraTestActivity> scenario)
            throws InterruptedException {
        CountDownLatch reopened = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            CameraView camera = activity.getCameraView();
            camera.close();
            camera.setOnCameraStateChangedListener(new CameraView.OnCameraStateChangedListener() {
                @Override public void onCameraOpened() {
                    reopened.countDown();
                }

                @Override public void onCameraClosed() {}
            });
            camera.open();
        });
        assertTrue("the camera did not open again " + DeviceState.whyTheCameraMightNotOpen(),
                reopened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    /** Waits until most of the preview is background, and says whether it got there. */
    private boolean awaitMostlyBackground(@NonNull CameraView camera)
            throws InterruptedException {
        long deadline = SystemClock.uptimeMillis() + SEGMENTATION_WAIT_MILLIS;
        float share = 0;
        while (SystemClock.uptimeMillis() < deadline) {
            Bitmap shown = ShownFrames.onScreen(camera);
            try {
                share = backgroundShare(shown, samples());
            } finally {
                shown.recycle();
            }
            if (share >= MOSTLY) {
                Log.i(TAG, backend + " preview is " + percent(share) + " background");
                return true;
            }
            Thread.sleep(POLL_MILLIS);
        }
        Log.i(TAG, backend + " preview reached only " + percent(share) + " background");
        return false;
    }

    /**
     * The preview once most of the given points show the background, or as it is when waiting
     * for that runs out. Waited for rather than taken after a pause, because a renderer set on an
     * open camera rebuilds its session first, and how long that takes varies.
     */
    @NonNull
    private static Bitmap awaitBackgroundAt(@NonNull CameraView camera, @NonNull float[][] points)
            throws InterruptedException {
        long deadline = SystemClock.uptimeMillis() + SEGMENTATION_WAIT_MILLIS;
        Bitmap shown = ShownFrames.onScreen(camera);
        while (backgroundShare(shown, points) < MOSTLY && SystemClock.uptimeMillis() < deadline) {
            shown.recycle();
            Thread.sleep(POLL_MILLIS);
            shown = ShownFrames.onScreen(camera);
        }
        return shown;
    }

    /** Takes a picture and returns the file, or null if the capture failed. */
    @Nullable
    private static File takePicture(@NonNull CameraView camera) throws Exception {
        File file = File.createTempFile("replaced", ".jpg",
                ApplicationProvider.getApplicationContext().getCacheDir());
        assertTrue(file.delete() || !file.exists());
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<File> captured = new AtomicReference<>();
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
        return captured.get();
    }

    /**
     * Asserts the subject half of a {@link #halfMask} came through and the other half is
     * background. Read a quarter of the way in from each edge, inside either half however a
     * preview crops.
     */
    private static void assertHalves(@NonNull String what, @NonNull Bitmap shown, boolean top) {
        float subject = backgroundShare(shown, half(top));
        float background = backgroundShare(shown, half(!top));
        String measured = String.format(Locale.US, "%s: subject half %s background, other half %s",
                what, percent(subject), percent(background));
        Log.i(TAG, measured);
        assertTrue("the " + (top ? "top" : "bottom") + " half should be the camera's frame; "
                + measured, subject < 0.2f);
        assertTrue("the " + (top ? "bottom" : "top") + " half should be background; " + measured,
                background > 0.8f);
    }

    /** {@link #assertHalves} across the frame rather than down it. */
    private static void assertSides(@NonNull String what, @NonNull Bitmap shown, boolean left) {
        float subject = backgroundShare(shown, side(left));
        float background = backgroundShare(shown, side(!left));
        String measured = String.format(Locale.US, "%s: subject side %s background, other side %s",
                what, percent(subject), percent(background));
        Log.i(TAG, measured);
        assertTrue("the " + (left ? "left" : "right") + " side should be the camera's frame; "
                + measured, subject < 0.2f);
        assertTrue("the " + (left ? "right" : "left") + " side should be background; "
                + measured, background > 0.8f);
    }

    /** The share of the given points, as fractions of the frame, that are background. */
    private static float backgroundShare(@NonNull Bitmap bitmap, @NonNull float[][] points) {
        int background = 0;
        for (float[] point : points) {
            int pixel = bitmap.getPixel(Math.round(point[0] * (bitmap.getWidth() - 1)),
                    Math.round(point[1] * (bitmap.getHeight() - 1)));
            if (Math.abs(Color.red(pixel) - Color.red(MAGENTA)) <= TOLERANCE
                    && Color.green(pixel) <= TOLERANCE
                    && Math.abs(Color.blue(pixel) - Color.blue(MAGENTA)) <= TOLERANCE) {
                background++;
            }
        }
        return (float) background / points.length;
    }

    /** A grid of points across the middle of the frame, clear of the edges a preview crops. */
    @NonNull
    private static float[][] samples() {
        return grid(0.15f, 0.85f, 0.15f, 0.85f);
    }

    /**
     * Points across the top or the bottom half, a margin away from the middle and the edges, and
     * below the system bars that cover the top of an edge-to-edge preview.
     */
    @NonNull
    private static float[][] half(boolean top) {
        return top ? grid(0.15f, 0.85f, 0.15f, 0.4f) : grid(0.15f, 0.85f, 0.6f, 0.9f);
    }

    /** Points across the left or the right side, a margin away from the middle and the edges. */
    @NonNull
    private static float[][] side(boolean left) {
        return left ? grid(0.1f, 0.4f, 0.15f, 0.85f) : grid(0.6f, 0.9f, 0.15f, 0.85f);
    }

    /** Points across the top left quarter, below the system bars. */
    @NonNull
    private static float[][] topLeftQuarter() {
        return grid(0.1f, 0.4f, 0.15f, 0.4f);
    }

    /** Five by five points spread evenly across a rectangle of the frame. */
    @NonNull
    private static float[][] grid(float left, float right, float top, float bottom) {
        int steps = 5;
        float[][] points = new float[steps * steps][];
        for (int row = 0; row < steps; row++) {
            for (int column = 0; column < steps; column++) {
                points[row * steps + column] = new float[] {
                        left + (right - left) * column / (steps - 1),
                        top + (bottom - top) * row / (steps - 1)};
            }
        }
        return points;
    }

    /**
     * A mask that is subject over one half and background over the other: the top half, or the
     * left half, row 0 at the top as a segmenter writes it.
     */
    @NonNull
    private static SegmentationMask halfMask(boolean topHalf, boolean mirrored) {
        int width = 16;
        int height = 16;
        ByteBuffer buffer = ByteBuffer.allocate(width * height * 4).order(ByteOrder.nativeOrder());
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean subject = topHalf ? y < height / 2 : x < width / 2;
                buffer.putFloat(subject ? 1f : 0f);
            }
        }
        buffer.rewind();
        return new SegmentationMask(buffer, width, height, mirrored);
    }

    /** A mask of one confidence everywhere. */
    @NonNull
    private static SegmentationMask uniformMask(float confidence, boolean mirrored) {
        int width = 16;
        int height = 16;
        ByteBuffer buffer = ByteBuffer.allocate(width * height * 4).order(ByteOrder.nativeOrder());
        for (int i = 0; i < width * height; i++) {
            buffer.putFloat(confidence);
        }
        buffer.rewind();
        return new SegmentationMask(buffer, width, height, mirrored);
    }

    @NonNull
    private static BackgroundReplacer magentaReplacer() {
        BackgroundReplacer replacer = new BackgroundReplacer();
        replacer.setBackgroundColor(MAGENTA);
        return replacer;
    }

    @NonNull
    private static String percent(float share) {
        return Math.round(share * 100) + "%";
    }

    private interface CameraAction {
        void run(@NonNull ActivityScenario<CameraTestActivity> scenario,
                @NonNull CameraView camera) throws Exception;
    }

    /**
     * Opens the camera, on a lens if one is named, and runs the action.
     *
     * @return false, having run nothing, when there is no camera facing that way
     */
    private boolean withOpenCamera(@Nullable CameraView.LensFacing lensFacing,
            @NonNull CameraAction action) throws Exception {
        try (ActivityScenario<CameraTestActivity> scenario =
                     ActivityScenario.launch(CameraTestActivity.class)) {
            CountDownLatch opened = new CountDownLatch(1);
            AtomicReference<CameraView> held = new AtomicReference<>();
            AtomicBoolean effects = new AtomicBoolean();
            scenario.onActivity(activity -> {
                CameraView camera = activity.getCameraView();
                held.set(camera);
                effects.set(camera.isFrameEffectSupported());
                if (lensFacing != null) {
                    camera.setLensFacing(lensFacing);
                }
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
            Assume.assumeTrue("this build reports no frame effects", effects.get());
            assertTrue("the camera did not open " + DeviceState.whyTheCameraMightNotOpen(),
                    opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            CameraView camera = held.get();
            assertNotNull(camera);
            AtomicReference<CameraView.LensFacing> opensOn = new AtomicReference<>();
            scenario.onActivity(activity -> opensOn.set(activity.getCameraView().getLensFacing()));
            if (lensFacing != null && opensOn.get() != lensFacing) {
                scenario.onActivity(activity -> activity.getCameraView().close());
                return false;
            }
            DeviceState.turnTheFlashOff(camera);
            try {
                action.run(scenario, camera);
            } finally {
                scenario.onActivity(activity -> {
                    CameraView view = activity.getCameraView();
                    view.exitBackgroundReplacement();
                    view.setFrameRenderer(null);
                    view.close();
                });
            }
            return true;
        }
    }
}
