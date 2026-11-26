package com.xlythe.view.camera.edit;

import android.content.Context;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.annotation.RequiresApi;
import androidx.annotation.UiThread;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.Crop;
import androidx.media3.effect.Presentation;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.TransformMath;

import java.io.File;

/**
 * Applies a {@link VideoEdits} to a video that has already been recorded, writing the result to a
 * second file and leaving the original alone.
 *
 * <p>A fire-once class, like the players in this library: construct one per export, {@link
 * #start()} it, and the result arrives on the main thread through an {@link
 * OnVideoEditedListener}. {@link #cancel()} abandons an export in progress, and reports nothing.
 * Start and cancel on the main thread, which is the one the export itself is driven from.</p>
 *
 * <pre>{@code
 * VideoEditor editor = new VideoEditor(context, recording, clip, new VideoEdits.Builder()
 *         .setTimeRangeMs(1000, 4000)
 *         .setRotationDegrees(90)
 *         .build());
 * editor.setOnVideoEditedListener(listener);
 * editor.start();
 * }</pre>
 */
@RequiresApi(23)
@OptIn(markerClass = UnstableApi.class)
public class VideoEditor {
    private static final String TAG = VideoEditor.class.getSimpleName();

    /** Delivers the result on the main thread, where the rest of the library's callbacks land. */
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private final File mInput;
    private final File mOutput;
    private final VideoEdits mEdits;
    private final Exporter mExporter;

    @Nullable private OnVideoEditedListener mOnVideoEditedListener;

    /** True once {@link #start()} has been called, whether or not the export is still running. */
    private boolean mStarted;

    /** True while an export is in flight, so a cancel after one finished does nothing. */
    private boolean mRunning;

    // Both arms check mRunning first, so an export that reports back after it was cancelled is
    // dropped rather than handed to a listener that has stopped expecting it.
    private final Exporter.Callback mCallback = new Exporter.Callback() {
        @Override
        public void onCompleted() {
            if (!mRunning) {
                return;
            }
            mRunning = false;
            mMainHandler.post(() -> {
                OnVideoEditedListener listener = mOnVideoEditedListener;
                if (listener != null) {
                    listener.onVideoEdited(mOutput);
                }
            });
        }

        @Override
        public void onFailed(Exception exception) {
            if (!mRunning) {
                return;
            }
            Log.w(TAG, "Failed to edit " + mInput, exception);
            onFailure();
        }
    };

    /**
     * @param input the recording to read, which is never written to
     * @param output where to write the edited video, overwritten if it already exists
     * @param edits what to change
     */
    public VideoEditor(Context context, File input, File output, VideoEdits edits) {
        this(input, output, edits, new TransformerExporter(context));
    }

    @VisibleForTesting
    VideoEditor(File input, File output, VideoEdits edits, Exporter exporter) {
        this.mInput = input;
        this.mOutput = output;
        this.mEdits = edits;
        this.mExporter = exporter;
        exporter.setCallback(mCallback);
    }

    public void setOnVideoEditedListener(@Nullable OnVideoEditedListener listener) {
        mOnVideoEditedListener = listener;
    }

    @Nullable
    public OnVideoEditedListener getOnVideoEditedListener() {
        return mOnVideoEditedListener;
    }

    /** Begins the export. Call once; a second call is ignored. */
    @UiThread
    public void start() {
        if (mStarted) {
            Log.w(TAG, "VideoEditor cannot be started more than once");
            return;
        }
        mStarted = true;

        if (!mInput.exists()) {
            // The exporter would reach the same conclusion, but only after opening the file on
            // one of its own threads, which puts the failure a long way from the mistake.
            Log.w(TAG, "Cannot edit " + mInput + ", there is no such file");
            onFailure();
            return;
        }

        mRunning = true;
        mExporter.start(toComposition(mInput, mEdits), mOutput.getAbsolutePath());
    }

    /** Abandons an export in progress. No callback follows a cancel. */
    @UiThread
    public void cancel() {
        if (!mRunning) {
            return;
        }
        mRunning = false;
        mExporter.cancel();
    }

    /** True between {@link #start()} and the callback that ends the export. */
    public boolean isRunning() {
        return mRunning;
    }

    private void onFailure() {
        mRunning = false;
        mMainHandler.post(() -> {
            OnVideoEditedListener listener = mOnVideoEditedListener;
            if (listener != null) {
                listener.onFailure();
            }
        });
    }

