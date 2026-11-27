package com.xlythe.sample.camera;

import static com.xlythe.sample.camera.MainActivity.TAG;

import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
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
    /** Where a held burst stops on its own, so one held by mistake does not fill the gallery. */
    private static final int MAX_BURST_PICTURES = 50;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // The layout contains a CameraView and control buttons (capture, confirm, cancel).
        // CameraFragment automatically binds to these controls using their resource IDs.
        return inflater.inflate(R.layout.fragment_main, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        CameraView camera = view.findViewById(R.id.camera);
        Button burst = view.findViewById(R.id.burst);
        burst.setOnTouchListener((button, event) -> onBurstTouched(camera, burst, event));
    }

    /**
     * Takes pictures for as long as the burst button is held, counting them on the button, and
     * says how many were filed once it is let go.
     */
    private boolean onBurstTouched(CameraView camera, Button burst, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                burst.setPressed(true);
                camera.startBurst(MAX_BURST_PICTURES, new CameraView.OnBurstListener() {
                    /** How many pictures have been filed so far. */
                    private int mSaved;

                    @Override
                    public void onBurstImageSaved(@NonNull Uri uri) {
                        mSaved++;
                        burst.setText(getString(R.string.btn_burst_count, mSaved));
                    }

                    @Override
                    public void onBurstFinished(int count) {
                        if (!isAdded()) {
                            return;
                        }
                        burst.setText(R.string.btn_burst);
                        report(getResources().getQuantityString(
                                R.plurals.burst_saved, count, count));
                    }
                });
                return true;
            case MotionEvent.ACTION_UP:
                burst.performClick();
                // Fall-through
            case MotionEvent.ACTION_CANCEL:
                burst.setPressed(false);
                camera.stopBurst();
                return true;
            default:
                return true;
        }
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
