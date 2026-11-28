package com.xlythe.view.camera.viewer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Gainmap;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.exifinterface.media.ExifInterface;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.MediaTags;
import com.xlythe.view.camera.test.CameraTestActivity;
import com.xlythe.view.camera.test.DeviceState;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MediaView on a real device, with real decoders, a real player and a real GPU: each kind of
 * file is found to be what it is and shown by the viewer that suits it.
 *
 * <p>Files pushed to /data/local/tmp/media-device, with an expected.txt that names each and
 * what it shows as, are checked too; without them that test skips. Each test saves what it saw
 * under the test's external files, in media-proof.</p>
 */
@RunWith(AndroidJUnit4.class)
public class MediaDeviceTest {
    private static final String TAG = "MediaDevice";

    /** Long enough for a camera, a codec or a decoder on a slow emulator. */
    private static final int TIMEOUT_SECONDS = 60;

    /** What the loaded callback records for a file that failed. */
    private static final int FAILED = -1;

    /** Where files to check are pushed, which the shell can read and the test cannot. */
    private static final String PUSHED = "/data/local/tmp/media-device/";

    /** The uuid of the box version 1 of the spherical video metadata goes in. */
    private static final byte[] SPHERICAL_UUID = {(byte) 0xFF, (byte) 0xCC, (byte) 0x82, 0x63,
            (byte) 0xF8, 0x55, 0x4A, (byte) 0x93, (byte) 0x88, 0x14, 0x58, 0x7A, 0x02, 0x52,
            0x1F, (byte) 0xDD};

    @Rule
    public final GrantPermissionRule mPermissions = GrantPermissionRule.grant(
            Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO);

    @Rule
    public final ActivityScenarioRule<ViewerTestActivity> mActivity =
            new ActivityScenarioRule<>(ViewerTestActivity.class);

    /** Skips, rather than fails, the capture the emulator's camera HAL stalled under. */
    @Rule
    public final TestRule mCameraHal = DeviceState.skipWhenTheEmulatorCameraHalStalls();

    private Instrumentation mInstrumentation;
    private Context mContext;
    private File mDirectory;

    /** The activity files are shown in. */
    private ActivityScenario<ViewerTestActivity> mScenario;

    /** The view the last file was shown in. */
    private MediaView mView;

    @Before
    public void setUp() {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mContext = mInstrumentation.getTargetContext();
        mScenario = mActivity.getScenario();
        mDirectory = new File(mContext.getCacheDir(), "media-test");
        assertTrue(mDirectory.isDirectory() || mDirectory.mkdirs());
        File copies = new File(mContext.getCacheDir(), ImageSources.CACHE_DIRECTORY);
        File[] old = copies.listFiles();
        if (old != null) {
            for (File file : old) {
                assertTrue(file.delete());
            }
        }
    }

