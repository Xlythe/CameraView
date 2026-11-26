package com.xlythe.view.camera.test;

import android.app.Activity;
import android.os.Bundle;

import androidx.annotation.Nullable;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.test.R;

public final class CameraTestActivity extends Activity {
    private CameraView cameraView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_test);
        cameraView = findViewById(R.id.camera);
    }

    @Override
    protected void onStop() {
        cameraView.close();
        super.onStop();
    }

    public CameraView getCameraView() {
        return cameraView;
    }
}
