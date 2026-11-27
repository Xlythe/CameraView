package com.xlythe.sample.camera;

import static com.xlythe.sample.camera.MainActivity.TAG;

import android.net.Uri;
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

import java.io.IOException;
import java.io.InputStream;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * MainFragment demonstrates the standard usage of the CameraView library.
 * By extending CameraFragment, the library automatically manages camera lifecycle,
 * background thread operations, permissions, and UI binding to layout controls.
 */
public class MainFragment extends CameraFragment {
    /**
     * The time-lapse speeds the speed button steps through, starting from a normal recording: a
     * crowd at ten times, clouds at thirty, a sunset at a hundred and twenty.
     */
    private static final int[] TIME_LAPSE_SPEEDS = {1, 10, 30, 120};

    /** The camera, which holds the time-lapse speed itself and saves it with its own state. */
    private CameraView mCameraView;

    /** Shows the speed the next recording plays at, and steps it on a tap. */
    private Button mTimeLapseButton;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // The layout contains a CameraView and control buttons (capture, confirm, cancel).
        // CameraFragment automatically binds to these controls using their resource IDs.
        View view = inflater.inflate(R.layout.fragment_main, container, false);
        mCameraView = view.findViewById(com.xlythe.view.camera.R.id.camera);
        // Holding capture records, so a time-lapse is made by holding it at a speed above 1x.
        mTimeLapseButton = view.findViewById(R.id.time_lapse);
        mTimeLapseButton.setOnClickListener(v -> {
            mCameraView.setTimeLapseSpeed(nextTimeLapseSpeed(mCameraView.getTimeLapseSpeed()));
            showTimeLapseSpeed();
        });
        return view;
    }

    /** Labels the button once the camera has restored the speed it was left at. */
    @Override
    public void onViewStateRestored(@Nullable Bundle savedInstanceState) {
        super.onViewStateRestored(savedInstanceState);
        showTimeLapseSpeed();
    }

    private void showTimeLapseSpeed() {
        mTimeLapseButton.setText(getString(R.string.time_lapse_speed,
                mCameraView.getTimeLapseSpeed()));
    }

    /** The speed after this one, back to a normal recording after the fastest. */
    private static int nextTimeLapseSpeed(int speed) {
        for (int i = 0; i < TIME_LAPSE_SPEEDS.length - 1; i++) {
            if (TIME_LAPSE_SPEEDS[i] == speed) {
                return TIME_LAPSE_SPEEDS[i + 1];
            }
        }
        return TIME_LAPSE_SPEEDS[0];
    }

    /**
     * Callback triggered once the library has taken a picture and filed it in the gallery.
     */
    @Override
    public void onImageSaved(@NonNull Uri uri) {
        report("Picture saved to " + uri);

        // Print out metadata (EXIF data) about the picture using the library's Exif helper.
        try (InputStream input = requireContext().getContentResolver().openInputStream(uri)) {
            if (input != null) {
                Log.d(TAG, new Exif(input).toString());
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not read the metadata of " + uri, e);
        }
    }

    /**
     * Callback triggered once the library has recorded a video and filed it in the gallery.
     */
    @Override
    public void onVideoSaved(@NonNull Uri uri) {
        report("Video saved to " + uri);
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
