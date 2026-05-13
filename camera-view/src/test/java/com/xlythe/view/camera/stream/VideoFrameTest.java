package com.xlythe.view.camera.stream;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(minSdk = 21)
public class VideoFrameTest {

    @Test
    public void testHeaderSerialization() {
        VideoFrame frame = new VideoFrame.Builder(VideoFrame.Type.HEADER)
                .width(1920)
                .height(1080)
                .orientation(90)
                .flipped(true)
                .bitRate(5000000)
                .frameRate(30)
                .iframeInterval(5)
                .build();

        byte[] serialized = frame.asBytes();
        VideoFrame deserialized = VideoFrame.fromBytes(serialized);

        assertEquals(VideoFrame.Type.HEADER, deserialized.getType());
        assertEquals(1920, deserialized.getWidth());
        assertEquals(1080, deserialized.getHeight());
        assertEquals(90, deserialized.getOrientation());
        assertTrue(deserialized.isFlipped());
        assertEquals(5000000, deserialized.getBitRate());
        assertEquals(30, deserialized.getFrameRate());
        assertEquals(5, deserialized.getIFrameInterval());
    }

    @Test
    public void testDataSerialization() {
        byte[] sampleData = new byte[]{10, 20, 30, 40, 50};
        VideoFrame frame = new VideoFrame.Builder(VideoFrame.Type.DATA)
                .data(sampleData)
                .presentationTimeUs(123456789L)
                .flags(1)
                .build();

        byte[] serialized = frame.asBytes();
        VideoFrame deserialized = VideoFrame.fromBytes(serialized);

        assertEquals(VideoFrame.Type.DATA, deserialized.getType());
        assertArrayEquals(sampleData, deserialized.getData());
        assertEquals(123456789L, deserialized.getPresentationTimeUs());
        assertEquals(1, deserialized.getFlags());
    }
}
