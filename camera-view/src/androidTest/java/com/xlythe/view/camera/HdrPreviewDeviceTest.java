package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.pm.ActivityInfo;
import android.graphics.RectF;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import com.xlythe.view.camera.test.CameraTestActivity;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * HDR framed on an HDR display, on each backend that can stream it: whether the preview really
 * reaches the display as HDR, and whether everything is put back afterwards.
 *
 * <p>A screenshot cannot show HDR, so this asks the platform instead: the window's colour mode
 * and the compositor's own account of the dataspace of the preview's layer, logged under
 * {@link #TAG} as well as asserted, because that is the evidence a device run leaves behind.</p>
 *
 * <p>Skipped where there is nothing to show: a display without HDR, or a camera without a 10-bit
 * profile. That is every emulator, where it checks instead that the preview reports HDR preview
 * unsupported and stays as it was.</p>
 */
@RunWith(AndroidJUnit4.class)
public final class HdrPreviewDeviceTest {
    private static final String TAG = "HdrPreviewDeviceTest";
    /** How long a bind, a rebind or a rotation is given. */
    private static final long TIMEOUT_MILLIS = 15_000;

    @Rule
    public final GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO);

    @Before
    public void requireTenBitCameras() {
        Assume.assumeTrue("no camera produces a 10-bit stream below API 33",
                Build.VERSION.SDK_INT >= 33);
    }

    @After
    public void restoreTheDefaultBackend() {
        CameraView.setDefaultBackend(CameraView.Backend.AUTO);
    }

    @Test
    public void cameraXFramesHdrInHdr() throws Exception {
        framesHdrInHdr(CameraView.Backend.CAMERA_X);
    }

    @Test
    public void camera2FramesHdrInHdr() throws Exception {
        framesHdrInHdr(CameraView.Backend.CAMERA2);
    }

    @Test
    public void cameraXFramesUltraHdrInHdr() throws Exception {
        framesUltraHdrInHdr(CameraView.Backend.CAMERA_X);
    }

    @Test
    public void camera2FramesUltraHdrInHdr() throws Exception {
        framesUltraHdrInHdr(CameraView.Backend.CAMERA2);
    }

    private void framesHdrInHdr(CameraView.Backend backend) throws Exception {
        CameraView.setDefaultBackend(backend);
        String name = backend.name().toLowerCase();
        try (ActivityScenario<CameraTestActivity> scenario =
                     ActivityScenario.launch(CameraTestActivity.class)) {
            open(scenario);
            assumeHdr(scenario, name, true);

            // SDR first: the TextureView, and a window left alone.
            scenario.onActivity(activity -> {
                assertFalse(activity.getCameraView().getHdrSurface().isShowing());
                assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, activity.getWindow().getColorMode());
            });
            Thread.sleep(1_500);
            HdrDeviceEvidence.compositor(TAG, name + "-sdr");
            HdrDeviceEvidence.screenshot(name + "-sdr");

            scenario.onActivity(activity ->
                    activity.getCameraView().setDynamicRange(CameraView.DynamicRange.HDR));
            assertTrue("the preview did not move onto the HDR surface",
                    waitFor(scenario, activity -> activity.getCameraView().getHdrSurface().isShowing()));
            Thread.sleep(1_500);
            scenario.onActivity(activity -> {
                assertEquals(ActivityInfo.COLOR_MODE_HDR, activity.getWindow().getColorMode());
                assertEquals(View.INVISIBLE, activity.getCameraView().asTextureView().getVisibility());
                assertCovers(activity.getCameraView());
                logDisplay(activity, name + "-hdr");
            });
            assertHdrLayer(name + "-hdr");
            HdrDeviceEvidence.cameraStreams(TAG, name + "-hdr");
            HdrDeviceEvidence.screenshot(name + "-hdr");

            // A still, which the HDR preview must not have cost.
            assertTrue("the picture failed beside an HDR preview",
                    takePicture(scenario, "hdr-preview-" + name + ".jpg") != null);
            assertTrue("taking a picture dropped the HDR preview",
                    waitFor(scenario, activity -> activity.getCameraView().getHdrSurface().isShowing()));

            // A recording, which stays framed in HDR throughout and is itself HLG or PQ.
            CountDownLatch recorded = new CountDownLatch(1);
            AtomicReference<File> video = new AtomicReference<>();
            File videoFile = new File(InstrumentationRegistry.getInstrumentation()
                    .getTargetContext().getCacheDir(), "hdr-preview-" + name + ".mp4");
            scenario.onActivity(activity -> {
                activity.getCameraView().setOnVideoCapturedListener(new CameraView.OnVideoCapturedListener() {
                    @Override public void onVideoConfirmation() {}
                    @Override public void onVideoCaptured(File file) {
                        video.set(file);
                        recorded.countDown();
                    }
                    @Override public void onFailure() { recorded.countDown(); }
                });
                activity.getCameraView().startRecording(videoFile);
            });
            assertTrue("recording did not start", waitFor(scenario, activity -> activity.getCameraView().isRecording()));
            assertTrue("the preview left the HDR surface while recording", waitFor(scenario,
                    activity -> activity.getCameraView().getHdrSurface().isShowing()));
            Thread.sleep(2_000);
            scenario.onActivity(activity -> {
                assertTrue("the preview left the HDR surface while recording",
                        activity.getCameraView().getHdrSurface().isShowing());
                assertEquals(ActivityInfo.COLOR_MODE_HDR, activity.getWindow().getColorMode());
            });
            assertHdrLayer(name + "-recording");
            HdrDeviceEvidence.cameraStreams(TAG, name + "-recording");
            scenario.onActivity(activity -> activity.getCameraView().stopRecording());
            assertTrue("the recording did not finish", recorded.await(20, TimeUnit.SECONDS));
            assertTrue("no recording was written", video.get() != null && video.get().length() > 0);
            int transfer = colorTransfer(video.get());
            Log.i(TAG, name + " recording colour transfer=" + transfer);
            assertTrue("the recording is not HDR: transfer " + transfer,
                    transfer == MediaFormat.COLOR_TRANSFER_HLG
                            || transfer == MediaFormat.COLOR_TRANSFER_ST2084);
            assertTrue("the preview did not come back onto the HDR surface after recording",
                    waitFor(scenario, activity -> activity.getCameraView().getHdrSurface().isShowing()));

            // Turned off and on again, which the app may do to animate the view.
            scenario.onActivity(activity -> activity.getCameraView().setHdrPreviewEnabled(false));
            assertTrue("turning HDR preview off left it on", waitFor(scenario,
                    activity -> !activity.getCameraView().getHdrSurface().isShowing()));
            scenario.onActivity(activity -> {
                assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, activity.getWindow().getColorMode());
                assertEquals(View.VISIBLE, activity.getCameraView().asTextureView().getVisibility());
            });
            scenario.onActivity(activity -> activity.getCameraView().setHdrPreviewEnabled(true));
            assertTrue("turning HDR preview on again did not show it", waitFor(scenario,
                    activity -> activity.getCameraView().getHdrSurface().isShowing()));

            // A preview smaller than the window: the overflow of the centre crop must not show.
            scenario.onActivity(activity -> {
                ViewGroup.LayoutParams params = activity.getCameraView().getLayoutParams();
                params.height = activity.getCameraView().getHeight() / 2;
                activity.getCameraView().setLayoutParams(params);
            });
            Thread.sleep(2_500);
            scenario.onActivity(activity -> assertCovers(activity.getCameraView()));
            HdrDeviceEvidence.screenshot(name + "-hdr-half");
            scenario.onActivity(activity -> {
                ViewGroup.LayoutParams params = activity.getCameraView().getLayoutParams();
                params.height = ViewGroup.LayoutParams.MATCH_PARENT;
                activity.getCameraView().setLayoutParams(params);
            });
            Thread.sleep(1_500);

            // Landscape, where the compositor rather than the transform turns the stream.
            // Turning recreates the activity, whose new CameraView restores HDR from saved state.
            scenario.onActivity(activity ->
                    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
            assertTrue("the activity did not turn", waitFor(scenario, activity ->
                    activity.getCameraView().getWidth() > activity.getCameraView().getHeight()));
            open(scenario);
            assertTrue("the turned preview did not move onto the HDR surface", waitFor(scenario,
                    activity -> activity.getCameraView().getHdrSurface().isShowing()));
            Thread.sleep(2_000);
            scenario.onActivity(activity -> {
                assertCovers(activity.getCameraView());
                logDisplay(activity, name + "-hdr-landscape");
            });
            assertHdrLayer(name + "-hdr-landscape");
            HdrDeviceEvidence.screenshot(name + "-hdr-landscape");
            scenario.onActivity(activity -> activity.getCameraView().setDynamicRange(CameraView.DynamicRange.SDR));
            assertTrue(waitFor(scenario, activity -> !activity.getCameraView().getHdrSurface().isShowing()));
            Thread.sleep(1_500);
            HdrDeviceEvidence.screenshot(name + "-sdr-landscape");
            scenario.onActivity(activity -> {
                activity.getCameraView().setDynamicRange(CameraView.DynamicRange.HDR);
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            });
            assertTrue(waitFor(scenario, activity ->
                    activity.getCameraView().getWidth() < activity.getCameraView().getHeight()));
            open(scenario);
            assertTrue(waitFor(scenario,
                    activity -> activity.getCameraView().getHdrSurface().isShowing()));

            // The front camera, mirrored by the camera on its way to the SurfaceView.
            AtomicBoolean front = new AtomicBoolean();
            scenario.onActivity(activity -> {
                if (activity.getCameraView().hasFrontFacingCamera()) {
                    front.set(true);
                    activity.getCameraView().toggleCamera();
                }
            });
            if (front.get()) {
                Thread.sleep(2_500);
                assertTrue("the front camera did not frame HDR in HDR", waitFor(scenario,
                        activity -> activity.getCameraView().isUsingFrontFacingCamera()
                                && activity.getCameraView().getHdrSurface().isShowing()));
                Thread.sleep(1_500);
                scenario.onActivity(activity -> assertCovers(activity.getCameraView()));
                assertHdrLayer(name + "-hdr-front");
                HdrDeviceEvidence.screenshot(name + "-hdr-front");
                scenario.onActivity(activity -> activity.getCameraView().toggleCamera());
                assertTrue(waitFor(scenario, activity -> !activity.getCameraView().isUsingFrontFacingCamera()
                        && activity.getCameraView().getHdrSurface().isShowing()));
            }

            // Back to SDR: the TextureView and the window's own mode.
            scenario.onActivity(activity -> activity.getCameraView().setDynamicRange(CameraView.DynamicRange.SDR));
            assertTrue("the preview did not return to the TextureView",
                    waitFor(scenario, activity -> !activity.getCameraView().getHdrSurface().isShowing()));
            scenario.onActivity(activity -> {
                assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, activity.getWindow().getColorMode());
                assertEquals(View.VISIBLE, activity.getCameraView().asTextureView().getVisibility());
            });
            Thread.sleep(1_000);
            String after = HdrDeviceEvidence.compositor(TAG, name + "-sdr-again");
            assertFalse("an HDR layer outlived the HDR preview", HdrDeviceEvidence.showsHdr(after));

            // And closing while HDR puts the window back too.
            scenario.onActivity(activity -> activity.getCameraView().setDynamicRange(CameraView.DynamicRange.HDR));
            assertTrue(waitFor(scenario, activity -> activity.getCameraView().getHdrSurface().isShowing()));
            scenario.onActivity(activity -> {
                activity.getCameraView().close();
                assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, activity.getWindow().getColorMode());
                assertFalse(activity.getCameraView().getHdrSurface().isShowing());
            });
        }
    }

    private void framesUltraHdrInHdr(CameraView.Backend backend) throws Exception {
        CameraView.setDefaultBackend(backend);
        String name = backend.name().toLowerCase() + "-ultrahdr";
        try (ActivityScenario<CameraTestActivity> scenario =
                     ActivityScenario.launch(CameraTestActivity.class)) {
            open(scenario);
            assumeHdr(scenario, name, false);
            AtomicBoolean ultraHdr = new AtomicBoolean();
            scenario.onActivity(activity -> ultraHdr.set(activity.getCameraView()
                    .isPictureFormatSupported(CameraView.PictureFormat.ULTRA_HDR)));
            Assume.assumeTrue("this camera writes no Ultra HDR", ultraHdr.get());

            Thread.sleep(1_500);
            HdrDeviceEvidence.compositor(TAG, name + "-jpeg");
            HdrDeviceEvidence.screenshot(name + "-jpeg");
            scenario.onActivity(activity ->
                    activity.getCameraView().setPictureFormat(CameraView.PictureFormat.ULTRA_HDR));
            assertTrue("an Ultra HDR picture was not framed in HDR", waitFor(scenario,
                    activity -> activity.getCameraView().getHdrSurface().isShowing()));
            Thread.sleep(1_500);
            scenario.onActivity(activity -> {
                assertEquals(ActivityInfo.COLOR_MODE_HDR, activity.getWindow().getColorMode());
                assertCovers(activity.getCameraView());
            });
            assertHdrLayer(name);
            HdrDeviceEvidence.cameraStreams(TAG, name);
            HdrDeviceEvidence.screenshot(name);

            File picture = takePicture(scenario, name + ".jpg");
            assertTrue("no Ultra HDR picture beside an HDR preview", picture != null);
            MediaTags tags = MediaTags.open(picture);
            Log.i(TAG, name + " picture " + picture.length() + " bytes, gain map "
                    + tags.getGainMap());
            assertNotEquals("the picture taken beside the HDR preview has no gain map",
                    MediaTags.GAIN_MAP_NONE, tags.getGainMap());
            assertTrue("taking an Ultra HDR picture dropped the HDR preview", waitFor(scenario,
                    activity -> activity.getCameraView().getHdrSurface().isShowing()));

            scenario.onActivity(activity ->
                    activity.getCameraView().setPictureFormat(CameraView.PictureFormat.JPEG));
            assertTrue("a JPEG kept the HDR preview", waitFor(scenario,
                    activity -> !activity.getCameraView().getHdrSurface().isShowing()));
            scenario.onActivity(activity -> assertEquals(ActivityInfo.COLOR_MODE_DEFAULT,
                    activity.getWindow().getColorMode()));
        }
    }

    /**
     * Skips where nothing can be framed in HDR, after checking the camera says so: a display
     * without HDR, or a camera without a 10-bit stream.
     */
    private static void assumeHdr(ActivityScenario<CameraTestActivity> scenario, String name,
            boolean needsHdrVideo) {
        AtomicBoolean hdrDisplay = new AtomicBoolean();
        AtomicBoolean hdrCamera = new AtomicBoolean();
        AtomicBoolean hdrPreview = new AtomicBoolean();
        scenario.onActivity(activity -> {
            CameraView camera = activity.getCameraView();
            Display display = camera.getDisplay();
            hdrDisplay.set(display.isHdr());
            hdrCamera.set(camera.isDynamicRangeSupported(CameraView.DynamicRange.HDR));
            hdrPreview.set(camera.isHdrPreviewSupported());
            Log.i(TAG, name + ": display isHdr=" + display.isHdr() + " hdrTypes="
                    + Arrays.toString(display.getHdrCapabilities().getSupportedHdrTypes())
                    + " hdrSdrRatio=" + display.getHdrSdrRatio()
                    + " cameraHdr=" + hdrCamera.get() + " hdrPreviewSupported=" + hdrPreview.get()
                    + " backend=" + camera.getBackend());
        });
        if (!hdrDisplay.get()) {
            assertFalse("a display without HDR reported HDR preview supported", hdrPreview.get());
        }
        Assume.assumeTrue("this display cannot show HDR", hdrDisplay.get());
        Assume.assumeTrue("this camera has no HDR preview", hdrPreview.get());
        if (needsHdrVideo) {
            Assume.assumeTrue("this camera records no HDR", hdrCamera.get());
        }
    }

    /** Takes a picture into the cache, returning it, or null if it failed. */
    private static File takePicture(ActivityScenario<CameraTestActivity> scenario, String name)
            throws InterruptedException {
        CountDownLatch pictured = new CountDownLatch(1);
        AtomicReference<File> saved = new AtomicReference<>();
        File picture = new File(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getCacheDir(), name);
        scenario.onActivity(activity -> {
            activity.getCameraView().setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
                @Override public void onImageConfirmation() {}
                @Override public void onImageCaptured(File file) {
                    if (file.length() > 0) saved.set(file);
                    pictured.countDown();
                }
                @Override public void onFailure() { pictured.countDown(); }
            });
            activity.getCameraView().takePicture(picture);
        });
        assertTrue("no picture within 20 seconds", pictured.await(20, TimeUnit.SECONDS));
        return saved.get();
    }

    /** The compositor is composing a layer of ours in BT.2020 HLG or PQ. */
    private static void assertHdrLayer(String label) throws IOException {
        String dump = HdrDeviceEvidence.compositor(TAG, label);
        assertTrue("no layer of ours is composed with an HLG or PQ dataspace: "
                + HdrDeviceEvidence.composedDataspaces(dump), HdrDeviceEvidence.showsHdr(dump));
    }

    /** Opens the current activity's CameraView, dark and without confirmations. */
    private static void open(ActivityScenario<CameraTestActivity> scenario)
            throws InterruptedException {
        scenario.onActivity(activity -> {
            CameraView view = activity.getCameraView();
            view.setFlash(CameraView.Flash.OFF);
            view.setShutterSoundEnabled(false);
            view.setImageConfirmationEnabled(false);
            view.setVideoConfirmationEnabled(false);
            view.open();
        });
        assertTrue("the camera did not open",
                waitFor(scenario, activity -> activity.getCameraView().isOpen()));
    }

    /** The preview, as scaled onto the SurfaceView, covers the whole of CameraView. */
    private static void assertCovers(CameraView camera) {
        SurfaceView surfaceView = camera.getHdrSurface().getSurfaceView();
        RectF shown = new RectF(
                surfaceView.getTranslationX(), surfaceView.getTranslationY(),
                surfaceView.getTranslationX() + surfaceView.getWidth() * surfaceView.getScaleX(),
                surfaceView.getTranslationY() + surfaceView.getHeight() * surfaceView.getScaleY());
        Log.i(TAG, "view " + camera.getWidth() + "x" + camera.getHeight() + " surface shown at "
                + shown + " scale " + surfaceView.getScaleX() + "," + surfaceView.getScaleY());
        assertTrue("the HDR preview leaves part of the view uncovered: " + shown,
                shown.left <= 0.5f && shown.top <= 0.5f
                        && shown.right >= camera.getWidth() - 0.5f
                        && shown.bottom >= camera.getHeight() - 0.5f);
        // Centre-cropped, so whichever axis overflows overflows evenly.
        assertEquals(camera.getWidth() / 2f, shown.centerX(), 2f);
        assertEquals(camera.getHeight() / 2f, shown.centerY(), 2f);
    }

    private static void logDisplay(CameraTestActivity activity, String label) {
        Display display = activity.getCameraView().getDisplay();
        Log.i(TAG, label + ": windowColorMode=" + activity.getWindow().getColorMode()
                + " desiredHdrHeadroom=" + (Build.VERSION.SDK_INT >= 35
                        ? activity.getWindow().getDesiredHdrHeadroom() : -1f)
                + " hdrSdrRatio=" + display.getHdrSdrRatio()
                + " highestHdrSdrRatio=" + (Build.VERSION.SDK_INT >= 35
                        ? display.getHighestHdrSdrRatio() : -1f));
    }

    private static int colorTransfer(File video) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(video.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    Log.i(TAG, "recording format " + format);
                    return format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)
                            ? format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) : -1;
                }
            }
            return -1;
        } finally {
            extractor.release();
        }
    }

    private static boolean waitFor(ActivityScenario<CameraTestActivity> scenario,
            Predicate<CameraTestActivity> condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            AtomicBoolean met = new AtomicBoolean();
            scenario.onActivity(activity -> met.set(condition.test(activity)));
            if (met.get()) return true;
            Thread.sleep(100);
        }
        return false;
    }
}
