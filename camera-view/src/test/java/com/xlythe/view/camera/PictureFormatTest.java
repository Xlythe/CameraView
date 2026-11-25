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
 * else would be worse than one that said no. The cameras registered here report no RAW
 * capability, so the camera2 backend declines everything but JPEG; what it does with a camera
 * that reports one is pinned by {@code RawCaptureTest} beside the backend itself.</p>
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

    /**
     * A camera with no RAW capability offers nothing but JPEG, and says so rather than pretending.
     *
     * <p>Ultra HDR needs a gain map, which only CameraX composes, and RAW needs a camera that
     * publishes both the capability and a RAW_SENSOR output. This one publishes neither.</p>
     */
    @Test
    public void camera2OffersOnlyJpegWithoutTheRawCapability() {
        cameraView.open();
        idle();

        for (CameraView.PictureFormat format : CameraView.PictureFormat.values()) {
            boolean supported = cameraView.isPictureFormatSupported(format);
            if (format == CameraView.PictureFormat.JPEG) {
                assertTrue("JPEG is the one every backend writes", supported);
            } else {
                assertFalse(format + " needs the CameraX backend", supported);
            }
        }
    }

    /** And asking for it anyway leaves the format it can write. */
    @Test
    public void anUnsupportedFormatIsDeclinedRatherThanAccepted() {
        cameraView.open();
        idle();

        for (CameraView.PictureFormat format : CameraView.PictureFormat.values()) {
            if (format == CameraView.PictureFormat.JPEG) {
                continue;
            }
            cameraView.setPictureFormat(format);
            idle();
            assertEquals("asking for " + format + " should be refused, not recorded",
                    CameraView.PictureFormat.JPEG, cameraView.getPictureFormat());
        }
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
        assertEquals(4, CameraView.PictureFormat.values().length);
    }

    private static void idle() {
        ShadowLooper.idleMainLooper();
    }
}
