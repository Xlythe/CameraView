package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;

import com.xlythe.view.camera.shadow.ShadowAvailableTextureView;
import com.xlythe.view.camera.shadow.ShadowInjectableImageReader;
import com.xlythe.view.camera.v2.FakeCamera2;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/**
 * Which file a picture is written as.
 *
 * <p>Everything was JPEG, with no way to ask for anything else, even though the CameraX this
 * library depends on has been able to write Ultra HDR since 1.4.0 - a JPEG carrying a gain map
 * that a capable display uses to push the bright parts further, and that anything else reads as
 * an ordinary photo.</p>
 *
 * <p>The behaviour worth pinning is the refusal. A format a camera cannot write is declined and
 * the previous one kept, because a backend that accepted the request and then wrote something
 * else would be worse than one that said no. On the camera2 backend, which has no Ultra HDR path
 * at all, that refusal is the whole story.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37},
        shadows = {ShadowAvailableTextureView.class, ShadowInjectableImageReader.class})
public class PictureFormatTest {
    private Context context;
    private CameraView cameraView;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Shadows.shadowOf((android.app.Application) context).grantPermissions(
                Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO);
        FakeCamera2.addBackAndFrontCameras(context);
        cameraView = new CameraView(context);
        cameraView.onFinishInflate();
        cameraView.layout(0, 0, 1080, 1920);
        idle();
    }

    /** JPEG, which is what every backend and every camera can write. */
    @Test
    public void theDefaultIsJpeg() {
        assertEquals(CameraView.PictureFormat.JPEG, cameraView.getPictureFormat());
        assertTrue(cameraView.isPictureFormatSupported(CameraView.PictureFormat.JPEG));
    }

    /** The camera2 backend has no gain-map path, so it says so rather than pretending. */
    @Test
    public void camera2DoesNotOfferUltraHdr() {
        cameraView.open();
        idle();

        assertFalse("camera2 writes the HAL's JPEG bytes and composes no gain map",
                cameraView.isPictureFormatSupported(CameraView.PictureFormat.ULTRA_HDR));
    }

    /** And asking for it anyway leaves the format it can write. */
    @Test
    public void anUnsupportedFormatIsDeclinedRatherThanAccepted() {
        cameraView.open();
        idle();

        cameraView.setPictureFormat(CameraView.PictureFormat.ULTRA_HDR);
        idle();

        assertEquals("the request should be refused, not recorded",
                CameraView.PictureFormat.JPEG, cameraView.getPictureFormat());
    }

    /** Asking for the format already in use is accepted and changes nothing. */
    @Test
    public void askingForJpegIsHonoured() {
        cameraView.open();
        idle();

        cameraView.setPictureFormat(CameraView.PictureFormat.JPEG);
        idle();

        assertEquals(CameraView.PictureFormat.JPEG, cameraView.getPictureFormat());
        assertTrue("and the camera is still usable", cameraView.isOpen());
    }

    /** The format is asked of the camera, so a closed one offers only what needs no camera. */
    @Test
    public void aClosedCameraOffersOnlyJpeg() {
        assertTrue(cameraView.isPictureFormatSupported(CameraView.PictureFormat.JPEG));
        assertFalse(cameraView.isPictureFormatSupported(CameraView.PictureFormat.ULTRA_HDR));
    }

    /** Every format has a stable id, since they are written into saved state and XML. */
    @Test
    public void everyFormatRoundTripsThroughItsId() {
        for (CameraView.PictureFormat format : CameraView.PictureFormat.values()) {
            assertEquals(format, CameraView.PictureFormat.valueOf(format.name()));
        }
        assertEquals(2, CameraView.PictureFormat.values().length);
    }

    private static void idle() {
        ShadowLooper.idleMainLooper();
    }
}
