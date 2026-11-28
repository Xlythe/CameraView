package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Matrix;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.xlythe.view.camera.shadow.ShadowAvailableTextureView;
import com.xlythe.view.camera.shadow.ShadowInjectableImageReader;
import com.xlythe.view.camera.shadow.ShadowSizedSurfaceView;
import com.xlythe.view.camera.v2.FakeCamera2;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The HDR surface, from the side of the view it stands in for, here CameraView's preview: when it
 * is offered, how it takes over from the TextureView, what it does to the window, and how it
 * gives everything back.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37, shadows = {ShadowSizedSurfaceView.class, ShadowAvailableTextureView.class,
        ShadowInjectableImageReader.class})
public class HdrSurfaceTest {
    /** The width of a 16:9 stream, the shape a camera hands back for a 16:9 preview. */
    private static final int STREAM_WIDTH = 1920;
    /** The height of the same stream. */
    private static final int STREAM_HEIGHT = 1080;

    private Activity activity;
    private CameraView cameraView;
    private HdrSurface hdrSurface;

    @Before
    public void setUp() {
        ShadowInjectableImageReader.reset();
        ShadowSizedSurfaceView.reset();
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        cameraView = new CameraView(activity);
        activity.setContentView(cameraView);
        idle();
        hdrSurface = cameraView.getHdrSurface();
    }

    // ---------- When it is offered ----------

    @Test
    public void anHdrDisplayCanShowAnHdrPreview() {
        makeTheDisplayHdr();
        assertTrue(hdrSurface.isAvailable());
    }

    @Test
    public void anSdrDisplayCannot() {
        assertFalse(hdrSurface.isAvailable());
    }

    @Test
    public void norCanAViewThatIsNotOnScreen() {
        makeTheDisplayHdr();
        activity.setContentView(new FrameLayout(activity));
        assertFalse(hdrSurface.isAvailable());
    }

    /** Below Android 8.0 a window has no HDR colour mode, whatever the display can do. */
    @Test
    @Config(sdk = 25)
    public void norCanAReleaseWithoutAnHdrWindow() {
        assertFalse(hdrSurface.isAvailable());
    }

    // ---------- Taking over from the TextureView ----------

    @Test
    public void theSurfaceIsHandedOverOnceItIsTheStreamsSize() {
        RecordingListener listener = new RecordingListener();
        hdrSurface.acquire(STREAM_WIDTH, STREAM_HEIGHT, listener);

        SurfaceView surfaceView = hdrSurface.getSurfaceView();
        assertNotNull(surfaceView);
        assertSame("beneath the TextureView", surfaceView, cameraView.getChildAt(0));
        assertEquals(View.VISIBLE, surfaceView.getVisibility());
        // Not yet: the last SDR frame stays up until the surface exists.
        assertNull(listener.surface);
        assertEquals(View.VISIBLE, cameraView.asTextureView().getVisibility());
        assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, window().getColorMode());

