package com.xlythe.sample.camera;

import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.xlythe.fragment.camera.CameraFragment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;

public class MainFragment extends CameraFragment {

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return View.inflate(getContext(), R.layout.fragment_main, container);
    }

    @Override
    public void onImageCaptured(final File file) {
        report("onImageCaptured");
        new FileTransferAsyncTask() {
            @Override
            protected void onPostExecute(File file) {
                report("Picture saved to " + file.getAbsolutePath());
            }
        }.execute(file, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES));
    }

    @Override
    public void onVideoCaptured(final File file) {
        report("onVideoCaptured");
        new FileTransferAsyncTask() {
            @Override
            protected void onPostExecute(File file) {
                report("Video saved to " + file.getAbsolutePath());
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
        Log.d("CameraSample", msg);
        Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
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
                newFile.delete();
                outputChannel = new FileOutputStream(newFile).getChannel();
                inputChannel = new FileInputStream(file).getChannel();
                inputChannel.transferTo(0, inputChannel.size(), outputChannel);
                inputChannel.close();
                file.delete();
            } finally {
                if (inputChannel != null) inputChannel.close();
                if (outputChannel != null) outputChannel.close();
            }
            return newFile;
        }
    }
}
