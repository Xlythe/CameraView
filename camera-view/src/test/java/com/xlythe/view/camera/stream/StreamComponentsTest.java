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
import java.io.OutputStream;
import java.io.PipedInputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
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
    public void stopBeforeStartIsSafeAndIdempotent() throws Exception {
        InputStream audioInput = Mockito.mock(InputStream.class);
        AudioPlayer audioPlayer = new AudioPlayer(audioInput);
        audioPlayer.stop();
        audioPlayer.stop();
        Mockito.verify(audioInput, Mockito.times(1)).close();

        OutputStream audioOutput = Mockito.mock(OutputStream.class);
        AudioRecorder audioRecorder = new AudioRecorder(audioOutput);
        audioRecorder.stop();
        audioRecorder.stop();
        Mockito.verify(audioOutput, Mockito.times(1)).close();

        Surface surface = Mockito.mock(Surface.class);
        InputStream videoInput = Mockito.mock(InputStream.class);
        VideoPlayer videoPlayer = new VideoPlayer(surface, videoInput);
        videoPlayer.stop();
        videoPlayer.stop();
        Mockito.verify(videoInput, Mockito.times(1)).close();
        Mockito.verify(surface, Mockito.times(1)).release();

        OutputStream videoOutput = Mockito.mock(OutputStream.class);
        VideoRecorder videoRecorder = new VideoRecorder(Mockito.mock(VideoRecorder.Canvas.class), videoOutput);
        videoRecorder.stop();
        videoRecorder.stop();
        Mockito.verify(videoOutput, Mockito.times(1)).close();
    }

    /**
     * The recorder sets a lossy sink's threshold from the rate it settled on, before the header
     * goes out, so not even the first chunk is held to the stream's starting guess.
     */
    @Test
    public void audioRecorderSetsTheDropThresholdFromItsRate() throws Exception {
        PipedInputStream pipe = new PipedInputStream(128 * 1024);
        LossyPipedOutputStream lossy = new LossyPipedOutputStream(pipe, 16 * 1024);
        AudioRecorder recorder = new AudioRecorder(lossy, 48000, 1);
        recorder.start();
        try {
            AudioStreamHeader header = AudioStreamHeader.read(pipe).header;
            assertNotNull(header);
            assertEquals(AudioRecorder.backlogBytesFor(header.getSampleRate(),
                    header.getChannelCount(), AudioRecorder.TARGET_BACKLOG_MILLIS),
                    lossy.getMaxBufferedBytes());
        } finally {
            recorder.stop();
        }
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
