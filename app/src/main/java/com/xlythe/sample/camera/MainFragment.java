package com.xlythe.sample.camera;

import static com.xlythe.sample.camera.MainActivity.TAG;

import android.graphics.RectF;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import com.xlythe.fragment.camera.CameraFragment;
import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.Exif;
import com.xlythe.view.camera.HighSpeedProfile;

import java.io.File;
import java.io.IOException;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * MainFragment demonstrates the standard usage of the CameraView library.
 * By extending CameraFragment, the library automatically manages camera lifecycle,
 * background thread operations, permissions, and UI binding to layout controls.
 */
public class MainFragment extends CameraFragment {
    /** The rate a slow-motion file plays back at, for working out how slow it will look. */
    private static final int PLAYBACK_FRAME_RATE = 30;

    /** Nothing selected, which is an ordinary recording. */
    private static final int ORDINARY_SPEED = -1;

    @Nullable
    private Button slowMotion;

    /** Which of the camera's published high-speed choices is selected, or -1 for none. */
    private int selectedHighSpeed = ORDINARY_SPEED;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // The layout contains a CameraView and control buttons (capture, confirm, cancel).
        // CameraFragment automatically binds to these controls using their resource IDs.
        return inflater.inflate(R.layout.fragment_main, container, false);
    }

    /**
     * Shows the focus reticle, which is the app's to draw.
     *
     * <p>CameraView reports when the camera took a focus request, where it was aimed, and what
     * came of it; everything the user sees of that is here. Driving the reticle from the camera
     * rather than from the touch is the point: the confirmation means the camera said it
     * converged, and a tap on a camera that declines to focus shows nothing at all.</p>
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        CameraView camera = view.findViewById(com.xlythe.view.camera.R.id.camera);
        FocusReticleView reticle = view.findViewById(R.id.focus_reticle);
        camera.setFocusListener(new CameraView.FocusListener() {
            @Override
            public void onFocusRequested(@NonNull RectF area) {
                reticle.showAt(area);
            }

            @Override
            public void onFocusCompleted(@NonNull RectF area, boolean locked) {
                reticle.settle(locked);
            }
        });

        slowMotion = view.findViewById(R.id.slow_motion);
        if (slowMotion != null) {
            slowMotion.setOnClickListener(button -> cycleSlowMotion(camera));
        }
    }

    /**
     * Shows the slow-motion control once there is a camera to ask.
     *
     * <p>The choices belong to the lens that is open, so there is nothing to show before one is:
     * the CameraX backend has no camera to ask until it has bound one, and either backend can
     * report a different list for the front lens than for the back.</p>
     */
    @Override
    public void onCameraOpened() {
        super.onCameraOpened();
        CameraView camera = getCameraView();
        if (slowMotion == null || camera == null) {
            return;
        }
        selectedHighSpeed = ORDINARY_SPEED;
        slowMotion.setText(R.string.btn_slow_motion_off);
        slowMotion.setVisibility(
                camera.isHighSpeedRecordingSupported() ? View.VISIBLE : View.GONE);
    }

    /**
     * Steps through the frame rates this camera publishes, and back to ordinary speed.
     *
     * <p>The list is read again on every press rather than held: switching lenses can change it,
     * and a frame rate the front camera does not have is one the library will decline.</p>
     */
    private void cycleSlowMotion(@NonNull CameraView camera) {
        List<HighSpeedProfile> published = camera.getSupportedHighSpeedProfiles();
        if (published.isEmpty()) {
            report(getString(R.string.toast_slow_motion_unavailable));
            selectHighSpeed(camera, null);
            return;
        }
        selectedHighSpeed++;
        if (selectedHighSpeed >= published.size()) {
            selectedHighSpeed = ORDINARY_SPEED;
        }
        selectHighSpeed(camera, selectedHighSpeed == ORDINARY_SPEED
                ? null : published.get(selectedHighSpeed));
    }

    private void selectHighSpeed(@NonNull CameraView camera, @Nullable HighSpeedProfile profile) {
        if (!camera.setHighSpeedProfile(profile)) {
            // Declined, which means the camera does not publish it - the list was read for a
            // different lens, or the camera closed in between.
            report(getString(R.string.toast_slow_motion_refused));
            selectedHighSpeed = ORDINARY_SPEED;
            profile = null;
        }
        if (slowMotion == null) {
            return;
        }
        if (profile == null) {
            slowMotion.setText(R.string.btn_slow_motion_off);
            report(getString(R.string.toast_slow_motion_off));
            return;
        }
        int slowdown = profile.getSlowdown(PLAYBACK_FRAME_RATE);
        slowMotion.setText(getString(R.string.btn_slow_motion_on, slowdown));
        report(getString(R.string.toast_slow_motion_on,
                profile.getSize().getWidth(), profile.getSize().getHeight(),
                profile.getFrameRate(), slowdown));
    }

    /** The CameraView this fragment's layout holds, or null before the view exists. */
    @Nullable
    private CameraView getCameraView() {
        View view = getView();
        return view == null ? null : view.findViewById(com.xlythe.view.camera.R.id.camera);
    }

    /**
     * Callback triggered by the library when a picture has been successfully taken and saved.
     * The picture is initially written to a temporary cache file.
     */
    @Override
    public void onImageCaptured(final File file) {
        // Print out metadata (EXIF data) about the captured picture using the library's Exif
        // helper, while the capture is still the cache file we were handed.
        try {
            Log.d(TAG, new Exif(file).toString());
        } catch (IOException e) {
            Log.w(TAG, "Could not read EXIF from " + file, e);
        }

        CaptureSaver.save(requireContext(), file, false /* isVideo */, uri -> {
            report(uri == null ? "Could not save the picture" : "Picture saved to " + uri);
        });
    }

    /**
     * Callback triggered by the library when a video recording successfully completes.
     */
    @Override
    public void onVideoCaptured(final File file) {
        CaptureSaver.save(requireContext(), file, true /* isVideo */, uri -> {
            report(uri == null ? "Could not save the video" : "Video saved to " + uri);
        });
    }

    /**
     * Callback triggered when video recording starts.
     */
    @Override
    protected void onRecordStart() {
        report("Recording");
    }

    /**
     * Callback triggered if camera initialization or capture fails.
     */
    @Override
    public void onFailure() {
       report("Failure");
    }

    private void report(String msg) {
        Log.d(TAG, msg);
        Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
    }

}
