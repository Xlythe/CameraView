package com.xlythe.view.camera.edit;

import android.graphics.RectF;
import android.net.Uri;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.Crop;
import androidx.media3.effect.Presentation;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;

import com.google.common.collect.ImmutableList;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Covers the translation from {@link VideoEdits} into the composition media3 exports. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
@OptIn(markerClass = UnstableApi.class)
public class VideoEditorTranslationTest {

    private static final File INPUT = new File("/recordings/clip.mp4");

    @Test
    public void anEmptyRequestIsACopyOfTheRecording() {
        Composition composition = VideoEditor.toComposition(INPUT, new VideoEdits.Builder().build());

        assertEquals(1, composition.sequences.size());
        EditedMediaItemSequence sequence = composition.sequences.get(0);
        assertTrue(sequence.trackTypes.contains(C.TRACK_TYPE_AUDIO));
        assertTrue(sequence.trackTypes.contains(C.TRACK_TYPE_VIDEO));
        assertFalse(sequence.isLooping);

        EditedMediaItem item = onlyItem(composition, 0);
        assertEquals(Uri.fromFile(INPUT), item.mediaItem.localConfiguration.uri);
        assertEquals(MediaItem.ClippingConfiguration.UNSET, item.mediaItem.clippingConfiguration);
        assertFalse(item.removeAudio);
        assertFalse(item.removeVideo);
        assertTrue(item.effects.videoEffects.isEmpty());
        assertTrue(item.effects.audioProcessors.isEmpty());
    }

    @Test
    public void aTimeRangeBecomesAClippingConfiguration() {
        Composition composition = VideoEditor.toComposition(INPUT, new VideoEdits.Builder()
                .setTimeRangeMs(1_000, 4_000)
                .build());

        MediaItem.ClippingConfiguration clipping = onlyItem(composition, 0).mediaItem.clippingConfiguration;
        assertEquals(1_000, clipping.startPositionMs);
        assertEquals(4_000, clipping.endPositionMs);
    }

    @Test
    public void aTimeRangeThatStartsAtTheBeginningStillClipsTheEnd() {
        Composition composition = VideoEditor.toComposition(INPUT, new VideoEdits.Builder()
                .setTimeRangeMs(0, 4_000)
                .build());

        MediaItem.ClippingConfiguration clipping = onlyItem(composition, 0).mediaItem.clippingConfiguration;
        assertEquals(0, clipping.startPositionMs);
        assertEquals(4_000, clipping.endPositionMs);
    }

    @Test
    public void anOpenEndedTimeRangeClipsOnlyTheStart() {
        Composition composition = VideoEditor.toComposition(INPUT, new VideoEdits.Builder()
                .setTimeRangeMs(1_000, VideoEdits.UNSET_TIME_MS)
                .build());

        MediaItem.ClippingConfiguration clipping = onlyItem(composition, 0).mediaItem.clippingConfiguration;
        assertEquals(1_000, clipping.startPositionMs);
        assertEquals(C.TIME_END_OF_SOURCE, clipping.endPositionUs);
    }

    @Test
    public void aCropBecomesTheSameRegionInDeviceCoordinates() {
        // The left half and the top quarter of the frame, which is asymmetric in both axes, so a
        // crop that reached media3 mirrored or with its axes swapped would not survive this.
        Composition composition = VideoEditor.toComposition(INPUT, new VideoEdits.Builder()
                .setCrop(new RectF(0f, 0f, 0.5f, 0.25f))
                .build());

        ImmutableList<Effect> effects = onlyItem(composition, 0).effects.videoEffects;
        assertEquals(1, effects.size());
        Crop crop = (Crop) effects.get(0);
        assertEquals(new Size(100, 200), crop.configure(200, 800));
    }

    @Test
    public void theWholeFrameIsACropThatDoesNothing() {
        Composition composition = VideoEditor.toComposition(INPUT, new VideoEdits.Builder()
                .setCrop(new RectF(0f, 0f, 1f, 1f))
                .build());

        Crop crop = (Crop) onlyItem(composition, 0).effects.videoEffects.get(0);
        assertEquals(new Size(200, 800), crop.configure(200, 800));
    }

    @Test
    public void aClockwiseRotationBecomesACounterClockwiseOne() {
        Composition composition = VideoEditor.toComposition(INPUT, new VideoEdits.Builder()
                .setRotationDegrees(90)
                .build());

        ScaleAndRotateTransformation transformation =
                (ScaleAndRotateTransformation) onlyItem(composition, 0).effects.videoEffects.get(0);
        assertEquals(270f, transformation.rotationDegrees, 0f);
        assertEquals(1f, transformation.scaleX, 0f);
        assertEquals(1f, transformation.scaleY, 0f);
    }

