package com.xlythe.view.camera.v2;

import android.annotation.TargetApi;

import com.xlythe.view.camera.CameraView;

@TargetApi(21)
public class Camera2Module extends Camera2VideoModule {
    /**
     * If true, the v21 API is functionally on par with the legacy module
     */
    public static final boolean READY = true;

    public Camera2Module(CameraView cameraView) {
        super(cameraView);
    }
}
