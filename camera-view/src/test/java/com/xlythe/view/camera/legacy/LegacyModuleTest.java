package com.xlythe.view.camera.legacy;

import android.content.Context;
import android.graphics.Rect;

import com.xlythe.view.camera.CameraView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class LegacyModuleTest {

    private Context context;
    private CameraView cameraView;
    private LegacyCameraModule module;

    @Before
    public void setup() {
        context = RuntimeEnvironment.application;
        cameraView = new CameraView(context);
        module = new LegacyCameraModule(cameraView);
    }

    @Test
    public void testOpenClose() {
        assertNotNull(module);
        module.close();
    }

    @Test
    public void testLensFacing() {
        assertFalse(module.hasFrontFacingCamera());
    }

    @Test
    public void testFocus() {
        module.focus(new Rect(0, 0, 10, 10), new Rect(0, 0, 10, 10));
    }

    @Test
    public void testZoom() {
        module.setZoomLevel(1);
    }

    @Test
    public void testPauseResume() {
        module.pause();
        assertFalse(module.isPaused());
        module.resume();
        assertFalse(module.isPaused());
    }

    @Test
    public void testRecording() {
        assertFalse(module.isRecording());
        module.stopRecording();
    }

    /** The old camera API delivers a JPEG and nothing else, so it says so for every other. */
    @Test
    public void onlyJpegIsOffered() {
        for (CameraView.PictureFormat format : CameraView.PictureFormat.values()) {
            assertEquals(format.name(), format == CameraView.PictureFormat.JPEG,
                    module.isPictureFormatSupported(format));
        }
        module.setPictureFormat(CameraView.PictureFormat.RAW);
        assertEquals(CameraView.PictureFormat.JPEG, module.getPictureFormat());
    }
}
