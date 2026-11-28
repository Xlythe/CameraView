package com.xlythe.view.camera.viewer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.widget.FrameLayout;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MediaView through what an app's views go through: a file changed before the last one showed,
 * the view taken off the screen and put back, the activity paused and resumed, and the activity
 * made again as a rotation makes it. Each file is lopsided, so a picture turned or mirrored on
 * the way back fails.
 */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 23)
public class MediaLifecycleDeviceTest {
    /** How long anything may take to show or play. */
    private static final int SECONDS = 20;

    /** A full sphere whose middle faces east. */
    private static final String SPHERE_XMP = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
            + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
            + "<rdf:Description rdf:about=\"\""
            + " xmlns:GPano=\"http://ns.google.com/photos/1.0/panorama/\""
            + " GPano:ProjectionType=\"equirectangular\" GPano:PoseHeadingDegrees=\"90\""
            + " GPano:CroppedAreaImageWidthPixels=\"2048\""
            + " GPano:CroppedAreaImageHeightPixels=\"1024\""
            + " GPano:FullPanoWidthPixels=\"2048\" GPano:FullPanoHeightPixels=\"1024\""
            + " GPano:CroppedAreaLeftPixels=\"0\" GPano:CroppedAreaTopPixels=\"0\"/>"
            + "</rdf:RDF></x:xmpmeta>";

    @Rule
    public final ActivityScenarioRule<ViewerTestActivity> mActivity =
            new ActivityScenarioRule<>(ViewerTestActivity.class);

    private Instrumentation mInstrumentation;
    private Context mContext;
    private File mDirectory;

    /** The activity files are shown in. */
    private ActivityScenario<ViewerTestActivity> mScenario;

    /** What the last view reported, in order: a type, or -1 for a failure. */
    private final List<Integer> mReports = Collections.synchronizedList(new ArrayList<>());

    @Before
    public void setUp() {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mContext = mInstrumentation.getTargetContext();
        mScenario = mActivity.getScenario();
        mDirectory = new File(mContext.getCacheDir(), "media-lifecycle");
        assertTrue(mDirectory.isDirectory() || mDirectory.mkdirs());
    }

    /** A sphere of the compass's colours, whose metadata turns it to face east. */
    private File sphere() throws IOException {
        File file = new File(mDirectory, "sphere.jpg");
        MediaDeviceTest.writeJpeg(file, ViewerDeviceTest.compass(2048, 1024, 2048, 1024, 0, 0),
                SPHERE_XMP, new byte[0]);
        return file;
    }

