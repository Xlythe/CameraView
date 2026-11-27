package com.xlythe.view.camera.gl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.opengl.GLES20;
import android.util.Size;

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
 * What the library's background blur hands the GPU: the frame shrunk upright into a reduced copy,
 * blurred across and down there, and the camera frame drawn back over it by the mask, into
 * whatever the caller was drawing into.
 *
 * <p>Nothing here draws pixels; a device test looks at the picture.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37, shadows = {ShadowRecordingGles20.class})
public class BackgroundBlurTest {
    private static final float TOLERANCE = 1e-6f;

    /** The external texture the camera frame is in, for a frame built by hand. */
    private static final int CAMERA_TEXTURE = 900;

    /** The framebuffer the caller is drawing into, which the blur has to leave bound. */
    private static final int TARGET = 777;

    /** A texture matrix with a mirror in it, so a frame's own matrix is recognisable. */
    private final float[] mirror = {
            -1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            1, 0, 0, 1,
    };

    private GlFrame frame;
    private BackgroundBlur blur;

    @Before
    public void setUp() {
        ShadowRecordingGles20.reset();
        frame = new GlFrame(new OesTextureProgram());
        frame.set(CAMERA_TEXTURE, mirror, 1080, 1920, 1_000L, new int[] {0, 0, 1080, 1920});
        blur = new BackgroundBlur();
        ShadowRecordingGles20.reset();
    }

    /** Until the segmenter says where the subject is, nothing is blurred. */
    @Test
    public void withNoMaskTheFrameGoesThroughUnchanged() {
        blur.onRendererStarted();

        assertFalse(blur.onDrawFrame(frame));
        assertTrue(ShadowRecordingGles20.draws().isEmpty());
    }

    /**
     * Four passes: shrink, blur across, blur down, and the composite, which goes into the caller's
     * framebuffer at the frame's size with the frame's own matrix.
     */
    @Test
    public void aMaskedFrameIsBlurredAtAReducedSizeAndDrawnBack() {
        blur.onRendererStarted();
        blur.setMask(mask(2, 2, false));
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, TARGET);

        assertTrue(blur.onDrawFrame(frame));

        List<ShadowRecordingGles20.Draw> draws = ShadowRecordingGles20.draws();
        assertEquals(4, draws.size());
        int[] reduced = {0, 0, 96, 171};
        ShadowRecordingGles20.Draw shrink = draws.get(0);
        assertArrayEquals(reduced, shrink.viewport);
        assertNotEquals(TARGET, shrink.framebuffer);
        assertEquals(CAMERA_TEXTURE, shrink.externalTexture);
        assertArrayEquals("shrunk upright, through the frame's matrix",
                mirror, shrink.textureMatrix, TOLERANCE);

        ShadowRecordingGles20.Draw across = draws.get(1);
        ShadowRecordingGles20.Draw down = draws.get(2);
        assertArrayEquals(reduced, across.viewport);
        assertNotEquals("blurred across into the other copy", shrink.framebuffer,
                across.framebuffer);
        assertEquals("and down back into the first", shrink.framebuffer, down.framebuffer);

        ShadowRecordingGles20.Draw composite = draws.get(3);
        assertEquals("drawn back into the caller's framebuffer", TARGET, composite.framebuffer);
        assertArrayEquals(new int[] {0, 0, 1080, 1920}, composite.viewport);
        assertEquals(CAMERA_TEXTURE, composite.externalTexture);
        assertArrayEquals(mirror, composite.textureMatrix, TOLERANCE);

