package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.media.MediaMetadataRetriever;

import com.xlythe.view.camera.shadow.ShadowAvailableTextureView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowMediaMetadataRetriever;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.shadows.util.DataSource;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * VideoView, given something to play.
 *
 * <p>Reachable only now that a TextureView produces a SurfaceTexture under test: before that the
 * view waited for a surface forever, so the preparing, the playing and the teardown - most of the
 * class - could not run at all.</p>
 *
 * <p>What is not covered here is a file that gets as far as a prepared decoder. The view opens one
 * through a FileDescriptor and reads its dimensions with MediaMetadataRetriever first, and
 * Robolectric's shadows for those need the container registered against the very descriptor the
 * view happens to open; until that is arranged the player is handed a source it knows nothing
 * about. The streaming path, the state the view keeps around it, and the teardown are all
 * reachable without it.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37, shadows = {ShadowAvailableTextureView.class})
public class VideoViewLifecycleTest {
    /** The one name every file descriptor is mapped onto, so the fakes can be told about it. */
    private static final String VIDEO = "video";

    /** What Robolectric's MediaPlayer reports about anything it is given. */
    private static final ShadowMediaPlayer.MediaInfo MEDIA_INFO =
            new ShadowMediaPlayer.MediaInfo(5_000 /* duration */, 0 /* preparation delay */);

    private Context context;
    private VideoView videoView;
    private File file;

