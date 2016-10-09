package com.xlythe.sample.camera;

import android.os.Bundle;
import android.support.annotation.Nullable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.xlythe.fragment.camera.CameraFragment;
import com.xlythe.view.camera.CameraView;

import java.io.File;

public class MainFragment extends CameraFragment {

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return View.inflate(getContext(), R.layout.fragment_main, container);
    }

    @Override
    public void onImageCaptured(File file) {
        Toast.makeText(getContext(), "Picture saved to " + file.getAbsolutePath(), Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onVideoCaptured(File file) {
        Toast.makeText(getContext(), "Video saved to " + file.getAbsolutePath(), Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onRecordStart() {
        Toast.makeText(getContext(), "Recording", Toast.LENGTH_SHORT).show();
    }
}
