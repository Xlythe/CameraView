package com.xlythe.view.camera.ar;

import android.media.Image;
import android.opengl.Matrix;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;

import com.google.ar.core.Camera;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Pose;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.NotYetAvailableException;
import com.xlythe.view.camera.WorldOverlay;

import java.util.List;

/**
 * An {@link ArFrame} backed by ARCore, filled in once per frame and reused.
 *
 * <p>Reused rather than allocated because this is called on the render thread thirty to sixty
 * times a second, and four matrices and a wrapper per frame is garbage the preview pays for.
 * That is why {@link ArFrame} is documented as valid only inside its callback.</p>
 *
 * @hide
 */
@RequiresApi(24)
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class ArCoreFrame implements ArFrame {
    private static final String TAG = "ArCoreFrame";

    /**
     * The near and far planes the projection is built with, in metres.
     *
     * <p>They decide nothing about what is drawn here - no depth buffer is involved - but they
     * do set how much precision the projection has. A near plane closer than this makes the
     * numbers coarse everywhere else, and ten centimetres is nearer than anything a phone can
     * focus on anyway.</p>
     */
    private static final float NEAR_METRES = 0.1f;
    private static final float FAR_METRES = 100f;

    private final float[] mViewMatrix = new float[16];
    private final float[] mProjectionMatrix = new float[16];
    private final float[] mViewProjectionMatrix = new float[16];

    @Nullable private Frame mFrame;
    @Nullable private DepthLookup mDepth;
    private int mViewWidth;
    private int mViewHeight;
    private long mTimestampNanos;

    /**
     * Takes the pose and the depth out of a frame, ready to be handed to a listener.
     *
     * <p>The depth image is read here and closed before this returns, because it is one of a
     * small pool ARCore will not refill until it is given back - holding one for the duration
     * of a callback would stall tracking within a few frames.</p>
     *
     * @param depthSupported whether to ask for depth at all, which saves a throw per frame on a
     *     device that has none
     * @return whether this frame is usable, which is false when it is not being tracked
     */
    public boolean set(@NonNull Frame frame, int viewWidth, int viewHeight,
            boolean depthSupported) {
        Camera camera = frame.getCamera();
        if (camera.getTrackingState() != TrackingState.TRACKING) {
            // The pose of an untracked frame is not an approximation, it is meaningless, and
            // anything drawn from one slides around the room.
            clear();
            return false;
        }
        mFrame = frame;
        mViewWidth = viewWidth;
        mViewHeight = viewHeight;
        mTimestampNanos = frame.getTimestamp();
        camera.getViewMatrix(mViewMatrix, 0);
        camera.getProjectionMatrix(mProjectionMatrix, 0, NEAR_METRES, FAR_METRES);
        Matrix.multiplyMM(mViewProjectionMatrix, 0, mProjectionMatrix, 0, mViewMatrix, 0);
        mDepth = depthSupported ? readDepth(frame, viewWidth, viewHeight) : null;
        return true;
    }

    @Nullable
    private static DepthLookup readDepth(@NonNull Frame frame, int viewWidth, int viewHeight) {
        // The smoothed depth rather than the raw: it fills the gaps a sparse reading leaves and
        // is reused from an earlier frame when a new one is not ready, both of which suit
        // deciding what is in front of what better than a reading that is absent half the time.
        try (Image depth = frame.acquireDepthImage16Bits()) {
            Image.Plane plane = depth.getPlanes()[0];
            return new DepthLookup(plane.getBuffer(), depth.getWidth(), depth.getHeight(),
                    plane.getRowStride(), viewWidth, viewHeight);
        } catch (NotYetAvailableException notYet) {
            // Ordinary for the first frames after a resume, and not worth logging every time.
            return null;
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to read depth from this frame", error);
            return null;
        }
    }

    /**
     * Why the given frame is not tracked, translated out of ARCore's enum.
     *
     * <p>A frame that is not tracked and reports no reason is one the session has not finished
     * starting from, which is reported as {@link ArTrackingFailure#STARTING} rather than as
     * "none": there is a difference between nothing being wrong and nothing being known, and
     * an app showing a message to the person holding the phone needs the first of them.</p>
     */
    @NonNull
    public static ArTrackingFailure failureFor(@NonNull Frame frame) {
        switch (frame.getCamera().getTrackingFailureReason()) {
            case INSUFFICIENT_LIGHT:
                return ArTrackingFailure.INSUFFICIENT_LIGHT;
            case INSUFFICIENT_FEATURES:
                return ArTrackingFailure.INSUFFICIENT_FEATURES;
            case EXCESSIVE_MOTION:
                return ArTrackingFailure.EXCESSIVE_MOTION;
            case CAMERA_UNAVAILABLE:
                return ArTrackingFailure.CAMERA_UNAVAILABLE;
            case BAD_STATE:
                return ArTrackingFailure.BAD_STATE;
            case NONE:
                return ArTrackingFailure.STARTING;
            default:
                return ArTrackingFailure.UNKNOWN;
        }
    }

    /** Drops the frame, so that a listener that kept this past its callback reads nothing. */
    public void clear() {
        mFrame = null;
        mDepth = null;
    }

    @NonNull
    @Override
    public float[] getViewMatrix() {
        return mViewMatrix;
    }

    @NonNull
    @Override
    public float[] getViewProjectionMatrix() {
        return mViewProjectionMatrix;
    }

    @Nullable
    @Override
    public WorldOverlay.SceneDepth getDepth() {
        return mDepth;
    }

    @Override
    public long getTimestampNanos() {
        return mTimestampNanos;
    }

    @Nullable
    @Override
    public float[] hitTest(float viewX, float viewY) {
        Frame frame = mFrame;
        if (frame == null || mViewWidth <= 0 || mViewHeight <= 0) {
            return null;
        }
        try {
            List<HitResult> hits = frame.hitTest(viewX, viewY);
            for (HitResult hit : hits) {
                Trackable trackable = hit.getTrackable();
                // The nearest hit on something still being tracked. An untracked trackable is
                // one ARCore has stopped believing in, and a position on it drifts.
                if (trackable.getTrackingState() == TrackingState.TRACKING) {
                    Pose pose = hit.getHitPose();
                    return new float[] {pose.tx(), pose.ty(), pose.tz()};
                }
            }
            return null;
        } catch (RuntimeException error) {
            // hitTest throws when the frame is not tracked, which the caller cannot prevent on
            // a frame it was handed.
            Log.w(TAG, "Unable to hit test this frame", error);
            return null;
        }
    }
}
