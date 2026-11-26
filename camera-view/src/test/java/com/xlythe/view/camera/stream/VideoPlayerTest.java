package com.xlythe.view.camera.stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;

import com.google.common.primitives.Ints;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowMediaCodec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The decoding half of a stream.
 *
 * <p>What arrives over a socket is the library's own framing - a header frame describing the video,
 * then length-prefixed H.264 frames - and this is the code that turns it back into something a
 * Surface can show. Robolectric's MediaCodec shadow stands in for the decoder, so what is under
 * test is the framing and the lifecycle: that the header is read before anything else, that a
 * stream which does not start with one is refused rather than fed to a decoder configured from
 * nothing, and that the end of a stream is reported once.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class VideoPlayerTest {
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;

    private static final long TIMEOUT_SECONDS = 5;

    private SurfaceTexture surfaceTexture;
    private Surface surface;

    @Before
    public void setUp() {
        // A passthrough decoder: the bytes in are the bytes out, which is all the player needs of
        // it. What the player does with them is the surface's business, not this test's.
        ShadowMediaCodec.addDecoder(MIME_TYPE, new ShadowMediaCodec.CodecConfig(
                4096 /* inputBufferSize */, 4096 /* outputBufferSize */,
                (in, out) -> out.put(in)));
        surfaceTexture = new SurfaceTexture(0);
        surface = new Surface(surfaceTexture);
    }

    @After
    public void tearDown() {
        ShadowMediaCodec.clearCodecs();
        surfaceTexture.release();
    }

    @Test
    public void aStreamIsPlayedAndItsMetadataReported() throws Exception {
        AtomicReference<VideoFrame> metadata = new AtomicReference<>();
        CountDownLatch described = new CountDownLatch(1);
        VideoPlayer player = new VideoPlayer(surface, stream(
                header(640, 480, 90),
                data(new byte[] {1, 2, 3, 4})));
        player.setOnMetadataAvailableListener((width, height, orientation, flipped) -> {
            metadata.set(new VideoFrame.Builder(VideoFrame.Type.HEADER)
                    .width(width).height(height).orientation(orientation).flipped(flipped)
                    .build());
            described.countDown();
        });

        player.start();

        assertTrue("the header has to be reported before any frame is shown",
                described.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(640, metadata.get().getWidth());
        assertEquals(480, metadata.get().getHeight());
        // Zero, not the 90 in the header: from API 23 the decoder is told the rotation and
        // applies it itself, so what the listener is given is the rotation still left to do. A
        // view that turned the frame by the header's value would turn it twice.
        assertEquals("the decoder already applied the rotation",
                0, metadata.get().getOrientation());
        player.stop();
    }

    /** The end of a stream is reported once, which is how a view knows to clear itself. */
    @Test
    public void theEndOfAStreamIsReported() throws Exception {
        CountDownLatch ended = new CountDownLatch(1);
        VideoPlayer player = new VideoPlayer(surface, stream(header(320, 240, 0)));
        player.setStreamEndListener(ended::countDown);

        player.start();

        assertTrue("a stream that runs out has to say so",
                ended.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertFalse(player.isPlaying());
    }

    /**
     * A stream that does not begin with a header is refused.
     *
     * <p>The decoder is configured from the header's dimensions, so feeding it a data frame first
     * would mean configuring it from nothing and failing somewhere less obvious.</p>
     */
    @Test
    public void aStreamWithoutAHeaderIsRefused() throws Exception {
        CountDownLatch ended = new CountDownLatch(1);
        VideoPlayer player = new VideoPlayer(surface, stream(data(new byte[] {9, 9, 9})));
        player.setStreamEndListener(ended::countDown);

        player.start();

        assertTrue(ended.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertFalse(player.isPlaying());
    }

    /** A truncated stream ends rather than blocking forever waiting for the rest of a frame. */
    @Test
    public void aTruncatedStreamEnds() throws Exception {
        byte[] whole = bytesOf(header(320, 240, 0), data(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}));
        byte[] truncated = new byte[whole.length - 4];
        System.arraycopy(whole, 0, truncated, 0, truncated.length);
        CountDownLatch ended = new CountDownLatch(1);
        VideoPlayer player = new VideoPlayer(surface, new ByteArrayInputStream(truncated));
        player.setStreamEndListener(ended::countDown);

        player.start();

        assertTrue(ended.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    /** A frame claiming a negative length is refused rather than used to size an array. */
    @Test
    public void aFrameWithANonsenseLengthIsRefused() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(Ints.toByteArray(-1));
        CountDownLatch ended = new CountDownLatch(1);
        VideoPlayer player = new VideoPlayer(surface, new ByteArrayInputStream(out.toByteArray()));
        player.setStreamEndListener(ended::countDown);

        player.start();

        assertTrue(ended.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    public void aPlayerCannotBeStartedTwiceOrRestarted() throws Exception {
        CountDownLatch ended = new CountDownLatch(1);
        VideoPlayer player = new VideoPlayer(surface, stream(header(320, 240, 0)));
        player.setStreamEndListener(ended::countDown);

        player.start();
        player.start();
        assertTrue(ended.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        player.stop();
        player.start();

        assertFalse("a stopped player stays stopped", player.isPlaying());
    }

    /** Stopping before starting is safe, and stopping twice is the same as stopping once. */
    @Test
    public void stoppingIsIdempotent() {
        VideoPlayer player = new VideoPlayer(surface, stream(header(320, 240, 0)));

        player.stop();
        player.stop();

        assertFalse(player.isPlaying());
    }

    private static VideoFrame header(int width, int height, int orientation) {
        return new VideoFrame.Builder(VideoFrame.Type.HEADER)
                .width(width)
                .height(height)
                .orientation(orientation)
                .flipped(false)
                .bitRate(1_000_000)
                .frameRate(30)
                .iframeInterval(1)
                .build();
    }

    private static VideoFrame data(byte[] bytes) {
        return new VideoFrame.Builder(VideoFrame.Type.DATA)
                .data(bytes)
                .presentationTimeUs(0)
                .flags(MediaCodec.BUFFER_FLAG_KEY_FRAME)
                .build();
    }

    /** The wire format: each frame length-prefixed, exactly as the recorder writes it. */
    private static InputStream stream(VideoFrame... frames) {
        return new ByteArrayInputStream(bytesOf(frames));
    }

    private static byte[] bytesOf(VideoFrame... frames) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            for (VideoFrame frame : frames) {
                byte[] bytes = frame.asBytes();
                out.write(Ints.toByteArray(bytes.length));
                out.write(bytes);
            }
        } catch (IOException error) {
            throw new AssertionError(error);
        }
        return out.toByteArray();
    }

}