        idle();
        assertNotNull(listener.surface);
        assertTrue(listener.surface.isValid());
        assertEquals(View.INVISIBLE, cameraView.asTextureView().getVisibility());
        assertEquals(ActivityInfo.COLOR_MODE_HDR, window().getColorMode());
        assertTrue(hdrSurface.isShowing());
    }

    /**
     * A rebind that keeps the stream's size gets the surface that is already there, on the next
     * pass, once it holds its lease.
     */
    @Test
    public void aStreamOfTheSameSizeIsHandedTheSurfaceOnTheNextPass() {
        HdrSurface.Lease first = hdrSurface.acquire(STREAM_WIDTH, STREAM_HEIGHT, new RecordingListener());
        idle();

        RecordingListener second = new RecordingListener();
        hdrSurface.acquire(STREAM_WIDTH, STREAM_HEIGHT, second);
        assertNull("not while acquire is still returning", second.surface);
        idle();
        assertNotNull(second.surface);
        first.release();
        settle();
        assertTrue(hdrSurface.isShowing());
    }

    /** A writer that scales to any size, such as a decoder, takes the surface as it comes. */
    @Test
    public void aStreamOfAnySizeTakesTheSurfaceAsItIs() {
        RecordingListener listener = new RecordingListener();
        hdrSurface.acquire(0, 0, listener);
        idle();
        assertNotNull(listener.surface);
        assertTrue(hdrSurface.isShowing());
    }

    @Test
    public void aStreamOfAnotherSizeWaitsForTheSurfaceToBeResized() {
        hdrSurface.acquire(STREAM_WIDTH, STREAM_HEIGHT, new RecordingListener());
        idle();

        RecordingListener smaller = new RecordingListener();
        hdrSurface.acquire(1280, 720, smaller);
        assertNull(smaller.surface);
        idle();
        assertNotNull(smaller.surface);
    }

    @Test
    public void onlyTheNewestStreamPositionsTheSurface() {
        HdrSurface.Lease first = hdrSurface.acquire(STREAM_WIDTH, STREAM_HEIGHT, new RecordingListener());
        idle();
        first.setTransform(scaleAndMove(2f, 1.5f, -10f, 20f));
        SurfaceView surfaceView = hdrSurface.getSurfaceView();
        assertEquals(0f, surfaceView.getPivotX(), 0f);
        assertEquals(0f, surfaceView.getPivotY(), 0f);
        assertEquals(2f, surfaceView.getScaleX(), 0f);
        assertEquals(1.5f, surfaceView.getScaleY(), 0f);
        assertEquals(-10f, surfaceView.getTranslationX(), 0f);
        assertEquals(20f, surfaceView.getTranslationY(), 0f);

        HdrSurface.Lease second = hdrSurface.acquire(STREAM_WIDTH, STREAM_HEIGHT, new RecordingListener());
        first.setTransform(scaleAndMove(3f, 3f, 0f, 0f));
        assertEquals("an older stream that is closing decides nothing",
                2f, surfaceView.getScaleX(), 0f);
        second.setTransform(scaleAndMove(1f, 1.25f, 0f, -135f));
        assertEquals(1.25f, surfaceView.getScaleY(), 0f);
        assertEquals(-135f, surfaceView.getTranslationY(), 0f);
    }

    // ---------- Giving it back ----------

    /**
     * An SDR stream takes over at once, and the SurfaceView goes once the HDR stream that may
     * still be writing into it lets go.
     */
    @Test
    public void anSdrStreamPutsTheTextureViewAndTheWindowBack() {
        HdrSurface.Lease lease = hdrSurface.acquire(STREAM_WIDTH, STREAM_HEIGHT, new RecordingListener());
        idle();

        hdrSurface.showSdr();
        assertEquals(View.VISIBLE, cameraView.asTextureView().getVisibility());
        assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, window().getColorMode());
        assertEquals("still held", View.VISIBLE, hdrSurface.getSurfaceView().getVisibility());

        lease.release();
        settle();
        assertEquals(View.GONE, hdrSurface.getSurfaceView().getVisibility());
        assertFalse(hdrSurface.isShowing());
    }

    @Test
    public void anHdrStreamThatStopsWithNothingAfterItPutsEverythingBack() {
        HdrSurface.Lease lease = hdrSurface.acquire(STREAM_WIDTH, STREAM_HEIGHT, new RecordingListener());
        idle();
        lease.setTransform(scaleAndMove(2f, 2f, 5f, 5f));

        lease.release();
        lease.release();
        settle();
        assertEquals(View.VISIBLE, cameraView.asTextureView().getVisibility());
        assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, window().getColorMode());
        SurfaceView surfaceView = hdrSurface.getSurfaceView();
        assertEquals(View.GONE, surfaceView.getVisibility());
        assertEquals(1f, surfaceView.getScaleX(), 0f);
        assertEquals(0f, surfaceView.getTranslationX(), 0f);
    }

    /**
     * A rebind can let go of the old stream a moment before it asks for the new one. The window
     * stays in HDR across the gap rather than dropping out and back in.
     */
    @Test
    public void aRebindThatLetsGoFirstStaysHdr() {
        HdrSurface.Lease first = hdrSurface.acquire(STREAM_WIDTH, STREAM_HEIGHT, new RecordingListener());
        idle();

        first.release();
        idle();
        hdrSurface.acquire(STREAM_WIDTH, STREAM_HEIGHT, new RecordingListener());
        settle();
        assertEquals(ActivityInfo.COLOR_MODE_HDR, window().getColorMode());
        assertTrue(hdrSurface.isShowing());
    }

    @Test
    public void theAppsOwnColourModeComesBack() {
        window().setColorMode(ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT);
        HdrSurface.Lease lease = hdrSurface.acquire(STREAM_WIDTH, STREAM_HEIGHT, new RecordingListener());
        idle();
        assertEquals(ActivityInfo.COLOR_MODE_HDR, window().getColorMode());

        lease.release();
        settle();
        assertEquals(ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT, window().getColorMode());
    }

    /** The window's colour mode is shared with whatever else in it shows HDR. */
    @Test
    public void theWindowStaysHdrWhileSomethingElseHoldsIt() {
        HdrColorMode.acquire(window());
        HdrSurface.Lease lease = hdrSurface.acquire(STREAM_WIDTH, STREAM_HEIGHT, new RecordingListener());
        idle();
        assertEquals(2, HdrColorMode.holds(window()));

        lease.release();
        settle();
        assertEquals(ActivityInfo.COLOR_MODE_HDR, window().getColorMode());
        HdrColorMode.release(window());
        assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, window().getColorMode());
    }

    /** Outside of an activity there is no window to switch, and the surface still works. */
    @Test
    public void aViewWithoutAnActivityStillGetsTheSurface() {
        CameraView detached = new CameraView(activity.getApplicationContext());
        activity.setContentView(detached);
        RecordingListener listener = new RecordingListener();
        detached.getHdrSurface().acquire(STREAM_WIDTH, STREAM_HEIGHT, listener);
        idle();
        assertNotNull(listener.surface);
        assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, window().getColorMode());
        detached.getHdrSurface().showSdr();
    }

    // ---------- Losing it ----------

    @Test
    public void aSurfaceDestroyedUnderAStreamIsReported() {
        RecordingListener held = new RecordingListener();
        hdrSurface.acquire(STREAM_WIDTH, STREAM_HEIGHT, held);
        idle();
        RecordingListener waiting = new RecordingListener();
        hdrSurface.acquire(1280, 720, waiting);

        shadowOfSurfaceView().destroySurface();
        assertEquals(1, held.losses);
        assertEquals("a stream that was never given the surface lost nothing", 0, waiting.losses);
        assertNull(hdrSurface.getSurfaceView().getHolder().getSurface());

        // And the stream that is waiting gets the new surface once there is one.
        shadowOfSurfaceView().recreateSurface();
        idle();
        assertNotNull(waiting.surface);
    }

    /** A stream that lost the surface is handed the new one once the window shows again. */
    @Test
    public void aStreamIsHandedTheSurfaceAgainAfterLosingIt() {
        RecordingListener listener = new RecordingListener();
        hdrSurface.acquire(STREAM_WIDTH, STREAM_HEIGHT, listener);
        idle();
        Surface first = listener.surface;

        shadowOfSurfaceView().destroySurface();
        assertEquals(1, listener.losses);
        shadowOfSurfaceView().recreateSurface();
        idle();
        assertEquals(2, listener.surfaces.size());
        assertTrue(listener.surface != first && listener.surface.isValid());
    }

    /**
     * Showing the SurfaceView again creates its surface at once, inside acquire. The stream is
     * still handed it only once acquire has returned its lease, as a camera's preview, which
     * keeps the lease to tell its own surface apart, needs.
     */
    @Test
    public void aSurfaceShownAgainIsHandedOverOnceTheLeaseIsHeld() {
        hdrSurface.acquire(STREAM_WIDTH, STREAM_HEIGHT, new RecordingListener()).release();
        idle();
        settle();
        assertEquals(View.GONE, hdrSurface.getSurfaceView().getVisibility());
        assertNull(hdrSurface.getSurfaceView().getHolder().getSurface());

        HdrSurface.Lease[] held = new HdrSurface.Lease[1];
        List<HdrSurface.Lease> heldWhenReady = new ArrayList<>();
        held[0] = hdrSurface.acquire(STREAM_WIDTH, STREAM_HEIGHT, new HdrSurface.Listener() {
            @Override
            public void onSurfaceReady(@NonNull Surface surface) {
                heldWhenReady.add(held[0]);
            }

            @Override
            public void onSurfaceLost() {}
        });
        assertNotNull("showing it again created the surface at once",
                hdrSurface.getSurfaceView().getHolder().getSurface());
        assertTrue("handed over inside acquire", heldWhenReady.isEmpty());
        idle();
        assertEquals(1, heldWhenReady.size());
        assertSame(held[0], heldWhenReady.get(0));
        assertTrue(hdrSurface.isShowing());
    }

    /** Hiding the SurfaceView once nothing holds it is not a loss anyone is told about. */
    @Test
    public void aSurfaceGivenBackIsNotReportedLost() {
        RecordingListener listener = new RecordingListener();
        HdrSurface.Lease lease = hdrSurface.acquire(STREAM_WIDTH, STREAM_HEIGHT, listener);
        idle();
        lease.release();
        settle();
        shadowOfSurfaceView().destroySurface();
        assertEquals(0, listener.losses);
    }

    // ---------- The view's own lifecycle ----------

    @Test
    public void leavingTheWindowPutsItBack() {
        HdrSurface.Lease lease = hdrSurface.acquire(STREAM_WIDTH, STREAM_HEIGHT, new RecordingListener());
        idle();

        activity.setContentView(new FrameLayout(activity));
        assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, window().getColorMode());
        assertEquals(View.VISIBLE, cameraView.asTextureView().getVisibility());
        assertEquals(View.GONE, hdrSurface.getSurfaceView().getVisibility());

        // A stream that finishes afterwards changes nothing, and has nothing left to position.
        lease.setTransform(scaleAndMove(2f, 2f, 0f, 0f));
        lease.release();
        settle();
        assertEquals(1f, hdrSurface.getSurfaceView().getScaleX(), 0f);
    }

    @Test
    public void closingTheCameraPutsItBack() {
        Shadows.shadowOf(activity.getApplication()).grantPermissions(Manifest.permission.CAMERA);
        FakeCamera2.addBackAndFrontCameras(activity);
        cameraView.layout(0, 0, 1080, 1920);
        cameraView.open();
        idle();
        assertTrue(cameraView.isOpen());
        hdrSurface.acquire(STREAM_WIDTH, STREAM_HEIGHT, new RecordingListener());
        idle();
        assertTrue(hdrSurface.isShowing());

        cameraView.close();
        idle();
        assertFalse(hdrSurface.isShowing());
        assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, window().getColorMode());
        assertEquals("the replacement TextureView is the one on screen",
                View.VISIBLE, cameraView.asTextureView().getVisibility());
        assertEquals(View.GONE, hdrSurface.getSurfaceView().getVisibility());
    }

    /** The TextureView keeps its place in front, and the reticle stays last. */
    @Test
    public void theSurfaceViewIsAddedBeneathEverything() {
        int children = cameraView.getChildCount();
        View last = cameraView.getChildAt(children - 1);
        hdrSurface.acquire(STREAM_WIDTH, STREAM_HEIGHT, new RecordingListener());
        assertEquals(children + 1, cameraView.getChildCount());
        assertSame(last, cameraView.getChildAt(children));
        ViewGroup.LayoutParams params = hdrSurface.getSurfaceView().getLayoutParams();
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, params.width);
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, params.height);

        // Created once, however many streams come and go.
        hdrSurface.showSdr();
        hdrSurface.acquire(STREAM_WIDTH, STREAM_HEIGHT, new RecordingListener());
        assertEquals(children + 1, cameraView.getChildCount());
    }

    private Window window() {
        return activity.getWindow();
    }

    private ShadowSizedSurfaceView shadowOfSurfaceView() {
        return Shadow.extract(hdrSurface.getSurfaceView());
    }

    private void makeTheDisplayHdr() {
        Display display = cameraView.getDisplay();
        Shadows.shadowOf(display).setDisplayHdrCapabilities(display.getDisplayId(),
                1000f, 500f, 0.0005f, Display.HdrCapabilities.HDR_TYPE_HLG,
                Display.HdrCapabilities.HDR_TYPE_HDR10);
    }

    private static Matrix scaleAndMove(float scaleX, float scaleY, float dx, float dy) {
        Matrix matrix = new Matrix();
        matrix.setScale(scaleX, scaleY);
        matrix.postTranslate(dx, dy);
        return matrix;
    }

    private static void idle() {
        ShadowLooper.shadowMainLooper().idle();
    }

    /** Runs the main looper past the pause a released surface waits before going. */
    private static void settle() {
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(1));
    }

    /** Remembers what one stream was told. */
    private static final class RecordingListener implements HdrSurface.Listener {
        /** The surface handed over, or null before it is. */
        Surface surface;
        /** How many times the surface was lost under the stream. */
        int losses;
        /** Every surface handed over, to catch one handed over twice. */
        final List<Surface> surfaces = new ArrayList<>();

        @Override
        public void onSurfaceReady(@NonNull Surface surface) {
            this.surface = surface;
            surfaces.add(surface);
            assertEquals("handed over once per surface", losses + 1, surfaces.size());
        }

        @Override
        public void onSurfaceLost() {
            losses++;
        }
    }
}
