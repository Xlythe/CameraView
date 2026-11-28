package com.xlythe.view.camera.viewer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import androidx.exifinterface.media.ExifInterface;

import com.xlythe.view.camera.shadow.ShadowRecordingGles20;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.SensorEventBuilder;
import org.robolectric.shadows.ShadowSensor;
import org.robolectric.shadows.ShadowSensorManager;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/**
 * The photo sphere viewer, drawn without a GPU: its patches and textures are drawn a pixel at a
 * time through its own matrix, and a picture whose every direction has a known colour says
 * whether the right colour lands in the right place.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = ShadowRecordingGles20.class)
public class PhotosphereViewTest {
    /** A landscape view: 70 degrees down, 86 across. */
    private static final int VIEW_WIDTH = 400;
    private static final int VIEW_HEIGHT = 300;

    private static final int RED = 0xFFFF0000;
    private static final int GREEN = 0xFF00FF00;
    private static final int BLUE = 0xFF0000FF;
    private static final int YELLOW = 0xFFFFFF00;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int MAGENTA = 0xFFFF00FF;
    private static final int CYAN = 0xFF00FFFF;

    private Activity mActivity;
    private FrameLayout mParent;
    private ExecutorService mWorker;
    private final Map<Integer, Bitmap> mTextures = new HashMap<>();
    private int mLoaded;
    private int mFailed;

    @Before
    public void setUp() {
        ShadowRecordingGles20.reset();
        mActivity = Robolectric.buildActivity(Activity.class).setup().get();
        mParent = new FrameLayout(mActivity);
        mActivity.setContentView(mParent);
        mWorker = Executors.newSingleThreadExecutor();
        mTextures.clear();
        mLoaded = 0;
        mFailed = 0;
    }

    @After
    public void tearDown() {
        mWorker.shutdownNow();
    }

    /** Each quarter of the compass, and each pole, shows where it should. */
    @Test
    public void everyDirectionShowsItsColour() {
        Bitmap picture = BitmapRegionSource.compass(2560, 1280, 2560, 1280, 0, 0);
        PhotosphereView view = show(new BitmapRegionSource(picture), null);

        // No metadata: the whole sphere, opening at the picture's middle.
        assertEquals(0, view.getHeading(), 1e-3);
        assertEquals(0, view.getPitch(), 1e-3);
        assertEquals(RED, (int) render(view).colorAt(0, 0));

        view.lookAt(90, 0);
        assertEquals(GREEN, (int) render(view).colorAt(0, 0));
        view.lookAt(-90, 0);
        assertEquals(YELLOW, (int) render(view).colorAt(0, 0));
        view.lookAt(180, 0);
        assertEquals(BLUE, (int) render(view).colorAt(0, 0));
        view.lookAt(0, 80);
        assertEquals(WHITE, (int) render(view).colorAt(0, 0));
        view.lookAt(0, -80);
        assertEquals(MAGENTA, (int) render(view).colorAt(0, 0));
        // Straight up and down, where every heading meets.
        view.lookAt(37, 90);
        assertEquals(WHITE, (int) render(view).colorAt(0, 0));
        view.lookAt(37, -90);
        assertEquals(MAGENTA, (int) render(view).colorAt(0, 0));
    }

    /** A direction off the middle lands where the projection puts it, to within a degree. */
    @Test
    public void directionsLandWhereThePerspectivePutsThem() {
        Bitmap picture = BitmapRegionSource.compass(2560, 1280, 2560, 1280, 0, 0);
        PhotosphereView view = show(new BitmapRegionSource(picture), null);
        SoftwareSphere sphere = render(view);
        double halfWidth = Math.tan(Math.toRadians(
                SphereMath.horizontalFov(view.getFov(), VIEW_WIDTH, VIEW_HEIGHT) / 2));

        // The cyan stripe runs from heading 28 to 32.
        assertEquals(CYAN, (int) sphere.colorAt(x(30, halfWidth), 0));
        assertEquals(CYAN, (int) sphere.colorAt(x(28.6, halfWidth), 0));
        assertEquals(CYAN, (int) sphere.colorAt(x(31.4, halfWidth), 0));
        assertEquals(RED, (int) sphere.colorAt(x(26.5, halfWidth), 0));
        assertEquals(RED, (int) sphere.colorAt(x(33.5, halfWidth), 0));
        // Left of the middle is west of north, still red until -45.
        assertEquals(RED, (int) sphere.colorAt(x(-40, halfWidth), 0));
    }

    /** Where the picture's ends meet, behind the middle, there is no seam. */
    @Test
    public void theEndsMeetWithoutASeam() {
        Bitmap picture = BitmapRegionSource.compass(2560, 1280, 2560, 1280, 0, 0);
        PhotosphereView view = show(new BitmapRegionSource(picture), null);
        view.lookAt(180, 0);
        SoftwareSphere sphere = render(view);
        for (float x = -0.2f; x <= 0.2f; x += 0.005f) {
            assertEquals("at " + x, BLUE, (int) sphere.colorAt(x, 0));
        }
        // The first column's texture borrows its left border from the picture's last column.
        boolean bordered = false;
        for (Bitmap texture : mTextures.values()) {
            int y = texture.getHeight() / 2;
            if (texture.getWidth() == 1026 && texture.getPixel(0, y) == BLUE
                    && texture.getPixel(2, y) == BLUE) {
                bordered = true;
            }
        }
        assertTrue(bordered);
    }

    /** A band around the horizon is shown where it was taken, and the rest left empty. */
    @Test
    public void aPartialSphereLeavesTheRestEmpty() {
        // Headings -90 to 90 and pitches 30 to -30 of a 2560 x 1280 sphere.
        Bitmap band = BitmapRegionSource.compass(1280, 427, 2560, 1280, 640, 427);
        GPano gpano = GPanoTest.parse(GPanoTest.xmp("CroppedAreaImageWidthPixels=\"1280\" "
                + "CroppedAreaImageHeightPixels=\"427\" FullPanoWidthPixels=\"2560\" "
                + "FullPanoHeightPixels=\"1280\" CroppedAreaLeftPixels=\"640\" "
                + "CroppedAreaTopPixels=\"427\""), 1280, 427);
        PhotosphereView view = show(new BitmapRegionSource(band), gpano);

        assertEquals(RED, (int) render(view).colorAt(0, 0));
        view.lookAt(80, 0);
        assertEquals(GREEN, (int) render(view).colorAt(0, 0));
        view.lookAt(-80, 20);
        assertEquals(YELLOW, (int) render(view).colorAt(0, 0));
        // Behind, and above or below the band: nothing.
        view.lookAt(180, 0);
        assertNull(render(view).colorAt(0, 0));
        view.lookAt(0, 45);
        assertNull(render(view).colorAt(0, 0));
        view.lookAt(0, -45);
        assertNull(render(view).colorAt(0, 0));
    }

    /** A pose turns the picture: its middle lands at its heading, and opens there. */
    @Test
    public void thePoseTurnsThePicture() {
        Bitmap picture = BitmapRegionSource.compass(2560, 1280, 2560, 1280, 0, 0);
        GPano gpano = GPanoTest.parse(GPanoTest.xmp("PoseHeadingDegrees=\"90\""), 2560, 1280);
        PhotosphereView view = show(new BitmapRegionSource(picture), gpano);

        assertEquals(90, view.getHeading(), 1e-3);
        assertEquals(RED, (int) render(view).colorAt(0, 0));
        // Due north is the picture's own west.
        view.lookAt(0, 0);
        assertEquals(YELLOW, (int) render(view).colorAt(0, 0));
    }

    /** The view opens the way the metadata says, and as wide. */
    @Test
    public void opensWhereTheMetadataSays() {
        Bitmap picture = BitmapRegionSource.compass(2560, 1280, 2560, 1280, 0, 0);
        GPano gpano = GPanoTest.parse(GPanoTest.xmp("InitialViewHeadingDegrees=\"100\" "
                + "InitialViewPitchDegrees=\"-20\" InitialHorizontalFOVDegrees=\"60\""),
                2560, 1280);
        PhotosphereView view = show(new BitmapRegionSource(picture), gpano);

        assertEquals(100, view.getHeading(), 1e-3);
        assertEquals(-20, view.getPitch(), 1e-3);
        assertEquals(60, SphereMath.horizontalFov(view.getFov(), VIEW_WIDTH, VIEW_HEIGHT), 1e-3);
        assertEquals(GREEN, (int) render(view).colorAt(0, 0));
    }

    /**
     * A 200 MP sphere is shrunk to the GPU budget, and while it loads the app never holds more
     * than one tile's decode and texture: under 6 MP.
     */
    @Test
    public void a200MegapixelSphereStaysWithinTheBudget() {
        FakeRegionSource source = new FakeRegionSource(20000, 10000, RED);
        PhotosphereView view = show(source, null);

        long texels = view.getRenderer().getTexels(true);
        assertTrue(texels <= SphereTiling.TEXEL_BUDGET);
        assertTrue(texels > SphereTiling.TEXEL_BUDGET * 0.95);
        // The low-resolution copy is gone once the tiles are in.
        assertEquals(texels, view.getRenderer().getTexels(false));
        int largestTexture = 0;
        for (Bitmap texture : mTextures.values()) {
            largestTexture = Math.max(largestTexture, texture.getWidth() * texture.getHeight());
        }
        long peak = source.peakLivePixels() + largestTexture;
        assertTrue("Held " + peak, peak < 6_000_000L);
        // And it holds none once loaded.
        assertEquals(0, source.livePixels());
        assertTrue(source.isClosed());
    }

    /** Drags turn the view, so what was under the finger stays under it. */
    @Test
    public void draggingTurnsTheView() {
        PhotosphereView view = show(new FakeRegionSource(400, 200, RED), null);
        double degreesPerPixel = SphereMath.horizontalFov(view.getFov(), VIEW_WIDTH,
                VIEW_HEIGHT) / VIEW_WIDTH;

        drag(view, 300, 150, 200, 170);
        assertEquals(100 * degreesPerPixel, view.getHeading(), 1e-3);
        assertEquals(20 * degreesPerPixel, view.getPitch(), 1e-3);

        // Pitch stops at the poles.
        drag(view, 200, 0, 200, 3000);
        assertEquals(90, view.getPitch(), 1e-3);
    }

    @Test
    public void pinchingZooms() {
        PhotosphereView view = show(new FakeRegionSource(400, 200, RED), null);
        double fov = view.getFov();
        // Fingers apart zoom in: a narrower view.
        LargeImageViewTest.pinch(view, 150, 300);
        assertTrue(view.getFov() < fov);
        LargeImageViewTest.pinch(view, 100, 3000);
        assertEquals(PhotosphereView.MIN_FOV, view.getFov(), 1e-6);
        // Together, out to the widest.
        LargeImageViewTest.pinch(view, 3000, 100);
        assertEquals(PhotosphereView.MAX_FOV, view.getFov(), 1e-6);
    }

    @Test
    public void flingingKeepsTurning() {
        PhotosphereView view = show(new FakeRegionSource(400, 200, RED), null);
        // A gentle flick leftward, 6 pixels every 10 milliseconds.
        flick(view, -6);
        double heading = view.getHeading();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        // It kept turning the way the finger went, eastward, and then stopped.
        double turned = SphereMath.wrapDegrees(view.getHeading() - heading);
        assertTrue("Turned " + turned, turned > 0.5);
        heading = view.getHeading();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertEquals(heading, view.getHeading(), 1e-9);

        // A hard flick, then a touch, which stops it where it is.
        flick(view, 60);
        long now = SystemClock.uptimeMillis();
        view.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 10, 10, 0));
        heading = view.getHeading();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        assertEquals(heading, view.getHeading(), 1e-9);
    }

    @Test
    public void aTapClicks() {
        PhotosphereView view = show(new FakeRegionSource(400, 200, RED), null);
        int[] clicks = {0};
        view.setOnClickListener(v -> clicks[0]++);
        long now = SystemClock.uptimeMillis();
        view.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 10, 10, 0));
        view.onTouchEvent(MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, 10, 10, 0));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertEquals(1, clicks[0]);
    }

    /**
     * With the gyroscope on, turning the phone turns the view, starting from wherever the view
     * looked; a drag still turns it on top; turned off, it stays where the phone left it.
     */
    @Test
    public void theGyroscopeTurnsTheView() {
        SensorManager sensors = (SensorManager) mActivity.getSystemService(Context.SENSOR_SERVICE);
        ShadowSensorManager shadowSensors = shadowOf(sensors);
        Sensor rotation = ShadowSensor.newInstance(Sensor.TYPE_GAME_ROTATION_VECTOR);
        shadowSensors.addSensor(rotation);
        PhotosphereView view = show(new FakeRegionSource(400, 200, RED), null);
        assertTrue(view.isGyroscopeAvailable());
        view.lookAt(10, 5);

        view.setGyroscopeEnabled(true);
        assertTrue(view.isGyroscopeEnabled());
        assertFalse(shadowSensors.getListeners().isEmpty());
        sense(shadowSensors, rotation, 100, 0);
        assertEquals(10, view.getHeading(), 1e-2);
        assertEquals(5, view.getPitch(), 1e-2);

        // Turned 30 degrees to the right.
        sense(shadowSensors, rotation, 130, 0);
        assertEquals(40, view.getHeading(), 1e-2);
        assertEquals(5, view.getPitch(), 1e-2);
        // Tipped up 20.
        sense(shadowSensors, rotation, 130, 20);
        assertEquals(40, view.getHeading(), 1e-2);
        assertEquals(25, view.getPitch(), 1e-2);

        // A drag adds to it.
        double degreesPerPixel = SphereMath.horizontalFov(view.getFov(), VIEW_WIDTH,
                VIEW_HEIGHT) / VIEW_WIDTH;
        drag(view, 300, 150, 200, 150);
        assertEquals(40 + 100 * degreesPerPixel, view.getHeading(), 1e-2);

        double heading = view.getHeading();
        view.setGyroscopeEnabled(false);
        assertFalse(view.isGyroscopeEnabled());
        assertTrue(shadowSensors.getListeners().isEmpty());
        assertEquals(heading, view.getHeading(), 1e-2);
        assertEquals(25, view.getPitch(), 1e-2);
        // Readings after that are ignored.
        sense(shadowSensors, rotation, 0, 0);
        assertEquals(heading, view.getHeading(), 1e-2);
    }

    /** The sensor is only listened to while the view is on screen. */
    @Test
    public void theGyroscopeStopsWhenDetached() {
        SensorManager sensors = (SensorManager) mActivity.getSystemService(Context.SENSOR_SERVICE);
        ShadowSensorManager shadowSensors = shadowOf(sensors);
        shadowSensors.addSensor(ShadowSensor.newInstance(Sensor.TYPE_GAME_ROTATION_VECTOR));
        PhotosphereView view = show(new FakeRegionSource(400, 200, RED), null);
        view.setGyroscopeEnabled(true);
        assertFalse(shadowSensors.getListeners().isEmpty());

        mParent.removeView(view);
        assertTrue(shadowSensors.getListeners().isEmpty());
        // Enabling it again while it is off screen does nothing until it is back.
        view.setGyroscopeEnabled(true);
        assertTrue(shadowSensors.getListeners().isEmpty());
    }

    /** Without a rotation sensor there is no gyroscope to follow. */
    @Test
    public void noSensorNoGyroscope() {
        PhotosphereView view = new PhotosphereView(mActivity);
        assertFalse(view.isGyroscopeAvailable());
        view.setGyroscopeEnabled(true);
        assertTrue(view.isGyroscopeEnabled());
        view.setGyroscopeEnabled(true);
        assertTrue(view.isGyroscopeEnabled());
    }

    /** A picture that cannot be opened or decoded is reported, once. */
    @Test
    public void reportsAFailure() {
        PhotosphereView view = attach(new PhotosphereView(mActivity));
        view.setOpener((context, location) -> {
            throw new IOException("Not a picture");
        });
        view.setFile(new File("missing.jpg"));
        pump(view, () -> mFailed > 0);
        assertEquals(1, mFailed);

        FakeRegionSource broken = new FakeRegionSource(400, 200, RED);
        broken.setFailing(true);
        view.setOpener((context, location) ->
                new ImageSources.Opened(broken, ExifInterface.ORIENTATION_NORMAL, null));
        view.setFile(new File("broken.jpg"));
        pump(view, () -> mFailed > 1);
        assertEquals(2, mFailed);
        assertEquals(0, mLoaded);
    }

    /** A new GL context, after the old one was lost, is filled again from the file. */
    @Test
    public void aNewContextIsFilledAgain() {
        Bitmap picture = BitmapRegionSource.compass(2560, 1280, 2560, 1280, 0, 0);
        PhotosphereView view = show(new BitmapRegionSource(picture), null);
        int patches = view.getRenderer().getPatchCount();

        view.getRenderer().onSurfaceCreated(null, null);
        assertEquals(0, view.getRenderer().getPatchCount());
        pump(view, () -> view.getRenderer().getPatchCount() == patches
                && view.getRenderer().getTexels(true) == view.getRenderer().getTexels(false));
        assertEquals(RED, (int) render(view).colorAt(0, 0));
        // Still reported only once.
        assertEquals(1, mLoaded);
    }

    /** Clearing the picture empties the sphere. */
    @Test
    public void nothingShowsNothing() {
        PhotosphereView view = show(new FakeRegionSource(400, 200, RED), null);
        view.setFile(null);
        view.getRenderer().onDrawFrame(null);
        assertEquals(0, view.getRenderer().getPatchCount());
        for (int texture : mTextures.keySet()) {
            assertTrue(ShadowRecordingGles20.isTextureDeleted(texture));
        }
        view.setFile(null);
    }

    /** Opens a picture in a view on screen, and waits for every tile to be uploaded. */
    private PhotosphereView show(RegionSource source, GPano gpano) {
        PhotosphereView view = attach(new PhotosphereView(mActivity));
        view.setOpener((context, location) ->
                new ImageSources.Opened(source, ExifInterface.ORIENTATION_NORMAL, gpano));
        view.setFile(new File("sphere.jpg"));
        boolean wraps = gpano == null || gpano.isFullCircle();
        int tiles = SphereTiling.plan(source.getWidth(), source.getHeight(), 0, wraps)
                .tiles.size();
        pump(view, () -> view.getRenderer().getPatchCount() == tiles
                && view.getRenderer().getTexels(true) == view.getRenderer().getTexels(false));
        assertEquals(1, mLoaded);
        return view;
    }

    private PhotosphereView attach(PhotosphereView view) {
        view.setExecutor(mWorker);
        view.setImageLoadListener(new ImageLoadListener() {
            @Override
            public void onImageLoaded() {
                mLoaded++;
            }

            @Override
            public void onImageFailed() {
                mFailed++;
            }
        });
        view.getRenderer().setUploader((texture, bitmap) ->
                mTextures.put(texture, bitmap.copy(Bitmap.Config.ARGB_8888, false)));
        mParent.addView(view, new FrameLayout.LayoutParams(VIEW_WIDTH, VIEW_HEIGHT));
        view.measure(exactly(VIEW_WIDTH), exactly(VIEW_HEIGHT));
        view.layout(0, 0, VIEW_WIDTH, VIEW_HEIGHT);
        view.getRenderer().onSurfaceCreated(null, null);
        view.getRenderer().onSurfaceChanged(null, VIEW_WIDTH, VIEW_HEIGHT);
        idle();
        return view;
    }

    /** Draws frames and runs the main thread until something is true. */
    private static void pump(PhotosphereView view, BooleanSupplier done) {
        long deadline = System.currentTimeMillis() + 60_000;
        while (!done.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("Timed out");
            }
            view.getRenderer().onDrawFrame(null);
            idle();
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    private SoftwareSphere render(PhotosphereView view) {
        return new SoftwareSphere(view.getRenderer(), mTextures);
    }

    /** Where a heading on the horizon lands across the screen, looking north. */
    private static float x(double heading, double halfWidth) {
        return (float) (Math.tan(Math.toRadians(heading)) / halfWidth);
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static int exactly(int size) {
        return View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY);
    }

    private static void drag(View view, float fromX, float fromY, float toX, float toY) {
        long now = SystemClock.uptimeMillis();
        view.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, fromX, fromY, 0));
        // A first move past the touch slop, so the rest is followed exactly.
        view.onTouchEvent(MotionEvent.obtain(now, now + 100, MotionEvent.ACTION_MOVE,
                fromX + (toX - fromX) / 2, fromY + (toY - fromY) / 2, 0));
        view.onTouchEvent(MotionEvent.obtain(now, now + 1000, MotionEvent.ACTION_MOVE, toX, toY,
                0));
        view.onTouchEvent(MotionEvent.obtain(now, now + 2000, MotionEvent.ACTION_UP, toX, toY,
                0));
    }

    /** A quick drag across the middle that lifts while still moving. */
    private static void flick(View view, int step) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        long now = SystemClock.uptimeMillis();
        view.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 200, 150, 0));
        for (int i = 1; i <= 4; i++) {
            view.onTouchEvent(MotionEvent.obtain(now, now + i * 10L, MotionEvent.ACTION_MOVE,
                    200 + i * step, 150, 0));
        }
        view.onTouchEvent(MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP,
                200 + 5 * step, 150, 0));
    }

    /** Reports the phone upright, turned to a heading and tipped up to a pitch. */
    private static void sense(ShadowSensorManager sensors, Sensor sensor, double heading,
            double pitch) {
        float[] q = quaternion(SphereMath.lookRotation(heading, pitch));
        sensors.sendSensorEventToListeners(SensorEventBuilder.newBuilder()
                .setSensor(sensor)
                .setTimestamp(SystemClock.elapsedRealtimeNanos())
                .setValues(new float[] {q[0], q[1], q[2], q[3], 0})
                .build());
        idle();
    }

    /** The unit quaternion {x, y, z, w} of a rotation, as a rotation vector sensor reports it. */
    private static float[] quaternion(float[] r) {
        double trace = r[0] + r[4] + r[8];
        double x;
        double y;
        double z;
        double w;
        if (trace > 0) {
            double s = Math.sqrt(trace + 1) * 2;
            w = s / 4;
            x = (r[7] - r[5]) / s;
            y = (r[2] - r[6]) / s;
            z = (r[3] - r[1]) / s;
        } else if (r[0] > r[4] && r[0] > r[8]) {
            double s = Math.sqrt(1 + r[0] - r[4] - r[8]) * 2;
            w = (r[7] - r[5]) / s;
            x = s / 4;
            y = (r[1] + r[3]) / s;
            z = (r[2] + r[6]) / s;
        } else if (r[4] > r[8]) {
            double s = Math.sqrt(1 + r[4] - r[0] - r[8]) * 2;
            w = (r[2] - r[6]) / s;
            x = (r[1] + r[3]) / s;
            y = s / 4;
            z = (r[5] + r[7]) / s;
        } else {
            double s = Math.sqrt(1 + r[8] - r[0] - r[4]) * 2;
            w = (r[3] - r[1]) / s;
            x = (r[2] + r[6]) / s;
            y = (r[5] + r[7]) / s;
            z = s / 4;
        }
        // The sensor reports the half with w at least 0.
        if (w < 0) {
            x = -x;
            y = -y;
            z = -z;
            w = -w;
        }
        return new float[] {(float) x, (float) y, (float) z, (float) w};
    }
}
