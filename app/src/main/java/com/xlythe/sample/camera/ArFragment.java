package com.xlythe.sample.camera;

import static com.xlythe.sample.camera.MainActivity.TAG;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.xlythe.fragment.camera.CameraFragment;
import com.xlythe.view.camera.ArAnchor;
import com.xlythe.view.camera.CameraView;

/**
 * ArFragment stands pillars of light in the room, where they stay as the phone moves and pass
 * behind whatever is in front of them.
 *
 * <p>The library follows the phone through the room, finds the surface under a tap and measures
 * how far away the scene is. Everything seen here - the light, the hints, the button - is this
 * fragment's.</p>
 */
public class ArFragment extends CameraFragment {
    /** The camera the pillars are drawn over. */
    private CameraView mCameraView;

    /** Says how tracking is going and what to do next. */
    private TextView mStatus;

    /** Starts and stops AR. */
    private MaterialButton mArButton;

    /** Draws the pillars, and holds their anchors. */
    private final PillarRenderer mRenderer = new PillarRenderer();

    /** Whether AR has been offered once, so it starts on its own the first time only. */
    private boolean mOffered;

    /** Hears how AR is tracking. */
    private final CameraView.ArListener mArListener = tracking -> {
        switch (tracking) {
            case TRACKING:
                showStatus(R.string.ar_tap_to_place);
                break;
            case INSUFFICIENT_LIGHT:
                showStatus(R.string.ar_too_dark);
                break;
            case EXCESSIVE_MOTION:
                showStatus(R.string.ar_too_fast);
                break;
            case INSUFFICIENT_FEATURES:
                showStatus(R.string.ar_too_plain);
                break;
            case STOPPED:
                showStatus(R.string.ar_stopped);
                updateButton();
                break;
            case INITIALIZING:
            default:
                showStatus(R.string.ar_initializing);
                break;
        }
    };

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_ar, container, false);
        mCameraView = view.findViewById(R.id.camera);
        mStatus = view.findViewById(R.id.ar_status);
        mArButton = view.findViewById(R.id.ar_toggle);
        mCameraView.setFrameRenderer(mRenderer);
        mArButton.setOnClickListener(v -> toggleAr());
        listenForTaps();
        return view;
    }

    /**
     * Stands a pillar wherever the preview is tapped, leaving the camera's own touch handling to
     * it. Taps, not every touch: a drag that ends on a surface is not a request to place one.
     */
    private void listenForTaps() {
        GestureDetector taps = new GestureDetector(requireContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapUp(@NonNull MotionEvent event) {
                        place(event.getX(), event.getY());
                        return false;
                    }
                });
        mCameraView.setOnTouchListener((v, event) -> {
            if (taps.onTouchEvent(event) || event.getActionMasked() == MotionEvent.ACTION_UP) {
                // A tap is a click, for whoever is listening for one.
                v.performClick();
            }
            return false;
        });
    }

    @Override
    public void onCameraOpened() {
        if (!mOffered) {
            mOffered = true;
            startAr();
        }
        updateButton();
    }

    private void toggleAr() {
        if (mCameraView.isInAr()) {
            mCameraView.exitAr();
            mRenderer.clear();
            showStatus(R.string.ar_off);
        } else {
            startAr();
        }
        updateButton();
    }

    private void startAr() {
        // Asked for by the fragment before the camera opens, so this only fails for a camera
        // that never opened.
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (!mCameraView.isArSupported()) {
            showStatus(R.string.ar_unsupported);
            return;
        }
        if (mCameraView.enterAr(mArListener)) {
            showStatus(R.string.ar_initializing);
        } else {
            showStatus(R.string.ar_unsupported);
        }
    }

    private void place(float x, float y) {
        if (!mCameraView.isInAr()) {
            return;
        }
        ArAnchor anchor = mCameraView.addArAnchor(x, y);
        if (anchor == null) {
            showStatus(R.string.ar_no_surface);
            return;
        }
        mRenderer.add(anchor);
        showStatus(mCameraView.isArDepthSupported()
                ? R.string.ar_placed_with_depth : R.string.ar_placed_without_depth);
    }

    private void updateButton() {
        if (mArButton != null) {
            mArButton.setText(mCameraView.isInAr() ? R.string.btn_ar_off : R.string.btn_ar_on);
        }
    }

    private void showStatus(@StringRes int message) {
        if (mStatus != null) {
            mStatus.setText(message);
        }
        Log.d(TAG, getString(message));
    }

    @Override
    public void onImageSaved(@NonNull Uri uri) {
        report("Picture saved to " + uri);
    }

    @Override
    public void onFailure() {
        report("Failure");
    }

    private void report(String message) {
        Log.d(TAG, message);
        Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
    }
}
