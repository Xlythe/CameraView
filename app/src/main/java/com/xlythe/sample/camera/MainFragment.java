package com.xlythe.sample.camera;

import static com.xlythe.sample.camera.MainActivity.TAG;

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
    /** How long the preview takes to come back after blinking for a picture. */
    private static final long SHUTTER_BLINK_MILLIS = 150;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // The layout contains a CameraView and control buttons (capture, confirm, cancel).
        // CameraFragment automatically binds to these controls using their resource IDs.
        return inflater.inflate(R.layout.fragment_main, container, false);
    }

    /**
     * Callback triggered as the picture is exposed. The library has already played the shutter
     * click; blinking the preview shows the moment too, as camera apps do.
     */
    @Override
    public void onShutter() {
        View camera = requireView().findViewById(R.id.camera);
        camera.animate().cancel();
        camera.setAlpha(0f);
        camera.animate().alpha(1f).setDuration(SHUTTER_BLINK_MILLIS);
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
