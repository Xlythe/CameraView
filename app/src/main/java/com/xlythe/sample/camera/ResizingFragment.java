package com.xlythe.sample.camera;

import static com.xlythe.sample.camera.MainActivity.TAG;

import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.motion.widget.MotionLayout;

import com.xlythe.fragment.camera.CameraFragment;
import com.xlythe.view.camera.Exif;

import java.io.File;
import java.io.IOException;

/**
 * ResizingFragment demonstrates CameraView's robust support for dynamic layout animations.
 * When resizing or animating layout constraints (here via MotionLayout), CameraView seamlessly
 * adapts its rendering surface without interrupting the underlying camera session or preview feed.
 */
public class ResizingFragment extends CameraFragment {
    private boolean isTransitionedToEnd = false;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_resizing, container, false);
        MotionLayout motionLayout = view.findViewById(com.xlythe.view.camera.R.id.layout_camera);

        // Clicking the resize button animates CameraView between full screen and thumbnail dimensions.
        view.findViewById(R.id.resize).setOnClickListener(v -> {
            if (isTransitionedToEnd) {
                motionLayout.transitionToStart();
            } else {
                motionLayout.transitionToEnd();
            }
            isTransitionedToEnd = !isTransitionedToEnd;
        });
        return view;
    }

    @Override
    public void onImageCaptured(final File file) {
        try {
            Log.d(TAG, new Exif(file).toString());
        } catch (IOException ignored) {
            // Metadata is only logged here, so a failure to read it is not worth reporting.
        }

        CaptureSaver.save(requireContext(), file, false /* isVideo */, uri ->
                report(uri == null ? "Could not save the picture" : "Picture saved to " + uri));
    }

    @Override
    public void onVideoCaptured(final File file) {
        CaptureSaver.save(requireContext(), file, true /* isVideo */, uri ->
                report(uri == null ? "Could not save the video" : "Video saved to " + uri));
    }

    @Override
    protected void onRecordStart() {
        report("Recording");
    }

    @Override
    public void onFailure() {
       report("Failure");
    }

    private void report(String msg) {
        Log.d(TAG, msg);
        Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
    }

}