    /** A picture and a recording this library captured show as a picture and a video. */
    @Test
    public void ourCapturesShowAsWhatTheyAre() throws Exception {
        assumeTrue("no camera on this device", mContext.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY));
        File picture = new File(mDirectory, "capture.jpg");
        File video = new File(mDirectory, "capture.mp4");
        assertTrue(!picture.exists() || picture.delete());
        assertTrue(!video.exists() || video.delete());
        capture(picture, video);
        // The camera's activity finished the one files are shown in, so they get a new one.
        mScenario = ActivityScenario.launch(ViewerTestActivity.class);
        try {
            showCaptures(picture, video);
        } finally {
            mScenario.close();
        }
    }

    private void showCaptures(File picture, File video) throws Exception {
        assertTrue("the capture failed", picture.length() > 0);
        MediaTags tags = MediaTags.open(picture);
        Log.i(TAG, "Captured on API " + Build.VERSION.SDK_INT + ": " + tags);
        assertNotEquals(-1, tags.getCaptureTime());
        assertEquals(MediaView.PICTURE, show(picture, "capture-picture"));
        if (video.length() > 0) {
            Log.i(TAG, "Recorded on API " + Build.VERSION.SDK_INT + ": " + MediaTags.open(video));
            assertEquals(MediaView.VIDEO, show(video, "capture-video"));
            assertTrue("the recording never played", play());
        } else {
            Log.w(TAG, "This device's encoder produced no recording");
        }
    }

    /**
     * The whole pipeline through the gallery: the camera files a picture in MediaStore, MediaTags
     * writes its title through the Uri the app owns, and MediaView shows it from that Uri.
     */
    @Test
    @SdkSuppress(minSdkVersion = 29)
    public void aPictureFiledInTheGalleryIsWrittenThroughItsUri() throws Exception {
        assumeTrue("no camera on this device", mContext.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY));
        AtomicReference<Uri> filed = new AtomicReference<>();
        try (ActivityScenario<CameraTestActivity> scenario =
                     ActivityScenario.launch(CameraTestActivity.class)) {
            CountDownLatch opened = new CountDownLatch(1);
            CountDownLatch saved = new CountDownLatch(1);
            AtomicReference<CameraView> held = new AtomicReference<>();
            scenario.onActivity(activity -> {
                CameraView camera = activity.getCameraView();
                held.set(camera);
                camera.setOnCameraStateChangedListener(
                        new CameraView.OnCameraStateChangedListener() {
                            @Override
                            public void onCameraOpened() {
                                opened.countDown();
                            }

                            @Override
                            public void onCameraClosed() {}
                        });
                if (camera.isOpen()) {
                    opened.countDown();
                } else {
                    camera.open();
                }
            });
            assertTrue("the camera never opened",
                    opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            CameraView camera = held.get();
            camera.setFlash(CameraView.Flash.OFF);
            try {
                camera.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
                    @Override
                    public void onImageConfirmation() {}

                    @Override
                    public void onImageSaved(@NonNull Uri uri) {
                        filed.set(uri);
                        saved.countDown();
                    }

                    @Override
                    public void onFailure() {
                        saved.countDown();
                    }
                });
                camera.takePicture();
                assertTrue("the picture was never filed",
                        saved.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            } finally {
                scenario.onActivity(activity -> activity.getCameraView().close());
            }
        }
        Uri uri = filed.get();
        assertNotNull("the capture failed", uri);
        try {
            MediaTags tags = MediaTags.open(mContext, uri);
            assertFalse("the app filed it, so it may write it", tags.isReadOnly());
            assertTrue(tags.setTitle("Filed and titled"));
            tags.save();
            assertEquals("Filed and titled", MediaTags.open(mContext, uri).getTitle());

            mScenario = ActivityScenario.launch(ViewerTestActivity.class);
            try {
                assertEquals(MediaView.PICTURE, showUri(uri, "gallery-picture"));
            } finally {
                mScenario.close();
            }
        } finally {
            mContext.getContentResolver().delete(uri, null, null);
        }
    }

    /** A DNG is a picture, turned by the orientation in its first IFD. */
    @Test
    public void aDngIsAPictureTurnedByItsOwnOrientation() throws Exception {
        File dng = new File(mDirectory, "turned.dng");
        SyntheticDng.write(dng, 1200, 800, ExifInterface.ORIENTATION_ROTATE_90);
        assertEquals(90, MediaTags.open(dng).getRotation());

        int type = show(dng, "dng");
        if (Build.VERSION.SDK_INT < 24) {
            assertEquals("nothing before Android 7.0 decodes a DNG", FAILED, type);
            return;
        }
        assertEquals(MediaView.PICTURE, type);
        LargeImageView picture = (LargeImageView) make(mView::getContentView);
        assertTrue("shown upright, it stands taller than it is wide", onMain(() ->
                picture.mViewport.scaledHeight() > picture.mViewport.scaledWidth()));
        // Upright, the stored left half, red, is the top; the stored top right, green, is the
        // bottom right, and the stored bottom right, blue, the bottom left. A mirror swaps the
        // last two.
        Bitmap shown = draw(picture);
        assertEquals('R', ViewerDeviceTest.hue(at(picture, shown, 400, 300)));
        assertEquals('G', ViewerDeviceTest.hue(at(picture, shown, 600, 900)));
        assertEquals('B', ViewerDeviceTest.hue(at(picture, shown, 200, 900)));
    }

    /**
     * A real HEIC turned and mirrored through its own transforms is shown turned and mirrored
     * exactly once: what shows matches the upright picture turned a quarter, or mirrored, and no
     * other way.
     */
    @Test
    @SdkSuppress(minSdkVersion = 28)
    public void aHeicIsTurnedByItsOwnTransformsExactlyOnce() throws Exception {
        File heic = new File(mDirectory, "turned.heic");
        try (FileOutputStream out = new FileOutputStream(heic)) {
            out.write(shell(
                    "cat /data/local/tmp/media-corpus/stills/androidx_sample_still_photo.heic"));
        }
        assumeTrue("the corpus was not pushed", heic.length() > 0);
        float[][] upright = grid(heic, 0, false);
        float[][] turned = grid(heic, 90, false);
        float[][] mirrored = grid(heic, 0, true);
        Log.i(TAG, "HEIC turned matches transform " + closest(upright, turned)
                + ", mirrored matches " + closest(upright, mirrored));
        assertEquals("turned a quarter clockwise", 1, closest(upright, turned));
        assertEquals("mirrored left to right", 4, closest(upright, mirrored));
    }

    /** The picture as shown with an orientation written into it, as a grid of brightness. */
    private float[][] grid(File file, int rotation, boolean mirrored) throws Exception {
        MediaTags tags = MediaTags.open(file);
        assertTrue(tags.setOrientation(rotation, mirrored));
        tags.save();
        assertEquals(MediaView.PICTURE, show(file, "heic-" + rotation + (mirrored ? "-m" : "")));
        LargeImageView picture = (LargeImageView) make(mView::getContentView);
        Bitmap shown = draw(picture);
        Viewport viewport = picture.mViewport;
        int left = Math.max(0, Math.round(viewport.getX()));
        int top = Math.max(0, Math.round(viewport.getY()));
        int width = Math.min(shown.getWidth() - left, Math.round(viewport.scaledWidth()));
        int height = Math.min(shown.getHeight() - top, Math.round(viewport.scaledHeight()));
        Bitmap small = Bitmap.createScaledBitmap(Bitmap.createBitmap(shown, left, top, width,
                height), 16, 16, true);
        float[][] grid = new float[16][16];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int c = small.getPixel(x, y);
                grid[y][x] = Color.red(c) * 0.3f + Color.green(c) * 0.59f + Color.blue(c) * 0.11f;
            }
        }
        return grid;
    }

    /**
     * Which of the eight ways to turn and mirror a grid makes it closest to another: 0 as it is,
     * 1 to 3 turned that many quarters clockwise, 4 mirrored, 5 to 7 mirrored then turned.
     */
    private static int closest(float[][] from, float[][] to) {
        int best = -1;
        double bestDifference = Double.MAX_VALUE;
        for (int way = 0; way < 8; way++) {
            double difference = 0;
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int sx = x;
                    int sy = y;
                    for (int turn = 0; turn < way % 4; turn++) {
                        int t = sx;
                        sx = sy;
                        sy = 15 - t;
                    }
                    if (way >= 4) {
                        sx = 15 - sx;
                    }
                    difference += Math.abs(from[sy][sx] - to[y][x]);
                }
            }
            if (difference < bestDifference) {
                bestDifference = difference;
                best = way;
            }
        }
        return best;
    }

    /** What a view draws, drawn in software. */
    private Bitmap draw(View view) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(),
                Bitmap.Config.ARGB_8888);
        mInstrumentation.runOnMainSync(() -> view.draw(new Canvas(bitmap)));
        return bitmap;
    }

    /** An upright picture's pixel in what a viewer drew. */
    private static int at(LargeImageView view, Bitmap shown, int x, int y) {
        Viewport viewport = view.mViewport;
        float scale = viewport.getScale();
        return shown.getPixel(Math.round(viewport.getX() + x * scale),
                Math.round(viewport.getY() + y * scale));
    }

    /**
     * An Ultra HDR picture puts the window in HDR, and its gain map survives into what the
     * viewer draws: the low-resolution copy and each tile cut from it at full detail.
     */
    @Test
    @SdkSuppress(minSdkVersion = 34)
    public void anUltraHdrPictureShowsInHdr() throws Exception {
        File jpeg = new File(mDirectory, "ultrahdr.jpg");
        writeUltraHdr(jpeg, 4000, 3000);
        MediaTags tags = MediaTags.open(jpeg);
        assertNotEquals(MediaTags.GAIN_MAP_NONE, tags.getGainMap());
        boolean whole = decodeKeepsGainmap(jpeg, false);
        boolean region = decodeKeepsGainmap(jpeg, true);
        int before = colorMode();

        assertEquals(MediaView.PICTURE, show(jpeg, "ultrahdr"));
        MediaView view = mView;
        assertTrue(onMain(view::isHoldingHdr));
        assertEquals(ActivityInfo.COLOR_MODE_HDR, colorMode());
        LargeImageView picture = (LargeImageView) make(view::getContentView);
        mInstrumentation.runOnMainSync(() -> {
            picture.mViewport.scaleTo(1f, 0, 0);
            picture.mViewport.moveTo(0, 0);
            picture.invalidate();
        });
        int[] last = {-1};
        assertTrue("the full-detail tiles never settled", waitUntil(() -> {
            int tiles = onMainCount(() -> picture.getTileCount(1));
            boolean settled = tiles > 0 && tiles == last[0];
            last[0] = tiles;
            SystemClock.sleep(500);
            return settled;
        }, 30));
        boolean base = onMain(picture::baseHasGainmap);
        int tiles = onMainCount(picture::getTileCount);
        int gainmapTiles = onMainCount(picture::getGainmapTileCount);
        save(screenshot(view), "ultrahdr-one-to-one");
        Log.i(TAG, "Ultra HDR on API " + Build.VERSION.SDK_INT
                + ": MediaTags gain map " + tags.getGainMap()
                + ", BitmapFactory keeps it " + whole
                + ", BitmapRegionDecoder keeps it " + region
                + ", viewer's copy keeps it " + base
                + ", tiles that keep it " + gainmapTiles + " of " + tiles);
        assertTrue(whole);
        assertTrue(base);
        assertEquals("every tile keeps the gain map", tiles, gainmapTiles);

        // Off the screen, the window goes back.
        mScenario.onActivity(activity -> activity.getRoot().removeAllViews());
        assertEquals(before, colorMode());
    }

    /** A motion photo shows its still, plays its clip while held, and stops when let go. */
    @Test
    @SdkSuppress(minSdkVersion = 23)
    public void aMotionPhotoPlaysItsClipWhileHeld() throws Exception {
        File clip = new File(mDirectory, "clip.mp4");
        record(clip, 320, 240, null);
        byte[] video = readAll(clip);
        File photo = new File(mDirectory, "motion.jpg");
        Bitmap still = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888);
        still.eraseColor(Color.BLUE);
        writeJpeg(photo, still, motionPhotoXmp(video.length), video);
        MediaTags.MotionPhoto motion = MediaTags.open(photo).getMotionPhoto();
        assertNotNull(motion);
        assertEquals(video.length, motion.getVideoLength());

        assertEquals(MediaView.MOTION_PHOTO, show(photo, "motion-still"));
        MediaView view = mView;
        assertFalse(onMain(view::isPlaying));
        mInstrumentation.runOnMainSync(view::performLongClick);
        assertTrue("the clip never played", waitUntil(() -> onMain(view::isPlaying), 20));
        // The clip's red and green frames cover the blue still while it plays.
        char[] shown = {'B'};
        waitUntil(() -> {
            Bitmap screen = screenshot(view);
            shown[0] = ViewerDeviceTest.hue(screen.getPixel(view.getWidth() / 2,
                    view.getHeight() / 2));
            return shown[0] != 'B';
        }, 3);
        save(screenshot(view), "motion-clip");
        assertTrue("the clip showed " + shown[0], shown[0] == 'R' || shown[0] == 'G');

        mInstrumentation.runOnMainSync(() -> {
            long now = SystemClock.uptimeMillis();
            MotionEvent up = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, 10, 10, 0);
            view.dispatchTouchEvent(up);
            up.recycle();
        });
        assertFalse("letting go stops the clip", onMain(view::isPlaying));
    }

    /** A 360° video plays on the inside of the sphere, each direction where it belongs. */
    @Test
    @SdkSuppress(minSdkVersion = 23)
    public void aSphericalVideoPlaysOnTheSphere() throws Exception {
        File video = new File(mDirectory, "sphere.mp4");
        record(video, 1024, 512, ViewerDeviceTest.compass(1024, 512, 1024, 512, 0, 0));
        makeSpherical(video);
        assertNotNull(MediaTags.open(video).getSphericalVideo());

        assertEquals(MediaView.SPHERICAL_VIDEO, show(video, "sphere-video"));
        assertTrue("the video never played", play());
        // Long enough for frames to reach the sphere's texture.
        SystemClock.sleep(1500);
        PhotosphereView sphere = (PhotosphereView) make(mView::getContentView);
        expect(sphere, 0, 0, 'R', "sphere-video-north");
        expect(sphere, 90, 0, 'G', "sphere-video-east");
        expect(sphere, 180, 0, 'B', "sphere-video-south");
        expect(sphere, -90, 0, 'Y', "sphere-video-west");
    }

    /**
     * Each pushed file shows as expected.txt says: one line per file, its name, the type it
     * shows as or FAILED, and "hdr" where it holds the window in HDR on Android 14 and later.
     */
    @Test
    public void pushedFilesShowAsExpected() throws Exception {
        byte[] expected = shell("cat " + PUSHED + "expected.txt");
        assumeTrue("nothing pushed to " + PUSHED, expected.length > 0);
        File directory = new File(mDirectory, "pushed");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        List<String> mismatches = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(expected), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\\s+");
                File file = new File(directory, parts[0]);
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(shell("cat " + PUSHED + parts[0]));
                }
                int want = typeOf(parts[1]);
                boolean hdr = parts.length > 2 && "hdr".equals(parts[2]);
                long start = SystemClock.elapsedRealtime();
                int got = show(file, "pushed-" + parts[0].replace('.', '-'));
                long millis = SystemClock.elapsedRealtime() - start;
                boolean holding = got != FAILED && onMain(mView::isHoldingHdr);
                boolean plays = got == MediaView.VIDEO || got == MediaView.SPHERICAL_VIDEO
                        || got == MediaView.AUDIO;
                boolean played = !plays || play();
                if (plays) {
                    save(screenshot(mView), "pushed-" + parts[0].replace('.', '-') + "-playing");
                }
                String result = parts[0] + ": " + nameOf(got) + (holding ? " HDR" : "")
                        + (played ? "" : ", did not play") + ", in " + millis + " ms";
                Log.i(TAG, "API " + Build.VERSION.SDK_INT + " " + result);
                boolean hdrWrong = Build.VERSION.SDK_INT >= 34 && hdr != holding;
                if (got != want || hdrWrong || !played) {
                    mismatches.add(result + "; expected " + parts[1] + (hdr ? " HDR" : ""));
                }
            }
        }
        assertTrue(TextUtils.join("\n", mismatches), mismatches.isEmpty());
    }

    // ---------- Showing ----------

    /**
     * Shows a file in a new MediaView, full screen, and waits until it says what the file is.
     *
     * @return the type it showed as, or {@link #FAILED}
     */
    private int show(File file, String name) throws Exception {
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
        AtomicReference<MediaView> made = new AtomicReference<>();
        mScenario.onActivity(activity -> {
            // Made with the activity, whose window it puts in HDR.
            MediaView view = new MediaView(activity);
            made.set(view);
            view.setMediaListener(listener);
            activity.getRoot().removeAllViews();
            activity.getRoot().addView(view, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            view.setFile(file);
        });
        MediaView view = made.get();
        mView = view;
        assertTrue(file + " was never reported", done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (type[0] != FAILED) {
            // The tiles and the first frame land after the report; give them a moment.
            SystemClock.sleep(2000);
            save(screenshot(view), name);
        }
        return type[0];
    }

    /** Shows a Uri in a new MediaView and waits until it says what it is. */
    private int showUri(Uri uri, String name) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        int[] type = {FAILED};
        AtomicReference<MediaView> made = new AtomicReference<>();
        mScenario.onActivity(activity -> {
            MediaView view = new MediaView(activity);
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
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            view.setUri(uri);
            made.set(view);
        });
        mView = made.get();
        assertTrue(uri + " was never reported", done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        SystemClock.sleep(1500);
        save(screenshot(mView), name);
        return type[0];
    }

    /** Plays what the last view shows, and waits until it is playing. */
    private boolean play() {
        MediaView view = mView;
        return waitUntil(() -> {
            if (onMain(view::isPlaying)) {
                return true;
            }
            mInstrumentation.runOnMainSync(view::play);
            SystemClock.sleep(200);
            return onMain(view::isPlaying);
        }, 15);
    }

    /** Points a sphere somewhere and checks the colour in the middle of the screen. */
    private void expect(PhotosphereView view, double heading, double pitch, char hue,
            String name) throws IOException {
        mInstrumentation.runOnMainSync(() -> view.lookAt(heading, pitch));
        SystemClock.sleep(500);
        Bitmap shown = screenshot(view);
        save(shown, name);
        assertEquals("Looking at " + heading + ", " + pitch, hue,
                ViewerDeviceTest.hue(shown.getPixel(view.getWidth() / 2, view.getHeight() / 2)));
    }

    /** The window's colour mode. */
    @RequiresApi(26)
    private int colorMode() {
        int[] mode = new int[1];
        mScenario.onActivity(activity -> mode[0] = activity.getWindow().getColorMode());
        return mode[0];
    }

    /** What is on the screen where a view is, which for a surface is the only way to see it. */
    private Bitmap screenshot(View view) {
        Bitmap screen = mInstrumentation.getUiAutomation().takeScreenshot();
        int[] location = new int[2];
        mInstrumentation.runOnMainSync(() -> view.getLocationOnScreen(location));
        return Bitmap.createBitmap(screen, location[0], location[1], view.getWidth(),
                view.getHeight());
    }

    /** What a command run as the shell user prints. */
    private byte[] shell(String command) throws IOException {
        ParcelFileDescriptor output = mInstrumentation.getUiAutomation()
                .executeShellCommand(command);
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(output)) {
            return readAll(in);
        }
    }

    private void save(Bitmap bitmap, String name) throws IOException {
        File directory = new File(mContext.getExternalFilesDir(null), "media-proof");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream out = new FileOutputStream(new File(directory,
                name + "-api" + Build.VERSION.SDK_INT + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    // ---------- Capturing ----------

    /** Takes a picture and records a few seconds with the library, in its test activity. */
    private void capture(File picture, File video) throws Exception {
        try (ActivityScenario<CameraTestActivity> scenario =
                     ActivityScenario.launch(CameraTestActivity.class)) {
            CountDownLatch opened = new CountDownLatch(1);
            AtomicReference<CameraView> held = new AtomicReference<>();
            scenario.onActivity(activity -> {
                CameraView camera = activity.getCameraView();
                held.set(camera);
                camera.setOnCameraStateChangedListener(
                        new CameraView.OnCameraStateChangedListener() {
                            @Override
                            public void onCameraOpened() {
                                opened.countDown();
                            }

                            @Override
                            public void onCameraClosed() {}
                        });
                if (camera.isOpen()) {
                    opened.countDown();
                } else {
                    camera.open();
                }
            });
            assertTrue("the camera never opened",
                    opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            CameraView camera = held.get();
            camera.setFlash(CameraView.Flash.OFF);
            try {
                CountDownLatch pictured = new CountDownLatch(1);
                camera.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
                    @Override
                    public void onImageConfirmation() {}

                    @Override
                    public void onImageCaptured(File saved) {
                        pictured.countDown();
                    }

                    @Override
                    public void onFailure() {
                        pictured.countDown();
                    }
                });
                camera.takePicture(picture);
                assertTrue("the capture never reported",
                        pictured.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

                CountDownLatch recorded = new CountDownLatch(1);
                camera.setOnVideoCapturedListener(new CameraView.OnVideoCapturedListener() {
                    @Override
                    public void onVideoConfirmation() {}

                    @Override
                    public void onVideoCaptured(File saved) {
                        recorded.countDown();
                    }

                    @Override
                    public void onFailure() {
                        recorded.countDown();
                    }
                });
                camera.startRecording(video);
                SystemClock.sleep(4000);
                camera.stopRecording();
                assertTrue("the recording never reported",
                        recorded.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            } finally {
                scenario.onActivity(activity -> activity.getCameraView().close());
            }
        }
    }

    // ---------- Making files ----------

    /** A grey picture whose gain map brightens its left half fourfold. */
    @RequiresApi(34)
    static void writeUltraHdr(File file, int width, int height) throws IOException {
        Bitmap base = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        base.eraseColor(0xFF808080);
        Bitmap gain = Bitmap.createBitmap(width / 4, height / 4, Bitmap.Config.ARGB_8888);
        gain.eraseColor(Color.BLACK);
        Paint white = new Paint();
        white.setColor(Color.WHITE);
        new Canvas(gain).drawRect(0, 0, gain.getWidth() / 2f, gain.getHeight(), white);
        Gainmap gainmap = new Gainmap(gain);
        gainmap.setRatioMin(1f, 1f, 1f);
        gainmap.setRatioMax(4f, 4f, 4f);
        gainmap.setDisplayRatioForFullHdr(4f);
        gainmap.setMinDisplayRatioForHdrTransition(1f);
        base.setGainmap(gainmap);
        try (FileOutputStream out = new FileOutputStream(file)) {
            assertTrue(base.compress(Bitmap.CompressFormat.JPEG, 90, out));
        }
    }

    /** Whether a whole decode, or a region decoded at half size, carries the gain map. */
    @RequiresApi(34)
    private static boolean decodeKeepsGainmap(File file, boolean region) throws IOException {
        Bitmap bitmap;
        if (region) {
            BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(file.getPath());
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 2;
            bitmap = decoder.decodeRegion(new Rect(0, 0, decoder.getWidth() / 2,
                    decoder.getHeight() / 2), options);
            decoder.recycle();
        } else {
            bitmap = BitmapFactory.decodeFile(file.getPath());
        }
        return bitmap != null && bitmap.hasGainmap();
    }

    /**
     * Records three seconds of video from a surface: a picture stretched over each frame, or
     * red and green by turns without one.
     */
    @RequiresApi(23)
    @SuppressWarnings("deprecation")
    static void record(File file, int width, int height, @Nullable Bitmap frame)
            throws IOException {
        MediaRecorder recorder = new MediaRecorder();
        try {
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setVideoSize(width, height);
            recorder.setVideoFrameRate(30);
            recorder.setVideoEncodingBitRate(4_000_000);
            recorder.setOutputFile(file.getPath());
            recorder.prepare();
            Surface surface = recorder.getSurface();
            recorder.start();
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            Rect whole = new Rect(0, 0, width, height);
            for (int i = 0; i < 90; i++) {
                Canvas canvas = surface.lockHardwareCanvas();
                if (frame != null) {
                    canvas.drawBitmap(frame, null, whole, paint);
                } else {
                    canvas.drawColor(i % 30 < 15 ? Color.RED : Color.GREEN);
                }
                surface.unlockCanvasAndPost(canvas);
                SystemClock.sleep(33);
            }
            recorder.stop();
        } finally {
            recorder.release();
        }
        assertTrue("the encoder wrote nothing", file.length() > 0);
    }

    /**
     * Gives a recording version 1 spherical metadata, in a uuid box at the end of its video
     * track. The grown moov goes at the end of the file and the old one becomes free space, so
     * no chunk moves.
     */
    private static void makeSpherical(File file) throws IOException {
        byte[] bytes = readAll(file);
        int moov = -1;
        int moovSize = 0;
        for (int at = 0; at + 8 <= bytes.length; ) {
            long size = u32(bytes, at);
            String type = new String(bytes, at + 4, 4, StandardCharsets.US_ASCII);
            if (size == 0) {
                size = bytes.length - at;
            }
            assertTrue("a box too large for this test", size > 1 && size <= bytes.length - at);
            if ("moov".equals(type)) {
                moov = at;
                moovSize = (int) size;
                break;
            }
            at += (int) size;
        }
        assertTrue("no moov", moov >= 0);
        int trak = -1;
        for (int at = moov + 8; at + 8 <= moov + moovSize; at += (int) u32(bytes, at)) {
            if ("trak".equals(new String(bytes, at + 4, 4, StandardCharsets.US_ASCII))) {
                trak = at;
                break;
            }
        }
        assertTrue("no track", trak >= 0);
        int trakEnd = trak + (int) u32(bytes, trak);

        byte[] xmp = ("<?xml version=\"1.0\"?><rdf:SphericalVideo"
                + " xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\""
                + " xmlns:GSpherical=\"http://ns.google.com/videos/1.0/spherical/\">"
                + "<GSpherical:Spherical>true</GSpherical:Spherical>"
                + "<GSpherical:Stitched>true</GSpherical:Stitched>"
                + "<GSpherical:StitchingSoftware>CameraView</GSpherical:StitchingSoftware>"
                + "<GSpherical:ProjectionType>equirectangular</GSpherical:ProjectionType>"
                + "</rdf:SphericalVideo>").getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream uuid = new ByteArrayOutputStream();
        uuid.write(be32(8 + 16 + xmp.length));
        uuid.write("uuid".getBytes(StandardCharsets.US_ASCII));
        uuid.write(SPHERICAL_UUID);
        uuid.write(xmp);
        byte[] box = uuid.toByteArray();

        ByteArrayOutputStream grown = new ByteArrayOutputStream();
        grown.write(bytes, moov, trakEnd - moov);
        grown.write(box);
        grown.write(bytes, trakEnd, moov + moovSize - trakEnd);
        byte[] newMoov = grown.toByteArray();
        System.arraycopy(be32(newMoov.length), 0, newMoov, 0, 4);
        System.arraycopy(be32((int) u32(bytes, trak) + box.length), 0, newMoov, trak - moov, 4);

        System.arraycopy("free".getBytes(StandardCharsets.US_ASCII), 0, bytes, moov + 4, 4);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
            out.write(newMoov);
        }
    }

    /** A JPEG of a bitmap with an XMP packet straight after its start, and bytes after it. */
    static void writeJpeg(File file, Bitmap bitmap, String xmp, byte[] tail)
            throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, encoded));
        byte[] jpeg = encoded.toByteArray();
        byte[] payload = ("http://ns.adobe.com/xap/1.0/\u0000" + xmp)
                .getBytes(StandardCharsets.UTF_8);
        int length = payload.length + 2;
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(jpeg, 0, 2);
            out.write(new byte[] {(byte) 0xFF, (byte) 0xE1, (byte) (length >> 8), (byte) length});
            out.write(payload);
            out.write(jpeg, 2, jpeg.length - 2);
            out.write(tail);
        }
    }

    /** A motion photo's XMP, for a clip of a length at the end of the file. */
    private static String motionPhotoXmp(long videoLength) {
        return "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
                + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description rdf:about=\"\""
                + " xmlns:Camera=\"http://ns.google.com/photos/1.0/camera/\""
                + " xmlns:Container=\"http://ns.google.com/photos/1.0/container/\""
                + " xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\""
                + " Camera:MotionPhoto=\"1\" Camera:MotionPhotoVersion=\"1\""
                + " Camera:MotionPhotoPresentationTimestampUs=\"0\">"
                + "<Container:Directory><rdf:Seq>"
                + "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"image/jpeg\""
                + " Item:Semantic=\"Primary\" Item:Length=\"0\" Item:Padding=\"0\"/></rdf:li>"
                + "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"video/mp4\""
                + " Item:Semantic=\"MotionPhoto\" Item:Length=\"" + videoLength + "\"/></rdf:li>"
                + "</rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>";
    }

    private static byte[] readAll(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return readAll(in);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static long u32(byte[] bytes, int at) {
        return ((bytes[at] & 0xFFL) << 24) | ((bytes[at + 1] & 0xFFL) << 16)
                | ((bytes[at + 2] & 0xFFL) << 8) | (bytes[at + 3] & 0xFFL);
    }

    private static byte[] be32(int value) {
        return new byte[] {(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8),
                (byte) value};
    }

    // ---------- Names ----------

    /** A media type by its constant's name, or FAILED. */
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
            case "FAILED":
                return FAILED;
            default:
                throw new IllegalArgumentException("No media type " + name);
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
            default:
                return "FAILED";
        }
    }

    // ---------- Threads ----------

    private boolean onMain(Check check) {
        AtomicReference<Boolean> result = new AtomicReference<>();
        mInstrumentation.runOnMainSync(() -> result.set(check.get()));
        return result.get();
    }

    private int onMainCount(Count count) {
        int[] result = new int[1];
        mInstrumentation.runOnMainSync(() -> result[0] = count.get());
        return result[0];
    }

    /** Makes a view on the main thread, where views have to be made. */
    private <T> T make(Make<T> make) {
        AtomicReference<T> result = new AtomicReference<>();
        mInstrumentation.runOnMainSync(() -> result.set(make.get()));
        return result.get();
    }

    /** Polls until something is true, and says whether it came true in time. */
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

    /** A count read on the main thread. */
    private interface Count {
        int get();
    }

    /** Something made on the main thread. */
    private interface Make<T> {
        T get();
    }
}