    @Test
    public void aScaleIsPassedThroughUntouched() {
        Composition composition = VideoEditor.toComposition(INPUT, new VideoEdits.Builder()
                .setScale(0.5f, 0.25f)
                .build());

        ScaleAndRotateTransformation transformation =
                (ScaleAndRotateTransformation) onlyItem(composition, 0).effects.videoEffects.get(0);
        assertEquals(0.5f, transformation.scaleX, 0f);
        assertEquals(0.25f, transformation.scaleY, 0f);
        assertEquals(0f, transformation.rotationDegrees, 0f);
    }

    @Test
    public void scalingOneAxisIsEnoughToTransformTheFrame() {
        Composition composition = VideoEditor.toComposition(INPUT, new VideoEdits.Builder()
                .setScale(1f, 0.5f)
                .build());

        ScaleAndRotateTransformation transformation =
                (ScaleAndRotateTransformation) onlyItem(composition, 0).effects.videoEffects.get(0);
        assertEquals(1f, transformation.scaleX, 0f);
        assertEquals(0.5f, transformation.scaleY, 0f);
    }

    @Test
    public void anOutputHeightBecomesAPresentation() {
        Composition composition = VideoEditor.toComposition(INPUT, new VideoEdits.Builder()
                .setOutputHeight(480)
                .build());

        ImmutableList<Effect> effects = onlyItem(composition, 0).effects.videoEffects;
        assertEquals(1, effects.size());
        Presentation presentation = (Presentation) effects.get(0);
        assertEquals(480, presentation.configure(1920, 1080).getHeight());
    }

    @Test
    public void theFrameTransformsRunInTheOrderTheyAreDescribedIn() {
        Composition composition = VideoEditor.toComposition(INPUT, new VideoEdits.Builder()
                .setCrop(new RectF(0f, 0f, 0.5f, 0.5f))
                .setRotationDegrees(180)
                .setOutputHeight(480)
                .build());

        ImmutableList<Effect> effects = onlyItem(composition, 0).effects.videoEffects;
        assertEquals(3, effects.size());
        assertTrue(effects.get(0) instanceof Crop);
        assertTrue(effects.get(1) instanceof ScaleAndRotateTransformation);
        assertTrue(effects.get(2) instanceof Presentation);
    }

    @Test
    public void removingTheAudioLeavesOneSequence() {
        Composition composition = VideoEditor.toComposition(INPUT, new VideoEdits.Builder()
                .setAudioRemoved(true)
                .build());

        assertEquals(1, composition.sequences.size());
        assertTrue(onlyItem(composition, 0).removeAudio);
    }

    @Test
    public void replacingTheAudioAddsALoopingAudioOnlySequence() {
        File music = new File("/music/track.m4a");

        Composition composition = VideoEditor.toComposition(INPUT, new VideoEdits.Builder()
                .setAudioSource(music)
                .build());

        assertEquals(2, composition.sequences.size());

        EditedMediaItemSequence video = composition.sequences.get(0);
        assertEquals(ImmutableList.of(C.TRACK_TYPE_VIDEO), ImmutableList.copyOf(video.trackTypes));
        assertFalse(video.isLooping);
        assertEquals(Uri.fromFile(INPUT), onlyItem(composition, 0).mediaItem.localConfiguration.uri);

        EditedMediaItemSequence audio = composition.sequences.get(1);
        assertEquals(ImmutableList.of(C.TRACK_TYPE_AUDIO), ImmutableList.copyOf(audio.trackTypes));
        assertTrue(audio.isLooping);

        EditedMediaItem audioItem = onlyItem(composition, 1);
        assertEquals(Uri.fromFile(music), audioItem.mediaItem.localConfiguration.uri);
        assertTrue(audioItem.removeVideo);
    }

    @Test
    public void aTrimAndAReplacementSoundtrackCombine() {
        Composition composition = VideoEditor.toComposition(INPUT, new VideoEdits.Builder()
                .setTimeRangeMs(1_000, 4_000)
                .setAudioSource(new File("/music/track.m4a"))
                .build());

        assertEquals(2, composition.sequences.size());
        assertEquals(1_000, onlyItem(composition, 0).mediaItem.clippingConfiguration.startPositionMs);
        assertEquals(MediaItem.ClippingConfiguration.UNSET,
                onlyItem(composition, 1).mediaItem.clippingConfiguration);
    }

    private static EditedMediaItem onlyItem(Composition composition, int sequence) {
        ImmutableList<EditedMediaItem> items = composition.sequences.get(sequence).editedMediaItems;
        assertEquals(1, items.size());
        return items.get(0);
    }
}
