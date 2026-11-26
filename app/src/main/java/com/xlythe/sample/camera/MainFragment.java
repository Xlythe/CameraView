package com.xlythe.sample.camera;

import static com.xlythe.sample.camera.MainActivity.TAG;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.xlythe.fragment.camera.CameraFragment;
import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.Exif;

import java.io.File;
import java.text.DecimalFormat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * MainFragment demonstrates the standard usage of the CameraView library.
 * By extending CameraFragment, the library automatically manages camera lifecycle,
 * background thread operations, permissions, and UI binding to layout controls.
 */
public class MainFragment extends CameraFragment {
    /** Labels a preset the way a camera app does: 0.6x, 1x, 2.7x. */
    private final DecimalFormat mZoomFormat = new DecimalFormat("0.#");

    /** The CameraView the zoom presets drive, held while the view exists. */
    @Nullable
    private CameraView mCameraView;

    /** The row the zoom preset buttons go in, once the camera says what they are. */
    @Nullable
    private ViewGroup mZoomPresets;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // The layout contains a CameraView and control buttons (capture, confirm, cancel).
        // CameraFragment automatically binds to these controls using their resource IDs.
        return inflater.inflate(R.layout.fragment_main, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mCameraView = view.findViewById(R.id.camera);
        mZoomPresets = view.findViewById(R.id.zoom_presets);
        // Heard for a pinch as well as for a tap on a preset, so the highlight follows the zoom
        // however it got there.
        mCameraView.setOnZoomChangedListener(this::highlightZoomPreset);
    }

    @Override
    public void onDestroyView() {
        mCameraView = null;
        mZoomPresets = null;
        super.onDestroyView();
    }

    @Override
    public void onCameraOpened() {
        super.onCameraOpened();
        showZoomPresets();
    }

    @Override
    protected void onToggle() {
        super.onToggle();
        // The presets belong to the lens, and the other one may have different stops.
        showZoomPresets();
    }

    /** Fills the row with a button for each zoom preset the camera suggests. */
    private void showZoomPresets() {
        CameraView cameraView = mCameraView;
        if (cameraView == null || mZoomPresets == null) {
            return;
        }
        mZoomPresets.removeAllViews();
        float[] presets = cameraView.getZoomPresets();
        if (presets.length < 2) {
            // A single stop is no choice at all.
            return;
        }
        LayoutInflater inflater = getLayoutInflater();
        for (float preset : presets) {
            TextView button = (TextView) inflater.inflate(
                    R.layout.zoom_preset, mZoomPresets, false);
            button.setText(getString(R.string.zoom_preset, mZoomFormat.format(preset)));
            button.setTag(preset);
            button.setOnClickListener(v -> cameraView.setZoomRatio(preset));
            mZoomPresets.addView(button);
        }
        highlightZoomPreset(cameraView.getZoomRatio());
    }

    /**
     * Highlights the preset nearest the zoom. Nearest as a ratio, so 0.5x is as far below 1x as 2x
     * is above it.
     */
    private void highlightZoomPreset(float zoomRatio) {
        if (mZoomPresets == null) {
            return;
        }
        View nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (int i = 0; i < mZoomPresets.getChildCount(); i++) {
            View button = mZoomPresets.getChildAt(i);
            double distance = Math.abs(Math.log((Float) button.getTag() / zoomRatio));
            if (distance < nearestDistance) {
                nearest = button;
                nearestDistance = distance;
            }
        }
        for (int i = 0; i < mZoomPresets.getChildCount(); i++) {
            View button = mZoomPresets.getChildAt(i);
            button.setSelected(button == nearest);
        }
    }

    /**
     * Callback triggered by the library when a picture has been successfully taken and saved.
     * The picture is initially written to a temporary cache file.
     */
    @Override
    public void onImageCaptured(final File file) {
        // Print out metadata (EXIF data) about the captured picture using the library's Exif
        // helper, while the capture is still the cache file we were handed.
        Log.d(TAG, Exif.describe(file));

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
