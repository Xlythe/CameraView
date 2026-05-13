package com.xlythe.view.camera;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(minSdk = 21)
public class CameraViewTest {

    private Context context;
    private CameraView cameraView;

    @Before
    public void setup() {
        context = RuntimeEnvironment.application;
        cameraView = new CameraView(context);
    }

    @Test
    public void testInitialProperties() {
        assertNotNull(cameraView);
        assertEquals(CameraView.Quality.HIGH, cameraView.getQuality());
        assertTrue(cameraView.isPinchToZoomEnabled());
    }

    @Test
    public void testPropertySetters() {
        cameraView.setQuality(CameraView.Quality.MAX);
        assertEquals(CameraView.Quality.MAX, cameraView.getQuality());

        cameraView.setPinchToZoomEnabled(false);
        assertFalse(cameraView.isPinchToZoomEnabled());
    }
}
