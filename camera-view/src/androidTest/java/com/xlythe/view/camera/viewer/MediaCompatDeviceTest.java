package com.xlythe.view.camera.viewer;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;


import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Opens every file of the media corpus in MediaView on a device, and checks each is found to be
 * what it is, shows or fails cleanly rather than crashing or hanging, stands the right way up,
 * sits on the sphere where its metadata says, plays its motion clip, and holds HDR where it
 * should. What decodes and plays on this release is written out as one line per file, for the
 * compatibility matrix.
 *
 * <p>The corpus is pushed to /data/local/tmp/media-corpus, with an expected.tsv beside it; without
 * it the test skips. Before Android 6.0, whose shell commands never say they have finished, it is
 * pushed to media-corpus under the test's external files instead. Pass {@code -e dir <folder>} to check one folder of it; files not pushed are
 * passed over.</p>
 */
@RunWith(AndroidJUnit4.class)
public class MediaCompatDeviceTest {
    private static final String TAG = "MediaCompat";

    /** Where the corpus is pushed, which the shell can read and the test cannot. */
    private static final String CORPUS = "/data/local/tmp/media-corpus/";

    /** How long a file may take to show or fail before it counts as a hang. */
    private static final int LOAD_SECONDS = 45;

    /** How long something may take to start playing. */
    private static final int PLAY_SECONDS = 10;

    /** What the loaded callback records for a file that failed. */
    private static final int FAILED = -1;

    /** What is recorded for a file that neither showed nor failed in time. */
    private static final int HUNG = -2;

    @Rule
    public final ActivityScenarioRule<ViewerTestActivity> mActivity =
            new ActivityScenarioRule<>(ViewerTestActivity.class);

    private Instrumentation mInstrumentation;
    private Context mContext;
    private File mDirectory;

    /** The corpus where the test can read it itself, before Android 6.0, or null. */
    @Nullable
    private File mLocalCorpus;

    @Before
    public void setUp() {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mContext = mInstrumentation.getTargetContext();
        mDirectory = new File(mContext.getCacheDir(), "media-compat");
        assertTrue(mDirectory.isDirectory() || mDirectory.mkdirs());
        File local = new File(mContext.getExternalFilesDir(null), "media-corpus");
        mLocalCorpus = local.isDirectory() ? local : null;
    }