    /** A picture red on its left, green top right and blue bottom right. */
    private File picture() throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[600 * 400];
        for (int y = 0; y < 400; y++) {
            for (int x = 0; x < 600; x++) {
                pixels[y * 600 + x] = x < 300 ? Color.RED : y < 200 ? Color.GREEN : Color.BLUE;
            }
        }
        bitmap.setPixels(pixels, 0, 600, 0, 0, 600, 400);
        File file = new File(mDirectory, "quarters.jpg");
        try (FileOutputStream out = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out));
        }
        return file;
    }

    private File video() throws IOException {
        File file = new File(mDirectory, "clip.mp4");
        if (!file.isFile()) {
            MediaDeviceTest.record(file, 320, 240, null);
        }
        return file;
    }

    /** Makes a MediaView, puts it on the screen, and gives it a file. */
    private MediaView show(File file) {
        AtomicReference<MediaView> made = new AtomicReference<>();
        mReports.clear();
        mScenario.onActivity(activity -> {
            MediaView view = new MediaView(activity);
            view.setMediaListener(new MediaListener() {
                @Override
                public void onMediaLoaded(int type) {
                    mReports.add(type);
                }

                @Override
                public void onMediaFailed() {
                    mReports.add(-1);
                }
            });
            activity.getRoot().removeAllViews();
            activity.getRoot().addView(view, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            view.setFile(file);
            made.set(view);
        });
        return made.get();
    }

    private void awaitReport() {
        assertTrue("never reported", waitUntil(() -> !mReports.isEmpty(), SECONDS));
    }

    @Test
    public void aFileChangedBeforeTheLastShowedReportsOnlyTheNewOne() throws Exception {
        File video = video();
        File picture = picture();
        MediaView view = show(video);
        mInstrumentation.runOnMainSync(() -> view.setFile(picture));
        awaitReport();
        SystemClock.sleep(3000);
        assertEquals(Collections.singletonList(MediaView.PICTURE), new ArrayList<>(mReports));
        assertTrue(make(view::getContentView) instanceof LargeImageView);

        mReports.clear();
        mInstrumentation.runOnMainSync(() -> {
            view.setFile(video);
            view.setFile(null);
        });
        SystemClock.sleep(3000);
        assertTrue("nothing to report for nothing", mReports.isEmpty());
        assertNull(make(view::getContentView));
    }

    @Test
    public void aVideoPlaysAgainOnceItsViewIsPutBack() throws Exception {
        MediaView view = show(video());
        awaitReport();
        assertEquals(MediaView.VIDEO, (int) mReports.get(0));
        assertTrue(play(view));
        mScenario.onActivity(activity -> activity.getRoot().removeView(view));
        assertFalse(onMain(view::isPlaying));
        mScenario.onActivity(activity -> activity.getRoot().addView(view));
        assertTrue("the video never played again", play(view));
    }

    @Test
    public void anAnimationAnimatesAgainOnceItsViewIsPutBack() throws Exception {
        File animation = new File(mDirectory, "ball.png");
        try (FileOutputStream out = new FileOutputStream(animation)) {
            copy("/data/local/tmp/media-corpus/stills/apng_bouncing_beach_ball.png", out);
        }
        assumeTrue("the corpus was not pushed", animation.length() > 0);
        MediaView view = show(animation);
        awaitReport();
        assertEquals(MediaView.ANIMATION, (int) mReports.get(0));
        assertTrue(onMain(view::isPlaying));
        mScenario.onActivity(activity -> activity.getRoot().removeView(view));
        assertFalse(onMain(view::isPlaying));
        mScenario.onActivity(activity -> activity.getRoot().addView(view));
        assertTrue("the animation never played again", waitUntil(() -> onMain(view::isPlaying),
                5));
        assertNotNull(make(view::getContentView));
    }

    /** A sphere turned by its pose looks the same way after the activity pauses and resumes. */
    @Test
    public void aTurnedSphereFacesTheSameWayAfterPauseAndResume() throws Exception {
        MediaView view = show(sphere());
        awaitReport();
        assertEquals(MediaView.PHOTOSPHERE, (int) mReports.get(0));
        PhotosphereView sphere = (PhotosphereView) make(view::getContentView);
        // Its middle, red, faces east; north is a quarter turn to its left, yellow.
        expect(sphere, 90, 'R');
        expect(sphere, 0, 'Y');
        expect(sphere, 180, 'G');

        mScenario.moveToState(Lifecycle.State.CREATED);
        mScenario.moveToState(Lifecycle.State.RESUMED);
        SystemClock.sleep(1000);
        expect(sphere, 90, 'R');
        expect(sphere, -90, 'B');
    }

    @Test
    public void aVideoPlaysAfterPauseAndResume() throws Exception {
        MediaView view = show(video());
        awaitReport();
        assertTrue(play(view));
        mScenario.moveToState(Lifecycle.State.CREATED);
        mScenario.moveToState(Lifecycle.State.RESUMED);
        assertTrue("the video never played after resuming", play(view));
    }

    /**
     * An activity made again, as a rotation makes it, leaves no HDR behind in the old window,
     * and the same file shows in the new one, the right way up.
     */
    @Test
    public void anActivityMadeAgainShowsTheFileAgain() throws Exception {
        File file = Build.VERSION.SDK_INT >= 34 ? new File(mDirectory, "hdr.jpg") : picture();
        if (Build.VERSION.SDK_INT >= 34) {
            MediaDeviceTest.writeUltraHdr(file, 1200, 800);
        }
        MediaView before = show(file);
        awaitReport();
        if (Build.VERSION.SDK_INT >= 34) {
            assertTrue(onMain(before::isHoldingHdr));
        }
        mScenario.recreate();
        if (Build.VERSION.SDK_INT >= 26) {
            int[] mode = new int[1];
            mScenario.onActivity(activity -> mode[0] = activity.getWindow().getColorMode());
            assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, mode[0]);
        }
        assertFalse(onMain(before::isHoldingHdr));
        MediaView after = show(picture());
        awaitReport();
        assertEquals(MediaView.PICTURE, (int) mReports.get(0));
        LargeImageView picture = (LargeImageView) make(after::getContentView);
        SystemClock.sleep(1000);
        Bitmap shown = Bitmap.createBitmap(picture.getWidth(),
                picture.getHeight(), Bitmap.Config.ARGB_8888);
        mInstrumentation.runOnMainSync(() -> picture.draw(new Canvas(shown)));
        Viewport viewport = picture.mViewport;
        float scale = viewport.getScale();
        assertEquals('R', ViewerDeviceTest.hue(shown.getPixel(
                Math.round(viewport.getX() + 150 * scale), Math.round(viewport.getY() + 200 * scale))));
        assertEquals('G', ViewerDeviceTest.hue(shown.getPixel(
                Math.round(viewport.getX() + 450 * scale), Math.round(viewport.getY() + 100 * scale))));
        assertEquals('B', ViewerDeviceTest.hue(shown.getPixel(
                Math.round(viewport.getX() + 450 * scale), Math.round(viewport.getY() + 300 * scale))));
    }

    // ---------- Helpers ----------

    private boolean play(MediaView view) {
        return waitUntil(() -> {
            if (!onMain(view::isPlaying)) {
                mInstrumentation.runOnMainSync(view::play);
            }
            SystemClock.sleep(200);
            return onMain(view::isPlaying);
        }, SECONDS);
    }

    private void expect(PhotosphereView view, double heading, char hue) {
        mInstrumentation.runOnMainSync(() -> view.lookAt(heading, 0));
        SystemClock.sleep(500);
        Bitmap screen = mInstrumentation.getUiAutomation().takeScreenshot();
        int[] location = new int[2];
        mInstrumentation.runOnMainSync(() -> view.getLocationOnScreen(location));
        int color = screen.getPixel(location[0] + view.getWidth() / 2,
                location[1] + view.getHeight() / 2);
        assertEquals("Looking at " + heading, hue, ViewerDeviceTest.hue(color));
    }

    private void copy(String path, FileOutputStream out) throws IOException {
        ParcelFileDescriptor output = mInstrumentation.getUiAutomation()
                .executeShellCommand("cat " + path);
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(output)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
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

    /** Something true or false. */
    private interface Check {
        boolean get();
    }

    /** Something made on the main thread. */
    private interface Make<T> {
        T get();
    }
}