        ShadowRecordingGles20.TextureUpload mask = null;
        for (ShadowRecordingGles20.TextureUpload upload : ShadowRecordingGles20.uploads()) {
            if (upload.internalFormat == GLES20.GL_ALPHA) {
                mask = upload;
            }
        }
        assertNotNull("the mask is uploaded as coverage alone", mask);
        assertEquals(2, mask.width);
        assertEquals(2, mask.height);
        assertArrayEquals("one byte per sample, top row first as the model gave it",
                new byte[] {(byte) 255, 0, 0, 0}, mask.pixels);
    }

    /** Each mask is read the way round its frame was: mirrored for a mirrored frame. */
    @Test
    public void aMirroredMaskIsReadMirrored() {
        blur.onRendererStarted();
        blur.setMask(mask(2, 2, true));
        blur.onDrawFrame(frame);

        assertArrayEquals(GlMath.topRowFirst(true),
                ShadowRecordingGles20.uniform("uMaskMatrix"), TOLERANCE);

        blur.setMask(mask(2, 2, false));
        blur.onDrawFrame(frame);

        assertArrayEquals(GlMath.topRowFirst(false),
                ShadowRecordingGles20.uniform("uMaskMatrix"), TOLERANCE);
    }

    /** The shrinking samples step one quarter of a reduced pixel, across and down the picture. */
    @Test
    public void theShrinkingStepFollowsThePictureAsItIsShown() {
        float[] identity = GlMath.identity();
        assertArrayEquals(new float[] {1f / 400, 0f, 0f, 1f / 200},
                BackgroundBlur.shrinkStep(identity, new Size(100, 50)), TOLERANCE);

        // A quarter turn: a step across the picture is a step along t in the camera's texture.
        float[] turned = {
                0, 1, 0, 0,
                -1, 0, 0, 0,
                0, 0, 1, 0,
                1, 0, 0, 1,
        };
        assertArrayEquals(new float[] {0f, 1f / 400, -1f / 200, 0f},
                BackgroundBlur.shrinkStep(turned, new Size(100, 50)), TOLERANCE);
    }

    @Test
    public void theReducedCopyKeepsTheShapeAtAFixedShortSide() {
        assertEquals(new Size(96, 171), BackgroundBlur.reducedSize(1080, 1920));
        assertEquals(new Size(171, 96), BackgroundBlur.reducedSize(1920, 1080));
        assertEquals(new Size(96, 128), BackgroundBlur.reducedSize(3000, 4000));
        assertEquals("a picture already that small is not shrunk",
                new Size(64, 48), BackgroundBlur.reducedSize(64, 48));
        assertEquals(new Size(1, 1), BackgroundBlur.reducedSize(0, 0));
    }

    /** The weights keep the picture as bright as it was, and fall away from the centre. */
    @Test
    public void theBlurWeightsAddUpToOneAndFallAway() {
        float[] weights = BackgroundBlur.gaussianWeights(BackgroundBlur.BLUR_RADIUS, 3f);

        assertEquals(BackgroundBlur.BLUR_RADIUS + 1, weights.length);
        float total = weights[0];
        for (int i = 1; i < weights.length; i++) {
            total += 2 * weights[i];
            assertTrue("weight " + i + " is smaller than the one before",
                    weights[i] < weights[i - 1]);
        }
        assertEquals(1f, total, 1e-5f);
    }

    /** A driver that will not build the blur leaves the frames as they are. */
    @Test
    public void aDriverThatWillNotBuildTheBlurPassesFramesThrough() {
        ShadowRecordingGles20.setShadersCompile(false);
        blur.onRendererStarted();
        blur.setMask(mask(2, 2, false));

        assertFalse(blur.onDrawFrame(frame));
        assertTrue(ShadowRecordingGles20.draws().isEmpty());
    }

    /**
     * A driver that will not draw offscreen leaves the frames as they are from then on, with the
     * caller's framebuffer bound as it was.
     */
    @Test
    public void aDriverThatWillNotDrawOffscreenPassesFramesThrough() {
        blur.onRendererStarted();
        blur.setMask(mask(2, 2, false));
        ShadowRecordingGles20.setFramebufferStatus(GLES20.GL_FRAMEBUFFER_UNSUPPORTED);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, TARGET);

        assertFalse(blur.onDrawFrame(frame));
        ShadowRecordingGles20.setFramebufferStatus(GLES20.GL_FRAMEBUFFER_COMPLETE);
        assertFalse("and does not try again", blur.onDrawFrame(frame));
        assertTrue(ShadowRecordingGles20.draws().isEmpty());
    }

    /** Stopping gives back every program and texture, and forgets the mask. */
    @Test
    public void stoppingReleasesEverything() {
        blur.onRendererStarted();
        blur.setMask(mask(2, 2, false));
        blur.onDrawFrame(frame);
        int programs = ShadowRecordingGles20.deletedProgramCount();

        blur.onRendererStopped();

        assertEquals("the shrink, the blur and the composite",
                programs + 3, ShadowRecordingGles20.deletedProgramCount());
        assertFalse("with no mask until the next arrives", blur.onDrawFrame(frame));
        blur.onRendererStopped();
    }

    private static SegmentationMask mask(int width, int height, boolean mirrored) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4)
                .order(ByteOrder.nativeOrder());
        // Only the top-left sample is subject, so a flip either way changes what is uploaded.
        for (int i = 0; i < width * height; i++) {
            buffer.putFloat(i == 0 ? 1f : 0f);
        }
        buffer.position(0);
        return new SegmentationMask(buffer, width, height, mirrored);
    }
}
