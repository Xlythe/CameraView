package com.xlythe.view.camera.viewer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;

import androidx.exifinterface.media.ExifInterface;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The viewers on a real device, with real decoders and a real GPU: what this release can decode
 * of a DNG, and what each viewer actually puts on the screen for pictures whose every part has a
 * known colour.
 *
 * <p>Each test saves what it saw under the app's external files, in viewer-proof.</p>
 */
@RunWith(AndroidJUnit4.class)
public class ViewerDeviceTest {
    private static final String TAG = "ViewerDevice";

    @Rule
    public final ActivityScenarioRule<ViewerTestActivity> mActivity =
            new ActivityScenarioRule<>(ViewerTestActivity.class);

    private Instrumentation mInstrumentation;
    private Context mContext;
    private File mDirectory;

    @Before
    public void setUp() {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mContext = mInstrumentation.getTargetContext();
        mDirectory = new File(mContext.getCacheDir(), "viewer-test");
        assertTrue(mDirectory.isDirectory() || mDirectory.mkdirs());
        File copies = new File(mContext.getCacheDir(), ImageSources.CACHE_DIRECTORY);
        File[] old = copies.listFiles();
        if (old != null) {
            for (File file : old) {
                assertTrue(file.delete());
            }
        }
    }

    /**
     * What decodes a DNG on this release. Logged as one line per device, under ViewerDevice, so
     * a run across API levels reads as a table.
     */
    @Test
    public void whatDecodesADng() throws IOException {
        File dng = new File(mDirectory, "probe.dng");
        SyntheticDng.write(dng, 1024, 768, ExifInterface.ORIENTATION_NORMAL);

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(dng.getPath(), bounds);
        long start = SystemClock.elapsedRealtime();
        Bitmap full = BitmapFactory.decodeFile(dng.getPath());
        long fullMillis = SystemClock.elapsedRealtime() - start;
        BitmapFactory.Options sampled = new BitmapFactory.Options();
        sampled.inSampleSize = 4;
        Bitmap quarter = BitmapFactory.decodeFile(dng.getPath(), sampled);
        String region;
        try {
            BitmapRegionDecoder decoder = newRegionDecoder(dng);
            region = decoder == null ? "null" : "opened " + decoder.getWidth();
        } catch (IOException e) {
            region = "refused (" + e.getMessage() + ")";
        }
        String imageDecoder = "absent";
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                Bitmap decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(dng),
                        (decoderToUse, info, source) ->
                                decoderToUse.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
                imageDecoder = decoded.getWidth() + "x" + decoded.getHeight();
            } catch (IOException e) {
                imageDecoder = "refused (" + e.getMessage() + ")";
            }
        }
        int orientation = new ExifInterface(dng.getPath()).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, -1);
        Log.i(TAG, "DNG on API " + Build.VERSION.SDK_INT
                + ": bounds " + bounds.outWidth + "x" + bounds.outHeight + " " + bounds.outMimeType
                + ", BitmapFactory " + (full == null ? "null"
                        : full.getWidth() + "x" + full.getHeight() + " in " + fullMillis + " ms")
                + ", 1/4 " + (quarter == null ? "null"
                        : quarter.getWidth() + "x" + quarter.getHeight())
                + ", BitmapRegionDecoder " + region
                + ", ImageDecoder " + imageDecoder
                + ", Exif orientation " + orientation);

        if (Build.VERSION.SDK_INT >= 24) {
            assertNotNull(full);
            assertEquals(1024, full.getWidth());
            assertEquals(768, full.getHeight());
            assertEquals('R', hue(full.getPixel(256, 384)));
            assertEquals('G', hue(full.getPixel(768, 192)));
            assertEquals('B', hue(full.getPixel(768, 576)));
            assertNotNull(quarter);
            assertEquals(256, quarter.getWidth());
            assertFalse(region.startsWith("opened"));
        } else {
            assertNull(full);
        }
        assertEquals(ExifInterface.ORIENTATION_NORMAL, orientation);
    }

    /**
     * A DNG turned a quarter is shown upright: decoded once into the cache, then viewed like any
     * other picture. Before Android 7.0 it is reported as a failure instead.
     */
    @Test
    public void aDngIsShownUpright() throws Exception {
        File dng = new File(mDirectory, "turned.dng");
        SyntheticDng.write(dng, 1200, 800, ExifInterface.ORIENTATION_ROTATE_90);
        LargeImageView view = make(() -> new LargeImageView(mContext));
        boolean loaded = show(view, v -> v.setFile(dng));

        if (Build.VERSION.SDK_INT < 24) {
            assertFalse(loaded);
            return;
        }
        assertTrue(loaded);
        assertEquals(1, new File(mContext.getCacheDir(), ImageSources.CACHE_DIRECTORY)
                .listFiles().length);
        Bitmap shown = draw(view);
        save(shown, "dng-upright");
        // Upright the stored left half, red, is the top; the stored top-right, green, is the
        // bottom-right, and the stored bottom-right, blue, the bottom-left.
        Picture picture = new Picture(view, 800, 1200);
        assertEquals('R', hue(picture.at(shown, 400, 300)));
        assertEquals('G', hue(picture.at(shown, 600, 900)));
        assertEquals('B', hue(picture.at(shown, 200, 900)));
    }

    /**
     * A 24 MP picture of fine stripes, zoomed to its own pixels, shows the stripes crisp: the
     * tiles came in at full detail. All the while it held no more than its budget.
     */
    @Test
    public void aLargePictureIsSharpWhenZoomedIn() throws Exception {
        int width = Build.VERSION.SDK_INT >= 26 ? 6000 : 3000;
        int height = width * 2 / 3;
        File jpeg = new File(mDirectory, "stripes.jpg");
        writeStripes(jpeg, width, height);
        LargeImageView view = make(() -> new LargeImageView(mContext));
        assertTrue(show(view, v -> v.setFile(jpeg)));
        save(draw(view), "large-fit");

        mInstrumentation.runOnMainSync(() -> {
            view.mViewport.scaleTo(1f, 0, 0);
            view.mViewport.moveTo(0, 0);
            view.invalidate();
        });
        // Until the full-detail tiles stop arriving.
        int[] last = {-1};
        waitFor(() -> {
            int tiles = onMainCount(() -> view.getTileCount(1));
            boolean settled = tiles > 0 && tiles == last[0];
            last[0] = tiles;
            SystemClock.sleep(500);
            return settled;
        }, 30);
        Bitmap shown = draw(view);
        save(shown, "large-one-to-one");
        int black = 0;
        int white = 0;
        int samples = Math.min(400, shown.getWidth());
        for (int x = 0; x < samples; x++) {
            int color = shown.getPixel(x, Math.min(200, shown.getHeight() / 2));
            int light = Color.red(color) + Color.green(color) + Color.blue(color);
            if (light < 120) {
                black++;
            } else if (light > 640) {
                white++;
            }
        }
        assertTrue("Black " + black + ", white " + white,
                black > samples * 3 / 10 && white > samples * 3 / 10);
        assertTrue(onMain(() -> view.getPeakPixels() <= view.getBudgetPixels()));
    }

    /** Each quarter of the compass and each pole shows on the GPU where it should. */
    @Test
    public void aPhotosphereShowsEveryDirection() throws Exception {
        File jpeg = new File(mDirectory, "compass.jpg");
        writeJpeg(jpeg, compass(2048, 1024, 2048, 1024, 0, 0), null);
        PhotosphereView view = make(() -> new PhotosphereView(mContext));
        assertTrue(show(view, v -> v.setFile(jpeg)));

        expect(view, 0, 0, 'R', "sphere-north");
        expect(view, 90, 0, 'G', "sphere-east");
        expect(view, 180, 0, 'B', "sphere-south");
        expect(view, -90, 0, 'Y', "sphere-west");
        expect(view, 0, 80, 'W', "sphere-up");
        expect(view, 0, -80, 'M', "sphere-down");
        // The stripe at heading 30 lands where the perspective puts it.
        mInstrumentation.runOnMainSync(() -> view.lookAt(0, 0));
        SystemClock.sleep(500);
        Bitmap shown = screenshot(view);
        double halfWidth = Math.tan(Math.toRadians(SphereMath.horizontalFov(view.getFov(),
                view.getWidth(), view.getHeight()) / 2));
        int x = (int) Math.round(view.getWidth() / 2.0
                * (1 + Math.tan(Math.toRadians(30)) / halfWidth));
        assertEquals('C', hue(shown.getPixel(x, view.getHeight() / 2)));
    }

    /** A band around the horizon shows where it was taken, and nothing above, below or behind. */
    @Test
    public void aPartialPhotosphereLeavesTheRestEmpty() throws Exception {
        File jpeg = new File(mDirectory, "band.jpg");
        writeJpeg(jpeg, compass(1024, 342, 2048, 1024, 512, 341), "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
                + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description rdf:about=\"\" xmlns:GPano=\"" + GPano.NAMESPACE + "\""
                + " GPano:ProjectionType=\"equirectangular\""
                + " GPano:CroppedAreaImageWidthPixels=\"1024\""
                + " GPano:CroppedAreaImageHeightPixels=\"342\""
                + " GPano:FullPanoWidthPixels=\"2048\" GPano:FullPanoHeightPixels=\"1024\""
                + " GPano:CroppedAreaLeftPixels=\"512\" GPano:CroppedAreaTopPixels=\"341\"/>"
                + "</rdf:RDF></x:xmpmeta>");
        PhotosphereView view = make(() -> new PhotosphereView(mContext));
        assertTrue(show(view, v -> v.setFile(jpeg)));

        expect(view, 0, 0, 'R', "band-north");
        expect(view, 75, 0, 'G', "band-east");
        expect(view, 180, 0, 'K', "band-behind");
        expect(view, 0, 60, 'K', "band-above");
        expect(view, 0, -60, 'K', "band-below");
    }

    /** A panorama that goes all the way round carries on past its end into its start. */
    @Test
    public void aFullCirclePanoramaWraps() throws Exception {
        File jpeg = new File(mDirectory, "strip.jpg");
        writeJpeg(jpeg, compass(3032, 532, 3032, 1516, 0, 472),
                stripXmp());
        PanoramaView view = make(() -> new PanoramaView(mContext));
        assertTrue(show(view, v -> v.setFile(jpeg)));
        save(draw(view), "panorama-start");
        assertTrue(onMain(() -> view.mViewport.wraps()));

        // Panned so the seam, due south, is in the middle: blue on both sides of it.
        mInstrumentation.runOnMainSync(() -> {
            Viewport viewport = view.mViewport;
            viewport.moveTo(view.getWidth() / 2f - viewport.scaledWidth(), 0);
            view.invalidate();
        });
        SystemClock.sleep(500);
        Bitmap shown = draw(view);
        save(shown, "panorama-seam");
        int middle = view.getWidth() / 2;
        assertEquals('B', hue(shown.getPixel(middle - 20, view.getHeight() / 2)));
        assertEquals('B', hue(shown.getPixel(middle + 20, view.getHeight() / 2)));
    }

    /** The sweep writer's metadata for a full-circle strip, 3032 x 532 of a 3032 x 1516 sphere. */
    private static String stripXmp() {
        return "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
                + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description rdf:about=\"\" xmlns:GPano=\"" + GPano.NAMESPACE + "\""
                + " GPano:UsePanoramaViewer=\"True\" GPano:ProjectionType=\"equirectangular\""
                + " GPano:PoseHeadingDegrees=\"0\" GPano:InitialViewHeadingDegrees=\"0\""
                + " GPano:CroppedAreaImageWidthPixels=\"3032\""
                + " GPano:CroppedAreaImageHeightPixels=\"532\""
                + " GPano:FullPanoWidthPixels=\"3032\" GPano:FullPanoHeightPixels=\"1516\""
                + " GPano:CroppedAreaLeftPixels=\"0\" GPano:CroppedAreaTopPixels=\"472\"/>"
                + "</rdf:RDF></x:xmpmeta>";
    }

    /** Points a photo sphere somewhere and checks the colour in the middle of the screen. */
    private void expect(PhotosphereView view, double heading, double pitch, char hue,
            String name) throws Exception {
        mInstrumentation.runOnMainSync(() -> view.lookAt(heading, pitch));
        SystemClock.sleep(500);
        Bitmap shown = screenshot(view);
        save(shown, name);
        assertEquals("Looking at " + heading + ", " + pitch, hue,
                hue(shown.getPixel(view.getWidth() / 2, view.getHeight() / 2)));
    }

    /** Shows a viewer full screen and waits until it says the picture is showing or failed. */
    private <T extends View> boolean show(T view, ViewAction<T> load) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Boolean> loaded = new AtomicReference<>();
        ImageLoadListener listener = new ImageLoadListener() {
            @Override
            public void onImageLoaded() {
                loaded.set(true);
                done.countDown();
            }

            @Override
            public void onImageFailed() {
                loaded.set(false);
                done.countDown();
            }
        };
        mActivity.getScenario().onActivity(activity -> {
            if (view instanceof LargeImageView) {
                ((LargeImageView) view).setImageLoadListener(listener);
            } else {
                ((PhotosphereView) view).setImageLoadListener(listener);
            }
            activity.getRoot().addView(view, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            load.run(view);
        });
        assertTrue("Never reported", done.await(60, TimeUnit.SECONDS));
        // The tiles land after the low-resolution copy; give them a moment.
        SystemClock.sleep(Boolean.TRUE.equals(loaded.get()) ? 3000 : 0);
        return Boolean.TRUE.equals(loaded.get());
    }

    /** What a view draws, drawn in software. */
    private Bitmap draw(View view) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(),
                Bitmap.Config.ARGB_8888);
        mInstrumentation.runOnMainSync(() -> view.draw(new Canvas(bitmap)));
        return bitmap;
    }

    /** What is on the screen where a view is, which for a surface is the only way to see it. */
    private Bitmap screenshot(View view) {
        Bitmap screen = mInstrumentation.getUiAutomation().takeScreenshot();
        int[] location = new int[2];
        mInstrumentation.runOnMainSync(() -> view.getLocationOnScreen(location));
        return Bitmap.createBitmap(screen, location[0], location[1], view.getWidth(),
                view.getHeight());
    }

    private void save(Bitmap bitmap, String name) throws IOException {
        File directory = new File(mContext.getExternalFilesDir(null), "viewer-proof");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream out = new FileOutputStream(new File(directory,
                name + "-api" + Build.VERSION.SDK_INT + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    private boolean onMain(Check check) {
        AtomicReference<Boolean> result = new AtomicReference<>();
        mInstrumentation.runOnMainSync(() -> result.set(check.get()));
        return result.get();
    }

    /** Makes a view on the main thread, where views have to be made. */
    private <T> T make(Make<T> make) {
        AtomicReference<T> result = new AtomicReference<>();
        mInstrumentation.runOnMainSync(() -> result.set(make.get()));
        return result.get();
    }

    private int onMainCount(Count count) {
        int[] result = new int[1];
        mInstrumentation.runOnMainSync(() -> result[0] = count.get());
        return result[0];
    }

    private static void waitFor(Check condition, int seconds) {
        long deadline = SystemClock.elapsedRealtime() + seconds * 1000L;
        while (!condition.get()) {
            if (SystemClock.elapsedRealtime() > deadline) {
                fail("Timed out");
            }
            SystemClock.sleep(100);
        }
    }

    /**
     * The dominant colour: R, G, B, Y (red and green), M (red and blue), C (green and blue),
     * W (all bright) or K (all dark).
     */
    static char hue(int color) {
        boolean r = Color.red(color) > 128;
        boolean g = Color.green(color) > 128;
        boolean b = Color.blue(color) > 128;
        if (r && g && b) {
            return 'W';
        }
        if (r && g) {
            return 'Y';
        }
        if (r && b) {
            return 'M';
        }
        if (g && b) {
            return 'C';
        }
        if (r) {
            return 'R';
        }
        if (g) {
            return 'G';
        }
        if (b) {
            return 'B';
        }
        return 'K';
    }

    /** Finds an upright picture's pixel in what a view drew, from where the view placed it. */
    private static final class Picture {
        final float scale;
        final float left;
        final float top;

        Picture(LargeImageView view, int width, int height) {
            Viewport viewport = view.mViewport;
            scale = viewport.getScale();
            left = viewport.getX();
            top = viewport.getY();
            assertEquals(width * scale, viewport.scaledWidth(), 1);
            assertEquals(height * scale, viewport.scaledHeight(), 1);
        }

        int at(Bitmap shown, int x, int y) {
            return shown.getPixel(Math.round(left + x * scale), Math.round(top + y * scale));
        }
    }

    /** Loads a picture into a viewer. */
    private interface ViewAction<T> {
        void run(T view);
    }

    /** Something true or false, here rather than java.util.function, which API 22 lacks. */
    private interface Check {
        boolean get();
    }

    /** Something made on the main thread. */
    private interface Make<T> {
        T get();
    }

    /** A count read on the main thread. */
    private interface Count {
        int get();
    }

    private static BitmapRegionDecoder newRegionDecoder(File file) throws IOException {
        if (Build.VERSION.SDK_INT >= 31) {
            return BitmapRegionDecoder.newInstance(file.getPath());
        }
        return newRegionDecoderBefore31(file);
    }

    @SuppressWarnings("deprecation")
    private static BitmapRegionDecoder newRegionDecoderBefore31(File file) throws IOException {
        return BitmapRegionDecoder.newInstance(file.getPath(), false);
    }

    /** Vertical stripes, four pixels black and four white. */
    private static void writeStripes(File file, int width, int height) throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int[] row = new int[width];
        for (int x = 0; x < width; x++) {
            row[x] = (x / 4) % 2 == 0 ? Color.BLACK : Color.WHITE;
        }
        for (int y = 0; y < height; y++) {
            bitmap.setPixels(row, 0, width, 0, y, width, 1);
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out));
        }
        bitmap.recycle();
    }

    /** The same compass picture the unit tests draw, here for a device. */
    static Bitmap compass(int width, int height, int fullWidth, int fullHeight, int left,
            int top) {
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            double pitch = 90 - (top + y + 0.5) / fullHeight * 180;
            for (int x = 0; x < width; x++) {
                double heading = (left + x + 0.5) / fullWidth * 360 - 180;
                pixels[y * width + x] = compassColor(heading, pitch);
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    private static int compassColor(double heading, double pitch) {
        if (pitch > 60) {
            return 0xFFFFFFFF;
        }
        if (pitch < -60) {
            return 0xFFFF00FF;
        }
        if (heading >= 28 && heading <= 32) {
            return 0xFF00FFFF;
        }
        if (heading >= -45 && heading < 45) {
            return 0xFFFF0000;
        }
        if (heading >= 45 && heading < 135) {
            return 0xFF00FF00;
        }
        if (heading >= -135 && heading < -45) {
            return 0xFFFFFF00;
        }
        return 0xFF0000FF;
    }

    /** A JPEG of a bitmap, with an XMP packet if one is given. */
    private static void writeJpeg(File file, Bitmap bitmap, String xmp) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, encoded));
        byte[] jpeg = encoded.toByteArray();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(jpeg, 0, 2);
            if (xmp != null) {
                byte[] payload = ("http://ns.adobe.com/xap/1.0/\u0000" + xmp)
                        .getBytes(StandardCharsets.UTF_8);
                int length = payload.length + 2;
                out.write(new byte[] {(byte) 0xFF, (byte) 0xE1, (byte) (length >> 8),
                        (byte) length});
                out.write(payload);
            }
            out.write(jpeg, 2, jpeg.length - 2);
        }
    }
}