    /**
     * Translates the edits into the composition the exporter runs.
     *
     * <p>The audio replacement is the only edit that needs more than one track: the video keeps
     * its own sequence and the replacement audio gets a second one, looped. media3 ends a looping
     * sequence with the longest sequence that is not looping, which here is the video, so the
     * replacement covers the clip whatever its own length.</p>
     */
    @VisibleForTesting
    static Composition toComposition(File input, VideoEdits edits) {
        EditedMediaItem video = toVideoItem(input, edits);

        File audioSource = edits.getAudioSource();
        if (audioSource == null) {
            return new Composition.Builder(
                    EditedMediaItemSequence.withAudioAndVideoFrom(ImmutableList.of(video)))
                    .build();
        }

        EditedMediaItem audio =
                new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(audioSource)))
                        .setRemoveVideo(true)
                        .build();
        return new Composition.Builder(
                EditedMediaItemSequence.withVideoFrom(ImmutableList.of(video)),
                new EditedMediaItemSequence.Builder(ImmutableSet.of(C.TRACK_TYPE_AUDIO))
                        .addItem(audio)
                        .setIsLooping(true)
                        .build())
                .build();
    }

    private static EditedMediaItem toVideoItem(File input, VideoEdits edits) {
        MediaItem.Builder mediaItem = new MediaItem.Builder().setUri(Uri.fromFile(input));
        if (edits.getStartMs() > 0 || edits.getEndMs() != VideoEdits.UNSET_TIME_MS) {
            MediaItem.ClippingConfiguration.Builder clipping =
                    new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(edits.getStartMs());
            if (edits.getEndMs() != VideoEdits.UNSET_TIME_MS) {
                clipping.setEndPositionMs(edits.getEndMs());
            }
            mediaItem.setClippingConfiguration(clipping.build());
        }

        // Only an explicit removal is expressed here; a replacement restricts the sequence to its
        // video track instead.
        return new EditedMediaItem.Builder(mediaItem.build())
                .setRemoveAudio(edits.isAudioRemoved())
                .setEffects(new Effects(ImmutableList.of(), toVideoEffects(edits)))
                .build();
    }

    /**
     * The frame transforms, in the order media3 applies them: the crop names a region of the
     * recorded frame, so it has to run before a rotation moves those coordinates, and the output
     * height is measured on the frame that comes out of the rotation.
     */
    private static ImmutableList<Effect> toVideoEffects(VideoEdits edits) {
        ImmutableList.Builder<Effect> effects = ImmutableList.builder();

        RectF crop = edits.getCrop();
        if (crop != null) {
            // A crop arrives as a fraction of the frame with (0, 0) at the top left; media3
            // crops in normalized device coordinates, with (-1, -1) at the bottom left.
            effects.add(new Crop(
                    toNdcX(crop.left), toNdcX(crop.right), toNdcY(crop.bottom), toNdcY(crop.top)));
        }

        if (edits.getScaleX() != 1f || edits.getScaleY() != 1f || edits.getRotationDegrees() != 0) {
            // The edits count rotation clockwise, as the camera and Exif do; media3 counts it
            // counter clockwise.
            effects.add(new ScaleAndRotateTransformation.Builder()
                    .setScale(edits.getScaleX(), edits.getScaleY())
                    .setRotationDegrees(TransformMath.within360(-edits.getRotationDegrees()))
                    .build());
        }

        if (edits.getOutputHeight() != VideoEdits.UNSET_HEIGHT) {
            effects.add(Presentation.createForHeight(edits.getOutputHeight()));
        }

        return effects.build();
    }

    private static float toNdcX(float fractionOfWidth) {
        return fractionOfWidth * 2f - 1f;
    }

    private static float toNdcY(float fractionOfHeight) {
        return 1f - fractionOfHeight * 2f;
    }

    /**
     * Reports the edited file, with the same shape as
     * {@link CameraView.OnVideoCapturedListener}. Both methods are called on the main thread.
     */
    public interface OnVideoEditedListener {
        void onVideoEdited(File file);
        void onFailure();
    }

    /**
     * The exporter a {@link VideoEditor} drives.
     *
     * <p>An export needs a decoder, an encoder and a muxer, and none of the three exist off a
     * device, so the editor reaches media3 through this interface and its tests stand in their
     * own exporter instead.</p>
     */
    interface Exporter {
        void setCallback(@NonNull Callback callback);

        void start(@NonNull Composition composition, @NonNull String outputPath);

        void cancel();

        interface Callback {
            void onCompleted();
            void onFailed(Exception exception);
        }
    }
}
