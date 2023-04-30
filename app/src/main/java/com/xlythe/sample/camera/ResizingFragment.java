package com.xlythe.sample.camera;

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
import androidx.constraintlayout.motion.widget.MotionLayout;

import com.xlythe.fragment.camera.CameraFragment;
import com.xlythe.view.camera.Exif;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;

public class ResizingFragment extends CameraFragment {
    private static final String TAG = "CameraSample";

    private boolean isTransitionedToEnd = false;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_resizing, container, false);
        MotionLayout motionLayout = view.findViewById(R.id.layout_camera);
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
        new FileTransferAsyncTask() {
            @Override
            protected void onPostExecute(File file) {
                report("Picture saved to " + file.getAbsolutePath());

                // Print out metadata about the picture
                try {
                    Log.d(TAG, new Exif(file).toString());
                } catch (IOException e) {}

                broadcastPicture(file);
            }
        }.execute(file, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES));
    }

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
            getActivity().sendBroadcast(intent);
        }
    }

    private void broadcastVideo(File file) {
        if (Build.VERSION.SDK_INT < 24) {
            Intent intent = new Intent(Camera.ACTION_NEW_VIDEO);
            intent.setData(Uri.fromFile(file));
            getActivity().sendBroadcast(intent);
        }
    }

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
