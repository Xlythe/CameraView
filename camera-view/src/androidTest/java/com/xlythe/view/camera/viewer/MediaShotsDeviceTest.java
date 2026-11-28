package com.xlythe.view.camera.viewer;

import static org.junit.Assume.assumeTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.widget.FrameLayout;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Screenshots of MediaView showing one file of each kind, for a person to look at: pictures,
 * panoramas, spheres, videos, motion photos, 360° video, sound, animations, HDR and camera raws.
 * Each is saved under the test's external files, in media-shots, and a line naming what showed
 * is logged under MediaShots. It asserts nothing; the other device tests do.
 *
 * <p>Reads the corpus pushed to /data/local/tmp/media-corpus, and skips without it.</p>
 */
@RunWith(AndroidJUnit4.class)
public class MediaShotsDeviceTest {
    private static final String TAG = "MediaShots";

    /** Where the corpus is pushed. */
    private static final String CORPUS = "/data/local/tmp/media-corpus/";

    /** Shoot it as it first shows. */
    private static final int STILL = 0;

    /** Play it first. */
    private static final int PLAY = 1;

    /** Hold it first, as a motion photo is held to play its clip. */
    private static final int HOLD = 2;

    /** Shoot it a moment after it first shows, so an animation has moved on. */
    private static final int LATER = 3;

    /** Show it with HDR turned off. */
    private static final int NO_HDR = 4;

    /** Each shot: its name, the corpus file, and what to do. */
    private static final Object[][] SHOTS = {
            {"picture-heic-exif6-irot270", "samsung/immich_samsung-one-ui-6.heic", STILL},
            {"picture-dng-orientation8", "raw/skia_sample_1mp_rotated.dng", STILL},
            {"panorama", "pixel/pixel6pro_panorama_2022.jpg", STILL},
            {"photosphere", "pixel/pixel7_photosphere_2025.jpg", STILL},
            {"video", "video/androidx_sample.mp4", PLAY},
            {"motion-photo-still", "pixel/pixel4xl_motionphoto_2024.jpg", STILL},
            {"motion-photo-held", "pixel/pixel4xl_motionphoto_2024.jpg", HOLD},
            {"spherical-video-mp4", "video/chromium_spherical.mp4", PLAY},
            {"spherical-video-webm", "video/chromium_bear-spherical-metadata.webm", PLAY},
            {"audio-cover", "audio/androidx_bear-id3.mp3", PLAY},
            {"animation-gif-first", "stills/gif_animated_rotating_earth.gif", STILL},
            {"animation-gif-later", "stills/gif_animated_rotating_earth.gif", LATER},
            {"animation-apng-first", "stills/apng_bouncing_beach_ball.png", STILL},
            {"animation-apng-later", "stills/apng_bouncing_beach_ball.png", LATER},
            {"animation-webp-first", "stills/webp_animated_clock.webp", STILL},
            {"animation-webp-later", "stills/webp_animated_clock.webp", LATER},
            {"hdr-on", "pixel/androidx_ultraHDR.jpg", STILL},
            {"hdr-off", "pixel/androidx_ultraHDR.jpg", NO_HDR},
            {"quicktime-without-ftyp", "apple/mxi_apple-livephoto-quicktime.mov", PLAY},
            {"raw-cr2", "raw/pixls_canon_eos_350d.cr2", STILL},
            {"raw-cr3", "raw/pixls_canon_eos_r100.cr3", STILL},
            {"raw-nef", "raw/pixls_nikon_d40.nef", STILL},
            {"raw-arw", "raw/pixls_sony_dslr_a230.arw", STILL},
            {"raw-raf", "raw/pixls_fujifilm_f550exr.raf", STILL},
            {"raw-orf", "raw/pixls_olympus_e-pl1.orf", STILL},
            {"raw-rw2", "raw/pixls_panasonic_dmc-gf1_16-9.rw2", STILL},
    };

    @Rule
    public final ActivityScenarioRule<ViewerTestActivity> mActivity =
            new ActivityScenarioRule<>(ViewerTestActivity.class);

