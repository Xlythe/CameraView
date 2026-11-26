package com.xlythe.view.camera;

import android.graphics.Point;
import android.graphics.Rect;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A face the camera itself is tracking.
 *
 * <p>Every camera on Android runs face detection in its image signal processor, because it needs
 * faces to decide what to focus and expose for; this is that result, which costs nothing to ask
 * for and arrives with the preview rather than a frame behind it. It is the same detector the
 * manufacturer's own camera app shows boxes from.</p>
 *
 * <p>What it is not is a face analyzer. There are no landmarks beyond the eyes and mouth, no
 * expression, no identity across sessions, and a camera reporting in its simple mode gives only
 * the bounds. For anything more, run a detector of your own over
 * {@link CameraView#startFrameAnalysis}.</p>
 *
 * <p>The bounds are in the camera's own coordinates, which are not the view's: the listener is
 * given a matrix that maps them.</p>
 *
 * @see CameraView#setFaceDetectionListener
 */
public final class Face {
    /** What {@link #getId()} reports when the camera does not track faces between frames. */
    public static final int ID_UNKNOWN = -1;

    private final Rect mBounds;
    private final int mScore;
    private final int mId;
    @Nullable private final Point mLeftEye;
    @Nullable private final Point mRightEye;
    @Nullable private final Point mMouth;

    /** Builds a face from what a backend's own detector reported. */
    @androidx.annotation.RestrictTo(androidx.annotation.RestrictTo.Scope.LIBRARY)
    @NonNull
    public static Face create(@NonNull Rect bounds, int score, int id,
            @Nullable Point leftEye, @Nullable Point rightEye, @Nullable Point mouth) {
        return new Face(bounds, score, id, leftEye, rightEye, mouth);
    }

    private Face(@NonNull Rect bounds, int score, int id,
            @Nullable Point leftEye, @Nullable Point rightEye, @Nullable Point mouth) {
        mBounds = bounds;
        mScore = score;
        mId = id;
        mLeftEye = leftEye;
        mRightEye = rightEye;
        mMouth = mouth;
    }

    /** The face's rectangle, in the coordinates the listener's matrix maps from. */
    @NonNull
    public Rect getBounds() {
        return mBounds;
    }

    /** How sure the camera is, from 1 to 100. */
    public int getScore() {
        return mScore;
    }

    /**
     * An identifier that stays with this face while the camera keeps tracking it, or
     * {@link #ID_UNKNOWN}.
     *
     * <p>Useful for smoothing: a box drawn for a face that keeps its id can be interpolated
     * towards its new position instead of jumping.</p>
     */
    public int getId() {
        return mId;
    }

    /** Where the camera thinks the left eye is, if it reports landmarks at all. */
    @Nullable
    public Point getLeftEyePosition() {
        return mLeftEye;
    }

    @Nullable
    public Point getRightEyePosition() {
        return mRightEye;
    }

    @Nullable
    public Point getMouthPosition() {
        return mMouth;
    }

    /** Whether this face came with eye and mouth positions. */
    public boolean hasLandmarks() {
        return mLeftEye != null || mRightEye != null || mMouth != null;
    }

    @NonNull
    @Override
    public String toString() {
        return "Face{bounds=" + mBounds + ", score=" + mScore + ", id=" + mId
                + ", landmarks=" + hasLandmarks() + "}";
    }
}
