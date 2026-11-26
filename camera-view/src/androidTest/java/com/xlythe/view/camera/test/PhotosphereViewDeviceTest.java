package com.xlythe.view.camera.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.PixelCopy;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.xlythe.view.camera.pano.GPanoBounds;
import com.xlythe.view.camera.pano.PhotosphereView;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Whether the photosphere viewer samples the sphere where it should.
 *
 * <p>The projection here lives in a fragment shader, which means there is no way to check it
 * except on a GPU: a sign error, a swapped axis or a half turn out all compile and all draw
 * something. So the test paints a picture whose colour says which direction it is - the western
 * half red, the eastern half blue, the sky green - points the view at each in turn, and reads
 * the pixels back off the surface.</p>
 *
 * <p>Needs API 24 for {@link PixelCopy}, which is the only way to read a surface a GPU drew
 * into.</p>
 */
@RunWith(AndroidJUnit4.class)
public final class PhotosphereViewDeviceTest {
    private static final String TAG = "PhotosphereViewTest";
    private static final long TIMEOUT_SECONDS = 20;

    /** How far a channel may stray, which is filtering across a boundary and nothing more. */
    private static final int TOLERANCE = 40;

    @Before
    public void needsPixelCopy() {
        Assume.assumeTrue("reading back a GPU surface needs API 24",
                Build.VERSION.SDK_INT >= 24);
    }

    /**
     * An equirectangular picture whose colour says which way you are looking.
     *
     * <p>West red, east blue, and the top band green, so that one pixel read back says both
     * whether the longitude is right and whether the latitude is the right way up.</p>
     */
    @NonNull
    private static Bitmap directionalSphere() {
        int width = 512;
        int height = 256;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        // The canvas runs from a yaw of -180 at the left to 180 at the right, so the left half
        // is the western side of the sphere.
        paint.setColor(Color.RED);
        canvas.drawRect(0, 0, width / 2f, height, paint);
        paint.setColor(Color.BLUE);
        canvas.drawRect(width / 2f, 0, width, height, paint);
        // And from a pitch of 90 at the top, so the top band is the sky.
        paint.setColor(Color.GREEN);
        canvas.drawRect(0, 0, width, height / 8f, paint);
        return bitmap;
    }

