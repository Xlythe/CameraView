package com.xlythe.view.camera.gl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.opengl.GLES20;

import com.xlythe.view.camera.shadow.ShadowRecordingGles20;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * What a background replacer hands the GPU: the newest mask as coverage, the colour or image to
 * put behind the subject, and the camera frame through its own matrix.
 *
 * <p>How a mask is read is asserted in {@link SegmentationMaskTest}. This is the renderer that
 * composites with one, which decides when a mask is uploaded, what the shader is told about
 * whether there is one, and what happens on a driver that will not build the program.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37, shadows = {ShadowRecordingGles20.class})
public class BackgroundReplacerTest {
    private static final float TOLERANCE = 1e-6f;

    /** The external texture the camera frame is in, for a frame built by hand. */
    private static final int CAMERA_TEXTURE = 900;

    private GlFrame frame;
    private BackgroundReplacer replacer;

    /** A texture matrix with a mirror in it, so a frame's own matrix is recognisable. */
    private final float[] mirror = {
            -1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            1, 0, 0, 1,
    };

    @Before
    public void setUp() {
        ShadowRecordingGles20.reset();
        frame = new GlFrame(new OesTextureProgram());
        frame.set(CAMERA_TEXTURE, mirror, 1080, 1920, 1_000L, new int[] {0, 0, 1080, 1920});
        replacer = new BackgroundReplacer();
    }

    /**
     * Before the model has said anything the shader is told there is no mask, which has it treat
     * every pixel as subject - the camera frame, whole, rather than a background flashing in.
     */
    @Test
    public void withNoMaskTheCameraFrameComesThroughWhole() {
        replacer.onRendererStarted();

        assertTrue("the replacer draws the frame itself", replacer.onDrawFrame(frame));

        assertArrayEquals(new float[] {0f}, ShadowRecordingGles20.uniform("uHasMask"), TOLERANCE);
        assertTrue("there is nothing to upload yet", ShadowRecordingGles20.uploads().isEmpty());
    }

    /** A mask is uploaded as one byte of coverage per sample, at the mask's own size. */
    @Test
    public void aMaskIsUploadedAsOneByteOfCoveragePerSample() {
        replacer.onRendererStarted();
        replacer.setMask(mask(2, 2, 0f, 0.5f, 1f, 0.25f));

        replacer.onDrawFrame(frame);

        List<ShadowRecordingGles20.TextureUpload> uploads = ShadowRecordingGles20.uploads();
        assertEquals(1, uploads.size());
        ShadowRecordingGles20.TextureUpload upload = uploads.get(0);
        assertEquals("coverage is one channel", GLES20.GL_ALPHA, upload.internalFormat);
        assertEquals(2, upload.width);
        assertEquals(2, upload.height);
        assertArrayEquals(new byte[] {0, (byte) 128, (byte) 255, 64}, upload.pixels);
        assertArrayEquals(new float[] {1f}, ShadowRecordingGles20.uniform("uHasMask"), TOLERANCE);
    }

    /**
     * Masks replace rather than queue: of two that arrive between frames only the newer is
     * uploaded, and it stays in use until another arrives.
     */
    @Test
    public void onlyTheNewestMaskIsUploadedAndItStays() {
        replacer.onRendererStarted();
        replacer.setMask(mask(3, 1, 1f, 1f, 1f));
        replacer.setMask(mask(2, 2, 0f, 0f, 1f, 1f));

        replacer.onDrawFrame(frame);
        replacer.onDrawFrame(frame);

        List<ShadowRecordingGles20.TextureUpload> uploads = ShadowRecordingGles20.uploads();
        assertEquals("one upload, for the newest mask, and none for a frame with no new mask",
                1, uploads.size());
        assertEquals(2, uploads.get(0).width);
        assertArrayEquals("the mask from the earlier frame is still the one in use",
                new float[] {1f}, ShadowRecordingGles20.uniform("uHasMask"), TOLERANCE);
    }

    /** With nothing chosen, what goes behind the subject is opaque black. */
    @Test
    public void theBackgroundIsOpaqueBlackUntilChosen() {
        replacer.onRendererStarted();
        replacer.onDrawFrame(frame);

        assertArrayEquals(new float[] {0f, 0f, 0f, 1f},
                ShadowRecordingGles20.uniform("uBackgroundColor"), TOLERANCE);
        assertArrayEquals(new float[] {0f},
                ShadowRecordingGles20.uniform("uHasBackgroundImage"), TOLERANCE);
    }