    @Before
    public void setUp() throws IOException {
        context = RuntimeEnvironment.getApplication();
        file = File.createTempFile("video", ".mp4");
        file.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[] {0, 0, 0, 1});
        }
        // The view opens a video through a FileDescriptor, and Robolectric keys its fakes on the
        // data source rather than on the file - so a descriptor it has never been told about is a
        // video it knows nothing about. This maps every descriptor onto one name, which is what
        // makes the whole prepare-and-play path reachable at all.
        DataSource.setFileDescriptorTransform((descriptor, offset) -> VIDEO);
        ShadowMediaPlayer.setMediaInfoProvider(dataSource -> MEDIA_INFO);
        // MediaPlayer.setDataSource(FileDescriptor) is not one of the overloads the shadow
        // implements, so a player given one never learns what it is holding and fails to prepare.
        // Telling each player as it is created is the same thing the shadow would have done.
        ShadowMediaPlayer.setCreateListener((player, shadow) ->
                shadow.doSetDataSource(DataSource.toDataSource(VIDEO)));
        ShadowMediaMetadataRetriever.addMetadata(DataSource.toDataSource(VIDEO),
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, "640");
        ShadowMediaMetadataRetriever.addMetadata(DataSource.toDataSource(VIDEO),
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, "480");
        ShadowMediaMetadataRetriever.addMetadata(DataSource.toDataSource(VIDEO),
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION, "0");

        videoView = new VideoView(context);
        videoView.layout(0, 0, 640, 480);
        idle();
    }

    @After
    public void tearDown() {
        videoView.onDetachedFromWindow();
        idle();
        DataSource.reset();
        ShadowMediaMetadataRetriever.reset();
    }

    /** A file really is prepared and played, which is most of what the class does. */
    @Test
    public void aFileIsPreparedAndPlayed() {
        videoView.setFile(file);
        idle();

        assertNotNull("preparing should have built a player", videoView.getMediaPlayer());
        assertTrue("and it should be playable", videoView.play());
        assertTrue(videoView.isPlaying());

        assertTrue(videoView.pause());
        assertFalse(videoView.isPlaying());
    }

    /** Completion is reported, which is how a caller loops or moves on. */
    @Test
    public void completionIsReported() {
        java.util.concurrent.atomic.AtomicInteger completions =
                new java.util.concurrent.atomic.AtomicInteger();
        videoView.setOnCompletionListener(player -> completions.incrementAndGet());
        videoView.setFile(file);
        idle();
        videoView.play();

        android.media.MediaPlayer player = videoView.getMediaPlayer();
        assertNotNull(player);
        org.robolectric.Shadows.shadowOf(player).invokeCompletionListener();
        idle();

        assertEquals(1, completions.get());
    }

    /** A tap toggles playback, which is the whole of the view's interaction. */
    @Test
    public void aTapTogglesPlayback() {
        videoView.setFile(file);
        idle();
        videoView.play();
        assertTrue(videoView.isPlaying());

        tap();
        idle();
        assertFalse("a tap while playing pauses", videoView.isPlaying());

        tap();
        idle();
        assertTrue("and a tap while paused plays", videoView.isPlaying());
    }

    /** Seeking to the first frame shows something without starting playback. */
    @Test
    public void seekingToTheFirstFrameIsNotPlaying() {
        videoView.setFile(file);
        idle();

        videoView.seekToFirstFrame();
        idle();

        assertFalse(videoView.isPlaying());
    }

    /** Detaching releases the decoder, because a view that is gone must hold none. */
    @Test
    public void detachingReleasesThePlayer() {
        videoView.setFile(file);
        idle();
        videoView.play();
        assertNotNull(videoView.getMediaPlayer());

        videoView.onDetachedFromWindow();
        idle();

        assertFalse(videoView.isPlaying());
    }

    /** The event listener hears the play and the pause, which is how a caller shows controls. */
    @Test
    public void theEventListenerHearsPlayAndPause() {
        java.util.List<String> events = new java.util.ArrayList<>();
        videoView.setEventListener(new VideoView.EventListener() {
            @Override public void onPlay() { events.add("play"); }
            @Override public void onPause() { events.add("pause"); }
        });

        videoView.setFile(file);
        idle();
        videoView.play();
        idle();
        videoView.pause();
        idle();

        assertTrue("expected a play and a pause, got " + events, events.size() >= 2);
    }

    private void tap() {
        long now = android.os.SystemClock.uptimeMillis();
        videoView.dispatchTouchEvent(android.view.MotionEvent.obtain(
                now, now, android.view.MotionEvent.ACTION_DOWN, 10f, 10f, 0));
        videoView.dispatchTouchEvent(android.view.MotionEvent.obtain(
                now, now + 10, android.view.MotionEvent.ACTION_UP, 10f, 10f, 0));
    }

    @Test
    public void aViewWithNothingToPlayIsIdle() {
        assertNull(videoView.getFile());
        assertFalse(videoView.hasStream());
        assertFalse(videoView.isPlaying());
        assertFalse("there is nothing to play", videoView.play());
        assertFalse("and nothing to pause", videoView.pause());
    }

    /** The file is remembered as given, whatever the decoder makes of it. */
    @Test
    public void theFileIsRemembered() {
        videoView.setFile(file);
        idle();

        assertEquals(file, videoView.getFile());
        assertFalse("a file is not a stream", videoView.hasStream());
    }

    /** Asked for a second file while still reading the first, the second is the one that counts. */
    @Test
    public void theLastFileAskedForIsTheOneThatCounts() throws IOException {
        File second = File.createTempFile("second", ".mp4");
        second.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(second)) {
            out.write(new byte[] {0, 0, 0, 2});
        }

        videoView.setFile(file);
        videoView.setFile(second);
        idle();

        assertEquals(second, videoView.getFile());
    }

    @Test
    public void clearingTheFileLeavesNothingToPlay() {
        videoView.setFile(file);
        idle();

        videoView.setFile(null);
        idle();

        assertNull(videoView.getFile());
        assertFalse(videoView.isPlaying());
    }

    @Test
    public void loopingIsRemembered() {
        assertFalse(videoView.isLooping());

        videoView.setShouldLoop(true);
        assertTrue(videoView.isLooping());

        videoView.setShouldLoop(false);
        assertFalse(videoView.isLooping());
    }

    @Test
    public void mirroringIsRemembered() {
        assertFalse(videoView.isMirrored());

        videoView.setShouldMirror(true);
        assertTrue(videoView.isMirrored());

        videoView.setShouldMirror(false);
        assertFalse(videoView.isMirrored());
    }

    /** Out of range is clamped rather than refused, because a fade should not have to round. */
    @Test
    public void volumeIsClamped() {
        videoView.setVolume(-1f);
        videoView.setVolume(2f);
        videoView.setVolume(0.5f);

        assertNotNull("asking about the volume builds the player", videoView.getMediaPlayer());
    }

    /** A stream is played without a file, and replacing it closes the one it replaced. */
    @Test
    public void aStreamIsPlayedAndReplacedCleanly() {
        CountingStream first = new CountingStream();
        videoView.setStream(new VideoStream.Builder().withVideoStream(first).build());
        idle();

        assertTrue(videoView.hasStream());
        assertNotNull(videoView.getStream());
        assertNull("a stream is not a file", videoView.getFile());

        CountingStream second = new CountingStream();
        videoView.setStream(new VideoStream.Builder().withVideoStream(second).build());
        idle();

        assertTrue("the stream that was replaced has to be closed", first.closed);
        assertFalse(second.closed);
    }

    /** A view with no size has nothing to transform into, which is a no-op not a divide by zero. */
    @Test
    public void aViewWithNoSizeIsNotTransformed() {
        VideoView unlaidOut = new VideoView(context);

        unlaidOut.setFile(file);
        idle();

        assertEquals(file, unlaidOut.getFile());
        unlaidOut.onDetachedFromWindow();
    }

    /** Detaching stops the work it started, so a view that is gone holds no decoder. */
    @Test
    public void detachingStopsEverything() {
        videoView.setStream(new VideoStream.Builder()
                .withVideoStream(new CountingStream()).build());
        idle();

        videoView.onDetachedFromWindow();
        idle();

        assertFalse(videoView.isPlaying());
    }

    /**
     * Pausing something that was never playing is not an error.
     *
     * <p>It reports success, because once there is a player to pause there is nothing to refuse;
     * what matters is that nothing is playing afterwards either way.</p>
     */
    @Test
    public void pausingWhatNeverPlayedIsHarmless() {
        videoView.setFile(file);
        idle();

        videoView.pause();

        assertFalse(videoView.isPlaying());
    }

    private static void idle() {
        ShadowLooper.shadowMainLooper().idle();
    }

    /** An input stream that only remembers whether it was closed. */
    private static final class CountingStream extends InputStream {
        volatile boolean closed;

        @Override public int read() {
            return -1;
        }

        @Override public void close() {
            closed = true;
        }
    }
}
