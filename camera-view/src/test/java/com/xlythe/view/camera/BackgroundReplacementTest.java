package com.xlythe.view.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;

import com.xlythe.view.camera.gl.BackgroundReplacer;
import com.xlythe.view.camera.gl.FrameRenderer;
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
import org.robolectric.util.ReflectionHelpers;

/**
 * Background replacement reads the frames an analyzer would, so it and frame analysis or the
 * barcode scanner refuse each other, each returning false and changing nothing.
 *
 * <p>Neither ML Kit model runs under test, so a running barcode scanner and a running replacement
 * are stood in for by the state CameraView keeps for them. Frame analysis runs for real.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, shadows = {ShadowInjectableImageReader.class, ShadowAvailableTextureView.class})
public class BackgroundReplacementTest {
    /** The camera under test, open on a fake Camera2 device. */
    private FakeCamera2.TestCameraView cameraView;

    /** A renderer the caller set, which a refused replacement must leave in place. */
    private final FrameRenderer renderer = frame -> false;

    @Before
    public void setUp() {
        ShadowInjectableImageReader.reset();
        Context context = RuntimeEnvironment.getApplication();
        Shadows.shadowOf((android.app.Application) context)
                .grantPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO);
        FakeCamera2.addBackAndFrontCameras(context);
        cameraView = FakeCamera2.laidOutCameraView(context, 1080, 1920);
        cameraView.open();
        idle();
        assertTrue("the fake camera should open", cameraView.isOpen());
        cameraView.setFrameRenderer(renderer);
    }

    @After
    public void tearDown() {
        ReflectionHelpers.setField(cameraView, "mUsingModuleBarcodeScanner", false);
        ReflectionHelpers.setField(cameraView, "mReplacingBackground", false);
        cameraView.close();
        idle();
        cameraView.release();
    }

    @Test
    public void refusedWhileFramesAreAnalyzed() {
        assertTrue(cameraView.startFrameAnalysis(frame -> {}));

        assertFalse(cameraView.enterBackgroundReplacement(new BackgroundReplacer()));

        assertFalse(cameraView.isReplacingBackground());
        assertSame("the caller's renderer is left alone", renderer, cameraView.getFrameRenderer());
        assertTrue("and so is the analysis", cameraView.isAnalyzingFrames());
    }

    @Test
    public void refusedWhileBarcodesAreScanned() {
        ReflectionHelpers.setField(cameraView, "mUsingModuleBarcodeScanner", true);

        assertFalse(cameraView.enterBackgroundReplacement(new BackgroundReplacer()));

        assertFalse(cameraView.isReplacingBackground());
        assertSame("the caller's renderer is left alone", renderer, cameraView.getFrameRenderer());
    }

    @Test
    public void frameAnalysisIsRefusedWhileTheBackgroundIsReplaced() {
        ReflectionHelpers.setField(cameraView, "mReplacingBackground", true);

        assertFalse(cameraView.startFrameAnalysis(frame -> {}));

        assertFalse(cameraView.isAnalyzingFrames());
    }

    @Test
    public void theBarcodeScannerIsRefusedWhileTheBackgroundIsReplaced() {
        ReflectionHelpers.setField(cameraView, "mReplacingBackground", true);

        assertFalse(cameraView.enterBarcodeScanner(barcodes -> {}, Barcode.Format.QR_CODE));

        Boolean scanning = ReflectionHelpers.getField(cameraView, "mUsingModuleBarcodeScanner");
        assertFalse("nothing was started that would have to be stopped", scanning);
    }

    private static void idle() {
        ShadowLooper.shadowMainLooper().idle();
    }
}
