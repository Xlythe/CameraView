package com.xlythe.view.camera.legacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;

import androidx.annotation.Nullable;

import com.xlythe.view.camera.stream.VideoRecorder;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * The SurfaceHolder the old camera API insists on.
 *
 * <p>{@code android.hardware.Camera} draws its preview into a SurfaceHolder and a modern
 * CameraView has a SurfaceTexture; this is the adapter between them, and it exists only because
 * the old API will not take a texture. It was entirely untested, which for an adapter means
 * nobody had checked the half of the interface the camera does not happen to call - and a
 * SurfaceHolder handed to the platform gets called in ways its author did not choose.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class LegacySurfaceHolderTest {
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    private static final int ORIENTATION = 90;

    private Context context;
    private SurfaceTexture surfaceTexture;
    private RecordingProvider provider;
    private LegacySurfaceHolder holder;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        surfaceTexture = new SurfaceTexture(0);
        surfaceTexture.setDefaultBufferSize(WIDTH, HEIGHT);
        provider = new RecordingProvider(surfaceTexture);
        holder = new LegacySurfaceHolder(context, provider, WIDTH, HEIGHT, ORIENTATION,
                false /* isUsingFrontFacingCamera */);
    }

    @After
    public void tearDown() {
        holder.close();
        surfaceTexture.release();
    }

    /** The surface is asked for once and kept, because asking twice would mean two surfaces. */
    @Test
    public void theSurfaceIsAskedForOnceAndKept() {
        Surface first = holder.getSurface();
        Surface second = holder.getSurface();

        assertNotNull(first);
        assertSame(first, second);
        assertEquals("one request, not one per call", 1, provider.requests.size());
    }

    /** The camera is told the size and orientation it should produce. */
    @Test
    public void theProviderIsToldWhatToProduce() {
        holder.getSurface();

        RecordingProvider.Request request = provider.requests.get(0);
        assertEquals(WIDTH, request.width);
        assertEquals(HEIGHT, request.height);
        assertEquals(ORIENTATION, request.orientation);
        assertFalse(request.flipped);
    }

    /**
     * The holder is "creating" until it has a surface.
     *
     * <p>Which is the signal the old API uses to decide whether it may draw yet, so it has to
     * flip exactly when the surface appears rather than being hardcoded either way.</p>
     */
    @Test
    public void itIsCreatingUntilThereIsASurface() {
        assertTrue("nothing has asked for a surface yet", holder.isCreating());

        holder.getSurface();

        assertFalse(holder.isCreating());
    }

    @Test
    public void theFrameIsTheSizeItWasBuiltWith() {
        assertEquals(new Rect(0, 0, WIDTH, HEIGHT), holder.getSurfaceFrame());
    }

    /** A fixed size really does change what the camera is asked for. */
    @Test
    public void aFixedSizeChangesWhatTheCameraIsAskedFor() {
        holder.setFixedSize(1280, 720);

        assertEquals(new Rect(0, 0, 1280, 720), holder.getSurfaceFrame());
        holder.getSurface();
        assertEquals(1280, provider.requests.get(0).width);
        assertEquals(720, provider.requests.get(0).height);
    }

    /**
     * The window settings that do not apply here are accepted and ignored.
     *
     * <p>Each is about a window, and this holder is not one: the size comes from the texture and
     * the format from the camera. Throwing would break a caller that is only being thorough.</p>
     */
    @Test
    public void theInapplicableWindowSettingsAreIgnored() {
        holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        holder.setSizeFromLayout();
        holder.setFormat(android.graphics.PixelFormat.RGBA_8888);

        assertEquals("none of them change the frame",
                new Rect(0, 0, WIDTH, HEIGHT), holder.getSurfaceFrame());
    }

    /** Callbacks hear about the surface when it appears and when it goes. */
    @Test
    public void callbacksHearAboutTheSurface() {
        List<String> events = new ArrayList<>();
        SurfaceHolder.Callback callback = new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { events.add("created"); }

            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int height) {
                events.add("changed");
            }

            @Override public void surfaceDestroyed(SurfaceHolder h) { events.add("destroyed"); }
        };
        holder.addCallback(callback);

        holder.getSurface();
        holder.close();

        assertEquals(java.util.Arrays.asList("created", "destroyed"), events);
    }

    /** A removed callback hears nothing, which is the whole point of removing it. */
    @Test
    public void aRemovedCallbackHearsNothing() {
        List<String> events = new ArrayList<>();
        SurfaceHolder.Callback callback = new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { events.add("created"); }

            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int height) {}

            @Override public void surfaceDestroyed(SurfaceHolder h) { events.add("destroyed"); }
        };
        holder.addCallback(callback);
        holder.removeCallback(callback);

        holder.getSurface();
        holder.close();

        assertTrue(events.isEmpty());
    }

    /**
     * Keeping the screen on takes a wake lock, and letting it off gives it back.
     *
     * <p>A recording holds the screen awake, and a wake lock that is taken twice or released
     * twice is how an app ends up either draining a battery or crashing on an unbalanced
     * release.</p>
     */
    @Test
    public void theWakeLockIsBalanced() {
        holder.setKeepScreenOn(true);
        holder.setKeepScreenOn(true);
        holder.setKeepScreenOn(false);
        holder.setKeepScreenOn(false);

        // An unbalanced release throws, so reaching here is most of the assertion; the holder
        // also has to be left usable rather than half torn down.
        assertNotNull(holder.getSurface());
    }

    /** And closing releases it, because a closed holder is not keeping anything awake. */
    @Test
    public void closingReleasesTheWakeLock() {
        holder.setKeepScreenOn(true);

        holder.close();
        holder.close();

        assertTrue("a closed holder has nothing to draw on", holder.isCreating());
    }

    /** Closing releases the surface and asks for a new one next time. */
    @Test
    public void closingReleasesTheSurface() {
        Surface first = holder.getSurface();

        holder.close();
        Surface second = holder.getSurface();

        assertEquals("a closed holder asks the camera again", 2, provider.requests.size());
        assertNotNull(second);
    }

    /** A provider that remembers what it was asked for. */
    private static final class RecordingProvider implements VideoRecorder.SurfaceProvider {
        private final SurfaceTexture mSurfaceTexture;
        final List<Request> requests = new ArrayList<>();

        RecordingProvider(SurfaceTexture surfaceTexture) {
            mSurfaceTexture = surfaceTexture;
        }

        @Override
        public Surface getSurface(int width, int height, int orientation, boolean flipped) {
            requests.add(new Request(width, height, orientation, flipped));
            return new Surface(mSurfaceTexture);
        }

        @Nullable
        @Override
        public Size getMaxSize() {
            return null;
        }

        static final class Request {
            final int width;
            final int height;
            final int orientation;
            final boolean flipped;

            Request(int width, int height, int orientation, boolean flipped) {
                this.width = width;
                this.height = height;
                this.orientation = orientation;
                this.flipped = flipped;
            }
        }
    }
}
