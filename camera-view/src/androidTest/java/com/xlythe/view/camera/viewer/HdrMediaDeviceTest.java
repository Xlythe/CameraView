package com.xlythe.view.camera.viewer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.xlythe.view.camera.HdrDeviceEvidence;
import com.xlythe.view.camera.VideoView;
import com.xlythe.view.camera.VideoViewAccess;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HDR media on an HDR display, as MediaView shows it: videos whose frames are HLG or PQ, and
 * pictures that are HDR through a gain map or through their own colour space.
 *
 * <p>A screenshot cannot show HDR brightness, so each case logs the compositor's account of this
 * app's layers and the window's colour mode, with HDR on and off. The files are pushed to
 * /data/local/tmp/hdr-media; without them, and on a display without HDR, this skips.</p>
 */
@RunWith(AndroidJUnit4.class)
public class HdrMediaDeviceTest {
    private static final String TAG = "HdrMediaDevice";

    /** Where the files are pushed, which the shell can read and the test cannot. */
    private static final String PUSHED = "/data/local/tmp/hdr-media/";

    /** How long a file is given to load, decode and play. */
    private static final long TIMEOUT_MILLIS = 20_000;

    @Rule
    public final ActivityScenarioRule<ViewerTestActivity> mActivity =
            new ActivityScenarioRule<>(ViewerTestActivity.class);

