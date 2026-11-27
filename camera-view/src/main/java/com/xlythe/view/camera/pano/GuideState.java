package com.xlythe.view.camera.pano;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * What the guide over the viewfinder shows, as of the newest frame.
 *
 * <p>Built on the analysis thread and read on the main thread, so it never changes once built: a
 * new frame publishes a new one. What changes faster than frames arrive - which way the phone
 * points - is not in here; the guide reads that from the sensor every time it draws.</p>
 */
final class GuideState {
    /** Turning, and frames are being painted. */
    static final int SWEEPING = 0;

    /** Turning too fast for a sharp frame, so nothing is being painted until the turn slows. */
    static final int TOO_FAST = 1;

    /** Everything asked for has been covered. */
    static final int COMPLETE = 2;

    /** The sweep is over and its picture is being written. */
    static final int SAVING = 3;

    /** What the sweep is doing. */
    @IntDef({SWEEPING, TOO_FAST, COMPLETE, SAVING})
    @Retention(RetentionPolicy.SOURCE)
    @interface Status {}

    /** Whether the sweep covers the whole sphere rather than a band. */
    final boolean photosphere;

    /** What the sweep is doing. */
    @Status final int status;

    /** How much of what was asked for is covered, from 0 to 1. */
    final float progress;

    /** The frames' width as the sensor writes them, for mapping the sphere onto the preview. */
    final int frameWidth;

    /** The frames' height as the sensor writes them. */
    final int frameHeight;

    /** The clockwise turn that stands a frame upright. */
    final int sensorRotation;

    /** Whether the frames come from a lens facing the user, whose preview is mirrored. */
    final boolean frontFacing;

    /** The frames' focal length in pixels. */
    final float focal;

    /** The yaw the sweep started at, which is the middle of {@link #thumbnail}. */
    final float startYaw;

    /** The pitch a panorama is being swept along. */
    final float bandPitch;

    /** Where each target is. */
    @NonNull final float[] targetYaws;

    /** Where each target is. */
    @NonNull final float[] targetPitches;

    /** Whether each target has been covered. */
    @NonNull final boolean[] targetsDone;

    /** The target to aim at next, or -1 when there is none. */
    final int nextTarget;

    /**
     * The sphere so far, small, with 0 where nothing has been painted; its middle column is
     * {@link #startYaw}. Null until the first frame is painted. Shared, and never written to
     * once published.
     */
    @Nullable final int[] thumbnail;

    /** The thumbnail's width, twice its height. */
    final int thumbnailWidth;

    /** Changes whenever the thumbnail does, so a reader knows when to copy it again. */
    final int thumbnailVersion;

    GuideState(boolean photosphere, @Status int status, float progress, int frameWidth,
            int frameHeight, int sensorRotation, boolean frontFacing, float focal, float startYaw,
            float bandPitch, @NonNull float[] targetYaws, @NonNull float[] targetPitches,
            @NonNull boolean[] targetsDone, int nextTarget, @Nullable int[] thumbnail,
            int thumbnailWidth, int thumbnailVersion) {
        this.photosphere = photosphere;
        this.status = status;
        this.progress = progress;
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
        this.sensorRotation = sensorRotation;
        this.frontFacing = frontFacing;
        this.focal = focal;
        this.startYaw = startYaw;
        this.bandPitch = bandPitch;
        this.targetYaws = targetYaws;
        this.targetPitches = targetPitches;
        this.targetsDone = targetsDone;
        this.nextTarget = nextTarget;
        this.thumbnail = thumbnail;
        this.thumbnailWidth = thumbnailWidth;
        this.thumbnailVersion = thumbnailVersion;
    }

    /** The same state, doing something else. */
    @NonNull
    GuideState withStatus(@Status int status) {
        return new GuideState(photosphere, status, status == COMPLETE ? 1f : progress,
                frameWidth, frameHeight, sensorRotation, frontFacing, focal, startYaw, bandPitch,
                targetYaws, targetPitches, targetsDone, status == SWEEPING ? nextTarget : -1,
                thumbnail, thumbnailWidth, thumbnailVersion);
    }

    /** Where the guide gets what it draws. */
    interface Source {
        /** What to show, or null before the first frame has arrived. */
        @Nullable
        GuideState getGuideState();

        /**
         * The rotation to draw with: the device's, as the preview on screen shows it, which is
         * a little behind the sensor because the preview is.
         *
         * @param matrix filled with the device-to-world matrix, row-major
         * @return false when there is no rotation to give
         */
        boolean getDisplayRotation(@NonNull float[] matrix);
    }
}
