package com.xlythe.view.camera;

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
 * Whether a picture from the user-facing lens comes out mirrored.
 *
 * <p>All three backends mirrored front stills unconditionally, while only video asked whether the
 * caller wanted it - so turning video mirroring off produced un-mirrored video and mirrored photos
 * from one session, with no way to say otherwise. There was no test on the still path at all, in
 * any backend, which is why nobody noticed.</p>
 *
 * <p>These assert the decision rather than the pixels: each backend asks
 * {@link ICameraModule#isMirroringThisPicture()}, and what the HAL or the Exif flip then does with
 * the answer is the platform's business.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37},
        shadows = {ShadowAvailableTextureView.class, ShadowInjectableImageReader.class})
public class FrontMirroringTest {
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

    /** On by default, which is what every backend did before there was anything to ask. */
    @Test
    public void frontPhotosAreMirroredByDefault() {
        assertTrue(cameraView.isFrontPhotoMirroringEnabled());
    }

    /** The rear lens never mirrors, whatever the setting says. */
    @Test
    public void theRearLensNeverMirrors() {
        cameraView.open();
        idle();
        assertFalse("the fake back camera is the default", cameraView.isUsingFrontFacingCamera());

        assertFalse("a rear photo is the scene as it is",
                cameraView.getCameraModule().isMirroringThisPicture());

        cameraView.setFrontPhotoMirroringEnabled(false);
        assertFalse(cameraView.getCameraModule().isMirroringThisPicture());
    }

    /** The front lens mirrors while the setting allows it, and stops when it does not. */
    @Test
    public void theFrontLensFollowsTheSetting() {
        cameraView.open();
        idle();
        cameraView.toggleCamera();
        idle();
        assertTrue("expected to be on the front camera", cameraView.isUsingFrontFacingCamera());

        assertTrue("a selfie should look like the preview it was framed in",
                cameraView.getCameraModule().isMirroringThisPicture());

        cameraView.setFrontPhotoMirroringEnabled(false);
        assertFalse("and should not, once the caller says so",
                cameraView.getCameraModule().isMirroringThisPicture());

        cameraView.setFrontPhotoMirroringEnabled(true);
        assertTrue(cameraView.getCameraModule().isMirroringThisPicture());
    }

    /**
     * The photo setting is its own, not the video one.
     *
     * <p>This is the bug: a caller who wanted un-mirrored recordings got mirrored photos anyway,
     * because the still path read the lens and nothing else.</p>
     */
    @Test
    public void thePhotoSettingIsIndependentOfTheVideoSetting() {
        cameraView.open();
        idle();
        cameraView.toggleCamera();
        idle();

        cameraView.setFrontVideoMirroringEnabled(false);

        assertFalse(cameraView.isFrontVideoMirroringEnabled());
        assertTrue("turning video mirroring off must not silently change photos",
                cameraView.isFrontPhotoMirroringEnabled());
        assertTrue(cameraView.getCameraModule().isMirroringThisPicture());

        cameraView.setFrontPhotoMirroringEnabled(false);
        assertFalse(cameraView.isFrontVideoMirroringEnabled());
        assertFalse(cameraView.isFrontPhotoMirroringEnabled());
    }

    /** Both settings survive a save and restore, and do not overwrite each other. */
    @Test
    public void bothSettingsSurviveSavedState() {
        cameraView.setFrontVideoMirroringEnabled(false);
        cameraView.setFrontPhotoMirroringEnabled(true);

        android.os.Parcelable saved = cameraView.onSaveInstanceState();

        CameraView restored = new CameraView(context);
        restored.onFinishInflate();
        restored.onRestoreInstanceState(saved);

        assertFalse("the video setting came back", restored.isFrontVideoMirroringEnabled());
        assertTrue("and so did the photo setting, separately",
                restored.isFrontPhotoMirroringEnabled());
    }

    /** And the other way round, since one bundle key for two settings would lose one of them. */
    @Test
    public void theTwoSettingsDoNotShareAKey() {
        cameraView.setFrontVideoMirroringEnabled(true);
        cameraView.setFrontPhotoMirroringEnabled(false);

        CameraView restored = new CameraView(context);
        restored.onFinishInflate();
        restored.onRestoreInstanceState(cameraView.onSaveInstanceState());

        assertTrue(restored.isFrontVideoMirroringEnabled());
        assertFalse(restored.isFrontPhotoMirroringEnabled());
    }

    private static void idle() {
        ShadowLooper.idleMainLooper();
    }
}
