package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import android.content.Context;

import com.xlythe.view.camera.shadow.ShadowAnnouncingCameraManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Which backend a CameraView ends up driving.
 *
 * <p>This used to be decided by a package-private constant that was always false, so an app had
 * no way to run CameraX at all; the point of these tests is that the choice is reachable, that
 * Camera2 is still what you get without asking, and that asking for a backend which cannot run
 * falls back rather than throwing out of a constructor.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, shadows = {ShadowAnnouncingCameraManager.class})
public class BackendSelectionTest {
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
    public void camera2IsWhatYouGetWithoutAsking() {
        assertEquals(CameraView.Backend.AUTO, CameraView.getDefaultBackend());
        assertEquals(CameraView.Backend.CAMERA2, new CameraView(context).getBackend());
    }

    @Test
    public void theDefaultCanBeChangedBeforeInflation() {
        CameraView.setDefaultBackend(CameraView.Backend.LEGACY);

        assertEquals(CameraView.Backend.LEGACY, CameraView.getDefaultBackend());
        assertEquals(CameraView.Backend.LEGACY, new CameraView(context).getBackend());
    }

    /**
     * The ids the layout attribute uses map to the enum they are documented as meaning.
     *
     * <p>The attribute itself is read by the same obtainStyledAttributes call as every other
     * CameraView attribute; what is worth pinning is the mapping, because the numbers are written
     * out again in attrs.xml and nothing else would catch them drifting apart.</p>
     */
    @Test
    public void theAttributeIdsMatchTheEnum() {
        assertSame(CameraView.Backend.AUTO, CameraView.Backend.fromId(0));
        assertSame(CameraView.Backend.CAMERA2, CameraView.Backend.fromId(1));
        assertSame(CameraView.Backend.CAMERA_X, CameraView.Backend.fromId(2));
        assertSame(CameraView.Backend.LEGACY, CameraView.Backend.fromId(3));
        assertSame("an id from a newer release reads as auto",
                CameraView.Backend.AUTO, CameraView.Backend.fromId(99));
    }
}
