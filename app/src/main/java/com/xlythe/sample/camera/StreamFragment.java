package com.xlythe.sample.camera;

import static com.xlythe.sample.camera.MainActivity.TAG;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.hardware.Camera;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
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

    @SuppressLint("StaticFieldLeak")
    @Override
    public void onImageCaptured(final File file) {
        new FileTransferAsyncTask() {
            @Override
            protected void onPostExecute(File file) {
                report("Picture saved to " + file.getAbsolutePath());

                try {
                    Log.d(TAG, new Exif(file).toString());
                } catch (IOException e) {
                    // ignored
                }

                broadcastPicture(file);
            }
        }.execute(file, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES));
    }

    @SuppressLint("StaticFieldLeak")
    @Override
    public void onVideoCaptured(final File file) {
        new FileTransferAsyncTask() {
            @Override
            protected void onPostExecute(File file) {
                report("Video saved to " + file.getAbsolutePath());
                broadcastVideo(file);
            }
        }.execute(file, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES));
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

    private void broadcastPicture(File file) {
        if (Build.VERSION.SDK_INT < 24) {
            Intent intent = new Intent(Camera.ACTION_NEW_PICTURE);
            intent.setData(Uri.fromFile(file));
            requireActivity().sendBroadcast(intent);
        }
    }

    private void broadcastVideo(File file) {
        if (Build.VERSION.SDK_INT < 24) {
            Intent intent = new Intent(Camera.ACTION_NEW_VIDEO);
            intent.setData(Uri.fromFile(file));
            requireActivity().sendBroadcast(intent);
        }
    }

    /**
     * Helper AsyncTask to move captured media from temporary cache to public external storage.
     */
    private static class FileTransferAsyncTask extends AsyncTask<File, Void, File> {
        @Override
        protected File doInBackground(File... params) {
            try {
                return moveFile(params[0], params[1]);
            } catch (IOException e) {
                e.printStackTrace();
                cancel(true);
            }
            return null;
        }

        private static File moveFile(File file, File dir) throws IOException {
            File newFile = new File(dir, file.getName());
            FileChannel outputChannel = null;
            FileChannel inputChannel = null;
            try {
                if (newFile.exists()) {
                    Log.w(TAG, "File " + newFile + " already exists. Replacing.");
                    if (!newFile.delete()) {
                        throw new IOException("Failed to delete destination file.");
                    }
                }
                outputChannel = new FileOutputStream(newFile).getChannel();
                inputChannel = new FileInputStream(file).getChannel();
                inputChannel.transferTo(0, inputChannel.size(), outputChannel);
                inputChannel.close();
                if (!file.delete()) {
                    throw new IOException("Failed to delete original file.");
                }
            } finally {
                if (inputChannel != null) inputChannel.close();
                if (outputChannel != null) outputChannel.close();
            }
            return newFile;
        }
    }
}
