package com.xlythe.sample.camera;

import static com.xlythe.sample.camera.MainActivity.TAG;

import android.annotation.SuppressLint;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xlythe.fragment.camera.CameraFragment;
import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.Exif;
import com.xlythe.view.camera.VideoView;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

/**
 * StreamFragment demonstrates how to capture live video streams from CameraView.
 * It pipes the live camera feed directly into a secondary VideoView for real-time rendering or processing.
 */
public class StreamFragment extends CameraFragment {
    private CameraView mCameraView;
    private VideoView mViewStreamView;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_stream, container, false);

        mCameraView = view.findViewById(R.id.camera);
        mViewStreamView = view.findViewById(R.id.video_stream);

        return view;
    }

    /**
     * Triggered when the camera successfully opens and begins previewing.
     * We access mCameraView.stream() to get an InputStream of the live frames and pass it to VideoView.
     */
    @SuppressLint({"CheckResult", "MissingPermission"})
    @Override
    public void onCameraOpened() {
        if (Build.VERSION.SDK_INT < 18) {
            return;
        }

        // Connect the live camera stream to the picture-in-picture video view.
        mViewStreamView.setStream(mCameraView.stream());
        mViewStreamView.play();
    }

    /**
     * Triggered when toggling between front and rear cameras.
     * We refresh the video stream to ensure seamless continuity of the live feed.
     */
    @SuppressLint({"CheckResult", "MissingPermission"})
    @Override
    protected void onToggle() {
        if (Build.VERSION.SDK_INT < 18) {
            return;
        }

        mViewStreamView.setStream(mCameraView.stream());
        mViewStreamView.play();
    }

    /**
     * Triggered when the camera closes.
     * We ensure the active stream is properly closed to avoid resource leaks.
     */
    @Override
    public void onCameraClosed() {
        if (Build.VERSION.SDK_INT < 18) {
            return;
        }

        if (mViewStreamView.hasStream()) {
            Objects.requireNonNull(mViewStreamView.getStream()).close();
        }
    }

    @Override
    public void onImageCaptured(final File file) {
        try {
            Log.d(TAG, new Exif(file).toString());
        } catch (IOException e) {
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
