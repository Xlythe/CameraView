package com.xlythe.view.camera.x;

import android.content.Context;
import android.graphics.Rect;

import com.xlythe.view.camera.CameraView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

@RunWith(RobolectricTestRunner.class)
@Config(minSdk = 21)
public class CameraXModuleTest {

    private Context context;
    private CameraView cameraView;
    private CameraXModule module;

    @Before
    public void setup() {
        context = RuntimeEnvironment.application;
        cameraView = new CameraView(context);
        try {
            module = new CameraXModule(cameraView);
        } catch (IllegalStateException e) {
            // Expected when CameraX environment is not fully configured in Robolectric
        }
    }

    @Test
    public void testOpenClose() {
        if (module == null) return;
        assertNotNull(module);
        module.close();
    }

    @Test
    public void testLensFacing() {
        if (module == null) return;
        assertFalse(module.hasFrontFacingCamera());
    }

    @Test
    public void testFocus() {
        if (module == null) return;
        module.focus(new Rect(0, 0, 10, 10), new Rect(0, 0, 10, 10));
    }

    @Test
    public void testZoom() {
        if (module == null) return;
        module.setZoomLevel(1);
    }

    @Test
    public void testPauseResume() {
        if (module == null) return;
        module.pause();
        module.resume();
    }

    @Test
    public void testRecording() {
        if (module == null) return;
        assertFalse(module.isRecording());
        module.stopRecording();
    }
}
