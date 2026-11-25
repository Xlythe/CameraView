package com.xlythe.view.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.util.Size;

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
 * What survives the app going to the background.
 *
 * <p>An app built on this library may be a call: when the user leaves it, the video can reasonably
 * stop - a camera cannot be held while another app wants it, and keeping the picture alive needs
 * picture-in-picture, which is the app's business and not this library's - but the conversation
 * should not. The audio has to keep going.</p>
 *
 * <p>It does, and these exist so it keeps doing so. The library does not track the streams it
 * hands out, and an audio stream is an AudioRecord that owes the camera nothing, so closing the
 * camera leaves it running. That is the behaviour an app needs, but until now it was a consequence
 * of the library not doing anything rather than a promise, which is the kind of thing a later
 * tidy-up removes without knowing it was load-bearing.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37},
        shadows = {ShadowAvailableTextureView.class, ShadowInjectableImageReader.class})
public class BackgroundStreamingTest {
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

    /** Closing the camera does not close a stream the caller is holding. */
    @Test
    public void aStreamOutlivesTheCameraThatStartedIt() {
        cameraView.open();
        idle();
        VideoStream stream = cameraView.stream(audioAndVideo());
        idle();
        assertTrue(stream.hasAudio());
        assertNotNull(stream.getAudioInputStream());

        cameraView.close();
        idle();

        // The camera is gone, so no more frames arrive - but the stream is the caller's and only
        // the caller ends it. An app that went to the background still has its conversation.
        assertTrue("the audio stream has to still be there", stream.hasAudio());
        assertNotNull(stream.getAudioInputStream());

        stream.close();
        idle();
    }

    /** An audio-only stream is unaffected by the camera at all, which is the point of it. */
    @Test
    public void anAudioOnlyStreamNeverNeededTheCamera() {
        cameraView.open();
        idle();
        VideoStream stream = cameraView.stream(new VideoStream.Params.Builder()
                .setAudioEnabled(true)
                .setVideoEnabled(false)
                .build());
        idle();

        cameraView.close();
        idle();

        assertTrue(stream.hasAudio());
        assertFalse("there was never a video half to lose", stream.hasVideo());
        assertNotNull(stream.getAudioInputStream());

        stream.close();
        idle();
    }

    /** Closing the stream is what ends it, and it can be done after the camera has gone. */
    @Test
    public void theCallerEndsTheStreamWheneverItLikes() {
        cameraView.open();
        idle();
        VideoStream stream = cameraView.stream(audioAndVideo());
        idle();

        cameraView.close();
        idle();
        stream.close();
        idle();

        // And again, because an app tearing down cannot always tell whether it already has.
        stream.close();
        idle();
        assertTrue("closing twice must be harmless", stream.hasAudio());
    }

    /** The camera can be opened again afterwards, so coming back to the foreground works. */
    @Test
    public void theCameraComesBackAfterAStreamOutlivedIt() {
        cameraView.open();
        idle();
        VideoStream stream = cameraView.stream(audioAndVideo());
        idle();

        cameraView.close();
        idle();
        assertFalse(cameraView.isOpen());

        cameraView.open();
        idle();
        assertTrue("the camera has to come back for the app returning to the foreground",
                cameraView.isOpen());

        stream.close();
        idle();
        cameraView.close();
        idle();
    }

    /** A stream left open when the camera closes must not hold the camera's readers. */
    @Test
    public void anOutlivingStreamHoldsNoCameraResources() {
        int before = ShadowInjectableImageReader.openReaderCount();
        cameraView.open();
        idle();
        VideoStream stream = cameraView.stream(audioAndVideo());
        idle();

        cameraView.close();
        idle();

        assertTrue("a stream outliving the camera must not keep its readers: " + before
                        + " -> " + ShadowInjectableImageReader.openReaderCount(),
                ShadowInjectableImageReader.openReaderCount() <= before);

        stream.close();
        idle();
    }

    private static VideoStream.Params audioAndVideo() {
        return new VideoStream.Params.Builder()
                .setAudioEnabled(true)
                .setVideoEnabled(true)
                .setMaxSize(new Size(640, 480))
                .setIsLossy(true)
                .build();
    }

    private static void idle() {
        ShadowLooper.idleMainLooper();
    }
}
