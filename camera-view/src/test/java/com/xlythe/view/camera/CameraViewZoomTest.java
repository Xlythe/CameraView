package com.xlythe.view.camera;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.os.Looper;

import com.xlythe.view.camera.shadow.ShadowAvailableTextureView;
import com.xlythe.view.camera.shadow.ShadowInjectableImageReader;
import com.xlythe.view.camera.v2.FakeCamera2;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

/**
 * Zoom through CameraView, the way an app drives it: by ratio, by level, and with a listener
 * keeping preset buttons in step.
 *
 * <p>The fake back camera crops up to 4x, and its front camera does too.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37},
        shadows = {ShadowAvailableTextureView.class, ShadowInjectableImageReader.class})
public class CameraViewZoomTest {
    private Context context;
    private CameraView cameraView;
    /** Every ratio the listener heard, in order. */
    private final List<Float> heard = new ArrayList<>();

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Shadows.shadowOf((android.app.Application) context).grantPermissions(
                Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO);
    }

    @After
    public void tearDown() {
        if (cameraView != null) {
            cameraView.close();
            idle();
        }
    }

    private void open(boolean logicalBackCamera) throws Exception {
        if (logicalBackCamera) {
            FakeCamera2.addLogicalBackCamera(context);
        } else {
            FakeCamera2.addBackAndFrontCameras(context);
        }
        cameraView = new CameraView(context);
        cameraView.onFinishInflate();
        cameraView.layout(0, 0, 1080, 1920);
        cameraView.setOnZoomChangedListener(ratio -> {
            assertTrue("delivered on the main thread", Looper.myLooper() == Looper.getMainLooper());
            heard.add(ratio);
        });
        cameraView.open();
        idle();
    }

    /** Each change is heard once, and a request that changes nothing is not heard at all. */
    @Test
    public void theListenerHearsEachChangeOnce() throws Exception {
        open(false /* logicalBackCamera */);

        cameraView.setZoomRatio(2f);
        idle();
        cameraView.setZoomRatio(2f);
        idle();
        cameraView.setZoomRatio(3f);
        idle();
        cameraView.setZoomRatio(40f);
        idle();
        cameraView.setZoomRatio(50f);
        idle();

        assertEquals("2x, 3x, and 40x held to the 4x top; the repeats are not news",
                List.of(2f, 3f, 4f), heard);
    }

    /** A level is the same zoom as a ratio, on a log scale across the camera's range. */
    @Test
    public void aLevelIsAPositionInTheRatioRange() throws Exception {
        open(false /* logicalBackCamera */);
        assertEquals(1f, cameraView.getMinZoomRatio(), 0f);
        assertEquals(4f, cameraView.getMaxZoomRatio(), 0f);

        cameraView.setZoomLevel(50);
        idle();

        assertEquals("halfway from 1x to 4x on a log scale is 2x",
                2f, cameraView.getZoomRatio(), 0.0001f);
        assertEquals(50, cameraView.getZoomLevel());
        assertEquals(List.of(cameraView.getZoomRatio()), heard);

        cameraView.setZoomRatio(4f);
        idle();
        assertEquals(cameraView.getMaxZoomLevel(), cameraView.getZoomLevel());
    }

    /** A lens switch that cannot keep the zoom is a change like any other. */
    @Test
    public void aLensSwitchThatMovesTheZoomIsHeard() throws Exception {
        open(true /* logicalBackCamera */);
        cameraView.setZoomRatio(cameraView.getMaxZoomRatio());
        idle();
        heard.clear();

        cameraView.toggleCamera();
        idle();

        assertEquals("the front camera crops to 4x at most", List.of(4f), heard);
        assertEquals(4f, cameraView.getZoomRatio(), 0f);
    }

    /** A camera with one lens and nothing to crop to 2x offers only 1x. */
    @Test
    public void aSingleLensHasOnePreset() throws Exception {
        open(false /* logicalBackCamera */);

        assertArrayEquals(new float[] {1f}, cameraView.getZoomPresets(), 0f);
    }

    /** A ratio that is not one is refused rather than handed to the camera. */
    @Test
    public void anImpossibleRatioIsIgnored() throws Exception {
        open(false /* logicalBackCamera */);
        cameraView.setZoomRatio(2f);
        idle();

        cameraView.setZoomRatio(Float.NaN);
        cameraView.setZoomRatio(0f);
        cameraView.setZoomRatio(-1f);
        cameraView.setZoomRatio(Float.POSITIVE_INFINITY);
        idle();

        assertEquals(2f, cameraView.getZoomRatio(), 0f);
        assertEquals(List.of(2f), heard);
    }

    private static void idle() {
        ShadowLooper.idleMainLooper();
    }
}
