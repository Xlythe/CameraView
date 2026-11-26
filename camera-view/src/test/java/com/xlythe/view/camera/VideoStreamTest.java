package com.xlythe.view.camera;

import android.util.Size;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37)
public class VideoStreamTest {

    @Test
    public void testParamsBuilder() {
        VideoStream.Params params = new VideoStream.Params.Builder()
                .setAudioEnabled(true)
                .setVideoEnabled(true)
                .setBitRate(1000000)
                .setFrameRate(30)
                .setIFrameInterval(5)
                .setIsLossy(false)
                .build();

        assertTrue(params.isAudioEnabled());
        assertTrue(params.isVideoEnabled());
        assertEquals(1000000, params.getBitRate());
        assertEquals(30, params.getFrameRate());
        assertEquals(5, params.getIFrameInterval());
        assertFalse(params.isLossy());
    }

    /** A caller sending the stream off-device has to be able to ask for less than the preview. */
    @Test
    public void paramsCarryAResolutionCap() {
        assertEquals(null, new VideoStream.Params.Builder().build().getMaxSize());

        VideoStream.Params capped = new VideoStream.Params.Builder()
                .setMaxSize(new Size(640, 480))
                .build();
        assertEquals(new Size(640, 480), capped.getMaxSize());
    }

    @Test
    public void paramsRejectAnEmptyResolutionCap() {
        VideoStream.Params.Builder builder = new VideoStream.Params.Builder();
        org.junit.Assert.assertThrows(IllegalArgumentException.class,
                () -> builder.setMaxSize(new Size(0, 480)).build());
        org.junit.Assert.assertThrows(IllegalArgumentException.class,
                () -> builder.setMaxSize(new Size(640, -1)).build());
    }

    @Test
    public void testVideoStreamWithStreams() {
        InputStream audioStream = new ByteArrayInputStream(new byte[]{1, 2, 3});
        InputStream videoStream = new ByteArrayInputStream(new byte[]{4, 5, 6});

        VideoStream stream = new VideoStream.Builder()
                .withAudioStream(audioStream)
                .withVideoStream(videoStream)
                .build();

        assertTrue(stream.hasAudio());
        assertTrue(stream.hasVideo());
        assertEquals(audioStream, stream.getAudioInputStream());
        assertEquals(videoStream, stream.getVideoInputStream());
        assertNotNull(stream.toString());

        stream.close();
    }

    @Test
    public void closeIsIdempotent() throws Exception {
        InputStream audioStream = Mockito.mock(InputStream.class);
        InputStream videoStream = Mockito.mock(InputStream.class);
        VideoStream stream = new VideoStream.Builder()
                .withAudioStream(audioStream)
                .withVideoStream(videoStream)
                .build();

        stream.close();
        stream.close();

        Mockito.verify(audioStream, Mockito.times(1)).close();
        Mockito.verify(videoStream, Mockito.times(1)).close();
    }

    @Test(expected = NullPointerException.class)
    public void nullInputStreamIsRejected() {
        new VideoStream.Builder().withAudioStream(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativeEncodingValuesAreRejected() {
        new VideoStream.Params.Builder().setFrameRate(-1).build();
    }
}
