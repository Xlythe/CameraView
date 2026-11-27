package com.xlythe.view.camera.ar;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import androidx.annotation.NonNull;

import com.xlythe.view.camera.gl.GlMath;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * What ARCore reports about a frame, turned into what the renderer draws with.
 *
 * <p>The figures are the ones a Galaxy S25 Ultra reported: a 640x480 image to track with and a
 * 1920x1080 texture to draw, the texture showing the middle three quarters of the image's
 * height.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public final class ArMathTest {
    private static final float TOLERANCE = 1e-4f;

    /** A texture that is the image, held the same way. */
    private static final float[] WHOLE_IMAGE = {0f, 0f, 1f, 0f, 0f, 1f};

    /** Where the S25's texture corners landed in its image. */
    private static final float[] S25_TEXTURE = {0f, 0.125f, 1f, 0.125f, 0f, 0.875f};

    /** A texture holding the image upside down, as GL would hold rows written from the top. */
    private static final float[] FLIPPED_IMAGE = {0f, 1f, 1f, 1f, 0f, 0f};

    /** A turn and a move, column-major: a quarter turn about y and a step to (1, 2, 3). */
    private static final float[] POSE = {
            0f, 0f, -1f, 0f,
            0f, 1f, 0f, 0f,
            1f, 0f, 0f, 0f,
            1f, 2f, 3f, 1f,
    };

    @Test
    public void theBoundsAreWhereTheTextureSitsInTheImage() {
        assertArrayEquals(new float[] {0f, 0f, 1f, 1f}, ArMath.textureBounds(WHOLE_IMAGE), 0f);
        assertArrayEquals(new float[] {0f, 0.125f, 1f, 0.875f},
                ArMath.textureBounds(S25_TEXTURE), TOLERANCE);
        assertArrayEquals("whichever way round it is held",
                new float[] {0f, 0f, 1f, 1f}, ArMath.textureBounds(FLIPPED_IMAGE), 0f);
    }

    @Test
    public void aTextureNeedsThreeCornersAndAnArea() {
        assertThrows(IllegalArgumentException.class, () -> ArMath.textureBounds(new float[4]));
        assertThrows(IllegalArgumentException.class, () -> ArMath.textureBounds(new float[6]));
        assertThrows(IllegalArgumentException.class,
                () -> ArMath.textureBounds(new float[] {0f, 0f, 1f, 0f, 2f, 0f}));
    }

    /**
     * A texture held the way the image is read out is read flipped, because GL counts its rows up
     * from the bottom and the camera writes them down from the top.
     */
    @Test
    public void aTextureHeldAsTheImageIsReadFlipped() {
        assertSameMapping(flip(), ArMath.bufferToTexture(WHOLE_IMAGE));
        assertSameMapping("a crop of the image is still the whole buffer", flip(),
                ArMath.bufferToTexture(S25_TEXTURE));
    }

    @Test
    public void aTextureHeldUpsideDownIsReadAsItIs() {
        assertSameMapping(GlMath.identity(), ArMath.bufferToTexture(FLIPPED_IMAGE));
    }

    /**
     * The buffer's intrinsics from the image's agree with the ones ARCore reports for the texture
     * itself, which is how the two were found to describe the same thing.
     */
    @Test
    public void theImagesIntrinsicsDescribeTheTexture() {
        float[] fromImage = ArMath.bufferIntrinsics(424.7863f, 425.43207f, 320.38397f,
                237.11583f, 640, 480, ArMath.textureBounds(S25_TEXTURE));
        float[] fromTexture = ArMath.bufferIntrinsics(1274.3589f, 1276.2961f, 962.1519f,
                532.3475f, 1920, 1080, new float[] {0f, 0f, 1f, 1f});
        assertArrayEquals(fromTexture, fromImage, 2e-3f);
        assertEquals(1274.3589f / 1920f, fromTexture[0], TOLERANCE);
        assertEquals(532.3475f / 1080f, fromTexture[3], TOLERANCE);
        assertEquals(0f, fromTexture[4], 0f);
    }

    @Test
    public void intrinsicsNeedAnImageALensAndAnExtent() {
        float[] whole = {0f, 0f, 1f, 1f};
        assertThrows(IllegalArgumentException.class,
                () -> ArMath.bufferIntrinsics(1f, 1f, 0f, 0f, 0, 480, whole));
        assertThrows(IllegalArgumentException.class,
                () -> ArMath.bufferIntrinsics(1f, 1f, 0f, 0f, 640, 0, whole));
        assertThrows(IllegalArgumentException.class,
                () -> ArMath.bufferIntrinsics(0f, 1f, 0f, 0f, 640, 480, whole));
        assertThrows(IllegalArgumentException.class,
                () -> ArMath.bufferIntrinsics(1f, Float.NaN, 0f, 0f, 640, 480, whole));
        assertThrows(IllegalArgumentException.class,
                () -> ArMath.bufferIntrinsics(1f, 1f, 0f, 0f, 640, 480, new float[3]));
    }

    /** The view matrix undoes the pose: together they leave a point where it was. */
    @Test
    public void theViewMatrixUndoesThePose() {
        float[] view = ArMath.invertRigid(POSE);
        assertArrayEquals(GlMath.identity(), GlMath.multiply(view, POSE), TOLERANCE);
        assertArrayEquals(GlMath.identity(), GlMath.multiply(POSE, view), TOLERANCE);
        assertThrows(IllegalArgumentException.class, () -> ArMath.invertRigid(new float[9]));
    }

    /**
     * A ray starts at the camera and leaves through the principal point along the way the camera
     * looks, which is its own -z turned into the world.
     */
    @Test
    public void aRayLeavesTheCameraWhereItLooks() {
        float[] lens = {0.8f, 1.4f, 0.4f, 0.6f, 0f};
        float[] ray = ArMath.worldRay(lens, POSE, 0.4f, 1f - 0.6f);
        assertArrayEquals(new float[] {1f, 2f, 3f}, slice(ray, 0), 0f);
        // The pose turns the camera's -z onto the world's -x.
        assertArrayEquals(new float[] {-1f, 0f, 0f}, slice(ray, 3), TOLERANCE);
        assertThrows(IllegalArgumentException.class,
                () -> ArMath.worldRay(lens, new float[4], 0f, 0f));
    }

    /**
     * A point on the preview is read from the buffer through the view's transform and the turn
     * the camera asks for: the middle of the view is the middle of the buffer, and the top of a
     * portrait view is the start of a back camera's rows, turned a quarter.
     */
    @Test
    public void aPointOnThePreviewIsReadFromTheBuffer() {
        float[] noTransform = {1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f};
        float[] request = GlMath.cameraRequest(90, false);
        assertArrayEquals(new float[] {0.5f, 0.5f},
                ArMath.viewToBuffer(noTransform, 1080, 1920, request, 540f, 960f), TOLERANCE);
        // The top left of the view, (0, 1) as GL counts it, is read from (0, 0) of the buffer.
        assertArrayEquals(new float[] {0f, 0f},
                ArMath.viewToBuffer(noTransform, 1080, 1920, request, 0f, 0f), TOLERANCE);
        // A view transform that doubles the content means a point is half as far in.
        float[] doubled = {0.5f, 0f, 0f, 0f, 0.5f, 0f, 0f, 0f, 1f};
        assertArrayEquals(new float[] {0.5f, 1f},
                ArMath.viewToBuffer(doubled, 1080, 1920, GlMath.identity(), 1080f, 0f),
                TOLERANCE);
    }

    @Test
    public void aPointOnThePreviewNeedsAViewWithASize() {
        float[] noTransform = {1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f};
        assertThrows(IllegalArgumentException.class, () -> ArMath.viewToBuffer(noTransform,
                0, 10, GlMath.identity(), 0f, 0f));
        assertThrows(IllegalArgumentException.class, () -> ArMath.viewToBuffer(noTransform,
                10, 0, GlMath.identity(), 0f, 0f));
        assertThrows(IllegalArgumentException.class, () -> ArMath.viewToBuffer(new float[4],
                10, 10, GlMath.identity(), 0f, 0f));
    }

    @NonNull
    private static float[] flip() {
        float[] flip = GlMath.identity();
        flip[5] = -1f;
        flip[13] = 1f;
        return flip;
    }

    private static void assertSameMapping(@NonNull float[] expected, @NonNull float[] actual) {
        assertSameMapping("", expected, actual);
    }

    /** Two matrices that move and turn texture coordinates take every corner to the same place. */
    private static void assertSameMapping(@NonNull String message, @NonNull float[] expected,
            @NonNull float[] actual) {
        float[][] corners = {{0f, 0f}, {1f, 0f}, {0f, 1f}, {1f, 1f}, {0.3f, 0.6f}};
        for (float[] corner : corners) {
            for (int axis = 0; axis < 2; axis++) {
                float want = expected[axis] * corner[0] + expected[4 + axis] * corner[1]
                        + expected[12 + axis];
                float got = actual[axis] * corner[0] + actual[4 + axis] * corner[1]
                        + actual[12 + axis];
                assertEquals(message, want, got, TOLERANCE);
            }
        }
    }

    @NonNull
    private static float[] slice(@NonNull float[] ray, int from) {
        return new float[] {ray[from], ray[from + 1], ray[from + 2]};
    }
}
