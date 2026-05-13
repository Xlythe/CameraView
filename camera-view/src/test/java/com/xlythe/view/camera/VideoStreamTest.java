package com.xlythe.view.camera;

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
@Config(minSdk = 21)
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
}
