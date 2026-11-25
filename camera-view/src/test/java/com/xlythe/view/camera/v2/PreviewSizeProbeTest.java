package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Size;

import com.xlythe.view.camera.shadow.ShadowConfigurableCameraDevice;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

/**
 * The preview size cap, including the branch that only exists when the camera can be asked
 * whether a configuration it does not guarantee would work.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37, shadows = {ShadowConfigurableCameraDevice.class},
        // Robolectric's default display is 470x320, which every camera output exceeds, so the
        // cap would always take its smallest-size fallback and the probe would never be reached.
        // A 1080x2400 panel is the shape this logic exists for.
        qualifiers = "w360dp-h800dp-xxxhdpi")
public class PreviewSizeProbeTest {
    private static final Size SIZE_4K = new Size(3840, 2160);
    private static final List<Size> CANDIDATES =
            Arrays.asList(SIZE_4K, FakeCamera2.SIZE_1080P, FakeCamera2.SIZE_720P,
                    FakeCamera2.SIZE_480P);

    private Context context;
    private FakeCamera2.TestCameraView cameraView;
    private Camera2Module module;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        FakeCamera2.addBackAndFrontCameras(context);
        cameraView = FakeCamera2.laidOutCameraView(context, 1080, 1920);
        module = new Camera2Module(cameraView);
    }

    @After
    public void tearDown() {
        module.close();
        FakeCamera2.idleMainLooper();
        cameraView.release();
    }

    /** With no camera open there is nothing to ask, so the guaranteed size stands. */
    @Test
    public void withoutAnOpenCameraTheCapIsTheGuaranteedSize() {
        ShadowConfigurableCameraDevice.setSessionConfigurationSupported(true);

        assertFalse("nothing should have been probed without a device",
                !ShadowConfigurableCameraDevice.getProbedConfigurations().isEmpty());
        assertEquals(Camera2Module.guaranteedPreviewCap(displaySize()),
                module.getPreviewSizeCap(CANDIDATES));
    }

    /** A camera that refuses the larger configuration keeps the guaranteed size. */
    @Test
    public void aRefusedProbeKeepsTheGuaranteedSize() {
        ShadowConfigurableCameraDevice.setSessionConfigurationSupported(false);
        module.open();
        FakeCamera2.idleMainLooper();

        assertEquals(Camera2Module.guaranteedPreviewCap(displaySize()),
                module.getPreviewSizeCap(CANDIDATES));
        assertTrue("the larger configuration should have been offered to the camera",
                ShadowConfigurableCameraDevice.getProbedConfigurations().size() >= 1);
    }

    /** A camera that accepts it gets the larger preview, which is the point of asking. */
    @Test
    public void anAcceptedProbeRaisesTheCapAboveTheGuarantee() {
        ShadowConfigurableCameraDevice.setSessionConfigurationSupported(true);
        module.open();
        FakeCamera2.idleMainLooper();

        assertEquals(SIZE_4K, module.getPreviewSizeCap(CANDIDATES));
    }

    /** Nothing is asked when no candidate exceeds what is already guaranteed. */
    @Test
    public void aCameraWithNothingLargerIsNotAsked() {
        ShadowConfigurableCameraDevice.setSessionConfigurationSupported(true);
        module.open();
        FakeCamera2.idleMainLooper();
        ShadowConfigurableCameraDevice.reset();
        ShadowConfigurableCameraDevice.setSessionConfigurationSupported(true);

        Size cap = module.getPreviewSizeCap(Arrays.asList(FakeCamera2.SIZE_480P));
        assertEquals(Camera2Module.guaranteedPreviewCap(displaySize()), cap);
        assertTrue("no probe was needed",
                ShadowConfigurableCameraDevice.getProbedConfigurations().isEmpty());
    }

    /** Every output larger than the panel must still leave the preview something to pick. */
    @Test
    public void aCapThatExcludesEverythingFallsBackToTheSmallest() {
        ShadowConfigurableCameraDevice.setSessionConfigurationSupported(false);

        Size cap = module.getPreviewSizeCap(Arrays.asList(SIZE_4K, FakeCamera2.SIZE_1080P));
        assertTrue("the cap has to admit at least one size",
                !Camera2Module.exceeds(FakeCamera2.SIZE_1080P, cap)
                        || !Camera2Module.exceeds(SIZE_4K, cap));
    }

    private Size displaySize() {
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return new Size(metrics.widthPixels, metrics.heightPixels);
    }
}
