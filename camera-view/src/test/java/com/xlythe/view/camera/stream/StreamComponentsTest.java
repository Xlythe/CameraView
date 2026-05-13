package com.xlythe.view.camera.stream;

import android.os.ParcelFileDescriptor;
import android.view.Surface;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

@RunWith(RobolectricTestRunner.class)
@Config(minSdk = 21)
public class StreamComponentsTest {

    @Test
    public void testAudioPlayerBasic() {
        InputStream in = new ByteArrayInputStream(new byte[0]);
        AudioPlayer player = new AudioPlayer(in);
        player.setStreamEndListener(() -> {});
        assertFalse(player.isPlaying());
    }

    @Test
    public void testAudioRecorderBasic() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AudioRecorder recorder = new AudioRecorder(out);
        assertFalse(recorder.isRecording());
    }

    @Test
    public void testVideoPlayerBasic() {
        Surface mockSurface = Mockito.mock(Surface.class);
        InputStream in = new ByteArrayInputStream(new byte[0]);
        VideoPlayer player = new VideoPlayer(mockSurface, in);
        player.setStreamEndListener(() -> {});
        player.setOnMetadataAvailableListener((w, h, o, f) -> {});
        assertFalse(player.isPlaying());
    }

    @Test
    public void testVideoRecorderBasic() {
        VideoRecorder.Canvas mockCanvas = Mockito.mock(VideoRecorder.Canvas.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        VideoRecorder recorder = new VideoRecorder(mockCanvas, out);

        recorder.setBitRate(5000000);
        recorder.setFrameRate(30);
        recorder.setIFrameInterval(5);

        assertEquals(5000000, recorder.getBitRate());
        assertEquals(30, recorder.getFrameRate());
        assertEquals(5, recorder.getIFrameInterval());

        assertFalse(recorder.isRecording());
    }
}