    @Test
    public void everyCorpusFileShowsOrFailsCleanly() throws Exception {
        byte[] expected = mLocalCorpus != null
                ? readAll(new File(mLocalCorpus, "expected.tsv"))
                : shell("cat " + CORPUS + "expected.tsv");
        assumeTrue("nothing pushed to " + CORPUS, expected.length > 0);
        Bundle arguments = InstrumentationRegistry.getArguments();
        String only = arguments.getString("dir");
        File results = new File(mContext.getExternalFilesDir(null),
                "media-compat-api" + Build.VERSION.SDK_INT + (only == null ? "" : "-" + only)
                        + ".tsv");
        List<String> problems = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(expected), StandardCharsets.UTF_8));
                PrintWriter out = new PrintWriter(new FileOutputStream(results), true)) {
            out.println("path\texpected\tdetected\tshown\tmillis\tplays\thdr\tproblems");
            String line;
            while ((line = reader.readLine()) != null) {
                String[] cells = line.split("\t");
                if (cells.length < 9 || (only != null && !cells[0].startsWith(only + "/"))) {
                    continue;
                }
                String row = check(cells, problems);
                if (row == null) {
                    continue;
                }
                out.println(row);
                Log.i(TAG, "API " + Build.VERSION.SDK_INT + "\t" + row);
            }
        }
        assertTrue(problems.size() + " problems:\n" + TextUtils.join("\n", problems),
                problems.isEmpty());
    }

    /**
     * Checks one file, adding what is wrong with it to the problems, and returns its line, or
     * null if it was not pushed: a device short of space takes the corpus a part at a time.
     */
    @Nullable
    private String check(String[] cells, List<String> problems) throws Exception {
        String path = cells[0];
        // Animations play from Android 9, and AVIF decodes at all from Android 12.
        int animatesFrom = "avif".equals(cells[8]) ? 31 : 28;
        int want = typeOf(Build.VERSION.SDK_INT >= animatesFrom ? cells[1] : cells[2]);
        File file = new File(mDirectory, path.replace('/', '_'));
        if (mLocalCorpus != null) {
            File local = new File(mLocalCorpus, path);
            if (!local.isFile()) {
                return null;
            }
            file = local;
        } else {
            try (OutputStream out = new FileOutputStream(file)) {
                copy(CORPUS + path, out);
            }
        }
        if (file.length() == 0) {
            assertTrue(file.delete());
            return null;
        }
        List<String> wrong = new ArrayList<>();
        int detected;
        try {
            detected = MediaDetector.detect(mContext, ImageLocation.of(file)).type;
        } catch (IOException e) {
            detected = FAILED;
        }
        if (detected != want) {
            wrong.add("detected " + nameOf(detected) + ", expected " + nameOf(want));
        }

        long start = SystemClock.elapsedRealtime();
        AtomicReference<MediaView> made = new AtomicReference<>();
        int shown = show(file, made);
        long millis = SystemClock.elapsedRealtime() - start;
        MediaView view = made.get();
        String plays = "-";
        boolean hdr = false;
        if (shown == HUNG) {
            wrong.add("neither showed nor failed in " + LOAD_SECONDS + " s");
        } else if (shown != FAILED) {
            hdr = onMain(view::isHoldingHdr);
            plays = checkShown(view, shown, cells, file, wrong);
        }
        boolean wantHdr = ("yes".equals(cells[6]) && Build.VERSION.SDK_INT >= 34)
                || ("yes".equals(cells[7]) && Build.VERSION.SDK_INT >= 26);
        if (shown != FAILED && shown != HUNG && wantHdr && !hdr) {
            wrong.add("HDR not held");
        }
        mActivity.getScenario().onActivity(activity -> activity.getRoot().removeAllViews());
        if (mLocalCorpus == null && !file.delete()) {
            Log.w(TAG, "Unable to delete " + file);
        }
        if (!wrong.isEmpty()) {
            problems.add(path + ": " + TextUtils.join("; ", wrong));
        }
        return TextUtils.join("\t", new Object[] {path, nameOf(want), nameOf(detected),
                shown == HUNG ? "HUNG" : shown == FAILED ? "FAILED" : "SHOWN", millis, plays,
                hdr ? "HDR" : "-", TextUtils.join("; ", wrong)});
    }

    /** Checks what showed: its orientation, place on the sphere, and that it plays. */
    private String checkShown(MediaView view, int shown, String[] cells, File file,
            List<String> wrong) {
        View content = make(view::getContentView);
        switch (shown) {
            case MediaView.PICTURE:
            case MediaView.PANORAMA:
                checkOrientation((LargeImageView) content, cells, file, wrong);
                return "-";
            case MediaView.PHOTOSPHERE:
                checkSphere((PhotosphereView) content, cells[4], wrong);
                return "-";
            case MediaView.MOTION_PHOTO: {
                // Watched from the main thread itself, since a clip of a few frames can start
                // and finish between two looks from here.
                AtomicBoolean seen = new AtomicBoolean();
                AtomicBoolean watching = new AtomicBoolean(true);
                Runnable[] watch = new Runnable[1];
                watch[0] = () -> {
                    if (view.isPlaying()) {
                        seen.set(true);
                    } else if (watching.get()) {
                        view.postDelayed(watch[0], 2);
                    }
                };
                mInstrumentation.runOnMainSync(() -> {
                    view.performLongClick();
                    view.post(watch[0]);
                });
                boolean clip = waitUntil(seen::get, PLAY_SECONDS);
                watching.set(false);
                mInstrumentation.runOnMainSync(() -> {
                    long now = SystemClock.uptimeMillis();
                    MotionEvent up = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, 1, 1, 0);
                    view.dispatchTouchEvent(up);
                    up.recycle();
                });
                if (!clip) {
                    wrong.add("motion clip did not play");
                }
                return clip ? "clip" : "no clip";
            }
            case MediaView.ANIMATION: {
                boolean animates = onMain(((AnimationView) content)::isAnimated);
                boolean playing = onMain(view::isPlaying);
                return animates && playing ? "animates" : "still";
            }
            default: {
                boolean played = waitUntil(() -> {
                    if (!onMain(view::isPlaying)) {
                        mInstrumentation.runOnMainSync(view::play);
                    }
                    SystemClock.sleep(200);
                    return onMain(view::isPlaying);
                }, PLAY_SECONDS);
                mInstrumentation.runOnMainSync(view::pause);
                return played ? "plays" : "no";
            }
        }
    }

    /**
     * A picture with an Exif orientation shows its stored size turned by it: wider than tall, or
     * taller than wide, as the stored picture turned is.
     */
    private void checkOrientation(LargeImageView view, String[] cells, File file,
            List<String> wrong) {
        String orientation = cells[3];
        String format = cells[8];
        if ("-".equals(orientation) || !(format.equals("jpg") || format.equals("png")
                || format.equals("webp"))) {
            return;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight == bounds.outWidth) {
            return;
        }
        int exif = Integer.parseInt(orientation);
        boolean turned = exif >= 5 && exif <= 8;
        boolean storedWide = bounds.outWidth > bounds.outHeight;
        float[] shown = new float[2];
        mInstrumentation.runOnMainSync(() -> {
            shown[0] = view.mViewport.scaledWidth();
            shown[1] = view.mViewport.scaledHeight();
        });
        boolean shownWide = shown[0] > shown[1];
        if (shownWide != (storedWide != turned)) {
            wrong.add("orientation " + exif + " shown " + (shownWide ? "wide" : "tall"));
        }
    }

    /** A sphere sits where its metadata says, and faces the way it says. */
    private void checkSphere(PhotosphereView view, String expected, List<String> wrong) {
        if ("-".equals(expected)) {
            return;
        }
        String[] parts = expected.split(",");
        GPano gpano = make(view::getGPano);
        if (gpano == null) {
            wrong.add("no sphere placement");
            return;
        }
        double[] want = {Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                Double.parseDouble(parts[2]), Double.parseDouble(parts[3])};
        double[] got = {gpano.left, gpano.top, gpano.width, gpano.height};
        for (int i = 0; i < 4; i++) {
            if (Math.abs(want[i] - got[i]) > 0.01) {
                wrong.add(String.format(Locale.US, "crop %.4f,%.4f,%.4f,%.4f, expected %s",
                        got[0], got[1], got[2], got[3], expected));
                break;
            }
        }
        double pose = Double.parseDouble(parts[4]);
        if (!Double.isNaN(pose) && Math.abs(pose - gpano.poseHeadingDegrees) > 0.5) {
            wrong.add("pose heading " + gpano.poseHeadingDegrees + ", expected " + pose);
        }
    }

    /**
     * Shows a file in a new MediaView, full screen, and waits until it shows or fails.
     *
     * @return the type it showed as, {@link #FAILED}, or {@link #HUNG}
     */
    private int show(File file, AtomicReference<MediaView> made) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        int[] type = {FAILED};
        MediaListener listener = new MediaListener() {
            @Override
            public void onMediaLoaded(int loaded) {
                type[0] = loaded;
                done.countDown();
            }

            @Override
            public void onMediaFailed() {
                done.countDown();
            }
        };
        mActivity.getScenario().onActivity(activity -> {
            MediaView view = new MediaView(activity);
            made.set(view);
            view.setMediaListener(listener);
            activity.getRoot().removeAllViews();
            activity.getRoot().addView(view, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            view.setFile(file);
        });
        if (!done.await(LOAD_SECONDS, TimeUnit.SECONDS)) {
            return HUNG;
        }
        if (type[0] != FAILED) {
            // The first tiles and frames land just after the report.
            SystemClock.sleep(500);
        }
        return type[0];
    }

    // ---------- Helpers ----------

    /** Copies a file the shell can read into a stream. */
    private void copy(String path, OutputStream out) throws IOException {
        ParcelFileDescriptor output = mInstrumentation.getUiAutomation()
                .executeShellCommand("cat " + path);
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(output)) {
            byte[] buffer = new byte[256 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }

    private static byte[] readAll(File file) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            return new byte[0];
        }
        return out.toByteArray();
    }

    /** What a command run as the shell user prints. */
    private byte[] shell(String command) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ParcelFileDescriptor output = mInstrumentation.getUiAutomation()
                .executeShellCommand(command);
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(output)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        return out.toByteArray();
    }

    private static int typeOf(String name) {
        switch (name) {
            case "PICTURE":
                return MediaView.PICTURE;
            case "PANORAMA":
                return MediaView.PANORAMA;
            case "PHOTOSPHERE":
                return MediaView.PHOTOSPHERE;
            case "VIDEO":
                return MediaView.VIDEO;
            case "MOTION_PHOTO":
                return MediaView.MOTION_PHOTO;
            case "SPHERICAL_VIDEO":
                return MediaView.SPHERICAL_VIDEO;
            case "AUDIO":
                return MediaView.AUDIO;
            case "ANIMATION":
                return MediaView.ANIMATION;
            default:
                return FAILED;
        }
    }

    private static String nameOf(int type) {
        switch (type) {
            case MediaView.PICTURE:
                return "PICTURE";
            case MediaView.PANORAMA:
                return "PANORAMA";
            case MediaView.PHOTOSPHERE:
                return "PHOTOSPHERE";
            case MediaView.VIDEO:
                return "VIDEO";
            case MediaView.MOTION_PHOTO:
                return "MOTION_PHOTO";
            case MediaView.SPHERICAL_VIDEO:
                return "SPHERICAL_VIDEO";
            case MediaView.AUDIO:
                return "AUDIO";
            case MediaView.ANIMATION:
                return "ANIMATION";
            default:
                return "FAILED";
        }
    }

    private boolean onMain(Check check) {
        AtomicReference<Boolean> result = new AtomicReference<>();
        mInstrumentation.runOnMainSync(() -> result.set(check.get()));
        return result.get();
    }

    private <T> T make(Make<T> make) {
        AtomicReference<T> result = new AtomicReference<>();
        mInstrumentation.runOnMainSync(() -> result.set(make.get()));
        return result.get();
    }

    private static boolean waitUntil(Check condition, int seconds) {
        long deadline = SystemClock.elapsedRealtime() + seconds * 1000L;
        while (!condition.get()) {
            if (SystemClock.elapsedRealtime() > deadline) {
                return false;
            }
            SystemClock.sleep(100);
        }
        return true;
    }

    /** Something true or false, here rather than java.util.function, which API 22 lacks. */
    private interface Check {
        boolean get();
    }

    /** Something made on the main thread. */
    private interface Make<T> {
        T get();
    }
}
