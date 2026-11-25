package com.xlythe.view.camera.edit;

import android.graphics.RectF;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class VideoEditsTest {

    @Test
    public void anEmptyRequestChangesNothing() {
        VideoEdits edits = new VideoEdits.Builder().build();

        assertEquals(0, edits.getStartMs());
        assertEquals(VideoEdits.UNSET_TIME_MS, edits.getEndMs());
        assertNull(edits.getCrop());
        assertEquals(1f, edits.getScaleX(), 0f);
        assertEquals(1f, edits.getScaleY(), 0f);
        assertEquals(VideoEdits.UNSET_HEIGHT, edits.getOutputHeight());
        assertEquals(0, edits.getRotationDegrees());
        assertFalse(edits.isAudioRemoved());
        assertNull(edits.getAudioSource());
    }

    @Test
    public void everyEditIsKept() {
        File audio = new File("music.m4a");

        VideoEdits edits = new VideoEdits.Builder()
                .setTimeRangeMs(1_000, 4_000)
                .setCrop(new RectF(0.1f, 0.2f, 0.9f, 0.8f))
                .setScale(0.5f, 0.25f)
                .setOutputHeight(480)
                .setRotationDegrees(90)
                .setAudioSource(audio)
                .build();

        assertEquals(1_000, edits.getStartMs());
        assertEquals(4_000, edits.getEndMs());
        assertEquals(new RectF(0.1f, 0.2f, 0.9f, 0.8f), edits.getCrop());
        assertEquals(0.5f, edits.getScaleX(), 0f);
        assertEquals(0.25f, edits.getScaleY(), 0f);
        assertEquals(480, edits.getOutputHeight());
        assertEquals(90, edits.getRotationDegrees());
        assertFalse(edits.isAudioRemoved());
        assertEquals(audio, edits.getAudioSource());
    }

    @Test
    public void theCropIsCopiedOnTheWayInAndOut() {
        RectF crop = new RectF(0.1f, 0.1f, 0.9f, 0.9f);
        VideoEdits.Builder builder = new VideoEdits.Builder().setCrop(crop);

        crop.set(0f, 0f, 1f, 1f);
        VideoEdits edits = builder.build();
        assertEquals(new RectF(0.1f, 0.1f, 0.9f, 0.9f), edits.getCrop());

        RectF read = edits.getCrop();
        read.set(0f, 0f, 1f, 1f);
        assertEquals(new RectF(0.1f, 0.1f, 0.9f, 0.9f), edits.getCrop());
    }

    @Test
    public void theCropCanBeCleared() {
        VideoEdits edits = new VideoEdits.Builder()
                .setCrop(new RectF(0.1f, 0.1f, 0.9f, 0.9f))
                .setCrop(null)
                .build();

        assertNull(edits.getCrop());
    }

    @Test
    public void rotationIsNormalizedIntoOneTurn() {
        assertEquals(270, new VideoEdits.Builder().setRotationDegrees(-90).build().getRotationDegrees());
        assertEquals(90, new VideoEdits.Builder().setRotationDegrees(450).build().getRotationDegrees());
        assertEquals(0, new VideoEdits.Builder().setRotationDegrees(360).build().getRotationDegrees());
    }

    @Test
    public void audioCanBeRemoved() {
        VideoEdits edits = new VideoEdits.Builder().setAudioRemoved(true).build();

        assertTrue(edits.isAudioRemoved());
        assertNull(edits.getAudioSource());
    }

    @Test
    public void removingAndReplacingTheAudioIsContradictory() {
        VideoEdits.Builder builder = new VideoEdits.Builder()
                .setAudioRemoved(true)
                .setAudioSource(new File("music.m4a"));

        try {
            builder.build();
            fail("Expected removing and replacing the audio to be rejected");
        } catch (IllegalStateException expected) {
            // Expected.
        }
    }

    @Test
    public void aClipCannotStartBeforeTheRecording() {
        assertRejected(new VideoEdits.Builder().setTimeRangeMs(-1, 1_000));
    }

    @Test
    public void aClipCannotEndBeforeItStarts() {
        assertRejected(new VideoEdits.Builder().setTimeRangeMs(4_000, 1_000));
        assertRejected(new VideoEdits.Builder().setTimeRangeMs(1_000, 1_000));
    }

    @Test
    public void aClipCanRunToTheEndOfTheRecording() {
        VideoEdits edits = new VideoEdits.Builder()
                .setTimeRangeMs(1_000, VideoEdits.UNSET_TIME_MS)
                .build();

        assertEquals(1_000, edits.getStartMs());
        assertEquals(VideoEdits.UNSET_TIME_MS, edits.getEndMs());
    }

    @Test
    public void aCropMustBeANonEmptyPartOfTheFrame() {
        assertRejected(new VideoEdits.Builder().setCrop(new RectF(-0.1f, 0f, 1f, 1f)));
        assertRejected(new VideoEdits.Builder().setCrop(new RectF(0f, -0.1f, 1f, 1f)));
        assertRejected(new VideoEdits.Builder().setCrop(new RectF(0f, 0f, 1.1f, 1f)));
        assertRejected(new VideoEdits.Builder().setCrop(new RectF(0f, 0f, 1f, 1.1f)));
        assertRejected(new VideoEdits.Builder().setCrop(new RectF(0.9f, 0f, 0.1f, 1f)));
        assertRejected(new VideoEdits.Builder().setCrop(new RectF(0f, 0.9f, 1f, 0.1f)));
    }

    @Test
    public void theWholeFrameIsAValidCrop() {
        VideoEdits edits = new VideoEdits.Builder().setCrop(new RectF(0f, 0f, 1f, 1f)).build();

        assertEquals(new RectF(0f, 0f, 1f, 1f), edits.getCrop());
    }

    @Test
    public void scaleMustBePositiveAndFinite() {
        assertRejected(new VideoEdits.Builder().setScale(0f, 1f));
        assertRejected(new VideoEdits.Builder().setScale(1f, -1f));
        assertRejected(new VideoEdits.Builder().setScale(Float.NaN, 1f));
        assertRejected(new VideoEdits.Builder().setScale(1f, Float.POSITIVE_INFINITY));
    }

    @Test
    public void anOutputHeightMustBePositive() {
        assertRejected(new VideoEdits.Builder().setOutputHeight(0));
        assertRejected(new VideoEdits.Builder().setOutputHeight(-2));
    }

    private static void assertRejected(VideoEdits.Builder builder) {
        try {
            builder.build();
            fail("Expected the edits to be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
