package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.hardware.Camera;
import android.os.Looper;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.MotionEvent;

import com.xlythe.view.camera.legacy.LegacyCameraModule;
import com.xlythe.view.camera.shadow.ShadowAvailableTextureView;
import com.xlythe.view.camera.shadow.ShadowInjectableImageReader;
import com.xlythe.view.camera.v2.FakeCamera2;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowCamera;
import org.robolectric.shadows.ShadowChoreographer;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Zoom through CameraView, the way an app drives it: by ratio, by level, and with a listener
 * keeping preset buttons in step.
 *
 * <p>The fake back camera crops up to 4x, and its front camera does too.</p>
 *
 * <p>Animated zooms are driven a display frame at a time, by advancing the main looper's clock
 * with Robolectric's choreographer paused.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37},
        shadows = {ShadowAvailableTextureView.class, ShadowInjectableImageReader.class})
public class CameraViewZoomTest {
    /** One display frame, as the animator sees it. */
    private static final long FRAME_MILLIS = 16;
    /** Comfortably more frames than a 300ms animation takes. */
    private static final int ANIMATION_FRAMES = 30;

    private Context context;
    private CameraView cameraView;
    /** Every ratio the listener heard, in order. */
    private final List<Float> heard = new ArrayList<>();

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Shadows.shadowOf((android.app.Application) context).grantPermissions(
                Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO);
        // Paused, the choreographer waits for the clock to reach each frame. Otherwise Robolectric
        // advances the clock itself and runs a whole animation in one pass of the looper.
        ShadowChoreographer.setPaused(true);
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(FRAME_MILLIS));
    }

    @After
    public void tearDown() {
        if (cameraView != null) {
            cameraView.close();
            idle();
        }
        CameraView.setDefaultBackend(CameraView.Backend.AUTO);
        ShadowCamera.clearCameraInfo();
    }

    private void open(boolean logicalBackCamera) throws Exception {
        if (logicalBackCamera) {
            FakeCamera2.addLogicalBackCamera(context);
        } else {
            FakeCamera2.addBackAndFrontCameras(context);
        }
        cameraView = new CameraView(context);
        cameraView.onFinishInflate();
        cameraView.layout(0, 0, 1080, 1920);
        cameraView.setOnZoomChangedListener(ratio -> {
            assertTrue("delivered on the main thread", Looper.myLooper() == Looper.getMainLooper());
            heard.add(ratio);
        });
        cameraView.open();
        idle();
    }

    /** Each change is heard once, and a request that changes nothing is not heard at all. */
    @Test
    public void theListenerHearsEachChangeOnce() throws Exception {
        open(false /* logicalBackCamera */);

        cameraView.setZoomRatio(2f);
        idle();
        cameraView.setZoomRatio(2f);
        idle();
        cameraView.setZoomRatio(3f);
        idle();
        cameraView.setZoomRatio(40f);
        idle();
        cameraView.setZoomRatio(50f);
        idle();

        assertEquals("2x, 3x, and 40x held to the 4x top; the repeats are not news",
                List.of(2f, 3f, 4f), heard);
    }

    /** A level is the same zoom as a ratio, on a log scale across the camera's range. */
    @Test
    public void aLevelIsAPositionInTheRatioRange() throws Exception {
        open(false /* logicalBackCamera */);
        assertEquals(1f, cameraView.getMinZoomRatio(), 0f);
        assertEquals(4f, cameraView.getMaxZoomRatio(), 0f);

        cameraView.setZoomLevel(50);
        idle();

        assertEquals("halfway from 1x to 4x on a log scale is 2x",
                2f, cameraView.getZoomRatio(), 0.0001f);
        assertEquals(50, cameraView.getZoomLevel());
        assertEquals(List.of(cameraView.getZoomRatio()), heard);

        cameraView.setZoomRatio(4f);
        idle();
        assertEquals(cameraView.getMaxZoomLevel(), cameraView.getZoomLevel());
    }

    /** A lens switch that cannot keep the zoom is a change like any other. */
    @Test
    public void aLensSwitchThatMovesTheZoomIsHeard() throws Exception {
        open(true /* logicalBackCamera */);
        cameraView.setZoomRatio(cameraView.getMaxZoomRatio());
        idle();
        heard.clear();

        cameraView.toggleCamera();
        idle();

        assertEquals("the front camera crops to 4x at most", List.of(4f), heard);
        assertEquals(4f, cameraView.getZoomRatio(), 0f);
    }

    /** A camera with one lens and nothing to crop to 2x offers only 1x. */
    @Test
    public void aSingleLensHasOnePreset() throws Exception {
        open(false /* logicalBackCamera */);

        assertEquals(List.of(new ZoomPreset(1f, "1")), cameraView.getZoomPresets());
    }

    /** A ratio that is not one is refused rather than handed to the camera. */
    @Test
    public void anImpossibleRatioIsIgnored() throws Exception {
        open(false /* logicalBackCamera */);
        cameraView.setZoomRatio(2f);
        idle();

        cameraView.setZoomRatio(Float.NaN);
        cameraView.setZoomRatio(0f);
        cameraView.setZoomRatio(-1f);
        cameraView.setZoomRatio(Float.POSITIVE_INFINITY);
        idle();

        assertEquals(2f, cameraView.getZoomRatio(), 0f);
        assertEquals(List.of(2f), heard);
    }

    /** A pinch multiplies the zoom it started from by how far the fingers spread. */
    @Test
    public void aPinchMultipliesTheZoom() throws Exception {
        open(false /* logicalBackCamera */);
        cameraView.setZoomRatio(1.5f);
        idle();

        pinch(400, 800);
        assertEquals("twice as far apart from 1.5x", 3f, cameraView.getZoomRatio(), 0.001f);

        pinch(800, 400);
        assertEquals("and back to half", 1.5f, cameraView.getZoomRatio(), 0.001f);
        assertEquals("each step of the pinch is heard",
                1.5f, heard.get(heard.size() - 1), 0.001f);
    }

    /** A pinch past the end of the range turns around the moment the fingers do. */
    @Test
    public void aPinchThatOvershootsTurnsAroundAtOnce() throws Exception {
        open(false /* logicalBackCamera */);
        cameraView.setZoomRatio(1.5f);
        idle();

        // 3.5 times as far apart would be 5.25x, which the 4x top of the range holds back.
        pinch(300, 1050, 525);

        assertEquals("held at 4x, then halved", 2f, cameraView.getZoomRatio(), 0.001f);
    }

    /** With pinch-to-zoom turned off, a pinch leaves the zoom alone. */
    @Test
    public void aDisabledPinchDoesNotZoom() throws Exception {
        open(false /* logicalBackCamera */);
        cameraView.setPinchToZoomEnabled(false);

        pinch(400, 800);

        assertEquals(1f, cameraView.getZoomRatio(), 0f);
        assertTrue(heard.isEmpty());
    }

    /** The zoom survives a configuration change as the ratio it was. */
    @Test
    public void theZoomSurvivesSaveAndRestore() throws Exception {
        open(false /* logicalBackCamera */);
        cameraView.setZoomRatio(2.5f);
        idle();

        cameraView.setId(1);
        SparseArray<Parcelable> container = new SparseArray<>();
        cameraView.saveHierarchyState(container);
        CameraView restored = new CameraView(context);
        restored.setId(1);
        restored.restoreHierarchyState(container);

        assertEquals(2.5f, restored.getZoomRatio(), 0f);
    }

    /**
     * An animated zoom eases over to the ratio a frame at a time, lands on it exactly, and is
     * heard at every step.
     */
    @Test
    public void anAnimatedZoomEasesOverToTheRatio() throws Exception {
        open(false /* logicalBackCamera */);

        cameraView.setZoomRatio(4f, true /* animate */);
        assertEquals("nothing moves before the first frame", 1f, cameraView.getZoomRatio(), 0f);
        List<Float> frames = advance(ANIMATION_FRAMES);

        int end = frames.indexOf(4f);
        assertTrue("never reached 4x: " + frames, end >= 0);
        assertTrue("about 300ms, not " + end * FRAME_MILLIS + "ms",
                end * FRAME_MILLIS >= 250 && end * FRAME_MILLIS <= 350);
        for (int i = 1; i <= end; i++) {
            assertTrue("each frame moves on: " + frames, frames.get(i) > frames.get(i - 1));
        }
        for (int i = end; i < frames.size(); i++) {
            assertEquals("and then it stays", 4f, frames.get(i), 0f);
        }
        assertTrue("every step is heard: " + heard, heard.containsAll(distinctAbove(1f, frames)));
        for (int i = 1; i < heard.size(); i++) {
            assertTrue("once, in order: " + heard, heard.get(i) > heard.get(i - 1));
        }
        assertEquals(4f, heard.get(heard.size() - 1), 0f);
    }

    /**
     * The zoom moves on a log scale, so it spends as long below 2x, the geometric middle of 1x to
     * 4x, as above it. Moving linearly, it would be past 2x a third of the way through.
     */
    @Test
    public void anAnimatedZoomMovesOnALogScale() throws Exception {
        open(false /* logicalBackCamera */);

        cameraView.setZoomRatio(4f, true /* animate */);
        List<Float> frames = advance(ANIMATION_FRAMES);

        int below = 0;
        int above = 0;
        for (int i = 0; i <= frames.indexOf(4f); i++) {
            if (frames.get(i) < 2f) {
                below++;
            } else {
                above++;
            }
        }
        assertTrue(below + " frames below 2x and " + above + " above: " + frames,
                Math.abs(below - above) <= 2);
    }

    /** It eases back down the same way, and lands on the bottom of the range exactly. */
    @Test
    public void anAnimatedZoomEasesOutAsWellAsIn() throws Exception {
        open(false /* logicalBackCamera */);
        cameraView.setZoomRatio(4f);
        idle();

        cameraView.setZoomRatio(1f, true /* animate */);
        List<Float> frames = advance(ANIMATION_FRAMES);

        int end = frames.indexOf(1f);
        assertTrue("never reached 1x: " + frames, end > 1);
        for (int i = 1; i <= end; i++) {
            assertTrue("each frame moves on: " + frames, frames.get(i) < frames.get(i - 1));
        }
    }

    /** A ratio beyond the range is held to it first, so the animation never stalls at the end. */
    @Test
    public void anAnimatedZoomPastTheRangeIsHeldToIt() throws Exception {
        open(false /* logicalBackCamera */);

        cameraView.setZoomRatio(40f, true /* animate */);
        List<Float> frames = advance(ANIMATION_FRAMES);

        int end = frames.indexOf(4f);
        assertTrue("about 300ms to the 4x top, not " + end * FRAME_MILLIS + "ms",
                end * FRAME_MILLIS >= 250 && end * FRAME_MILLIS <= 350);
    }

    /** Animating to where the zoom already is has nothing to do. */
    @Test
    public void anAnimatedZoomToTheSameRatioDoesNothing() throws Exception {
        open(false /* logicalBackCamera */);

        cameraView.setZoomRatio(1f, true /* animate */);
        advance(ANIMATION_FRAMES);

        assertTrue(heard.isEmpty());
    }

    /** A closed camera has no preview to animate, so the zoom is set at once for the next open. */
    @Test
    public void anAnimatedZoomOnAClosedCameraIsSetAtOnce() throws Exception {
        open(false /* logicalBackCamera */);
        cameraView.close();
        idle();

        cameraView.setZoomRatio(2.5f, true /* animate */);

        assertEquals(2.5f, cameraView.getZoomRatio(), 0f);
    }

    /** Asking again for the ratio an animation is heading to does not start it over. */
    @Test
    public void askingForTheSameTargetAgainKeepsTheAnimationGoing() throws Exception {
        open(false /* logicalBackCamera */);
        cameraView.setZoomRatio(4f, true /* animate */);
        List<Float> frames = advance(5);

        cameraView.setZoomRatio(4f, true /* animate */);
        frames.addAll(advance(ANIMATION_FRAMES));

        int end = frames.indexOf(4f);
        assertTrue("still about 300ms in all, not " + end * FRAME_MILLIS + "ms",
                end * FRAME_MILLIS >= 250 && end * FRAME_MILLIS <= 350);
    }

    /** A new target takes over from wherever the zoom has got to. */
    @Test
    public void aNewTargetTakesOverMidAnimation() throws Exception {
        open(false /* logicalBackCamera */);
        cameraView.setZoomRatio(4f, true /* animate */);
        advance(10);
        float reached = cameraView.getZoomRatio();
        assertTrue(reached > 1f && reached < 4f);

        cameraView.setZoomRatio(1f, true /* animate */);
        List<Float> frames = advance(ANIMATION_FRAMES);

        assertTrue("it turns around from " + reached + "x: " + frames,
                frames.get(1) < reached && frames.get(1) > 1f);
        assertEquals(1f, cameraView.getZoomRatio(), 0f);
    }

    /** The fingers take over from wherever the animation has got to. */
    @Test
    public void aPinchStopsTheAnimation() throws Exception {
        open(false /* logicalBackCamera */);
        cameraView.setZoomRatio(4f, true /* animate */);
        advance(5);
        float reached = cameraView.getZoomRatio();

        pinch(400, 800);
        advance(ANIMATION_FRAMES);

        assertEquals("twice as far apart from " + reached + "x, and no further",
                reached * 2, cameraView.getZoomRatio(), 0.001f);
    }

    /** A zoom that is not animated replaces the animation rather than racing it. */
    @Test
    public void aZoomThatIsNotAnimatedStopsTheAnimation() throws Exception {
        open(false /* logicalBackCamera */);
        cameraView.setZoomRatio(4f, true /* animate */);
        advance(5);

        cameraView.setZoomRatio(1.5f);
        advance(ANIMATION_FRAMES);

        assertEquals(1.5f, cameraView.getZoomRatio(), 0f);
        assertEquals(1.5f, heard.get(heard.size() - 1), 0f);
    }

    /** As does a level. */
    @Test
    public void aZoomLevelStopsTheAnimation() throws Exception {
        open(false /* logicalBackCamera */);
        cameraView.setZoomRatio(4f, true /* animate */);
        advance(5);

        cameraView.setZoomLevel(50);
        advance(ANIMATION_FRAMES);

        assertEquals("halfway from 1x to 4x", 2f, cameraView.getZoomRatio(), 0.0001f);
    }

    /** Another lens is another camera, and the animation was heading for a stop on this one. */
    @Test
    public void aLensSwitchStopsTheAnimation() throws Exception {
        open(false /* logicalBackCamera */);
        cameraView.setZoomRatio(4f, true /* animate */);
        advance(5);
        float reached = cameraView.getZoomRatio();

        cameraView.toggleCamera();
        idle();
        advance(ANIMATION_FRAMES);

        assertTrue(cameraView.isUsingFrontFacingCamera());
        assertEquals("the front camera keeps the zoom it was handed",
                reached, cameraView.getZoomRatio(), 0f);
    }

    /** Choosing the other lens by facing stops it too. */
    @Test
    public void choosingTheOtherLensStopsTheAnimation() throws Exception {
        open(false /* logicalBackCamera */);
        cameraView.setZoomRatio(4f, true /* animate */);
        advance(5);
        float reached = cameraView.getZoomRatio();

        cameraView.setLensFacing(CameraView.LensFacing.FRONT);
        idle();
        advance(ANIMATION_FRAMES);

        assertEquals(reached, cameraView.getZoomRatio(), 0f);
    }

    /**
     * Naming the lens already in use is not a switch, which matters to Compose: it passes the
     * lens again on every recomposition, and each step of the animation can cause one.
     */
    @Test
    public void choosingTheSameLensLeavesTheAnimationAlone() throws Exception {
        open(false /* logicalBackCamera */);
        cameraView.setZoomRatio(4f, true /* animate */);
        advance(5);

        cameraView.setLensFacing(CameraView.LensFacing.BACK);
        advance(ANIMATION_FRAMES);

        assertEquals(4f, cameraView.getZoomRatio(), 0f);
    }

    /** Closing stops the animation, at the ratio it was heading for, ready for the next open. */
    @Test
    public void closingLandsTheAnimationOnItsTarget() throws Exception {
        open(false /* logicalBackCamera */);
        cameraView.setZoomRatio(4f, true /* animate */);
        advance(5);

        cameraView.close();
        idle();
        assertEquals(4f, cameraView.getZoomRatio(), 0f);
        int heardOnClose = heard.size();
        advance(ANIMATION_FRAMES);

        assertEquals("nothing moves once the camera is closed", heardOnClose, heard.size());
        assertEquals(4f, heard.get(heard.size() - 1), 0f);
    }

    /** A configuration change mid-animation comes back at the ratio it was heading for. */
    @Test
    public void savedStateHoldsTheAnimationsTarget() throws Exception {
        open(false /* logicalBackCamera */);
        cameraView.setZoomRatio(4f, true /* animate */);
        advance(5);
        assertTrue(cameraView.getZoomRatio() < 4f);

        cameraView.setId(1);
        SparseArray<Parcelable> container = new SparseArray<>();
        cameraView.saveHierarchyState(container);
        CameraView restored = new CameraView(context);
        restored.setId(1);
        restored.restoreHierarchyState(container);

        assertEquals(4f, restored.getZoomRatio(), 0f);
    }

    /**
     * The old camera API animates the same way, stepping through its own zoom table: each step
     * lands on the nearest entry, and the last on the target's.
     */
    @Test
    public void theOldCameraApiAnimatesThroughItsZoomTable() {
        Camera.CameraInfo back = new Camera.CameraInfo();
        back.facing = Camera.CameraInfo.CAMERA_FACING_BACK;
        back.orientation = 90;
        ShadowCamera.addCameraInfo(0, back);
        CameraView.setDefaultBackend(CameraView.Backend.LEGACY);
        cameraView = new CameraView(context);
        cameraView.onFinishInflate();
        cameraView.layout(0, 0, 1080, 1920);
        cameraView.setOnZoomChangedListener(heard::add);
        cameraView.open();
        idle();
        assertTrue(cameraView.isOpen());
        // The shadowed camera hands out the same parameters every time, so a zoom table written
        // into them is the camera's.
        Camera camera = ReflectionHelpers.getField(
                (LegacyCameraModule) ReflectionHelpers.getField(cameraView, "mCameraModule"),
                "mCamera");
        Camera.Parameters parameters = camera.getParameters();
        parameters.set("zoom-supported", "true");
        parameters.set("max-zoom", 4);
        parameters.set("zoom-ratios", "100,150,200,300,400");
        parameters.set("zoom", 0);

        cameraView.setZoomRatio(4f, true /* animate */);
        List<Float> frames = advance(ANIMATION_FRAMES);

        assertEquals("every entry on the way, and nothing between them",
                List.of(1.5f, 2f, 3f, 4f), heard);
        assertEquals(4f, frames.get(frames.size() - 1), 0f);
        assertEquals("the camera is on the last entry", 4, camera.getParameters().getZoom());
        assertFalse("and the animation took more than one frame", frames.get(0) == 4f);
    }

    /** Runs the clock forward a frame at a time, returning the zoom after each frame. */
    private List<Float> advance(int frames) {
        List<Float> zooms = new ArrayList<>();
        for (int i = 0; i < frames; i++) {
            ShadowLooper.idleMainLooper(FRAME_MILLIS, TimeUnit.MILLISECONDS);
            zooms.add(cameraView.getZoomRatio());
        }
        return zooms;
    }

    /** The ratios a run of frames moved through, each once, after it left the starting one. */
    private static List<Float> distinctAbove(float start, List<Float> frames) {
        List<Float> moves = new ArrayList<>();
        float last = start;
        for (float frame : frames) {
            if (frame != last) {
                moves.add(frame);
                last = frame;
            }
        }
        return moves;
    }

    /**
     * Pinches across the middle of the view through a series of finger spans, in pixels.
     *
     * <p>The detector only starts once the span has moved past its slop, and measures from the
     * span it started at, so the fingers come down a little inside the first span and the scale is
     * each span over the one before it.</p>
     */
    private void pinch(float... spans) {
        long downTime = SystemClock.uptimeMillis();
        float centre = cameraView.getWidth() / 2f;
        float y = cameraView.getHeight() / 2f;
        float down = spans[0] - 100;
        touch(downTime, MotionEvent.ACTION_DOWN, centre, y, down, 1);
        touch(downTime, MotionEvent.ACTION_POINTER_DOWN
                | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), centre, y, down, 2);
        for (float span : spans) {
            touch(downTime, MotionEvent.ACTION_MOVE, centre, y, span, 2);
        }
        float last = spans[spans.length - 1];
        touch(downTime, MotionEvent.ACTION_POINTER_UP
                | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), centre, y, last, 2);
        touch(downTime, MotionEvent.ACTION_UP, centre, y, last, 1);
        idle();
    }

    /** Sends one event with one or two fingers either side of a point, a span apart. */
    private void touch(long downTime, int action, float x, float y, float span, int fingers) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[fingers];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[fingers];
        for (int i = 0; i < fingers; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = i == 0 ? x - span / 2 : x + span / 2;
            coords[i].y = y;
            coords[i].pressure = 1f;
            coords[i].size = 1f;
        }
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                fingers, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0);
        cameraView.onTouchEvent(event);
        event.recycle();
    }

    private static void idle() {
        ShadowLooper.idleMainLooper();
    }
}
