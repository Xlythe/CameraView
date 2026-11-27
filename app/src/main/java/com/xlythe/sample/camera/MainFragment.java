package com.xlythe.sample.camera;

import static com.xlythe.sample.camera.MainActivity.TAG;

import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // The layout contains a CameraView and control buttons (capture, confirm, cancel).
        // CameraFragment automatically binds to these controls using their resource IDs.
        return inflater.inflate(R.layout.fragment_main, container, false);
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