    /** A colour int reaches the shader as straight red, green, blue and alpha in 0..1. */
    @Test
    public void theBackgroundColourReachesTheShaderAsStraightRgba() {
        replacer.setBackgroundColor(0x80FF8000);
        replacer.onRendererStarted();
        replacer.onDrawFrame(frame);

        assertArrayEquals(new float[] {1f, 128 / 255f, 0f, 128 / 255f},
                ShadowRecordingGles20.uniform("uBackgroundColor"), TOLERANCE);
    }

    /** An image takes over from the colour once it is uploaded, and null gives the colour back. */
    @Test
    public void anImageReplacesTheColourUntilItIsCleared() {
        replacer.onRendererStarted();
        replacer.setBackgroundImage(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888));
        replacer.onDrawFrame(frame);
        assertArrayEquals(new float[] {1f},
                ShadowRecordingGles20.uniform("uHasBackgroundImage"), TOLERANCE);

        replacer.setBackgroundImage(null);
        replacer.onDrawFrame(frame);
        assertArrayEquals(new float[] {0f},
                ShadowRecordingGles20.uniform("uHasBackgroundImage"), TOLERANCE);
    }

    /** A bitmap recycled before the render thread reached it is left out rather than read. */
    @Test
    public void aRecycledImageIsLeftOut() {
        Bitmap background = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        background.recycle();
        replacer.onRendererStarted();
        replacer.setBackgroundImage(background);

        replacer.onDrawFrame(frame);

        assertArrayEquals(new float[] {0f},
                ShadowRecordingGles20.uniform("uHasBackgroundImage"), TOLERANCE);
    }

    /** The camera frame is sampled through its own matrix, across the whole picture. */
    @Test
    public void theCameraFrameIsSampledThroughItsOwnMatrix() {
        replacer.onRendererStarted();
        replacer.onDrawFrame(frame);

        List<ShadowRecordingGles20.Draw> draws = ShadowRecordingGles20.draws();
        assertEquals("one quad per frame", 1, draws.size());
        assertArrayEquals(mirror, draws.get(0).textureMatrix, TOLERANCE);
        assertEquals(CAMERA_TEXTURE, draws.get(0).externalTexture);
        assertArrayEquals(new int[] {0, 0, 1080, 1920}, draws.get(0).viewport);
    }

    /**
     * A driver that will not compile the compositor leaves the camera working: every frame goes
     * back to the library, which draws it as it is.
     */
    @Test
    public void aShaderTheDriverRefusesLeavesFramesAsTheyAre() {
        ShadowRecordingGles20.setShadersCompile(false);
        replacer.onRendererStarted();
        replacer.setMask(mask(1, 1, 1f));

        assertFalse(replacer.onDrawFrame(frame));
        assertTrue("nothing drawn", ShadowRecordingGles20.draws().isEmpty());
        assertTrue("and nothing uploaded", ShadowRecordingGles20.uploads().isEmpty());
    }

    /**
     * Stopping gives the program and both textures back, and forgets the mask with its texture,
     * so a renderer started again shows the whole frame until the model reports.
     */
    @Test
    public void stoppingGivesEverythingBackAndForgetsTheMask() {
        replacer.onRendererStarted();
        replacer.setMask(mask(1, 1, 1f));
        replacer.setBackgroundImage(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888));
        replacer.onDrawFrame(frame);
        int program = ShadowRecordingGles20.draws().get(0).program;
        int maskTexture = ShadowRecordingGles20.uploads().get(0).texture;
        int backgroundTexture = ShadowRecordingGles20.draws().get(0).texture2d;
        assertNotEquals("the mask and the background are separate textures",
                maskTexture, backgroundTexture);

        replacer.onRendererStopped();

        assertTrue(ShadowRecordingGles20.isProgramDeleted(program));
        assertTrue(ShadowRecordingGles20.isTextureDeleted(maskTexture));
        assertTrue(ShadowRecordingGles20.isTextureDeleted(backgroundTexture));
        assertFalse("a stopped replacer draws nothing", replacer.onDrawFrame(frame));

        replacer.onRendererStarted();
        replacer.onDrawFrame(frame);
        assertArrayEquals(new float[] {0f}, ShadowRecordingGles20.uniform("uHasMask"), TOLERANCE);
    }

    /** A mask of these confidences, row major, in the native-order floats a model hands over. */
    private static SegmentationMask mask(int width, int height, float... confidences) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(confidences.length * Float.BYTES)
                .order(ByteOrder.nativeOrder());
        for (float confidence : confidences) {
            buffer.putFloat(confidence);
        }
        buffer.rewind();
        return new SegmentationMask(buffer, width, height, false);
    }
}
