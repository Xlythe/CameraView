package com.xlythe.sample.camera;

import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xlythe.fragment.camera.CameraFragment;
import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.ar.ArFrame;
import com.xlythe.view.camera.ar.ArFrameListener;
import com.xlythe.view.camera.ar.ArTrackingFailure;

/**
 * ArFragment stands a pillar of light where you tap, and lets the room hide it.
 *
 * <p>What the AR backend is for. CameraView tracks the world and reports where the camera is
 * and how far away everything it can see is; this decides that the thing to draw is a pillar
 * and that a tap is where to put it. The drawing is in {@link PillarOfLightView}, and the point
 * of the demo is what happens when something comes between you and the pillar.</p>
 *
 * <p>The backend has to be asked for before the view is built, which is why it is set here
 * rather than in the layout - and it may not be granted: a device without ARCore gets Camera2,
 * the camera still works, and nothing is tracked. {@link CameraView#getBackend()} is how that
 * is told apart from a bug.</p>
 */
public class ArFragment extends CameraFragment implements ArFrameListener {
    private CameraView mCameraView;
    private PillarOfLightView mPillar;
    private TextView mStatus;

    /**
     * Where the screen was last tapped, waiting for a frame to ask about it.
     *
     * <p>Waiting because a hit test needs a frame and a frame only exists inside its callback,
     * so a tap cannot be answered where it happens. NaN means nothing is waiting. Written from
     * the main thread and read from the render thread, hence volatile.</p>
     */
    private volatile float mPendingTapX = Float.NaN;
    private volatile float mPendingTapY;

    /**
     * The message on screen, so that the render thread only posts when it changes.
     *
     * <p>Volatile because it is read on the render thread and written on the main one. A stale
     * read costs one redundant post, which is why this is the whole of the synchronisation.</p>
     */
    private volatile int mShownStatus;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        // Before the view is inflated, because a CameraView builds its backend as it is
        // constructed: asking afterwards would be asking the one that already exists.
        if (Build.VERSION.SDK_INT >= 24) {
            CameraView.setDefaultBackend(CameraView.Backend.AR);
        }
        View view = inflater.inflate(R.layout.fragment_ar, container, false);
        mCameraView = view.findViewById(R.id.camera);
        mPillar = view.findViewById(R.id.pillar);
        mStatus = view.findViewById(R.id.ar_status);
        mCameraView.setOnTouchListener((ignored, event) -> onCameraTouched(event));
        return view;
    }

    @Override
    public void onDestroyView() {
        // So that every other screen in this sample gets the backend it would have had.
        CameraView.setDefaultBackend(CameraView.Backend.AUTO);
        super.onDestroyView();
    }

    @Override
    public void onCameraOpened() {
        if (mCameraView.getBackend() != CameraView.Backend.AR) {
            setStatus(R.string.ar_status_unavailable);
            return;
        }
        setStatus(R.string.ar_status_looking);
        mCameraView.setArFrameListener(this);
    }

    @Override
    public void onCameraClosed() {
        super.onCameraClosed();
        mCameraView.setArFrameListener(null);
    }

    /**
     * One tracked frame, on the render thread.
     *
     * <p>Everything that needs the frame happens here, because none of it outlives the call:
     * the hit test, the pose, and reading the depth. What is left for the main thread is
     * painting, which the pillar asks for itself.</p>
     */
    @Override
    public void onArFrame(@NonNull ArFrame frame) {
        float tapX = mPendingTapX;
        if (!Float.isNaN(tapX)) {
            float[] position = frame.hitTest(tapX, mPendingTapY);
            if (position != null) {
                // Cleared only on success, so a tap at a moment the session had nothing to put
                // the pillar on is answered by the next frame rather than lost.
                mPendingTapX = Float.NaN;
                mPillar.setPosition(position);
            }
        }
        mPillar.onPose(frame.getViewMatrix(), frame.getViewProjectionMatrix(), frame.getDepth());
        showStatus(mPillar.hasPosition()
                ? R.string.ar_status_placed : R.string.ar_status_tap);
    }

    @Override
    public void onArNotTracking(@NonNull ArTrackingFailure reason) {
        mPillar.onTrackingLost();
        showStatus(describe(reason));
    }

    /**
     * Remembers where the room was tapped, for the next frame to ask about.
     *
     * <p>A hit test rather than a point a fixed distance ahead, so the pillar ends up on the
     * floor or the table that was tapped and stays there as the phone moves.</p>
     */
    private boolean onCameraTouched(@NonNull MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_UP) {
            // Returned true so that the up event still arrives: a listener that refuses the
            // down event is not offered the rest of the gesture.
            return event.getActionMasked() == MotionEvent.ACTION_DOWN;
        }
        mPendingTapY = event.getY();
        mPendingTapX = event.getX();
        setStatus(R.string.ar_status_placing);
        return true;
    }

    private static int describe(@NonNull ArTrackingFailure reason) {
        switch (reason) {
            case INSUFFICIENT_LIGHT:
                return R.string.ar_status_dark;
            case INSUFFICIENT_FEATURES:
                return R.string.ar_status_featureless;
            case EXCESSIVE_MOTION:
                return R.string.ar_status_fast;
            case CAMERA_UNAVAILABLE:
                return R.string.ar_status_no_camera;
            case STARTING:
                return R.string.ar_status_looking;
            default:
                return R.string.ar_status_lost;
        }
    }

    /** Called from the render thread, so it posts, and only when there is a change to post. */
    private void showStatus(int message) {
        if (message == mShownStatus) {
            return;
        }
        View view = getView();
        if (view != null) {
            view.post(() -> setStatus(message));
        }
    }

    private void setStatus(int message) {
        mShownStatus = message;
        if (mStatus != null) {
            mStatus.setText(message);
        }
    }
}