    /** Puts a viewer on screen showing that picture, and returns it. */
    private static PhotosphereView show(@NonNull ActivityScenario<CameraTestActivity> scenario,
            @NonNull Bitmap panorama) throws InterruptedException {
        AtomicReference<PhotosphereView> held = new AtomicReference<>();
        CountDownLatch laidOut = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            PhotosphereView view = new PhotosphereView(activity);
            ViewGroup content = activity.findViewById(android.R.id.content);
            content.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            view.setPanorama(panorama, GPanoBounds.forFullSphere(
                    panorama.getWidth(), panorama.getHeight()));
            view.post(laidOut::countDown);
            held.set(view);
        });
        assertTrue("the viewer was never laid out",
                laidOut.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        PhotosphereView view = held.get();
        assertNotNull(view);
        return view;
    }

    /** The colour at the middle of the viewer, read back off the surface it drew into. */
    private static int centreColour(@NonNull PhotosphereView view) throws InterruptedException {
        // Rendered on demand, so a frame has to be asked for and then waited for: a read taken
        // before the draw lands gets whatever the surface held before.
        CountDownLatch drawn = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            view.requestRender();
            view.postDelayed(drawn::countDown, 500);
        });
        assertTrue(drawn.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        Bitmap shot = Bitmap.createBitmap(view.getWidth(), view.getHeight(),
                Bitmap.Config.ARGB_8888);
        CountDownLatch copied = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger(PixelCopy.ERROR_UNKNOWN);
        new Handler(Looper.getMainLooper()).post(() -> PixelCopy.request(view, shot, outcome -> {
            result.set(outcome);
            copied.countDown();
        }, new Handler(Looper.getMainLooper())));
        assertTrue("the surface was never read back",
                copied.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        Assume.assumeTrue("this device would not let the surface be read back, result "
                + result.get(), result.get() == PixelCopy.SUCCESS);
        int colour = shot.getPixel(shot.getWidth() / 2, shot.getHeight() / 2);
        shot.recycle();
        return colour;
    }

    private static void assertColour(String what, int expected, int actual) {
        assertEquals(what + " red", Color.red(expected), Color.red(actual), TOLERANCE);
        assertEquals(what + " green", Color.green(expected), Color.green(actual), TOLERANCE);
        assertEquals(what + " blue", Color.blue(expected), Color.blue(actual), TOLERANCE);
    }

    private static void assertEquals(String what, int expected, int actual, int tolerance) {
        assertTrue(what + ": expected " + expected + " but was " + actual,
                Math.abs(expected - actual) <= tolerance);
    }

    /**
     * The claim: looking a given way shows what is that way round the sphere.
     *
     * <p>One test for all three directions because putting a viewer on screen is most of the
     * runtime, and each look is one more assertion against the same surface.</p>
     */
    @Test
    public void theViewerSamplesTheDirectionItIsLookingIn() throws Exception {
        Bitmap panorama = directionalSphere();
        try (ActivityScenario<CameraTestActivity> scenario =
                     ActivityScenario.launch(CameraTestActivity.class)) {
            PhotosphereView view = show(scenario, panorama);

            // Due west, which is the middle of the red half.
            lookAt(view, -90f, 0f);
            int west = centreColour(view);
            Log.i(TAG, "west " + Integer.toHexString(west));
            assertColour("looking west", Color.RED, west);

            // Due east, the middle of the blue half.
            lookAt(view, 90f, 0f);
            int east = centreColour(view);
            Log.i(TAG, "east " + Integer.toHexString(east));
            assertColour("looking east", Color.BLUE, east);

            // Straight up, which is the green band whichever way round you are facing. This is
            // the one that fails if the latitude is upside down - a canvas counts downwards and
            // the sky is up.
            lookAt(view, -90f, 80f);
            int up = centreColour(view);
            Log.i(TAG, "up " + Integer.toHexString(up));
            assertColour("looking up", Color.GREEN, up);
        } finally {
            panorama.recycle();
        }
    }

    /**
     * A band shows as a band, with nothing drawn where the picture does not reach.
     *
     * <p>What the bounds are for. A quarter-turn sweep shown without them is stretched over the
     * whole sphere, and the way to tell is that there is no empty part - so the assertion is
     * that looking away from the band shows nothing.</p>
     */
    @Test
    public void lookingAwayFromABandShowsNothing() throws Exception {
        Bitmap panorama = directionalSphere();
        try (ActivityScenario<CameraTestActivity> scenario =
                     ActivityScenario.launch(CameraTestActivity.class)) {
            AtomicReference<PhotosphereView> held = new AtomicReference<>();
            CountDownLatch laidOut = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                PhotosphereView view = new PhotosphereView(activity);
                ViewGroup content = activity.findViewById(android.R.id.content);
                content.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                // The picture is a quarter of the canvas wide, sitting a quarter in - so it
                // covers a quarter turn starting a quarter turn west of the back.
                view.setPanorama(panorama, GPanoBounds.fromXmp(
                        "<rdf:Description GPano:ProjectionType=\"equirectangular\""
                                + " GPano:FullPanoWidthPixels=\"2048\""
                                + " GPano:FullPanoHeightPixels=\"1024\""
                                + " GPano:CroppedAreaImageWidthPixels=\"512\""
                                + " GPano:CroppedAreaImageHeightPixels=\"256\""
                                + " GPano:CroppedAreaLeftPixels=\"512\""
                                + " GPano:CroppedAreaTopPixels=\"384\" />"));
                view.post(laidOut::countDown);
                held.set(view);
            });
            assertTrue(laidOut.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            PhotosphereView view = held.get();
            assertNotNull(view);

            // Behind the band, which the picture says nothing about.
            lookAt(view, 120f, 0f);
            int behind = centreColour(view);
            Log.i(TAG, "behind the band " + Integer.toHexString(behind));
            assertColour("looking away from a band", Color.BLACK, behind);
        } finally {
            panorama.recycle();
        }
    }

    /** Looking past the pole is held short of it, so a drag cannot spin the view. */
    @Test
    public void theViewCannotBeTippedPastThePole() throws Exception {
        Bitmap panorama = directionalSphere();
        try (ActivityScenario<CameraTestActivity> scenario =
                     ActivityScenario.launch(CameraTestActivity.class)) {
            PhotosphereView view = show(scenario, panorama);

            lookAt(view, 0f, 200f);

            AtomicReference<Float> pitch = new AtomicReference<>();
            scenario.onActivity(ignored -> pitch.set(view.getPitchDegrees()));
            assertTrue("a pitch of " + pitch.get() + " is past the pole",
                    pitch.get() <= 89f && pitch.get() >= -89f);
        } finally {
            panorama.recycle();
        }
    }

    private static void lookAt(@NonNull PhotosphereView view, float yaw, float pitch)
            throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            view.lookAt(yaw, pitch);
            done.countDown();
        });
        assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }
}
