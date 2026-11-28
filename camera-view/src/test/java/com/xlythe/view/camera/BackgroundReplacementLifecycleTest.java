package com.xlythe.view.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;

import com.xlythe.view.camera.gl.BackgroundReplacer;
import com.xlythe.view.camera.shadow.ShadowAvailableTextureView;
import com.xlythe.view.camera.shadow.ShadowCallbackCameraCaptureSession;
import com.xlythe.view.camera.shadow.ShadowFrameSurfaceTexture;
import com.xlythe.view.camera.shadow.ShadowInjectableImageReader;
import com.xlythe.view.camera.shadow.ShadowRecordingGles20;
import com.xlythe.view.camera.v2.Camera2Module;
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
 * A background replacement lasts as long as the view says it does: across the camera closing and
 * opening again, and across a lens switch.
 *
 * <p>Closing the camera stopped the segmenter while the view went on saying it replaced the
 * background, and the replacer, which outlives a close as every renderer does, drew no mask
 * again. On Camera2 a lens switch also left the segmenter's stream behind on the last lens. Run
 * against the Camera2 backend with ML Kit's segmenter built for real, which it can be off a device
 * though it cannot segment there.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, qualifiers = "w360dp-h800dp-xxxhdpi", shadows = {
        ShadowAvailableTextureView.class,
        ShadowCallbackCameraCaptureSession.class,
        ShadowInjectableImageReader.class,
        ShadowFrameSurfaceTexture.class,
        ShadowRecordingGles20.class})
public class BackgroundReplacementLifecycleTest {
    /** How long a test waits for the segmenter's stream to reach the camera. */
    private static final long STREAM_WAIT_MILLIS = 10_000;

    private FakeCamera2.TestCameraView cameraView;
    private final BackgroundReplacer replacer = new BackgroundReplacer();

    @Before
    public void setUp() {
        ShadowCallbackCameraCaptureSession.reset();
        ShadowInjectableImageReader.reset();
        ShadowFrameSurfaceTexture.reset();
        ShadowRecordingGles20.reset();
        Context context = RuntimeEnvironment.getApplication();
        MlKitForTests.bringUp();
        Shadows.shadowOf((android.app.Application) context)
                .grantPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO);
        FakeCamera2.addBackAndFrontCameras(context);
        cameraView = FakeCamera2.laidOutCameraView(context, 1080, 1920);
        cameraView.open();
        idle();
        assertTrue("the fake camera should open", cameraView.isOpen());
        assertTrue("the background should be replaced",
                cameraView.enterBackgroundReplacement(replacer));
        assertNotNull("and the segmenter running", segmentationStream());
    }

    @After
    public void tearDown() {
        cameraView.exitBackgroundReplacement();
        cameraView.close();
        idle();
        cameraView.release();
        MlKitForTests.takeDown();
    }

    @Test
    public void theSegmenterStartsAgainWhenTheCameraReopens() {
        cameraView.close();
        idle();
        assertNull("closing stops the segmenter", segmentationStream());
        assertTrue("but not the replacement", cameraView.isReplacingBackground());

        cameraView.open();
        idle();

        assertTrue(cameraView.isOpen());
        assertTrue(cameraView.isReplacingBackground());
        assertSame("the replacer is still what draws", replacer, cameraView.getFrameRenderer());
        assertNotNull("the segmenter runs again", segmentationStream());
        awaitStreamInSession(segmentationStream());
    }

    @Test
    public void leavingAfterAReopenStopsTheSegmenter() {
        cameraView.close();
        idle();
        cameraView.open();
        idle();

        cameraView.exitBackgroundReplacement();
        idle();

        assertFalse(cameraView.isReplacingBackground());
        assertNull(segmentationStream());
        assertNull(cameraView.getFrameRenderer());
    }

    /**
     * A lens switch segments the new lens through a stream of its own, sized for that lens and
     * labelling its frames as that lens's, rather than carrying the last lens's stream over.
     */
    @Test
    public void aLensSwitchSegmentsTheNewLensThroughAStreamOfItsOwn() {
        FrameStream back = segmentationStream();
        awaitStreamInSession(back);

        cameraView.toggleCamera();
        idle();

        FrameStream front = segmentationStream();
        assertNotNull("the segmenter runs on the new lens", front);
        assertNotSame("through a stream of its own", back, front);
        assertFalse("the last lens's stream has stopped", back.isAnalyzing());
        awaitStreamInSession(front);
        assertTrue(cameraView.isReplacingBackground());
    }

    /** The module's segmentation stream, which it keeps private. */
    private FrameStream segmentationStream() {
        Camera2Module module = ReflectionHelpers.getField(cameraView, "mCameraModule");
        return ReflectionHelpers.getField(module, "mSegmentationStream");
    }

    /** Waits until the camera's session is carrying a stream and the given stream is running. */
    private void awaitStreamInSession(FrameStream stream) {
        Camera2Module module = ReflectionHelpers.getField(cameraView, "mCameraModule");
        long deadline = System.currentTimeMillis() + STREAM_WAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            idle();
            Object provider = ReflectionHelpers.getField(module, "mStreamProvider");
            if (provider != null && stream.isAnalyzing()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("the segmenter's stream never reached the camera");
    }

    private static void idle() {
        ShadowLooper.shadowMainLooper().idle();
    }
}
