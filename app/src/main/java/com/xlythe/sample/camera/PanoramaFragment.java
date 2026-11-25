package com.xlythe.sample.camera;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xlythe.fragment.camera.CameraFragment;
import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.pano.PanoramaSession;

import java.io.File;

/**
 * PanoramaFragment sweeps a panorama or a photosphere and shows it filling in.
 *
 * <p>What the library asks of an app is two calls and a listener: start a sweep, be told how far
 * along it is and where to point next, and stop it with a file. Everything on screen here - the
 * arrow, the bar, the thumbnail of the sphere so far - is this app deciding how to ask somebody
 * to turn round, which is exactly the part a camera library should stay out of.</p>
 *
 * <p>The instruction to turn on the spot is not decoration. Frames are placed by rotation
 * alone, so walking while sweeping is the one thing that cannot be stitched out.</p>
 */
public class PanoramaFragment extends CameraFragment {
    private CameraView mCameraView;
    private TextView mStatus;
    private Button mStartStop;
    private ImageView mPreview;
    private View mArrow;

    private CameraView.PanoramaMode mMode = CameraView.PanoramaMode.PANORAMA;
    private boolean mSweeping;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_panorama, container, false);
        mCameraView = view.findViewById(R.id.camera);
        mStatus = view.findViewById(R.id.panorama_status);
        mStartStop = view.findViewById(R.id.panorama_start_stop);
        mPreview = view.findViewById(R.id.panorama_preview);
        mArrow = view.findViewById(R.id.panorama_arrow);
        mStartStop.setOnClickListener(ignored -> toggleSweep());
        view.findViewById(R.id.panorama_mode).setOnClickListener(ignored -> toggleMode());
        showMode();
        return view;
    }

    @Override
    public void onCameraOpened() {
        if (!mCameraView.isFrameAnalysisSupported()) {
            mStatus.setText(R.string.panorama_status_unsupported);
            mStartStop.setEnabled(false);
            return;
        }
        mStartStop.setEnabled(true);
        mStatus.setText(R.string.panorama_status_ready);
    }

    @Override
    public void onCameraClosed() {
        super.onCameraClosed();
        if (mSweeping) {
            // Saved rather than dropped: the camera closing is not a reason to lose what was
            // already swept.
            stopSweep();
        }
    }

    private void toggleMode() {
        if (mSweeping) {
            return;
        }
        mMode = mMode == CameraView.PanoramaMode.PANORAMA
                ? CameraView.PanoramaMode.PHOTOSPHERE : CameraView.PanoramaMode.PANORAMA;
        showMode();
    }

    private void showMode() {
        Button mode = requireView().findViewById(R.id.panorama_mode);
        mode.setText(mMode == CameraView.PanoramaMode.PANORAMA
                ? R.string.panorama_mode_flat : R.string.panorama_mode_sphere);
    }

    private void toggleSweep() {
        if (mSweeping) {
            stopSweep();
        } else {
            startSweep();
        }
    }

    @SuppressLint("MissingPermission")
    private void startSweep() {
        try {
            mCameraView.startPanorama(mMode, new PanoramaSession.Listener() {
                @Override
                public void onPanoramaProgress(float progress, float nextYaw, float nextPitch) {
                    showProgress(progress, nextYaw, nextPitch);
                }

                @Override
                public void onPanoramaCaptured(@NonNull File file) {
                    mStatus.setText(getString(R.string.panorama_status_saved, file.getName()));
                    mPreview.setImageURI(null);
                    mPreview.setImageURI(android.net.Uri.fromFile(file));
                    // Into the gallery, the same as a still, so the panorama can be opened in a
                    // viewer that understands the metadata it carries.
                    CaptureSaver.save(requireContext(), file, false, saved -> {});
                }

                @Override
                public void onPanoramaFailed() {
                    mStatus.setText(R.string.panorama_status_failed);
                }
            });
        } catch (RuntimeException error) {
            // startPanorama refuses a device that cannot report its rotation, or a backend that
            // cannot deliver frames, rather than accepting a sweep that could never progress.
            mStatus.setText(getString(R.string.panorama_status_refused, error.getMessage()));
            return;
        }
        mSweeping = true;
        mStartStop.setText(R.string.panorama_stop);
        mStatus.setText(R.string.panorama_status_turn);
    }

    private void stopSweep() {
        mSweeping = false;
        mStartStop.setText(R.string.panorama_start);
        mArrow.setVisibility(View.GONE);
        mStatus.setText(R.string.panorama_status_saving);
        mCameraView.stopPanorama(new File(requireContext().getCacheDir(),
                "panorama-" + System.currentTimeMillis() + ".jpg"));
    }

    /**
     * Points at the next gap and shows the sphere so far.
     *
     * <p>The arrow is turned to the direction the sweep wants rather than animated along a path,
     * because the gap it is pointing at can be anywhere - somebody who drifted off the row is
     * sent back to the hole they left.</p>
     */
    private void showProgress(float progress, float nextYaw, float nextPitch) {
        PanoramaSession session = mCameraView.getPanoramaSession();
        if (session == null) {
            return;
        }
        mStatus.setText(getString(R.string.panorama_status_progress, Math.round(progress * 100)));
        if (Float.isNaN(nextYaw)) {
            mArrow.setVisibility(View.GONE);
            mStatus.setText(R.string.panorama_status_complete);
        } else {
            mArrow.setVisibility(View.VISIBLE);
            // The direction from where the camera is now to where the gap is, as an angle on
            // screen: a gap to the right points right, one above points up.
            float[] at = session.getLastDirection();
            if (at != null) {
                float dYaw = nextYaw - at[0];
                float dPitch = nextPitch - at[1];
                mArrow.setRotation((float) Math.toDegrees(Math.atan2(-dPitch, dYaw)));
            }
        }
        // The same bitmap every time, so it has to be told it changed.
        mPreview.setImageBitmap(null);
        mPreview.setImageBitmap(session.getBitmap());
    }
}
