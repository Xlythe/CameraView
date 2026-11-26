package com.xlythe.view.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Application;
import android.view.View;

import com.xlythe.view.camera.shadow.ShadowAvailableTextureView;
import com.xlythe.view.camera.shadow.ShadowCallbackCameraCaptureSession;
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
import org.robolectric.shadows.ShadowMediaPlayer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** A recording's confirmation looks like the preview it was framed in. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37},
        shadows = {ShadowAvailableTextureView.class, ShadowInjectableImageReader.class})
public class FrontVideoConfirmationTest {
    private Application context;
    private CameraView cameraView;

    @Before
    public void setUp() {
        // The confirmation starts its player before there is a surface to prepare it on, which
        // a real MediaPlayer refuses with an exception VideoView expects.
        ShadowMediaPlayer.setCreateListener((player, shadow) -> shadow.setInvalidStateBehavior(
                ShadowMediaPlayer.InvalidStateBehavior.EMULATE));
        context = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(context).grantPermissions(
                Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO);
        FakeCamera2.addBackAndFrontCameras(context);
        cameraView = new CameraView(context);
        cameraView.onFinishInflate();
        cameraView.layout(0, 0, 1080, 1920);
        cameraView.setVideoConfirmationEnabled(true);
        cameraView.open();
        ShadowLooper.idleMainLooper();
    }

    @After
    public void tearDown() {
        cameraView.close();
        ShadowLooper.idleMainLooper();
    }

    /** A front file that was not written mirrored, as none has been here, is flipped to show. */
    @Test
    public void aFrontRecordingIsShownMirrored() throws IOException {
        cameraView.toggleCamera();
        ShadowLooper.idleMainLooper();
        assertTrue(cameraView.isUsingFrontFacingCamera());

        cameraView.showVideoConfirmation(recording("front.mp4"));

        assertTrue(videoPreview().isMirrored());
    }

    /**
     * A front recording on Camera2 is drawn mirrored on its way to the file, so its confirmation
     * shows the file as it is. Robolectric's EGL starts and draws nothing, which is all this needs:
     * it is the module's answer about the file that decides the flip, not the pixels.
     */
    @Test
    @Config(shadows = {ShadowCallbackCameraCaptureSession.class})
    public void aFrontRecordingWrittenMirroredIsShownAsItIs() {
        cameraView.toggleCamera();
        ShadowLooper.idleMainLooper();
        assertTrue(cameraView.isUsingFrontFacingCamera());

        cameraView.startRecording(new File(context.getCacheDir(), "drawn.mp4"));
        ShadowLooper.idleMainLooper();
        assertTrue("the recording never started", cameraView.isRecording());
        cameraView.stopRecording();
        ShadowLooper.idleMainLooper();

        assertTrue("the confirmation never opened", videoPreview().getVisibility() == View.VISIBLE);
        assertFalse(videoPreview().isMirrored());
    }

    @Test
    public void aBackRecordingIsShownAsItIs() throws IOException {
        cameraView.showVideoConfirmation(recording("back.mp4"));

        assertFalse(videoPreview().isMirrored());
    }

    /** A file for the confirmation to open. */
    private File recording(String name) throws IOException {
        File file = new File(context.getCacheDir(), name);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(new byte[16]);
        }
        return file;
    }

    private VideoView videoPreview() {
        for (int i = 0; i < cameraView.getChildCount(); i++) {
            if (cameraView.getChildAt(i) instanceof VideoView) {
                return (VideoView) cameraView.getChildAt(i);
            }
        }
        throw new AssertionError("CameraView has no video preview");
    }
}
