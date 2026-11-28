package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Matrix;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.Display;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.xlythe.view.camera.shadow.ShadowAvailableTextureView;
import com.xlythe.view.camera.shadow.ShadowSizedSurfaceView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowMediaMetadataRetriever;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.shadows.util.DataSource;

import java.time.Duration;

/**
 * An HDR video in VideoView: shown on the HDR surface on a display that can show it, so that its
 * frames reach the display as HDR, and on the TextureView everywhere else.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37, shadows = {ShadowAvailableTextureView.class, ShadowSizedSurfaceView.class})
public class VideoViewHdrTest {
    /** An HLG video. */
    private static final Uri HLG = Uri.parse("content://media/external/video/media/1");
    /** An SDR video. */
    private static final Uri SDR = Uri.parse("content://media/external/video/media/2");

    private Activity activity;
    private VideoView videoView;
    private DataSource hlgSource;
    private DataSource sdrSource;

    @Before
    public void setUp() {
        ShadowSizedSurfaceView.reset();
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        hlgSource = DataSource.toDataSource(activity, HLG);
        sdrSource = DataSource.toDataSource(activity, SDR);
        ShadowMediaPlayer.setMediaInfoProvider(
                dataSource -> new ShadowMediaPlayer.MediaInfo(5_000 /* duration */, 0 /* delay */));
        describe(hlgSource, "7" /* MediaFormat.COLOR_TRANSFER_HLG */);
        describe(sdrSource, "3" /* MediaFormat.COLOR_TRANSFER_SDR_VIDEO */);

        videoView = new VideoView(activity);
        activity.setContentView(videoView, new ViewGroup.LayoutParams(1080, 1920));
        videoView.layout(0, 0, 1080, 1920);
        idle();
    }

    @After
    public void tearDown() {
        DataSource.reset();
        ShadowMediaMetadataRetriever.reset();
    }

    @Test
    public void anHdrVideoOnAnHdrDisplayPlaysOnTheHdrSurface() throws InterruptedException {
        makeTheDisplayHdr();
        videoView.setUri(HLG);
        awaitPlayerFor(hlgSource);

        assertTrue(videoView.isShowingHdr());
        assertEquals(ActivityInfo.COLOR_MODE_HDR, activity.getWindow().getColorMode());
        assertEquals(View.INVISIBLE, textureView().getVisibility());
        assertEquals(View.VISIBLE, videoView.getHdrSurface().getSurfaceView().getVisibility());
        assertTrue(videoView.play());
        assertTrue(videoView.isPlaying());
    }

    /** The SurfaceView takes the same fit as the TextureView, as a scale and a translation. */
    @Test
    public void theHdrSurfaceIsFittedLikeTheTextureView() throws InterruptedException {
        makeTheDisplayHdr();
        videoView.setFitsInside(true);
        videoView.setUri(HLG);
        awaitPlayerFor(hlgSource);

        Matrix expected = textureView().getTransform(new Matrix());
        float[] values = new float[9];
        expected.getValues(values);
        SurfaceView surfaceView = videoView.getHdrSurface().getSurfaceView();
        assertEquals(values[Matrix.MSCALE_X], surfaceView.getScaleX(), 1e-4f);
        assertEquals(values[Matrix.MSCALE_Y], surfaceView.getScaleY(), 1e-4f);
        assertEquals(values[Matrix.MTRANS_X], surfaceView.getTranslationX(), 1e-2f);
        assertEquals(values[Matrix.MTRANS_Y], surfaceView.getTranslationY(), 1e-2f);
        // A landscape video letterboxed in a portrait view.
        assertTrue(surfaceView.getScaleY() < 1f);
    }

    @Test
    public void anSdrDisplayPlaysItOnTheTextureView() throws InterruptedException {
        videoView.setUri(HLG);
        awaitPlayerFor(hlgSource);

        assertFalse(videoView.isShowingHdr());
        assertEquals(View.VISIBLE, textureView().getVisibility());
        assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, activity.getWindow().getColorMode());
    }

    @Test
    public void anSdrVideoPlaysOnTheTextureView() throws InterruptedException {
        makeTheDisplayHdr();
        videoView.setUri(SDR);
        awaitPlayerFor(sdrSource);

        assertFalse(videoView.isShowingHdr());
        assertEquals(View.VISIBLE, textureView().getVisibility());
    }

    /** A SurfaceView cannot be mirrored, so a mirrored video stays on the TextureView. */
    @Test
    public void aMirroredVideoPlaysOnTheTextureView() throws InterruptedException {
        makeTheDisplayHdr();
        videoView.setShouldMirror(true);
        videoView.setUri(HLG);
        awaitPlayerFor(hlgSource);

        assertFalse(videoView.isShowingHdr());
    }

    @Test
    public void turningHdrOffAndOnMovesTheVideo() throws InterruptedException {
        makeTheDisplayHdr();
        videoView.setUri(HLG);
        MediaPlayer first = awaitPlayerFor(hlgSource);
        assertTrue(videoView.isShowingHdr());

        videoView.setHdrEnabled(false);
        assertFalse(videoView.isHdrEnabled());
        MediaPlayer second = awaitPlayerFor(hlgSource);
        settle();
        assertFalse(videoView.isShowingHdr());
        assertNotSame("prepared again, onto the TextureView", first, second);
        assertEquals(View.VISIBLE, textureView().getVisibility());
        assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, activity.getWindow().getColorMode());

        videoView.setHdrEnabled(true);
        awaitPlayerFor(hlgSource);
        idle();
        assertTrue(videoView.isShowingHdr());
    }

    /**
     * A hidden window takes the SurfaceView's surface with it; the video is prepared again on the
     * new one, from where it was.
     */
    @Test
    public void aLostSurfaceIsPreparedAgainOnTheNewOne() throws InterruptedException {
        makeTheDisplayHdr();
        videoView.setUri(HLG);
        MediaPlayer first = awaitPlayerFor(hlgSource);
        assertTrue(videoView.play());

        ShadowSizedSurfaceView surface = Shadow.extract(videoView.getHdrSurface().getSurfaceView());
        surface.destroySurface();
        assertFalse(videoView.isPlaying());

        surface.recreateSurface();
        MediaPlayer second = awaitPlayerFor(hlgSource);
        assertNotSame(first, second);
        assertTrue(videoView.isShowingHdr());
    }

    /** The window is not left in HDR on behalf of a view that has gone. */
    @Test
    public void leavingTheWindowPutsItBack() throws InterruptedException {
        makeTheDisplayHdr();
        videoView.setUri(HLG);
        awaitPlayerFor(hlgSource);
        assertEquals(ActivityInfo.COLOR_MODE_HDR, activity.getWindow().getColorMode());

        activity.setContentView(new FrameLayout(activity));
        idle();
        assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, activity.getWindow().getColorMode());
        assertFalse(videoView.isShowingHdr());
    }

    /** The next video starts on the TextureView unless it too is HDR. */
    @Test
    public void anSdrVideoAfterAnHdrOneGoesBackToTheTextureView() throws InterruptedException {
        makeTheDisplayHdr();
        videoView.setUri(HLG);
        awaitPlayerFor(hlgSource);

        videoView.setUri(SDR);
        awaitPlayerFor(sdrSource);
        settle();
        assertFalse(videoView.isShowingHdr());
        assertEquals(View.VISIBLE, textureView().getVisibility());
        assertEquals(View.GONE, videoView.getHdrSurface().getSurfaceView().getVisibility());
        assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, activity.getWindow().getColorMode());
    }

    private static void describe(DataSource source, String transfer) {
        ShadowMediaMetadataRetriever.addMetadata(source,
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, "1920");
        ShadowMediaMetadataRetriever.addMetadata(source,
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, "1080");
        ShadowMediaMetadataRetriever.addMetadata(source,
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION, "0");
        ShadowMediaMetadataRetriever.addMetadata(source,
                MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER, transfer);
    }

    private void makeTheDisplayHdr() {
        Display display = videoView.getDisplay();
        Shadows.shadowOf(display).setDisplayHdrCapabilities(display.getDisplayId(),
                1000f, 500f, 0.0005f, Display.HdrCapabilities.HDR_TYPE_HLG,
                Display.HdrCapabilities.HDR_TYPE_HDR10);
    }

    private TextureView textureView() {
        for (int i = 0; i < videoView.getChildCount(); i++) {
            if (videoView.getChildAt(i) instanceof TextureView) {
                return (TextureView) videoView.getChildAt(i);
            }
        }
        throw new AssertionError("the view never created a TextureView");
    }

    /** Runs the main looper until a player holds the source, since metadata is read off it. */
    private MediaPlayer awaitPlayerFor(DataSource expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            idle();
            MediaPlayer player = videoView.getMediaPlayer();
            if (expected.equals(Shadows.shadowOf(player).getDataSource())) {
                idle();
                assertNotNull(player);
                return player;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("no player was given " + expected);
    }

    private static void idle() {
        ShadowLooper.shadowMainLooper().idle();
    }

    private static void settle() {
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(1));
    }
}