    private Context mContext;
    private ActivityScenario<ViewerTestActivity> mScenario;
    private File mDirectory;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mScenario = mActivity.getScenario();
        mDirectory = new File(mContext.getCacheDir(), "hdr-media");
        assertTrue(mDirectory.isDirectory() || mDirectory.mkdirs());
    }

    /**
     * An HLG video and a PQ video play on the HDR surface, and only there are they HDR. On a
     * display without HDR they play on the TextureView, as before.
     */
    @Test
    public void hdrVideosReachTheDisplayAsHdr() throws Exception {
        boolean hdrDisplay = isHdrDisplay();
        for (String name : new String[] {"hlg.mp4", "hdr10.mp4"}) {
            File file = pushed(name);
            MediaView view = show(file);
            assertTrue(name + " did not load", waitFor(() -> view.getContentView() instanceof VideoView
                    && view.getMediaType() == MediaView.VIDEO));
            VideoView video = (VideoView) view.getContentView();
            if (!hdrDisplay) {
                onMain(view::play);
                Thread.sleep(1_500);
                onMain(() -> {
                    assertFalse(name + " left the TextureView on a display without HDR",
                            VideoViewAccess.isShowingHdr(video));
                    logWindow(name + "-sdr-display");
                });
                HdrDeviceEvidence.compositor(TAG, name + "-sdr-display");
                HdrDeviceEvidence.screenshot("video-" + name + "-sdr-display");
                remove(view);
                continue;
            }
            assertTrue(name + " did not move onto the HDR surface",
                    waitFor(() -> VideoViewAccess.isShowingHdr(video)));
            onMain(view::play);
            Thread.sleep(1_500);
            onMain(() -> {
                assertEquals(ActivityInfo.COLOR_MODE_HDR, activity().getWindow().getColorMode());
                logWindow(name + "-surfaceview");
            });
            String after = HdrDeviceEvidence.compositor(TAG, name + "-surfaceview");
            assertTrue(name + " shows no HLG or PQ layer", HdrDeviceEvidence.showsHdr(after));
            HdrDeviceEvidence.screenshot("video-" + name + "-surfaceview");

            // What MediaView did before: the window in HDR, the video on the TextureView.
            onMain(() -> video.setHdrEnabled(false));
            assertTrue(name + " did not go back to the TextureView",
                    waitFor(() -> !VideoViewAccess.isShowingHdr(video)));
            onMain(view::play);
            Thread.sleep(1_500);
            onMain(() -> logWindow(name + "-textureview"));
            String before = HdrDeviceEvidence.compositor(TAG, name + "-textureview");
            assertFalse(name + " kept an HDR layer on the TextureView",
                    HdrDeviceEvidence.showsHdr(before));
            HdrDeviceEvidence.screenshot("video-" + name + "-textureview");

            onMain(() -> video.setHdrEnabled(true));
            assertTrue(waitFor(() -> VideoViewAccess.isShowingHdr(video)));
            remove(view);
            Thread.sleep(500);
            onMain(() -> assertEquals("the window stayed in HDR after the video went",
                    ActivityInfo.COLOR_MODE_DEFAULT, activity().getWindow().getColorMode()));
        }
    }

    /**
     * Pictures that are HDR - by a gain map, or by their own BT.2020 HLG or PQ colour space -
     * hold the window in HDR, keep their colour space when decoded, and let go when turned off.
     */
    @Test
    public void hdrPicturesHoldTheWindowInHdr() throws Exception {
        assumeHdrDisplay();
        assumeTrue("HDR colour spaces arrived in Android 14", Build.VERSION.SDK_INT >= 34);
        // Each of these is HDR as Android 14 and later decodes it, or as its header says; an
        // Apple HEIC's gain map and an HLG ICC profile are logged rather than required.
        java.util.Set<String> required = new java.util.HashSet<>(java.util.Arrays.asList(
                "ultrahdr.jpg", "iso-gainmap.heic", "pq.avif", "pq.png"));
        for (String name : new String[] {"ultrahdr.jpg", "iso-gainmap.heic", "apple-gainmap.heic",
                "pq.avif", "pq.png", "hlg.png"}) {
            File file = pushed(name);
            if (file == null) {
                continue;
            }
            for (boolean hdr : new boolean[] {false, true}) {
                String label = name + (hdr ? "-hdr" : "-sdr");
                MediaView view = show(file, hdr);
                assertTrue(name + " did not load", waitFor(() ->
                        view.getContentView() instanceof LargeImageView
                                && ((LargeImageView) view.getContentView()).getBase() != null));
                Thread.sleep(1_500);
                AtomicReference<String> decoded = new AtomicReference<>();
                AtomicBoolean holding = new AtomicBoolean();
                AtomicInteger mode = new AtomicInteger();
                onMain(() -> {
                    LargeImageView image = (LargeImageView) view.getContentView();
                    Bitmap base = image.getBase();
                    Bitmap tile = image.getAnyTile();
                    decoded.set("base " + describe(base) + " tile " + describe(tile));
                    holding.set(view.isHoldingHdr());
                    mode.set(activity().getWindow().getColorMode());
                    logWindow(label);
                });
                Log.i(TAG, label + ": holdsHdr=" + holding.get() + " colorMode=" + mode.get()
                        + " " + decoded.get());
                HdrDeviceEvidence.compositor(TAG, label);
                HdrDeviceEvidence.screenshot("still-" + label);
                if (hdr && required.contains(name)) {
                    assertTrue(name + " did not hold the window in HDR", holding.get());
                    assertEquals(ActivityInfo.COLOR_MODE_HDR, mode.get());
                } else if (!hdr) {
                    assertFalse(holding.get());
                    assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, mode.get());
                }
                remove(view);
            }
        }
    }

    private static String describe(Bitmap bitmap) {
        if (bitmap == null) {
            return "none";
        }
        return bitmap.getWidth() + "x" + bitmap.getHeight() + " " + bitmap.getConfig()
                + " " + bitmap.getColorSpace()
                + (Build.VERSION.SDK_INT >= 34 ? " gainmap=" + bitmap.hasGainmap() : "");
    }

    private void assumeHdrDisplay() {
        assumeTrue("this display cannot show HDR", isHdrDisplay());
    }

    private boolean isHdrDisplay() {
        AtomicBoolean hdr = new AtomicBoolean();
        onMain(() -> {
            Display display = activity().getWindow().getDecorView().getDisplay();
            hdr.set(display != null && display.isHdr());
        });
        return hdr.get();
    }

    /** Copies a pushed file into the cache, or returns null if it was not pushed. */
    private File pushed(String name) throws Exception {
        File file = new File(mDirectory, name);
        if (!HdrDeviceEvidence.copyFromShell(PUSHED + name, file)) {
            Log.w(TAG, name + " was not pushed to " + PUSHED);
            assumeTrue(name + " was not pushed", !name.endsWith(".mp4"));
            return null;
        }
        return file;
    }

    private MediaView show(File file) throws InterruptedException {
        return show(file, true);
    }

    private MediaView show(File file, boolean hdr) throws InterruptedException {
        AtomicReference<MediaView> created = new AtomicReference<>();
        onMain(() -> {
            MediaView view = new MediaView(activity());
            view.setHdrEnabled(hdr);
            activity().getRoot().addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            view.setFile(file);
            created.set(view);
        });
        return created.get();
    }

    private void remove(View view) {
        onMain(() -> activity().getRoot().removeView(view));
    }

    private void logWindow(String label) {
        Display display = activity().getWindow().getDecorView().getDisplay();
        Log.i(TAG, label + ": windowColorMode=" + activity().getWindow().getColorMode()
                + " desiredHdrHeadroom=" + (Build.VERSION.SDK_INT >= 35
                        ? activity().getWindow().getDesiredHdrHeadroom() : -1f)
                + " hdrSdrRatio=" + (Build.VERSION.SDK_INT >= 34 ? display.getHdrSdrRatio() : -1f));
    }

    /** The activity, from the main thread. */
    private ViewerTestActivity activity() {
        AtomicReference<ViewerTestActivity> activity = new AtomicReference<>();
        mScenario.onActivity(activity::set);
        return activity.get();
    }

    private void onMain(Runnable runnable) {
        mScenario.onActivity(activity -> runnable.run());
    }

    private interface Condition {
        boolean met();
    }

    private boolean waitFor(Condition condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            AtomicBoolean met = new AtomicBoolean();
            mScenario.onActivity(activity -> met.set(condition.met()));
            if (met.get()) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }
}
