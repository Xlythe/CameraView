package com.xlythe.sample.camera;

import static com.xlythe.sample.camera.MainActivity.TAG;

import android.graphics.RectF;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.xlythe.fragment.camera.CameraFragment;
import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.Exif;

import java.io.File;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * MainFragment demonstrates the standard usage of the CameraView library.
 * By extending CameraFragment, the library automatically manages camera lifecycle,
 * background thread operations, permissions, and UI binding to layout controls.
 */
public class MainFragment extends CameraFragment {

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