    @Test
    public void shootEveryKind() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        File directory = new File(context.getCacheDir(), "media-shots");
        File shots = new File(context.getExternalFilesDir(null), "media-shots");
        if (!directory.isDirectory() && !directory.mkdirs()
                || !shots.isDirectory() && !shots.mkdirs()) {
            throw new IOException("Unable to make the shot directories");
        }
        File probe = copy(instrumentation, SHOTS[0][1].toString(), directory);
        assumeTrue("the corpus was not pushed", probe.length() > 0);
        for (Object[] shot : SHOTS) {
            String name = (String) shot[0];
            int action = (Integer) shot[2];
            File file = copy(instrumentation, (String) shot[1], directory);
            CountDownLatch done = new CountDownLatch(1);
            int[] type = {-1};
            AtomicReference<MediaView> made = new AtomicReference<>();
            mActivity.getScenario().onActivity(activity -> {
                MediaView view = new MediaView(activity);
                view.setHdrEnabled(action != NO_HDR);
                view.setMediaListener(new MediaListener() {
                    @Override
                    public void onMediaLoaded(int loaded) {
                        type[0] = loaded;
                        done.countDown();
                    }

                    @Override
                    public void onMediaFailed() {
                        done.countDown();
                    }
                });
                activity.getRoot().removeAllViews();
                activity.getRoot().addView(view, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
                view.setFile(file);
                made.set(view);
            });
            boolean reported = done.await(45, TimeUnit.SECONDS);
            MediaView view = made.get();
            SystemClock.sleep(1500);
            if (action == PLAY) {
                for (int i = 0; i < 50 && !isPlaying(instrumentation, view); i++) {
                    instrumentation.runOnMainSync(view::play);
                    SystemClock.sleep(200);
                }
                SystemClock.sleep(1500);
            } else if (action == HOLD) {
                instrumentation.runOnMainSync(view::performLongClick);
                SystemClock.sleep(700);
            } else if (action == LATER) {
                SystemClock.sleep(700);
            }
            int[] mode = new int[1];
            if (Build.VERSION.SDK_INT >= 26) {
                mActivity.getScenario().onActivity(activity ->
                        mode[0] = activity.getWindow().getColorMode());
            }
            boolean playing = isPlaying(instrumentation, view);
            save(screenshot(instrumentation, view), new File(shots,
                    name + "-api" + Build.VERSION.SDK_INT + ".png"));
            Log.i(TAG, "SHOT\t" + name + "\t" + shot[1] + "\t" + (reported ? type[0] : "hung")
                    + "\tplaying=" + playing + "\tcolorMode=" + mode[0]);
            if (action == HOLD) {
                instrumentation.runOnMainSync(() -> {
                    long now = SystemClock.uptimeMillis();
                    MotionEvent up = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, 1, 1, 0);
                    view.dispatchTouchEvent(up);
                    up.recycle();
                });
            }
            mActivity.getScenario().onActivity(activity -> activity.getRoot().removeAllViews());
        }
    }

    private static boolean isPlaying(Instrumentation instrumentation, MediaView view) {
        boolean[] playing = new boolean[1];
        instrumentation.runOnMainSync(() -> playing[0] = view.isPlaying());
        return playing[0];
    }

    private static Bitmap screenshot(Instrumentation instrumentation, MediaView view) {
        Bitmap screen = instrumentation.getUiAutomation().takeScreenshot();
        int[] location = new int[2];
        instrumentation.runOnMainSync(() -> view.getLocationOnScreen(location));
        return Bitmap.createBitmap(screen, location[0], location[1],
                Math.min(view.getWidth(), screen.getWidth() - location[0]),
                Math.min(view.getHeight(), screen.getHeight() - location[1]));
    }

    private static void save(Bitmap bitmap, File file) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    /** Copies a corpus file the shell can read to where the test can. */
    private static File copy(Instrumentation instrumentation, String path, File directory)
            throws IOException {
        File file = new File(directory, path.substring(path.lastIndexOf('/') + 1));
        if (file.isFile() && file.length() > 0) {
            return file;
        }
        ParcelFileDescriptor output = instrumentation.getUiAutomation()
                .executeShellCommand("cat " + CORPUS + path);
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(output);
                FileOutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[256 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        return file;
    }
}
