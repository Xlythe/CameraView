package com.xlythe.view.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.content.Context;
import android.graphics.SurfaceTexture;

import androidx.annotation.NonNull;

import com.xlythe.view.camera.gl.CameraTextureProvider;
import com.xlythe.view.camera.gl.FrameRenderer;
import com.xlythe.view.camera.gl.GlFrame;
import com.xlythe.view.camera.shadow.ShadowAnnouncingCameraManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The two hooks a caller reaches camera frames through, and the probe that says whether they do
 * anything.
 *
 * <p>What a renderer draws cannot be tested here - there is no GL in a unit test, which is also
 * why CameraX cannot bind an effect off a device - so these assert the seam itself: that the hooks
 * are off until something is set, that setting one is not refused by a backend that will ignore
 * it, and that the probe tells the truth about which backend was chosen. An app decides whether to
 * offer a filter at all on the strength of that last one.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, shadows = {ShadowAnnouncingCameraManager.class})
public class FrameEffectSeamTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        com.xlythe.view.camera.v2.FakeCamera2.addBackAndFrontCameras(context);
    }

    @After
    public void tearDown() {
        CameraView.setDefaultBackend(CameraView.Backend.AUTO);
    }

    @Test
    public void framesGoNowhereUntilSomethingAsks() {
        CameraView cameraView = new CameraView(context);

        assertNull(cameraView.getFrameRenderer());
        assertNull(cameraView.getCameraTextureProvider());
    }

    @Test
    public void theRendererIsTheOneThatWasSet() {
        CameraView cameraView = new CameraView(context);
        FrameRenderer renderer = new CountingRenderer();

        cameraView.setFrameRenderer(renderer);
        assertSame(renderer, cameraView.getFrameRenderer());

        cameraView.setFrameRenderer(null);
        assertNull("clearing it has to put the camera's own frame back",
                cameraView.getFrameRenderer());
    }

    @Test
    public void theTextureProviderIsTheOneThatWasSet() {
        CameraView cameraView = new CameraView(context);
        CameraTextureProvider provider = new BorrowedTextureProvider();

        cameraView.setCameraTextureProvider(provider);
        assertSame(provider, cameraView.getCameraTextureProvider());

        cameraView.setCameraTextureProvider(null);
        assertNull(cameraView.getCameraTextureProvider());
    }

    /**
     * Camera2 and the original Camera API hand their buffers straight to the surfaces that consume
     * them, so there is nowhere for a frame to be drawn on the way out and both say so.
     *
     * <p>CameraX's answer is asserted against the bound backend in {@code CameraXHarnessTest},
     * because reaching it here would mean standing a camera provider up.</p>
     */
    @Test
    public void theOlderBackendsHaveNowhereToDrawAFrame() {
        CameraView.setDefaultBackend(CameraView.Backend.CAMERA2);
        assertFalse(new CameraView(context).isFrameEffectSupported());

        CameraView.setDefaultBackend(CameraView.Backend.LEGACY);
        assertFalse(new CameraView(context).isFrameEffectSupported());
    }

    /**
     * A backend without effects takes a renderer and ignores it rather than throwing.
     *
     * <p>Deliberate: an effect is usually decoration, and an app that sets one on whatever camera
     * it finds should get a working camera. The probe above is how a caller who cannot live
     * without the effect finds out.</p>
     */
    @Test
    public void aBackendWithNoEffectsStillTakesARenderer() {
        CameraView.setDefaultBackend(CameraView.Backend.LEGACY);
        CameraView cameraView = new CameraView(context);
        FrameRenderer renderer = new CountingRenderer();

        cameraView.setFrameRenderer(renderer);
        cameraView.setCameraTextureProvider(new BorrowedTextureProvider());

        assertFalse(cameraView.isFrameEffectSupported());
        assertSame("accepted and remembered, even where it will never be called",
                renderer, cameraView.getFrameRenderer());
    }

    private static final class CountingRenderer implements FrameRenderer {
        @Override
        public boolean onDrawFrame(@NonNull GlFrame frame) {
            return false;
        }
    }

    private static final class BorrowedTextureProvider implements CameraTextureProvider {
        @Override
        public int onCreateCameraTexture() {
            return 1;
        }

        @Override
        public boolean onCameraFrameAvailable(@NonNull SurfaceTexture texture) {
            return false;
        }

        @Override
        public void onReleaseCameraTexture(int textureName) {}
    }
}
