package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Matrix;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.TextureView;
import android.view.ViewGroup;

import com.xlythe.view.camera.shadow.ShadowAvailableTextureView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowMediaMetadataRetriever;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.shadows.util.DataSource;

import java.io.File;

/**
 * VideoView playing a uri, such as the one a recording filed by startRecording() is reported by.
 *
 * <p>The view is attached to a window, because its TextureView only reports a surface once it is,
 * and nothing is prepared without one.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, shadows = {ShadowAvailableTextureView.class})
public class VideoViewUriTest {
    private static final Uri URI = Uri.parse("content://media/external/video/media/1");

    private Activity activity;
    private VideoView videoView;
    private DataSource uriSource;

    @Before
    public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        uriSource = DataSource.toDataSource(activity, URI);
        ShadowMediaPlayer.setMediaInfoProvider(
                dataSource -> new ShadowMediaPlayer.MediaInfo(5_000 /* duration */, 0 /* delay */));
        // Portrait, so fitting it into the landscape view below takes a transform.
        ShadowMediaMetadataRetriever.addMetadata(uriSource,
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, "480");
        ShadowMediaMetadataRetriever.addMetadata(uriSource,
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, "640");
        ShadowMediaMetadataRetriever.addMetadata(uriSource,
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION, "0");

        videoView = new VideoView(activity);
        activity.setContentView(videoView, new ViewGroup.LayoutParams(640, 480));
        videoView.layout(0, 0, 640, 480);
        idle();
    }

    @After
    public void tearDown() {
        DataSource.reset();
        ShadowMediaMetadataRetriever.reset();
    }

    @Test
    public void aUriIsPreparedAndPlayed() throws InterruptedException {
        videoView.setShouldLoop(true);

        videoView.setUri(URI);
        MediaPlayer player = awaitPlayerFor(uriSource);

        assertTrue(videoView.play());
        assertTrue(videoView.isPlaying());
        assertTrue("looping applies to a uri as to a file", player.isLooping());
        assertNull("a uri replaces any file", videoView.getFile());
    }

    /** A uri is fitted to the view by the same centre-crop a file is. */
    @Test
    public void aUriIsFittedToTheView() throws InterruptedException {
        videoView.setUri(URI);
        awaitPlayerFor(uriSource);

        assertFalse("a portrait video in a landscape view has to be transformed",
                transformOfTheTextureView().isIdentity());
    }

    /** Whichever was asked for last, uri or file, is the one the view holds. */
    @Test
    public void aFileAfterAUriReplacesIt() throws InterruptedException {
        videoView.setUri(URI);
        awaitPlayerFor(uriSource);

        File file = new File(activity.getCacheDir(), "after-uri.mp4");
        videoView.setFile(file);
        idle();

        assertEquals(file, videoView.getFile());
        assertNotEquals(uriSource, Shadows.shadowOf(videoView.getMediaPlayer()).getDataSource());
    }

    /** Runs the main looper until the player holds the source, since metadata is read off it. */
    private MediaPlayer awaitPlayerFor(DataSource expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!expected.equals(Shadows.shadowOf(videoView.getMediaPlayer()).getDataSource())
                && System.currentTimeMillis() < deadline) {
            idle();
            Thread.sleep(10);
        }
        MediaPlayer player = videoView.getMediaPlayer();
        assertEquals(expected, Shadows.shadowOf(player).getDataSource());
        return player;
    }

    private Matrix transformOfTheTextureView() {
        for (int i = 0; i < videoView.getChildCount(); i++) {
            if (videoView.getChildAt(i) instanceof TextureView) {
                return ((TextureView) videoView.getChildAt(i)).getTransform(new Matrix());
            }
        }
        throw new AssertionError("the view never created a TextureView");
    }

    private static void idle() {
        ShadowLooper.shadowMainLooper().idle();
    }
}
