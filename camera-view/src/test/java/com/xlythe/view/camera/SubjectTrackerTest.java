package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.RectF;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * The frame-to-frame tracker behind tap-to-track, driven with drawn frames.
 *
 * <p>Each scene is a smooth, low-contrast background with a textured square on it, which is what
 * a subject against a room looks like to a brightness correlator. The square is drawn from its own
 * coordinates, so its texture travels and scales with it.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class SubjectTrackerTest {
    private static final int WIDTH = 640;

    private static final int HEIGHT = 480;

    /** The subject's side, in frame pixels: larger than the patch the tracker follows. */
    private static final int SUBJECT_SIZE = 100;

    /** How close, in frame pixels, the tracker has to keep to the subject's centre. */
    private static final float TOLERANCE = 6f;

    @Test
    public void aMovingSubjectIsFollowed() {
        SubjectTracker tracker = new SubjectTracker();
        Scene scene = new Scene(WIDTH, HEIGHT);
        scene.subjectX = 200;
        scene.subjectY = 240;
        assertTrue(scene.start(tracker, 200, 240));

        for (int frame = 0; frame < 40; frame++) {
            scene.subjectX += 6;
            scene.subjectY += frame % 2 == 0 ? 3 : -2;
            RectF bounds = scene.track(tracker);
            assertNotNull("lost on frame " + frame, bounds);
            assertEquals(scene.subjectX, bounds.centerX(), TOLERANCE);
            assertEquals(scene.subjectY, bounds.centerY(), TOLERANCE);
        }
        assertTrue(tracker.getCorrelation() > SubjectTracker.MIN_CORRELATION);
    }

    /** The other half of the job: the subject stays put and the camera moves. */
    @Test
    public void aPanningCameraKeepsTheSubject() {
        SubjectTracker tracker = new SubjectTracker();
        Scene scene = new Scene(WIDTH, HEIGHT);
        scene.subjectX = 320;
        scene.subjectY = 240;
        assertTrue(scene.start(tracker, 320, 240));

        for (int frame = 0; frame < 25; frame++) {
            scene.panX -= 9;
            scene.panY += 2;
            RectF bounds = scene.track(tracker);
            assertNotNull("lost on frame " + frame, bounds);
            assertEquals(320 + scene.panX, bounds.centerX(), TOLERANCE);
            assertEquals(240 + scene.panY, bounds.centerY(), TOLERANCE);
        }
    }

    /** Tracking moves exposure onto the subject, so it has to survive the brightness changing. */
    @Test
    public void anExposureChangeIsNotTheSubjectLeaving() {
        SubjectTracker tracker = new SubjectTracker();
        Scene scene = new Scene(WIDTH, HEIGHT);
        scene.subjectX = 300;
        scene.subjectY = 200;
        assertTrue(scene.start(tracker, 300, 200));

        for (int frame = 0; frame < 20; frame++) {
            scene.gain -= 0.025f;
            scene.subjectX += 4;
            RectF bounds = scene.track(tracker);
            assertNotNull("lost at a gain of " + scene.gain, bounds);
            assertEquals(scene.subjectX, bounds.centerX(), TOLERANCE);
        }
    }

    @Test
    public void anApproachingSubjectGrowsItsBox() {
        SubjectTracker tracker = new SubjectTracker();
        Scene scene = new Scene(WIDTH, HEIGHT);
        scene.subjectX = 320;
        scene.subjectY = 240;
        assertTrue(scene.start(tracker, 320, 240));
        RectF first = scene.track(tracker);
        assertNotNull(first);

        RectF bounds = first;
        for (int frame = 0; frame < 24; frame++) {
            scene.subjectScale *= 1.03f;
            bounds = scene.track(tracker);
            assertNotNull("lost at a scale of " + scene.subjectScale, bounds);
        }
        assertTrue("the box should grow with the subject: " + first + " to " + bounds,
                bounds.width() > first.width() * 1.4f);
        assertEquals(320, bounds.centerX(), TOLERANCE);
    }

    /** A blank wall matches itself everywhere, so there is nothing to follow. */
    @Test
    public void aFlatPatchIsRefused() {
        SubjectTracker tracker = new SubjectTracker();
        byte[] flat = new byte[WIDTH * HEIGHT];
        Arrays.fill(flat, (byte) 128);

        assertFalse(tracker.start(ByteBuffer.wrap(flat), WIDTH, HEIGHT, WIDTH, 320, 240));
        assertFalse(tracker.isTracking());
        assertNull(tracker.track(ByteBuffer.wrap(flat), WIDTH, HEIGHT, WIDTH));
    }

    /**
     * A subject that disappears is held for a few frames, then given up.
     *
     * <p>Holding covers a hand passing in front of it; giving up is what lets the camera go back
     * to choosing its own focus instead of metering empty space.</p>
     */
    @Test
    public void aSubjectThatLeavesIsLostAfterAFewFrames() {
        SubjectTracker tracker = new SubjectTracker();
        Scene scene = new Scene(WIDTH, HEIGHT);
        scene.subjectX = 320;
        scene.subjectY = 240;
        assertTrue(scene.start(tracker, 320, 240));
        assertNotNull(scene.track(tracker));

        scene.subjectVisible = false;
        for (int frame = 1; frame < SubjectTracker.MAX_MISSES; frame++) {
            RectF held = scene.track(tracker);
            assertNotNull("held on miss " + frame, held);
            assertEquals("where it was last seen", 320, held.centerX(), TOLERANCE);
        }
        assertNull(scene.track(tracker));
        assertFalse(tracker.isTracking());
    }

    /** A different frame size is a different camera, and the old position means nothing there. */
    @Test
    public void aNewFrameSizeLosesTheSubject() {
        SubjectTracker tracker = new SubjectTracker();
        Scene scene = new Scene(WIDTH, HEIGHT);
        scene.subjectX = 320;
        scene.subjectY = 240;
        assertTrue(scene.start(tracker, 320, 240));

        Scene other = new Scene(HEIGHT, WIDTH);
        assertNull(other.track(tracker));
        assertFalse(tracker.isTracking());
    }

    /** Camera planes pad their rows, and a padded row must not shear the picture. */
    @Test
    public void paddedRowsAreRead() {
        SubjectTracker tracker = new SubjectTracker();
        Scene scene = new Scene(WIDTH, HEIGHT);
        scene.rowPadding = 96;
        scene.subjectX = 250;
        scene.subjectY = 300;
        assertTrue(scene.start(tracker, 250, 300));

        for (int frame = 0; frame < 10; frame++) {
            scene.subjectX += 5;
            scene.subjectY -= 5;
            RectF bounds = scene.track(tracker);
            assertNotNull(bounds);
            assertEquals(scene.subjectX, bounds.centerX(), TOLERANCE);
            assertEquals(scene.subjectY, bounds.centerY(), TOLERANCE);
        }
    }

    /** The old camera API hands over full preview frames, which are reduced much further. */
    @Test
    public void aLargeFrameIsReducedAndStillFollowed() {
        SubjectTracker tracker = new SubjectTracker();
        Scene scene = new Scene(1920, 1080);
        scene.subjectSize = 300;
        scene.subjectX = 900;
        scene.subjectY = 500;
        assertTrue(scene.start(tracker, 900, 500));

        for (int frame = 0; frame < 15; frame++) {
            scene.subjectX += 18;
            RectF bounds = scene.track(tracker);
            assertNotNull(bounds);
            assertEquals(scene.subjectX, bounds.centerX(), 3 * TOLERANCE);
            assertEquals(scene.subjectY, bounds.centerY(), 3 * TOLERANCE);
        }
    }

    /** A point near the edge starts a patch that fits, rather than one hanging off the frame. */
    @Test
    public void aPointAtTheEdgeStartsAPatchInsideTheFrame() {
        SubjectTracker tracker = new SubjectTracker();
        Scene scene = new Scene(WIDTH, HEIGHT);
        scene.subjectX = 40;
        scene.subjectY = 40;
        assertTrue(scene.start(tracker, 0, 0));

        RectF bounds = scene.track(tracker);
        assertNotNull(bounds);
        assertTrue(bounds.left >= 0);
        assertTrue(bounds.top >= 0);
    }

    @Test
    public void stoppingForgetsTheSubject() {
        SubjectTracker tracker = new SubjectTracker();
        Scene scene = new Scene(WIDTH, HEIGHT);
        scene.subjectX = 320;
        scene.subjectY = 240;
        assertTrue(scene.start(tracker, 320, 240));

        tracker.stop();

        assertFalse(tracker.isTracking());
        assertNull(scene.track(tracker));
    }

    /** Draws frames of a textured square on a smooth background. */
    private static final class Scene {
        final int width;
        final int height;
        float subjectX;
        float subjectY;
        float subjectSize = SUBJECT_SIZE;
        float subjectScale = 1f;
        boolean subjectVisible = true;
        /** How far the whole scene has moved, as a camera turning would move it. */
        float panX;
        float panY;
        /** A multiplier on every grey level, as an exposure change would apply. */
        float gain = 1f;
        int rowPadding;
        /** The background as last drawn, which only a pan changes, and the pan it was drawn at. */
        private float[] backdrop;
        private float backdropPanX;
        private float backdropPanY;

        Scene(int width, int height) {
            this.width = width;
            this.height = height;
        }

        boolean start(SubjectTracker tracker, float x, float y) {
            return tracker.start(render(), width, height, width + rowPadding, x, y);
        }

        RectF track(SubjectTracker tracker) {
            return tracker.track(render(), width, height, width + rowPadding);
        }

        ByteBuffer render() {
            if (backdrop == null || backdropPanX != panX || backdropPanY != panY) {
                backdrop = new float[width * height];
                backdropPanX = panX;
                backdropPanY = panY;
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        backdrop[y * width + x] = background(x - panX, y - panY);
                    }
                }
            }
            int stride = width + rowPadding;
            byte[] pixels = new byte[stride * height];
            float half = subjectSize * subjectScale / 2f;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    float value = backdrop[y * width + x];
                    float localX = x - (subjectX + panX);
                    float localY = y - (subjectY + panY);
                    if (subjectVisible && Math.abs(localX) < half && Math.abs(localY) < half) {
                        value = texture(localX / subjectScale, localY / subjectScale);
                    }
                    pixels[y * stride + x] = (byte) Math.max(0, Math.min(255, value * gain));
                }
            }
            return ByteBuffer.wrap(pixels);
        }

        /** A gentle wash of light, with texture of its own so a pan has something to follow. */
        private static float background(float x, float y) {
            return 110 + 25 * (float) (Math.sin(x / 23.0) * Math.cos(y / 31.0))
                    + 0.3f * (texture(x * 0.7f + 1000, y * 0.7f + 1000) - 128);
        }

        /** Blocky value noise, eight pixels to a block, softened where blocks meet. */
        private static float texture(float x, float y) {
            float cellX = x / 8f;
            float cellY = y / 8f;
            int x0 = (int) Math.floor(cellX);
            int y0 = (int) Math.floor(cellY);
            float fx = cellX - x0;
            float fy = cellY - y0;
            float top = lerp(hash(x0, y0), hash(x0 + 1, y0), fx);
            float bottom = lerp(hash(x0, y0 + 1), hash(x0 + 1, y0 + 1), fx);
            return lerp(top, bottom, fy);
        }

        private static float lerp(float a, float b, float t) {
            return a + (b - a) * t;
        }

        private static float hash(int x, int y) {
            int h = x * 374761393 + y * 668265263;
            h = (h ^ (h >>> 13)) * 1274126177;
            return 30 + ((h ^ (h >>> 16)) & 0xFF) * 0.75f;
        }
    }
}
