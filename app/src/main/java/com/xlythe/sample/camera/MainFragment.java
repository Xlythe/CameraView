package com.xlythe.sample.camera;

import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.Nullable;
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
        new FileTransferAsyncTask() {
            @Override
            protected void onPostExecute(File file) {
                Toast.makeText(getContext(), "Picture saved to " + file.getAbsolutePath(), Toast.LENGTH_SHORT).show();
            }
        }.execute(file, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES));
    }

    @Override
    public void onVideoCaptured(final File file) {
        new FileTransferAsyncTask() {
            @Override
            protected void onPostExecute(File file) {
                Toast.makeText(getContext(), "Video saved to " + file.getAbsolutePath(), Toast.LENGTH_SHORT).show();
            }
        }.execute(file, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES));
    }

    @Override
    protected void onRecordStart() {
        Toast.makeText(getContext(), "Recording", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onFailure() {
        Toast.makeText(getContext(), "Failure", Toast.LENGTH_SHORT).show();
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
